#include "JniUtils.h"
#include <fstream>
#include <sys/stat.h>

namespace JniUtils {

    bool fileExists(const std::string& path) {
        struct stat buffer;
        return (stat(path.c_str(), &buffer) == 0);
    }

    std::vector<uint8_t> readFile(const std::string& path) {
        std::ifstream file(path, std::ios::binary);
        if (!file) return {};

        // 移动指针到末尾获取大小
        file.seekg(0, std::ios::end);
        std::streamsize size = file.tellg();
        file.seekg(0, std::ios::beg);

        if (size <= 0) return {};

        std::vector<uint8_t> buffer(size);
        if (file.read((char*)buffer.data(), size)) {
            return buffer;
        }
        return {};
    }

    bool writeFile(const std::string& path, const std::vector<uint8_t>& data) {
        std::ofstream file(path, std::ios::binary);
        if (!file) return false;
        file.write((const char*)data.data(), data.size());
        return true;
    }

}