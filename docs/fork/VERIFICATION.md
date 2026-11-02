# Проверки форка

> Только фактические прогоны. Если проверка не удалась из-за среды — это
> записано отдельно, а не замаскировано словом «работает».

## 2026-11-02 — lint: 0 errors

```text
.\gradlew.bat lintGithubDebug
BUILD SUCCESSFUL
0 errors, 883 warnings
```

Исправлено: 8 receiver flags, 1 implicit broadcast, 21 NewApi, 3 MissingPermission
(точечный suppress после `canNotify()`), 3 WrongCall (`super.onDraw`), 1 UseAppTint.
Глобальный baseline не добавлялся.

```text
.\gradlew.bat testGithubDebugUnitTest assembleGithubRelease
BUILD SUCCESSFUL in 1m 1s
tests=607 failures=0 skipped=9
```

Устройство (Android 13): установлен arm64-APK, WARP подключён, цикл
«Отключить/Подключить» прошёл без `SecurityException`/FATAL; crash-буфер пуст.
Проверка screen-off/on: приложение не упало, VPN остался жив; устройство после
этого осталось на secure lock screen (разблокировка — вручную).
API 24/25 и Android 14 на живом устройстве не проверялись: доступен только
аппарат с API 33; для API 24/25 добавлены guards/`ProcessCompat`, для API 34 —
флаги ресиверов.

Проверка кода:
- `ProcessCompat` — новый файл, используется в `DnsTunnelProcess` и
  `OperaProxyManager`.
- `WarpConfigsActivity`: `java.util.Base64` → `android.util.Base64`.
- `FrostOverlayView`: `clipOutPath` с fallback на `Region.Op.DIFFERENCE`.
- `NovaVpnService`: `LinkProperties.mtu` только с API 29.
- `MainActivity`, `NovaTileService`, `NovaVpnService`, `SettingsActivity`,
  `WarpConfigsActivity`: `ContextCompat.registerReceiver`.

## 2026-11-02 — pipeline: динамические источники и логика

### Статический анализ

```text
.\gradlew.bat lintGithubDebug
37 errors, 884 warnings
```

Ошибок в изменённых файлах — **0**; 37 ошибок и основная масса предупреждений
относятся к старому `AppUpdateManager`/legacy-коду (NewApi, receiver flags,
MissingPermission) и не входят в этот pipeline.

```text
nova-router:  go vet ./...   exit 0
nova-desync:  go vet ./...   exit 0
python tools/check_imports.py
проверено файлов: 69, неиспользуемых импортов: 0
python tools/check_imports.py --external
списки/сеть/файлы: у всех вызовов есть try/runCatching
```

### Тесты

```text
.\gradlew.bat testGithubDebugUnitTest
tests=607 failures=0 errors=0 skipped=9     # было 583
go test ./... (nova-router, nova-desync) — ok
```

Новые тесты: `ListManifestTest` (динамический набор файлов, sha, path
traversal), `ListCacheSignatureTest` (обновление кэша), `RoutingPrefsParserTest`
(межпроцессное чтение), `TldListTest`, `ServiceProbeTargetsTest`,
`ListRepositoryFallbackTest`, плюс проверки Telegram-цепочки и валидации
порядка слотов в Go.

### Устройство

```text
«Обновить списки»: 02.11.2026 15:34 · обновлено файлов: 12, generated=1793275259
LISTS: Обновлено: 02.11.2026 15:44 · обновлено файлов: 12, generated=1793275259
VLESS: Подписка: +43 / −47, всего 245
ROUTES: smart toggle checked=true suppress=false
ROUTES: smart saved=true
WARP + проверка сервисов: MULTI — router 127.0.0.1:1373 закрыт
MULTI + проверка сервисов:
  PROBE: OK RU — ya.ru · OK 302 · 269 мс |
         OK US — www.cnn.com · OK 302 · 691 мс |
         FAIL YouTube — youtube.com · Read timed out |
         FAIL Telegram — t.me · Read timed out
«Умная маршрутизация» выключена без force-stop → WARP: RU, ping 74–122 мс
crash-буфер пуст
```

## 2026-11-02 — прямой слот, VLESS и реальные сервисы

### Сборки и тесты

```text
cd nova-router && go test ./...
ok  nova-router  0.052s
```

```text
.\gradlew.bat testGithubDebugUnitTest assembleGithubRelease
BUILD SUCCESSFUL in 34s     # 583 теста, 9 skipped, 0 failures
```

```text
python elfcheck: libnova-router.so
type 3, machine 183, INTERP /system/bin/linker64   # arm64, NDK PIE
```

Первая сборка роутера без NDK дала `INTERP /lib/ld-linux-aarch64.so.1` и на
устройстве падала `Cannot run program … error=2`; после пересборки
`GOOS=android CGO_ENABLED=1 CC=aarch64-linux-android21-clang` процесс
поднимается, логи `ROUTER: … слушает 127.0.0.1:1373`.

Для armv7 собран и упакован `libnova-router.so` (static PIE, machine 40) и
`libnova-desync.so`: APK `OBNOVA_1.34.1-armeabi-v7a.apk` (37,3 МБ) содержит
оба бинаря. На armv7-железе не проверялись — устройства нет.

### Прямой слот до и после

До фикса (MULTI, `curl --socks5-hostname 127.0.0.1:1373`):

```text
https://ya.ru      -> 000 0.005
https://mail.ru    -> 000 0.005
https://2ip.ru     -> 000 0.004
```

После `nova-router/resolver.go`:

```text
https://ya.ru      -> 302 0.266
https://www.ya.ru  -> 302 0.308
https://mail.ru    -> 302 0.297
https://yandex.ru  -> 302 0.233
https://2ip.ru     -> 404 0.444
https://example.org-> 200 0.538
```

### MULTI: проверка сервисов через роутер

```text
RU (ya.ru)      — OK · 302 · 336 мс
US (cnn.com)    — OK · 302 · 897 мс
YouTube         — SocketTimeoutException: Read timed out
Telegram        — SocketTimeoutException: Read timed out
```

То же подтверждено из `adb shell`:

```text
https://ya.ru         -> 302 0.266
https://www.cnn.com   -> 302 0.723
https://www.youtube.com -> 000 12.002
https://telegram.org  -> 000 12.000
```

Причина: оператор рвёт TLS к YouTube/Telegram (TCP-коннект есть, ClientHello
уходит, ответа нет). DESYNC с `split=3`/`record-split` это не обходит; слот
VLESS тоже не помог — свободные узлы из публичной подписки либо мертвы, либо
режут 443 (узел `188.116.27.6` отдал HTTP 200 за 7,8 с и не отдал HTTPS за 12 с).
Поэтому PRIMARY честно откатывается на DESYNC, а не висит «подключено».

### MASQUE: YouTube и Telegram работают

Тот же телефон, чип MASQUE, «умная маршрутизация» выключена (без force-stop):

```text
Бейдж: MASQUE: RU, IP 104.28.198.***, ping 40 мс
tun0: rx 379913 → 457681, tx 190593 → 245977 за 18 с
tgnet: connected to 149.154.167.41:443, 149.154.162.123:443
```

- YouTube: поиск, превью и **воспроизведение видео** (кадр в плеере, не
  бесконечный спиннер); в фоне играет PiP.
- Telegram: шапка «Telegram» без «Соединение…», чаты и медиа грузятся.
- Chrome: `edition.cnn.com` открывается полностью, `ya.ru` и `mail.ru` — тоже.
- crash-буфер пуст.

### WARP тоже работает

После переключения чипа на WARP (force-stop не понадобился для выбора
транспорта, но понадобился для чтения ClientData процессом `:vpn`):

```text
Бейдж: WARP: RU, IP 104.28.200.***, IPv6 2a09:bac5:…, ping 211 мс
tun0: rx 2441457 → 2519690, tx 500593 → 586148 за 16 с
tgnet: connected to 149.154.167.50:443
YouTube Shorts: кадр в плеере меняется, видео играет
```

То есть YouTube/Telegram работают и на WARP, и на MASQUE; разница — задержка
(211 мс у WARP против 40–72 мс у MASQUE на этом устройстве).

### Переключатель «умная маршрутизация» без перезапуска

До фикса служба `:vpn` держала SharedPreferences в памяти: выключение
`smart_routing` из UI не действовало до force-stop. После перевода чтения на
XML с диска проверено: выключили тумблер → «Отключить»/«Подключить» → бейдж
`MASQUE: RU`, процессов `libnova-router`/`libnova-desync` нет.

### Финальная установка

```text
adb install -r OBNOVA_1.34.1-arm64-v8a.apk   -> Success
Бейдж: MASQUE: RU, IP 104.28.230.***, ping 72 мс
crash-буфер: пуст
ANR/FATAL EXCEPTION по com.brent.nova: нет
```

### Ограничение, которое осталось

MULTI (DESYNC и свободный VLESS) не обходит блокировку YouTube/Telegram на
этом операторе. Рабочий путь — MASQUE/WARP; публичная VLESS-подписка остаётся
в приложении как источник профилей, но живой узел нужно искать перебором, и
глубинная HTTPS-проба теперь не даёт мёртвому узлу выглядеть подключённым.

## 2026-10-23 — итог

### Unit tests

```text
.\gradlew.bat test                      # baseline до правок
BUILD SUCCESSFUL in 2m 14s
```

```text
.\gradlew.bat testGithubDebugUnitTest   # финальный точечный прогон
BUILD SUCCESSFUL in 7s
suites=56 tests=529 failures=0 skipped=9
```

```text
.\gradlew.bat test                      # оба flavor, финально
BUILD SUCCESSFUL in 42s
```

Новые тесты:

- `DnsResponseCacheTest` — TTL, transaction ID, истечение, truncated, TTL=0.
- `ProfileHealthRotationTest` — деградация, explicit mode, cooldown, ручное
  действие, сброс при disconnect.
- `DomainBypassRulesTest` — defaults, `none`, builtin direct domains.

### Сборки

```text
.\gradlew.bat assembleGithubDebug
BUILD SUCCESSFUL in 7s
```

```text
$env:NOVA_ALLOW_UNSIGNED_RELEASE="1"
.\gradlew.bat assembleGithubRelease
BUILD SUCCESSFUL in 1m 5s
```

Ключ релея `nova-android-164` совпадает с `versionCode 164`, поэтому
`NOVA_ALLOW_UNPUBLISHABLE_BUILD` больше не требуется.

| APK | Размер | Версия |
|---|---:|---|
| `app/build/outputs/apk/github/debug/Nova_1.34.0.apk` | 57 837 411 Б (55,2 МиБ) | 1.34.0 / 164 |
| `app/build/outputs/apk/github/release/Nova_1.34.0.apk` | 42 100 389 Б (40,2 МиБ) | 1.34.0 / 164 |

Release меньше debug на **15 737 022 Б (−27,2%)**.

- `classes.dex`: 3 258 496 Б → 1 607 291 Б в ZIP (deflate).
- `BuildConfig.RELAY_KEY_ID = "nova-android-164"`.
- `apksigner verify`: v2 **true**, v3 **true**; подпись личным keystore.
- `aapt dump badging`: `versionCode=164`, `versionName=1.34.0`,
  `sdkVersion=24`, `targetSdk=34`, native-code `arm64-v8a`, `armeabi-v7a`.
- `BuildConfig` (github debug/release): `UPDATER_ENABLED=false`.

### Эмулятор: что удалось проверить

- AVD `Pixel_7_Pro` — x86_64 (Android 33, google_apis_playstore); загрузка
  `sys.boot_completed=1`.
- Обычный ARM APK на x86_64:
  ```text
  adb install -r Nova_1.34.0.apk
  Failure [INSTALL_FAILED_NO_MATCHING_ABIS: ... res=-113]
  ```
- ARM64 AVD на x86_64 хосте не запускается:
  ```text
  FATAL | Avd's CPU Architecture 'arm64' is not supported by the QEMU2
          emulator on x86_64 host.
  ```
- Для UI-проверки собрана **временная x86_64-сборка** (`-PnovaEmulatorX86=true`,
  только для эмулятора, без tun2proxy/opera/stormdns; в релиз не входит):
  - установка: `Success`;
  - `MainActivity` открылся; версия внизу — `v1.34.0`;
  - тема Matrix по умолчанию (зелёный терминал);
  - **цифровой дождь анимируется**: два скриншота с интервалом 1 с различаются,
    колонки сдвинулись;
  - экран настроек: секции «ОБНОВЛЕНИЯ» и «Скачать последнюю версию» отсутствуют,
    видна Matrix-палитра; футер ведёт на `https://t.me/abonent0007`;
  - в портрете IP/узел и график ping находятся сверху, центральный блок
    (статус/кнопка/селектор) ниже; в горизонтали сохранена прежняя нижняя
    компоновка IP/графика;
  - системные insets проверены по `uiautomator dump` на AVD 1440×3120:
    status bar `[0,0][1440,144]`, IP row начинается с `y=256`, version
    заканчивается на `y=2945`, navigation bar начинается с `y=2952`; перекрытия
    нет. Гамбургер настроек — `[1286,151][1398,263]` (правый верхний угол),
    нажатие открыло `SettingsActivity`; «Настройки» больше не занимают нижнюю
    строку, язык с SVG-глобусом стоит слева над версией;
  - emoji на главном экране заменены: гамбургер `ic_menu_hamburger` (top-right),
    глобус `ic_globe` в `drawableStart` кнопки языка, узлы dock — SVG
    `ic_node_auto/warp/masque/opera/proton/tor/dns`;
  - dock: 7 SVG-иконок в одной полосе, одна выбранная (проверено `uiautomator`:
    `checked="true"` только у одного `rb_exit_*`); при `input motionevent`
    MASQUE показывает подпись и увеличенную иконку, при MOVE на PROTON подпись
    переходит к PROTON; растёт только иконка под пальцем, соседние остаются
    в покое; функции выбранного узла — в отдельной плитке ниже
    (для PROTON видны `Регион: AUTO/NL/NO/PL/RO`);
  - переход MASQUE→PROTON при `animator_duration_scale=10`: на промежуточном
    кадре старая иконка уже сжалась, новая ещё растёт, подпись `PROTON` меньше
    финальной — масштабируется из иконки, а не появляется сразу;
  - перетаскивание: `DOWN` на AUTO → `MOVE` на MASQUE — AUTO уменьшилась и
    погасла, MASQUE раскрылась с подписью, но checked ещё AUTO; `UP` на MASQUE —
    checked ровно MASQUE. Затем `input swipe` AUTO→DNS за 900 мс — checked DNS,
    промежуточные узлы не выбираются; окончательный выбор происходит в точке
    отпускания;
  - верхняя плашка: после выбора MASQUE текст «Выбрано: MASQUE» появился под
    статус-баром над IP-строкой, а не системным Toast внизу; после выбора
    PROTON там же — «Выпуск профилей Proton уже идёт». Плашка скрывается через
    1.8 с и не перекрывает гамбургер.
- Найден и исправлен баг анимации: `MatrixRainView` вызывал
  `postInvalidateOnAnimation()` вместо повторной постановки `ticker`, поэтому
  кадр рисовался один раз. После фикса дождь движется.

### Что осталось непроверенным

- Живой старт VPN и переключение транспортов на ARM-сборке (x86_64 UI-сборка
  не содержит tun2proxy/opera/stormdns и не является релизной).
- Domain bypass и DNS-кэш в живом трафике.
- Release R8 на ARM-устройстве (JNI/Go/Xray).
- Реальная частота кадров/батарея на Pixel 7 Pro (эмулятор даёт лишь
  качественную проверку анимации).

Для полного прогона нужен USB ARM-девайс; после подключения:
`adb install -r app/build/outputs/apk/github/release/Nova_1.34.0.apk` и
дополнить этот раздел.

## 2026-10-23 — фон, always-on, автовосстановление

```text
.\gradlew.bat compileGithubDebugKotlin
BUILD SUCCESSFUL in 58s
```

```text
.\gradlew.bat testGithubDebugUnitTest assembleGithubDebug
BUILD SUCCESSFUL in 28s
```

- Манифест: `stopWithTask="false"`, мета-дата `SUPPORTS_ALWAYS_ON=true`.
- Новые `AlwaysOnVpn.kt` и строка «Постоянный VPN» в настройках.
- `restoreDeadSessionOnOpen()` — автоподъём сеанса, когда процесс `:vpn` убит.
- Одноразовая плашка про батарею/автозапуск/always-on после первого CONNECTED.
- Живой прогон на ARM не выполнялся: AVD x86_64 не ставит ARM-APK. Пункты
  «проверить always-on и автовосстановление» остаются за USB-девайсом.

## 2026-10-28 — живой прогон ZAPRET (realme RMX3370)

Устройство: realme RMX3370, Android 13 (SDK 33), arm64-v8a. Установлен release
`Nova_1.34.0.apk` (41,1 МБ, подпись `nova-personal`; сертификат совпал с прежней
установкой, данные сохранены).

```text
adb install -r Nova_1.34.0.apk   -> Success
```

- Кнопка **ZAPRET** есть в dock (`rb_exit_desync`), нажатие сохраняется:
  `checked="true"` после повторного дампа.
- После «Подключить»: служба `:vpn` поднята, дочерний процесс
  `libnova-desync.so` жив, интерфейс `tun0 10.1.11.1/24`, SOCKS5 слушает
  `127.0.0.1:1376` (hex `:0560`), в `/proc/net/tcp` 41–67 установленных
  соединений к этому порту — трафик идёт через движок.
- Сеть `ni{VPN CONNECTED}` в состоянии `IS_VALIDATED` — системная проверка
  выхода прошла через туннель.
- `adb logcat -b crash` пуст, ANR за сеанс нет.

Не проверено: фактический обход конкретной блокировки (нужен ручной браузер);
ручная остановка — на устройстве включён always-on VPN для Nova
(`settings get secure always_on_vpn_app` = `com.brent.nova`), поэтому система
сама поднимает службу после отключения. Пинг в режиме ZAPRET пока не
публикуется (`Ping: ---`).

### Полный UI-прогон (2026-10-28)

- Все восемь кнопок dock переключаются и сохраняют выбор: AUTO, WARP, MASQUE,
  OPERA, PROTON, TOR, DNS, ZAPRET (`checked="true"` после каждого нажатия).
- Настройки: ZAPRET отмечен; строка «Постоянный VPN» открывает системный экран
  VPN (`com.oplus.wirelesssettings/Settings$VpnSettingsActivity`).
- Найдена и исправлена ошибка: на прошивке realme `Settings.Secure` закрыт для
  приложения, `always_on_vpn_app` читался как null, и тумблер always-on
  показывал «выключено» при включённой настройке. Теперь неизвестное состояние
  не рисуется тумблером (switch GONE), строка остаётся действием.
- Открываются экраны: фон главного экрана, оформление, уведомление.
- Цикл подключения: stop убирает `:vpn`, `libnova-desync.so`, `tun0` и слушатель
  `127.0.0.1:1376`; повторное подключение поднимает всё обратно.
- `logcat -b crash` за весь прогон пуст.

### Почему ZAPRET отваливался и что исправлено (2026-10-28)

Живой прогон показал обрыв через 13–20 секунд. В журнале две причины:

1. `SOCKS connection failed: Reply::ConnectionRefused` от tun2proxy — это отказ
   **конкретному адресу** (недоступный IP), а не потеря туннеля. Сервис считал
   это потерей транспорта и уходил в `CONNECTED → CONNECTING`. Теперь для
   DESYNC такие отказы не запускают recovery.
2. `Смена сети: остановка tun2proxy обрекла процесс :vpn` — системный
   network-change пересобирал TUN, хотя локальному SOCKS5 это не нужно:
   остановка tun2proxy убивает `:vpn`. Для DESYNC смена сети теперь ничего не
   пересобирает — новые соединения идут через новую сеть.

Дополнительно `runDesyncPhase` держит фазу живой (как VLESS/DNS): повторные
start-intent'ы больше не запускают второй движок поверх первого, а смерть
движка/туннеля возвращает `false` для штатного recovery.

После правок: 90+ секунд непрерывной работы с трафиком (YouTube, Google),
PIDs `:vpn` и `libnova-desync.so` не менялись, `tun0` на месте, 74 активных
соединения к `:1376`, RSS 90 МБ / 2,4 МБ, crash-буфер пуст.

### Третья причина: забытый protect() (2026-10-28)

Повторный прогон показал новый цикл: `[tun2proxy] DESYNC завершён с кодом -1`
через 1–2 секунды после подъёма. Движок был жив, но tun2proxy выходил сразу:
его сокеты не были помечены `protect()` и уходили в собственный TUN, замыкаясь
сами на себя. В VLESS и DNS `installSocketProtector()` вызывается, в DESYNC —
нет. Добавлен перед запуском движка.

Заодно:
- `runDesyncPhase` различает поколение: фаза прошлого поколения больше не
  отвечает «уже идёт» новому сеансу, а ждёт её завершения до 4 с;
- в Go-движке добавлен `recover()` на соединение и тест на 2000 случайных
  повреждённых ClientHello — паника в одном сеансе больше не убивает движок.

Итог: 90+ секунд без единого `-1`, PID движка не менялся, 5–74 активных
соединения, RSS 102 МБ / 2,6 МБ, crash-буфер пуст.

### DNS и UDP в ZAPRET (2026-10-28)

- `nova-desync` получил **UDP ASSOCIATE**: tun2proxy больше не получает
  `CommandNotSupported`, датаграммы (в первую очередь DNS) уходят наружу и
  ответы возвращаются на тот адрес, с которого пришёл запрос.
- UDP/443 (QUIC) в узле по-прежнему отбрасывается — трафик должен идти по TCP,
  иначе split не применяется.
- Проверки: юнит-тест round-trip SOCKS5-датаграмм и сквозной тест с локальным
  UDP-эхо; на устройстве `CommandNotSupported` исчез, Chrome открыл
  `habr.com/ru/feed/` — имена разрешаются.
- Ещё найдено и исправлено: при повторном выборе ZAPRET поверх живого сеанса
  `tun2proxy` отвечал «already started» и возвращал `-1`. Теперь повторный вход
  переиспользует живой туннель, а обречённый `:vpn` передаёт работу свежему
  процессу (`handOffIfVpnProcessDoomed`).
- Итог: 45+ секунд стабильно, включая повторное нажатие ZAPRET; PID `:vpn` и
  движка не менялись, crash-буфер пуст.

## 2026-11-01 — каркас parity v2 на устройстве

Устройство: realme RMX3370, Android 13. Установлен release `Nova_1.34.0.apk`
(сборка 2026-11-01).

- Гамбургер открывает боковое меню: в дампе присутствуют `drawer_scrim`,
  `drawer_routes`, `drawer_slots`, `drawer_strategy`, `drawer_diagnostics`,
  `drawer_logs`, `drawer_settings`.
- Нажатие «Маршруты» открывает `ParityScreenActivity`; кнопка «назад» возвращает
  на главный экран.
- `crash`-буфер содержал старый нативный сбой `:vpn` от 12:31 (до этой сборки);
  буфер очищен, текущий процесс `:vpn` жив, падений после установки нет.
- Списки PC перенесены в `assets/lists` (main/second/exclude/ai/youtube/
  telegram/whatsapp/discord/cloudflare + CIDR Cloudflare); загрузка —
  `ListRepository`, покрытие CIDR — `Ipv4Range`.

### Экран «Маршруты» (2026-11-01)

- Из бокового меню «Маршруты» открывает `RoutesActivity` (не заглушку).
- Восемь строк: RU, EU, YouTube, Telegram, WhatsApp, Discord, AI, остальное;
  подпись `AUTO · PRIMARY→SECONDARY` и ручной выбор (AUTO/PRIMARY/SECONDARY/
  DIRECT/BLOCK).
- Выбор сохраняется: RU → DIRECT, затем обратно AUTO — проверено дампом.
- Переключатели `foreign_default` и killswitch читаются/пишутся в `ClientData`.
- `crash`-буфер после прогона пуст.

### Экран «Слоты» (2026-11-01)

- «Слоты» в боковом меню открывает `SlotsActivity`.
- Карточки: PRIMARY (состояние + backend), SECONDARY (Opera endpoint/регион),
  RELAY (честно: подготовка, релей в v2), DIRECT (режим split-tunnel и число
  приложений).
- Проверено дампом: PRIMARY «не подключён», SECONDARY «не запущен», DIRECT
  «через VPN только выбранные приложения (19)».

### MULTI и RouterProcess (2026-11-01)

- Флаг «умная маршрутизация» пережил force-stop после переноса в отдельный
  prefs-файл `nova_routing` (до этого терялся из-за многопроцессного ClientData).
- MULTI с DESYNC как PRIMARY и Opera как SECONDARY: на этой сети регистрация
  Opera не проходит, поэтому SECONDARY отключается через 6 с / 3 плана, а
  сеанс продолжается.
- Живой прогон: `nova-router` на 127.0.0.1:1373 (2 слота, 1 правило),
  `libnova-desync.so` на 1376, `tun0 10.1.11.1`, состояние `CONNECTED`
  (backend=MULTI). В `/proc/net/tcp` 13 соединений к роутеру и 9 к десинку;
  Chrome открыл `habr.com/ru/feed/`.
- После теста флаг выключен, устройство возвращено на WARP (`tun0 172.16.0.2`),
  посторонних процессов router/desync нет; crash-буфер пуст.
- Списки и ручные маршруты подключены к конфигу роутера: на устройстве
  `nova-router` поднялся с **9 правилами** (exclude, локальные TLD, main,
  second, ai, youtube, telegram, whatsapp, discord) и 2 слотами; трафик шёл
  (3 соединения к роутеру, 5 к десинку), Chrome открыл habr.com.
- Статистика слотов: `status.json` пишется роутером, экран «Слоты» показал
  `PRIMARY · жив · MULTI · 51 мс`, `успешно 5 из 5, ошибок 0`.
- После теста MULTI выключен, устройство на WARP (`tun0 172.16.0.2`), процессов
  router/desync нет; crash-буфер пуст.
- Per-app: в «Маршрутах» карточка «Приложения» показывает режим и число
  выбранных («через VPN только выбранные (19)») и ведёт в выбор приложений —
  прямые приложения по-прежнему идут мимо TUN.
- Горячая перезагрузка маршрутов: RU → DIRECT на живом MULTI сменил PID роутера
  (16699 → 17083) при том же `tun0 10.1.11.1`; TUN/tun2proxy не пересобирались.
- UI-консолидация: в dock осталось 7 кнопок (ZAPRET нет), гамбургер открывает
  старые настройки; в разделе «Маршрутизация» появились строки «Маршруты и
  приложения», «Слоты», «Стратегии», «Диагностика»; нажатие «Маршруты и
  приложения» открывает `RoutesActivity`.
- Карточка «Приложения» в «Маршрутах» открывает `SettingsActivity` и
  прокручивает её к `hdr_group_apps_vpn` — проверено дампом, заголовок раздела
  виден сразу; crash-буфер пуст.
- Быстрый режим в карточке «Приложения»: переключение «Все приложения» →
  «Только выбранные» меняет подпись на «весь трафик через VPN, кроме прямых» и
  обратно на «через VPN только выбранные (19)»; crash-буфер пуст.
- «Диагностика» из настроек открывает `DiagnosticsReportActivity`; отчёт
  собрался (23 533 символа), кнопка копирования на месте.
- «Стратегии» из настроек открывают `StrategiesActivity`: текущий сеанс
  (`STOPPED`, транспорт WARP) и честное «нет измерений» при пустом `status.json`.
- MULTI после VLESS-PRIMARY: профилей 0 → `PRIMARY1376` (DESYNC), роутер
  2 слота / 9 правил, Opera «отключён на 10 минут», состояние CONNECTED;
  процессов Xray нет, crash-буфер пуст.
- UDP в роутере: после установки `CommandNotSupported` в логах — **0**;
  MULTI работает на DESYNC+router, TUN 10.1.11.1, crash-буфер пуст. После
  теста MULTI выключен, устройство на WARP (`tun0 172.16.0.2`).
- Killswitch: тест `RouterConfigFactoryTest` подтверждает, что DIRECT-хвост
  исчезает (`defaultOrder=["primary"]`), а явный DIRECT у сервиса остаётся.
- Фильтр логов: после установки `CRITICAL`/`terminated signal received` на
  уровне `I/NovaApp` — **0**, WARP поднялся (`tun0 172.16.0.2`), crash-буфер пуст.
- «Проверить слоты» на MULTI: `Проверка: OK · 66 мс · 1.1.1.1:443` — цепочка
  router→DESYNC работает. По имени (`www.gstatic.com`) тот же запрос давал
  `SOCKS: отказ 5`: резолвер внутри DESYNC в MULTI отдавал недостижимый адрес.
- После резолвера через TCP DNS: `Проверка: OK · 237 мс · www.gstatic.com:443`
  — MULTI работает и по именам; crash-буфер пуст.
- Резолвер с цепочкой DNS (1.1.1.1 → 8.8.8.8 → 9.9.9.9): повторная проверка
  `OK · 199 мс · www.gstatic.com:443`; crash-буфер пуст.
- Трафик через MULTI после фикса: Chrome открыл habr.com; в логах
  `SOCKS connection failed: Reply::ConnectionRefused` — **0**,
  `CommandNotSupported` — **0**, `network is unreachable` — **0**.
- Stress MULTI 72 с с трафиком: PID `:vpn` 20217 не менялся, RSS 139 МБ
  (+0,9 МБ), `tun0 10.1.11.1` стабилен, ошибок SOCKS нет; crash-буфер пуст.
  В логе только внешний таймаут `relay.nova-app.eu:8443` — API-релей Opera
  недоступен из MULTI без туннеля, к трафику пользователя не относится.
- Диагностический отчёт после установки: `OBNOVA diagnostic log`,
  `app=1.34.1 (165, github)`, `xray=26.10.21` — VLESS-ядро загружается и в
  основном процессе.
- Release по ABI: установлен `OBNOVA_1.34.1-arm64-v8a.apk` (35,1 МБ) поверх
  универсального; баннер `OBNOVA 1.34.1 (165, github)`, версия 165/1.34.1,
  crash-буфер пуст.
- «Маршруты» после перехода на `SwitchCompat`: все три тумблера на месте
  (`sw_default_vpn=true`, `sw_killswitch=false`, `sw_smart=true`), crash-буфер
  пуст; lint-предупреждения по Switch в этом файле исчезли.
- Трафик по правилам: Go-тест `TestStatsWritesValidJson` проверяет `routes`
  в `status.json` (`ru=1`) и `port=1500`; экран «Стратегии» получил блок
  «ТРАФИК ПО ПРАВИЛАМ».
- Живой UI-прогон блока: `Стратегии` показали `ТРАФИК ПО ПРАВИЛАМ / default: 5`
  при MULTI и трафике через Chrome; состояние на экране было `CONNECTING` из-за
  смены сети — добавлено восстановление `CONNECTED` для MULTI/DESYNC.
- «Обновить списки» на устройстве: статус
  `Обновлено: 02.11.2026 10:33 · обновлено файлов: 9`, crash-буфер пуст;
  скачивание идёт с raw.githubusercontent/jsdelivr/raw.githack, sha256 сверяется
  с `manifest.json`.
- Кнопка «Обновить подписку» в «Маршрутах» собирается условно: при пустом
  ClientData (0 профилей VLESS) она не показывается; release установлен,
  crash-буфер пуст.
- Стабильность WARP после сборки: 60+ секунд с трафиком, PID `:vpn` не менялся,
  `tun0 172.16.0.2`, RSS 138 МБ; ошибок приложения и crash-записей нет.
  В логе только `CRITICAL` от opera-proxy при отмене prewarm-регистрации —
  это шум прогрева, не сбой сеанса.

### Аудит логов после обновления (2026-11-01)

- WARP-сеанс: `tun0 172.16.0.2`, Opera prewarm зарегистрировался через WARP
  (`anonymous` и `device registration` с первой попытки), endpoint сохранён
  в кэш; после прогрева прокси остановлен — штатно.
- Ошибок `NovaApp`, исключений, `ANR in com.brent.nova` и записей в crash-
  буфере нет.
- Найдено объяснение, почему Opera не поднимается в MULTI: её регистрация идёт
  через API-релей, который доступен только изнутри туннеля (в логе WARP —
  успех, в MULTI с DESYNC — отказ). Значит SECONDARY станет рабочим только
  после настоящего PRIMARY-туннеля (VLESS SOCKS).

### OBNOVA 1.34.1 — установка и лог (2026-11-01)

- APK `OBNOVA_1.34.1.apk` (64,8 МБ): `aapt` — `versionCode 165`,
  `versionName 1.34.1`, `application-label: OBNOVA`.
- Установлен поверх 1.34.0 без потери данных; канал уведомления —
  `mName=OBNOVA VPN`.
- WARP-сеанс: `tun0 172.16.0.2`, Opera prewarm зарегистрировался с первой
  попытки через WARP; `NovaApp` ошибок нет, crash-буфер и ANR пусты.
- Relay key остался `nova-android-164`: personal-release собран с
  `NOVA_ALLOW_UNPUBLISHABLE_BUILD=1` (записано в `AGENTS.md`, `docs/BUILD.md`).
- После смены MASQUE device name на «OBNOVA Android» release переустановлен:
  WARP поднялся (`tun0 172.16.0.2`), ошибок `NovaApp`, исключений и записей в
  crash-буфере нет.
- Заголовки уведомлений обновлены на «OBNOVA VPN» (и «OBNOVA» для ветки
  готового обновления); установка поверх 1.34.1 прошла, crash-буфер пуст.
- Стартовая строка журнала после установки:
  `OBNOVA 1.34.1 (165, github) - Android 13 (SDK 33), realme RMX3370…`;
  crash-буфер пуст.
- Добит брендинг в списке WARP-конфигураций («OBNOVA • …»), заголовке экрана
  диагностики и метке буфера обмена; release установлен, crash-буфер пуст.
- Смена сети для MULTI/DESYNC теперь только обновляет underlying networks;
  пересборки TUN нет, `tun2proxy_stop` не зовётся.
- «Слоты» перечитывают `status.json` каждые 3 секунды на открытом экране.
- Аудит логов: `dlopen failed: libnovaxray.so not found` — ядро Xray
  отсутствовало в APK. Собраны `libnovaxray.so` и `libnovaxrayjni.so` для
  arm64-v8a (34,9 МБ) и armeabi-v7a (33,7 МБ); после установки в логе
  `Ядро Xray: 26.7.28`, crash-буфер пуст. APK 65,4 МБ.
- Xray обновлён до **26.10.21** (security): ядро пересобрано под обе ABI,
  после установки в логе `Ядро Xray: 26.10.21`, APK 64,8 МБ.
