/**
 * Defines stable Wasmline error codes.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */

#pragma once

#include <cstdint>

namespace wasmline {
    /** Defines stable error codes returned by the native API. */
    enum class WasmlineErrorCode : int32_t {
        ACTION_NOT_BOUND = 1001,
        UNKNOWN_ACTION = 1002,
        INVALID_PAYLOAD = 1003,
        HANDLER_FAILED = 1004,
        SERIALIZATION_FAILED = 1005,
        ENGINE_NOT_INITIALIZED = 2001,
        CORE_TRAP = 2002,
        CORE_EXPORT_NOT_FOUND = 2003,
        INVOCATION_PROTOCOL_MISMATCH = 2004,
        COMPONENT_TRAP = 2101,
        COMPONENT_EXPORT_NOT_FOUND = 2102,
        COMPONENT_CALL_FAILED = 2103,
        COMPONENT_RESOURCE_INVALID = 2104,
        RESPONSE_MALFORMED = 3001,
        RESPONSE_MISSING = 3002,
        TRANSPORT_FAILURE = 3003,
        RESPONSE_UNSUPPORTED_VERSION = 3004,
    };
} // namespace wasmline
