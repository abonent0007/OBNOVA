package com.example.nova

/**
 * Прозрачный WSS-релей Telegram (как TgRelay в Kaliopa).
 *
 * `{dc}` подставляет номер дата-центра; роутер сам добавляет вариант `-1`
 * для media и гоняет маршруты. Секрет не нужен: это официальные KWS-хосты
 * Telegram. Egress — через cfproxy (MASQUE 127.0.0.1:1379).
 */
object TelegramRelay {
    const val URL = "wss://kws{dc}.web.telegram.org/apiws"
    const val SECRET = ""

    val isConfigured: Boolean
        get() = URL.startsWith("wss://") && URL.contains("{dc}")
}
