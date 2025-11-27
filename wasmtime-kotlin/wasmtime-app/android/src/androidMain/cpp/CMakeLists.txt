cmake_minimum_required(VERSION 3.18.0)
project(WasmLine)

# 设置 C++ 标准
set(CMAKE_CXX_STANDARD 17)

# 设置Library库
get_filename_component(LIBRARY_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../../platforms/android" ABSOLUTE)

# 打印信息
message(NOTICE "Wasmline CMakeLogcat --> Build Architecture : ${ANDROID_ABI}")
message(NOTICE "Wasmline CMakeLogcat --> Source Dir : ${CMAKE_CURRENT_SOURCE_DIR}")
message(NOTICE "Wasmline CMakeLogcat --> Library Dir : ${LIBRARY_DIR}")

#  导入 Wasmtime 静态库 (Prebuilt)
add_library(libwasmtime STATIC IMPORTED)

# 设置libwasmtime library 路径
set_target_properties(libwasmtime PROPERTIES IMPORTED_LOCATION "${LIBRARY_DIR}/${ANDROID_ABI}/lib/libwasmtime.a")

# 设置头文件路径
include_directories(${LIBRARY_DIR}/${ANDROID_ABI}/include)

# 编译为静态库，供 JNI 复用
add_library(wasmtime_core STATIC WasmtimeCore.cpp)

# 编译为共享库
add_library(wasmline SHARED WasmtimeJni.cpp)

# Math & Dynamic linker
target_link_libraries(
        wasmline
        wasmtime_core
        libwasmtime
        log
        m
        dl
)

target_compile_definitions(
        wasmtime_core PRIVATE
        WASMTIME_FEATURE_COMPILER
        WASMTIME_FEATURE_WASI
)

#[[
# ========================================================
# Target 3: C++ Sample (仅在非 Android 或手动开启时构建)
# ========================================================
# 如果你想在 Windows 上跑这个，你需要配置 Windows 的 CMake 环境
if (NOT ANDROID)
    add_executable(
        wasm_sample
        WasmtimeSample.cpp
    )
    target_link_libraries(
        wasm_sample
        wasmtime_core
        libwasmtime
        # Windows 需要链接 ws2_32 等库，Linux 需要 pthread/dl/m
        # m dl pthread
    )
endif()
]]