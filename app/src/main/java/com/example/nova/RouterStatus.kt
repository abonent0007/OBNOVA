package com.example.nova

import org.json.JSONObject
import java.io.File

/**
 * Статистика слотов, которую пишет `nova-router` (status.json).
 *
 * Читаем мягко: файла может не быть, процесс мог быть остановлен, формат —
 * обновиться. Любая ошибка означает «статистики нет», а не падение экрана.
 */
data class SlotStat(
    val attempts: Long,
    val successes: Long,
    val failures: Long,
    val lastLatencyMs: Long,
    val ewmaMs: Double,
)

object RouterStatus {

    fun readPort(file: File): Int = runCatching {
        if (!file.exists()) return 0
        JSONObject(file.readText()).optInt("port", 0)
    }.getOrDefault(0)

    fun readRoutes(file: File): Map<String, Long> = runCatching {
        if (!file.exists()) return emptyMap()
        val routes = JSONObject(file.readText()).optJSONObject("routes") ?: return emptyMap()
        buildMap {
            val keys = routes.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, routes.optLong(key))
            }
        }
    }.getOrDefault(emptyMap())

    fun read(file: File): Map<String, SlotStat> {
        if (!file.exists()) return emptyMap()
        return runCatching {
            val root = JSONObject(file.readText())
            val slots = root.optJSONObject("slots") ?: return emptyMap()
            buildMap {
                val keys = slots.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val entry = slots.optJSONObject(key) ?: continue
                    put(
                        key,
                        SlotStat(
                            attempts = entry.optLong("attempts"),
                            successes = entry.optLong("successes"),
                            failures = entry.optLong("failures"),
                            lastLatencyMs = entry.optLong("lastLatencyMs"),
                            ewmaMs = entry.optDouble("ewmaMs", 0.0),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyMap())
    }
}
