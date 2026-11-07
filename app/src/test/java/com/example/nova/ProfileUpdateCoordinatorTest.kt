package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProfileUpdateCoordinatorTest {

    @Test
    fun `failure result closes the update flow`() {
        val result = ProfileUpdateCoordinator.failure(IllegalStateException("timeout"))
        assertFalse(result.ok)
        assertEquals(0, result.okSources)
        assertEquals(ExternalSources.PUBLIC_VLESS_SOURCES.size, result.failedSources)
        assertEquals(0, result.added)
        assertEquals(0, result.removed)
        assertEquals("Профили не обновились: timeout", result.message)
    }

    @Test
    fun `failure without a message still explains the state`() {
        val result = ProfileUpdateCoordinator.failure(null)
        assertEquals("Профили не обновились: неизвестная ошибка", result.message)
    }
}
