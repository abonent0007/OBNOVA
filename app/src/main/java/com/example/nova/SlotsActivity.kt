package com.example.nova

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Экран «Слоты»: показывает, чем сейчас является каждый слот.
 *
 * Источники — только то, что приложение уже знает: состояние службы, backend,
 * endpoint Opera, режим раздельного туннелирования. Здоровье/RTT появятся,
 * когда `nova-router` начнёт публиковать статистику.
 */
class SlotsActivity : AppCompatActivity() {

    private lateinit var clientData: ClientData
    private lateinit var container: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val refreshTick = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            render()
            handler.postDelayed(this, 3_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_slots)
        clientData = ClientData(this)
        container = findViewById(R.id.slots_container)
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        val probeStatus = findViewById<TextView>(R.id.tv_probe_status)
        findViewById<View>(R.id.btn_probe_slots).setOnClickListener {
            val file = RouterProcess.statusFile(this)
            val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < 10_000L
            val port = RouterStatus.readPort(file)
            if (!fresh || port !in 1..65535) {
                probeStatus.text = "Проверка: роутер не запущен — включите «Умную маршрутизацию»"
                return@setOnClickListener
            }
            probeStatus.text = "Проверка: идёт запрос через роутер…"
            Thread({
                val result = SlotProbe.run(port)
                runOnUiThread {
                    probeStatus.text = if (result.ok) {
                        "Проверка: OK · ${result.latencyMs} мс · ${result.detail}"
                    } else {
                        "Проверка: ошибка · ${result.detail}"
                    }
                }
            }, "NovaSlotProbe").apply { isDaemon = true }.start()
        }
    }

    override fun onResume() {
        super.onResume()
        render()
        handler.removeCallbacks(refreshTick)
        handler.postDelayed(refreshTick, 3_000L)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshTick)
        super.onPause()
    }

    private fun render() {
        container.removeAllViews()
        val state = clientData.getServiceState()
        val backend = clientData.getServiceBackend().ifBlank { "—" }
        val connected = state == NovaVpnService.STATE_CONNECTED
        val stats = RouterStatus.read(RouterProcess.statusFile(this))

        val primaryStat = stats["primary"]
        container.addView(
            card(
                title = "PRIMARY",
                status = when {
                    primaryStat != null && primaryStat.successes > 0 ->
                        "жив · $backend · ${primaryStat.ewmaMs.toInt()} мс"
                    connected -> "жив · $backend"
                    else -> "не подключён"
                },
                detail = when {
                    primaryStat != null && primaryStat.attempts > 0 ->
                        "успешно ${primaryStat.successes} из ${primaryStat.attempts}, " +
                            "ошибок ${primaryStat.failures}"
                    backend.startsWith("WARP") -> "Cloudflare WARP"
                    backend.startsWith("MASQUE") -> "Cloudflare MASQUE"
                    backend.startsWith("VLESS") -> "импортированный VLESS"
                    backend.startsWith("DESYNC") -> "ZAPRET (локальный)"
                    backend.startsWith("OPERA") -> "Opera"
                    backend.startsWith("MULTI") -> "nova-router: DESYNC + DIRECT"
                    else -> "транспорт не выбран"
                },
                accent = connected,
            ),
        )

        val operaEndpoint = OperaProxyManager.getCurrentEndpoint()
        val secondaryStat = stats["secondary"]
        container.addView(
            card(
                title = "SECONDARY",
                status = when {
                    secondaryStat != null && secondaryStat.successes > 0 ->
                        "Opera · ${secondaryStat.ewmaMs.toInt()} мс"
                    !operaEndpoint.isNullOrBlank() -> "Opera готов"
                    else -> "не запущен"
                },
                detail = when {
                    secondaryStat != null && secondaryStat.attempts > 0 ->
                        "успешно ${secondaryStat.successes} из ${secondaryStat.attempts}, " +
                            "ошибок ${secondaryStat.failures}"
                    !operaEndpoint.isNullOrBlank() -> operaEndpoint
                    else -> "Opera · регион ${clientData.getExitRegionPreference()}"
                },
                accent = (secondaryStat?.successes ?: 0) > 0 || !operaEndpoint.isNullOrBlank(),
            ),
        )

        container.addView(
            card(
                title = "RELAY",
                status = "подготовка",
                detail = "Telegram-релей (WSS/CF) входит в v2 — процесс и маршрут появятся отдельным шагом.",
                accent = false,
            ),
        )

        val splitMode = clientData.getSplitMode()
        val directDetail = when (splitMode) {
            1 -> "через VPN только выбранные приложения (${clientData.getSplitApps().size})"
            2 -> "мимо VPN: выбранные (${clientData.getSplitApps().size}) и прямые"
            else -> "весь трафик через VPN, кроме прямых приложений"
        }
        container.addView(
            card(
                title = "DIRECT",
                status = "мимо туннеля",
                detail = directDetail,
                accent = true,
            ),
        )
    }

    private fun card(title: String, status: String, detail: String, accent: Boolean): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundColor(0xFF121A14.toInt())
        }
        val titleView = TextView(this).apply {
            text = title
            setTextColor(0xFF7FA98C.toInt())
            textSize = 11f
            letterSpacing = 0.18f
        }
        val statusView = TextView(this).apply {
            text = status
            setTextColor(if (accent) 0xFF50C878.toInt() else 0xFFE0A458.toInt())
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(2))
        }
        val detailView = TextView(this).apply {
            text = detail
            setTextColor(0xFFDFFFE7.toInt())
            textSize = 12f
        }
        card.addView(titleView)
        card.addView(statusView)
        card.addView(detailView)
        card.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(10) }
        card.gravity = Gravity.START
        return card
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
