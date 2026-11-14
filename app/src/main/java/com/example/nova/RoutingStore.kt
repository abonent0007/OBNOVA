package com.example.nova

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * Настройки маршрутизации — отдельный файл prefs.
 *
 * Почему не `ClientData`: тот пишется из процесса `:vpn`, а SharedPreferences
 * не умеет несколько процессов — запись из службы затирала флаги, выставленные
 * в UI (проверено: «умная маршрутизация» исчезала после force-stop). Здесь
 * пишет только UI, служба читает; гонки нет.
 *
 * Почему чтение идёт ещё и из XML: SharedPreferences кэшируются в памяти
 * процесса. Служба `:vpn` читала `smart_routing` один раз и до перезапуска
 * процесса не видела, что человек выключил «умную маршрутизацию»: следующий
 * connect снова поднимал MULTI. Теперь значение берётся из файла на диске,
 * поэтому переключатель действует со следующего подключения.
 *
 * `commit()` здесь намеренный: UI должен записать флаг до того, как служба
 * начнёт следующее подключение, иначе `apply()` мог бы оставить старое
 * значение на диске в момент чтения из другого процесса.
 */
@SuppressLint("ApplySharedPref", "UseKtx")
class RoutingStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val prefsFile: File =
        File(File(appContext.applicationInfo.dataDir, "shared_prefs"), "$FILE.xml")

    init {
        migrateAiOverridesToAuto()
    }

    /**
     * До появления слота `dnsai` AI уводили на SECONDARY/PRIMARY вручную.
     * Теперь штатный путь — DNS-AI, и старый override молча обходил бы его.
     * Сбрасываем только «выход через другой слот», не трогая DIRECT/BLOCK.
     */
    private fun migrateAiOverridesToAuto() {
        val migrationKey = "migration_ai_dnsai_v1"
        if (prefs.getBoolean(migrationKey, false)) return
        val legacy = setOf(
            RouteSettings.encodeOverride(RouteClass.EU),
            RouteSettings.encodeOverride(RouteClass.RU),
            RouteSettings.encodeOverride(RouteClass.YOUTUBE),
        )
        for (service in listOf("ai", "google_ai")) {
            if (getRouteOverride(service) in legacy) {
                setRouteOverride(service, RouteSettings.AUTO)
            }
        }
        prefs.edit().putBoolean(migrationKey, true).commit()
    }

    fun isSmartRoutingEnabled(): Boolean =
        RoutingPrefsParser.boolean(readPrefsXml(), KEY_SMART)
            ?: prefs.getBoolean(KEY_SMART, false)

    fun setSmartRoutingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SMART, enabled).commit()
    }

    fun isForeignDefaultVpn(): Boolean =
        RoutingPrefsParser.boolean(readPrefsXml(), KEY_FOREIGN_DEFAULT)
            ?: prefs.getBoolean(KEY_FOREIGN_DEFAULT, true)

    fun setForeignDefaultVpn(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FOREIGN_DEFAULT, enabled).commit()
    }

    fun isKillSwitch(): Boolean = getRoutePolicyMode().isKillSwitch

    /**
     * Совместимость со старым экраном: включает/выключает только прежнее
     * поведение (LIMITED), но никогда не Strict Full Tunnel. Strict — отдельное
     * явное действие пользователя.
     */
    fun setKillSwitch(enabled: Boolean) {
        setRoutePolicyMode(if (enabled) RoutePolicyMode.LIMITED else RoutePolicyMode.OFF)
    }

    /** Текущая политика выхода. Читается лениво, без записи из :vpn. */
    fun getRoutePolicyMode(): RoutePolicyMode {
        val raw = RoutingPrefsParser.string(readPrefsXml(), KEY_POLICY_MODE)
            ?: prefs.getString(KEY_POLICY_MODE, null)
        val legacy = RoutingPrefsParser.boolean(readPrefsXml(), KEY_KILLSWITCH)
            ?: prefs.getBoolean(KEY_KILLSWITCH, false)
        return RoutePolicyMode.migrateFromLegacy(raw, legacy)
    }

    fun setRoutePolicyMode(mode: RoutePolicyMode) {
        prefs.edit()
            .putString(KEY_POLICY_MODE, mode.storageValue)
            // Зеркало для отката версии: старый APK читает только boolean.
            .putBoolean(KEY_KILLSWITCH, mode.isKillSwitch)
            .putBoolean(KEY_POLICY_MODE_MIGRATED, true)
            .putLong(KEY_POLICY_MODE_SET_AT, System.currentTimeMillis())
            .commit()
    }

    /** Когда режим менялся: экран не считает Strict активным до нового CONNECTED. */
    fun getRoutePolicyModeSetAt(): Long =
        RoutingPrefsParser.long(readPrefsXml(), KEY_POLICY_MODE_SET_AT)
            ?: prefs.getLong(KEY_POLICY_MODE_SET_AT, 0L)

    /**
     * Включение Strict Full Tunnel — только явным действием пользователя.
     * Прежний режим запоминается, чтобы выключение Strict не меняло поведение
     * молча: LIMITED останется LIMITED, OFF останется OFF.
     */
    fun enterStrictFullTunnel() {
        val current = getRoutePolicyMode()
        if (current == RoutePolicyMode.STRICT_FULL) return
        prefs.edit()
            .putString(KEY_POLICY_MODE_BEFORE_STRICT, current.storageValue)
            .putString(KEY_POLICY_MODE, RoutePolicyMode.STRICT_FULL.storageValue)
            .putBoolean(KEY_KILLSWITCH, true)
            .putBoolean(KEY_POLICY_MODE_MIGRATED, true)
            .putLong(KEY_POLICY_MODE_SET_AT, System.currentTimeMillis())
            .commit()
        LogManager.log("POLICY: STRICT_FULL включён (было ${current.storageValue})")
    }

    /** Выключение Strict возвращает ровно тот режим, что был до него. */
    fun exitStrictFullTunnel() {
        if (getRoutePolicyMode() != RoutePolicyMode.STRICT_FULL) return
        val raw = RoutingPrefsParser.string(readPrefsXml(), KEY_POLICY_MODE_BEFORE_STRICT)
            ?: prefs.getString(KEY_POLICY_MODE_BEFORE_STRICT, null)
        val restore = RoutePolicyMode.fromStorage(raw) ?: RoutePolicyMode.LIMITED
        setRoutePolicyMode(restore)
        prefs.edit().remove(KEY_POLICY_MODE_BEFORE_STRICT).commit()
        LogManager.log("POLICY: STRICT_FULL выключен, восстановлен ${restore.storageValue}")
    }

    /**
     * Одноразовая запись миграции старого `killswitch` в новый режим.
     *
     * Зовётся только из UI-процесса: SharedPreferences не умеют несколько
     * процессов, и запись из `:vpn` затирала бы флаги, выставленные на экране.
     * До миграции [getRoutePolicyMode] и так возвращает правильное значение,
     * поэтому служба не зависит от факта записи.
     */
    fun migratePolicyModeIfNeeded() {
        if (!isMainProcess) return
        val raw = RoutingPrefsParser.string(readPrefsXml(), KEY_POLICY_MODE)
            ?: prefs.getString(KEY_POLICY_MODE, null)
        if (raw != null) return
        val mode = getRoutePolicyMode()
        setRoutePolicyMode(mode)
        LogManager.log("POLICY: старый killswitch мигрирован в режим ${mode.storageValue}")
    }

    private val isMainProcess: Boolean by lazy {
        runCatching {
            File("/proc/self/cmdline").readText().trim('\u0000', '\n') == appContext.packageName
        }.getOrDefault(false)
    }

    fun getRouteOverride(service: String): String {
        val key = "route_override_$service"
        return RoutingPrefsParser.string(readPrefsXml(), key)
            ?: prefs.getString(key, RouteSettings.AUTO)
            ?: RouteSettings.AUTO
    }

    fun setRouteOverride(service: String, value: String) {
        prefs.edit().putString("route_override_$service", value).commit()
    }

    /** После неудачи Opera не пробуем её 10 минут — иначе каждое подключение +6 с. */
    fun operaRetryAfterMs(): Long =
        RoutingPrefsParser.long(readPrefsXml(), "opera_retry_after")
            ?: prefs.getLong("opera_retry_after", 0L)

    fun setOperaRetryAfterMs(value: Long) {
        prefs.edit().putLong("opera_retry_after", value).commit()
    }

    private fun readPrefsXml(): String? = runCatching {
        if (prefsFile.exists()) prefsFile.readText() else null
    }.getOrNull()

    companion object {
        private const val FILE = "nova_routing"
        private const val KEY_SMART = "smart_routing"
        private const val KEY_FOREIGN_DEFAULT = "foreign_default_vpn"
        private const val KEY_KILLSWITCH = "killswitch"
        private const val KEY_POLICY_MODE = "policy_mode"
        private const val KEY_POLICY_MODE_BEFORE_STRICT = "policy_mode_before_strict"
        private const val KEY_POLICY_MODE_MIGRATED = "migration_policy_mode_v1"
        private const val KEY_POLICY_MODE_SET_AT = "policy_mode_set_at"
    }
}
