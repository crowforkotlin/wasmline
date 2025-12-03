cmake_minimum_required(VERSION 3.17)
project(WasmtimeSample)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# =============================================================
# 1. 路径配置
# =============================================================
set(ROOT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../..")
set(CORE_SRC_DIR "${ROOT_DIR}/wasmtime-cpp/src")
set(CORE_INC_DIR "${ROOT_DIR}/wasmtime-cpp/include")
set(PLATFORMS_DIR "${ROOT_DIR}/platforms")
set(WASM_RES_DIR "${ROOT_DIR}/wasm") # 你的 wasm 源文件目录

# =============================================================
# 2. 平台检测 & 库路径 (保持不变，此处省略部分细节，核心是下面的文件拷贝)
# =============================================================
if(WIN32)
    set(OS_DIR "windows")
    add_definitions(-DWIN32_LEAN_AND_MEAN)
    if(CMAKE_SIZEOF_VOID_P EQUAL 8)
        set(ARCH_DIR "x64")
    else()
        message(FATAL_ERROR "32-bit build is not supported.")
    endif()
    set(WASMTIME_HOME "${PLATFORMS_DIR}/${OS_DIR}/${ARCH_DIR}")
    set(WASMTIME_LIB_FILE "${WASMTIME_HOME}/lib/wasmtime.dll.lib")
    set(WASMTIME_DLL_FILE "${WASMTIME_HOME}/lib/wasmtime.dll")

elseif(APPLE)
    set(OS_DIR "mac")
    if(CMAKE_SYSTEM_PROCESSOR STREQUAL "arm64")
        set(ARCH_DIR "aarch64")
    else()
        set(ARCH_DIR "x86_64")
    endif()
    set(WASMTIME_HOME "${PLATFORMS_DIR}/${OS_DIR}/${ARCH_DIR}")
    set(WASMTIME_LIB_FILE "${WASMTIME_HOME}/lib/libwasmtime.dylib")
    set(WASMTIME_DLL_FILE "${WASMTIME_HOME}/lib/libwasmtime.dylib")

elseif(UNIX)
    set(OS_DIR "linux")
    if(CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64|arm64")
        set(ARCH_DIR "aarch64")
    else()
        set(ARCH_DIR "x86_64")
    endif()
    set(WASMTIME_HOME "${PLATFORMS_DIR}/${OS_DIR}/${ARCH_DIR}")
    set(WASMTIME_LIB_FILE "${WASMTIME_HOME}/lib/libwasmtime.so")
    set(WASMTIME_DLL_FILE "${WASMTIME_HOME}/lib/libwasmtime.so")
endif()

include_directories(${CORE_INC_DIR})
include_directories("${WASMTIME_HOME}/include")

# =============================================================
# 3. 源文件与目标
# =============================================================
set(SOURCE_FILES
    WasmtimeSample.cpp
    DesktopLogger.cpp
    ${CORE_SRC_DIR}/WasmEngine.cpp
    ${CORE_SRC_DIR}/WasmModule.cpp
    ${CORE_SRC_DIR}/WasmSession.cpp
    ${CORE_SRC_DIR}/WasmApi.cpp
    ${CORE_SRC_DIR}/WasmFileUtils.cpp
)

add_executable(WasmtimeSample ${SOURCE_FILES})
target_link_libraries(WasmtimeSample PRIVATE ${WASMTIME_LIB_FILE})

if(UNIX AND NOT APPLE)
    target_link_libraries(WasmtimeSample PRIVATE pthread dl)
endif()

# =============================================================
# 4. 【核心修改】自动扫描并复制所有 .wasm 文件
# =============================================================

# 扫描 wasm 目录下的所有 .wasm 文件
file(GLOB ALL_WASM_FILES "${WASM_RES_DIR}/*.wasm")

message(STATUS "Found WASM files to copy: ${ALL_WASM_FILES}")

# 遍历每一个文件并创建复制命令
foreach(WASM_FILE ${ALL_WASM_FILES})
    get_filename_component(FILE_NAME ${WASM_FILE} NAME) # 获取文件名 (e.g., plugin.wasm)
    
    add_custom_command(TARGET WasmtimeSample POST_BUILD
        COMMAND ${CMAKE_COMMAND} -E copy_if_different
        "${WASM_FILE}"
        "$<TARGET_FILE_DIR:WasmtimeSample>/${FILE_NAME}"
        COMMENT "Copying ${FILE_NAME} to build directory..."
    )
endforeach()

# 复制运行时库
add_custom_command(TARGET WasmtimeSample POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
    "${WASMTIME_DLL_FILE}"
    "$<TARGET_FILE_DIR:WasmtimeSample>/"
    COMMENT "Copying runtime library..."
)