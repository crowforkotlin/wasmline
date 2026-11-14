package crow.wasmline.loader.internal

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal actual class WasmlineCacheLock {
    private val mutex = Mutex()

    actual fun <T> withLock(block: () -> T): T = runBlocking { mutex.withLock { block() } }
}
