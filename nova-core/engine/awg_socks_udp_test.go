package nova

import (
	"context"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestParseSocksUDPDatagram(t *testing.T) {
	ipv4 := []byte{0, 0, 0, 1, 1, 2, 3, 4, 0x1f, 0x90, 'p', 'i', 'n', 'g'}
	host, port, payload, frag, err := parseSocksUDPDatagram(ipv4)
	if err != nil || host != "1.2.3.4" || port != 8080 || string(payload) != "ping" || frag != 0 {
		t.Fatalf("IPv4: host=%q port=%d payload=%q frag=%d err=%v", host, port, payload, frag, err)
	}

	domain := append([]byte{0, 0, 0, 3, byte(len("echo.test"))}, []byte("echo.test")...)
	domain = append(domain, 0x00, 0x35, 'x')
	host, port, payload, _, err = parseSocksUDPDatagram(domain)
	if err != nil || host != "echo.test" || port != 53 || string(payload) != "x" {
		t.Fatalf("DOMAIN: host=%q port=%d payload=%q err=%v", host, port, payload, err)
	}

	ipv6 := make([]byte, 4+16+2+1)
	ipv6[3] = 4
	copy(ipv6[4:20], net.ParseIP("2001:db8::1").To16())
	binary.BigEndian.PutUint16(ipv6[20:22], 443)
	ipv6[22] = 'q'
	host, port, payload, _, err = parseSocksUDPDatagram(ipv6)
	if err != nil || host != "2001:db8::1" || port != 443 || string(payload) != "q" {
		t.Fatalf("IPv6: host=%q port=%d payload=%q err=%v", host, port, payload, err)
	}

	for name, packet := range map[string][]byte{
		"short":        {0, 0, 0},
		"bad rsv":      {1, 0, 0, 1, 1, 2, 3, 4, 0, 1},
		"bad atyp":     {0, 0, 0, 9, 1, 2, 3, 4, 0, 1},
		"empty domain": {0, 0, 0, 3, 0, 0, 1},
	} {
		if _, _, _, _, err := parseSocksUDPDatagram(packet); err == nil {
			t.Fatalf("%s: ожидалась ошибка", name)
		}
	}
}

func TestParseExpectedUDPEndpoint(t *testing.T) {
	for _, tc := range []struct {
		host string
		port string
		want bool
	}{
		{"0.0.0.0", "0", false},
		{"::", "0", false},
		{"127.0.0.1", "0", false},
		{"127.0.0.1", "5353", true},
		{"::1", "5353", true},
		{"example.com", "53", false},
		{"bad", "53", false},
	} {
		got := parseExpectedUDPEndpoint(tc.host, tc.port)
		if (got != nil) != tc.want {
			t.Fatalf("%s:%s: got %v, want %v", tc.host, tc.port, got, tc.want)
		}
	}
}

func TestSocksUDPAssociateEchoIPv4AndDomain(t *testing.T) {
	echo, received := startUDPEcho(t)
	serverAddr := startUDPSocksServer(t, echoDialer(echo.LocalAddr().(*net.UDPAddr)))
	control, relay := openUDPAssociate(t, serverAddr)
	defer control.Close()

	client, err := net.DialUDP("udp", nil, relay)
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()

	// IPv4
	writeSocksUDP(t, client, "127.0.0.1", echo.LocalAddr().(*net.UDPAddr).Port, []byte("ipv4"), 0)
	host, port, payload := readSocksUDP(t, client)
	if host != "127.0.0.1" || port != echo.LocalAddr().(*net.UDPAddr).Port || string(payload) != "ipv4" {
		t.Fatalf("IPv4 ответ: host=%q port=%d payload=%q", host, port, payload)
	}

	// DOMAIN: dialer резолвит echo.test в 127.0.0.1, ответ несёт реальный
	// удалённый адрес, а не исходное имя.
	writeSocksUDP(t, client, "echo.test", echo.LocalAddr().(*net.UDPAddr).Port, []byte("domain"), 0)
	host, port, payload = readSocksUDP(t, client)
	if host != "127.0.0.1" || port != echo.LocalAddr().(*net.UDPAddr).Port || string(payload) != "domain" {
		t.Fatalf("DOMAIN ответ: host=%q port=%d payload=%q", host, port, payload)
	}

	if got := atomic.LoadInt64(received); got != 2 {
		t.Fatalf("echo получил %d пакетов, ожидалось 2", got)
	}
}

func TestSocksUDPAssociateRejectsFragAndForeignClient(t *testing.T) {
	echo, _ := startUDPEcho(t)
	serverAddr := startUDPSocksServer(t, echoDialer(echo.LocalAddr().(*net.UDPAddr)))
	control, relay := openUDPAssociate(t, serverAddr)
	defer control.Close()

	client, err := net.DialUDP("udp", nil, relay)
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	foreign, err := net.DialUDP("udp", nil, relay)
	if err != nil {
		t.Fatal(err)
	}
	defer foreign.Close()

	// Сначала легальный пакет фиксирует клиента и создаёт flow.
	writeSocksUDP(t, client, "127.0.0.1", echo.LocalAddr().(*net.UDPAddr).Port, []byte("own"), 0)
	_, _, payload := readSocksUDP(t, client)
	if string(payload) != "own" {
		t.Fatalf("свой клиент получил %q", payload)
	}

	// FRAG != 0 не должен доходить до echo.
	writeSocksUDP(t, client, "127.0.0.1", echo.LocalAddr().(*net.UDPAddr).Port, []byte("frag"), 1)
	expectUDPTimeout(t, client)

	// Чужой UDP-сокет не должен пользоваться ассоциацией.
	writeSocksUDP(t, foreign, "127.0.0.1", echo.LocalAddr().(*net.UDPAddr).Port, []byte("foreign"), 0)
	expectUDPTimeout(t, foreign)

	// Свой клиент после этого продолжает работать.
	writeSocksUDP(t, client, "127.0.0.1", echo.LocalAddr().(*net.UDPAddr).Port, []byte("own2"), 0)
	_, _, payload = readSocksUDP(t, client)
	if string(payload) != "own2" {
		t.Fatalf("свой клиент получил %q", payload)
	}
}

func TestSocksUDPAssociateDialErrorHasNoDirectFallback(t *testing.T) {
	echo, received := startUDPEcho(t)
	serverAddr := startUDPSocksServer(t, func(context.Context, string, int) (net.Conn, error) {
		return nil, errors.New("dial запрещён")
	})
	control, relay := openUDPAssociate(t, serverAddr)
	defer control.Close()

	client, err := net.DialUDP("udp", nil, relay)
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()

	writeSocksUDP(t, client, "127.0.0.1", echo.LocalAddr().(*net.UDPAddr).Port, []byte("must-drop"), 0)
	expectUDPTimeout(t, client)
	if got := atomic.LoadInt64(received); got != 0 {
		t.Fatalf("echo получил %d пакетов, direct-fallback недопустим", got)
	}
}

func TestSocksUDPAssociateClosesWithControl(t *testing.T) {
	echo, _ := startUDPEcho(t)
	serverAddr := startUDPSocksServer(t, echoDialer(echo.LocalAddr().(*net.UDPAddr)))
	control, relay := openUDPAssociate(t, serverAddr)

	client, err := net.DialUDP("udp", nil, relay)
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()

	writeSocksUDP(t, client, "127.0.0.1", echo.LocalAddr().(*net.UDPAddr).Port, []byte("before"), 0)
	_, _, payload := readSocksUDP(t, client)
	if string(payload) != "before" {
		t.Fatalf("до закрытия получено %q", payload)
	}

	_ = control.Close()
	time.Sleep(200 * time.Millisecond)

	writeSocksUDP(t, client, "127.0.0.1", echo.LocalAddr().(*net.UDPAddr).Port, []byte("after"), 0)
	expectUDPTimeout(t, client)
}

func startUDPEcho(t *testing.T) (*net.UDPConn, *int64) {
	t.Helper()
	conn, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	var received int64
	go func() {
		buf := make([]byte, 65_535)
		for {
			n, from, err := conn.ReadFromUDP(buf)
			if err != nil {
				return
			}
			atomic.AddInt64(&received, 1)
			_, _ = conn.WriteToUDP(buf[:n], from)
		}
	}()
	t.Cleanup(func() { _ = conn.Close() })
	return conn, &received
}

func startUDPSocksServer(t *testing.T, dialUDP func(context.Context, string, int) (net.Conn, error)) string {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	server := newAwgSocksServer(listener, func(context.Context, string, string) (net.Conn, error) {
		return nil, errors.New("TCP в этом тесте не используется")
	}, dialUDP)
	ctx, cancel := context.WithCancel(context.Background())
	go server.serve(ctx)
	t.Cleanup(func() {
		cancel()
		server.close()
	})
	return listener.Addr().String()
}

func echoDialer(echo *net.UDPAddr) func(context.Context, string, int) (net.Conn, error) {
	return func(_ context.Context, host string, port int) (net.Conn, error) {
		if host == "echo.test" {
			host = "127.0.0.1"
		}
		ip := net.ParseIP(host)
		if ip == nil {
			return nil, errors.New("неизвестный хост " + host)
		}
		return net.DialUDP("udp", nil, &net.UDPAddr{IP: ip, Port: port})
	}
}

func openUDPAssociate(t *testing.T, serverAddr string) (net.Conn, *net.UDPAddr) {
	t.Helper()
	control, err := net.Dial("tcp", serverAddr)
	if err != nil {
		t.Fatal(err)
	}
	_ = control.SetDeadline(time.Now().Add(5 * time.Second))
	if _, err := control.Write([]byte{5, 1, 0}); err != nil {
		t.Fatal(err)
	}
	greeting := make([]byte, 2)
	if _, err := io.ReadFull(control, greeting); err != nil || greeting[0] != 5 || greeting[1] != 0 {
		t.Fatalf("greeting: %v %v", greeting, err)
	}
	if _, err := control.Write([]byte{5, 3, 0, 1, 0, 0, 0, 0, 0, 0}); err != nil {
		t.Fatal(err)
	}
	reply := make([]byte, 10)
	if _, err := io.ReadFull(control, reply); err != nil {
		t.Fatal(err)
	}
	if reply[0] != 5 || reply[1] != 0 || reply[3] != 1 {
		t.Fatalf("UDP ASSOCIATE reply: %v", reply)
	}
	port := int(binary.BigEndian.Uint16(reply[8:10]))
	_ = control.SetDeadline(time.Time{})
	return control, &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: port}
}

func writeSocksUDP(t *testing.T, conn *net.UDPConn, host string, port int, payload []byte, frag byte) {
	t.Helper()
	packet, err := buildSocksUDPDatagram(host, port, payload)
	if err != nil {
		t.Fatal(err)
	}
	packet[2] = frag
	if _, err := conn.Write(packet); err != nil {
		t.Fatal(err)
	}
}

func readSocksUDP(t *testing.T, conn *net.UDPConn) (string, int, []byte) {
	t.Helper()
	_ = conn.SetReadDeadline(time.Now().Add(2 * time.Second))
	buf := make([]byte, 65_535)
	n, err := conn.Read(buf)
	if err != nil {
		t.Fatalf("чтение UDP: %v", err)
	}
	host, port, payload, _, err := parseSocksUDPDatagram(buf[:n])
	if err != nil {
		t.Fatalf("разбор ответа: %v", err)
	}
	return host, port, payload
}

func expectUDPTimeout(t *testing.T, conn *net.UDPConn) {
	t.Helper()
	_ = conn.SetReadDeadline(time.Now().Add(350 * time.Millisecond))
	buf := make([]byte, 1024)
	_, err := conn.Read(buf)
	if err == nil {
		t.Fatal("ожидался таймаут, получен пакет")
	}
	if !strings.Contains(err.Error(), "timeout") {
		t.Fatalf("ожидался timeout, получено %v", err)
	}
}
