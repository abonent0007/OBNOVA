package com.example.nova

import java.net.InetAddress

/**
 * Минимальная проверка egress для AI-маршрутов.
 *
 * nonRuEligible = true только если одновременно:
 *  • страна — валидный двухбуквенный код и это не RU/спецкод;
 *  • IP — публичный (не loopback/private/link-local/multicast/CGNAT).
 *
 * Неизвестное состояние всегда false. Полноценный P-F Health не заменяет:
 * здесь нет истории, TTL и повторных проверок.
 */
object EgressEligibility {

    private val NON_COUNTRY_CODES = setOf("T1", "XX", "A1", "A2", "O1", "EU", "AP")

    fun isEligible(ip: String?, country: String?): Boolean {
        val code = country?.trim()?.uppercase().orEmpty()
        if (code.length != 2 || code.any { it !in 'A'..'Z' }) return false
        if (code == "RU" || code in NON_COUNTRY_CODES) return false
        val address = parseLiteral(ip?.trim().orEmpty()) ?: return false
        return address.isPublic()
    }

    private fun parseLiteral(value: String): InetAddress? {
        // Не даём InetAddress разрешать имена: принимаем только литералы.
        val looksLikeIpv4 = value.count { it == '.' } == 3 &&
            value.split('.').all { part -> part.isNotEmpty() && part.all(Char::isDigit) }
        val looksLikeIpv6 = value.contains(':') &&
            value.all { it.isDigit() || it in "abcdefABCDEF:." }
        if (!looksLikeIpv4 && !looksLikeIpv6) return null
        return runCatching { InetAddress.getByName(value) }.getOrNull()
    }

    private fun InetAddress.isPublic(): Boolean {
        if (isAnyLocalAddress || isLoopbackAddress || isLinkLocalAddress ||
            isSiteLocalAddress || isMulticastAddress
        ) {
            return false
        }
        val raw = address
        if (raw.size == 4) {
            val first = raw[0].toInt() and 0xff
            val second = raw[1].toInt() and 0xff
            // CGNAT 100.64.0.0/10.
            if (first == 100 && second in 64..127) return false
            // 0.0.0.0/8 и 127.0.0.0/8 уже покрыты, но оставляем явно.
            if (first == 0 || first == 127) return false
        } else if (raw.size == 16) {
            // fc00::/7 (ULA).
            if ((raw[0].toInt() and 0xfe) == 0xfc) return false
            // fe80::/10.
            if ((raw[0].toInt() and 0xff) == 0xfe &&
                (raw[1].toInt() and 0xc0) == 0x80
            ) {
                return false
            }
        }
        return true
    }
}
