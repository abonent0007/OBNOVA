# Журнал изменений личного форка Nova Android

> Формат: дата → что изменено → где → чем проверено. Все пути от корня
> `C:\Users\User\Documents\Nova-Android-Fork`.

## 2026-11-04 — pipeline: источники, загрузчик, миграция DNS

Изменение без смены версии: готовится к следующему релизу. Полная
инвентаризация — `docs/fork/DATA-SOURCES.md`.

### Удалённые заглушки и дубли

- `WarpConfigsActivity`: запасная загрузка подписки больше не использует
  `URL.openStream()` без тайм-аутов и потолка (поток и диалог прогресса могли
  висеть бесконечно). Теперь `FeedHttp` и видимая ошибка «Подписка … не
  загрузилась: причина».
- Убраны четыре копии `openConnection` (`DnsServerFeed`, `OperaEndpointFeed`,
  `ListSync`, `WarpConfigsActivity`) — один `FeedHttp` с проверкой HTTP-кода,
  закрытием `errorStream` и отказом от тела больше потолка.
- Удалены неиспользуемые импорты: `TronRingsView` (`kotlin.math.min`),
  `WarpConfigCardAdapter` (`android.content.Context`).

### Восстановленные логические звенья

- **IP-списки YouTube и Telegram теперь доезжают.** `ListSync` синхронизировал
  только `ip/cloudflare.txt`, хотя `ListRepository` читал
  `youtube_ips.txt`/`telegram_ips.txt` — манифест их публиковал, обновление
  проходило мимо. Добавлена карта `IP_ASSETS` и имена назначения в
  `ListManifest`.
- **Миграция снятого `geohide.ru`** срабатывает по адресу, а не по id: правило,
  добавленное вручную или импортированное под другим именем, больше не остаётся
  мёртвым. Шаг версионный (`VERSION = 5`), идемпотентный, журнал пишет
  вызывающий — функция проверяется без Android-рантайма.
- Адреса лент DNS-туннеля, Opera и обновлений сведены в
  `ExternalSources.updateFeedMirrors`; добавлены проверки в тестах.

### Проверки

- JVM: **621/0/9** (было 610; +11 тестов: `FeedHttpTest` — 4,
  `ExternalSourcesTest` — 3, `DnsRulesTest` — 4).
- `go vet ./...` в `nova-router` — чисто; `nova-core` на Windows не собирается
  из-за Linux-only `syscall` (проверяется в Android-сборке).
- Lint: 0 ошибок; предупреждения — существующие (HardcodedText и т.п.).
- Скан импортов: 0 неиспользуемых; скан сетевых вызовов: 0 без обработки
  ошибок, у всех HTTP-загрузок есть тайм-ауты.

## 2026-11-04 — 1.34.3 / versionCode 167

- **UI:** исправлено наложение в правом верхнем углу, которое осталось в 1.34.2:
  бейдж страны/режима стоял под гамбургером, но поверх подписи «Ping».
  Теперь `tv_country_badge` — под `btn_settings` (8dp ниже, по его правому краю),
  а `tv_internet_label` — слева от гамбургера в верхней строке. Горизонталь:
  пинг возвращается к графику, бейдж остаётся в средней колонке под кнопкой.
- Проверено на RMX3370 (1080×2400): `btn_settings [948,116][1044,212]`,
  `tv_internet_label [774,116][924,228]`, `tv_country_badge [786,242][1044,327]` —
  пересечений нет ни в отключённом, ни в подключённом состоянии.

## 2026-11-04 — 1.34.2 / versionCode 166

- **DoH:** старый `https://geohide.ru/dns-query` снят. В умолчаниях первыми
  идут **`https://us.geohide.ru/dns-query`** и **`https://eu.geohide.ru/dns-query`**
  (оба с автовыбором DoH/DoT). Сохранённое правило с legacy-URL миграция
  заменяет парой us/eu и ставит её в начало приоритета.
- **UI:** бейдж страны/режима (`tv_country_badge`) смещён под гамбургер
  (`btn_settings`) — больше не накладывается на кнопку меню.
- Тесты: JVM 610/0/9 (обновлены ожидания порядка DNS-умолчаний).

## 2026-11-03 — SNI для IP-литералов и автозапуск Opera

- `nova-router/sni.go`: ClientHello читается **после** SOCKS-reply (Cronet
  начинает TLS только после него); из SNI восстанавливается FQDN, и
  HTTP-CONNECT к Opera уходит с именем, а не с голым IP. Только CONNECT на 443
  с IP-литералом.
- `OperaProxyManager`: параметры `bootstrapApiProxy` / `bootstrapDialProxy`.
  Stage 2 запускает Opera с `-api-proxy socks5h://127.0.0.1:1376` и
  `-proxy socks5h://127.0.0.1:1376` — API и туннель с одного egress (DESYNC).
  Если задан прокси из настроек, `-proxy` не навязывается.
- `NovaVpnService`: AWG-мост поднимается до Opera; Opera стартует только после
  открытия `127.0.0.1:1378`; старый 10-минутный backoff игнорируется при
  `CF_PROXY_SLOT_ENABLED`; мост гасится после Opera в `finally`.
- **Проверено на устройстве:** `142.251.x:443 → secondary
  (SNI "rr…googlevideo.com")`, лента YouTube грузится, Shorts играет
  (0:08 из 0:48, кадры меняются); `149.154.167.x → cfproxy`; Chrome открывает
  `ya.ru`, `github.com`, `example.com`.
- **Проверено:** автозапуск Opera — `MULTI активен … / Opera34920`,
  `curl --proxy http://127.0.0.1:34920 …/generate_204` → **204**.
- **Меньше ложных реконнектов:** MULTI-проба получила бюджет 4,5 с и первой
  идёт `https://cp.cloudflare.com/generate_204` (литералы 1.1.1.1 в РФ режутся);
  «VPN потерял underlying networks» требует 3 отказа подряд, а для MULTI это
  событие вообще не повод рвать сеанс — обновляем underlying networks без
  пересборки TUN и остаёмся в CONNECTED.
- **Play Services:** правило `apis` (`apis.google.com`, `gvt1.com` → `primary`)
  стоит до YouTube и до CIDR; `nova-router` теперь предпочитает SNI-правило
  CIDR-правилу, поэтому `android.apis.google.com` на IP `142.251.x` больше не
  уходит в Opera. `youtubei.googleapis.com` остаётся на `secondary`.
- **Проверено:** `android.apis.google.com:443 → primary` (404), видео 1:25
  играет (0:11 → 0:25), за 70 с тишины — ноль `CONNECTED → CONNECTING`.

## 2026-11-03 — тест MASQUE: Telegram живёт, YouTube нет

- Причина «Соединение...» в Telegram: он был привязан к мёртвым MTProto-прокси
  (`91.219.227.10:9443`, `85.192.35.94:443`) и к локальному `127.0.0.1:1373`
  (router живёт только в MULTI). Прямой DC2 `149.154.167.50` оператор режет:
  TCP timeout и в direct, и в DESYNC; через cfproxy — TCP есть, 0 байт.
- В **MASQUE** (`backend=WARP, транспорт=MASQUE`, TUN `172.16.0.2/32`) прокси
  Telegram `65.109.194.157:300` поднялся: статус «Подключён, пинг: 100 мс»,
  баннер «Соединение...» исчез, `tgnet: connected to 149.154.167.41:443`.
- **YouTube в MASQUE не работает:** media session `state=6` (BUFFERING),
  `position=0` через 30 с. WARP не тянет `googlevideo`, поэтому YouTube остаётся
  на связке MULTI + Opera.
- Итог: MASQUE — для Telegram через его MTProto-прокси; MULTI — для YouTube.
  Оба одновременно без VLESS-узла не получить.

## 2026-11-03 — MULTI: Telegram через MTProto-прокси в Opera

- Правило `mtproto-proxy`: `65.109.194.157/32 → secondary → cfproxy → primary`.
  Telegram держит этот прокси включённым; Opera принимает
  `CONNECT 65.109.194.157:300` (200) и доносит MTProto до Hetzner → DC2.
- **Проверено:** `ROUTER: 65.109.194.***:300 → secondary`; в Telegram прокси
  «Подключён, пинг: 668 мс», баннер «Соединение...» исчез, чаты живые;
  `www.youtube.com` через router → 200.
- AWG-мост (`cfproxy`) в этой сети сейчас не несёт трафик: даже
  `cp.cloudflare.com` через 1378 → 000, поэтому cfproxy оставлен вторым
  фоллбеком, а рабочий egress — Opera.
- Bundled seed теперь используется всегда: verified-конфиги могли остаться от
  MASQUE-сессии (endpoint 162.159.x:443) и давали «мост поднят, трафика нет».

## 2026-11-03 — WSS-прокладка для Telegram (Cloudflare Worker)

- `tools/cf-relay/worker.js` — Worker `nova-connect`: принимает WSS на
  `/tcp?host=&port=`, открывает TCP через `cloudflare:sockets` и гоняет байты
  в обе стороны. Разрешены только сети Telegram (`149.154.160.0/20`,
  `91.108.0.0/16`, `2001:67c:4e8::/48`) и только с секретом.
- `nova-router`: тип слота `ws` — TLS + WebSocket-клиент (маскирование,
  ping/pong, close), байтовый мост TCP↔фреймы. Правило `telegram-ip`:
  `ws → secondary → cfproxy → primary`.
- **Ключевая находка:** Cloudflare отдаёт бинарный кадр как **Blob**, а не
  ArrayBuffer. Без `await data.arrayBuffer()` первый же кадр уходил в
  `TransformStream` объектом и отбрасывался — Telegram висел на «Соединение...».
- **Проверено:** `ROUTER: 149.154.167.***:443 → ws`; Telegram **без прокси**
  подключается, баннер исчезает, чаты обновляются; прокси `65.109…` выключен.
- Официальный `kwsN.web.telegram.org` для этой сети не годится: резолвится в
  `149.154.167.99`, то есть в заблокированный диапазон. Поэтому реле — свой
  Worker на Cloudflare.
- **Диагностика AWG (2026-11-03):** мост поднимается, но handshake не
  завершается: `Handshake did not complete after 5 seconds` — UDP до
  WARP-эндпоинтов (500/903) оператор не пропускает. `Reserved` из WARP-профиля
  теперь передаётся в конфиг, но это не помогает, пока не проходит handshake.
- **Мост WSS проверен эхо-тестом:** публичный TCP-эхо через Worker вернул
  `hello-echo` — транспорт исправен. Нестабильность именно в плече
  Cloudflare → Telegram DC: в 20:04 данные шли, в 21:36 те же соединения
  отдавали 0 байт. Поэтому Telegram оставлен на MTProto-прокси через Opera,
  а `ws` — экспериментальный резерв.

## 2026-11-03 — MASQUE-SOCKS слот и обновление апстримов

- `nova-core/engine/masque_socks.go`: `StartMasqueSocks` — отдельный
  userspace MASQUE поверх `wgnetstack.CreateNetTUN`, SOCKS5 на
  `127.0.0.1:1379`; `activeMasque` не трогается, второй TUN не создаётся.
- `MasqueSocksBridge.kt`: перебор портов/SNI (443, `last_port`, 8443;
  `i.ytimg.com`), запуск из MULTI; `cfproxy` в роутере теперь смотрит на 1379,
  AWG (1378) остался запасным.
- **Проверено на устройстве:** `MASQUE SOCKS … 162.159.198.2:443`,
  `204` за 0.30 с, `example.com` 200, `colo=DME`, `loc=RU`.
- **Прямые Telegram DC через MASQUE не проходят:** `149.154.167.50/41`
  таймаут при живом `example.com` — фильтрация на выходе DME подтверждена.
  Поэтому Telegram остаётся на MTProto-прокси, а не на прямых DC.
- **Прокси 65.109 через MASQUE подключается** (`ROUTER: 65.109…:300 → cfproxy`),
  но MTProto-ping 3.5 с против 0.67 с через Opera — для `mtproto-proxy`
  оставлен порядок `secondary → cfproxy → primary`.
- **Апстримы:** opera-proxy `v1.31.0` (пересобран), Xray-core `26.10.31`
  (пересобран), `quic-go v0.65.0`, `connect-ip-go`/`usque` (адаптированы
  `PrepareTlsConfig`/`EnrollKey`/`ConnectTunnel`). Резерв регионов Opera:
  `EU → AS → AM`; в селекторе появился `AS`.

## 2026-11-04 — Telegram через KWS без прокси в Telegram

- `nova-router`: слот `ws` стал **транспортным терминатором** obfuscated2 по
  образцу `tgrelay` из Kaliopa. `tg_init.go` разбирает init (ключ `[8:40]`,
  IV `[40:56]`, keystream после 64 нулей, тег/DC в зашифрованном хвосте
  `[56:64]`), патчит `dc=0` по IP; `tg_relay.go` режет ciphertext на пакеты и
  шлёт в KWS **по одному пакету на кадр**, первым кадром — ровно 64 байта init.
- Маршруты: `kws{dc}` и `kws{dc}-1` (media), гонка, победа только после
  **первого ответного кадра** (5 с); egress — через `cfproxy` (MASQUE 1379).
- Приёмка на устройстве: Telegram **без прокси**, `proxy_lines=0`,
  `ROUTER: 149.154.x:443 → ws`, `tun2proxy` отдаёт реальные байты
  (`Ok(828), Ok(5013)`, `Ok(1502), Ok(725)`), чат открывается.
- Тесты: Go — init/DC/патч/abridged/intermediate; JVM — 610/0/9.

## 2026-11-02 — Stage 2: AWG SOCKS-мост

- Новый `nova-core/engine/awg_socks.go`: отдельный userspace-экземпляр
  AmneziaWG (`amneziawg-go/v3` + `wgnetstack`), адрес из профиля (fallback
  `10.204.0.1`), MTU 1280, `NewSmartBind()` и локальный SOCKS5 CONNECT на
  `127.0.0.1:1378`. Глобальные `activeDevice`/`stateMu` не используются.
- `mobile.go`: `StartAwgSocks(config, listen, enabled)`, `StopAwgSocks`,
  `AwgSocksStatus`; флаг `CF_PROXY_SLOT_ENABLED` включается
  `-PnovaCfProxySlot=true`, по умолчанию выключен.
- AAR пересобран (`nova-core-api24-stripped.aar`, `libgojni.so` для arm64 и
  armv7); источник профиля — актуальный `WarpVerifiedConfig` из ClientData,
  fallback — bundled seed. UI-кнопка Stage 2 скрыта за флагом.
- **IP/CIDR-маршрутизация:** `nova-router` правила теперь содержат `cidrs`;
  `ip/youtube.txt` и `ip/telegram.txt` (assets) задают Google/YouTube и
  Telegram-диапазоны. Telegram IP уходит в `cfproxy`, YouTube IP — в `secondary`
  (Opera). `RouterConfigFactory` и Go-тест покрывают порядок и матчинг.
- **Проверено:** `149.154.167.50:443 → cfproxy`; `www.youtube.com:443 →
  secondary` (200); `redirector.googlevideo.com → secondary` (404/данные).
  Официальное YouTube-приложение всё ещё уходит в `primary` для части
  IP-литералов: Opera-CONNECT на голый IP не всегда проходит, нужен следующий
  разбор.
- **Проверено:** `curl --socks5-hostname 127.0.0.1:1378
  https://cp.cloudflare.com/generate_204` → **204**; ручной
  `liboperaproxy.so -api-proxy socks5://127.0.0.1:1378` открыл `127.0.0.1:1371`
  (204); `yt-dlp` через этот Opera-прокси скачал реальный видеочанк
  `jNQXAC9IVRw` (195 771 байт).
- **Ограничение:** YouTube-приложение ходит по IP-литералам, поэтому
  доменные правила `nova-router` его не ловят (`ROUTER: 142.251.x → primary`).
  Следующий шаг — IP/CIDR-правила для `googlevideo`/Google ASN.
- `media_static`-разделение откатано: на этой сети `i.ytimg.com` через DIRECT
  даёт таймаут, через Opera — 404/данные; превью остаются на слоте YouTube.
- Тесты: **606/0/9**, lint 0 errors, release собирается.

## 2026-11-02 — lint: 0 errors без baseline

- **8 `UnspecifiedRegisterReceiverFlag`** закрыты через
  `ContextCompat.registerReceiver`: внутренние события (`VPN_STATE`,
  `STOP_VPN`, `WARP_CONFIG_DISCOVERY`, `UPDATE_STATE_CHANGED`) — с
  `RECEIVER_NOT_EXPORTED`; системные (`SCREEN_ON/OFF`, `TETHER_STATE_CHANGED`,
  `PACKAGE_ADDED/REMOVED`) — с `RECEIVER_EXPORTED`, иначе на части прошивок
  они не доходят.
- **`UnsafeImplicitIntentLaunch`**: `VPN_STATE` broadcast теперь с
  `setPackage(packageName)`.
- **21 `NewApi`** закрыты совместимыми обёртками:
  `ProcessCompat` (`isAlive`, `waitFor`, `destroyForcibly` для API 24–25),
  guard для `Canvas.clipOutPath` (API 26), `LinkProperties.mtu` (API 29),
  `android.util.Base64` вместо `java.util.Base64` (API 26).
- **3 `MissingPermission`**: `canNotify()` уже проверяет
  `POST_NOTIFICATIONS`; на трёх вызовах `notify` добавлен точечный
  `@SuppressLint("MissingPermission")` с комментарием. Глобального baseline нет.
- **3 `WrongCall`**: это `super.onDraw` внутри своей иерархии, а не вызов
  чужого View; подавлено локально на `drawLayers`/`drawContent`.
- **1 `UseAppTint`**: `android:tint` → `app:tint`.
- Итог: `lintGithubDebug` — **0 errors**, 883 warnings; тесты — **606/0/9**,
  release собирается.

## 2026-11-02 — pipeline: динамические данные и логическая непрерывность

### Что изменено

- **Инвентаризация источников**: новый `ExternalSources.kt` — единственная
  точка со всеми внешними адресами; полная таблица — `docs/fork/EXTERNAL-DATA.md`.
- **`ListSync` больше не знает имён файлов**: набор берётся из `manifest.json`
  (`ListManifest`), проверяется на `list/…txt` без `..` и `u_`. Новый
  `list/local_tlds.txt` доедет без выпуска версии.
- **Атомарное обновление списков**: файлы скачиваются в `.staging`, сверяются
  по sha256 и заменяют прежние только все вместе. Один сбойный файл больше не
  оставляет смесь старых и новых правил. Манифест и каждый файл проверяются на
  каждом зеркале отдельно: устаревший jsdelivr не блокирует обновление, если
  raw.githubusercontent уже отдал правильную версию.
- **Кэш списков межпроцессный**: `ListCacheSignature` (размер, mtime и
  `meta.txt`) заставляет службу `:vpn` перечитать списки после обновления из
  основного процесса; `ACTION_RELOAD_ROUTER` дополнительно сбрасывает кэш.
- **Локальные TLD — данные**: `assets/lists/local_tlds.txt` + `TldList`;
  `RoutingListsSnapshot` отдаёт их политике, `RoutingPolicy.decide` и
  `RouterConfigFactory` используют снимок, а не только константу.
- **Проверка сервисов** берёт цели из списков (`ServiceProbe.targetsFor`) и
  сначала проверяет, что порт роутера открыт (порт читается из `status.json`,
  а не жёстко 1373); иначе показывает понятное «MULTI не активен» вместо
  четырёх таймаутов.
- **Одна таблица подписей маршрутов**: `RouteSettings.chainHint` заменила
  вторую копию в `RoutesActivity`; Telegram честно `PRIMARY` без DIRECT.
- **`RouterConfigFactory`**: `primaryPort` валидируется; Telegram закреплён
  (`primary` + `spoof`), без DIRECT-хвоста.
- **`nova-router`**: `validate()` отвергает порядок, ссылающийся на
  несуществующий слот; пустой порядок остаётся допустимым (BLOCK).
- **`RoutingStore`**: разбор XML вынесен в `RoutingPrefsParser` и покрыт
  тестами; `commit()` оставлен осознанно (запись до следующего подключения).
- **Журнал стал доказательным**: после синхронизации пишется
  `LISTS: … обновлено файлов: 12 …`, после проверки — одна строка `PROBE:`
  с `OK/FAIL` по каждому сервису. Это видно в `adb logcat` без разбора UI.

### Удалённые заглушки

- Зашитый список девяти файлов в `ListSync`.
- Вторая копия таблицы `defaultHint` в `RoutesActivity`.
- Подпись `RELAY` для Telegram в UI.
- Безусловная загрузка `ip/cloudflare.txt` вне манифеста.
- Зашитые цели проверки сервисов (теперь список + запасные хосты).

### Восстановленные логические звенья

- После «Обновить списки» служба действительно видит новые файлы, а не свой
  старый кэш.
- Пустой/битый локальный список больше не затирает встроенный: срабатывает
  `ListRepository.*From(local, asset)`.
- Проверка сервисов не выдаёт «timeout» там, где просто не запущен MULTI.
- Telegram не уходит в DIRECT при отказе слота.
- Опечатка в имени слота в конфиге роутера теперь ошибка загрузки, а не
  молчаливый пропуск.

### Проверено

- `go test ./...` в `nova-router` и `nova-desync` — ok.
- `testGithubDebugUnitTest` — **606 тестов, 0 failures, 9 skipped** (было 583;
  добавлены `ListManifestTest`, `ListCacheSignatureTest`,
  `RoutingPrefsParserTest`, `TldListTest`, `ServiceProbeTargetsTest`,
  `ListRepositoryFallbackTest`, тесты цепочек и валидации Go).
- `tools/check_imports.py` — 69 изменённых Kotlin-файлов, 0 неиспользуемых
  импортов; `--external` — у всех сетевых/файловых вызовов есть
  `try`/`runCatching`.
- `lintGithubDebug` — 0 ошибок в изменённых файлах; общие 37 ошибок остаются
  в старом `AppUpdateManager`/legacy-коде (NewApi, receiver flags) и к этому
  pipeline не относятся.

## 2026-11-02 — прямой слот, VLESS-проверка и честный транспорт

- **Прямой слот `nova-router` не работал по имени**: `dial tcp ya.ru:443` падал
  за 4–5 мс, хотя `77.88.55.88:443` отвечал 406. Причина — Go-бинарь на Android
  не имеет системного `resolv.conf`, а `net.Dial` по имени молча отказывал.
  Добавлен `nova-router/resolver.go`: публичный DNS по TCP
  (`1.1.1.1 → 8.8.8.8 → 9.9.9.9`), кэш на 5 минут, IPv4 вперёд. Проверено на
  устройстве: `ya.ru` 302 за 0,27 с, `mail.ru` 302 за 0,30 с, `2ip.ru` 404 за
  0,44 с (раньше — `000` за 0,005 с). Это же чинит локальные TLD и `exclude`
  у обычных приложений, которые ходят по имени.
- `RouterProcess` теперь запускает роутер с `-v`, а строки «нет слота» пишет в
  журнал уровня WARN с подавлением повторов на 5 с — отказ маршрута больше не
  выглядит молчанием. Плюс роутер собирается NDK-тулчейном
  (`GOOS=android`, `CGO_ENABLED=1`), иначе процесс падал с
  `error=2` из-за glibc-интерпретатора; для armv7 собраны и упакованы
  `libnova-router.so` и `libnova-desync.so` (на живом armv7-девайсе не
  проверялись — тестового устройства нет).
- **VLESS-профиль проверяется по HTTPS, а не только по HTTP**: узел
  `188.116.27.6` отдавал `api.ipify.org` по 80 и молча ронял TLS на 443.
  `awaitVlessProxyReady(..., deep = true)` поднимает настоящий TLS до
  `example.com`; такой узел отбраковывается до подъёма TUN. Перебор расширен
  до 12 профилей / 45 с, отвергнутые уходят вниз списка
  (`demoteVlessProfileLinks`), поэтому следующее подключение начинает с
  непроверенных.
- Публичная подписка VLESS из стокового PC-форка
  (`profiles/subscriptions.json`, `iboxz/free-v2ray-collector`) загружается
  кнопкой **«Загрузить публичную подписку»** в «Маршрутах». На устройстве
  импортировано **249 профилей**. Свободные узлы нестабильны: из проверенных
  двенадцати ни один не прошёл HTTPS-пробу, поэтому MULTI честно откатывается
  на DESYNC, а не висит «подключено» на мёртвом узле.
- **Кнопка «Проверить RU / US / YouTube / Telegram»** в «Маршрутах»: четыре
  настоящих TLS-запроса через `127.0.0.1:1373`. На MULTI результат:
  `RU (ya.ru) — OK 302 336 мс`, `US (cnn.com) — OK 302 897 мс`,
  `YouTube — Read timed out`, `Telegram — Read timed out`.
- На том же устройстве **MASQUE и WARP проходят эти же проверки**: YouTube
  играет видео, Telegram держит соединение (`tgnet connected to
  149.154.167.41:443`), CNN и российские сайты открываются. Разница — задержка:
  40–72 мс у MASQUE, 211 мс у WARP. Это рабочий обход блокировки для данного
  оператора; MULTI его не заменяет.
- Бейдж главного экрана больше не врёт: при активном MULTI он показывает
  `MULTI: <страна>`, а не выбранный чип `WARP/MASQUE`. Подпись Telegram в
  «Маршрутах» исправлена с `RELAY` на `PRIMARY` — слота relay в конфигурации
  нет, и обещать его нельзя.
- `RoutingStore` читает `smart_routing` (и остальные ключи) из XML на диске, а
  не из кэша SharedPreferences процесса `:vpn`. Переключатель «умная
  маршрутизация» теперь действует со следующего подключения **без
  force-stop**; проверено: выключили в «Маршрутах» → отключили/включили VPN →
  бейдж `MASQUE: RU`, процессов router/desync нет.

## 2026-11-01 — 1.34.1 / versionCode 165 — OBNOVA

- Приложение переименовано в **OBNOVA**: `app_name`, описание, `Start/Stop
  OBNOVA VPN`, канал и заголовок уведомления «OBNOVA VPN», превью в настройках
  уведомлений, стартовая строка журнала, список WARP-конфигураций, экран
  диагностики и метка буфера обмена, подписи `ParityScreenActivity` и плитки
  Quick Settings, а также MASQUE device name «OBNOVA Android». Технические
  имена (package `com.brent.nova`, классы, каналы, ключи) не менялись.
- `versionName 1.34.0 → 1.34.1`, `versionCode 164 → 165`.
- APK теперь называется `OBNOVA_1.34.1.apk`.
- Release собирается **по ABI**: `OBNOVA_1.34.1-arm64-v8a.apk` (35,1 МБ),
  `OBNOVA_1.34.1-armeabi-v7a.apk` (33,2 МБ) и универсальный
  `OBNOVA_1.34.1-universal.apk` (64,8 МБ). Для телефона ставится один
  ABI-APK — экономия ~46% против универсального.
- Ключ релея остался стоковым `nova-android-164`, поэтому publishability-gate
  не проходит по версии; personal-release собирается с
  `NOVA_ALLOW_UNPUBLISHABLE_BUILD=1`. Для публикуемой сборки ключ нужно
  получить из upstream под 165.

## 2026-11-01 — parity v2: политика маршрутов и каркас меню

- Новый `RoutingPolicy.kt`: порядок правил PC-форка (`user_direct → user_ru/eu →
  exclude → локальные TLD → режим приложения → AI → EU → YouTube → RU →
  discord/telegram/whatsapp → Cloudflare-IP → foreign_default`). Чистый Kotlin,
  без Android API.
- Новый `SlotResolver.kt`: класс маршрута → цепочка слотов (PRIMARY/SECONDARY/
  RELAY/DIRECT) с учётом страны выхода, Tor и killswitch — аналог строки PAC.
- Новые `DomainList.kt` и `RoutingListsSnapshot.kt`: формат списков PC
  (`#`-комментарии, границы доменных меток) и неизменяемый снимок для политики.
- Новый `nova-router/` (Go): SOCKS5-диспетчер между tun2proxy и слотами —
  правила по домену, цепочки `socks5/http/direct`, первый живой слот, тесты
  конфигурации, fallback и HTTP CONNECT. Бинарь `libnova-router.so` (3,6 МБ,
  arm64) упакован в `jniLibs`, процессом управляет `RouterProcess.kt`.
- `RouterConfig.kt` собирает JSON-конфигурацию для роутера из Kotlin-политики
  (детерминированный порядок, экранирование) — тесты на точный JSON.
- `RouteSettings.kt` + `RoutesActivity`: экран «Маршруты» с восемью сервисными
  категориями, ручным выбором слота, переключателями `foreign_default` и
  killswitch; значения хранятся в `ClientData` и переживают перезапуск.
- `SlotsActivity`: экран «Слоты» с карточками PRIMARY/SECONDARY/RELAY/DIRECT;
  показывает состояние службы, backend, endpoint Opera, режим split-tunnel.
- `MULTI`: первый реальный путь через `nova-router` — PRIMARY = DESYNC
  (локальный SOCKS5), SECONDARY = Opera (HTTP CONNECT, подключается, если
  доступна), DIRECT — напрямую, локальные TLD в DIRECT. При недоступной Opera
  сеанс не падает и работает без SECONDARY.
- `MULTI`: если в подписке есть живой VLESS-профиль, он поднимается как
  **PRIMARY** (SOCKS5 без TUN), а DESYNC остаётся запасным слотом `spoof`;
  без профилей PRIMARY — DESYNC, как раньше. VLESS проверяется до 8 профилей
  за 20 с, первый живой остаётся; при смене транспорта ядро останавливается.
- `nova-router` получил **UDP ASSOCIATE**: tun2proxy больше не получает
  `CommandNotSupported` для DNS/UDP; датаграммы уходят через выбранный слот,
  UDP/443 (QUIC) отбрасывается, чтобы трафик шёл по TCP. Тест — UDP-эхо через
  релей.
- Killswitch теперь реально влияет на конфиг роутера: при включённом
  переключателе цепочки не получают DIRECT-хвост (явный DIRECT у сервиса
  сохраняется), поэтому при падении слотов соединение отклоняется, а не уходит
  напрямую.
- Шум в журнале: отмена prewarm-регистрации Opera (`terminated signal received`)
  больше не пишется как событие/CRITICAL, а уходит в debug — сеанс она не
  затрагивает.
- «Слоты»: кнопка «Проверить слоты» — реальный SOCKS5-запрос через
  `nova-router` к `1.1.1.1:443` с фолбэком по имени; статус показывает мс или
  причину отказа. Порт берётся из `status.json`, потому что `RouterProcess`
  живёт в процессе `:vpn`, а экран — в основном.
- Новый `ListSync` + кнопка **«Обновить списки»** в «Маршрутах»: тянет
  стоковый фид `confeden/nova_updates/nova_pc` (`manifest.json` + sha256) через
  raw.githubusercontent, jsdelivr и raw.githack, проверяет хеши и кладёт списки
  в `filesDir/routing_lists`; `ListRepository` предпочитает их встроенным
  asset-копиям, после успеха конфиг роутера перезагружается на живом сеансе.
  На устройстве: «Обновлено 02.11.2026 10:33 · обновлено файлов: 9».
- В карточке «Списки маршрутов» добавлена кнопка **«Обновить подписку»**, если
  в ClientData сохранена VLESS-подписка: она вызывает
  `VlessSubscriptionManager.refresh(force=true)` и показывает
  `+добавлено / −удалено / всего` (или причину отказа). Отдельный экран
  «WARP конфигурации» с подпиской остаётся как был.
- `nova-router` теперь считает и трафик по правилам: у каждого правила есть
  `name` (`ru`, `eu`, `youtube`, `telegram`, `exclude`, `local-tld`, …), счётчик
  попаданий пишется в `status.json` (`routes`). `StrategiesActivity` показывает
  блок «ТРАФИК ПО ПРАВИЛАМ»; Go-тест проверяет запись и разбор `routes`.
- MULTI/DESYNC при смене сети возвращают состояние `CONNECTED`, если туннель
  жив: раньше сторож ставил `CONNECTING`, пересборки не было, и экран висел
  «подключаемся» над рабочим туннелем.
- Известное ограничение: запрос по **имени** через DESYNC в MULTI падал
  (резолвер внутри движка отдавал недостижимый адрес), по IP — работал.
- Исправлено: `nova-desync` разрешает имена собственным резолвером через
  публичный DNS по TCP (1.1.1.1 → 8.8.8.8 → 9.9.9.9) с откатом на системный;
  TCP-путь уже проверен на той же сети. После правки проверка по имени
  проходит: `OK · 199 мс · www.gstatic.com:443`, а в трафике MULTI нет ни
  одного `SOCKS connection failed`/`CommandNotSupported`.
- `RouterConfigFactory` собирает конфиг из списков PC (exclude, main, second,
  ai, youtube, telegram, whatsapp, discord), локальных TLD и ручных маршрутов;
  `BLOCK` даёт пустой порядок слотов.
- `RouterConfigFactory` получил слот `spoof` и порядок `primary → spoof →
  direct`; Opera остаётся `secondary`.
- Проверка MULTI после добавления VLESS-PRIMARY: при 0 профилей PRIMARY
  остаётся DESYNC, роутер поднимается с 2 слотами и 9 правилами; Opera
  пропускается по 10-минутному backoff, ошибок нет.
- `nova-router` пишет `status.json`: попытки/успехи/ошибки и EWMA задержки по
  каждому слоту; `RouterStatus` читает его, экран «Слоты» показывает живые мс и
  счётчик успехов вместо статичного текста.
- Экран «Маршруты»: добавлена карточка «Приложения» — режим split-tunnel и число
  выбранных приложений со ссылкой на выбор. На Android per-app решается TUN-ом
  (`addAllowedApplication`), а не SOCKS-политикой, поэтому список не дублируется.
- Изменение маршрута на живом MULTI-сеансе перезагружает только конфиг роутера
  (`ACTION_RELOAD_ROUTER`), TUN и tun2proxy не пересобираются.
- MULTI, как и DESYNC, больше не реагирует на смену сети пересборкой TUN:
  локальные слоты не привязаны к интерфейсу, а `tun2proxy_stop` обрекал `:vpn`.
- UI-консолидация: кнопка ZAPRET убрана с главного экрана и из настроек —
  DESYNC теперь внутренний PRIMARY у MULTI. Боковое меню убрано; гамбургер
  снова открывает старые настройки, а «Маршруты и приложения», «Слоты»,
  «Стратегии», «Диагностика» добавлены строками в раздел «Маршрутизация».
- Карточка «Приложения» в «Маршрутах» открывает настройки сразу на разделе
  split-tunnel (`EXTRA_SCROLL_TO=split_apps`) и даёт быстрый выбор режима:
  «Все приложения» / «Только выбранные» (с подсказкой, что применится после
  переподключения VPN).
- `RoutesActivity` переведён на `SwitchCompat` (рекомендация AppCompat, убраны
  lint-предупреждения `UseSwitchCompatOrMaterialCode`); поведение тумблеров не
  изменилось.
- Opera после неудачи не пробуется 10 минут (`opera_retry_after` в RoutingStore):
  иначе каждое подключение MULTI тратило 6 секунд на заведомо мёртвую попытку.
- `DiagnosticsReportActivity`: реальный снимок через `DiagnosticSnapshot.buildReport`
  (23 КБ текста на устройстве), кнопка «Копировать»; строка «Диагностика» в
  настройках открывает его вместо заглушки. Отчёт начинается с
  `OBNOVA diagnostic log` и содержит `app=1.34.1 (165, …)` и `xray=26.10.21`.
- `StrategiesActivity`: аналитика из `status.json` — текущий сеанс, рейтинг
  кандидатов по доле успехов и EWMA, список слотов; обновление раз в 3 с.
  Томпсон/CUSUM отложены до появления истории измерений.
- Смена сети у MULTI/DESYNC обновляет `setUnderlyingNetworks` без пересборки
  TUN — ядро перестаёт считать VPN привязанным к старой сети.
- «Слоты» обновляют статистику сами каждые 3 секунды, пока экран открыт.
- Аудит логов устройства нашёл `dlopen failed: libnovaxray.so not found`:
  VLESS-ядро не было собрано в этом checkout. `libnovaxray.so` и
  `libnovaxrayjni.so` собраны для arm64-v8a и armeabi-v7a (NDK 27.2,
  `c-shared`, `-checklinkname=0`, page size 16K), упакованы в `jniLibs`;
  APK вырос до 65,4 МБ. В логе `Ядро Xray: 26.7.28`.
- Вендоренный Xray обновлён **26.7.28 → 26.10.21** (security-релиз: удалены
  скомпрометированные корневые CA, сборка на Go 1.27.3). `nova-xray/go.mod`
  обновлён (`go mod tidy`), ядро пересобрано под обе ABI; лог устройства после
  установки — `Ядро Xray: 26.10.21`, APK 64,8 МБ.
- Аудит логов WARP-сеанса: ошибок, ANR и crash нет; Opera prewarm успешно
  регистрируется только при живом туннеле (API-релей недоступен из MULTI с
  DESYNC) — это подтверждает, что SECONDARY ждёт VLESS-PRIMARY.
- `RoutingStore`: настройки маршрутизации вынесены в отдельный prefs-файл
  `nova_routing`. Причина: `ClientData` пишется из `:vpn`, а SharedPreferences
  не многопроцессный — флаг «умной маршрутизации» терялся после force-stop.
- `ListRepository` + `Ipv4Range`: списки PC в `assets/lists`, CIDR Cloudflare.
- Тесты: `RoutingPolicyTest` (14), `SlotResolverTest` (8), `DomainListTest` (4);
  `testGithubDebugUnitTest` и `assembleGithubDebug` — BUILD SUCCESSFUL.
- Новый `ParityScreenActivity` и экран-заглушка для «Маршрутов», «Слотов»,
  «Стратегий», «Диагностики» и «Журналов».
- Главный экран: гамбургер теперь открывает боковое меню (панель 300dp поверх
  контента), а не сразу настройки; «Настройки» — пункт меню.

## 2026-10-23 — 1.34.0 / versionCode 164

### Идентичность и обновления

- `app/build.gradle.kts`: `versionName 1.33 → 1.34.0`,
  `versionCode 163 → 164`.
- `app/build.gradle.kts`: `UPDATER_ENABLED` для flavor `github` переключён
  `true → false`; `fdroid` уже был `false`. Теперь официальный апдейтер выключен
  в обеих сборках.
- `SettingsActivity.kt`: при выключенном апдейтере скрываются не только
  переключатель и кнопка проверки, но и заголовок «ОБНОВЛЕНИЯ» и ссылка
  «Скачать последнюю версию».
- `SettingsActivity.kt`: внешняя ссылка в нижнем футере заменена с
  `https://t.me/nova_txt` на `https://t.me/abonent0007` (и `tg://resolve`
  domain `abonent0007`).
- `MainActivity.kt`: `refreshInstallUpdateButton()` первым делом прячет плашку
  обновления; в форке она не появляется и не опрашивает `DownloadManager`.
- Сетевые входы апдейтера уже имеют guards `isUpdaterEnabled` (проверено в
  `AppUpdateManager.syncSchedule`, `enqueueImmediateCheck`, `performUpdateCheck`,
  `performManualUpdateCheck`, `installReadyUpdate`, `resumePendingInstallIfAllowed`).
  Класс не удалён: он содержит helper'ы foreground-уведомления VPN.
- `app/build.gradle.kts`: relay key синхронизирован с текущим upstream
  `1.33.1` — `nova-android-164` вместо `nova-android-163`. Это важно для
  Opera/Proton/Cloudflare: старый ключ 163 мог быть погашен сервером, из-за
  чего регистрация Opera через relay не проходила. versionCode уже 164, так
  что ключ и версия теперь совпадают.
- DNS-AI: в Android-исходниках это автоматический `PriorityDns`
  (`dns.dns-ai.ru`), а не отдельная кнопка главного экрана. В baseline и
  upstream 1.33.1 такой кнопки нет; при запросе на неё нужно уточнить
  поведение (DNS-туннель, DoH-резолвер или ярлык настроек).

### Локальные TLD и direct-домены

- `DomainBypassRules.kt`: добавлены `LOCAL_EXTRA_ZONES`
  (`.москва`/punycode, `.moscow`, `.рус`/punycode, `.дети`/punycode, `.tatar`),
  `DEFAULT_ZONES`, `BUILTIN_DIRECT_DOMAINS` (10 российских сервисов на `.com` из
  PC `list/u_direct.txt`), `NONE_TOKEN`, `effectiveZones()`, `effectiveDomains()`.
- `ClientData.kt`: `isDomainBypassEnabled()` по умолчанию `true` для свежей
  установки; явно выключенное пользователем значение сохраняется.
- `DomainBypassActivity.kt` + `activity_domain_bypass.xml`: новый чекбокс
  «Локальные TLD…»; первый запуск отмечает его; сводка учитывает встроенные
  домены; при снятии всех галочек сохраняется `none`, а не пустая строка.
- `NovaVpnService.kt`: `applyCoreDomainBypassPolicy()` и
  `domainBypassAddresses()` используют `effectiveZones`/`effectiveDomains`,
  поэтому свежая установка реально получает локальные зоны и встроенные домены.
  Ограничение Android (WARP/MASQUE, TCP 80/443) остаётся и описано в логе.

### DNS TTL-кэш

- Новый `DnsResponseCache.kt`: разбор DNS-ответа, кэш по qname/qtype/qclass/
  recursion + отпечаток опций запроса (EDNS/DO), минимальный TTL answer-записей,
  negative fallback 60 с, отказ от TC и TTL=0, лимит 3600 с, LRU-подобное
  вытеснение, подстановка transaction ID при выдаче.
- `LocalDnsProxyManager.kt`: кэш проверяется до failover upstream, заполняется
  после успешного ответа, очищается при смене upstream/остановке.
- `DnsResponseCacheTest.kt`: TTL, переписывание ID, истечение, TC, TTL=0.

### Ротация профилей

- Новый `ProfileHealthRotation.kt`: пороги PC-форка — 45 с между сэмплами,
  90 с устаивания, 3 деградации, cooldown 15 мин, 2 смены/час, EWMA baseline,
  фактор 1.8, защита от ручного действия; только режим `auto`.
- `MainActivity.kt`: после успешного замера ping вызывается
  `maybeRotateProfile()`; при деградации нажимается существующая кнопка
  «следующий профиль» (она уже знает порядок и безопасно перезапускает сеанс).
  Ручной клик сбрасывает политику; автоматический клик не считается ручным.
  В auto разрешены только `MASQUE` и `AWG Proton`, как в PC-форке; WARP/Opera/
  Tor/DNS не трогаются.
- `ProfileHealthRotationTest.kt`: деградация, явный режим, cooldown, ручное
  действие, сброс при отключении.

### Matrix и фон

- `NovaTheme.kt`: `DEFAULT_KEY = "matrix"`.
- Новый `MatrixRainView.kt`: Canvas, 30 fps, fade-след, 7–18 глифов в колонке,
  статичный кадр при слабом устройстве/`animator scale = 0`, остановка при
  `onDetached`/невидимом окне, без WebView.
- Исправлен тикер: вместо `postInvalidateOnAnimation()` (только перерисовка)
  снова ставится `ticker` через `postOnAnimation`; дождь реально движется,
  проверено двумя скриншотами на AVD.
- `activity_main.xml`: добавлен `MatrixRainView` (`matrix_rain`) под контентом.
- `MainActivity.kt`: при теме Matrix дождь заменяет созвездие; при остальных
  темах работает прежний `NovaNetworkBackgroundView`; акцент передаётся в оба.
  При смене темы без пересоздания Activity фон синхронизируется в
  `applyThemeAccent()`.

### Главный экран

- `activity_main.xml`: строка IP/узла и график ping перенесены в верхнюю часть
  экрана; центральный блок (статус, микро-лог, кнопка, селектор) опущен
  (`btnConnect` vertical bias 0.62).
- «Настройки»/язык остаются внизу и привязаны к строке версии, а не к строке
  адресов.
- Для горизонтали в `MainActivity.applyOrientationLayout()` сохранена прежняя
  нижняя компоновка адресов/графика, чтобы не пересекаться со статусом и
  кнопкой в средней колонке.
- `MainActivity`: добавлен `applySystemBarInsets()` — верхний отступ строки
  IP/узла учитывает status bar/вырез, нижний отступ версии — navigation bar
  (gesture bar). «Настройки» привязаны к версии и поднимаются вместе с ней;
  селектор пересчитывает высоту кнопок. Проверено bounds на AVD 1440×3120:
  IP `[84,256]`, version до `2945`, nav bar начинается с `2952`.

### Dock выбора узлов

- `activity_main.xml`: каналы главного экрана — одна полоса SVG-иконок
  (`DockRadioButton`) с дорожкой-ползунком; подписи появляются над иконкой при
  приближении пальца/курсора. Панель каналов — отдельная стеклянная плитка.
- Под выбранным узлом — отдельная плитка функций (`ll_exit_sub_region`:
  регион Opera/Proton, вход Tor, подсказка DoT); `tv_exit_last` — отдельная
  плитка статуса.
- `DockRadioButton.kt`: SVG-иконка из `app:dockIcon` (fallback — прежняя точка),
  акцентная tint-раскраска, анимация магнификации; текстовой подписи на кнопке
  нет — она проявляется над иконкой при наведении. Источник выбранного
  состояния — `RadioGroup`, поэтому активен ровно один узел. Preview при drag
  анимируется отдельно (`previewProgress`): иконка растёт, подпись растёт из неё
  (масштаб 0.15→1.0 вместе с прозрачностью), покинутая иконка плавно сжимается.
- `FlowRadioGroup.kt`: dockMode, ACTION_MOVE/HOVER, дорожка-ползунок, сброс через
  140 мс. Увеличивается **только ближайшая к пальцу иконка**; соседние остаются
  в покое. Под пальцем рисуется линза (radial glow) и две расходящиеся волны.
- Перетаскивание по дорожке: под пальцем показывается preview-выбор (иконка
  раскрывается до подписи, прежняя плавно сжимается), а `RadioGroup`
  получает окончательный `check()` только на `ACTION_UP` — на каком узле
  отпустили палец, тот и выбран. Во время drag `HorizontalScrollView` не
  перехватывает движение.
- `MainActivity`: dock включается только для главного экрана; `applyRegionChipHeights`
  не сжимает иконки; акцент темы передаётся в `DockRadioButton`/дорожку.

### Иконки и верхнее уведомление

- С главного экрана убраны emoji: «⚙️ Настройки» заменена на SVG-гамбургер
  (`ic_menu_hamburger`) в правом верхнем углу (32dp, стеклянная круглая кнопка
  `bg_icon_button`); «🌍 RU» — на SVG-глобус (`ic_globe`) в `drawableStart`
  кнопки языка. Навигация настроек осталась прежней.
- Узлы dock получили отдельные SVG: `ic_node_auto/warp/masque/opera/proton/tor/dns`;
  у Opera на x86-сборке иконка остаётся приглушённой, как и сама кнопка.
- `tv_top_notice` — верхняя плашка выбора/переключения (`showTopNotice`): текст
  «Выбрано: …», «Переключаем VPN на …», «Готовим профили Proton…» и ошибка
  переключения больше не уходят в системный Toast у нижней кромки. Плашка
  появляется под статус-баром, не перекрывает IP-строку и скрывается через 1.8 с.

### Release/R8

- `gradle.properties`: убран явный `android.r8.optimizedResourceShrinking=false`
  (дефолт `true`); `strictFullModeForKeepRules` пока `false` — включать только
  после проверки keep-правил.
- `app/proguard-rules.pro`: keep для `nova.**`, `go.**`, `XrayBridge`; прежние
  правила Tor/Opera/NovaViewInflater сохранены.
- Release собран с личным `nova-personal.jks` (в `.gitignore`) и
  `NOVA_ALLOW_UNPUBLISHABLE_BUILD=1`; APK подписан v2/v3, versionCode 164.
- Debug 57,837,411 байт → release 42,100,389 байт (−27,2%); `classes.dex`
  сжат (3,258,496 → 1,607,291 байт). Отдельного `enableDexCompress` нет.

### Фон и устойчивость VPN (после первого билда)

- `AndroidManifest.xml`: у `NovaVpnService` явно указан
  `android:stopWithTask="false"` (значение по умолчанию, но теперь намерение
  зафиксировано) и добавлена мета-дата
  `android.net.VpnService.SUPPORTS_ALWAYS_ON = true`. Это включает Nova в
  системный список «Постоянный VPN»; без мета-даты поддержка считается
  включённой, но с ней поведение явное.
- Новый `AlwaysOnVpn.kt`: чтение `always_on_vpn_app`/`always_on_vpn_lockdown`
  из `Settings.Secure` (в `runCatching`) и открытие
  `Settings.ACTION_VPN_SETTINGS`. Публичного API для always-on у `VpnService`
  нет, поэтому единственный доступный источник — защищённые настройки.
- `SettingsActivity` + `activity_settings.xml`: строка «Постоянный VPN» с
  тумблером-индикатором; нажатие открывает системный экран VPN, состояние
  перечитывается в `onResume`.
- `MainActivity.restoreDeadSessionOnOpen()`: если при возврате в приложение
  служба `:vpn` не найдена, но `auto_reconnect` включён и сохранена
  `restartSession`, сеанс поднимается автоматически через
  `ACTION_RESTORE_LAST_SESSION`. Раньше `reconcileSystemVpnStateIfNeeded()`
  выходил по `?: return`, потому что после kill процесса VPN-интерфейс исчезает
  вместе с ним, и экран оставался в STOPPED — отсюда ручное «Подключить».
- `MainActivity.maybeShowBackgroundGuide()`: одноразовая плашка после первого
  успешного подключения — «Разрешить работу без ограничений батареи»,
  «Разрешить автозапуск (вендор)», «Включить постоянный VPN». Флаг
  `background_guide_shown` в `ClientData`; если всё уже настроено, плашка не
  показывается и флаг ставится молча.
- `systemExempted` как тип FGS **не включали**: по официальной документации он
  разрешён VPN-приложению только при already-on VPN в настройках системы, иначе
  `ForegroundServiceTypeNotAllowedException`; выигрыш — только снятие лимитов
  запуска FGS, от OEM-киллера не спасает. `specialUse` оставлен.
- Аудит двух ИИ (gpt-oss 120B, Claude Haiku 4.5) и Luna показал, что типовые
  советы (`stopWithTask` «критично», `directBootAware=true`,
  `extractNativeLibs=false`, `largeHeap`, manifest-`CONNECTIVITY_CHANGE`) —
  мифы или вредны для этого приложения; правки не вносились.

### DESYNC: движок обхода DPI (Android)

- Новый каталог `nova-desync/` (Go, отдельный модуль): локальный SOCKS5, который
  для порта 443 режет первый TLS-рекорд на несколько записей и TCP-сегментов с
  паузами, а также отправляет decoy-ClientHello с подменённым SNI на отдельном
  соединении. Остальные порты — passthrough.
- Тесты: разбор ClientHello, пересчёт длин, замена SNI, деление рекорда,
  отказ на мусоре — `go test ./...` зелёный, `go vet` чистый.
- Живая проверка на Windows: `curl` через прокси к `https://example.com` —
  200 для базы, split 3, record-split и decoy.
- Найдено ограничение: паддинг настоящего ClientHello ломает transcript
  (`SEC_E_MESSAGE_ALTERED`), поэтому паддинг оставлен только для decoy.
- Сборка: `arm64` — 2,6 МБ (`-trimpath -ldflags=-s -w`, CGO выключен).
  32-битный `arm` пока не собран: для `android/arm` нужен cgo и полный NDK,
  локальный NDK 30 без toolchains.
- В APK упакован `libnova-desync.so` через `app/src/main/jniLibs/arm64-v8a/`
  (2,6 МБ); в `packaging.jniLibs` добавлен `keepDebugSymbols`, хеш файла в APK
  совпадает с исходным — strip не тронул.
- `DesyncProxyManager.kt`: запуск из `nativeLibraryDir`, `LD_LIBRARY_PATH`,
  ожидание готовности по порту, наблюдатель с тремя перезапусками, снятие
  осиротевшего процесса через `/proc`. Decoy оставлен включённым по требованию
  владельца (три модели считают его слабым, но решение владельца сильнее).
- Backend: `BACKEND_DESYNC`/`TRANSPORT_DESYNC`, `runDesyncPhase` (движок →
  `startProxyTunnel` → `CONNECTED`), ветки в `startSmartConnection`,
  переприменении настроек и основном цикле, остановка движка в общем cleanup.
  `compileGithubDebugKotlin` — BUILD SUCCESSFUL.
- Аудит трёх моделей (Google AI, Luna, Claude Haiku) дал обязательные пункты:
  supervisor (сделан), IPv6, per-app, Doze/батарея, переключатель QUIC.
  Decoy оставлен по решению владельца.
- Найдено живым прогоном на realme RMX3370: `Settings.Secure` там закрыт для
  приложения, `always_on_vpn_app` читался как null, и тумблер «Постоянный VPN»
  показывал выключенное состояние при включённой настройке. `AlwaysOnVpn.readState`
  теперь возвращает `null` для «неизвестно», а `updateAlwaysOnRow` в этом случае
  прячет тумблер и оставляет строку действием.
- UI: восьмая кнопка **ZAPRET** (`rb_exit_desync`, значение `desync`) в dock и
  в селекторе настроек, иконка `ic_node_desync`. `ConnectionSelectorPolicy`:
  ORDER/LABELS/ROWS (3+2+3)/BUTTON_IDS; в `RegionTransportPolicy.KNOWN_REGIONS`
  добавлен `desync`; тест политики обновлён (8 кнопок) и дополнен проверкой
  ZAPRET.
- `testGithubDebugUnitTest assembleGithubDebug` — BUILD SUCCESSFUL; release
  `Nova_1.34.0.apk` 41,1 МБ установлен на realme RMX3370 (см. VERIFICATION).
- Стабильность ZAPRET (найдено живым прогоном): отказ SOCKS конкретному
  адресу больше не считается потерей транспорта; смена сети не пересобирает
  TUN для DESYNC; `runDesyncPhase` держит фазу и не плодит второй движок.
  После правок — 90+ секунд без обрыва (см. VERIFICATION).
- Корень цикла `-1`: в DESYNC не вызывался `installSocketProtector()`, сокеты
  tun2proxy замыкались в собственный TUN. Добавлен; Go-движок получил
  `recover()` на соединение и тест на повреждённые ClientHello. Фаза теперь
  различает поколения и не блокирует новый сеанс.
- DNS/UDP: добавлен SOCKS5 UDP ASSOCIATE (DNS работает без `CommandNotSupported`),
  UDP/443 отбрасывается; повторный выбор ZAPRET переиспользует живой туннель,
  обречённый `:vpn` передаёт работу новому процессу.
- Ещё нет: отдельного раздела настроек DESYNC — параметры уже живут в
  `ClientData` (`desync_split`, `desync_delay_ms`, `desync_record_split`,
  `desync_decoy_enabled`, `desync_decoy_sni`), но UI для них не сделан. QUIC не
  поднимается потому, что SOCKS5-сервер отвергает UDP ASSOCIATE.

### Статический анализ и устранение хардкода (2026-10-28)

- Инвентаризация DESYNC: из `DesyncProxyManager` убраны зашитые `split=3`,
  `delay=12ms`, `record-split`, decoy `www.microsoft.com` — всё вынесено в
  `DesyncOptions` и `ClientData.getDesyncOptions()` (prefs, нормализация границ
  1..8 / 0..200 мс, валидация decoy-хоста с откатом на дефолт).
- Восстановлено логическое звено совместимости: `Process.isAlive`,
  `waitFor(timeout)` и `destroyForcibly` существуют с API 26, а `minSdk 24`.
  Добавлены `aliveCompat`/`waitExitCompat`/`destroyForciblyCompat`; на Android 7
  остановка движка больше не падает с `NoSuchMethodError`.
- Fallback-цепочка: недоступный бинарь → понятный отказ узла; битый decoy →
  дефолтный хост; сбой decoy/паддинга → обычный поток; движок не открыл порт →
  `start()` возвращает 0, служба не объявляет CONNECTED; падение движка →
  до трёх перезапусков, затем лимит.
- Тесты: новый `DesyncOptionsTest` (6 кейсов: дефолты, границы, разбор URL,
  битые хосты, аргументы движка, выключенный decoy). `testGithubDebugUnitTest` —
  зелёный.
- `go test ./...` и `go vet ./...` — чисто; `lintGithubDebug` по файлам DESYNC
  новых замечаний не даёт (в репозитории остаются 42 старых ошибки, не связанных
  с этой работой). Скан импортов новых Kotlin-файлов — неиспользуемых нет.
- Не делалось: массовый рефакторинг `nova.pyw`-подобных гигантов, замена
  внешних источников данных (у DESYNC их нет) и UI-раздел настроек — параметры
  пока меняются только через `ClientData`.

### Документация

- `docs/plans/2026-10-23-nova-android-fork-parity.md` — полный план.
- `docs/architecture/ARCHITECTURE.md` — карта архитектуры.
- `docs/fork/AGENT-HANDOFF.md` — инструкция следующему агенту.
- `docs/fork/CHANGELOG.md` — этот журнал.
- `docs/fork/RELEASE_AUDIT.md` — хардкоды/заглушки/R8/подпись.
- `docs/fork/VERIFICATION.md` — фактические прогоны.

## AGI-консультация

- Совет (Duck + Claude Haiku) по коду: подтвердил направление, но часть
  замечаний оказалась неточной для этого кода:
  - кэш хранит **весь** ответ, включая CNAME-цепочку, а не только A;
  - ID переписывается в уже готовом пакете, compression pointers не зависят от
    ID и остаются валидными;
  - `android.enableDexCompress` в AGP 9.x не подтверждён — не используем.
- Полезные замечания, внесённые в код:
  - в ключ кэша добавлен отпечаток EDNS/DO (иначе DNSSEC-запрос мог получить
    не-DNSSEC ответ);
  - `MatrixRainView` проверяет `isShown`, не создаёт `Paint` в каждом кадре,
    имеет static fallback;
  - `ProfileHealthRotation` не трогает явно выбранный режим.
- Остающиеся риски: Android-ядро обходит только WARP/MASQUE TCP 80/443.
  Relay key обновлён до `nova-android-164` (совпадает с versionCode 164);
  cloud profiles не затронуты.

## Проверки

- Baseline до правок: `gradlew test` — **BUILD SUCCESSFUL**.
- После правок: см. `docs/fork/VERIFICATION.md` (обновляется по мере прогонов).
