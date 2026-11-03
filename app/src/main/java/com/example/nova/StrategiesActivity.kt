package com.example.nova

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * «Стратегии»: пока аналитика, а не обучение.
 *
 * Источник — `status.json` роутера: успехи, ошибки и EWMA по слотам. Здесь они
 * превращаются в понятный рейтинг кандидатов. Томпсон/CUSUM появятся, когда
 * будет чем их кормить (несколько сетей и история), но экран уже честный:
 * показывает только измеренное.
 */
class StrategiesActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            render()
            handler.postDelayed(this, 3_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_strategies)
        container = findViewById(R.id.strategy_container)
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        render()
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 3_000L)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun render() {
        container.removeAllViews()
        val clientData = ClientData(this)
        val state = clientData.getServiceState()
        val backend = clientData.getServiceBackend().ifBlank { "—" }
        container.addView(
            card(
                title = "ТЕКУЩИЙ СЕАНС",
                lines = listOf(
                    "Состояние: $state",
                    "Транспорт: $backend",
                ),
            ),
        )

        val stats = RouterStatus.read(RouterProcess.statusFile(this))
        if (stats.isEmpty()) {
            container.addView(
                card(
                    title = "КАНДИДАТЫ",
                    lines = listOf(
                        "Нет измерений. Включите «Умную маршрутизацию» в «Маршрутах» " +
                            "и поработайте в сети — статистика появится здесь.",
                    ),
                ),
            )
            return
        }

        val ranked = stats.entries.sortedByDescending { (_, stat) -> score(stat) }
        container.addView(
            card(
                title = "КАНДИДАТЫ",
                lines = ranked.mapIndexed { index, (name, stat) ->
                    val percent = if (stat.attempts > 0) {
                        (stat.successes * 100 / stat.attempts)
                    } else {
                        0
                    }
                    "#${index + 1} $name · $percent% · ${stat.ewmaMs.toInt()} мс " +
                        "(ok ${stat.successes}/${stat.attempts})"
                },
            ),
        )

        container.addView(
            card(
                title = "СЛОТЫ",
                lines = stats.map { (name, stat) ->
                    "$name: попыток ${stat.attempts}, ошибок ${stat.failures}, " +
                        "EWMA ${stat.ewmaMs.toInt()} мс"
                },
            ),
        )

        val routes = RouterStatus.readRoutes(RouterProcess.statusFile(this))
        if (routes.isNotEmpty()) {
            container.addView(
                card(
                    title = "ТРАФИК ПО ПРАВИЛАМ",
                    lines = routes.entries
                        .sortedByDescending { it.value }
                        .map { (name, count) -> "$name: $count" },
                ),
            )
        }
    }

    /** Простая оценка: доля успехов важнее скорости, задержка штрафуется. */
    private fun score(stat: SlotStat): Double {
        if (stat.attempts <= 0) return 0.0
        val rate = stat.successes.toDouble() / stat.attempts
        return rate * 1000.0 - stat.ewmaMs
    }

    private fun card(title: String, lines: List<String>): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundColor(0xFF121A14.toInt())
        }
        card.addView(TextView(this).apply {
            text = title
            setTextColor(0xFF7FA98C.toInt())
            textSize = 11f
            letterSpacing = 0.18f
        })
        for (line in lines) {
            card.addView(TextView(this).apply {
                text = line
                setTextColor(0xFFDFFFE7.toInt())
                textSize = 14f
                setPadding(0, dp(6), 0, 0)
            })
        }
        card.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(10) }
        return card
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
