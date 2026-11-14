package main

import "testing"

func TestSupportsUDP(t *testing.T) {
	cfg := Config{Slots: map[string]Slot{
		"awg":       {Type: SlotSocks5, Addr: "127.0.0.1:1378", UDP: true},
		"secondary": {Type: SlotHTTP, Addr: "127.0.0.1:1371"},
		"ws":        {Type: SlotWS, URL: "wss://relay.example/apiws"},
		"direct":    {Type: SlotDirect, UDP: true},
		"vless":     {Type: SlotSocks5, Addr: "127.0.0.1:1379"},
	}}
	for _, tc := range []struct {
		slot string
		want bool
	}{
		{"awg", true},
		{"direct", true},
		{"secondary", false},
		{"ws", false},
		{"vless", false},
		{"unknown", false},
	} {
		if got := cfg.SupportsUDP(tc.slot); got != tc.want {
			t.Fatalf("SupportsUDP(%q)=%v, want %v", tc.slot, got, tc.want)
		}
	}
}

func TestSupportsUDPUnknownConfigIsFalse(t *testing.T) {
	var cfg Config
	if cfg.SupportsUDP("awg") {
		t.Fatal("пустая конфигурация не должна поддерживать UDP")
	}
}

func TestDNSAISlotIsRejectedAsEgress(t *testing.T) {
	cfg := Config{
		Slots: map[string]Slot{
			"dnsai": {
				Type: SlotDNSAI,
				Addr: "192.144.59.14",
				URL:  "https://dns.dns-ai.ru/dns-query",
			},
		},
		DefaultOrder: []string{"dnsai"},
	}
	if err := cfg.validate(); err == nil {
		t.Fatal("dnsai-слот должен отклоняться как egress")
	}
}

func TestUDPOrderIndependentFromTCP(t *testing.T) {
	cfg := Config{
		Slots: map[string]Slot{
			"secondary": {Type: SlotHTTP, Addr: "127.0.0.1:1371"},
			"primary":   {Type: SlotSocks5, Addr: "127.0.0.1:1378", UDP: true},
			"direct":    {Type: SlotDirect, UDP: true},
		},
		Rules: []Rule{
			{
				Name:     "example",
				Domains:  []string{"example.test"},
				TCPOrder: []string{"secondary", "direct"},
				UDPOrder: []string{"primary"},
			},
			{
				Name:     "drop",
				Domains:  []string{"drop.test"},
				TCPOrder: []string{"primary"},
				// UDPOrder пуст: совпавшее правило обязано дать DROP,
				// а не defaultUdpOrder.
			},
		},
		DefaultOrder:    []string{"direct"},
		DefaultUDPOrder: []string{"direct"},
	}

	tcp, route := cfg.OrderFor("example.test")
	if route != "example" || len(tcp) != 2 || tcp[0] != "secondary" || tcp[1] != "direct" {
		t.Fatalf("TCP order = %v (%s)", tcp, route)
	}
	udp, route, matched := cfg.UDPOrderFor("example.test")
	if !matched || route != "example" || len(udp) != 1 || udp[0] != "primary" {
		t.Fatalf("UDP order = %v (%s, matched=%v)", udp, route, matched)
	}

	udp, route, matched = cfg.UDPOrderFor("other.test")
	if matched || route != "default" || len(udp) != 1 || udp[0] != "direct" {
		t.Fatalf("default UDP = %v (%s, matched=%v)", udp, route, matched)
	}

	udp, route, matched = cfg.UDPOrderFor("drop.test")
	if !matched || route != "drop" || len(udp) != 0 {
		t.Fatalf("пустой UDP order = %v (%s, matched=%v)", udp, route, matched)
	}
}
