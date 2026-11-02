package com.example.nova

import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Проверка живого маршрута через `nova-router`.
 *
 * Приложение открывает SOCKS5 к локальному роутеру и просит соединить
 * `cp.cloudflare.com:80`. Успех означает, что цепочка «router → PRIMARY»
 * действительно работает, а не просто «процесс запущен».
 */
object SlotProbe {

    data class Result(
        val ok: Boolean,
        val latencyMs: Long,
        val detail: String,
    )

    fun run(
        routerPort: Int,
        host: String = "www.gstatic.com",
        targetPort: Int = 443,
        timeoutMs: Int = 5_000,
    ): Result {
        val primary = runOnce(routerPort, host, targetPort, timeoutMs)
        if (primary.ok) return primary
        val fallback = runOnce(routerPort, "1.1.1.1", targetPort, timeoutMs)
        return if (fallback.ok) {
            fallback.copy(detail = "1.1.1.1:$targetPort (имя $host не разрешилось)")
        } else {
            primary
        }
    }

    private fun runOnce(
        routerPort: Int,
        host: String,
        targetPort: Int,
        timeoutMs: Int,
    ): Result {
        if (routerPort !in 1..65535) {
            return Result(false, 0, "роутер не запущен")
        }
        val started = System.currentTimeMillis()
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", routerPort), timeoutMs)
                socket.soTimeout = timeoutMs
                val output: OutputStream = socket.getOutputStream()
                val input = DataInputStream(socket.getInputStream())

                output.write(byteArrayOf(5, 1, 0))
                output.flush()
                val greeting = ByteArray(2)
                input.readFully(greeting)
                if (greeting[0] != 5.toByte() || greeting[1] != 0.toByte()) {
                    return Result(false, 0, "SOCKS: метод не принят")
                }

                val name = host.toByteArray(Charsets.US_ASCII)
                val request = ByteArray(5 + name.size + 2)
                request[0] = 5
                request[1] = 1
                request[2] = 0
                request[3] = 3
                request[4] = name.size.toByte()
                name.copyInto(request, 5)
                request[5 + name.size] = (targetPort shr 8).toByte()
                request[6 + name.size] = targetPort.toByte()
                output.write(request)
                output.flush()

                val reply = ByteArray(4)
                input.readFully(reply)
                if (reply[0] != 5.toByte() || reply[1] != 0.toByte()) {
                    return Result(false, 0, "SOCKS: отказ ${reply[1].toInt() and 0xFF}")
                }
                // Дочитываем BND.ADDR/PORT, чтобы не оставить мусор в сокете.
                when (reply[3].toInt()) {
                    1 -> input.readFully(ByteArray(4 + 2))
                    4 -> input.readFully(ByteArray(16 + 2))
                    3 -> {
                        val len = input.read()
                        if (len > 0) input.readFully(ByteArray(len + 2))
                    }
                }
                Result(true, System.currentTimeMillis() - started, "$host:$targetPort")
            }
        }.getOrElse { error ->
            Result(false, System.currentTimeMillis() - started, error.javaClass.simpleName + ": " + error.message)
        }
    }
}
