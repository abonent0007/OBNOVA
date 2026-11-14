# P-E.1 baseline — IPv6 / data plane

> Снято 2026-11-14 на устройстве `97db202c` (RMX3370, Android). Версия
> `1.34.11/175`. Это исходные данные **до** P-E.2A; не смешивать с
> результатами после изменений.
>
> **Обновление P-E.2A (2026-11-14):** пункт «WARP IPv6 selected-app FAIL»
> в этом файле — исторический baseline. Контролируемый прогон со счётчиками
> TUN + WireGuard bind и чистая release-сборка дали PASS на всём пути
> (Firefox отрисовал WARP IPv6 `2a09:bac5:...`). Ранний FAIL не
> воспроизвёлся и совпал с ретраями handshake WARP (вероятная причина
> временной нестабильности сессии, не доказанная первопричина). Актуальная
> классификация — `docs/fork/VERIFICATION.md`, раздел P-E.2A controlled.

## 1. Физическая сеть LTE (VPN OFF)

```text
ip -6 addr:
  lo        ::1/128
  rmnet_data0 fe80::1f04:e80b:7c05:4a8f/64 scope link
  r_rmnet_data0 fe80::8331:2c0a:ee00:43d7/64 scope link
  (global IPv6 address отсутствует)

ip -6 route:
  <пусто>

IPv4 egress (curl -4 ipv4.icanhazip.com): 92.36.1.97
IPv6 egress (curl -6 ipv6.icanhazip.com): FAIL
  Trying 2606:4700::6810:b8f1:443...
  Immediate connect fail: Network is unreachable
```

**Вывод:** у Tele2 LTE в этой сессии нет глобального IPv6. AAAA-ответ
получен, но IPv6-маршрута нет. `NO BASELINE` для физического IPv6.

## 2. Физическая сеть Wi-Fi (VPN OFF)

```text
wlan0: 192.168.31.135/24
ip -6 addr:
  wlan0 fe80::18d4:3aff:febd:24e5/64 scope link
  (global IPv6 address отсутствует)

ip -6 route:
  <пусто>

IPv4 egress (curl -4 ipv4.icanhazip.com): 194.34.99.100
IPv6 egress (curl -6 ipv6.icanhazip.com): FAIL (Network is unreachable)
```

**Вывод:** у Wi-Fi `EdelveyS` в этой сессии нет глобального IPv6.
`NO BASELINE` для физического IPv6.

## 3. MULTI (VPN ON, LTE)

```text
tun0: mtu 1420
  inet6 fe80::5194:abe0:6471:868f/64 scope link
  (global IPv6 address отсутствует, ::/0 в TUN нет)

ip -6 route show table 1170:
  fe80::/64 dev tun0
  unreachable default dev lo

ip -6 rule:
  12000: from all iif tun0 lookup 97
  13000: from all fwmark ... uidrange <selected apps> lookup 1170
  ...
```

Для UID, попавших в VPN, IPv6-таблица 1170 содержит
`unreachable default` → IPv6 должен завершаться DROP, а не уходить
физически. Это **fail-closed по таблице маршрутов**.

Selected UID: **10155 = com.google.android.googlequicksearchbox**, не Chrome.
Chrome имеет UID **10168** и в VPN allow-list не входит — его
`ERR_ADDRESS_UNREACHABLE` относится к физическому пути, не к VPN.
Для UID 10155 в MULTI:

```text
ip -6 rule: uidrange 10155 lookup 1175
ip -6 route table 1175: unreachable default dev lo
```

То есть выбранное приложение (Google) получает route-level DROP, но
controlled HTTP-запрос от него не выполнялся. MULTI selected-app:
**route-level fail-closed; runtime NOT TESTED**.

```text
IPv4 TCP: Telegram 200, YouTube 200
SOCKS5 IPv4 (curl --socks5-hostname 127.0.0.1:1373 ipv4.icanhazip.com):
  104.28.255.123
SOCKS5 IPv6 (curl -6 --socks5-hostname ... ipv6.icanhazip.com):
  FAIL (Couldn't connect to server)
```

## 3b. WARP (VPN ON, LTE) — отдельно

```text
tun0: mtu 1280
  inet6 2606:4700:110:812c:a554:b442:26d4:1330/128 scope global
  inet6 fe80::df5c:ea34:6f34:23e2/64 scope link

ip -6 route table 1171:
  2606:4700:110:812c:a554:b442:26d4:1330 dev tun0
  fe80::/64 dev tun0
  default dev tun0
```

WARP имеет IPv6-адрес и `default` на tun0. UI-наблюдение WARP:
`2a09:bac5:51b3::***` (IPv6), `country=RU` — это ответ Cloudflare trace,
полученный приложением, но не контролируемый endpoint.

Фактическая передача IPv6 selected-app не подтверждена:

```text
curl -6 --interface tun0 https://ipv6.icanhazip.com -> Couldn't connect
curl -4 --interface tun0 https://ipv4.icanhazip.com -> timeout
Chrome (UID 10168, не в VPN) -> IPv6 literal -> ERR_ADDRESS_UNREACHABLE
```

`--interface tun0` — не эквивалент выбранного VPN-приложения (UID shell не
входит в allow-list). Chrome также не входит в allow-list (UID 10168), поэтому
его ошибка не является selected-app тестом.

Для фактически выбранного UID 10155 (Google app) в WARP:

```text
ip -6 rule: uidrange 10155 lookup 1176
ip -6 route table 1176:
  default dev tun0
```

IPv6-маршрут для выбранного UID есть, но controlled HTTP-запрос от него не
выполнялся. UI trace `2a09:bac5:...` относится к `:vpn`/table 1171, не к
UID 10155. WARP IPv6 selected-app: **NOT TESTED** (route present, runtime
не подтверждён); `FAIL` не заявляю.

Дополнительные selected-app попытки:

- Firefox UID 10918 выбран; IPv4 через Firefox: `104.28.198.244` (WARP
  egress) — selected-app IPv4 PASS. `ip -6 rule` → table 1182 с
  `default dev tun0`.
- IPv6 literal `https://[2606:4700:4700::1111]/...` использовать нельзя:
  `2606:4700:4700::1111` — DNS-резолвер Cloudflare 1.1.1.1, а не обычный
  HTTP trace endpoint. Результат литерала — **NOT TESTED**, не FAIL.
- Корректный endpoint: `https://ipv6.icanhazip.com` — AAAA-only
  (`2606:4700::6810:b8f1`, `...:b9f1`), A-записи нет (SOA), TLS валиден.
  Firefox UID 10918 через WARP: IPv4 PASS, IPv6 HTTPS —
  `Соединение не удалось` (Firefox error 131), IPv6-ответа и
  `client_ip_seen_by_server` нет. UID/route/table подтверждены.
  WARP IPv6 selected-app: **FAIL (connectivity)** по корректному
  AAAA-only endpoint; физического IPv6-выхода не наблюдалось.
- Chrome UID 10168 добавлен в allow-list; `ip -6 rule` → table 1178,
  `table 1178` содержит `default dev tun0`. Но Chrome всё равно
  `ERR_ADDRESS_UNREACHABLE`: его сетевой sandbox-процесс работает под UID
  900xx, для которых правила 10168 не действуют. Это не валидный
  selected-app тест.
- Временный diagnostic app `com.example.nova.ipv6diag` (UID 10324) собран и
  установлен, но не появился в списке split-apps (кэш/обновление списка);
  в VPN не включён, результат не используется.

## 4. DNS

- A-запросы работают (`curl -4`, Telegram/YouTube).
- AAAA-ответы приходят (`ipv6.icanhazip.com` → `2606:4700::6810:b8f1`,
  `2606:4700::6810:b9f1`), но IPv6-соединение невозможно:
  `Network is unreachable`.
- UDP/53 в `nova-router` не наблюдался (tun2proxy virtual DNS); это
  зафиксировано в P-C.1.
- DoH/DoT QNAME не проверялись в этом baseline; ограничение P-C.

## 5. `client_ip_seen_by_server`

| Канал | IPv4, который видит сервер | IPv6 |
|---|---|---|
| LTE physical | `92.36.1.97` | нет |
| Wi-Fi physical | `194.34.99.100` | нет |
| MULTI через SOCKS5 | `104.28.255.123` (WARP) | нет |

## 6. Ограничения baseline

- Нет root/tcpdump: packet-level capture IPv6 недоступен.
- `curl` в `adb shell` работает от UID shell, который не входит в список
  выбранных VPN-приложений; это проверка системного/физического пути, а не
  TUN выбранного приложения.
- Физического IPv6 нет ни на LTE, ни на Wi-Fi → нельзя доказать отсутствие
  утечки на IPv6-сети; фиксируется `NO BASELINE`, не PASS.
- WARP/MASQUE/PROTON отдельно не проверялись в этом baseline.
- Kill Switch сценарии не проверялись (P-E.2B).
- `SOCKS5 REP=0x05` — стандартный `Connection refused`; в текущем router это
  отказ при отсутствии подходящего слота (`writeReply(client, 0x05)`), а не
  IPv6-ошибка. Для диагностики сохранять полный текст ошибки и контекст слота.

## 7. Статус baseline

- IPv6 физический: **NO BASELINE** (нет глобального IPv6 у LTE и Wi-Fi).
- IPv6 MULTI: **ROUTE-LEVEL FAIL-CLOSED** — выбранный UID 10155
  (Google app) lookup table 1175 с `unreachable default`; selected-app
  runtime — NOT TESTED; packet-level — NOT TESTED.
- IPv6 WARP: tun0 IPv6/table 1171 есть; UID 10155 lookup 1176 с
  `default dev tun0`, runtime NOT TESTED. Firefox UID 10918 (selected,
  table 1182 `default dev tun0`): IPv4 PASS (`104.28.198.244`), корректный
  AAAA-only `ipv6.icanhazip.com` — HTTPS FAIL (connectivity), IPv6-ответа
  нет. WARP IPv6 selected-app **FAIL (no transmission)** по корректному
  endpoint; физического IPv6-выхода не наблюдалось. Literal
  `2606:4700:4700::1111` — DNS-resolver, тест невалиден (NOT TESTED). Chrome UID 10168 sandbox 900xx — тест невалиден; diagnostic
  app UID 10324 не выбран в split-list.
- UI trace `2a09:bac5:...` относится к `:vpn`/table 1171, не к UID 10155;
  PASS по нему не заявляю.
- IPv4: PASS (Telegram/YouTube, SOCKS5 egress).
- P-E.2A: **diff для MULTI/OPERA/VLESS не требуется** по результатам
  baseline. Добавление `::/0` приостановлено; сначала selected-app runtime
  на сети с глобальным IPv6 и проверка WARP IPv6-транспорта.
- Kill Switch: P-E.2B, не начат.

## 8. Решение по P-E.2A (2026-11-14)

- Текущий MULTI уже не отправляет IPv6 выбранных UID в физическую сеть:
  таблица 1170 `unreachable default`.
- Это route-level доказательство; packet-level и selected-app runtime
  остаются NOT TESTED.
- `NovaVpnService.kt` не менялся; `tun2proxy`/`nova-router` не менялись.
- WARP: наличие IPv6-адреса и default route не считается доказательством
  передачи; нужен selected-app тест через WARP.
- OPERA/VLESS: проверить UID rule/table 1170 (или аналог) отдельно.

