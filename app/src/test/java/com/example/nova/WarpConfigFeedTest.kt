package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WarpConfigFeedTest {

    @Test
    fun `parses a generated WARP config`() {
        val identity = WarpConfigFeed.parse(
            """{"privKey":"abc=","peer_pub":"peer=","client_ipv4":"172.16.0.2","client_ipv6":"2606:4700::1"}""",
        )
        assertEquals("abc=", identity?.privateKey)
        assertEquals("peer=", identity?.peerPublicKey)
        assertEquals("172.16.0.2", identity?.ipv4)
        assertEquals("2606:4700::1", identity?.ipv6)
        assertEquals("", identity?.reserved)
    }

    @Test
    fun `rejects incomplete or broken bodies`() {
        assertNull(WarpConfigFeed.parse("not json"))
        assertNull(WarpConfigFeed.parse("""{"privKey":"abc="}"""))
        assertNull(WarpConfigFeed.parse("""{"privKey":"","peer_pub":"peer=","client_ipv4":"172.16.0.2"}"""))
    }

    @Test
    fun `feed endpoints are https and ordered`() {
        assertEquals(5, WarpConfigFeed.ENDPOINTS.size)
        assertEquals("https://warp-workers.warp-api.cfd", WarpConfigFeed.ENDPOINTS.first())
        assertEquals(WarpConfigFeed.ENDPOINTS.size, WarpConfigFeed.ENDPOINTS.distinct().size)
        assertEquals(true, WarpConfigFeed.ENDPOINTS.all { it.startsWith("https://") })
    }
}
