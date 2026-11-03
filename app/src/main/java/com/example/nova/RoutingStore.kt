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

    fun isKillSwitch(): Boolean =
        RoutingPrefsParser.boolean(readPrefsXml(), KEY_KILLSWITCH)
            ?: prefs.getBoolean(KEY_KILLSWITCH, false)

    fun setKillSwitch(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_KILLSWITCH, enabled).commit()
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
    }
}
