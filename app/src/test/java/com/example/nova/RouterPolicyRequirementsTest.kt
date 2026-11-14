package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterPolicyRequirementsTest {

    @Test
    fun `smart routing requires multi`() {
        val result = RouterPolicyRequirements.evaluate(
            smartRouting = true,
            overrides = emptyMap(),
            killSwitch = false,
        )
        assertTrue(result.requiresMulti)
        assertEquals(listOf("smart-routing"), result.reasons)
    }

    @Test
    fun `service override requires multi`() {
        val result = RouterPolicyRequirements.evaluate(
            smartRouting = false,
            overrides = mapOf(
                "telegram" to RouteSettings.encodeOverride(RouteClass.TELEGRAM),
                "youtube" to RouteSettings.AUTO,
            ),
            killSwitch = false,
        )
        assertTrue(result.requiresMulti)
        assertEquals(listOf("override:telegram=telegram"), result.reasons)
    }

    @Test
    fun `auto only overrides do not force multi`() {
        val result = RouterPolicyRequirements.evaluate(
            smartRouting = false,
            overrides = mapOf("telegram" to RouteSettings.AUTO, "youtube" to RouteSettings.AUTO),
            killSwitch = false,
        )
        assertFalse(result.requiresMulti)
        assertTrue(result.reasons.isEmpty())
    }

    @Test
    fun `kill switch requires multi`() {
        val result = RouterPolicyRequirements.evaluate(
            smartRouting = false,
            overrides = emptyMap(),
            killSwitch = true,
        )
        assertTrue(result.requiresMulti)
        assertTrue(result.reasons.contains("kill-switch"))
    }

    @Test
    fun `data plane hint names router and core limits`() {
        assertTrue(
            RouterPolicyRequirements.dataPlaneHint(NovaVpnService.BACKEND_MULTI)
                .contains("router"),
        )
        assertTrue(
            RouterPolicyRequirements.dataPlaneHint(NovaVpnService.BACKEND_WARP)
                .contains("не применяются"),
        )
        assertTrue(
            RouterPolicyRequirements.dataPlaneHint(NovaVpnService.BACKEND_OPERA)
                .contains("не применяются"),
        )
        assertTrue(
            RouterPolicyRequirements.dataPlaneHint(
                NovaVpnService.BACKEND_WARP,
                killSwitch = true,
            ).contains("Kill Switch"),
        )
        assertFalse(
            RouterPolicyRequirements.dataPlaneHint(
                NovaVpnService.BACKEND_MULTI,
                killSwitch = true,
            ).contains("Kill Switch"),
        )
    }
}
