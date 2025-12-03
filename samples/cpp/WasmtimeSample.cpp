#include <iostream>
#include <string>
#include <vector>
#include <chrono>
#include <filesystem>
#include "WasmApi.h"

namespace fs = std::filesystem;

#define INFO(msg) std::cout << "[sample] " << msg << std::endl

/**
 * 执行单次 Wasm 逻辑
 * @param wasmFile  文件名 (例如 "plugin.wasm")
 * @param action    要调用的动作
 * @param inputData 输入数据
 */
void runWasmLogic(const std::string& wasmFile, const std::string& action, const std::string& inputData) {
    // 自动推导 key 和 cache 文件名
    std::string key = wasmFile; 
    std::string cacheFile = wasmFile;
    // 将后缀替换为 .cwasm
    size_t lastdot = cacheFile.find_last_of(".");
    if (lastdot != std::string::npos) {
        cacheFile = cacheFile.substr(0, lastdot) + ".cwasm";
    } else {
        cacheFile += ".cwasm";
    }

    fs::path currentPath = fs::current_path();
    fs::path wasmPath = currentPath / wasmFile;
    fs::path cachePath = currentPath / cacheFile;

    INFO("------------------------------------------------");
    INFO("Target: " << key);
    
    bool isLoaded = false;
    auto startLoad = std::chrono::high_resolution_clock::now();

    // 1. 尝试加载缓存 (AOT)
    if (fs::exists(cachePath)) {
        INFO(">> Finding Cache: YES (" << cacheFile << ")");
        if (WasmApi::loadModule(key, cachePath.string(), false)) {
            isLoaded = true;
            INFO(">> Mode: AOT (Loaded from Cache)");
        } else {
            INFO(">> Mode: AOT Failed (Deleting corrupted cache)");
            fs::remove(cachePath);
        }
    } else {
        INFO(">> Finding Cache: NO");
    }

    // 2. 如果缓存没命中，加载源码 (JIT)
    if (!isLoaded) {
        if (!fs::exists(wasmPath)) {
            std::cerr << "[Error] Source file not found: " << wasmPath.string() << std::endl;
            return;
        }

        INFO(">> Mode: JIT (Compiling from Source)");
        if (WasmApi::loadModule(key, wasmPath.string(), true)) {
            isLoaded = true;
            // 编译后立即保存缓存
            if (WasmApi::saveModuleCache(key, cachePath.string())) {
                INFO(">> Cache Saved: " << cacheFile);
            }
        }
    }

    auto endLoad = std::chrono::high_resolution_clock::now();
    
    if (!isLoaded) {
        std::cerr << "[Error] Failed to load module." << std::endl;
        return;
    }

    // 3. 执行调用
    auto startCall = std::chrono::high_resolution_clock::now();
    std::string result = WasmApi::call(key, action, inputData);
    auto endCall = std::chrono::high_resolution_clock::now();

    // 4. 打印统计
    auto loadTime = std::chrono::duration_cast<std::chrono::microseconds>(endLoad - startLoad).count();
    auto callTime = std::chrono::duration_cast<std::chrono::microseconds>(endCall - startCall).count();
    
    int resultVal = 0;
    if(!result.empty()) resultVal = (int)result[0];

    INFO(">> Load Time: " << loadTime / 1000.0 << " ms");
    INFO(">> Call Time: " << callTime / 1000.0 << " ms");
    INFO(">> Result   : " << resultVal);
    INFO("------------------------------------------------");
}

int main(int argc, char** argv) {
    // 1. 初始化引擎
    WasmApi::initEngine();

    // 2. 确定文件名 (默认 plugin.wasm，或者通过命令行参数传入)
    std::string targetWasm = "plugin.wasm";
    if (argc > 1) {
        targetWasm = argv[1];
    }
    
    // 构造测试数据 (1 + 2)
    char rawData[] = {1, 2};
    std::string payload(rawData, 2);

    INFO("=== [PASS 1] First Run (Expect JIT + Save Cache) ===");
    runWasmLogic(targetWasm, "add", payload);

    INFO("\n=== [PASS 2] Second Run (Expect AOT Cache Hit) ===");
    // 注意：WasmApi 内部有 Session 缓存，为了演示文件级 AOT，
    // 这里其实是在复用内存中的 Module。
    // 如果要严格测试文件加载速度，可以在这里调用 WasmApi::releaseModule(targetWasm) 清理内存
    // 从而强制重新从文件加载 .cwasm
    
    WasmApi::releaseModule(targetWasm); // 强制释放内存，确保从文件系统读取 .cwasm
    runWasmLogic(targetWasm, "add", payload);

    // 3. 释放引擎
    WasmApi::releaseEngine();
    return 0;
}