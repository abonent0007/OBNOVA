package com.example.nova

/**
 * Выбор APK из ассетов GitHub-релиза.
 *
 * Зачем отдельно. В релизе лежат шесть файлов: три release и три debug. Простой
 * «первый .apk» ошибался дважды: на 32-битном устройстве предлагал arm64-сборку
 * (установка падает), а при другом порядке ассетов мог взять debug, который не
 * встаёт поверх release без удаления приложения. Правило должно быть явным и
 * проверяемым без сети.
 */
internal object UpdateAssetSelector {

    /**
     * @param assets пары «имя файла → URL» из `assets` релиза;
     * @param abis `Build.SUPPORTED_ABIS` устройства, в порядке приоритета.
     * @return выбранная пара или null, если подходящего APK нет.
     */
    fun pick(assets: List<Pair<String, String>>, abis: List<String>): Pair<String, String>? {
        val releaseApks = assets.filter { (name, url) ->
            name.endsWith(".apk", ignoreCase = true) &&
                url.isNotBlank() &&
                !name.contains("debug", ignoreCase = true)
        }
        if (releaseApks.isEmpty()) return null

        for (abi in abis) {
            val normalized = abi.trim().lowercase()
            if (normalized.isEmpty()) continue
            releaseApks.firstOrNull { (name, _) ->
                name.lowercase().contains("-$normalized.apk")
            }?.let { return it }
        }
        return releaseApks.firstOrNull { (name, _) -> name.contains("universal", ignoreCase = true) }
    }
}
