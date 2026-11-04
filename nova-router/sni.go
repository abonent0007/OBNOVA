package main

import (
	"bufio"
	"encoding/binary"
	"fmt"
	"net"
	"strings"
	"time"
)

// peekTLSClientHello читает первый TLS-рекорд, не забирая его из потока:
// байты остаются в bufio.Reader и уходят в upstream после CONNECT.
//
// Нужен только для IP-литералов: Opera HTTP CONNECT ожидает FQDN и часто
// отказывает на `CONNECT 142.251.x.x:443`. SNI из ClientHello даёт имя,
// которое можно подставить в CONNECT, не меняя сам TLS-поток.
func peekTLSClientHello(conn net.Conn) (*bufio.Reader, []byte, string) {
	reader := bufio.NewReaderSize(conn, 16*1024)
	_ = conn.SetReadDeadline(time.Now().Add(1 * time.Second))
	defer conn.SetReadDeadline(time.Time{})

	header, err := reader.Peek(5)
	if err != nil || header[0] != 0x16 {
		return reader, nil, ""
	}
	recordLen := int(binary.BigEndian.Uint16(header[3:5]))
	if recordLen <= 0 || recordLen > 16*1024 {
		return reader, nil, ""
	}
	record, err := reader.Peek(5 + recordLen)
	if err != nil {
		return reader, nil, ""
	}
	sni := parseSNI(record[5:])
	if sni == "" {
		return reader, nil, ""
	}
	return reader, record, sni
}

func parseSNI(handshake []byte) string {
	if len(handshake) < 4 || handshake[0] != 1 {
		return ""
	}
	length := int(handshake[1])<<16 | int(handshake[2])<<8 | int(handshake[3])
	if length <= 0 || len(handshake) < 4+length {
		return ""
	}
	body := handshake[4 : 4+length]
	if len(body) < 34 {
		return ""
	}
	pos := 34 // version(2) + random(32)
	if pos >= len(body) {
		return ""
	}
	sessionLen := int(body[pos])
	pos += 1 + sessionLen
	if pos+2 > len(body) {
		return ""
	}
	cipherLen := int(binary.BigEndian.Uint16(body[pos : pos+2]))
	pos += 2 + cipherLen
	if pos >= len(body) {
		return ""
	}
	compressionLen := int(body[pos])
	pos += 1 + compressionLen
	if pos+2 > len(body) {
		return ""
	}
	extensionsLen := int(binary.BigEndian.Uint16(body[pos : pos+2]))
	pos += 2
	if extensionsLen < 0 || pos+extensionsLen > len(body) {
		return ""
	}
	end := pos + extensionsLen
	for pos+4 <= end {
		typ := binary.BigEndian.Uint16(body[pos : pos+2])
		size := int(binary.BigEndian.Uint16(body[pos+2 : pos+4]))
		pos += 4
		if size < 0 || pos+size > end {
			return ""
		}
		if typ == 0 {
			return parseSNIExtension(body[pos : pos+size])
		}
		pos += size
	}
	return ""
}

func parseSNIExtension(data []byte) string {
	if len(data) < 2 {
		return ""
	}
	listLen := int(binary.BigEndian.Uint16(data[:2]))
	if listLen <= 0 || 2+listLen > len(data) {
		return ""
	}
	pos := 2
	end := 2 + listLen
	for pos+3 <= end {
		nameType := data[pos]
		nameLen := int(binary.BigEndian.Uint16(data[pos+1 : pos+3]))
		pos += 3
		if nameLen <= 0 || pos+nameLen > end {
			return ""
		}
		if nameType == 0 {
			name := strings.TrimSpace(string(data[pos : pos+nameLen]))
			if name == "" || strings.ContainsAny(name, " \t\r\n") {
				return ""
			}
			return name
		}
		pos += nameLen
	}
	return ""
}

// validSNI отсекает мусор, который нельзя отправлять в CONNECT.
func validSNI(raw string) (string, error) {
	sni := strings.TrimSpace(raw)
	if sni == "" || len(sni) > 253 {
		return "", fmt.Errorf("пустой или слишком длинный SNI")
	}
	if strings.HasPrefix(sni, ".") || strings.HasSuffix(sni, ".") || strings.Contains(sni, "..") {
		return "", fmt.Errorf("плохой SNI: %q", sni)
	}
	if strings.ContainsAny(sni, " \t\r\n") {
		return "", fmt.Errorf("пробелы в SNI: %q", sni)
	}
	for _, label := range strings.Split(sni, ".") {
		if label == "" || len(label) > 63 {
			return "", fmt.Errorf("плохая метка SNI: %q", label)
		}
	}
	return sni, nil
}
