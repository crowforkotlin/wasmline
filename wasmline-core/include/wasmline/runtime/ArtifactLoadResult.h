/**
 * Defines the structured result returned by native artifact loading.
 *
 * Date: 2026-09-02
 * Author: crowforkotlin
 */

#pragma once

#include <cstdint>
#include <string>
#include <utility>
#include <vector>

#include "wasmline/protocol/ErrorDefs.h"

namespace wasmline {
    /**
     * Carries a native artifact load result and optional backend diagnostics.
     *
     * Date: 2026-09-02
     * Author: crowforkotlin
     */
    class ArtifactLoadResult final {
    public:
        /** Creates a successful artifact load result. */
        static ArtifactLoadResult success() { return ArtifactLoadResult(true, WasmlineErrorCode::UNKNOWN, {}, {}); }

        /** Creates a failed artifact load result. */
        static ArtifactLoadResult failure(WasmlineErrorCode code, std::string message, std::vector<uint8_t> details = {}) {
            return ArtifactLoadResult(false, code, std::move(message), std::move(details));
        }

        /** Returns whether the artifact was loaded successfully. */
        bool isSuccess() const noexcept { return success_; }

        /** Returns the stable failure code. */
        WasmlineErrorCode errorCode() const noexcept { return errorCode_; }

        /** Returns the human-readable failure message. */
        const std::string& message() const noexcept { return message_; }

        /** Returns backend-specific diagnostic bytes. */
        const std::vector<uint8_t>& details() const noexcept { return details_; }

    private:
        ArtifactLoadResult(bool success, WasmlineErrorCode errorCode, std::string message, std::vector<uint8_t> details)
            : success_(success), errorCode_(errorCode), message_(std::move(message)), details_(std::move(details)) {}

        bool success_;
        WasmlineErrorCode errorCode_;
        std::string message_;
        std::vector<uint8_t> details_;
    };
} // namespace wasmline
