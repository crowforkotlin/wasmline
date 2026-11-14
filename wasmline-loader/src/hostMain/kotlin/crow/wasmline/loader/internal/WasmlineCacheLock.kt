package crow.wasmline.loader.internal

/** Serializes short cache-capacity operations, never network calls. */
internal expect class WasmlineCacheLock() {
    fun <T> withLock(block: () -> T): T
}
