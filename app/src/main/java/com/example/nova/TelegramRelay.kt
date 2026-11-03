package com.example.nova

/**
 * Прозрачное WSS-реле для Telegram.
 *
 * `nova-router` открывает WebSocket к Cloudflare Worker, тот поднимает
 * соединение к `kwsN.web.telegram.org`, и MTProto идёт как обычный трафик
 * дата-центра. Приложение Telegram об этом не знает: прокси в нём не нужен.
 *
 * URL и секрет — от Worker'а `nova-connect`. Пустой URL выключает слот.
 */
object TelegramRelay {
    // Пока реле не подтвердило передачу MTProto, слот выключен: пустой URL
    // убирает его из конфигурации, и Telegram идёт проверенным путём.
    const val URL = "wss://nova-connect.yurentiy-avd.workers.dev/tcp"
    const val SECRET = "2cf81934c6674643ab5a1d5243d021ee1b4677b5f92349d19ed9202804da150b"

    val isConfigured: Boolean
        get() = URL.startsWith("wss://") && SECRET.isNotBlank()
}
