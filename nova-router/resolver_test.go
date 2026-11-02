package main

import (
	"net"
	"testing"
	"time"
)

func TestResolveHostKeepsIPLiteral(t *testing.T) {
	ips, err := resolveHost("127.0.0.1")
	if err != nil {
		t.Fatalf("resolveHost: %v", err)
	}
	if len(ips) != 1 || !ips[0].Equal(net.ParseIP("127.0.0.1")) {
		t.Fatalf("адреса: %v", ips)
	}
}

func TestDialDirectByIP(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	defer listener.Close()

	accepted := make(chan struct{})
	go func() {
		conn, acceptErr := listener.Accept()
		if acceptErr == nil {
			_ = conn.Close()
			close(accepted)
		}
	}()

	port := listener.Addr().(*net.TCPAddr).Port
	conn, err := dialDirect("127.0.0.1", port, 2*time.Second)
	if err != nil {
		t.Fatalf("dialDirect: %v", err)
	}
	_ = conn.Close()
	select {
	case <-accepted:
	case <-time.After(2 * time.Second):
		t.Fatal("соединение не дошло до слушателя")
	}
}

func TestPreferIPv4(t *testing.T) {
	ips := []net.IP{
		net.ParseIP("2a00:1450:4010:c0f::64"),
		net.ParseIP("142.250.74.100"),
	}
	ordered := preferIPv4(ips)
	if ordered[0].To4() == nil {
		t.Fatalf("IPv4 не первый: %v", ordered)
	}
	if len(ordered) != 2 {
		t.Fatalf("потеряны адреса: %v", ordered)
	}
}
