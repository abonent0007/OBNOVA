package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Test

class TldListTest {

    @Test
    fun `parses zones comments and unicode`() {
        val parsed = TldList.parse(
            sequenceOf(
                "# comment",
                "ru",
                ".su",
                "рф",
                "xn--P1AI",
                " ru ",
                "bad zone",
                "co.uk",
                "",
            ),
        )
        assertEquals(setOf("ru", "su", "рф", "xn--p1ai"), parsed)
    }

    @Test
    fun `empty input gives empty set`() {
        assertEquals(emptySet<String>(), TldList.parse(emptySequence()))
        assertEquals(emptySet<String>(), TldList.parse(sequenceOf("# only comment")))
    }
}
