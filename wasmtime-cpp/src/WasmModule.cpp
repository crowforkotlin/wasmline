#include "WasmModule.h"
#include "WasmConfig.h"
#include "WasmExecutor.h"
#include "JniUtils.h"
#include <chrono>

// 辅助：计算耗时
static long long current_ms() {
    auto now = std::chrono::high_resolution_clock::now();
    return std::chrono::duration_cast<std::chrono::milliseconds>(now.time_since_epoch()).count();
}

WasmModule::WasmModule() {}

WasmModule::~WasmModule() {
    if (linker) wasmtime_linker_delete(linker);
    if (module) wasmtime_module_delete(module);
    if (engine) wasm_engine_delete(engine);
}

// 全局唯一的 Engine 指针
static wasm_engine_t* g_engine = nullptr;

// 获取全局 Engine（懒加载，线程安全由 C++11 静态变量保证，或者单纯依靠主线程初始化）
static wasm_engine_t* getGlobalEngine() {
    if (!g_engine) {
        // 1. 创建配置
        wasm_config_t* conf = WasmConfig::createAndroidConfig();
        
        // 2. 创建 Engine (Engine 会接管 Config 的所有权)
        g_engine = wasm_engine_new_with_config(conf);
        
        if (!g_engine) {
            LOGE("FATAL: Failed to create global wasm engine!");
        } else {
            LOGI("Global Wasm Engine initialized.");
        }
    }
    return g_engine;
}

bool WasmModule::initCommon() {

    // 获取全局单例
    engine = getGlobalEngine();
    if (!engine) {
        LOGE("Failed to create engine");
        return false;
    }

    // 3. 创建 Linker 并配置 WASI
    linker = wasmtime_linker_new(engine);
    wasmtime_linker_define_wasi(linker);

    // 4. 注册 Host Functions
    // 这里将具体的函数注册逻辑交给 Executor 处理
    WasmExecutor::registerHostFunctions(linker);

    return true;
}

WasmModule* WasmModule::loadFromPath(const std::string& path) {
    if (!JniUtils::fileExists(path)) {
        LOGI("Cache file not found: %s", path.c_str());
        return nullptr;
    }

    auto start = current_ms();
    // 从 C++ 层直接读取文件，不经过 Java
    auto data = JniUtils::readFile(path);
    if (data.empty()) return nullptr;

    auto* instance = new WasmModule();
    if (!instance->initCommon()) { delete instance; return nullptr; }

    LOGI("AOT Deserialize... size=%zu", data.size());
    
    // 反序列化
    wasmtime_error_t* err = wasmtime_module_deserialize(instance->engine, data.data(), data.size(), &instance->module);
    if (err) {
        wasm_byte_vec_t msg;
        wasmtime_error_message(err, &msg);
        LOGE("Deserialize failed: %s", msg.data);
        wasm_byte_vec_delete(&msg);
        wasmtime_error_delete(err);
        delete instance;
        return nullptr;
    }

    LOGI("AOT Success. Time: %lld ms", (current_ms() - start));
    return instance;
}

WasmModule* WasmModule::loadFromSource(const std::vector<uint8_t>& source) {
    if (source.empty()) return nullptr;

    auto start = current_ms();
    auto* instance = new WasmModule();
    if (!instance->initCommon()) { delete instance; return nullptr; }

    LOGI("JIT Compiling... size=%zu", source.size());

    // 编译源码
    wasmtime_error_t* err = wasmtime_module_new(instance->engine, source.data(), source.size(), &instance->module);
    if (err) {
        wasm_byte_vec_t msg;
        wasmtime_error_message(err, &msg);
        LOGE("Compile failed: %s", msg.data);
        wasm_byte_vec_delete(&msg);
        wasmtime_error_delete(err);
        delete instance;
        return nullptr;
    }

    LOGI("JIT Success. Time: %lld ms", (current_ms() - start));
    return instance;
}

WasmModule* WasmModule::loadFromSourcePath(const std::string& path) {
    if (!JniUtils::fileExists(path)) {
        LOGE("Source file not found: %s", path.c_str());
        return nullptr;
    }

    auto start = current_ms();
    
    // 1. C++ 直接读取文件，不经过 Java Heap
    auto data = JniUtils::readFile(path);
    if (data.empty()) return nullptr;

    auto* instance = new WasmModule();
    if (!instance->initCommon()) { delete instance; return nullptr; }

    LOGI("JIT Compiling from path... size=%zu", data.size());

    // 2. 编译
    wasmtime_error_t* err = wasmtime_module_new(instance->engine, data.data(), data.size(), &instance->module);
    if (err) {
        wasm_byte_vec_t msg;
        wasmtime_error_message(err, &msg);
        LOGE("Compile failed: %s", msg.data);
        wasm_byte_vec_delete(&msg);
        wasmtime_error_delete(err);
        delete instance;
        return nullptr;
    }

    LOGI("JIT (Path) Success. Time: %lld ms", (current_ms() - start));
    return instance;
}

bool WasmModule::saveCacheToPath(const std::string& path) {
    if (!module) return false;

    LOGI("Serializing module...");
    wasm_byte_vec_t serialized;
    wasmtime_error_t* err = wasmtime_module_serialize(module, &serialized);

    if (err) {
        wasm_byte_vec_t msg;
        wasmtime_error_message(err, &msg);
        LOGE("Serialize failed: %s", msg.data);
        wasm_byte_vec_delete(&msg);
        wasmtime_error_delete(err);
        return false;
    }

    // 转换为 vector 并写入
    std::vector<uint8_t> data(serialized.data, serialized.data + serialized.size);
    wasm_byte_vec_delete(&serialized);

    bool success = JniUtils::writeFile(path, data);
    LOGI("Cache saved to %s (size: %zu, success: %d)", path.c_str(), data.size(), success);
    return success;
}

std::string WasmModule::call(const std::string& action, const std::string& json) {
    WasmExecutor exec(this, action, json);
    return exec.run();
}