package main

import (
	"context"
	"errors"
	"net"
	"time"
)

// Резолвер движка не должен зависеть от системного DNS процесса `:vpn`:
// в MULTI он отдаёт адрес из виртуального DNS tun2proxy, и прямое соединение
// по нему невозможно (проверено: `www.gstatic.com` → отказ, `1.1.1.1` → OK).
// Поэтому имена разрешаем через публичный DNS по TCP — этот путь уже проверен
// как рабочий на той же сети.
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

func dialTarget(network, address string) (net.Conn, error) {
	dialer := net.Dialer{Timeout: 10 * time.Second, Resolver: engineResolver}
	conn, err := dialer.Dial(network, address)
	if err == nil {
		return conn, nil
	}
	return net.DialTimeout(network, address, 10*time.Second)
}

func resolveHost(host string) ([]net.IP, error) {
	if ip := net.ParseIP(host); ip != nil {
		return []net.IP{ip}, nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	addresses, err := engineResolver.LookupIPAddr(ctx, host)
	if err != nil {
		return nil, err
	}
	out := make([]net.IP, 0, len(addresses))
	for _, address := range addresses {
		out = append(out, address.IP)
	}
	return out, nil
}
