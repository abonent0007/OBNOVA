# AGENTS.md — Nova Android (личный локальный форк)

> Правила для агентов, работающих с этим форком. Человеческая документация — `README.md`.
> Глубокие детали — `docs/`; открывай только то, что относится к задаче.

## Что это за проект

Nova для Android: приложение с активной адаптацией к DPI-фильтрации. Стек: Kotlin + Gradle
(Android), Go-ядро `nova-core` (AmneziaWG, MASQUE), `nova-xray` (VLESS/REALITY), tun2proxy
и opera-proxy. Версия приложения — в `app/build.gradle.kts` (`versionName`/`versionCode`).

## Личный локальный форк

- Это локальный форк **только для личного использования**. Не публиковать его и не пушить
  в публичные репозитории.
- `upstream` = https://github.com/confeden/Nova-Android (ветка `main`), рабочая ветка — `personal`.
- Обновление upstream: `git fetch upstream`, затем `git log --oneline personal..upstream/main`
  и merge/rebase в `personal`.
- Встроенные узлы, профили и подписки остаются стоковыми: не менять источники и логику их
  обновления (WARP/MASQUE, Opera, Proton, импорт подписок).
- Секреты и локальные данные не коммитить: `local.properties`, `keystore.properties`,
  `*.jks`, `*.keystore` (см. `.gitignore`).

## Сборка

- PC-форк (`Nova-main`) использует этот каталог через junction
  `C:\Users\User\Documents\Nova Android` (пути `../../Nova Android` в `go.mod`). Для Go-сборки PC
  сначала выполнить `tools/deps/fetch_go_deps.sh`.
- Требуются JDK 17, Android SDK Platform 34 и Gradle wrapper 9.3.1.
- Debug: `./gradlew assembleGithubDebug` (Windows: `gradlew.bat assembleGithubDebug`);
  результат — `app/build/outputs/apk/github/debug/`.
- Release: `./gradlew assembleGithubRelease` — требует `keystore.properties`.
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

## Проверки

- Сборка: `./gradlew assembleGithubDebug`.
- Тесты/линт — по наличию задач Gradle: `./gradlew test`, `./gradlew lint`.
- CI публикации APK: `.github/workflows/publish_apk_update.yml`.

## Границы

- Не менять stock-источники профилей, подписок и встроенных узлов.
- Не коммитить сборки, ключи и `local.properties`.
- Не публиковать форк и не отправлять изменения в upstream.
