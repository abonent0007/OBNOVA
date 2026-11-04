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
 * Локальный DESYNC-прокси отдельным процессом.
 *
 * Устроен как [DnsTunnelProcess] и `liboperaproxy.so`: имя `lib*.so` нужно,
 * чтобы Android распаковал файл из APK исполняемым, а `LD_LIBRARY_PATH`
 * указывает на каталог родных библиотек. Сокеты движка идут мимо нашего TUN:
 * пакет приложения всегда исключён из своего же VPN.
 *
 * Процесс живёт ровно столько, сколько нужен tun2proxy. Наблюдатель
 * перезапускает его после падения (не больше трёх раз), потому что мёртвый
 * прокси оставляет TUN с портом, который никто не слушает.
 */
object DesyncProxyManager {

    const val BINARY = "libnova-desync.so"
    const val DEFAULT_PORT = 1376

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
    private var restartsLeft = 0

    @Volatile
    private var activeOptions: DesyncOptions = DesyncOptions()

    fun isAvailable(context: Context): Boolean =
        File(context.applicationInfo.nativeLibraryDir.orEmpty(), BINARY).exists()

    fun isRunning(): Boolean = process.get()?.aliveCompat() == true

    fun port(): Int = activePort

    /**
     * Поднимает движок и возвращает порт SOCKS5; 0 — не поднялся.
     * Порт фиксируется на всю сессию: tun2proxy смотрит именно на него.
     */
    fun start(
        context: Context,
        logger: (String) -> Unit,
        preferredPort: Int = 0,
        options: DesyncOptions = DesyncOptions(),
    ): Int {
        stop(logger)
        killStale(logger)
        stopping = false
        restartsLeft = RESTART_LIMIT
        activeOptions = options.normalized()

        val ctx = context.applicationContext
        val nativeLibDir = ctx.applicationInfo.nativeLibraryDir.orEmpty()
        val binary = File(nativeLibDir, BINARY)
        if (!binary.exists()) {
            logger("DESYNC: движок $BINARY не найден для ABI ${android.os.Build.SUPPORTED_ABIS.joinToString()}.")
            return 0
        }

        val port = when {
            preferredPort in 1..65535 && awaitPortFree(preferredPort) -> preferredPort
            else -> freePort()
        }
        if (port == 0) {
            logger("DESYNC: не нашлось свободного локального порта под SOCKS5.")
            return 0
        }

        appContext = ctx
        activePort = port

        val started = launch(ctx, port, activeOptions, logger) ?: return 0
        process.set(started)
        if (!awaitReady(port)) {
            logger("DESYNC: движок не открыл порт $port за ${READY_TIMEOUT_MS / 1000} с.")
            stop(logger)
            return 0
        }
        LogManager.log("DESYNC: движок слушает 127.0.0.1:$port.")
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
            logger("DESYNC: остановка движка не удалась: ${error.message}")
        }
    }

    private fun launch(ctx: Context, port: Int, options: DesyncOptions, logger: (String) -> Unit): Process? {
        val nativeLibDir = ctx.applicationInfo.nativeLibraryDir.orEmpty()
        val binary = File(nativeLibDir, BINARY)
        val args = listOf(binary.absolutePath) + options.engineArgs(port)
        val started = try {
            ProcessBuilder(args).apply {
                directory(ctx.filesDir)
                redirectErrorStream(true)
                val env = environment()
                val existing = env["LD_LIBRARY_PATH"].orEmpty().trim()
                env["LD_LIBRARY_PATH"] = when {
                    existing.isBlank() -> nativeLibDir
                    existing.split(':').any { it == nativeLibDir } -> existing
                    else -> "$nativeLibDir:$existing"
                }
            }.start()
        } catch (error: Exception) {
            logger("DESYNC: движок не запустился: ${error.message}")
            return null
        }
        pump(started, logger)
        return started
    }

    /** Читает вывод до конца жизни процесса; без чтения труба переполнится. */
    private fun pump(running: Process, logger: (String) -> Unit) {
        Thread({
            try {
                BufferedReader(InputStreamReader(running.inputStream)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        LogManager.d("DESYNC: $line")
                    }
                }
            } catch (_: Exception) {
                // Труба закрылась вместе с процессом — штатный конец.
            } finally {
                if (process.get() === running && !stopping) {
                    val code = runCatching { running.exitValue() }.getOrNull()
                    LogManager.log("DESYNC: движок завершился (код $code).")
                    process.compareAndSet(running, null)
                    scheduleRestart(logger)
                }
            }
        }, "NovaDesyncLog").apply { isDaemon = true }.start()
    }

    private fun scheduleRestart(logger: (String) -> Unit) {
        val ctx = appContext ?: return
        if (stopping) return
        if (restartsLeft <= 0) {
            LogManager.log("DESYNC: лимит перезапусков исчерпан.")
            return
        }
        restartsLeft -= 1
        Thread({
            try {
                Thread.sleep(RESTART_DELAY_MS)
                if (stopping) return@Thread
                val port = activePort
                if (port == 0) return@Thread
                val restarted = launch(ctx, port, activeOptions, logger) ?: return@Thread
                process.set(restarted)
                if (awaitReady(port)) {
                    LogManager.log("DESYNC: движок перезапущен на 127.0.0.1:$port.")
                } else {
                    LogManager.log("DESYNC: перезапуск не поднялся, снимаем процесс.")
                    restarted.destroy()
                }
            } catch (_: InterruptedException) {
            }
        }, "NovaDesyncSupervisor").apply { isDaemon = true }.start()
    }

    private fun awaitReady(port: Int): Boolean {
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), 200)
                    return true
                }
            } catch (_: Exception) {
                Thread.sleep(100)
            }
        }
        return false
    }

    /** Снимает движок, переживший прошлый запуск (его переусыновил init). */
    private fun killStale(logger: (String) -> Unit) {
        val self = android.os.Process.myPid()
        for (entry in File("/proc").list().orEmpty()) {
            val pid = entry.toIntOrNull() ?: continue
            if (pid == self) continue
            val cmdline = runCatching { File("/proc/$entry/cmdline").readText() }.getOrNull() ?: continue
            if (!cmdline.contains(BINARY)) continue
            runCatching { android.os.Process.killProcess(pid) }
            logger("DESYNC: снят осиротевший движок (pid $pid).")
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

    // Process.isAlive/waitFor(timeout)/destroyForcibly появились в API 26,
    // а minSdk 24: на старых устройствах спрашиваем код завершения сами.
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
