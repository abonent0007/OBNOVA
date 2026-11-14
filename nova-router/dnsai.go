package main

import (
	"bytes"
	"context"
	"crypto/tls"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"
)

// DNS-AI: DoH-резолвер, который отдаёт для нейросетей собственные рабочие
// адреса. Слот dnsai резолвит имя через него и соединяется напрямую, не
// заворачивая трафик в WARP/Opera: именно в этом смысл сервиса.
const (
	dohTimeout    = 10 * time.Second
	dohCacheTTL   = 5 * time.Minute
	dohFailureTTL = 45 * time.Second
	dohMaxBody    = 16 * 1024
)

type dohCacheEntry struct {
	ips     []net.IP
	expires time.Time
	err     error
}

var dohCache = struct {
	sync.Mutex
	entries map[string]dohCacheEntry
}{entries: make(map[string]dohCacheEntry)}

func dialDNSAI(slot Slot, host string, port int) (net.Conn, error) {
	if ip := net.ParseIP(host); ip != nil {
		return net.DialTimeout("tcp", net.JoinHostPort(host, strconv.Itoa(port)), dialTimeout)
	}
	ips, err := resolveDNSAI(slot, host)
	if err != nil {
		return nil, err
	}
	relayVia := strings.TrimSpace(slot.RelayVia)
	var lastErr error
	for _, ip := range preferIPv4(ips) {
		// TCP-коннект ничего не говорит о живом TLS: у DNS-AI бывают узлы,
		// которые принимают TCP и молча роняют рукопожатие. Пробуем настоящий
		// TLS с SNI исходного сервиса и только потом отдаём соединение роутеру.
		if port == 443 && net.ParseIP(host) == nil {
			probe, dialErr := dialDNSAIRelay(slot, relayVia, ip.String(), port)
			if dialErr != nil || !probeRelayTLS(probe, host, 6*time.Second) {
				if probe != nil {
					_ = probe.Close()
				}
				lastErr = dialErr
				if lastErr == nil {
					lastErr = fmt.Errorf("TLS-проба %s через %s не прошла", host, ip)
				}
				continue
			}
			_ = probe.Close()
		}
		conn, dialErr := dialDNSAIRelay(slot, relayVia, ip.String(), port)
		if dialErr == nil {
			return conn, nil
		}
		lastErr = dialErr
	}
	if lastErr == nil {
		lastErr = errors.New("DNS-AI не вернул адресов")
	}
	return nil, lastErr
}

func dialDNSAIRelay(slot Slot, relayVia, ip string, port int) (net.Conn, error) {
	if strings.TrimSpace(relayVia) != "" {
		return dialSocks5(relayVia, ip, port)
	}
	return net.DialTimeout("tcp", net.JoinHostPort(ip, strconv.Itoa(port)), dialTimeout)
}

// probeRelayTLS проверяет именно тот TLS, который увидит клиент: SNI исходного
// сервиса, настоящий сертификат. TCP-коннект этого не показывает.
func probeRelayTLS(conn net.Conn, serverName string, timeout time.Duration) bool {
	if conn == nil {
		return false
	}
	_ = conn.SetDeadline(time.Now().Add(timeout))
	tlsConn := tls.Client(conn, &tls.Config{
		ServerName: serverName,
		MinVersion: tls.VersionTLS12,
		NextProtos: []string{"h2", "http/1.1"},
	})
	err := tlsConn.Handshake()
	_ = tlsConn.Close()
	return err == nil
}

func resolveDNSAI(slot Slot, host string) (ips []net.IP, err error) {
	key := strings.TrimSpace(slot.URL) + "|" + strings.ToLower(strings.TrimSpace(host))
	now := time.Now()
	dohCache.Lock()
	if entry, ok := dohCache.entries[key]; ok && now.Before(entry.expires) {
		cached := entry.ips
		cachedErr := entry.err
		dohCache.Unlock()
		if cachedErr != nil {
			return nil, cachedErr
		}
		return cached, nil
	}
	dohCache.Unlock()
	defer func() {
		if err != nil {
			log.Printf("DNS-AI %s: %v", host, err)
			dohCache.Lock()
			dohCache.entries[key] = dohCacheEntry{
				err:     err,
				expires: time.Now().Add(dohFailureTTL),
			}
			dohCache.Unlock()
		}
	}()

	parsed, err := url.Parse(strings.TrimSpace(slot.URL))
	if err != nil || parsed.Hostname() == "" {
		return nil, fmt.Errorf("DNS-AI: плохой URL %q", slot.URL)
	}
	bootstrap := parseBootstrap(slot.Addr)
	if len(bootstrap) == 0 {
		return nil, errors.New("DNS-AI: нет bootstrap-адресов")
	}
	query := buildDNSQuery(host)
	if query == nil {
		return nil, fmt.Errorf("DNS-AI: плохое имя %q", host)
	}
	dialer := &net.Dialer{Timeout: 3 * time.Second}
	transport := &http.Transport{
		Proxy: nil,
		TLSClientConfig: &tls.Config{
			ServerName: parsed.Hostname(),
			MinVersion: tls.VersionTLS12,
			// dns.dns-ai.ru на HTTP/1.1 отвечает 505: RFC 8484 разрешает
			// серверу требовать HTTP/2.
			NextProtos: []string{"h2", "http/1.1"},
		},
		ForceAttemptHTTP2:     true,
		TLSHandshakeTimeout:   dohTimeout,
		ResponseHeaderTimeout: dohTimeout,
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			via := strings.TrimSpace(slot.Via)
			if via != "" {
				// Сначала прямой DoH: DNS-AI отвечает по-разному в зависимости
				// от адреса клиента, и ответ из РФ — тот, что нужен нейросетям.
				// Если прямой путь режется, идём через PRIMARY. Именно имя, а не
				// IP: MASQUE-SOCKS не соединяет по IP (ATYP=IPv4 → 000).
				direct := &net.Dialer{Timeout: 1200 * time.Millisecond}
				for _, ip := range bootstrap {
					conn, dialErr := direct.DialContext(ctx, "tcp", net.JoinHostPort(ip, "443"))
					if dialErr == nil {
						return conn, nil
					}
				}
				return dialSocks5(via, parsed.Hostname(), 443)
			}
			var lastErr error
			for _, ip := range bootstrap {
				conn, dialErr := dialer.DialContext(ctx, "tcp", net.JoinHostPort(ip, "443"))
				if dialErr == nil {
					return conn, nil
				}
				lastErr = dialErr
			}
			if lastErr == nil {
				lastErr = errors.New("нет bootstrap-адресов DNS-AI")
			}
			return nil, lastErr
		},
	}
	client := &http.Client{Transport: transport, Timeout: dohTimeout}
	req, err := http.NewRequest(http.MethodPost, slot.URL, bytes.NewReader(query))
	if err != nil {
		return nil, err
	}
	req.Header.Set("content-type", "application/dns-message")
	req.Header.Set("accept", "application/dns-message")
	resp, err := client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("DNS-AI запрос: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("DNS-AI ответ HTTP %d", resp.StatusCode)
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, dohMaxBody))
	if err != nil {
		return nil, fmt.Errorf("DNS-AI чтение: %w", err)
	}
	ips, err = parseDNSAnswers(body)
	if err != nil {
		return nil, err
	}
	if len(ips) == 0 {
		return nil, fmt.Errorf("DNS-AI не вернул A-записей для %s", host)
	}
	log.Printf("DNS-AI %s → %v", host, ips)
	dohCache.Lock()
	dohCache.entries[key] = dohCacheEntry{ips: ips, expires: time.Now().Add(dohCacheTTL)}
	dohCache.Unlock()
	return ips, nil
}

func parseBootstrap(raw string) []string {
	out := make([]string, 0, 4)
	for _, item := range strings.Split(raw, ",") {
		host := strings.TrimSpace(item)
		if host == "" {
			continue
		}
		if strings.HasPrefix(host, "[") {
			host = strings.TrimSuffix(strings.TrimPrefix(host, "["), "]")
		}
		if net.ParseIP(host) != nil {
			out = append(out, host)
		}
	}
	return out
}

// buildDNSQuery собирает минимальный запрос A/IN. Идентификатор не нужен:
// DoH сопоставляет ответ с запросом сам.
func buildDNSQuery(host string) []byte {
	name := strings.TrimSuffix(strings.TrimSpace(host), ".")
	if name == "" || len(name) > 253 {
		return nil
	}
	labels := strings.Split(name, ".")
	question := make([]byte, 0, len(name)+2)
	for _, label := range labels {
		if label == "" || len(label) > 63 {
			return nil
		}
		question = append(question, byte(len(label)))
		question = append(question, label...)
	}
	question = append(question, 0)
	query := make([]byte, 12, 12+len(question)+4)
	binary.BigEndian.PutUint16(query[2:4], 0x0100) // RD
	binary.BigEndian.PutUint16(query[4:6], 1)      // QDCOUNT
	query = append(query, question...)
	query = append(query, 0, 1, 0, 1) // A/IN
	return query
}

func parseDNSAnswers(msg []byte) ([]net.IP, error) {
	if len(msg) < 12 {
		return nil, errors.New("DNS-AI: короткий ответ")
	}
	ancount := int(binary.BigEndian.Uint16(msg[6:8]))
	offset := 12
	var err error
	offset, err = skipDNSName(msg, offset)
	if err != nil {
		return nil, err
	}
	offset += 4 // QTYPE + QCLASS
	ips := make([]net.IP, 0, 4)
	for i := 0; i < ancount && offset+10 <= len(msg); i++ {
		offset, err = skipDNSName(msg, offset)
		if err != nil {
			return nil, err
		}
		if offset+10 > len(msg) {
			break
		}
		recordType := binary.BigEndian.Uint16(msg[offset : offset+2])
		rdlen := int(binary.BigEndian.Uint16(msg[offset+8 : offset+10]))
		offset += 10
		if offset+rdlen > len(msg) {
			break
		}
		switch {
		case recordType == 1 && rdlen == 4:
			ips = append(ips, net.IPv4(msg[offset], msg[offset+1], msg[offset+2], msg[offset+3]))
		case recordType == 28 && rdlen == 16:
			ip := make(net.IP, 16)
			copy(ip, msg[offset:offset+16])
			ips = append(ips, ip)
		}
		offset += rdlen
	}
	return ips, nil
}

func skipDNSName(msg []byte, offset int) (int, error) {
	for offset < len(msg) {
		length := int(msg[offset])
		if length == 0 {
			return offset + 1, nil
		}
		if length&0xc0 == 0xc0 {
			if offset+1 >= len(msg) {
				return 0, errors.New("DNS-AI: обрезанный указатель имени")
			}
			return offset + 2, nil
		}
		offset += 1 + length
	}
	return 0, errors.New("DNS-AI: имя выходит за ответ")
}
