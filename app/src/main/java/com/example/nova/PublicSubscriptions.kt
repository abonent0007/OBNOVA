package com.example.nova

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Публичные подписки VLESS из PC-версии: четыре источника, один общий список
 * профилей.
 *
 * Зачем отдельно от [VlessSubscriptionManager]. Пользовательская подписка — одна
 * и хранит один адрес; публичных источников четыре, и у каждого свои ETag,
 * Last-Modified и состав. Смешивать их в одно состояние значило бы потерять,
 * какой источник что принёс, и при смене одного адреса удалить чужие профили.
 *
 * Правила те же, что у одиночной подписки: удаляются только профили, которые
 * были в этом источнике и пропали из него; профили пользовательской подписки и
 * добавленные вручную не трогаются. Источник, ответивший 304 или ошибкой, свои
 * профили сохраняет.
 */
object PublicSubscriptions {

    /** Сколько профилей брать с одного источника. */
    private const val SOURCE_LIMIT = 120

    private const val FILE_NAME = "public_subscriptions.json"

    data class SourceState(
        val url: String,
        val etag: String = "",
        val lastModified: String = "",
        val identities: List<String> = emptyList(),
    )

    data class State(
        val sources: List<SourceState> = emptyList(),
        val lastCheckedAt: Long = 0L,
    )

    data class Result(
        val added: Int,
        val removed: Int,
        val total: Int,
        val okSources: Int,
        val failedSources: Int,
        val message: String,
    )

    /** Пользователь уже включал публичные подписки — только тогда их обновляем. */
    fun isActivated(context: Context): Boolean = stateFile(context).baseFile.exists()

    /**
     * Опрашивает все источники. Вызывать с IO-потока: сеть и чтение файлов.
     *
     * @param force игнорировать ETag/Last-Modified.
     */
    @Synchronized
    fun sync(
        context: Context,
        force: Boolean = false,
        progress: VlessSubscriptionFetcher.Progress? = null,
    ): Result {
        val app = context.applicationContext
        val previous = read(app)
        val previousByUrl = previous.sources.associateBy { it.url }
        val freshLinks = ArrayList<String>()
        val removalIdentities = HashSet<String>()
        val updated = LinkedHashMap<String, SourceState>()
        val errors = ArrayList<String>()
        var okSources = 0
        var failedSources = 0

        for (source in ExternalSources.PUBLIC_VLESS_SOURCES) {
            val old = previousByUrl[source.url]
            val validators = if (force || old == null) {
                VlessSubscriptionFetcher.Validators()
            } else {
                VlessSubscriptionFetcher.Validators(old.etag, old.lastModified)
            }
            var outcome: VlessSubscriptionFetcher.Result? = null
            var lastFailure: VlessSubscriptionFetcher.Result.Failed? = null
            for (mirror in ExternalSources.subscriptionMirrors(source.url)) {
                val fetched = VlessSubscriptionFetcher.fetch(
                    url = mirror,
                    validators = validators,
                    limit = SOURCE_LIMIT,
                    progress = progress,
                )
                if (fetched is VlessSubscriptionFetcher.Result.Failed) {
                    lastFailure = fetched
                    continue
                }
                outcome = fetched
                if (mirror != source.url) {
                    LogManager.log("Публичная подписка ${source.name}: зеркало $mirror")
                }
                break
            }
            when (val result = outcome ?: lastFailure
                ?: VlessSubscriptionFetcher.Result.Failed(0, "нет ответа")) {
                is VlessSubscriptionFetcher.Result.NotModified -> {
                    okSources++
                    updated[source.url] = (old ?: SourceState(source.url)).copy(
                        etag = result.validators.etag.ifBlank { old?.etag.orEmpty() },
                        lastModified = result.validators.lastModified
                            .ifBlank { old?.lastModified.orEmpty() },
                    )
                }

                is VlessSubscriptionFetcher.Result.Failed -> {
                    failedSources++
                    errors += "${source.name}: HTTP ${result.httpCode}"
                    old?.let { updated[source.url] = it }
                }

                is VlessSubscriptionFetcher.Result.Ok -> {
                    if (result.configs.isEmpty()) {
                        // Пустой ответ не повод удалять живые профили источника.
                        failedSources++
                        errors += "${source.name}: пустой ответ"
                        old?.let { updated[source.url] = it }
                        continue
                    }
                    okSources++
                    val links = result.configs.map { it.toUri() }
                    freshLinks += links
                    old?.identities?.let(removalIdentities::addAll)
                    updated[source.url] = SourceState(
                        url = source.url,
                        etag = result.validators.etag,
                        lastModified = result.validators.lastModified,
                        identities = result.configs.map { it.identity },
                    )
                }
            }
        }

        val clientData = ClientData(app)
        var added = 0
        var removed = 0
        var total = clientData.getVlessProfileLinks().size
        if (freshLinks.isNotEmpty()) {
            val sync = clientData.syncVlessSubscriptionProfiles(
                freshLinks = freshLinks,
                previousIdentities = removalIdentities,
            )
            added = sync.added
            removed = sync.removed
            total = sync.total
        }

        val message = buildString {
            append("источников: ${ExternalSources.PUBLIC_VLESS_SOURCES.size}, ок: $okSources")
            if (failedSources > 0) {
                append(", ошибок: $failedSources")
                if (errors.isNotEmpty()) append(" (${errors.take(2).joinToString("; ")})")
            }
            append("; добавлено $added, удалено $removed, всего $total")
        }
        write(app, State(updated.values.toList(), System.currentTimeMillis()))
        LogManager.log("Публичные подписки VLESS: $message")
        return Result(added, removed, total, okSources, failedSources, message)
    }

    private fun stateFile(context: Context): AtomicFile =
        AtomicFile(File(context.applicationContext.filesDir, FILE_NAME))

    private fun read(context: Context): State {
        val raw = runCatching {
            stateFile(context).baseFile
                .takeIf { it.exists() }
                ?.readText(Charsets.UTF_8)
        }.getOrNull().orEmpty()
        if (raw.isBlank()) return State()
        return runCatching {
            val root = JSONObject(raw)
            val array = root.optJSONArray("sources") ?: JSONArray()
            val sources = buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val url = item.optString("url").trim()
                    if (url.isBlank()) continue
                    val identities = item.optJSONArray("identities")?.let { ids ->
                        (0 until ids.length()).mapNotNull { ids.optString(it).trim().takeIf { value -> value.isNotBlank() } }
                    }.orEmpty()
                    add(
                        SourceState(
                            url = url,
                            etag = item.optString("etag"),
                            lastModified = item.optString("lastModified"),
                            identities = identities,
                        )
                    )
                }
            }
            State(sources = sources, lastCheckedAt = root.optLong("lastCheckedAt", 0L))
        }.getOrDefault(State())
    }

    private fun write(context: Context, state: State) {
        val payload = JSONObject()
            .put("lastCheckedAt", state.lastCheckedAt)
            .put("sources", JSONArray().also { array ->
                state.sources.forEach { source ->
                    array.put(
                        JSONObject()
                            .put("url", source.url)
                            .put("etag", source.etag)
                            .put("lastModified", source.lastModified)
                            .put("identities", JSONArray(source.identities)),
                    )
                }
            })
        runCatching {
            val file = stateFile(context)
            val stream = file.startWrite()
            try {
                stream.write(payload.toString().toByteArray(Charsets.UTF_8))
                file.finishWrite(stream)
            } catch (error: Throwable) {
                file.failWrite(stream)
                throw error
            }
        }
    }
}
