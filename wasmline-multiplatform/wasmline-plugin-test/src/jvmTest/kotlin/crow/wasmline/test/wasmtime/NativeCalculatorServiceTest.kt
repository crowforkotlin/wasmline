package crow.wasmline.test.wasmtime

import crow.wasmline.link
import crow.wasmline.test.plugin.CalculatorInput
import crow.wasmline.test.plugin.CalculatorOutput
import crow.wasmline.test.plugin.CalculatorService
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * End-to-end tests for list-based calculator calls.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */
class NativeCalculatorServiceTest {

    /**
     * Tests arithmetic operations with non-empty and negative input lists.
     */
    @Test
    fun calculatesListOperations() {
        NativePluginTestSupport.withLoadedPlugin { wasmline ->
            val calculator = wasmline.link<CalculatorService>()

            assertEquals(CalculatorOutput(15), calculator.calculate(CalculatorInput(listOf(1, 2, 3, 4, 5), "sum")))
            assertEquals(CalculatorOutput(120), calculator.calculate(CalculatorInput(listOf(1, 2, 3, 4, 5), "product")))
            assertEquals(CalculatorOutput(9), calculator.calculate(CalculatorInput(listOf(-4, 9, 2, 7), "max")))
            assertEquals(CalculatorOutput(-4), calculator.calculate(CalculatorInput(listOf(-4, 9, 2, 7), "min")))
        }
    }

    /**
     * Tests empty list behavior for operations with defined fallback values.
     */
    @Test
    fun handlesEmptyListBoundaries() {
        NativePluginTestSupport.withLoadedPlugin { wasmline ->
            val calculator = wasmline.link<CalculatorService>()

            assertEquals(CalculatorOutput(0), calculator.calculate(CalculatorInput(emptyList(), "sum")))
            assertEquals(CalculatorOutput(1), calculator.calculate(CalculatorInput(emptyList(), "product")))
            assertEquals(CalculatorOutput(0), calculator.calculate(CalculatorInput(emptyList(), "max")))
            assertEquals(CalculatorOutput(0), calculator.calculate(CalculatorInput(emptyList(), "min")))
        }
    }
}
