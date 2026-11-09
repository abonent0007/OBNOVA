## OBNOVA 1.34.10 (174)

Личный форк [Nova Android](https://github.com/confeden/Nova-Android). Это **не официальная сборка Nova**.

### Что нового

- **KALIOPA parity, Phase 1:** меню начинается с блока «Профили и подписки»: «1. Обновить всё и сгенерировать», «2. Проверить и очистить», «Слить все профили в одну базу», настройки генерации WARP и Proton. Старые дублирующие кнопки скрыты.
- **Единая база профилей:** `UnifiedProfileStore` собирает WARP-семена, сгенерированные WARP, VLESS-подписки и Proton в один `profiles_unified.json` с `origin`, `alive`, `last_checked`, `fail_count`; TCP-проверка и мягкое удаление мёртвых. Bundled seeds не удаляются.
- **Источники:** шесть публичных VLESS-источников PC-версии (добавлены `aviamastersgh/vpn-free-russia` verified и RU), лимит VLESS — 1200.
- **Маршруты:** Telegram-домены идут через WSS-реле первыми; Google AI/opencode — отдельное правило Opera → primary → DESYNC; Spotify — DIRECT. Discord/WhatsApp оставлены на primary (в РФ прямой выход рискован).
- **Opera:** endpoint `.244` приоритетнее `.247`; API и tunnel используют один egress; добавлен PC-вариант bootstrap через WARP-AWG (SOCKS 1378) с MASQUE-запасом.
- **Конфигурации:** экран и пункт меню удалены — нужные конфигурации подгружаются автоматически.

### Установка

1. Скачайте APK ниже.
2. Установите `arm64-v8a` (или `universal`).
3. Android 7.0+ (minSdk 24). Подпись — личный ключ форка.

### APK

| Файл | ABI |
|---|---|
| `OBNOVA_1.34.10-arm64-v8a.apk` | arm64-v8a |
| `OBNOVA_1.34.10-armeabi-v7a.apk` | armeabi-v7a |
| `OBNOVA_1.34.10-universal.apk` | универсальный |

### SHA-256

```text
AB0EC0800D325E0B1538C521EB3A851276E777762B95E3423D65382C2424D2BD  OBNOVA_1.34.10-arm64-v8a.apk
8222182495D949123F799ACE9F1D9211C473A447ED534B70CBB808C6A22876B3  OBNOVA_1.34.10-armeabi-v7a.apk
0C0BACAD3EDA341B3AB29E1E7E3AA33D78EE113ECF877B75E378D6994F98926E  OBNOVA_1.34.10-universal.apk
```

### Debug-сборки

Для диагностики; release-подпись для повседневного использования.

| Файл | ABI |
|---|---|
| `OBNOVA_1.34.10-debug-arm64-v8a.apk` | arm64-v8a |
| `OBNOVA_1.34.10-debug-armeabi-v7a.apk` | armeabi-v7a |
| `OBNOVA_1.34.10-debug-universal.apk` | универсальный |

### Проверено

- 82 класса / 661 unit-тест, 0 падений; lint — 0 ошибок.
- RMX3370 (Android 13): пройдены меню настроек, профили (merge 969), селекторы, WARP и MASQUE (CONNECTED), MULTI (CONNECTED), диалоги генерации/DNS/MTU/Opera API/WARP+.
- Opera на текущей сети не поднялась: upstream API/egress даёт timeout/502; это внешняя проблема, не выбор endpoint.

### Поддержка

Спасибо, что пользуетесь OBNOVA! Добровольная поддержка проекта:

[Перевести на Сбербанк](https://www.sberbank.ru/ru/choise_bank?requisiteNumber=79684520007&bankCode=100000000111)

### Лицензия

GPL-3.0, как у upstream.
