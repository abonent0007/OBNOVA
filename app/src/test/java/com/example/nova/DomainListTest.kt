package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainListTest {

    private val list = DomainList.parse(
        sequenceOf(
            "# комментарий",
            "",
            "Example.COM",
            "  youtube.com  ",
            "bad line without dot",
            "example.com # хвостовой комментарий",
        ),
    )

    @Test
    fun `parser keeps only valid unique domains`() {
        assertEquals(2, list.size)
    }

    @Test
    fun `matches subdomains but not neighbouring labels`() {
        assertTrue(list.contains("example.com"))
        assertTrue(list.contains("WWW.Example.Com."))
        assertTrue(list.contains("a.b.youtube.com"))
        assertFalse(list.contains("notexample.com"))
        assertFalse(list.contains("example.com.evil.net"))
    }

    @Test
    fun `empty and null never match`() {
        assertFalse(list.contains(null))
        assertFalse(list.contains(""))
        assertFalse(DomainList.EMPTY.contains("example.com"))
    }

    @Test
    fun `snapshot delegates to its lists`() {
        val snapshot = RoutingListsSnapshot(
            main = list,
            youtube = DomainList.parse(sequenceOf("youtube.com")),
            cloudflareIps = setOf("104.16.0.1"),
        )
        assertTrue(snapshot.inMain("www.example.com"))
        assertTrue(snapshot.inYoutube("music.youtube.com"))
        assertTrue(snapshot.isCloudflareIp("104.16.0.1"))
        assertFalse(snapshot.inExclude("example.com"))
    }
}
