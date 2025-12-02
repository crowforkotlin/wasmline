#include "WasmManager.h"
#include "WasmLog.h"
#include "FileUtils.h"

namespace crow {

    // ... (getInstance, createConfig 等保持不变) ...
    WasmManager& WasmManager::getInstance() {
        static WasmManager instance;
        return instance;
    }
    
    WasmManager::~WasmManager() { releaseEngine(); }

    wasm_config_t* WasmManager::createConfig() {
        wasm_config_t* conf = wasm_config_new();
        // 1. 开启 Wasm GC (垃圾回收) 支持
        // Kotlin/Wasm 编译产物依赖 Wasm GC 标准，如果不开启，无法加载模块。
        wasmtime_config_wasm_gc_set(conf, true);

        // 2. 开启函数引用 (Function References)
        // 这是 Wasm GC 的前置依赖，允许将函数作为值传递（Typed Function References）。
        wasmtime_config_wasm_function_references_set(conf, true);

        // 3. 开启异常处理 (Exception Handling)
        // 允许 Wasm 内部抛出和捕获异常（try-catch），Kotlin 的异常机制依赖此项。
        wasmtime_config_wasm_exceptions_set(conf, true);

        // 4. 关闭 SIMD (单指令多数据流)
        // 如果 Wasm 模块没用到向量运算优化，关掉可以略微减小编译开销。
        // 这里的 relaxed_simd 是 SIMD 的扩展。 simd在android上必须关闭
        wasmtime_config_wasm_simd_set(conf, false);
        wasmtime_config_wasm_relaxed_simd_set(conf, false);

        // 5. 【关键】关闭基于信号的 Trap (崩溃) 捕获
        // 默认情况下，Wasmtime 利用 OS 的信号 (SIGSEGV/SIGBUS) 来检测内存越界，速度快但需要注册信号处理器。
        // 在 Android 上，JVM (ART) 也有自己的信号处理器，两者极易冲突导致 Crash (Fault address 错误)。
        // 设置为 false 后，Wasmtime 会在生成的机器码中插入显式的 if-check 来检查边界，更安全但稍慢一点点。
        wasmtime_config_signals_based_traps_set(conf, false);

        // 6. 【关键】设置内存保护区大小为 0
        // 通常 Wasmtime 会在每个实例内存末尾保留巨大的虚拟内存（Guard Pages，通常 4GB）来配合信号机制捕获越界。
        // 既然上面关闭了 signals_based_traps，就不需要这个保护区了。
        // 设置为 0 可以极大减少【虚拟内存 (VSS)】的占用，避免 32位设备或资源受限设备 OOM。
        wasmtime_config_memory_guard_size_set(conf, 0);

        // 7. 限制 Wasm 栈大小为 512KB
        // 防止递归过深导致宿主进程栈溢出崩溃。
        wasmtime_config_max_wasm_stack_set(conf, 512 * 1024);
        return conf;
    }

    void WasmManager::initEngine() {
        std::unique_lock<std::shared_mutex> lock(cacheMutex);
        if (!engine) {
            auto conf = createConfig();
            engine = wasm_engine_new_with_config(conf);
            LOGI("Wasm Engine Initialized.");
        }
    }

    void WasmManager::releaseEngine() {
        // 1. 先清空所有 Session
        {
            std::unique_lock<std::shared_mutex> lock(sessionMutex);
            for (auto& kv : sessionCache) {
                delete kv.second; // 触发 Session 析构
            }
            sessionCache.clear();
        }

        // 2. 再清空 Modules 和 Engine
        std::unique_lock<std::shared_mutex> lock(cacheMutex);
        for (auto& kv : moduleCache) {
            wasmtime_module_delete(kv.second);
        }
        moduleCache.clear();
        if (engine) {
            wasm_engine_delete(engine);
            engine = nullptr;
            LOGI("Wasm Engine Released.");
        }
    }

    wasmtime_module_t* WasmManager::getOrLoadModule(const std::string& key, const std::string& filePath, bool isJit) {
        {
            std::shared_lock<std::shared_mutex> lock(cacheMutex);
            if (!engine) return nullptr;
            auto it = moduleCache.find(key);
            if (it != moduleCache.end()) return it->second;
        }

        std::unique_lock<std::shared_mutex> lock(cacheMutex);
        if (!engine) return nullptr;
        if (moduleCache.find(key) != moduleCache.end()) return moduleCache[key];

        LOGI("Cache Miss. Loading: %s", filePath.c_str());
        auto data = FileUtils::readFile(filePath);
        if (data.empty()) return nullptr;

        wasmtime_module_t* module = nullptr;
        wasmtime_error_t* error = nullptr;
        if (isJit) {
            error = wasmtime_module_new(engine, data.data(), data.size(), &module);
        } else {
            error = wasmtime_module_deserialize(engine, data.data(), data.size(), &module);
        }

        if (error) {
            wasmtime_error_delete(error);
            return nullptr;
        }
        moduleCache[key] = module;
        return module;
    }

    wasmtime_module_t* WasmManager::getModule(const std::string& key) {
        std::shared_lock<std::shared_mutex> lock(cacheMutex);
        auto it = moduleCache.find(key);
        return (it != moduleCache.end()) ? it->second : nullptr;
    }

    // [新增] 获取 Session，如果没有则创建
    WasmSession* WasmManager::getOrCreateSession(const std::string& key) {
        // 1. 尝试从缓存获取 (读锁)
        {
            std::shared_lock<std::shared_mutex> lock(sessionMutex);
            auto it = sessionCache.find(key);
            if (it != sessionCache.end()) {
                WasmSession* value = it->second;
                if (value != nullptr) {
                    LOGI("[wasmtime] 2. get session (Ptr): Found object at address: %p", (void*)value);
                } else {
                    LOGI("[wasmtime] 2. get session (Ptr): Found nullptr in cache for key!");
                }
                return value;
            }
        }

        // 2. 获取对应的 Module (需要先加载好)
        auto* module = getModule(key);
        if (!module) {
            LOGE("Cannot create session: Module %s not found", key.c_str());
            return nullptr;
        } else {
            LOGI("[wasmtime] 1. GET module cache (Ptr): address: %p", (void*)module);
        }

        // 3. 创建新 Session (写锁)
        std::unique_lock<std::shared_mutex> lock(sessionMutex);
        // 双重检查
        if (sessionCache.find(key) != sessionCache.end()) {
            LOGI("[wasmtime] 3. Create sessions and cache.");
            return sessionCache[key];
        }

        // 创建堆对象
        auto* session = new WasmSession(engine, module, key);
        // 初始化 (在此处初始化，确保只做一次)
        if (!session->initialize()) {
            delete session;
            return nullptr;
        }

        sessionCache[key] = session;
        return session;
    }

    // [新增] 释放 Session
    void WasmManager::releaseSession(const std::string& key) {
        std::unique_lock<std::shared_mutex> lock(sessionMutex);
        auto it = sessionCache.find(key);
        if (it != sessionCache.end()) {
            delete it->second; // 析构 WasmSession
            sessionCache.erase(it);
            LOGI("Session Released: %s", key.c_str());
        }
    }

    void WasmManager::releaseModule(const std::string& key) {
        // [关键] 释放模块前，必须先释放依赖该模块的 Session
        releaseSession(key);

        std::unique_lock<std::shared_mutex> lock(cacheMutex);
        auto it = moduleCache.find(key);
        if (it != moduleCache.end()) {
            wasmtime_module_delete(it->second);
            moduleCache.erase(it);
            LOGI("Module Released: %s", key.c_str());
        }
    }

    wasm_engine_t* WasmManager::getEngine() const { return engine; }
}