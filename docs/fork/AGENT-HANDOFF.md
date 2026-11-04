# Handoff: как продолжить этот форк

> Прочитай сначала `AGENTS.md`, затем `docs/architecture/ARCHITECTURE.md` и
> `docs/fork/CHANGELOG.md`. Этот файл — точка входа для следующего агента.

## 1. Текущее состояние

- Ветка: `personal`, версия **1.34.4** / `versionCode 168` (OBNOVA).
- Официальный апдейтер APK выключен (`UPDATER_ENABLED = false`), UI обновлений
  скрыт. Облачные профили и подписки не тронуты.
- Тема по умолчанию — **Matrix** с `MatrixRainView` (цифровой дождь).
- Главный экран: узлы — SVG-иконки в `DockRadioButton`; настройки — SVG-гамбургер
  справа сверху; подтверждение выбора — `tv_top_notice` под статус-баром, не Toast
  внизу. Если менять навигацию/иконки, смотри `docs/fork/CHANGELOG.md`.
- Локальные TLD и встроенные direct-домены включены по умолчанию через
  domain bypass; на Android ядро покрывает WARP/MASQUE TCP 80/443.
- Локальный DNS-прокси получил TTL-кэш.
- Добавлена адаптивная ротация профилей в режиме `auto` (порог 3 деградации,
  cooldown 15 мин, 2/час, ручной выбор защищён).
- Фон: манифест помечает поддержку Always-on (`SUPPORTS_ALWAYS_ON`), в настройках
  есть строка «Постоянный VPN» (`AlwaysOnVpn.kt`), а `MainActivity` сам поднимает
  сеанс, если процесс `:vpn` был убит и `restartSession` сохранён. После первого
  CONNECTED один раз показывается плашка про батарею/автозапуск/always-on.
- Сборка/тесты: см. `docs/fork/VERIFICATION.md`; не считать «готово» без
  свежего прогона. Unit tests — 606/0 failed (9 skipped). `lintGithubDebug` —
  0 errors (глобального baseline нет).
- Внешние источники и запасные пути описаны в `docs/fork/EXTERNAL-DATA.md`;
  список файлов маршрутов берётся из манифеста, а не из кода.
- **MULTI и блокировки:** на этом операторе MULTI (DESYNC + свободный VLESS) не
  обходит YouTube и Telegram: TCP-коннект есть, TLS-ответа нет. Проверка
  «RU/US — OK, YouTube/Telegram — timeout» встроена в «Маршруты»
  (`ServiceProbe.kt`). Рабочий путь для этих сервисов — **MASQUE/WARP**;
  публичная VLESS-подписка (`iboxz/free-v2ray-collector`, 249 профилей) остаётся
  источником узлов, но живой узел ищется перебором.
- Прямой слот `nova-router` резолвит имена собственным DNS по TCP
  (`nova-router/resolver.go`): без этого `ya.ru`/`mail.ru` через DIRECT падали
  за 5 мс, хотя по IP отвечали.
- Бейдж главного экрана показывает фактический транспорт (`MULTI: RU`,
  `MASQUE: RU`, `VLESS: …`); `smart_routing` читается из XML на диске, поэтому
  тумблер действует со следующего подключения без force-stop.
- **Stage 2 (AWG SOCKS)** реализован за флагом `-PnovaCfProxySlot=true`
  (`CF_PROXY_SLOT_ENABLED`): `startAwgSocks` поднимает изолированный
  userspace-AWG на `127.0.0.1:1378`; проверено `204` к Cloudflare. Opera
  bootstrap через `-api-proxy socks5://127.0.0.1:1378` открывает `1371`,
  `yt-dlp` скачал видеочанк. YouTube-приложение ходит по IP-литералам и
  доменными правилами не ловится — следующий шаг IP/CIDR-правила.
- Эмулятор `Pixel_7_Pro` — x86_64, APK — только ARM. ARM64 AVD на x86_64 хосте
  не запускается (`FATAL: CPU Architecture arm64 is not supported`); для
  живого прогона нужен USB ARM-девайс.

## 2. Быстрые команды

```powershell
# из корня Android-форка
.\gradlew.bat test
.\gradlew.bat assembleGithubDebug
.\gradlew.bat assembleGithubRelease   # нужен keystore / env
```

- JDK: `JAVA_HOME` должен указывать на JDK 17 (Gradle 9.3.1).
- Android SDK: `ANDROID_HOME=C:\Android`; AVD — `Pixel_7_Pro`.
- Эмулятор: `& "$env:ANDROID_HOME\emulator\emulator.exe" -avd Pixel_7_Pro`
  (MCP `android-emulator` в глобальном конфиге выключен; используем adb/emulator).
- Установка: `adb install -r app\build\outputs\apk\github\release\OBNOVA_<версия>-arm64-v8a.apk`.
- Логи: `adb logcat -s Nova:* AndroidRuntime:E`.

## 3. Где что менять

| Задача | Файлы |
|---|---|
| Версия/сборка/флаги | `app/build.gradle.kts`, `gradle.properties`, `proguard-rules.pro` |
| Официальный апдейтер | `AppUpdateManager.kt`, `SettingsActivity.kt`, `MainActivity.kt`, `UpdateCheckWorker.kt`, `UpdateActionReceiver.kt`; не удалять notification-helper'ы |
| Локальные TLD/direct | `DomainBypassRules.kt`, `DomainBypassActivity.kt`, `activity_domain_bypass.xml`, `NovaVpnService.kt` (`applyCoreDomainBypassPolicy`, `domainBypassAddresses`), `nova-core/dnsname/zone.go` |
| DNS-кэш | `DnsResponseCache.kt`, `LocalDnsProxyManager.kt`, `DnsResponseCacheTest.kt` |
| Ротация | `ProfileHealthRotation.kt`, `MainActivity.kt` (`maybeRotateProfile`), `ProfileRotation.kt` |
| Matrix | `NovaTheme.kt`, `MatrixRainView.kt`, `activity_main.xml`, `MainActivity.kt` (`showNetworkBackground`/`hideNetworkBackground`) |
| Фон/always-on | `AlwaysOnVpn.kt`, `SettingsActivity.kt` (`row_always_on`), `MainActivity.kt` (`restoreDeadSessionOnOpen`, `maybeShowBackgroundGuide`) |
| ZAPRET (DESYNC) | `nova-desync/` (Go-движок), `DesyncProxyManager.kt`, `NovaVpnService.kt` (`runDesyncPhase`), `ConnectionSelectorPolicy.kt`, `ic_node_desync.xml` |
| Облачные профили — **не трогать** | `ProtonProfileManager.kt`, `ProtonProfileStore.kt`, `VlessSubscription*.kt`, `WarpProfileGenerator.kt`, `Gateway*.kt` |

## 4. Жёсткие правила форка

1. Не пушить в upstream и не публиковать сборку.
2. Не менять URL/логику облачных профилей, подписок и встроенных узлов.
3. Не включать `LiveNodeRotation` (`MAX_ROTATIONS_PER_SESSION = 0`) без новых
   замеров — это измеренное решение, а не забытая заглушка.
4. Не менять `panic = "unwind"` в Rust-профиле PC (для Android неактуально, но
   общая политика стабильности).
5. Не коммитить `keystore.properties`, `*.jks`, `local.properties`, `bin/`,
   `temp/`, сборки.
6. Секреты релея (`RELAY_KEY_ID`, `OPERA_RELAY_PASSWORD`, `TG_CF_WS_SECRET`) —
   стоковые; не ротировать без инфраструктуры nova-app.eu.

## 5. Release и relay key

- В форке `versionCode = 164` и `relayKeyId = nova-android-164` (ключ
  синхронизирован с upstream 1.33.1). Публикационная проверка Gradle теперь
  проходит по версии; для личной сборки без WSS-секрета:
  ```powershell
  $env:NOVA_ALLOW_UNSIGNED_RELEASE = "1"
  .\gradlew.bat assembleGithubRelease
  ```
- Без `keystore.properties` release будет unsigned; для установки на эмулятор
  используй debug, для личного APK — свой JKS вне git.
- Если сервер релея отклонит ключ 164, регистрация Opera/Proton/Cloudflare
  уйдёт в direct fallback; загрузка облачных профилей не затрагивается.
  Ключ обновлять из upstream при смене версии.
- Смена applicationId/подписи ломает обновление поверх upstream-сборки:
  старую установку нужно удалить.

## 6. Что осталось (приоритет)

1. Прогнать `gradlew test`, `assembleGithubDebug`, установить на AVD, проверить
   Matrix, отсутствие update UI, domain bypass, DNS-кэш, ротацию.
2. Включить/проверить R8 release (`optimizedResourceShrinking`,
   `strictFullModeForKeepRules` только после keep-теста JNI/Go/Xray).
3. Проверить размер APK и фактическое сжатие dex (`unzip -lv`); не использовать
   несуществующий `android.enableDexCompress`.
4. При желании — перенести ротацию с UI-уровня в background-service, но только
   после замеров и с сохранением cooldown/лимитов.
5. Android parity дальше: IPv6/Happy Eyeballs, UDP/QUIC relay, медиа/YouTube —
   отдельными фазами, не смешивать с текущей стабилизацией.

## 7. Известные ограничения

- Domain bypass в Go-ядре работает на WARP/MASQUE и TCP 80/443; Opera/VLESS
  отдают TUN в tun2proxy. Не обещать в UI полное покрытие.
- `ProfileHealthRotation` использует существующую сортировку профилей, а не
  отдельный замер кандидатов (в Android нет прямого RTT-каталога PC-формата).
- DNS-AI в Android — это автоматический `PriorityDns` (`dns.dns-ai.ru`), а не
  отдельная кнопка на главном экране. В исходниках (и в upstream 1.33.1) нет
  отдельной main-screen кнопки DNS-AI; не удалять `PriorityDns` при правках
  маршрутизации/DNS. Если пользователь просит кнопку — уточнить, что именно
  она должна переключать.
- `MatrixRainView` на слабых устройствах рисует статичный кадр; это осознанно.
- `AppUpdateManager` остаётся в APK ради foreground-уведомлений; сетевые входы
  закрыты флагом, UI скрыт.

## 8. Проверка перед словом «готово»

- `gradlew test` — зелёный.
- `assembleGithubDebug` — успешно, APK установлен на AVD.
- Нет краша при старте/подключении; Matrix виден; пунктов обновления нет.
- Cloud profile sync (Proton/VLESS) не изменён и работает.
- `docs/fork/VERIFICATION.md` обновлён фактическим выводом.
