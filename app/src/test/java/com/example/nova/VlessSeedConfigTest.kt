package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VlessSeedConfigTest {
    @Test
    fun `verified seed builds reality config`() {
        val seed = ExternalSources.VLESS_VERIFIED_SEEDS.first { it.contains("jarbook") }
        val config = VlessConfig.parse(seed) ?: error("seed did not parse")
        assertEquals("185.189.45.195", config.host)
        assertEquals("reality", config.security)
        assertEquals("c6f97313", config.realityShortId)
        assertEquals("xtls-rprx-vision", config.flow)
        val json = VlessXrayConfig.build(config, 18888)
        File(System.getProperty("java.io.tmpdir"), "app-vless.json").writeText(json)
        assertTrue(json.contains("\"security\":\"reality\""))
        assertTrue(json.contains("\"publicKey\":\"BIy6H-jndn24Ukyw83ML8LDB1HL8WLJmfCADEy8lzVs\""))
        assertTrue(json.contains("\"network\":\"raw\""))
    }

    @Test
    fun `tls settings omit removed allowInsecure`() {
        val config = VlessConfig(
            uuid = "00000000-0000-0000-0000-000000000000",
            host = "example.com",
            port = 443,
            security = "tls",
            sni = "example.com",
        )
        val json = VlessXrayConfig.build(config, 18889)
        assertTrue(json.contains("\"security\":\"tls\""))
        assertFalse(json.contains("allowInsecure"))
    }
}
