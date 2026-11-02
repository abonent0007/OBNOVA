package com.example.nova

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

class SlotProbeTest {

    private fun fakeSocksServer(replyCode: Byte): Pair<ServerSocket, Thread> {
        val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val thread = Thread {
            runCatching {
                server.accept().use { socket: Socket ->
                    val input = DataInputStream(socket.getInputStream())
                    val output: OutputStream = socket.getOutputStream()
                    val greeting = ByteArray(2)
                    input.readFully(greeting)
                    input.readFully(ByteArray(greeting[1].toInt()))
                    output.write(byteArrayOf(5, 0))
                    output.flush()
                    val header = ByteArray(4)
                    input.readFully(header)
                    when (header[3].toInt()) {
                        3 -> {
                            val len = input.read()
                            input.readFully(ByteArray(len + 2))
                        }
                    }
                    output.write(byteArrayOf(5, replyCode, 0, 1, 0, 0, 0, 0, 0, 0))
                    output.flush()
                    Thread.sleep(200)
                }
            }
        }.apply { isDaemon = true; start() }
        return server to thread
    }

    @Test
    fun `successful handshake reports latency`() {
        val (server, _) = fakeSocksServer(0)
        server.use {
            val result = SlotProbe.run(server.localPort, timeoutMs = 2_000)
            assertTrue(result.detail, result.ok)
            assertTrue(result.latencyMs >= 0)
        }
    }

    @Test
    fun `refused handshake reports error`() {
        val (server, _) = fakeSocksServer(5)
        server.use {
            val result = SlotProbe.run(server.localPort, timeoutMs = 2_000)
            assertFalse(result.ok)
            assertTrue(result.detail.contains("отказ"))
        }
    }

    @Test
    fun `missing router is reported without exception`() {
        val result = SlotProbe.run(0)
        assertFalse(result.ok)
        assertTrue(result.detail.contains("роутер"))
    }
}
