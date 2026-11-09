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
     * Bootstrap-адреса WARP-фида: готовый конфиг, когда регистрация Cloudflare
     * не проходит. Это именно семена обхода, а не данные: список обязан быть
     * известен до первого запроса, поэтому он живёт в коде. Порядок — от
     * основного работника к личному Worker'у владельца; последний намеренно
     * в конце, чтобы приложение не зависело от него.
     */
    val WARP_CONFIG_FEED_BASES = listOf(
        "https://warp-workers.warp-api.cfd",
        "https://warp-sub.warp-api.cfd",
        "https://warp-vercel-chi.warp-api.cfd",
        "https://warp-vercel-murex.warp-api.cfd",
        "https://warp-netlify.warp-api.cfd",
        "https://obnova-warp.yurentiy-avd.workers.dev/",
    )

    /**
     * Bootstrap-хосты Proton API. Запасной узел для заблокированных сетей
     * ищется динамически через DoH (`ProtonDoh`), поэтому здесь только те
     * адреса, которые обязаны быть известны до первого запроса.
     */
    val PROTON_API_HOSTS = listOf(
        "https://vpn-api.proton.me",
        "https://api.protonvpn.ch",
    )

    /** Регистрация WARP в Cloudflare: внешний API, а не наш фид. */
    const val CLOUDFLARE_WARP_REG_URL =
        "https://api.cloudflareclient.com/v0a4471/reg"

    /** Подписка AWG/WARP по умолчанию: внутренний источник автоподгрузки. */
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
        // Два резервных публичных списка из PC-версии. Тело решает протокол
        // (VLESS/VMess/Trojan/SS); это резерв, а не основа приватного маршрута.
        VlessSource(
            name = "vpn-free-russia verified (public)",
            url = "https://raw.githubusercontent.com/aviamastersgh/vpn-free-russia/main/verified_configs.txt",
        ),
        VlessSource(
            name = "vpn-free-russia RU (public)",
            url = "https://raw.githubusercontent.com/aviamastersgh/vpn-free-russia/main/ru_configs.txt",
        ),
    )

    /** Нейтральный адрес: по нему проверяется, что узел пропускает TLS. */
    /**
     * Проверенные вручную VLESS-семена (2026-11-09): узел из штатного источника
     * Delta-Kronecker REALITY. Через Xray он даёт YouTube 200 и отвечает на
     * Telegram DC2. Это не новый источник, а приоритетная проба: если узел
     * умрёт, MULTI перейдёт к обычному списку профилей.
     */
    val VLESS_VERIFIED_SEEDS = listOf(
        // 2026-11-09, проверено Xray 26.10.31: TLS WS, SNI мобильный DPI
        // пропускает (200), YouTube 200 и Telegram DC2 302.
        "vless://95f85937-7b95-45c4-8d69-95da6a978569@95.129.236.248:443" +
            "?alpn=http%2F1.1&encryption=none" +
            "&path=%2Fgateway%2Fv1%2Fws%2F791f9a4d19f715c2ffaedfbaf99d88bd" +
            "&security=tls&sni=chat.gpt.prem-ai.shop&host=chat.gpt.prem-ai.shop" +
            "&type=ws&fp=firefox#OBNOVA%20seed%20prem-ai",
        // 2026-11-09, проверено Xray 26.10.31 с PC и SNI-проба с телефона:
        // эти SNI мобильный DPI не блокирует (200/403, не 000).
        "vless://dd5bed0b-ee77-481b-9f2c-250a632ddd9f@185.189.45.195:443" +
            "?security=reality&encryption=none" +
            "&pbk=BIy6H-jndn24Ukyw83ML8LDB1HL8WLJmfCADEy8lzVs" +
            "&headerType=none&fp=chrome&spx=/&type=tcp&flow=xtls-rprx-vision" +
            "&sni=get.jarbook.net&sid=c6f97313#OBNOVA%20seed%20jarbook",
        "vless://c82031be-e90f-4b15-ab4f-7ccef236d3ca@83.171.241.153:443" +
            "?encryption=none&pbk=ixnCbdlT5sAVd-ilp2nG11CvnNsBCf9xPD56TlXxlR4" +
            "&security=reality&sid=a91531ad58c8611c&sni=d58daft.plp1.h1cloud.net" +
            "&type=tcp&fp=edge#OBNOVA%20seed%20h1cloud",
        "vless://3bb97d46-789b-4229-88f6-9beb222a4137@217.25.93.165:443" +
            "?security=reality&encryption=none" +
            "&pbk=ZJlfiMh4ebjrTl2_kwAk_zXMAaCiwNS7yMd5XzlUgmI" +
            "&headerType=none&fp=chrome&type=tcp&flow=xtls-rprx-vision" +
            "&sni=yoy-api.bachidze.com#OBNOVA%20seed%20bachidze",
        // Старое семя оставлено запасным: на проводном канале работает, но
        // мобильный DPI блокирует его SNI.
        "vless://f8d52ece-fad4-478d-875d-df6eed3befa7@193.25.216.88:443" +
            "?type=tcp&security=reality&encryption=none&sni=sw.golangwikito.cfd" +
            "&fp=chrome&pbk=Kb8SsVPprs46BJSUNp5JvWLI-TFEiHmlBOXLQd37Glg" +
            "&sid=a1037b25dcef6aa7&headerType=none&flow=xtls-rprx-vision" +
            "#OBNOVA%20seed%20golangwikito",
    )

    const val NEUTRAL_HTTPS_PROBE_HOST = "example.com"

    /**
     * PC-схема прямо предупреждает: EU-пул Opera на `77.111.247.*` отдаёт
     * `/sorry`/502, рабочий — `77.111.244.*`. Ставим `.244` в голову очереди,
     * `.247` — в хвост; остальные не трогаем. Это не выбор страны: страну
     * задаёт регион, здесь только порядок уже полученных endpoint-ов.
     */
    fun prioritizeOperaEndpoints(endpoints: List<String>): List<String> =
        endpoints.sortedBy { endpoint ->
            when {
                endpoint.startsWith("77.111.244.") -> 0
                endpoint.startsWith("77.111.247.") -> 2
                else -> 1
            }
        }

    /** Слабый EU-пул: с него не начинаем, если есть свежий feed. */
    fun isWeakOperaEndpoint(endpoint: String): Boolean =
        endpoint.startsWith("77.111.247.")

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
