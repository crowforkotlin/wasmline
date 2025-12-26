/**
 * File system utilities.
 *
 * Date: 2025-12-02
 * Author: crowforkotlin
 */

#pragma once

#include <string>
#include <vector>
#include <cstdint>

namespace wasmline {
    class Utils {
    public:
        // Checks if a file exists at the given path
        static bool exists(const std::string &path);

        // Reads file content into a byte vector
        static std::vector<uint8_t> readFile(const std::string &path);

        // Writes raw data to file (Optimized for pointer access)
        static bool writeFile(const std::string &path, const uint8_t *data, size_t len);
    };
}