# План: перенос логики Nova PC в Android-форк

> Дата: 2026-11-01. База: Android `1.34.0/164`, PC `1.44.2.15`.
> Это план переделки, а не переписывания «с нуля»: облачные источники профилей
> остаются стоковыми, меняется слой принятия решений и маршрутизации.

## 1. Цель

Сделать Android-приложение логическим аналогом PC-форка: не «один выбранный
транспорт», а **несколько слотов одновременно** (primary, secondary, relay,
direct), правила по домену/приложению/списку, измерение выхода по стране,
failover и обучение стратегий. Сохранить то, что на Android уже работает
(VpnService, WARP/MASQUE, VLESS, Proton, Opera, Tor, DNS-кэш, ZAPRET).

## 2. Чем PC отличается (gap-матрица)

| Область PC | Android сейчас | Что делать |
|---|---|---|
| PAC: 12+ правил, порядок `user_direct → … → foreign_default` | один backend + domain bypass | перенести порядок правил в `RoutingPolicy` |
| Слоты: primary (WARP/MASQUE/VLESS), secondary (Opera/Tor/импорт), relay, direct | один активный backend | ввести `SlotRegistry` и мультислотовый data-plane |
| Health слотов, `last_resort`, pinned egress | частично (fallback-цепочки) | `SlotHealth` + цепочки попыток |
| Измерение страны выхода | нет | `EgressCountryProbe` (geo по IP) |
| `foreign_default`, killswitch | нет | политики `ForeignDefault`, `KillSwitch` |
| Списки main/second/exclude/ai/cloudflare/youtube/telegram/whatsapp/discord/games | частично (`DomainBypassRules`, direct apps) | `ListRepository` + те же списки |
| DNS-AI/NRPT-разблокировка | `PriorityDns`, DoH/DoT, кэш | `DnsPolicy` по домену и приложению |
| Telegram-релей 1372 (WSS/CF, pinned) | нет | отдельный `nova-tgrelay` (Go) + маршрут |
| Proton: варианты `-p2/-p3`, порты 443/1194/4500 | один конфиг на узел | `ProtonVariants` |
| VLESS pre-flight (TCP 2 с, 8 потоков) | нет | `VlessPreflight` перед записью |
| Opera: backoff `60·2^n`, discover, fastest | частично | `OperaBackoff` |
| Обучение: Томпсон, CUSUM, эволюция стратегий | WARP discovery + health rotation | портировать `nova-learn`/`nova-probe` |
| TLS: JA4, терминатор, нейтральный SNI | маски SNI | JA4-профили; терминатор — только там, где допустим |
| Панель стратегий, диагностика, редактор маршрутов | частично | новые экраны |
| Killswitch / always-on | always-on есть | связать с политикой |

## 3. Целевая архитектура Android

```
VpnService (tun)
   └── nova-router (Go, локальный процесс)
          ├── RoutingPolicy (Kotlin-движок, решения передаются в Go)
          ├── slot-primary  (SOCKS 127.0.0.1:1370: WARP/MASQUE/VLESS)
          ├── slot-secondary(HTTP/SOCKS 127.0.0.1:1371: Opera/Tor/импорт)
          ├── slot-relay    (127.0.0.1:1372: Telegram)
          ├── slot-direct   (protect()-сокеты)
          └── DNS-политика (DoH/DoT/plain, кэш, AI-домены)
```

- **nova-router** — ключевой новый компонент: принимает TUN-пакеты, определяет
  домен/IP, спрашивает политику, отдаёт соединение нужному слоту. Это замена
  PAC, которого на Android нет. tun2proxy остаётся только как транспорт одного
  слоя; для мультислота нужен свой маршрутизатор.
- **RoutingPolicy** — чистый Kotlin-модуль без Android API, тестируется JVM.
- **SlotRegistry/Health** — состояние слотов, RTT, страна выхода, backoff.
- **ListRepository** — чтение списков (read-only из стоковых источников; ничего
  не публикуем и не меняем источники профилей).

## 4. Фазы

### Phase 0 — Guardrails и baseline (2–3 дня)

- Зафиксировать `1.34.0/164` как baseline; feature flag `PARITY_V2`.
- Матрица устройств: реальный ARM (RMX3370), AVD x86_64 (только UI).
- Метрики: страна выхода, RTT, доля ошибок, батарея, размер APK.
- Acceptance: baseline-прогон ChatGPT/Gemini/YouTube/Telegram записан в
  `docs/fork/VERIFICATION.md`; известные ограничения зафиксированы.

### Phase 1 — RoutingPolicy (1–2 недели)

- `RoutingPolicy.kt`, `RouteRule.kt`, `RouteDecision.kt` — порядок правил PC,
  но входы Android: package, host, IP, страна слота.
- Правила: localhost/IP/plain, user_direct, user_ru/eu, exclude, локальные TLD,
  app-режимы, AI, eu, youtube, ru, discord/telegram/whatsapp, Cloudflare-IP,
  foreign_default, killswitch.
- `ListRepository` + парсеры списков; кэш и версия.
- Тесты: таблица «вход → маршрут» на 50+ кейсов, включая границы доменов.
- Acceptance: движок даёт те же решения, что PAC PC на одинаковых входах.

### Phase 2 — Мультислот и data-plane (3–4 недели)

- `nova-router` (Go): TUN → policy → слот; TCP и UDP (кроме QUIC-политики).
- `SlotRegistry`: primary/secondary/relay/direct, запуск и остановка.
- `SlotHealth`: RTT, ошибки, backoff, `last_resort`.
- `EgressCountryProbe`: страна выхода по IP, кэш 10 минут.
- Интеграция в `NovaVpnService`: вместо одного backend — координатор слотов.
- Acceptance: одновременно живут WARP и Opera; RU идёт в primary, EU — в
  secondary; падение primary переключает только затронутые соединения.

### Phase 3 — Списки и политики (2 недели)

- Порядок правил PC целиком, `foreign_default`, killswitch.
- Профильные списки: youtube/telegram/whatsapp/discord/games.
- Per-app overrides: приложение → слот, не ломая `selectedApps`.
- Acceptance: таблица маршрутов из `docs/agents/routing.md` воспроизводится
  на Android; утечек нет (проверка по IP/стране).

### Phase 4 — Сервисные профили (2–3 недели)

- Telegram-релей `nova-tgrelay` (Go, WSS/CF) на 127.0.0.1:1372; pinned egress,
  запрет `opera-http` для MTProto.
- Proton-варианты (`-p2/-p3`, порты 443/1194/4500).
- VLESS pre-flight (TCP 2 с, 8 потоков) до записи профиля.
- Opera backoff `60·2^n` до 30 минут, discover раньше ленты.
- Acceptance: Telegram без 502; ChatGPT/Gemini через Opera; YouTube играет.

### Phase 5 — DNS-политика (2 недели)

- `DnsPolicy`: домен → резолвер (AI-домены → DoH-разблокировка, остальные —
  обычный каскад).
- NRPT-аналог на Android: точечные правила по домену/приложению.
- Кэш (уже есть) + negative TTL + защита от утечек при смене сети.
- Acceptance: AI-домены открываются без смены DNS вручную; утечек нет.

### Phase 6 — Обучение стратегий (3–5 недель, R&D)

- Портировать `nova-learn` (Томпсон, CUSUM) и `nova-probe` на Android:
  отдельный Rust/Go-модуль или JNI к `nova-rs`.
- Кандидаты: WARP-профили, MASQUE-эндпоинты, ZAPRET-параметры, VLESS-узлы.
- CUSUM фиксирует смену режима; Томпсон выбирает; `--dry-run`-аналог отсеивает
  невалидные стратегии до применения.
- Acceptance: на реальной сети доля успешных подключений растёт, замеры в
  `docs/fork/VERIFICATION.md`; откат флагом.

### Phase 7 — UI и диагностика (2–3 недели)

- Экран «Маршруты»: слоты, страна, RTT, правила, per-app.
- Панель стратегий: текущая, кандидаты, история, ручной выбор.
- Диагностика: снимок состояния, экспорт логов, проверка утечек.
- Acceptance: пользователь видит, куда идёт каждый тип трафика, и может
  переопределить, не заходя в код.

### Phase 8 — Стабилизация и релиз (1–2 недели)

- R8, keep-правила JNI/Go/Xray, ABI arm64 + armv7.
- Батарея: 30–45 fps для Matrix, Doze, always-on.
- Версия: 2.0.0. Relay key: `nova-android-164` привязан к versionCode 164 —
  до получения нового ключа выпускать personal-сборку с
  `NOVA_ALLOW_UNPUBLISHABLE_BUILD=1` и не публиковать.
- Acceptance: release ставится, 24 ч без падений, тесты зелёные.

## 5. Файлы (ориентир)

| Задача | Файлы |
|---|---|
| Политика | новые `RoutingPolicy.kt`, `RouteRule.kt`, `RouteDecision.kt`, `ListRepository.kt` |
| Слоты | новые `SlotRegistry.kt`, `SlotHealth.kt`, `EgressCountryProbe.kt`, `nova-router/` |
| Сервис | `NovaVpnService.kt` (разбить на координатор + фазы), `ConnectionSelectorPolicy.kt`, `RegionTransportPolicy.kt` |
| DNS | `LocalDnsProxyManager.kt`, `DnsResponseCache.kt`, `PriorityDns.kt` |
| Telegram | `nova-tgrelay/` (Go), маршрут в политике |
| UI | новые `RoutingSettingsActivity`, `StrategyPanelActivity`, `SlotStatusView` |
| Тесты | `app/src/test/...`, Go-тесты `nova-router/`, `nova-tgrelay/` |

## 6. Что на Android невозможно или опасно

- **WinDivert/WFP, winws, PAC для всех приложений** — нет; аналог только через
  VpnService + userspace-роутер.
- **Raw-сокеты, подмена seq/TTL** — только root; ZAPRET остаётся эвристикой.
- **Прозрачный TLS-терминатор** — ломает проверку сертификатов; не делать.
- **Перехват конкретных процессов без VPN** — нет; только per-app allow/deny.
- **Один в один UI PC** — не цель; цель — та же логика решений.

## 7. Риски

| Риск | Митигация |
|---|---|
| Переписывание `NovaVpnService` ломает существующие транспорты | feature flag, поэтапно, старые ветки сохраняются |
| Мультислот жрёт батарею | ленивый старт secondary, health раз в 30 с, Doze |
| Ошибки политики → утечка трафика | табличные тесты, проверка IP/страны на устройстве |
| Telegram-релей на Android сложен | отдельный процесс, как opera-proxy; отключаемо |
| Обучение стратегий непредсказуемо | dry-run, лимиты, ручной откат |
| Relay key/подпись | personal-сборка, не публиковать |

## 8. Порядок и оценка

0 → 1 → 2 — фундамент (6–8 недель). Далее 3/4/5 можно вести параллельно
(4–6 недель). Обучение и UI — после стабильного мультислота (5–8 недель).
Итого до полноценного parity — **3–4 месяца** при работе одним агентом.

## 9. Definition of Done

1. На устройстве одновременно работают primary и secondary; RU/EU/YouTube/
   Telegram/AI идут по своим маршрутам, как в PC PAC.
2. Есть страна выхода и failover; killswitch и foreign_default работают.
3. Telegram-релей и Proton-варианты включены; VLESS pre-flight отсеивает мёртвые.
4. DNS-политика разблокирует AI-домены без ручных действий.
5. Тесты политики и устройства зелёные; 24 ч без падений; утечек нет.
6. Документация: этапный CHANGELOG, VERIFICATION, ARCHITECTURE обновлены.
