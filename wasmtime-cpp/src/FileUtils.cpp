#include "FileUtils.h"
#include <fstream>
#include <sys/stat.h>

namespace crow {

    bool FileUtils::exists(const std::string& path) {
        struct stat buffer;
        return (stat(path.c_str(), &buffer) == 0);
    }

    std::vector<uint8_t> FileUtils::readFile(const std::string& path) {
        std::ifstream file(path, std::ios::binary | std::ios::ate);
        if (!file) return {};

        std::streamsize size = file.tellg();
        file.seekg(0, std::ios::beg);

        if (size <= 0) return {};

        std::vector<uint8_t> buffer(size);
        if (file.read((char*)buffer.data(), size)) {
            return buffer;
        }
        return {};
    }

    // [新增] 核心实现：直接写入指针数据
    bool FileUtils::writeFile(const std::string &path, const uint8_t *data, size_t len) {
        std::ofstream file(path, std::ios::binary);
        if (!file) return false;
        // ofstream.write 需要 const char*，这里强转是安全的
        file.write((const char *) data, len);
        return true;
    }

    // [修改] 原有的 vector 版本改为调用上面的核心实现
    bool FileUtils::writeFile(const std::string &path, const std::vector<uint8_t> &data) {
        if (data.empty()) return false;
        // 直接传递 vector 内部的指针和大小
        return writeFile(path, data.data(), data.size());
    }

}