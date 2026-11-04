package com.example.nova

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Ipv4RangeTest {

    @Test
    fun `cidr matches inside and rejects outside`() {
        val range = Ipv4Range.parse("104.16.0.0/13")!!
        assertTrue(range.contains("104.16.1.1"))
        assertTrue(range.contains("104.23.255.255"))
        assertFalse(range.contains("104.24.0.1"))
        assertFalse(range.contains("8.8.8.8"))
    }

    @Test
    fun `single address is a 32-bit range`() {
        val single = Ipv4Range.parse("1.1.1.1")!!
        assertTrue(single.contains("1.1.1.1"))
        assertFalse(single.contains("1.1.1.2"))
    }

    @Test
    fun `broken input is rejected without exceptions`() {
        assertNull(Ipv4Range.parse("1.2.3.4/99"))
        assertNull(Ipv4Range.parse("1.2.3"))
        assertNull(Ipv4Range.parse("256.1.1.1"))
        assertNull(Ipv4Range.parse("# comment"))
        assertFalse(Ipv4Range.parse("10.0.0.0/8")!!.contains("10.0.0"))
    }
}
