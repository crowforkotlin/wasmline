/**
 * Implements Wasmtime error and trap messages.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */

#include "wasmtime/WasmtimeMessage.h"

namespace wasmline::wasmtime {
    std::string errorMessage(const wasmtime_error_t* error) {
        if (!error) return {};
        wasm_byte_vec_t message{};
        wasmtime_error_message(error, &message);
        std::string result(message.data ? message.data : "", message.size);
        wasm_byte_vec_delete(&message);
        return result;
    }

    std::string trapMessage(const wasm_trap_t* trap) {
        if (!trap) return {};
        wasm_byte_vec_t message{};
        wasm_trap_message(trap, &message);
        std::string result(message.data ? message.data : "", message.size);
        wasm_byte_vec_delete(&message);
        return result;
    }
} // namespace wasmline::wasmtime
