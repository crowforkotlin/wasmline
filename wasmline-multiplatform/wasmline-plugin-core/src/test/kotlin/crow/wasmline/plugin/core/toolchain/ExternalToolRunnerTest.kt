package crow.wasmline.plugin.core.toolchain

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExternalToolRunnerTest {
    @Test
    fun runsWithoutShellAndCapturesOutput() {
        val result = ExternalToolRunner().run(javaExecutable(), listOf("-version"))

        assertEquals(0, result.exitCode)
        assertTrue(result.command.first().endsWith("java") || result.command.first().endsWith("java.exe"))
        assertTrue(result.output.contains("version", ignoreCase = true))
    }

    @Test
    fun reportsNonZeroExitCodeWithCommand() {
        val error = assertFailsWith<ToolExecutionException> {
            ExternalToolRunner().run(javaExecutable(), listOf("-Xwasmline-invalid-option"))
        }

        assertTrue(error.message.orEmpty().contains("java"))
        assertEquals(1, error.result?.exitCode)
    }

    @Test
    fun terminatesTimedOutProcess() {
        val error = assertFailsWith<ToolExecutionException> {
            ExternalToolRunner(defaultTimeoutMillis = 50).run(
                executable = javaExecutable(),
                arguments = listOf(
                    "-cp",
                    System.getProperty("java.class.path"),
                    ExternalToolRunnerSleepMain::class.java.name,
                ),
            )
        }

        assertTrue(error.message.orEmpty().contains("timed out", ignoreCase = true))
        assertTrue(error.result != null)
    }

    private fun javaExecutable(): File {
        val suffix = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) ".exe" else ""
        return File(System.getProperty("java.home"), "bin/java$suffix")
    }
}

private object ExternalToolRunnerSleepMain {
    @JvmStatic
    fun main(args: Array<String>) {
        Thread.sleep(5_000)
    }
}
