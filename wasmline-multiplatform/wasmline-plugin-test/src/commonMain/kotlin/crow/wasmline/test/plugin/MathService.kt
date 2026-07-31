package crow.wasmline.test.plugin

import crow.wasmline.WasmlineService

/**
 * Math service interface for testing basic arithmetic operations.
 *
 * Provides add, subtract, and multiply operations to validate
 * host-to-WASM bidirectional communication.
 *
 * 2026-07-30
 * @author crowforkotlin
 */
interface MathService : WasmlineService {
    fun add(a: Int, b: Int): Int
    fun subtract(a: Int, b: Int): Int
    fun multiply(a: Long, b: Long): Long
}

/**
 * Implementation of MathService with straightforward arithmetic.
 *
 * 2026-07-30
 * @author crowforkotlin
 */
class MathServiceImpl : MathService {
    override fun add(a: Int, b: Int): Int = a + b
    override fun subtract(a: Int, b: Int): Int = a - b
    override fun multiply(a: Long, b: Long): Long = a * b
}
