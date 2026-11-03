package com.example.nova

import android.content.Context
import nova.Nova
import org.json.JSONObject

/**
 * Слот `warp` для MULTI: локальный SOCKS5 поверх userspace MASQUE.
 *
 * В отличие от `AwgSocksBridge` (UDP WireGuard 500/903, который оператор
 * режет), здесь рукопожатие идёт по QUIC/443 с проверенным SNI (`i.ytimg.com`
 * или сохранённым в `ClientData`). Второй системный TUN не создаётся.
 */
object MasqueSocksBridge {

    const val LISTEN_ADDRESS = "127.0.0.1:1379"
    const val LISTEN_PORT = 1379

    fun isListening(): Boolean = runCatching {
        java.net.Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress("127.0.0.1", LISTEN_PORT), 300)
        }
        true
    }.getOrDefault(false)

    /**
     * Поднимает MASQUE SOCKS: перебирает сохранённые SNI и порты до первого
     * живого рукопожатия. Возвращает `true`, если порт 1379 открылся.
     */
    fun startAndWait(context: Context): Boolean {
        if (!BuildConfig.CF_PROXY_SLOT_ENABLED) return false
        stop()
        val clientData = ClientData(context.applicationContext)
        val identityJson = clientData.getMasqueConfigJson()?.trim().orEmpty()
        if (identityJson.isEmpty()) {
            LogManager.log("MULTI: MASQUE SOCKS пропущен — нет сохранённой личности MASQUE.")
            return false
        }
        val identity = runCatching { JSONObject(identityJson) }.getOrNull() ?: return false
        val endpoint = identity.optString("last_endpoint").trim().ifEmpty {
            identity.optString("endpoint_v4").trim()
        }.substringBefore(':')
        if (endpoint.isEmpty()) return false

        // Сначала — уже доказавшие себя SNI и порт 443: на этой сети именно
        // `i.ytimg.com@443` даёт 63 мс и 19/19 проб.
        val snis = (
            clientData.getPreferredSniHosts(ClientData.SNI_SCOPE_MASQUE, limit = 2) +
                listOf("i.ytimg.com")
            ).map { it.trim() }.distinct()
        val lastPort = identity.optInt("last_port", 0)
        // Не больше четырёх попыток: каждая — до 8 секунд рукопожатия.
        val ports = buildList {
            add(443)
            if (lastPort in 1..65535 && lastPort != 443) add(lastPort)
            add(8443)
        }.distinct().take(3)

        for (port in ports) {
            for (sni in snis) {
                val started = runCatching {
                    Nova.startMasqueSocks(
                        identityJson,
                        endpoint,
                        port.toLong(),
                        sni,
                        LISTEN_ADDRESS,
                        true,
                    )
                }.onFailure { err ->
                    LogManager.d(
                        "MULTI: MASQUE SOCKS $endpoint:$port (sni=${sni.ifEmpty { "default" }}) — ${err.message}"
                    )
                }.getOrNull()
                if (started != null && started.contains("\"running\":true") && isListening()) {
                    LogManager.log(
                        "MULTI: MASQUE SOCKS $LISTEN_ADDRESS готов через $endpoint:$port " +
                            "(sni=${sni.ifEmpty { "default" }})."
                    )
                    return true
                }
                stop()
            }
        }
        LogManager.log("MULTI: MASQUE SOCKS не поднялся ни на одном сочетании порт/SNI.")
        return false
    }

    fun stop() {
        runCatching { Nova.stopMasqueSocks() }
    }
}
