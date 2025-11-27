#include <iostream>
#include <fstream>
#include <vector>
#include "WasmtimeCore.h"

// 读取文件辅助函数
std::vector<uint8_t> readFile(const std::string& path) {
    std::ifstream file(path, std::ios::binary | std::ios::ate);
    if (!file.is_open()) return {};
    std::streamsize size = file.tellg();
    file.seekg(0, std::ios::beg);
    std::vector<uint8_t> buffer(size);
    if (file.read((char*)buffer.data(), size)) return buffer;
    return {};
}

// 桌面端日志回调
void console_logger(const std::string& msg) {
    std::cout << "[Sample] " << msg << std::endl;
}

int main(int argc, char** argv) {
    if (argc < 2) {
        std::cerr << "Usage: ./wasm_sample <path_to_add.wasm>" << std::endl;
        return 1;
    }

    std::string wasmPath = argv[1];
    std::cout << "Loading Wasm file: " << wasmPath << std::endl;
    auto wasmBytes = readFile(wasmPath);
    if (wasmBytes.empty()) {
        std::cerr << "Failed to read file." << std::endl;
        return 1;
    }

    // 1. 配置 (针对桌面端，通常可以开启更多优化)
    WasmConfig config;
    config.enableGc = true;
    config.enableExceptionHandling = true;
    config.enableFunctionReferences = true;
    
    // Windows/Linux 通常支持 SIMD 和信号处理，可以开启以获得更高性能
    config.enableSimd = true; 
    config.enableSignalsBasedTraps = true; 
    config.enableCraneliftOpt = true; // 开启优化
    
    config.enableWasiOutput = true;

    // 2. 初始化 Core
    WasmtimeCore core(config);
    core.setLogCallback(console_logger);

    // 3. 运行
    int a = 10;
    int b = 20;
    std::cout << "Running add(" << a << ", " << b << ")..." << std::endl;
    
    int32_t result = core.runAddFunction(wasmBytes, a, b);

    std::cout << "Final Result: " << result << std::endl;

    return 0;
}