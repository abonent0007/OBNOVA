package com.example.nova

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/**
 * Настройки выпуска собственных WARP-профилей.
 *
 * Пресеты повторяют логику PC-версии: `optimal` — текущее поведение (50
 * профилей, случайный junk), `fast` — меньше профилей и мягче junk, `compat` —
 * минимальный junk для сетей, где AmneziaWG-заголовок режут, `custom` — значения
 * пользователя. Хранится файлом, потому что выпуск идёт в процессе `:vpn`, а
 * экран — в основном (I2).
 */
object WarpGenerationSettings {

    const val PRESET_OPTIMAL = "optimal"
    const val PRESET_FAST = "fast"
    const val PRESET_COMPAT = "compat"
    const val PRESET_CUSTOM = "custom"

    val PRESETS = listOf(
        PRESET_OPTIMAL to "Оптимально",
        PRESET_FAST to "Быстро",
        PRESET_COMPAT to "Совместимо",
        PRESET_CUSTOM to "Свои значения",
    )

    data class Values(
        val preset: String = PRESET_OPTIMAL,
        val profileCount: Int = 50,
        /** 0 — случайный junk, как в optimal. */
        val junkCount: Int = 0,
        val junkMin: Int = 0,
        val junkMax: Int = 0,
    ) {
        val normalized: Values
            get() = copy(
                preset = if (PRESETS.any { it.first == preset }) preset else PRESET_OPTIMAL,
                profileCount = profileCount.coerceIn(MIN_PROFILES, MAX_PROFILES),
                junkCount = junkCount.coerceIn(0, 12),
                junkMin = junkMin.coerceIn(0, 1000),
                junkMax = junkMax.coerceIn(0, 1500),
            )
    }

    const val MIN_PROFILES = 5
    const val MAX_PROFILES = 50

    private const val FILE_NAME = "warp_generation_settings.json"

    fun defaults(preset: String = PRESET_OPTIMAL): Values = when (preset) {
        PRESET_FAST -> Values(PRESET_FAST, profileCount = 25, junkCount = 3, junkMin = 30, junkMax = 60)
        PRESET_COMPAT -> Values(PRESET_COMPAT, profileCount = 15, junkCount = 1, junkMin = 20, junkMax = 40)
        PRESET_CUSTOM -> Values(PRESET_CUSTOM, profileCount = 30, junkCount = 4, junkMin = 40, junkMax = 80)
        else -> Values(PRESET_OPTIMAL)
    }

    fun load(context: Context?): Values {
        val file = fileFor(context) ?: return Values()
        val raw = runCatching { file.baseFile.takeIf { it.exists() }?.readText(Charsets.UTF_8) }
            .getOrNull().orEmpty()
        if (raw.isBlank()) return Values()
        return runCatching {
            val json = JSONObject(raw)
            Values(
                preset = json.optString("preset", PRESET_OPTIMAL),
                profileCount = json.optInt("profile_count", 50),
                junkCount = json.optInt("junk_count", 0),
                junkMin = json.optInt("junk_min", 0),
                junkMax = json.optInt("junk_max", 0),
            ).normalized
        }.getOrDefault(Values())
    }

    fun save(context: Context?, values: Values) {
        val file = fileFor(context) ?: return
        val normalized = values.normalized
        val payload = JSONObject()
            .put("preset", normalized.preset)
            .put("profile_count", normalized.profileCount)
            .put("junk_count", normalized.junkCount)
            .put("junk_min", normalized.junkMin)
            .put("junk_max", normalized.junkMax)
        runCatching {
            val stream = file.startWrite()
            try {
                stream.write(payload.toString().toByteArray(Charsets.UTF_8))
                file.finishWrite(stream)
            } catch (error: Throwable) {
                file.failWrite(stream)
                throw error
            }
        }
    }

    private fun fileFor(context: Context?): AtomicFile? {
        val dir = context?.applicationContext?.filesDir ?: return null
        return AtomicFile(File(dir, FILE_NAME))
    }
}
