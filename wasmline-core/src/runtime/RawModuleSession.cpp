/**
 * Implements direct calls to Core Wasm exports.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */

#include "wasmline/runtime/RawModuleSession.h"

#include "logging/NativeLogger.h"
#include "wasi/WasiConfig.h"
#include "wasmtime/WasmtimeMessage.h"

#include <utility>

namespace wasmline {
    namespace {
        void unrootValues(std::vector<wasmtime_val_t>& values) {
            for (auto& value : values) {
                wasmtime_val_unroot(&value);
            }
        }
    } // namespace

    RawModuleSession::RawModuleSession(wasm_engine_t* engine, wasmtime_module_t* module, std::string key)
        : key_(std::move(key)), engine_(engine), module_(module) {
        if (!engine_) {
            LOGE("[Wasmtime] RawModuleSession -> Engine is null: %s", key_.c_str());
            return;
        }

        store_ = wasmtime_store_new(engine_, this, nullptr);
        if (!store_) {
            LOGE("[Wasmtime] RawModuleSession -> Failed to create store: %s", key_.c_str());
            return;
        }

        context_ = wasmtime_store_context(store_);
        linker_ = wasmtime_linker_new(engine_);
        if (!context_ || !linker_) {
            LOGE("[Wasmtime] RawModuleSession -> Failed to create runtime state: %s", key_.c_str());
        }
    }

    RawModuleSession::~RawModuleSession() {
        if (linker_) wasmtime_linker_delete(linker_);
        if (store_) wasmtime_store_delete(store_);
    }

    bool RawModuleSession::initialize() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (initialized_) return true;

        if (!store_ || !context_ || !linker_ || !module_) {
            LOGE("[Wasmtime] RawModuleSession -> Invalid state before initialization: %s", key_.c_str());
            return false;
        }

        wasmtime_error_t* defineWasiError = wasmtime_linker_define_wasi(linker_);
        if (defineWasiError) {
            LOGE("[Wasmtime] RawModuleSession -> Failed to define WASI: %s", wasmtime::errorMessage(defineWasiError).c_str());
            wasmtime_error_delete(defineWasiError);
            return false;
        }

        wasi_config_t* wasi = wasi_config_new();
        if (!wasi) {
            LOGE("[Wasmtime] RawModuleSession -> Failed to create WASI config: %s", key_.c_str());
            return false;
        }
        wasi::configure(wasi, "[Wasmtime-Wasi] raw export logger");

        wasmtime_error_t* wasiError = wasmtime_context_set_wasi(context_, wasi);
        if (wasiError) {
            LOGE("[Wasmtime] RawModuleSession -> Failed to configure WASI: %s", wasmtime::errorMessage(wasiError).c_str());
            wasmtime_error_delete(wasiError);
            wasi_config_delete(wasi);
            return false;
        }

        wasm_trap_t* trap = nullptr;
        wasmtime_error_t* instantiateError = wasmtime_linker_instantiate(linker_, context_, module_, &instance_, &trap);
        if (instantiateError) {
            LOGE("[Wasmtime] RawModuleSession -> Instantiation failed: %s", wasmtime::errorMessage(instantiateError).c_str());
            wasmtime_error_delete(instantiateError);
            return false;
        }
        if (trap) {
            LOGE("[Wasmtime] RawModuleSession -> Instantiation trapped: %s", wasmtime::trapMessage(trap).c_str());
            wasm_trap_delete(trap);
            return false;
        }

        initialized_ = true;
        return true;
    }

    InvocationResult RawModuleSession::invoke(std::string_view exportName, const std::vector<RawValue>& arguments) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!initialized_) {
            return InvocationResult::failure(WasmlineErrorCode::ENGINE_NOT_INITIALIZED, "Raw module session is not initialized.");
        }
        if (exportName.empty()) {
            return InvocationResult::failure(WasmlineErrorCode::CORE_EXPORT_NOT_FOUND, "Raw export name is empty.");
        }

        wasmtime_extern_t exported{};
        if (!wasmtime_instance_export_get(context_, &instance_, exportName.data(), exportName.size(), &exported) ||
            exported.kind != WASMTIME_EXTERN_FUNC) {
            return InvocationResult::failure(WasmlineErrorCode::CORE_EXPORT_NOT_FOUND, "Raw export is not available.");
        }

        wasm_functype_t* functionType = wasmtime_func_type(context_, &exported.of.func);
        if (!functionType) {
            return InvocationResult::failure(WasmlineErrorCode::INVALID_PAYLOAD, "Raw export type is not available.");
        }

        const wasm_valtype_vec_t* parameterTypes = wasm_functype_params(functionType);
        if (!parameterTypes || (parameterTypes->size > 0 && !parameterTypes->data) || parameterTypes->size != arguments.size()) {
            wasm_functype_delete(functionType);
            return InvocationResult::failure(WasmlineErrorCode::INVALID_PAYLOAD, "Raw export parameter count does not match.");
        }

        for (size_t index = 0; index < arguments.size(); ++index) {
            if (!matchesType(wasm_valtype_kind(parameterTypes->data[index]), arguments[index].type)) {
                wasm_functype_delete(functionType);
                return InvocationResult::failure(WasmlineErrorCode::INVALID_PAYLOAD, "Raw export parameter type does not match.");
            }
        }

        const wasm_valtype_vec_t* resultTypes = wasm_functype_results(functionType);
        const size_t resultCount = resultTypes ? resultTypes->size : 0;
        if (!resultTypes || (resultCount > 0 && !resultTypes->data)) {
            wasm_functype_delete(functionType);
            return InvocationResult::failure(WasmlineErrorCode::INVALID_PAYLOAD, "Raw export result type is not available.");
        }
        for (size_t index = 0; index < resultCount; ++index) {
            if (!isSupportedType(wasm_valtype_kind(resultTypes->data[index]))) {
                wasm_functype_delete(functionType);
                return InvocationResult::failure(WasmlineErrorCode::INVALID_PAYLOAD, "Raw export result type is not supported.");
            }
        }
        std::vector<wasmtime_val_t> callArguments(arguments.size());
        std::vector<wasmtime_val_t> callResults(resultCount);
        for (size_t index = 0; index < arguments.size(); ++index) {
            if (!toWasmtimeValue(arguments[index], &callArguments[index])) {
                wasm_functype_delete(functionType);
                return InvocationResult::failure(WasmlineErrorCode::INVALID_PAYLOAD, "Raw export parameter value is not supported.");
            }
        }

        wasm_trap_t* trap = nullptr;
        wasmtime_error_t* callError = wasmtime_func_call(context_, &exported.of.func, callArguments.data(), callArguments.size(),
                                                         callResults.data(), callResults.size(), &trap);
        wasm_functype_delete(functionType);

        if (callError) {
            std::string message = wasmtime::errorMessage(callError);
            wasmtime_error_delete(callError);
            unrootValues(callResults);
            return InvocationResult::failure(WasmlineErrorCode::INVALID_PAYLOAD, std::move(message));
        }
        if (trap) {
            std::string message = wasmtime::trapMessage(trap);
            wasm_trap_delete(trap);
            unrootValues(callResults);
            return InvocationResult::failure(WasmlineErrorCode::CORE_TRAP, std::move(message));
        }

        std::vector<RawValue> values;
        values.reserve(callResults.size());
        for (const auto& value : callResults) {
            RawValue rawValue;
            if (!toRawValue(value, &rawValue)) {
                unrootValues(callResults);
                return InvocationResult::failure(WasmlineErrorCode::INVALID_PAYLOAD, "Raw export result type is not supported.");
            }
            values.push_back(rawValue);
        }

        unrootValues(callResults);
        return InvocationResult::success(std::move(values));
    }

    bool RawModuleSession::matchesType(wasm_valkind_t actual, RawValue::Type expected) {
        switch (expected) {
        case RawValue::Type::I32:
            return actual == WASM_I32;
        case RawValue::Type::I64:
            return actual == WASM_I64;
        case RawValue::Type::F32:
            return actual == WASM_F32;
        case RawValue::Type::F64:
            return actual == WASM_F64;
        }
        return false;
    }

    bool RawModuleSession::isSupportedType(wasm_valkind_t actual) {
        return actual == WASM_I32 || actual == WASM_I64 || actual == WASM_F32 || actual == WASM_F64;
    }

    bool RawModuleSession::toWasmtimeValue(const RawValue& value, wasmtime_val_t* result) {
        if (!result) return false;
        switch (value.type) {
        case RawValue::Type::I32:
            result->kind = WASMTIME_I32;
            result->of.i32 = value.data.i32;
            return true;
        case RawValue::Type::I64:
            result->kind = WASMTIME_I64;
            result->of.i64 = value.data.i64;
            return true;
        case RawValue::Type::F32:
            result->kind = WASMTIME_F32;
            result->of.f32 = value.data.f32;
            return true;
        case RawValue::Type::F64:
            result->kind = WASMTIME_F64;
            result->of.f64 = value.data.f64;
            return true;
        }
        return false;
    }

    bool RawModuleSession::toRawValue(const wasmtime_val_t& value, RawValue* result) {
        if (!result) return false;
        switch (value.kind) {
        case WASMTIME_I32:
            *result = RawValue::fromI32(value.of.i32);
            return true;
        case WASMTIME_I64:
            *result = RawValue::fromI64(value.of.i64);
            return true;
        case WASMTIME_F32:
            *result = RawValue::fromF32(value.of.f32);
            return true;
        case WASMTIME_F64:
            *result = RawValue::fromF64(value.of.f64);
            return true;
        default:
            return false;
        }
    }

} // namespace wasmline
