package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoutingPrefsParserTest {

    private val xml = """
        <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
        <map>
            <boolean name="smart_routing" value="true" />
            <boolean name="killswitch" value="false" />
            <string name="route_override_telegram">primary</string>
            <long name="opera_retry_after" value="123456" />
        </map>
    """.trimIndent()

    @Test
    fun `reads typed values`() {
        assertEquals(true, RoutingPrefsParser.boolean(xml, "smart_routing"))
        assertEquals(false, RoutingPrefsParser.boolean(xml, "killswitch"))
        assertEquals("primary", RoutingPrefsParser.string(xml, "route_override_telegram"))
        assertEquals(123456L, RoutingPrefsParser.long(xml, "opera_retry_after"))
    }

    @Test
    fun `missing and malformed values fall back to null`() {
        assertNull(RoutingPrefsParser.boolean(xml, "missing"))
        assertNull(RoutingPrefsParser.string(null, "route_override_telegram"))
        assertNull(RoutingPrefsParser.long(xml, "opera_retry_after_typo"))
        assertNull(RoutingPrefsParser.long(xml, "smart_routing"))
    }
}
