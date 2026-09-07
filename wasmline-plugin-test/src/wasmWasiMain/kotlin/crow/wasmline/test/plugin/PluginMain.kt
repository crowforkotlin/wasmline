package crow.wasmline.test.plugin

import crow.wasmline.Wasmline
import crow.wasmline.bind

private val wasmline = Wasmline.get()

/**
 * Registers all services exposed by the integration test plugin.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */
fun main() {
    wasmline.bind(EchoServiceImpl())
    wasmline.bind(MathServiceImpl())
    wasmline.bind(CalculatorServiceImpl())
}
