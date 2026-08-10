package crow.wasmline.plugin.core.component

private val versionToken = Regex("""(?<![A-Za-z0-9])v?(\d+(?:\.\d+)+(?:[-+][0-9A-Za-z.-]+)?)""")

/** Checks the semantic version reported by a pinned external tool. */
internal fun verifyToolVersion(toolName: String, output: String, expectedVersion: String): String {
    val expected = expectedVersion.trim().removePrefix("v")
    require(expected.isNotBlank()) { toolName + " expected version must not be blank." }
    val actual = versionToken.find(output)?.groupValues?.get(1)
        ?: error(toolName + " did not report a semantic version. Output: " + output.trim())
    check(actual == expected) {
        toolName + " version mismatch: expected " + expected + ", actual " + actual + "."
    }
    return output.trim()
}
