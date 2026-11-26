package crow.wasmline.plugin.core.toolchain

import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.test.Test
import kotlin.test.assertFailsWith

class OverlappingFileLockTest {
    @Test
    fun `second lock on the same file fails in one JVM`() {
        val file = Files.createTempFile("wasmline-lock-", ".lock")
        try {
            FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { first ->
                first.lock().use {
                    assertFailsWith<OverlappingFileLockException> {
                        FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { second ->
                            second.lock()
                        }
                    }
                }
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
