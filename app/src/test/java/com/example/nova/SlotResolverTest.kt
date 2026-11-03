package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotResolverTest {

    private fun slots(
        primaryAlive: Boolean = true,
        primaryCountry: String? = "RU",
        secondaryAlive: Boolean = true,
        secondaryTor: Boolean = false,
        relayAlive: Boolean = true,
    ): Map<SlotKind, SlotState> = mapOf(
        SlotKind.PRIMARY to SlotState(SlotKind.PRIMARY, primaryAlive, primaryCountry),
        SlotKind.SECONDARY to SlotState(SlotKind.SECONDARY, secondaryAlive, "SE", secondaryTor),
        SlotKind.RELAY to SlotState(SlotKind.RELAY, relayAlive),
    )

    @Test
    fun `direct and block are terminal`() {
        assertEquals(listOf(SlotKind.DIRECT), SlotResolver.chainFor(RouteClass.DIRECT, slots()))
        assertTrue(SlotResolver.chainFor(RouteClass.BLOCK, slots()).isEmpty())
    }

    @Test
    fun `ru prefers primary then secondary then direct`() {
        assertEquals(
            listOf(SlotKind.PRIMARY, SlotKind.SECONDARY, SlotKind.DIRECT),
            SlotResolver.chainFor(RouteClass.RU, slots()),
        )
        assertEquals(
            listOf(SlotKind.SECONDARY, SlotKind.DIRECT),
            SlotResolver.chainFor(RouteClass.RU, slots(primaryAlive = false)),
        )
    }

    @Test
    fun `eu puts primary first only when it exits abroad`() {
        assertEquals(
            listOf(SlotKind.SECONDARY, SlotKind.PRIMARY, SlotKind.DIRECT),
            SlotResolver.chainFor(RouteClass.EU, slots(primaryCountry = "RU")),
        )
        assertEquals(
            listOf(SlotKind.PRIMARY, SlotKind.SECONDARY, SlotKind.DIRECT),
            SlotResolver.chainFor(RouteClass.EU, slots(primaryCountry = "NL")),
        )
    }

    @Test
    fun `youtube avoids tor and foreign primary decides order`() {
        assertEquals(
            listOf(SlotKind.SECONDARY, SlotKind.PRIMARY, SlotKind.DIRECT),
            SlotResolver.chainFor(RouteClass.YOUTUBE, slots(primaryCountry = "RU")),
        )
        assertEquals(
            listOf(SlotKind.PRIMARY, SlotKind.SECONDARY, SlotKind.DIRECT),
            SlotResolver.chainFor(RouteClass.YOUTUBE, slots(primaryCountry = "NL")),
        )
        assertEquals(
            listOf(SlotKind.PRIMARY, SlotKind.SECONDARY, SlotKind.DIRECT),
            SlotResolver.chainFor(RouteClass.YOUTUBE, slots(primaryCountry = "RU", secondaryTor = true)),
        )
    }

    @Test
    fun `telegram uses relay and never falls back to direct`() {
        assertEquals(listOf(SlotKind.RELAY), SlotResolver.chainFor(RouteClass.TELEGRAM, slots()))
        assertEquals(
            listOf(SlotKind.PRIMARY),
            SlotResolver.chainFor(RouteClass.TELEGRAM, slots(relayAlive = false)),
        )
        assertTrue(
            SlotResolver.chainFor(RouteClass.TELEGRAM, slots(primaryAlive = false, relayAlive = false)).isEmpty(),
        )
    }

    @Test
    fun `ai prefers secondary`() {
        assertEquals(
            listOf(SlotKind.SECONDARY, SlotKind.PRIMARY, SlotKind.DIRECT),
            SlotResolver.chainFor(RouteClass.AI, slots()),
        )
    }

    @Test
    fun `killswitch removes direct tail`() {
        assertEquals(
            listOf(SlotKind.PRIMARY, SlotKind.SECONDARY),
            SlotResolver.chainFor(RouteClass.RU, slots(), killSwitch = true),
        )
        assertTrue(SlotResolver.chainFor(RouteClass.RU, slots(primaryAlive = false, secondaryAlive = false), killSwitch = true).isEmpty())
    }

    @Test
    fun `abroad detection ignores unknown country`() {
        assertTrue(!SlotResolver.isAbroad(SlotState(SlotKind.PRIMARY, true, null)))
        assertTrue(!SlotResolver.isAbroad(SlotState(SlotKind.PRIMARY, true, "RU")))
        assertTrue(SlotResolver.isAbroad(SlotState(SlotKind.PRIMARY, true, "nl")))
    }
}
