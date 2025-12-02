@file:OptIn(ExperimentalWasmInterop::class, UnsafeWasmMemoryApi::class)
@file:Suppress("FunctionName")

package crow.wasmtime.wasmline

import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import okio.Buffer
import okio.use
import kotlin.time.Clock
import kotlin.wasm.unsafe.UnsafeWasmMemoryApi
import kotlin.wasm.unsafe.withScopedMemoryAllocator

// --- 1. 底层 Import (全部 private/internal，对外隐藏) ---
@WasmImport("env", "host_get_action_size")
external fun host_get_action_size(): Int

@WasmImport("env", "host_get_json_size")
external fun host_get_json_size(): Int

@WasmImport("env", "host_read_input_byte")
external fun host_read_input_byte(type: Int, index: Int): Int

@WasmImport("env", "host_write_result_byte")
external fun host_write_result_byte(byte: Int)

// [新接口] 告诉 Host：把 type 类型的数据拷贝到 ptr 这个地址，长度为 len
@WasmImport("env", "host_copy_to_memory")
external fun host_copy_to_memory(type: Int, ptr: Int, len: Int)

// [新接口] 告诉 Host：从 ptr 这个地址读取 len 长度的数据作为结果
@WasmImport("env", "host_read_from_memory")
external fun host_read_from_memory(ptr: Int, len: Int)

// --- 2. 内部桥接工具 ---
internal object HostBridge {
    fun getAction(): String {
        val size = host_get_action_size()
        if (size == 0) return ""
        return readStringFromHost(0, size)
    }

    fun getJson(): String {
        val size = host_get_json_size()
        if (size == 0) return ""
        return readStringFromHost(1, size)
    }

    /*private fun readString(type: Int, size: Int): String {
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
    }*/

    /**
     * 核心优化：批量读取
     * 1. 在 Wasm 线性内存申请 buffer
     * 2. 让 C++ memcpy 数据进来
     * 3. 在 Wasm 内部循环拷贝到 ByteArray (极快，无 Host Call 开销)
     */
    private fun readStringFromHost(type: Int, size: Int): String {
        // 使用 Scoped Allocator，代码块结束自动释放内存，无泄漏风险
        withScopedMemoryAllocator { allocator ->
            // A. 申请内存 (返回的是 unsafe Pointer)
            val pointer = allocator.allocate(size)

            // B. 传入地址 (Int)，让 C++ 直接 memcpy
            host_copy_to_memory(type, pointer.address.toInt(), size)

            // C. 将数据从 Unsafe 内存搬运到 Kotlin 安全内存 (ByteArray)
            // 注意：这个循环是在 Wasm 虚拟机内部执行的，是纯计算指令，非常快
            val start = Clock.System.now().toEpochMilliseconds()
            val bytes = ByteArray(size)
            for (i in 0 until size) {
                // 指针运算 + 读取字节
                bytes[i] = (pointer + i).loadByte()
            }
            val datas = ProtoBuf.decodeFromByteArray<Data>(bytes)
            println("spend time 22222 -> ${Clock.System.now().toEpochMilliseconds() - start} \t ${datas}")
            return Json.encodeToString(datas)
        }
    }

    /**
     * 核心优化：批量写入
     */
    fun sendResult(result: String) {
        if (result.isEmpty()) return
        val bytes = result.encodeToByteArray()
        val size = bytes.size

        withScopedMemoryAllocator { allocator ->
            // A. 申请内存
            val pointer = allocator.allocate(size)

            // B. 将 Kotlin ByteArray 搬运到 Unsafe 内存
            for (i in 0 until size) {
                (pointer + i).storeByte(bytes[i])
            }

            // C. 告诉 Host 地址和长度，让它 memcpy 拿走
            host_read_from_memory(pointer.address.toInt(), size)
        }
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