package com.example.nova

/**
 * Гистерезис автоматической смены профиля по качеству канала.
 *
 * Это Android-порт политики PC-форка (`nova_profile_rotation.py`), но без сокетов
 * и без похода на сервер: класс принимает уже измеренную задержку. Смена делает
 * существующая кнопка «следующий профиль», которая знает порядок списка и
 * безопасно перезапускает сеанс.
 *
 * Почему такие пороги:
 *  - 90 секунд после подключения — узел и DNS должны устояться;
 *  - 3 деградированных сэмпла подряд — одиночный всплеск не считается;
 *  - 45 секунд между сэмплами — не дёргать канал чаще, чем раз в несколько проб;
 *  - 15 минут cooldown и 2 смены в час — защита от «качелей» между узлами;
 *  - задержка выше `max(30 ms, baseline × 1.8)` считается деградацией.
 *
 * Режим `auto` — единственный, где класс вообще что-то решает. Явно выбранный
 * транспорт/профиль не подменяется: это правило PC-форка.
 */
class ProfileHealthRotation(
    private val sampleIntervalMs: Long = 45_000L,
    private val minActiveMs: Long = 90_000L,
    private val degradedLimit: Int = 3,
    private val cooldownMs: Long = 15 * 60_000L,
    private val maxPerHour: Int = 2,
    private val rttDegradedFactor: Double = 1.8,
    private val minRttMs: Double = 30.0,
    private val ewmaAlpha: Double = 0.3,
    private val userActionCooldownMs: Long = 5 * 60_000L,
) {

    data class Decision(val rotate: Boolean, val reason: String)

    private var activeSinceMs = -1L
    private var lastSampleAtMs = -1L
    private var baselineMs = -1.0
    private var samples = 0
    private var degradedStreak = 0
    private var lastRotationAtMs = 0L
    private var lastUserActionAtMs = 0L
    private val rotationTimes = ArrayDeque<Long>()

    fun onConnected(nowMs: Long) {
        if (activeSinceMs < 0L) activeSinceMs = nowMs
    }

    fun onDisconnected() {
        activeSinceMs = -1L
        lastSampleAtMs = -1L
        baselineMs = -1.0
        samples = 0
        degradedStreak = 0
    }

    /** Ручной выбор профиля сбрасывает накопленную деградацию. */
    fun noteUserAction(nowMs: Long) {
        lastUserActionAtMs = nowMs
        activeSinceMs = nowMs
        lastSampleAtMs = -1L
        baselineMs = -1.0
        samples = 0
        degradedStreak = 0
    }

    fun observe(nowMs: Long, latencyMs: Int, mode: String): Decision {
        if (mode.trim().lowercase() != "auto") return Decision(false, "режим не auto")
        if (activeSinceMs < 0L) activeSinceMs = nowMs
        if (nowMs - activeSinceMs < minActiveMs) return Decision(false, "канал ещё устаивается")
        if (lastSampleAtMs >= 0L && nowMs - lastSampleAtMs < sampleIntervalMs) {
            return Decision(false, "рано для следующего сэмпла")
        }
        lastSampleAtMs = nowMs

        if (latencyMs <= 0) {
            degradedStreak++
        } else {
            val value = latencyMs.toDouble()
            if (baselineMs <= 0.0) {
                baselineMs = value
                samples = 1
                degradedStreak = 0
                return Decision(false, "накопление baseline")
            }
            samples++
            val threshold = maxOf(minRttMs, baselineMs * rttDegradedFactor)
            if (value > threshold) {
                degradedStreak++
            } else {
                degradedStreak = 0
                baselineMs = ewmaAlpha * value + (1.0 - ewmaAlpha) * baselineMs
            }
        }

        if (degradedStreak < degradedLimit) {
            return Decision(false, "деградация $degradedStreak/$degradedLimit")
        }
        if (lastRotationAtMs != 0L && nowMs - lastRotationAtMs < cooldownMs) {
            return Decision(false, "cooldown после прошлой смены")
        }
        if (lastUserActionAtMs != 0L && nowMs - lastUserActionAtMs < userActionCooldownMs) {
            return Decision(false, "недавнее действие пользователя")
        }
        rotationTimes.removeAll { it < nowMs - 3_600_000L }
        if (rotationTimes.size >= maxPerHour) {
            return Decision(false, "лимит $maxPerHour смены в час")
        }
        return Decision(true, "канал деградировал: $degradedStreak сэмпла подряд")
    }

    fun noteRotation(nowMs: Long, success: Boolean) {
        if (!success) {
            lastRotationAtMs = nowMs - cooldownMs + 60_000L
            degradedStreak = 0
            return
        }
        lastRotationAtMs = nowMs
        rotationTimes.addLast(nowMs)
        activeSinceMs = nowMs
        lastSampleAtMs = -1L
        baselineMs = -1.0
        samples = 0
        degradedStreak = 0
    }

    fun degradedStreakForTest(): Int = degradedStreak
    fun baselineForTest(): Double = baselineMs
}
