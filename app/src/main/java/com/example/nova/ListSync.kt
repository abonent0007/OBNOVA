package com.example.nova

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * Загрузка списков маршрутизации из стокового фида PC-форка.
 *
 * Источник — `confeden/nova_updates/nova_pc` (`manifest.json` + sha256),
 * те же зеркала, что у PC: raw.githubusercontent, jsdelivr, raw.githack.
 *
 * Набор файлов берётся из манифеста, а не из зашитого списка: если фид добавит
 * `local_tlds.txt` или новый сервисный список, приложение скачает его без
 * выпуска новой версии. Списки кладутся во временный каталог и заменяют
 * прежние только после того, как все файлы прошли проверку sha256 — иначе
 * один медленный запрос оставлял смесь старых и новых правил.
 */
object ListSync {

    private const val DIR = "routing_lists"
    private const val STAGING = ".staging"
    private const val MAX_BYTES = 4 * 1024 * 1024
    private const val TIMEOUT_MS = 12_000

    /**
     * IP-списки фида; имя файла на устройстве задаёт `ListManifest`.
     *
     * Раньше здесь был только Cloudflare, а `youtube_ips.txt` и
     * `telegram_ips.txt` искались в том же каталоге и никогда не обновлялись:
     * манифест их публиковал, но синхронизация проходила мимо. Теперь список
     * один, а соответствие «путь в фиде → имя файла» живёт в одном месте.
     */
    private val IP_ASSETS = listOf(
        "ip/cloudflare.txt",
        "ip/youtube.txt",
        "ip/telegram.txt",
    )

    data class Result(
        val ok: Boolean,
        val message: String,
        val updatedAt: Long = 0L,
    )

    fun sync(context: Context): Result {
        val app = context.applicationContext
        return try {
            val manifest = fetchManifest()
                ?: return Result(false, "манифест недоступен или не разобран")
            val listPaths = manifest.listPaths()
            if (listPaths.isEmpty()) {
                return Result(false, "в манифесте нет списков")
            }

            val dir = File(app.filesDir, DIR).apply { mkdirs() }
            val staging = File(dir, STAGING).apply {
                deleteRecursively()
                mkdirs()
            }
            val failures = mutableListOf<String>()

            for (relativePath in listPaths) {
                val expected = manifest.shaFor(relativePath) ?: continue
                val destination = ListManifest.destinationName(relativePath)
                if (destination == null) {
                    failures += relativePath
                    continue
                }
                val body = fetchVerified(
                    "${ExternalSources.LIST_BASE}/$relativePath",
                    expected,
                )
                if (body == null) {
                    failures += relativePath
                    continue
                }
                File(staging, destination).writeBytes(body)
            }

            for (relativePath in IP_ASSETS) {
                val expected = manifest.shaFor(relativePath) ?: continue
                val destinationName = ListManifest.destinationName(relativePath)
                    ?: run {
                        failures += relativePath
                        continue
                    }
                val body = fetchVerified(
                    "${ExternalSources.LIST_BASE}/$relativePath",
                    expected,
                )
                if (body != null) {
                    File(staging, destinationName).writeBytes(body)
                } else {
                    failures += relativePath
                }
            }

            if (failures.isNotEmpty()) {
                staging.deleteRecursively()
                return Result(
                    false,
                    "не скачалось или не совпало: ${failures.take(3).joinToString(", ")}" +
                        (if (failures.size > 3) " и ещё ${failures.size - 3}" else "") +
                        "; прежние списки оставлены",
                )
            }

            val staged = staging.listFiles().orEmpty()
            if (staged.isEmpty()) {
                staging.deleteRecursively()
                return Result(false, "ни один список не загрузился")
            }
            for (file in staged) {
                val destination = File(dir, file.name)
                if (destination.exists()) destination.delete()
                if (!file.renameTo(destination)) {
                    file.copyTo(destination, overwrite = true)
                    file.delete()
                }
            }
            staging.deleteRecursively()

            val stamp = System.currentTimeMillis()
            writeMeta(dir, stamp, manifest.generated)
            ListRepository.invalidate()
            Result(
                true,
                "обновлено файлов: ${staged.size}, generated=${manifest.generated}",
                stamp,
            )
        } catch (error: Exception) {
            Result(false, "ошибка: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    fun lastUpdatedAt(context: Context): Long {
        val meta = File(context.applicationContext.filesDir, "$DIR/meta.txt")
        if (!meta.exists()) return 0L
        return runCatching {
            meta.readText().lineSequence()
                .firstOrNull { it.startsWith("updated=") }
                ?.substringAfter('=')
                ?.toLongOrNull()
                ?: 0L
        }.getOrDefault(0L)
    }

    private fun writeMeta(dir: File, updatedAt: Long, generated: Long) {
        val temp = File(dir, "meta.txt.tmp")
        temp.writeText("updated=$updatedAt generated=$generated\n")
        val target = File(dir, "meta.txt")
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    /**
     * Манифест пробуем со всех зеркал: 200 с HTML или обрезанным JSON —
     * не повод сдаваться, если следующее зеркало отдаёт валидный документ.
     */
    private fun fetchManifest(): ListManifest.Manifest? {
        for (url in ExternalSources.listMirrors(ExternalSources.LIST_MANIFEST)) {
            val body = runCatching { httpGet(url) }.getOrNull() ?: continue
            if (body.isEmpty() || body.size > MAX_BYTES) continue
            val manifest = ListManifest.parse(String(body, Charsets.UTF_8)) ?: continue
            if (manifest.listPaths().isNotEmpty()) return manifest
        }
        return null
    }

    /**
     * Файл принимается только с совпавшим sha256. Проверка идёт на каждом
     * зеркале отдельно: устаревший jsdelivr не должен блокировать обновление,
     * если raw.githubusercontent уже отдал правильную версию.
     */
    private fun fetchVerified(relativePath: String, expectedSha: String): ByteArray? {
        for (url in ExternalSources.listMirrors(relativePath)) {
            val body = runCatching { httpGet(url) }.getOrNull() ?: continue
            if (body.isEmpty() || body.size > MAX_BYTES) continue
            if (sha256(body).equals(expectedSha, ignoreCase = true)) return body
        }
        return null
    }

    private fun httpGet(url: String): ByteArray = FeedHttp.bytes(
        url,
        FeedHttp.Policy(
            connectTimeoutMs = TIMEOUT_MS,
            readTimeoutMs = TIMEOUT_MS,
            maxBytes = MAX_BYTES,
        ),
    )

    private fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it) }
}
