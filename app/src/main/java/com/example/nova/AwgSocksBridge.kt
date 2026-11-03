package com.example.nova

import android.content.Context
import nova.Nova

/**
 * Stage 2: локальный SOCKS5-мост через userspace AmneziaWG.
 *
 * Запускается только при `CF_PROXY_SLOT_ENABLED=true` (`-PnovaCfProxySlot=true`).
 * Берёт актуальный verified-профиль из ClientData, иначе bundled seed. Это
 * отдельное AWG-устройство: activeDevice не трогается, второй TUN не создаётся.
 */
object AwgSocksBridge {

    const val LISTEN_ADDRESS = "127.0.0.1:1378"
    const val LISTEN_PORT = 1378
    const val LOCAL_SOCKS_URL = "socks5h://$LISTEN_ADDRESS"

    /** TCP-probe: мост готов принимать CONNECT, когда порт открылся. */
    fun isListening(): Boolean = runCatching {
        java.net.Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress("127.0.0.1", LISTEN_PORT), 300)
        }
        true
    }.getOrDefault(false)

    /**
     * Поднимает мост и ждёт открытия порта. Opera запускается только после
     * этого: без готового `-api-proxy` она уходит в прямой discover и падает.
     */
    fun startAndWait(context: Context, timeoutMs: Long = 1_500L): Boolean {
        val status = startFromBundled(context)
        if (!status.contains("\"running\":true")) return false
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isListening()) return true
            Thread.sleep(100)
        }
        return isListening()
    }

    fun startFromBundled(context: Context): String {
        if (!BuildConfig.CF_PROXY_SLOT_ENABLED) {
            return "CF_PROXY_SLOT_ENABLED=false"
        }
        return runCatching {
            val clientData = ClientData(context.applicationContext)
            val endpoint = clientData.getConfig()?.peerEndpoint.orEmpty().trim()
            val verified = clientData.getWarpVerifiedConfigs()
            val chosen = verified.firstOrNull { config ->
                endpoint.isNotBlank() && "${config.host}:${config.port}" == endpoint
            } ?: verified.maxByOrNull { it.lastVerifiedAt }
            val config = chosen?.rawConfig?.takeIf { it.isNotBlank() }
                ?: context.assets.open("warp_verified_seeds.json")
                    .bufferedReader()
                    .use { it.readText() }
                    .let { org.json.JSONArray(it).getJSONObject(0).getString("raw_config") }
            Nova.startAwgSocks(config, LISTEN_ADDRESS, true)
        }.getOrElse { error ->
            "ошибка запуска: ${error.javaClass.simpleName}: ${error.message}"
        }
    }

    fun stop(): String = runCatching {
        Nova.stopAwgSocks()
        Nova.awgSocksStatus()
    }.getOrElse { "ошибка остановки: ${it.message}" }

    fun status(): String = runCatching { Nova.awgSocksStatus() }
        .getOrDefault("{\"running\":false}")
}
