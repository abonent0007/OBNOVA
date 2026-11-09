package com.example.nova

import java.util.Locale

/**
 * Класс маршрута — то, что в PC-версии выбирает PAC.
 *
 * Политика не знает, какие слоты сейчас живы: она отвечает «куда этот трафик
 * должен идти по правилам», а цепочку слотов строит SlotResolver. Такое
 * разделение позволяет тестировать правила на JVM без Android и без сети.
 */
enum class RouteClass {
    DIRECT,
    RU,
    EU,
    YOUTUBE,
    TELEGRAM,
    WHATSAPP,
    DISCORD,
    AI,
    GOOGLE_AI,
    BLOCK,
}

data class RouteDecision(
    val routeClass: RouteClass,
    val reason: String,
)

data class RouteRequest(
    val host: String?,
    val ip: String? = null,
    val packageName: String? = null,
)

/** Режим по умолчанию: весь трафик через VPN или только явные правила. */
enum class DefaultRoutingMode { VPN, DIRECT }

data class RoutingConfig(
    val mode: DefaultRoutingMode = DefaultRoutingMode.VPN,
    /** Локальные TLD идут напрямую (`.ru`, `.su`, `.рф` …). */
    val localTldDirect: Boolean = true,
    /** Непойманный трафик — в VPN (наш патч PC `foreign_default`). */
    val foreignDefaultVpn: Boolean = true,
    /** Приложения, которым назначен маршрут явно. */
    val appModes: Map<String, RouteClass> = emptyMap(),
    val userDirect: Set<String> = emptySet(),
    val userRu: Set<String> = emptySet(),
    val userEu: Set<String> = emptySet(),
)

/** Источники списков. В Android их наполняет ListRepository. */
interface RoutingLists {
    fun inExclude(host: String): Boolean
    fun inSecond(host: String): Boolean
    fun inMain(host: String): Boolean
    fun inAi(host: String): Boolean
    /** Google AI/opencode: Opera первой, затем primary, DESYNC — запас. */
    fun inGoogleAi(host: String): Boolean = false
    fun inYoutube(host: String): Boolean
    fun inTelegram(host: String): Boolean
    fun inWhatsapp(host: String): Boolean
    fun inDiscord(host: String): Boolean
    fun isCloudflareIp(ip: String): Boolean
    /** Локальные TLD — тоже данные: фид может принести свой набор. */
    fun isLocalTld(host: String): Boolean = RoutingPolicy.isLocalTld(host)
}

object EmptyRoutingLists : RoutingLists {
    override fun inExclude(host: String) = false
    override fun inSecond(host: String) = false
    override fun inMain(host: String) = false
    override fun inAi(host: String) = false
    override fun inYoutube(host: String) = false
    override fun inTelegram(host: String) = false
    override fun inWhatsapp(host: String) = false
    override fun inDiscord(host: String) = false
    override fun isCloudflareIp(ip: String) = false
}

/**
 * Порядок правил повторяет PAC PC 1.44.x (docs/agents/routing.md):
 *
 * `localhost/IP → user_direct → user_ru/eu → exclude → локальные TLD →
 *  app-режимы → AI → eu → youtube → ru → discord/telegram/whatsapp →
 *  Cloudflare-IP → foreign_default`.
 *
 * Правило `eu_over_exclude` появится вместе со списками, когда у них будет
 * отдельный источник; до этого его роль исполняет `userEu`.
 */
object RoutingPolicy {

    val LOCAL_TLDS = setOf(
        "ru", "su",
        "рф", "xn--p1ai",
        "москва", "xn--80adxhks",
        "moscow",
        "рус", "xn--p1acf",
        "дети", "xn--d1acj3b",
        "tatar",
    )

    fun decide(
        request: RouteRequest,
        config: RoutingConfig = RoutingConfig(),
        lists: RoutingLists = EmptyRoutingLists,
    ): RouteDecision {
        val host = normalizeHost(request.host)
        val ip = request.ip?.trim()

        // 1. Локальные имена, IP-литералы и plain hostnames — всегда напрямую.
        if (host == null && ip == null) return RouteDecision(RouteClass.DIRECT, "пустой адрес")
        if (host != null && isLocalHost(host)) return RouteDecision(RouteClass.DIRECT, "локальный адрес")
        if (host != null && isIpLiteral(host)) return RouteDecision(RouteClass.DIRECT, "IP-литерал")
        if (host != null && !host.contains('.')) return RouteDecision(RouteClass.DIRECT, "plain hostname")

        // 2. Режим «PAC off»: всё напрямую, кроме явного режима приложения.
        if (config.mode == DefaultRoutingMode.DIRECT) {
            config.appModes[request.packageName]?.let {
                return RouteDecision(it, "явный режим приложения")
            }
            return RouteDecision(RouteClass.DIRECT, "режим DIRECT")
        }

        if (host != null) {
            // 3. Пользовательские списки.
            if (matchAny(host, config.userDirect)) return RouteDecision(RouteClass.DIRECT, "user_direct")
            if (matchAny(host, config.userRu)) return RouteDecision(RouteClass.RU, "user_ru")
            if (matchAny(host, config.userEu)) return RouteDecision(RouteClass.EU, "user_eu")

            // 4. exclude — всегда напрямую.
            if (lists.inExclude(host)) return RouteDecision(RouteClass.DIRECT, "exclude")

            // 5. Локальные TLD.
            if (config.localTldDirect && lists.isLocalTld(host)) {
                return RouteDecision(RouteClass.DIRECT, "локальный TLD")
            }
        }

        // 6. Явный режим приложения важнее списков сервисов.
        config.appModes[request.packageName]?.let {
            return RouteDecision(it, "режим приложения")
        }

        if (host != null) {
            // 7. AI-домены.
            if (lists.inAi(host)) return RouteDecision(RouteClass.AI, "AI-домены")

            // 8–9. EU и YouTube.
            if (lists.inSecond(host)) return RouteDecision(RouteClass.EU, "second (EU)")
            if (lists.inYoutube(host)) return RouteDecision(RouteClass.YOUTUBE, "youtube")

            // 10. RU-список.
            if (lists.inMain(host)) return RouteDecision(RouteClass.RU, "main (RU)")

            // 11. Сервисные профили.
            if (lists.inDiscord(host)) return RouteDecision(RouteClass.DISCORD, "discord")
            if (lists.inTelegram(host)) return RouteDecision(RouteClass.TELEGRAM, "telegram")
            if (lists.inWhatsapp(host)) return RouteDecision(RouteClass.WHATSAPP, "whatsapp")
        }

        // 12. Cloudflare-IP fallback.
        if (ip != null && lists.isCloudflareIp(ip)) {
            return RouteDecision(RouteClass.RU, "Cloudflare IP")
        }

        // 13. foreign_default.
        return if (config.foreignDefaultVpn) {
            RouteDecision(RouteClass.RU, "foreign_default → VPN")
        } else {
            RouteDecision(RouteClass.DIRECT, "foreign_default → DIRECT")
        }
    }

    fun normalizeHost(raw: String?): String? =
        raw?.trim()
            ?.lowercase(Locale.US)
            ?.removeSuffix(".")
            ?.takeIf { it.isNotEmpty() }

    fun isLocalHost(host: String): Boolean =
        host == "localhost" ||
            host.endsWith(".localhost") ||
            host.endsWith(".local") ||
            host == "127.0.0.1" ||
            host == "::1"

    fun isIpLiteral(host: String): Boolean {
        if (host.contains(':')) return true
        val parts = host.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) && part.toIntOrNull() in 0..255
        }
    }

    fun isLocalTld(host: String): Boolean {
        val tld = host.substringAfterLast('.')
        return tld in LOCAL_TLDS
    }

    /** Домен или любой его поддомен; `example.com` не матчит `notexample.com`. */
    fun matches(host: String, domain: String): Boolean {
        val normalized = normalizeHost(domain) ?: return false
        return host == normalized || host.endsWith(".$normalized")
    }

    private fun matchAny(host: String, domains: Set<String>): Boolean =
        domains.any { matches(host, it) }
}
