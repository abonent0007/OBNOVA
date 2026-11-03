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
    ): String {
        require(primaryPort in 1..65535) { "primaryPort вне диапазона: $primaryPort" }
        val opera = operaPort in 1..65535
        val spoof = spoofPort in 1..65535
        val cfProxy = cfProxyPort in 1..65535
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
            // проходит (проверено 200), а WARP-мост сейчас не несёт трафик.
            // cfproxy оставлен вторым, primary — последним.
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
        if (input.telegramIps.isNotEmpty()) {
            val baseTelegramOrder = order("telegram", input.overrides, opera, spoof, killSwitch)
            // Первым идёт WSS-реле: оно несёт MTProto до DC через Cloudflare,
            // а оператор видит обычный HTTPS к Cloudflare. Opera и cfproxy —
            // запас, если реле не ответило. При ручном override не вмешиваемся.
            val telegramIpOrder = if (input.overrides["telegram"] == null) {
                buildList {
                    if (input.telegramRelayUrl.isNotBlank()) add("ws")
                    if (opera) add("secondary")
                    if (cfProxy) add("cfproxy")
                    add("primary")
                }
            } else {
                baseTelegramOrder
            }
            rules += RouterConfig.RuleSpec(
                domains = emptyList(),
                order = telegramIpOrder,
                name = "telegram-ip",
                cidrs = input.telegramIps,
            )
        }
        add(rules, input.youtube, order("youtube", input.overrides, opera, spoof, killSwitch), "youtube")
        add(rules, input.main, order("ru", input.overrides, opera, spoof, killSwitch), "ru")
        add(rules, input.telegram, order("telegram", input.overrides, opera, spoof, killSwitch), "telegram")
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
        if (input.telegramRelayUrl.isNotBlank()) {
            slots["ws"] = RouterConfig.SlotSpec(
                type = "ws",
                url = input.telegramRelayUrl,
                secret = input.telegramRelaySecret,
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
            RouteClass.EU, RouteClass.YOUTUBE, RouteClass.AI -> eu
            RouteClass.TELEGRAM -> pinned
            RouteClass.WHATSAPP, RouteClass.DISCORD -> ru
            null -> when (service) {
                "eu", "youtube", "ai" -> eu
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
