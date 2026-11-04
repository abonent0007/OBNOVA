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

    @Test
    fun `update feed mirrors keep raw first and pages second`() {
        val mirrors = ExternalSources.updateFeedMirrors(ExternalSources.DNS_TUNNEL_FEED)
        assertEquals(2, mirrors.size)
        assertEquals(
            "https://raw.githubusercontent.com/confeden/nova_updates/main/nova_android/dns_tunnel.json",
            mirrors[0],
        )
        assertEquals(
            "https://confeden.github.io/nova_updates/nova_android/dns_tunnel.json",
            mirrors[1],
        )
        assertTrue(mirrors.all { it.startsWith("https://") })
    }

    @Test
    fun `every published feed path is relative and unique`() {
        val paths = listOf(
            ExternalSources.LIST_MANIFEST,
            ExternalSources.DNS_TUNNEL_FEED,
            ExternalSources.OPERA_ENDPOINTS_FEED,
        )
        assertTrue(paths.all { !it.startsWith("/") && it.isNotBlank() })
        assertEquals(paths.size, paths.distinct().size)
    }

    @Test
    fun `updates point at the fork repository, not upstream`() {
        assertEquals("abonent0007/OBNOVA", ExternalSources.RELEASES_REPO)
        assertEquals(
            "https://api.github.com/repos/abonent0007/OBNOVA/releases/latest",
            ExternalSources.GITHUB_LATEST_RELEASE_API,
        )
        assertEquals(
            "https://github.com/abonent0007/OBNOVA/releases",
            ExternalSources.RELEASES_PAGE_URL,
        )
    }

    /**
     * Подписки и списки остаются стоковыми: наш репозиторий — только канал
     * обновления APK. Иначе пользователь получал бы чужие/локальные узлы.
     */
    @Test
    fun `subscriptions and data feeds stay on the upstream sources`() {
        assertTrue(ExternalSources.PUBLIC_VLESS_SUBSCRIPTION_URL.startsWith("https://iboxz.github.io/"))
        assertTrue(
            ExternalSources.WARP_CONFIGS_FEED.startsWith(
                "https://raw.githubusercontent.com/confeden/auto-warp-config/",
            ),
        )
        assertTrue(
            ExternalSources.listMirrors("nova_pc/list/main.txt")
                .all { it.contains("confeden/nova_updates") },
        )
        assertTrue(
            ExternalSources.updateFeedMirrors("opera_endpoints.json")
                .all { it.contains("confeden/nova_updates") || it.contains("confeden.github.io") },
        )
        assertTrue(!ExternalSources.PUBLIC_VLESS_SUBSCRIPTION_URL.contains("abonent0007"))
        assertTrue(!ExternalSources.WARP_CONFIGS_FEED.contains("abonent0007"))
    }

    @Test
    fun `warp configs and tor mirrors are https`() {
        assertTrue(ExternalSources.WARP_CONFIGS_FEED.startsWith("https://"))
        assertTrue(ExternalSources.TOR_BRIDGE_MIRROR_BASES.isNotEmpty())
        assertTrue(ExternalSources.TOR_BRIDGE_FALLBACK_BASES.isNotEmpty())
        assertTrue(
            (ExternalSources.TOR_BRIDGE_MIRROR_BASES + ExternalSources.TOR_BRIDGE_FALLBACK_BASES)
                .all { it.startsWith("https://") },
        )
        assertTrue(ExternalSources.TOR_MOAT_BUILTIN_URL.startsWith("https://"))
    }
}
