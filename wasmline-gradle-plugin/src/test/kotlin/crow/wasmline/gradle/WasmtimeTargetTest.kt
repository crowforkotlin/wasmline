package crow.wasmline.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class WasmtimeTargetTest {
    @Test
    fun detectsSupportedHostTargets() {
        val cases = listOf(
            Triple("Mac OS X", "aarch64", WasmtimeTarget.AARCH64_MACOS),
            Triple("Darwin", "x86_64", WasmtimeTarget.X86_64_MACOS),
            Triple("Linux", "arm64", WasmtimeTarget.AARCH64_LINUX),
            Triple("Linux", "amd64", WasmtimeTarget.X86_64_LINUX),
            Triple("Windows 11", "x64", WasmtimeTarget.X86_64_WINDOWS),
        )

        cases.forEach { (osName, osArch, expected) ->
            assertEquals(expected, WasmtimeTarget.fromHost(osName, osArch))
        }
    }

    @Test
    fun predefinedTargetsExposeStableWasmtimeNames() {
        assertEquals("pulley64", WasmtimeTarget.PULLEY_64.targetName)
        assertEquals("aarch64-android", WasmtimeTarget.AARCH64_ANDROID.targetName)
        assertEquals("x86_64-linux", WasmtimeTarget.X86_64_LINUX.targetName)
    }

    @Test
    fun allTargetsCoverEverySupportedAotPlatform() {
        assertEquals(
            listOf(
                "pulley32",
                "pulley64",
                "x86_64-linux",
                "aarch64-linux",
                "aarch64-android",
                "x86_64-android",
                "aarch64-macos",
                "x86_64-macos",
                "x86_64-windows",
            ),
            WasmtimeTarget.ALL.map(WasmtimeTarget::targetName),
        )
    }

    @Test
    fun customTargetsPreserveAdditionalTriples() {
        val custom = WasmtimeTarget.custom(" aarch64-linux-android ")

        assertEquals("aarch64-linux-android", custom.targetName)
        assertEquals(custom, WasmtimeTarget.custom("aarch64-linux-android"))
        assertSame(WasmtimeTarget.X86_64_LINUX, WasmtimeTarget.custom("x86_64-linux"))
        assertFailsWith<IllegalArgumentException> { WasmtimeTarget.custom(" ") }
    }

    @Test
    fun rejectsUnsupportedHostTargets() {
        assertFailsWith<IllegalStateException> {
            WasmtimeTarget.fromHost("Linux", "riscv64")
        }
        assertFailsWith<IllegalStateException> {
            WasmtimeTarget.fromHost("Windows 11", "arm64")
        }
    }
}
