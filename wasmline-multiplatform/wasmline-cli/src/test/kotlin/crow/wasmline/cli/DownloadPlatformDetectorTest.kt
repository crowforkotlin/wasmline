package crow.wasmline.cli

import org.junit.Test
import kotlin.test.assertEquals

class DownloadPlatformDetectorTest {

    @Test
    fun `apple silicon should win over x64 jvm on macos`() {
        assertEquals(
            expected = "aarch64-macos",
            actual = DownloadPlatformDetector.detectPlatform(
                osName = "Mac OS X",
                osArch = "x86_64",
                macHardwareArm64 = true,
            ),
        )
    }

    @Test
    fun `intel mac should stay x64`() {
        assertEquals(
            expected = "x86_64-macos",
            actual = DownloadPlatformDetector.detectPlatform(
                osName = "Mac OS X",
                osArch = "x86_64",
                macHardwareArm64 = false,
            ),
        )
    }

    @Test
    fun `arm jvm on macos should map to aarch64`() {
        assertEquals(
            expected = "aarch64-macos",
            actual = DownloadPlatformDetector.detectPlatform(
                osName = "Mac OS X",
                osArch = "arm64",
            ),
        )
    }

    @Test
    fun `linux amd64 should map to x86_64 linux`() {
        assertEquals(
            expected = "x86_64-linux",
            actual = DownloadPlatformDetector.detectPlatform(
                osName = "Linux",
                osArch = "amd64",
            ),
        )
    }
}
