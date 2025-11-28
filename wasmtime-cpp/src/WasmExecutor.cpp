#include "WasmExecutor.h"
#include "WasmModule.h"
#include <cstring>

// 日志回调
static ptrdiff_t wasi_write_cb(void* data, const unsigned char* buffer, size_t size) {
    if (size == 0 || !buffer) return 0;
    size_t print_size = size > 4096 ? 4096 : size;
    std::string msg((const char*)buffer, print_size);
    while (!msg.empty() && (msg.back() == '\n' || msg.back() == '\r')) msg.pop_back();
    if (!msg.empty()) LOGI("[WASI] %s", msg.c_str());
    return size;
}

WasmExecutor::WasmExecutor(WasmModule* m, std::string a, std::string j)
    : holder(m), inputAction(std::move(a)), inputJson(std::move(j)) {
    
    // Store 的 data 设置为 this，以便 static callback 获取实例
    store = wasmtime_store_new(holder->getEngine(), this, nullptr);
    context = wasmtime_store_context(store);

    wasi_config_t* wasi = wasi_config_new();
    wasi_config_inherit_env(wasi);
    wasi_config_set_stdout_custom(wasi, wasi_write_cb, nullptr, nullptr);
    wasi_config_set_stderr_custom(wasi, wasi_write_cb, nullptr, nullptr);
    wasmtime_context_set_wasi(context, wasi);
}

WasmExecutor::~WasmExecutor() {
    if (store) wasmtime_store_delete(store);
}

void WasmExecutor::registerHostFunctions(wasmtime_linker_t* linker) {
    auto def = [&](const char* name, wasmtime_func_callback_t cb, 
                   std::vector<wasm_valkind_t> p, std::vector<wasm_valkind_t> r) {
        wasm_valtype_vec_t params, results;
        std::vector<wasm_valtype_t*> vp, vr;
        for(auto k:p) vp.push_back(wasm_valtype_new(k));
        for(auto k:r) vr.push_back(wasm_valtype_new(k));
        wasm_valtype_vec_new(&params, vp.size(), vp.data());
        wasm_valtype_vec_new(&results, vr.size(), vr.data());
        wasm_functype_t* ty = wasm_functype_new(&params, &results);
        wasmtime_linker_define_func(linker, "env", 3, name, strlen(name), ty, cb, nullptr, nullptr);
        wasm_functype_delete(ty);
    };

    def("host_get_action_size", host_get_action_size, {}, {WASM_I32});
    def("host_get_json_size", host_get_json_size, {}, {WASM_I32});
    def("host_read_input_byte", host_read_input_byte, {WASM_I32, WASM_I32}, {WASM_I32});
    def("host_write_result_byte", host_write_result_byte, {WASM_I32}, {});
}

std::string WasmExecutor::run() {
    wasmtime_instance_t instance;
    wasm_trap_t* trap = nullptr;

    // 1. Instantiate
    wasmtime_error_t* err = wasmtime_linker_instantiate(holder->getLinker(), context, holder->getModule(), &instance, &trap);
    if (err || trap) {
        LOGE("Instantiate failed");
        if(err) wasmtime_error_delete(err);
        if(trap) wasm_trap_delete(trap);
        return "{\"error\": \"Instantiate Failed\"}";
    }

    // 2. _initialize
    wasmtime_extern_t init_ext;
    if (wasmtime_instance_export_get(context, &instance, "_initialize", 11, &init_ext)) {
        wasmtime_func_call(context, &init_ext.of.func, nullptr, 0, nullptr, 0, &trap);
        if (trap) {
            LOGE("Init trap caught (could be normal exit)");
            wasm_trap_delete(trap);
            trap = nullptr;
        }
    }

    // 3. run_entry
    wasmtime_extern_t run_ext;
    if (wasmtime_instance_export_get(context, &instance, "run_entry", 9, &run_ext)) {
        wasmtime_func_call(context, &run_ext.of.func, nullptr, 0, nullptr, 0, &trap);
        if (trap) {
            wasm_byte_vec_t msg;
            wasm_trap_message(trap, &msg);
            LOGE("Run trap: %s", msg.data);
            wasm_byte_vec_delete(&msg);
            wasm_trap_delete(trap);
            return "{\"error\": \"Run Trap\"}";
        }
    } else {
        return "{\"error\": \"Export run_entry not found\"}";
    }

    return outputResult.empty() ? "{}" : outputResult;
}

// --- Host Function Implementations ---

static WasmExecutor* get_self(wasmtime_caller_t* caller) {
    return (WasmExecutor*)wasmtime_context_get_data(wasmtime_caller_context(caller));
}

wasm_trap_t* WasmExecutor::host_get_action_size(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs, wasmtime_val_t* results, size_t nresults) {
    auto* self = get_self(caller);
    results[0].kind = WASMTIME_I32;
    results[0].of.i32 = (int32_t)self->inputAction.size();
    return nullptr;
}

wasm_trap_t* WasmExecutor::host_get_json_size(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs, wasmtime_val_t* results, size_t nresults) {
    auto* self = get_self(caller);
    results[0].kind = WASMTIME_I32;
    results[0].of.i32 = (int32_t)self->inputJson.size();
    return nullptr;
}

wasm_trap_t* WasmExecutor::host_read_input_byte(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs, wasmtime_val_t* results, size_t nresults) {
    auto* self = get_self(caller);
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

wasm_trap_t* WasmExecutor::host_write_result_byte(void* env, wasmtime_caller_t* caller, const wasmtime_val_t* args, size_t nargs, wasmtime_val_t* results, size_t nresults) {
    auto* self = get_self(caller);
    self->outputResult += (char)args[0].of.i32;
    return nullptr;
}