package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Test

class RoutingPolicyTest {

    private class Lists(
        val exclude: Set<String> = emptySet(),
        val second: Set<String> = emptySet(),
        val main: Set<String> = emptySet(),
        val ai: Set<String> = emptySet(),
        val youtube: Set<String> = emptySet(),
        val telegram: Set<String> = emptySet(),
        val whatsapp: Set<String> = emptySet(),
        val discord: Set<String> = emptySet(),
        val cloudflare: Set<String> = emptySet(),
    ) : RoutingLists {
        override fun inExclude(host: String) = exclude.any { RoutingPolicy.matches(host, it) }
        override fun inSecond(host: String) = second.any { RoutingPolicy.matches(host, it) }
        override fun inMain(host: String) = main.any { RoutingPolicy.matches(host, it) }
        override fun inAi(host: String) = ai.any { RoutingPolicy.matches(host, it) }
        override fun inYoutube(host: String) = youtube.any { RoutingPolicy.matches(host, it) }
        override fun inTelegram(host: String) = telegram.any { RoutingPolicy.matches(host, it) }
        override fun inWhatsapp(host: String) = whatsapp.any { RoutingPolicy.matches(host, it) }
        override fun inDiscord(host: String) = discord.any { RoutingPolicy.matches(host, it) }
        override fun isCloudflareIp(ip: String) = ip in cloudflare
    }

    private fun route(
        host: String?,
        ip: String? = null,
        packageName: String? = null,
        config: RoutingConfig = RoutingConfig(),
        lists: RoutingLists = EmptyRoutingLists,
    ) = RoutingPolicy.decide(RouteRequest(host, ip, packageName), config, lists).routeClass

    @Test
    fun `local addresses and literals always direct`() {
        assertEquals(RouteClass.DIRECT, route("localhost"))
        assertEquals(RouteClass.DIRECT, route("api.localhost"))
        assertEquals(RouteClass.DIRECT, route("printer.local"))
        assertEquals(RouteClass.DIRECT, route("127.0.0.1"))
        assertEquals(RouteClass.DIRECT, route("192.168.1.10"))
        assertEquals(RouteClass.DIRECT, route("router"))
    }

    @Test
    fun `direct mode keeps everything direct except explicit app mode`() {
        val config = RoutingConfig(
            mode = DefaultRoutingMode.DIRECT,
            appModes = mapOf("org.telegram.messenger" to RouteClass.TELEGRAM),
        )
        assertEquals(RouteClass.DIRECT, route("google.com", config = config))
        assertEquals(
            RouteClass.TELEGRAM,
            route("google.com", packageName = "org.telegram.messenger", config = config),
        )
    }

    @Test
    fun `user lists win over service lists`() {
        val config = RoutingConfig(
            userDirect = setOf("example.com"),
            userEu = setOf("eu.example"),
        )
        val lists = Lists(main = setOf("example.com", "eu.example"))
        assertEquals(RouteClass.DIRECT, route("www.example.com", config = config, lists = lists))
        assertEquals(RouteClass.EU, route("eu.example", config = config, lists = lists))
    }

    @Test
    fun `exclude beats local tld and lists`() {
        val lists = Lists(exclude = setOf("bank.ru"), main = setOf("bank.ru"))
        assertEquals(RouteClass.DIRECT, route("bank.ru", lists = lists))
    }

    @Test
    fun `local tlds go direct including punycode`() {
        assertEquals(RouteClass.DIRECT, route("yandex.ru"))
        assertEquals(RouteClass.DIRECT, route("example.xn--p1ai"))
        assertEquals(RouteClass.DIRECT, route("example.москва"))
        assertEquals(RouteClass.DIRECT, route("example.tatar"))
    }

    @Test
    fun `local tlds can be turned off`() {
        val config = RoutingConfig(localTldDirect = false, foreignDefaultVpn = true)
        assertEquals(RouteClass.RU, route("yandex.ru", config = config))
    }

    @Test
    fun `app mode beats service lists`() {
        val config = RoutingConfig(appModes = mapOf("com.example.app" to RouteClass.EU))
        val lists = Lists(main = setOf("google.com"))
        assertEquals(RouteClass.EU, route("google.com", packageName = "com.example.app", config = config, lists = lists))
    }

    @Test
    fun `ai is checked before eu and then youtube before ru`() {
        val lists = Lists(
            ai = setOf("gemini.google.com"),
            second = setOf("example.eu"),
            youtube = setOf("youtube.com"),
            main = setOf("youtube.com"),
        )
        assertEquals(RouteClass.AI, route("gemini.google.com", lists = lists))
        assertEquals(RouteClass.EU, route("example.eu", lists = lists))
        assertEquals(RouteClass.YOUTUBE, route("youtube.com", lists = lists))
    }

    @Test
    fun `service profiles are distinct`() {
        val lists = Lists(
            telegram = setOf("telegram.org"),
            discord = setOf("discord.com"),
            whatsapp = setOf("whatsapp.com"),
        )
        assertEquals(RouteClass.TELEGRAM, route("telegram.org", lists = lists))
        assertEquals(RouteClass.DISCORD, route("discord.com", lists = lists))
        assertEquals(RouteClass.WHATSAPP, route("whatsapp.com", lists = lists))
    }

    @Test
    fun `cloudflare ip fallback beats foreign default`() {
        val lists = Lists(cloudflare = setOf("104.16.0.1"))
        assertEquals(
            RouteClass.RU,
            route("unknown-service.example", ip = "104.16.0.1", lists = lists),
        )
    }

    @Test
    fun `foreign default follows the switch`() {
        assertEquals(RouteClass.RU, route("unknown.example"))
        assertEquals(
            RouteClass.DIRECT,
            route("unknown.example", config = RoutingConfig(foreignDefaultVpn = false)),
        )
    }

    @Test
    fun `domain matching does not cross labels`() {
        val lists = Lists(main = setOf("example.com"))
        val config = RoutingConfig(foreignDefaultVpn = false)
        assertEquals(RouteClass.RU, route("a.example.com", lists = lists))
        assertEquals(RouteClass.DIRECT, route("notexample.com", config = config, lists = lists))
    }

    @Test
    fun `case and trailing dot are normalized`() {
        val lists = Lists(main = setOf("example.com"))
        assertEquals(RouteClass.RU, route("WWW.Example.COM.", lists = lists))
    }

    @Test
    fun `local tld set can come from the synced snapshot`() {
        val snapshot = RoutingListsSnapshot(localTlds = setOf("example"))
        assertEquals(
            RouteClass.DIRECT,
            route(
                "host.example",
                config = RoutingConfig(foreignDefaultVpn = false),
                lists = snapshot,
            ),
        )
        assertEquals(
            RouteClass.RU,
            route(
                "host.ru",
                config = RoutingConfig(foreignDefaultVpn = true),
                lists = snapshot,
            ),
        )
    }
}
