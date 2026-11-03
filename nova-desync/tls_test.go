package main

import (
	"bytes"
	"encoding/binary"
	"testing"
)

func makeHello(sni string) []byte {
	name := []byte(sni)
	body := make([]byte, 0, 5+len(name))
	body = append(body, byte((3+len(name))>>8), byte(3+len(name)))
	body = append(body, 0x00)
	body = append(body, byte(len(name)>>8), byte(len(name)))
	body = append(body, name...)

	ext := []byte{0x00, 0x00}
	ext = append(ext, byte(len(body)>>8), byte(len(body)))
	ext = append(ext, body...)

	hs := []byte{0x01, 0, 0, 0, 0x03, 0x03}
	hs = append(hs, make([]byte, 32)...)
	hs = append(hs, 0x00)
	hs = append(hs, 0x00, 0x02, 0x13, 0x01)
	hs = append(hs, 0x01, 0x00)
	hs = append(hs, byte(len(ext)>>8), byte(len(ext)))
	hs = append(hs, ext...)

	l := len(hs) - 4
	hs[1], hs[2], hs[3] = byte(l>>16), byte(l>>8), byte(l)

	rec := []byte{0x16, 0x03, 0x01, byte(len(hs) >> 8), byte(len(hs))}
	return append(rec, hs...)
}

func sniof(t *testing.T, rec []byte) string {
	t.Helper()
	info, err := parseHello(rec)
	if err != nil {
		t.Fatalf("parseHello: %v", err)
	}
	for off := info.extStart; off+4 <= info.extEnd; {
		typ := binary.BigEndian.Uint16(rec[off:])
		l := int(binary.BigEndian.Uint16(rec[off+2:]))
		data := off + 4
		if typ == 0 {
			nameLen := int(binary.BigEndian.Uint16(rec[data+3:]))
			return string(rec[data+5 : data+5+nameLen])
		}
		off = data + l
	}
	t.Fatal("SNI не найден")
	return ""
}

func TestParseHello(t *testing.T) {
	hello := makeHello("example.com")
	if _, err := parseHello(hello); err != nil {
		t.Fatalf("parseHello: %v", err)
	}
	if got := sniof(t, hello); got != "example.com" {
		t.Fatalf("SNI=%q", got)
	}
}

func TestAddPadding(t *testing.T) {
	hello := makeHello("example.com")
	out, err := addPadding(hello, 64)
	if err != nil {
		t.Fatalf("addPadding: %v", err)
	}
	if len(out) != len(hello)+68 {
		t.Fatalf("длина %d, ожидалось %d", len(out), len(hello)+68)
	}
	if _, err := parseHello(out); err != nil {
		t.Fatalf("после паддинга: %v", err)
	}
	if binary.BigEndian.Uint16(out[3:5]) != uint16(len(out)-5) {
		t.Fatal("длина записи не пересчитана")
	}
	hs := int(out[6])<<16 | int(out[7])<<8 | int(out[8])
	if hs != len(out)-9 {
		t.Fatal("длина handshake не пересчитана")
	}
	if got := sniof(t, out); got != "example.com" {
		t.Fatalf("SNI после паддинга=%q", got)
	}
}

func TestReplaceSNI(t *testing.T) {
	hello := makeHello("blocked.example")
	out, err := replaceSNI(hello, "www.microsoft.com")
	if err != nil {
		t.Fatalf("replaceSNI: %v", err)
	}
	if _, err := parseHello(out); err != nil {
		t.Fatalf("после замены: %v", err)
	}
	if got := sniof(t, out); got != "www.microsoft.com" {
		t.Fatalf("SNI=%q", got)
	}
	if bytes.Contains(out, []byte("blocked.example")) {
		t.Fatal("старое имя осталось в пакете")
	}
	if binary.BigEndian.Uint16(out[3:5]) != uint16(len(out)-5) {
		t.Fatal("длина записи не пересчитана")
	}
}

func TestSplitRecord(t *testing.T) {
	hello := makeHello("example.com")
	parts, err := splitRecord(hello, 3)
	if err != nil {
		t.Fatalf("splitRecord: %v", err)
	}
	if len(parts) != 3 {
		t.Fatalf("частей %d", len(parts))
	}
	var payload []byte
	for _, p := range parts {
		if len(p) < 5 {
			t.Fatal("короткий рекорд")
		}
		if int(binary.BigEndian.Uint16(p[3:5])) != len(p)-5 {
			t.Fatal("неверная длина чанка")
		}
		payload = append(payload, p[5:]...)
	}
	if !bytes.Equal(payload, hello[5:]) {
		t.Fatal("полезная нагрузка изменилась")
	}
}

func TestParseHelloRejectsGarbage(t *testing.T) {
	if _, err := parseHello([]byte{0x16, 0x03, 0x01, 0, 4, 1, 2, 3, 4}); err == nil {
		t.Fatal("ожидалась ошибка")
	}
}
