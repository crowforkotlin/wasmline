package crow.wasmline

import kotlin.reflect.KClass

@Suppress("unused")
class WasmlineHostApiCompileTest {
    private interface EchoService : WasmlineService {
        fun echo(payload: ByteArray): ByteArray
    }

    private class EchoServiceImpl : EchoService {
        override fun echo(payload: ByteArray): ByteArray = payload
    }

    @Suppress("UNUSED_VARIABLE")
    private fun compileAgainstHostApi(
        wasmline: Wasmline,
        implementation: EchoService,
        contract: KClass<EchoService> = EchoService::class,
    ) {
        val loadState = Wasmline.load(filepath = "plugin.pwasm")

        Wasmline.init()
        Wasmline.shutdown()

        wasmline.bind(implementation)
        wasmline.bind(contract, implementation)

        val linked = wasmline.link<EchoService>()

        wasmline.close()
    }

    private fun compileAgainstConvenienceOverloads(wasmline: Wasmline) {
        compileAgainstHostApi(wasmline, EchoServiceImpl())
    }
}

