#pragma once
#include <string>
#include <vector>

namespace crow {
    class FileUtils {
    public:
        // 检查文件是否存在
        static bool exists(const std::string& path);
        // 读取文件二进制数据
        static std::vector<uint8_t> readFile(const std::string& path);
        // 写入文件二进制数据
        static bool writeFile(const std::string& path, const std::vector<uint8_t>& data);
        // [新增] 写入文件 (接受 原始指针 + 长度)，用于高性能写入，避免构造 vector
        static bool writeFile(const std::string& path, const uint8_t* data, size_t len);
    };
}