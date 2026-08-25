/**
 * Provides direct calls to Core Wasm exports.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 */

#pragma once

#include <cstddef>
#include <cstdint>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

#include "wasmline/invocation/RawWasmTypes.h"
#include <wasmtime.h>

namespace wasmline {
    class RawSessionRegistry;

    /**
     * Provides direct calls to Core Wasm exports.
     *
     * Date: 2026-08-25
     * Author: crowforkotlin
     */
    class RawModuleSession {
    public:
        /** Creates a session for a compiled Core Wasm module. */
        RawModuleSession(wasm_engine_t* engine, wasmtime_module_t* module, std::string sessionKey);

        /** Releases the session resources. */
        ~RawModuleSession();

        /** Instantiates the module and prepares its call state. */
        bool initialize();

        /** Invokes an exported Core Wasm function. */
        InvocationResult invoke(std::string_view exportName, const std::vector<RawValue>& arguments);

        /** Returns reflected exports of a compiled module. */
        static std::vector<RawExportDefinition> describe(const wasmtime_module_t* module);

        /** Reads the current exported linear memory. */
        InvocationResult readMemory(uint64_t offset, uint64_t length, std::vector<uint8_t>* output);

        /** Writes bytes into the current exported linear memory. */
        InvocationResult writeMemory(uint64_t offset, const uint8_t* bytes, uint64_t length);

        /** Returns the current exported memory size in bytes or pages. */
        InvocationResult memorySize(bool pages) const;

        /** Grows the current exported memory and returns its previous page count. */
        InvocationResult growMemory(uint64_t deltaPages);

        /** Returns and clears the last structured failure raised by an import. */
        InvocationResult takeLastImportFailure();

    private:
        friend class RawSessionRegistry;

        std::string sessionKey_;
        wasm_engine_t* engine_;
        wasmtime_module_t* module_;
        wasmtime_store_t* store_ = nullptr;
        wasmtime_context_t* context_ = nullptr;
        wasmtime_linker_t* linker_ = nullptr;
        wasmtime_instance_t instance_{};
        bool initialized_ = false;
        bool importCallbackActive_ = false;
        std::string memoryExportName_ = "memory";
        /**
         * Owns one native import definition and its parent session pointer.
         *
         * Date: 2026-08-25
         * Author: crowforkotlin
         */
        struct ImportBinding {
            /** Parent session that receives the import callback. */
            RawModuleSession* session = nullptr;
            /** Declared import metadata. */
            RawImportDefinition definition;
        };

        mutable std::recursive_mutex mutex_;
        std::vector<std::unique_ptr<ImportBinding>> importBindings_;
        RawImportCallback importCallback_ = nullptr;
        RawImportBufferFree importBufferFree_ = nullptr;
        void* importCallbackUser_ = nullptr;
        RawImportUserFinalizer importUserFinalizer_ = nullptr;
        InvocationResult lastImportFailure_ = InvocationResult::success();

        static bool toWasmtimeValue(const RawValue& value, wasmtime_val_t* result);

        static bool toRawValue(const wasmtime_val_t& value, RawValue* result);

        static wasm_trap_t* importTrampoline(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs,
                                             wasmtime_val_t* results, size_t nresults);

        wasm_trap_t* invokeImport(const ImportBinding& binding, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs,
                                  wasmtime_val_t* results, size_t nresults);

        bool configureWasi();
        bool defineImport(const RawImportDefinition& definition);
        bool findMemory(wasmtime_memory_t* memory) const;
        bool initialize(const std::vector<RawImportDefinition>& imports, RawImportCallback callback, RawImportBufferFree bufferFree,
                        bool configureWasiImports, std::string memoryExportName);
        void adoptImportUser(void* callbackUser, RawImportUserFinalizer userFinalizer) noexcept;
        static RawFunctionSignature signatureOf(const wasm_functype_t* type, bool* supported);
        static wasm_trap_t* makeTrap(const std::string& message);
    };
} // namespace wasmline
