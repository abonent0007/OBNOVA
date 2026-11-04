package com.example.nova

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Каркас новых экранов parity-слоя (Маршруты, Слоты, Стратегии, Диагностика).
 *
 * Пока это заглушка с честным текстом о том, что здесь появится; экраны будут
 * заменяться по одному, чтобы не ломать главный экран и настройки.
 */
class ParityScreenActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_parity_screen)

        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val body = intent.getStringExtra(EXTRA_BODY).orEmpty()
        findViewById<TextView>(R.id.tv_title).text = title
        findViewById<TextView>(R.id.tv_body).text = body
        findViewById<android.view.View>(R.id.btn_back).setOnClickListener { finish() }
    }

    companion object {
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_BODY = "extra_body"

        const val SCREEN_ROUTES = "routes"
        const val SCREEN_SLOTS = "slots"
        const val SCREEN_STRATEGY = "strategy"
        const val SCREEN_DIAGNOSTICS = "diagnostics"
        const val SCREEN_LOGS = "logs"

        fun intentFor(
            context: android.content.Context,
            screen: String,
        ): android.content.Intent {
            val (title, body) = when (screen) {
                SCREEN_ROUTES -> "Маршруты" to
                    "Здесь появится редактор маршрутов: RU, EU, YouTube, Telegram, " +
                        "AI и DIRECT — каждый со своим слотом, как в PAC PC."
                SCREEN_SLOTS -> "Слоты" to
                    "Здесь будут карточки PRIMARY, SECONDARY, RELAY и DIRECT: " +
                        "страна выхода, RTT, здоровье и кнопки проверки."
                SCREEN_STRATEGY -> "Стратегии" to
                    "Здесь появится обучение: кандидаты, Томпсон, CUSUM и ручное " +
                        "подтверждение выбора."
                SCREEN_DIAGNOSTICS -> "Диагностика" to
                    "Здесь будет снимок состояния, проверка утечек и экспорт логов."
                SCREEN_LOGS -> "Журналы" to
                    "Здесь будет журнал решений маршрутизации и событий слотов."
                else -> "Nova" to ""
            }
            return android.content.Intent(context, ParityScreenActivity::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_BODY, body)
        }
    }
}
