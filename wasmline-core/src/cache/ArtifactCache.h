/**
 * Provides thread-safe caching for native Wasmtime artifacts.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */

#pragma once

#include <condition_variable>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <unordered_set>

namespace wasmline::cache {
    template <typename Handle> class ArtifactCache final {
    public:
        using Loader = std::function<Handle*(const std::string&, const std::string&)>;
        using Deleter = void (*)(Handle*);

        explicit ArtifactCache(Deleter deleter) : deleter_(deleter) {}

        ~ArtifactCache() { clear(); }

        ArtifactCache(const ArtifactCache&) = delete;

        ArtifactCache& operator=(const ArtifactCache&) = delete;

        Handle* load(const std::string& key, const std::string& path, const Loader& loader) {
            std::unique_lock<std::mutex> lock(mutex_);
            while (true) {
                const auto cached = cache_.find(key);
                if (cached != cache_.end()) return cached->second;
                if (loading_.find(key) == loading_.end()) break;
                condition_.wait(lock);
            }
            loading_.insert(key);
            lock.unlock();

            Handle* loaded = nullptr;
            try {
                loaded = loader(key, path);
            } catch (...) {
                finishLoading(key);
                return nullptr;
            }

            std::unique_ptr<Handle, Deleter> owned(loaded, deleter_);
            lock.lock();
            if (loaded) {
                try {
                    cache_.emplace(key, loaded);
                    owned.release();
                } catch (...) {
                    loaded = nullptr;
                }
            }
            loading_.erase(key);
            condition_.notify_all();
            return loaded;
        }

        Handle* loadUnsafe(const std::string& key, const std::string& path, const Loader& loader) {
            const auto cached = cache_.find(key);
            if (cached != cache_.end()) return cached->second;

            Handle* loaded = nullptr;
            try {
                loaded = loader(key, path);
            } catch (...) {
                return nullptr;
            }
            if (!loaded) return nullptr;

            std::unique_ptr<Handle, Deleter> owned(loaded, deleter_);
            try {
                cache_.emplace(key, loaded);
                owned.release();
            } catch (...) {
                return nullptr;
            }
            return loaded;
        }

        Handle* get(const std::string& key) const {
            std::lock_guard<std::mutex> lock(mutex_);
            const auto cached = cache_.find(key);
            return cached == cache_.end() ? nullptr : cached->second;
        }

        void release(const std::string& key) {
            std::lock_guard<std::mutex> lock(mutex_);
            const auto cached = cache_.find(key);
            if (cached == cache_.end()) return;
            deleter_(cached->second);
            cache_.erase(cached);
        }

        void clear() {
            std::lock_guard<std::mutex> lock(mutex_);
            for (const auto& item : cache_) {
                deleter_(item.second);
            }
            cache_.clear();
        }

    private:
        void finishLoading(const std::string& key) {
            std::lock_guard<std::mutex> lock(mutex_);
            loading_.erase(key);
            condition_.notify_all();
        }

        Deleter deleter_;
        std::unordered_map<std::string, Handle*> cache_;
        std::unordered_set<std::string> loading_;
        mutable std::mutex mutex_;
        std::condition_variable condition_;
    };
} // namespace wasmline::cache
