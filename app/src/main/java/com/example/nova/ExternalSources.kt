package com.example.nova

/**
 * Единственная точка инвентаризации внешних источников форка.
 *
 * Здесь перечислены все адреса, по которым приложение получает данные. Если
 * появляется новый источник, он добавляется сюда, а не растворяется строкой в
 * случайном экране: так проще проверить, что у каждого вызова есть таймаут,
 * проверка ответа и запасной путь.
 *
 * Что остаётся константой и почему:
 *  - репозиторий списков — стоковый фид PC-форка `confeden/nova_updates`;
 *  - публичная VLESS-подписка — тот же источник, что в
 *    `profiles/subscriptions.json` PC; это seed, а не гарантия живых узлов;
 *  - нейтральный хост для проверки TLS — `example.com`: он не принадлежит
 *    блокируемым сервисам и отвечает с любого исправного выхода.
 */
object ExternalSources {

    const val LIST_REPO = "confeden/nova_updates"
    const val LIST_BASE = "nova_pc"
    const val LIST_MANIFEST = "$LIST_BASE/manifest.json"

    /**
     * Публичная подписка VLESS из стокового PC-форка. Узлы свободные и
     * нестабильные; приложение проверяет каждый профиль по HTTPS и уводит
     * нерабочие вниз списка.
     */
    const val PUBLIC_VLESS_SUBSCRIPTION_URL =
        "https://iboxz.github.io/free-v2ray-collector/main/vless.txt"

    /** Нейтральный адрес: по нему проверяется, что узел пропускает TLS. */
    const val NEUTRAL_HTTPS_PROBE_HOST = "example.com"

    /** Зеркала фида: порядок важен — первым идёт основной raw-адрес. */
    fun listMirrors(relativePath: String): List<String> = listOf(
        "https://raw.githubusercontent.com/$LIST_REPO/main/$relativePath",
        "https://cdn.jsdelivr.net/gh/$LIST_REPO@main/$relativePath",
        "https://raw.githack.com/$LIST_REPO/main/$relativePath",
    )
}
