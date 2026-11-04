package main

import (
	"context"
	"errors"
	"net"
	"strconv"
	"sync"
	"time"
)

// Резолвер движка не должен зависеть от системного DNS процесса `:vpn`:
// в MULTI он отдаёт адрес из виртуального DNS tun2proxy, а прямой сокет
// `libnova-router.so` живёт вне TUN и системного `resolv.conf` на Android
// попросту нет. Прямое соединение по имени падало за миллисекунды, хотя по
// IP тот же хост отвечал: `ya.ru` → 000, `77.88.55.88` → 406.
//
// Имена разрешаем через публичный DNS по TCP — тот же путь, что уже работает
// в `nova-desync` на этой же сети.
var engineResolver = &net.Resolver{
	PreferGo: true,
	Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
		dialer := net.Dialer{Timeout: 3 * time.Second}
		var lastErr error
		for _, server := range []string{"1.1.1.1:53", "8.8.8.8:53", "9.9.9.9:53"} {
			conn, err := dialer.DialContext(ctx, "tcp", server)
			if err == nil {
				return conn, nil
			}
			lastErr = err
		}
		if lastErr == nil {
			lastErr = errors.New("нет доступного DNS по TCP")
		}
		return nil, lastErr
	},
}

const (
	dnsCacheTTL     = 5 * time.Minute
	dnsLookupBudget = 5 * time.Second
)

type dnsEntry struct {
	ips     []net.IP
	expires time.Time
}

var dnsCache = struct {
	sync.Mutex
	entries map[string]dnsEntry
}{entries: make(map[string]dnsEntry)}

// resolveHost отдаёт адреса хоста. IP-литерал возвращается как есть.
// Результат кэшируется на dnsCacheTTL: приложение на каждое соединение
// спрашивает одно и то же имя, и поход в DNS на каждый SYN — лишняя задержка.
func resolveHost(host string) ([]net.IP, error) {
	if ip := net.ParseIP(host); ip != nil {
		return []net.IP{ip}, nil
	}

	now := time.Now()
	dnsCache.Lock()
	if entry, ok := dnsCache.entries[host]; ok && now.Before(entry.expires) {
		ips := entry.ips
		dnsCache.Unlock()
		return ips, nil
	}
	dnsCache.Unlock()

	ctx, cancel := context.WithTimeout(context.Background(), dnsLookupBudget)
	defer cancel()
	addresses, err := engineResolver.LookupIPAddr(ctx, host)
	if err != nil {
		return nil, err
	}
	ips := make([]net.IP, 0, len(addresses))
	for _, address := range addresses {
		ips = append(ips, address.IP)
	}
	if len(ips) == 0 {
		return nil, errors.New("DNS не вернул адресов")
	}

	dnsCache.Lock()
	dnsCache.entries[host] = dnsEntry{ips: ips, expires: now.Add(dnsCacheTTL)}
	dnsCache.Unlock()
	return ips, nil
}

// dialDirect соединяется по имени через собственный резолвер. Для IP-литерала
// резолвер не нужен и поведение прежнее.
func dialDirect(host string, port int, timeout time.Duration) (net.Conn, error) {
	ips, err := resolveHost(host)
	if err != nil {
		return nil, err
	}
	var lastErr error
	for _, ip := range preferIPv4(ips) {
		address := net.JoinHostPort(ip.String(), strconv.Itoa(port))
		conn, dialErr := net.DialTimeout("tcp", address, timeout)
		if dialErr == nil {
			return conn, nil
		}
		lastErr = dialErr
	}
	if lastErr == nil {
		lastErr = errors.New("нет адресов для соединения")
	}
	return nil, lastErr
}

// preferIPv4 ставит IPv4 вперёд: на мобильных сетях IPv6 нередко объявлен,
// но фактически не проходит, и попытка только замедляет отказ.
func preferIPv4(ips []net.IP) []net.IP {
	out := make([]net.IP, 0, len(ips))
	for _, ip := range ips {
		if ip.To4() != nil {
			out = append(out, ip)
		}
	}
	for _, ip := range ips {
		if ip.To4() == nil {
			out = append(out, ip)
		}
	}
	return out
}
