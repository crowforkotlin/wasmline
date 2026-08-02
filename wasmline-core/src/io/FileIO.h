/**
 * Provides file input and output operations.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */

#pragma once

#include <cstdint>
#include <cstddef>
#include <string>
#include <vector>

namespace wasmline::io {
    bool exists(const std::string& path);

    std::vector<uint8_t> readFile(const std::string& path);

    bool writeFile(const std::string& path, const uint8_t* data, size_t len);
} // namespace wasmline::io
