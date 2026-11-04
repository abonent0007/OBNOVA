package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func TestStatsRecordsFallbackAndLatency(t *testing.T) {
	proxy := startFakeSocks5(t)
	stats := NewStats()
	cfg := Config{
		Slots: map[string]Slot{
			"primary":   {Type: SlotSocks5, Addr: "127.0.0.1:1"},
			"secondary": {Type: SlotSocks5, Addr: proxy.Addr().String()},
		},
		DefaultOrder: []string{"primary", "secondary"},
	}
	conn, slot, err := (Router{cfg: cfg, stats: stats}).Dial("example.com", 443)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	if slot != "secondary" {
		t.Fatalf("слот %s", slot)
	}
	snapshot := stats.snapshot()
	if snapshot["primary"].Failures != 1 || snapshot["primary"].Attempts != 1 {
		t.Fatalf("primary: %+v", snapshot["primary"])
	}
	if snapshot["secondary"].Successes != 1 {
		t.Fatalf("secondary: %+v", snapshot["secondary"])
	}
}

func TestStatsWritesValidJson(t *testing.T) {
	stats := NewStats()
	stats.record("primary", 12_000_000, true)
	stats.recordRoute("ru")
	path := filepath.Join(t.TempDir(), "status.json")
	stats.writeFile(path, 1500)

	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var payload struct {
		Updated string               `json:"updated"`
		Port    int                  `json:"port"`
		Slots   map[string]SlotStats `json:"slots"`
		Routes  map[string]int64     `json:"routes"`
	}
	if err := json.Unmarshal(raw, &payload); err != nil {
		t.Fatalf("json: %v (%s)", err, raw)
	}
	if payload.Slots["primary"].LastLatencyMs != 12 {
		t.Fatalf("latency: %+v", payload.Slots["primary"])
	}
	if payload.Port != 1500 {
		t.Fatalf("port: %d", payload.Port)
	}
	if payload.Routes["ru"] != 1 {
		t.Fatalf("routes: %+v", payload.Routes)
	}
	if payload.Updated == "" {
		t.Fatal("нет updated")
	}
}
