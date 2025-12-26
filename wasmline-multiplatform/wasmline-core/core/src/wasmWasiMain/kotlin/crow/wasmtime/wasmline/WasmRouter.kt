package crow.wasmtime.wasmline

// --- 3. 路由注册中心 ---
object WasmRouter {
    private val handlers = mutableMapOf<String, (String) -> String>()

    // 对外暴露的注册接口
    fun register(action: String, handler: (String) -> String) {
        handlers[action] = handler
    }

    // 内部调用
    internal fun dispatch(action: String, args: String): String {
        val handler = handlers[action]
        return handler?.invoke(args) ?: """{"error": "No handler for action '$action'"}"""
    }
}