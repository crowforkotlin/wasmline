/**
 * Encodes typed invocation values for native platform bridges.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */

#pragma once

#include <cstdint>
#include <string>
#include <string_view>
#include <vector>

#include "wasmline/invocation/InvocationResult.h"

namespace wasmline {
    /** Identifies the value encoding used by a typed invocation. */
    enum class TypedInvocationKind : uint8_t {
        RAW = 1,
        COMPONENT = 2,
    };

    /** Encodes and decodes typed invocation values. */
    class TypedInvocationCodec {
    public:
        /** Decodes raw Core Wasm arguments. */
        static bool decodeRawArguments(std::string_view input, std::vector<RawValue>* values, std::string* error);

        /** Decodes Component Model arguments. */
        static bool decodeComponentArguments(std::string_view input, std::vector<ComponentValue>* values, std::string* error);

        /** Encodes Component Model arguments for a typed host callback. */
        static std::vector<uint8_t> encodeComponentArguments(const std::vector<ComponentValue>& values);

        /** Decodes a typed Component host callback result. */
        static bool decodeComponentResult(std::string_view input, InvocationResult* result, std::string* error);

        /** Encodes an invocation result for the selected value kind. */
        static std::vector<uint8_t> encodeResult(const InvocationResult& result, TypedInvocationKind kind);
    };
} // namespace wasmline
