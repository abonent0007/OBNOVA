package com.example.nova

import org.json.JSONObject

/**
 * Разбор `manifest.json` фида списков без Android и сети.
 *
 * Манифест — единственный источник истины о том, какие файлы существуют.
 * Раньше список из девяти имён был зашит в `ListSync`, и новый файл в фиде
 * (например, `local_tlds.txt`) просто не доезжал до устройства. Теперь набор
 * берётся из манифеста, а имена проверяются на безопасность.
 */
object ListManifest {

    data class Manifest(
        val generated: Long,
        val files: Map<String, String>,
    ) {
        /** Пути файлов из каталога list, которые можно безопасно скачать. */
        fun listPaths(): List<String> =
            files.keys.filter(::isListPath).sorted()

        fun shaFor(relativePath: String): String? = files[relativePath]
    }

    fun parse(raw: String): Manifest? = runCatching {
        val root = JSONObject(raw)
        val filesJson = root.optJSONObject("files") ?: return null
        val files = LinkedHashMap<String, String>()
        val keys = filesJson.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val relativePath = normalizeRelative(key) ?: continue
            val entry = filesJson.optJSONObject(key) ?: continue
            val sha = entry.optString("sha256").trim()
            if (sha.isNotEmpty()) files[relativePath] = sha
        }
        Manifest(generated = root.optLong("generated", 0L), files = files)
    }.getOrNull()

    /**
     * Ключи фида встречаются в двух видах: `list/main.txt` и
     * `nova_pc/list/main.txt`. Оба приводим к пути от корня фида.
     */
    fun normalizeRelative(raw: String): String? {
        var path = raw.trim().replace('\\', '/')
        while (path.startsWith("./")) path = path.removePrefix("./")
        path = path.removePrefix("${ExternalSources.LIST_BASE}/")
        if (path.isEmpty() || path.startsWith("/")) return null
        if (path.split('/').any { it == ".." || it.isEmpty() }) return null
        return path
    }

    fun isListPath(relativePath: String): Boolean {
        if (!relativePath.startsWith("list/")) return false
        if (relativePath.count { it == '/' } != 1) return false
        val name = relativePath.removePrefix("list/")
        if (name.startsWith("u_")) return false
        if (!name.endsWith(".txt")) return false
        return name.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }
    }

    fun destinationName(relativePath: String): String? = when {
        isListPath(relativePath) -> relativePath.removePrefix("list/")
        relativePath == "ip/cloudflare.txt" -> "cloudflare_ips.txt"
        else -> null
    }
}
