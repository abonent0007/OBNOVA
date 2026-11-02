package main

import (
	"math/rand"
	"testing"
)

// Повреждённый ClientHello приходит снаружи, и ни одна из функций разбора
// не имеет права паниковать: паника в горутине уносит весь движок.
func TestMalformedInputNeverPanics(t *testing.T) {
	rng := rand.New(rand.NewSource(1))
	for i := 0; i < 2000; i++ {
		size := rng.Intn(300)
		buf := make([]byte, size)
		rng.Read(buf)
		if size > 0 && i%2 == 0 {
			buf[0] = 0x16
		}
		func() {
			defer func() {
				if recovered := recover(); recovered != nil {
					t.Fatalf("паника на %d байтах: %v", size, recovered)
				}
			}()
			_, _ = parseHello(buf)
			_, _ = addPadding(buf, 32)
			_, _ = replaceSNI(buf, "example.org")
			_, _ = splitRecord(buf, 3)
			_ = splitBytes(buf, 3)
		}()
	}
}
