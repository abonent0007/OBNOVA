package com.example.nova

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Единый загрузчик внешних лент.
 *
 * Зачем отдельный объект. Раньше каждый фид (DNS-туннель, Opera, списки
 * маршрутизации) нёс свою копию `openConnection`, и копии разошлись: у одной был
 * потолок тела, у другой нет, а запасной загрузчик подписки в `WarpConfigsActivity`
 * вообще шёл через `URL.openStream()` без тайм-аутов — зависший хост держал поток
 * и диалог прогресса бесконечно. Один вход с обязательными сроками и потолком
 * убирает этот класс дефектов: новый источник нельзя добавить «мимо» проверок.
 *
 * Потолок — не украшение: тело читается порциями и при превышении [Policy.maxBytes]
 * выбрасывается [IOException], а не собирается в память целиком. Вызывающий код
 * обязан поймать ошибку и перейти к следующему зеркалу или запасному источнику.
 */
internal object FeedHttp {

    /**
     * @param connectTimeoutMs срок установки соединения;
     * @param readTimeoutMs срок чтения;
     * @param maxBytes потолок тела ответа (превышение — ошибка, не обрезка);
     * @param userAgent подпись клиента, по умолчанию с версией приложения.
     */
    data class Policy(
        val connectTimeoutMs: Int,
        val readTimeoutMs: Int,
        val maxBytes: Int,
        val userAgent: String = "OBNOVA-Android/${BuildConfig.VERSION_NAME}",
        /** Дополнительные заголовки; нужны фидам, которые фильтруют по `X-Client`. */
        val headers: Map<String, String> = emptyMap(),
    ) {
        init {
            require(connectTimeoutMs > 0) { "connectTimeoutMs must be positive" }
            require(readTimeoutMs > 0) { "readTimeoutMs must be positive" }
            require(maxBytes > 0) { "maxBytes must be positive" }
        }
    }

    /** Тело ответа как текст в UTF-8. */
    fun text(url: String, policy: Policy): String = String(bytes(url, policy), Charsets.UTF_8)

    /**
     * Тело ответа как байты.
     *
     * @throws IOException сеть, не-200 или тело больше [Policy.maxBytes].
     */
    @Throws(IOException::class)
    fun bytes(url: String, policy: Policy): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = policy.connectTimeoutMs
            connection.readTimeout = policy.readTimeoutMs
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", policy.userAgent)
            policy.headers.forEach { (name, value) ->
                connection.setRequestProperty(name, value)
            }
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP $code")
            }
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream(minOf(policy.maxBytes, INITIAL_BUFFER_BYTES))
                val buffer = ByteArray(READ_BUFFER_BYTES)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (total + read > policy.maxBytes) {
                        throw IOException("body exceeds ${policy.maxBytes} bytes")
                    }
                    output.write(buffer, 0, read)
                    total += read
                }
                output.toByteArray()
            }
        } finally {
            // errorStream у HttpURLConnection не закрывается вместе с inputStream:
            // на 4xx/5xx это отдельный поток, и без закрытия соединение висит.
            runCatching { connection.errorStream?.close() }
            connection.disconnect()
        }
    }

    private const val INITIAL_BUFFER_BYTES = 16 * 1024
    private const val READ_BUFFER_BYTES = 8 * 1024
}
