package com.example.nova

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EgressEligibilityTest {

    @Test
    fun `public non-ru address is eligible`() {
        assertTrue(EgressEligibility.isEligible("104.28.230.248", "NL"))
        assertTrue(EgressEligibility.isEligible("2a09:bac5:51ad:286e::407:69", "DE"))
    }

    @Test
    fun `ru is never eligible`() {
        assertFalse(EgressEligibility.isEligible("104.28.230.248", "RU"))
        assertFalse(EgressEligibility.isEligible("104.28.230.248", "ru"))
    }

    @Test
    fun `unknown country or special code is not eligible`() {
        assertFalse(EgressEligibility.isEligible("104.28.230.248", ""))
        assertFalse(EgressEligibility.isEligible("104.28.230.248", null))
        assertFalse(EgressEligibility.isEligible("104.28.230.248", "R"))
        assertFalse(EgressEligibility.isEligible("104.28.230.248", "T1"))
        assertFalse(EgressEligibility.isEligible("104.28.230.248", "XX"))
        assertFalse(EgressEligibility.isEligible("104.28.230.248", "EU"))
    }

    @Test
    fun `private loopback link-local cgnat and multicast are not eligible`() {
        assertFalse(EgressEligibility.isEligible("10.0.0.1", "NL"))
        assertFalse(EgressEligibility.isEligible("172.16.0.1", "NL"))
        assertFalse(EgressEligibility.isEligible("192.168.1.1", "NL"))
        assertFalse(EgressEligibility.isEligible("127.0.0.1", "NL"))
        assertFalse(EgressEligibility.isEligible("169.254.1.1", "NL"))
        assertFalse(EgressEligibility.isEligible("100.64.0.1", "NL"))
        assertFalse(EgressEligibility.isEligible("224.0.0.1", "NL"))
        assertFalse(EgressEligibility.isEligible("::1", "NL"))
        assertFalse(EgressEligibility.isEligible("fe80::1", "NL"))
        assertFalse(EgressEligibility.isEligible("fc00::1", "NL"))
    }

    @Test
    fun `invalid or missing ip is not eligible`() {
        assertFalse(EgressEligibility.isEligible("", "NL"))
        assertFalse(EgressEligibility.isEligible(null, "NL"))
        assertFalse(EgressEligibility.isEligible("not-an-ip", "NL"))
        assertFalse(EgressEligibility.isEligible("example.com", "NL"))
        assertFalse(EgressEligibility.isEligible("999.1.1.1", "NL"))
    }
}
