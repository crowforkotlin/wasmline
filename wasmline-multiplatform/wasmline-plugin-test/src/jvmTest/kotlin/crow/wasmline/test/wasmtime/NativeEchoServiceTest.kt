package crow.wasmline.test.wasmtime

import crow.wasmline.link
import crow.wasmline.test.plugin.EchoService
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * End-to-end tests for EchoService validating string round-trip communication.
 *
 * 2026-07-30
 * @author crowforkotlin
 */
class NativeEchoServiceTest {

    /**
     * Tests echoing simple strings.
     */
    @Test
    fun echoesSimpleString() {
        NativePluginTestSupport.withLoadedPlugin { wasmline ->
            val echoService = wasmline.link<EchoService>()
            assertEquals("Hello, World!", echoService.echo("Hello, World!"))
            assertEquals("Wasmline", echoService.echo("Wasmline"))
        }
    }

    /**
     * Tests echoing with prefix.
     */
    @Test
    fun echoesWithPrefix() {
        NativePluginTestSupport.withLoadedPlugin { wasmline ->
            val echoService = wasmline.link<EchoService>()
            assertEquals("Prefix: Test Message", echoService.echoWithPrefix("Prefix: ", "Test Message"))
            assertEquals("[INFO] Log entry", echoService.echoWithPrefix("[INFO] ", "Log entry"))
        }
    }
}
