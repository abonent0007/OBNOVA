package com.example.nova

/**
 * Собирает конфигурацию `nova-router` из списков, ручных маршрутов и живых
 * слотов. Отдельный чистый объект: его можно проверить тестом без службы.
 */
object RouterConfigFactory {

    /**
     * Play Services/FCM и загрузки Play: gRPC-потоки и проверки целостности
     * Opera рвёт 500-й, а из РФ эти хосты доступны через DESYNC.
     * `googleapis.com` целиком сюда не входит: `youtubei.googleapis.com`
     * обязан остаться на SECONDARY.
     */
    private val APIS_DOMAINS = listOf("apis.google.com", "gvt1.com")

    /**
     * MTProto-прокси, который Telegram держит включённым. Через WARP-мост
     * Hetzner доступен, а прямой DC2 оператор режет; поэтому IP прокси идёт
     * в cfproxy, а не в default (DESYNC).
     */
    private val MTPROTO_PROXY_CIDRS = listOf("65.109.194.157/32")

    data class Input(
        val localTlds: Set<String> = emptySet(),
        val exclude: List<String> = emptyList(),
        val main: List<String> = emptyList(),
        val second: List<String> = emptyList(),
        val ai: List<String> = emptyList(),
        val googleAi: List<String> = emptyList(),
        val spotify: List<String> = emptyList(),
        val youtube: List<String> = emptyList(),
        /** IP-литералы YouTube/Google: Cronet обходит доменные правила. */
        val youtubeIps: List<String> = emptyList(),
        /** IP-литералы Telegram: MTProto часто идёт по IP. */
        val telegramIps: List<String> = emptyList(),
        /** WSS-реле для Telegram (Cloudflare Worker). Пусто — слот выключен. */
        val telegramRelayUrl: String = "",
        val telegramRelaySecret: String = "",
        val telegram: List<String> = emptyList(),
        val whatsapp: List<String> = emptyList(),
        val discord: List<String> = emptyList(),
        val overrides: Map<String, RouteClass?> = emptyMap(),
        /** Killswitch: без DIRECT-хвоста, если слоты не ответили. */
        val killSwitch: Boolean = false,
    )

    /**
     * @param primaryPort основной слот (VLESS, если есть живой профиль, иначе
     *        DESYNC).
     * @param operaPort вторичный слот Opera; 0 — нет.
     * @param spoofPort DESYNC как запасной слот, когда основным стал VLESS;
     *        0 — не нужен.
     */
    fun build(
        input: Input,
        primaryPort: Int,
        operaPort: Int = 0,
        spoofPort: Int = 0,
        cfProxyPort: Int = 0,
        vlessPort: Int = 0,
        awgPort: Int = 0,
    ): String {
        require(primaryPort in 1..65535) { "primaryPort вне диапазона: $primaryPort" }
        val opera = operaPort in 1..65535
        val spoof = spoofPort in 1..65535
        val cfProxy = cfProxyPort in 1..65535
        val vless = vlessPort in 1..65535
        val awg = awgPort in 1..65535
        val killSwitch = input.killSwitch
        val rules = mutableListOf<RouterConfig.RuleSpec>()

        // Порядок как в PAC: exclude → локальные TLD → AI/EU → YouTube →
        // RU(main) → сервисные. IP-правила стоят до доменных: Cronet/YouTube
        // приходит с IP-литералом и иначе падает в defaultOrder.
        if (input.exclude.isNotEmpty()) {
            rules += RouterConfig.RuleSpec(input.exclude, listOf("direct"), "exclude")
        }
        if (input.localTlds.isNotEmpty()) {
            rules += RouterConfig.RuleSpec(input.localTlds.toList(), listOf("direct"), "local-tld")
        }
        // До YouTube и до CIDR: Play Services не должен попасть в Opera.
        add(rules, APIS_DOMAINS, order("apis", input.overrides, opera, spoof, killSwitch), "apis")
        if (cfProxy || opera) {
            // MTProto-прокси Telegram: Opera первой — её CONNECT на IP:300
            // стабильно даёт ~700 мс, тогда как WARP-egress из DME до Hetzner
            // отдаёт 3.5 с. cfproxy — резерв.
            val mtprotoProxyOrder = buildList {
                if (opera) add("secondary")
                if (cfProxy) add("cfproxy")
                add("primary")
            }
            rules += RouterConfig.RuleSpec(
                domains = emptyList(),
                order = mtprotoProxyOrder,
                name = "mtproto-proxy",
                cidrs = MTPROTO_PROXY_CIDRS,
            )
        }
        add(rules, input.googleAi, order("google_ai", input.overrides, opera, spoof, killSwitch), "google-ai")
        add(rules, input.spotify, order("spotify", input.overrides, opera, spoof, killSwitch), "spotify")
        add(rules, input.ai, order("ai", input.overrides, opera, spoof, killSwitch), "ai")
        add(rules, input.second, order("eu", input.overrides, opera, spoof, killSwitch), "eu")
        if (input.youtubeIps.isNotEmpty()) {
            rules += RouterConfig.RuleSpec(
                domains = emptyList(),
                order = order("youtube", input.overrides, opera, spoof, killSwitch),
                name = "youtube-ip",
                cidrs = input.youtubeIps,
            )
        }
        val baseTelegramOrder = order("telegram", input.overrides, opera, spoof, killSwitch)
        // Как на ПК: Telegram идёт через WSS-реле, а реле — через MASQUE
        // (via). Прямой cfproxy ниже остаётся запасом, затем Opera. Это
        // правило одно для IP и доменов: раньше доменный Telegram обходил
        // реле и уходил в primary, хотя IP уже шли через реле.
        val telegramPreferredOrder = if (input.overrides["telegram"] == null) {
            buildList {
                // Telegram MTProto DC (149.154.*, 91.108.*) через MASQUE не
                // соединяется (curl: 000), поэтому первым идёт VLESS-слот:
                // проверенное семя Xray отвечает на DC2. Затем Opera, DESYNC,
                // cfproxy/primary — запас. WSS `kws*.web.telegram.org/apiws`
                // отдаёт 487/106 и closes — Telegram на нём зацикливается.
                if (awg) add("awg")
                if (vless) add("vless")
                if (opera) add("secondary")
                if (spoof) add("spoof")
                if (cfProxy) add("cfproxy")
                add("primary")
            }
        } else {
            baseTelegramOrder
        }
        if (input.telegramIps.isNotEmpty()) {
            rules += RouterConfig.RuleSpec(
                domains = emptyList(),
                order = telegramPreferredOrder,
                name = "telegram-ip",
                cidrs = input.telegramIps,
            )
        }
        add(rules, input.youtube, order("youtube", input.overrides, opera, spoof, killSwitch), "youtube")
        add(rules, input.main, order("ru", input.overrides, opera, spoof, killSwitch), "ru")
        add(rules, input.telegram, telegramPreferredOrder, "telegram")
        add(rules, input.whatsapp, order("whatsapp", input.overrides, opera, spoof, killSwitch), "whatsapp")
        add(rules, input.discord, order("discord", input.overrides, opera, spoof, killSwitch), "discord")

        val slots = mutableMapOf(
            "primary" to RouterConfig.SlotSpec("socks5", "127.0.0.1:$primaryPort"),
            "direct" to RouterConfig.SlotSpec("direct"),
        )
        if (spoof) {
            slots["spoof"] = RouterConfig.SlotSpec("socks5", "127.0.0.1:$spoofPort")
        }
        if (opera) {
            slots["secondary"] = RouterConfig.SlotSpec("http", "127.0.0.1:$operaPort")
        }
        if (cfProxy) {
            slots["cfproxy"] = RouterConfig.SlotSpec("socks5", "127.0.0.1:$cfProxyPort")
        }
        if (vless) {
            slots["vless"] = RouterConfig.SlotSpec("socks5", "127.0.0.1:$vlessPort")
        }
        if (awg) {
            slots["awg"] = RouterConfig.SlotSpec("socks5", "127.0.0.1:$awgPort")
        }
        if (input.telegramRelayUrl.isNotBlank()) {
            slots["ws"] = RouterConfig.SlotSpec(
                type = "ws",
                url = input.telegramRelayUrl,
                secret = input.telegramRelaySecret,
                // Как на ПК (warp-socks): WSS открывается через MASQUE, а не
                // напрямую с телефона — так реле идёт тем же egress, что primary.
                via = if (cfProxy) "127.0.0.1:$cfProxyPort" else "",
            )
        }

        return RouterConfig.build(
            slots = slots,
            rules = rules,
            defaultOrder = order("other", input.overrides, opera, spoof, killSwitch),
        )
    }

    fun order(
        service: String,
        overrides: Map<String, RouteClass?>,
        opera: Boolean,
        spoof: Boolean = false,
        killSwitch: Boolean = false,
    ): List<String> {
        val tail = buildList {
            if (spoof) add("spoof")
            if (!killSwitch) add("direct")
        }
        val ru = listOf("primary") + tail
        val eu = (if (opera) listOf("secondary") else emptyList()) + ru
        // YouTube: primary (MASQUE/WARP) первым — он реально тянет
        // www.youtube.com и googlevideo (проверено curl через SOCKS 1379).
        // Opera — второй слот, DESYNC — обход DPI, direct — последний.
        val google = buildList {
            add("primary")
            if (opera) add("secondary")
            if (spoof) add("spoof")
            if (!killSwitch) add("direct")
        }
        val googlePrimaryFirst = google
        // Telegram в PC-политике закреплён за relay/PRIMARY и не должен уходить
        // в DIRECT: иначе при отказе слота он молча пойдёт мимо туннеля.
        val pinned = buildList {
            add("primary")
            if (spoof) add("spoof")
        }
        return when (overrides[service]) {
            RouteClass.DIRECT -> listOf("direct")
            RouteClass.BLOCK -> emptyList()
            RouteClass.RU -> ru
            RouteClass.EU -> eu
            RouteClass.YOUTUBE, RouteClass.AI -> google
            RouteClass.GOOGLE_AI -> googlePrimaryFirst
            RouteClass.TELEGRAM -> pinned
            RouteClass.WHATSAPP, RouteClass.DISCORD -> ru
            null -> when (service) {
                "eu" -> eu
                "youtube", "ai" -> google
                "google_ai" -> googlePrimaryFirst
                "spotify" -> listOf("direct")
                "telegram" -> pinned
                else -> ru
            }
        }
    }

    private fun add(
        rules: MutableList<RouterConfig.RuleSpec>,
        domains: List<String>,
        order: List<String>,
        name: String,
    ) {
        if (domains.isEmpty()) return
        rules += RouterConfig.RuleSpec(domains, order, name)
    }
}
