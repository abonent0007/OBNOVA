package main

import (
	"encoding/json"
	"fmt"
	"net/netip"
	"os"
	"strings"
)

const (
	SlotSocks5 = "socks5"
	SlotHTTP   = "http"
	SlotDirect = "direct"
)

type Slot struct {
	Type string `json:"type"`
	Addr string `json:"addr,omitempty"`
}

type Rule struct {
	Name    string   `json:"name"`
	Domains []string `json:"domains"`
	CIDRs   []string `json:"cidrs,omitempty"`
	Order   []string `json:"order"`
}

// Config — скомпилированная политика из Kotlin (RoutingPolicy/SlotResolver):
// слоты, правила по доменам и порядок по умолчанию.
type Config struct {
	Slots        map[string]Slot `json:"slots"`
	Rules        []Rule          `json:"rules"`
	DefaultOrder []string        `json:"defaultOrder"`
}

func LoadConfig(path string) (Config, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return Config{}, err
	}
	var cfg Config
	if err := json.Unmarshal(raw, &cfg); err != nil {
		return Config{}, fmt.Errorf("разбор конфигурации: %w", err)
	}
	if err := cfg.validate(); err != nil {
		return Config{}, err
	}
	return cfg, nil
}

func (c Config) validate() error {
	if len(c.Slots) == 0 {
		return fmt.Errorf("в конфигурации нет слотов")
	}
	if len(c.DefaultOrder) == 0 {
		return fmt.Errorf("пустой defaultOrder")
	}
	for name, slot := range c.Slots {
		switch slot.Type {
		case SlotSocks5, SlotHTTP:
			if slot.Addr == "" {
				return fmt.Errorf("слот %s: пустой адрес", name)
			}
		case SlotDirect:
		default:
			return fmt.Errorf("слот %s: неизвестный тип %q", name, slot.Type)
		}
	}
	// Порядок ссылается на слоты по имени. Опечатка в имени раньше молча
	// выпадала из цепочки: Router.Dial пропускал отсутствующий слот и уходил
	// в следующий, вплоть до DIRECT. Теперь такой конфиг не загружается.
	for _, name := range c.DefaultOrder {
		if _, ok := c.Slots[name]; !ok {
			return fmt.Errorf("defaultOrder ссылается на неизвестный слот %q", name)
		}
	}
	for _, rule := range c.Rules {
		for _, name := range rule.Order {
			if _, ok := c.Slots[name]; !ok {
				return fmt.Errorf("правило %q ссылается на неизвестный слот %q", rule.Name, name)
			}
		}
		for _, cidr := range rule.CIDRs {
			if _, err := netip.ParsePrefix(cidr); err != nil {
				return fmt.Errorf("правило %q: плохой CIDR %q: %w", rule.Name, cidr, err)
			}
		}
	}
	return nil
}

// OrderFor возвращает порядок слотов: для IP-литерала проверяются CIDR-правила,
// для имени — доменные. Раньше CIDR не поддерживался, и Cronet/YouTube,
// приходящие с IP вместо hostname, всегда падали в defaultOrder (primary).
func (c Config) OrderFor(host string) ([]string, string) {
	normalized := normalizeHost(host)
	addr, isIP := parseAddr(normalized)
	for _, rule := range c.Rules {
		if isIP {
			for _, cidr := range rule.CIDRs {
				prefix, err := netip.ParsePrefix(cidr)
				if err == nil && prefix.Contains(addr) {
					return rule.Order, ruleName(rule)
				}
			}
			continue
		}
		for _, domain := range rule.Domains {
			if matchDomain(normalized, domain) {
				return rule.Order, ruleName(rule)
			}
		}
	}
	return c.DefaultOrder, "default"
}

func parseAddr(host string) (netip.Addr, bool) {
	addr, err := netip.ParseAddr(host)
	if err != nil {
		return netip.Addr{}, false
	}
	return addr.Unmap(), true
}

func ruleName(rule Rule) string {
	if rule.Name != "" {
		return rule.Name
	}
	return "rule"
}

func normalizeHost(host string) string {
	return strings.TrimSuffix(strings.ToLower(strings.TrimSpace(host)), ".")
}

func matchDomain(host, domain string) bool {
	d := normalizeHost(domain)
	if d == "" || host == "" {
		return false
	}
	return host == d || strings.HasSuffix(host, "."+d)
}
