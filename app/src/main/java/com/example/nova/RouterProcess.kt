package com.example.nova

import android.content.Context
import android.os.Build
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Локальный SOCKS5-диспетчер `nova-router` отдельным процессом.
 *
 * Устроен как `DesyncProxyManager`: бинарь лежит в `nativeLibraryDir` под именем
 * `lib*.so`, конфигурация пишется в `filesDir/router`, процесс перезапускается
 * наблюдателем. Порт фиксируется: tun2proxy смотрит именно на него.
 */
object RouterProcess {

    const val BINARY = "libnova-router.so"
    const val DEFAULT_PORT = 1373

    private const val READY_TIMEOUT_MS = 5_000L
    private const val RESTART_LIMIT = 3
    private const val RESTART_DELAY_MS = 700L

    private val process = AtomicReference<Process?>(null)

    @Volatile
    private var stopping = false

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var activePort = 0

    @Volatile
    private var configPath = ""

    @Volatile
    private var statusPath = ""

    @Volatile
    private var restartsLeft = 0

    fun isAvailable(context: Context): Boolean =
        File(context.applicationInfo.nativeLibraryDir.orEmpty(), BINARY).exists()

    /** Файл статистики слотов, который пишет сам роутер. */
    fun statusFile(context: Context): File =
        File(File(context.applicationContext.filesDir, "router"), "status.json")

    fun isRunning(): Boolean = process.get()?.aliveCompat() == true

    fun port(): Int = activePort

    fun start(
        context: Context,
        configJson: String,
        logger: (String) -> Unit,
        preferredPort: Int = DEFAULT_PORT,
    ): Int {
        stop(logger)
        killStale(logger)
        stopping = false
        restartsLeft = RESTART_LIMIT

        val ctx = context.applicationContext
        val nativeLibDir = ctx.applicationInfo.nativeLibraryDir.orEmpty()
        if (!File(nativeLibDir, BINARY).exists()) {
            logger("ROUTER: $BINARY не найден для ABI ${Build.SUPPORTED_ABIS.joinToString()}.")
            return 0
        }

        val workDir = File(ctx.filesDir, "router")
        val config = File(workDir, "config.json")
        val status = File(workDir, "status.json")
        try {
            workDir.mkdirs()
            config.writeText(configJson)
        } catch (error: Exception) {
            logger("ROUTER: не удалось записать конфигурацию: ${error.message}")
            return 0
        }

        val port = when {
            preferredPort in 1..65535 && awaitPortFree(preferredPort) -> preferredPort
            else -> freePort()
        }
        if (port == 0) {
            logger("ROUTER: нет свободного порта.")
            return 0
        }

        appContext = ctx
        activePort = port
        configPath = config.absolutePath
        statusPath = status.absolutePath

        val started = launch(ctx, port, config.absolutePath, status.absolutePath, logger) ?: return 0
        process.set(started)
        if (!awaitReady(port)) {
            logger("ROUTER: порт $port не открылся за ${READY_TIMEOUT_MS / 1000} с.")
            stop(logger)
            return 0
        }
        LogManager.log("ROUTER: диспетчер слушает 127.0.0.1:$port.")
        return port
    }

    fun stop(logger: (String) -> Unit) {
        stopping = true
        appContext = null
        activePort = 0
        val running = process.getAndSet(null) ?: return
        if (!running.aliveCompat()) return
        try {
            running.destroy()
            if (!running.waitExitCompat(1_500)) {
                running.destroyForciblyCompat()
                running.waitExitCompat(1_500)
            }
        } catch (error: Exception) {
            logger("ROUTER: остановка не удалась: ${error.message}")
        }
    }

    private fun launch(
        ctx: Context,
        port: Int,
        config: String,
        status: String,
        logger: (String) -> Unit,
    ): Process? {
        val nativeLibDir = ctx.applicationInfo.nativeLibraryDir.orEmpty()
        val binary = File(nativeLibDir, BINARY)
        val args = listOf(
            binary.absolutePath,
            "-listen", "127.0.0.1:$port",
            "-config", config,
            "-status", status,
            "-v",
        )
        val started = try {
            ProcessBuilder(args).apply {
                directory(ctx.filesDir)
                redirectErrorStream(true)
                environment()["LD_LIBRARY_PATH"] = nativeLibDir
            }.start()
        } catch (error: Exception) {
            logger("ROUTER: не запустился: ${error.message}")
            return null
        }
        pump(started, logger)
        return started
    }

    private fun pump(running: Process, logger: (String) -> Unit) {
        Thread({
            val lastFailure = HashMap<String, Long>()
            try {
                BufferedReader(InputStreamReader(running.inputStream)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        when {
                            line.contains("нет слота") -> {
                                // Отказ маршрута виден и на обычном уровне журнала:
                                // без него «приложение активно, а сайт не грузится»
                                // выглядело как молчание. Повторы одного хоста гасим
                                // на 5 секунд, иначе браузер забивает журнал.
                                val key = line.take(80)
                                val now = android.os.SystemClock.elapsedRealtime()
                                val previous = lastFailure[key]
                                if (previous == null || now - previous > 5_000L) {
                                    lastFailure[key] = now
                                    LogManager.w("ROUTER: $line")
                                }
                            }
                            line.contains("слушает") || line.contains("конфигурация") ->
                                LogManager.log("ROUTER: $line")
                            else -> LogManager.d("ROUTER: $line")
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                if (process.get() === running && !stopping) {
                    val code = runCatching { running.exitValue() }.getOrNull()
                    LogManager.log("ROUTER: процесс завершился (код $code).")
                    process.compareAndSet(running, null)
                    scheduleRestart(logger)
                }
            }
        }, "NovaRouterLog").apply { isDaemon = true }.start()
    }

    private fun scheduleRestart(logger: (String) -> Unit) {
        val ctx = appContext ?: return
        if (stopping || restartsLeft <= 0) {
            if (restartsLeft <= 0) LogManager.log("ROUTER: лимит перезапусков исчерпан.")
            return
        }
        restartsLeft -= 1
        Thread({
            try {
                Thread.sleep(RESTART_DELAY_MS)
                if (stopping) return@Thread
                val port = activePort
                val config = configPath
                if (port == 0 || config.isEmpty()) return@Thread
                val restarted = launch(ctx, port, config, statusPath, logger) ?: return@Thread
                process.set(restarted)
                if (awaitReady(port)) {
                    LogManager.log("ROUTER: процесс перезапущен на 127.0.0.1:$port.")
                } else {
                    restarted.destroy()
                }
            } catch (_: InterruptedException) {
            }
        }, "NovaRouterSupervisor").apply { isDaemon = true }.start()
    }

    private fun awaitReady(port: Int): Boolean {
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 200) }
                return true
            } catch (_: Exception) {
                Thread.sleep(100)
            }
        }
        return false
    }

    private fun killStale(logger: (String) -> Unit) {
        val self = android.os.Process.myPid()
        for (entry in File("/proc").list().orEmpty()) {
            val pid = entry.toIntOrNull() ?: continue
            if (pid == self) continue
            val cmdline = runCatching { File("/proc/$entry/cmdline").readText() }.getOrNull() ?: continue
            if (!cmdline.contains(BINARY)) continue
            runCatching { android.os.Process.killProcess(pid) }
            logger("ROUTER: снят осиротевший процесс (pid $pid).")
        }
    }

    private fun awaitPortFree(port: Int): Boolean = try {
        ServerSocket().use {
            it.reuseAddress = true
            it.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 1)
        }
        true
    } catch (_: Exception) {
        false
    }

    private fun freePort(): Int = try {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
    } catch (_: Exception) {
        0
    }

    private fun Process.aliveCompat(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            isAlive
        } else {
            runCatching { exitValue() }.isFailure
        }

    private fun Process.waitExitCompat(timeoutMs: Long): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (runCatching { exitValue() }.isSuccess) return true
            Thread.sleep(50)
        }
        return false
    }

    private fun Process.destroyForciblyCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) destroyForcibly() else destroy()
    }
}
