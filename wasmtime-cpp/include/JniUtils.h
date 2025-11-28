#ifndef JNI_UTILS_H
#define JNI_UTILS_H
#include <string>
#include <vector>

namespace JniUtils {
    bool fileExists(const std::string& path);
    std::vector<uint8_t> readFile(const std::string& path);
    bool writeFile(const std::string& path, const std::vector<uint8_t>& data);
}

#endif //JNI_UTILS_H