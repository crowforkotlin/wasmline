@file:OptIn(ExperimentalWasmInterop::class)
@file:Suppress("FunctionName")

package crow.wasmtime.wasmline

// --- 1. 底层 Import (全部 private/internal，对外隐藏) ---
@WasmImport("env", "host_get_action_size")
external fun host_get_action_size(): Int

@WasmImport("env", "host_get_json_size")
external fun host_get_json_size(): Int

@WasmImport("env", "host_read_input_byte")
external fun host_read_input_byte(type: Int, index: Int): Int

@WasmImport("env", "host_write_result_byte")
external fun host_write_result_byte(byte: Int)

// --- 2. 内部桥接工具 ---
internal object HostBridge {
    fun getAction(): String {
        val size = host_get_action_size()
        if (size == 0) return ""
        return readString(0, size)
    }

    fun getJson(): String {
        val size = host_get_json_size()
        if (size == 0) return ""
        return readString(1, size)
    }

    private fun readString(type: Int, size: Int): String {
        val bytes = ByteArray(size)
        for (i in 0 until size) {
            bytes[i] = host_read_input_byte(type, i).toByte()
        }
        return bytes.decodeToString()
    }

    fun sendResult(result: String) {
        val bytes = result.encodeToByteArray()
        for (b in bytes) {
            host_write_result_byte(b.toInt())
        }
    }
}

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

// --- 4. 统一入口 (SDK 负责导出) ---
fun RunWasmEngineEntry() {
    // 1. 自动拉取参数
    val action = HostBridge.getAction()
    val args = HostBridge.getJson()

    println("action is : $action \t arg is : $args")

    // 2. 自动捕获异常并分发
    val result = try {
        WasmRouter.dispatch(action, args)
    } catch (e: Exception) {
        """{"error": "Wasm Panic: ${e.message}"}"""
    }

    // 3. 自动回传
    HostBridge.sendResult(result)
}

fun main() { println("[Wasm SDK] Initialized.") }