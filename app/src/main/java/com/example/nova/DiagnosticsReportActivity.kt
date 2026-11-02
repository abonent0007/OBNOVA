package com.example.nova

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Диагностика: снимок состояния, который уже умеет собирать DiagnosticSnapshot.
 *
 * Отдельный экран нужен, чтобы не включать диагностическую сборку: отчёт
 * собирается на месте, без секретов (их вычищает сам DiagnosticSnapshot).
 */
class DiagnosticsReportActivity : AppCompatActivity() {

    private lateinit var reportView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostics_report)
        reportView = findViewById(R.id.tv_report)
        findViewById<android.view.View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<android.view.View>(R.id.btn_copy).setOnClickListener { copyReport() }
        render()
    }

    private fun render() {
        reportView.text = runCatching {
            DiagnosticSnapshot.buildReport(this, maxLogChars = 20_000)
        }.getOrElse { error ->
            "Не удалось собрать отчёт: ${error.javaClass.simpleName}: ${error.message}"
        }
    }

    private fun copyReport() {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("OBNOVA diagnostics", reportView.text))
        Toast.makeText(this, "Отчёт скопирован", Toast.LENGTH_SHORT).show()
    }
}
