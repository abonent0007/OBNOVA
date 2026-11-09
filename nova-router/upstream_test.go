package main

import (
	"io"
	"net"
	"strconv"
	"testing"
)

// TestDialSlotSocks5UsesSNIForIPHost: MASQUE-SOCKS не соединяет по IP,
// поэтому при IP-хосте и валидном SNI в SOCKS-запрос уходит домен.
func TestDialSlotSocks5UsesSNIForIPHost(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	defer listener.Close()

	got := make(chan string, 1)
	go func() {
		conn, err := listener.Accept()
		if err != nil {
			return
		}
		defer conn.Close()
		head := make([]byte, 2)
		if _, err := io.ReadFull(conn, head); err != nil {
			return
		}
		if _, err := io.ReadFull(conn, make([]byte, int(head[1]))); err != nil {
			return
		}
		if _, err := conn.Write([]byte{5, 0}); err != nil {
			return
		}
		req := make([]byte, 4)
		if _, err := io.ReadFull(conn, req); err != nil {
			return
		}
		var host string
		switch req[3] {
		case 1:
			buf := make([]byte, 4)
			if _, err := io.ReadFull(conn, buf); err != nil {
				return
			}
			host = net.IP(buf).String()
		case 3:
			length := make([]byte, 1)
			if _, err := io.ReadFull(conn, length); err != nil {
				return
			}
			buf := make([]byte, int(length[0]))
			if _, err := io.ReadFull(conn, buf); err != nil {
				return
			}
			host = string(buf)
		case 4:
			buf := make([]byte, 16)
			if _, err := io.ReadFull(conn, buf); err != nil {
				return
			}
			host = net.IP(buf).String()
		default:
			return
		}
		portBuf := make([]byte, 2)
		if _, err := io.ReadFull(conn, portBuf); err != nil {
			return
		}
		got <- net.JoinHostPort(host, strconv.Itoa(int(portBuf[0])<<8|int(portBuf[1])))
		_, _ = conn.Write([]byte{5, 0, 0, 1, 0, 0, 0, 0, 0, 0})
	}()

	conn, err := dialSlot(Slot{Type: SlotSocks5, Addr: listener.Addr().String()}, "203.0.113.1", 443, "example.com")
	if err != nil {
		t.Fatalf("dialSlot: %v", err)
	}
	_ = conn.Close()

	if target := <-got; target != "example.com:443" {
		t.Fatalf("SOCKS target = %q, want example.com:443", target)
	}
}
