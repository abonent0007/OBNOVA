package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutePolicyModeTest {

    @Test
    fun `old kill switch true migrates to limited only`() {
        val mode = RoutePolicyMode.migrateFromLegacy(
            storedRaw = null,
            legacyKillSwitch = true,
        )
        assertEquals(RoutePolicyMode.LIMITED, mode)
        assertTrue(mode.isKillSwitch)
        assertFalse(mode.isStrictFullTunnel)
    }

    @Test
    fun `old kill switch false migrates to off`() {
        val mode = RoutePolicyMode.migrateFromLegacy(
            storedRaw = null,
            legacyKillSwitch = false,
        )
        assertEquals(RoutePolicyMode.OFF, mode)
        assertFalse(mode.isKillSwitch)
    }

    @Test
    fun `missing old setting migrates to off`() {
        val mode = RoutePolicyMode.migrateFromLegacy(
            storedRaw = null,
            legacyKillSwitch = null,
        )
        assertEquals(RoutePolicyMode.OFF, mode)
    }

    @Test
    fun `legacy true never becomes strict full tunnel`() {
        val all = RoutePolicyMode.values().toList()
        val migrated = all.map { RoutePolicyMode.migrateFromLegacy(null, true) }.toSet()
        assertFalse(RoutePolicyMode.STRICT_FULL in migrated)
        assertTrue(RoutePolicyMode.STRICT_FULL in all)
    }

    @Test
    fun `explicit stored mode wins over legacy boolean`() {
        val mode = RoutePolicyMode.migrateFromLegacy(
            storedRaw = RoutePolicyMode.STRICT_FULL.storageValue,
            legacyKillSwitch = true,
        )
        assertEquals(RoutePolicyMode.STRICT_FULL, mode)
        assertTrue(mode.isStrictFullTunnel)
    }

    @Test
    fun `stored off wins over legacy true`() {
        val mode = RoutePolicyMode.migrateFromLegacy(
            storedRaw = "off",
            legacyKillSwitch = true,
        )
        assertEquals(RoutePolicyMode.OFF, mode)
    }

    @Test
    fun `storage values are stable and parsed case-insensitively`() {
        assertEquals("off", RoutePolicyMode.OFF.storageValue)
        assertEquals("limited", RoutePolicyMode.LIMITED.storageValue)
        assertEquals("strict_full", RoutePolicyMode.STRICT_FULL.storageValue)
        assertEquals("selective", RoutePolicyMode.SELECTIVE.storageValue)
        assertEquals(RoutePolicyMode.STRICT_FULL, RoutePolicyMode.fromStorage(" STRICT_FULL "))
        assertEquals(RoutePolicyMode.LIMITED, RoutePolicyMode.fromStorage("Limited"))
    }

    @Test
    fun `unknown stored value falls back to legacy migration`() {
        assertNull(RoutePolicyMode.fromStorage("future_mode"))
        assertEquals(
            RoutePolicyMode.LIMITED,
            RoutePolicyMode.migrateFromLegacy("future_mode", true),
        )
        assertEquals(
            RoutePolicyMode.OFF,
            RoutePolicyMode.migrateFromLegacy("future_mode", false),
        )
    }

    @Test
    fun `selective is reserved and counts as kill switch`() {
        assertTrue(RoutePolicyMode.SELECTIVE.isKillSwitch)
        assertFalse(RoutePolicyMode.SELECTIVE.isStrictFullTunnel)
    }
}
