package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalSourcesTest {

    @Test
    fun `list mirrors keep the documented order and path`() {
        val mirrors = ExternalSources.listMirrors("nova_pc/list/main.txt")
        assertEquals(3, mirrors.size)
        assertTrue(mirrors[0].startsWith("https://raw.githubusercontent.com/"))
        assertTrue(mirrors[1].startsWith("https://cdn.jsdelivr.net/"))
        assertTrue(mirrors[2].startsWith("https://raw.githack.com/"))
        assertTrue(mirrors.all { it.endsWith("/nova_pc/list/main.txt") })
    }

    @Test
    fun `public subscription is an absolute https url`() {
        assertTrue(ExternalSources.PUBLIC_VLESS_SUBSCRIPTION_URL.startsWith("https://"))
        assertTrue(ExternalSources.NEUTRAL_HTTPS_PROBE_HOST.contains('.'))
    }
}
