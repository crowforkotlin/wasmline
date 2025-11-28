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

    WasmSession::WasmSession(wasm_engine_t* engine, wasmtime_module_t* m) : module(m) {
        store = wasmtime_store_new(engine, this, nullptr);
        context = wasmtime_store_context(store);
        
        linker = wasmtime_linker_new(engine);
        wasmtime_linker_define_wasi(linker);

        wasi_config_t* wasi = wasi_config_new();
        wasi_config_inherit_env(wasi);
        wasi_config_set_stdout_custom(wasi, wasi_writer, nullptr, nullptr);
        wasi_config_set_stderr_custom(wasi, wasi_writer, nullptr, nullptr);
        wasmtime_context_set_wasi(context, wasi);
    }

    WasmSession::~WasmSession() {
        if (linker) wasmtime_linker_delete(linker);
        if (store) wasmtime_store_delete(store);
    }

    void WasmSession::registerHostFunctions() {
        // 使用 Lambda 表达式替代宏，彻底解决逗号解析问题
        // 同时也自动处理 wasm_valtype_new 的内存分配，代码更简洁
        auto define = [&](const char* name, wasmtime_func_callback_t cb,
                          std::vector<wasm_valkind_t> param_kinds,
                          std::vector<wasm_valkind_t> result_kinds) {
            
            wasm_valtype_vec_t p_vec, r_vec;
            std::vector<wasm_valtype_t*> p_types, r_types;

            // 将枚举转换为 Wasmtime 类型指针
            for (auto k : param_kinds) p_types.push_back(wasm_valtype_new(k));
            for (auto k : result_kinds) r_types.push_back(wasm_valtype_new(k));

            // 构造类型向量
            wasm_valtype_vec_new(&p_vec, p_types.size(), p_types.data());
            wasm_valtype_vec_new(&r_vec, r_types.size(), r_types.data());

            // 创建函数类型签名
            wasm_functype_t* ty = wasm_functype_new(&p_vec, &r_vec);

            // 注册函数
            wasmtime_linker_define_func(linker, "env", 3, name, strlen(name), ty, cb, nullptr, nullptr);

            // 清理类型签名 (Wasmtime 会拷贝一份，所以这里可以释放)
            wasm_functype_delete(ty);
        };

        // 1. host_get_action_size: () -> i32
        define("host_get_action_size", host_get_action_size, 
               {}, 
               {WASM_I32});

        // 2. host_get_json_size: () -> i32
        define("host_get_json_size", host_get_json_size, 
               {}, 
               {WASM_I32});

        // 3. host_read_input_byte: (i32, i32) -> i32
        define("host_read_input_byte", host_read_input_byte, 
               {WASM_I32, WASM_I32}, 
               {WASM_I32});

        // 4. host_write_result_byte: (i32) -> void
        define("host_write_result_byte", host_write_result_byte, 
               {WASM_I32}, 
               {});
    }

    std::string WasmSession::call(const std::string& a, const std::string& j) {
        inputAction = a;
        inputJson = j;
        outputResult = "";

        wasmtime_instance_t instance;
        wasm_trap_t* trap = nullptr;
        wasmtime_error_t* error = nullptr;

        // 1. 实例化
        error = wasmtime_linker_instantiate(linker, context, module, &instance, &trap);
        if (error || trap) {
            if (error) {
                wasm_byte_vec_t msg;
                wasmtime_error_message(error, &msg);
                LOGE("Instantiation Error: %s", msg.data);
                wasm_byte_vec_delete(&msg);
                wasmtime_error_delete(error);
            }
            if (trap) {
                wasm_byte_vec_t msg;
                wasm_trap_message(trap, &msg);
                LOGE("Instantiation Trap: %s", msg.data);
                wasm_byte_vec_delete(&msg);
                wasm_trap_delete(trap);
            }
            return "{\"error\":\"Instantiate Failed\"}";
        }

        // 2. _initialize
        wasmtime_extern_t item;
        if (wasmtime_instance_export_get(context, &instance, "_initialize", 11, &item)) {
             wasmtime_func_call(context, &item.of.func, nullptr, 0, nullptr, 0, &trap);
             if (trap) { wasm_trap_delete(trap); }
        }

        // 3. run_entry
        if (wasmtime_instance_export_get(context, &instance, "run_entry", 9, &item)) {
            error = wasmtime_func_call(context, &item.of.func, nullptr, 0, nullptr, 0, &trap);
            if (error || trap) {
                if(trap) {
                    wasm_byte_vec_t msg;
                    wasm_trap_message(trap, &msg);
                    LOGE("Execution Trap: %s", msg.data);
                    wasm_byte_vec_delete(&msg);
                    wasm_trap_delete(trap);
                }
                if(error) wasmtime_error_delete(error);
                return "{\"error\":\"Execution Error\"}";
            }
        } else {
            return "{\"error\":\"No run_entry export\"}";
        }

        return outputResult.empty() ? "{}" : outputResult;
    }

    // --- Host Functions 实现 ---

    static WasmSession* get_session(wasmtime_caller_t* caller) {
        return (WasmSession*)wasmtime_context_get_data(wasmtime_caller_context(caller));
    }

    wasm_trap_t* WasmSession::host_get_action_size(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs, wasmtime_val_t* results, size_t nresults) {
        auto* self = get_session(caller);
        results[0].kind = WASMTIME_I32;
        results[0].of.i32 = (int32_t)self->inputAction.size();
        return nullptr;
    }

    wasm_trap_t* WasmSession::host_get_json_size(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs, wasmtime_val_t* results, size_t nresults) {
        auto* self = get_session(caller);
        results[0].kind = WASMTIME_I32;
        results[0].of.i32 = (int32_t)self->inputJson.size();
        return nullptr;
    }

    wasm_trap_t* WasmSession::host_read_input_byte(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs, wasmtime_val_t* results, size_t nresults) {
        auto* self = get_session(caller);
        int32_t type = args[0].of.i32; // 0=action, 1=json
        int32_t index = args[1].of.i32;
        const std::string* target = (type == 0) ? &self->inputAction : &self->inputJson;

        if (index < 0 || index >= target->size()) {
            return wasmtime_trap_new("Index OOB", 9);
        }
        results[0].kind = WASMTIME_I32;
        results[0].of.i32 = (uint8_t)(*target)[index];
        return nullptr;
    }

    wasm_trap_t* WasmSession::host_write_result_byte(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs, wasmtime_val_t* results, size_t nresults) {
        auto* self = get_session(caller);
        self->outputResult += (char)args[0].of.i32;
        return nullptr;
    }

}