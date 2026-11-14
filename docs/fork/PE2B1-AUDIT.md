# P-E.2B.1 — аудит источников DIRECT и обхода VPN

> Только анализ существующего кода. Production-код не менялся, DEBUG-счётчики
> не возвращались. Версия `1.34.11 / 175`. Снято 2026-11-14.

Обозначения: **Strict** — Strict Full Tunnel (MULTI, весь трафик устройства);
**Limited** — только выбранные приложения; **Selective** — явные исключения.

Главный вывод: текущий `killSwitch` уже убирает `direct` из
`defaultOrder`/`order()`, но **не является Strict**. Семь источников DIRECT
работают независимо от него, а Android-уровень вообще не ограничен.

---

## 1. Матрица

### 1.1. Android VPN и область защиты

| Механизм | Файл / метод | Текущее поведение | Strict | Limited | Риск | Проверка | Доказательность |
|---|---|---|---|---|---|---|---|
| `addDisallowedApplication(directApps)` | `NovaVpnService.applySplitTunnelPolicy` (26999–27032), `applyOperaSplitTunnelPolicy` (27138–27164), `applyDefaultSplitTunnelExclusions` (27043–27056) | В режиме «все приложения» из VPN исключаются Nova и `DirectAppsPolicy.resolve()` | **Запретить** (кроме собственного пакета) | Разрешить только явный список пользователя | Банки/госуслуги/операторы идут мимо VPN, включая IPv6 | Unit (сборка политики) + device (`ip rule`, table) | Код |
| `DirectAppsPolicy` (закрытый список + свои + снятые) | `DirectAppsPolicy.resolve` (50–85), `RussianDirectApps.all` (283), `ClientData.isRussianDirectAppsEnabled` (3531, default `true`), `getDirectApps` (3552) | Всегда добавляет банки/госуслуги; мастер-выключатель убирает только закрытый список, свои остаются | **Выключить полностью** | Только явный список, закрытый — off | Скрытый обход, о котором UI молчит в режиме «все приложения» | Unit + device | Код |
| `addAllowedApplication` (allow-list) | `applySplitTunnelPolicy` mode 1 (26980–26996), `applyOperaSplitTunnelPolicy` mode 1 (27108–27135) | В VPN только выбранные; остальные — системная сеть | **Запретить** (Full Tunnel несовместим) | **Разрешить** | Приложения вне списка выходят напрямую; нельзя заявлять защиту устройства | Unit + device | Код |
| Собственный пакет | `addDisallowedApplication(packageName)`: 27019, 27045, 27140 | Nova всегда вне VPN (иначе рекурсия tun2proxy/Xray) | **Оставить** как инфраструктуру | Оставить | Трафик самого VPN-приложения мимо туннеля; пользовательского трафика там нет | Device | Код |
| `allowBypass()` | — | **Не вызывается** (единственное упоминание — комментарий `UnderlyingNetwork.kt:27`) | Запретить вызов (assert в тесте) | Запретить | `allowBypass` разрешил бы приложениям обходить VPN | Unit/static | Код |
| `protect()` | `NovaVpnService` 8173 и др. | Защищает underlay-сокеты (пробы DNS, транспорт) от собственного TUN | Оставить | Оставить | Если protect получит пользовательский сокет — обход; сейчас только инфраструктура | Code review + device | Код |
| Локальные сети `excludeRoute` | `buildLocalNetworkBypassPrefixes` (27912–27925), `applyLocalNetworkBypass` (27875–27895) | `10/8`, `172.16/12`, `192.168/16`, `127/8`, `169.254/16`, `100.64/10`, multicast, `fc00::/7`, `fe80::/10`, `ff00::/8`, адреса link-properties | **Оставить** (документированное исключение LAN) | Оставить | Доступ к локальным устройствам идёт мимо VPN; интернет — нет | Device | Код |
| DNS-carve-out приоритетного DoH | `directBypassDnsAddresses` (27276–27416) | В WARP адреса DoH владельца выводятся `excludeRoute`; в MULTI `ownerDnsViaTunnel=true` их убирает, но `userBypass` остаётся | **Запретить** | Разрешить только явные DNS-правила | Открытый/шифрованный DNS мимо туннеля | Unit + device | Код |
| Строгий Private DNS | `applyPrivateDnsBypass` (27706–27800) | Если `coreInterceptHandlesDns=false` (MULTI/tun2proxy), сервер Private DNS резолвится и выводится `excludeRoute`; плюс plain-резолверы в `directBypassDnsAddresses` (27407–27415) | **Запретить**; Private DNS off/auto или DROP | Явно разрешить, с предупреждением | Утечка DNS и обход политики; при отказе — потеря резолвинга | Unit + device | Код |
| Обход по своим доменам | `domainBypassAddresses` (27515–27609) | Android 13+: резолвит домены и выводит адреса `excludeRoute` — **данные** идут мимо VPN | **Запретить** | Разрешить только явный список | Прямой трафик по выбранным доменам | Unit + device | Код |
| Обход по зонам (`.ru` и др.) | `applyCoreDomainBypassPolicy` (8714+), `nova-core/engine/domain_bypass.go` | Работает только там, где пакеты видит Go-core (WARP/MASQUE); в MULTI/tun2proxy не применяется | **Запретить** (в Strict MULTI и так инертен) | Только явные зоны | Утечка через ядро на других backend | Code review | Код |
| Tethering / hotspot | — (кода нет) | Android не заворачивает tethering в VpnService | **Вне области**, заявить в UI | Вне области | Трафик клиентов hotspot идёт физически | Device (NOT TESTED) | Платформа |
| Системные UID / captive portal | — (кода нет) | При full tunnel в VPN попадают все UID, кроме disallowed; отдельные системные компоненты могут биндиться к сетям | Принять как платформенное ограничение | — | Возможен системный трафик мимо VPN | Device (NOT TESTED) | Платформа |
| Always-on / Lockdown (детект) | `AlwaysOnVpn` (1–58), `DiagnosticSnapshot` 233–235 | API 29+: `VpnService.isAlwaysOn()`/`isLockdownEnabled()` (подтверждено в SDK 35); <29 — `Settings.Secure`, может быть `null` | Показывать фактическое состояние; не обещать больше | То же | Ложное «защищено» при выключенном Lockdown | Unit + device | Код + SDK |
| Остановка VPN-сервиса | — | Маршруты ОС восстанавливаются; собственной блокировки нет | Только Android Lockdown | То же | Трафик уходит в сеть оператора | Device (Wi-Fi/LTE) | Платформа |

### 1.2. Router (`RouterConfigFactory` → `nova-router`)

| Механизм | Файл / метод | Текущее поведение | Strict | Limited | Риск | Проверка | Доказательность |
|---|---|---|---|---|---|---|---|
| Слот `direct` | `RouterConfigFactory.build` 321–328 | Создаётся **всегда** (`"direct" to SlotSpec("direct", ...)`) | **Не создавать**; валидатор запрещает | Разрешить только для явных правил | Любое правило может уйти напрямую | Unit (config JSON) | Код |
| Правило `exclude` | 98–105 | `order=["direct"]`, `udpOrder=["direct"]` — **не зависит от killSwitch** | **Убрать** правило (трафик в VPN) | Только явный список | Скрытый DIRECT для целого списка домен | Unit | Код |
| Правило `local-tld` | 106–113 | `order=["direct"]`, `udpOrder=["direct"]` — всегда | **Убрать** правило | Только явный список | Все `.ru/.su/...` мимо VPN | Unit | Код |
| `RouteClass.DIRECT` override | 453–455 | `listOf("direct")` независимо от killSwitch | **Отклонить** override / скрыть в UI | Разрешить | Пользовательский DIRECT в Strict | Unit + UI | Код |
| `china-ai` | 196–202, 447, 469 | `listOf("direct")`, `udpOrder=["direct"]` | **Убрать** direct (в VPN или DROP) | Только явно | DeepSeek/Qwen мимо VPN | Unit | Код |
| `spotify` | 219–225, 469 | `listOf("direct")`, `udpOrder=["direct"]` | **Убрать** direct | Только явно | Spotify мимо VPN | Unit | Код |
| `defaultOrder` | 385–399 | `direct` добавляется только при `!killSwitch` | Уже корректно; **проверять валидатором** | — | Частичная защита | Unit | Код |
| `order()` tail | 418–421, 427–431 | `direct` только при `!killSwitch` | Уже корректно; валидатор | — | Частичная защита | Unit | Код |
| `defaultUdpOrder` | 403 | `primaryUdpOrder`, без direct | Корректно | — | — | Unit | Код |
| TCP fallback между слотами | `nova-router/upstream.go` 34–48 | Перебирает слоты по порядку; `direct` — если он в порядке | Разрешён только среди VPN-слотов | — | При наличии direct — утечка | Unit | Код |
| UDP: только первый слот | `nova-router/udp.go` 30–38, 147 | Первый слот; не UDP-capable → DROP; fallback/direct нет | Сохранить | Сохранить | — | Unit | Код |
| `dnsai` как egress | `nova-router/config.go` 102–105 | Отклоняется: resolver-only | Сохранить | Сохранить | — | Unit | Код |
| Валидация конфига | `nova-router/config.go` 120–146 | Проверяет ссылки на слоты/CIDR; **не знает про Strict** | Добавить `strict`-режим: запрет `direct` в слотах/порядках | — | Опечатка/старый конфиг вернёт direct | Unit | Код |

### 1.3. Управление режимами и область

| Механизм | Файл / метод | Текущее поведение | Strict | Limited | Риск | Проверка | Доказательность |
|---|---|---|---|---|---|---|---|
| AUTO + требования → MULTI | `RouterPolicyRequirements.evaluate` (18–44), `startSmartConnection` 6103–6114 | smart routing / любой override / killSwitch → `policyMulti` | Сохранить; Strict всегда требует MULTI | То же | AUTO может уйти в WARP без правил | Unit | Код |
| Отказ MULTI | 6117–6141 | Повтор/остановка; **на WARP не переходит** | Сохранить | Сохранить | — | Device | Код |
| Переключение на WARP/OPERA/VLESS при killSwitch | `RouterPolicyRequirements.dataPlaneHint` 65–67; `RoutesActivity` 56–58 | Только предупреждение в подписи | **Отклонять или требовать явного отключения Strict** | Предупреждение | Пользователь думает, что Strict активен | Unit + UI | Код |
| Потеря слота | `upstream.go` 34–48 | Перебор VPN-слотов; если пусто — ошибка/DROP | DROP | DROP | — | Integration | Код |
| Остановка/повторный запуск сервиса | `START_STICKY`, `restorePersistedSession` | Сессия восстанавливается; Strict-флаг сохраняется | Не обещать защиту без Lockdown | — | Окно без VPN | Device | Код |
| UI DIRECT-выбора | `RoutesActivity.showPicker` 452–459 | DIRECT доступен всегда; подтверждений нет | Скрыть/запретить | Разрешить | Пользователь включит обход в Strict | UI test | Код |
| UI Kill Switch | `RoutesActivity` 56–58 | Простой boolean без области и предупреждений | Новый режим + подтверждение | — | «Strict» выглядит включённым, обходы остаются | UI test | Код |
| Scope (full/limited) | `ClientData.getSplitMode` 8020 (default 0), `DirectAppsPolicy` | Full tunnel по умолчанию, но с закрытым списком direct | Full = mode 0 + DirectAppsPolicy off | Limited = mode 1/2 | Нельзя заявлять «всё устройство» при allow-list | Unit + UI | Код |
| IPv6 в MULTI | `configureInternetRoutes` c `enableIpv6DefaultRoute=false`; P-E.1 baseline | `tun0` без `::/0`; Android добавляет `unreachable default` в таблицу VPN для UID | Сохранить; **проверить full tunnel** | То же | При full tunnel runtime не проверялся | Device (NOT TESTED) | Код + P-E.1 |

---

## 2. Полная карта источников DIRECT / обхода VPN

**Android-уровень**
1. `addDisallowedApplication` для `DirectAppsPolicy` (закрытый список банков/госуслуг + свои).
2. `addAllowedApplication` (allow-list): всё остальное — системная сеть.
3. `excludeRoute` локальных сетей (LAN) — осознанное исключение.
4. DNS-carve-out: DoH владельца / пользовательский bypass / plain-резолверы при строгом Private DNS.
5. `excludeRoute` для своих доменов (Android 13+).
6. Собственный пакет Nova (инфраструктура).
7. Tethering и часть системного трафика — вне VpnService (платформа).
8. Остановленный VPN: трафик возвращается в сеть (нужен Lockdown).

**Router**
9. Слот `direct` (создаётся всегда).
10. Правило `exclude` (TCP+UDP).
11. Правило `local-tld` (TCP+UDP).
12. `RouteClass.DIRECT` override.
13. `china-ai` (TCP+UDP).
14. `spotify` (TCP+UDP).
15. `direct`-хвост в `defaultOrder`/`order()` — **уже отключается** `killSwitch`.

**Прочее**
16. `SlotResolver` — умеет killSwitch, но **не используется** (мёртвый код; не защита).
17. `RoutingPolicy.localTldDirect` — legacy-класс, в генерации правил не участвует.

---

## 3. Обязательные изменения для Strict Full Tunnel

1. **Модель режима.** Отдельный `RoutePolicyMode` (STRICT_FULL / LIMITED / SELECTIVE), а не
   `killSwitch: Boolean`. Миграция `routing_killswitch` → `STRICT_FULL`.
2. **RouterConfigFactory:** флаг `strict`:
   - не создавать слот `direct`;
   - не генерировать правила `exclude`, `local-tld`, `china-ai`, `spotify` с direct;
   - `RouteClass.DIRECT` → отклонить (или BLOCK);
   - проверить, что ни один `order`/`udpOrder`/`defaultOrder` не содержит `direct`.
3. **Валидатор RouterConfig:** в strict-режиме запретить слот `direct` и любое его
   упоминание; пустой order = DROP. Это защита от старых prefs и опечаток.
4. **NovaVpnService:** strict ⇒ full tunnel: `splitMode=0`, **не вызывать**
   `DirectAppsPolicy`, `addDisallowedApplication` только для `packageName`,
   `allowBypass` не вызывать.
5. **DNS:** в strict отключить `directBypassDnsAddresses`, `domainBypassAddresses`,
   `applyPrivateDnsBypass` (никаких `excludeRoute` под резолверы/домены); при
   недоступности DNS — DROP, без system fallback.
6. **Режимы:** strict разрешён только в MULTI; при выборе WARP/OPERA/VLESS —
   отклонять или требовать явного отключения Strict с подтверждением.
7. **UI:** трёхзначный индикатор области (весь трафик / только выбранные /
   VPN остановлен + Lockdown on/off/unknown); подтверждение с перечислением
   последствий (банки, госуслуги, операторы пойдут через VPN); скрыть DIRECT
   в strict.
8. **IPv6:** подтвердить на устройстве в full tunnel, что Android даёт
   `unreachable default` для всех UID (P-E.1 проверял только selected).

## 4. Изменения для Limited / Selective Direct

1. **Limited:** allow-list без закрытого списка; `DirectAppsPolicy` не участвует;
   UI честно пишет «защищены только выбранные приложения».
2. **Selective:** отдельный список приложений (только явный выбор), отдельный
   список доменов/CIDR; никаких `.ru`, `local-tld`, `exclude`, `spotify`,
   `china-ai` по умолчанию.
3. **UDP:** direct только по явным IP/CIDR; по доменам — не угадывать (P-C).
4. **UI:** приложение из `addDisallowedApplication` помечать «Полный обход VPN».
5. Strict и Selective взаимоисключающие; переключение — с подтверждением.

## 5. Lockdown

- **API 29+:** `VpnService.isAlwaysOn()` и `isLockdownEnabled()` — публичные,
  подтверждены в `android-35/android.jar` и документации (Added in API 29).
  Пока сервис запущен, это достоверный источник; `DiagnosticSnapshot` уже его
  использует под `SDK_INT >= Q`.
- **API 24–28:** публичного API нет; `AlwaysOnVpn` читает
  `Settings.Secure.always_on_vpn_app/lockdown` без разрешений, но на части
  прошивок возвращает `null`. Тогда состояние — **unknown**, UI не имеет права
  показывать «выключено» или обещать защиту.
- **Сервис остановлен:** узнать Lockdown нечем (нет живого `VpnService`);
  остаётся `Settings.Secure` с теми же ограничениями → `unknown`.
- Включить Always-on/Lockdown программно нельзя; только системный экран
  (`AlwaysOnVpn.openSettings`). Манифест уже объявляет
  `SUPPORTS_ALWAYS_ON` (AndroidManifest.xml:240).

**Вердикт:** детект Lockdown — **PASS для API 29+** (код + SDK), **NOT VERIFIED
для 24–28 и для остановленного сервиса**. Без подтверждённого Lockdown UI
обязан писать «защита при остановке VPN не гарантируется».

## 6. Минимальный план реализации

| Шаг | Зависит от | Содержание | Тесты |
|---|---|---|---|
| B.2.0 | — | `RoutePolicyMode` + миграция prefs | unit |
| B.2.1 | B.2.0 | `RouterConfigFactory` strict: нет direct-слота/правил/override | unit: JSON без `"direct"` |
| B.2.2 | B.2.1 | Валидатор `nova-router` strict | unit: reject direct |
| B.2.3 | B.2.0 | `NovaVpnService`: full tunnel, DirectAppsPolicy off, DNS carve-outs off | unit (fake builder) |
| B.2.4 | B.2.0 | Режимы: strict только MULTI; запрет переключения backend | unit + UI |
| B.2.5 | B.2.3 | UI: область, Lockdown-статус, подтверждение | UI test |
| B.2.6 | всё | Runtime: потеря слота, DNS, Wi-Fi→LTE, Lockdown on/off | device |
| B.3.1 | B.2.0 | Selective: явные списки приложений/доменов/IP | unit |
| B.3.2 | B.3.1 | UI «Полный обход VPN», предупреждения | UI test |
| B.3.3 | B.3.1 | UDP direct только IP/CIDR | unit + device |

## 7. Ограничения аудита

- Runtime Strict не проверялся: режима ещё нет. Всё, что ниже кода, — `NOT TESTED`.
- Tethering и системные UID — платформенное поведение, без root не доказуемо.
- Packet-level отсутствие утечек по-прежнему не доказано (нет root/tcpdump).
- `SlotResolver` и `RoutingPolicy.localTldDirect` — мёртвый/legacy код,
  в защите не участвуют; удалять в рамках P-E.2B не требуется.
