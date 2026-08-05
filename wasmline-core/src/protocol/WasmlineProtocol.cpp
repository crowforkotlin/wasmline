/**
 * Encodes the Core Wasmline response frame.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */

#include "wasmline/protocol/WasmlineProtocol.h"

#include <algorithm>
#include <cstring>

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

        uint32_t readUint32(std::string_view buffer, size_t offset) {
            return static_cast<uint32_t>(static_cast<uint8_t>(buffer[offset])) |
                   (static_cast<uint32_t>(static_cast<uint8_t>(buffer[offset + 1])) << 8u) |
                   (static_cast<uint32_t>(static_cast<uint8_t>(buffer[offset + 2])) << 16u) |
                   (static_cast<uint32_t>(static_cast<uint8_t>(buffer[offset + 3])) << 24u);
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

    bool WasmlineResponseCodec::decode(std::string_view encoded, WasmlineResponseFrame* result, std::string* errorMessage) {
        auto fail = [&](std::string message) {
            if (errorMessage) *errorMessage = std::move(message);
            return false;
        };
        if (!result) return fail("Wasmline response output is null.");
        *result = {};
        if (encoded.size() < kHeaderSize) return fail("Wasmline response header is incomplete.");
        if (std::memcmp(encoded.data(), kMagic, sizeof(kMagic)) != 0) return fail("Wasmline response magic is invalid.");
        if (static_cast<uint8_t>(encoded[4]) != kFrameVersion) return fail("Wasmline response version is unsupported.");

        const uint8_t status = static_cast<uint8_t>(encoded[5]);
        const uint32_t errorCode = readUint32(encoded, 6);
        const uint32_t messageLength = readUint32(encoded, 10);
        const uint32_t payloadLength = readUint32(encoded, 14);
        const uint64_t encodedBodyLength = static_cast<uint64_t>(messageLength) + static_cast<uint64_t>(payloadLength);
        if (encodedBodyLength != encoded.size() - kHeaderSize) return fail("Wasmline response lengths are invalid.");

        if (status == kSuccessStatus) {
            if (errorCode != 0 || messageLength != 0) return fail("Successful Wasmline response contains error fields.");
        } else if (status == kFailureStatus) {
            if (errorCode == 0) return fail("Failed Wasmline response has no error code.");
        } else {
            return fail("Wasmline response status is invalid.");
        }

        const size_t messageOffset = kHeaderSize;
        const size_t payloadOffset = messageOffset + messageLength;
        result->isSuccess = status == kSuccessStatus;
        result->errorCode = errorCode;
        result->message.assign(encoded.data() + messageOffset, messageLength);
        result->payload.assign(encoded.data() + payloadOffset, payloadLength);
        if (errorMessage) errorMessage->clear();
        return true;
    }
} // namespace wasmline
