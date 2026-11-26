package crow.wasmline.web

import kotlin.test.Test
import kotlin.test.assertContentEquals

class WebSecureRandomTest {
    @Test
    fun `secure random uses the platform crypto source`() {
        js("crypto.getRandomValues = function(array) { array.fill(0xAB); return array; }")
        assertContentEquals(ByteArray(8) { 0xAB.toByte() }, webSecureRandomBytes(8))
    }
}
