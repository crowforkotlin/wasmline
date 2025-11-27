cmake_minimum_required(VERSION 3.18.0)
project(WasmLine)

# 设置 C++ 标准
set(CMAKE_CXX_STANDARD 17)

# 打印基础信息
message(STATUS "Build System: ${CMAKE_HOST_SYSTEM_NAME}")
message(STATUS "Source Dir : ${CMAKE_CURRENT_SOURCE_DIR}")

# ==============================================================================
#  macOS Build Configuration (Local Debugging)
# ==============================================================================
if(APPLE)
    message(NOTICE "--> Configuring for macOS (Local Debug)")

    # 假设你在 M1/M2/M3 Mac 上，使用 platforms/macos/aarch64
    # 如果是 Intel Mac，你需要下载 x86_64 的库并修改此处路径
    set(MAC_PLATFORM_DIR "${CMAKE_CURRENT_SOURCE_DIR}/platforms/macos/aarch64")

    # 检查库文件是否存在
    if(NOT EXISTS "${MAC_PLATFORM_DIR}/lib/libwasmtime.a")
        message(FATAL_ERROR "libwasmtime.a not found at ${MAC_PLATFORM_DIR}/lib/. Please check your path.")
    endif()

    # 设置头文件路径
    include_directories(${MAC_PLATFORM_DIR}/include)
    include_directories(${CMAKE_CURRENT_SOURCE_DIR}) # 包含当前的 .h

    # 创建可执行程序
    add_executable(
        wasmline_sample
        WasmtimeSample.cpp
        WasmtimeCore.cpp
    )

    # 链接库
    # macOS 上静态链接 Wasmtime 通常需要链接 pthread, dl, m
    target_link_libraries(
        wasmline_sample
        "${MAC_PLATFORM_DIR}/lib/libwasmtime.a"
        pthread
        dl
        m
    )
    
    message(NOTICE "--> Executable 'wasmline_mac' will be built.")
    
else()
    message(WARNING "Unsupported platform for this project configuration.")
endif()