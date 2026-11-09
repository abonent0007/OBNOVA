package com.example.nova

import android.content.Context

/**
 * Единое обновление профилей и публичных подписок.
 *
 * Зачем отдельный модуль. В приложении есть личная подписка, четыре публичных
 * источника и свои WARP-профили; обновление одного не должно стирать остальные.
 * Этот объект собирает публичные источники в один проход и возвращает сводку,
 * которую показывает кнопка «Обновить профили и подписки».
 *
 * Ключевое правило: источники **дополняют** друг друга. `PublicSubscriptions`
 * хранит состав каждого URL отдельно, поэтому обновление одного не удаляет
 * профили другого и не трогает встроенные семена.
 */
object ProfileUpdateCoordinator {

    data class Result(
        val okSources: Int,
        val failedSources: Int,
        val added: Int,
        val removed: Int,
        val total: Int,
        val message: String,
    ) {
        val ok: Boolean get() = okSources > 0
    }

    /**
     * Итог оборванного прогона. Единая точка, чтобы экран не собирал
     * «пустой» результат вручную и не оставлял кнопку в состоянии «идёт».
     */
    fun failure(error: Throwable?): Result = Result(
        okSources = 0,
        failedSources = ExternalSources.PUBLIC_VLESS_SOURCES.size,
        added = 0,
        removed = 0,
        total = 0,
        message = "Профили не обновились: ${error?.message ?: "неизвестная ошибка"}",
    )

    /**
     * Обновляет все публичные подписки. Вызывать с IO-потока: сеть и запись.
     *
     * @param progress человекочитаемая фаза для строки состояния.
     */
    fun updateAll(
        context: Context,
        progress: ((String) -> Unit)? = null,
    ): Result {
        progress?.invoke("Публичные подписки: ${ExternalSources.PUBLIC_VLESS_SOURCES.size} источников…")
        val sync = PublicSubscriptions.sync(
            context = context,
            force = false,
            progress = { kept, chars ->
                progress?.invoke("Публичные подписки: $kept профилей, $chars символов…")
            },
        )
        val message = if (sync.okSources > 0) {
            "Профили: +${sync.added} / −${sync.removed}, всего ${sync.total}; " +
                "источников ${sync.okSources}/${ExternalSources.PUBLIC_VLESS_SOURCES.size}"
        } else {
            "Профили не обновились: ${sync.message}"
        }
        LogManager.log("Обновление профилей и подписок: $message")
        return Result(
            okSources = sync.okSources,
            failedSources = sync.failedSources,
            added = sync.added,
            removed = sync.removed,
            total = sync.total,
            message = message,
        )
    }
}
