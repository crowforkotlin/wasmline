/**
 * Encodes the Core Wasmline response frame.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */

#include "wasmline/protocol/WasmlineProtocol.h"

#include <algorithm>

namespace wasmline {
    namespace {
        constexpr char kMagic[] = {'W', 'L', 'M', 'F'};
        constexpr size_t kHeaderSize = 18;
        constexpr uint8_t kSuccessStatus = 0;
        constexpr uint8_t kFailureStatus = 1;

        void writeUint32(std::string& buffer, size_t offset, uint32_t value) {
            buffer[offset] = static_cast<char>(value & 0xFFu);
            buffer[offset + 1] = static_cast<char>((value >> 8) & 0xFFu);
            buffer[offset + 2] = static_cast<char>((value >> 16) & 0xFFu);
            buffer[offset + 3] = static_cast<char>((value >> 24) & 0xFFu);
        }

        std::string encode(uint8_t status, uint32_t errorCode, std::string_view message, std::string_view payload) {
            std::string result;
            result.resize(kHeaderSize + message.size() + payload.size());
            result[0] = kMagic[0];
            result[1] = kMagic[1];
            result[2] = kMagic[2];
            result[3] = kMagic[3];
            result[4] = static_cast<char>(WasmlineResponseCodec::kFrameVersion);
            result[5] = static_cast<char>(status);
            writeUint32(result, 6, errorCode);
            writeUint32(result, 10, static_cast<uint32_t>(message.size()));
            writeUint32(result, 14, static_cast<uint32_t>(payload.size()));
            std::copy(message.begin(), message.end(), result.begin() + kHeaderSize);
            std::copy(payload.begin(), payload.end(), result.begin() + kHeaderSize + message.size());
            return result;
        }
    } // namespace

    std::string WasmlineResponseCodec::success(std::string_view payload) {
        return encode(kSuccessStatus, 0, {}, payload);
    }

    std::string WasmlineResponseCodec::failure(WasmlineErrorCode code, std::string_view message, std::string_view details) {
        return encode(kFailureStatus, static_cast<uint32_t>(code), message, details);
    }
} // namespace wasmline
