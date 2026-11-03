package main

import (
	"bufio"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"strconv"
	"strings"
	"time"
)

const dialTimeout = 8 * time.Second

// Router пробует слоты по порядку и возвращает первое живое соединение.
type Router struct {
	cfg   Config
	stats *Stats
}

func (r Router) Dial(host string, port int) (net.Conn, string, error) {
	return r.DialSNI(host, port, "")
}

// DialSNI принимает SNI, извлечённый из ClientHello. Он используется только
// HTTP-слотом: Opera ждёт FQDN, а не голый IP.
func (r Router) DialSNI(host string, port int, sni string) (net.Conn, string, error) {
	order, routeName := r.routeOrderFor(host, sni)
	if r.stats != nil {
		r.stats.recordRoute(routeName)
	}
	var lastErr error
	for _, name := range order {
		slot, ok := r.cfg.Slots[name]
		if !ok {
			continue
		}
		started := time.Now()
		conn, err := dialSlot(slot, host, port, sni)
		if r.stats != nil {
			r.stats.record(name, time.Since(started), err == nil)
		}
		if err == nil {
			return conn, name, nil
		}
		lastErr = fmt.Errorf("%s: %w", name, err)
	}
	if lastErr == nil {
		lastErr = fmt.Errorf("нет доступных слотов")
	}
	return nil, "", lastErr
}

// routeOrderFor выбирает правило: SNI точнее CIDR. Хост
// `android.apis.google.com` живёт в сетях Google, но Opera не тянет его
// gRPC-потоки (500), поэтому доменное исключение должно победить IP-правило.
// Если SNI не совпал ни с одним правилом, решает адрес.
func (r Router) routeOrderFor(host, sni string) ([]string, string) {
	if sni != "" {
		if order, name := r.cfg.OrderFor(sni); name != "default" {
			return order, name
		}
	}
	return r.cfg.OrderFor(host)
}

func dialSlot(slot Slot, host string, port int, sni string) (net.Conn, error) {
	switch slot.Type {
	case SlotDirect:
		return dialDirect(host, port, dialTimeout)
	case SlotSocks5:
		return dialSocks5(slot.Addr, host, port)
	case SlotHTTP:
		targetHost := host
		if candidate, err := validSNI(sni); err == nil {
			targetHost = candidate
		}
		return dialHTTPConnect(slot.Addr, targetHost, port)
	default:
		return nil, fmt.Errorf("неизвестный тип слота %q", slot.Type)
	}
}

func dialSocks5(proxyAddr, host string, port int) (net.Conn, error) {
	conn, err := net.DialTimeout("tcp", proxyAddr, dialTimeout)
	if err != nil {
		return nil, err
	}
	_ = conn.SetDeadline(time.Now().Add(dialTimeout))

	if _, err := conn.Write([]byte{5, 1, 0}); err != nil {
		conn.Close()
		return nil, err
	}
	greeting := make([]byte, 2)
	if _, err := io.ReadFull(conn, greeting); err != nil {
		conn.Close()
		return nil, err
	}
	if greeting[0] != 5 || greeting[1] != 0 {
		conn.Close()
		return nil, fmt.Errorf("SOCKS5: метод не принят")
	}

	request := []byte{5, 1, 0}
	request = append(request, encodeAddr(host)...)
	request = binary.BigEndian.AppendUint16(request, uint16(port))
	if _, err := conn.Write(request); err != nil {
		conn.Close()
		return nil, err
	}
	reply := make([]byte, 4)
	if _, err := io.ReadFull(conn, reply); err != nil {
		conn.Close()
		return nil, err
	}
	if reply[0] != 5 || reply[1] != 0 {
		conn.Close()
		return nil, fmt.Errorf("SOCKS5: отказ %d", reply[1])
	}
	if err := skipAddr(conn, reply[3]); err != nil {
		conn.Close()
		return nil, err
	}
	_ = conn.SetDeadline(time.Time{})
	return conn, nil
}

func dialHTTPConnect(proxyAddr, host string, port int) (net.Conn, error) {
	conn, err := net.DialTimeout("tcp", proxyAddr, dialTimeout)
	if err != nil {
		return nil, err
	}
	_ = conn.SetDeadline(time.Now().Add(dialTimeout))
	target := net.JoinHostPort(host, strconv.Itoa(port))
	request := "CONNECT " + target + " HTTP/1.1\r\nHost: " + target + "\r\n\r\n"
	if _, err := conn.Write([]byte(request)); err != nil {
		conn.Close()
		return nil, err
	}
	reader := bufio.NewReader(conn)
	status, err := reader.ReadString('\n')
	if err != nil {
		conn.Close()
		return nil, err
	}
	if !strings.Contains(status, " 200") {
		conn.Close()
		return nil, fmt.Errorf("HTTP CONNECT: %s", strings.TrimSpace(status))
	}
	for {
		line, err := reader.ReadString('\n')
		if err != nil {
			conn.Close()
			return nil, err
		}
		if line == "\r\n" || line == "\n" {
			break
		}
	}
	_ = conn.SetDeadline(time.Time{})
	return &bufferedConn{Conn: conn, reader: reader}, nil
}

func encodeAddr(host string) []byte {
	ip := net.ParseIP(host)
	if ip4 := ip.To4(); ip4 != nil {
		return append([]byte{1}, ip4...)
	}
	if ip6 := ip.To16(); ip6 != nil {
		return append([]byte{4}, ip6...)
	}
	out := []byte{3, byte(len(host))}
	return append(out, host...)
}

func skipAddr(r io.Reader, atyp byte) error {
	switch atyp {
	case 1:
		_, err := io.ReadFull(r, make([]byte, 4+2))
		return err
	case 4:
		_, err := io.ReadFull(r, make([]byte, 16+2))
		return err
	case 3:
		l := make([]byte, 1)
		if _, err := io.ReadFull(r, l); err != nil {
			return err
		}
		_, err := io.ReadFull(r, make([]byte, int(l[0])+2))
		return err
	default:
		return fmt.Errorf("неизвестный ATYP %d", atyp)
	}
}

type bufferedConn struct {
	net.Conn
	reader *bufio.Reader
}

func (c *bufferedConn) Read(p []byte) (int, error) { return c.reader.Read(p) }
