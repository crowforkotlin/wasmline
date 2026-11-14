package crow.wasmline.network.ktor

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KtorNetworkClientTest {
    @Test
    fun sinkFailureStopsReceptionAfterTheFirstSegment() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val sinkCalled = CountDownLatch(1)
        val finished = CountDownLatch(1)
        var firstSegmentReachedSink = false
        var sentBytes = 0
        server.createContext("/artifact") { exchange ->
            try {
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { output ->
                    output.write(ByteArray(1024))
                    output.flush()
                    sentBytes += 1024
                    firstSegmentReachedSink = sinkCalled.await(2, TimeUnit.SECONDS)
                    if (!firstSegmentReachedSink) {
                        output.write(ByteArray(1024 * 1024))
                        output.flush()
                        sentBytes += 1024 * 1024
                    }
                }
            } finally {
                exchange.close()
                finished.countDown()
            }
        }
        server.start()
        val client = HttpClient(CIO)
        try {
            val failure = assertFailsWith<IllegalStateException> {
                KtorNetworkClient(client).fetchTo("http://127.0.0.1:${server.address.port}/artifact") { _, _, _ ->
                    sinkCalled.countDown()
                    error("test-artifact-limit")
                }
            }
            assertEquals("test-artifact-limit", failure.message)
            assertTrue(finished.await(3, TimeUnit.SECONDS))
            assertTrue(firstSegmentReachedSink)
            assertEquals(1024, sentBytes)
        } finally {
            client.close()
            server.stop(0)
        }
    }

    @Test
    fun streamsSuccessfulBodiesAndSkipsErrorBodies() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/artifact") { exchange ->
            exchange.sendResponseHeaders(200, 3)
            exchange.responseBody.use { it.write(byteArrayOf(1, 2, 3)) }
            exchange.close()
        }
        server.createContext("/missing") { exchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        server.start()
        val client = HttpClient(CIO)
        try {
            val adapter = KtorNetworkClient(client)
            val bytes = mutableListOf<Byte>()
            val status = adapter.fetchTo("http://127.0.0.1:${server.address.port}/artifact") { buffer, offset, count ->
                bytes += buffer.copyOfRange(offset, offset + count).toList()
            }
            assertEquals(200, status.statusCode)
            assertEquals(listOf<Byte>(1, 2, 3), bytes)
            assertEquals(
                404,
                adapter.fetchTo("http://127.0.0.1:${server.address.port}/missing") { _, _, _ ->
                    error("Error body must not reach sink")
                }.statusCode,
            )
        } finally {
            client.close()
            server.stop(0)
        }
    }
}
