package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterConfigTest {

    @Test
    fun `builds deterministic json`() {
        val json = RouterConfig.build(
            slots = mapOf(
                "primary" to RouterConfig.SlotSpec("socks5", "127.0.0.1:1370"),
                "secondary" to RouterConfig.SlotSpec("http", "127.0.0.1:1371"),
                "direct" to RouterConfig.SlotSpec("direct"),
            ),
            rules = listOf(
                RouterConfig.RuleSpec(listOf("youtube.com"), listOf("secondary", "primary", "direct")),
            ),
            defaultOrder = listOf("primary", "secondary", "direct"),
        )
        assertEquals(
            "{\"slots\":{\"direct\":{\"type\":\"direct\"}," +
                "\"primary\":{\"type\":\"socks5\",\"addr\":\"127.0.0.1:1370\"}," +
                "\"secondary\":{\"type\":\"http\",\"addr\":\"127.0.0.1:1371\"}}," +
                "\"rules\":[{\"domains\":[\"youtube.com\"],\"tcpOrder\":[\"secondary\",\"primary\",\"direct\"],\"udpOrder\":[]}]," +
                "\"defaultOrder\":[\"primary\",\"secondary\",\"direct\"],\"defaultUdpOrder\":[]}",
            json,
        )
    }

    @Test
    fun `allowQuic is serialized only when enabled`() {
        val enabled = RouterConfig.build(
            slots = mapOf("primary" to RouterConfig.SlotSpec("socks5", "127.0.0.1:1378", udp = true)),
            rules = listOf(
                RouterConfig.RuleSpec(
                    domains = listOf("quic.example"),
                    order = listOf("primary"),
                    name = "quic",
                    udpOrder = listOf("primary"),
                    allowQuic = true,
                ),
            ),
            defaultOrder = listOf("primary"),
            defaultUdpOrder = listOf("primary"),
        )
        assertTrue(enabled.contains("\"allowQuic\":true"))
        val disabled = RouterConfig.build(
            slots = mapOf("primary" to RouterConfig.SlotSpec("socks5", "127.0.0.1:1378", udp = true)),
            rules = listOf(
                RouterConfig.RuleSpec(
                    domains = listOf("quic.example"),
                    order = listOf("primary"),
                    name = "quic",
                    udpOrder = listOf("primary"),
                ),
            ),
            defaultOrder = listOf("primary"),
            defaultUdpOrder = listOf("primary"),
        )
        assertTrue(!disabled.contains("\"allowQuic\""))
    }

    @Test
    fun `escapes quotes and control characters`() {
        val json = RouterConfig.build(
            slots = mapOf("x" to RouterConfig.SlotSpec("direct")),
            rules = emptyList(),
            defaultOrder = listOf("x\"y\\z"),
        )
        assertTrue(json.contains("x\\\"y\\\\z"))
    }

    @Test
    fun `empty rules are valid json`() {
        val json = RouterConfig.build(
            slots = mapOf("direct" to RouterConfig.SlotSpec("direct")),
            rules = emptyList(),
            defaultOrder = listOf("direct"),
        )
        assertTrue(json.contains("\"rules\":[]"))
    }
}
