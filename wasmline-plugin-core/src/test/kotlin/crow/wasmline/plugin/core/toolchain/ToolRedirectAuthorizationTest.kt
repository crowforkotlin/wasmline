package crow.wasmline.plugin.core.toolchain

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import crow.wasmline.plugin.core.aot.AotCompilerCache
import crow.wasmline.plugin.core.aot.AotCompilerResolver
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.KeyStore
import java.util.Collections
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ToolRedirectAuthorizationTest {
    @Test
    fun toolDownloadCredentialsFollowOnlyTheOriginalOrigin() = verifyRedirects(aot = false)

    @Test
    fun aotDownloadCredentialsFollowOnlyTheOriginalOrigin() = verifyRedirects(aot = true)

    private fun verifyRedirects(aot: Boolean) = runBlocking {
        val directory = Files.createTempDirectory("wasmline-redirect-test").toFile()
        val originalSocketFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
        val origin = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val other = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try {
            val ssl = sslContext(directory)
            origin.httpsConfigurator = HttpsConfigurator(ssl)
            other.httpsConfigurator = HttpsConfigurator(ssl)
            HttpsURLConnection.setDefaultSSLSocketFactory(ssl.socketFactory)
            val token = "wasmline-test-fake-token"
            val bytes = byteArrayOf(0, 97, 115, 109)
            val received = Collections.synchronizedMap(mutableMapOf<String, String?>())
            val originUrl = "https://localhost:${origin.address.port}"
            origin.createContext("/asset") { exchange ->
                received["origin"] = exchange.requestHeaders.getFirst("Authorization")
                val destination = when (exchange.requestURI.query) {
                    "same" -> "$originUrl/payload"
                    "port" -> "https://localhost:${other.address.port}/payload"
                    "return" -> "https://127.0.0.1:${other.address.port}/return"
                    else -> "https://127.0.0.1:${other.address.port}/payload"
                }
                exchange.responseHeaders.add("Location", destination)
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }
            origin.createContext("/payload") { exchange ->
                received["payload"] = exchange.requestHeaders.getFirst("Authorization")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                exchange.close()
            }
            other.createContext("/payload") { exchange ->
                received["payload"] = exchange.requestHeaders.getFirst("Authorization")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                exchange.close()
            }
            other.createContext("/return") { exchange ->
                received["other"] = exchange.requestHeaders.getFirst("Authorization")
                exchange.responseHeaders.add("Location", "$originUrl/payload")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }
            origin.start()
            other.start()
            for (scenario in listOf("same", "host", "port", "return")) {
                received.clear()
                val destination = File(directory, "$scenario.wasm")
                if (aot) {
                    val resolver = AotCompilerResolver(AotCompilerCache(File(directory, "aot-cache")))
                    resolver.use {
                        val method = AotCompilerResolver::class.java.declaredMethods.single { it.name == "downloadFollowingRedirects" }
                        method.isAccessible = true
                        suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
                            val result = method.invoke(
                                resolver,
                                "$originUrl/asset?$scenario",
                                destination,
                                bytes.size.toLong(),
                                token,
                                continuation,
                            )
                            if (result === COROUTINE_SUSPENDED) COROUTINE_SUSPENDED else Unit
                        }
                    }
                } else {
                    val spec = ToolAssetSpec(
                        tool = WasmlineTool.WASI_PREVIEW1_REACTOR_ADAPTER,
                        version = "0.0.1",
                        platform = "universal",
                        assetId = 1,
                        size = bytes.size.toLong(),
                        archiveName = "$scenario.wasm",
                        downloadUrl = "$originUrl/asset?$scenario",
                        sha256 = File(directory, "digest.wasm").apply { writeBytes(bytes) }.let(FileDigest::sha256Hex),
                        distribution = ToolDistribution.FILE,
                        entryFileName = "$scenario.wasm",
                        executable = false,
                    )
                    ToolDownloader().use { downloader ->
                        val installed = downloader.resolveOrDownload(spec, ToolCache(File(directory, "tool-$scenario")), token)
                        installed.file.copyTo(destination)
                    }
                }
                assertContentEquals(bytes, destination.readBytes())
                assertEquals("Bearer $token", received["origin"], scenario)
                assertEquals(if (scenario == "same") "Bearer $token" else null, received["payload"], scenario)
                if (scenario == "return") assertEquals(null, received["other"])
            }
        } finally {
            origin.stop(0)
            other.stop(0)
            HttpsURLConnection.setDefaultSSLSocketFactory(originalSocketFactory)
            directory.deleteRecursively()
        }
    }

    private fun sslContext(directory: File): SSLContext {
        val password = "wasmline-local-test".toCharArray()
        val storeFile = File(directory, "localhost.p12")
        val suffix = if (System.getProperty("os.name").startsWith("Windows", true)) ".exe" else ""
        ExternalToolRunner().run(
            File(System.getProperty("java.home"), "bin/keytool$suffix"),
            listOf(
                "-genkeypair",
                "-alias",
                "localhost",
                "-keyalg",
                "RSA",
                "-keystore",
                storeFile.absolutePath,
                "-storetype",
                "PKCS12",
                "-storepass",
                String(password),
                "-keypass",
                String(password),
                "-dname",
                "CN=localhost",
                "-ext",
                "SAN=dns:localhost,ip:127.0.0.1",
                "-validity",
                "1",
                "-noprompt",
            ),
        )
        val store = KeyStore.getInstance("PKCS12").apply { storeFile.inputStream().use { load(it, password) } }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
        val trusts = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        return SSLContext.getInstance("TLS").apply { init(keys.keyManagers, trusts.trustManagers, null) }
    }
}
