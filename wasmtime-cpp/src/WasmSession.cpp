#include "WasmSession.h"
#include "WasmLog.h"
#include <cstring>
#include <vector>

namespace crow {

    static ptrdiff_t wasi_writer(void* data, const unsigned char* buffer, size_t size) {
        if (size > 0 && buffer) {
            std::string msg((const char*)buffer, size > 1024 ? 1024 : size);
            LOGI("[WASI] %s", msg.c_str());
        }
        return size;
    }

    WasmSession::WasmSession(wasm_engine_t* eng, wasmtime_module_t* m, std::string id) 
        : engine(eng), module(m), sessionId(std::move(id)) {
        // 构造时只准备 Store，不进行繁重的实例化
        store = wasmtime_store_new(engine, this, nullptr);
        context = wasmtime_store_context(store);
        linker = wasmtime_linker_new(engine);
    }

    WasmSession::~WasmSession() {
        // 析构时清理资源
        if (linker) wasmtime_linker_delete(linker);
        if (store) wasmtime_store_delete(store);
        LOGI("Session Destroyed: %s", sessionId.c_str());
    }

    // 核心优化：初始化逻辑抽离
    bool WasmSession::initialize() {
        std::lock_guard<std::mutex> lock(sessionMutex); // 加锁保证安全
        
        if (isInitialized) return true;

        // 1. 配置 WASI
        wasmtime_linker_define_wasi(linker);
        wasi_config_t* wasi = wasi_config_new();
        wasi_config_inherit_env(wasi);
        wasi_config_set_stdout_custom(wasi, wasi_writer, nullptr, nullptr);
        wasi_config_set_stderr_custom(wasi, wasi_writer, nullptr, nullptr);
        wasmtime_context_set_wasi(context, wasi);

        // 2. 注册 Host Functions
        registerHostFunctions();

        // 3. 实例化 Module (耗时操作)
        wasm_trap_t* trap = nullptr;
        wasmtime_error_t* error = wasmtime_linker_instantiate(linker, context, module, &instance, &trap);
        
        if (error || trap) {
            if (error) {
                wasm_byte_vec_t msg;
                wasmtime_error_message(error, &msg);
                LOGE("Init Instantiate Error: %s", msg.data);
                wasm_byte_vec_delete(&msg);
                wasmtime_error_delete(error);
            }
            if (trap) {
                wasm_byte_vec_t msg;
                wasm_trap_message(trap, &msg);
                LOGE("Init Instantiate Trap: %s", msg.data);
                wasm_byte_vec_delete(&msg);
                wasm_trap_delete(trap);
            }
            return false;
        }

        // 【新增】获取 Wasm 导出的线性内存 (Linear Memory)
        // 这一点至关重要！Kotlin/Wasm 默认会导出一个名为 "memory" 的对象
        wasmtime_extern_t mem_ext;
        // 参数 "memory" 是 Wasm 标准导出名
        if (wasmtime_instance_export_get(context, &instance, "memory", 6, &mem_ext) &&
            mem_ext.kind == WASMTIME_EXTERN_MEMORY) {
            
            // 保存到成员变量
            memory = mem_ext.of.memory;
            hasMemory = true;
            // LOGI("Wasm Memory exported successfully.");
        } else {
            LOGE("Failed to find exported 'memory' in Wasm module!");
            hasMemory = false;
            return false;
        }

        // 4. 执行 _initialize (WASI 构造函数，耗时操作)
        wasmtime_extern_t item;
        if (wasmtime_instance_export_get(context, &instance, "_initialize", 11, &item)) {
             wasmtime_func_call(context, &item.of.func, nullptr, 0, nullptr, 0, &trap);
             if (trap) { wasm_trap_delete(trap); }
        }

        isInitialized = true;
        LOGI("Session Initialized: %s", sessionId.c_str());
        return true;
    }

    // 注册函数逻辑不变，只需注意 Lambda 捕获
    void WasmSession::registerHostFunctions() {
        auto define = [&](const char* name, wasmtime_func_callback_t cb,
                          std::vector<wasm_valkind_t> param_kinds,
                          std::vector<wasm_valkind_t> result_kinds) {
            wasm_valtype_vec_t p_vec, r_vec;
            std::vector<wasm_valtype_t*> p_types, r_types;
            for (auto k : param_kinds) p_types.push_back(wasm_valtype_new(k));
            for (auto k : result_kinds) r_types.push_back(wasm_valtype_new(k));
            wasm_valtype_vec_new(&p_vec, p_types.size(), p_types.data());
            wasm_valtype_vec_new(&r_vec, r_types.size(), r_types.data());
            wasm_functype_t* ty = wasm_functype_new(&p_vec, &r_vec);
            wasmtime_linker_define_func(linker, "env", 3, name, strlen(name), ty, cb, nullptr, nullptr);
            wasm_functype_delete(ty);
        };

        define("host_get_action_size", host_get_action_size, {}, {WASM_I32});
        define("host_get_json_size", host_get_json_size, {}, {WASM_I32});
        define("host_read_input_byte", host_read_input_byte, {WASM_I32, WASM_I32}, {WASM_I32});
        define("host_write_result_byte", host_write_result_byte, {WASM_I32}, {});
        define("host_copy_to_memory", host_copy_to_memory, {WASM_I32, WASM_I32, WASM_I32}, {});
        define("host_read_from_memory", host_read_from_memory, {WASM_I32, WASM_I32}, {});
    }

    std::string WasmSession::call(const char* act, size_t actLen, const char* jsn, size_t jsnLen) {
//        auto start = std::chrono::high_resolution_clock::now();
//        auto duration = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::high_resolution_clock::now() - start).count();
//        LOGI("[wasmtime] native call spend time : %lld ms", duration);
        std::lock_guard<std::mutex> lock(sessionMutex); // 关键：执行时加锁，防止数据竞争
        
        if (!isInitialized) return "{\"error\":\"Session not initialized\"}";

        // 重置数据
        // Zero Copy: 仅保存指针引用
        actionPtr = act;
        actionSize = actLen;
        jsonPtr = jsn;
        jsonSize = jsnLen;
        outputResult = "";

        wasmtime_extern_t item;
        wasm_trap_t* trap = nullptr;
        wasmtime_error_t* error = nullptr;
        // 直接调用 run_entry，不再实例化
        if (wasmtime_instance_export_get(context, &instance, "run_entry", 9, &item)) {
            error = wasmtime_func_call(context, &item.of.func, nullptr, 0, nullptr, 0, &trap);
            if (error || trap) {
                if(trap) {
                    wasm_byte_vec_t msg;
                    wasm_trap_message(trap, &msg);
                    LOGE("[]Execution Trap: %s", msg.data);
                    wasm_byte_vec_delete(&msg);
                    wasm_trap_delete(trap);
                }
                if(error) wasmtime_error_delete(error);
                return "{\"error\":\"Execution Error\"}";
            }
        } else {
            return "{\"error\":\"No run_entry export\"}";
        }

        // 调用结束，重置指针防止野指针（虽然 session 是受控的，但好习惯）
        actionPtr = nullptr;
        jsonPtr = nullptr;

        return outputResult.empty() ? "{}" : outputResult;
    }

    // Host Functions 实现不变，因为通过 getUserData 获取 this 指针
    static WasmSession* get_session(wasmtime_caller_t* caller) {
        return (WasmSession*)wasmtime_context_get_data(wasmtime_caller_context(caller));
    }

    wasm_trap_t* WasmSession::host_get_action_size(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs, wasmtime_val_t* results, size_t nresults) {
        auto* self = get_session(caller);
        results[0].kind = WASMTIME_I32;
        results[0].of.i32 = (int32_t)self->actionSize;
        return nullptr;
    }

    wasm_trap_t* WasmSession::host_get_json_size(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs, wasmtime_val_t* results, size_t nresults) {
        auto* self = get_session(caller);
        results[0].kind = WASMTIME_I32;
        results[0].of.i32 = (int32_t)self->jsonSize;
        return nullptr;
    }

    wasm_trap_t* WasmSession::host_read_input_byte(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs, wasmtime_val_t* results, size_t nresults) {
        auto* self = get_session(caller);
        int32_t type = args[0].of.i32;
        int32_t index = args[1].of.i32;
        const char* ptr = (type == 0) ? self->actionPtr : self->jsonPtr;
        size_t size = (type == 0) ? self->actionSize : self->jsonSize;
        if (index < 0 || index >= size) {
            return wasmtime_trap_new("Index OOB", 9);
        }
        results[0].kind = WASMTIME_I32;
        results[0].of.i32 = (uint8_t)ptr[index];
        return nullptr;
    }

    wasm_trap_t* WasmSession::host_write_result_byte(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs, wasmtime_val_t* results, size_t nresults) {
        auto* self = get_session(caller);
        self->outputResult += (char)args[0].of.i32;
        return nullptr;
    }
    wasm_trap_t* WasmSession::host_copy_to_memory(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs, wasmtime_val_t* results, size_t nresults) {
        auto* self = get_session(caller);
        
        // 安全检查
        if (!self->hasMemory) {
            return wasmtime_trap_new("Memory not initialized", 22);
        }

        int32_t type = args[0].of.i32;
        int32_t wasmPtr = args[1].of.i32;
        int32_t len = args[2].of.i32;

        // 获取数据源
        const char* srcData = (type == 0) ? self->actionPtr : self->jsonPtr;
        size_t maxLen = (type == 0) ? self->actionSize : self->jsonSize;
        
        if (len < 0) return nullptr; // 简单的参数防御
        if ((size_t)len > maxLen) len = (int32_t)maxLen;

        // 获取内存指针
        uint8_t* rawMemory = wasmtime_memory_data(self->context, &self->memory);

        if (rawMemory && srcData && len > 0) {
            memcpy(rawMemory + wasmPtr, srcData, len);
        }

        return nullptr;
    }

    wasm_trap_t* WasmSession::host_read_from_memory(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs, wasmtime_val_t* results, size_t nresults) {
        auto* self = get_session(caller);
        int32_t wasmPtr = args[0].of.i32;
        int32_t len = args[1].of.i32;

        uint8_t* rawMemory = wasmtime_memory_data(self->context, &self->memory);

        // ⚡️ Memcpy: Wasm -> C++ (Append)
        if (rawMemory && len > 0) {
            // 直接从裸指针构造 string 并追加
            self->outputResult.append((char*)(rawMemory + wasmPtr), len);
        }

        return nullptr;
    }
}