package com.example.nova

/**
 * Чтение значений из XML SharedPreferences без привязки к Android.
 *
 * Нужен, потому что SharedPreferences кэшируются в памяти процесса: служба
 * `:vpn` не видела переключателей, выставленных в UI, до перезапуска. Формат
 * простой и стабильный (`<boolean name="..." value="true" />`), поэтому
 * разбираем его сами и покрываем тестами.
 */
object RoutingPrefsParser {

    fun boolean(xml: String?, key: String): Boolean? {
        val line = lineFor(xml, key, "boolean") ?: return null
        return when {
            line.contains("value=\"true\"") -> true
            line.contains("value=\"false\"") -> false
            else -> null
        }
    }

    fun string(xml: String?, key: String): String? {
        val line = lineFor(xml, key, "string") ?: return null
        return line.substringAfter('>', "").substringBefore("</string>")
            .takeIf { it.isNotEmpty() }
    }

    fun long(xml: String?, key: String): Long? {
        val line = lineFor(xml, key, "long") ?: return null
        return line.substringAfter("value=\"", "").substringBefore('"').toLongOrNull()
    }

    private fun lineFor(xml: String?, key: String, tag: String): String? =
        xml?.lineSequence()
            ?.firstOrNull { it.contains("name=\"$key\"") && it.contains("<$tag") }
}
