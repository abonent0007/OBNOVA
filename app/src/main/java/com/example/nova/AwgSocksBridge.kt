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

    fun startWithConfig(config: String): Boolean {
        if (!BuildConfig.CF_PROXY_SLOT_ENABLED) return false
        stop()
        val status = runCatching {
            Nova.startAwgSocks(config, LISTEN_ADDRESS, true)
        }.getOrElse { error ->
            LogManager.log("MULTI: AWG seed не запустился: ${error.javaClass.simpleName}: ${error.message}")
            return false
        }
        if (!status.contains("\"running\":true")) {
            LogManager.log("MULTI: AWG seed статус: $status")
            return false
        }
        val deadline = System.currentTimeMillis() + 2_000L
        while (System.currentTimeMillis() < deadline) {
            if (isListening()) return true
            Thread.sleep(50)
        }
        return false
    }

    fun startFromBundled(context: Context): String {
        if (!BuildConfig.CF_PROXY_SLOT_ENABLED) {
            return "CF_PROXY_SLOT_ENABLED=false"
        }
        // Проверенный AWG-профиль из PC-версии (полный AmneziaWG: Jc/Jmin/
        // Jmax/H1..H4/I1). Bootstrap-профиль без obfuscation часто не
        // пробивает UDP, а этот уже работает в PC-маршруте Telegram.
        val seed = runCatching {
            context.assets.open("warp_awg_seed.conf").bufferedReader().use { it.readText() }
        }.getOrNull()
        if (!seed.isNullOrBlank()) {
            val seedStatus = runCatching {
                Nova.startAwgSocks(seed, LISTEN_ADDRESS, true)
            }.getOrElse { error -> "Ошибка AWG seed: ${error.javaClass.simpleName}: ${error.message}" }
            if (seedStatus.contains("\"running\":true")) {
                LogManager.log("MULTI: AWG-мост из проверенного PC-профиля (assets/warp_awg_seed.conf).")
                return seedStatus
            }
            LogManager.log("MULTI: AWG seed не поднялся: $seedStatus")
        }
        return runCatching {
            val clientData = ClientData(context.applicationContext)
            val warp = clientData.getConfig()
            val warpEndpoint = warp?.peerEndpoint.orEmpty().trim()
            val warpPort = warpEndpoint.substringAfterLast(':', "").toIntOrNull()
            // MASQUE-профиль ходит на 443 и для userspace-WireGuard не годится:
            // мост поднимается, но handshake не проходит. Берём bootstrap-личность.
            val looksWireGuard = warpPort in setOf(500, 4500, 2408, 51820, 1701)
            val fromWarp = warp != null &&
                warp.privateKey.isNotBlank() &&
                warp.peerPublicKey.isNotBlank() &&
                warpEndpoint.isNotBlank() &&
                looksWireGuard
            val config = if (fromWarp) {
                buildString {
                    appendLine("[Interface]")
                    appendLine("PrivateKey = ${warp!!.privateKey}")
                    val addresses = listOfNotNull(
                        warp.ipv4.trim().takeIf { it.isNotEmpty() },
                        warp.ipv6.trim().takeIf { it.isNotEmpty() },
                    )
                    if (addresses.isNotEmpty()) appendLine("Address = ${addresses.joinToString(", ")}")
                    appendLine("DNS = 1.1.1.1")
                    appendLine("MTU = 1280")
                    // Reserved (client_id WARP) обязателен: с нулями Cloudflare
                    // отвечает только по своей сети, а внешний egress молча дропает.
                    if (!warp.reserved.isNullOrBlank()) {
                        appendLine("Reserved = ${warp.reserved}")
                    }
                    appendLine()
                    appendLine("[Peer]")
                    appendLine("PublicKey = ${warp.peerPublicKey}")
                    appendLine("AllowedIPs = 0.0.0.0/0, ::/0")
                    appendLine("Endpoint = ${warp.peerEndpoint}")
                }
            } else {
                // Bootstrap-личность WARP: нормальный WG-endpoint (162.159.x:500),
                // в отличие от bundled seed с устаревшим 8.34.146.3:903.
                val bootstrap = org.json.JSONObject(
                    context.assets.open("warp_bootstrap.json")
                        .bufferedReader()
                        .use { it.readText() }
                )
                buildString {
                    appendLine("[Interface]")
                    appendLine("PrivateKey = ${bootstrap.getString("private_key")}")
                    val v4 = bootstrap.optString("ipv4").trim()
                    val v6 = bootstrap.optString("ipv6").trim()
                    val addresses = buildList {
                        if (v4.isNotEmpty()) add(if ('/' in v4) v4 else "$v4/32")
                        if (v6.isNotEmpty()) add(if ('/' in v6) v6 else "$v6/128")
                    }
                    if (addresses.isNotEmpty()) appendLine("Address = ${addresses.joinToString(", ")}")
                    appendLine("DNS = 1.1.1.1")
                    appendLine("MTU = 1280")
                    appendLine()
                    appendLine("[Peer]")
                    appendLine("PublicKey = ${bootstrap.getString("peer_pub")}")
                    appendLine("AllowedIPs = 0.0.0.0/0, ::/0")
                    appendLine("Endpoint = ${bootstrap.getString("peer_endpoint")}")
                }
            }
            LogManager.log(
                "MULTI: AWG-мост из ${if (fromWarp) "текущего WARP-профиля" else "bootstrap-профиля"}" +
                    ", endpoint=$warpEndpoint" +
                    ", reserved=${warp?.reserved ?: "нет"}"
            )
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
