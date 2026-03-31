package repro

import crow.wasmline.WasmlineService
import crow.wasmline.bind

interface EchoService : WasmlineService {
    fun echo(payload: ByteArray): ByteArray
}

class EchoServiceImpl : EchoService {
    override fun echo(payload: ByteArray): ByteArray = payload
}

fun install() {
    bind(EchoServiceImpl())
}

