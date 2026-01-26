cmake_minimum_required(VERSION 3.18.0)
project(wasmline)

# 设置 C++ 标准
set(CMAKE_CXX_STANDARD 17)

# 设置Library库
get_filename_component(ROOT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../" ABSOLUTE)
get_filename_component(LIBRARY_DIR "${ROOT_DIR}/platforms/android" ABSOLUTE)

# 打印信息
message(STATUS "Wasmline CMakeLogcat --> Build Architecture : ${ANDROID_ABI}")
message(NOTICE "Wasmline CMakeLogcat --> Source Dir : ${CMAKE_CURRENT_SOURCE_DIR}")
message(NOTICE "Wasmline CMakeLogcat --> Root Dir : ${ROOT_DIR}")
message(NOTICE "Wasmline CMakeLogcat --> Library Dir : ${LIBRARY_DIR}")

#  导入 Wasmtime 静态库 (Prebuilt)
add_library(libwasmtime STATIC IMPORTED)

# 设置libwasmtime library 路径
set_target_properties(libwasmtime PROPERTIES IMPORTED_LOCATION "${LIBRARY_DIR}/${ANDROID_ABI}/lib/libwasmtime.a")

# 设置头文件路径
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

# 编译为共享库
add_library(wasmline SHARED ${ROOT_DIR}/wasmline-multiplatform/wasmline/src/jniMain/native/WasmlineJni.cpp)

# Math & Dynamic linker
target_link_libraries(
        wasmline
        wasmline_core
        libwasmtime
        log
        m
        dl
)

# 强制关闭所有日志 (无论是 Debug 还是 Release)
# add_compile_definitions(DISABLE_WASM_LOGS)