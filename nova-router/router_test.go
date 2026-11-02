package main

import (
	"bufio"
	"encoding/binary"
	"io"
	"net"
	"strconv"
	"strings"
	"testing"
	"time"
)

func orderOf(cfg Config, host string) string {
	order, _ := cfg.OrderFor(host)
	return strings.Join(order, ",")
}

func TestOrderForUsesFirstMatchingRule(t *testing.T) {
	cfg := Config{
		Rules: []Rule{
			{Domains: []string{"youtube.com"}, Order: []string{"secondary", "primary"}},
			{Domains: []string{"example.com"}, Order: []string{"primary", "direct"}},
		},
		DefaultOrder: []string{"primary", "secondary", "direct"},
	}
	if got := orderOf(cfg, "music.youtube.com"); got != "secondary,primary" {
		t.Fatalf("youtube: %s", got)
	}
	if got := orderOf(cfg, "WWW.Example.COM."); got != "primary,direct" {
		t.Fatalf("example: %s", got)
	}
	if got := orderOf(cfg, "notexample.com"); got != "primary,secondary,direct" {
		t.Fatalf("default: %s", got)
	}
}

func startEchoServer(t *testing.T) net.Listener {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = ln.Close() })
	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			go func() {
				defer conn.Close()
				_, _ = io.Copy(conn, conn)
			}()
		}
	}()
	return ln
}

func startFakeSocks5(t *testing.T) net.Listener {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = ln.Close() })
	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			go func() {
				defer conn.Close()
				head := make([]byte, 2)
				if _, err := io.ReadFull(conn, head); err != nil {
					return
				}
				_, _ = io.ReadFull(conn, make([]byte, int(head[1])))
				_, _ = conn.Write([]byte{5, 0})
				req := make([]byte, 4)
				if _, err := io.ReadFull(conn, req); err != nil {
					return
				}
				switch req[3] {
				case 1:
					_, _ = io.ReadFull(conn, make([]byte, 6))
				case 3:
					l := make([]byte, 1)
					_, _ = io.ReadFull(conn, l)
					_, _ = io.ReadFull(conn, make([]byte, int(l[0])+2))
				case 4:
					_, _ = io.ReadFull(conn, make([]byte, 18))
				}
				_, _ = conn.Write([]byte{5, 0, 0, 1, 0, 0, 0, 0, 0, 0})
				_, _ = io.Copy(conn, conn)
			}()
		}
	}()
	return ln
}

func startFakeHTTPProxy(t *testing.T) net.Listener {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = ln.Close() })
	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			go func() {
				defer conn.Close()
				reader := bufio.NewReader(conn)
				for {
					line, err := reader.ReadString('\n')
					if err != nil {
						return
					}
					if line == "\r\n" {
						break
					}
				}
				_, _ = conn.Write([]byte("HTTP/1.1 200 Connection Established\r\n\r\n"))
				_, _ = io.Copy(conn, reader)
			}()
		}
	}()
	return ln
}

func echoRoundTrip(t *testing.T, conn net.Conn) {
	t.Helper()
	_ = conn.SetDeadline(time.Now().Add(3 * time.Second))
	if _, err := conn.Write([]byte("ping")); err != nil {
		t.Fatal(err)
	}
	buf := make([]byte, 4)
	if _, err := io.ReadFull(conn, buf); err != nil {
		t.Fatal(err)
	}
	if string(buf) != "ping" {
		t.Fatalf("эхо %q", buf)
	}
}

func TestRouterFallsBackToSecondSlot(t *testing.T) {
	proxy := startFakeSocks5(t)
	cfg := Config{
		Slots: map[string]Slot{
			"primary":   {Type: SlotSocks5, Addr: "127.0.0.1:1"},
			"secondary": {Type: SlotSocks5, Addr: proxy.Addr().String()},
		},
		DefaultOrder: []string{"primary", "secondary"},
	}
	conn, slot, err := (Router{cfg: cfg}).Dial("example.com", 443)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	if slot != "secondary" {
		t.Fatalf("слот %s", slot)
	}
	echoRoundTrip(t, conn)
}

func TestHTTPConnectUpstream(t *testing.T) {
	proxy := startFakeHTTPProxy(t)
	cfg := Config{
		Slots:        map[string]Slot{"secondary": {Type: SlotHTTP, Addr: proxy.Addr().String()}},
		DefaultOrder: []string{"secondary"},
	}
	conn, _, err := (Router{cfg: cfg}).Dial("example.com", 443)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	echoRoundTrip(t, conn)
}

func TestDirectUpstream(t *testing.T) {
	echo := startEchoServer(t)
	port := echo.Addr().(*net.TCPAddr).Port
	cfg := Config{
		Slots:        map[string]Slot{"direct": {Type: SlotDirect}},
		DefaultOrder: []string{"direct"},
	}
	conn, _, err := (Router{cfg: cfg}).Dial("127.0.0.1", port)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	echoRoundTrip(t, conn)
}

func TestHandshakeRoundTrip(t *testing.T) {
	client, server := net.Pipe()
	defer client.Close()
	defer server.Close()
	done := make(chan struct{})
	go func() {
		cmd, host, port, err := handshake(server)
		if err != nil || cmd != 1 || host != "example.com" || port != 443 {
			t.Errorf("handshake: %q %d %v", host, port, err)
		}
		close(done)
	}()

	if _, err := client.Write([]byte{5, 1, 0}); err != nil {
		t.Fatal(err)
	}
	greeting := make([]byte, 2)
	if _, err := io.ReadFull(client, greeting); err != nil {
		t.Fatal(err)
	}
	if greeting[0] != 5 || greeting[1] != 0 {
		t.Fatalf("greeting %v", greeting)
	}

	request := []byte{5, 1, 0, 3, byte(len("example.com"))}
	request = append(request, []byte("example.com")...)
	request = binary.BigEndian.AppendUint16(request, 443)
	if _, err := client.Write(request); err != nil {
		t.Fatal(err)
	}
	<-done
}

func TestConfigValidationRejectsBrokenSlots(t *testing.T) {
	cfg := Config{
		Slots:        map[string]Slot{"bad": {Type: "quic"}},
		DefaultOrder: []string{"bad"},
	}
	if err := cfg.validate(); err == nil {
		t.Fatal("ожидалась ошибка")
	}
	_ = strconv.Itoa(1)
}

func TestConfigValidationRejectsUnknownOrderSlot(t *testing.T) {
	base := map[string]Slot{
		"primary": {Type: SlotDirect},
		"direct":  {Type: SlotDirect},
	}
	for name, cfg := range map[string]Config{
		"default": {
			Slots:        base,
			DefaultOrder: []string{"primary", "typo"},
		},
		"rule": {
			Slots:        base,
			DefaultOrder: []string{"primary"},
			Rules: []Rule{{
				Name:    "telegram",
				Domains: []string{"telegram.org"},
				Order:   []string{"relay"},
			}},
		},
	} {
		if err := cfg.validate(); err == nil {
			t.Fatalf("%s: ожидалась ошибка про неизвестный слот", name)
		}
	}
}

func TestConfigValidationAcceptsEmptyRuleOrder(t *testing.T) {
	cfg := Config{
		Slots: map[string]Slot{
			"primary": {Type: SlotDirect},
			"direct":  {Type: SlotDirect},
		},
		DefaultOrder: []string{"primary", "direct"},
		Rules: []Rule{{
			Name:    "blocked",
			Domains: []string{"blocked.example"},
			Order:   []string{},
		}},
	}
	if err := cfg.validate(); err != nil {
		t.Fatalf("пустой порядок — это BLOCK, ошибки быть не должно: %v", err)
	}
}
