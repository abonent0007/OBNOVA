package com.example.nova

import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Состояние системного «Постоянного VPN» для Nova.
 *
 * Android не даёт публичного API «включён ли always-on для моего пакета»:
 * `VpnService` умеет только объявлять поддержку мета-датой. Единственный
 * доступный источник — защищённые настройки `always_on_vpn_app` и
 * `always_on_vpn_lockdown`. Читаются они без разрешений, но на некоторых
 * прошивках доступ может быть закрыт — поэтому всё в `runCatching`.
 *
 * Always-on — единственный механизм, при котором систему обязывает поднимать
 * VPN после kill процесса и перезагрузки. Наш `START_STICKY` и AlarmManager
 * этого не гарантируют, особенно на Xiaomi/Samsung.
 */
object AlwaysOnVpn {

    private const val KEY_ALWAYS_ON_APP = "always_on_vpn_app"
    private const val KEY_LOCKDOWN = "always_on_vpn_lockdown"

    /** Пакет, закреплённый как постоянный VPN, либо null, если always-on выключен. */
    fun configuredPackage(context: Context): String? = runCatching {
        Settings.Secure.getString(context.contentResolver, KEY_ALWAYS_ON_APP)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    fun isNovaAlwaysOn(context: Context): Boolean = readState(context) == true

    /**
     * Состояние always-on для Nova: `true` — включён, `false` — выключен,
     * `null` — прочитать нельзя.
     *
     * На части прошивок `Settings.Secure` закрыт для обычных приложений:
     * значение есть (его видит `adb shell settings get`), а `getString`
     * возвращает null. Отличить «выключено» от «недоступно» нельзя, поэтому
     * null — это «неизвестно», и UI не имеет права рисовать выключенный
     * тумблер: он соврал бы.
     */
    fun readState(context: Context): Boolean? = runCatching {
        Settings.Secure.getString(context.contentResolver, KEY_ALWAYS_ON_APP)
    }.getOrNull()?.let { it.trim() == context.packageName }

    /** Always-on включён именно для Nova и в режиме блокировки трафика. */
    fun isLockdown(context: Context): Boolean = isNovaAlwaysOn(context) && runCatching {
        Settings.Secure.getInt(context.contentResolver, KEY_LOCKDOWN, 0) != 0
    }.getOrDefault(false)

    /** Открывает системный экран VPN, где включается «Постоянный VPN». */
    fun openSettings(context: Context): Boolean = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_VPN_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.isSuccess
}
