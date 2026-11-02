package com.example.nova

/**
 * Параметры узла ZAPRET (DESYNC).
 *
 * Значения приходят из настроек, а не из кода: порт, split и decoy — это выбор
 * человека, а не константа сборки. Здесь же единственная точка нормализации,
 * чтобы служба и движок не расходились в трактовке кривого значения из prefs.
 */
data class DesyncOptions(
    val split: Int = DEFAULT_SPLIT,
    val delayMs: Int = DEFAULT_DELAY_MS,
    val recordSplit: Boolean = true,
    val decoyEnabled: Boolean = true,
    val decoySni: String = DEFAULT_DECOY_SNI,
) {

    fun normalized(): DesyncOptions = copy(
        split = split.coerceIn(1, MAX_SPLIT),
        delayMs = delayMs.coerceIn(0, MAX_DELAY_MS),
        decoySni = normalizeDecoy(decoySni),
    )

    /** Аргументы движка; пустой decoy означает «не отправлять». */
    fun engineArgs(port: Int): List<String> = buildList {
        add("-listen")
        add("127.0.0.1:$port")
        add("-split")
        add(split.toString())
        add("-delay")
        add("${delayMs}ms")
        add("-record-split=$recordSplit")
        if (decoyEnabled) {
            add("-decoy")
            add(decoySni)
            add("-decoy-wait")
            add("80ms")
        }
    }

    companion object {
        const val DEFAULT_SPLIT = 3
        const val DEFAULT_DELAY_MS = 12
        const val MAX_SPLIT = 8
        const val MAX_DELAY_MS = 200
        const val DEFAULT_DECOY_SNI = "www.microsoft.com"

        private val HOST = Regex("^[a-z0-9]([a-z0-9.-]{0,251}[a-z0-9])?$")

        /**
         * Приводит decoy к голому хосту.
         *
         * Мусор и пустая строка не должны ломать соединение: тогда decoy просто
         * не отправляется, а реальный поток идёт как обычно. Поэтому откат —
         * к известному домену, а не к ошибке.
         */
        fun normalizeDecoy(value: String?): String {
            val host = value
                ?.trim()
                ?.lowercase()
                ?.removePrefix("https://")
                ?.removePrefix("http://")
                ?.substringBefore('/')
                ?.substringBefore(':')
                .orEmpty()
            return if (host.length > 3 && host.contains('.') && HOST.matches(host)) {
                host
            } else {
                DEFAULT_DECOY_SNI
            }
        }
    }
}
