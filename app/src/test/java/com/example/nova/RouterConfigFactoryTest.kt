package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun `telegram relay slot is first for telegram ips`() {
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
        assertTrue(json.contains("\"order\":[\"ws\",\"cfproxy\",\"secondary\",\"primary\"]"))
    }

    @Test
    fun `google ai tries primary before desync`() {
        assertEquals(
            listOf("secondary", "primary", "spoof", "direct"),
            RouterConfigFactory.order("google_ai", emptyMap(), opera = true, spoof = true),
        )
        val json = RouterConfigFactory.build(
            input = input().copy(googleAi = listOf("gemini.google.com")),
            primaryPort = 1376,
            operaPort = 1371,
        )
        assertTrue(json.contains("\"name\":\"google-ai\""))
        assertTrue(json.contains("\"order\":[\"secondary\",\"primary\",\"direct\"]"))
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
        assertTrue(json.contains("\"order\":[\"ws\",\"cfproxy\",\"secondary\",\"primary\"]"))
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
                "\"name\":\"mtproto-proxy\",\"domains\":[],\"cidrs\":[\"65.109.194.157/32\"],\"order\":[\"secondary\",\"cfproxy\",\"primary\"]"
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
                "\"name\":\"apis\",\"domains\":[\"apis.google.com\",\"gvt1.com\"],\"order\":[\"primary\",\"direct\"]"
            )
        )
        // apis.google.com не покрывает youtubei.googleapis.com — другой суффикс.
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
        assertTrue(json.contains("\"defaultOrder\":[\"primary\",\"direct\"]"))
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
        assertTrue(json.contains("\"domains\":[\"telegram.org\"],\"order\":[]"))
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
                "\"name\":\"telegram-ip\",\"domains\":[],\"cidrs\":[\"149.154.160.0/20\"],\"order\":[\"cfproxy\",\"secondary\",\"primary\"]"
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
    fun `invalid primary port is rejected before writing a config`() {
        assertThrows(IllegalArgumentException::class.java) {
            RouterConfigFactory.build(input(), primaryPort = 0)
        }
    }
}
