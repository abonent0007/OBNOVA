package main

import (
	"os"
	"testing"
)

// Живая проверка WSS-клиента против настоящего реле.
// Запускается только при заданных OBNOVA_RELAY_URL и OBNOVA_RELAY_SECRET:
//
//	OBNOVA_RELAY_URL=wss://.../apiws OBNOVA_RELAY_SECRET=... go test -run LiveRelay -v
func TestLiveRelayHandshake(t *testing.T) {
	relayURL := os.Getenv("OBNOVA_RELAY_URL")
	secret := os.Getenv("OBNOVA_RELAY_SECRET")
	if relayURL == "" || secret == "" {
		t.Skip("нет OBNOVA_RELAY_URL/OBNOVA_RELAY_SECRET")
	}
	conn, err := dialWebSocket(relayURL+"?dc=2", secret, "")
	if err != nil {
		t.Fatalf("реле не открылось: %v", err)
	}
	_ = conn.Close()
}
