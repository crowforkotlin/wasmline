#pragma once
#include <string>
#include <vector>
#include <mutex> 
#include "wasm.h"
#include "wasmtime.h"

namespace crow {

    class WasmSession {
    public:
        // 构造函数只负责基础指针置空
        WasmSession(wasm_engine_t* engine, wasmtime_module_t* module, std::string id);
        ~WasmSession();

        // [新增] 初始化环境：注册函数、实例化、执行 _initialize (只执行一次)
        bool initialize();

        // [修改] 执行调用：只负责传参和运行 run_entry
        std::string callJson(const char* action, size_t actionLen, const char* json, size_t jsonLen);

        // [新增] 专用于二进制/Protobuf 调用，不返回默认的 "{}"
        std::string callProtobuf(const char* action, size_t actionLen, const char* data, size_t dataLen);

        // 数据区 (Host Functions 读写)
        const char* actionPtr = nullptr;
        size_t actionSize = 0;

        const char* inputPtr = nullptr;
        size_t inputSize = 0;

        std::string outputResult;

    private:
        std::string sessionId; // 标识符
        wasm_engine_t* engine = nullptr;
        wasmtime_module_t* module = nullptr;

        wasmtime_store_t* store = nullptr;
        wasmtime_context_t* context = nullptr;
        wasmtime_linker_t* linker = nullptr;
        wasmtime_instance_t instance; // 实例持有者

        bool isInitialized = false; // 标记是否初始化过
        std::mutex sessionMutex; // [新增] 线程锁，防止多线程同时操作同一个Session的内存

        // 【新增】Wasm 内存对象，用于 memcpy
        wasmtime_memory_t memory; 
        bool hasMemory = false;

        void registerHostFunctions();

        // [新增] 核心私有实现
        std::string internalCall(const char* act, size_t actLen, const char* in, size_t inLen);

        // Host Functions Callbacks
        static wasm_trap_t* host_get_action_size(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs, wasmtime_val_t* results, size_t nresults);
        static wasm_trap_t* host_get_json_size(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs, wasmtime_val_t* results, size_t nresults);
        static wasm_trap_t* host_read_input_byte(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs, wasmtime_val_t* results, size_t nresults);
        static wasm_trap_t* host_write_result_byte(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs, wasmtime_val_t* results, size_t nresults);
        static wasm_trap_t* host_copy_to_memory(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs, wasmtime_val_t* results, size_t nresults);
        static wasm_trap_t* host_read_from_memory(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs, wasmtime_val_t* results, size_t nresults);
    };
}