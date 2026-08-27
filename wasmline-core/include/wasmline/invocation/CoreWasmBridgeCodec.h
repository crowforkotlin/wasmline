/**
 * Defines private native bridge records for Core Wasm module sessions.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 */

#pragma once

#include <string>
#include <string_view>
#include <vector>

#include "wasmline/invocation/InvocationResult.h"
#include "wasmline/invocation/RawWasmTypes.h"

namespace wasmline {
    /**
     * Encodes Core Wasm metadata records shared by JNI and Kotlin/Native.
     *
     * Date: 2026-08-25
     * Author: crowforkotlin
     */
    class CoreWasmBridgeCodec {
    public:
        /** Decodes caller-provided raw import declarations. */
        static bool decodeImports(std::string_view input, std::vector<RawImportDefinition>* imports, std::string* error);

        /** Encodes the reflected export inventory of a compiled module. */
        static std::vector<uint8_t> encodeExports(const std::vector<RawExportDefinition>& exports);
    };
} // namespace wasmline
