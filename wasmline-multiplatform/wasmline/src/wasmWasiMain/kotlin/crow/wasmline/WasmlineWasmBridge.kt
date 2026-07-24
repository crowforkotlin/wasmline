@file:OptIn(
    ExperimentalWasmInterop::class,
    UnsafeWasmMemoryApi::class,
    ExperimentalSerializationApi::class,
)
@file:Suppress("FunctionName", "SpellCheckingInspection")

package crow.wasmline

import kotlinx.serialization.ExperimentalSerializationApi
import kotlin.wasm.unsafe.UnsafeWasmMemoryApi
import kotlin.wasm.unsafe.withScopedMemoryAllocator

// Inbound bridge imports.

/** Copies inbound host data of the requested kind into the provided linear-memory buffer. */
@WasmImport("env", "bridge_inbound_copy_params")
internal external fun bridge_inbound_copy_params(type: Int, ptr: Int, len: Int)

/** Publishes the wasm response bytes stored at the provided linear-memory address. */
@WasmImport("env", "bridge_inbound_set_response")
internal external fun bridge_inbound_set_response(ptr: Int, len: Int)

// Outbound bridge imports.

/**
 * Invokes the host with action and payload buffers and writes the result into the provided output
 * buffer when it fits. Returns a negative size when the caller must fetch the full result via the
 * slow-path API.
 */
@WasmImport("env", "bridge_outbound_call_host")
internal external fun bridge_outbound_call_host(aPtr: Int, aLen: Int, pPtr: Int, pLen: Int, outPtr: Int, outLen: Int): Int

/** Copies the full outbound host response into the provided linear-memory buffer. */
@WasmImport("env", "bridge_outbound_get_response")
internal external fun bridge_outbound_get_response(ptr: Int)

/**
 * Wasm-side bridge utilities for exchanging inbound and outbound payloads with the host runtime.
 *
 * This object owns the low-level memory copies used by generated entrypoints and bridge calls.
 */
internal object WasmlineWasmBridge {

    private const val PRE_ALLOC_SIZE = 1024

    /** Reads a host-owned inbound buffer into a wasm-managed [ByteArray]. */
    internal fun readBytesFromHost(type: Int, size: Int): ByteArray {
        if (size <= 0) return ByteArray(0)

        // ScopedAllocator allocates linear-memory pages on the stack with minimal overhead.
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

    /** Writes the wasm result buffer back to the host runtime. */
    internal fun sendResult(result: ByteArray) {
        if (result.isEmpty()) {
            bridge_inbound_set_response(0, 0)
            return
        }

        withScopedMemoryAllocator { allocator ->
            val size = result.size
            val pointer = allocator.allocate(size)
            for (i in 0 until size) {
                (pointer + i).storeByte(result[i])
            }
            bridge_inbound_set_response(pointer.address.toInt(), size)
        }
    }

    /**
     * Calls the host runtime and returns its response payload.
     *
     * The fast path reuses a preallocated result buffer. When the host reports that the response
     * does not fit, this method falls back to a second copy using the exact response size.
     *
     * @param action the host action name to invoke.
     * @param payload the outbound request payload.
     * @return the host response payload.
     */
    internal fun callHost(action: String, payload: ByteArray): ByteArray {
        val actionBytes = action.encodeToByteArray()

        withScopedMemoryAllocator { allocator ->
            // Prepare input buffers in wasm linear memory.
            val aPtrAddress = if (actionBytes.isEmpty()) {
                0
            } else {
                val aPtr = allocator.allocate(actionBytes.size)
                for (i in actionBytes.indices) (aPtr + i).storeByte(actionBytes[i])
                aPtr.address.toInt()
            }

            val pPtrAddress = if (payload.isEmpty()) {
                0
            } else {
                val pPtr = allocator.allocate(payload.size)
                for (i in payload.indices) (pPtr + i).storeByte(payload[i])
                pPtr.address.toInt()
            }

            // Reserve a small scratch buffer for the common fast-path response.
            val tempResultPtr = allocator.allocate(PRE_ALLOC_SIZE)

            // Invoke the host and let it decide whether the fast-path buffer is sufficient.
            val resultStatus = bridge_outbound_call_host(
                aPtrAddress,
                actionBytes.size,
                pPtrAddress,
                payload.size,
                tempResultPtr.address.toInt(),
                PRE_ALLOC_SIZE,
            )

            if (resultStatus >= 0) {
                // Fast path: the full response already fits in the scratch buffer.
                val realLen = resultStatus
                if (realLen == 0) return ByteArray(0)

                val result = ByteArray(realLen)
                for (i in 0 until realLen) {
                    result[i] = (tempResultPtr + i).loadByte()
                }
                return result
            } else {
                // Slow path: allocate the exact buffer size and fetch the full response.
                val neededSize = -resultStatus

                val finalResPtr = allocator.allocate(neededSize)

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
