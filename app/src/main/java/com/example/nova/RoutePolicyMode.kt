package com.example.nova

/**
 * Политика выхода пользовательского трафика.
 *
 * Значение хранится строкой, как и остальные настройки маршрутизации: строка
 * переживает откат версии и не ломает старые prefs неизвестным enum-значением.
 *
 * [OFF] — прежнее поведение: `direct` разрешён как хвост маршрутов.
 *
 * [LIMITED] — совместимый со старым «Kill Switch»: `direct` уже убран из
 * `defaultOrder`/`order()`, но [DirectAppsPolicy], `exclude`, `local-tld`,
 * DNS-carve-outs и split-tunnel продолжают действовать. Это **не** Strict:
 * часть трафика по-прежнему может уйти мимо VPN.
 *
 * [STRICT_FULL] — весь пользовательский трафик через MULTI, без `direct`,
 * без `addAllowedApplication`/`addDisallowedApplication`, без DNS-обходов.
 * Включается только явным действием пользователя.
 *
 * [SELECTIVE] — зарезервировано под Selective Direct. Функциональность не
 * реализована; миграция и UI это значение не выставляют.
 */
enum class RoutePolicyMode(val storageValue: String) {
    OFF("off"),
    LIMITED("limited"),
    STRICT_FULL("strict_full"),
    SELECTIVE("selective");

    /** Есть ли запрет DIRECT-хвоста (поведение прежнего killSwitch). */
    val isKillSwitch: Boolean get() = this != OFF

    /** Полный Strict: пока только модель, поведение появится в B.2.2/B.2.3. */
    val isStrictFullTunnel: Boolean get() = this == STRICT_FULL

    companion object {

        fun fromStorage(raw: String?): RoutePolicyMode? {
            val value = raw?.trim()?.lowercase().orEmpty()
            if (value.isEmpty()) return null
            return values().firstOrNull { it.storageValue == value }
        }

        /**
         * Миграция старой настройки `killswitch`.
         *
         * Старый `true` означал лишь «убрать direct из хвоста маршрутов», а не
         * согласие на Strict Full Tunnel: банки, госуслуги, `exclude`,
         * `local-tld` и DNS-обходы при нём продолжали работать. Поэтому
         * единственное допустимое отображение — [LIMITED]. В [STRICT_FULL]
         * пользователь переходит только сам, отдельным подтверждением.
         *
         * Явно сохранённый режим сильнее старого boolean: после отката версии
         * настройка не должна «мигрировать» повторно.
         */
        fun migrateFromLegacy(
            storedRaw: String?,
            legacyKillSwitch: Boolean?,
        ): RoutePolicyMode =
            fromStorage(storedRaw) ?: if (legacyKillSwitch == true) LIMITED else OFF
    }
}
