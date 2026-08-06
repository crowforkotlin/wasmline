package crow.wasmline.kotlin

import org.jetbrains.kotlin.config.CompilerConfiguration
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(CompilerConfiguration.Internals::class)
class WasmlineCommandLineProcessorTest {

    @Test
    fun compilerPluginOptionCanDisableIrGeneration() {
        val processor = WasmlineCommandLineProcessor()
        val option = processor.pluginOptions.single { it.optionName == ENABLE_COMPILER_PLUGIN_OPTION_NAME }
        val configuration = CompilerConfiguration()

        processor.processOption(option, "false", configuration)

        assertFalse(configuration.get(ENABLE_COMPILER_PLUGIN_OPTION, true))
    }

    @Test
    fun malformedCompilerPluginOptionKeepsIrGenerationEnabled() {
        val processor = WasmlineCommandLineProcessor()
        val option = processor.pluginOptions.single { it.optionName == ENABLE_COMPILER_PLUGIN_OPTION_NAME }
        val configuration = CompilerConfiguration()

        processor.processOption(option, "not-a-boolean", configuration)

        assertTrue(configuration.get(ENABLE_COMPILER_PLUGIN_OPTION, false))
    }
}
