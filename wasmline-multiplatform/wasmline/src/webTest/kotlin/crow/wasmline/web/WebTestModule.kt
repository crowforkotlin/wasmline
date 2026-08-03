package crow.wasmline.web

/**
 * Hand-encoded wasm binary used as a shared fixture by the web tests.
 *
 * Equivalent WAT source:
 * ```wat
 * (module
 *   (import "env" "host_add" (func $host_add (param i32 i32) (result i32)))
 *   (func (export "add") (param i32 i32) (result i32)
 *     local.get 0 local.get 1 i32.add)
 *   (func (export "add64") (param i64 i64) (result i64)
 *     local.get 0 local.get 1 i64.add)
 *   (func (export "call_host") (param i32 i32) (result i32)
 *     local.get 0 local.get 1 call $host_add)
 *   (memory (export "memory") 1))
 * ```
 *
 * 2026-07-29
 * @author crowforkotlin
 */
internal object WebTestModule {

    const val HOST_MODULE = "env"
    const val HOST_FUNCTION = "host_add"

    /** Raw module bytes assembled section by section. */
    fun bytes(): ByteArray = intArrayOf(
        0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,
        0x01, 0x0D, 0x02,
        0x60, 0x02, 0x7F, 0x7F, 0x01, 0x7F,
        0x60, 0x02, 0x7E, 0x7E, 0x01, 0x7E,
        0x02, 0x10, 0x01,
        0x03, 0x65, 0x6E, 0x76,
        0x08, 0x68, 0x6F, 0x73, 0x74, 0x5F, 0x61, 0x64, 0x64,
        0x00, 0x00,
        0x03, 0x04, 0x03, 0x00, 0x01, 0x00,
        0x05, 0x03, 0x01, 0x00, 0x01,
        0x07, 0x24, 0x04,
        0x03, 0x61, 0x64, 0x64, 0x00, 0x01,
        0x05, 0x61, 0x64, 0x64, 0x36, 0x34, 0x00, 0x02,
        0x09, 0x63, 0x61, 0x6C, 0x6C, 0x5F, 0x68, 0x6F, 0x73, 0x74, 0x00, 0x03,
        0x06, 0x6D, 0x65, 0x6D, 0x6F, 0x72, 0x79, 0x02, 0x00,
        0x0A, 0x1A, 0x03,
        0x07, 0x00, 0x20, 0x00, 0x20, 0x01, 0x6A, 0x0B,
        0x07, 0x00, 0x20, 0x00, 0x20, 0x01, 0x7C, 0x0B,
        0x08, 0x00, 0x20, 0x00, 0x20, 0x01, 0x10, 0x00, 0x0B,
    ).map(Int::toByte).toByteArray()

    /** Instantiates the fixture with the given host_add implementation. */
    fun instantiate(hostAdd: (Int, Int) -> Int = { a, b -> a + b }): WebWasmInstanceHandle {
        val imports = WebWasmImportsBuilder()
            .function(
                module = HOST_MODULE,
                name = HOST_FUNCTION,
                paramTypes = listOf(WebWasmType.I32, WebWasmType.I32),
                resultTypes = listOf(WebWasmType.I32),
            ) { params ->
                val a = (params[0] as WebWasmValue.I32).value
                val b = (params[1] as WebWasmValue.I32).value
                listOf(WebWasmValue.I32(hostAdd(a, b)))
            }
        return WebWasmRuntime.instantiate(WebWasmRuntime.compile(bytes()), imports)
    }
}
