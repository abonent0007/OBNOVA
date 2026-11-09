package com.example.nova

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Единая база профилей личного форка.
 *
 * Зачем отдельно от старых store-ов. Сейчас WARP-семена, сгенерированные
 * профили, VLESS-подписки и Proton лежат в четырёх разных файлах, и экран не
 * может ответить на простой вопрос «сколько у меня всего профилей и какие из
 * них живы». Эта база собирает их в один append-only список с происхождением
 * (`origin`), состоянием (`alive`), временем проверки и счётчиком отказов.
 *
 * Важно: это **не** замена рабочих store-ов на первом этапе. Движок и старые
 * экраны продолжают читать свои файлы; база синхронизируется из них и служит
 * источником для проверки/очистки. Так обновления подписок и встроенные семена
 * остаются нетронутыми, а переход к единому источнику можно делать поэтапно.
 */
data class UnifiedProfile(
    val id: String,
    /** `warp`, `masque`, `vless`, `proton`. */
    val protocol: String,
    val engine: String,
    val host: String,
    val port: Int,
    /** `bundled`, `generated`, `imported`, `verified`, `subscription`, `proton`. */
    val origin: String,
    val originId: String,
    val legacyId: String,
    /** Минимум данных для дедупликации: ссылка VLESS, ключ Proton или пусто. */
    val payload: String,
    val alive: Boolean? = null,
    val lastCheckedAt: Long = 0L,
    val failCount: Int = 0,
)

data class UnifiedProfileStats(
    val total: Int,
    val alive: Int,
    val dead: Int,
    val untested: Int,
    val byProtocol: Map<String, Int>,
) {
    fun summary(): String {
        if (total == 0) return "База профилей пуста — нажмите «Слить в одну базу»"
        val protocols = byProtocol.entries.joinToString(", ") { "${it.key} ${it.value}" }
        return "В базе $total: живых $alive, мёртвых $dead, не проверено $untested" +
            if (protocols.isBlank()) "" else " ($protocols)"
    }
}

object UnifiedProfileStore {

    const val VERSION = 1
    const val DEAD_THRESHOLD = 2
    private const val FILE_NAME = "profiles_unified.json"
    private const val DEAD_FILE_NAME = "profiles_dead.json"
    private const val MAX_PROFILES = 1_600
    private const val PROBE_TIMEOUT_MS = 3_000L
    private const val PROBE_PARALLELISM = 32
    private const val DEAD_TTL_MS = 7L * 24 * 60 * 60 * 1000

    private val writeLock = Any()

    /** Чистый идентификатор: одинаковый профиль не должен плодить записи. */
    fun idFor(protocol: String, host: String, port: Int, payload: String): String =
        sha256("${protocol.lowercase()}|${host.lowercase()}|$port|$payload").take(24)

    /**
     * Слияние append-only: старые записи сохраняют порядок и результаты
     * проверок, новые добавляются в конец, дубликаты по [UnifiedProfile.id]
     * обновляют только метаданные.
     */
    fun merge(
        existing: List<UnifiedProfile>,
        incoming: List<UnifiedProfile>,
    ): List<UnifiedProfile> {
        val merged = LinkedHashMap<String, UnifiedProfile>()
        existing.forEach { merged[it.id] = it }
        incoming.forEach { record ->
            val old = merged[record.id]
            merged[record.id] = if (old == null) {
                record
            } else {
                record.copy(
                    alive = old.alive,
                    lastCheckedAt = old.lastCheckedAt,
                    failCount = old.failCount,
                )
            }
        }
        return merged.values.take(MAX_PROFILES).toList()
    }

    fun stats(profiles: List<UnifiedProfile>): UnifiedProfileStats {
        var alive = 0
        var dead = 0
        var untested = 0
        val byProtocol = LinkedHashMap<String, Int>()
        profiles.forEach { profile ->
            when (profile.alive) {
                true -> alive++
                false -> dead++
                null -> untested++
            }
            byProtocol[profile.protocol] = (byProtocol[profile.protocol] ?: 0) + 1
        }
        return UnifiedProfileStats(
            total = profiles.size,
            alive = alive,
            dead = dead,
            untested = untested,
            byProtocol = byProtocol,
        )
    }

    @Synchronized
    fun read(context: Context): List<UnifiedProfile> {
        val raw = runCatching {
            fileFor(context).baseFile.takeIf { it.exists() }?.readText(Charsets.UTF_8)
        }.getOrNull().orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONObject(raw).optJSONArray("profiles") ?: JSONArray()
            buildList(array.length()) {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.let(::parseProfile)?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun syncFromLegacy(context: Context): UnifiedProfileStats {
        val existing = read(context)
        val incoming = collectLegacy(context)
        val merged = merge(existing, incoming)
        write(context, merged)
        LogManager.log(
            "Единая база профилей: собрано ${incoming.size}, всего ${merged.size} " +
                "(${stats(merged).summary()})."
        )
        return stats(merged)
    }

    /**
     * TCP-проверка доступности. WARP AWG (UDP) сюда не входит: TCP-коннект к
     * UDP-порту ничего не доказывает, поэтому такие записи честно остаются
     * непроверенными, а не помечаются мёртвыми.
     */
    @Synchronized
    fun checkAll(context: Context, timeoutMs: Long = PROBE_TIMEOUT_MS): UnifiedProfileStats {
        val records = read(context)
        val probeable = records.filter(::isProbeable)
        if (probeable.isEmpty()) {
            LogManager.log("Единая база: нет TCP-проверяемых профилей (UDP-AWG пропущен).")
            return stats(records)
        }
        val pool = Executors.newFixedThreadPool(PROBE_PARALLELISM)
        val results = try {
            pool.invokeAll(
                probeable.map { profile ->
                    Callable {
                        profile.id to tcpAlive(profile.host, profile.port, timeoutMs)
                    }
                },
            ).mapNotNull { it.get() }.toMap()
        } finally {
            pool.shutdown()
        }
        val now = System.currentTimeMillis()
        val updated = records.map { profile ->
            val reachable = results[profile.id] ?: return@map profile
            if (reachable) {
                profile.copy(alive = true, lastCheckedAt = now, failCount = 0)
            } else {
                profile.copy(
                    alive = false,
                    lastCheckedAt = now,
                    failCount = profile.failCount + 1,
                )
            }
        }
        write(context, updated)
        LogManager.log("Единая база: проверено ${results.size}, ${stats(updated).summary()}.")
        return stats(updated)
    }

    /**
     * Удаляет только доказанно мёртвые не-bundled записи. Встроенные семена
     * остаются всегда: это последний запасной путь, и его нельзя вычистить
     * сетевой ошибкой.
     */
    @Synchronized
    fun removeDead(context: Context, threshold: Int = DEAD_THRESHOLD): Int {
        val records = read(context)
        val client = ClientData(context)
        val generated = WarpGeneratedStore(context)
        var removed = 0
        val kept = ArrayList<UnifiedProfile>(records.size)
        for (profile in records) {
            val dead = profile.alive == false && profile.failCount >= threshold
            if (!dead || profile.origin == "bundled") {
                kept += if (profile.origin == "bundled" && profile.alive == false) {
                    profile.copy(alive = null, failCount = 0)
                } else {
                    profile
                }
                continue
            }
            val removedLegacy = when (profile.protocol) {
                "vless" -> runCatching {
                    client.removeVlessProfileByIdentity(profile.legacyId)
                }.getOrDefault(false)
                "warp", "masque" -> when (profile.origin) {
                    "generated" -> runCatching {
                        generated.removeProfiles(setOf("${profile.host}:${profile.port}"))
                        true
                    }.getOrDefault(false)
                    "verified", "imported" -> runCatching {
                        client.removeWarpVerifiedConfig(profile.legacyId)
                        true
                    }.getOrDefault(false)
                    else -> false
                }
                // Proton перевыпускается менеджером; удалять его из чужого
                // файла во время фонового замера небезопасно.
                else -> false
            }
            if (removedLegacy) {
                if (profile.protocol == "vless") markDead(context, profile.legacyId)
                removed++
            } else {
                kept += profile
            }
        }
        if (removed > 0) write(context, kept)
        LogManager.log("Единая база: удалено мёртвых профилей $removed.")
        return removed
    }

    fun summary(context: Context): String = stats(read(context)).summary()

    /**
     * Идентичности VLESS, которые уже удалены как мёртвые. Публичная подписка
     * приносит их снова при каждом обновлении; без этого списка очистка была
     * бы косметической — через час профиль вернулся бы из фида.
     */
    @Synchronized
    fun deadIdentities(context: Context): Set<String> {
        val raw = runCatching {
            deadFileFor(context).baseFile.takeIf { it.exists() }?.readText(Charsets.UTF_8)
        }.getOrNull().orEmpty()
        if (raw.isBlank()) return emptySet()
        val now = System.currentTimeMillis()
        return runCatching {
            val json = JSONObject(raw)
            val alive = JSONObject()
            val result = HashSet<String>()
            json.keys().asSequence().toList().forEach { identity ->
                val until = json.optLong(identity, 0L)
                if (until > now) {
                    result += identity
                    alive.put(identity, until)
                }
            }
            if (alive.length() != json.length()) writeDead(context, alive)
            result
        }.getOrDefault(emptySet())
    }

    private fun markDead(context: Context, identity: String) {
        if (identity.isBlank()) return
        val raw = runCatching {
            deadFileFor(context).baseFile.takeIf { it.exists() }?.readText(Charsets.UTF_8)
        }.getOrNull().orEmpty()
        val json = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
        json.put(identity, System.currentTimeMillis() + DEAD_TTL_MS)
        writeDead(context, json)
    }

    private fun writeDead(context: Context, json: JSONObject) {
        val file = deadFileFor(context)
        synchronized(writeLock) {
            var stream: java.io.FileOutputStream? = null
            try {
                stream = file.startWrite()
                stream.write(json.toString().toByteArray(Charsets.UTF_8))
                file.finishWrite(stream)
            } catch (error: Throwable) {
                if (stream != null) runCatching { file.failWrite(stream) }
                LogManager.log("Единая база: список мёртвых не записан — ${error.message}")
            }
        }
    }

    private fun collectLegacy(context: Context): List<UnifiedProfile> {
        val client = ClientData(context)
        val incoming = ArrayList<UnifiedProfile>()

        client.getWarpVerifiedMergedConfigs().forEach { config ->
            if (config.host.isBlank() || config.port !in 1..65535) return@forEach
            val protocol = if (
                config.engine.contains("masque", ignoreCase = true) ||
                config.mode.contains("masque", ignoreCase = true)
            ) {
                "masque"
            } else {
                "warp"
            }
            val bundled = config.endpointSource.equals("bundled-seed", ignoreCase = true)
            val origin = when {
                bundled -> "bundled"
                config.userImported -> "imported"
                else -> "verified"
            }
            incoming += UnifiedProfile(
                id = idFor(protocol, config.host, config.port, config.id),
                protocol = protocol,
                engine = config.engine,
                host = config.host,
                port = config.port,
                origin = origin,
                originId = config.endpointSource,
                legacyId = config.id,
                payload = config.id,
            )
        }

        WarpGeneratedStore(context).read().profiles.forEach { profile ->
            if (profile.host.isBlank() || profile.port !in 1..65535) return@forEach
            incoming += UnifiedProfile(
                id = idFor("warp", profile.host, profile.port, ""),
                protocol = "warp",
                engine = "warpgen",
                host = profile.host,
                port = profile.port,
                origin = "generated",
                originId = "warp_generated.json",
                legacyId = "${profile.host}:${profile.port}",
                payload = "",
            )
        }

        val deadVless = deadIdentities(context)
        client.getVlessProfileLinks().forEach { link ->
            val parsed = VlessConfig.parse(link) ?: return@forEach
            if (parsed.identity in deadVless) return@forEach
            incoming += UnifiedProfile(
                id = idFor("vless", parsed.host, parsed.port, parsed.identity),
                protocol = "vless",
                engine = "xray",
                host = parsed.host,
                port = parsed.port,
                origin = "subscription",
                originId = "",
                legacyId = parsed.identity,
                payload = link,
            )
        }

        ProtonProfileStore(context).readProfiles().forEach { profile ->
            if (profile.entryIp.isBlank() || profile.port !in 1..65535) return@forEach
            incoming += UnifiedProfile(
                id = idFor("proton", profile.entryIp, profile.port, profile.peerPublicKey),
                protocol = "proton",
                engine = "awg",
                host = profile.entryIp,
                port = profile.port,
                origin = "proton",
                originId = profile.serverName,
                legacyId = "${profile.entryIp}:${profile.port}",
                payload = profile.peerPublicKey,
            )
        }
        return incoming
    }

    private fun isProbeable(profile: UnifiedProfile): Boolean {
        if (profile.host.isBlank() || profile.port !in 1..65535) return false
        return when (profile.protocol) {
            "vless", "proton", "masque" -> true
            "warp" -> profile.engine.contains("masque", ignoreCase = true)
            else -> false
        }
    }

    private fun tcpAlive(host: String, port: Int, timeoutMs: Long): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMs.toInt())
            true
        }
    }.getOrDefault(false)

    private fun parseProfile(json: JSONObject): UnifiedProfile? {
        val id = json.optString("id").trim()
        val host = json.optString("host").trim()
        val protocol = json.optString("protocol").trim()
        val port = json.optInt("port", -1)
        if (id.isBlank() || host.isBlank() || protocol.isBlank() || port !in 1..65535) return null
        val aliveRaw = json.optString("alive", "")
        return UnifiedProfile(
            id = id,
            protocol = protocol,
            engine = json.optString("engine"),
            host = host,
            port = port,
            origin = json.optString("origin"),
            originId = json.optString("origin_id"),
            legacyId = json.optString("legacy_id"),
            payload = json.optString("payload"),
            alive = when (aliveRaw) {
                "true" -> true
                "false" -> false
                else -> null
            },
            lastCheckedAt = json.optLong("last_checked", 0L),
            failCount = json.optInt("fail_count", 0),
        )
    }

    private fun write(context: Context, profiles: List<UnifiedProfile>) {
        val payload = JSONObject().apply {
            put("version", VERSION)
            put("profiles", JSONArray().apply {
                profiles.forEach { profile ->
                    put(JSONObject().apply {
                        put("id", profile.id)
                        put("protocol", profile.protocol)
                        put("engine", profile.engine)
                        put("host", profile.host)
                        put("port", profile.port)
                        put("origin", profile.origin)
                        put("origin_id", profile.originId)
                        put("legacy_id", profile.legacyId)
                        put("payload", profile.payload)
                        profile.alive?.let { put("alive", it) }
                        put("last_checked", profile.lastCheckedAt)
                        put("fail_count", profile.failCount)
                    })
                }
            })
        }.toString()
        val file = fileFor(context)
        synchronized(writeLock) {
            var stream: java.io.FileOutputStream? = null
            try {
                stream = file.startWrite()
                stream.write(payload.toByteArray(Charsets.UTF_8))
                file.finishWrite(stream)
            } catch (error: Throwable) {
                if (stream != null) runCatching { file.failWrite(stream) }
                LogManager.log("Единая база: запись не удалась — ${error.message}")
            }
        }
    }

    private fun fileFor(context: Context): AtomicFile =
        AtomicFile(File(context.applicationContext.filesDir, FILE_NAME))

    private fun deadFileFor(context: Context): AtomicFile =
        AtomicFile(File(context.applicationContext.filesDir, DEAD_FILE_NAME))

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
