package crow.wasmline.samples.minimal.service

import crow.wasmline.Wasmline
import crow.wasmline.bind
import crow.wasmline.samples.minimal.service.common.GreetingService

fun main() {
    Wasmline.get().bind(object : GreetingService {
        override fun greet(name: String): String {
            println("[plugin] greet($name)")
            return "Hello, $name!"
        }
    })
}
