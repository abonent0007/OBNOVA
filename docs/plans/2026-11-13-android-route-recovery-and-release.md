# Карта планов: стабилизация маршрутов и выпуск OBNOVA (2026-11-13)

> Точка входа после сжатия сессии. Читать вместе с `docs/fork/AGENT-HANDOFF.md`,
> `docs/fork/CHANGELOG.md`, `docs/fork/VERIFICATION.md`.
> Текущая версия проекта — **1.34.11 / versionCode 175**, релиз ещё не поднят.

## 0. Контрольное состояние (2026-11-14)

**P0 + P1 — CLOSED. P2 — PARTIAL. P3 — BLOCKED.**

- P0: Android UDP bridge — PASS.
- P1.1: UDP capabilities — PASS.
- P1.2: UDP dispatcher — PASS.
- P1.3: Android regression — PASS (два домена, TCP-only drop, AWG-down drop).
- P2.2: внешний UDP через WARP — PASS (DNS/STUN, один egress с TCP).
- P2.3: non-RU egress — BLOCKED; на проверенных Wi-Fi и LTE маршрутах WARP
  дал RU-адреса, Proton NL не поднялся.
- P3: Google AI в MULTI — BLOCKED до рабочего non-RU endpoint.

**Код заморожен.** Не трогать `awg_socks_udp.go`, `nova-router`,
`SupportsUDP`, strict first slot, TCP dispatcher и правила маршрутизации.
Версию не поднимать. Разблокировка — один официальный Proton NL `.conf`
(локально, вне Git/assets/APK) или VPS в NL/DE.

## 1. Цель

Довести личный Android-форк до состояния «поставил на чистый телефон — работают
Telegram, YouTube, Gemini, ChatGPT и обычные приложения в России», без зависших
регистраций и мусора от старых сборок. Основной критерий: чистая установка,
подключение < 20 с, основные сервисы работают.

## 2. Фактическое состояние на 2026-11-13

### Работает

- Чистый цикл `tools/rebuild-install-clean.ps1`: `gradlew clean` → release с
  `-PnovaCfProxySlot=true` → установка → `pm clear` → запуск.
- На чистой установке нет зависшей регистрации WARP: авто-генерация профилей
  на connect отключена при отсутствии identity.
- MULTI поднимается на `AWG bundled seed` (bundled WARP bootstrap,
  `162.159.195.9:500`), TUN и роутер работают.
- Автоперенос профилей в единую базу после первого CONNECTED (меню больше не
  пустое).
- Через локальный роутер (проверено `curl --socks5-hostname 127.0.0.1:1373`):
  - `https://www.youtube.com/` → **200**
  - `https://telegram.org/` → **200**
  - `https://gemini.google.com/` → **200**
  - `https://chatgpt.com/` → **200**
- **Non-RU egress (новое):** при живом Opera default-маршрут —
  `secondary → primary → spoof → direct`, поэтому обычные приложения выходят с
  US-адреса (`ipinfo.io` → `77.111.246.61`, Ashburn). Telegram/YouTube идут
  закреплёнными правилами через `primary` и не затрагиваются. Opera взята с ПК
  через `adb reverse tcp:1371 tcp:1371` (на ПК — `opera-proxy` US).
- **Смена split-режима:** `SessionReapply` теперь распознаёт MULTI/DESYNC/DNS/
  VLESS как tun2proxy-сеансы и делает controlled restart, поэтому новый
  allow-list применяется сразу, а не после ручного переподключения.
- Кнопка «след. профиль» в MULTI не рвёт TUN: горячая смена PRIMARY, у
  MASQUE-перебора бюджет; AWG теперь перебирает следующие seeds, а не уходит
  в мёртвый VLESS.

### Не работает / блокеры

- **ChatGPT-приложение — 403, браузер работает.** `android.chat.openai.com` с
  IP Opera отвечает `403 {"cf_details":"Request is not allowed…"}`; это
  репутация IP прокси, а не маршрут. Нужен чистый non-RU egress (VPS/живой
  EU-релей), DNS-AI не помогает.
- **Gemini-приложение — QUIC.** Запрос уходит в UDP 443
  (`tun2proxy: Beginning #… UDP …:443`, `timed out`), а Opera — TCP-прокси и
  UDP не несёт; TCP-fallback приложение не делает. Браузер Gemini работает.
- **Opera EU не поднимается:** API-регистрация
  `api2.sec-tunnel.com/v4/register_subscriber` уходит в timeout; до
  `server-selection` дело не доходит. Прямой API и через MASQUE/AWG — timeout/502.
  `opera-proxy` уже v1.31.0 (последний), обновлять некуда.
- **EU-выхода нет:** Cloudflare trace по слотам даёт `loc=RU`
  (DME/AMS/ARN), публичные VLESS мертвы.
- **MASQUE seed из PC не подхватывается:** в логах используется endpoint
  `162.159.195.9` из WARP bootstrap, а не `app/src/main/assets/masque_seed.json`
  (PC `Cloudflare MASQUE 3.json`). Нужно проверить, почему
  `getMasqueConfigJson()` не пуст на чистой установке и кто его создаёт.
- **Telegram voice:** `awgSocksServer` TCP-only, UDP relay нет; голос через VPN
  сейчас не работает. Решение — UDP ASSOCIATE через `wgnetstack` или честное
  ограничение в UI.
- **QUIC UDP/443:** `nova-router/udp.go` дропает; YouTube откатывается на TCP.
  Для Telegram voice нужен отдельный UDP-путь.

## 3. Принятые решения (не пересматривать без новых замеров)

1. **Один TUN + router 1373.** PRIMARY — логическая группа
   MASQUE/AWG/DESYNC; TUN не пересобирать при смене профиля.
2. **Route matrix (целевая):**
   - `direct` — RU, local TLD, exclude, Spotify.
   - `opera → primary → spoof → direct` — default (не-RU для остальных
     приложений), когда Opera жива; без Opera — `primary → direct`.
   - `primary → direct` — YouTube.
   - `primary → awg → vless` — Telegram, без direct (fail-closed).
   - `opera → vless` — OpenAI app-хосты, fail-closed; без DNS-AI/direct/primary.
   - `opera → vless` — Claude/Anthropic, отдельная группа, тот же fail-closed.
   - `direct` — DeepSeek/Qwen/китайские AI; группа `china-ai` переопределяется
     отдельно, не затрагивая остальные AI-правила.
   - `opera → direct → dnsai → primary` — Gemini support-хосты.
   - `opera → dnsai → direct → …` — `google_ai`/`ai`: страна выхода важнее
     резолвера, DNS-AI остаётся запасом.
   - DNS-AI — **только резолвер** и браузерный AI; не транспорт для app.
3. **OpenAI app fail-closed:** `openai` домены
   `ab.chatgpt.com`, `android.chat.openai.com`, `api.openai.com`,
   `auth.openai.com`, `oaistatic.com`, `oaiusercontent.com` — только
   Opera/VLESS, без тихого RU-выхода.
4. **First-run:** bundled/feed → TUN → фоновая регистрация с таймаутом; UI не
   блокировать. Автогенерация WARP на connect при отсутствии identity
   запрещена; полный скан — по кнопке.
5. **Сборка:** release только с `-PnovaCfProxySlot=true`, иначе MULTI падает в
   DESYNC (см. AGENTS.md).
6. **Чистые тесты:** `pm clear` перед тестом, но помнить: он стирает
   `masque_config.json`. Для будущего — отдельный сброс маршрутов/кэшей без
   стирания учётных данных.

## 4. План работ

### P0.1 Аудит Proton AWG/netstack (2026-11-13)

- `tools/amneziawg-go/tun/netstack/tun.go` содержит `DialContext` (TCP/UDP),
  `DialUDP`, `DialUDPAddrPort`; `AwgSocksInstance` уже держит `*wgnetstack.Net`.
  Значит, отдельный WireGuard-клиент и второй `VpnService` не нужны.
- **Граница процессов:** netstack живёт в `:vpn` (JNI), `nova-router` — отдельный
  процесс. Прямой вызов `netStack.DialUDP` из роутера невозможен без переноса
  движка. Правильный шов — существующий локальный SOCKS-мост: добавить в
  `awg_socks.go` UDP ASSOCIATE и заставить роутер пересылать UDP в этот relay.
- `serveUDPAssociate` сейчас: `port == 443` пропускается, остальной UDP идёт
  **direct**, слот не выбирается. Нужна capability-based маршрутизация:
  `SupportsUDP(slot)` → UDP через relay слота, иначе fail-closed/ошибка.
- **Не доказано:** отсутствие TCP-fallback у Gemini. Нужен эксперимент C:
  контролируемо отклонять UDP/443 для google-ai и смотреть, появится ли TCP SYN.
- **Порядок:** (1) стабилизировать Proton lifecycle/READY; (2) UDP ASSOCIATE в
  `awg_socks.go` + Go-тест; (3) UDP outbound в роутере; (4) `proton_awg` слот и
  `google-ai` TCP+UDP fail-closed; (5) реальные Android-тесты. Не менять
  openai/anthropic/china-ai/Telegram/YouTube/Play/Gmail.
- **Критерии P0 (без них не трогать google-ai):**
  1. UDP-запрос через `wgnetstack.Net` получает ответ.
  2. SOCKS5 UDP ASSOCIATE передаёт запрос и возвращает ответ через этот
     netstack (BND.ADDR/BND.PORT, ATYP IPv4/IPv6/DOMAIN, закрытие по TCP
     control, без `direct` при ошибке `DialUDP`).
  3. `nova-router` шлёт пакет в `proton_awg` и при отказе Proton не уходит в
     `direct`.
- **Состояния Proton:** `STARTING → HANDSHAKING → TUNNEL_READY → PROBING →
  CONNECTED/FAILED`; handshake — не доказательство готовности, нужны UDP-probe,
  TX/RX и защита underlay-сокета.
- **Классификация UDP:** домен из SOCKS5 → DNS-кеш IP↔domain (TTL, CNAME,
  несколько доменов на IP) → QUIC Initial SNI как резерв; неклассифицированный
  пакет не отправлять в случайный слот.

### P0.2 Куратор (ChatGPT): UDP ASSOCIATE — рекомендации

Источник: диалог `https://chatgpt.com/c/6af6f462-ca28-83ed-8ae4-c7c893e1fde1`
(куратор). Записываю дословно по смыслу, чтобы не потерять при сжатии.

- **Два разных UDP-сокета, не смешивать:**
  - локальный приёмник SOCKS5 — обычный host `net.ListenUDP("udp", 127.0.0.1:0)`;
  - удалённый транспорт — только `netStack.DialUDP` (connected `gonet.UDPConn`).
  - `ListenUDPAddrPort` netstack — это внутренний loopback gVisor, а не host
    loopback; для приёма пакетов от другого процесса Android он не годится.
- **Компоненты:** `udpAssociation` (владелец relay, control, client, flows,
  closeOnce/done), `udpFlow` (connected conn, remote, lastActivity),
  узкий адаптер `UDPTransport`/функции для тестируемости.
- **Последовательность:** завершить SOCKS5 handshake → CMD 0x03 → проверить
  версию/резерв/адрес клиента → взять TCP peer IP как ожидаемый IP клиента →
  host UDP listener `127.0.0.1:0` → вернуть реальные BND.ADDR/BND.PORT →
  запустить UDP loop → следить за TCP control. Порт выбирает ОС; `0.0.0.0` в
  BND не возвращать.
- **RFC 1928:** адрес/порт в UDP ASSOCIATE request — ожидаемый адрес клиента;
  нулевой допустим; закрытие TCP control завершает ассоциацию.
- **Безопасность:** ассоциация привязана к TCP control и адресу клиента;
  посторонний локальный процесс не должен пользоваться relay.
- **FRAG:** принимать только `FRAG = 0`, остальные отклонять.
- **DNS:** при `ATYP = DOMAIN` резолвить через netstack (`LookupContextHost`),
  не через системный DNS Android.
- **Ошибка `DialUDP`:** drop/ошибка, без `direct`-fallback.
- **Следующий шаг куратора:** независимый аудит diff этого этапа до
  `SupportsUDP(slot)` и правила `google-ai`.

**C. Go-тесты (минимум):** парсер IPv4/IPv6/DOMAIN и битые пакеты; `FRAG != 0`
drop; echo через тестовый транспорт; DOMAIN через `LookupContextHost`;
повторный запрос к тому же адресу использует flow; два назначения — два flow;
ответ с правильными ATYP/адресом/портом; чужой IP/порт drop; `DialUDP`/DNS
ошибка → drop без системного UDP; TCP EOF закрывает listener и все сокеты;
повторный cleanup безопасен; нет оставшихся горутин. Команды:
`go test -count=100`, `go test -race`.

**D. Android-проверки:** relay доступен другому процессу по BND; Proton egress
на свой VPS UDP echo (nonce + внешний IP = NL); DOMAIN через netstack без
системного DNS; два назначения одновременно; Proton off → нет обхода; TCP EOF
закрывает ресурсы; 100 циклов без роста FD/goroutine/памяти. Доказательство —
серверные логи, nonce и счётчики AWG TX/RX, а не успешный `Write()`.

**E. Типичные ошибки gVisor:** не путать netstack-loopback с host-loopback;
`Write()` не равен доставке; для адреса источника использовать `ReadFrom` или
известный `RemoteAddr`; большой буфер; закрывать сокет при отмене; проверять
DNS-конфиг netstack; нормализовать IPv4-mapped IPv6; один flow на endpoint;
`sync.Once`/владение при cleanup; не игнорировать асинхронные UDP-ошибки.

**F. Условия завершения этапа:** Go-тесты и `-race` зелёные; реальный UDP echo
через Proton; DOMAIN через netstack; второй клиент не может использовать чужую
ассоциацию; при отключении Proton нет обходного маршрута; TCP EOF закрывает
UDP-ресурсы; 100 циклов без утечек. Граница: не менять `nova-router`,
`serveUDPAssociate`, `google-ai` и остальные правила. На выходе — diff, тесты и
Android-логи; до независимого аудита этап не принят.

**G. Аудит 2 (куратор):** lifecycle/FRAG/DNS/no-direct — приняты; остались
idle cleanup race (исправлено флагом `removed` + идемпотентный `removeFlow`),
`-race` (нет cgo на Windows — нужен Linux/WSL), AAR/Android (не собрано).
`serve()` синхронно делает DNS/Dial — куратор считает это P1, не блокером P0;
асинхронные очереди сейчас не добавлять. Следующие шаги: `-race` на Linux,
AAR, реальный UDP echo через Proton, проверка утечки при отключении AWG.

**H. Аудит 3 (куратор):** порядок — сначала `go test -race ./engine` на
Linux/WSL, затем AAR/APK, Android UDP echo через Proton, только потом router.
- `touch()`/cleanup должны быть под одним mutex: исправлено — `touchFlow()`
  обновляет `lastActivity` под `a.mu`, cleanup под тем же mutex помечает
  `removed`, удаляет из map и закрывает conn после unlock.
- Проверять `DST.ADDR:DST.PORT` из UDP ASSOCIATE, если клиент задал конкретный
  endpoint; нулевой — по RFC 1928, привязка по TCP peer IP + первому UDP-порту.
  Исправлено: `parseExpectedUDPEndpoint` + проверка в `handleDatagram`.
- P0 принят, когда race-тесты зелёные, Android UDP echo через Proton работает,
  DOMAIN работает, direct fallback отсутствует, ресурсы освобождаются.
- P1 — router UDP/SupportsUDP; P2 — google-ai. DNS/Dial async — P1, не
  задерживать Android-проверку.

**I. Race-проверка (выполнено):** `go test -race ./engine` — **ok**;
focused `-race -count=20` — **ok** (33 с). Использован MinGW-w64 gcc 16.2
(`CGO_ENABLED=1`, `CC=...\mingw64\bin\gcc.exe`). Следующий шаг — AAR/APK и
Android UDP echo через Proton.

- **Toolchain для AAR:** Go 1.27.4 — `%USERPROFILE%\dev\go`;
  MinGW-w64 gcc 16.2 — `%USERPROFILE%\dev\mingw64`; `gomobile` —
  `%USERPROFILE%\dev\go\bin\gomobile.exe`; NDK `27.2.12479018`.
  Команда: `gomobile bind -trimpath -ldflags="-buildid=" -androidapi 24
  -target=android/arm,android/arm64 -o nova-core.aar .` из `nova-core`.
  Затем заменить `app/libs/nova-core-api24*.aar` и
  `app/src/main/jniLibs/<abi>/libgojni.so`, собрать APK.

**J. Аудит 4 (куратор):** DNS UDP через `1.1.1.1:53` — достаточно для
первичного P0 transport proof, но доказывает только WARP/AWG, не Proton NL.
- Тест: Kotlin/Android-клиент в отдельном процессе (не Go), TCP control →
  SOCKS5 UDP ASSOCIATE с заранее открытым UDP-портом → DNS-запрос `example.com A`
  → проверить TX ID, QR, question, source 1.1.1.1:53 → повторить ATYP=DOMAIN
  (`one.one.one.one:53`) → закрыть TCP и убедиться, что ассоциация умерла.
- Снять AWG TX/RX до/после; прислать лог клиента, счётчики и DNS-результат.
- P0: Android UDP round-trip на WARP/AWG + DOMAIN + fail-closed при отключении
  AWG + 100 циклов/ресурсы. P1: router UDP/SupportsUDP. P2: Proton NL/VPS +
  Gemini/ChatGPT в MULTI. `nova-router` пока не трогать.
- VPS обязателен только для подтверждения Proton egress, не для P0.

**K. Аудит 5 (куратор):** попытка подставить PC Proton NL в
`warp_awg_seed.conf` (NL-FREE-241 185.183.34.41:443, NL-FREE#103
212.8.253.154:443/1194) не дала handshake. Гипотеза: **PROTON mode и
AwgSocksBridge используют разные движки/обработку AWG-параметров**
(стандартный WireGuard vs AmneziaWG), либо PC-профили от 29.09 устарели.
План:
1. Не менять seed вслепую; исследовать оба движка и совместимость
   `NovaMode`/S1..I1/`configToUAPI`.
2. Получить свежий WARP-конфиг генератором приложения; проверить handshake на
   PC через `StartAwgSocks`, затем на Android.
3. Получить фактический рабочий Proton runtime config (debug-only, приватный
   каталог) и передать его в bridge без assets/Git/логов.
4. Сохранить контрольную точку рабочего Proton NL (212.8.253.155).
5. Только после воспроизводимого handshake + UDP DNS — возвращаться к P0.
`awg_socks_udp.go`, `nova-router`, правила и direct-fallback не менять.

**L. P0-Bridge: PASS (контролируемый локальный peer).** Android end-to-end:
`LOCAL_ECHO PASS`, `DOMAIN_ECHO PASS` (DNS `udp-test.example` через
`10.77.0.1:53`, `LookupContextHost`), `LIFECYCLE PASS`, 100/100 циклов,
FD 157→157, threads 43→43, `FAIL_CLOSED PASS`, `protect_result=true` IPv4/IPv6.
Тестовый сервер/activity и тестовый конфиг удалены из release; baseline
`warp_awg_seed.conf` восстановлен. Внешний Proton/WARP endpoint по-прежнему не
отвечает — это отдельный P2-блокер, не мешающий начать P1.
Следующий этап — P1: UDP в `nova-router`, `SupportsUDP(slot)`, затем P2
Proton/external egress.

**M. P1.1/P1.2: UDP dispatcher в router — реализовано, тесты зелёные.**
- P1.1: `SlotSpec.udp` задаётся транспортом при создании слота
  (`multiPrimaryKind`/`multiCfProxyKind`/`multiAwgPort`), не сравнением с
  портом 1378; тест на смену порта AWG проходит.
- P1.2: `serveUDPAssociate` проходит `routeOrderFor` и обслуживает **только
  первый слот правила**. `udp:true` → SOCKS5 UDP ASSOCIATE к слоту,
  hostname сохраняется (`ATYP=DOMAIN`), ответы фильтруются по flow. Для
  каждого домена — отдельная upstream-ассоциация, чтобы одинаковый порт у
  двух имён не смешивал ответы; payload копируется перед горутиной (найден
  race). `udp:false`/неизвестный слот/ошибка транспорта → drop; direct
  fallback отсутствует. `UDP/443` отбрасывается.
- `go test ./...`, `-race`, `-count=20` — ok; Kotlin unit-тесты — ok.
- arm64 и armv7 `libnova-router.so` пересобраны (armv7 — cgo/NDK), оба PIE.
- Открытый вопрос: при первом TCP-only слоте (Opera `secondary`) UDP,
  включая DNS/53, отбрасывается по контракту fail-closed. Это осознанно;
  рабочий DNS через UDP появится только с UDP-capable первым слотом (AWG)
  после P2. Android обычно использует DoT/TCP, но приложения с прямым UDP/53
  останутся без ответа.
- **P1.3 Android:** `ROUTER_UDP_TWO_DOMAINS PASS` (first.example →
  10.77.0.1, second.example → 10.77.0.2, один порт, одновременно),
  `ROUTER_UDP_TCP_ONLY DROP PASS`, `ROUTER_UDP_NO_AWG DROP PASS`. Тестовая
  инфраструктура удалена, release пересобран.

**N. P2: внешний UDP подтверждён на WARP, non-RU egress — BLOCKED.**
- Штатный PROTON (Wi-Fi и LTE): все импортированные Proton-конфиги
  `warp-awg-exact` дают `no_traffic`, сессия останавливается. Это P2.1 FAIL.
- MULTI/WARP на Wi-Fi: `EXTERNAL_UDP_DNS PASS` (1.1.1.1:53) и
  `EXTERNAL_UDP_STUN PASS mapped=104.28.230.248:40314`; TCP-egress тот же,
  физический IP Wi-Fi `194.34.98.234` — direct-утечки нет. P2.2 PASS.
- `104.28.230.248` — Cloudflare WARP, geo **RU/Moscow**; на LTE WARP дал
  `104.28.198.247`, тоже RU/Moscow. На проверенных Wi-Fi и LTE маршрутах
  non-RU не получен, поэтому P2.3 BLOCKED. Без рабочего Proton NL/VPS
  дальше нельзя; код заморожен.
- Временная activity внешнего UDP удалена; release пересобран без неё.

### P0. Non-RU выход и Gemini Android (состояние)

- **Сделано:** при живом Opera default-маршрут `secondary → primary → spoof →
  direct`; Chrome показывает US-адрес. Opera берётся с ПК через
  `adb reverse tcp:1371 tcp:1371` (на ПК — `opera-proxy` US).
- **Причина Gemini:** приложению нужен QUIC/UDP 443; Opera — TCP-only и
  `nova-router` пропускает `UDP/443`. Успешного TCP-fallback в окне
  диагностики не видно, но его отсутствие не доказано. WARP (UDP, RU) отвечает
  «страна не поддерживается», **Proton NL (UDP, non-RU) — работает**: egress
  `212.8.253.155`, HTTP/3, Gemini и ChatGPT app отвечают.
- **Граница:** это режим PROTON, не MULTI. В MULTI нет non-RU UDP-слота
  (`secondary` — TCP-only, `awg`/`primary` — TCP-мост с RU-выходом, VLESS
  мёртв, MASQUE не поднялся). Для Gemini в MULTI нужен UDP-capable слот и
  пересылка UDP; без этого рабочий вариант — PROTON.
- **AI-группы:** `openai`, `google-ai`, `anthropic`, `china-ai` — независимые
  правила, общие слоты, переопределение через «Маршруты и приложения».
  Runtime-проверка групп в MULTI остаётся открытой (сеанс сейчас PROTON).
- **Осталось:** UDP-capable non-RU слот для MULTI (Proton/AWG с UDP ASSOCIATE
  или VLESS); проверить Claude/DeepSeek/Qwen под аккаунтами (сейчас открываются
  страницы, но вход/отправка не тестировались).

### P1. First-run и данные

- Проверить, кто создаёт `masque_config.json` при чистом старте (WARP bootstrap?).
- `masque_seed.json` использовать только как fallback; не хранить секрет в
  публичном APK дольше, чем нужно (пока это тестовый ключ владельца).
- Автоперенос в `UnifiedProfileStore` оставить, но не запускать тяжёлый
  `syncFromLegacy` на каждом connect — только при пустой базе (сделано).
- Критерий: чистый install → база не пуста, нет «регистрирую устройство»,
  connect < 20 с.

### P2. Маршруты и UDP

- Убрать из рабочих цепочек мёртвые `spoof`/`cfproxy`/`ws`, если они не нужны
  после стабилизации; оставить как ручной резерв.
- Telegram voice: проверить `DialUDPAddrPort` в `wgnetstack`; если есть —
  UDP ASSOCIATE через AWG, иначе ограничение в UI.
- QUIC: оставить drop UDP/443 для проксируемых категорий; проверить YouTube
  playback 3+ мин и Telegram voice.
- Критерий: Telegram/YouTube/Gemini стабильны 10+ минут, ChatGPT — при наличии
  EU.

### P3. Выпуск 1.34.12 / 176

- Поднять `versionName`/`versionCode` в `app/build.gradle.kts`.
- Обновить `CHANGELOG`, `VERIFICATION`, `AGENT-HANDOFF`, релизный файл
  `docs/fork/RELEASE_1.34.12.md`.
- `gradlew test` + `assembleGithubRelease` + `assembleGithubDebug` с
  `-PnovaCfProxySlot=true`; SHA-256 шести APK.
- Тег `v1.34.12`, публикация GitHub Release в `abonent0007/OBNOVA` (6 файлов:
  release/debug × arm64-v8a/armeabi-v7a/universal).
- Только после зелёного чистого прогона на реальном устройстве.

## 5. Открытые вопросы

1. Почему `masque_config.json` на чистом старте не пуст / откуда WARP endpoint
   вместо `masque_seed.json`?
2. Есть ли рабочий EU-релей Opera API (не `relay.nova-app.eu`)?
3. Готов ли владелец дать EU-VPS/VLESS для ChatGPT app?
4. Нужен ли Telegram voice через VPN, или достаточно текста/медиа?
5. Нужен ли UID-маршрут app vs browser, или domain split достаточно?

## 6. Быстрые команды

```powershell
# мост Opera с ПК (после подключения телефона по USB)
.\tools\enable-opera-bridge.ps1

# чистый цикл (release + установка + очистка данных + запуск)
.\tools\rebuild-install-clean.ps1 -Variant release

# только тесты
.\gradlew.bat testGithubDebugUnitTest

# egress через роутер (после adb forward tcp:1373 tcp:1373)
curl.exe -sS --ssl-no-revoke --socks5-hostname 127.0.0.1:1373 `
  https://www.cloudflare.com/cdn-cgi/trace
```
