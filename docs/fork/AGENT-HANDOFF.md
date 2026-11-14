# Handoff: как продолжить этот форк

> Прочитай сначала `AGENTS.md`, затем `docs/architecture/ARCHITECTURE.md` и
> `docs/fork/CHANGELOG.md`. Этот файл — точка входа для следующего агента.

## 0. Последнее обновление — 2026-11-14

- Версия: **1.34.12 / versionCode 176** (релиз 2026-11-15).
- **Контрольное состояние (2026-11-14): P0/P1 — CLOSED, P-A/P-B — CLOSED,
  P-C — CLOSED WITH LIMITATIONS, P-D — ACCEPTED, P-E.1 — CLOSED,
  P-E.2A WARP IPv6 — CLOSED/PASS, P-E.2A MULTI IPv6 — route-level DROP
  (runtime NOT TESTED), P-E.2B — NOT STARTED, P2.3 — BLOCKED, P3 — BLOCKED.**
  Аудит P-E.2B.1 (источники DIRECT/обхода) — `docs/fork/PE2B1-AUDIT.md`:
  текущий `killSwitch` не Strict (7 источников direct в router + Android);
  Lockdown детектируется публично с API 29.
- **P-E.2B.1 модель (2026-11-14) — B.2.1 PASS.** `RoutePolicyMode`
  (`OFF/LIMITED/STRICT_FULL/SELECTIVE`); старый `killswitch=true`
  мигрирует только в `LIMITED`, не в Strict; запись миграции — из UI-процесса.
  Unit 702/0; release PASS; device: миграция `limited`, MULTI подключается.
  UI Strict не добавлен, `STRICT_FULL` нигде не выставляется.
- **P-E.2B.2 router-валидация (2026-11-14) — PASS.** В Strict фабрика не
  создаёт `direct`-слот, `exclude`/`local-tld`, `spotify`/`china-ai` DIRECT;
  `RouteClass.DIRECT` отклоняется; JSON содержит `"strict":true`.
  `nova-router` независимо отклоняет direct в слоте/типе/любом порядке и
  `exclude`/`local-tld`, без автоисправления и отката. Kotlin 706/0;
  Go test + `-race` PASS; JSON-фикстура Kotlin→Go проходит.
- **P-E.2B.3 Android full tunnel (2026-11-14) — PARTIAL ACCEPTED, B.2.3.2
  выполнен, ждёт финальной приёмки.** Strict: без пользовательских
  allow/disallow-списков и DirectAppsPolicy, без DNS/domain/LAN-carve-outs,
  только MULTI; AUTO → MULTI, ручной WARP/OPERA/VLESS отклоняется, restore
  non-MULTI не выполняется. `ip rule` покрывает все UID, кроме служебного
  10997; IPv6 `unreachable default`; новые приложения попадают в VPN сразу.
  UI: точная область (Nova UID 10997 исключён, tethering не подтверждена),
  FULL TUNNEL / Strict не активен / LIMITED / Lockdown; после отказа backend —
  «НЕ ПОДКЛЮЧЕНО» + причина, зависание «ПОДКЛЮЧЕНИЕ» исправлено.
  Дополнительно: DNS NXDOMAIN — PASS (VPN остался активен); потеря upstream
  (`svc data disable`) — PASS (VPN/router/tun0/правила живы, приложение без
  ответа); отказ MULTI через DIRECT-override — PASS (без WARP, retry);
  Wi-Fi↔LTE — PASS (Strict-конфиг пересобран); Lockdown ON — PASS (UI ВКЛ,
  при остановке VPN Firefox `NS_ERROR_OFFLINE`); Lockdown/Always-on возвращены
  OFF. NOT TESTED: потеря primary-процесса (kill запрещён без root), resolver
  outage (Private DNS на ColorOS недоступен), tethering (platform NOT_VPN,
  защита не заявляется). Пользователи: VPN-правила `uidrange 0-99999`
  покрывают только user 0; user 10/999 вне Strict — межпользовательская
  защита не заявлена. Версия `1.34.12/176`; Strict в поставку не включать до
  финальной приёмки.
- **P-E.2B.3.1 аудит исключения Nova (2026-11-14) — accepted with
  limitation.** `docs/fork/PE2B31-NOVA-EXCLUSION-AUDIT.md`:
  `addDisallowedApplication(packageName)` исключает весь UID 10997;
  `protect()` — только конкретный fd. Без исключения ломаются opera-proxy
  (child), nova-router (child, `ws` без `via`), tun2proxy (нет
  protect-callback), часть Java-сокетов. Решение: оставить исключение и честно
  указывать область; protect-callback/отдельный UID отложены.
  Код заморожен до появления рабочего non-RU endpoint. Не трогать
  `awg_socks_udp.go`, `nova-router`, `SupportsUDP`, strict first slot, TCP
  dispatcher и правила маршрутизации.
- **P1.2 UDP dispatcher — PASS на контролируемом peer.** `nova-router` больше
  не отправляет UDP напрямую: каждый датаграм проходит политику и первый слот
  правила; `udp:true` → SOCKS5 UDP ASSOCIATE к слоту, `ATYP=DOMAIN`
  сохраняется, иначе drop без fallback. Android end-to-end:
  `ROUTER_UDP_DOMAIN PASS` (`youtube.com:9000` → inner `10.77.0.1:9000`) и
  `ROUTER_UDP_DNS PASS`. Подробности — `docs/fork/VERIFICATION.md`.
- **P2.2 внешний UDP — PASS на WARP:** DNS `1.1.1.1:53` и STUN через
  `nova-router` работают, egress `104.28.230.248:40314` совпадает с TCP,
  физический IP `194.34.98.234` не утекает. **P2.3 non-RU — BLOCKED:** на
  проверенных Wi-Fi и LTE маршрутах WARP дал RU-адреса (`104.28.230.248`,
  `104.28.198.247`); штатный PROTON на обеих сетях не поднялся
  (`no_traffic`). Нужен один официальный Proton NL `.conf` или VPS NL/DE.
- **P2 остаётся блокером:** внешний Proton/WARP endpoint не всегда завершает
  handshake, поэтому UDP в реальный интернет может не проходить. На локальном
  WireGuard-peer диспетчер доказан; `nova-router`/`awg_socks_udp.go` готовы.
- **P-C DNS (2026-11-14):** `dnsai` — resolver-only, egress-слот удалён;
  старый `dnsai`-слот отклоняется `Config.validate()`. MULTI использует
  virtual DNS tun2proxy: router видит TCP-домен/SNI, но UDP — `ATYP=IPv4`,
  QNAME для QUIC недоступен. `LocalDnsProxyManager`/`DnsResponseCache` в MULTI
  не работают. DoH/DoT — только endpoint/SNI, без MITM. AI UDP/443 и AI без
  verified non-RU — DROP. `ping youtube.com` — системный netd/Private DNS
  (`dns.geohide.ru`), не router; app-путь YouTube TCP работает.
- **P-E IPv6 (2026-11-14):** baseline `docs/fork/PE1-BASELINE.md`:
  LTE/Wi-Fi без глобального IPv6 (`NO BASELINE`); MULTI UID 10155 →
  table 1175 `unreachable default` (route-level DROP). WARP: Firefox UID 10918
  в allow-list, `default dev tun0`; IPv4 PASS (`104.28.198.244`). Ранний
  `ipv6.icanhazip.com` FAIL (error 131) **не воспроизвёлся**. Контролируемый
  прогон с временными счётчиками (debug-AAR, затем удалены): полный путь
  TUN Read IPv6 → bind TX → bind RX → TUN Write PASS, Firefox отрисовал
  `2a09:bac5:5157:...` (RDAP: CLOUDFLAREWARP), 6/6 IPv6-запросов PASS на
  чистой release-сборке. Вывод: WARP selected-app IPv6 — **PASS** (закрыт);
  ранний FAIL по времени совпал с `Retrying handshake ... after 15 seconds` —
  это **вероятная причина** временной нестабильности сессии, не доказанная
  первопричина; дефект Go-core не подтверждён. Literal `2606:4700:4700::1111` —
  DNS-резолвер, не endpoint. P-E.2B не начат; `::/0` в MULTI не добавлялся.
- **P-D AUTO (2026-11-14):** `RouterPolicyRequirements` — AUTO выбирает MULTI
  при smart routing, service override != auto или kill switch; иначе прежний
  WARP/Opera. При обязательном MULTI отказ не переводит на WARP. Ручные режимы
  не подменяются. UI показывает data plane; Kill Switch warning вне MULTI;
  foreign-default отключён как неработающий. Тесты 693/0; device AUTO без
  требований → WARP + hint, override-прогон NOT VERIFIED.
- **Ограничения P-C:** IP→domain mapping запрещён без достоверных A/AAAA
  (tun2proxy их не отдаёт); IPv6/AAAA DNS не покрыт; resolver failure не даёт
  direct fallback. Полный отчёт — `docs/fork/VERIFICATION.md`, раздел P-C.
- **Capability больше не привязана к порту 1378:** `multiPrimaryKind`/
  `multiCfProxyKind` фиксируют транспорт при создании слота.
- Перед тестами обязателен чистый цикл:
  `.\tools\rebuild-install-clean.ps1 -Variant release` (clean → release →
  install → `pm clear` → launch). Флаг `-KeepData` сохраняет prefs/учётки.
- **Non-RU выход (проверено):** при живом Opera default-маршрут —
  `secondary → primary → spoof → direct`; Chrome показывает
  `77.111.246.61` / Ashburn / **US**. Telegram и YouTube идут закреплёнными
  правилами через `primary` и не затрагиваются.
- **Источник Opera сейчас — ПК:** `.\tools\enable-opera-bridge.ps1` (ждёт
  телефон, делает `adb reverse tcp:1371 tcp:1371`, проверяет мост). На ПК
  запущен `opera-proxy` (US). Без USB-кабеля мост исчезает, и default
  откатывается на `primary` (RU). Для автономности нужен рабочий релей
  `relay.nova-app.eu` с актуальным ключом Android или свой EU-VPS.
- **Раздельное туннелирование:** после смены режима (например, на «Все»)
  обязателен полный reconnect: мягкий REAPPLY не перестраивает allow-list
  системного VPN. Проверено на устройстве: смена режима перезапускает сеанс,
  `dumpsys` показывает UIDs `0-99999`, Chrome снова в туннеле. В коде
  `SessionReapply` дополнительно относит `NovaMultiVPN`/`NovaDesyncVPN`/
  `NovaDnsVPN`/`NovaVlessVPN` к tun2proxy-сеансам.
- **AI-приложения (обновлено 2026-11-13):** в MULTI + Opera ChatGPT app
  получает 403 (`android.chat.openai.com` → `Request is not allowed`), Gemini
  висит на QUIC/UDP 443. **Рабочий вариант — PROTON (AWG NL):** egress
  `212.8.253.155` (NL), HTTP/3 проходит, Gemini Android отвечает, ChatGPT app
  отвечает, Claude/DeepSeek/Qwen открываются, Telegram/YouTube работают.
  Это режим PROTON, а не MULTI: в MULTI нет non-RU UDP-слота. Для Gemini
  в MULTI нужен UDP-capable слот (Proton/AWG с UDP ASSOCIATE или VLESS).
- **Нестабильность Proton:** рабочий узел `212.8.253.155` (NL) подтверждён, но
  повторные подключения после переустановки APK ушли в «ПОДКЛЮЧЕНИЕ... AWG
  Proton» на 3+ минуты и были остановлены. Бесплатные узлы Proton перебираются
  медленно; для стабильности нужен свой VPS/AWG или платный Proton.
- **ChatGPT-маршрут:** все домены OpenAI/ChatGPT сведены в одно правило
  `openai` до `google-ai`: `chatgpt.com`, `chat.openai.com`, `auth0`,
  `ws.chatgpt.com`, `workos.com`, `challenges.cloudflare.com` и др. Порядок
  `secondary → vless`, fail-closed — один IP на авторизацию, API и WebSocket.
- **AI-группы:** `openai`, `anthropic`, `china-ai`, `google-ai`, `ai` —
  независимые правила поверх общих слотов. Claude — fail-closed
  `secondary → vless`; DeepSeek/Qwen — `direct`. Списки в
  `assets/lists/anthropic.txt` и `china_ai.txt`; новый сервис добавляется
  списком и одной строкой порядка в `RouterConfigFactory`.
- **Релей с Android не работает на этой сети:** с PC-ключом мост стартует, но
  TCP до `relay.nova-app.eu:8443` с LTE уходит в timeout 8 с. Остаётся
  ПК-мост `tools/enable-opera-bridge.ps1` или свой VPS.
- Полная карта работ и блокеров:
  `docs/plans/2026-11-13-android-route-recovery-and-release.md`.
- Следующий шаг: подключить телефон по USB (мост Opera), проверить
  controlled restart на смене split-режима; для AI-приложений — чистый
  non-RU egress с UDP (VPS/релей).

## 1. Текущее состояние

- Ветка: `personal`, версия **1.34.12** / `versionCode 176` (OBNOVA).
- KALIOPA Phase 1+2: меню начинается с «Профили и подписки», «Конфигурации»
  удалены; `UnifiedProfileStore` собирает WARP/VLESS/Proton в одну базу, шесть
  VLESS-источников, `google_ai`/Spotify-правила, Opera same-egress + WARP-AWG
  bootstrap. План и статус — `docs/plans/2026-11-09-kaliopa-android-parity.md`.
- Встроенный апдейтер включён для `github`-сборки (`UPDATER_ENABLED = true`) и
  читает релизы `abonent0007/OBNOVA`; у `fdroid` он выключен. Подписки (VLESS,
  WARP) и списки остаются стоковыми.
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
