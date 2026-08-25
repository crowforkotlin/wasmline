/**
 * Defines native Core Wasm raw invocation contracts.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 */

#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#include "wasmline/invocation/InvocationResult.h"

namespace wasmline {
    /**
     * Describes a scalar Core Wasm function signature.
     *
     * Date: 2026-08-25
     * Author: crowforkotlin
     */
    struct RawFunctionSignature {
        /** Parameter types in declaration order. */
        std::vector<RawValue::Type> parameters;
        /** Result types in declaration order. */
        std::vector<RawValue::Type> results;
    };

    /**
     * Describes one caller-provided synchronous host import.
     *
     * Date: 2026-08-25
     * Author: crowforkotlin
     */
    struct RawImportDefinition {
        /** Import module namespace. */
        std::string module;
        /** Import field name. */
        std::string name;
        /** Exact scalar function signature. */
        RawFunctionSignature signature;
    };

    /**
     * Describes one reflected Core Wasm export.
     *
     * Kind values are bridge-stable: 0=function, 1=memory, 2=global,
     * 3=table, and 4=unknown.
     *
     * Date: 2026-08-25
     * Author: crowforkotlin
     */
    struct RawExportDefinition {
        /** Exact export name. */
        std::string name;
        /** Stable export kind code documented above. */
        uint8_t kind = 4;
        /** Reflected scalar signature when supported. */
        RawFunctionSignature signature;
        /** Whether the signature field contains valid reflected types. */
        bool hasSignature = false;
    };

    /** Receives one synchronous host import call and returns an owner-allocated result carrier. */
    using RawImportCallback = char* (*)(void* user, const char* sessionKey, const char* module, size_t moduleLen, const char* name,
                                        size_t nameLen, const void* arguments, size_t argumentsLen, size_t* resultLen);

    /** Releases each non-null buffer returned by RawImportCallback and is required for registered imports. */
    using RawImportBufferFree = void (*)(char* buffer);

    /** Releases callback user data when a session is destroyed. */
    using RawImportUserFinalizer = void (*)(void* user);
} // namespace wasmline
