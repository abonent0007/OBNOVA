package com.example.nova

import android.content.Context
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Проверка сервисов через живой `nova-router`.
 *
 * Сам экран живёт вне VPN (собственный пакет исключён из TUN), поэтому «просто
 * открыть сайт» из приложения нельзя: запрос уйдёт мимо туннеля. Здесь тот же
 * путь, что у tun2proxy, — SOCKS5 на 127.0.0.1:1373, затем настоящий TLS до
 * сайта. Так видно не «VPN включён», а проходит ли конкретный сервис.
 *
 * Адреса проб берутся из актуальных списков: первый подходящий домен из
 * `main.txt` для RU, из `second.txt` для US, из `youtube.txt` и `telegram.txt`
 * для сервисов. Зашитые хосты остаются только запасным вариантом, если списки
 * ещё не загрузились.
 */
object ServiceProbe {

    data class Result(
        val name: String,
        val ok: Boolean,
        val detail: String,
    )

    data class Target(
        val name: String,
        val hosts: List<String>,
    )

    private val FALLBACK_RU = listOf("ya.ru", "mail.ru", "yandex.ru")
    private val FALLBACK_US = listOf("www.cnn.com", "example.org", "www.wikipedia.org")
    private val FALLBACK_YOUTUBE = listOf("www.youtube.com", "youtube.com")
    private val FALLBACK_TELEGRAM = listOf("telegram.org", "t.me")

    fun targetsFor(lists: RoutingListsSnapshot): List<Target> = listOf(
        Target("RU", chooseHosts(FALLBACK_RU, lists.mainDomains())),
        Target("US", chooseHosts(FALLBACK_US, lists.secondDomains())),
        Target("YouTube", chooseHosts(FALLBACK_YOUTUBE, lists.youtubeDomains())),
        Target("Telegram", chooseHosts(FALLBACK_TELEGRAM, lists.telegramDomains())),
    )

    fun run(
        context: Context? = null,
        routerPort: Int? = null,
        timeoutMs: Int = 7_000,
    ): List<Result> {
        // Порт берём из status.json: при занятом 1373 роутер выбирает свободный,
        // и жёсткая константа давала бы ложное «MULTI не активен».
        val port = routerPort
            ?: context?.let { RouterStatus.readPort(RouterProcess.statusFile(it)) }
                ?.takeIf { it in 1..65535 }
            ?: RouterProcess.DEFAULT_PORT
        if (!routerReachable(port)) {
            return listOf(
                Result(
                    "MULTI",
                    false,
                    "router 127.0.0.1:$port закрыт — проверка доступна только в MULTI",
                ),
            )
        }
        val lists = context?.let { ListRepository.load(it) } ?: RoutingListsSnapshot.EMPTY
        return targetsFor(lists).map { target -> check(port, target, timeoutMs) }
    }

    private fun chooseHosts(preferred: List<String>, listed: List<String>): List<String> {
        // Списки — не каталог для проб: main.txt начинается с 1337x.to и
        // accounts.google.com, и первый же домен дал бы ложный отказ. Берём
        // только проверенные хосты, а список подтверждает, что категория жива.
        val fromList = preferred.filter { host -> matchesAny(host, listed) }
        return (fromList + preferred).distinct()
    }

    private fun matchesAny(host: String, domains: List<String>): Boolean =
        domains.any { domain -> host == domain || host.endsWith(".$domain") }

    private fun routerReachable(routerPort: Int): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", routerPort), 800)
        }
        true
    } catch (_: Exception) {
        false
    }

    private fun check(routerPort: Int, target: Target, timeoutMs: Int): Result {
        var lastError = "нет адресов для проверки"
        for (host in target.hosts) {
            val startedAt = System.currentTimeMillis()
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", routerPort), timeoutMs)
                    socket.soTimeout = timeoutMs
                    socksConnect(socket.getOutputStream(), socket.getInputStream(), host, 443)

                    val factory = javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory
                    val tls = factory.createSocket(socket, host, 443, true) as javax.net.ssl.SSLSocket
                    tls.soTimeout = timeoutMs
                    tls.startHandshake()
                    val output = tls.outputStream
                    output.write(
                        (
                            "HEAD / HTTP/1.1\r\n" +
                                "Host: $host\r\n" +
                                "Connection: close\r\n" +
                                "User-Agent: OBNOVA-Android\r\n\r\n"
                            ).toByteArray(Charsets.US_ASCII)
                    )
                    output.flush()
                    val statusLine = tls.inputStream.bufferedReader().readLine().orEmpty()
                    val code = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
                    val elapsed = System.currentTimeMillis() - startedAt
                    if (code in 200..399) {
                        return Result(target.name, true, "$host · OK $code · $elapsed мс")
                    }
                    lastError = "$host · HTTP ${statusLine.ifBlank { "нет ответа" }}"
                }
            } catch (error: Exception) {
                lastError = "$host · ${error.javaClass.simpleName}: " +
                    error.message.orEmpty().take(80)
            }
        }
        return Result(target.name, false, lastError)
    }

    private fun socksConnect(output: OutputStream, input: InputStream, host: String, port: Int) {
        output.write(byteArrayOf(5, 1, 0))
        output.flush()
        val greeting = ByteArray(2)
        readFully(input, greeting)
        require(greeting[0] == 5.toByte() && greeting[1] == 0.toByte()) {
            "SOCKS5: метод не принят"
        }

        val hostBytes = host.toByteArray(Charsets.US_ASCII)
        val request = ByteArray(7 + hostBytes.size)
        request[0] = 5
        request[1] = 1
        request[2] = 0
        request[3] = 3
        request[4] = hostBytes.size.toByte()
        hostBytes.copyInto(request, 5)
        request[5 + hostBytes.size] = ((port shr 8) and 0xFF).toByte()
        request[6 + hostBytes.size] = (port and 0xFF).toByte()
        output.write(request)
        output.flush()

        val reply = ByteArray(4)
        readFully(input, reply)
        require(reply[0] == 5.toByte() && reply[1] == 0.toByte()) {
            "SOCKS5: отказ ${reply[1]}"
        }
        val addressType = reply[3].toInt()
        val skip = when (addressType) {
            1 -> 4 + 2
            4 -> 16 + 2
            3 -> {
                val length = ByteArray(1)
                readFully(input, length)
                length[0].toInt() + 2
            }
            else -> error("SOCKS5: ATYP $addressType")
        }
        readFully(input, ByteArray(skip))
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) error("SOCKS5: соединение закрыто")
            offset += read
        }
    }
}
