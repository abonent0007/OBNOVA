package com.example.nova

import android.content.Context
import java.io.File

/**
 * Загрузка списков маршрутизации из `assets/lists`.
 *
 * Списки — данные, а не код: их можно обновлять отдельно от логики. Снимок
 * кэшируется до изменения файлов: подпись включает размер и время правки,
 * поэтому служба `:vpn` замечает синхронизацию, сделанную в основном процессе,
 * и не работает по устаревшим правилам.
 *
 * Локальный список, в котором не осталось ни одного домена (пустой файл,
 * только комментарии, оборванная загрузка), не подменяет встроенный: иначе
 * одна плохая синхронизация молча выключила бы целую категорию маршрутов.
 */
object ListRepository {

    private const val DIR = "lists"
    private const val ROUTING_DIR = "routing_lists"

    @Volatile
    private var snapshot: RoutingListsSnapshot? = null

    @Volatile
    private var snapshotSignature: String? = null

    fun load(context: Context): RoutingListsSnapshot {
        val app = context.applicationContext
        val signature = signature(app)
        snapshot?.let { cached ->
            if (snapshotSignature == signature) return cached
        }
        synchronized(this) {
            snapshot?.let { cached ->
                if (snapshotSignature == signature) return cached
            }
            val loaded = RoutingListsSnapshot(
                exclude = domains(app, "exclude.txt"),
                second = domains(app, "second.txt"),
                main = domains(app, "main.txt"),
                ai = domains(app, "ai.txt"),
                youtube = domains(app, "youtube.txt"),
                telegram = domains(app, "telegram.txt"),
                whatsapp = domains(app, "whatsapp.txt"),
                discord = domains(app, "discord.txt"),
                cloudflareRanges = ranges(app, "cloudflare_ips.txt"),
                localTlds = tlds(app),
            )
            snapshot = loaded
            snapshotSignature = signature
            return loaded
        }
    }

    fun invalidate() {
        snapshot = null
        snapshotSignature = null
    }

    private fun signature(context: Context): String = ListCacheSignature.of(
        File(context.filesDir, ROUTING_DIR),
        File(File(context.filesDir, ROUTING_DIR), "meta.txt"),
    )

    private fun domains(context: Context, name: String): DomainList =
        domainsFrom(readLocal(context, name), assetLines(context, name))

    internal fun domainsFrom(localLines: List<String>?, assetLines: List<String>): DomainList {
        if (localLines != null) {
            val parsed = DomainList.parse(localLines.asSequence())
            if (parsed.size > 0) return parsed
        }
        return DomainList.parse(assetLines.asSequence())
    }

    private fun ranges(context: Context, name: String): List<Ipv4Range> =
        rangesFrom(readLocal(context, name), assetLines(context, name))

    internal fun rangesFrom(localLines: List<String>?, assetLines: List<String>): List<Ipv4Range> {
        val parsedLocal = localLines?.mapNotNull { Ipv4Range.parse(it) }.orEmpty()
        if (parsedLocal.isNotEmpty()) return parsedLocal
        return assetLines.mapNotNull { Ipv4Range.parse(it) }
    }

    private fun tlds(context: Context): Set<String> =
        tldsFrom(readLocal(context, "local_tlds.txt"), assetLines(context, "local_tlds.txt"))

    internal fun tldsFrom(localLines: List<String>?, assetLines: List<String>): Set<String> {
        if (localLines != null) {
            val parsed = TldList.parse(localLines.asSequence())
            if (parsed.isNotEmpty()) return parsed
        }
        val fromAssets = TldList.parse(assetLines.asSequence())
        return fromAssets.ifEmpty { RoutingPolicy.LOCAL_TLDS }
    }

    private fun readLocal(context: Context, name: String): List<String>? {
        val local = File(context.filesDir, "$ROUTING_DIR/$name")
        if (!local.exists()) return null
        return runCatching { local.readLines() }.getOrNull()
    }

    private fun assetLines(context: Context, name: String): List<String> =
        runCatching {
            context.assets.open("$DIR/$name").bufferedReader().use { it.readLines() }
        }.getOrDefault(emptyList())
}
