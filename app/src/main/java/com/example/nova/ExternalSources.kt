package com.example.nova

/**
 * Единственная точка инвентаризации внешних источников форка.
 *
 * Здесь перечислены все адреса, по которым приложение получает данные. Если
 * появляется новый источник, он добавляется сюда, а не растворяется строкой в
 * случайном экране: так проще проверить, что у каждого вызова есть таймаут,
 * проверка ответа и запасной путь.
 *
 * Что остаётся константой и почему:
 *  - репозиторий списков — стоковый фид PC-форка `confeden/nova_updates`;
 *  - публичная VLESS-подписка — тот же источник, что в
 *    `profiles/subscriptions.json` PC; это seed, а не гарантия живых узлов;
 *  - нейтральный хост для проверки TLS — `example.com`: он не принадлежит
 *    блокируемым сервисам и отвечает с любого исправного выхода.
 */
object ExternalSources {

    const val LIST_REPO = "confeden/nova_updates"
    const val LIST_BASE = "nova_pc"
    const val LIST_MANIFEST = "$LIST_BASE/manifest.json"

    /** Лента собственного DNS-туннеля Nova (домен, NS, метод, ключ). */
    const val DNS_TUNNEL_FEED = "nova_android/dns_tunnel.json"

    /** Опубликованный список рабочих адресов Opera. */
    const val OPERA_ENDPOINTS_FEED = "opera_endpoints.json"

    /**
     * Репозиторий релизов **этого форка**, а не upstream.
     *
     * Обновление обязано смотреть сюда: upstream-релизы подписаны другим ключом
     * и не встают поверх личной сборки. Если репозиторий приватный, GitHub API
     * отвечает 404 без токена — `AppUpdateManager` пишет это в журнал и
     * обновление остаётся ручным.
     */
    const val RELEASES_REPO = "abonent0007/OBNOVA"

    /** Последний релиз форка напрямую через GitHub API. */
    const val GITHUB_LATEST_RELEASE_API =
        "https://api.github.com/repos/$RELEASES_REPO/releases/latest"

    /** Страница релизов для кнопки «Скачать последнюю версию». */
    const val RELEASES_PAGE_URL = "https://github.com/$RELEASES_REPO/releases"

    /**
     * Подписка AWG/WARP по умолчанию в экране «Конфигурации».
     *
     * Источник — стоковый PC-форк; на наш релизный репозиторий его не менять:
     * обновления APK и подписки — разные каналы.
     */
    const val WARP_CONFIGS_FEED =
        "https://raw.githubusercontent.com/confeden/auto-warp-config/refs/heads/main/config.txt"

    /**
     * Сборщики мостов Tor: основной `OnionHop` и запасной `Delta-Kronecker`.
     * Зеркала запрашиваются наперегонки — см. `TorBridges`.
     */
    val TOR_BRIDGE_MIRROR_BASES = listOf(
        "https://raw.githubusercontent.com/center2055/OnionHop-Bridges-Collector/main/bridge/",
        "https://center2055.github.io/OnionHop-Bridges-Collector/bridge/",
        "https://cdn.jsdelivr.net/gh/center2055/OnionHop-Bridges-Collector@main/bridge/",
        "https://cdn.statically.io/gh/center2055/OnionHop-Bridges-Collector@main/bridge/",
    )

    val TOR_BRIDGE_FALLBACK_BASES = listOf(
        "https://raw.githubusercontent.com/Delta-Kronecker/Tor-Bridges-Collector/main/bridge/",
        "https://cdn.jsdelivr.net/gh/Delta-Kronecker/Tor-Bridges-Collector@main/bridge/",
    )

    /** MOAT (Tor Project): настройки и встроенные мосты. */
    const val TOR_MOAT_SETTINGS_URL = "https://bridges.torproject.org/moat/circumvention/settings"
    const val TOR_MOAT_BUILTIN_URL = "https://bridges.torproject.org/moat/circumvention/builtin"

    /** Публичная подписка VLESS из стокового PC-форка (первый источник). */
    const val PUBLIC_VLESS_SUBSCRIPTION_URL =
        "https://iboxz.github.io/free-v2ray-collector/main/vless.txt"

    /** Один публичный источник подписок: имя для экрана и адрес обновления. */
    data class VlessSource(val name: String, val url: String)

    /**
     * Все публичные источники VLESS из PC-версии
     * (`resources/nova_subscriptions.py`, `DEFAULT_SOURCES`).
     *
     * Порядок важен: iboxz идёт первым, потому что `github.io` отвечает из
     * России без обхода; остальные три — независимые пулы, которые PC опрашивает
     * с тем же интервалом 12 ч.
     */
    val PUBLIC_VLESS_SOURCES = listOf(
        VlessSource(
            name = "iboxz",
            url = PUBLIC_VLESS_SUBSCRIPTION_URL,
        ),
        VlessSource(
            name = "Delta-Kronecker REALITY",
            url = "https://raw.githubusercontent.com/Delta-Kronecker/V2ray-Config/main/config/reality/reality.txt",
        ),
        VlessSource(
            name = "0xRadikal VLESS",
            url = "https://raw.githubusercontent.com/0xRadikal/Free-v2ray-Configs/main/protocols/vless.txt",
        ),
        VlessSource(
            name = "barry-far VLESS",
            url = "https://raw.githubusercontent.com/barry-far/V2ray-Config/main/Splitted-By-Protocol/vless.txt",
        ),
    )

    /** Нейтральный адрес: по нему проверяется, что узел пропускает TLS. */
    const val NEUTRAL_HTTPS_PROBE_HOST = "example.com"

    /** Зеркала фида: порядок важен — первым идёт основной raw-адрес. */
    fun listMirrors(relativePath: String): List<String> = listOf(
        "https://raw.githubusercontent.com/$LIST_REPO/main/$relativePath",
        "https://cdn.jsdelivr.net/gh/$LIST_REPO@main/$relativePath",
        "https://raw.githack.com/$LIST_REPO/main/$relativePath",
    )

    /**
     * Оригинальный репозиторий PC-версии: там лежат `ip/`-списки, которых нет
     * в опубликованном манифесте `nova_updates` (только `list/`).
     */
    const val DATA_REPO = "confeden/Nova"

    /** Зеркала данных `confeden/Nova`: raw, jsDelivr, raw.githack. */
    fun dataMirrors(relativePath: String): List<String> = listOf(
        "https://raw.githubusercontent.com/$DATA_REPO/main/$relativePath",
        "https://cdn.jsdelivr.net/gh/$DATA_REPO@main/$relativePath",
        "https://raw.githack.com/$DATA_REPO/main/$relativePath",
    )

    /**
     * Зеркала произвольной GitHub-подписки в порядке PC-версии:
     * `raw.githack.com` (быстрее всего отвечает из РФ), исходный raw, jsDelivr.
     *
     * Для не-GitHub ссылки возвращается она сама: подменять чужой адрес
     * зеркалом нельзя.
     */
    fun subscriptionMirrors(url: String): List<String> {
        val prefix = "https://raw.githubusercontent.com/"
        if (!url.startsWith(prefix, ignoreCase = true)) return listOf(url)
        val parts = url.removePrefix(prefix).split('/', limit = 4)
        if (parts.size < 4 || parts[3].isBlank()) return listOf(url)
        val owner = parts[0]
        val repo = parts[1]
        val ref = parts[2]
        val path = parts[3]
        return listOf(
            "https://raw.githack.com/$owner/$repo/$ref/$path",
            url,
            "https://cdn.jsdelivr.net/gh/$owner/$repo@$ref/$path",
        )
    }

    /**
     * Два адреса лент `nova_updates`: raw и GitHub Pages.
     *
     * Один и тот же файл в одном репозитории; если один хост недоступен,
     * второй обычно жив. Порядок фиксирован тестом: raw обновляется первым,
     * Pages — следом.
     */
    fun updateFeedMirrors(relativePath: String): List<String> = listOf(
        "https://raw.githubusercontent.com/$LIST_REPO/main/$relativePath",
        "https://confeden.github.io/nova_updates/$relativePath",
    )
}
