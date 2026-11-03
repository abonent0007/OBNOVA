package main

import (
	"encoding/binary"
	"io"
	"net"
	"testing"
	"time"
)

// Сквозная проверка UDP ASSOCIATE: tun2proxy шлёт датаграммы в SOCKS-формате,
// движок обязан переслать их наружу и вернуть ответ.
func TestUDPAssociateForwardsDatagrams(t *testing.T) {
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

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	go func() {
		conn, err := ln.Accept()
		if err != nil {
			return
		}
		_ = serveSOCKS(conn, config{})
	}()

	control, err := net.Dial("tcp", ln.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer control.Close()
	if _, err := control.Write([]byte{5, 1, 0}); err != nil {
		t.Fatal(err)
	}
	greeting := make([]byte, 2)
	if _, err := io.ReadFull(control, greeting); err != nil {
		t.Fatal(err)
	}
	if greeting[0] != 5 || greeting[1] != 0 {
		t.Fatalf("greeting %v", greeting)
	}
	// UDP ASSOCIATE, DST 0.0.0.0:0
	if _, err := control.Write([]byte{5, 3, 0, 1, 0, 0, 0, 0, 0, 0}); err != nil {
		t.Fatal(err)
	}
	reply := make([]byte, 10)
	if _, err := io.ReadFull(control, reply); err != nil {
		t.Fatal(err)
	}
	if reply[1] != 0 {
		t.Fatalf("UDP ASSOCIATE отклонён: %v", reply)
	}
	relay := &net.UDPAddr{
		IP:   net.IP(reply[4:8]),
		Port: int(binary.BigEndian.Uint16(reply[8:10])),
	}

	client, err := net.DialUDP("udp", nil, relay)
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	_ = client.SetDeadline(time.Now().Add(5 * time.Second))

	packet, err := buildUDPDatagram("127.0.0.1", echoPort, []byte("ping"))
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
	host, port, payload, ok := parseUDPDatagram(buf[:n])
	if !ok || host != "127.0.0.1" || port != echoPort || string(payload) != "ping" {
		t.Fatalf("ответ %s:%d %q ok=%v", host, port, payload, ok)
	}
}
