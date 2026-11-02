# Архитектура Nova Android (личный форк)

> Карта для человека и следующего агента. Обновляется вместе с кодом.
> Версия на момент составления: `1.34.0` / `versionCode 164`.

## 1. Верхний уровень

```
MainActivity / SettingsActivity / специализированные Activity
        │
        ├── ClientData  ── SharedPreferences + AtomicFile (состояние, профили, флаги)
        ├── NovaVpnService (:vpn) ── VpnService + TUN + выбор транспорта
        │       ├── nova-core AAR (Go: AmneziaWG/WARP, MASQUE, DNS, domain bypass)
        │       ├── nova-xray AAR/JNI (VLESS/REALITY)
        │       ├── tun2proxy / opera-proxy (дочерние процессы)
        │       └── Tor (tor-android, obfs4/snowflake)
        └── UI-слой: темы, фон, графики, логи
```

`NovaVpnService` — самый большой и связанный класс; изменения в нём делать
точечно, по существующим методам, а не переписывать целиком.

## 2. Запуск и экраны

| Файл | Роль |
|---|---|
| `app/src/main/AndroidManifest.xml` | Activity/Service/Receiver, `NovaVpnService` в процессе `:vpn`, foreground service |
| `MainActivity.kt` | Главный экран: статус, подключение, dock выбора узла (`DockRadioButton`/`FlowRadioGroup.dockMode`, SVG-иконки), плитка функций узла, верхняя плашка выбора (`tv_top_notice`), фон, график задержки, плашка обновления (в форке скрыта); системные insets |
| `SettingsActivity.kt` | Настройки: транспорт, DNS, домены, темы, автозапуск, обновления (в форке скрыты) |
| `ConnectionSelectorPolicy.kt` | Единый порядок и идентификаторы кнопок/чипов транспорта |
| `RegionTransportPolicy.kt` | Допустимые транспорты для региона/режима |
| `ImportedSourcePolicy.kt` | Выбор встроенных vs импортированных профилей |
| `NovaTunnelControl.kt` | Команды управления службой из UI |
| `NovaBootReceiver.kt` | Автозапуск/восстановление после перезагрузки |
| `NovaTileService.kt` / `NovaWidgetProvider.kt` | Quick Settings и виджет |
| `AlwaysOnVpn.kt` | Чтение системного always-on VPN и переход в `Settings.ACTION_VPN_SETTINGS` |

## 3. Транспорты и профили

| Область | Ключевые файлы | Примечание |
|---|---|---|
| WARP / AmneziaWG | `WarpClient.kt`, `WarpProfileGenerator.kt`, `WarpGeneratedStore.kt`, `WarpConfigsActivity.kt` | Регистрация Cloudflare, генерация AWG, проверка конфигов |
| MASQUE | `MasqueStartPolicy.kt`, `ExitAddress*.kt`, `ExitColoPolicy.kt`, `NovaVpnService` | HTTP/3 через Go-ядро |
| Opera VPN | `OperaProxyManager.kt`, `OperaEndpointFeed.kt`, `OperaApiRelayBridge.kt` | Внешний `opera-proxy`, API-релей |
| Proton | `ProtonApi.kt`, `ProtonProfileManager.kt`, `ProtonProfileStore.kt`, `ProtonRace.kt`, `ProtonLatency.kt`, `ProtonRelay.kt`, `ProtonDoh.kt` | Бесплатные профили; **источник не менять** |
| VLESS/REALITY | `VlessConfig.kt`, `VlessSubscription*.kt`, `VlessXrayConfig.kt`, `XrayBridge.kt` | Xray-core отдельным AAR/JNI |
| Tor | `TorTransport.kt`, `TorBridges.kt` | `tor-android`, мосты |
| DNS-туннель | `DnsTunnelProcess.kt`, `DnsTunnelEndpoint.kt`, `DnsProfileStore.kt`, `StormDnsConfig.kt` | Отдельный медленный транспорт |
| Локальный прокси | `LocalAppProxyManager.kt`, `LocalProxyActivity.kt` | Раздача соседним приложениям |

Порядок перебора задают `ProfileRotation.kt` (порядок/понижение/повышение),
`ConnectionSelectorPolicy.kt` (транспорт) и сортировки в `ClientData.kt`
(качество, задержка, успехи/отказы). `LiveNodeRotation.kt` **намеренно выключен**
(`MAX_ROTATIONS_PER_SESSION = 0`) по замерам: не включать без новых данных.

## 4. Маршрутизация и DNS

- `NovaVpnService.establish()` строит TUN, маршруты и split-tunnel.
- `DirectAppsPolicy.kt` + `RussianDirectApps.kt` — закрытый список российских
  приложений, которые всегда идут напрямую.
- `DomainBypassRules.kt` — разбор зон/доменов, которые обходят туннель.
- `nova-core/dnsname/zone.go` — та же семантика в Go; решения принимаются в ядре.
- `NovaVpnService.applyCoreDomainBypassPolicy()` передаёт зоны/домены в
  `Nova.setDomainBypassPolicy(...)`; `domainBypassAddresses()` резолвит домены
  для выреза маршрута.
- `LocalDnsPolicy.kt` — выбор direct/proxy для DNS и медиа-приложений.
- `LocalDnsProxyManager.kt` — локальный UDP DNS на `127.0.0.1:53`; upstream
  plain/DoH/DoT, failover по upstream.
- `DnsResponseCache.kt` — TTL-кэш ответов (новый слой форка).
- `DnsRules.kt`, `PriorityDns.kt`, `TrustedDns.kt`, `DnsProbe.kt`,
  `DnsServerFeed.kt` — правила, приоритеты, проверки и фиды.
- Ограничение Android: обход по зонам в Go-ядре работает на WARP/MASQUE и TCP
  80/443; Opera/VLESS отдают TUN в tun2proxy. Это записано в логах и UI.

## 5. Облачные профили (НЕ ТРОГАТЬ)

Стоковые источники и логика:

- `ProtonProfileManager.kt`, `ProtonProfileStore.kt`
- `VlessSubscription.kt`, `VlessSubscriptionFetcher.kt`,
  `VlessSubscriptionManager.kt`, `VlessSubscriptionWorker.kt`
- `WarpProfileGenerator.kt`, `WarpGeneratedStore.kt`
- `GatewayEndpoints.kt`, `GatewayPortal.kt`
- `OperaEndpointFeed.kt` (фид Opera, не апдейтер APK)

Их URL, заголовки, форматы и расписание не меняются. Официальный апдейтер APK
(`AppUpdateManager`, `UpdateCheckWorker`, `UpdateActionReceiver`,
`notification_update_ready.xml`) — отдельная подсистема; в форке выключен флагом
`BuildConfig.UPDATER_ENABLED = false`.

## 6. Оформление и фон

- `NovaTheme.kt` — 12 палитр, `DEFAULT_KEY = "matrix"` в форке; `ThemeSettingsActivity`.
- `NovaAppearance.kt`, `NovaAppearanceDrawable.kt`, `NovaTexturePatterns.kt` —
  предпросмотр и фактуры.
- `MainBackgroundPolicy.kt` — `image` / `animation` / `none`, проверка слабого устройства.
- `NovaMainBackdrop.kt`, `BackdropRevealImageView.kt` — фактура главного экрана.
- `NovaNetworkBackgroundView.kt` — созвездие для остальных тем.
- `MatrixRainView.kt` — цифровой дождь Matrix (Canvas, 30 fps, static fallback).

## 7. Логи, состояние, диагностика

- `ClientData.kt` — центральное хранилище: SharedPreferences, JSON/AtomicFile,
  профили, статистика, флаги, состояние службы.
- `LogManager.kt` — журнал с обезличиванием.
- `DiagnosticLogSanitizer.kt`, `DiagnosticLogNoise.kt`, `DiagnosticSnapshot.kt` —
  диагностика без секретов.
- `AppCacheManager.kt` — кэш списка приложений для split-tunnel.

## 8. Сборка

- `app/build.gradle.kts`: два flavor (`github`, `fdroid`), release R8 +
  `shrinkResources`, `useLegacyPackaging = true`; `UPDATER_ENABLED` теперь false
  у обоих.
- `app/libs/nova-core-api24-stripped.aar` — готовое Go-ядро; пересобирать только
  при изменении Go (`tools/build_nova_core_aar.sh`).
- `nova-xray/` — отдельное ядро Xray; `cd nova-xray && ./build.sh`.
- `keystore.properties` и `local.properties` — локальные, не коммитить.
- Release без стокового ключа релея: `NOVA_ALLOW_UNPUBLISHABLE_BUILD=1`
  (личный форк), см. `docs/BUILD.md`.

## 9. Поток данных (упрощённо)

```
пользователь → MainActivity → NovaTunnelControl → NovaVpnService
    → выбор транспорта (ConnectionSelectorPolicy / ClientData)
    → WARP/MASQUE (Go) | Opera (process) | Proton | VLESS (Xray) | Tor | DNS
    → TUN → приложения
DNS: приложение → LocalDnsProxyManager → DoH/DoT/plain → DnsResponseCache
Домены: DNS-ответ → nova-core/dnsname → domain bypass → прямой сокет/маршрут
```

## 10. Правила для следующего агента

1. Не менять облачные источники профилей (раздел 5).
2. Не включать `LiveNodeRotation` без новых замеров.
3. Не трогать `panic = "unwind"` и нативные ABI без пересборки.
4. Любая правка `NovaVpnService` — точечная, с логом и проверкой на эмуляторе.
5. Секреты и keystore — только локально.
6. После изменений: `gradlew test`, `assembleGithubDebug`, проверка на AVD,
   затем release R8. Журнал — `docs/fork/CHANGELOG.md`.
