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
    private suspend fun compileAgainstHostApi(
        wasmline: Wasmline,
        implementation: EchoService,
        contract: KClass<EchoService> = EchoService::class,
    ) {
        wasmline.bind(implementation)
        wasmline.bind(contract, implementation)
        wasmline.bindAs<EchoService>(implementation)

        val linked = wasmline.link<EchoService>()
    }

    private suspend fun compileAgainstConvenienceOverloads(wasmline: Wasmline) {
        compileAgainstHostApi(wasmline, EchoServiceImpl())
    }
}

