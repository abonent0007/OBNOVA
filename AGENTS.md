# AGENTS.md — Nova Android (личный локальный форк)

> Правила для агентов, работающих с этим форком. Человеческая документация — `README.md`.
> Глубокие детали — `docs/`; открывай только то, что относится к задаче.

## Что это за проект

Nova для Android: приложение с активной адаптацией к DPI-фильтрации. Стек: Kotlin + Gradle
(Android), Go-ядро `nova-core` (AmneziaWG, MASQUE), `nova-xray` (VLESS/REALITY), tun2proxy
и opera-proxy. Версия приложения — в `app/build.gradle.kts` (`versionName`/`versionCode`).

## Текущее состояние личного форка

- Версия **1.34.5 / versionCode 169** (OBNOVA).
- Встроенный апдейтер включён для `github`-сборки (`BuildConfig.UPDATER_ENABLED = true`)
  и читает релизы **`abonent0007/OBNOVA`** через GitHub API; `fdroid`-сборка
  апдейтер не включает (правило каталога). Облачные профили (Proton, VLESS, WARP)
  не затронуты.
- Тема по умолчанию — **Matrix**; на главном экране работает `MatrixRainView`
  (цифровой дождь), остальные темы сохраняют прежний фон.
- Локальные TLD (`.ru`, `.su`, `.рф`, `.москва`, `.moscow`, `.рус`, `.дети`,
  `.tatar`) и встроенные direct-домены PC-форка включены по умолчанию через
  domain bypass (WARP/MASQUE, TCP 80/443 — ограничение Android).
- `LocalDnsProxyManager` использует TTL-кэш `DnsResponseCache`.
- В режиме `auto` работает `ProfileHealthRotation` (3 деградации, cooldown 15 мин,
  2 смены/час, ручной выбор защищён).
- Главный экран: каналы — macOS-dock из SVG-иконок (`DockRadioButton`,
  `FlowRadioGroup.dockMode`), одна выбранная иконка; при перетаскивании иконка
  под пальцем раскрывается, прежняя сжимается, выбор фиксируется на отпускании.
  Функции выбранного узла — отдельной плиткой ниже. Системные insets учтены
  (`applySystemBarInsets`). Настройки — SVG-гамбургер в правом верхнем углу;
  выбор узла подтверждается верхней плашкой `tv_top_notice`, а не Toast внизу.

Документация форка:

- `docs/architecture/ARCHITECTURE.md` — карта проекта.
- `docs/fork/CHANGELOG.md` — что и где изменено.
- `docs/fork/AGENT-HANDOFF.md` — инструкция следующему агенту.
- `docs/fork/VERIFICATION.md` — фактические прогоны.
- `docs/plans/2026-10-23-nova-android-fork-parity.md` — план.

## Публичный форк (решение владельца, 2026-11-04)

- Репозиторий `abonent0007/OBNOVA` **публичный**: исходники, история и релизы
  доступны всем. Это осознанное решение владельца — без него встроенная
  проверка обновлений не может читать GitHub API без токена.
- `upstream` = https://github.com/confeden/Nova-Android (ветка `main`), рабочая ветка — `personal`.
- Обновление upstream: `git fetch upstream`, затем `git log --oneline personal..upstream/main`
  и merge/rebase в `personal`.
- Встроенные узлы, профили и подписки остаются стоковыми: не менять источники и логику их
  обновления (WARP/MASQUE, Opera, Proton, импорт подписок).
- Секреты и локальные данные не коммитить: `local.properties`, `keystore.properties`,
  `*.jks`, `*.keystore` (см. `.gitignore`). Ключ релея в `app/build.gradle.kts`
  публичен намеренно (он и так лежит в APK и меняется каждый выпуск).

## Сборка

- PC-форк (`Nova-main`) использует этот каталог через junction
  `C:\Users\User\Documents\Nova Android` (пути `../../Nova Android` в `go.mod`). Для Go-сборки PC
  сначала выполнить `tools/deps/fetch_go_deps.sh`.
- Требуются JDK 17 (`JAVA_HOME`), Android SDK (compileSdk 35, targetSdk 34) и
  Gradle wrapper 9.3.1. AVD для проверки — `Pixel_7_Pro`; MCP android-emulator
  глобально выключен, используйте `adb`/`emulator` как fallback.
- Debug: `./gradlew assembleGithubDebug` (Windows: `gradlew.bat assembleGithubDebug`);
  результат — `app/build/outputs/apk/github/debug/`.
- Release: `./gradlew assembleGithubRelease` — требует `keystore.properties`;
  ключ релея остался `nova-android-164`, а versionCode теперь 165, поэтому
  personal-сборка идёт с `NOVA_ALLOW_UNPUBLISHABLE_BUILD=1`. Для публикуемой
  сборки нужен ключ из upstream под 165. Без WSS-секрета:
  `NOVA_ALLOW_UNSIGNED_RELEASE=1` (см. `docs/fork/AGENT-HANDOFF.md`).
- F-Droid: `./gradlew assembleFdroidRelease`.
- Пересборка Go-ядра (только при его изменении): `tools/build_nova_core_aar.sh`.
- Пересборка VLESS-ядра: `cd nova-xray && ./build.sh`.
- Полное описание — `docs/BUILD.md`.

## Структура

| Область | Где |
|---|---|
| Приложение: UI, VPN-служба, шлюз, разбор конфигураций | `app/` |
| Go-ядро: AmneziaWG, MASQUE, подбор узлов, DNS | `nova-core/` |
| Обёртка Xray-core и JNI для VLESS/REALITY | `nova-xray/` |
| Вендоренный Xray-core | `tools/xray-core/` |
| Сборка и архитектурные решения | `docs/` |
| Карта архитектуры и журнал форка | `docs/architecture/ARCHITECTURE.md`, `docs/fork/` |
| Новые слои форка | `DnsResponseCache.kt`, `ProfileHealthRotation.kt`, `MatrixRainView.kt` |

## Проверки

- Сборка: `./gradlew assembleGithubDebug`.
- Тесты/линт: `./gradlew test`, `./gradlew lint`.
- Release после R8 проверять на AVD отдельно: JNI/Go/Xray могут ломаться только
  в минифицированной сборке.
- CI публикации APK: `.github/workflows/publish_apk_update.yml` — в личном форке
  не использовать для публикации.

## Границы

- Не менять stock-источники профилей, подписок и встроенных узлов.
- Не коммитить сборки, ключи и `local.properties`.
- Не отправлять изменения в upstream; публичный репозиторий форка — `abonent0007/OBNOVA`.

<!-- Added: 2026-11-03 -->
## Versioning for OBNOVA releases
Every subsequent release bumps the version: versionName 1.34.2, 1.34.3, ... and versionCode +1 (166, 167, ...) in app/build.gradle.kts. A release includes: bump versionName/versionCode, update docs/fork/CHANGELOG.md and docs/fork/VERIFICATION.md, rebuild arm64-v8a / armeabi-v7a / universal (release + debug) APKs, create a git tag vX.Y.Z, and publish a GitHub release with fresh SHA-256 values. Never reuse the previous version number for a changed build.
