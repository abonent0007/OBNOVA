package com.example.nova

/**
 * TTL-кэш DNS-ответов для локального DNS-прокси.
 *
 * Зачем. `LocalDnsProxyManager` пересылает каждый запрос в DoH/DoT/plain, а
 * приложения на телефоне спрашивают одни и те же имена десятки раз. Без кэша
 * каждый повтор — это TLS-соединение, лишний RTT и риск таймаута; с кэшем
 * повторный ответ отдаётся с диска памяти до истечения TTL из самого ответа.
 *
 * Кэш намеренно не знает ни о VPN, ни о профилях: это чистый разбор DNS-пакета,
 * поэтому его можно проверить JVM-тестами без эмулятора. Ключ не включает ID
 * запроса — иначе один и тот же вопрос от разных клиентов не попадал бы в кэш;
 * ID подставляет вызывающая сторона через [withTransactionId].
 */
class DnsResponseCache(
    private val maxEntries: Int = 512,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {

    private data class Entry(val response: ByteArray, val expiresAtMs: Long)

    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)

    /** Возвращает копию ответа без изменения ID; `null` — кэш не знает ответа. */
    fun get(query: ByteArray): ByteArray? {
        val key = keyOf(query) ?: return null
        val now = clockMs()
        synchronized(entries) {
            val entry = entries[key] ?: return null
            if (entry.expiresAtMs <= now) {
                entries.remove(key)
                return null
            }
            return entry.response.copyOf()
        }
    }

    /** Кладёт ответ, если в нём есть пригодный TTL. Ответ копируется. */
    fun put(query: ByteArray, response: ByteArray) {
        val key = keyOf(query) ?: return
        val ttlSeconds = ttlSeconds(response) ?: return
        if (ttlSeconds <= 0) return
        val now = clockMs()
        val expiresAt = now + ttlSeconds * 1000L
        synchronized(entries) {
            entries[key] = Entry(response.copyOf(), expiresAt)
            while (entries.size > maxEntries.coerceAtLeast(1)) {
                val eldest = entries.entries.iterator()
                if (!eldest.hasNext()) break
                eldest.next()
                eldest.remove()
            }
        }
    }

    fun clear() {
        synchronized(entries) { entries.clear() }
    }

    fun size(): Int {
        val now = clockMs()
        synchronized(entries) {
            entries.entries.removeAll { it.value.expiresAtMs <= now }
            return entries.size
        }
    }

    companion object {

        /**
         * Копирует ответ и подставляет ID исходного запроса.
         *
         * DNS-клиент сопоставляет ответ с запросом по ID. Кэш хранит ответ с
         * чужим ID, поэтому без этой замены первый же повтор выглядел бы как
         * «ответ не на тот запрос» и отбрасывался системным резолвером.
         */
        fun withTransactionId(response: ByteArray, query: ByteArray): ByteArray {
            if (response.size < 2 || query.size < 2) return response
            val copy = response.copyOf()
            copy[0] = query[0]
            copy[1] = query[1]
            return copy
        }

        /** Ключ вопроса: имя + тип + класс + бит рекурсии. */
        fun keyOf(query: ByteArray): String? {
            if (query.size < 12) return null
            val qdCount = u16(query, 4)
            if (qdCount <= 0) return null
            val nameAt = readName(query, 12) ?: return null
            val pos = nameAt.second
            if (pos + 4 > query.size) return null
            val qType = u16(query, pos)
            val qClass = u16(query, pos + 2)
            val recursion = (u16(query, 2) and 0x0100) != 0
            // Отпечаток всего запроса, кроме transaction ID: сюда входят
            // EDNS0/DO и прочие опции. Иначе DNSSEC-запрос мог бы получить
            // обычный кэшированный ответ без нужной OPT-записи.
            val optionsFingerprint = query.copyOfRange(2, query.size).contentHashCode()
            return "${nameAt.first.lowercase()}|$qType|$qClass|$recursion|$optionsFingerprint"
        }

        /**
         * Минимальный TTL ответа в секундах.
         *
         * Для положительного ответа берётся минимум по answer-записям: именно он
         * определяет, когда набор адресов может измениться. Для NXDOMAIN — TTL
         * SOA из authority, иначе 60 секунд. Усечённый ответ не кэшируется.
         */
        fun ttlSeconds(response: ByteArray): Int? {
            if (response.size < 12) return null
            val flags = u16(response, 2)
            if ((flags and 0x0200) != 0) return null // TC
            val rcode = flags and 0x000F
            val qdCount = u16(response, 4)
            val anCount = u16(response, 6)
            val nsCount = u16(response, 8)

            var pos = 12
            repeat(qdCount) {
                val question = readName(response, pos) ?: return null
                pos = question.second + 4
                if (pos > response.size) return null
            }

            var minAnswerTtl = Int.MAX_VALUE
            repeat(anCount) {
                val rr = readResourceRecord(response, pos) ?: return null
                if (rr.type != 41 && rr.ttl in 1 until minAnswerTtl) minAnswerTtl = rr.ttl
                pos = rr.next
            }
            if (anCount > 0) {
                // TTL=0 — явный запрет кэшировать; не подменяем его negative-TTL.
                if (minAnswerTtl == Int.MAX_VALUE) return null
                return minAnswerTtl.coerceAtMost(MAX_TTL_SECONDS)
            }

            // Отрицательный ответ: SOA в authority задаёт границу кэша.
            var minAuthorityTtl = Int.MAX_VALUE
            repeat(nsCount) {
                val rr = readResourceRecord(response, pos) ?: return null
                if (rr.ttl in 1 until minAuthorityTtl) minAuthorityTtl = rr.ttl
                pos = rr.next
            }
            if (minAuthorityTtl != Int.MAX_VALUE) {
                return minAuthorityTtl.coerceAtMost(NEGATIVE_MAX_TTL_SECONDS)
            }
            return when (rcode) {
                0, 3 -> NEGATIVE_FALLBACK_TTL_SECONDS
                else -> null
            }
        }

        private data class ResourceRecord(val type: Int, val ttl: Int, val next: Int)

        private fun readResourceRecord(packet: ByteArray, start: Int): ResourceRecord? {
            val name = readName(packet, start) ?: return null
            var pos = name.second
            if (pos + 10 > packet.size) return null
            val type = u16(packet, pos)
            // pos + 2..3 — класс, +4..7 — TTL, +8..9 — длина RDATA.
            val ttl = s32(packet, pos + 4)
            val rdLength = u16(packet, pos + 8)
            val next = pos + 10 + rdLength
            if (ttl < 0 || next > packet.size) return null
            return ResourceRecord(type = type, ttl = ttl, next = next)
        }

        /** Читает DNS-имя, включая сжатые указатели; возвращает имя и позицию после него. */
        private fun readName(packet: ByteArray, start: Int): Pair<String, Int>? {
            if (start !in packet.indices) return null
            val labels = ArrayList<String>(4)
            var pos = start
            var afterPointer: Int? = null
            var jumps = 0
            while (true) {
                if (pos !in packet.indices || jumps++ > 32) return null
                val length = packet[pos].toInt() and 0xFF
                if (length == 0) {
                    val end = afterPointer ?: (pos + 1)
                    return (labels.joinToString(".") to end)
                }
                if ((length and 0xC0) == 0xC0) {
                    if (pos + 1 !in packet.indices) return null
                    val pointer = ((length and 0x3F) shl 8) or (packet[pos + 1].toInt() and 0xFF)
                    if (afterPointer == null) afterPointer = pos + 2
                    if (pointer !in packet.indices) return null
                    pos = pointer
                    continue
                }
                if (length > 63 || pos + 1 + length > packet.size) return null
                labels += String(packet, pos + 1, length, Charsets.US_ASCII)
                pos += 1 + length
            }
        }

        private fun u16(bytes: ByteArray, at: Int): Int {
            if (at < 0 || at + 1 >= bytes.size) return 0
            return ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)
        }

        private fun s32(bytes: ByteArray, at: Int): Int {
            if (at < 0 || at + 3 >= bytes.size) return -1
            val value = ((bytes[at].toInt() and 0xFF).toLong() shl 24) or
                ((bytes[at + 1].toInt() and 0xFF).toLong() shl 16) or
                ((bytes[at + 2].toInt() and 0xFF).toLong() shl 8) or
                (bytes[at + 3].toInt() and 0xFF).toLong()
            return if (value > Int.MAX_VALUE) -1 else value.toInt()
        }

        private const val MAX_TTL_SECONDS = 3600
        private const val NEGATIVE_MAX_TTL_SECONDS = 300
        private const val NEGATIVE_FALLBACK_TTL_SECONDS = 60
    }
}
