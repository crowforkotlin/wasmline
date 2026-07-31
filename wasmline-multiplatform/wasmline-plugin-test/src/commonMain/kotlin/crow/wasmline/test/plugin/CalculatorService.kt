package crow.wasmline.test.plugin

import crow.wasmline.WasmlineService

/**
 * Input data class for calculator operations.
 *
 * 2026-07-30
 * @author crowforkotlin
 */
data class CalculatorInput(val numbers: List<Int>, val operation: String)

/**
 * Output data class for calculator results.
 *
 * 2026-07-30
 * @author crowforkotlin
 */
data class CalculatorOutput(val result: Int)

/**
 * Calculator service interface for testing complex parameter passing.
 *
 * Supports sum, product, max, and min operations on integer lists.
 *
 * 2026-07-30
 * @author crowforkotlin
 */
interface CalculatorService : WasmlineService {
    fun calculate(input: CalculatorInput): CalculatorOutput
}

/**
 * Implementation of CalculatorService with list-based arithmetic.
 *
 * 2026-07-30
 * @author crowforkotlin
 */
class CalculatorServiceImpl : CalculatorService {
    override fun calculate(input: CalculatorInput): CalculatorOutput {
        val result = when (input.operation) {
            "sum" -> input.numbers.sum()
            "product" -> input.numbers.fold(1) { acc, i -> acc * i }
            "max" -> input.numbers.maxOrNull() ?: 0
            "min" -> input.numbers.minOrNull() ?: 0
            else -> throw IllegalArgumentException("Unknown operation: ${input.operation}")
        }
        return CalculatorOutput(result)
    }
}
