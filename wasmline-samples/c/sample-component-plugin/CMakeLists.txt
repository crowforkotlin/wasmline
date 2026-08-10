cmake_minimum_required(VERSION 3.20)

project(wasmline_component_c_fixture LANGUAGES C)

find_program(WIT_BINDGEN_EXECUTABLE NAMES wit-bindgen REQUIRED)
find_program(WASM_TOOLS_EXECUTABLE NAMES wasm-tools REQUIRED)

set(
    WASMLINE_WIT_DIR
    "${CMAKE_CURRENT_LIST_DIR}/../../../wasmline-multiplatform/wasmline-plugin-core/src/main/resources/META-INF/wasmline/wit/wasmline-rpc"
    CACHE PATH
    "Directory containing the canonical wasmline:rpc WIT world"
)
set(
    WASI_PREVIEW1_ADAPTER
    ""
    CACHE FILEPATH
    "Optional wasi_snapshot_preview1 reactor adapter"
)

set(WIT_WORLD "${WASMLINE_WIT_DIR}/world.wit")
if(NOT EXISTS "${WIT_WORLD}")
    message(FATAL_ERROR "Wasmline RPC WIT world does not exist: ${WIT_WORLD}")
endif()
if(WASI_PREVIEW1_ADAPTER AND NOT EXISTS "${WASI_PREVIEW1_ADAPTER}")
    message(FATAL_ERROR "WASI Preview 1 adapter does not exist: ${WASI_PREVIEW1_ADAPTER}")
endif()

function(require_pinned_tool_version executable expected)
    execute_process(
        COMMAND "${executable}" --version
        RESULT_VARIABLE status
        OUTPUT_VARIABLE output
        ERROR_VARIABLE error_output
        OUTPUT_STRIP_TRAILING_WHITESPACE
        ERROR_STRIP_TRAILING_WHITESPACE
    )
    if(NOT status EQUAL 0)
        message(FATAL_ERROR "Unable to query ${executable}: ${error_output}")
    endif()
    string(LENGTH "${expected}" expected_length)
    string(LENGTH "${output}" output_length)
    string(FIND "${output}" "${expected}" expected_position)
    set(version_matches FALSE)
    if(expected_position EQUAL 0)
        if(output_length EQUAL expected_length)
            set(version_matches TRUE)
        elseif(output_length GREATER expected_length)
            string(SUBSTRING "${output}" ${expected_length} 1 suffix_separator)
            if(suffix_separator STREQUAL " ")
                set(version_matches TRUE)
            endif()
        endif()
    endif()
    if(NOT version_matches)
        message(FATAL_ERROR "Expected '${expected}', but ${executable} reported '${output}'")
    endif()
endfunction()

require_pinned_tool_version("${WIT_BINDGEN_EXECUTABLE}" "wit-bindgen-cli 0.57.1")
require_pinned_tool_version("${WASM_TOOLS_EXECUTABLE}" "wasm-tools 1.255.0")

set(GENERATED_DIR "${CMAKE_CURRENT_BINARY_DIR}/generated")
set(GENERATED_SOURCE "${GENERATED_DIR}/plugin.c")
set(GENERATED_HEADER "${GENERATED_DIR}/plugin.h")
set(GENERATED_TYPE_OBJECT "${GENERATED_DIR}/plugin_component_type.o")

add_custom_command(
    OUTPUT
        "${GENERATED_SOURCE}"
        "${GENERATED_HEADER}"
        "${GENERATED_TYPE_OBJECT}"
    COMMAND "${CMAKE_COMMAND}" -E make_directory "${GENERATED_DIR}"
    COMMAND
        "${WIT_BINDGEN_EXECUTABLE}" c
        --world plugin
        --out-dir "${GENERATED_DIR}"
        "${WASMLINE_WIT_DIR}"
    DEPENDS "${WIT_WORLD}"
    COMMENT "Generating C bindings for wasmline:rpc@1.0.0"
    VERBATIM
)

set(CORE_WASM "${CMAKE_CURRENT_BINARY_DIR}/plugin.core.wasm")
add_custom_command(
    OUTPUT "${CORE_WASM}"
    COMMAND
        "${CMAKE_C_COMPILER}"
        -std=c17
        -mexec-model=reactor
        "-I${GENERATED_DIR}"
        "${GENERATED_SOURCE}"
        "${GENERATED_TYPE_OBJECT}"
        "${CMAKE_CURRENT_LIST_DIR}/src/plugin_impl.c"
        -o "${CORE_WASM}"
    DEPENDS
        "${GENERATED_SOURCE}"
        "${GENERATED_HEADER}"
        "${GENERATED_TYPE_OBJECT}"
        "${CMAKE_CURRENT_LIST_DIR}/src/plugin_impl.c"
    COMMENT "Compiling the C fixture to Core Wasm"
    VERBATIM
)

set(COMPONENT_WASM "${CMAKE_CURRENT_BINARY_DIR}/plugin.component.wasm")
set(COMPONENT_NEW_ARGUMENTS component new "${CORE_WASM}")
if(WASI_PREVIEW1_ADAPTER)
    list(APPEND COMPONENT_NEW_ARGUMENTS --adapt "wasi_snapshot_preview1=${WASI_PREVIEW1_ADAPTER}")
endif()
list(APPEND COMPONENT_NEW_ARGUMENTS -o "${COMPONENT_WASM}")

add_custom_command(
    OUTPUT "${COMPONENT_WASM}"
    COMMAND "${WASM_TOOLS_EXECUTABLE}" ${COMPONENT_NEW_ARGUMENTS}
    COMMAND "${WASM_TOOLS_EXECUTABLE}" validate "${COMPONENT_WASM}"
    COMMAND "${WASM_TOOLS_EXECUTABLE}" component wit "${COMPONENT_WASM}"
    DEPENDS "${CORE_WASM}"
    COMMENT "Creating and validating the C Component fixture"
    VERBATIM
)

add_custom_target(wasmline_component ALL DEPENDS "${COMPONENT_WASM}")
