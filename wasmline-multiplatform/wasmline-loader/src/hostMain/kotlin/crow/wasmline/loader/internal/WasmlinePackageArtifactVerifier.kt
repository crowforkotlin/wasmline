package crow.wasmline.loader.internal

import okio.ByteString.Companion.toByteString

/** Verifies package artifact bytes immediately before the native load handoff. */
internal object WasmlinePackageArtifactVerifier {
    fun verify(path: String, expectedSha256: String): String? {
        if (expectedSha256.isBlank()) {
            return "Verified package artifact '$path' is missing sha256 metadata."
        }

        val bytes = readHostFileBytes(path)
            ?: return "Verified package artifact '$path' could not be read for sha256 verification."
        val actualSha256 = bytes.toByteString().sha256().hex()
        return if (actualSha256.equals(expectedSha256, ignoreCase = true)) {
            null
        } else {
            "Verified package artifact '$path' failed sha256 verification before native loading. " +
                "Expected $expectedSha256, actual $actualSha256."
        }
    }
}
