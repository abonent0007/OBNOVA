package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceProbeTargetsTest {

    private fun snapshot(
        main: List<String> = emptyList(),
        second: List<String> = emptyList(),
        youtube: List<String> = emptyList(),
        telegram: List<String> = emptyList(),
    ) = RoutingListsSnapshot(
        main = DomainList.parse(main.asSequence()),
        second = DomainList.parse(second.asSequence()),
        youtube = DomainList.parse(youtube.asSequence()),
        telegram = DomainList.parse(telegram.asSequence()),
    )

    @Test
    fun `empty lists use built-in fallbacks`() {
        val targets = ServiceProbe.targetsFor(snapshot())
        assertEquals("ya.ru", targets.first { it.name == "RU" }.hosts.first())
        assertEquals("www.cnn.com", targets.first { it.name == "US" }.hosts.first())
        assertEquals("www.youtube.com", targets.first { it.name == "YouTube" }.hosts.first())
        assertEquals("telegram.org", targets.first { it.name == "Telegram" }.hosts.first())
    }

    @Test
    fun `probe hosts follow the synced lists first`() {
        val targets = ServiceProbe.targetsFor(
            snapshot(
                main = listOf("mail.ru", "example.ru"),
                second = listOf("example.org"),
                youtube = listOf("youtube.com"),
                telegram = listOf("t.me"),
            ),
        )
        assertEquals("mail.ru", targets.first { it.name == "RU" }.hosts.first())
        assertEquals("example.org", targets.first { it.name == "US" }.hosts.first())
        assertEquals("www.youtube.com", targets.first { it.name == "YouTube" }.hosts.first())
        assertEquals("t.me", targets.first { it.name == "Telegram" }.hosts.first())
    }

    @Test
    fun `unknown list entries do not replace the fallback`() {
        val targets = ServiceProbe.targetsFor(
            snapshot(main = listOf("something.example")),
        )
        val ru = targets.first { it.name == "RU" }.hosts
        assertEquals("ya.ru", ru.first())
        assertTrue(ru.contains("mail.ru"))
    }
}
