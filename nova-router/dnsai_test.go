package main

import (
	"encoding/binary"
	"net"
	"testing"
)

func TestBuildDNSQuery(t *testing.T) {
	query := buildDNSQuery("chatgpt.com")
	if query == nil {
		t.Fatal("query is nil")
	}
	if binary.BigEndian.Uint16(query[4:6]) != 1 {
		t.Fatalf("QDCOUNT = %d", binary.BigEndian.Uint16(query[4:6]))
	}
	// Метка 7 "chatgpt", 3 "com", завершающий ноль и 4 байта QTYPE/QCLASS.
	if len(query) != 12+1+7+1+3+1+4 {
		t.Fatalf("длина запроса = %d", len(query))
	}
	if buildDNSQuery("") != nil || buildDNSQuery("..") != nil {
		t.Fatal("пустое имя должно отвергаться")
	}
}

func TestParseDNSAnswersCompressedName(t *testing.T) {
	query := buildDNSQuery("chatgpt.com")
	msg := make([]byte, 0, 64)
	msg = append(msg, query...)
	binary.BigEndian.PutUint16(msg[2:4], 0x8180)
	binary.BigEndian.PutUint16(msg[6:8], 1) // ANCOUNT
	msg = append(msg,
		0xc0, 0x0c, // указатель на имя вопроса
		0x00, 0x01, // A
		0x00, 0x01, // IN
		0x00, 0x00, 0x00, 0x3c, // TTL
		0x00, 0x04, // RDLENGTH
		8, 6, 112, 0,
	)
	ips, err := parseDNSAnswers(msg)
	if err != nil {
		t.Fatal(err)
	}
	if len(ips) != 1 || !ips[0].Equal(net.IPv4(8, 6, 112, 0)) {
		t.Fatalf("ips = %v", ips)
	}
}

func TestParseBootstrap(t *testing.T) {
	got := parseBootstrap(" 192.144.59.14, 186.246.49.127 ,[2606:4700::1],bad")
	if len(got) != 3 {
		t.Fatalf("bootstrap = %v", got)
	}
	if got[0] != "192.144.59.14" || got[2] != "2606:4700::1" {
		t.Fatalf("bootstrap = %v", got)
	}
}
