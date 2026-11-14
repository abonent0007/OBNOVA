package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func strictBase() Config {
	return Config{
		Slots: map[string]Slot{
			"primary": {Type: SlotSocks5, Addr: "127.0.0.1:1376"},
		},
		Rules:        nil,
		DefaultOrder: []string{"primary"},
		Strict:       true,
	}
}

func TestStrictConfigWithoutDirectPasses(t *testing.T) {
	cfg := strictBase()
	cfg.Rules = []Rule{
		{Name: "ru", Domains: []string{"example.ru"}, TCPOrder: []string{"primary"}},
	}
	if err := cfg.validate(); err != nil {
		t.Fatalf("чистая strict-конфигурация отклонена: %v", err)
	}
}

func TestStrictRejectsDirectSlot(t *testing.T) {
	cfg := strictBase()
	cfg.Slots["direct"] = Slot{Type: SlotDirect}
	if err := cfg.validate(); err == nil || !strings.Contains(err.Error(), "strict") {
		t.Fatalf("ожидался strict-отказ для слота direct, получено: %v", err)
	}
}

func TestStrictRejectsDirectTypeUnderAnotherName(t *testing.T) {
	cfg := strictBase()
	cfg.Slots["fallback"] = Slot{Type: SlotDirect}
	if err := cfg.validate(); err == nil || !strings.Contains(err.Error(), "strict") {
		t.Fatalf("ожидался strict-отказ для типа direct, получено: %v", err)
	}
}

func TestStrictRejectsDirectInDefaultOrder(t *testing.T) {
	cfg := strictBase()
	cfg.DefaultOrder = []string{"primary", "direct"}
	if err := cfg.validate(); err == nil || !strings.Contains(err.Error(), "strict") {
		t.Fatalf("ожидался strict-отказ defaultOrder, получено: %v", err)
	}
}

func TestStrictRejectsDirectInDefaultUDPOrder(t *testing.T) {
	cfg := strictBase()
	cfg.DefaultUDPOrder = []string{"direct"}
	if err := cfg.validate(); err == nil || !strings.Contains(err.Error(), "strict") {
		t.Fatalf("ожидался strict-отказ defaultUdpOrder, получено: %v", err)
	}
}

func TestStrictRejectsDirectInRuleOrders(t *testing.T) {
	cases := []struct {
		name string
		rule Rule
	}{
		{"tcpOrder", Rule{Name: "a", Domains: []string{"a.test"}, TCPOrder: []string{"primary", "direct"}}},
		{"udpOrder", Rule{Name: "b", Domains: []string{"b.test"}, TCPOrder: []string{"primary"}, UDPOrder: []string{"direct"}}},
		{"legacy order", Rule{Name: "c", Domains: []string{"c.test"}, Order: []string{"direct"}}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			cfg := strictBase()
			cfg.Rules = []Rule{tc.rule}
			if err := cfg.validate(); err == nil || !strings.Contains(err.Error(), "strict") {
				t.Fatalf("ожидался strict-отказ %s, получено: %v", tc.name, err)
			}
		})
	}
}

func TestStrictRejectsExcludeAndLocalTLDRules(t *testing.T) {
	for _, name := range []string{"exclude", "local-tld"} {
		t.Run(name, func(t *testing.T) {
			cfg := strictBase()
			cfg.Rules = []Rule{{Name: name, Domains: []string{"x.test"}, TCPOrder: []string{"primary"}}}
			if err := cfg.validate(); err == nil || !strings.Contains(err.Error(), "strict") {
				t.Fatalf("ожидался strict-отказ правила %s, получено: %v", name, err)
			}
		})
	}
}

func TestStrictDoesNotMaskDirectInRuleByCleanDefault(t *testing.T) {
	cfg := strictBase() // defaultOrder чист
	cfg.Rules = []Rule{{Name: "yt", Domains: []string{"youtube.com"}, TCPOrder: []string{"direct"}}}
	if err := cfg.validate(); err == nil {
		t.Fatal("direct в правиле не отклонён при чистом defaultOrder")
	}
}

func TestNonStrictKeepsDirectAllowed(t *testing.T) {
	cfg := Config{
		Slots: map[string]Slot{
			"primary": {Type: SlotSocks5, Addr: "127.0.0.1:1376"},
			"direct":  {Type: SlotDirect},
		},
		Rules:        []Rule{{Name: "exclude", Domains: []string{"x.test"}, TCPOrder: []string{"direct"}}},
		DefaultOrder: []string{"primary", "direct"},
	}
	if err := cfg.validate(); err != nil {
		t.Fatalf("прежняя конфигурация с direct должна оставаться валидной: %v", err)
	}
}

func TestStrictFixtureFromKotlin(t *testing.T) {
	path := filepath.Join("testdata", "strict_config.json")
	if _, err := os.Stat(path); err != nil {
		t.Fatalf("нет фикстуры %s: %v", path, err)
	}
	cfg, err := LoadConfig(path)
	if err != nil {
		t.Fatalf("Go-валидатор отклонил strict-конфигурацию из Kotlin: %v", err)
	}
	if !cfg.Strict {
		t.Fatal("strict-флаг не прочитан из JSON")
	}
	if _, ok := cfg.Slots["direct"]; ok {
		t.Fatal("в strict-фикстуре оказался слот direct")
	}
	for _, order := range append([][]string{cfg.DefaultOrder, cfg.DefaultUDPOrder}, ruleOrders(cfg.Rules)...) {
		for _, name := range order {
			if strings.EqualFold(name, SlotDirect) {
				t.Fatalf("в strict-фикстуре остался direct в порядке %v", order)
			}
		}
	}
}

func ruleOrders(rules []Rule) [][]string {
	out := make([][]string, 0, len(rules)*2)
	for _, rule := range rules {
		out = append(out, rule.tcpOrder(), rule.UDPOrder)
	}
	return out
}
