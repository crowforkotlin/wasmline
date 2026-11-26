package crow.wasmline.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CliPathSegmentTest {
    @Test
    fun `accepts dotted names and semantic versions`() {
        assertEquals("crow.wasmline.demo", requirePathSegment("crow.wasmline.demo", "plugin id"))
        assertEquals("1.0.0-beta2", requirePathSegment("1.0.0-beta2", "version"))
    }

    @Test
    fun `rejects path separators and NUL`() {
        assertFailsWith<IllegalArgumentException> { requirePathSegment("../evil", "name") }
        assertFailsWith<IllegalArgumentException> { requirePathSegment("nested/name", "name") }
        assertFailsWith<IllegalArgumentException> { requirePathSegment("nested\\name", "name") }
        assertFailsWith<IllegalArgumentException> { requirePathSegment("name\u0000suffix", "name") }
    }
}
