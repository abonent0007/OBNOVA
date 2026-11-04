package com.example.nova

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Проверки единого загрузчика лент: код ответа, декодирование и потолок тела.
 *
 * Сервер поднимается локальный и одноразовый: тест не ходит в сеть и не зависит
 * от того, доступен ли GitHub с машины сборки. `com.sun.net.httpserver` здесь не
 * используется намеренно — в unit-тестах Android его нет на classpath.
 */
class FeedHttpTest {

    private class FakeServer(private val status: Int, private val body: ByteArray) {
        private val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val worker = thread(start = true, isDaemon = true, name = "feed-http-test") {
            runCatching {
                socket.accept().use { client ->
                    client.soTimeout = 5_000
                    val reader = client.getInputStream().bufferedReader()
                    // Заголовки запроса до пустой строки; тело GET не нужно.
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val reason = if (status == 200) "OK" else "Error"
                    val header = buildString {
                        append("HTTP/1.1 $status $reason\r\n")
                        append("Content-Length: ${body.size}\r\n")
                        append("Connection: close\r\n")
                        append("\r\n")
                    }
                    val output = client.getOutputStream()
                    output.write(header.toByteArray(Charsets.US_ASCII))
                    output.write(body)
                    output.flush()
                }
            }
        }

        fun url(): String = "http://127.0.0.1:${socket.localPort}/feed"

        fun close() {
            runCatching { socket.close() }
            worker.join(1_000)
        }
    }

    private fun policy(maxBytes: Int = 64 * 1024) = FeedHttp.Policy(
        connectTimeoutMs = 2_000,
        readTimeoutMs = 2_000,
        maxBytes = maxBytes,
    )

    private fun <T> withServer(status: Int, body: String, block: (String) -> T): T {
        val server = FakeServer(status, body.toByteArray(Charsets.UTF_8))
        return try {
            block(server.url())
        } finally {
            server.close()
        }
    }

    @Test
    fun `reads a text feed with the requested limits`() {
        withServer(status = 200, body = "привет, лента") { url ->
            assertEquals("привет, лента", FeedHttp.text(url, policy()))
        }
    }

    @Test
    fun `non-200 is an error, not an empty body`() {
        withServer(status = 404, body = "") { url ->
            try {
                FeedHttp.text(url, policy())
                fail("404 должен был стать ошибкой")
            } catch (expected: IOException) {
                assertTrue(expected.message.orEmpty().contains("404"))
            }
        }
    }

    @Test
    fun `a body over the limit is rejected instead of being buffered whole`() {
        withServer(status = 200, body = "x".repeat(4096)) { url ->
            try {
                FeedHttp.bytes(url, policy(maxBytes = 64))
                fail("тело больше потолка должно было стать ошибкой")
            } catch (expected: IOException) {
                assertTrue(expected.message.orEmpty().contains("exceeds"))
            }
        }
    }

    @Test
    fun `invalid policy is rejected before any request`() {
        try {
            FeedHttp.Policy(connectTimeoutMs = 0, readTimeoutMs = 1, maxBytes = 1)
            fail("нулевой тайм-аут должен быть ошибкой")
        } catch (_: IllegalArgumentException) {
            // ожидаемо
        }
    }
}
