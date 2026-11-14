package crow.wasmline.loader.internal

internal actual class WasmlineCacheLock {
    actual fun <T> withLock(block: () -> T): T = synchronized(this, block)
}
