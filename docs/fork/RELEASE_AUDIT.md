# Аудит release-сборки: хардкоды, заглушки, R8

> Проверка выполнена 2026-10-23 по `app/src/main` и `nova-core`. Цель — убрать
> тестовые значения, не выкинув намеренные константы, от которых зависит
> маршрутизация, протоколы или подпись.

## Что убрано/заменено

| Было | Стало | Почему |
|---|---|---|
| `android:hint="ozon.ru&#10;example.com"` в экране доменов | `ozon.ru&#10;vk.com` | `example.com` — тестовый пример, не пользовательский домен |
| DNS-подсказки `dns.example.com` | `dns.google` | реальный публичный DoH/DoT, понятный пример |
| `android.r8.optimizedResourceShrinking=false` | строка удалена (дефолт `true`) | явный `false` отключал оптимизацию ресурсов и увеличивал APK |
| Нет keep-правил для gomobile/Xray | добавлены `nova.**`, `go.**`, `XrayBridge` | JNI ищет классы/методы по именам; release R8 не должен их переименовывать |

## Оставлено намеренно (не заглушки)

| Место | Почему |
|---|---|
| `127.0.0.1` в DNS/Opera/Tor/VLESS | loopback-порты локальных прокси и DNS |
| `8.8.8.8:53` и `example.com` в `NovaVpnService` (Proton control probe) | реальная проверка живости сокета, не тестовые данные |
| `relayKeyId = nova-android-164`, `relayKeyToken` | текущий публичный ключ upstream 1.33.1; совпадает с `versionCode 164`. Нужен для регистрации Opera/Proton/Cloudflare. Не менять без новой upstream-версии |
| `TG_CF_WS_SECRET`/`NovaRelay` пустые в личной сборке | нет доступа к инфраструктуре nova-app.eu; WSS-релей Telegram просто отключён |
| `LiveNodeRotation.MAX_ROTATIONS_PER_SESSION = 0` | измеренное решение: бесшовная смена узла давала ложные срабатывания. Не включать без новых замеров |
| `AppUpdateManager` | класс содержит helper'ы foreground-уведомления VPN; сетевые входы закрыты `UPDATER_ENABLED=false`, UI скрыт |
| `traffic_mask_russia.sni` с `stub`/`localhost` в именах | это данные маски SNI, а не код-заглушка |
| `android.builtInKotlin=false`, `android.newDsl=false` и соседние | текущая совместимость AGP 9.1; менять отдельной миграцией |
| `android.r8.strictFullModeForKeepRules=false` | включать только после проверки всех keep-правил JNI/Go/Xray; сейчас оставлено безопасное значение |
| `useLegacyPackaging=true` | сжимает native `.so` в APK; это не dex и не ломает JNI |

## «Dex-сжатие»

В AGP 9.x нет подтверждённого публичного флага `android.enableDexCompress`.
Не добавлять его. Безопасные рычаги:

1. R8 (`isMinifyEnabled`) и `shrinkResources` уже включены в release.
2. `optimizedResourceShrinking` теперь на дефолтном `true`.
3. Фактическое сжатие проверять по APK:
   ```powershell
   # список методов сжатия entries
   Add-Type -AssemblyName System.IO.Compression.FileSystem
   [IO.Compression.ZipFile]::OpenRead("Nova_1.34.0.apk").Entries |
     Select-Object FullName, CompressedLength, Length, @{n='Method';e={$_.CompressionMethod}}
   ```
4. `zipalign`/`apksigner` — штатные шаги AGP; вручную zip не перепаковывать.

## R8/keep

- `-keepclasseswithmembernames class * { native <methods>; }` — базовое.
- `nova.**`, `go.**` — gomobile/JNI.
- `XrayBridge` — c-shared JNI.
- `org.torproject.jni.TorService`, `net.freehaven.tor.control.**` — уже были.
- `com.example.operaproxy.ProxyVpnService` — уже был.
- `NovaViewInflater` — уже был.
- Release проверять на устройстве: если падает JNI/Go, смотреть `UnsatisfiedLinkError`
  и добавлять точечный keep, а не отключать R8 целиком.

## Release-подпись

- Локальный `nova-personal.jks` и `keystore.properties` созданы, оба в
  `.gitignore`; в репозиторий не попадают.
- Сборка: `NOVA_ALLOW_UNSIGNED_RELEASE=1` (нет WSS-секрета). Ключ релея 164
  совпадает с versionCode 164, поэтому `NOVA_ALLOW_UNPUBLISHABLE_BUILD` больше
  не нужен. APK подписан личным keystore, потому что `keystore.properties`
  существует.
- Установка поверх upstream-сборки невозможна из-за другой подписи: старую
  версию нужно удалить (потеря данных приложения).
