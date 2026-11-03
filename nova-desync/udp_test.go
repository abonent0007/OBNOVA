package main

import (
	"bytes"
	"testing"
)

func TestUDPDatagramRoundTrip(t *testing.T) {
	cases := []struct {
		host string
		port int
	}{
		{"1.1.1.1", 53},
		{"dns.google", 53},
		{"2606:4700:4700::1111", 53},
	}
	for _, c := range cases {
		packet, err := buildUDPDatagram(c.host, c.port, []byte("query"))
		if err != nil {
			t.Fatalf("build %s: %v", c.host, err)
		}
		host, port, payload, ok := parseUDPDatagram(packet)
		if !ok {
			t.Fatalf("parse %s: не разобрался", c.host)
		}
		if host != c.host || port != c.port || !bytes.Equal(payload, []byte("query")) {
			t.Fatalf("round-trip %s: получено %s:%d %q", c.host, host, port, payload)
		}
	}
}

func TestUDPDatagramRejectsBrokenInput(t *testing.T) {
	if _, _, _, ok := parseUDPDatagram([]byte{0, 0, 0}); ok {
		t.Fatal("короткий пакет принят")
	}
	if _, _, _, ok := parseUDPDatagram([]byte{0, 0, 1, 1, 1, 1, 1, 1, 0, 53}); ok {
		t.Fatal("фрагментированный пакет принят")
	}
	if _, _, _, ok := parseUDPDatagram([]byte{0, 0, 0, 3, 10, 'a', 'b', 0, 53}); ok {
		t.Fatal("обрезанный домен принят")
	}
}
