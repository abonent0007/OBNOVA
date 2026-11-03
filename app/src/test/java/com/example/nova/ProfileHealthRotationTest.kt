package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileHealthRotationTest {

    @Test
    fun `three high samples in auto request a rotation`() {
        val policy = ProfileHealthRotation()
        policy.onConnected(0L)
        assertFalse(policy.observe(100_000L, 100, "auto").rotate)
        assertFalse(policy.observe(145_000L, 300, "auto").rotate)
        assertFalse(policy.observe(190_000L, 300, "auto").rotate)
        assertTrue(policy.observe(235_000L, 300, "auto").rotate)
    }

    @Test
    fun `explicit mode never rotates`() {
        val policy = ProfileHealthRotation()
        policy.onConnected(0L)
        repeat(10) { index ->
            assertFalse(policy.observe(100_000L + index * 50_000L, 900, "masque").rotate)
        }
    }

    @Test
    fun `cooldown after successful rotation blocks a second switch`() {
        val policy = ProfileHealthRotation()
        policy.onConnected(0L)
        policy.observe(100_000L, 100, "auto")
        policy.observe(145_000L, 300, "auto")
        policy.observe(190_000L, 300, "auto")
        assertTrue(policy.observe(235_000L, 300, "auto").rotate)
        policy.noteRotation(235_000L, success = true)

        // Новый baseline после смены, затем снова деградация — но cooldown ещё идёт.
        policy.observe(330_000L, 100, "auto")
        policy.observe(375_000L, 300, "auto")
        policy.observe(420_000L, 300, "auto")
        assertFalse(policy.observe(465_000L, 300, "auto").rotate)
        assertTrue(policy.degradedStreakForTest() >= 3)
    }

    @Test
    fun `manual action resets baseline and blocks immediate rotation`() {
        val policy = ProfileHealthRotation()
        policy.onConnected(0L)
        policy.observe(100_000L, 100, "auto")
        policy.observe(145_000L, 300, "auto")
        policy.noteUserAction(150_000L)
        assertFalse(policy.observe(200_000L, 900, "auto").rotate)
    }

    @Test
    fun `disconnect resets accumulated degradation`() {
        val policy = ProfileHealthRotation()
        policy.onConnected(0L)
        policy.observe(100_000L, 100, "auto")
        policy.observe(145_000L, 300, "auto")
        policy.observe(190_000L, 300, "auto")
        policy.onDisconnected()
        assertEquals(0, policy.degradedStreakForTest())
    }
}
