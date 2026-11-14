package main

import (
	"encoding/binary"
	"io"
	"net"
	"sync"
	"testing"
	"time"
)

// startUDPClient открывает клиентскую сторону UDP ASSOCIATE через net.Pipe.
// Возвращает control и адрес relay, который вернул роутер.
func startUDPClient(t *testing.T, router Router) (net.Conn, *net.UDPAddr) {
	t.Helper()
	control, server := net.Pipe()
	go func() { _ = serveUDPAssociate(server, router) }()
	t.Cleanup(func() {
		_ = control.Close()
		_ = server.Close()
	})

	reply := make([]byte, 10)
	if _, err := io.ReadFull(control, reply); err != nil {
		t.Fatalf("UDP ASSOCIATE reply: %v", err)
	}
	if reply[1] != 0 {
		t.Fatalf("UDP ASSOCIATE отклонён: %v", reply)
	}
	return control, &net.UDPAddr{
		IP:   net.IP(reply[4:8]),
		Port: int(binary.BigEndian.Uint16(reply[8:10])),
	}
}

func exchangeUDP(t *testing.T, relay *net.UDPAddr, host string, port int, payload string) (string, int, string) {
	t.Helper()
	client, err := net.DialUDP("udp", nil, relay)
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	_ = client.SetDeadline(time.Now().Add(5 * time.Second))

	packet, err := buildUDPDatagram(host, port, []byte(payload))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := client.Write(packet); err != nil {
		t.Fatal(err)
	}
	buf := make([]byte, 2048)
	n, _, err := client.ReadFromUDP(buf)
	if err != nil {
		t.Fatalf("ответ не пришёл: %v", err)
	}
	gotHost, gotPort, gotPayload, ok := parseUDPDatagram(buf[:n])
	if !ok {
		t.Fatalf("битый ответ: %v", buf[:n])
	}
	return gotHost, gotPort, string(gotPayload)
}

func TestUDPAssociateForwardsDirectDatagrams(t *testing.T) {
	echo, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer echo.Close()
	echoPort := echo.LocalAddr().(*net.UDPAddr).Port
	go func() {
		buf := make([]byte, 2048)
		for {
			n, addr, err := echo.ReadFromUDP(buf)
			if err != nil {
				return
			}
			_, _ = echo.WriteToUDP(buf[:n], addr)
		}
	}()

	router := Router{cfg: Config{
		Slots:           map[string]Slot{"direct": {Type: SlotDirect, UDP: true}},
		DefaultOrder:    []string{"direct"},
		DefaultUDPOrder: []string{"direct"},
	}, stats: NewStats()}
	_, relay := startUDPClient(t, router)

	host, port, payload := exchangeUDP(t, relay, "127.0.0.1", echoPort, "ping")
	if host != "127.0.0.1" || port != echoPort || payload != "ping" {
		t.Fatalf("ответ %s:%d %q", host, port, payload)
	}
}

// fakeSocks5UDP поднимает минимальный SOCKS5-сервер с UDP ASSOCIATE: все
// датаграммы возвращаются отправителю с тем же адресом назначения.
type fakeUDPUpstream struct {
	addr        string
	received    chan string
	connections chan struct{}
	closed      chan struct{}
	closeOnce   sync.Once
}

func startFakeSocks5UDP(t *testing.T) *fakeUDPUpstream {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = ln.Close() })
	fake := &fakeUDPUpstream{
		addr:        ln.Addr().String(),
		received:    make(chan string, 16),
		connections: make(chan struct{}, 16),
		closed:      make(chan struct{}),
	}
	go func() {
		for {
			control, err := ln.Accept()
			if err != nil {
				return
			}
			go fake.serve(control)
		}
	}()
	return fake
}

func (f *fakeUDPUpstream) serve(control net.Conn) {
	defer control.Close()
	select {
	case f.connections <- struct{}{}:
	default:
	}
	greeting := make([]byte, 2)
	if _, err := io.ReadFull(control, greeting); err != nil {
		return
	}
	methods := make([]byte, int(greeting[1]))
	if _, err := io.ReadFull(control, methods); err != nil {
		return
	}
	if _, err := control.Write([]byte{5, 0}); err != nil {
		return
	}
	header := make([]byte, 4)
	if _, err := io.ReadFull(control, header); err != nil {
		return
	}
	if header[1] != socksUDPAssociate {
		return
	}
	if _, _, err := readSocksAddr(control, header[3]); err != nil {
		return
	}
	relay, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		_ = writeReply(control, 0x01)
		return
	}
	defer relay.Close()
	local := relay.LocalAddr().(*net.UDPAddr)
	reply := []byte{5, 0, 0, 1, 127, 0, 0, 1, byte(local.Port >> 8), byte(local.Port)}
	if _, err := control.Write(reply); err != nil {
		return
	}
	go func() {
		_, _ = io.Copy(io.Discard, control)
		f.closeOnce.Do(func() { close(f.closed) })
		_ = relay.Close()
	}()

	buf := make([]byte, 65_535)
	for {
		n, from, err := relay.ReadFromUDP(buf)
		if err != nil {
			return
		}
		host, port, payload, ok := parseUDPDatagram(buf[:n])
		if !ok {
			continue
		}
		select {
		case f.received <- host:
		default:
		}
		// Реальный AWG-мост возвращает IP, а не исходный домен. Разные
		// домены должны разрешаться в разные адреса, чтобы тест ловил
		// перекрёстную доставку.
		replyHost := host
		switch host {
		case "first.example":
			replyHost = "10.77.0.1"
		case "second.example":
			replyHost = "10.77.0.2"
		default:
			if net.ParseIP(host) == nil {
				replyHost = "127.0.0.1"
			}
		}
		response, err := buildUDPDatagram(replyHost, port, payload)
		if err == nil {
			_, _ = relay.WriteToUDP(response, from)
		}
	}
}

func TestUDPAssociateUsesUDPOrderNotTCPOrder(t *testing.T) {
	upstream := startFakeSocks5UDP(t)
	router := Router{cfg: Config{
		Slots: map[string]Slot{
			"secondary": {Type: SlotHTTP, Addr: "127.0.0.1:1371"},
			"awg":       {Type: SlotSocks5, Addr: upstream.addr, UDP: true},
			"direct":    {Type: SlotDirect, UDP: true},
		},
		Rules: []Rule{{
			Name:     "example",
			Domains:  []string{"example.test"},
			TCPOrder: []string{"secondary", "direct"},
			UDPOrder: []string{"awg"},
		}},
		DefaultOrder:    []string{"direct"},
		DefaultUDPOrder: []string{"direct"},
	}, stats: NewStats()}

	_, relay := startUDPClient(t, router)
	host, port, payload := exchangeUDP(t, relay, "example.test", 9000, "udp-order")
	if host != "127.0.0.1" || port != 9000 || payload != "udp-order" {
		t.Fatalf("ответ %s:%d %q", host, port, payload)
	}
}

func TestUDPAssociateDrops443WithoutAllowQUIC(t *testing.T) {
	upstream := startFakeSocks5UDP(t)
	router := Router{cfg: Config{
		Slots: map[string]Slot{
			"awg": {Type: SlotSocks5, Addr: upstream.addr, UDP: true},
		},
		Rules: []Rule{{
			Name:     "example",
			Domains:  []string{"example.test"},
			TCPOrder: []string{"awg"},
			UDPOrder: []string{"awg"},
		}},
		DefaultOrder:    []string{"awg"},
		DefaultUDPOrder: []string{"awg"},
	}, stats: NewStats()}

	_, relay := startUDPClient(t, router)
	client, err := net.DialUDP("udp", nil, relay)
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	_ = client.SetDeadline(time.Now().Add(500 * time.Millisecond))
	packet, err := buildUDPDatagram("example.test", 443, []byte("quic"))
	if err != nil {
		t.Fatal(err)
	}
	_, _ = client.Write(packet)
	buf := make([]byte, 2048)
	if _, _, err := client.ReadFromUDP(buf); err == nil {
		t.Fatal("UDP/443 прошёл без allowQuic")
	}
	select {
	case host := <-upstream.received:
		t.Fatalf("upstream получил UDP/443 без allowQuic: %s", host)
	default:
	}
}

func TestUDPAssociateDispatches443WithAllowQUIC(t *testing.T) {
	upstream := startFakeSocks5UDP(t)
	router := Router{cfg: Config{
		Slots: map[string]Slot{
			"awg": {Type: SlotSocks5, Addr: upstream.addr, UDP: true},
		},
		Rules: []Rule{{
			Name:      "example",
			Domains:   []string{"example.test"},
			TCPOrder:  []string{"awg"},
			UDPOrder:  []string{"awg"},
			AllowQUIC: true,
		}},
		DefaultOrder:    []string{"awg"},
		DefaultUDPOrder: []string{"awg"},
	}, stats: NewStats()}

	_, relay := startUDPClient(t, router)
	host, port, payload := exchangeUDP(t, relay, "example.test", 443, "quic")
	if host != "127.0.0.1" || port != 443 || payload != "quic" {
		t.Fatalf("ответ %s:%d %q", host, port, payload)
	}
}

func TestUDPAssociateRoutesThroughFirstSlotAndKeepsDomain(t *testing.T) {
	upstream := startFakeSocks5UDP(t)
	router := Router{cfg: Config{
		Slots: map[string]Slot{
			"awg": {Type: SlotSocks5, Addr: upstream.addr, UDP: true},
		},
		DefaultOrder:    []string{"awg"},
		DefaultUDPOrder: []string{"awg"},
	}, stats: NewStats()}

	control, relay := startUDPClient(t, router)
	host, port, payload := exchangeUDP(t, relay, "udp-test.example", 9000, "domain")
	if host != "127.0.0.1" || port != 9000 || payload != "domain" {
		t.Fatalf("ответ %s:%d %q", host, port, payload)
	}
	select {
	case seen := <-upstream.received:
		if seen != "udp-test.example" {
			t.Fatalf("upstream получил %q, ожидался домен без резолва", seen)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("upstream не получил датаграмму")
	}

	_ = control.Close()
	select {
	case <-upstream.closed:
	case <-time.After(2 * time.Second):
		t.Fatal("закрытие клиентской ассоциации не закрыло upstream control")
	}
}

func TestUDPAssociateKeepsConcurrentDomainsSeparate(t *testing.T) {
	upstream := startFakeSocks5UDP(t)
	router := Router{cfg: Config{
		Slots: map[string]Slot{
			"awg": {Type: SlotSocks5, Addr: upstream.addr, UDP: true},
		},
		DefaultOrder:    []string{"awg"},
		DefaultUDPOrder: []string{"awg"},
	}, stats: NewStats()}

	_, relay := startUDPClient(t, router)
	client, err := net.DialUDP("udp", nil, relay)
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	_ = client.SetDeadline(time.Now().Add(5 * time.Second))

	requests := []struct {
		host  string
		nonce string
	}{
		{"first.example", "nonce-first"},
		{"second.example", "nonce-second"},
	}
	for _, request := range requests {
		packet, err := buildUDPDatagram(request.host, 9000, []byte(request.nonce))
		if err != nil {
			t.Fatal(err)
		}
		if _, err := client.Write(packet); err != nil {
			t.Fatal(err)
		}
	}

	got := make(map[string]string)
	buf := make([]byte, 4096)
	for len(got) < len(requests) {
		n, _, err := client.ReadFromUDP(buf)
		if err != nil {
			t.Fatalf("ответы получены не все: %v", err)
		}
		host, _, payload, ok := parseUDPDatagram(buf[:n])
		if !ok {
			continue
		}
		got[string(payload)] = host
	}
	if got["nonce-first"] != "10.77.0.1" {
		t.Fatalf("first.example получил ответ от %q", got["nonce-first"])
	}
	if got["nonce-second"] != "10.77.0.2" {
		t.Fatalf("second.example получил ответ от %q", got["nonce-second"])
	}
	seen := make(map[string]bool)
	for i := 0; i < len(requests); i++ {
		select {
		case host := <-upstream.received:
			seen[host] = true
		case <-time.After(2 * time.Second):
			t.Fatal("upstream получил не все домены")
		}
	}
	if !seen["first.example"] || !seen["second.example"] {
		t.Fatalf("upstream видел %v", seen)
	}
	for i := 0; i < 2; i++ {
		select {
		case <-upstream.connections:
		case <-time.After(2 * time.Second):
			t.Fatal("router не открыл отдельную ассоциацию на каждый домен")
		}
	}
}

func TestUDPAssociateDropsWhenFirstSlotIsTCPOnly(t *testing.T) {
	upstream := startFakeSocks5UDP(t)
	router := Router{cfg: Config{
		Slots: map[string]Slot{
			"secondary": {Type: SlotHTTP, Addr: "127.0.0.1:1371"},
			"direct":    {Type: SlotDirect, UDP: true},
		},
		DefaultOrder:    []string{"secondary", "direct"},
		DefaultUDPOrder: []string{"secondary", "direct"},
	}, stats: NewStats()}

	_, relay := startUDPClient(t, router)
	client, err := net.DialUDP("udp", nil, relay)
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	_ = client.SetDeadline(time.Now().Add(500 * time.Millisecond))
	packet, err := buildUDPDatagram("example.test", 9000, []byte("drop"))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := client.Write(packet); err != nil {
		t.Fatal(err)
	}
	buf := make([]byte, 2048)
	if _, _, err := client.ReadFromUDP(buf); err == nil {
		t.Fatal("UDP прошёл через TCP-only первый слот или direct fallback")
	}
	select {
	case seen := <-upstream.received:
		t.Fatalf("upstream не должен был получить трафик, получено %q", seen)
	default:
	}
}

func TestUDPAssociateDropsWhenSlotHasNoCapability(t *testing.T) {
	upstream := startFakeSocks5UDP(t)
	router := Router{cfg: Config{
		Slots: map[string]Slot{
			"primary": {Type: SlotSocks5, Addr: upstream.addr},
		},
		DefaultOrder:    []string{"primary"},
		DefaultUDPOrder: []string{"primary"},
	}, stats: NewStats()}

	_, relay := startUDPClient(t, router)
	client, err := net.DialUDP("udp", nil, relay)
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	_ = client.SetDeadline(time.Now().Add(500 * time.Millisecond))
	packet, err := buildUDPDatagram("udp-test.example", 9000, []byte("drop"))
	if err != nil {
		t.Fatal(err)
	}
	_, _ = client.Write(packet)
	buf := make([]byte, 2048)
	if _, _, err := client.ReadFromUDP(buf); err == nil {
		t.Fatal("UDP прошёл через слот без capability")
	}
	select {
	case seen := <-upstream.received:
		t.Fatalf("слот без capability получил трафик %q", seen)
	default:
	}
}
