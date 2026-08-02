/**
 * Implements native Wasm invocation results.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */

#include "wasmline/invocation/InvocationResult.h"

#include <utility>

namespace wasmline {
    RawValue RawValue::fromI32(int32_t value) {
        RawValue result;
        result.type = Type::I32;
        result.data.i32 = value;
        return result;
    }

    RawValue RawValue::fromI64(int64_t value) {
        RawValue result;
        result.type = Type::I64;
        result.data.i64 = value;
        return result;
    }

    RawValue RawValue::fromF32(float value) {
        RawValue result;
        result.type = Type::F32;
        result.data.f32 = value;
        return result;
    }

    RawValue RawValue::fromF64(double value) {
        RawValue result;
        result.type = Type::F64;
        result.data.f64 = value;
        return result;
    }

    InvocationResult::InvocationResult(bool success, WasmlineErrorCode code, std::string message, std::vector<uint8_t> details,
                                       std::vector<RawValue> values, std::vector<ComponentValue> componentValues)
        : success_(success), errorCode_(code), message_(std::move(message)), details_(std::move(details)), values_(std::move(values)),
          componentValues_(std::move(componentValues)) {}

    InvocationResult InvocationResult::success(std::vector<RawValue> values) {
        return InvocationResult(true, static_cast<WasmlineErrorCode>(0), {}, {}, std::move(values), {});
    }

    InvocationResult InvocationResult::successComponent(std::vector<ComponentValue> values) {
        return InvocationResult(true, static_cast<WasmlineErrorCode>(0), {}, {}, {}, std::move(values));
    }

    InvocationResult InvocationResult::failure(WasmlineErrorCode code, std::string message, std::vector<uint8_t> details) {
        return InvocationResult(false, code, std::move(message), std::move(details), {}, {});
    }

    bool InvocationResult::isSuccess() const {
        return success_;
    }

    WasmlineErrorCode InvocationResult::errorCode() const {
        return errorCode_;
    }

    const std::string& InvocationResult::message() const {
        return message_;
    }

    const std::vector<uint8_t>& InvocationResult::details() const {
        return details_;
    }

    const std::vector<RawValue>& InvocationResult::values() const {
        return values_;
    }

    const std::vector<ComponentValue>& InvocationResult::componentValues() const {
        return componentValues_;
    }
} // namespace wasmline
