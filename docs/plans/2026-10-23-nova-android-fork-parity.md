# План: Nova Android — parity с PC-форком

> Черновик создан в Plan mode. Записывать в `C:\Users\User\Documents\Nova-Android-Fork`
> в этом режиме нельзя; после переключения агента в build/coder файл нужно перенести
> в `docs/plans/` Android-репозитория.

## Цель

Повторить в `Nova-Android-Fork` проверенные улучшения PC-форка, сохранив 100%
стоковой логики загрузки профилей из облака. Обновить версию, убрать официальный
апдейтер из UI, сделать Matrix темой по умолчанию с анимированным дождём, добавить
локальные TLD → DIRECT, TTL-кэш DNS и ротацию профилей по RTT/ошибкам. Довести
release APK до состояния без тестовых заглушек и проверить на эмуляторе.

## Текущее состояние (аудит)

- `app/build.gradle.kts`: `versionName = "1.33"`, `versionCode = 163`,
  `compileSdk = 35`, `targetSdk = 34`, `minSdk = 24`, NDK 27.2.
- Release уже использует R8 + `shrinkResources`; `useLegacyPackaging = true`.
  `keystore.properties` и `local.properties` отсутствуют.
- `AppUpdateManager`: два APK-фида (`confeden/nova_updates/apk_version.json` и
  GitHub Releases); `BuildConfig.UPDATER_ENABLED = true` для flavor `github`.
  Update UI: строка в `SettingsActivity`/`activity_settings.xml`, плашка в
  `MainActivity` (`btnInstallUpdate`, `UpdateChipView`), уведомления/WorkManager.
  `AppUpdateManager` также содержит helper'ы foreground-уведомления VPN — класс
  целиком удалять нельзя.
- `ProfileRotation` — только порядок/понижение профилей; `LiveNodeRotation`
  отключён (`MAX_ROTATIONS_PER_SESSION = 0`) намеренно по замерам. RTT/failure-
  ротации уровня PC в Android нет.
- `DomainBypassActivity` при первом запуске включает `ru`, `su`, `cyrillic`;
  `DomainBypassRules` и `nova-core/dnsname/zone.go` умеют зоны/домены/punycode.
  Нет предустановленных `.москва`, `.moscow`, `.рус`, `.дети`, `.tatar` и
  встроенного списка российских сервисов на `.com`.
- `LocalDnsProxyManager` уже умеет plain/DoH/DoT, но без TTL-кэша.
- `NovaTheme` уже содержит палитру `matrix`, но `DEFAULT_KEY = "aurora"`;
  `NovaNetworkBackgroundView` — анимация частиц, не цифровой дождь.
- `NovaMainBackdrop` — фактура/растровый фон главного экрана.
- Профили из облака: `ProtonProfileManager`, `VlessSubscriptionManager`,
  `VlessSubscriptionFetcher`, `WarpProfileGenerator`, `GatewayEndpoints`,
  `ProtonProfileStore` — **не трогать**.
- Эмулятор: AVD `Pixel_7_Pro`; `adb` работает, устройство сейчас не запущено.
- JDK 17 использовать через `JAVA_HOME`; `java` в PATH сейчас 21.

## AGI-консультация и решения

Совет: Duck (план + критика) и Google AI Mode; Claude Haiku позже ответил в
fast-режиме. Общий вывод: порядок — версия/апдейтер, локальные TLD, DNS TTL,
ротация, Matrix, release-тесты.

Корректировки к ответам AGI:
- `confeden/nova_updates` — не опечатка, это APK-фид; profile sync отдельный.
- `MAX_ROTATIONS_PER_SESSION = 0` — не заглушка, а измеренное решение. Не включать
  его обратно; PC-ротация — новый слой поверх списка профилей.
- `android.enableDexCompress` не подтверждён в документации AGP. Не использовать
  несуществующий флаг; безопасный путь — R8 full/resource shrinking и замер APK.
- `android.r8.optimizedResourceShrinking` и
  `android.r8.strictFullModeForKeepRules` существуют в AGP 9.x; включать после
  проверки keep-правил JNI/Go/Xray.
- Android-версия не обязана совпадать с PC; версия — независимый fork-релиз.

## Фазы

### Phase 0 — Baseline и ветка

- Убедиться, что ветка `personal`, рабочее дерево чистое.
- Собрать baseline: `gradlew.bat test`, `lint`, `assembleGithubDebug`.
- Записать размер/хеш debug APK и текущее поведение: updater, Matrix, TLD,
  профили, DNS.
- Запустить AVD `Pixel_7_Pro`, установить baseline, снять скриншоты.
- Acceptance: baseline APK устанавливается, VPN стартует, логи без падений.

### Phase 1 — Fork identity, версия, updater

- `versionName = "1.34.0"`, `versionCode = 164`; добавить
  `BuildConfig.FORK_IDENTIFIER = "personal"` (или flavor).
- `UPDATER_ENABLED = false` для `github` и `fdroid`; убрать/скрыть:
  - строку обновления в `SettingsActivity`/`activity_settings.xml`;
  - плашку `btnInstallUpdate`/`UpdateChipView` в `MainActivity`;
  - уведомления и периодические WorkManager-задачи.
- Оставить `AppUpdateManager` notification-helper'ы; update-методы должны
  возвращать no-op при `UPDATER_ENABLED=false`.
- Не менять `ProtonProfileManager`, `VlessSubscription*`, `WarpProfileGenerator`,
  `Gateway*`; загрузка профилей из облака остаётся стоковой.
- Release signing: локальный `keystore.properties` + JKS вне git; для теста —
  debug. Личный release собирать с `NOVA_ALLOW_UNPUBLISHABLE_BUILD=1` или
  отдельным `personal` flavor, потому что stock relay key `nova-android-163`
  не совпадёт с новым versionCode. Ключ релея не ротировать: он принадлежит
  upstream-инфраструктуре. Если сервер его отклонит — API-регистрация уйдёт в
  direct fallback; cloud profiles это не затрагивает.
- Acceptance: в UI нет пунктов обновления; в логах нет запросов к APK-фидам;
  `versionName/versionCode` новые; cloud profile sync работает.

### Phase 2 — Local TLD → DIRECT и DNS TTL

- Ввести единый список локальных TLD (Kotlin + Go, синхронно):
  `ru`, `su`, `рф`/`xn--p1ai`, `москва`/`xn--80adxhks`, `moscow`,
  `рус`/`xn--p1acf`, `дети`/`xn--d1acj3b`, `tatar`.
- `DomainBypassActivity`: first-run defaults + чекбоксы для новых зон; не ломать
  существующие `ru/su/cyrillic`.
- `DomainBypassRules`/`nova-core/dnsname/zone.go`: тесты на Unicode и punycode;
  `xn--` уже декодируется, проверить границы (`notru`, `67ozon.ru`).
- Встроенный `user_direct`-список российских сервисов на `.com`:
  `vk.com`, `yandex.com`, `yandex.net`, `yastatic.net`, `userapi.com`, `vk.me`,
  `mycdn.me`, `rt.com`, `tass.com`, `sputniknews.com` — как редактируемый
  built-in, не как облачный профиль.
- `LocalDnsProxyManager`: добавить TTL-кэш (positive/negative, expiry, LRU),
  парсить TTL из DoH/DoT-ответа; при ошибке/истечении — live query; кэш не
  должен влиять на cloud profile sync.
- Acceptance: Kotlin/Go unit tests; на эмуляторе `.ru/.su/.рф/.москва/.moscow/
  .рус/.дети/.tatar` идут DIRECT, `.by/.kz/.com` — по политике; повторный DNS-
  запрос берётся из кэша до истечения TTL.

### Phase 3 — Ротация профилей по RTT/ошибкам

- Новый чистый `ProfileHealthRotation.kt` по образцу PC
  `nova_profile_rotation.py`: baseline EWMA, 3 деградированных сэмпла,
  cooldown 15 мин, не более 2/час, кандидат ≥30% лучше по RTT, explicit profile
  защищён, только одна группа, без mid-flow switch.
- Интегрировать в `NovaVpnService`/`ClientData`/profile stores: выбирать
  существующий профиль группы (MASQUE/Proton/VLESS/Custom), не обращаться к
  серверу профилей; ротация только для новых соединений.
- Не включать `LiveNodeRotation` (`MAX_ROTATIONS_PER_SESSION=0`); это другой,
  измеренно неудачный механизм.
- Acceptance: unit tests с fake clock; на эмуляторе — 2+ профиля, cooldown,
  explicit profile, нет лишних переключений.

### Phase 4 — Matrix theme + digital rain

- `NovaTheme.DEFAULT_KEY = "matrix"`; остальные темы сохранить.
- Новый `MatrixRainView` (Canvas, не WebView): колонки символов, зелёный
  люминофор, моноширинный шрифт, 30–45 fps, lifecycle `onAttached/onDetached`,
  отключение при reduced motion/низкой батарее.
- Подключить как фон главного экрана (вместо/поверх
  `NovaNetworkBackgroundView` при Matrix); для настроек — существующая Matrix
  палитра; не ломать `NovaMainBackdrop`.
- Acceptance: Matrix по умолчанию, анимация плавная на Pixel_7_Pro, нет утечек,
  другие темы не сломаны.

### Phase 5 — Hardcode/stub audit и release

- Пройти `TODO/FIXME/example.com/localhost/test/debug/stub` по `app/` и
  `nova-core/`; удалить тестовые URL/фейковые данные из release; оставить и
  документировать намеренные константы (relay key, `MAX_ROTATIONS_PER_SESSION=0`,
  version literals).
- R8: сохранить `isMinifyEnabled`/`isShrinkResources`; добавить keep-правила для
  JNI/Go/Xray/reflection; проверить release.
- Размер: измерить `unzip -lv` (dex/native), включить
  `android.r8.optimizedResourceShrinking=true` и, после проверки,
  `android.r8.strictFullModeForKeepRules=true`. Не использовать
  `android.enableDexCompress` — в документации AGP 9.x такого публичного флага
  нет. «Dex-сжатие» трактовать как R8/resource shrink + фактическую проверку
  сжатия dex в APK.
- `targetSdk = 35` — отдельная задача после тестов edge-to-edge; не смешивать с
  parity.
- Acceptance: release APK собирается, устанавливается, все функции работают,
  размер не хуже baseline; нет тестовых заглушек.

### Phase 6 — Эмулятор и приёмка

- `emulator -avd Pixel_7_Pro`, `adb wait-for-device`.
- `gradlew.bat test`, `lint`, `assembleGithubDebug`, `assembleGithubRelease`.
- Проверить: старт/стоп VPN, Matrix rain/FPS, local TLD direct, DNS cache,
  ротацию/cooldown, отсутствие update UI/запросов, cloud profile loading,
  импорт подписок, логи `adb logcat`.
- Снять скриншоты Matrix/настроек; проверить release R8 на JNI/Go.
- Acceptance: зелёные тесты/lint; signed release APK; отчёт с размерами и
  результатами.

## Файлы (ориентир)

- Build: `app/build.gradle.kts`, `gradle.properties`, `proguard-rules.pro`.
- UI: `MainActivity.kt`, `SettingsActivity.kt`, `activity_settings.xml`,
  `activity_main.xml`, `NovaTheme.kt`, `NovaNetworkBackgroundView.kt`,
  новый `MatrixRainView.kt`.
- Update: `AppUpdateManager.kt`, `UpdateCheckWorker.kt`, `UpdateActionReceiver.kt`,
  `NovaBootReceiver.kt` (guards only; notification helpers сохранить).
- Routing/DNS: `DomainBypassActivity.kt`, `DomainBypassRules.kt`,
  `LocalDnsProxyManager.kt`, `ClientData.kt`, `NovaVpnService.kt`,
  `nova-core/dnsname/zone.go`, `nova-core/engine/domain_bypass.go`.
- Rotation: `ProfileRotation.kt`, новый `ProfileHealthRotation.kt`,
  `NovaVpnService.kt`, profile stores.
- Tests: `app/src/test/...`, `nova-core/dnsname/*_test.go`.

## Риски

| Риск | Митигация |
|---|---|
| Смена подписи APK | personal keystore; старую upstream-подпись удалить/переустановить, иначе Android не обновит |
| Relay key 163 не совпадёт с 164 | не ротировать ключ; personal build с `NOVA_ALLOW_UNPUBLISHABLE_BUILD=1`; direct fallback |
| R8 ломает JNI/Go/Xray | keep-правила, release-тест на эмуляторе, сравнение с debug |
| Matrix rain жрёт батарею | 30–45 fps, lifecycle, reduced motion, battery guard |
| TLD direct создаёт утечку/обход | тесты зон, не включать `.by/.kz/.ua` по умолчанию |
| Ротация рвёт активный поток | только новые соединения, cooldown, explicit profile protected |
| Cloud profiles затронуты | не менять `ProtonProfileManager`, `VlessSubscription*`, `WarpProfileGenerator`, `Gateway*` |

## Решения (согласовано 2026-10-23)

1. Версия — `versionName = "1.34.0"`, `versionCode = 164` (заметный fork-релиз).
2. Подпись — новый личный `nova-personal.jks` вне git. Проверено: инструменты уже
   в системе — JDK 17 `C:\Program Files\Microsoft\jdk-17.0.18.8-hotspot`
   (`keytool.exe` OK), `ANDROID_HOME=C:\Android`, build-tools 34/35/36,
   `apksigner.bat` 35.0.0 OK. Готовых Nova-ключей нет; чужие keystore
   (`spectroduo`, `bigboss`, `chakral`) не переиспользовать.
   Расположение: `C:\Users\User\Documents\keystore_nova\nova-personal.jks`,
   `keystore.properties` — в корне проекта (уже в `.gitignore` вместе с `*.jks`).
   Старую upstream-подпись на устройстве придётся удалить (uninstall) перед установкой.
3. Matrix — тема по умолчанию на всех экранах, селектор тем в настройках
   сохраняется; цифровой дождь — только фон главного экрана.
4. Relay key `nova-android-163` остаётся стоковым; personal release собирается с
   `NOVA_ALLOW_UNPUBLISHABLE_BUILD=1`. При отказе сервера — direct fallback,
   cloud profiles не затрагиваются.
