package main

import (
	"bufio"
	"bytes"
	"testing"
)

func TestWSFrameRoundTrip(t *testing.T) {
	for _, size := range []int{0, 1, 125, 126, 65535, 65536, 100000} {
		payload := bytes.Repeat([]byte{0xAB}, size)
		var buf bytes.Buffer
		if err := writeWSFrame(&buf, 0x2, payload); err != nil {
			t.Fatalf("write %d: %v", size, err)
		}
		reader := bufio.NewReader(bytes.NewReader(buf.Bytes()))
		opcode, got, err := readWSFrame(reader)
		if err != nil {
			t.Fatalf("read %d: %v", size, err)
		}
		if opcode != 0x2 || !bytes.Equal(got, payload) {
			t.Fatalf("payload %d искажён (opcode %d, len %d)", size, opcode, len(got))
		}
	}
}

func TestWSFrameReadsUnmaskedServerFrame(t *testing.T) {
	// Серверные фреймы не маскируются: 0x82, длина 5, payload.
	raw := []byte{0x82, 0x05, 'h', 'e', 'l', 'l', 'o'}
	opcode, payload, err := readWSFrame(bufio.NewReader(bytes.NewReader(raw)))
	if err != nil {
		t.Fatal(err)
	}
	if opcode != 0x2 || string(payload) != "hello" {
		t.Fatalf("opcode=%d payload=%q", opcode, payload)
	}
}
