@file:OptIn(ExperimentalWasmInterop::class, UnsafeWasmMemoryApi::class)
@file:Suppress("FunctionName")

package crow.wasmtime.wasmline

import kotlin.time.Clock
import kotlin.wasm.unsafe.UnsafeWasmMemoryApi
import kotlin.wasm.unsafe.withScopedMemoryAllocator

private const val TYPE_HOST_ACTION = 0
private const val TYPE_HOST_INPUT = 1

/*
* Import Inbound
* */
// --- 1. 底层 Import (全部 private/internal，对外隐藏) ---
// type: 0=Action, 1=Input
@WasmImport("env", "bridge_inbound_get_size")
external fun bridge_inbound_get_size(type: Int): Int

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
external fun bridge_outbound_call_host(aPtr: Int, aLen: Int, pPtr: Int, pLen: Int): Int

@WasmImport("env", "bridge_outbound_get_response")
external fun bridge_outbound_get_response(ptr: Int)


// --- 2. 内部桥接工具 ---
internal object WasmBridge {

    fun getAction(): String {
        val size = bridge_inbound_get_size(type = TYPE_HOST_ACTION)
        if (size == 0) return ""
        return readStringFromHost(type = TYPE_HOST_ACTION, size)
    }

    fun getJson(): String {
        val size = bridge_inbound_get_size(type = TYPE_HOST_INPUT)
        if (size == 0) return ""
        return readStringFromHost(TYPE_HOST_INPUT, size)
    }

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
            bridge_inbound_copy_params(type, pointer.address.toInt(), size)

            // C. 将数据从 Unsafe 内存搬运到 Kotlin 安全内存 (ByteArray)
            // 注意：这个循环是在 Wasm 虚拟机内部执行的，是纯计算指令，非常快
            val start = Clock.System.now().toEpochMilliseconds()
            val bytes = ByteArray(size)
            for (i in 0 until size) {
                // 指针运算 + 读取字节
                bytes[i] = (pointer + i).loadByte()
            }
            return "bytes size ${bytes.size}"
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

        // 开启内存作用域：在此块内分配的内存，块结束后会自动释放
        // 这就是 Wasm 的内存管理方式，无需手动 free
        withScopedMemoryAllocator { allocator ->

            // 1. [Alloc] 申请内存放 Action
            val aPtr = allocator.allocate(actionBytes.size)
            for (i in actionBytes.indices) (aPtr + i).storeByte(actionBytes[i])

            // 2. [Alloc] 申请内存放 Payload
            val pPtr = allocator.allocate(payload.size)
            for (i in payload.indices) (pPtr + i).storeByte(payload[i])

            // 3. [Push] 调用 Host，获取结果长度
            // C++ 会在这里执行 JNI -> Java，并暂存结果
            val resLen = bridge_outbound_call_host(
                aPtr.address.toInt(), actionBytes.size,
                pPtr.address.toInt(), payload.size
            )

            // 如果结果为空，直接返回
            if (resLen <= 0) return ByteArray(0)

            // 4. [Alloc] 申请结果内存
            val resPtr = allocator.allocate(resLen)

            // 5. [Pull] 把结果从 C++ 暂存区拉过来
            bridge_outbound_get_response(resPtr.address.toInt())

            // 6. [Copy] 转为 Kotlin 对象
            val result = ByteArray(resLen)
            for (i in 0 until resLen) result[i] = (resPtr + i).loadByte()

            return result
        } // 离开作用域，aPtr, pPtr, resPtr 指向的内存被“释放”（复用）
    }
}