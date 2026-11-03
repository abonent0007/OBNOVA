package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesyncOptionsTest {

    @Test
    fun `defaults survive normalization`() {
        val options = DesyncOptions().normalized()
        assertEquals(DesyncOptions.DEFAULT_SPLIT, options.split)
        assertEquals(DesyncOptions.DEFAULT_DELAY_MS, options.delayMs)
        assertTrue(options.recordSplit)
        assertTrue(options.decoyEnabled)
    }

    @Test
    fun `normalization is idempotent and port appears once`() {
        val once = DesyncOptions(split = 99, delayMs = 10_000, decoySni = " bad host ").normalized()
        assertEquals(once, once.normalized())
        val args = once.engineArgs(1500)
        assertEquals(1, args.count { it == "127.0.0.1:1500" })
        assertEquals(1, args.count { it == "-listen" })
    }

    @Test
    fun `split and delay are clamped to safe bounds`() {
        assertEquals(1, DesyncOptions(split = 0).normalized().split)
        assertEquals(DesyncOptions.MAX_SPLIT, DesyncOptions(split = 99).normalized().split)
        assertEquals(0, DesyncOptions(delayMs = -5).normalized().delayMs)
        assertEquals(DesyncOptions.MAX_DELAY_MS, DesyncOptions(delayMs = 10_000).normalized().delayMs)
    }

    @Test
    fun `decoy accepts host and strips scheme and path`() {
        assertEquals("example.org", DesyncOptions.normalizeDecoy("https://example.org/path?q=1"))
        assertEquals("example.org", DesyncOptions.normalizeDecoy("EXAMPLE.org"))
        assertEquals("example.org", DesyncOptions.normalizeDecoy("example.org:443"))
    }

    @Test
    fun `broken decoy falls back instead of failing the session`() {
        assertEquals(DesyncOptions.DEFAULT_DECOY_SNI, DesyncOptions.normalizeDecoy(null))
        assertEquals(DesyncOptions.DEFAULT_DECOY_SNI, DesyncOptions.normalizeDecoy(""))
        assertEquals(DesyncOptions.DEFAULT_DECOY_SNI, DesyncOptions.normalizeDecoy("localhost"))
        assertEquals(DesyncOptions.DEFAULT_DECOY_SNI, DesyncOptions.normalizeDecoy("два слова"))
        assertEquals(DesyncOptions.DEFAULT_DECOY_SNI, DesyncOptions.normalizeDecoy("bad host.com"))
    }

    @Test
    fun `engine args carry the chosen values`() {
        val args = DesyncOptions(split = 4, delayMs = 25, decoySni = "example.org").engineArgs(1500)
        assertTrue(args.containsAll(listOf("-listen", "127.0.0.1:1500", "-split", "4", "-delay", "25ms")))
        assertTrue(args.contains("-decoy"))
        assertTrue(args.contains("example.org"))
    }

    @Test
    fun `decoy can be turned off`() {
        val args = DesyncOptions(decoyEnabled = false).engineArgs(1500)
        assertFalse(args.contains("-decoy"))
    }
}
