package com.example.nova

import java.util.Locale

/**
 * Список локальных TLD — данные, а не код.
 *
 * Встроенный набор остаётся запасным, но если фид принесёт
 * `list/local_tlds.txt`, политика возьмёт его. Формат тот же, что у остальных
 * списков: одна зона в строке, `#` — комментарий.
 */
object TldList {

    fun parse(lines: Sequence<String>): Set<String> {
        val result = LinkedHashSet<String>()
        for (raw in lines) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val normalized = line
                .removePrefix(".")
                .lowercase(Locale.US)
                .trim()
            if (normalized.isEmpty()) continue
            // Политика сравнивает только последнюю метку; `co.uk` здесь
            // никогда не сработает, поэтому не принимаем его молча.
            if (normalized.contains('.')) continue
            if (normalized.any { it == '/' || it == ' ' || it == '\t' }) continue
            result += normalized
        }
        return result
    }
}
