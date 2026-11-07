package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Test

class WarpGeneratedStoreMergeTest {

    private fun profile(host: String, port: Int, rttMs: Int = 0) = WarpGeneratedProfile(
        host = host,
        port = port,
        rttMs = rttMs,
        junkCount = 4,
        junkMin = 40,
        junkMax = 70,
        createdAt = 1_000L,
    )

    @Test
    fun `existing profile wins and new endpoints append in order`() {
        val existing = listOf(profile("1.1.1.1", 2408, rttMs = 42))
        val incoming = listOf(
            profile("1.1.1.1", 2408, rttMs = 999),
            profile("2.2.2.2", 500),
        )
        val merged = mergeWarpProfiles(existing, incoming)
        assertEquals(listOf("1.1.1.1:2408", "2.2.2.2:500"), merged.map { "${it.host}:${it.port}" })
        assertEquals(42, merged.first().rttMs)
    }

    @Test
    fun `limit keeps the oldest entries and drops the tail`() {
        val existing = (1..3).map { profile("10.0.0.$it", 2408) }
        val incoming = (4..6).map { profile("10.0.0.$it", 2408) }
        val merged = mergeWarpProfiles(existing, incoming, limit = 4)
        assertEquals(4, merged.size)
        assertEquals("10.0.0.1", merged.first().host)
        assertEquals("10.0.0.4", merged.last().host)
    }

    @Test
    fun `duplicate incoming entries are collapsed`() {
        val merged = mergeWarpProfiles(
            existing = emptyList(),
            incoming = listOf(profile("3.3.3.3", 443), profile("3.3.3.3", 443)),
        )
        assertEquals(1, merged.size)
    }
}
