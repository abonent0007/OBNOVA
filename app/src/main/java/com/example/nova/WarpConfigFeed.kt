package com.example.nova

import org.json.JSONObject

/**
 * Запасной источник WARP-конфигурации, когда регистрация на
 * `api.cloudflareclient.com` из России не проходит.
 *
 * Адреса взяты с публичного генератора `warp-generation.github.io`
 * (`warp-workers.warp-api.cfd` и зеркала). Это не замена собственной
 * регистрации: сначала приложение всегда пробует Cloudflare напрямую. Фид
 * используется только как последний шанс, потому что готовый приватный ключ
 * выдаёт чужая сторона — для личного форка это допустимый запасной путь, но не
 * основной.
 */
object WarpConfigFeed {

    /** Зеркала в порядке генератора: workers, sub, vercel, netlify. */
    val ENDPOINTS = listOf(
        "https://warp-workers.warp-api.cfd",
        "https://warp-sub.warp-api.cfd",
        "https://warp-vercel-chi.warp-api.cfd",
        "https://warp-vercel-murex.warp-api.cfd",
        "https://warp-netlify.warp-api.cfd",
    )

    /** Разбор ответа; null — мусор или неполный конфиг. */
    fun parse(body: String): WarpGeneratedStore.Identity? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val privateKey = json.optString("privKey").trim()
        val peerKey = json.optString("peer_pub").trim()
        val ipv4 = json.optString("client_ipv4").trim()
        val ipv6 = json.optString("client_ipv6").trim()
        if (privateKey.isBlank() || peerKey.isBlank() || ipv4.isBlank()) return null
        return WarpGeneratedStore.Identity(
            privateKey = privateKey,
            // Публичный ключ выводится из приватного, но фид его не отдаёт;
            // в хранилище он нужен только для аудита, поэтому пустая строка.
            publicKey = "",
            ipv4 = ipv4,
            ipv6 = ipv6,
            peerPublicKey = peerKey,
            reserved = "",
            createdAt = System.currentTimeMillis(),
        )
    }

    /** Первый ответивший фид или null. Вызывать с IO-потока. */
    fun fetch(): WarpGeneratedStore.Identity? {
        for (endpoint in ENDPOINTS) {
            val body = runCatching {
                FeedHttp.text(
                    endpoint,
                    FeedHttp.Policy(
                        connectTimeoutMs = 8_000,
                        readTimeoutMs = 12_000,
                        maxBytes = 64 * 1024,
                        userAgent = "WARP",
                        headers = mapOf("X-Client" to "WARP"),
                    ),
                )
            }.getOrNull() ?: continue
            parse(body)?.let { identity ->
                LogManager.log("WARP: регистрация не прошла, конфиг взят из фида $endpoint.")
                return identity
            }
        }
        LogManager.log("WARP: фид конфигурации недоступен — остаётся только своя регистрация.")
        return null
    }
}
