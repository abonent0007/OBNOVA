package com.example.nova

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WarpProfileGeneratorTest {

    @Test
    fun `fresh running state is live`() {
        val progress = WarpProfileGenerator.Progress(
            state = WarpProfileGenerator.STATE_RUNNING,
            message = "WARP: ищу точки входа",
            done = 0,
            total = 25,
            atMs = 1_000L,
        )
        assertTrue(WarpProfileGenerator.isLive(progress, 1_000L + 60_000L))
        assertTrue(WarpProfileGenerator.isLive(progress, 1_000L + WarpProfileGenerator.STALE_RUNNING_MS))
    }

    @Test
    fun `stale running state is not live`() {
        val progress = WarpProfileGenerator.Progress(
            state = WarpProfileGenerator.STATE_RUNNING,
            message = "WARP: ищу точки входа",
            done = 0,
            total = 25,
            atMs = 1_000L,
        )
        assertFalse(
            WarpProfileGenerator.isLive(
                progress,
                1_000L + WarpProfileGenerator.STALE_RUNNING_MS + 1,
            )
        )
    }

    @Test
    fun `terminal states are not live`() {
        val done = WarpProfileGenerator.Progress(WarpProfileGenerator.STATE_DONE, "ok", 25, 25, 1_000L)
        val failed = WarpProfileGenerator.Progress(WarpProfileGenerator.STATE_FAILED, "err", 0, 25, 1_000L)
        val idle = WarpProfileGenerator.Progress(WarpProfileGenerator.STATE_IDLE, "", 0, 0)
        assertFalse(WarpProfileGenerator.isLive(done, 1_000L))
        assertFalse(WarpProfileGenerator.isLive(failed, 1_000L))
        assertFalse(WarpProfileGenerator.isLive(idle, 1_000L))
    }
}
