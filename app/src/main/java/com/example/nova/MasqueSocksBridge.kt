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

    /** Активное сочетание, которое поднялось последним. Нужно кнопке «след. профиль». */
    @Volatile
    var lastStartedEndpoint: String = ""
        private set
    @Volatile
    var lastStartedPort: Int = 0
        private set
    @Volatile
    var lastStartedSni: String = ""
        private set

    fun isListening(): Boolean = runCatching {
        java.net.Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress("127.0.0.1", LISTEN_PORT), 300)
        }
        true
    }.getOrDefault(false)

    /**
     * Поднимает MASQUE SOCKS: перебирает сохранённые endpoint'ы, SNI и порты до
     * первого живого рукопожатия.
     *
     * @param avoidEndpoint endpoint, с которого начинать не нужно: так кнопка
     *        «след. профиль» уходит на другой выход, а не перезапускает тот же.
     * @param preferEndpoint endpoint, который нужно попробовать первым: им
     *        восстанавливается прежний профиль, если новый не поднялся.
     * @param budgetMs общий срок перебора. Один только первый endpoint с тремя
     *        портами и SNI иначе занимает больше минуты.
     */
    fun startAndWait(
        context: Context,
        avoidEndpoint: String? = null,
        preferEndpoint: String? = null,
        budgetMs: Long = 20_000L,
    ): Boolean {
        if (!BuildConfig.CF_PROXY_SLOT_ENABLED) return false
        LogManager.log(
            "MULTI: MASQUE startAndWait вход (listening=${isListening()}, " +
                "avoid=$avoidEndpoint, prefer=$preferEndpoint, budget=$budgetMs)."
        )
        stop()
        LogManager.log("MULTI: MASQUE старый мост остановлен.")
        lastStartedEndpoint = ""
        lastStartedPort = 0
        lastStartedSni = ""
        val clientData = ClientData(context.applicationContext)
        var identityJson = clientData.getMasqueConfigJson()?.trim().orEmpty()
        if (identityJson.isEmpty()) {
            // Чистая установка: берём заранее выпущенный MASQUE-ключ из APK.
            // Регистрация через Cloudflare API из РФ висит, а UDP/500 режется;
            // этот ключ уже проверен в PC-версии и работает по QUIC/443.
            identityJson = masqueSeedIdentityJson(context)?.also { seeded ->
                clientData.saveMasqueConfigJson(seeded)
                LogManager.log("MULTI: MASQUE identity взята из bundled masque_seed.json.")
            }.orEmpty()
        }
        if (identityJson.isEmpty()) {
            LogManager.log("MULTI: MASQUE SOCKS пропущен — нет сохранённой личности MASQUE.")
            return false
        }
        val identity = runCatching { JSONObject(identityJson) }.getOrNull() ?: return false
        val endpoints = endpointCandidates(identity)
        if (endpoints.isEmpty()) return false
        val orderedEndpoints = orderEndpoints(
            endpoints = endpoints,
            avoidEndpoint = avoidEndpoint,
            preferEndpoint = preferEndpoint,
        )

        // Сначала — уже доказавшие себя SNI и порт 443: на этой сети именно
        // `i.ytimg.com@443` даёт 63 мс и 19/19 проб.
        val snis = (
            clientData.getPreferredSniHosts(ClientData.SNI_SCOPE_MASQUE, limit = 2) +
                listOf("i.ytimg.com")
            ).map { it.trim() }.distinct()
        val lastPort = identity.optInt("last_port", 0)
        // Не больше трёх попыток на endpoint: каждая — до 8 секунд рукопожатия.
        val ports = buildList {
            add(443)
            if (lastPort in 1..65535 && lastPort != 443) add(lastPort)
            add(8443)
        }.distinct().take(3)
        val deadline = System.currentTimeMillis() + budgetMs.coerceAtLeast(3_000L)
        // JNI-вызов рукопожатия может не вернуться на мёртвом endpoint, а
        // проверка дедлайна стоит только между попытками. Жёсткий сторож
        // останавливает мост извне, иначе кнопка «след. профиль» висит минутами.
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        val watchdog = Thread {
            try {
                Thread.sleep(budgetMs.coerceAtLeast(3_000L) + 1_000L)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (!finished.get()) {
                LogManager.log("MULTI: перебор MASQUE превысил бюджет — принудительно останавливаем.")
                stop()
            }
        }.apply {
            name = "NovaMasqueBudget"
            isDaemon = true
            start()
        }
        return try {
            for (endpoint in orderedEndpoints.take(3)) {
                for (port in ports) {
                    for (sni in snis) {
                        if (System.currentTimeMillis() >= deadline) break
                        // Мёртвый адрес отсекаем TCP-пробой: JNI-рукопожатие на
                        // нём может висеть без таймаута.
                        if (!tcpReachable(endpoint, port, 700)) continue
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
                                "MULTI: MASQUE SOCKS $endpoint:$port " +
                                    "(sni=${sni.ifEmpty { "default" }}) — ${err.message}"
                            )
                        }.getOrNull()
                        if (started != null && started.contains("\"running\":true") && isListening()) {
                            lastStartedEndpoint = endpoint
                            lastStartedPort = port
                            lastStartedSni = sni
                            LogManager.log(
                                "MULTI: MASQUE SOCKS $LISTEN_ADDRESS готов через $endpoint:$port " +
                                    "(sni=${sni.ifEmpty { "default" }})."
                            )
                            return true
                        }
                        stop()
                    }
                    if (System.currentTimeMillis() >= deadline) break
                }
                if (System.currentTimeMillis() >= deadline) break
            }
            stop()
            LogManager.log("MULTI: MASQUE SOCKS не поднялся ни на одном endpoint/порту/SNI.")
            false
        } finally {
            finished.set(true)
            watchdog.interrupt()
        }
    }

    /**
     * Все известные endpoint'ы личности: сохранённый, основной и кандидаты из
     * регистрации WARP. Раньше брался только один, поэтому «смена профиля» не
     * могла сменить выход — перебирались лишь порт и SNI у того же IP.
     */
    /**
     * Заранее выпущенная MASQUE-личность из APK.
     *
     * Не регистрация: ключ уже есть, Cloudflare принимает его по QUIC/443.
     * Нужен только пока `masque_config.json` пуст; дальше работает обычный путь.
     */
    private fun masqueSeedIdentityJson(context: Context): String? = runCatching {
        val raw = context.assets.open("masque_seed.json").bufferedReader().use { it.readText() }
        val json = JSONObject(raw)
        if (json.optString("private_key").isBlank() || json.optString("endpoint_pub_key").isBlank()) {
            null
        } else {
            raw
        }
    }.getOrNull()

    /**
     * Identity MASQUE из встроенного WARP bootstrap.
     *
     * Это не регистрация: ключ уже лежит в APK, а Cloudflare принимает его и
     * для MASQUE. Нужен только на чистой установке, пока `masque_config.json`
     * ещё не создан; после сохранения используется обычный путь.
     */
    private fun bootstrapIdentityJson(context: Context): String? {
        val root = runCatching {
            context.assets.open("warp_bootstrap.json").bufferedReader().use { it.readText() }
        }.mapCatching { JSONObject(it) }.getOrNull() ?: return null
        val host = hostOnly(root.optString("peer_endpoint"))
        val privateKey = root.optString("private_key").trim()
        val peerPub = root.optString("peer_pub").trim()
        val ipv4 = root.optString("ipv4").trim()
        val ipv6 = root.optString("ipv6").trim()
        if (host.isBlank() || privateKey.isBlank() || peerPub.isBlank() || ipv4.isBlank()) return null
        return JSONObject().apply {
            put("private_key", privateKey)
            put("endpoint_v4", host)
            put("endpoint_v6", "")
            put("endpoint_pub_key", peerPub)
            put("ipv4", ipv4)
            put("ipv6", ipv6)
            put("last_endpoint", "$host:443")
            put("last_port", 443)
            put("ports", org.json.JSONArray(listOf(443, 500, 8443)))
        }.toString()
    }

    internal fun endpointCandidates(identity: JSONObject): List<String> {
        val values = linkedSetOf<String>()
        fun add(raw: String?) {
            val host = hostOnly(raw)
            if (host.isNotBlank()) values.add(host)
        }
        add(identity.optString("last_endpoint"))
        add(identity.optString("endpoint_v4"))
        add(identity.optString("endpoint_v6"))
        for (field in listOf("endpoint_v4_candidates", "endpoint_v6_candidates")) {
            val array = identity.optJSONArray(field) ?: continue
            for (i in 0 until array.length()) add(array.optString(i))
        }
        return values.toList()
    }

    internal fun orderEndpoints(
        endpoints: List<String>,
        avoidEndpoint: String?,
        preferEndpoint: String?,
    ): List<String> {
        val avoid = hostOnly(avoidEndpoint)
        val prefer = hostOnly(preferEndpoint)
        return when {
            prefer.isNotBlank() -> listOf(prefer) + endpoints.filter { !it.equals(prefer, ignoreCase = true) }
            avoid.isNotBlank() -> {
                val index = endpoints.indexOfFirst { it.equals(avoid, ignoreCase = true) }
                if (index < 0) endpoints else endpoints.drop(index + 1) + endpoints.take(index + 1)
            }
            else -> endpoints
        }
    }

    /** `[2606:...]:443` и `1.2.3.4:443` одинаково превращаются в хост без порта. */
    internal fun hostOnly(raw: String?): String {
        val clean = raw?.trim().orEmpty()
        if (clean.isBlank()) return ""
        if (clean.startsWith("[")) return clean.substringAfter('[').substringBefore(']')
        val colons = clean.count { it == ':' }
        return if (colons == 1) clean.substringBefore(':').trim() else clean
    }

    private fun tcpReachable(host: String, port: Int, timeoutMs: Int): Boolean = runCatching {
        java.net.Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress(host, port), timeoutMs)
        }
        true
    }.getOrDefault(false)

    fun stop() {
        runCatching { Nova.stopMasqueSocks() }
    }
}
