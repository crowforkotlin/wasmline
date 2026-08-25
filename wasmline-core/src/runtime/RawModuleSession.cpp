/**
 * Implements configurable direct calls to Core Wasm exports.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 */

#include "wasmline/runtime/RawModuleSession.h"

#include "wasmline/internal/logging/NativeLogger.h"
#include "wasmline/invocation/TypedInvocationCodec.h"
#include "wasmline/internal/wasi/WasiConfig.h"
#include "wasmline/internal/wasmtime/WasmtimeMessage.h"

#include <algorithm>
#include <cstring>
#include <exception>
#include <limits>
#include <unordered_map>
#include <unordered_set>

namespace wasmline {
    namespace {
        // Keep bridge kind values independent from Wasmtime's C enum order.
        constexpr uint8_t kFunction = 0;
        constexpr uint8_t kMemory = 1;
        constexpr uint8_t kGlobal = 2;
        constexpr uint8_t kTable = 3;
        constexpr uint8_t kUnknown = 4;
        constexpr const char* kConcurrentTrapMessage = "__wasmline_concurrent_access__";

        using ImportBufferOwner = std::unique_ptr<char, RawImportBufferFree>;

        /**
         * Restores the raw import callback state on every exit path.
         *
         * Date: 2026-08-25
         * Author: crowforkotlin
         */
        class ImportCallbackGuard final {
        public:
            /** Marks the callback active until this guard is destroyed. */
            explicit ImportCallbackGuard(bool& active) noexcept : active_(active) { active_ = true; }

            /** Restores the inactive callback state. */
            ~ImportCallbackGuard() { active_ = false; }

            ImportCallbackGuard(const ImportCallbackGuard&) = delete;
            ImportCallbackGuard& operator=(const ImportCallbackGuard&) = delete;

        private:
            bool& active_;
        };

        void unrootValues(std::vector<wasmtime_val_t>& values) {
            for (auto& value : values)
                wasmtime_val_unroot(&value);
        }

        std::string nameText(const wasm_name_t* name) {
            if (!name || !name->data || name->size == 0) return {};
            return std::string(reinterpret_cast<const char*>(name->data), name->size);
        }

        bool typeVector(const wasm_valtype_vec_t* types, RawFunctionSignature* signature, bool parameters) {
            if (!types || (types->size > 0 && !types->data) || !signature) return false;
            auto& destination = parameters ? signature->parameters : signature->results;
            destination.reserve(types->size);
            for (size_t index = 0; index < types->size; ++index) {
                const auto kind = wasm_valtype_kind(types->data[index]);
                switch (kind) {
                case WASM_I32:
                    destination.push_back(RawValue::Type::I32);
                    break;
                case WASM_I64:
                    destination.push_back(RawValue::Type::I64);
                    break;
                case WASM_F32:
                    destination.push_back(RawValue::Type::F32);
                    break;
                case WASM_F64:
                    destination.push_back(RawValue::Type::F64);
                    break;
                default:
                    return false;
                }
            }
            return true;
        }

        bool sameSignature(const RawFunctionSignature& expected, const wasm_functype_t* actual) {
            if (!actual) return false;
            RawFunctionSignature reflected;
            if (!typeVector(wasm_functype_params(actual), &reflected, true) ||
                !typeVector(wasm_functype_results(actual), &reflected, false)) {
                return false;
            }
            return reflected.parameters == expected.parameters && reflected.results == expected.results;
        }

        std::vector<wasm_valtype_t*> makeTypes(const std::vector<RawValue::Type>& types) {
            std::vector<wasm_valtype_t*> values;
            values.reserve(types.size());
            for (const auto type : types) {
                wasm_valkind_t kind = WASM_I32;
                switch (type) {
                case RawValue::Type::I32:
                    kind = WASM_I32;
                    break;
                case RawValue::Type::I64:
                    kind = WASM_I64;
                    break;
                case RawValue::Type::F32:
                    kind = WASM_F32;
                    break;
                case RawValue::Type::F64:
                    kind = WASM_F64;
                    break;
                }
                values.push_back(wasm_valtype_new(kind));
            }
            return values;
        }

        wasm_functype_t* makeFunctionType(const RawFunctionSignature& signature) {
            auto parameters = makeTypes(signature.parameters);
            auto results = makeTypes(signature.results);
            wasm_valtype_vec_t parameterVector;
            wasm_valtype_vec_t resultVector;
            wasm_valtype_vec_new(&parameterVector, parameters.size(), parameters.data());
            wasm_valtype_vec_new(&resultVector, results.size(), results.data());
            return wasm_functype_new(&parameterVector, &resultVector);
        }

        std::string resultMessage(const InvocationResult& result) {
            return result.message().empty() ? "Raw import callback failed." : result.message();
        }

        bool isConcurrentTrapMessage(const std::string& message) {
            return message == kConcurrentTrapMessage;
        }
    } // namespace

    RawModuleSession::RawModuleSession(wasm_engine_t* engine, wasmtime_module_t* module, std::string sessionKey)
        : sessionKey_(std::move(sessionKey)), engine_(engine ? wasmtime_engine_clone(engine) : nullptr),
          module_(module ? wasmtime_module_clone(module) : nullptr) {
        if (!engine_) {
            LOGE("[Wasmtime] RawModuleSession -> Engine is null: %s", sessionKey_.c_str());
            return;
        }
        store_ = wasmtime_store_new(engine_, this, nullptr);
        if (!store_) {
            LOGE("[Wasmtime] RawModuleSession -> Failed to create store: %s", sessionKey_.c_str());
            return;
        }
        context_ = wasmtime_store_context(store_);
        linker_ = wasmtime_linker_new(engine_);
        if (!context_ || !linker_) {
            LOGE("[Wasmtime] RawModuleSession -> Failed to create runtime state: %s", sessionKey_.c_str());
        }
    }

    RawModuleSession::~RawModuleSession() {
        std::lock_guard<std::recursive_mutex> lock(mutex_);
        importBindings_.clear();
        if (importUserFinalizer_ && importCallbackUser_) importUserFinalizer_(importCallbackUser_);
        importCallbackUser_ = nullptr;
        if (linker_) wasmtime_linker_delete(linker_);
        if (store_) wasmtime_store_delete(store_);
        if (module_) wasmtime_module_delete(module_);
        if (engine_) wasm_engine_delete(engine_);
    }

    bool RawModuleSession::initialize() {
        return initialize({}, nullptr, nullptr, true, "memory");
    }

    bool RawModuleSession::initialize(const std::vector<RawImportDefinition>& imports, RawImportCallback callback,
                                      RawImportBufferFree bufferFree, bool configureWasiImports, std::string memoryExportName) {
        std::unique_lock<std::recursive_mutex> lock(mutex_, std::try_to_lock);
        if (!lock.owns_lock()) {
            lastImportFailure_ = InvocationResult::failure(WasmlineErrorCode::CONCURRENT_ACCESS, "Raw module session is busy.");
            return false;
        }
        if (initialized_) return true;
        if (!store_ || !context_ || !linker_ || !module_) {
            LOGE("[Wasmtime] RawModuleSession -> Invalid state before initialization: %s", sessionKey_.c_str());
            return false;
        }
        importCallback_ = callback;
        importBufferFree_ = bufferFree;
        memoryExportName_ = std::move(memoryExportName);

        std::unordered_map<std::string, const RawImportDefinition*> definitions;
        for (const auto& definition : imports) {
            const std::string id = definition.module + "\n" + definition.name;
            if (definitions.find(id) != definitions.end()) {
                lastImportFailure_ =
                    InvocationResult::failure(WasmlineErrorCode::IMPORT_SIGNATURE_MISMATCH, "Duplicate raw import definition.");
                return false;
            }
            definitions.emplace(id, &definition);
        }

        if (configureWasiImports && !configureWasi()) return false;

        wasm_importtype_vec_t importTypes;
        wasmtime_module_imports(module_, &importTypes);
        std::unordered_set<std::string> consumedDefinitions;
        for (size_t index = 0; index < importTypes.size; ++index) {
            const auto* importType = importTypes.data[index];
            const auto* moduleName = wasm_importtype_module(importType);
            const auto* fieldName = wasm_importtype_name(importType);
            const std::string moduleText = nameText(moduleName);
            const std::string fieldText = nameText(fieldName);
            const auto* externalType = wasm_importtype_type(importType);
            if (!externalType || wasm_externtype_kind(externalType) != WASM_EXTERN_FUNC) {
                // WASI definitions may include non-function imports. The
                // generic raw contract intentionally leaves those unsupported.
                if (configureWasiImports && moduleText == "wasi_snapshot_preview1") continue;
                wasm_importtype_vec_delete(&importTypes);
                lastImportFailure_ = InvocationResult::failure(WasmlineErrorCode::IMPORT_SIGNATURE_MISMATCH,
                                                               "Only scalar function imports are supported by RAW_EXPORT.");
                return false;
            }
            const std::string id = moduleText + "\n" + fieldText;
            const auto found = definitions.find(id);
            if (found == definitions.end()) {
                if (configureWasiImports && moduleText == "wasi_snapshot_preview1") continue;
                wasm_importtype_vec_delete(&importTypes);
                lastImportFailure_ = InvocationResult::failure(WasmlineErrorCode::IMPORT_MISSING,
                                                               "Required raw import is not registered: " + moduleText + "." + fieldText);
                return false;
            }
            const auto* functionType = wasm_externtype_as_functype_const(externalType);
            if (!functionType || !sameSignature(found->second->signature, functionType)) {
                wasm_importtype_vec_delete(&importTypes);
                lastImportFailure_ = InvocationResult::failure(WasmlineErrorCode::IMPORT_SIGNATURE_MISMATCH,
                                                               "Raw import signature does not match: " + moduleText + "." + fieldText);
                return false;
            }
            if (!defineImport(*found->second)) {
                wasm_importtype_vec_delete(&importTypes);
                return false;
            }
            consumedDefinitions.insert(id);
        }
        wasm_importtype_vec_delete(&importTypes);

        for (const auto& entry : definitions) {
            if (consumedDefinitions.find(entry.first) == consumedDefinitions.end()) {
                lastImportFailure_ = InvocationResult::failure(WasmlineErrorCode::IMPORT_SIGNATURE_MISMATCH,
                                                               "Raw import is not declared by the module: " + entry.first);
                return false;
            }
        }

        wasm_trap_t* trap = nullptr;
        wasmtime_error_t* instantiateError = wasmtime_linker_instantiate(linker_, context_, module_, &instance_, &trap);
        if (instantiateError) {
            const std::string message = wasmtime::errorMessage(instantiateError);
            wasmtime_error_delete(instantiateError);
            if (trap) wasm_trap_delete(trap);
            lastImportFailure_ = InvocationResult::failure(WasmlineErrorCode::INSTANTIATION_FAILED, message);
            return false;
        }
        if (trap) {
            const std::string message = wasmtime::trapMessage(trap);
            wasm_trap_delete(trap);
            lastImportFailure_ = InvocationResult::failure(WasmlineErrorCode::WASM_TRAP, message);
            return false;
        }
        initialized_ = true;
        return true;
    }

    bool RawModuleSession::configureWasi() {
        wasmtime_error_t* defineError = wasmtime_linker_define_wasi(linker_);
        if (defineError) {
            lastImportFailure_ = InvocationResult::failure(WasmlineErrorCode::INSTANTIATION_FAILED, wasmtime::errorMessage(defineError));
            wasmtime_error_delete(defineError);
            return false;
        }
        wasi_config_t* wasi = wasi_config_new();
        if (!wasi) {
            lastImportFailure_ = InvocationResult::failure(WasmlineErrorCode::INSTANTIATION_FAILED, "Failed to create WASI configuration.");
            return false;
        }
        wasi::configure(wasi, "[Wasmtime-Wasi] raw export logger");
        wasmtime_error_t* wasiError = wasmtime_context_set_wasi(context_, wasi);
        if (wasiError) {
            lastImportFailure_ = InvocationResult::failure(WasmlineErrorCode::INSTANTIATION_FAILED, wasmtime::errorMessage(wasiError));
            wasmtime_error_delete(wasiError);
            wasi_config_delete(wasi);
            return false;
        }
        return true;
    }

    bool RawModuleSession::defineImport(const RawImportDefinition& definition) {
        if (!importCallback_) {
            lastImportFailure_ = InvocationResult::failure(WasmlineErrorCode::IMPORT_MISSING, "A raw import callback is not registered.");
            return false;
        }
        if (!importBufferFree_) {
            lastImportFailure_ =
                InvocationResult::failure(WasmlineErrorCode::IMPORT_MISSING, "A raw import response buffer finalizer is not registered.");
            return false;
        }
        auto binding = std::make_unique<ImportBinding>();
        binding->session = this;
        binding->definition = definition;
        wasm_functype_t* functionType = makeFunctionType(definition.signature);
        if (!functionType) {
            lastImportFailure_ =
                InvocationResult::failure(WasmlineErrorCode::IMPORT_SIGNATURE_MISMATCH, "Could not construct raw import signature.");
            return false;
        }
        wasmtime_error_t* error =
            wasmtime_linker_define_func(linker_, definition.module.data(), definition.module.size(), definition.name.data(),
                                        definition.name.size(), functionType, &RawModuleSession::importTrampoline, binding.get(), nullptr);
        wasm_functype_delete(functionType);
        if (error) {
            lastImportFailure_ = InvocationResult::failure(WasmlineErrorCode::IMPORT_SIGNATURE_MISMATCH, wasmtime::errorMessage(error));
            wasmtime_error_delete(error);
            return false;
        }
        importBindings_.push_back(std::move(binding));
        return true;
    }

    std::vector<RawExportDefinition> RawModuleSession::describe(const wasmtime_module_t* module) {
        std::vector<RawExportDefinition> result;
        if (!module) return result;
        wasm_exporttype_vec_t exportTypes;
        wasmtime_module_exports(module, &exportTypes);
        result.reserve(exportTypes.size);
        for (size_t index = 0; index < exportTypes.size; ++index) {
            const auto* exportType = exportTypes.data[index];
            RawExportDefinition definition;
            definition.name = nameText(wasm_exporttype_name(exportType));
            const auto* externalType = wasm_exporttype_type(exportType);
            const auto kind = externalType ? wasm_externtype_kind(externalType) : kUnknown;
            switch (kind) {
            case WASM_EXTERN_FUNC:
                definition.kind = kFunction;
                break;
            case WASM_EXTERN_MEMORY:
                definition.kind = kMemory;
                break;
            case WASM_EXTERN_GLOBAL:
                definition.kind = kGlobal;
                break;
            case WASM_EXTERN_TABLE:
                definition.kind = kTable;
                break;
            default:
                definition.kind = kUnknown;
                break;
            }
            if (externalType && kind == WASM_EXTERN_FUNC) {
                const auto* functionType = wasm_externtype_as_functype_const(externalType);
                bool supported = false;
                definition.signature = signatureOf(functionType, &supported);
                definition.hasSignature = supported;
            }
            result.push_back(std::move(definition));
        }
        wasm_exporttype_vec_delete(&exportTypes);
        return result;
    }

    RawFunctionSignature RawModuleSession::signatureOf(const wasm_functype_t* type, bool* supported) {
        RawFunctionSignature signature;
        bool valid = typeVector(type ? wasm_functype_params(type) : nullptr, &signature, true) &&
                     typeVector(type ? wasm_functype_results(type) : nullptr, &signature, false);
        if (supported) *supported = valid;
        return signature;
    }

    InvocationResult RawModuleSession::invoke(std::string_view exportName, const std::vector<RawValue>& arguments) {
        std::unique_lock<std::recursive_mutex> lock(mutex_, std::try_to_lock);
        if (!lock.owns_lock()) {
            return InvocationResult::failure(WasmlineErrorCode::CONCURRENT_ACCESS, "Raw module session already has an active operation.");
        }
        lastImportFailure_ = InvocationResult::success();
        if (!initialized_) return InvocationResult::failure(WasmlineErrorCode::SESSION_CLOSED, "Raw module session is not initialized.");
        if (importCallbackActive_)
            return InvocationResult::failure(WasmlineErrorCode::REENTRANT_CALL, "Raw import callback cannot reenter its session.");
        if (exportName.empty()) return InvocationResult::failure(WasmlineErrorCode::EXPORT_NOT_FOUND, "Raw export name is empty.");

        wasmtime_extern_t exported{};
        if (!wasmtime_instance_export_get(context_, &instance_, exportName.data(), exportName.size(), &exported)) {
            return InvocationResult::failure(WasmlineErrorCode::EXPORT_NOT_FOUND, "Raw export is not available.");
        }
        if (exported.kind != WASMTIME_EXTERN_FUNC) {
            return InvocationResult::failure(WasmlineErrorCode::EXPORT_KIND_MISMATCH, "Raw export is not a function.");
        }
        wasm_functype_t* functionType = wasmtime_func_type(context_, &exported.of.func);
        if (!functionType)
            return InvocationResult::failure(WasmlineErrorCode::RESULT_TYPE_UNSUPPORTED, "Raw export type is not available.");

        bool supported = false;
        const RawFunctionSignature signature = signatureOf(functionType, &supported);
        if (!supported) {
            wasm_functype_delete(functionType);
            return InvocationResult::failure(WasmlineErrorCode::RESULT_TYPE_UNSUPPORTED, "Raw export uses an unsupported value type.");
        }
        if (arguments.size() != signature.parameters.size()) {
            wasm_functype_delete(functionType);
            return InvocationResult::failure(WasmlineErrorCode::ARGUMENT_COUNT_MISMATCH, "Raw export parameter count does not match.");
        }
        for (size_t index = 0; index < arguments.size(); ++index) {
            if (arguments[index].type != signature.parameters[index]) {
                wasm_functype_delete(functionType);
                return InvocationResult::failure(WasmlineErrorCode::ARGUMENT_TYPE_MISMATCH, "Raw export parameter type does not match.");
            }
        }

        std::vector<wasmtime_val_t> callArguments(arguments.size());
        std::vector<wasmtime_val_t> callResults(signature.results.size());
        for (size_t index = 0; index < arguments.size(); ++index) {
            if (!toWasmtimeValue(arguments[index], &callArguments[index])) {
                wasm_functype_delete(functionType);
                return InvocationResult::failure(WasmlineErrorCode::ARGUMENT_TYPE_MISMATCH, "Raw export argument is unsupported.");
            }
        }
        wasm_trap_t* trap = nullptr;
        wasmtime_error_t* callError = wasmtime_func_call(context_, &exported.of.func, callArguments.data(), callArguments.size(),
                                                         callResults.data(), callResults.size(), &trap);
        wasm_functype_delete(functionType);
        if (callError) {
            const std::string message = wasmtime::errorMessage(callError);
            wasmtime_error_delete(callError);
            unrootValues(callResults);
            if (!lastImportFailure_.isSuccess()) {
                InvocationResult failure = lastImportFailure_;
                lastImportFailure_ = InvocationResult::success();
                return failure;
            }
            if (isConcurrentTrapMessage(message)) {
                return InvocationResult::failure(WasmlineErrorCode::CONCURRENT_ACCESS,
                                                 "Raw module session already has an active operation.");
            }
            return InvocationResult::failure(WasmlineErrorCode::WASM_TRAP, message);
        }
        if (trap) {
            const std::string message = wasmtime::trapMessage(trap);
            wasm_trap_delete(trap);
            unrootValues(callResults);
            if (!lastImportFailure_.isSuccess()) {
                InvocationResult failure = lastImportFailure_;
                lastImportFailure_ = InvocationResult::success();
                return failure;
            }
            if (isConcurrentTrapMessage(message)) {
                return InvocationResult::failure(WasmlineErrorCode::CONCURRENT_ACCESS,
                                                 "Raw module session already has an active operation.");
            }
            return InvocationResult::failure(WasmlineErrorCode::WASM_TRAP, message);
        }

        std::vector<RawValue> values;
        values.reserve(callResults.size());
        for (const auto& value : callResults) {
            RawValue rawValue;
            if (!toRawValue(value, &rawValue)) {
                unrootValues(callResults);
                return InvocationResult::failure(WasmlineErrorCode::RESULT_TYPE_UNSUPPORTED, "Raw export result type is unsupported.");
            }
            values.push_back(rawValue);
        }
        unrootValues(callResults);
        return InvocationResult::success(std::move(values));
    }

    wasm_trap_t* RawModuleSession::importTrampoline(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs,
                                                    wasmtime_val_t* results, size_t nresults) {
        auto* binding = static_cast<ImportBinding*>(env);
        if (!binding || !binding->session) return wasmtime_trap_new("Raw import session is unavailable", 35);
        return binding->session->invokeImport(*binding, caller, args, nargs, results, nresults);
    }

    wasm_trap_t* RawModuleSession::invokeImport(const ImportBinding& binding, wasmtime_caller_t*, const wasmtime_val_t* args, size_t nargs,
                                                wasmtime_val_t* results, size_t nresults) {
        std::unique_lock<std::recursive_mutex> lock(mutex_, std::try_to_lock);
        if (!lock.owns_lock()) return makeTrap(kConcurrentTrapMessage);
        if (!importCallback_) {
            lastImportFailure_ = InvocationResult::failure(WasmlineErrorCode::IMPORT_HANDLER_FAILED, "Raw import callback is unavailable.");
            return makeTrap(lastImportFailure_.message());
        }
        if (importCallbackActive_) {
            lastImportFailure_ =
                InvocationResult::failure(WasmlineErrorCode::REENTRANT_CALL, "Nested raw import callbacks are not supported.");
            return makeTrap(lastImportFailure_.message());
        }
        if (nargs != binding.definition.signature.parameters.size() || nresults != binding.definition.signature.results.size() ||
            (nargs > 0 && !args) || (nresults > 0 && !results)) {
            lastImportFailure_ = InvocationResult::failure(WasmlineErrorCode::IMPORT_SIGNATURE_MISMATCH,
                                                           "Raw import callback slots do not match its declaration.");
            return makeTrap(lastImportFailure_.message());
        }

        ImportCallbackGuard callbackGuard(importCallbackActive_);
        try {
            std::vector<RawValue> rawArguments;
            rawArguments.reserve(nargs);
            for (size_t index = 0; index < nargs; ++index) {
                RawValue value;
                if (!toRawValue(args[index], &value)) {
                    lastImportFailure_ =
                        InvocationResult::failure(WasmlineErrorCode::ARGUMENT_TYPE_MISMATCH, "Raw import argument type is unsupported.");
                    return makeTrap(lastImportFailure_.message());
                }
                rawArguments.push_back(value);
            }
            const std::vector<uint8_t> argumentCarrier = TypedInvocationCodec::encodeRawArguments(rawArguments);
            if (argumentCarrier.empty()) {
                lastImportFailure_ = InvocationResult::failure(WasmlineErrorCode::IMPORT_HANDLER_FAILED,
                                                               "Raw import argument carrier could not be encoded.");
                return makeTrap(lastImportFailure_.message());
            }
            size_t responseLength = 0;
            char* response =
                importCallback_(importCallbackUser_, sessionKey_.data(), binding.definition.module.data(), binding.definition.module.size(),
                                binding.definition.name.data(), binding.definition.name.size(), argumentCarrier.data(),
                                argumentCarrier.size(), &responseLength);
            ImportBufferOwner responseOwner(response, importBufferFree_);
            InvocationResult callbackResult =
                InvocationResult::failure(WasmlineErrorCode::IMPORT_HANDLER_FAILED, "Raw import callback returned no response.");
            if (response) {
                std::string decodeError;
                if (!TypedInvocationCodec::decodeRawResult(std::string_view(response, responseLength), &callbackResult, &decodeError)) {
                    callbackResult =
                        InvocationResult::failure(WasmlineErrorCode::IMPORT_HANDLER_FAILED,
                                                  decodeError.empty() ? "Raw import callback response is malformed." : decodeError);
                }
            }
            if (!callbackResult.isSuccess()) {
                lastImportFailure_ = callbackResult;
                return makeTrap(resultMessage(callbackResult));
            }
            if (callbackResult.values().size() != binding.definition.signature.results.size()) {
                lastImportFailure_ = InvocationResult::failure(WasmlineErrorCode::IMPORT_SIGNATURE_MISMATCH,
                                                               "Raw import result count does not match its declaration.");
                return makeTrap(lastImportFailure_.message());
            }
            for (size_t index = 0; index < callbackResult.values().size(); ++index) {
                if (callbackResult.values()[index].type != binding.definition.signature.results[index] ||
                    !toWasmtimeValue(callbackResult.values()[index], &results[index])) {
                    lastImportFailure_ = InvocationResult::failure(WasmlineErrorCode::IMPORT_SIGNATURE_MISMATCH,
                                                                   "Raw import result type does not match its declaration.");
                    return makeTrap(lastImportFailure_.message());
                }
            }
            return nullptr;
        } catch (const std::exception& error) {
            lastImportFailure_ = InvocationResult::failure(WasmlineErrorCode::IMPORT_HANDLER_FAILED, error.what());
        } catch (...) {
            lastImportFailure_ =
                InvocationResult::failure(WasmlineErrorCode::IMPORT_HANDLER_FAILED, "Raw import callback failed unexpectedly.");
        }
        return makeTrap(lastImportFailure_.message());
    }

    bool RawModuleSession::findMemory(wasmtime_memory_t* memory) const {
        if (!memory || !initialized_ || !context_ || memoryExportName_.empty()) return false;
        wasmtime_extern_t external{};
        if (!wasmtime_instance_export_get(context_, &instance_, memoryExportName_.data(), memoryExportName_.size(), &external) ||
            external.kind != WASMTIME_EXTERN_MEMORY) {
            return false;
        }
        *memory = external.of.memory;
        return true;
    }

    InvocationResult RawModuleSession::readMemory(uint64_t offset, uint64_t length, std::vector<uint8_t>* output) {
        std::unique_lock<std::recursive_mutex> lock(mutex_, std::try_to_lock);
        if (!lock.owns_lock()) {
            return InvocationResult::failure(WasmlineErrorCode::CONCURRENT_ACCESS, "Raw module session already has an active operation.");
        }
        if (!output) return InvocationResult::failure(WasmlineErrorCode::TRANSPORT_FAILURE, "Memory output is null.");
        wasmtime_memory_t memory{};
        if (!findMemory(&memory))
            return InvocationResult::failure(WasmlineErrorCode::MEMORY_OUT_OF_BOUNDS, "Exported linear memory is unavailable.");
        const size_t size = wasmtime_memory_data_size(context_, &memory);
        if (offset > size || length > size - offset || length > static_cast<uint64_t>(std::numeric_limits<size_t>::max())) {
            return InvocationResult::failure(WasmlineErrorCode::MEMORY_OUT_OF_BOUNDS, "Linear memory read is out of bounds.");
        }
        output->resize(static_cast<size_t>(length));
        if (length > 0) std::memcpy(output->data(), wasmtime_memory_data(context_, &memory) + offset, static_cast<size_t>(length));
        return InvocationResult::success();
    }

    InvocationResult RawModuleSession::writeMemory(uint64_t offset, const uint8_t* bytes, uint64_t length) {
        std::unique_lock<std::recursive_mutex> lock(mutex_, std::try_to_lock);
        if (!lock.owns_lock()) {
            return InvocationResult::failure(WasmlineErrorCode::CONCURRENT_ACCESS, "Raw module session already has an active operation.");
        }
        wasmtime_memory_t memory{};
        if (!findMemory(&memory))
            return InvocationResult::failure(WasmlineErrorCode::MEMORY_OUT_OF_BOUNDS, "Exported linear memory is unavailable.");
        const size_t size = wasmtime_memory_data_size(context_, &memory);
        if ((length > 0 && !bytes) || offset > size || length > size - offset) {
            return InvocationResult::failure(WasmlineErrorCode::MEMORY_OUT_OF_BOUNDS, "Linear memory write is out of bounds.");
        }
        if (length > 0) std::memcpy(wasmtime_memory_data(context_, &memory) + offset, bytes, static_cast<size_t>(length));
        return InvocationResult::success();
    }

    InvocationResult RawModuleSession::memorySize(bool pages) const {
        std::unique_lock<std::recursive_mutex> lock(mutex_, std::try_to_lock);
        if (!lock.owns_lock()) {
            return InvocationResult::failure(WasmlineErrorCode::CONCURRENT_ACCESS, "Raw module session already has an active operation.");
        }
        wasmtime_memory_t memory{};
        if (!findMemory(&memory))
            return InvocationResult::failure(WasmlineErrorCode::MEMORY_OUT_OF_BOUNDS, "Exported linear memory is unavailable.");
        return InvocationResult::success({RawValue::fromI64(
            static_cast<int64_t>(pages ? wasmtime_memory_size(context_, &memory) : wasmtime_memory_data_size(context_, &memory)))});
    }

    InvocationResult RawModuleSession::growMemory(uint64_t deltaPages) {
        std::unique_lock<std::recursive_mutex> lock(mutex_, std::try_to_lock);
        if (!lock.owns_lock()) {
            return InvocationResult::failure(WasmlineErrorCode::CONCURRENT_ACCESS, "Raw module session already has an active operation.");
        }
        wasmtime_memory_t memory{};
        if (!findMemory(&memory))
            return InvocationResult::failure(WasmlineErrorCode::MEMORY_OUT_OF_BOUNDS, "Exported linear memory is unavailable.");
        uint64_t previous = 0;
        wasmtime_error_t* error = wasmtime_memory_grow(context_, &memory, deltaPages, &previous);
        if (error) {
            const std::string message = wasmtime::errorMessage(error);
            wasmtime_error_delete(error);
            return InvocationResult::failure(WasmlineErrorCode::MEMORY_OUT_OF_BOUNDS, message);
        }
        return InvocationResult::success({RawValue::fromI64(static_cast<int64_t>(previous))});
    }

    InvocationResult RawModuleSession::takeLastImportFailure() {
        std::unique_lock<std::recursive_mutex> lock(mutex_, std::try_to_lock);
        if (!lock.owns_lock()) {
            return InvocationResult::failure(WasmlineErrorCode::CONCURRENT_ACCESS, "Raw module session already has an active operation.");
        }
        InvocationResult result = lastImportFailure_;
        lastImportFailure_ = InvocationResult::success();
        return result;
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

    void RawModuleSession::adoptImportUser(void* callbackUser, RawImportUserFinalizer userFinalizer) noexcept {
        importCallbackUser_ = callbackUser;
        importUserFinalizer_ = userFinalizer;
    }

    wasm_trap_t* RawModuleSession::makeTrap(const std::string& message) {
        return wasmtime_trap_new(message.data(), message.size());
    }
} // namespace wasmline
