package com.example.nova

/**
 * Сервисные категории маршрутов — то, что в PC-версии задаётся `routes` в
 * `routing_settings.json` (`ai_apps`, `telegram`, `browser`, …).
 *
 * Значение хранится строкой: `auto` — политика решает сама, иначе конкретный
 * класс маршрута. Строка, а не enum, чтобы новые значения не ломали старые
 * настройки при откате версии.
 */
object RouteSettings {

    const val AUTO = "auto"

    data class Service(
        val key: String,
        val title: String,
        val defaultClass: RouteClass,
    )

    val SERVICES: List<Service> = listOf(
        Service("ru", "RU-сайты", RouteClass.RU),
        Service("eu", "EU и гео-сервисы", RouteClass.EU),
        Service("youtube", "YouTube", RouteClass.YOUTUBE),
        Service("telegram", "Telegram", RouteClass.TELEGRAM),
        Service("whatsapp", "WhatsApp", RouteClass.WHATSAPP),
        Service("discord", "Discord", RouteClass.DISCORD),
        Service("spotify", "Spotify", RouteClass.DIRECT),
        Service("ai", "AI (Gemini, ChatGPT)", RouteClass.AI),
        Service("google_ai", "Google AI / opencode", RouteClass.GOOGLE_AI),
        Service("other", "Остальной трафик", RouteClass.RU),
    )

    fun service(key: String): Service? = SERVICES.firstOrNull { it.key == key }

    /** Разбор сохранённого значения: неизвестное — это AUTO. */
    fun parseOverride(raw: String?): RouteClass? {
        val value = raw?.trim()?.lowercase().orEmpty()
        if (value.isEmpty() || value == AUTO) return null
        return when (value) {
            "direct" -> RouteClass.DIRECT
            "block" -> RouteClass.BLOCK
            "ru" -> RouteClass.RU
            "eu" -> RouteClass.EU
            "youtube" -> RouteClass.YOUTUBE
            "telegram" -> RouteClass.TELEGRAM
            "whatsapp" -> RouteClass.WHATSAPP
            "discord" -> RouteClass.DISCORD
            "ai" -> RouteClass.AI
            "google_ai" -> RouteClass.GOOGLE_AI
            else -> null
        }
    }

    fun encodeOverride(routeClass: RouteClass?): String =
        when (routeClass) {
            null -> AUTO
            RouteClass.DIRECT -> "direct"
            RouteClass.BLOCK -> "block"
            RouteClass.RU -> "ru"
            RouteClass.EU -> "eu"
            RouteClass.YOUTUBE -> "youtube"
            RouteClass.TELEGRAM -> "telegram"
            RouteClass.WHATSAPP -> "whatsapp"
            RouteClass.DISCORD -> "discord"
            RouteClass.AI -> "ai"
            RouteClass.GOOGLE_AI -> "google_ai"
        }

    /** Человеческая подпись выбора для строки экрана. */
    fun label(routeClass: RouteClass?): String = when (routeClass) {
        null -> "AUTO"
        RouteClass.DIRECT -> "DIRECT"
        RouteClass.BLOCK -> "BLOCK"
        RouteClass.RU -> "PRIMARY"
        RouteClass.EU -> "SECONDARY"
        RouteClass.YOUTUBE -> "SECONDARY"
        RouteClass.TELEGRAM -> "PRIMARY"
        RouteClass.WHATSAPP -> "PRIMARY"
        RouteClass.DISCORD -> "PRIMARY"
        RouteClass.AI -> "SECONDARY"
        RouteClass.GOOGLE_AI -> "SECONDARY"
    }

    /**
     * Подпись автоматической цепочки — ровно та, которую соберёт
     * `RouterConfigFactory.order`. Раньше эта таблица жила второй копией в
     * `RoutesActivity.defaultHint`, и копии разошлись: экран обещал Telegram
     * `RELAY`, которого в конфигурации роутера нет. Одна таблица на UI и тесты.
     */
    fun chainHint(routeClass: RouteClass?): String = when (routeClass) {
        null -> "AUTO"
        RouteClass.DIRECT -> "DIRECT"
        RouteClass.BLOCK -> "BLOCK"
        RouteClass.RU -> "PRIMARY → DIRECT"
        RouteClass.EU -> "SECONDARY → PRIMARY → DIRECT"
        RouteClass.YOUTUBE -> "SECONDARY → PRIMARY → DIRECT"
        RouteClass.TELEGRAM -> "PRIMARY"
        RouteClass.WHATSAPP -> "PRIMARY → DIRECT"
        RouteClass.DISCORD -> "PRIMARY → DIRECT"
        RouteClass.AI -> "SECONDARY → PRIMARY → DIRECT"
        RouteClass.GOOGLE_AI -> "SECONDARY → PRIMARY → DIRECT"
    }
}
