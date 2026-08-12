package crow.wasmline.sample.component

import crow.wasmline.Wasmline
import crow.wasmline.bind
import crow.wasmline.link

private val wasmline = Wasmline.get()
private var initializationCount = 0

fun main() {
    initializationCount += 1
    wasmline.bind(
        object : ComponentPluginService {
            override fun echo(request: ComponentEchoRequest): ComponentEchoResponse =
                ComponentEchoResponse("plugin:${request.value}")

            override fun callback(payload: ByteArray): ByteArray =
                wasmline.link<ComponentHostService>().callback(payload)

            override fun empty(): ByteArray = ByteArray(0)

            override fun trap() {
                error("Intentional Component trap from the sample plugin.")
            }

            override fun initializationCount(): Int = initializationCount
        },
    )
}
