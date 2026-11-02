package main

import (
	"encoding/json"
	"fmt"
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
	}
	return nil
}

// OrderFor возвращает порядок слотов и имя правила для домена: первое
// подходящее правило, иначе defaultOrder. Сравнение — по границе метки.
func (c Config) OrderFor(host string) ([]string, string) {
	normalized := normalizeHost(host)
	for _, rule := range c.Rules {
		for _, domain := range rule.Domains {
			if matchDomain(normalized, domain) {
				name := rule.Name
				if name == "" {
					name = "rule"
				}
				return rule.Order, name
			}
		}
	}
	return c.DefaultOrder, "default"
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
