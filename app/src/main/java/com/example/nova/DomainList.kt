package com.example.nova

import java.util.Locale

/**
 * Доменный список в том же формате, что `list/name.txt` PC-форка:
 * по одному домену в строке, `#` — комментарий, пустые строки пропускаются.
 *
 * Матчинг — по границе метки: `example.com` ловит `a.example.com`, но не
 * `notexample.com`. Этого достаточно для списков маршрутизации; префиксное
 * дерево добавим, когда списки станут действительно большими.
 */
class DomainList private constructor(
    private val domains: List<String>,
) {

    val size: Int get() = domains.size

    /** Домены списка — для сборки правил роутера. */
    fun domains(): List<String> = domains

    fun contains(host: String?): Boolean {
        val normalized = RoutingPolicy.normalizeHost(host) ?: return false
        return domains.any { normalized == it || normalized.endsWith(".$it") }
    }

    companion object {
        val EMPTY: DomainList = DomainList(emptyList())

        fun parse(lines: Sequence<String>): DomainList {
            val seen = LinkedHashSet<String>()
            for (raw in lines) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val normalized = RoutingPolicy.normalizeHost(line) ?: continue
                if (!normalized.contains('.')) continue
                seen += normalized.lowercase(Locale.US)
            }
            return DomainList(seen.toList())
        }
    }
}
