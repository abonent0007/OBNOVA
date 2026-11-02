package com.example.nova

/**
 * IPv4-диапазон в формате CIDR — для списка Cloudflare-адресов.
 *
 * Диапазоны нужны, потому что `ip/cloudflare.txt` — это CIDR, а не отдельные
 * адреса. Сравнение идёт по 32-битному числу, без аллокаций на каждый пакет.
 */
class Ipv4Range private constructor(
    private val base: Long,
    private val maskBits: Int,
) {

    fun contains(ip: String): Boolean {
        val value = parseIp(ip) ?: return false
        val mask = if (maskBits == 0) 0L else (0xFFFFFFFFL shl (32 - maskBits)) and 0xFFFFFFFFL
        return (value and mask) == (base and mask)
    }

    companion object {
        fun parse(cidr: String): Ipv4Range? {
            val trimmed = cidr.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return null
            val parts = trimmed.split('/')
            val ip = parseIp(parts[0]) ?: return null
            if (parts.size == 1) return Ipv4Range(ip, 32)
            val bits = parts[1].toIntOrNull() ?: return null
            if (bits !in 0..32) return null
            return Ipv4Range(ip, bits)
        }

        fun parseIp(ip: String): Long? {
            val parts = ip.trim().split('.')
            if (parts.size != 4) return null
            var value = 0L
            for (part in parts) {
                val octet = part.toIntOrNull() ?: return null
                if (octet !in 0..255) return null
                value = (value shl 8) or octet.toLong()
            }
            return value
        }
    }
}
