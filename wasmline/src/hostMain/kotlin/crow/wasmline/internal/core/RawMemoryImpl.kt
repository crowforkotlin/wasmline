package crow.wasmline.internal.core

import crow.wasmline.RawMemory
import crow.wasmline.coreFailure
import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.invocation.WasmlineErrorCode
import crow.wasmline.invocation.WasmlineFailure

/**
 * Applies checked public memory operations to a backend memory implementation.
 *
 * Date: 2026-09-02
 * Author: crowforkotlin
 */
internal class RawMemoryImpl(
    private val backend: CoreWasmBackendMemory,
    private val beginOperation: () -> WasmlineFailure?,
    private val endOperation: () -> Unit,
) : RawMemory {
    override fun byteSize(): WasmlineCallResult<Long> = memoryOperation { backend.byteSize }

    override fun pageCount(): WasmlineCallResult<Long> = memoryOperation { backend.pageCount }

    override fun read(offset: Long, length: Int): WasmlineCallResult<ByteArray> = memoryOperation {
        val failure = checkMemoryInputRange(offset, length)
        if (failure != null) throw RawMemoryRangeException(failure)
        ByteArray(length).also { destination ->
            backend.readInto(destination, 0, offset, length)
        }
    }

    override fun readInto(destination: ByteArray, destinationOffset: Int, sourceOffset: Long, length: Int): WasmlineCallResult<Unit> {
        checkArrayRange(destination.size, destinationOffset, length)?.let { return WasmlineCallResult.Failure(it) }
        return memoryOperation {
            val failure = checkMemoryInputRange(sourceOffset, length)
            if (failure != null) throw RawMemoryRangeException(failure)
            backend.readInto(destination, destinationOffset, sourceOffset, length)
        }
    }

    override fun write(offset: Long, bytes: ByteArray): WasmlineCallResult<Unit> = memoryOperation {
        val failure = checkMemoryInputRange(offset, bytes.size)
        if (failure != null) throw RawMemoryRangeException(failure)
        backend.writeFrom(bytes, 0, offset, bytes.size)
    }

    override fun writeFrom(source: ByteArray, sourceOffset: Int, destinationOffset: Long, length: Int): WasmlineCallResult<Unit> {
        checkArrayRange(source.size, sourceOffset, length)?.let { return WasmlineCallResult.Failure(it) }
        return memoryOperation {
            val failure = checkMemoryInputRange(destinationOffset, length)
            if (failure != null) throw RawMemoryRangeException(failure)
            backend.writeFrom(source, sourceOffset, destinationOffset, length)
        }
    }

    override fun readUtf8(offset: Long, length: Int): WasmlineCallResult<String> = when (val bytes = read(offset, length)) {
        is WasmlineCallResult.Failure -> bytes
        is WasmlineCallResult.Success -> WasmlineCallResult.Success(bytes.value.decodeToString())
    }

    override fun grow(deltaPages: Long): WasmlineCallResult<Long> {
        if (deltaPages < 0) return coreFailure(WasmlineErrorCode.MEMORY_OUT_OF_BOUNDS, "Memory growth must not be negative.")
        return memoryOperation { backend.grow(deltaPages) }
    }

    private inline fun <T> memoryOperation(block: () -> T): WasmlineCallResult<T> {
        beginOperation()?.let { return WasmlineCallResult.Failure(it) }
        return try {
            WasmlineCallResult.Success(block())
        } catch (failure: RawMemoryRangeException) {
            WasmlineCallResult.Failure(failure.failure)
        } catch (failure: CoreWasmBackendFailure) {
            WasmlineCallResult.Failure(failure.failure)
        } catch (failure: Throwable) {
            coreFailure(
                WasmlineErrorCode.MEMORY_OUT_OF_BOUNDS,
                "Core Wasm memory operation failed: ${failure.message ?: failure}",
            )
        } finally {
            endOperation()
        }
    }
}

/**
 * Carries a checked memory-range failure through an internal operation block.
 *
 * Date: 2026-09-02
 * Author: crowforkotlin
 */
private class RawMemoryRangeException(val failure: WasmlineFailure) : RuntimeException(failure.message)

private fun checkMemoryInputRange(offset: Long, length: Int): WasmlineFailure? {
    if (offset < 0 || length < 0) {
        return WasmlineFailure(
            WasmlineErrorCode.MEMORY_OUT_OF_BOUNDS,
            "Linear memory range offset=$offset length=$length is invalid.",
        )
    }
    return null
}

private fun checkArrayRange(size: Int, offset: Int, length: Int): WasmlineFailure? {
    if (offset < 0 || length < 0 || offset > size || length > size - offset) {
        return WasmlineFailure(
            WasmlineErrorCode.MEMORY_OUT_OF_BOUNDS,
            "ByteArray range offset=$offset length=$length exceeds size=$size.",
        )
    }
    return null
}
