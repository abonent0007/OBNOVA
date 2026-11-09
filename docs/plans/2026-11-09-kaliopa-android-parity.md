# KALIOPA → Android: план паритета и модернизации меню

> Дата: 2026-11-09. Источник схемы: `http://127.0.0.1:8099/` →
> `templates/kaliopa-clean.json` (KALIOPA 1.44.2.40). Обсуждение: Google AI Mode
> (council), канал duck.ai в тот момент был недоступен (модель/лок). Замечания
> AGI учтены ниже; всё, что не переносится, отмечено явно.

## 1. Что в PC-схеме

- **PAC 1369:** localhost/LAN/exclude/.ru/user_direct → DIRECT;
  ai_apps/eu/youtube → Opera EU 1371 (NL); Google AI/opencode → Opera 1371,
  только если она легла → WARP 1370; ru/user_ru/foreign-default → WARP 1370
  (RU exit); Telegram → relay 1372 → WARP 1370; discord/whatsapp/spotify →
  DIRECT; browser=auto, games=direct, obs=auto.
- **Primary 1370:** Custom/WARPgen (AWG UDP), альтернативы Proton AWG / CF AWG /
  MASQUE взаимоисключающие.
- **Opera 1371:** HTTP CONNECT TCP 443, bootstrap через WARP 1370, выход NL.
- **Резервы:** Tor 1375 / VLESS 1379 только при secondary=tor/profile.
- **DNS:** локальный 53 → DoH `dns-ai.ru` через WARP 1370 (fallback 1371).
- **Telegram:** relay 1372 → WARP 1370; TLS-терминатор 1374 опционален.
- **PAC только TCP;** QUIC UDP/443 на PC перехватывают WinDivert/WFP.

## 2. Замечания AGI (приняты)

1. **UDP/QUIC:** Android TUN + tun2proxy не гарантирует тот же охват UDP, что
   WinDivert/WFP. Нельзя обещать PC-поведение QUIC; проверять на устройстве,
   UDP-профили не считать «мёртвыми» по TCP-пробе.
2. **Opera только TCP 443:** не отправлять в Opera произвольный HTTP/80 и
   кастомные порты; для них primary/DIRECT.
3. **Telegram:** PC SOCKS5 1372 нельзя один-в-один перенести в TUN; оставляем
   WSS-relay/cfproxy и ставим relay первым для Telegram-доменов.
4. **Per-app:** games/obs на Android — это UID/пакет, а не домены; не смешивать
   с L7-правилами, вынести в «Приложения».
5. **Единая база:** простой фасад над старыми store-ами даёт гонки. Нужна
   append-only база с `origin/hash/status/last_checked/fail_count`, мягким
   удалением и миграцией; bundled seeds — read-only и не удаляются.
6. **Меню:** убрать дубли, один экран диагностики, «Конфигурации» удалить.
   Порядок пользователя важнее предложенного AGI: сначала профили, потом
   проверка/слияние, затем соединение и маршруты.
7. **Не переносить:** системный PAC, сложные proxy chains, несколько локальных
   inbound-портов (SOCKS/HTTP) — на Android всё идёт через TUN.

## 3. Противоречия PC-схемы (проверить на RMX3370)

- Opera edge: `77.111.247.19` в sub, но note говорит, что он отдаёт `/sorry`, а
  рабочий — `77.111.244.47`. Проверить оба через текущий NL-выход.
- Fallback 1370→1371 назван «гео, не health». Проверить, что при падении WARP
  Opera не притворяется живой; нужен явный health/таймаут.
- Telegram 1372 (SOCKS5) vs Android WSS: проверить мобильные сети, оставить WSS
  как основной, SOCKS-релей — только если появится локальный inbound.
- PAC TCP-only vs QUIC: проверить YouTube/Chrome с QUIC, убедиться, что UDP не
  утекает в DIRECT и не вешает сессию.

## 4. Целевое меню (сверху вниз)

1. **ПРОФИЛИ И ПОДПИСКИ**
   - `1. Обновить всё` — подписки (6 источников PC) + генерация WARP.
   - `2. Проверить и очистить` — TCP-доступность, dead-профили, soft-delete.
   - `Слить в одну базу` — импорт/дедупликация всех store-ов.
   - `Настройки генерации WARP`.
   - `Обновить Proton` (только при выбранном PROTON).
   - Строка состояния базы: всего/живых/мёртвых/по протоколам.
2. **ПОДКЛЮЧЕНИЕ** — селектор AUTO/MULTI/WARP/…, без дублирующих кнопок.
3. **МАРШРУТЫ** — правила сервисов, DNS, bypass, direct flow, локальный прокси.
4. **ПРИЛОЖЕНИЕ** — автозапуск, always-on, фон, QS, виджет, тема, уведомления;
   сюда же переносится per-app (Apps VPN).
5. **ОБНОВЛЕНИЯ** — автообновление, ручная проверка, релиз.
6. **ДИАГНОСТИКА** — один экран: логи, TUN, проверки.

Удалить: `Конфигурации` (экран, макет, адаптер, пункт меню), дублирующие
кнопки обновления в блоке подключения, отдельный экран «Slots/Strategy» —
позже слить с маршрутами.

## 5. Маршрутное соответствие PC → Android

| PC | Android сейчас | Решение |
|---|---|---|
| .ru / local / LAN → DIRECT | local TLD → direct; RU-класс → primary | оставить local direct; RU-класс не менять без теста |
| ai_apps/eu/youtube → Opera NL | EU/YouTube/AI → secondary → spoof → primary | уже совпадает; Opera bootstrap через primary |
| Google AI → Opera, fallback WARP | входит в `ai` | Phase 2: отдельный `google_ai` список |
| Telegram relay → WARP | WSS/cfproxy + primary/spoof | Phase 1: relay первым для доменов Telegram |
| Discord/WhatsApp/Spotify → DIRECT | primary (RU) | Phase 2: проверить direct на устройстве |
| Games/OBS по приложению | per-app split | Phase 2: UID-правила, не домены |
| Primary WARP → Proton/CF/MASQUE | MULTI VLESS→MASQUE→DESYNC | не ломать: MULTI оставить, PC-порядок — Phase 3 |

## 6. Фазы

### Phase 1 (эта сессия)
- 6 источников VLESS из PC (+2: aviamastersgh verified/ru).
- `UnifiedProfileStore` (append-only JSON, hash/id, origin, alive/checked/fail,
  merge, stats, sync from legacy, removeDead для VLESS/generated/imported;
  bundled seeds не удаляются).
- `UnifiedProfileChecker` — TCP-проба VLESS/Proton/MASQUE, UDP-AWG честно
  помечается untested.
- Новый верхний блок «Профили и подписки»: Обновить всё / Проверить и очистить /
  Слить / Настройки генерации / Обновить Proton / статус.
- Удаление «Конфигураций» из меню, макета, манифеста, адаптера и активности.
- Telegram: relay первым для доменных правил.
- Тесты: merge/dedup/stats/id, 6 источников, обновление меню не ломает старые
  тесты; сборка `assembleGithubDebug`.

### Phase 2
- `google_ai/opencode` отдельными правилами (Opera → primary fallback).
- `spotify` (DIRECT), `discord/whatsapp` (решить DIRECT vs primary по тесту).
- `games/obs` — только per-app, UI «Приложения».
- Проверка Opera endpoint (247.19 vs 244.47), health/geo fallback.
- Единая база как источник для engine-адаптеров (не только UI/checker).

### Phase 3
- Первичный слот PC-порядка WARP→Proton→CF→MASQUE (после device-тестов,
  чтобы не сломать YouTube/Telegram).
- Планировщик обновления/проверки (WorkManager), «Подключить лучший».
- Удаление legacy-store-ов после миграции.

## 7. Критерии приёмки Phase 1

- В меню сверху «Профили и подписки»; «Конфигурации» отсутствует.
- «Обновить всё» обновляет 6 источников и запускает генерацию WARP.
- «Слить в одну базу» показывает единый счётчик; повторный запуск не плодит
  дубли; bundled seeds остаются.
- «Проверить и очистить» не удаляет bundled/UDP-AWG, удаляет только доказанно
  мёртвые VLESS/generated/imported после порога.
- Все unit-тесты зелёные; `assembleGithubDebug` проходит; на устройстве — смоук
  меню и обновления.

## 8. Статус Phase 1 (2026-11-09)

- [x] 6 источников VLESS и лимит 1200.
- [x] `UnifiedProfileStore`: merge/dedup/stats/sync/check/removeDead, dead-registry.
- [x] Верхний блок «Профили и подписки» и удаление «Конфигураций».
- [x] Telegram-домены идут через relay первыми.
- [x] `google_ai`/`opencode` отдельным правилом (Opera → primary → DESYNC).
- [x] Opera EU: `.244` приоритетнее `.247`, слабый кэш перезапрашивает ленту.
- [x] Opera: API и tunnel в одном прогоне используют один egress.
- [x] Spotify — отдельное DIRECT-правило.
- [x] Unit-тесты: 82 класса / 661 тест, 0 падений; debug-сборка.
- [ ] Opera на RMX3370: локальный MASQUE не регистрирует API (timeout), релей
      как `-proxy` тоже timeout; 502/регистрация остаются внешней проблемой.
      Первичный MULTI при этом поднимается без Opera.
- [ ] UI-смоук других экранов, Discord/WhatsApp direct и per-app — Phase 2.
- [ ] Phase 3: PC-порядок primary и engine-адаптеры единой базы.
