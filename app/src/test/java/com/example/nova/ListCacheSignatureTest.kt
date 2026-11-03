package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File
import java.util.UUID

class ListCacheSignatureTest {

    @Test
    fun `signature changes when a list or meta file changes`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "nova-sig-${UUID.randomUUID()}")
        dir.mkdirs()
        try {
            val main = File(dir, "main.txt").apply { writeText("ya.ru\n") }
            val meta = File(dir, "meta.txt").apply { writeText("updated=1\n") }
            val first = ListCacheSignature.of(dir, meta)

            main.writeText("ya.ru\nmail.ru\n")
            val second = ListCacheSignature.of(dir, meta)
            assertNotEquals(first, second)

            meta.writeText("updated=2\n")
            val third = ListCacheSignature.of(dir, meta)
            assertNotEquals(second, third)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `empty directory has a stable none signature`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "nova-sig-${UUID.randomUUID()}")
        dir.mkdirs()
        try {
            assertEquals("none", ListCacheSignature.of(dir, File(dir, "meta.txt")))
        } finally {
            dir.deleteRecursively()
        }
    }
}
