# Документация личного форка

| Файл | Для чего |
|---|---|
| `docs/architecture/ARCHITECTURE.md` | карта модулей, потоков данных и границ |
| `docs/fork/CHANGELOG.md` | что, где и когда изменено |
| `docs/fork/AGENT-HANDOFF.md` | как продолжить работу следующему агенту |
| `docs/fork/VERIFICATION.md` | фактические тесты, сборки и проверки на AVD |
| `docs/fork/RELEASE_AUDIT.md` | хардкоды/заглушки/R8/подпись/размер APK |
| `docs/plans/2026-10-23-nova-android-fork-parity.md` | исходный план parity с PC-форком |

Правила: не публиковать форк, не менять облачные источники профилей, не
коммитить keystore/секреты. Перед релизом — свежий `gradlew test` и проверка на
устройстве/эмуляторе, совместимом с ABI APK.
