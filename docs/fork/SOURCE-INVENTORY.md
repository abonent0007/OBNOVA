# Инвентаризация источников данных

> Дата: 2026-11-08. Карта всех сетевых источников приложения и того, что
> остаётся статическим намеренно. Канонический список адресов —
> `ExternalSources.kt`; таблица ниже объясняет, кто их читает и что будет,
> если источник не ответит.

## Динамические источники

| Данные | Где объявлены | Кто обновляет | Запасной путь |
|---|---|---|---|
| WARP-конфигурация (готовая личность) | `ExternalSources.WARP_CONFIG_FEED_BASES` | `WarpConfigFeed.fetch()` перебирает 6 зеркал | Своя регистрация Cloudflare (`WarpClient`), затем встроенные семена |
| Точки входа WARP | `WarpProfileGenerator` + `ClientData.getWarpVerifiedMergedConfigs()` | Сканер ядра и текущий набор проверенных/импортированных конфигов | Встроенный `warp_verified_seeds.json` (asset) |
| VLESS-подписки (6 источников) | `ExternalSources.PUBLIC_VLESS_SOURCES` | `PublicSubscriptions.sync()` с ETag/Last-Modified | Старые профили источника сохраняются; зеркала raw → githack → jsDelivr |
| Мосты Tor | `ExternalSources.TOR_BRIDGE_MIRROR_BASES`, `TOR_BRIDGE_FALLBACK_BASES` | `TorBridgeManager` (гонка зеркал, кэш 30 мин) | Встроенные snowflake-мосты и сохранённый `tor_bridges.json` |
| MOAT-мосты Tor | `ExternalSources.TOR_MOAT_SETTINGS_URL`, `TOR_MOAT_BUILTIN_URL` | `TorBridges` при сборе | Пропуск MOAT, остаются публичные сборщики |
| Профиль DNS-туннеля | `ExternalSources.DNS_TUNNEL_FEED` | `DnsServerFeed` через `updateFeedMirrors` | Встроенный профиль `DnsServerFeed.SEED` |
| Серверы Opera | `ExternalSources.OPERA_ENDPOINTS_FEED` | `OperaEndpointFeed` (кэш, повтор через 15 мин) | Встроенные endpoint-ы Opera |
| Proton API | `ExternalSources.PROTON_API_HOSTS` | `ProtonApi.call()`; альтернативный узел ищется через `ProtonDoh` | Запасные узлы из DoH, затем прежний набор профилей |
| Список узлов Proton | `https://vpn-api.proton.me/vpn/logicals` | `ProtonProfileManager` | `proton_profiles.json` + встроенные `proton_nodes.json` |
| Обновление APK | `ExternalSources.GITHUB_LATEST_RELEASE_API` | `AppUpdateManager` | Кнопка ручного скачивания `RELEASES_PAGE_URL` |
| Списки домен/IP | `ExternalSources.LIST_MANIFEST`, `DATA_REPO` | `ListRepository`/`ListSync` | Встроенные asset-копии |

## Статические данные (намеренно)

| Данные | Почему остаются статическими |
|---|---|
| `warp_verified_seeds.json` | Стоковый запасной набор; обновляется только с APK, чтобы первый запуск работал без сети |
| SNI-пулы (`SniMaskPolicy`) | Стоковые списки + пользовательский список; внешнего фида у PC-версии нет |
| TLD и direct-домены | Стоковая политика обхода; менять источник нельзя по границам форка |
| DoH-серверы по умолчанию (`DnsRules`) | Это конфигурация, а не данные: список должен быть известен до первого DNS-запроса |
| Bootstrap-адреса фидов | Нужны до первого запроса; централизованы в `ExternalSources` и проверяются тестами на HTTPS/порядок |
| Проверочные URL (`cp.cloudflare.com`, `1.1.1.1`) | Диагностические пробы, не пользовательские данные |

## Границы

- Подписки, списки и встроенные узлы остаются стоковыми (`confeden/*`).
  Репозиторий `abonent0007/OBNOVA` — только канал обновления APK.
- Новый сетевой адрес добавляется в `ExternalSources`, а не строкой в экране.
- У каждого внешнего вызова обязательны таймаут, проверка ответа и запасной
  путь; проверка — `python tools/audit_diff.py --strict`.
