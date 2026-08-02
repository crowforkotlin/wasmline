package crow.wasmline.web

import crow.wasmline.WasmlineLog
import crow.wasmline.internal.protocol.WasmlineResponseCodec
import crow.wasmline.invocation.WasmlineCallError
import crow.wasmline.invocation.WasmlineErrorCode
import kotlin.random.Random

/**
 * Live wasm plugin module driven by the web bindings toolkit.
 *
 * Owns the full host communication flow for one instantiated module:
 * - WASI preview1 shims (`fd_write`, `random_get`, `clock_time_get`)
 * - the `env.bridge_*` imports used by the Wasmline wasm-side bridge
 * - inbound calls through `__wasmline_wasi_entry`
 * - outbound calls routed to the host dispatcher
 *
 * All payloads cross the boundary as raw ByteArray; linear memory access
 * goes through [WebWasmMemory] so no interop leaks out of this class.
 *
 * 2026-07-29
 * @author crowforkotlin
 */
internal class WebWasmPlugin(binary: ByteArray) {

    private var dispatcher: ((String, ByteArray) -> ByteArray)? = null
    private var memory: WebWasmMemory? = null
    private val entryFunction: WebWasmFunction

    // Parameter staging shared with the wasm side through the env bridge.
    private var inboundAction: ByteArray = EMPTY
    private var inboundPayload: ByteArray = EMPTY
    private var inboundResponse: ByteArray = EMPTY
    private var outboundOverflow: ByteArray = EMPTY

    init {
        val module = WebWasmRuntime.compile(binary)
        val instance = WebWasmRuntime.instantiate(module, buildImports())
        memory = instance.memory()
        instance.function(INIT_EXPORT).invoke()
        entryFunction = instance.function(ENTRY_EXPORT)
    }

    /** Sets the outbound dispatcher for WASI host calls. */
    fun setDispatcher(dispatcher: (String, ByteArray) -> ByteArray) {
        this.dispatcher = dispatcher
    }

    /** Calls the wasm entry function with action and payload. */
    fun call(action: String, payload: ByteArray): ByteArray {
        inboundAction = action.encodeToByteArray()
        inboundPayload = payload
        inboundResponse = EMPTY
        outboundOverflow = EMPTY

        entryFunction.invoke(
            args = listOf(
                WebWasmValue.I32(inboundAction.size),
                WebWasmValue.I32(inboundPayload.size),
            ),
        )
        return inboundResponse
    }

    fun close() {
        dispatcher = null
        memory = null
        inboundAction = EMPTY
        inboundPayload = EMPTY
        inboundResponse = EMPTY
        outboundOverflow = EMPTY
    }

    /** Builds the import object with all necessary WASI and bridge functions. */
    private fun buildImports(): WebJsObject = WebWasmImportsBuilder()
        .function(
            module = WASI_MODULE,
            name = "fd_write",
            paramTypes = listOf(WebWasmType.I32, WebWasmType.I32, WebWasmType.I32, WebWasmType.I32),
            resultTypes = listOf(WebWasmType.I32),
        ) { args ->
            wasiFdWrite(
                fd = args[0].i32(),
                iovsPointer = args[1].i32(),
                iovsCount = args[2].i32(),
                writtenPointer = args[3].i32(),
            )
            listOf(WebWasmValue.I32(0))
        }
        .function(
            module = WASI_MODULE,
            name = "random_get",
            paramTypes = listOf(WebWasmType.I32, WebWasmType.I32),
            resultTypes = listOf(WebWasmType.I32),
        ) { args ->
            requireMemory().write(args[0].i32(), Random.nextBytes(args[1].i32()))
            listOf(WebWasmValue.I32(0))
        }
        .function(
            module = WASI_MODULE,
            name = "clock_time_get",
            paramTypes = listOf(WebWasmType.I32, WebWasmType.I64, WebWasmType.I32),
            resultTypes = listOf(WebWasmType.I32),
        ) { args ->
            val nanos = webNowMillis().toLong() * NANOS_PER_MILLI
            requireMemory().write(args[2].i32(), nanos.toLeBytes())
            listOf(WebWasmValue.I32(0))
        }
        .function(
            module = ENV_MODULE,
            name = "bridge_inbound_copy_params",
            paramTypes = listOf(WebWasmType.I32, WebWasmType.I32, WebWasmType.I32),
            resultTypes = emptyList(),
        ) { args ->
            val source = if (args[0].i32() == 0) inboundAction else inboundPayload
            requireMemory().write(args[1].i32(), source.copyOf(args[2].i32()))
            emptyList()
        }
        .function(
            module = ENV_MODULE,
            name = "bridge_inbound_set_response",
            paramTypes = listOf(WebWasmType.I32, WebWasmType.I32),
            resultTypes = emptyList(),
        ) { args ->
            val length = args[1].i32()
            inboundResponse = if (length == 0) EMPTY else requireMemory().read(args[0].i32(), length)
            emptyList()
        }
        .function(
            module = ENV_MODULE,
            name = "bridge_outbound_call_host",
            paramTypes = listOf(
                WebWasmType.I32,
                WebWasmType.I32,
                WebWasmType.I32,
                WebWasmType.I32,
                WebWasmType.I32,
                WebWasmType.I32,
            ),
            resultTypes = listOf(WebWasmType.I32),
        ) { args ->
            val status = outboundCallHost(
                actionPointer = args[0].i32(),
                actionLength = args[1].i32(),
                payloadPointer = args[2].i32(),
                payloadLength = args[3].i32(),
                outPointer = args[4].i32(),
                outCapacity = args[5].i32(),
            )
            listOf(WebWasmValue.I32(status))
        }
        .function(
            module = ENV_MODULE,
            name = "bridge_outbound_get_response",
            paramTypes = listOf(WebWasmType.I32),
            resultTypes = emptyList(),
        ) { args ->
            requireMemory().write(args[0].i32(), outboundOverflow)
            emptyList()
        }
        .build()

    /** Writes WASI stdout/stderr using fd_write shim. */
    private fun wasiFdWrite(fd: Int, iovsPointer: Int, iovsCount: Int, writtenPointer: Int) {
        val memory = requireMemory()
        val iovecs = memory.read(iovsPointer, iovsCount * IOVEC_SIZE)

        var total = 0
        val merged = StringBuilder()
        for (index in 0 until iovsCount) {
            val pointer = iovecs.readI32Le(index * IOVEC_SIZE)
            val length = iovecs.readI32Le(index * IOVEC_SIZE + 4)
            if (length > 0) merged.append(memory.readText(pointer, length))
            total += length
        }
        if (writtenPointer != 0) {
            memory.write(writtenPointer, total.toLeBytes())
        }

        val text = merged.toString().trimEnd()
        if (text.isEmpty()) return
        val log = WasmlineLog.logger
        when {
            fd == STDERR_FD && log != null -> log.error(text)
            log != null -> log.info(text)
            else -> println(text)
        }
    }

    /** Invokes the host dispatcher with action/payload; handles overflow responses. */
    private fun outboundCallHost(
        actionPointer: Int,
        actionLength: Int,
        payloadPointer: Int,
        payloadLength: Int,
        outPointer: Int,
        outCapacity: Int,
    ): Int {
        val memory = requireMemory()

        val action = memory.readText(actionPointer, actionLength)
        val payload = memory.read(payloadPointer, payloadLength)
        val response = try {
            val currentDispatcher = dispatcher
            if (currentDispatcher == null) {
                WasmlineResponseCodec.encodeFailure(
                    WasmlineCallError(
                        code = WasmlineErrorCode.ACTION_NOT_BOUND,
                        message = "No Wasmline outbound action is bound.",
                    ),
                )
            } else {
                currentDispatcher(action, payload)
            }
        } catch (error: Throwable) {
            WasmlineResponseCodec.encodeFailure(
                WasmlineCallError(
                    code = WasmlineErrorCode.HANDLER_FAILED,
                    message = error.message ?: "Wasmline outbound action handler failed.",
                ),
            )
        }

        // Responses larger than the guest buffer are handed over in a second
        // pass through bridge_outbound_get_response, signalled by a negative size.
        return if (response.size <= outCapacity) {
            memory.write(outPointer, response)
            response.size
        } else {
            outboundOverflow = response
            -response.size
        }
    }

    /** Returns the plugin's linear memory instance. */
    private fun requireMemory(): WebWasmMemory = checkNotNull(memory) { "Plugin memory is not initialized yet." }

    /** Constants used across the plugin lifecycle. */
    private companion object {
        val EMPTY = ByteArray(0)
        const val WASI_MODULE = "wasi_snapshot_preview1"
        const val ENV_MODULE = "env"
        const val INIT_EXPORT = "__wasmline_wasi_init"
        const val ENTRY_EXPORT = "__wasmline_wasi_entry"
        const val IOVEC_SIZE = 8
        const val STDERR_FD = 2
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

/** Returns the i32 value wrapped in this [WebWasmValue]. */
private fun WebWasmValue.i32(): Int = (this as WebWasmValue.I32).value

/** Reads a little-endian 32-bit integer from the buffer. */
private fun ByteArray.readI32Le(offset: Int): Int = (this[offset].toInt() and 0xFF) or
    ((this[offset + 1].toInt() and 0xFF) shl 8) or
    ((this[offset + 2].toInt() and 0xFF) shl 16) or
    ((this[offset + 3].toInt() and 0xFF) shl 24)

/** Converts an int to a 4-byte little-endian array. */
private fun Int.toLeBytes(): ByteArray = ByteArray(4) { index -> (this shr (index * 8)).toByte() }

/** Converts a long to an 8-byte little-endian array. */
private fun Long.toLeBytes(): ByteArray = ByteArray(8) { index -> (this shr (index * 8)).toByte() }
