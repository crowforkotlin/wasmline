cmake_minimum_required(VERSION 3.18.0)
project(wasmline)

# 设置 C++ 标准
set(CMAKE_CXX_STANDARD 17)

# Wasmtime version & variant (passed from Gradle or use defaults)
if(NOT DEFINED WASMTIME_VERSION)
    set(WASMTIME_VERSION "release-v45.0.5")
endif()
if(NOT DEFINED WASMTIME_VARIANT)
    set(WASMTIME_VARIANT "pulley")
endif()

# 设置Library库 (new versioned + variant-aware path)
get_filename_component(ROOT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../" ABSOLUTE)
get_filename_component(LIBRARY_DIR "${ROOT_DIR}/build/platforms/${WASMTIME_VERSION}/${WASMTIME_VARIANT}/android" ABSOLUTE)

# 打印信息
message(STATUS "Wasmline CMakeLogcat --> Build Architecture : ${ANDROID_ABI}")
message(NOTICE "Wasmline CMakeLogcat --> Source Dir : ${CMAKE_CURRENT_SOURCE_DIR}")
message(NOTICE "Wasmline CMakeLogcat --> Root Dir : ${ROOT_DIR}")
message(NOTICE "Wasmline CMakeLogcat --> Library Dir : ${LIBRARY_DIR}")
message(NOTICE "Wasmline CMakeLogcat --> Wasmtime Version : ${WASMTIME_VERSION}")
message(NOTICE "Wasmline CMakeLogcat --> Wasmtime Variant : ${WASMTIME_VARIANT}")

# 设置头文件路径 (wasmtime headers still needed for C++ API types)
include_directories(native/include)
include_directories(${LIBRARY_DIR}/${ANDROID_ABI}/include)
include_directories(${ROOT_DIR}/wasmline-core/include)
include_directories(${ROOT_DIR}/wasmline-core/include/extensions)

# 编译为静态库，供 JNI 复用
add_library(wasmline_core STATIC
        native/AndroidLogger.cpp
        ${ROOT_DIR}/wasmline-multiplatform/wasmline/src/jniMain/native/JniHostHandler.cpp
        ${ROOT_DIR}/wasmline-core/src/extensions/FileUtils.cpp
        ${ROOT_DIR}/wasmline-core/src/Api.cpp
        ${ROOT_DIR}/wasmline-core/src/Engine.cpp
        ${ROOT_DIR}/wasmline-core/src/Module.cpp
        ${ROOT_DIR}/wasmline-core/src/Session.cpp
)

# 编译为共享库 (桥接层，不包含 wasmtime 引擎)
add_library(wasmline SHARED ${ROOT_DIR}/wasmline-multiplatform/wasmline/src/jniMain/native/WasmlineJni.cpp)

# libwasmtime.so 由 engine 模块在运行时加载，
# 此处允许未解析的 wasmtime 符号在运行时由动态链接器解析。
target_link_options(wasmline PRIVATE "-Wl,--unresolved-symbols=ignore-in-shared-libs")

# Math & Dynamic linker (libwasmtime 已移除，由 engine 模块提供)
target_link_libraries(
        wasmline
        wasmline_core
        log
        m
        dl
)

# 强制关闭所有日志 (无论是 Debug 还是 Release)
# add_compile_definitions(DISABLE_WASM_LOGS)
