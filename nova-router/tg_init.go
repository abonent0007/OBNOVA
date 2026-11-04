package main

import (
	"crypto/aes"
	"crypto/cipher"
	"encoding/binary"
	"fmt"
	"net"
)

// Разбор obfuscated2-инициализации Telegram и нарезка потока на MTProto-пакеты.
//
// Формат init (64 байта): первые 56 байт — случайные и уходят как есть, хвост
// [56:64] зашифрован AES-CTR (ключ init[8:40], IV init[40:56]) и содержит тег
// транспорта (LE32) и DC (int16, минус = media). Keystream начинается с нуля,
// поэтому перед чтением хвоста его нужно продвинуть на 64 нулевых байта.

type tgProto int

const (
	tgAbridged tgProto = iota
	tgIntermediate
	tgPaddedIntermediate
)

type tgInit struct {
	raw   []byte
	proto tgProto
	dc    int
	media bool
}

const (
	tgTagAbridged     = 0xEFEFEFEF
	tgTagIntermediate = 0xEEEEEEEE
	tgTagPadded       = 0xDDDDDDDD
)

func parseTGInit(init []byte, fallbackDC int, fallbackMedia bool) (*tgInit, error) {
	if len(init) < 64 {
		return nil, fmt.Errorf("init короче 64 байт: %d", len(init))
	}
	block, err := aes.NewCipher(init[8:40])
	if err != nil {
		return nil, fmt.Errorf("AES init: %w", err)
	}
	stream := cipher.NewCTR(block, init[40:56])
	zeros := make([]byte, 64)
	keystream := make([]byte, 64)
	stream.XORKeyStream(keystream, zeros)

	plain := make([]byte, 8)
	for i := 0; i < 8; i++ {
		plain[i] = init[56+i] ^ keystream[56+i]
	}
	tag := binary.LittleEndian.Uint32(plain[0:4])
	var proto tgProto
	switch tag {
	case tgTagAbridged:
		proto = tgAbridged
	case tgTagIntermediate:
		proto = tgIntermediate
	case tgTagPadded:
		proto = tgPaddedIntermediate
	default:
		return nil, fmt.Errorf("неизвестный тег MTProto 0x%08x", tag)
	}
	dcSigned := int(int16(binary.LittleEndian.Uint16(plain[4:6])))
	media := dcSigned < 0
	dc := dcSigned
	if dc < 0 {
		dc = -dc
	}
	if dc > 10000 {
		dc -= 10000
	}

	raw := append([]byte(nil), init[:64]...)
	if (dc < 1 || dc > 5) && fallbackDC >= 1 && fallbackDC <= 5 {
		dc = fallbackDC
		media = fallbackMedia
		// Патчим зашифрованный DC в init: C' = C ^ P_old ^ P_new.
		// Для media DC знак отрицательный — KWS различает kwsN и kwsN-1.
		signed := int16(dc)
		if media {
			signed = -signed
		}
		newPlain := make([]byte, 2)
		binary.LittleEndian.PutUint16(newPlain, uint16(signed))
		raw[60] ^= plain[4] ^ newPlain[0]
		raw[61] ^= plain[5] ^ newPlain[1]
	}
	return &tgInit{raw: raw, proto: proto, dc: dc, media: media}, nil
}

// tgSplitter режет obfuscated2-поток на целые MTProto-пакеты.
// Наружу отдаётся ciphertext; plaintext нужен только для чтения длин.
type tgSplitter struct {
	stream    cipher.Stream
	proto     tgProto
	cipherBuf []byte
	plainBuf  []byte
	scratch   []byte
	disabled  bool
}

func newTGSplitter(init []byte, proto tgProto) (*tgSplitter, error) {
	block, err := aes.NewCipher(init[8:40])
	if err != nil {
		return nil, err
	}
	stream := cipher.NewCTR(block, init[40:56])
	zeros := make([]byte, 64)
	dummy := make([]byte, 64)
	stream.XORKeyStream(dummy, zeros)
	return &tgSplitter{stream: stream, proto: proto}, nil
}

// split возвращает готовые ciphertext-пакеты. Неполный хвост остаётся в буфере.
func (s *tgSplitter) split(chunk []byte) [][]byte {
	if len(chunk) == 0 {
		return nil
	}
	if s.disabled {
		return [][]byte{append([]byte(nil), chunk...)}
	}
	s.cipherBuf = append(s.cipherBuf, chunk...)
	if cap(s.scratch) < len(chunk) {
		s.scratch = make([]byte, len(chunk))
	}
	plain := s.scratch[:len(chunk)]
	s.stream.XORKeyStream(plain, chunk)
	s.plainBuf = append(s.plainBuf, plain...)

	var out [][]byte
	for {
		size, status := s.peek()
		if status == 0 {
			return out
		}
		if status == 2 {
			if len(s.cipherBuf) > 0 {
				out = append(out, append([]byte(nil), s.cipherBuf...))
			}
			s.cipherBuf = nil
			s.plainBuf = nil
			s.disabled = true
			return out
		}
		if len(s.cipherBuf) < size || len(s.plainBuf) < size {
			return out
		}
		out = append(out, append([]byte(nil), s.cipherBuf[:size]...))
		s.cipherBuf = s.cipherBuf[size:]
		s.plainBuf = s.plainBuf[size:]
	}
}

// peek: 0 — нужно больше данных, 1 — готов пакет size, 2 — кадрирование сломалось.
func (s *tgSplitter) peek() (int, int) {
	if len(s.plainBuf) == 0 {
		return 0, 0
	}
	switch s.proto {
	case tgAbridged:
		tag := s.plainBuf[0] & 0x7F
		header := 1
		var payload int
		if tag == 0x7F {
			if len(s.plainBuf) < 4 {
				return 0, 0
			}
			header = 4
			payload = (int(s.plainBuf[1]) | int(s.plainBuf[2])<<8 | int(s.plainBuf[3])<<16) * 4
		} else {
			payload = int(tag) * 4
		}
		if payload <= 0 {
			return 0, 2
		}
		return header + payload, 1
	case tgIntermediate, tgPaddedIntermediate:
		if len(s.plainBuf) < 4 {
			return 0, 0
		}
		payload := int(binary.LittleEndian.Uint32(s.plainBuf[:4]) & 0x7FFFFFFF)
		if payload <= 0 {
			return 0, 2
		}
		return 4 + payload, 1
	}
	return 0, 2
}

// tgExactTargets — адреса Telegram, где media-флаг известен точно
// (таблица из tgrelay, совпадает с публичными DC-адресами).
var tgExactTargets = map[string]struct {
	dc    int
	media bool
}{
	"149.154.175.50": {1, false},
	"149.154.175.51": {1, false},
	"149.154.175.53": {1, false},
	"149.154.175.54": {1, false},
	"149.154.175.55": {1, false},
	"149.154.175.59": {1, false},
	"149.154.175.52": {1, true},
	"149.154.167.35": {2, false},
	"149.154.167.36": {2, false},
	"149.154.167.41": {2, false},
	"149.154.167.50": {2, false},
	"149.154.167.51": {2, false},
	"149.154.167.220": {2, false},
	"95.161.76.100":  {2, false},
	"149.154.162.123": {2, true},
	"149.154.167.151": {2, true},
	"149.154.167.222": {2, true},
	"149.154.167.223": {2, true},
	"149.154.167.99": {2, true},
	"149.154.175.100": {3, false},
	"149.154.175.101": {3, false},
	"149.154.175.102": {3, true},
	"149.154.164.250": {4, true},
	"149.154.165.111": {4, true},
	"149.154.166.120": {4, true},
	"149.154.166.121": {4, true},
	"149.154.167.91": {4, false},
	"149.154.167.92": {4, false},
	"149.154.167.118": {4, true},
	"91.108.56.100": {5, false},
	"91.108.56.101": {5, false},
	"91.108.56.102": {5, true},
	"91.108.56.116": {5, false},
	"91.108.56.126": {5, false},
	"91.108.56.128": {5, true},
	"91.108.56.123": {5, true},
	"91.108.56.151": {5, true},
	"149.154.171.5": {5, false},
	"91.105.192.100": {203, false},
}

// fallbackDCForIP — таблица DC по адресу, если клиент прислал dc=0.
func fallbackDCForIP(host string) (int, bool) {
	if exact, ok := tgExactTargets[host]; ok {
		return exact.dc, exact.media
	}
	ip := net.ParseIP(host)
	if ip == nil {
		return 0, false
	}
	v4 := ip.To4()
	if v4 == nil {
		return 0, false
	}
	switch {
	case v4[0] == 149 && v4[1] == 154 && v4[2] == 167:
		switch {
		case v4[3] >= 91 && v4[3] <= 92:
			return 4, false
		case v4[3] >= 40 && v4[3] <= 51:
			return 2, false
		}
	case v4[0] == 149 && v4[1] == 154 && v4[2] == 175:
		if v4[3] >= 50 && v4[3] <= 59 {
			return 1, false
		}
		if v4[3] == 100 {
			return 3, false
		}
	case v4[0] == 91 && v4[1] == 108 && v4[2] == 56:
		return 5, false
	case v4[0] == 149 && v4[1] == 154 && v4[2] == 171:
		return 5, false
	}
	return 0, false
}
