package main

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/binary"
	"testing"
)

// buildInit повторяет то, что делает клиент Telegram: 64 случайных байта,
// тег и DC в хвосте, затем хвост шифруется CTR-потоком.
func buildInit(t *testing.T, tag uint32, dc int16) (init []byte, clientStream cipher.Stream) {
	t.Helper()
	buf := make([]byte, 64)
	if _, err := rand.Read(buf); err != nil {
		t.Fatal(err)
	}
	buf[0] = 0x00
	binary.LittleEndian.PutUint32(buf[56:60], tag)
	binary.LittleEndian.PutUint16(buf[60:62], uint16(dc))
	block, err := aes.NewCipher(buf[8:40])
	if err != nil {
		t.Fatal(err)
	}
	wire := make([]byte, 64)
	stream := cipher.NewCTR(block, buf[40:56])
	stream.XORKeyStream(wire, buf)
	init = append(append([]byte(nil), buf[:56]...), wire[56:64]...)
	clientStream = cipher.NewCTR(block, buf[40:56])
	clientStream.XORKeyStream(make([]byte, 64), make([]byte, 64))
	return init, clientStream
}

func TestParseTGInitAbridged(t *testing.T) {
	init, _ := buildInit(t, tgTagAbridged, 2)
	info, err := parseTGInit(init, 0, false)
	if err != nil {
		t.Fatal(err)
	}
	if info.proto != tgAbridged || info.dc != 2 || info.media {
		t.Fatalf("proto=%v dc=%d media=%v", info.proto, info.dc, info.media)
	}
}

func TestParseTGInitMediaAndDCFromIP(t *testing.T) {
	init, _ := buildInit(t, tgTagIntermediate, -4)
	info, err := parseTGInit(init, 0, false)
	if err != nil {
		t.Fatal(err)
	}
	if info.proto != tgIntermediate || info.dc != 4 || !info.media {
		t.Fatalf("proto=%v dc=%d media=%v", info.proto, info.dc, info.media)
	}
}

func TestParseTGInitPatchesZeroDC(t *testing.T) {
	init, _ := buildInit(t, tgTagAbridged, 0)
	info, err := parseTGInit(init, 3, false)
	if err != nil {
		t.Fatal(err)
	}
	if info.dc != 3 {
		t.Fatalf("dc=%d", info.dc)
	}
	// Патч должен читаться тем же способом, что и у KWS.
	patched, err := parseTGInit(info.raw, 0, false)
	if err != nil {
		t.Fatal(err)
	}
	if patched.dc != 3 {
		t.Fatalf("после патча dc=%d", patched.dc)
	}
}

func TestParseTGInitMediaFallbackPatchesNegativeDC(t *testing.T) {
	init, _ := buildInit(t, tgTagAbridged, 0)
	info, err := parseTGInit(init, 2, true)
	if err != nil {
		t.Fatal(err)
	}
	if info.dc != 2 || !info.media {
		t.Fatalf("dc=%d media=%v", info.dc, info.media)
	}
	patched, err := parseTGInit(info.raw, 0, false)
	if err != nil {
		t.Fatal(err)
	}
	if patched.dc != 2 || !patched.media {
		t.Fatalf("после патча dc=%d media=%v", patched.dc, patched.media)
	}
}

func TestSplitterAbridged(t *testing.T) {
	init, stream := buildInit(t, tgTagAbridged, 2)
	splitter, err := newTGSplitter(init, tgAbridged)
	if err != nil {
		t.Fatal(err)
	}
	payload := bytes.Repeat([]byte{0xAB}, 16)
	frame := append([]byte{byte(len(payload) / 4)}, payload...)
	cipherFrame := make([]byte, len(frame))
	stream.XORKeyStream(cipherFrame, frame)

	parts := splitter.split(cipherFrame[:5])
	parts = append(parts, splitter.split(cipherFrame[5:])...)
	if len(parts) != 1 || !bytes.Equal(parts[0], cipherFrame) {
		t.Fatalf("abridged split: %d частей", len(parts))
	}
}

func TestSplitterIntermediateTwoPackets(t *testing.T) {
	init, stream := buildInit(t, tgTagIntermediate, 2)
	splitter, err := newTGSplitter(init, tgIntermediate)
	if err != nil {
		t.Fatal(err)
	}
	frame := func(p []byte) []byte {
		out := make([]byte, 4+len(p))
		binary.LittleEndian.PutUint32(out[:4], uint32(len(p)))
		copy(out[4:], p)
		return out
	}
	plain := append(frame([]byte("abcd")), frame([]byte("efghij"))...)
	cipherText := make([]byte, len(plain))
	stream.XORKeyStream(cipherText, plain)

	parts := splitter.split(cipherText)
	if len(parts) != 2 {
		t.Fatalf("ожидалось 2 пакета, получено %d", len(parts))
	}
	if !bytes.Equal(parts[0], cipherText[:8]) || !bytes.Equal(parts[1], cipherText[8:]) {
		t.Fatal("границы пакетов не совпали")
	}
}
