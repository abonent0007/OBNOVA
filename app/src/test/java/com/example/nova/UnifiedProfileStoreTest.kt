package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedProfileStoreTest {

    private fun profile(
        protocol: String,
        host: String,
        port: Int,
        origin: String = "subscription",
        alive: Boolean? = null,
        failCount: Int = 0,
    ) = UnifiedProfile(
        id = UnifiedProfileStore.idFor(protocol, host, port, ""),
        protocol = protocol,
        engine = protocol,
        host = host,
        port = port,
        origin = origin,
        originId = "",
        legacyId = "$host:$port",
        payload = "",
        alive = alive,
        failCount = failCount,
    )

    @Test
    fun `merge deduplicates and keeps probe state of existing record`() {
        val old = profile("vless", "1.1.1.1", 443, alive = true, failCount = 0)
        val duplicate = profile("vless", "1.1.1.1", 443)
        val fresh = profile("proton", "2.2.2.2", 443)
        val merged = UnifiedProfileStore.merge(listOf(old), listOf(duplicate, fresh))
        assertEquals(2, merged.size)
        assertEquals(true, merged.first { it.host == "1.1.1.1" }.alive)
        assertEquals(null, merged.first { it.host == "2.2.2.2" }.alive)
    }

    @Test
    fun `stats count alive dead and untested by protocol`() {
        val stats = UnifiedProfileStore.stats(
            listOf(
                profile("vless", "1.1.1.1", 443, alive = true),
                profile("vless", "1.1.1.2", 443, alive = false, failCount = 2),
                profile("proton", "2.2.2.2", 443),
            ),
        )
        assertEquals(3, stats.total)
        assertEquals(1, stats.alive)
        assertEquals(1, stats.dead)
        assertEquals(1, stats.untested)
        assertEquals(2, stats.byProtocol["vless"])
        assertTrue(stats.summary().contains("В базе 3"))
    }

    @Test
    fun `id is stable and differs by endpoint`() {
        val first = UnifiedProfileStore.idFor("vless", "Example.COM", 443, "link")
        val same = UnifiedProfileStore.idFor("vless", "example.com", 443, "link")
        val otherPort = UnifiedProfileStore.idFor("vless", "example.com", 8443, "link")
        assertEquals(first, same)
        assertNotEquals(first, otherPort)
        assertEquals(24, first.length)
    }
}
