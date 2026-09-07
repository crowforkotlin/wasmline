package crow.wasmline.plugin.core.toolchain

import java.io.File
import java.security.MessageDigest

/** File digest helpers shared by downloads and Component build results. */

internal object FileDigest {
    /** Calculates a lowercase SHA-256 digest. */
    fun sha256Hex(file: File): String {
        require(file.isFile) { "Cannot hash missing file: " + file.absolutePath }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}
