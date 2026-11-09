## OBNOVA 1.34.11 (175)

Этот выпуск личного форка [Nova Android](https://github.com/confeden/Nova-Android) возвращает **YouTube-видео** и **Telegram** в MULTI.

### Главное

- **YouTube:** `nova-router` теперь передаёт в MASQUE домен из SNI, а не IP. Раньше MASQUE закрывал соединение (`Ok(N), Ok(0)`), поэтому грузились только превью. Проверено на новом видео: кадры, звук и таймер идут.
- **Telegram:** путь как в PC-версии — `Telegram → WARP AWG → DC`, а не MASQUE. Добавлен слот `awg` (SOCKS 1378), проверенный AWG-seed и MTProto-проба DC4/DC2; приложение выбирает семя, чей egress реально отвечает. Telegram online, аватары и сообщения работают.
- **Nova исключена из VPN** в режиме «все приложения»: иначе сокеты Xray/MASQUE/Opera зацикливались в собственном TUN.
- **VLESS:** проверенные семена идут первыми; `allowInsecure` удалён — Xray 26.10.31 не грузит конфиг с этим полем. VLESS остаётся запасом.
- **YouTube/AI:** порядок `primary → secondary → spoof → direct`; DESYNC — обход DPI.
- **Повторный CONNECT_SMART** во время `CONNECTING` игнорируется.
- **Футер настроек:** `OBNOVA v…`.

### Установка

1. Скачайте APK ниже.
2. Рекомендуется `arm64-v8a` (или `universal`).
3. Android 7.0+ (minSdk 24). Подпись — как у предыдущих выпусков.

### APK

| Файл | ABI |
|---|---|
| `OBNOVA_1.34.11-arm64-v8a.apk` | arm64-v8a |
| `OBNOVA_1.34.11-armeabi-v7a.apk` | armeabi-v7a |
| `OBNOVA_1.34.11-universal.apk` | универсальный |

### SHA-256

```text
C41DBB247735453FB9528A6798670D0DA095E4441553AA6C7E0C2F53CB5FE080  OBNOVA_1.34.11-arm64-v8a.apk
079B77F939F1527EF116B367D604FFA9AD55FFDAC5C8ED136036AA8971BC8803  OBNOVA_1.34.11-armeabi-v7a.apk
5EA58A2441C28FB463A39643878349E435201E984C76A7A2C5636970FC426FA5  OBNOVA_1.34.11-universal.apk
```

### Debug-сборки

Для отладки; release-подпись для повседневного использования.

| Файл | ABI |
|---|---|
| `OBNOVA_1.34.11-debug-arm64-v8a.apk` | arm64-v8a |
| `OBNOVA_1.34.11-debug-armeabi-v7a.apk` | armeabi-v7a |
| `OBNOVA_1.34.11-debug-universal.apk` | универсальный |

### SHA-256 (debug)

```text
3E007A0DBA31457D4FE7CE5485CA9FD143ED4FDE86DADA5D91217E700FD4FAA9  OBNOVA_1.34.11-debug-arm64-v8a.apk
8B563BBEE0043EE700234915E2D52B0AF5336A49EB387B31CAA9C7C890705D7A  OBNOVA_1.34.11-debug-armeabi-v7a.apk
7EDFE686DD4007832727CFC0520DCF6837C32635E4F79DB61B68408FC5471C52  OBNOVA_1.34.11-debug-universal.apk
```

### Проверки

- 83 класса / 666 unit-тестов, 0 падений.
- RMX3370 (Android 13): YouTube — новое видео играет; Telegram — online, аватары и сообщения; AWG seed #3 прошёл MTProto-пробу DC4; Google через AWG — 204.
- Opera остаётся необязательной: upstream API/egress иногда даёт timeout/502, MULTI её не блокирует.

### Поддержка

Спасибо, что пользуетесь OBNOVA! Добровольная поддержка проекта:

[Перевести на Сбербанк](https://www.sberbank.ru/ru/choise_bank?requisiteNumber=79684520007&bankCode=100000000111)

### Лицензия

GPL-3.0, как у upstream.
