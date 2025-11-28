#include "WasmModule.h"
#include "WasmConfig.h"
#include "JniUtils.h"
#include <mutex>
#include <map>

// 1. 智能指针管理全局 Engine
static WasmModule::EnginePtr g_engine = nullptr;
static std::mutex g_engine_mutex;

// 2. 智能指针管理模块缓存
static std::map<std::string, WasmModule::ModulePtr> g_module_cache;
static std::mutex g_module_cache_mutex;

// Engine 删除器
static void delete_engine_internal(wasm_engine_t* engine) {
    if (engine) {
        LOGI("Running wasm_engine_delete...");
        wasm_engine_delete(engine);
    }
}

// Module 删除器
static void delete_module_internal(wasmtime_module_t* module) {
    if (module) {
        // LOGI("Running wasmtime_module_delete..."); 
        wasmtime_module_delete(module);
    }
}

WasmModule::EnginePtr WasmModule::getGlobalEngine() {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    if (!g_engine) {
        wasm_engine_t* raw_engine = wasm_engine_new_with_config(WasmConfig::createAndroidConfig());
        if(raw_engine) {
            g_engine = WasmModule::EnginePtr(raw_engine, delete_engine_internal);
            LOGI("Global Engine Initialized");
        }
        else LOGE("Global Engine Init Failed");
    }
    return g_engine;
}

// 辅助：获取缓存
static WasmModule::ModulePtr getCached(const std::string& key) {
    std::lock_guard<std::mutex> lock(g_module_cache_mutex);
    auto it = g_module_cache.find(key);
    return (it != g_module_cache.end()) ? it->second : nullptr;
}

// 辅助：存入缓存
static void putCached(const std::string& key, WasmModule::ModulePtr m) {
    std::lock_guard<std::mutex> lock(g_module_cache_mutex);
    g_module_cache[key] = m;
}

WasmModule::ModulePtr WasmModule::loadFromPath(const std::string& path) {
    if (!JniUtils::fileExists(path)) return nullptr;

    auto cached = getCached(path);
    if (cached) return cached;

    auto enginePtr = getGlobalEngine();
    if (!enginePtr) return nullptr;

    auto data = JniUtils::readFile(path);
    if (data.empty()) return nullptr;

    wasmtime_module_t* raw_module = nullptr;
    // 使用 enginePtr.get() 获取裸指针
    wasmtime_error_t* err = wasmtime_module_deserialize(enginePtr.get(), data.data(), data.size(), &raw_module);
    if (err) {
        wasm_byte_vec_t msg; wasmtime_error_message(err, &msg);
        LOGE("AOT Error: %s", msg.data);
        wasm_byte_vec_delete(&msg); wasmtime_error_delete(err);
        return nullptr;
    }

    // 封装成 shared_ptr
    WasmModule::ModulePtr modulePtr(raw_module, delete_module_internal);
    putCached(path, modulePtr);
    return modulePtr;
}

WasmModule::ModulePtr WasmModule::loadFromSourcePath(const std::string& path) {
    if (!JniUtils::fileExists(path)) return nullptr;

    auto cached = getCached(path);
    if (cached) return cached;

    auto enginePtr = getGlobalEngine();
    if (!enginePtr) return nullptr;

    auto data = JniUtils::readFile(path);
    if (data.empty()) return nullptr;

    wasmtime_module_t* raw_module = nullptr;
    wasmtime_error_t* err = wasmtime_module_new(enginePtr.get(), data.data(), data.size(), &raw_module);
    if (err) {
        wasm_byte_vec_t msg; wasmtime_error_message(err, &msg);
        LOGE("JIT Error: %s", msg.data);
        wasm_byte_vec_delete(&msg); wasmtime_error_delete(err);
        return nullptr;
    }

    WasmModule::ModulePtr modulePtr(raw_module, delete_module_internal);
    putCached(path, modulePtr);
    return modulePtr;
}

WasmModule::ModulePtr WasmModule::loadFromSource(const std::vector<uint8_t>& source) {
    auto enginePtr = getGlobalEngine();
    if (!enginePtr || source.empty()) return nullptr;

    wasmtime_module_t* raw_module = nullptr;
    wasmtime_module_new(enginePtr.get(), source.data(), source.size(), &raw_module);
    
    if (raw_module) {
        return WasmModule::ModulePtr(raw_module, delete_module_internal);
    }
    return nullptr;
}

void WasmModule::removeCache(const std::string& path) {
    std::lock_guard<std::mutex> lock(g_module_cache_mutex);
    g_module_cache.erase(path); 
}

void WasmModule::freeAllResources() {
    // 1. 清空 Map (引用计数 -1，但如果有 Session 正在用，Module 不会被 delete)
    {
        std::lock_guard<std::mutex> lock(g_module_cache_mutex);
        g_module_cache.clear(); 
    }
    
    // 2. 释放全局 Engine 引用 (引用计数 -1，同上)
    {
        std::lock_guard<std::mutex> lock(g_engine_mutex);
        if (g_engine) {
            LOGI("Releasing global engine reference. (Current Refs: %ld)", g_engine.use_count());
            g_engine.reset();
        }
    }
    LOGI("All resources flagged for release");
}

bool WasmModule::saveCacheToPath(ModulePtr module, const std::string& path) {
    if (!module) return false;
    wasm_byte_vec_t serialized;
    // 使用 module.get() 获取裸指针进行序列化
    wasmtime_error_t* err = wasmtime_module_serialize(module.get(), &serialized);
    if (err) {
        wasmtime_error_delete(err); return false;
    }
    std::vector<uint8_t> data(serialized.data, serialized.data + serialized.size);
    wasm_byte_vec_delete(&serialized);
    return JniUtils::writeFile(path, data);
}