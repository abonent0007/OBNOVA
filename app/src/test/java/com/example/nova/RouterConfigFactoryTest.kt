package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RouterConfigFactoryTest {

    private fun input(
        operaOverride: Map<String, RouteClass?> = emptyMap(),
    ) = RouterConfigFactory.Input(
        localTlds = setOf("ru", "su"),
        exclude = listOf("bank.example"),
        main = listOf("blocked.example"),
        second = listOf("geo.example"),
        ai = listOf("gemini.google.com"),
        youtube = listOf("youtube.com"),
        telegram = listOf("telegram.org"),
        whatsapp = listOf("whatsapp.com"),
        discord = listOf("discord.com"),
        overrides = operaOverride,
    )

    @Test
    fun `telegram relay slot stays available but is not auto-first`() {
        val json = RouterConfigFactory.build(
            input = input().copy(
                telegramRelayUrl = "wss://relay.example/apiws",
                telegramRelaySecret = "s3cret",
                telegramIps = listOf("149.154.160.0/20"),
            ),
            primaryPort = 1376,
            operaPort = 1371,
            cfProxyPort = 1378,
        )
        assertTrue(json.contains("\"ws\":{\"type\":\"ws\",\"url\":\"wss://relay.example/apiws\",\"secret\":\"s3cret\",\"via\":\"127.0.0.1:1378\"}"))
        assertTrue(json.contains("\"tcpOrder\":[\"primary\"]"))
        assertFalse(json.contains("\"tcpOrder\":[\"ws\""))
    }

    @Test
    fun `spotify goes direct before the eu list`() {
        val json = RouterConfigFactory.build(
            input = input().copy(spotify = listOf("spotify.com")),
            primaryPort = 1376,
            operaPort = 1371,
        )
        assertTrue(json.contains("\"name\":\"spotify\""))
        assertTrue(json.contains("\"tcpOrder\":[\"direct\"]"))
    }

    @Test
    fun `strict config has no direct slot rule or order`() {
        val json = RouterConfigFactory.build(
            input = input().copy(
                strict = true,
                chinaAi = listOf("deepseek.com"),
                spotify = listOf("spotify.com"),
            ),
            primaryPort = 1376,
            operaPort = 1371,
            vlessPort = 1379,
            udpSlots = setOf("primary", "vless"),
        )
        assertTrue(json.contains("\"strict\":true"))
        assertFalse("в strict не должно быть direct вообще:\n$json", json.contains("direct"))
        assertFalse(json.contains("\"name\":\"exclude\""))
        assertFalse(json.contains("\"name\":\"local-tld\""))
        assertFalse(json.contains("\"name\":\"spotify\""))
        assertFalse(json.contains("\"name\":\"china-ai\""))
        // Остальные правила на месте: strict не должен «выключать» маршруты.
        assertTrue(json.contains("\"name\":\"ru\""))
        assertTrue(json.contains("\"name\":\"telegram\""))
    }

    @Test
    fun `strict rejects direct override`() {
        assertThrows(IllegalArgumentException::class.java) {
            RouterConfigFactory.build(
                input = input().copy(
                    strict = true,
                    overrides = mapOf("youtube" to RouteClass.DIRECT),
                ),
                primaryPort = 1376,
                operaPort = 1371,
            )
        }
    }

    @Test
    fun `limited keeps legacy direct behaviour`() {
        val json = RouterConfigFactory.build(
            input = input().copy(
                killSwitch = true,
                spotify = listOf("spotify.com"),
                chinaAi = listOf("deepseek.com"),
            ),
            primaryPort = 1376,
            operaPort = 1371,
        )
        assertFalse(json.contains("\"strict\":true"))
        assertTrue(json.contains("\"name\":\"exclude\""))
        assertTrue(json.contains("\"name\":\"local-tld\""))
        assertTrue(json.contains("\"name\":\"spotify\""))
        assertTrue(json.contains("\"name\":\"china-ai\""))
        assertTrue(json.contains("\"direct\""))
    }

    @Test
    fun `strict config matches serialized go fixture`() {
        val expected = strictFixtureJson()
        val fixture = File("../nova-router/testdata/strict_config.json")
        if (System.getenv("NOVA_WRITE_STRICT_FIXTURE") == "1") {
            fixture.parentFile?.mkdirs()
            fixture.writeText(expected)
        }
        assertTrue("нет фикстуры ${fixture.absolutePath}", fixture.exists())
        assertEquals(expected, fixture.readText())
    }

    private fun strictFixtureJson(): String = RouterConfigFactory.build(
        input = RouterConfigFactory.Input(
            localTlds = setOf("ru", "su"),
            exclude = listOf("bank.example"),
            main = listOf("blocked.example"),
            second = listOf("geo.example"),
            chinaAi = listOf("deepseek.com"),
            spotify = listOf("spotify.com"),
            telegram = listOf("telegram.org"),
            strict = true,
        ),
        primaryPort = 1376,
        operaPort = 1371,
        vlessPort = 1379,
        udpSlots = setOf("primary", "vless"),
    )

    @Test
    fun `youtube prefers primary before desync`() {
        assertEquals(
            listOf("primary", "secondary", "spoof", "direct"),
            RouterConfigFactory.order("youtube", emptyMap(), opera = true, spoof = true),
        )
    }

    @Test
    fun `google ai uses only verified non-ru transports`() {
        assertEquals(
            emptyList<String>(),
            RouterConfigFactory.order("google_ai", emptyMap(), opera = true, spoof = true),
        )
        assertEquals(
            listOf("secondary"),
            RouterConfigFactory.order(
                "google_ai",
                emptyMap(),
                opera = true,
                spoof = true,
                nonRuSlots = setOf("secondary"),
            ),
        )
        assertEquals(
            listOf("secondary", "vless"),
            RouterConfigFactory.order(
                "google_ai",
                emptyMap(),
                opera = true,
                vless = true,
                nonRuSlots = setOf("secondary", "vless"),
            ),
        )
        val json = RouterConfigFactory.build(
            input = input().copy(googleAi = listOf("gemini.google.com")),
            primaryPort = 1376,
            operaPort = 1371,
            nonRuSlots = setOf("secondary"),
        )
        assertTrue(json.contains("\"name\":\"google-ai\""))
        assertTrue(json.contains("\"tcpOrder\":[\"secondary\"]"))
        assertFalse(json.contains("\"tcpOrder\":[\"secondary\",\"direct\""))
    }

    @Test
    fun `dnsai is not used as ai egress`() {
        assertEquals(
            emptyList<String>(),
            RouterConfigFactory.order(
                "google_ai",
                emptyMap(),
                opera = true,
                spoof = true,
                dnsAi = true,
            ),
        )
        val json = RouterConfigFactory.build(
            input = input().copy(googleAi = listOf("gemini.google.com")),
            primaryPort = 1376,
            operaPort = 1371,
            dnsAiUrl = "https://dns.dns-ai.ru/dns-query",
            dnsAiBootstrap = "192.144.59.14,186.246.49.127",
            nonRuSlots = setOf("secondary"),
        )
        assertFalse(json.contains("\"dnsai\":{\"type\":\"dnsai\""))
        assertTrue(json.contains("\"tcpOrder\":[\"secondary\"]"))
        assertFalse(json.contains("\"tcpOrder\":[\"dnsai\""))
    }

    @Test
    fun `google ai json uses only verified vless`() {
        val json = RouterConfigFactory.build(
            input = input().copy(googleAi = listOf("gemini.google.com")),
            primaryPort = 1376,
            operaPort = 1371,
            vlessPort = 1379,
            awgPort = 1378,
            nonRuSlots = setOf("vless"),
        )
        assertTrue(json.contains("\"name\":\"google-ai\""))
        val rule = parseRules(json).first { it.name == "google-ai" }
        assertEquals(listOf("vless"), rule.order)
    }

    @Test
    fun `ai rules never contain direct dnsai or spoof`() {
        val json = RouterConfigFactory.build(
            input = input().copy(
                googleAi = listOf("gemini.google.com"),
                ai = listOf("ai.example"),
                anthropic = listOf("claude.example"),
            ),
            primaryPort = 1376,
            operaPort = 1371,
            vlessPort = 1379,
            dnsAiUrl = "https://dns.dns-ai.ru/dns-query",
            dnsAiBootstrap = "192.144.59.14,186.246.49.127",
            nonRuSlots = setOf("secondary", "vless"),
        )
        val rules = parseRules(json).associateBy { it.name }
        for (name in listOf("google-ai", "ai", "gemini-support", "openai", "anthropic")) {
            val rule = rules[name] ?: error("нет правила $name")
            assertEquals("order $name", listOf("secondary", "vless"), rule.order)
            assertFalse("$name: direct", rule.order.contains("direct"))
            assertFalse("$name: dnsai", rule.order.contains("dnsai"))
            assertFalse("$name: spoof", rule.order.contains("spoof"))
        }
    }

    @Test
    fun `telegram domains use relay even without telegram ips`() {
        val json = RouterConfigFactory.build(
            input = input().copy(
                telegramRelayUrl = "wss://relay.example/apiws",
                telegramRelaySecret = "s3cret",
            ),
            primaryPort = 1376,
            operaPort = 1371,
            cfProxyPort = 1378,
        )
        assertTrue(json.contains("\"name\":\"telegram\""))
        assertTrue(json.contains("\"tcpOrder\":[\"primary\"]"))
    }

    @Test
    fun `telegram prefers vless slot when present`() {
        val json = RouterConfigFactory.build(
            input = input().copy(telegramIps = listOf("149.154.160.0/20")),
            primaryPort = 1379,
            spoofPort = 1376,
            cfProxyPort = 1379,
            vlessPort = 14080,
        )
        assertTrue(json.contains("\"vless\":{\"type\":\"socks5\",\"addr\":\"127.0.0.1:14080\"}"))
        assertTrue(json.contains("\"tcpOrder\":[\"vless\",\"primary\"]"))
    }

    @Test
    fun `telegram prefers awg slot before vless`() {
        val json = RouterConfigFactory.build(
            input = input().copy(telegramIps = listOf("149.154.160.0/20")),
            primaryPort = 1379,
            spoofPort = 1376,
            cfProxyPort = 1379,
            vlessPort = 14080,
            awgPort = 1378,
        )
        assertTrue(json.contains("\"awg\":{\"type\":\"socks5\",\"addr\":\"127.0.0.1:1378\"}"))
        assertTrue(json.contains("\"tcpOrder\":[\"awg\",\"vless\",\"primary\"]"))
    }

    @Test
    fun `mtproto proxy ip goes to cfproxy before default`() {
        val json = RouterConfigFactory.build(
            input = input(),
            primaryPort = 1376,
            operaPort = 1371,
            cfProxyPort = 1378,
        )
        val proxyAt = json.indexOf("\"name\":\"mtproto-proxy\"")
        val ruAt = json.indexOf("\"name\":\"ru\"")
        assertTrue(proxyAt >= 0)
        assertTrue(proxyAt < ruAt)
        assertTrue(
            json.contains(
                "\"name\":\"mtproto-proxy\",\"domains\":[],\"cidrs\":[\"65.109.194.157/32\"],\"tcpOrder\":[\"secondary\",\"cfproxy\",\"primary\"]"
            )
        )
    }

    @Test
    fun `apis rule is before youtube and keeps youtubei on secondary`() {
        val json = RouterConfigFactory.build(
            input = input(),
            primaryPort = 1376,
            operaPort = 1371,
        )
        val apisAt = json.indexOf("\"name\":\"apis\"")
        val youtubeAt = json.indexOf("\"name\":\"youtube\"")
        assertTrue(apisAt >= 0)
        assertTrue(youtubeAt >= 0)
        assertTrue(apisAt < youtubeAt)
        assertTrue(
            json.contains(
                "\"name\":\"apis\",\"domains\":[\"apis.google.com\",\"gvt1.com\"],\"tcpOrder\":[\"primary\",\"direct\"]"
            )
        )
        // apis.google.com РЅРµ РїРѕРєСЂС‹РІР°РµС‚ youtubei.googleapis.com вЂ” РґСЂСѓРіРѕР№ СЃСѓС„С„РёРєСЃ.
        assertFalse(json.contains("\"domains\":[\"apis.google.com\",\"gvt1.com\",\"youtubei"))
    }

    @Test
    fun `without opera eu goes through primary`() {
        val order = RouterConfigFactory.order("eu", emptyMap(), opera = false)
        assertEquals(listOf("primary", "direct"), order)
    }

    @Test
    fun `with opera eu prefers secondary`() {
        val order = RouterConfigFactory.order("eu", emptyMap(), opera = true)
        assertEquals(listOf("secondary", "primary", "direct"), order)
    }

    @Test
    fun `manual overrides win over defaults`() {
        val overrides = mapOf(
            "ru" to RouteClass.DIRECT,
            "youtube" to RouteClass.RU,
            "ai" to RouteClass.BLOCK,
        )
        assertEquals(listOf("direct"), RouterConfigFactory.order("ru", overrides, opera = true))
        assertEquals(listOf("primary", "direct"), RouterConfigFactory.order("youtube", overrides, opera = true))
        assertTrue(RouterConfigFactory.order("ai", overrides, opera = true).isEmpty())
    }

    @Test
    fun `config contains exclude, local tld and service rules in order`() {
        val json = RouterConfigFactory.build(input(), primaryPort = 1376, operaPort = 1371)
        val excludeAt = json.indexOf("bank.example")
        val tldAt = json.indexOf("\"ru\"")
        val mainAt = json.indexOf("blocked.example")
        assertTrue(excludeAt in 0 until tldAt)
        assertTrue(tldAt < mainAt)
        assertTrue(json.contains("\"secondary\":{\"type\":\"http\",\"addr\":\"127.0.0.1:1371\"}"))
        assertTrue(json.contains("\"defaultOrder\":[\"secondary\",\"primary\",\"direct\"]"))
    }

    @Test
    fun `without opera there is no secondary slot`() {
        val json = RouterConfigFactory.build(input(), primaryPort = 1376, operaPort = 0)
        assertFalse(json.contains("\"secondary\""))
    }

    @Test
    fun `blocked service keeps an empty order`() {
        val json = RouterConfigFactory.build(
            input(mapOf("telegram" to RouteClass.BLOCK)),
            primaryPort = 1376,
        )
        assertTrue(json.contains("\"domains\":[\"telegram.org\"],\"tcpOrder\":[]"))
    }

    @Test
    fun `spoof slot goes after primary and before direct`() {
        val order = RouterConfigFactory.order("ru", emptyMap(), opera = false, spoof = true)
        assertEquals(listOf("primary", "spoof", "direct"), order)
        val json = RouterConfigFactory.build(input(), primaryPort = 1370, spoofPort = 1376)
        assertTrue(json.contains("\"spoof\":{\"type\":\"socks5\",\"addr\":\"127.0.0.1:1376\"}"))
    }

    @Test
    fun `killswitch removes direct tail but keeps explicit direct`() {
        val blocked = RouterConfigFactory.order("ru", emptyMap(), opera = false, killSwitch = true)
        assertEquals(listOf("primary"), blocked)
        val explicit = RouterConfigFactory.order(
            "ru",
            mapOf("ru" to RouteClass.DIRECT),
            opera = false,
            killSwitch = true,
        )
        assertEquals(listOf("direct"), explicit)
        val config = RouterConfigFactory.build(
            input().copy(killSwitch = true),
            primaryPort = 1376,
        )
        assertTrue(config.contains("\"defaultOrder\":[\"primary\"]"))
    }

    @Test
    fun `ip literals use cidr rules for youtube and telegram`() {
        val json = RouterConfigFactory.build(
            input().copy(
                youtubeIps = listOf("142.250.0.0/15", "172.217.0.0/16"),
                telegramIps = listOf("149.154.160.0/20"),
            ),
            primaryPort = 1376,
            operaPort = 1371,
            cfProxyPort = 1378,
        )
        val youtubeIpAt = json.indexOf("\"name\":\"youtube-ip\"")
        val telegramIpAt = json.indexOf("\"name\":\"telegram-ip\"")
        val mainAt = json.indexOf("\"name\":\"ru\"")
        assertTrue(youtubeIpAt in 0 until mainAt)
        assertTrue(telegramIpAt in 0 until mainAt)
        assertTrue(json.contains("\"cidrs\":[\"142.250.0.0/15\",\"172.217.0.0/16\"]"))
        assertTrue(json.contains("\"cidrs\":[\"149.154.160.0/20\"]"))
        assertTrue(json.contains("\"cfproxy\":{\"type\":\"socks5\",\"addr\":\"127.0.0.1:1378\"}"))
        assertTrue(
            json.contains(
                "\"name\":\"telegram-ip\",\"domains\":[],\"cidrs\":[\"149.154.160.0/20\"],\"tcpOrder\":[\"primary\"]"
            )
        )
    }

    @Test
    fun `telegram is pinned and never falls back to direct`() {
        assertEquals(
            listOf("primary", "spoof"),
            RouterConfigFactory.order("telegram", emptyMap(), opera = true, spoof = true),
        )
        assertEquals(
            listOf("primary"),
            RouterConfigFactory.order("telegram", emptyMap(), opera = false),
        )
        assertFalse(
            RouterConfigFactory.order("telegram", emptyMap(), opera = true).contains("direct"),
        )
    }

    @Test
    fun `openai rule keeps the whole chatgpt set on one fail-closed route`() {
        val json = RouterConfigFactory.build(
            input(),
            primaryPort = 1376,
            operaPort = 1371,
            nonRuSlots = setOf("secondary"),
        )
        val openAiAt = json.indexOf("\"name\":\"openai\"")
        val googleAiAt = json.indexOf("\"name\":\"google-ai\"")
        assertTrue(openAiAt >= 0)
        if (googleAiAt >= 0) assertTrue(openAiAt < googleAiAt)
        val rule = json.substring(openAiAt, json.indexOf('}', openAiAt))
        for (domain in listOf(
            "chatgpt.com",
            "chat.openai.com",
            "ws.chatgpt.com",
            "auth0.openai.com",
            "android.chat.openai.com",
            "oaiusercontent.com",
            "openaimerge.com",
            "workos.com",
            "challenges.cloudflare.com",
        )) {
            assertTrue("нет домена $domain в openai", rule.contains("\"$domain\""))
        }
        assertTrue(rule.contains("\"tcpOrder\":[\"secondary\"]"))
        assertFalse(rule.contains("\"direct\""))
        assertFalse(rule.contains("\"dnsai\""))
        assertEquals(
            listOf("secondary"),
            RouterConfigFactory.order(
                "openai",
                emptyMap(),
                opera = true,
                nonRuSlots = setOf("secondary"),
            ),
        )
        assertTrue(
            "AI override DIRECT не должен возвращать direct",
            RouterConfigFactory.order(
                "openai",
                mapOf("openai" to RouteClass.DIRECT),
                opera = true,
            ).isEmpty(),
        )
    }

    @Test
    fun `anthropic shares the transport but has its own fail-closed policy`() {
        assertTrue(RouterConfigFactory.order("anthropic", emptyMap(), opera = true).isEmpty())
        assertEquals(
            listOf("secondary"),
            RouterConfigFactory.order(
                "anthropic",
                emptyMap(),
                opera = true,
                nonRuSlots = setOf("secondary"),
            ),
        )
        assertEquals(
            listOf("secondary", "vless"),
            RouterConfigFactory.order(
                "anthropic",
                emptyMap(),
                opera = true,
                vless = true,
                nonRuSlots = setOf("secondary", "vless"),
            ),
        )
        assertTrue(RouterConfigFactory.order("anthropic", emptyMap(), opera = false).isEmpty())
    }

    @Test
    fun `china ai group goes direct and can be overridden separately`() {
        assertEquals(
            listOf("direct"),
            RouterConfigFactory.order("china_ai", emptyMap(), opera = true),
        )
        assertEquals(
            listOf("secondary", "primary"),
            RouterConfigFactory.order(
                "china_ai",
                mapOf("china_ai" to RouteClass.EU),
                opera = true,
                killSwitch = true,
            ),
        )
        val json = RouterConfigFactory.build(
            input = input().copy(
                anthropic = listOf("claude.ai"),
                chinaAi = listOf("deepseek.com"),
            ),
            primaryPort = 1376,
            operaPort = 1371,
        )
        assertTrue(json.contains("\"name\":\"anthropic\""))
        assertTrue(json.contains("\"name\":\"china-ai\""))
    }

    @Test
    fun `udp capability is explicit per slot`() {
        val json = RouterConfigFactory.build(
            input = input(),
            primaryPort = 1378,
            operaPort = 1371,
            awgPort = 1378,
            udpSlots = setOf("primary", "awg", "direct"),
        )
        assertTrue(
            json.contains(
                "\"primary\":{\"type\":\"socks5\",\"addr\":\"127.0.0.1:1378\",\"udp\":true}"
            )
        )
        assertTrue(
            json.contains(
                "\"awg\":{\"type\":\"socks5\",\"addr\":\"127.0.0.1:1378\",\"udp\":true}"
            )
        )
        assertTrue(json.contains("\"direct\":{\"type\":\"direct\",\"udp\":true}"))
        assertFalse(
            json.contains(
                "\"secondary\":{\"type\":\"http\",\"addr\":\"127.0.0.1:1371\",\"udp\":true}"
            )
        )
    }

    @Test
    fun `udp capability survives an awg port change`() {
        // Capability приходит от транспорта, а не от совпадения с 1378:
        // при смене listen-порта слот остаётся UDP-capable.
        val json = RouterConfigFactory.build(
            input = input(),
            primaryPort = 19378,
            awgPort = 19378,
            udpSlots = setOf("primary", "awg", "direct"),
        )
        assertTrue(
            json.contains(
                "\"awg\":{\"type\":\"socks5\",\"addr\":\"127.0.0.1:19378\",\"udp\":true}"
            )
        )
        assertTrue(
            json.contains(
                "\"primary\":{\"type\":\"socks5\",\"addr\":\"127.0.0.1:19378\",\"udp\":true}"
            )
        )
    }

    @Test
    fun `awg port without explicit capability stays tcp-only`() {
        val json = RouterConfigFactory.build(
            input = input(),
            primaryPort = 1378,
            cfProxyPort = 1378,
            awgPort = 1378,
            udpSlots = setOf("direct"),
        )
        assertFalse(json.contains("\"addr\":\"127.0.0.1:1378\",\"udp\":true"))
        assertTrue(json.contains("\"direct\":{\"type\":\"direct\",\"udp\":true}"))
    }

    @Test
    fun `real lists route telegram and openai through their specific rules`() {
        val json = RouterConfigFactory.build(
            input = RouterConfigFactory.Input(
                localTlds = setOf("ru", "su"),
                exclude = assetList("exclude.txt"),
                main = assetList("main.txt"),
                second = assetList("second.txt"),
                ai = assetList("ai.txt"),
                googleAi = assetList("google_ai.txt"),
                anthropic = assetList("anthropic.txt"),
                chinaAi = assetList("china_ai.txt"),
                spotify = assetList("spotify.txt"),
                youtube = assetList("youtube.txt"),
                telegram = assetList("telegram.txt"),
                whatsapp = assetList("whatsapp.txt"),
                discord = assetList("discord.txt"),
            ),
            primaryPort = 1378,
            operaPort = 1371,
            cfProxyPort = 1378,
            vlessPort = 1390,
            awgPort = 1378,
        )
        val rules = parseRules(json)
        // Telegram-домены не должны перехватываться eu/ru.
        assertEquals("telegram", winner("telegram.org", rules))
        assertEquals("telegram", winner("web.telegram.org", rules))
        assertEquals("telegram", winner("t.me", rules))
        assertEquals("telegram", winner("telegra.ph", rules))
        assertEquals("telegram", winner("telegram.me", rules))
        assertEquals("telegram", winner("telesco.pe", rules))
        // Bare openai.com — в специализированном правиле, не в google-ai.
        assertEquals("openai", winner("openai.com", rules))
        assertEquals("openai", winner("chatgpt.com", rules))
        assertEquals("openai", winner("api.openai.com", rules))
        assertEquals("openai", winner("auth.openai.com", rules))
        // AI-домены остаются в своих правилах.
        assertEquals("google-ai", winner("gemini.google.com", rules))
        assertEquals("gemini-support", winner("android.googleapis.com", rules))
        assertEquals("gemini-support", winner("play-fe.googleapis.com", rules))
    }

    @Test
    fun `udp orders are explicit and independent from tcp`() {
        val json = RouterConfigFactory.build(
            input = input().copy(
                youtube = listOf("youtube.com"),
                googleAi = listOf("gemini.google.com"),
                telegram = listOf("telegram.org"),
                telegramIps = listOf("149.154.160.0/20"),
                youtubeIps = listOf("142.250.0.0/15"),
                anthropic = listOf("claude.example"),
            ),
            primaryPort = 1378,
            operaPort = 1371,
            vlessPort = 1390,
            awgPort = 1378,
            nonRuSlots = setOf("secondary"),
            udpSlots = setOf("primary", "awg", "direct"),
        )
        assertTrue(json.contains("\"defaultUdpOrder\":[\"primary\"]"))
        val rules = parseRules(json).associateBy { it.name }
        assertEquals(listOf("primary"), rules["youtube"]?.udpOrder)
        assertEquals(listOf("primary"), rules["youtube-ip"]?.udpOrder)
        assertEquals(listOf("awg"), rules["telegram"]?.udpOrder)
        assertEquals(listOf("awg"), rules["telegram-ip"]?.udpOrder)
        assertEquals(listOf("primary"), rules["ru"]?.udpOrder)
        assertEquals(emptyList<String>(), rules["google-ai"]?.udpOrder)
        assertEquals(emptyList<String>(), rules["gemini-support"]?.udpOrder)
        assertEquals(emptyList<String>(), rules["openai"]?.udpOrder)
        assertEquals(emptyList<String>(), rules["anthropic"]?.udpOrder)
    }

    private fun assetList(name: String): List<String> {
        val candidates = listOf(
            File("src/main/assets/lists/$name"),
            File("app/src/main/assets/lists/$name"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("не найден assets/lists/$name")
        return file.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
    }

    private data class ParsedRule(
        val name: String,
        val domains: List<String>,
        val order: List<String>,
        val udpOrder: List<String>,
    )

    private fun parseRules(json: String): List<ParsedRule> {
        val block = json.substringAfter("\"rules\":[")
            .substringBefore("],\"defaultOrder\"")
        val regex = Regex(
            """\{"name":"([^"]+)","domains":\[(.*?)\](?:,"cidrs":\[.*?\])?,"tcpOrder":\[(.*?)\](?:,"udpOrder":\[(.*?)\])?\}""",
        )
        return regex.findAll(block).map { match ->
            ParsedRule(
                name = match.groupValues[1],
                domains = quoted(match.groupValues[2]),
                order = quoted(match.groupValues[3]),
                udpOrder = quoted(match.groupValues[4]),
            )
        }.toList()
    }

    private fun quoted(value: String): List<String> =
        Regex("\"([^\"]*)\"").findAll(value).map { it.groupValues[1] }.toList()

    private fun winner(host: String, rules: List<ParsedRule>): String =
        rules.firstOrNull { rule ->
            rule.domains.any { domain -> host == domain || host.endsWith(".$domain") }
        }?.name ?: "default"

    @Test
    fun `invalid primary port is rejected before writing a config`() {
        assertThrows(IllegalArgumentException::class.java) {
            RouterConfigFactory.build(input(), primaryPort = 0)
        }
    }
}



