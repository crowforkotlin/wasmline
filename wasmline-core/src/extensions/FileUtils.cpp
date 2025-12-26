/**
 * Implementation of file system utilities.
 *
 * Date: 2025-12-02
 * Author: crowforkotlin
 */

#include "FileUtils.h"
#include <fstream>
#include <sys/stat.h>

namespace wasmline {
    bool Utils::exists(const std::string &path) {
        struct stat buffer{};
        return (stat(path.c_str(), &buffer) == 0);
    }

    std::vector<uint8_t> Utils::readFile(const std::string &path) {
        std::ifstream file(path, std::ios::binary | std::ios::ate);
        if (!file) return {};

        auto size = file.tellg();
        if (size <= 0) return {};

        file.seekg(0, std::ios::beg);
        std::vector<uint8_t> buffer(size);
        if (file.read(reinterpret_cast<char *>(buffer.data()), size)) {
            return buffer;
        }
        return {};
    }

    bool Utils::writeFile(const std::string &path, const uint8_t *data, size_t len) {
        std::ofstream file(path, std::ios::binary);
        if (!file) return false;
        file.write(reinterpret_cast<const char *>(data), static_cast<std::streamsize>(len));
        return true;
    }
}