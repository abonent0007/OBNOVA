package main

import (
	"encoding/binary"
	"testing"
)

func clientHelloWithSNI(name string) []byte {
	sni := []byte(name)
	sniList := make([]byte, 5+len(sni))
	binary.BigEndian.PutUint16(sniList[0:2], uint16(3+len(sni)))
	sniList[2] = 0
	binary.BigEndian.PutUint16(sniList[3:5], uint16(len(sni)))
	copy(sniList[5:], sni)
	extension := append([]byte{0, 0}, byte(len(sniList)>>8), byte(len(sniList)))
	extension = append(extension, sniList...)
	extensions := append([]byte{byte(len(extension) >> 8), byte(len(extension))}, extension...)

	body := make([]byte, 0, 64)
	body = append(body, 0x03, 0x03)             // legacy version
	body = append(body, make([]byte, 32)...)    // random
	body = append(body, 0)                      // session id length
	body = append(body, 0x00, 0x02, 0x13, 0x01) // cipher suites
	body = append(body, 0x01, 0x00)             // compression
	body = append(body, extensions...)

	handshake := make([]byte, 4, 4+len(body))
	handshake[0] = 1
	handshake[1] = byte(len(body) >> 16)
	handshake[2] = byte(len(body) >> 8)
	handshake[3] = byte(len(body))
	handshake = append(handshake, body...)

	record := make([]byte, 5, 5+len(handshake))
	record[0] = 0x16
	record[1] = 0x03
	record[2] = 0x01
	binary.BigEndian.PutUint16(record[3:5], uint16(len(handshake)))
	return append(record, handshake...)
}

func TestParseSNI(t *testing.T) {
	hello := clientHelloWithSNI("rr1---sn-oj5hn5-5m.googlevideo.com")
	if got := parseSNI(hello[5:]); got != "rr1---sn-oj5hn5-5m.googlevideo.com" {
		t.Fatalf("SNI: %q", got)
	}
	if got := parseSNI(hello[5:]); got == "" {
		t.Fatal("SNI не найден")
	}
}

func TestParseSNIRejectsJunk(t *testing.T) {
	if got := parseSNI([]byte{1, 2, 3, 4}); got != "" {
		t.Fatalf("ожидалась пустая строка, получено %q", got)
	}
	if _, err := validSNI("bad host name"); err == nil {
		t.Fatal("плохой SNI принят")
	}
	if _, err := validSNI("rr1---sn-oj5hn5-5m.googlevideo.com"); err != nil {
		t.Fatalf("валидный SNI отклонён: %v", err)
	}
}
