# P-E.2B.3.1 — аудит исключения Nova и protect()

> Только анализ существующего кода; production не менялся. Версия
> `1.34.11/175`. Снято 2026-11-14.

## 1. Что означает текущее исключение

`VpnService.Builder.addDisallowedApplication(packageName)` исключает из VPN
**весь UID приложения** (10997), а не отдельные сокеты. `VpnService.protect(fd)`
действует на **конкретный файловый дескриптор** в процессе, который его вызвал.
Поэтому заменить одно на другое можно только там, где для каждого сокета есть
fd и callback в Java.

## 2. Матрица по компонентам

| Компонент | Процесс / UID | Кто создаёт сокеты | protect() | Результат проверяется | Если не защищён |
|---|---|---|---|---|---|
| `NovaVpnService` Java | `:vpn`, 10997 | HTTP-профили, обновления, DNS-пробы, ExitAddress, Opera bootstrap | частично: `DnsProbe` принимает `protector`, `ExitAddressV4.overNetwork` биндится к underlay; `WarpClient.registerThroughActiveTunnel` намеренно без protect | `DnsProbe` — да; остальные — нет | Трафик уйдёт в собственный TUN; для внешних запросов это не утечка, но ломает проверки egress/регистрацию |
| UI-процесс | `:main`, 10997 | обновления, фиды, настройки | нет | — | Попадёт в VPN (не обход); при недоступности VPN — блокируется |
| `nova-router` | отдельный child-процесс, тот же UID 10997 | primary/secondary/spoof/cfproxy/awg — loopback; `ws` (Telegram relay) — внешний, если `via` пуст; `direct` — внешний (в Strict отсутствует) | **нет механизма** | — | Внешние сокеты router уходят в TUN → tun2proxy → router: петля/отказ. `direct` в Strict не создаётся, но `ws` без `via` остаётся |
| `opera-proxy` | child-процесс (`ProcessBuilder`), UID 10997 | регистрация/API, upstream Opera, relay | **нет** | — | Регистрация и данные замыкаются в собственный TUN |
| tun2proxy | in-process Rust (`libtun2proxy.so` + JNI) | SOCKS5 к `127.0.0.1:$routerPort`; виртуальный DNS | **нет callback в `tun2proxy.h`** (только log/traffic callbacks) | — | Loopback обычно идёт через `local` table, но гарантии нет; код и комментарий в `NovaVpnService` считают protect обязательным |
| Xray (VLESS) | in-process Go (`libnovaxrayjni.so`) | TCP-диалеры Xray | **есть**: `internet.RegisterDialerController` → JNI `VpnService.protect(fd)` | **нет** (`C.nova_invoke_protect` игнорирует результат) | При неудаче protect сокет уйдёт в TUN; отказа нет |
| Go-core SmartBind (WARP/AWG) | in-process Go | UDP bind/транспорт WireGuard | **есть**: `GlobalProtector` → `VpnService.protect` | Логируется `protect_result`, но **не прерывает** открытие | При `false` транспортный сокет уйдёт в TUN, VPN зациклится |
| Go-core DNS DoH | in-process Go | DoH/DoT bootstrap | **есть**: `protectRawConn` | **да**: `protect returned false` → ошибка | Fail-closed |
| MASQUE | in-process Go | UDP/QUIC | **есть**: `bindSocket` к underlying network, fallback `protect` | Да (fallback) | — |
| Desync / DNS-tunnel | child-процессы | внешние SOCKS/DNS | **нет** | — | Не используются в Strict MULTI, но архитектурно та же проблема |

## 3. Ключевые факты

1. **Router и Opera — отдельные процессы с тем же UID.** Java не может вызвать
   `protect()` для их сокетов: fd принадлежит другому процессу, а `protect`
   не является межпроцессным API. `RouterProcess.launch` и
   `OperaProxyManager.startOperaProcess` используют `ProcessBuilder`.
2. **tun2proxy не предоставляет protect-callback.** `app/src/main/cpp/include/
   tun2proxy.h` объявляет только `tun2proxy_set_log_callback` и
   `tun2proxy_set_traffic_status_callback`; JNI-обёртка (`native-lib.cpp`)
   передаёт только `service` для логов. Значит, его сокеты (если выйдут за
   loopback) защитить нечем.
3. **Xray и Go-core умеют protect точечно**, но Xray не проверяет результат,
   а SmartBind не делает fail-closed при `protect=false`.
4. **Java-слой защищает только часть сокетов.** `DnsProbe` принимает
   `protector`, но вызовы с `protector = null` есть (Opera bootstrap);
   `AppUpdateManager`, `FeedHttp`, `ExitAddressV4.viaProxy` и др. идут без
   protect. Это не утечка в обход VPN (они попадут в туннель), но при Strict
   без исключения пакета меняется их путь и возможны отказы проверок.
5. **`ws`-слот Telegram** в Strict остаётся: при `cfProxy` он идёт через
   loopback (`via=127.0.0.1:1378`), без него — внешним соединением из router.
   Это единственный оставшийся внешний сокет router в Strict-конфигурации.

## 4. Вывод

**Убрать `addDisallowedApplication(packageName)` сейчас нельзя без
архитектурных изменений.** Минимально блокируют:

- `liboperaproxy.so` — child-процесс без protect;
- `nova-router` — child-процесс без protect (для `ws` без `via`);
- `tun2proxy` — нет callback; безопасность loopback не доказана;
- часть Java-сокетов без protect (не утечка, но меняет поведение).

`protect()` покрывает только Go-core, MASQUE, Xray (с оговоркой) и отдельные
Java-сокеты. Это не эквивалент исключению пакета.

## 5. Варианты

| Вариант | Стоимость | Что даёт |
|---|---|---|
| Оставить исключение пакета и честно указать в UI | низкая | Работающий Strict для всех пользовательских приложений; служебный пакет — задокументированное исключение |
| Добавить protect в router/Opera/tun2proxy | высокая: Go/NDK/JNI, пересборка prebuilt-библиотек; у Opera/tun2proxy нет исходников в репозитории | Можно убрать исключение пакета, но остаются Java-сокеты |
| Вынести сетевой движок в отдельный пакет/UID | очень высокая: второй APK/сервис, подпись, доставка | Исключается только инфраструктурный UID; пользовательские приложения — в VPN |

## 6. Рекомендация

1. Не удалять строку ради формальности: она защищает работающий транспорт.
2. В UI и документации писать точную область: «весь пользовательский трафик;
   служебный пакет Nova (VPN-движок) исключён».
3. Если нужен буквально весь телефон — отдельный этап: protect-callback в
   router/Opera/tun2proxy или отдельный UID; без этого заявлять полную защиту
   нельзя.
4. До решения не включать Strict пользователям.
