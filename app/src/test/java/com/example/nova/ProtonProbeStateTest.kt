package com.example.nova

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtonProbeStateTest {

    @Test
    fun `fresh running probe is not stale`() {
        val state = ProtonProfileStore.ProbeState(
            state = ProtonProfileStore.STATE_RUNNING,
            total = 80,
            done = 30,
            alive = 12,
            atMs = 1_000L,
        )
        assertFalse(state.isStale(nowMs = 1_000L + 149_999L))
        assertFalse(state.isStale(nowMs = 1_000L, maxAgeMs = 150_000L))
    }

    @Test
    fun `running probe without updates becomes stale`() {
        val state = ProtonProfileStore.ProbeState(
            state = ProtonProfileStore.STATE_RUNNING,
            total = 80,
            done = 30,
            alive = 12,
            atMs = 1_000L,
        )
        assertTrue(state.isStale(nowMs = 1_000L + 150_001L))
    }

    @Test
    fun `stale requested state is also treated as interrupted`() {
        val state = ProtonProfileStore.ProbeState(
            state = ProtonProfileStore.STATE_REQUESTED,
            total = 80,
            done = 0,
            alive = 0,
            atMs = 1_000L,
        )
        assertFalse(state.isStale(nowMs = 1_000L + 149_999L))
        assertTrue(state.isStale(nowMs = 1_000L + 150_001L))
    }

    @Test
    fun `terminal states are never stale and legacy running is interrupted`() {
        val done = ProtonProfileStore.ProbeState(
            ProtonProfileStore.STATE_DONE, 80, 80, 23, atMs = 1_000L,
        )
        val failed = ProtonProfileStore.ProbeState(
            ProtonProfileStore.STATE_FAILED, 0, 0, 0, atMs = 1_000L,
        )
        val legacy = ProtonProfileStore.ProbeState(
            ProtonProfileStore.STATE_RUNNING, 80, 30, 12,
        )
        assertFalse(done.isStale(nowMs = Long.MAX_VALUE / 2))
        assertFalse(failed.isStale(nowMs = Long.MAX_VALUE / 2))
        assertTrue(legacy.isStale(nowMs = Long.MAX_VALUE / 2))
        assertTrue(done.finished)
        assertTrue(failed.finished)
        assertFalse(legacy.finished)
    }
}
