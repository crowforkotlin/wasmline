/**
 * Represents the result of a native Wasm invocation.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */

#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "wasmline/protocol/ErrorDefs.h"
#include "wasmline/value/ComponentValue.h"

namespace wasmline {
    /** Stores a scalar value for a Core Wasm export call. */
    struct RawValue {
        /** Defines the supported scalar types. */
        enum class Type : uint8_t {
            I32,
            I64,
            F32,
            F64,
        };

        Type type = Type::I32;

        union Data {
            int32_t i32;
            int64_t i64;
            float f32;
            double f64;

            Data() : i32(0) {}
        } data;

        /** Creates an i32 value. */
        static RawValue fromI32(int32_t value);

        /** Creates an i64 value. */
        static RawValue fromI64(int64_t value);

        /** Creates an f32 value. */
        static RawValue fromF32(float value);

        /** Creates an f64 value. */
        static RawValue fromF64(double value);
    };

    /** Stores a successful or failed native invocation result. */
    class InvocationResult {
    public:
        /** Creates a successful Core Wasm result. */
        static InvocationResult success(std::vector<RawValue> values = {});

        /** Creates a successful Component Model result. */
        static InvocationResult successComponent(std::vector<ComponentValue> values = {});

        /** Creates a failed invocation result. */
        static InvocationResult failure(WasmlineErrorCode code, std::string message, std::vector<uint8_t> details = {});

        /** Returns whether the invocation succeeded. */
        bool isSuccess() const;

        /** Returns the stable error code. */
        WasmlineErrorCode errorCode() const;

        /** Returns the diagnostic message. */
        const std::string& message() const;

        /** Returns optional diagnostic data. */
        const std::vector<uint8_t>& details() const;

        /** Returns Core Wasm result values. */
        const std::vector<RawValue>& values() const;

        /** Returns Component Model result values. */
        const std::vector<ComponentValue>& componentValues() const;

    private:
        InvocationResult(bool success, WasmlineErrorCode code, std::string message, std::vector<uint8_t> details,
                         std::vector<RawValue> values, std::vector<ComponentValue> componentValues);

        bool success_;
        WasmlineErrorCode errorCode_;
        std::string message_;
        std::vector<uint8_t> details_;
        std::vector<RawValue> values_;
        std::vector<ComponentValue> componentValues_;
    };
} // namespace wasmline
