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

    bool FileUtils::writeFile(const std::string& path, const std::vector<uint8_t>& data) {
        std::ofstream file(path, std::ios::binary);
        if (!file) return false;
        file.write((const char*)data.data(), data.size());
        return true;
    }
}