package com.example.nova

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * Фоновое обновление подписки VLESS.
 *
 * Без него список профилей замерзал на момент импорта: провайдеры ротируют ключи
 * постоянно, и через неделю вся подписка оказывалась мёртвой — перебор честно
 * проходил её целиком и не находил ни одного живого узла.
 */
class VlessSubscriptionWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : Worker(appContext, workerParams) {

    override fun doWork(): Result {
        LogManager.setAppContext(applicationContext)
        val url = ClientData(applicationContext).getVlessSubscription()?.url.orEmpty()
        // Своя подписка и публичные источники обновляются одним проходом.
        var userOk = true
        if (url.isNotBlank()) {
            userOk = VlessSubscriptionManager.refresh(applicationContext, url) !is
                VlessSubscriptionManager.Outcome.Failed
        }
        if (PublicSubscriptions.isActivated(applicationContext)) {
            PublicSubscriptions.sync(applicationContext)
        }
        return if (userOk) {
            Result.success()
        } else {
            // Неудача сети — обычное дело в фоне: повторим по расписанию WorkManager,
            // а не с нуля при следующем запуске приложения.
            Result.retry()
        }
    }
}
