package crow.wasmline

import crow.wasmline.loader.WasmlineLoadRequest
import crow.wasmline.loader.WasmlineLoader
import crow.wasmline.loader.WasmlineSource
import kotlin.reflect.KClass

/** Compile-only smoke test for the public host API overloads. */
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
        val result = WasmlineLoader.load(source = "plugin.pwasm")
        val requestResult = WasmlineLoader.load(
            WasmlineLoadRequest(source = WasmlineSource.LocalArtifactPath("plugin.pwasm")),
        )

        WasmlineLoader.bootstrap()
        WasmlineLoader.warmup(WasmlineWarmupMode.PULLEY)
        WasmlineLoader.shutdown()

        wasmline.bind(implementation)
        wasmline.bind(contract, implementation)

        val linked = wasmline.link<EchoService>()

        wasmline.close()
    }

    private suspend fun compileAgainstConvenienceOverloads(wasmline: Wasmline) {
        compileAgainstHostApi(wasmline, EchoServiceImpl())
    }
}
