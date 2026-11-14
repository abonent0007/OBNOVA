package com.example.nova

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

/**
 * Экран «Маршруты»: сервисные категории и их слот, плюс переключатели
 * политики. Значения хранятся в RoutingStore и читаются RoutingPolicy,
 * когда сервис переходит на мультислот.
 */
class RoutesActivity : AppCompatActivity() {

    private lateinit var routing: RoutingStore
    private lateinit var container: LinearLayout
    private lateinit var switchDefaultVpn: SwitchCompat
    private lateinit var switchKillswitch: SwitchCompat
    private lateinit var switchSmart: SwitchCompat
    private var suppressCallbacks = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_routes)
        routing = RoutingStore(this)
        routing.migratePolicyModeIfNeeded()
        container = findViewById(R.id.routes_container)
        switchDefaultVpn = findViewById<SwitchCompat>(R.id.sw_default_vpn)
        switchKillswitch = findViewById<SwitchCompat>(R.id.sw_killswitch)
        switchSmart = findViewById<SwitchCompat>(R.id.sw_smart)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        suppressCallbacks = true
        switchDefaultVpn.isChecked = routing.isForeignDefaultVpn()
        switchKillswitch.isChecked = routing.isKillSwitch()
        switchSmart.isChecked = routing.isSmartRoutingEnabled()
        // foreign-default пока не участвует в RouterConfig: не показываем его
        // как работающую настройку.
        switchDefaultVpn.isEnabled = false
        switchDefaultVpn.alpha = 0.45f
        findViewById<TextView>(R.id.tv_mode_title)?.apply {
            text = "Весь остальной трафик - через VPN (пока не подключено)"
        }
        suppressCallbacks = false

        switchDefaultVpn.setOnCheckedChangeListener { _, checked ->
            if (!suppressCallbacks) routing.setForeignDefaultVpn(checked)
        }
        switchKillswitch.setOnCheckedChangeListener { _, checked ->
            if (!suppressCallbacks) routing.setKillSwitch(checked)
        }
        switchSmart.setOnCheckedChangeListener { _, checked ->
            LogManager.log("ROUTES: smart toggle checked=$checked suppress=$suppressCallbacks")
            if (!suppressCallbacks) {
                routing.setSmartRoutingEnabled(checked)
                LogManager.log("ROUTES: smart saved=${routing.isSmartRoutingEnabled()}")
            }
        }

        renderRows()
    }

    private fun renderRows() {
        container.removeAllViews()
        container.addView(policyCard())
        for (service in RouteSettings.SERVICES) {
            container.addView(buildRow(service))
        }
        container.addView(appsCard())
        container.addView(listsCard())
    }

    /**
     * Состояние политики выхода и единственная точка включения Strict.
     *
     * Strict не показывается активным по одной лишь настройке: нужен живой
     * MULTI (state=CONNECTED, backend=MULTI). Иначе экран честно пишет, что
     * защита не подтверждена, — иначе пользователь считал бы Strict работающим
     * поверх WARP или остановленного туннеля.
     */
    private fun policyCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundColor(0xFF121A14.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
        }
        card.addView(TextView(this).apply {
            text = "ПОЛИТИКА ВЫХОДА"
            setTextColor(0xFF7FA98C.toInt())
            textSize = 11f
            letterSpacing = 0.18f
        })

        val mode = routing.getRoutePolicyMode()
        val client = ClientData(this)
        val state = client.getServiceState()
        val backend = client.getServiceBackend()
        // Смена режима сама по себе не делает Strict активным: нужен CONNECTED,
        // записанный ПОСЛЕ смены режима, и backend MULTI.
        val modeSetAt = routing.getRoutePolicyModeSetAt()
        val stateUpdatedAt = client.getServiceStateUpdatedAt()
        val appliedAfterModeChange = modeSetAt <= 0L || stateUpdatedAt >= modeSetAt
        val strictActive = mode.isStrictFullTunnel &&
            state == NovaVpnService.STATE_CONNECTED &&
            backend == NovaVpnService.BACKEND_MULTI &&
            appliedAfterModeChange

        val scope = TextView(this).apply {
            text = when {
                strictActive ->
                    "Strict Full Tunnel активен.\n\n" +
                        "Сетевой трафик приложений устройства направляется через MULTI. " +
                        "Служебный пакет Nova (UID 10997), необходимый для работы VPN-движка, " +
                        "исключён из VPN и может использовать системную сеть. " +
                        "Защита tethering не подтверждена."
                mode.isStrictFullTunnel ->
                    "STRICT выбран, но НЕ активен: MULTI не подтверждён после смены " +
                        "режима (state=$state, backend=$backend)."
                mode == RoutePolicyMode.LIMITED -> {
                    val split = client.getSplitMode()
                    val splitLabel = when (split) {
                        1 -> "только выбранные приложения"
                        2 -> "все, кроме выбранных"
                        else -> "все приложения"
                    }
                    "LIMITED — Kill Switch; split-tunnel: $splitLabel; " +
                        "DirectAppsPolicy действует. Это не Strict."
                }
                else -> "Обычный режим — DIRECT разрешён."
            }
            setTextColor(0xFFDFFFE7.toInt())
            textSize = 13f
            setPadding(0, dp(6), 0, 0)
        }
        card.addView(scope)

        val strictSwitch = SwitchCompat(this).apply {
            text = "Strict Full Tunnel (только MULTI)"
            textSize = 13f
            isChecked = mode.isStrictFullTunnel
        }
        var ignore = false
        strictSwitch.setOnCheckedChangeListener { button, checked ->
            if (ignore) return@setOnCheckedChangeListener
            if (!checked) {
                routing.exitStrictFullTunnel()
                LogManager.log("ROUTES: Strict Full Tunnel выключен из UI")
                renderRows()
                return@setOnCheckedChangeListener
            }
            AlertDialog.Builder(this)
                .setTitle("Включить Strict Full Tunnel?")
                .setMessage(
                    "Весь трафик приложений устройства пойдёт через MULTI. " +
                        "Банки, госуслуги и приложения операторов тоже будут работать " +
                        "через VPN и могут перестать открываться.\n\n" +
                        "Split-tunnel, DirectAppsPolicy, DNS-обходы и LAN-bypass " +
                        "временно отключаются (настройки сохраняются). Доступен только MULTI: " +
                        "WARP/OPERA/VLESS будут отклонены.\n\n" +
                        "Служебный пакет Nova (UID 10997) остаётся вне VPN — это нужно для " +
                        "работы VPN-движка; tethering не гарантируется.\n\n" +
                        "Strict станет активным только после успешного запуска MULTI."
                )
                .setPositiveButton("Включить Strict") { _, _ ->
                    routing.enterStrictFullTunnel()
                    LogManager.log("ROUTES: Strict Full Tunnel включён из UI")
                    Toast.makeText(
                        this,
                        "Strict сохранён. Переподключите VPN — MULTI применит политику.",
                        Toast.LENGTH_LONG,
                    ).show()
                    renderRows()
                }
                .setNegativeButton("Отмена") { _, _ ->
                    ignore = true
                    button.isChecked = false
                    ignore = false
                }
                .show()
        }
        card.addView(strictSwitch)

        card.addView(TextView(this).apply {
            text = "Strict: банки/госуслуги/операторы идут через VPN; " +
                "новые приложения попадают в full tunnel без обновления списков."
            setTextColor(0xFF7FA98C.toInt())
            textSize = 11f
            setPadding(0, dp(4), 0, 0)
        })

        val serviceActive = state == NovaVpnService.STATE_CONNECTED ||
            state == NovaVpnService.STATE_CONNECTING
        val alwaysOn: Boolean? = if (serviceActive) {
            client.getServiceAlwaysOn() ?: AlwaysOnVpn.readState(this)
        } else {
            AlwaysOnVpn.readState(this)
        }
        val lockdown: Boolean? = when {
            serviceActive -> client.getServiceLockdown()
                ?: if (alwaysOn == true) AlwaysOnVpn.isLockdown(this) else null
            alwaysOn == false -> false
            alwaysOn == true -> AlwaysOnVpn.isLockdown(this)
            else -> null
        }
        card.addView(TextView(this).apply {
            val prefix = if (state == NovaVpnService.STATE_CONNECTED) {
                "Lockdown (если VPN остановится)"
            } else {
                "VPN STOPPED"
            }
            text = when (lockdown) {
                true -> "$prefix: Lockdown ВКЛ — система блокирует трафик без VPN."
                false -> "$prefix: Lockdown ВЫКЛ — защита при остановке VPN не гарантируется."
                null -> "$prefix: Lockdown НЕИЗВЕСТНО — состояние не подтверждено."
            } + if (alwaysOn == null) " Always-on: неизвестно." else ""
            setTextColor(0xFF7FA98C.toInt())
            textSize = 11f
            setPadding(0, dp(6), 0, 0)
        })
        return card
    }

    /**
     * Списки маршрутов: кнопка тянет стоковый фид PC-форка
     * (`confeden/nova_updates/nova_pc`) и подменяет встроенные копии.
     */
    private fun listsCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundColor(0xFF121A14.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
        }
        card.addView(TextView(this).apply {
            text = "Списки маршрутов"
            setTextColor(0xFF7FA98C.toInt())
            textSize = 11f
            letterSpacing = 0.18f
        })
        val status = TextView(this).apply {
            val last = ListSync.lastUpdatedAt(this@RoutesActivity)
            text = if (last > 0) {
                "Обновлено: " + android.text.format.DateFormat.format("dd.MM.yyyy HH:mm", last)
            } else {
                "Встроенные списки (сеть ещё не обновляла)"
            }
            setTextColor(0xFFDFFFE7.toInt())
            textSize = 13f
            setPadding(0, dp(4), 0, 0)
        }
        card.addView(status)
        val button = TextView(this).apply {
            text = "Обновить списки"
            textSize = 13f
            setTextColor(0xFF50C878.toInt())
            setPadding(0, dp(8), 0, 0)
            isClickable = true
            isFocusable = true
        }
        button.setOnClickListener {
            if (!button.isEnabled) return@setOnClickListener
            button.isEnabled = false
            status.text = "Загрузка списков…"
            Thread({
                val result = ListSync.sync(this)
                runOnUiThread {
                    status.text = if (result.ok) {
                        "Обновлено: " + android.text.format.DateFormat.format(
                            "dd.MM.yyyy HH:mm",
                            result.updatedAt,
                        ) + " · " + result.message
                    } else {
                        "Не удалось: " + result.message
                    }
                    button.isEnabled = true
                    LogManager.log("LISTS: ${status.text}")
                    if (result.ok) requestRouterReload()
                }
            }, "NovaListSync").apply { isDaemon = true }.start()
        }
        card.addView(button)

        val subscription = ClientData(this@RoutesActivity).getVlessSubscription()
        if (subscription != null) {
            val subStatus = TextView(this).apply {
                text = "Подписка VLESS: ${subscription.url}"
                setTextColor(0xFF7FA98C.toInt())
                textSize = 11f
                setPadding(0, dp(8), 0, 0)
            }
            card.addView(subStatus)
            val subButton = TextView(this).apply {
                text = "Обновить подписку"
                textSize = 13f
                setTextColor(0xFF50C878.toInt())
                setPadding(0, dp(6), 0, 0)
                isClickable = true
                isFocusable = true
            }
            subButton.setOnClickListener {
                if (!subButton.isEnabled) return@setOnClickListener
                subButton.isEnabled = false
                subStatus.text = "Обновляем подписку…"
                Thread({
                    val outcome = VlessSubscriptionManager.refresh(
                        this,
                        subscription.url,
                        force = true,
                    )
                    runOnUiThread {
                        subStatus.text = when (outcome) {
                            is VlessSubscriptionManager.Outcome.Updated ->
                                "Подписка: +${outcome.added} / −${outcome.removed}, всего ${outcome.total}"
                            is VlessSubscriptionManager.Outcome.Unchanged ->
                                "Подписка не изменилась (${outcome.total})"
                            VlessSubscriptionManager.Outcome.NoVlessFound ->
                                "В подписке нет VLESS-ссылок"
                            is VlessSubscriptionManager.Outcome.Failed ->
                                "Ошибка подписки: ${outcome.message}"
                        }
                        subButton.isEnabled = true
                        LogManager.log("VLESS: ${subStatus.text}")
                    }
                }, "NovaSubscriptionSync").apply { isDaemon = true }.start()
            }
            card.addView(subButton)
        }

        // Публичные подписки из стокового PC-форка: шесть источников
        // (`profiles/subscriptions.json`), как в PC. Кнопка нужна, когда своей
        // подписки ещё нет: без профилей VLESS MULTI остаётся на DESYNC и не
        // может обойти блокировку YouTube.
        val publicStatus = TextView(this).apply {
            text = "Публичные подписки VLESS (6 источников, сток PC, узлы нестабильны)"
            setTextColor(0xFF7FA98C.toInt())
            textSize = 11f
            setPadding(0, dp(10), 0, 0)
        }
        card.addView(publicStatus)
        val publicButton = TextView(this).apply {
            text = "Загрузить публичные подписки"
            textSize = 13f
            setTextColor(0xFF50C878.toInt())
            setPadding(0, dp(6), 0, 0)
            isClickable = true
            isFocusable = true
        }
        publicButton.setOnClickListener {
            if (!publicButton.isEnabled) return@setOnClickListener
            publicButton.isEnabled = false
            publicStatus.text = "Загружаем публичные подписки…"
            Thread({
                val result = PublicSubscriptions.sync(
                    context = this,
                    force = true,
                    progress = { kept, charsRead ->
                        runOnUiThread {
                            if (!isFinishing) {
                                publicStatus.text = "Публичные подписки: $kept профилей, $charsRead символов…"
                            }
                        }
                    },
                )
                runOnUiThread {
                    publicStatus.text = if (result.okSources > 0) {
                        "VLESS: +${result.added} / −${result.removed}, всего ${result.total}. " +
                            "Источников: ${result.okSources}/" +
                            "${ExternalSources.PUBLIC_VLESS_SOURCES.size}. Переподключите VPN."
                    } else {
                        "Ошибка публичных подписок: ${result.message}"
                    }
                    publicButton.isEnabled = true
                    LogManager.log("VLESS: ${publicStatus.text}")
                    if (result.okSources > 0 && result.total > 0) {
                        Toast.makeText(
                            this@RoutesActivity,
                            "Публичные подписки сохранены. Переподключите VPN.",
                            Toast.LENGTH_LONG,
                        ).show()
                        VlessSubscriptionManager.syncSchedule(this@RoutesActivity)
                        // Карточка перестраивается, чтобы появилась кнопка
                        // «Обновить подписку»: до импорта подписки её не было.
                        renderRows()
                    }
                }
            }, "NovaPublicSubscription").apply { isDaemon = true }.start()
        }
        card.addView(publicButton)

        val probeStatus = TextView(this).apply {
            text = "Проверка сервисов через активный MULTI"
            setTextColor(0xFF7FA98C.toInt())
            textSize = 11f
            setPadding(0, dp(10), 0, 0)
        }
        card.addView(probeStatus)
        val probeButton = TextView(this).apply {
            text = "Проверить RU / US / YouTube / Telegram"
            textSize = 13f
            setTextColor(0xFF50C878.toInt())
            setPadding(0, dp(6), 0, 0)
            isClickable = true
            isFocusable = true
        }
        probeButton.setOnClickListener {
            if (!probeButton.isEnabled) return@setOnClickListener
            probeButton.isEnabled = false
            probeStatus.text = "Проверяем через локальный роутер…"
            Thread({
                val results = ServiceProbe.run(this)
                runOnUiThread {
                    probeStatus.text = results.joinToString("\n") { result ->
                        (if (result.ok) "✓ " else "✗ ") + result.name + " — " + result.detail
                    }
                    probeButton.isEnabled = true
                    LogManager.log(
                        "PROBE: " + results.joinToString(" | ") { result ->
                            (if (result.ok) "OK " else "FAIL ") + result.name + " — " + result.detail
                        },
                    )
                }
            }, "NovaServiceProbe").apply { isDaemon = true }.start()
        }
        card.addView(probeButton)

        if (BuildConfig.CF_PROXY_SLOT_ENABLED) {
            val awgStatus = TextView(this).apply {
                text = "AWG SOCKS: " + AwgSocksBridge.status()
                setTextColor(0xFF7FA98C.toInt())
                textSize = 11f
                setPadding(0, dp(10), 0, 0)
            }
            card.addView(awgStatus)
            val awgButton = TextView(this).apply {
                text = "Запустить AWG SOCKS (127.0.0.1:1378)"
                textSize = 13f
                setTextColor(0xFF50C878.toInt())
                setPadding(0, dp(6), 0, 0)
                isClickable = true
                isFocusable = true
            }
            awgButton.setOnClickListener {
                if (!awgButton.isEnabled) return@setOnClickListener
                awgButton.isEnabled = false
                awgStatus.text = "AWG SOCKS: запуск…"
                Thread({
                    val result = AwgSocksBridge.startFromBundled(this)
                    runOnUiThread {
                        awgStatus.text = "AWG SOCKS: $result"
                        LogManager.log("AWG-SOCKS: $result")
                        awgButton.isEnabled = true
                    }
                }, "NovaAwgSocks").apply { isDaemon = true }.start()
            }
            card.addView(awgButton)
            val awgStop = TextView(this).apply {
                text = "Остановить AWG SOCKS"
                textSize = 12f
                setTextColor(0xFF7FA98C.toInt())
                setPadding(0, dp(4), 0, 0)
                isClickable = true
                isFocusable = true
            }
            awgStop.setOnClickListener {
                awgStatus.text = "AWG SOCKS: " + AwgSocksBridge.stop()
            }
            card.addView(awgStop)
        }
        return card
    }

    /**
     * Приложения — не часть SOCKS-политики: Android решает это на уровне TUN
     * (`addAllowedApplication`/`addDisallowedApplication`). Поэтому здесь
     * показываем режим и уводим в существующий выбор приложений, а не дублируем
     * список.
     */
    private fun appsCard(): View {
        val clientData = ClientData(this)
        val mode = clientData.getSplitMode()
        val count = clientData.getSplitApps().size
        val detail = when (mode) {
            1 -> "через VPN только выбранные ($count)"
            2 -> "мимо VPN: выбранные ($count) и прямые"
            else -> "весь трафик через VPN, кроме прямых"
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundColor(0xFF121A14.toInt())
            isClickable = true
            isFocusable = true
        }
        row.addView(TextView(this).apply {
            text = "Приложения"
            setTextColor(0xFF7FA98C.toInt())
            textSize = 11f
            letterSpacing = 0.18f
        })
        row.addView(TextView(this).apply {
            text = detail
            setTextColor(0xFFDFFFE7.toInt())
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(4), 0, 0)
        })

        val modes = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        fun modeButton(label: String, mode: Int): TextView = TextView(this).apply {
            text = label
            textSize = 12f
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setTextColor(if (clientData.getSplitMode() == mode) 0xFF0B0F0C.toInt() else 0xFFDFFFE7.toInt())
            setBackgroundColor(
                if (clientData.getSplitMode() == mode) 0xFF50C878.toInt() else 0xFF1F2E23.toInt()
            )
            isClickable = true
            isFocusable = true
            setOnClickListener {
                clientData.setSplitMode(mode)
                renderRows()
            }
        }
        modes.addView(modeButton("Все приложения", 0))
        modes.addView(
            TextView(this).apply { text = "   "; setTextColor(0x00000000) },
        )
        modes.addView(modeButton("Только выбранные", 1))
        row.addView(modes)
        row.addView(TextView(this).apply {
            text = "Применится после переподключения VPN"
            setTextColor(0xFF7FA98C.toInt())
            textSize = 11f
            setPadding(0, dp(6), 0, 0)
        })
        row.addView(TextView(this).apply {
            text = "Выбрать приложения →"
            setTextColor(0xFF50C878.toInt())
            textSize = 12f
            setPadding(0, dp(6), 0, 0)
        })
        row.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) }
        row.setOnClickListener {
            startActivity(
                Intent(this, SettingsActivity::class.java)
                    .putExtra(SettingsActivity.EXTRA_SCROLL_TO, SettingsActivity.SCROLL_SPLIT_APPS)
            )
        }
        return row
    }

    private fun buildRow(service: RouteSettings.Service): View {
        val overrideClass = RouteSettings.parseOverride(routing.getRouteOverride(service.key))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(0xFF121A14.toInt())
            isClickable = true
            isFocusable = true
        }
        val title = TextView(this).apply {
            text = service.title
            setTextColor(0xFFDFFFE7.toInt())
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val value = TextView(this).apply {
            text = if (overrideClass != null) {
                RouteSettings.label(overrideClass)
            } else {
                "AUTO · " + RouteSettings.chainHint(service.defaultClass)
            }
            setTextColor(if (overrideClass == null) 0xFF7FA98C.toInt() else 0xFF50C878.toInt())
            textSize = 12f
        }
        row.addView(title)
        row.addView(value)
        row.setOnClickListener { showPicker(service) }
        row.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(6) }
        return row
    }

    private fun showPicker(service: RouteSettings.Service) {
        val choices = listOf(
            "AUTO" to null,
            "PRIMARY" to RouteClass.RU,
            "SECONDARY" to RouteClass.EU,
            "DIRECT" to RouteClass.DIRECT,
            "BLOCK" to RouteClass.BLOCK,
        )
        val labels = choices.map { it.first }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(service.title)
            .setItems(labels) { _, which ->
                val target = choices[which].second
                routing.setRouteOverride(service.key, RouteSettings.encodeOverride(target))
                renderRows()
                requestRouterReload()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    /** Применить маршруты на живом MULTI-сеансе, не пересобирая TUN. */
    private fun requestRouterReload() {
        val running = (getSystemService(android.app.ActivityManager::class.java)
            ?.getRunningServices(Int.MAX_VALUE) ?: emptyList())
            .any { it.service?.className == NovaVpnService::class.java.name }
        if (!running) return
        LogManager.log("ROUTES: маршруты изменены — просим роутер перезагрузиться.")
        runCatching {
            startService(
                Intent(this, NovaVpnService::class.java)
                    .setAction(NovaVpnService.ACTION_RELOAD_ROUTER)
            )
        }
    }
}

