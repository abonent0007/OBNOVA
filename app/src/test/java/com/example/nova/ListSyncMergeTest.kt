package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Test

class ListSyncMergeTest {

    @Test
    fun `merges upstream and fallback lists without duplicates`() {
        val merged = ListSync.mergeCidrLines(
            listOf("149.154.160.0/20", "91.108.0.0/16"),
            listOf("149.154.160.0/20", "91.108.4.0/22"),
        )
        assertEquals(
            listOf("149.154.160.0/20", "91.108.0.0/16", "91.108.4.0/22"),
            merged,
        )
    }

    @Test
    fun `drops comments ipv6 and malformed entries`() {
        val merged = ListSync.mergeCidrLines(
            listOf(
                "# Telegram MTProto",
                "",
                "2400:cb00::/32",
                "not-a-cidr",
                "999.1.1.0/24",
                " 149.154.164.0/22  # inline",
            ),
        )
        assertEquals(listOf("149.154.164.0/22"), merged)
    }

    @Test
    fun `an empty upstream leaves the fallback untouched`() {
        val merged = ListSync.mergeCidrLines(
            emptyList(),
            listOf("149.154.160.0/20"),
        )
        assertEquals(listOf("149.154.160.0/20"), merged)
    }
}
