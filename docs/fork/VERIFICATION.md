# Проверки форка

> Только фактические прогоны. Если проверка не удалась из-за среды — это
> записано отдельно, а не замаскировано словом «работает».

## 2026-11-15 — Release 1.34.12 (176)

- Версия: `versionName=1.34.12`, `versionCode=176`; `aapt2 dump badging`
  подтверждает.
- Release + R8: `assembleGithubRelease -PnovaCfProxySlot=true` — PASS.
- Debug: `assembleGithubDebug` — PASS.
- Kotlin: **706 тестов, 0 failures**; Go `nova-router` test + `-race` — PASS.
- Release APK:
  - `230699B2580A7DFFB6996F28A44991F28083F59E5C8D91C02C820163FB62C4B4` arm64-v8a
  - `0024A6B96FA8AAC8D5BE7FE18D31D75456555B4535E92C082DCB9920C563C079` armeabi-v7a
  - `093626679DD537F6F4C677483DC4205158E616D4663D55F86CF636C1EBF8CF17` universal
- Debug APK:
  - `5BA513B92C5855DE68A78CEE84587D479E9D9210BBCFE1D2B133D9DB639050F8` arm64-v8a
  - `F2155B88F4C7FECFC903EA09763BD903BA686D8D880D611CDCA40D7CC0D72374` armeabi-v7a
  - `3A919AA4D9496B8F743090962ABBBD533AA99032CB8D0D19D842FA2CB838C5BD` universal
- Release notes: `docs/fork/RELEASE_1.34.12.md`.
- Ограничения Strict (Nova UID 10997, tethering, user 0) — в release notes.


## 2026-11-14 — P-E.2B.3.2: UI отказа и финальные runtime-проверки

- UI Strict: точная формулировка области — «Strict Full Tunnel активен.
  Сетевой трафик приложений устройства направляется через MULTI. Служебный
  пакет Nova (UID 10997) … исключён из VPN … tethering не подтверждена».
- После отказа ручного backend: служба очищает pending-флаги, UI отменяет
  старт-флоу, показывает «НЕ ПОДКЛЮЧЕНО» и Toast с причиной
  «Strict: доступен только MULTI». Зависание «ПОДКЛЮЧЕНИЕ» устранено.
- Подтверждение включает предупреждение о банках/госуслугах/операторах,
  исключении Nova и tethering.

| Проверка | Результат | Маршрут/факт |
|---|---|---|
| Strict активен, все приложения | PASS | `ip rule`: UID 0-10996, 10998-20996, 20998-99999 → VPN table; Nova 10997 исключён |
| Банки/госуслуги/оператор | PASS (route-level) | UID 10308/10251/10794/10863 в VPN-диапазоне |
| Новое приложение | PASS | UID 10324 (`ipv6diag`) в VPN-диапазоне без обновления списков |
| DirectAppsPolicy | PASS | в Strict не вызывается |
| IPv4 | PASS | Firefox → `104.28.198.244` (через MULTI) |
| IPv6 | PASS (fail-closed) | table VPN: `unreachable default`; IPv6-сайт не открывается |
| AUTO + Strict | PASS | `CONNECTED backend=MULTI` |
| Ручной WARP | PASS | `STRICT: запуск ru отклонён`; UI «НЕ ПОДКЛЮЧЕНО» + причина |
| Отказ запуска MULTI | PASS (config-failure) | DIRECT-override в Strict → `RouterConfig отклонён`, WARP не поднят, MULTI retry; после возврата AUTO — CONNECTED |
| Потеря upstream / всех слотов | PASS | `svc data disable`: VPN-процесс, `libnova-router.so`, `tun0`, table 1237 и UID-правила остались; Firefox не получил ответа; после `svc data enable` Strict-конфиг пересобран |
| Потеря primary-процесса | NOT TESTED | `kill` дочернего `libnova-desync.so` из shell запрещён (UID 10997, `Operation not permitted`); root/debug-хука нет |
| DNS failure (NXDOMAIN) | PASS | `nonexistent-probe.invalid`: Firefox «Адрес не найден», VPN остался ACTIVE, physical fallback не появился |
| DNS resolver outage | NOT TESTED | без root: `settings put global private_dns_*` запрещён (`WRITE_SECURE_SETTINGS`), `android.settings.PRIVATE_DNS_SETTINGS` на ColorOS отсутствует; управляемого отказа резолвера нет |
| Wi-Fi → LTE | PASS | LTE→Wi-Fi и Wi-Fi→LTE: MULTI rotate, `STRICT: RouterConfig strict=true`, соединение активно |
| Дополнительные Android users | **NOT COVERED (route-level)** | VPN-правила `uidrange 0-99999` покрывают только user 0; user 10 (1010997) и user 999 вне диапазона. Strict не является межпользовательским |
| Lockdown ON | PASS | системный экран: Always-on + Lockdown; служба отдала ВКЛ, UI «Lockdown ВКЛ» |
| VPN остановлен + Lockdown ON | PASS | force-stop Nova при Lockdown ON: Firefox `NS_ERROR_OFFLINE` при живом LTE; настройки возвращены OFF |
| Lockdown OFF | PASS | служба отдаёт `isLockdownEnabled=false`; UI «ВЫКЛ» |
| VPN остановлен | PASS | Strict не активен, Lockdown UNKNOWN (Settings.Secure закрыт) |
| Tethering | **NOT COVERED (platform)** | запрос `com.google.android.networkstack.tethering` идёт с `NOT_VPN`; клиентский runtime не проверялся; защита не заявляется |
| Исключение Nova UID | PASS | единственный UID вне VPN — 10997; пользовательские приложения не исключены |

- Kotlin: **706 тестов, 0 failures**; release + R8 — PASS.
- Версия `1.34.11/175`; Strict на устройстве выключен, MULTI работает;
  Selective Direct не начат. P0/P1, Go-core, tun2proxy, UDP dispatcher и
  P-E.2A не менялись.

## 2026-11-14 — P-E.2B.3.1: аудит исключения Nova

- Полный аудит: `docs/fork/PE2B31-NOVA-EXCLUSION-AUDIT.md`.
- `addDisallowedApplication(packageName)` исключает весь UID 10997, а
  `protect()` действует только на конкретный fd в вызывающем процессе.
- Без исключения пакета сейчас ломаются:
  - `opera-proxy` — child-процесс, внешние регистрация/API/relay, protect нет;
  - `nova-router` — child-процесс, `ws`-слот без `via` и `direct` (в Strict
    direct отсутствует, `ws` остаётся);
  - `tun2proxy` — in-process Rust, protect-callback в API нет;
  - часть Java-сокетов (`AppUpdateManager`, `FeedHttp`, Opera bootstrap
    `DnsProbe` с `protector=null`) — без protect; это не обход, но путь
    меняется.
- Точечно защищены: Go-core SmartBind (результат только логируется), DNS DoH
  (fail-closed), MASQUE (bind к underlay), Xray (protect есть, результат не
  проверяется).
- Вывод: убрать исключение нельзя без отдельного этапа (protect-callback в
  router/opera/tun2proxy или отдельный пакет/UID). Рекомендация — оставить
  исключение и честно указывать область в UI.
- Код не менялся; Strict на устройстве выключен, MULTI работает.

## 2026-11-14 — P-E.2B.3: Android Strict Full Tunnel

- `startSmartConnection`: Strict — явная проверка `getRoutePolicyMode() ==
  STRICT_FULL`; AUTO/MULTI разрешены, ручной WARP/OPERA/VLESS/DNS/TOR
  отклоняется до создания TUN. `policyMulti = strict || …`.
- `onStartCommand`: центральный запрет для Strict — explicit WARP-параметры,
  `ACTION_START_OPERA_ONLY`, `WARP_BOOTSTRAP` и ручной регион не запускаются.
- `restorePersistedSession`: Strict не восстанавливает warp/opera-сессию,
  только `multi`.
- `buildMultiRouterConfig`: `strict=true` в JSON; `udpSlots` без direct;
  лог `STRICT: RouterConfig strict=true direct=false`.
- TUN (MULTI): `applyOperaSplitTunnelPolicy` в Strict не применяет
  `DirectAppsPolicy`/split-списки; `applyPlainDnsIntercept(empty)` и
  `setDomainBypassPolicy(false)`; DNS/domain `excludeRoute` не добавляются;
  `applyPrivateDnsBypass` выключен; LAN-bypass выключен.
- `protect()` сохранён для underlay-сокетов.
- UI: карточка «Политика выхода» — FULL TUNNEL / STRICT выбран, но не активен /
  LIMITED / обычный; Strict активен только при CONNECTED+MULTI и обновлении
  состояния после смены режима (`policy_mode_set_at`). Подтверждение с
  предупреждением о банках/госуслугах/операторах; выключение возвращает
  прежний режим.
- Lockdown в UI: служба снимает `VpnService.isAlwaysOn()`/
  `isLockdownEnabled()` (API 29+) и пишет в `service_state.json`; UI читает
  оттуда, при остановленном сервисе — `Settings.Secure`/UNKNOWN.

| Проверка | Результат |
|---|---|
| Strict + все приложения | **PASS**: `ip rule` — uidrange 0-10996, 10998-20996, 20998-99999 → table 1224; исключён только UID 10997 (Nova) |
| IPv4 default | **PASS**: `default dev tun0`; LAN-вычетов нет (`10.1.11.0/24 dev tun0` только адрес TUN) |
| Банк/госуслуги/оператор | **PASS (route-level)**: UID 10308 (Sber), 10251 (Tinkoff), 10794 (Alfa), 10863 (Госуслуги) в диапазоне VPN |
| Приложение из DirectAppsPolicy | **PASS (route-level)**: DirectAppsPolicy не вызывается в Strict |
| Новое приложение после включения | **PASS**: `ipv6diag` UID 10324 установлен после Strict, попал в диапазон 0-10996 без обновления списков |
| DNS failure | **NOT TESTED** (нет управляемого отказа) |
| Потеря primary / всех слотов | **NOT TESTED** (нет root/управляемого отказа) |
| AUTO + Strict | **PASS**: CONNECTED backend=MULTI |
| Ручной WARP | **PASS**: `STRICT: запуск ru отклонён — Strict Full Tunnel работает только в MULTI`, туннель не поднят |
| UI после отказа backend | **PARTIAL**: причина «Strict: доступен только MULTI» показана, но главный статус может остаться «ПОДКЛЮЧЕНИЕ» до перерисовки экрана |
| Отказ MULTI | **NOT TESTED** |
| Wi-Fi → LTE | **NOT TESTED** (Wi-Fi выключен на устройстве) |
| IPv4 regression | **PASS**: Firefox `104.28.198.244` |
| IPv6 | **PASS (fail-closed)**: table 1224 `unreachable default`; `ipv6.icanhazip.com` не открывается |
| Underlay protect() | **PASS (косвенно)**: MULTI/tun2proxy работают через защищённые underlay-сокеты |
| VPN остановлен + Lockdown | **PASS (UI)**: работающий Strict — «Lockdown ВЫКЛ»; после остановки — UNKNOWN, Strict не показывается активным |
| Lockdown ON | **NOT TESTED**: нет `WRITE_SECURE_SETTINGS`, включается только системным экраном |
| Tethering | **NOT TESTED**; защита не заявляется |

- Отклонение от буквы задания: в Strict остаётся один
  `addDisallowedApplication(packageName)` — служебный пакет Nova. Без него
  router/tun2proxy/Xray-сокеты попадают в собственный TUN и MULTI
  зацикливается; `protect()` не покрывает сокеты дочерних процессов.
  Пользовательские приложения не исключаются (доказано диапазонами UID).
- Runtime Strict: включался на устройстве; **в пользовательскую поставку не
  включается** до отдельного решения по приёмке.
- Версия `1.34.11/175`. P0/P1, P-E.2A, Go-core, tun2proxy и UDP dispatcher
  не менялись.

## 2026-11-14 — P-E.2B.2: Strict RouterConfig + Go-валидатор

- `RouterConfig.build` пишет `"strict":true` только для Strict-конфигурации;
  прежний JSON не изменился.
- `RouterConfigFactory` в `strict`:
  - слот `direct` не создаётся;
  - правила `exclude` и `local-tld` не генерируются;
  - правила `spotify` и `china-ai` не генерируются (трафик уходит в defaultOrder);
  - любой `RouteClass.DIRECT` в overrides → `IllegalArgumentException`;
  - `killSwitch` для strict считается включённым, поэтому `direct` не попадает
    ни в `defaultOrder`, ни в хвост `order()`.
- `nova-router.Config.Strict`: валидатор независимо отклоняет:
  - слот с именем `direct` или типом `direct`;
  - `direct` в `defaultOrder`/`defaultUdpOrder`;
  - `direct` в `tcpOrder`/`udpOrder`/legacy `order` любого правила;
  - правила `exclude` и `local-tld`.
  Strict-проверка идёт **до** проверки ссылок на слоты, поэтому direct в
  порядке отклоняется как strict-нарушение, даже если слота direct нет.
  Валидатор ничего не исправляет и не удаляет direct.
- `LoadConfig` при ошибке возвращает ошибку; автоматического отката к старой
  или обычной конфигурации нет. Подключение к службе — B.2.3, runtime Strict
  не включался.

| Сценарий | Результат |
|---|---|
| STRICT_FULL без DIRECT | PASS (Kotlin + Go fixture) |
| DIRECT slot | REJECT (Go) |
| DIRECT-тип под другим именем | REJECT (Go) |
| DIRECT в defaultOrder | REJECT (Go) |
| DIRECT в defaultUdpOrder | REJECT (Go) |
| DIRECT в tcpOrder | REJECT (Go) |
| DIRECT в udpOrder | REJECT (Go) |
| DIRECT в legacy order | REJECT (Go) |
| DIRECT в правиле при чистом defaultOrder | REJECT (Go) |
| exclude / local-tld | REJECT (Go) + не генерируются (Kotlin) |
| RouteClass.DIRECT override | REJECT (Kotlin, исключение) |
| Старая конфигурация без режима | PASS (direct разрешён, как раньше) |
| LIMITED | PASS (direct-правила сохраняются, как раньше) |
| Ошибка Strict-валидации → fallback | Нет fallback (ошибка `LoadConfig`) |
| Фактический JSON Kotlin → Go | PASS: `nova-router/testdata/strict_config.json` собран фабрикой (`NOVA_WRITE_STRICT_FIXTURE=1`) и принят `LoadConfig` |

- Kotlin: **706 тестов, 0 failures**; `RouterConfigFactoryTest` — 35.
- Go: `go test ./...` — PASS; `go test -race -count=1 ./...` — PASS.
- Release `assembleGithubRelease` + R8 — PASS.
- Runtime Strict — **NOT TESTED** (в службе не включался, это B.2.3).

Проверка вызовов `isKillSwitch()` после перехода на enum:

| Место | Поведение |
|---|---|
| `RoutingStore` (источник) | `mode != OFF`; для LIMITED — как старый `true` |
| `NovaVpnService:6110` (AUTO-требования) | LIMITED требует MULTI, как раньше |
| `NovaVpnService:11292` (RouterConfigFactory) | LIMITED убирает direct-хвост, как раньше |
| `MainActivity:6867` (dataPlaneHint) | предупреждение о backend — как раньше |
| `RoutesActivity:43/58` | состояние тумблера и запись LIMITED/OFF |
| `ClientData.isKillSwitch`/`setKillSwitch` | мёртвый код, ключ `routing_killswitch`, никем не вызывается; на новую модель не влияет |

`SELECTIVE` нигде не выставляется, поэтому его `isKillSwitch=true` в runtime недостижим.

## 2026-11-14 — P-E.2B.1 модель режимов (B.2.1)

- Добавлен `RoutePolicyMode`: `OFF`, `LIMITED`, `STRICT_FULL`, `SELECTIVE`
  (Selective — только резерв, функциональности нет).
- `RoutingStore`: ключ `policy_mode`, ленивое чтение, миграция старого
  `killswitch`; запись миграции — только из UI-процесса
  (`/proc/self/cmdline`), чтобы не затирать prefs из `:vpn`.
- Миграция: старый `killswitch=true` → **LIMITED** (не Strict), `false` →
  `OFF`; явно сохранённый режим сильнее boolean; неизвестное значение →
  fallback к старому boolean.
- `setKillSwitch(true/false)` совместим: LIMITED/OFF, зеркало `killswitch`
  пишется для отката версии. `isKillSwitch()` = `mode != OFF`.
- UI Strict не добавлен; `STRICT_FULL` нигде не выставляется.

| Проверка | Результат |
|---|---|
| Unit `RoutePolicyModeTest` | PASS (9 тестов: legacy true→LIMITED, false→OFF, stored wins, unknown fallback, case/trim) |
| Полный unit-набор | PASS: 702 теста, 0 failures, 0 errors (было 693) |
| Release `assembleGithubRelease` + R8 | PASS |
| Device: старый `killswitch=true` → режим | PASS: `POLICY: старый killswitch мигрирован в режим limited`; тумблер остался включён |
| Device: `setKillSwitch(false)` | PASS: тумблер выключен, режим OFF |
| Device: MULTI после миграции | PASS: `CONNECTING → CONNECTED (backend=MULTI)`, egress `92.36.7.***` |
| Профиль/данные после `install -r` | PASS: настройки и списки сохранились |
| `STRICT_FULL` авто-включение | NOT PRESENT (ни одного пути) |

- Ограничение: устройство проверялось на release-сборке без root; запись
  `policy_mode` подтверждена логом миграции, файл prefs напрямую не читался.

## 2026-11-14 — P-E.2A: controlled WARP IPv6 counters (диагностическая сборка)

> Инструментализация ставилась только в отдельную debug-AAR; после прогона
> удалена и release пересобран из чистого AAR (строки `DIAG-TUN`/`DIAG-FLOW`
> в установленной `libgojni.so` отсутствуют).

Фактический backend WARP — WireGuard-подобный (`device.NewDevice` +
`SmartBind`), runtime UAPI: `allowed_ip=0.0.0.0/0,::/0`.

Контролируемый прогон Firefox UID 10918 (table 1190/1191, `default dev tun0`),
счётчики TUN + WireGuard bind:

| Запрос | r4 | r6 | w4 | w6 | bind_tx | bind_rx |
|---|---|---|---|---|---|---|
| baseline | 809→830 | 73→76 | 548→560 | 52→54 | 851→875 | 601→615 |
| IPv6 #1 icanhazip | — | 360→584 | — | 288→506 | 3550→3885 | 2778→3092 |
| IPv6 #2 v6.ident.me | — | 591→691 | — | 510→596 | 3973→4619 | 3169→3673 |
| IPv6 #3 icanhazip | — | 698→808 | — | 600→698 | 4666→4839 | 3708→3858 |
| IPv4 regression | 4228→4339 | — | 3162→3262 | — | 4851→5046 | 3862→4035 |

- `DIAG-FLOW`: `READ6 2606:4700:110:812c:...:ephem -> 2606:4700::6810:b8f1:443`,
  ответ `WRITE6 2606:4700::6810:b8f1:443 -> ... len=1280/846/600`, т.е. полный
  обмен TLS-данными через tun0.
- Firefox отрисовал IPv6-адрес: `2a09:bac5:5157:2878::408:6f`, после
  переподключения — `2a09:bac5:5157:26fa::3e2:36`. RDAP: `2a09:bac5::/32` =
  **CLOUDFLAREWARP**. Глобального IPv6 вне tun0 у устройства нет.
- Чистая release-сборка (без счётчиков) повторно: `ipv6.icanhazip.com` →
  `2a09:bac5:5157:26fa::3e2:36`, `v6.ident.me` → тот же адрес,
  IPv4 → `104.28.198.244`. Остаточных `DIAG-*` логов нет.

| Участок | Результат |
|---|---|
| DNS AAAA для Firefox | PASS |
| UID / allow-list / table / default dev tun0 | PASS |
| IPv6 TCP/443 в TUN Read | **PASS** (per-flow log) |
| Go-core routing IPv6 | PASS (пакеты ушли в bind TX, DROP не наблюдался) |
| WARP transport TX | PASS (bind_tx растёт синхронно) |
| WARP transport RX | PASS (bind_rx растёт) |
| TUN Write IPv6 | PASS (WRITE6 обратно в приложение) |
| Приложение получает IPv6-ответ | **PASS** (страница отрисовала WARP IPv6) |
| WARP selected-app IPv6 | **PASS** |
| IPv4 regression | PASS (`104.28.198.244`) |
| Физическая IPv6-утечка | не наблюдалась (глобального IPv6 вне tun0 нет) |

- Ранний `FAIL (connectivity)` (Firefox error 131) **не воспроизвёлся**: 6/6
  IPv6-запросов PASS, в т.ч. после полного reconnect. Вероятная причина —
  временная нестабильность WARP-сессии (по времени совпала с
  `Retrying handshake because we stopped hearing back after 15 seconds`);
  доказанной первопричиной handshake timeout не считается. Дефект Go-core
  не подтверждён.
- Счётчики агрегатные (UID на TUN недоступен); привязка к тесту — по
  per-flow destination/source и TCP/443 + отрисованному IPv6-адресу.
- Возврат в MULTI: UID 10155/10918 → table 1193, `unreachable default` —
  route-level DROP подтверждён повторно; runtime IPv6 для MULTI — NOT TESTED
  (диагностическое приложение не выбирается в split-list).
- Production-код не менялся: правки `tunnel.go`/`smart_bind.go` откатаны,
  `diag_tun.go` удалён; в диффе остались только ранее принятые P0/P1
  изменения.

## 2026-11-14 — P2: внешний UDP есть, non-RU egress нет

- **P2.1 штатный PROTON (Wi-Fi и LTE): FAIL.** Оба прогона перебирали
  импортированные Proton-конфиги (`warp-awg-exact`, 100 профилей), все
  попытки закончились `success=false`, причина `no_traffic`; сессия
  останавливалась. На Wi-Fi при этом штатный MULTI/WARP поднимался и TCP
  работал.

## 2026-11-14 — P-E.2A: WARP IPv6 diagnostic

- Корректный AAAA-only endpoint `ipv6.icanhazip.com` (TLS valid, A нет);
  Firefox UID 10918 в allow-list, `ip -6 rule` → table 1182,
  `default dev tun0`.
- IPv4 через Firefox/WARP: `104.28.198.244` — PASS.
- IPv6 HTTPS: Firefox error 131, ответа и `client_ip_seen_by_server` нет —
  **FAIL (connectivity)** для selected-app.
- MULTI UID 10155: table 1175 `unreachable default` — route-level DROP.
- Предыдущий literal `2606:4700:4700::1111` — DNS-резолвер Cloudflare, не
  HTTP endpoint; его результат NOT TESTED и в классификацию не входит.

| Участок | Результат |
|---|---|
| DNS AAAA для Firefox | PASS (endpoint AAAA-only) |
| UID/allow-list/table | PASS |
| IPv4 через WARP | PASS |
| IPv6 TCP/443 в TUN | NOT TESTED (нет packet capture) |
| Go-core Read IPv6 | NOT TESTED (нет счётчиков/логов) |
| WARP transport IPv6 | NOT TESTED |
| Return path в TUN | NOT TESTED |
| WARP selected-app IPv6 | **FAIL (connectivity)** |
| Физическая IPv6-утечка | не наблюдалась; packet-level NOT TESTED |

- Наиболее ранняя подтверждённая точка: selected-app IPv6-соединение не
  устанавливается после подтверждённого route/table; локализовать TUN vs
  Go-core vs transport без packet-level/счётчиков нельзя.
- Предложение для следующего шага (без внедрения): диагностические счётчики
  `ipver=6` на `AndroidTUN.Read/Write`, runtime `allowed_ip` WireGuard и
  pcap/tcpdump на корне; либо controlled endpoint с серверным логом.
- Production-код, `NovaVpnService`, `nova-router`, `tun2proxy`, Go-core не
  менялись.

## 2026-11-14 — P-E.1 baseline: IPv6 route-level fail-closed

- Снят `docs/fork/PE1-BASELINE.md`: LTE/Wi-Fi без глобального IPv6
  (`NO BASELINE`); Wi-Fi IPv4 egress `194.34.99.100`, LTE `92.36.1.97`.
- MULTI: `tun0` только `fe80::/64`; для выбранных VPN UID действует
  `lookup 1170`, где `unreachable default` — IPv6 завершается DROP, а не
  уходит физически. Это route-level fail-closed, не packet-level PASS.
- WARP: `tun0` имеет IPv6 `2606:4700:110:.../128` и `default` в table 1171,
  но фактическая передача IPv6 не подтверждена (`NOT TESTED`).
- SOCKS5 IPv4 egress `104.28.255.123`; SOCKS5 IPv6 — connect fail.
- P-E.2A diff для MULTI/OPERA/VLESS **не требуется** по baseline; добавление
  `::/0` приостановлено до selected-app runtime на сети с глобальным IPv6.
- KILL SWITCH: P-E.2B, не начат.
- Root/tcpdump отсутствуют — packet-level ограничение зафиксировано.

## 2026-11-14 — P-D: AUTO capability-aware и data plane UI

- `RouterPolicyRequirements` — чистая функция: `requiresMulti` при smart routing,
  любом service override != `auto` или kill switch; всегда генерируемые
  `defaultOrder`/`exclude`/`local-tld` не считаются требованием MULTI.
- `startSmartConnection`: AUTO + requirements → MULTI; при отказе MULTI —
  retry/stop, без перехода на WARP/MASQUE/OPERA. Ручные режимы не подменяются
  (`policyMulti` только для `auto`).
- UI: `dataPlaneHint` показывает `MULTI: активны правила router` /
  `Go-core: правила router не применяются` / `Один транспорт: ...`;
  при kill switch вне MULTI — предупреждение, что Kill Switch не действует.
- `RoutesActivity`: foreign-default переключатель отключён и помечен
  «пока не подключено» (ключ не участвует в RouterConfig).
- Тесты: 693 Kotlin, 0 failures (+5 RouterPolicyRequirements).
- Device AUTO без router-requirements: `backend=WARP`, UI hint
  «Go-core: правила router не применяются»; IP `104.28.198.***`, RU.
  AUTO с активным override на устройстве — NOT VERIFIED (unit-тест).

## 2026-11-14 — P-C: DNS resolver/egress separation и ограничения

- **P-C.1 аудит:** MULTI использует virtual DNS tun2proxy; `nova-router`
  видит для UDP `ATYP=IPv4` (QUIC), для TCP — домен/SNI. UDP/53 в router не
  доходит. `LocalDnsProxyManager` не запускается, `DnsResponseCache` —
  только внутри него. Go-core (WARP/MASQUE) имеет отдельный DNS intercept.
- **P-C.2.1:** `dnsai` удалён из egress-слотов; Go `Config.validate()` теперь
  отклоняет любой слот `type=dnsai`. DNS-AI остаётся в resolver-слое
  (`PriorityDns`), не как транспорт.
- **P-C.2.2 — BLOCKED BY TUN2PROXY:** `Tun2proxyDns_Virtual` отвечает
  синтетическим адресом; в `tun2proxy.h` нет callback с реальными A/AAAA/
  CNAME. IP→domain mapping по синтетике/последнему домену запрещён.
- **P-C.3A:** вариант A — TCP по домену/SNI; UDP только IP/CIDR +
  `defaultUdpOrder`; AI UDP/443 `allowQuic=false`; AI без verified non-RU —
  DROP. Evристики IP→domain не вводились.
- **P-C.4 факты:**
  - Chrome/YouTube TCP идут по домену (`example.com:80 → primary`,
    `youtube.com:443`), UDP QUIC — ATYP=IPv4, 443 DROP.
  - DNS/53 в router не наблюдался; DoT/853 виден как TCP на IP
    (`111.88.96.53:853 → primary`), QNAME внутри TLS недоступен.
  - `ping youtube.com` — это системный netd resolver/Private DNS
    (`dns.geohide.ru`), не virtual DNS приложения; `youtu.be` и wikipedia
    резолвятся, youtube.com — NXDOMAIN/unknown host. Это не `udpOrder` и не
    router; app-путь YouTube TCP работает.
  - `LocalDnsProxyManager` в MULTI не активен; его TTL-кэш не считается
    проверенным.
- **Ограничения:** QNAME для UDP недоступен; DoH/DoT только endpoint/SNI;
  AAAA/DNS over IPv6 не покрыты; при недоступном resolver — fail-closed для
  AI, без direct fallback.
- **Тесты:** Kotlin 688/0; Go `nova-router` ok/race; `TestDNSAISlotIsRejectedAsEgress`,
  `TestUDPOrderIndependentFromTCP`, 443 allow/drop тесты — ok.

## 2026-11-14 — P-B.2: allowQuic на уровне правила

- `RuleSpec.allowQuic` (по умолчанию false) и Go `Rule.AllowQUIC`.
- UDP/443 разрешается только при `allowQuic=true` **и** непустом `udpOrder`
  **и** UDP-capable первом слоте; иначе DROP.
- `allowQuic` не выбирает транспорт и не ослабляет `SupportsUDP`/egress.
- Все текущие правила `allowQuic=false`; поведение UDP/443 не изменилось.
- Тесты: drop без разрешения, dispatch с разрешением; Kotlin 688/0.

## 2026-11-14 — P-B.1: tcpOrder/udpOrder

- `RuleSpec` пишет `tcpOrder`/`udpOrder`; добавлен `defaultUdpOrder`.
- Go `UDPOrderFor` различает «правило не найдено» (default) и «правило
  найдено, udpOrder пуст» (DROP).
- UDP dispatcher использует только `udpOrder`; TCP-семантика не изменена.
- UDP-порядки: youtube/ru/default → primary (если primary UDP-capable),
  telegram → awg (если awg UDP-capable), direct-правила → direct, AI/apis/
  gemini-support/eu → [].
- Тесты: независимые TCP/UDP порядки, Opera в TCP не влияет на UDP;
  Go ok/race/count20.

## 2026-11-14 — P-A.2.1: egress eligibility hardening

- `EgressEligibility`: страна ровно 2 буквы, не RU/спецкод; IP публичный
  (не private/loopback/link-local/multicast/CGNAT).
- `multiEgressGeneration`/port guard: старый probe не меняет новый сеанс.
- Тесты: NL/DE true; RU/unknown/T1/XX/EU false; private/loopback/CGNAT/
  invalid false.


- **P2.1 штатный PROTON (Wi-Fi и LTE): FAIL.** Оба прогона перебирали
  импортированные Proton-конфиги (`warp-awg-exact`, 100 профилей), все
  попытки закончились `success=false`, причина `no_traffic`; сессия
  останавливалась. На Wi-Fi при этом штатный MULTI/WARP поднимался и TCP
  работал.
- **P2.2 внешний UDP (MULTI/WARP, Wi-Fi) — PASS:**
  - `EXTERNAL_UDP_DNS PASS len=61 rcode=0`: DNS `1.1.1.1:53` через router →
    WARP → ответ;
  - `EXTERNAL_UDP_STUN PASS mapped=104.28.230.248:40314`: STUN Binding
    Request через router → WARP; внешний сервер видит `104.28.230.248`.
  - TCP-egress через тот же router: `104.28.230.248`; физический IP Wi-Fi:
    `194.34.98.234`. UDP и TCP выходят одним WARP-адресом, direct-утечки нет.
- **P2.3 non-RU egress — BLOCKED.** `104.28.230.248` по `ip-api.com`:
  `country=RU, city=Moscow, org=Cloudflare WARP`. Это не non-RU выход,
  поэтому для Google AI/Proton NL критерий не выполнен.
- Вывод: UDP-транспорт и диспетчер доказаны на внешнем WARP; остаётся
  получить рабочий non-RU endpoint (Proton NL / VPS). На проверенных Wi-Fi
  и LTE маршрутах WARP дал RU egress; это не утверждение о поведении WARP
  во всех сетях, а результат этих двух прогонов.
- **P2.4 WARP LTE (Tele2):** `EXTERNAL_UDP_DNS PASS` (1.1.1.1;53),
  STUN к `74.125.250.129:19302` — timeout (сервер недоступен на этом
  маршруте). TCP-egress через router: `104.28.198.247`; `ip-api.com` —
  `RU, Moscow, Cloudflare WARP`. На проверенном LTE-маршруте WARP также
  дал RU egress, non-RU не получен. DNS-ответ доказывает внешний UDP и на
  LTE.
- Временная activity внешнего UDP удалена; release пересобран без неё.

## 2026-11-14 — P1.3 Android: два домена, TCP-only drop, AWG-down drop

- Локальный peer с двумя адресами `10.77.0.1`/`10.77.0.2`, DNS
  `first.example → 10.77.0.1`, `second.example → 10.77.0.2`, echo на обоих
  `:9000`; клиент — `AwgSocksBridge` в `:vpn` (`10.77.0.3`, peer
  `127.0.0.1:51820`), UDP-клиент — временная activity через
  `nova-router 127.0.0.1:1373`.
- `ROUTER_UDP_TWO_DOMAINS PASS`: оба домена на порту 9000 одновременно с
  одного клиента, nonce вернулись со своими IP (`10.77.0.1` и `10.77.0.2`);
  в логах `[UDP-TEST-DNS] query=first.example` и `query=second.example`.
- `ROUTER_UDP_TCP_ONLY DROP PASS`: `gemini.google.com:9000` (первый слот
  правила — не UDP-транспорт) ответа не получил, в тестовый DNS запрос не
  ушёл.
- `ROUTER_UDP_NO_AWG DROP PASS`: после остановки локального peer повторный
  `first.example:9000` ответа не получил; fallback на другой слот/direct не
  произошёл.
- Тестовая activity/peer/конфиг после прогона удалены; release пересобран
  без них: в dex нет `UdpRouterTestActivity`, `libnova-router.so` в APK
  совпадает с `jniLibs`.
- Финальная регрессия: Telegram 200; YouTube — первый запрос ушёл в `spoof`
  и получил TLS-таймаут, второй — 200 (~1.0 с, `primary`). Это отдельный
  дефект TCP-fallback, не UDP-диспетчер. Kotlin — 679 тестов, 0 failures;
  `nova-router` и `nova-core/engine` — ok.

## 2026-11-13 — P1.1/P1.2 UDP capabilities и dispatcher в nova-router

- `RouterConfig.SlotSpec` получил явное поле `udp`; JSON слота содержит
  `"udp":true` только когда capability задана.
- `RouterConfigFactory.build` принимает `udpSlots`; capability задаётся при
  создании транспортного слота и **не выводится из номера порта**:
  `multiPrimaryKind`/`multiCfProxyKind` фиксируют фактический bridge
  (`awg`/`masque`), `multiAwgPort` — отдельный AWG-слот. Тест на смену
  listen-порта AWG (`19378`) подтверждает сохранение `udp:true`.
- Go `Slot` получил `UDP bool`; `Config.SupportsUDP(slot)` — чистая функция,
  неизвестный слот false, без сокетов и изменения состояния.
- **P1.2:** `serveUDPAssociate` больше не делает прямой `net.DialUDP` в обход
  политики. Каждый датаграм проходит `routeOrderFor`; берётся **первый слот
  правила**. Если у него нет `udp:true` — drop, без перебора и direct
  fallback. Для `socks5` открывается SOCKS5 UDP ASSOCIATE к слоту (AWG
  `127.0.0.1:1378`), hostname передаётся как `ATYP=DOMAIN`; ответы
  фильтруются по отправленным адресам. `UDP/443` отбрасывается.
- **Найден и исправлен баг фильтра ответов:** AWG-мост отвечает на
  `ATYP=DOMAIN` уже разрешённым IP. Вместо эвристики по порту теперь для
  каждого домена создаётся **отдельная upstream-ассоциация** (`slot + domain`),
  поэтому ответы двух имён с одинаковым портом не смешиваются; для
  IP-назначений ассоциация остаётся одна на слот с точной проверкой
  `host:port`. Лимит — 32 ассоциации на клиента.
- **Найден и исправлен race при параллельных датаграмах:** `payload` был
  срезом переиспользуемого буфера `ReadFromUDP`, и следующий датаграм
  перезаписывал данные до обработки в горутине. Теперь payload копируется
  перед `go forward`; тест с двумя одновременными DOMAIN-запросами
  (`first.example`/`second.example`, один порт) проходит, `-race` — ok.
- Go-тесты `nova-router`: ok, `-race` — ok, `-count=20` — ok. Проверены:
  direct UDP, маршрут через первый слот с сохранением домена, drop для
  TCP-only первого слота, drop без capability, закрытие upstream при
  закрытии клиентского control.
- Kotlin unit-тесты: ok (28 тестов `RouterConfigFactoryTest`, включая
  UDP-capability и смену порта).
- `libnova-router.so` пересобран для arm64 и armv7; armv7 — cgo/NDK
  `armv7a-linux-androideabi24-clang`, оба ELF `DYN` (PIE).
- TCP-регрессия на arm64: YouTube 200, Telegram 200 через роутер.
- Ограничение: строгий выбор первого слота означает, что UDP через
  TCP-only слот (например, `secondary`/Opera) отбрасывается по контракту
  fail-closed; DNS, который Android отправит по UDP/53 через такой слот,
  тоже будет отброшен. Это ожидаемо до появления UDP-capable слота первым
  в правиле (P2/AWG).
- **P1.2 Android end-to-end с контролируемым локальным WireGuard-peer —
  PASS:**
  - сервер `10.77.0.1:9000`/`10.77.0.1:53` в основном процессе, клиент —
    `AwgSocksBridge` в `:vpn` с тестовым конфигом `127.0.0.1:51820`;
  - клиент — временная activity, SOCKS5 UDP ASSOCIATE к `nova-router`
    `127.0.0.1:1373`;
  - `ROUTER_UDP_DOMAIN PASS`: `youtube.com:9000` → правило youtube →
    `primary` (AWG, `udp:true`) → `ATYP=DOMAIN` → внутренний DNS
    `[UDP-TEST-DNS] query=youtube.com` → inner `10.77.0.1:9000`, payload 13 Б;
  - `ROUTER_UDP_DNS PASS len=45 rcode=0`: IP-датаграм `10.77.0.1:53` через
    router → AWG → ответ;
  - это доказывает выбор слота по политике, сохранение домена, upstream
    SOCKS5 UDP ASSOCIATE и возврат ответа через router;
  - тестовая activity, локальный peer и тестовый конфиг удалены после
    прогона; release пересобран без них.
- Ограничение внешнего egress: Proton/WARP endpoint по-прежнему не всегда
  завершает handshake (P2), поэтому реальный внешний UDP может быть
  недоступен; на контролируемом peer диспетчер работает.
- **Финальная чистая сборка:** тестовая activity/peer/конфиг удалены, в dex
  нет `UdpRouterTestActivity`; `libnova-router.so` в APK совпадает с
  `jniLibs`. TCP-регрессия: Telegram 200; YouTube 200 три раза подряд.
  Первый запрос ушёл в `spoof` и получил TLS-таймаут — это существующий
  TCP-fallback транспорт, повторные запросы прошли через `primary` (200,
  ~1.2 с). Kotlin: 679 тестов, 0 failures. `nova-router`: ok, `-race` — ok,
  `-count=20` — ok. `nova-core/engine`: ok; `cmd/pktcap` не собирается на
  Windows (`syscall.AF_PACKET`) — Linux-only, не регрессия.

## 2026-11-13 — P0 UDP ASSOCIATE на Android: FAIL по транспорту AWG

```text
APK: OBNOVA_1.34.11 (AAR с awg_socks_udp, Kotlin test activity)
Режим: MULTI, PRIMARY = AWG SOCKS 127.0.0.1:1378
Команда: adb shell am start -n com.brent.nova/com.example.nova.UdpAssociateTestActivity --ei cycles 1
```

- **Клиент дошёл до relay:** `SOCKS_TCP_CONNECTED 127.0.0.1:1378`,
  `SOCKS_GREETING 05 00`, `SOCKS_BND 127.0.0.1:37081`.
- **UDP-запрос ушёл:** `UDP_SEND 1.1.1.1:53 bnd=127.0.0.1:37081`.
- **Ответа нет:** `FATAL SocketTimeoutException: Poll timed out` через 5 с.
- **Причина выше UDP-кода:** `GoLog [AWG-SLOT] peer … Handshake did not
  complete after 5 seconds, retrying` непрерывно; router TCP к 1378 —
  `read tcp 127.0.0.1:…->127.0.0.1:1378: i/o timeout`. Bundled
  `warp_awg_seed.conf` не поднимает data-plane на этой сети.
- **`:udp-test`:** activity с `android:process=":udp-test"` не ставится на этом
  ROM (`INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION: ParsedActivityImpl cannot be
  cast to String`); без `android:process` APK ставится, клиент работает в
  основном процессе (всё равно отдельно от `:vpn`). Для точного `:udp-test`
  нужен service/другой компонент.
- **P0 verdict: FAIL (blocked by AWG handshake).** UDP ASSOCIATE/парсер/BND
  работают; data-plane AWG мёртв. Следующий шаг — рабочий WARP/Proton config в
  `AwgSocksBridge`, затем повторить single/DOMAIN/lifecycle/100/fail-closed.
- **Перебор конфигов:** bundled WARP seed, PC Proton NL-FREE-241 (185.183.34.41:443),
  NL-FREE#103 (212.8.253.154:443/1194), свежий generated NL-FREE#126
  (185.107.56.69:88), свежий private key + NL-FREE#103 — везде
  `Handshake did not complete after 5 seconds`. PC-диагностика `StartAwgSocks`
  с тем же свежим конфигом тоже даёт `read udp 10.2.0.2:…: i/o timeout`.
- **Вывод:** это не Android-специфичный и не UDP-ASSOCIATE дефект; на текущей
  сети/конфигах не поднимается AWG data-plane. Нужен точный рабочий
  Proton-профиль (как в сеансе 212.8.253.155), VPS или другая сеть; затем
  повторить тест. Исходный `warp_awg_seed.conf` восстановлен.
- **Аудит движков:** полный PROTON mode (`StartWireGuard`) и `StartAwgSocks`
  используют один и тот же `configToUAPI` + `NewSmartBind` +
  `wgdevice.NewDevice`; разница — TUN: `CreateAndroidTUN` vs
  `wgnetstack.CreateNetTUN`. `NovaVpnService` ставит `setSocketProtector`;
  `SmartBind.Open` вызывает `GlobalProtector` для IPv4/IPv6, но результат
  `protect()` не логируется. PC `StartAwgSocks` с тем же свежим конфигом тоже
  не завершает handshake; `pktmon` без прав администратора недоступен.
  Повторный PROTON mode сейчас тоже не подключается и откатывается в MULTI/RU —
  значит, блокер как минимум сеть/endpoint, а не только bridge.
- **SmartBind/protect (добавлено логирование `[AWG-BIND]`):**
  - Android: IPv4 `protect_called=true`, `protect_result=true`,
    `local=0.0.0.0:37541`; IPv6 `protect_result=true`,
    `local=[::]:49777`; `first_send_endpoint=8.6.112.6:8742`.
  - PC: `protect_called=false` (нет Android), `local=0.0.0.0:65056`,
    `first_send_endpoint=185.107.56.69:88`, затем `read udp 10.2.0.2:…:
    i/o timeout`.
  - Вывод: `SmartBind`/`protect` предварительно исправны, handshake уходит на
    endpoint, но ответа нет ни на PC, ни на Android. Нужен контролируемый
    WireGuard VPS для разделения «endpoint/ключи/сеть» и «движок bridge».
- **Контролируемый локальный WireGuard peer (PC):** `TestWireGuardLoopHandshake`
  поднимает userspace WireGuard-сервер на `127.0.0.1:51820` и `StartAwgSocks`
  с отдельной парой ключей; handshake завершился за **0.29 с**, `-race` — ok.
  Это доказывает, что движок/bind/`configToUAPI` bridge рабочие; отказ с
  Proton/WARP — внешний endpoint/сеть/ключи, а не `StartAwgSocks`.
- **P0 Android end-to-end с локальным peer — PASS:**
  - тестовый сервер `10.77.0.1:9000` (userspace WireGuard, отдельные ключи) в
    основном процессе, bridge — в `:vpn`, SOCKS5 127.0.0.1:1378;
  - `LOCAL_ECHO PASS`: outer `127.0.0.1:49019`, inner `10.77.0.1:9000`,
    payload 14 Б;
  - `LIFECYCLE PASS`: после закрытия TCP control ответа нет;
  - `STRESS DONE ok=100 failed=0 fd_before=157 fd_after=157 threads 43→43`;
  - `FAIL_CLOSED PASS`: без сервера ответа нет.
  - `DOMAIN_ECHO PASS`: `udp-test.example:9000` → `[UDP-TEST-DNS] query=udp-test.example`
    (DNS-сервер `10.77.0.1:53` внутри туннеля) → inner `10.77.0.1:9000`,
    payload 15 Б. Это доказывает `netStack.LookupContextHost` и ATYP=DOMAIN.
  - Это доказывает весь UDP ASSOCIATE-путь на Android (SOCKS5 relay →
    `netStack.DialUDP` → WireGuard handshake → echo → return), включая
    `protect_called=true`/`protect_result=true` для IPv4/IPv6.
  - Ограничение: сервер локальный (loopback), не Proton; DOMAIN и внешний
    egress ещё не проверены. Тестовый клиентский конфиг лежит в
    `assets/warp_awg_seed.conf` только на время теста; перед обычной сборкой
    вернуть baseline.

## 2026-11-13 — P0 UDP ASSOCIATE (Go, без AAR/Android)

- Добавлен `nova-core/engine/awg_socks_udp.go`: SOCKS5 UDP ASSOCIATE поверх
  `wgnetstack.Net` (host relay + `DialUDP`, DOMAIN через `LookupContextHost`,
  FRAG=0, привязка к TCP peer IP/первому UDP-порту, idle 120 с, WaitGroup,
  отмена контекста, без direct-fallback).
- `awg_socks.go`: CMD 3, снятие deadline control, `dialUDP`-хук.
- Тесты: `go test ./engine` — ok; focused `-count=100` — ok (161 с);
  `-race` full engine — ok; focused `-race -count=20` — ok (33 с) через
  MinGW-w64 gcc 16.2, `CGO_ENABLED=1`.
- **Не проверено:** AAR/APK не пересобран, реальный UDP через Proton на
  Android не запускался; `serve()` синхронно делает DNS/Dial — P1.
- Граница: `nova-router`, `SupportsUDP`, `google-ai` не менялись.

## 2026-11-13 — Gemini Android: причина найдена, рабочий транспорт Proton NL (RMX3370)

```text
APK: OBNOVA_1.34.11-arm64-v8a (AI-группы, 676 тестов)
Режим: PROTON → AWG PROTON: NL, egress 212.8.253.155 (Naaldwijk, NL)
```

- **Симптом в MULTI + Opera (US):** Gemini Android отправляет сообщение и
  зависает; в логах только `tun2proxy: Beginning #… UDP …:443`,
  `Ending … with "timed out"`, TCP к `aida`/`generativelanguage` нет. Ошибки
  сети/авторизации в logcat нет.
- **Симптом в WARP (UDP, RU):** сообщение отправляется, ответ приходит быстро —
  «Эта страна не поддерживается». Значит UDP-путь работает, но IP российский.
- **Proton NL (UDP, non-RU):** `ipinfo.io` → **212.8.253.155, NL**;
  `cloudflare-quic.com` → **«your browser used HTTP/3»** (QUIC/UDP 443 проходит);
  Gemini Android: `Hi` → **«Hey! What can I help you with today?»**.
- **ChatGPT app в том же Proton NL:** `Hi` → **«Hi! 😊 How can I help you
  today?»** — сетевой ошибки нет; предыдущий 403 был свойством IP Opera, а не
  маршрута.
- **Остальные сервисы в Proton NL:** Claude (`claude.ai/login`) — страница
  открылась; DeepSeek (`chat.deepseek.com`) — страница входа; Qwen
  (`chat.qwen.ai`) — чат; Telegram — чаты загрузились; YouTube mobile — лента и
  превью загрузились.
- **Причина:** Gemini Android для генерации использует QUIC/UDP 443 и не
  откатывается на TCP, если UDP недоступен. Opera — HTTP CONNECT, UDP не несёт;
  `nova-router` в `serveUDPAssociate` явно пропускает `port == 443`. Поэтому в
  MULTI приложение висит. WARP доказывает, что дело в IP, а не в UDP; Proton NL
  закрывает оба условия.
- **Ограничение:** это режим **PROTON**, а не MULTI. В MULTI нет non-RU
  UDP-слота: `secondary` — TCP-only, `awg`/`primary` — TCP-мост с RU-выходом,
  VLESS-профили мертвы, MASQUE не поднялся. Чтобы Gemini работал именно в MULTI,
  нужен UDP-capable non-RU слот и пересылка UDP через него; в код это не
  добавлялось, чтобы не ломать существующую логику (по указанию владельца).
- **AI-группы:** `openai` (fail-closed), `google-ai`, `anthropic`
  (fail-closed), `china-ai` (direct) собраны и покрыты тестами; в PROTON-режиме
  роутер не участвует, поэтому их runtime-проверка в MULTI остаётся открытой.
- **Тесты:** 676 тестов, 0 падений, 9 skipped; release-сборка успешна.
- **Нестабильность Proton:** после установки финального APK повторные
  подключения к Proton NL ушли в «ПОДКЛЮЧЕНИЕ... AWG Proton» на 3+ минуты;
  сеанс остановлен вручную. Рабочий узел `212.8.253.155` подтверждён в
  предыдущем сеансе, но бесплатные узлы Proton перебираются медленно и не
  гарантируют соединение с первой попытки. Для стабильности нужен свой
  VPS/AWG-узел или платный Proton.

## 2026-11-13 — non-RU egress: Opera первым слотом (RMX3370)

```text
tools/rebuild-install-clean.ps1 -Variant release
BUILD SUCCESSFUL; adb install -r → pm clear → launch
adb reverse tcp:1371 tcp:1371   # Opera US на ПК
```

- **Egress по умолчанию:** Chrome `https://ipinfo.io/json` → `77.111.246.61`,
  Ashburn, Virginia, `US`; через роутер `127.0.0.1:1373` — тот же адрес.
  До правки маршрут по умолчанию был `primary` (RU).
- **Проверка сервисов через роутер:** YouTube — **200** (0,58 с),
  telegram.org — **200** (1,94 с), gemini.google.com — **200** (2,14 с),
  chatgpt.com — **200** (2,10 с).
- **Раздельное туннелирование:** на устройстве был включён режим «Только
  выбранные» (после восстановления prefs), поэтому Chrome сначала ходил мимо
  VPN (`92.36.79.163`, RU). Переключение на «Все» + полное переподключение
  дало US-адрес. Мягкий REAPPLY сам по себе allow-list не перестраивает.
- **Приложения AI:** ChatGPT — «Ошибка сети»;
  `android.chat.openai.com` с IP Opera отвечает **403**
  `{"cf_details":"Request is not allowed. Please try again later."}`. Gemini —
  запрос уходит в QUIC/UDP 443 (`tun2proxy: Beginning #60 UDP …:443`,
  `timed out`), TCP-прокси Opera UDP не несёт. Оба блокера внешние.
- **ChatGPT-маршрут (код):** все домены OpenAI/ChatGPT, включая `chatgpt.com`,
  `ws.chatgpt.com`, `auth0.openai.com`, `workos.com` и
  `challenges.cloudflare.com`, сведены в одно правило `openai` до
  `google-ai`; порядок fail-closed `secondary → vless`, без direct/dnsai.
  Проверка после установки: `api.openai.com/v1/models` → **401** (дошло до
  OpenAI), `chatgpt.com` → **200**, `android.chat.openai.com` → **403**
  (IP Opera). Через AWG/DESYNC тот же хост — 000/403, лучше не становится.
- **Релей с Android (проверено):** с PC-ключом мост
  `OperaApiRelayBridge` стартует, но TCP до `relay.nova-app.eu:8443` с LTE
  не встаёт (`SocketTimeoutException` 8 с), Opera останавливается на каждой
  попытке. Для телефона без ПК этот путь на данной сети не работает.
- **AI-группы:** добавлены независимые правила `anthropic` (Claude,
  fail-closed `secondary → vless`) и `china-ai` (DeepSeek/Qwen,
  `direct`); списки — `assets/lists/anthropic.txt`, `china_ai.txt`. Слоты
  общие, поэтому новый AI-сервис добавляется списком и одной строкой порядка.
- **Установка AI-групп:** APK собран и установлен. На момент проверки телефон
  уже был в другом режиме (`sessionId=NovaVPN`, `tun1`), роутер не активен;
  чтобы не прерывать сеанс пользователя, группы в MULTI на устройстве не
  перепроверялись. Конфиг проверен unit-тестами (676 тестов).
- **Тесты:** Kotlin — 84 класса / 676 тестов, 0 падений, 9 skipped;
  release-сборка с `-PnovaCfProxySlot=true` успешна (environment
  `NOVA_ALLOW_UNSIGNED_RELEASE=1`, `NOVA_ALLOW_UNPUBLISHABLE_BUILD=1`).
- **Раздельное туннелирование (проверено на устройстве):** переключение
  «Только выбранные» → «Все» перезапустило VPN-сеанс; `dumpsys connectivity`
  показывает `Uids: <{0-10040 … 21002-99999}>`, Chrome снова в туннеле и
  `ipinfo.io` → US. Мягкий REAPPLY для MULTI сам завершает процесс `:vpn`
  (`force-stop tun2proxy`, `exit(-1)`), поэтому новый allow-list применяется.
- **Controlled restart (код):** `SessionReapply` относит `NovaMultiVPN`,
  `NovaDesyncVPN`, `NovaDnsVPN`, `NovaVlessVPN` к tun2proxy-сеансам; на этом
  прогоне сработал существующий путь мягкого реаплая с перезапуском процесса,
  отдельного controlled restart не потребовалось.

## 2026-11-13 — чистый install, упрощённые маршруты (RMX3370)

```text
powershell tools/rebuild-install-clean.ps1 -Variant release
gradlew clean assembleGithubRelease -PnovaCfProxySlot=true
BUILD SUCCESSFUL
adb install -r → pm clear com.brent.nova → launch
```

- **Чистый старт:** «регистрирую устройство» не висит; автогенератор WARP на
  connect отключён. MULTI поднялся на `AWG bundled seed` / bundled WARP
  bootstrap `162.159.195.9:500`, статус «АКТИВНО И РАБОТАЕТ».
- **Через роутер 127.0.0.1:1373** (`curl --socks5-hostname`):
  - `https://www.youtube.com/` → **200** (0,64 с)
  - `https://telegram.org/` → **404** (соединение есть, 0,29 с)
  - `https://gemini.google.com/` → **200** (14,9 с)
  - `https://chatgpt.com/` → **403** (6,4 с) в первом прогоне; после
    переподключения и работы DNS-AI — **200** (1,3 с). Приложение ChatGPT при
    этом всё равно показывает «Ошибка сети» (app-хосты `ab.chatgpt.com`/
    `android.chat.openai.com`), браузер работает.
- **Автообновление меню:** при запуске `MainActivity` раз в сутки в фоне
  вызывает `ProfileUpdateCoordinator.updateAll`, чтобы подписки сами
  появлялись в меню; после CONNECTED база дополняется `syncFromLegacy`.
- **Non-RU попытка:** после CONNECTED в фоне поднимается VLESS-слот
  (`startVlessForForeignEgressInBackground`) и default-маршрут получает
  `vless → primary → direct`. На этой сети все проверенные VLESS-семена не
  прошли пробу, поэтому egress остался `loc=RU` (DME).
- **Opera EU:** API `api2.sec-tunnel.com/v4/register_subscriber` — timeout до
  выбора endpoint; `server-selection fastest/first` не наступает. Вшитый
  `opera-proxy` — v1.31.0 (последний из `Alexey71/opera-proxy`).
- **Маршруты:** OpenAI app — `secondary → vless` fail-closed; Telegram —
  `awg → vless → primary`; Gemini support — `direct → primary`.
- **Тесты:** 84 класса / 673 теста, 0 падений, 9 skipped.
- **База профилей:** после CONNECTED выполняется автоперенос
  `UnifiedProfileStore.syncFromLegacy` (меню не пустое).
- **Не работает:** ChatGPT (нужен EU-egress); Telegram voice (нет UDP relay).
- Карта планов: `docs/plans/2026-11-13-android-route-recovery-and-release.md`.

## 2026-11-12 — стабилизация MULTI, живой прогон RMX3370

```text
./gradlew testGithubDebugUnitTest
BUILD SUCCESSFUL; 84 класса / 672 теста, 0 падений, 9 skipped
cd nova-router && go test ./...
ok nova-router
./gradlew assembleGithubRelease -PnovaCfProxySlot=true
BUILD SUCCESSFUL
```

- **Флаг сборки:** первый релиз без `-PnovaCfProxySlot=true` дал на устройстве
  `MULTI: DESYNC`; в логах `MASQUE-мост не поднялся`, `AWG seed не
  запустился` — это `CF_PROXY_SLOT_ENABLED=false`, а не сетевой отказ.
  С флагом: `MULTI: MASQUE 162.159.198.2:443`, `MULTI активен: router
  127.0.0.1:1373 → PRIMARY1376 / DIRECT`.
- **Проверка сервисов через роутер:** RU 302, US 302, YouTube 302,
  Telegram 200, Gemini 200; ChatGPT `api.openai.com` — HTTP 403.
- **DNS-AI:** до сброса override AI в маршрутах стоял `SECONDARY`, запросы шли
  `chatgpt.com:443 → primary`. После миграции — `→ dnsai`; в логе
  `DNS-AI chatgpt.com → [62.60.230.***]`, `DNS-AI gemini.google.com →
  [109.120.131.***, 89.169.55.***]`. С этими адресами ChatGPT — timeout,
  Gemini — TLS handshake failure. То есть слот и маршрут работают, но
  полученные DNS-AI адреса в текущей сети не отвечают; нужен разбор способа
  подключения DNS-AI (DoT/порт/профиль), а не «ещё один слот».
- **Разбор DNS-AI (совет моделей + живые замеры):** сервис — прозрачный
  SNI-splice на TCP/443: сертификат настоящий, ALPN пробрасывается, HTTP через
  узел даёт 200 там, где прямой Cloudflare — 403. `chatgpt.com` жёстко прибит к
  `62.60.230.61`, и этот узел сейчас мёртв (RST через ~19 с). У Gemini два
  адреса: `109.120.131.200` отвечает 200 за 0,2 с, `89.169.55.148` — таймаут;
  нужен failover. `192.144.59.14`/`186.246.49.127` в `endpoints.json` уже
  `retired`; актуальные — `194.87.196.13` и `45.151.102.219` (DoH только по
  HTTP/2, DoT 853). `PriorityDns.KNOWN_ADDRESSES` обновлены до upstream
  (IPv6 + актуальные IPv4), `DnsRulesTest` больше не хардкодит старые адреса.
  Для мобильного DPI добавлен `relayVia`: DoH — через PRIMARY, TCP/TLS к
  релею — через AWG/VLESS (как на ПК), DESYNC — запас. После того как AWG-мост
  поднялся, `chatgpt.com` через роутер отдал **200 OK за 1,2 с** (раньше — RST
  на мёртвом узле). Gemini пока падает на TLS: DNS-AI отдаёт два адреса, выбор
  шёл по TCP; добавлена TLS-проба с SNI перед выдачей соединения.
- **AI на телефоне (финал):** после готовности AWG `chatgpt.com` через роутер —
  **200 OK за 1,34 с** (маршрут `dnsai`, релей через AWG), `gemini.google.com` —
  **200 OK за 13,1 с** (TLS-проба отбросила мёртвый узел; запрос ушёл `direct`
  как запас). На ПК та же схема: DNS-AI DoH через WARP, ChatGPT/OpenAI —
  Opera EU, Gemini — WARP.
- **Почему «работали только Telegram и YouTube»:** в настройках стоял режим
  «Только выбранные» (126 приложений). Переключено на «Все приложения
  используют VPN»; после переподключения остальные приложения получили
  маршрут. **Gemini-приложение** на вопрос «What is 2 plus 2?» ответило не
  ошибкой сети, а плашкой **«Эта страна не поддерживается»** — это региональное
  ограничение Google для приложения, а не обрыв VPN. **ChatGPT-приложение**
  показывает сетевую ошибку: `ab.chatgpt.com` через DNS-AI отдаёт 403,
  `android.chat.openai.com` — 403; PC использует для OpenAI Opera EU, а
  Android-Opera сейчас не поднимается (API 502). Браузерный `chatgpt.com`
  через роутер — 200. Вывод: для приложений нужен поддерживаемый страной
  выход (Opera EU/VLESS EU), браузерные версии работают через DNS-AI.
- **Кнопка «след. профиль»:** до фикса висела >55 с на `MASQUE-мост`;
  причина — `stop()`/JNI `Close()` живого MASQUE-туннеля. После — при живом
  мосте переключение уходит на VLESS, а у перебора MASQUE есть бюджет и
  TCP-предпроверка.
- **Потеря сети (svc data disable/enable):** watchdog сработал
  (`MULTI rotate`, `живого VLESS-профиля нет`, `планируем новый MULTI-цикл`),
  но `restorePersistedSession` ответил «restart session отсутствует»:
  `decodeRestartSession` принимал только `warp`/`opera` и выбрасывал
  `kind="multi"`. Исправлено; повторный прогон — после пересборки.

## 2026-11-09 — 1.34.11 / versionCode 175

```text
./gradlew testGithubDebugUnitTest assembleGithubRelease assembleGithubDebug
BUILD SUCCESSFUL; 83 класса / 666 тестов, 0 падений

release:
C41DBB247735453FB9528A6798670D0DA095E4441553AA6C7E0C2F53CB5FE080  OBNOVA_1.34.11-arm64-v8a.apk
079B77F939F1527EF116B367D604FFA9AD55FFDAC5C8ED136036AA8971BC8803  OBNOVA_1.34.11-armeabi-v7a.apk
5EA58A2441C28FB463A39643878349E435201E984C76A7A2C5636970FC426FA5  OBNOVA_1.34.11-universal.apk

debug:
3E007A0DBA31457D4FE7CE5485CA9FD143ED4FDE86DADA5D91217E700FD4FAA9  OBNOVA_1.34.11-debug-arm64-v8a.apk
8B563BBEE0043EE700234915E2D52B0AF5336A49EB387B31CAA9C7C890705D7A  OBNOVA_1.34.11-debug-armeabi-v7a.apk
7EDFE686DD4007832727CFC0520DCF6837C32635E4F79DB61B68408FC5471C52  OBNOVA_1.34.11-debug-universal.apk
```

- **YouTube — зелёный:** после фикса `nova-router` (IP → домен из SNI для
  SOCKS-слота) открыто **новое** видео, не Shorts из кэша; кадры, звук и
  таймер идут, `googlevideo` обслуживается через `primary`. Проверено
  пользователем на RMX3370.
- **Telegram — зелёный (PC-путь):** `Telegram → awg 1378 → WARP AWG → DC`.
  В логах: `AWG seed #3 (8.6.112.*:8742) прошёл MTProto-пробу DC4`,
  `149.154.* → awg`; Telegram online, аватары загружены, сообщения
  отправляются. Пользователь подтвердил.
- **AWG data-plane:** Google через `127.0.0.1:1378` — `204`; входящие
  `AWG recv passthrough` идут; `NovaMode = warp-awg-exact`, fake off.
- **VLESS:** `allowInsecure` удалён (Xray 26.10.31), проверенные семена
  идут первыми; VLESS остаётся запасом, если AWG не поднялся.
- **Apps VPN:** режим «Все приложения через VPN»; YouTube больше не исключён.
  Nova исключена из VPN, иначе Xray/MASQUE/Opera зацикливаются в TUN.
- **Футер:** `OBNOVA v1.34.11` — проверено на устройстве.
- **Opera:** остаётся необязательной; upstream API/egress иногда даёт
  timeout/502, MULTI её не блокирует.
- 1.34.11 **опубликован** после зелёных YouTube/Telegram.

## 2026-11-09 — 1.34.10 / versionCode 174 (сборка)

```text
./gradlew testGithubDebugUnitTest assembleGithubRelease assembleGithubDebug
BUILD SUCCESSFUL; 82 класса / 661 тест, 0 падений

release:
AB0EC0800D325E0B1538C521EB3A851276E777762B95E3423D65382C2424D2BD  OBNOVA_1.34.10-arm64-v8a.apk
8222182495D949123F799ACE9F1D9211C473A447ED534B70CBB808C6A22876B3  OBNOVA_1.34.10-armeabi-v7a.apk
0C0BACAD3EDA341B3AB29E1E7E3AA33D78EE113ECF877B75E378D6994F98926E  OBNOVA_1.34.10-universal.apk

debug:
C109199BCD6189D0C3436ED35A77DF5DD42DCAA19BDD3745EFC06BA104B11865  OBNOVA_1.34.10-debug-arm64-v8a.apk
DB6EDA7FCCECFD13D17299C97E013D0E083A19C258C86261CA80D9A8BDE12940  OBNOVA_1.34.10-debug-armeabi-v7a.apk
1B4D69E88EE929F46D26264DC437915F8E367625316B30E2CD721086DC1B6C9C  OBNOVA_1.34.10-debug-universal.apk
```

Статический аудит меню настроек: 37 `row_*`/`btn_*`; у всех, кроме
`row_logs_container` (это контейнер, не кликабельная строка), есть обработчик
в `SettingsActivity`. «Конфигурации» отсутствуют.

Живой прогон меню и функций на RMX3370 (Android 13, `97db202c`), 1.34.10:

- Установлена 1.34.10 (174); главный экран и футер показывают версию.
- Настройки: блок «Профили и подписки», «Слить» → **969** профилей
  (warp 102, vless 684, proton 180, masque 3); диалог генерации WARP
  открывается/закрывается.
- Селектор: AUTO → WARP → OPERA → MASQUE → PROTON → TOR → DNS все
  переключаются; возврат на MULTI.
- Открылись экраны: Routes, Slots, Strategies, DiagnosticsReport, DnsSettings,
  DomainBypass, DirectFlow, LocalProxy, BackgroundSettings, ThemeSettings,
  NotificationSettings, SniMaskSettings; диалоги DNS-профиля, MTU, Opera API
  proxy и WARP+ открываются.
- «Проверить обновления»: GitHub API ответил, найден 1.34.9 (до публикации
  1.34.10); «Поделиться» открывает chooser; Apps VPN показывает список
  приложений и режимы «Все / Только выбранные / Всё, кроме выбранных».
- MULTI: `CONNECTED (backend=MULTI)`, бейдж `MULTI: RU`, router 1373 →
  PRIMARY1376 / DIRECT.
- WARP: `CONNECTED (backend=WARP)`, бейдж `WARP: RU`.
- MASQUE: `CONNECTED (backend=WARP, транспорт=MASQUE)`, бейдж `MASQUE: RU`.
- PROTON: за 60 с перебирал endpoint-ы (`AWG Proton`), до CONNECTED не дошёл —
  сеть режет UDP; это ожидаемое поведение перебора, не падение.
- Opera: WARP-AWG мост поднялся как cfproxy, но к моменту Opera умер; попытки
  через DESYNC/релей дали `anonymous registration ... context deadline
  exceeded` (502/таймаут). Opera не поднялась, YouTube-лента не загрузилась
  (роутер не видел youtube-запросов), Telegram показал список чатов, но статус
  «Соединение…». Это сетевая/Opera-проблема, не падение приложения.


## 2026-11-09 — KALIOPA parity, Phase 1 + device smoke

```text
./gradlew testGithubDebugUnitTest
BUILD SUCCESSFUL; classes=82 tests=661 failures=0 errors=0
  UnifiedProfileStoreTest: 3/3
  RouteSettingsTest: 5/5
  RouterConfigFactoryTest: 17/17 (relay-first Telegram, google_ai, Spotify)
  ExternalSourcesTest: 11/11 (6 источников VLESS, Opera .244 priority)

./gradlew assembleGithubDebug / assembleGithubRelease
BUILD SUCCESSFUL

./gradlew lintGithubDebug
BUILD SUCCESSFUL; Error: 0, Warning: 868 (стиль/старые API)
Lint снова упал внутри ObjectAnimatorDetector (NPE "Text range missing from
end source") — дефект детектора, отчёт создан.

python tools/audit_diff.py --base HEAD --strict
audit: files=16 unused_imports=0 unguarded_calls=0
```

Устройство RMX3370 (Android 13, `97db202c`) — смоук нового меню:

```text
merge:  «В базе 591: живых 0, мёртвых 0, не проверено 591
        (warp 95, vless 396, proton 100)»
check:  «В базе 591: живых 141, мёртвых 355, не проверено 95»
update: «Профили: +288 / −55, всего 629; источников 6/6;
        в базе 882 (warp 95, vless 684, proton 100, masque 3)»
WARP:   «выпущено 7, добавлено 7, всего 52»
```

Настройки генерации WARP открываются из нового блока; «Конфигурации» в меню
отсутствуют.

Opera EU на этом сеансе не поднялась: и `77.111.247.*`, и `77.111.244.*`
вернули `502 Bad Gateway` от upstream. Приоритет `.244` сработал, но причина
глубже — API-сеанс/egress. После правила «один egress» журнал подтвердил:

```text
Stage 2: API SurfEasy и туннель через один egress — socks5h://127.0.0.1:1379.
Opera API SurfEasy через Stage 2 egress (DESYNC) socks5h://127.0.0.1:1379
anonymous registration ... context deadline exceeded
```

Попытка использовать релей как `-proxy` тоже дала timeout регистрации.
MULTI при этом поднялся (`CONNECTED backend=MULTI`) без Opera; Google AI
живьём не проверен, пока Opera недоступна. Это внешняя проблема API/egress,
не выбор endpoint.

После этого добавлен PC-вариант: Opera bootstrap через WARP-AWG
`AwgSocksBridge` (SOCKS 1378) с MASQUE-запасом. Сборка и unit-тесты прошли;
финальный live-тест не выполнен — телефон на экране блокировки (PIN).

## 2026-11-08 — статический аудит (без выпуска)

```text
./gradlew lintGithubDebug
BUILD SUCCESSFUL; issues=906 (Error: 0, Warning: 906 — в основном старый стиль)
Lint упал внутри ObjectAnimatorDetector (NPE "Text range missing from end
source") — это дефект самого детектора, отчёт и сборка при этом созданы.

./gradlew testGithubDebugUnitTest
BUILD SUCCESSFUL
ProtonProbeStateTest: 4/4
WarpGeneratedStoreMergeTest: 3/3
ProfileUpdateCoordinatorTest: 2/2
ExternalSourcesTest: 10/10

python tools/audit_diff.py --base HEAD --strict
audit: files=14 unused_imports=0 unguarded_calls=0
```

Проверено на JVM: обновление WARP-набора сохраняет порядок и не заменяет
проверенные записи; состояние замера Proton считается оборванным по `atMs`;
bootstrap-источники WARP/Proton/Cloudflare остаются HTTPS и в документированном
порядке. Android-сборка `assembleGithubDebug` проходит.

## 2026-11-07 — WARP-генератор, TOR, DNS, Proton (RMX3370, 1.34.9)

### WARP-генератор: число профилей и зависание кнопки

```text
WARP: прошлый прогон прерван — можно повторить     (после force-stop, кнопка снова активна)
настройки: пресет «Быстро», профилей 25
WARP-генератор: личность уже есть, регистрацию пропускаем.
WARP-генератор: сканер нашёл 0 точек и добил ответ 50 случайными
WARP-генератор: первый проход не нашёл ни одной проверенной точки — второй не тратим
WARP-генератор: выпущено 25 профилей, добавлено 0, всего в наборе 55
WARP: выпущено 25, добавлено 0, всего 55
```

Первый прогон до правок: два скана по 45 с (91 с), кнопка оставалась
заблокированной после force-stop из-за состояния `running` в файле. После
правок: 46 с и ровно 25 профилей за прогон при пресете «Быстро»; диалог
подставляет значения пресета («Совместимо» → 15, «Быстро» → 25). Счётчик
«добавлено» показывает реальный прирост: повторный прогон по тем же
встроенным точкам даёт «выпущено 25, добавлено 0, всего 55».

### TOR: obfs4 и авто-перебор

```text
TOR: авто-вход, попытка 1 из 4 — obfs4.
TOR: вход obfs4, прокси ядра слушает 127.0.0.1:39675, мостов 16.
TOR: загрузка 2% — Connected to pluggable transport
TOR: загрузка 14% — Handshaking with a relay        (150 с, таймаут)
TOR: через obfs4 цепочка не построилась.
TOR: авто-вход, попытка 2 из 4 — webtunnel.
TOR: загрузка 100% — Done                            (7 с)
TOR: загрузка завершена, SOCKS5 на 127.0.0.1:9050. webtunnel: принято 6, неудачных 1, живых 5
TOR: выход измерен через цепочку — US.
TOR: проба через цепочку прошла за 357 мс.
```

Итог: **obfs4 на этой мобильной сети не проходит handshake с релеем** (все
16 мостов, 14 %); авто-перебор штатно переходит на webtunnel и поднимает
сеанс. Бейдж `TOR: US`, `tun0` поднят, tun2proxy слушает SOCKS5 9050.

### DNS: MTU-пробы

```text
DNS-движок: Rejected (31/31): t.nova-app.eu via 8.8.8.8:53
            reason=UPLOAD_MTU | value=0 | totals: valid=0, rejected=31
DNS-движок: No valid connections found after MTU testing!
DNS: туннель не поднялся — ни одна несущая не дошла до точки выхода.
DNS не поднялся, а подменять его другим протоколом нельзя.
Состояние сеанса: CONNECTING → STOPPED (backend=DNS)
```

Два прохода по 31 пробе, затем терминальный `STOPPED` — бесконечного
`CONNECTING` нет. Причина отказа — сервер `t.nova-app.eu` возвращает
`UPLOAD_MTU=0` на всех резолверах.

### Proton после смены версии клиента

```text
Proton /auth/v4/sessions: ответ через запасной узел за 29143 мс
Proton /auth/v4/credentialless: ответ через запасной узел за 41459 мс
Proton: получено 118 бесплатных узлов.
Proton: пул 248 узлов (список 118, проверенных 1, из прошивки +129)
Proton probe: из 80 TCP-откликом ответили 23, молчат 57. Сохранено 100, лучший 72 мс
Proton: обновление закончено — Proton: 100 шт.
```

HTTP 422 «version no longer supported» больше не появляется. Без живого
туннеля `/vpn/logicals` не отвечал (прямые хосты, релеи и 6 запасных узлов —
timeout); **через подключённый Proton-профиль** список получен за 3 с,
сохранено 100 профилей. Подключение: `CONNECTED (backend=WARP,
транспорт=AWG Proton)`, бейдж `AWG PROTON: JP`.

## 2026-11-03 — SNI + автозапуск Opera (устройство RMX3370)

```text
MULTI: AWG SOCKS 127.0.0.1:1378 готов — слот cfproxy для Telegram.
Stage 2: API SurfEasy и туннель через один egress — socks5h://127.0.0.1:1376.
Встроенный Opera proxy готов на 127.0.0.1:34920.
MULTI активен: router 127.0.0.1:1373 → PRIMARY1376 / Opera34920 / DIRECT

ROUTER: 172.217.132.***:443 — ClientHello 1751 байт,
        SNI "rr3---sn-5hne6nsk.googlevideo.com"
ROUTER: 172.217.132.***:443 → secondary (SNI "rr3---sn-5hne6nsk.googlevideo.com")
ROUTER: 149.154.167.***:443 → cfproxy (SNI "")
ROUTER: 142.251.144.***:443 → secondary (SNI "i.ytimg.com")

curl --proxy http://127.0.0.1:34920 https://cp.cloudflare.com/generate_204
204 0.564
curl --socks5-hostname 127.0.0.1:1373 https://www.youtube.com
200 2.156
```

YouTube-приложение: лента с реальными карточками (названия и просмотры), Shorts
играет — 0:08 из 0:48, разница кадров за 4 с — **45,8 %**. Telegram: список
чатов загружен, MTProto-соединения `149.154.167.x:443 → cfproxy`. Chrome:
`ya.ru`, `github.com`, `example.com` открываются (Chrome вне allow-list,
идёт напрямую).

Замечание: в MULTI остаётся цикл health-reconnect (`underlying networks`)
каждые ~13 с, если периодический tunnel-probe не проходит; на воспроизведение
это не влияет, но требует отдельного разбора.

Уточнение 2026-11-03: цикл закрыт. MULTI-проба ходит в
`https://cp.cloudflare.com/generate_204` с бюджетом 4,5 с, «потеря underlying
networks» требует 3 отказа, а для MULTI обновляет сети без реконнекта. За 70 с
наблюдения — **0** переходов `CONNECTED → CONNECTING`. Исключение
`apis.google.com`/`gvt1.com` проверено: `android.apis.google.com:443 → primary`;
`youtubei.googleapis.com` и `rr*.googlevideo.com` по-прежнему `secondary`.

### MASQUE: Telegram (2026-11-03)

```text
Состояние сеанса: CONNECTED (backend=WARP, транспорт=MASQUE)
tun0 172.16.0.2/32
MASQUE-ZT@162.159.198.***:443 19/19, avg=63ms

tgnet: connecting via proxy 65.109.194.157:300 secret[54]
tgnet: connected to 149.154.167.41:443
Настройки прокси: 65.109.194.157:300 — «Подключён, пинг: 100 мс»
Баннер «Соединение...» исчез, список чатов живой.
```

YouTube в этом же режиме: `PlaybackState {state=6, position=0}` через 30 с —
WARP не отдаёт `googlevideo`. Вывод: MASQUE — для Telegram, MULTI — для YouTube.

### MULTI: Telegram через Opera (2026-11-03)

```text
ROUTER: 65.109.194.***:300 → secondary      (Opera, CONNECT 200)
Telegram: 65.109.194.157:300 — «Подключён, пинг: 668 мс»
Баннер «Соединение...» исчез, список чатов живой
curl --socks5-hostname 127.0.0.1:1373 https://www.youtube.com → 200
```

`cfproxy` вторым: мост поднят (`127.0.0.1:1378` слушает), но сейчас не несёт
трафик — даже `cp.cloudflare.com` через него отдаёт 000.

### WSS-реле для Telegram (2026-11-03)

```text
Cloudflare Worker: https://nova-connect.yurentiy-avd.workers.dev/tcp
ROUTER: 149.154.167.***:443 → ws (SNI "")
tgnet: connected to 149.154.167.41:443, 149.154.162.123:5222  (без proxy)
Telegram: баннера «Соединение...» нет, чаты обновляются
```

Первый вариант — passthrough в `kwsN.web.telegram.org` — отдавал 101, но
закрывался с `404`: KWS ждёт один MTProto-пакет на кадр. Перешли на сырой
TCP-over-WS: Worker `connect()` + `WebSocketPair`. Ошибка, из-за которой мост
молчал: `event.data` приходит **Blob**, и без `await data.arrayBuffer()` кадр
отбрасывался.

## 2026-11-02 — Stage 2: AWG SOCKS

```text
StartAwgSocks: CF_PROXY_SLOT_ENABLED=true, 127.0.0.1:1378
{"running":true,"listen":"127.0.0.1:1378","mtu":1280,
 "addresses":["172.16.0.2","2606:4700:110:..."]}

curl --socks5-hostname 127.0.0.1:1378 https://cp.cloudflare.com/generate_204
204 0.448

liboperaproxy -bind-address 127.0.0.1:1371 -api-proxy socks5://127.0.0.1:1378
Init complete; curl через 1371 → 204
yt-dlp --proxy http://127.0.0.1:13710 -f 269 jNQXAC9IVRw
100% 227.29KiB → файл 195 771 байт

MULTI с уже слушающим Opera:
MULTI активен: router 127.0.0.1:1373 → PRIMARY1376 / Opera1371 / DIRECT
router youtube.com:443 → secondary (200), googlevideo.com:443 → secondary (404)
router 149.154.167.50:443 → cfproxy
```

Наблюдение: `youtubeIps=8`, `telegramIps=16`; Telegram IP уже матчится
CIDR-правилом и уходит в `cfproxy`. Часть YouTube IP (`142.251.x`) всё ещё
попадает в `primary`: Opera-CONNECT на голый IP не всегда проходит. Доменные
`youtube.com`/`googlevideo.com` идут в `secondary` корректно.

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
tests=606 failures=0 skipped=9
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
tests=606 failures=0 errors=0 skipped=9     # было 583
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

### Главный экран: правый верхний угол (2026-11-04, 1.34.3)

- Устройство: realme RMX3370, 1080×2400, release `1.34.3` (167), установлен
  поверх 1.34.2 (`adb install -r` → `Success`).
- Отключённое состояние (бейдж `gone`): `tv_internet_label [774,116][924,228]`,
  `btn_settings [948,116][1044,212]`, `tv_ip_address [72,206][470,342]` —
  пересечений нет.
- Подключённое состояние (MULTI): `tv_country_badge [786,242][1044,327]`,
  `tv_internet_label [774,116][924,228]`, `btn_settings [948,116][1044,212]` —
  бейдж под гамбургером, пинг слева от него, наложений нет.
- Горизонталь (2160×1080): `tv_country_badge [691,703][949,788]` под
  `btnConnect`, `tv_internet_label [1286,745][1436,857]` у графика — как и
  задумано, пересечений нет.
- Скриншоты: `docs/screenshots/main.png` (обновлён под 1.34.3).

- Живой прогон: `nova-router` на 127.0.0.1:1373 (2 слота, 1 правило),
  `libnova-desync.so` на 1376, `tun0 10.1.11.1`, состояние `CONNECTED`
  (backend=MULTI). В `/proc/net/tcp` 13 соединений к роутеру и 9 к десинку;
  Chrome открыл `habr.com/ru/feed/`.
- После теста флаг выключен, устройство возвращено на WARP (`tun0 172.16.0.2`),
  посторонних процессов router/desync нет; crash-буфер пуст.

### Статический анализ и регрессия (2026-11-04)

- `gradlew testGithubDebugUnitTest`: **635/0/9** (было 610/0/9).
- `gradlew lintGithubDebug`: BUILD SUCCESSFUL, **0 ошибок**, 892 предупреждения
  (HardcodedText 273, UseKtx 188, UnusedResources 98 — все существовавшие).
- `go vet ./...` в `nova-router`: чисто. `nova-core` на Windows не проверяется:
  `syscall.Dup`/`AF_PACKET` есть только в Linux-сборке.
- Скан импортов по 181 Kotlin-файлу: неиспользуемых **0** (удалены
  `kotlin.math.min` и `android.content.Context`).
- Скан сетевых вызовов: 66 площадок, **0 без try/catch**; все HTTP-загрузки —
  через `FeedHttp` с тайм-аутами; 22 сокета без тайм-аута в окне — это
  `ServerSocket`-слушатели либо `connect(..., timeout)`, проверено вручную.
- Тесты обновления данных: `FeedHttpTest` (200/404/потолок/политика),
  `ExternalSourcesTest` (зеркала raw→Pages, HTTPS, репозиторий релизов),
  `DnsRulesTest` (legacy geohide по адресу, идемпотентность, чужой id),
  `UpdateAssetSelectorTest` (ABI, universal, отказ от debug).
- Публичность репозитория (2026-11-04): анонимный
  `GET /repos/abonent0007/OBNOVA` → 200 `private=false`;
  `releases/latest` → `v1.34.3`, 6 ассетов; HEAD
  `OBNOVA_1.34.3-arm64-v8a.apk` → 200, 38 113 907 байт.
  Перед публикацией проверены дерево (2162 файла) и 2 коммита: `local.properties`,
  `keystore.properties`, `*.jks`/`*.keystore` отсутствуют, ключей и токенов нет
  (совпадения — только тестовые пароли в vendored `tools/xray-core`).

### Устройство: 1.34.4 / 168 (2026-11-04)

- Установка поверх 1.34.3: `adb install -r OBNOVA_1.34.4-arm64-v8a.apk` →
  `Success`; `versionName=1.34.4`, `versionCode=168`, crash-буфер пуст.
- Правый верхний угол (1080×2400): `btn_settings [948,116][1044,212]`,
  `tv_internet_label [774,116][924,228]`, `tv_country_badge [786,242][1044,327]`
  — без пересечений, как в 1.34.3.
- DNS-правила: экран показал 8 строк, первые две — `https://eu.geohide.ru/dns-query`
  и `https://us.geohide.ru/dns-query`, обе включены; сводка
  `DNS: eu.geohide.ru + ещё 7, автовыбор пути`. Версия файла 5 читается без
  потери списка.
- Списки: «Обновить списки» → `Обновлено … обновлено файлов: 14`
  (+2 IP-файла из `confeden/Nova`). Журнал:
  `LISTS: cloudflare_ips.txt — 15 строк из confeden/Nova, всего 15`,
  `LISTS: telegram_ips.txt — 10 строк из confeden/Nova, всего 16` (6 диапазонов
  сохранены из встроенного asset). `youtube_ips.txt` остаётся встроенным: в
  PC-репозитории его нет.
- MULTI подключён: `ROUTER: 149.154.167.x:443 → ws`, `:5222 → ws`,
  `youtubei.googleapis.com → secondary`; `tun2proxy` фиксирует двусторонний
  обмен. Проверка сервисов: RU `ya.ru` 302/230 мс, US `cnn.com` 302/576 мс,
  YouTube 302/679 мс, Telegram 200/650 мс.
- Telegram (`org.telegram.messenger.web`): чат-лист открылся с аватарами и
  непрочитанными, `tgnet` пишет `config write ok` и handshake; соединения
  `149.154.167.41:443` и `:5222` идут через `ws`. Прокси внутри Telegram не
  настраивался.
- Публичные подписки VLESS: кнопка «Загрузить публичные подписки» → **4/4
  источника**, `добавлено 162, удалено 0, всего 400` (общий лимит профилей).
  Три `raw.githubusercontent.com` источника прошли через зеркало
  `raw.githack.com`, iboxz — напрямую. Состояние сохранено в
  `public_subscriptions.json`, worker запланирован.

### 1.34.6 — профили/подписки и Cloudflare (2026-11-05)

- JVM: **638/0/9** (добавлены `WarpConfigFeedTest` и проверки фида).
- Release/debug собраны: 6 APK; release arm64 SHA-256
  `2D5300D66DCCE647C9B995384BC65B2E67A67890B146CA5DFD7E52A7B84FDEDF`.
- Что проверено статически: кнопка «Обновить профили и подписки» вызывает
  `ProfileUpdateCoordinator` (4 источника), затем запускает
  `ACTION_START_WARP_NETWORK_ADAPTATION`; Cloudflare-генератор при пустом скане
  берёт встроенные точки Nova, дополняет `warp_generated.json`, а при отказе
  регистрации использует `warp-workers.warp-api.cfd`.
- Проверка на устройстве 1.34.6 не завершена: adb-соединение с RMX3370
  прервалось после установки 1.34.5; сетевые сценарии кнопок нужно повторить,
  когда телефон снова появится.

### 1.34.7 — устройство RMX3370 (2026-11-05)

- Установка 1.34.7/171 поверх 1.34.5: `Success`, запуск без crash.
- Новая кнопка «Обновить профили и подписки» видна выше Cloudflare. Нажатие:
  `Профили обновлены. Адаптация WARP…`, служба
  `START_WARP_NETWORK_ADAPTATION` запущена. Прогресс data-plane за 4 минуты не
  появился — прогон требует больше времени/отдельной проверки.
- Cloudflare-кнопка после чистого запуска: скан 45 с, затем
  `WARP: 50 новых, всего 60 профилей` — fallback на встроенные точки Nova
  сработал, надписи «точки входа не найдены» больше нет; выпуск дополнил набор.
- Публичные подписки: в 1.34.5 проверено 4/4 источника; в 1.34.7 новый
  координатор сообщил «Профили обновлены».
- Telegram/YouTube в 1.34.7 не перепроверялись; в 1.34.5 Telegram работал через
  `ws`, YouTube — через `secondary`.


- Иконка 1.34.5: после установки и перезапуска launcher
  (`am force-stop com.android.launcher`) поиск «OBNOVA» показывает новый знак —
  тёмный скруглённый квадрат с зелёной рамкой и тремя узлами; старая звезда
  больше не отображается.
- Ошибочная подписка: `https://example.invalid/nope.txt` → журнал
  `Подписка с example.invalid не загрузилась: Unable to resolve host…`;
  видимая ошибка вместо «конфигураций не найдено».
- Апдейтер включён (`UPDATER_ENABLED=true`, github): в настройках видны
  «Автообновление», «Проверить обновление» и «Скачать последнюю версию». Ручная
  проверка: `Метаданные обновления взяты из релизов GitHub: 1.34.3,
  OBNOVA_1.34.3-arm64-v8a.apk` — источник наш репозиторий, ABI выбран верно;
  обновления нет, потому что установлена 1.34.4. Подписки (VLESS `iboxz`,
  WARP `confeden/auto-warp-config`) остаются стоковыми.

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
