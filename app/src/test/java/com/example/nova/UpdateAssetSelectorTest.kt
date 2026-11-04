package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateAssetSelectorTest {

    private fun assets(vararg names: String): List<Pair<String, String>> =
        names.map { it to "https://example.test/$it" }

    private val release = listOf(
        "OBNOVA_1.34.3-arm64-v8a.apk",
        "OBNOVA_1.34.3-armeabi-v7a.apk",
        "OBNOVA_1.34.3-debug-arm64-v8a.apk",
        "OBNOVA_1.34.3-debug-armeabi-v7a.apk",
        "OBNOVA_1.34.3-debug-universal.apk",
        "OBNOVA_1.34.3-universal.apk",
    )

    @Test
    fun `arm64 device gets the arm64 release even when another abi comes first`() {
        val selected = UpdateAssetSelector.pick(
            assets(*release.toTypedArray()),
            abis = listOf("arm64-v8a", "armeabi-v7a", "armeabi"),
        )
        assertEquals("OBNOVA_1.34.3-arm64-v8a.apk", selected?.first)
    }

    @Test
    fun `32-bit device gets the v7a release, not the first apk`() {
        val selected = UpdateAssetSelector.pick(
            assets(*release.toTypedArray()),
            abis = listOf("armeabi-v7a", "armeabi"),
        )
        assertEquals("OBNOVA_1.34.3-armeabi-v7a.apk", selected?.first)
    }

    @Test
    fun `debug builds are never offered`() {
        val selected = UpdateAssetSelector.pick(
            assets(
                "OBNOVA_9.9.9-debug-arm64-v8a.apk",
                "OBNOVA_9.9.9-debug-universal.apk",
                "OBNOVA_9.9.9-universal.apk",
            ),
            abis = listOf("arm64-v8a"),
        )
        assertEquals("OBNOVA_9.9.9-universal.apk", selected?.first)
    }

    @Test
    fun `secondary abi is used when the primary one is absent`() {
        val selected = UpdateAssetSelector.pick(
            assets("OBNOVA_9.9.9-armeabi-v7a.apk", "OBNOVA_9.9.9-universal.apk"),
            abis = listOf("arm64-v8a", "armeabi-v7a"),
        )
        assertEquals("OBNOVA_9.9.9-armeabi-v7a.apk", selected?.first)
    }

    @Test
    fun `universal is the fallback when no exact abi is published`() {
        val selected = UpdateAssetSelector.pick(
            assets("OBNOVA_9.9.9-armeabi-v7a.apk", "OBNOVA_9.9.9-universal.apk"),
            abis = listOf("x86_64"),
        )
        assertEquals("OBNOVA_9.9.9-universal.apk", selected?.first)
    }

    @Test
    fun `an apk for another abi is never offered`() {
        assertNull(
            UpdateAssetSelector.pick(
                assets("OBNOVA_9.9.9-armeabi-v7a.apk"),
                abis = listOf("arm64-v8a"),
            ),
        )
    }

    @Test
    fun `no apk in the release yields null`() {
        assertNull(UpdateAssetSelector.pick(emptyList(), listOf("arm64-v8a")))
        assertNull(UpdateAssetSelector.pick(assets("notes.txt"), listOf("arm64-v8a")))
    }
}
