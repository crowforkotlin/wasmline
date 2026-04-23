/**
 * wasmline core constants
 *
 * 2026-01-01 19:50:09 Thu PM
 * @author crowforkotlin
 */
#pragma once

#include <string_view>

namespace wasmline {
    constexpr std::string_view kWasmlineInitExportName = "__wasmline_wasi_init";
    constexpr std::string_view kWasmlineEntryExportName = "__wasmline_wasi_entry";
}