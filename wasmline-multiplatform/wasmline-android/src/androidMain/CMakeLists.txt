cmake_minimum_required(VERSION 3.18.0)
project(wasmline)

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)

# Size optimization: each function/data in its own section so the linker can
# garbage-collect unused ones.  --strip-debug removes DWARF sections but keeps
# the dynamic symbol table (.dynsym) intact for crash stack traces.
add_compile_options(-ffunction-sections -fdata-sections)
add_link_options(
    -Wl,--gc-sections
    -Wl,--strip-debug
    -Wl,--exclude-libs,ALL
)

# Wasmtime version & variant (passed from Gradle or use defaults)
if(NOT DEFINED WASMTIME_VERSION)
    set(WASMTIME_VERSION "release-v45.0.5")
endif()
if(NOT DEFINED WASMTIME_VARIANT)
    set(WASMTIME_VARIANT "pulley")
endif()

# Set Library directory path (new versioned + variant-aware path)
get_filename_component(ROOT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../" ABSOLUTE)
get_filename_component(LIBRARY_DIR "${ROOT_DIR}/build/platforms/${WASMTIME_VERSION}/${WASMTIME_VARIANT}/android" ABSOLUTE)

# Print diagnostic messages
message(STATUS "Wasmline CMakeLogcat --> Build Architecture : ${ANDROID_ABI}")
message(NOTICE "Wasmline CMakeLogcat --> Source Dir : ${CMAKE_CURRENT_SOURCE_DIR}")
message(NOTICE "Wasmline CMakeLogcat --> Root Dir : ${ROOT_DIR}")
message(NOTICE "Wasmline CMakeLogcat --> Library Dir : ${LIBRARY_DIR}")
message(NOTICE "Wasmline CMakeLogcat --> Wasmtime Version : ${WASMTIME_VERSION}")
message(NOTICE "Wasmline CMakeLogcat --> Wasmtime Variant : ${WASMTIME_VARIANT}")

# Set header include paths (wasmtime headers still needed for C++ API types)
include_directories(native/include)
include_directories(${LIBRARY_DIR}/${ANDROID_ABI}/include)
include_directories(${ROOT_DIR}/wasmline-core/include)
include_directories(${ROOT_DIR}/wasmline-core/src)

# Compile as a static library for JNI reuse
add_library(wasmline_core STATIC
        native/AndroidLogger.cpp
        ${ROOT_DIR}/wasmline-multiplatform/wasmline/src/jniMain/native/JniHostHandler.cpp
        ${ROOT_DIR}/wasmline-core/src/api/Api.cpp
        ${ROOT_DIR}/wasmline-core/src/runtime/Component.cpp
        ${ROOT_DIR}/wasmline-core/src/runtime/ComponentSession.cpp
        ${ROOT_DIR}/wasmline-core/src/runtime/Engine.cpp
        ${ROOT_DIR}/wasmline-core/src/runtime/Module.cpp
        ${ROOT_DIR}/wasmline-core/src/runtime/RawModuleSession.cpp
        ${ROOT_DIR}/wasmline-core/src/runtime/Session.cpp
        ${ROOT_DIR}/wasmline-core/src/value/ComponentValue.cpp
        ${ROOT_DIR}/wasmline-core/src/invocation/InvocationResult.cpp
        ${ROOT_DIR}/wasmline-core/src/invocation/TypedInvocationCodec.cpp
        ${ROOT_DIR}/wasmline-core/src/protocol/WasmlineProtocol.cpp
        ${ROOT_DIR}/wasmline-core/src/io/FileIO.cpp
        ${ROOT_DIR}/wasmline-core/src/wasmtime/WasmtimeMessage.cpp
        ${ROOT_DIR}/wasmline-core/src/wasi/WasiConfig.cpp
)

# Path to the Wasmtime static library (downloaded to build/platforms/ via the init script)
set(WASMTIME_STATIC_LIB "${LIBRARY_DIR}/${ANDROID_ABI}/lib/libwasmtime.a")

# Single shared library: Bridge layer + Wasmtime engine (statically linked, loaded once)
add_library(wasmline SHARED ${ROOT_DIR}/wasmline-multiplatform/wasmline/src/jniMain/native/WasmlineJni.cpp)

# --whole-archive ensures all symbols from libwasmtime.a are exported into libwasmline.so,
# preventing the linker from stripping Wasmtime functions not explicitly referenced by core.
target_link_libraries(
        wasmline
        wasmline_core
        -Wl,--whole-archive
        ${WASMTIME_STATIC_LIB}
        -Wl,--no-whole-archive
        log
        m
        dl
)

# Forcing all logs shut down (whether Debug or Release)
# add_compile_definitions(DISABLE_WASM_LOGS)
