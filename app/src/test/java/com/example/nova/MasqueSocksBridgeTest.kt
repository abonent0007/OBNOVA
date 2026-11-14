package com.example.nova

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Чистая часть MASQUE-моста: какие endpoint'ы вообще известны и в каком
 * порядке их пробовать. Раньше брался ровно один адрес, поэтому «следующий
 * профиль» не мог сменить выход — только порт и SNI у того же IP.
 */
class MasqueSocksBridgeTest {

    @Test
    fun `endpoint candidates merge stored, primary and registration list`() {
        val identity = JSONObject()
            .put("last_endpoint", "162.159.192.1:443")
            .put("endpoint_v4", "162.159.192.1")
            .put("endpoint_v6", "[2606:4700:100::a29f:c001]:443")
            .put(
                "endpoint_v4_candidates",
                JSONArray()
                    .put("162.159.193.10")
                    .put("162.159.193.11"),
            )

        assertEquals(
            listOf(
                "162.159.192.1",
                "2606:4700:100::a29f:c001",
                "162.159.193.10",
                "162.159.193.11",
            ),
            MasqueSocksBridge.endpointCandidates(identity),
        )
    }

    @Test
    fun `hostOnly keeps bare IPv6 and strips IPv4 port`() {
        assertEquals("162.159.192.1", MasqueSocksBridge.hostOnly("162.159.192.1:443"))
        assertEquals("2606:4700::1", MasqueSocksBridge.hostOnly("[2606:4700::1]:500"))
        assertEquals("2606:4700::1", MasqueSocksBridge.hostOnly("2606:4700::1"))
        assertEquals("", MasqueSocksBridge.hostOnly("  "))
    }

    @Test
    fun `next profile starts after the active endpoint`() {
        val endpoints = listOf("a", "b", "c")
        assertEquals(
            listOf("b", "c", "a"),
            MasqueSocksBridge.orderEndpoints(endpoints, avoidEndpoint = "a", preferEndpoint = null),
        )
        assertEquals(
            listOf("a", "b", "c"),
            MasqueSocksBridge.orderEndpoints(endpoints, avoidEndpoint = "unknown", preferEndpoint = null),
        )
    }

    @Test
    fun `restore puts the previous endpoint first`() {
        val endpoints = listOf("a", "b", "c")
        assertEquals(
            listOf("b", "a", "c"),
            MasqueSocksBridge.orderEndpoints(endpoints, avoidEndpoint = null, preferEndpoint = "b"),
        )
    }
}
