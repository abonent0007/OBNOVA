package main

import (
	"encoding/json"
	"os"
	"sync"
	"time"
)

type SlotStats struct {
	Attempts     int64   `json:"attempts"`
	Successes    int64   `json:"successes"`
	Failures     int64   `json:"failures"`
	LastLatencyMs int64  `json:"lastLatencyMs"`
	EwmaMs       float64 `json:"ewmaMs"`
}

type Stats struct {
	mu     sync.Mutex
	slots  map[string]*SlotStats
	routes map[string]int64
}

func NewStats() *Stats {
	return &Stats{
		slots:  make(map[string]*SlotStats),
		routes: make(map[string]int64),
	}
}

func (s *Stats) recordRoute(route string) {
	if route == "" {
		route = "default"
	}
	s.mu.Lock()
	s.routes[route]++
	s.mu.Unlock()
}

func (s *Stats) record(slot string, latency time.Duration, ok bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	entry := s.slots[slot]
	if entry == nil {
		entry = &SlotStats{}
		s.slots[slot] = entry
	}
	entry.Attempts++
	ms := float64(latency.Microseconds()) / 1000.0
	if ok {
		entry.Successes++
		entry.LastLatencyMs = int64(ms + 0.5)
		if entry.EwmaMs == 0 {
			entry.EwmaMs = ms
		} else {
			entry.EwmaMs = entry.EwmaMs*0.7 + ms*0.3
		}
	} else {
		entry.Failures++
	}
}

func (s *Stats) snapshot() map[string]SlotStats {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make(map[string]SlotStats, len(s.slots))
	for name, entry := range s.slots {
		out[name] = *entry
	}
	return out
}

func (s *Stats) routeSnapshot() map[string]int64 {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make(map[string]int64, len(s.routes))
	for name, count := range s.routes {
		out[name] = count
	}
	return out
}

func (s *Stats) writeFile(path string, port int) {
	if path == "" {
		return
	}
	payload := struct {
		Updated string               `json:"updated"`
		Port    int                  `json:"port"`
		Slots   map[string]SlotStats `json:"slots"`
		Routes  map[string]int64     `json:"routes"`
	}{
		Updated: time.Now().Format(time.RFC3339),
		Port:    port,
		Slots:   s.snapshot(),
		Routes:  s.routeSnapshot(),
	}
	raw, err := json.Marshal(payload)
	if err != nil {
		return
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, raw, 0o600); err != nil {
		return
	}
	_ = os.Rename(tmp, path)
}
