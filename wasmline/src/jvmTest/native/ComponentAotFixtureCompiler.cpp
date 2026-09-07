#include <fstream>
#include <iostream>
#include <iterator>
#include <string>
#include <vector>

#include <wasmtime.h>
#include <wasmtime/component/component.h>

namespace {
    std::string errorMessage(wasmtime_error_t* error) {
        wasm_byte_vec_t message{};
        wasmtime_error_message(error, &message);
        std::string result(message.data, message.size);
        wasm_byte_vec_delete(&message);
        wasmtime_error_delete(error);
        return result;
    }
}

int main(int argc, char** argv) {
    if (argc != 3) {
        std::cerr << "usage: component-aot-fixture-compiler INPUT.component.wasm OUTPUT.cwasm\n";
        return 2;
    }

    std::ifstream input(argv[1], std::ios::binary);
    if (!input) {
        std::cerr << "unable to open input component\n";
        return 2;
    }
    const std::vector<uint8_t> bytes{std::istreambuf_iterator<char>(input), std::istreambuf_iterator<char>()};

    wasm_config_t* config = wasm_config_new();
    if (!config) return 2;
    wasmtime_config_wasm_component_model_set(config, true);
    wasmtime_config_wasm_gc_set(config, true);
    wasmtime_config_gc_support_set(config, true);
    wasmtime_config_wasm_reference_types_set(config, true);
    wasmtime_config_wasm_function_references_set(config, true);
    wasmtime_config_wasm_exceptions_set(config, true);
    wasmtime_config_wasm_simd_set(config, false);
    wasmtime_config_wasm_relaxed_simd_set(config, false);
    wasmtime_config_signals_based_traps_set(config, false);
    wasmtime_config_memory_guard_size_set(config, 0);
    wasmtime_config_max_wasm_stack_set(config, 512 * 1024);
    wasmtime_config_cranelift_opt_level_set(config, WASMTIME_OPT_LEVEL_NONE);
    wasmtime_config_cranelift_debug_verifier_set(config, false);

    wasm_engine_t* engine = wasm_engine_new_with_config(config);
    if (!engine) {
        std::cerr << "unable to create Wasmtime engine\n";
        return 1;
    }
    wasmtime_component_t* component = nullptr;
    if (wasmtime_error_t* error = wasmtime_component_new(engine, bytes.data(), bytes.size(), &component)) {
        std::cerr << errorMessage(error) << '\n';
        wasm_engine_delete(engine);
        return 1;
    }
    wasm_byte_vec_t serialized{};
    if (wasmtime_error_t* error = wasmtime_component_serialize(component, &serialized)) {
        std::cerr << errorMessage(error) << '\n';
        wasmtime_component_delete(component);
        wasm_engine_delete(engine);
        return 1;
    }

    std::ofstream output(argv[2], std::ios::binary | std::ios::trunc);
    output.write(serialized.data, static_cast<std::streamsize>(serialized.size));
    const bool written = output.good();
    wasm_byte_vec_delete(&serialized);
    wasmtime_component_delete(component);
    wasm_engine_delete(engine);
    return written ? 0 : 1;
}
