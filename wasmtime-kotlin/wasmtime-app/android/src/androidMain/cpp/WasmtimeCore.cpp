    #include "WasmtimeCore.h"
    #include <iostream>
    #include <sstream>

    // 辅助函数：将 Wasm 类型枚举转换为字符串
    static const char* get_kind_name(wasm_valkind_t kind) {
        switch (kind) {
            case WASM_I32: return "i32";
            case WASM_I64: return "i64";
            case WASM_F32: return "f32";
            case WASM_F64: return "f64";
            case WASM_FUNCREF: return "funcref";
            case WASM_EXTERNREF: return "externref";
            default: return "unknown";
        }
    }

    // WASI 输出回调的中转函数（静态函数，用于转接给类实例）
    static ptrdiff_t wasi_write_callback(void* data, const unsigned char* buffer, size_t size) {
        if (size == 0 || data == nullptr) return 0;

        // 将 data 转换回 LogCallback 指针
        auto* callback = static_cast<LogCallback*>(data);

        std::string msg((const char*)buffer, size);
        // 移除末尾换行符，避免日志系统重复换行
        while (!msg.empty() && (msg.back() == '\n' || msg.back() == '\r')) {
            msg.pop_back();
        }

        if (!msg.empty()) {
            (*callback)(msg);
        }
        return size;
    }

    WasmtimeCore::WasmtimeCore(const WasmConfig& conf) : config(conf) {}

    WasmtimeCore::~WasmtimeCore() {
        // 资源释放通常在 runAddFunction 内部的栈变量销毁时自动完成
        // 如果将 Engine 持久化，则需在此处释放
    }

    void WasmtimeCore::setLogCallback(LogCallback callback) {
        this->logCallback = callback;
    }

    void WasmtimeCore::log(const std::string& msg) {
        if (logCallback) {
            logCallback(msg);
        } else {
            // 默认输出到标准输出 (方便桌面端调试)
            std::cout << "[Core] " << msg << std::endl;
        }
    }

    void WasmtimeCore::logError(const char* prefix, wasmtime_error_t* error, wasm_trap_t* trap) {
        wasm_byte_vec_t error_message;
        std::ostringstream ss;
        ss << prefix << ": ";

        if (error != nullptr) {
            wasmtime_error_message(error, &error_message);
            wasmtime_error_delete(error);
            ss << std::string(error_message.data, error_message.size);
        } else if (trap != nullptr) {
            wasm_trap_message(trap, &error_message);
            wasm_trap_delete(trap);
            ss << "TRAP -> " << std::string(error_message.data, error_message.size);
        } else {
            ss << "Unknown Error";
        }

        log(ss.str());
        if (error || trap) wasm_byte_vec_delete(&error_message);
    }

    int32_t WasmtimeCore::runAddFunction(const std::vector<uint8_t>& wasmBytes, int a, int b) {
        log("=== Start Execution ===");

        // 1. 创建并应用配置
        wasm_config_t* wasm_conf = wasm_config_new();

        // 应用基础特性
        wasmtime_config_wasm_gc_set(wasm_conf, config.enableGc);
        wasmtime_config_wasm_function_references_set(wasm_conf, config.enableFunctionReferences);
        wasmtime_config_wasm_exceptions_set(wasm_conf, config.enableExceptionHandling);

        // 应用高级/平台特性
        wasmtime_config_wasm_simd_set(wasm_conf, config.enableSimd);
        wasmtime_config_wasm_relaxed_simd_set(wasm_conf, config.enableSimd);

        if (!config.enableCraneliftOpt) {
            wasmtime_config_cranelift_opt_level_set(wasm_conf, WASMTIME_OPT_LEVEL_NONE);
        }

        if (!config.enableSignalsBasedTraps) {
            wasmtime_config_signals_based_traps_set(wasm_conf, false);
            // 配合显式检查，禁用虚拟内存保护页
            wasmtime_config_memory_guard_size_set(wasm_conf, 0);
            // wasmtime_config_static_memory_maximum_size_set 已在 v39 移除或行为变更，通常只需上面两行
        }

        // 2. 创建引擎与 Store
        wasm_engine_t* engine = wasm_engine_new_with_config(wasm_conf);
        if (!engine) {
            log("Failed to create engine");
            return -1;
        }
        wasmtime_store_t* store = wasmtime_store_new(engine, nullptr, nullptr);
        wasmtime_context_t* context = wasmtime_store_context(store);

        // 3. 配置 WASI
        wasi_config_t* wasi_conf = wasi_config_new();
        wasi_config_inherit_argv(wasi_conf);
        wasi_config_inherit_env(wasi_conf);

        if (config.enableWasiOutput && logCallback) {
            // 挂载日志回调，将 this->logCallback 的指针传进去
            wasi_config_set_stdout_custom(wasi_conf, wasi_write_callback, &logCallback, nullptr);
            wasi_config_set_stderr_custom(wasi_conf, wasi_write_callback, &logCallback, nullptr);
        }

        wasmtime_error_t* error = wasmtime_context_set_wasi(context, wasi_conf);
        if (error) {
            logError("WASI Config Error", error, nullptr);
            return -1;
        }

        // 4. 链接器与模块编译
        wasmtime_linker_t* linker = wasmtime_linker_new(engine);
        wasmtime_linker_define_wasi(linker);

        log("Compiling Module...");
        wasmtime_module_t* module = nullptr;
        error = wasmtime_module_new(engine, wasmBytes.data(), wasmBytes.size(), &module);
        if (error) {
            logError("Compile Failed", error, nullptr);
            return -1;
        }

        // 5. 实例化
        log("Instantiating...");
        wasmtime_instance_t instance;
        wasm_trap_t* trap = nullptr;
        error = wasmtime_linker_instantiate(linker, context, module, &instance, &trap);
        if (error || trap) {
            logError("Instantiation Failed", error, trap);
            return -1;
        }

        // 6. 初始化 Kotlin 运行时
        if (!initializeKotlinRuntime(context, &instance)) {
            log("Runtime Initialization failed (check logs for details)");
            // 视情况决定是否继续，通常如果初始化挂了，后面也跑不通
            return -1;
        }

        // 7. 获取并检查 'add' 函数
        log("Looking for 'add' export...");
        wasmtime_extern_t run_extern;
        bool found = wasmtime_instance_export_get(context, &instance, "add", 3, &run_extern);

        if (!found || run_extern.kind != WASM_EXTERN_FUNC) {
            log("Export 'add' not found or is not a function");
            return -1;
        }

        wasmtime_func_t func_obj = run_extern.of.func;

        // 签名检查 (防止参数错误导致崩溃)
        wasm_functype_t* func_type = wasmtime_func_type(context, &func_obj);
        const wasm_valtype_vec_t* params = wasm_functype_params(func_type);

        if (params->size != 2 ||
            wasm_valtype_kind(params->data[0]) != WASM_I32 ||
            wasm_valtype_kind(params->data[1]) != WASM_I32) {

            std::ostringstream ss;
            ss << "Signature Mismatch! Expected (i32, i32), got: (";
            for(int i=0; i<params->size; i++) {
                ss << get_kind_name(wasm_valtype_kind(params->data[i])) << " ";
            }
            ss << ")";
            log(ss.str());
            wasm_functype_delete(func_type);
            return -1;
        }
        wasm_functype_delete(func_type);

        // 8. 执行函数
        log("Invoking add(" + std::to_string(a) + ", " + std::to_string(b) + ")...");

        wasmtime_val_t args[2];
        args[0].kind = WASMTIME_I32; args[0].of.i32 = a;
        args[1].kind = WASMTIME_I32; args[1].of.i32 = b;

        wasmtime_val_t results[1];

        error = wasmtime_func_call(context, &func_obj, args, 2, results, 1, &trap);

        int32_t result_val = -1;
        if (error || trap) {
            logError("Function Call Failed", error, trap);
        } else {
            if (results[0].kind == WASMTIME_I32) {
                result_val = results[0].of.i32;
                log("Success! Result: " + std::to_string(result_val));
            } else {
                log("Success, but return type is not i32");
            }
        }

        // 9. 清理资源
        wasmtime_module_delete(module);
        wasmtime_linker_delete(linker);
        wasmtime_store_delete(store);
        wasm_engine_delete(engine);

        return result_val;
    }

    bool WasmtimeCore::initializeKotlinRuntime(wasmtime_context_t* context, wasmtime_instance_t* instance) {
        wasmtime_extern_t init_extern;
        wasmtime_func_t init_func;
        bool needs_init = false;

        // 尝试查找 _initialize (Reactor) 或 _start (Command)
        if (wasmtime_instance_export_get(context, instance, "_initialize", 11, &init_extern) &&
            init_extern.kind == WASM_EXTERN_FUNC) {
            init_func = init_extern.of.func;
            needs_init = true;
        } else if (wasmtime_instance_export_get(context, instance, "_start", 6, &init_extern) &&
                 init_extern.kind == WASM_EXTERN_FUNC) {
            init_func = init_extern.of.func;
            needs_init = true;
        }

        if (needs_init) {
            wasm_trap_t* trap = nullptr;
            wasmtime_error_t* error = wasmtime_func_call(context, &init_func, nullptr, 0, nullptr, 0, &trap);
            if (error || trap) {
                 // 检查是否是 exit(0) (正常退出)
                 // 简略处理：如果是 trap 且不严重，可以返回 true
                 // 这里为了演示，如果有错误打印日志，但如果是 exit(0) 实际上在 Trap Message 里会有体现
                 logError("Runtime Init Trace (might be normal exit)", error, trap);
                 // 注意：对于 Command 模式，_start 运行完可能会 trap exit，但也可能意味着初始化完成
                 // 严格来说需要解析 trap message。这里暂且假设非致命。
            }
            return true;
        }
        return true; // 没找到初始化函数，可能无需初始化
    }