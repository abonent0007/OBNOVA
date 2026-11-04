package com.example.nova

import java.io.File

/**
 * Дешёвая подпись локальных списков для кэша `ListRepository`.
 *
 * Кэш живёт в каждом процессе свой: синхронизация списков идёт в основном
 * процессе, а решение о маршрутах принимает `:vpn`. Без подписи служба
 * возвращала старый снимок и после «Обновить списки» продолжала работать по
 * прежним правилам. Подпись меняется при любом изменении файла или `meta.txt`.
 */
object ListCacheSignature {

    fun of(directory: File, meta: File): String {
        if (!directory.isDirectory) return "none"
        val files = directory.listFiles { file ->
            file.isFile &&
                file.name != "meta.txt" &&
                (file.name.endsWith(".txt") || file.name.endsWith(".bin"))
        }?.sortedBy { it.name }.orEmpty()
        if (files.isEmpty()) return "none"
        return buildString {
            for (file in files) {
                append(file.name).append(':')
                    .append(file.length()).append(':')
                    .append(file.lastModified()).append(';')
            }
            if (meta.exists()) {
                append("meta:").append(meta.length()).append(':').append(meta.lastModified())
                    .append(':').append(runCatching { meta.readText().trim() }.getOrDefault(""))
            }
        }
    }
}
