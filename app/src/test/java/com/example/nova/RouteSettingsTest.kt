package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteSettingsTest {

    @Test
    fun `unknown and empty values mean auto`() {
        assertNull(RouteSettings.parseOverride(null))
        assertNull(RouteSettings.parseOverride(""))
        assertNull(RouteSettings.parseOverride("auto"))
        assertNull(RouteSettings.parseOverride("atlantis"))
    }

    @Test
    fun `every route class survives encode-parse round trip`() {
        val classes = listOf(
            RouteClass.DIRECT,
            RouteClass.BLOCK,
            RouteClass.RU,
            RouteClass.EU,
            RouteClass.YOUTUBE,
            RouteClass.TELEGRAM,
            RouteClass.WHATSAPP,
            RouteClass.DISCORD,
            RouteClass.AI,
            RouteClass.GOOGLE_AI,
        )
        for (routeClass in classes) {
            assertEquals(routeClass, RouteSettings.parseOverride(RouteSettings.encodeOverride(routeClass)))
        }
        assertNull(RouteSettings.parseOverride(RouteSettings.encodeOverride(null)))
    }

    @Test
    fun `labels do not lie about destination`() {
        assertEquals("AUTO", RouteSettings.label(null))
        assertEquals("DIRECT", RouteSettings.label(RouteClass.DIRECT))
        // RELAY в конфигурации роутера нет: RouterConfigFactory не создаёт такой
        // слот, поэтому Telegram фактически идёт в PRIMARY. Подпись обязана
        // совпадать с тем, что реально делает маршрутизатор.
        assertEquals("PRIMARY", RouteSettings.label(RouteClass.TELEGRAM))
        assertEquals("PRIMARY", RouteSettings.label(RouteClass.RU))
        assertEquals("SECONDARY", RouteSettings.label(RouteClass.EU))
    }

    @Test
    fun `automatic chain hints match RouterConfigFactory`() {
        assertEquals("PRIMARY → DIRECT", RouteSettings.chainHint(RouteClass.RU))
        assertEquals("SECONDARY → PRIMARY → DIRECT", RouteSettings.chainHint(RouteClass.EU))
        assertEquals("SECONDARY → PRIMARY → DIRECT", RouteSettings.chainHint(RouteClass.YOUTUBE))
        assertEquals("SECONDARY → PRIMARY → DIRECT", RouteSettings.chainHint(RouteClass.GOOGLE_AI))
        assertEquals("PRIMARY", RouteSettings.chainHint(RouteClass.TELEGRAM))
        assertTrue(!RouteSettings.chainHint(RouteClass.TELEGRAM).contains("DIRECT"))
    }

    @Test
    fun `service list keeps stable keys`() {
        assertEquals(
            listOf("ru", "eu", "youtube", "telegram", "whatsapp", "discord", "ai", "google_ai", "other"),
            RouteSettings.SERVICES.map { it.key },
        )
    }
}
