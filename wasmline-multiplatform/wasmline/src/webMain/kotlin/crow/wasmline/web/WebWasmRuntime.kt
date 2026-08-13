package crow.wasmline.web

/** Failure raised by the web WebAssembly runtime wrappers. */
internal class WebWasmException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Shared entry point to the `WebAssembly` JS API.
 *
 * Compilation and instantiation are funneled through this object so shared
 * code never calls the binding primitives directly, and every failure
 * surfaces as a [WebWasmException] carrying the failed stage.
 *
 * Date: 2026-07-29
 * Author: crowforkotlin
 */
internal object WebWasmRuntime {

    fun compile(binary: ByteArray): WebWasmModule = runStage("compilation") {
        require(binary.isNotEmpty()) { "Wasm binary is empty." }
        webCompileWasm(binary)
    }

    fun instantiate(module: WebWasmModule, imports: WebJsObject): WebWasmInstanceHandle = runStage("instantiation") {
        WebWasmInstanceHandle(webInstantiateWasm(module, imports))
    }

    fun instantiate(module: WebWasmModule, imports: WebWasmImportsBuilder): WebWasmInstanceHandle = instantiate(module, imports.build())

    private inline fun <T> runStage(stage: String, block: () -> T): T = try {
        block()
    } catch (failure: WebWasmException) {
        throw failure
    } catch (failure: Throwable) {
        throw WebWasmException("WebAssembly $stage failed: ${failure.message ?: failure}", failure)
    }
}

/**
 * Wrapper around a live `WebAssembly.Instance`.
 *
 * Exported functions and memories are handed out as small typed handles
 * instead of raw JS values.
 *
 * Date: 2026-07-29
 * Author: crowforkotlin
 */
internal class WebWasmInstanceHandle internal constructor(private val instance: WebWasmInstance) {
    val exports: WebJsObject = webExportsOf(instance)

    fun functionOrNull(name: String): WebWasmFunction? {
        val value = webObjectRead(exports, name) ?: return null
        return if (webIsFunction(value)) WebWasmFunction(name, value) else null
    }

    fun function(name: String): WebWasmFunction = functionOrNull(name)
        ?: throw WebWasmException("Export '$name' is missing or is not a function.")

    fun memoryOrNull(name: String = DEFAULT_MEMORY_EXPORT): WebWasmMemory? {
        val value = webObjectRead(exports, name) ?: return null
        return if (webIsWasmMemory(value)) WebWasmMemory(value) else null
    }

    fun memory(name: String = DEFAULT_MEMORY_EXPORT): WebWasmMemory = memoryOrNull(name)
        ?: throw WebWasmException("Export '$name' is missing or is not a WebAssembly.Memory.")

    private companion object {
        const val DEFAULT_MEMORY_EXPORT = "memory"
    }
}

/**
 * Invoker for a single exported wasm function.
 *
 * Arguments and results travel as [WebWasmValue], so call sites declare
 * the expected result types explicitly instead of guessing from raw numbers.
 *
 * Date: 2026-07-29
 * Author: crowforkotlin
 */
internal class WebWasmFunction internal constructor(private val name: String, private val function: WebJsValue) {
    fun invoke(args: List<WebWasmValue> = emptyList(), resultTypes: List<WebWasmType> = emptyList()): List<WebWasmValue> {
        val encodedArgs = webArrayOf(args.map(WebWasmValueCodec::encode))
        val result = try {
            webCallFunction(function, encodedArgs)
        } catch (failure: Throwable) {
            throw WebWasmException("Invocation of wasm export '$name' failed: ${failure.message ?: failure}", failure)
        }
        return WebWasmValueCodec.decodeResults(result, resultTypes)
    }
}

/**
 * Byte-level accessor for a `WebAssembly.Memory` export.
 *
 * A fresh view is created per operation because `memory.buffer` is detached
 * and replaced whenever the module grows its memory.
 *
 * Date: 2026-07-29
 * Author: crowforkotlin
 */
internal class WebWasmMemory internal constructor(private val memory: WebJsValue) {
    fun read(pointer: Int, length: Int): ByteArray {
        if (length == 0) return ByteArray(0)
        return webBytesCopyOut(webMemoryBytes(memory, pointer, length))
    }

    fun write(pointer: Int, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        webBytesCopyIn(webMemoryBytes(memory, pointer, bytes.size), bytes)
    }

    fun readText(pointer: Int, length: Int): String = read(pointer, length).decodeToString()
}
