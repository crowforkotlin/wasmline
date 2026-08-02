/**
 * Defines Wasmline core constants.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */
#pragma once

#include <string_view>

namespace wasmline {
    constexpr std::string_view kWasmlineInitExportName = "__wasmline_wasi_init";
    constexpr std::string_view kWasmlineEntryExportName = "__wasmline_wasi_entry";
} // namespace wasmline
