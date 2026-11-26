package crow.wasmline.web

import kotlin.test.Test
import kotlin.test.assertFailsWith

class WebImportNamespaceTest {
    @Test
    fun `reserved import namespaces are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            WebWasmImportsBuilder().rawFunction("__proto__", "x") { null }
        }
        assertFailsWith<IllegalArgumentException> {
            WebWasmImportsBuilder().rawFunction("constructor", "x") { null }
        }
    }
}
