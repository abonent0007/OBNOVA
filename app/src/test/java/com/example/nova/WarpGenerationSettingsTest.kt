package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WarpGenerationSettingsTest {

    @Test
    fun `presets keep profile counts in range`() {
        val presets = listOf(
            WarpGenerationSettings.PRESET_OPTIMAL,
            WarpGenerationSettings.PRESET_FAST,
            WarpGenerationSettings.PRESET_COMPAT,
            WarpGenerationSettings.PRESET_CUSTOM,
        )
        for (preset in presets) {
            val values = WarpGenerationSettings.defaults(preset).normalized
            assertEquals(preset, values.preset)
            assertTrue(values.profileCount in WarpGenerationSettings.MIN_PROFILES..WarpGenerationSettings.MAX_PROFILES)
        }
    }

    @Test
    fun `unknown preset and wild values are normalized`() {
        val values = WarpGenerationSettings.Values(
            preset = "nonsense",
            profileCount = 999,
            junkCount = 99,
            junkMin = -5,
            junkMax = 5000,
        ).normalized
        assertEquals(WarpGenerationSettings.PRESET_OPTIMAL, values.preset)
        assertEquals(WarpGenerationSettings.MAX_PROFILES, values.profileCount)
        assertEquals(12, values.junkCount)
        assertEquals(0, values.junkMin)
        assertEquals(1500, values.junkMax)
    }

    @Test
    fun `custom preset has its own defaults`() {
        val custom = WarpGenerationSettings.defaults(WarpGenerationSettings.PRESET_CUSTOM)
        assertEquals(30, custom.profileCount)
        assertEquals(4, custom.junkCount)
        assertTrue(custom.junkMin < custom.junkMax)
    }
}
