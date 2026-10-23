/**
 * Provides internal thread-safe caching for native Wasmtime artifacts.
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
    /**
     * Caches reference-counted native Wasmtime artifact handles.
     *
     * Date: 2026-08-25
     * Author: crowforkotlin
     */
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
                if (cached != cache_.end()) {
                    ++cached->second.references;
                    return cached->second.handle;
                }
                const auto artifact = artifacts_.find(path);
                if (artifact != artifacts_.end()) {
                    try {
                        return bind(key, artifact);
                    } catch (...) {
                        return nullptr;
                    }
                }
                if (loading_.find(path) == loading_.end()) break;
                condition_.wait(lock);
            }
            loading_.insert(path);
            lock.unlock();

            Handle* loaded = nullptr;
            try {
                loaded = loader(key, path);
            } catch (...) {
                finishLoading(path);
                return nullptr;
            }

            std::unique_ptr<Handle, Deleter> owned(loaded, deleter_);
            lock.lock();
            Handle* result = nullptr;
            if (loaded) {
                try {
                    const auto cached = cache_.find(key);
                    if (cached != cache_.end()) {
                        ++cached->second.references;
                        result = cached->second.handle;
                    } else {
                        const auto [artifact, inserted] = artifacts_.emplace(path, Artifact{loaded, 0});
                        try {
                            result = bind(key, artifact);
                        } catch (...) {
                            if (inserted) artifacts_.erase(artifact);
                            throw;
                        }
                        if (inserted) owned.release();
                    }
                } catch (...) {
                    result = nullptr;
                }
            }
            loading_.erase(path);
            condition_.notify_all();
            lock.unlock();
            return result;
        }

        Handle* loadUnsafe(const std::string& key, const std::string& path, const Loader& loader) {
            const auto cached = cache_.find(key);
            if (cached != cache_.end()) {
                ++cached->second.references;
                return cached->second.handle;
            }
            const auto artifact = artifacts_.find(path);
            if (artifact != artifacts_.end()) {
                try {
                    return bind(key, artifact);
                } catch (...) {
                    return nullptr;
                }
            }

            Handle* loaded = nullptr;
            try {
                loaded = loader(key, path);
            } catch (...) {
                return nullptr;
            }
            if (!loaded) return nullptr;

            std::unique_ptr<Handle, Deleter> owned(loaded, deleter_);
            try {
                const auto [created, inserted] = artifacts_.emplace(path, Artifact{loaded, 0});
                Handle* result;
                try {
                    result = bind(key, created);
                } catch (...) {
                    if (inserted) artifacts_.erase(created);
                    throw;
                }
                if (inserted) owned.release();
                return result;
            } catch (...) {
                return nullptr;
            }
        }

        Handle* get(const std::string& key) const {
            std::lock_guard<std::mutex> lock(mutex_);
            const auto cached = cache_.find(key);
            return cached == cache_.end() ? nullptr : cached->second.handle;
        }

        bool release(const std::string& key) {
            std::unique_ptr<Handle, Deleter> released(nullptr, deleter_);
            {
                std::lock_guard<std::mutex> lock(mutex_);
                const auto cached = cache_.find(key);
                if (cached == cache_.end()) return false;
                if (--cached->second.references != 0) return false;
                const auto artifact = artifacts_.find(*cached->second.path);
                cache_.erase(cached);
                if (--artifact->second.bindings == 0) {
                    released.reset(artifact->second.handle);
                    artifacts_.erase(artifact);
                }
            }
            return released != nullptr;
        }

        bool empty() const {
            std::lock_guard<std::mutex> lock(mutex_);
            return cache_.empty() && loading_.empty();
        }

        void clear() {
            ArtifactMap detached;
            {
                std::lock_guard<std::mutex> lock(mutex_);
                cache_.clear();
                artifacts_.swap(detached);
            }
            for (const auto& item : detached) {
                deleter_(item.second.handle);
            }
        }

    private:
        void finishLoading(const std::string& path) {
            std::lock_guard<std::mutex> lock(mutex_);
            loading_.erase(path);
            condition_.notify_all();
        }

        struct Artifact {
            Handle* handle;
            size_t bindings;
        };

        using ArtifactMap = std::unordered_map<std::string, Artifact>;

        struct Entry {
            Handle* handle;
            // Map keys remain stable across rehashes; the last binding releases this path.
            const std::string* path;
            size_t references;
        };

        Handle* bind(const std::string& key, typename ArtifactMap::iterator artifact) {
            const auto [binding, inserted] = cache_.emplace(key, Entry{artifact->second.handle, &artifact->first, 1});
            if (inserted) {
                ++artifact->second.bindings;
            } else {
                ++binding->second.references;
            }
            return binding->second.handle;
        }

        Deleter deleter_;
        ArtifactMap artifacts_;
        std::unordered_map<std::string, Entry> cache_;
        std::unordered_set<std::string> loading_;
        mutable std::mutex mutex_;
        std::condition_variable condition_;
    };
} // namespace wasmline::cache
