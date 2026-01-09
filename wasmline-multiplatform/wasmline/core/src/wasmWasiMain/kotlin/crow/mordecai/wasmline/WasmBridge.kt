@file:OptIn(
    ExperimentalWasmInterop::class, UnsafeWasmMemoryApi::class,
    ExperimentalSerializationApi::class
)
@file:Suppress("FunctionName")

package crow.mordecai.wasmline

import kotlinx.serialization.ExperimentalSerializationApi
import kotlin.wasm.unsafe.UnsafeWasmMemoryApi
import kotlin.wasm.unsafe.withScopedMemoryAllocator

private const val TYPE_HOST_ACTION = 0
private const val TYPE_HOST_INPUT = 1

/*
* Import Inbound
* */
// 告诉 Host：把 type 类型的数据拷贝到 ptr 这个地址，长度为 len
@WasmImport("env", "bridge_inbound_copy_params")
external fun bridge_inbound_copy_params(type: Int, ptr: Int, len: Int)

// 告诉 Host：从 ptr 这个地址读取 len 长度的数据作为结果
@WasmImport("env", "bridge_inbound_set_response")
external fun bridge_inbound_set_response(ptr: Int, len: Int)

/*
* Import Outbound
* */
@WasmImport("env", "bridge_outbound_call_host")
external fun bridge_outbound_call_host(
    aPtr: Int, aLen: Int,
    pPtr: Int, pLen: Int,
    outPtr: Int, outLen: Int
): Int

@WasmImport("env", "bridge_outbound_get_response")
external fun bridge_outbound_get_response(ptr: Int)


// --- 2. 内部桥接工具 ---
internal object WasmBridge {

    private const val PRE_ALLOC_SIZE = 1024

    /**
     * 核心优化：批量读取
     * 1. 在 Wasm 线性内存申请 buffer
     * 2. 让 C++ memcpy 数据进来
     * 3. 在 Wasm 内部循环拷贝到 ByteArray (极快，无 Host Call 开销)
     */
    fun readBytesFromHost(type: Int, size: Int): ByteArray {
        // ScopedAllocator allocates or reuses Pages on the stack, which is very fast and has no GC pressure.
        withScopedMemoryAllocator { allocator ->
            val pointer = allocator.allocate(size)
            bridge_inbound_copy_params(type, pointer.address.toInt(), size)
            val bytes = ByteArray(size)
            for (i in 0 until size) {
                bytes[i] = (pointer + i).loadByte()
            }
            return bytes
        }
    }

    /**
     * 核心优化：批量写入
     */
    fun  sendResult(result: ByteArray) {
        withScopedMemoryAllocator { allocator ->
            val size = result.size
            val pointer = allocator.allocate(size)
            for (i in 0 until size) { (pointer + i).storeByte(result[i]) }
            bridge_inbound_set_response(pointer.address.toInt(), size)
        }
    }

    /**
     * Wasm 呼叫 Host
     * @param action 方法名
     * @param payload 参数数据
     * @return Host 返回的结果
     */
    fun callHost(action: String, payload: ByteArray): ByteArray {
        val actionBytes = action.encodeToByteArray()

        withScopedMemoryAllocator { allocator ->
            // 1. 准备输入参数
            val aPtr = allocator.allocate(actionBytes.size)
            for (i in actionBytes.indices) (aPtr + i).storeByte(actionBytes[i])

            val pPtr = allocator.allocate(payload.size)
            for (i in payload.indices) (pPtr + i).storeByte(payload[i])

            // 2. [关键] 准备接收结果的“草稿纸” (Fast Path Buffer)
            val tempResultPtr = allocator.allocate(PRE_ALLOC_SIZE)

            // 3. 调用 Host，传入草稿纸地址
            val resultStatus = bridge_outbound_call_host(
                aPtr.address.toInt(), actionBytes.size,
                pPtr.address.toInt(), payload.size,
                tempResultPtr.address.toInt(), PRE_ALLOC_SIZE
            )

            // 4. 分支判断
            if (resultStatus >= 0) {
                // === Fast Path ===
                // 结果已经写入 tempResultPtr，长度为 resultStatus
                // 我们只需要转成 ByteArray，无需再次 Host Call
                val realLen = resultStatus
                if (realLen == 0) return ByteArray(0)

                val result = ByteArray(realLen)
                for (i in 0 until realLen) {
                    result[i] = (tempResultPtr + i).loadByte()
                }
                return result

            } else {
                // === Slow Path ===
                // 结果太长了，草稿纸放不下。
                // resultStatus 是负数，绝对值是需要的真实长度。
                val neededSize = -resultStatus

                // 重新申请足够大的内存
                val finalResPtr = allocator.allocate(neededSize)

                // 发起第二次调用，把暂存的数据拉过来
                bridge_outbound_get_response(finalResPtr.address.toInt())

                val result = ByteArray(neededSize)
                for (i in 0 until neededSize) {
                    result[i] = (finalResPtr + i).loadByte()
                }
                return result
            }
        }
    }
}