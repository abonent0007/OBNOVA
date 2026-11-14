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
        /** Claude/Anthropic: отдельная политика, общий транспортный слой. */
        val anthropic: List<String> = emptyList(),
        /** DeepSeek/Qwen и другие китайские AI: обычно доступны напрямую. */
        val chinaAi: List<String> = emptyList(),
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
        /**
         * Strict Full Tunnel: конфигурация вообще не содержит `direct`.
         * Пока выставляется только тестами; включение в службе — B.2.3.
         */
        val strict: Boolean = false,
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
        dnsAiUrl: String = "",
        dnsAiBootstrap: String = "",
        dnsAiVia: String = "",
        dnsAiRelayVia: String = "",
        /** Слоты, для которых транспорт явно проверен как UDP-capable. */
        udpSlots: Set<String> = emptySet(),
        /** Транспорты с фактически проверенным non-RU egress. */
        nonRuSlots: Set<String> = emptySet(),
    ): String {
        require(primaryPort in 1..65535) { "primaryPort вне диапазона: $primaryPort" }
        val opera = operaPort in 1..65535
        val spoof = spoofPort in 1..65535
        val cfProxy = cfProxyPort in 1..65535
        val vless = vlessPort in 1..65535
        val awg = awgPort in 1..65535
        val dnsAi = dnsAiUrl.isNotBlank() && dnsAiBootstrap.isNotBlank()
        val strictFullTunnel = input.strict
        val killSwitch = input.killSwitch || strictFullTunnel
        if (strictFullTunnel) {
            // DIRECT — это явный пользовательский выбор слота. В Strict его нет
            // вовсе: молча заменить его на VPN нельзя (это было бы исправление
            // конфигурации), поэтому фабрика отказывается собирать такую
            // политику. Служба обязана трактовать это как отказ Strict.
            input.overrides.forEach { (service, route) ->
                require(route != RouteClass.DIRECT) {
                    "STRICT_FULL: DIRECT-override для $service запрещён"
                }
            }
        }
        // AI-маршруты допускают только транспорты с фактически проверенным
        // non-RU egress. secondary/vless — кандидаты; без проверки их здесь нет.
        val aiEgressOrder = buildList {
            if (opera && "secondary" in nonRuSlots) add("secondary")
            if (vless && "vless" in nonRuSlots) add("vless")
        }
        // UDP-порядок описывает реальную capability: если primary не умеет
        // UDP, правило получает пустой udpOrder, а не несуществующий слот.
        val primaryUdpOrder = if ("primary" in udpSlots) listOf("primary") else emptyList()
        val rules = mutableListOf<RouterConfig.RuleSpec>()

        // Порядок как в PAC: exclude → локальные TLD → AI/EU → YouTube →
        // RU(main) → сервисные. IP-правила стоят до доменных: Cronet/YouTube
        // приходит с IP-литералом и иначе падает в defaultOrder.
        if (!strictFullTunnel && input.exclude.isNotEmpty()) {
            rules += RouterConfig.RuleSpec(
                input.exclude,
                listOf("direct"),
                "exclude",
                udpOrder = listOf("direct"),
            )
        }
        if (!strictFullTunnel && input.localTlds.isNotEmpty()) {
            rules += RouterConfig.RuleSpec(
                input.localTlds.toList(),
                listOf("direct"),
                "local-tld",
                udpOrder = listOf("direct"),
            )
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
        // Служебные домены Gemini-приложения. Все они могут участвовать в
        // auth/API, поэтому direct/dnsai/spoof запрещены: иначе одна сессия
        // получит смешанные egress-IP. AI-домены (aida/aisandbox/
        // generativelanguage) остаются в ai/google_ai.
        val geminiSupport = listOf(
            "android.googleapis.com",
            "play-fe.googleapis.com",
            "signaler-pa.googleapis.com",
            "content-push.googleapis.com",
            "firebaseinstallations.googleapis.com",
            "firebaseremoteconfig.googleapis.com",
            "growth-pa.googleapis.com",
            "clientservices.googleapis.com",
        )
        rules += RouterConfig.RuleSpec(
            domains = geminiSupport,
            // Все текущие домены — auth/API/service. Direct/dnsai/spoof
            // запрещены: они могут смешать RU и non-RU внутри одной сессии.
            order = aiEgressOrder,
            name = "gemini-support",
        )
        // OpenAI: один согласованный выход для всего набора доменов, как в
        // сетевой документации OpenAI. Дробление на несколько правил ломает
        // сессию: авторизация, статика, WebSocket и API должны выходить с одного
        // IP. Opera EU/US — первый слот, VLESS — запас; DNS-AI/direct не
        // используем, потому что 403 приходит уже после успешного dial.
        if (dnsAi || opera || vless || awg) {
            rules += RouterConfig.RuleSpec(
                domains = listOf(
                    // Интерфейс и вход.
                    "chatgpt.com",
                    "chat.openai.com",
                    "openai.com",
                    "auth.openai.com",
                    "auth0.openai.com",
                    // API и служебные хосты приложения.
                    "ab.chatgpt.com",
                    "android.chat.openai.com",
                    "api.openai.com",
                    "ws.chatgpt.com",
                    // Статика, файлы и вспомогательные сервисы.
                    "oaistatic.com",
                    "oaiusercontent.com",
                    "openaimerge.com",
                    "workos.com",
                    // Проверка Cloudflare при входе. Домен общий, но без него
                    // форма входа ChatGPT не проходит.
                    "challenges.cloudflare.com",
                ),
                // Только проверенный non-RU egress; иначе пустой order = DROP.
                order = aiEgressOrder,
                name = "openai",
            )
        }
        // Claude/Anthropic: та же fail-closed политика; direct/dnsai/spoof
        // запрещены, пока egress не подтверждён.
        add(
            rules,
            input.anthropic,
            aiEgressOrder,
            "anthropic",
        )
        if (!strictFullTunnel) {
            // Китайские AI доступны из РФ напрямую; отдельная группа позволяет
            // переопределить это, не трогая остальные AI-правила. В Strict
            // DIRECT-правило не создаётся: трафик уходит в defaultOrder.
            add(
                rules,
                input.chinaAi,
                order("china_ai", input.overrides, opera, spoof, killSwitch),
                "china-ai",
                udpOrder = listOf("direct"),
            )
        }
        add(
            rules,
            input.googleAi,
            order(
                "google_ai",
                input.overrides,
                opera,
                spoof,
                killSwitch,
                vless = vless,
                awg = awg,
                dnsAi = dnsAi,
                nonRuSlots = nonRuSlots,
            ),
            "google-ai",
        )
        if (!strictFullTunnel) {
            // Spotify в PC-политике идёт напрямую; в Strict правило не
            // создаётся, иначе оно вернуло бы DIRECT.
            add(
                rules,
                input.spotify,
                order("spotify", input.overrides, opera, spoof, killSwitch),
                "spotify",
                udpOrder = listOf("direct"),
            )
        }
        add(
            rules,
            input.ai,
            order(
                "ai",
                input.overrides,
                opera,
                spoof,
                killSwitch,
                vless = vless,
                awg = awg,
                dnsAi = dnsAi,
                nonRuSlots = nonRuSlots,
            ),
            "ai",
        )
        add(rules, input.second, order("eu", input.overrides, opera, spoof, killSwitch), "eu")
        if (input.youtubeIps.isNotEmpty()) {
            rules += RouterConfig.RuleSpec(
                domains = emptyList(),
                order = order("youtube", input.overrides, opera, spoof, killSwitch),
                name = "youtube-ip",
                cidrs = input.youtubeIps,
                udpOrder = primaryUdpOrder,
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
                add("primary")
            }
        } else {
            baseTelegramOrder
        }
        // Telegram UDP идёт только через проверенный AWG-слот; без него —
        // DROP, без перехода на primary/Opera.
        val telegramUdpOrder = if (awg && "awg" in udpSlots) listOf("awg") else emptyList()
        if (input.telegramIps.isNotEmpty()) {
            rules += RouterConfig.RuleSpec(
                domains = emptyList(),
                order = telegramPreferredOrder,
                name = "telegram-ip",
                cidrs = input.telegramIps,
                udpOrder = telegramUdpOrder,
            )
        }
        add(
            rules,
            input.youtube,
            order("youtube", input.overrides, opera, spoof, killSwitch),
            "youtube",
            udpOrder = primaryUdpOrder,
        )
        // Специфические сервисы — раньше общей категории ru: иначе их
        // override и закреплённый порядок молча перекрываются списком main.
        add(
            rules,
            input.telegram,
            telegramPreferredOrder,
            "telegram",
            udpOrder = telegramUdpOrder,
        )
        add(
            rules,
            input.whatsapp,
            order("whatsapp", input.overrides, opera, spoof, killSwitch),
            "whatsapp",
            udpOrder = primaryUdpOrder,
        )
        add(
            rules,
            input.discord,
            order("discord", input.overrides, opera, spoof, killSwitch),
            "discord",
            udpOrder = primaryUdpOrder,
        )
        add(
            rules,
            input.main,
            order("ru", input.overrides, opera, spoof, killSwitch),
            "ru",
            udpOrder = primaryUdpOrder,
        )

        val slots = mutableMapOf(
            "primary" to RouterConfig.SlotSpec(
                type = "socks5",
                addr = "127.0.0.1:$primaryPort",
                udp = "primary" in udpSlots,
            ),
        )
        if (!strictFullTunnel) {
            slots["direct"] = RouterConfig.SlotSpec("direct", udp = "direct" in udpSlots)
        }
        if (spoof) {
            slots["spoof"] = RouterConfig.SlotSpec(
                type = "socks5",
                addr = "127.0.0.1:$spoofPort",
                udp = "spoof" in udpSlots,
            )
        }
        if (opera) {
            slots["secondary"] = RouterConfig.SlotSpec(
                type = "http",
                addr = "127.0.0.1:$operaPort",
                udp = "secondary" in udpSlots,
            )
        }
        if (cfProxy) {
            slots["cfproxy"] = RouterConfig.SlotSpec(
                type = "socks5",
                addr = "127.0.0.1:$cfProxyPort",
                udp = "cfproxy" in udpSlots,
            )
        }
        if (vless) {
            slots["vless"] = RouterConfig.SlotSpec(
                type = "socks5",
                addr = "127.0.0.1:$vlessPort",
                udp = "vless" in udpSlots,
            )
        }
        if (awg) {
            slots["awg"] = RouterConfig.SlotSpec(
                type = "socks5",
                addr = "127.0.0.1:$awgPort",
                udp = "awg" in udpSlots,
            )
        }
        // DNS-AI больше не egress-слот: его конфигурация (URL/bootstrap/via)
        // остаётся в DNS resolver-слое (PriorityDns), а не в router.
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
            // Приложения, которым нужен IP другой страны, идут в default.
            // Живой Opera (US/EU) — единственный проверенный non-RU выход,
            // поэтому он первый; VLESS — запас, когда Opera недоступна.
            // Telegram и YouTube этим правилом не затрагиваются: у них свои
            // закреплённые маршруты.
            defaultOrder = when {
                opera -> buildList {
                    add("secondary")
                    add("primary")
                    if (spoof) add("spoof")
                    if (!killSwitch) add("direct")
                }
                vless -> buildList {
                    add("vless")
                    add("primary")
                    if (spoof) add("spoof")
                    if (!killSwitch) add("direct")
                }
                else -> order("other", input.overrides, opera, spoof, killSwitch)
            },
            // UDP по умолчанию идёт только в primary; если primary не
            // UDP-capable, router отбросит пакет. direct автоматически не
            // добавляется.
            defaultUdpOrder = primaryUdpOrder,
            strict = strictFullTunnel,
        )
    }

    fun order(
        service: String,
        overrides: Map<String, RouteClass?>,
        opera: Boolean,
        spoof: Boolean = false,
        killSwitch: Boolean = false,
        vless: Boolean = false,
        awg: Boolean = false,
        dnsAi: Boolean = false,
        nonRuSlots: Set<String> = emptySet(),
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
        // AI: только транспорты с фактически проверенным non-RU egress.
        // secondary/vless — кандидаты; без проверки их здесь нет.
        val ai = buildList {
            if (opera && "secondary" in nonRuSlots) add("secondary")
            if (vless && "vless" in nonRuSlots) add("vless")
        }
        val aiService = service in setOf("ai", "google_ai", "openai", "anthropic")
        // Telegram в PC-политике закреплён за relay/PRIMARY и не должен уходить
        // в DIRECT: иначе при отказе слота он молча пойдёт мимо туннеля.
        val pinned = buildList {
            add("primary")
            if (spoof) add("spoof")
        }
        // DeepSeek/Qwen: из РФ доступны напрямую, туннель им не нужен.
        val chinaAi = listOf("direct")
        // Для AI любой override, кроме BLOCK, не должен обходить non-RU
        // ограничение: иначе пользовательская настройка вернёт direct.
        if (aiService && overrides[service] != null && overrides[service] != RouteClass.BLOCK) {
            return ai
        }
        return when (overrides[service]) {
            RouteClass.DIRECT -> listOf("direct")
            RouteClass.BLOCK -> emptyList()
            RouteClass.RU -> ru
            RouteClass.EU -> eu
            RouteClass.YOUTUBE -> google
            RouteClass.AI, RouteClass.GOOGLE_AI -> ai
            RouteClass.TELEGRAM -> pinned
            RouteClass.WHATSAPP, RouteClass.DISCORD -> ru
            null -> when (service) {
                "eu" -> eu
                "youtube" -> google
                "ai", "google_ai" -> ai
                "openai" -> ai
                "anthropic" -> ai
                "china_ai" -> chinaAi
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
        udpOrder: List<String> = emptyList(),
    ) {
        if (domains.isEmpty()) return
        rules += RouterConfig.RuleSpec(domains, order, name, udpOrder = udpOrder)
    }
}
