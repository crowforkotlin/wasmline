package crow.wasmline.minimal

import crow.wasmline.Wasmline
import crow.wasmline.bind

fun main() {
    Wasmline.get().bind(object : GreetingService {
        override fun greet(name: String): String {
            println("[plugin] greet($name)")
            return "Hello, $name!"
        }
    })
}
