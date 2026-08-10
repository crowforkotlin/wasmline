/**
 * Defines the Core Wasmline response frame.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */

#pragma once

#include <cstdint>
#include <string>
#include "wasmline/protocol/ErrorDefs.h"
#include <string_view>

namespace wasmline {
    /** Stores a decoded Wasmline response frame. */
    struct WasmlineResponseFrame {
        bool isSuccess = false;
        uint32_t errorCode = 0;
        std::string message;
        std::string payload;
    };

    /** Encodes Core Wasmline invocation results. */
    class WasmlineResponseCodec {
    public:
        static constexpr uint8_t kFrameVersion = 1;

        /** Encodes a successful response. */
        static std::string success(std::string_view payload);

        /** Encodes a failed response. */
        static std::string failure(WasmlineErrorCode code, std::string_view message, std::string_view details = {});

        /** Decodes and validates a response frame. */
        static bool decode(std::string_view encoded, WasmlineResponseFrame* result, std::string* errorMessage = nullptr);
    };
} // namespace wasmline
