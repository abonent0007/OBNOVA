package com.example.nova

import android.os.Build
import java.util.concurrent.TimeUnit

/**
 * Совместимые обёртки над `Process` для minSdk 24.
 *
 * `isAlive`, `waitFor(timeout, unit)` и `destroyForcibly` появились в API 26.
 * На Android 7.x прямой вызов даёт `NoSuchMethodError`; эти обёртки
 * повторяют уже проверенный подход `RouterProcess.aliveCompat`.
 */
object ProcessCompat {

    fun isAlive(process: Process): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            process.isAlive
        } else {
            runCatching { process.exitValue() }.isFailure
        }

    fun waitFor(process: Process, timeoutMs: Long): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (runCatching { process.exitValue() }.isSuccess) return true
            runCatching { Thread.sleep(50L) }
        }
        return false
    }

    fun destroyForcibly(process: Process) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            process.destroyForcibly()
        } else {
            process.destroy()
        }
    }
}
