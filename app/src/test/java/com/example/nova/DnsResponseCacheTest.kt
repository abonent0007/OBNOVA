package com.example.nova

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class DnsResponseCacheTest {

    @Test
    fun `positive response uses answer ttl`() {
        val query = query(0x1234, "example.com")
        val response = response(query, ttl = 300)
        assertEquals(300, DnsResponseCache.ttlSeconds(response))
    }

    @Test
    fun `cache hit survives transaction id change and rewrites it`() {
        var now = 1_000L
        val cache = DnsResponseCache(clockMs = { now })
        val first = query(0x1111, "example.com")
        val second = query(0x2222, "example.com")
        val answer = response(first, ttl = 60)

        cache.put(first, answer)
        val cached = cache.get(second)
        assertNotNull(cached)
        val served = DnsResponseCache.withTransactionId(cached!!, second)
        assertEquals(0x22, served[0].toInt() and 0xFF)
        assertEquals(0x22, served[1].toInt() and 0xFF)
        assertArrayEquals(answer.copyOfRange(12, answer.size), served.copyOfRange(12, served.size))
    }

    @Test
    fun `entry expires after ttl`() {
        var now = 5_000L
        val cache = DnsResponseCache(clockMs = { now })
        val query = query(0x0101, "example.com")
        cache.put(query, response(query, ttl = 10))
        assertNotNull(cache.get(query))
        now += 10_001L
        assertNull(cache.get(query))
    }

    @Test
    fun `truncated response is not cached`() {
        val query = query(0x0101, "example.com")
        val response = response(query, ttl = 60, flags = 0x8380) // TC set
        assertNull(DnsResponseCache.ttlSeconds(response))
        val cache = DnsResponseCache()
        cache.put(query, response)
        assertNull(cache.get(query))
    }

    @Test
    fun `ttl zero is not cached`() {
        val query = query(0x0101, "example.com")
        val response = response(query, ttl = 0)
        assertNull(DnsResponseCache.ttlSeconds(response))
        val cache = DnsResponseCache()
        cache.put(query, response)
        assertNull(cache.get(query))
    }

    private fun query(id: Int, name: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeShort(id)
            out.writeShort(0x0100) // recursion desired
            out.writeShort(1) // QDCOUNT
            out.writeShort(0) // ANCOUNT
            out.writeShort(0) // NSCOUNT
            out.writeShort(0) // ARCOUNT
            writeName(out, name)
            out.writeShort(1) // A
            out.writeShort(1) // IN
        }
        return bytes.toByteArray()
    }

    private fun response(query: ByteArray, ttl: Int, flags: Int = 0x8180): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.write(query, 0, 2) // same id
            out.writeShort(flags)
            out.writeShort(1) // QDCOUNT
            out.writeShort(1) // ANCOUNT
            out.writeShort(0) // NSCOUNT
            out.writeShort(0) // ARCOUNT
            out.write(query, 12, query.size - 12)
            // compression pointer to the question name at offset 12
            out.writeShort(0xC00C)
            out.writeShort(1) // A
            out.writeShort(1) // IN
            out.writeInt(ttl)
            out.writeShort(4)
            out.write(byteArrayOf(93, 184.toByte(), 216.toByte(), 34))
        }
        return bytes.toByteArray()
    }

    private fun writeName(out: DataOutputStream, name: String) {
        for (label in name.split('.')) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.writeByte(bytes.size)
            out.write(bytes)
        }
        out.writeByte(0)
    }
}
