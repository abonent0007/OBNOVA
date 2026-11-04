package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListRepositoryFallbackTest {

    @Test
    fun `empty local list falls back to the built-in copy`() {
        val parsed = ListRepository.domainsFrom(
            localLines = listOf("# only comments", ""),
            assetLines = listOf("builtin.example", "second.example"),
        )
        assertTrue(parsed.contains("builtin.example"))
        assertTrue(parsed.contains("second.example"))
    }

    @Test
    fun `non-empty local list wins over built-in`() {
        val parsed = ListRepository.domainsFrom(
            localLines = listOf("local.example"),
            assetLines = listOf("builtin.example"),
        )
        assertTrue(parsed.contains("local.example"))
        assertFalse(parsed.contains("builtin.example"))
    }

    @Test
    fun `tld fallback keeps built-in zones only when local is empty`() {
        val fallback = ListRepository.tldsFrom(
            localLines = listOf("# nothing"),
            assetLines = listOf("ru", "su"),
        )
        assertEquals(setOf("ru", "su"), fallback)

        val local = ListRepository.tldsFrom(
            localLines = listOf("ru", "tatar"),
            assetLines = listOf("su"),
        )
        assertEquals(setOf("ru", "tatar"), local)
    }
}
