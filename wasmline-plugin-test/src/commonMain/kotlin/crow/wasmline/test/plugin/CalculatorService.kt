package crow.wasmline.test.plugin

import crow.wasmline.WasmlineService
import kotlinx.serialization.Serializable

/**
 * Input data class for calculator operations.
 *
 * Date: 2026-07-30
 * Author: crowforkotlin
 */
@Serializable
data class CalculatorInput(val numbers: List<Int>, val operation: String)

/**
 * Output data class for calculator results.
 *
 * Date: 2026-07-30
 * Author: crowforkotlin
 */
@Serializable
data class CalculatorOutput(val result: Int)

/**
 * Calculator service interface for testing complex parameter passing.
 *
 * Supports sum, product, max, and min operations on integer lists.
 *
 * Date: 2026-07-30
 * Author: crowforkotlin
 */
interface CalculatorService : WasmlineService {
    fun calculate(input: CalculatorInput): CalculatorOutput
}

/**
 * Implementation of CalculatorService with list-based arithmetic.
 *
 * Date: 2026-07-30
 * Author: crowforkotlin
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
