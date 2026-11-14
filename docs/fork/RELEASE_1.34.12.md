## OBNOVA 1.34.12 (176)

Этот выпуск добавляет **Strict Full Tunnel** как явный opt-in режим и
закрывает диагностику IPv6/WARP. По умолчанию Strict **выключен**: обычное
поведение и старый Kill Switch не меняются.

### Главное

- **Strict Full Tunnel (opt-in):** весь трафик пользовательских приложений —
  только через MULTI; без `direct`, `exclude`, `local-tld`, `spotify`/`china-ai`
  DIRECT, без DNS/domain/LAN-обходов. Ручной WARP/OPERA/VLESS отклоняется.
- **Совместимость:** старый `killswitch=true` мигрирует в `LIMITED`, а не в
  Strict. Включение Strict — только отдельным подтверждением в UI.
- **Go-валидатор:** некорректная Strict-конфигурация отклоняется, а не
  исправляется; fallback на WARP/старую конфигурацию отсутствует.
- **UI:** точная область защиты, Lockdown ON/OFF/UNKNOWN, отказ backend
  показывается как «НЕ ПОДКЛЮЧЕНО» с причиной.
- **WARP IPv6:** подтверждён полный путь TUN → Go-core → WARP TX/RX → TUN;
  ранний FAIL не воспроизвёлся. Это диагностика, production-транспорт не
  менялся.

### Область защиты Strict

- Пользовательские приложения текущего пользователя Android — через MULTI.
- Служебный пакет Nova (UID 10997) исключён из VPN: без этого сокеты
  router/Opera/tun2proxy замыкаются в собственный TUN. Это документированное
  исключение, а не скрытый обход.
- Tethering не покрыт; межпользовательская защита (user 10/999) не заявлена.
- IPv6 в MULTI остаётся route-level fail-closed через `unreachable default`.

### Проверки

- Kotlin: **706 тестов, 0 падений**; Go `nova-router` test + `-race` — PASS.
- Release + R8 — PASS; debug — PASS.
- Устройство RMX3370: Strict route-level (все UID, кроме 10997), банки и новые
  приложения в VPN, IPv4 PASS, IPv6 fail-closed, AUTO→MULTI, ручной WARP
  отклонён, upstream loss без fallback, MULTI failure без WARP, Wi-Fi↔LTE,
  Lockdown ON/OFF, DNS NXDOMAIN без fallback.
- NOT TESTED: потеря primary-процесса (нужен root), resolver outage,
  tethering.

### Установка

1. Скачайте APK ниже.
2. Рекомендуется `arm64-v8a` (или `universal`).
3. Android 7.0+ (minSdk 24). Подпись — как у предыдущих выпусков.

### APK

| Файл | ABI |
|---|---|
| `OBNOVA_1.34.12-arm64-v8a.apk` | arm64-v8a |
| `OBNOVA_1.34.12-armeabi-v7a.apk` | armeabi-v7a |
| `OBNOVA_1.34.12-universal.apk` | универсальный |

### SHA-256

```text
230699B2580A7DFFB6996F28A44991F28083F59E5C8D91C02C820163FB62C4B4  OBNOVA_1.34.12-arm64-v8a.apk
0024A6B96FA8AAC8D5BE7FE18D31D75456555B4535E92C082DCB9920C563C079  OBNOVA_1.34.12-armeabi-v7a.apk
093626679DD537F6F4C677483DC4205158E616D4663D55F86CF636C1EBF8CF17  OBNOVA_1.34.12-universal.apk
```

### Debug-сборки

Для отладки; release-подпись для повседневного использования.

| Файл | ABI |
|---|---|
| `OBNOVA_1.34.12-debug-arm64-v8a.apk` | arm64-v8a |
| `OBNOVA_1.34.12-debug-armeabi-v7a.apk` | armeabi-v7a |
| `OBNOVA_1.34.12-debug-universal.apk` | универсальный |

### SHA-256 (debug)

```text
5BA513B92C5855DE68A78CEE84587D479E9D9210BBCFE1D2B133D9DB639050F8  OBNOVA_1.34.12-debug-arm64-v8a.apk
F2155B88F4C7FECFC903EA09763BD903BA686D8D880D611CDCA40D7CC0D72374  OBNOVA_1.34.12-debug-armeabi-v7a.apk
3A919AA4D9496B8F743090962ABBBD533AA99032CB8D0D19D842FA2CB838C5BD  OBNOVA_1.34.12-debug-universal.apk
```

### Поддержка

Спасибо, что пользуетесь OBNOVA! Добровольная поддержка проекта:

[Перевести на Сбербанк](https://www.sberbank.ru/ru/choise_bank?requisiteNumber=79684520007&bankCode=100000000111)

### Лицензия

GPL-3.0, как у upstream.
