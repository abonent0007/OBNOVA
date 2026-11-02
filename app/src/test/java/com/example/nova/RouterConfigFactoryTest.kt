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
    fun `media static goes direct before youtube video rule`() {
        val json = RouterConfigFactory.build(
            input().copy(mediaStatic = listOf("ytimg.com", "ggpht.com")),
            primaryPort = 1376,
        )
        val staticAt = json.indexOf("ytimg.com")
        val youtubeAt = json.indexOf("\"youtube.com\"")
        assertTrue(staticAt in 0 until youtubeAt)
        assertTrue(json.contains("\"name\":\"media-static\""))
        assertTrue(json.contains("\"order\":[\"direct\"]"))
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
