package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListManifestTest {

    @Test
    fun `discovers list files from manifest instead of a fixed list`() {
        val manifest = ListManifest.parse(
            """
            {
              "generated": 42,
              "files": {
                "list/main.txt": {"sha256": "AA"},
                "nova_pc/list/second.txt": {"sha256": "BB"},
                "list/local_tlds.txt": {"sha256": "CC"},
                "list/u_private.txt": {"sha256": "DD"},
                "list/../evil.txt": {"sha256": "EE"},
                "other/readme.txt": {"sha256": "FF"},
                "ip/cloudflare.txt": {"sha256": "11"}
              }
            }
            """.trimIndent(),
        )

        requireNotNull(manifest)
        assertEquals(42L, manifest.generated)
        assertEquals(
            listOf("list/local_tlds.txt", "list/main.txt", "list/second.txt"),
            manifest.listPaths(),
        )
        assertEquals("BB", manifest.shaFor("list/second.txt"))
        assertEquals("11", manifest.shaFor("ip/cloudflare.txt"))
    }

    @Test
    fun `rejects malformed json and missing files object`() {
        assertNull(ListManifest.parse("not json"))
        assertNull(ListManifest.parse("""{"generated":1}"""))
    }

    @Test
    fun `normalizes legacy prefix and rejects traversal`() {
        assertEquals("list/main.txt", ListManifest.normalizeRelative("nova_pc/list/main.txt"))
        assertEquals("list/main.txt", ListManifest.normalizeRelative("./list/main.txt"))
        assertNull(ListManifest.normalizeRelative("../list/main.txt"))
        assertNull(ListManifest.normalizeRelative("nova_pc/../secret.txt"))
        assertNull(ListManifest.normalizeRelative("/etc/passwd"))
    }

    @Test
    fun `destination names match ListRepository expectations`() {
        assertEquals("main.txt", ListManifest.destinationName("list/main.txt"))
        assertEquals("cloudflare_ips.txt", ListManifest.destinationName("ip/cloudflare.txt"))
        assertEquals("youtube_ips.txt", ListManifest.destinationName("ip/youtube.txt"))
        assertEquals("telegram_ips.txt", ListManifest.destinationName("ip/telegram.txt"))
        assertNull(ListManifest.destinationName("other/file.txt"))
    }

    @Test
    fun `list path validation blocks nested and non-text names`() {
        assertTrue(ListManifest.isListPath("list/main.txt"))
        assertTrue(ListManifest.isListPath("list/local_tlds.txt"))
        assertTrue(!ListManifest.isListPath("list/nested/main.txt"))
        assertTrue(!ListManifest.isListPath("list/main.json"))
        assertTrue(!ListManifest.isListPath("list/u_private.txt"))
        assertTrue(!ListManifest.isListPath("list/has space.txt"))
    }
}
