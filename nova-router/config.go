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
	SlotWS     = "ws"
	// SlotDNSAI резолвит имя через DNS-AI (DoH) и соединяется напрямую с
	// полученным адресом. Это не прокси: DNS-AI отдаёт для нейросетей рабочие
	// адреса, а трафик до них должен идти мимо WARP/Opera.
	SlotDNSAI = "dnsai"
)

type Slot struct {
	Type   string `json:"type"`
	Addr   string `json:"addr,omitempty"`
	URL    string `json:"url,omitempty"`
	Secret string `json:"secret,omitempty"`
	// Via — необязательный SOCKS5-адрес, через который открывается WSS.
	// Так реле Telegram идёт через MASQUE (127.0.0.1:1379), как на ПК
	// (warp-socks), а не напрямую с телефона.
	Via string `json:"via,omitempty"`
	// RelayVia — SOCKS5-адрес для соединения с релеем DNS-AI. Прямой TCP до
	// релея на мобильных сетях проходит, а TLS режет DPI; через DESYNC — нет.
	RelayVia string `json:"relayVia,omitempty"`
	// UDP — явная capability транспорта, заданная Kotlin-фабрикой. Не
	// выводится из имени слота или его типа.
	UDP bool `json:"udp,omitempty"`
}

type Rule struct {
	Name    string   `json:"name"`
	Domains []string `json:"domains"`
	CIDRs   []string `json:"cidrs,omitempty"`
	// Order — legacy TCP-порядок старых конфигов. Новые конфиги пишут TCPOrder.
	Order    []string `json:"order,omitempty"`
	TCPOrder []string `json:"tcpOrder,omitempty"`
	UDPOrder []string `json:"udpOrder,omitempty"`
	// AllowQUIC разрешает UDP/443 для этого правила. Это именно порт:
	// SupportsUDP и udpOrder проверяются отдельно.
	AllowQUIC bool `json:"allowQuic,omitempty"`
}

func (r Rule) tcpOrder() []string {
	if len(r.TCPOrder) > 0 {
		return r.TCPOrder
	}
	return r.Order
}

// Config — скомпилированная политика из Kotlin (RoutingPolicy/SlotResolver):
// слоты, правила по доменам и порядок по умолчанию.
type Config struct {
	Slots           map[string]Slot `json:"slots"`
	Rules           []Rule          `json:"rules"`
	DefaultOrder    []string        `json:"defaultOrder"`
	DefaultUDPOrder []string        `json:"defaultUdpOrder,omitempty"`
	// Strict — Strict Full Tunnel: ни слот, ни порядок, ни правило не имеют
	// права ссылаться на direct. Проверяется независимо от Kotlin-фабрики.
	Strict bool `json:"strict,omitempty"`
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

func (c Config) SupportsUDP(slot string) bool {
	s, ok := c.Slots[slot]
	return ok && s.UDP
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
		case SlotDNSAI:
			// DNS-AI — resolver, а не egress-транспорт. Старые конфиги с
			// dnsai-слотом должны отклоняться, а не молча обслуживаться.
			return fmt.Errorf("слот %s: dnsai теперь resolver-only и не может быть egress-слотом", name)
		case SlotWS:
			if slot.URL == "" {
				return fmt.Errorf("слот %s: пустой URL реле", name)
			}
			if !strings.HasPrefix(slot.URL, "wss://") {
				return fmt.Errorf("слот %s: реле должно быть wss://", name)
			}
		default:
			return fmt.Errorf("слот %s: неизвестный тип %q", name, slot.Type)
		}
	}
	// Strict проверяется до ссылок на слоты: конфигурация с direct в порядке
	// обязана быть отклонена именно как Strict-нарушение, даже если слота
	// direct в ней нет и обычная валидация споткнулась бы позже.
	if c.Strict {
		if err := c.validateStrict(); err != nil {
			return err
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
	for _, name := range c.DefaultUDPOrder {
		if _, ok := c.Slots[name]; !ok {
			return fmt.Errorf("defaultUdpOrder ссылается на неизвестный слот %q", name)
		}
	}
	for _, rule := range c.Rules {
		for _, name := range rule.tcpOrder() {
			if _, ok := c.Slots[name]; !ok {
				return fmt.Errorf("правило %q: tcpOrder ссылается на неизвестный слот %q", rule.Name, name)
			}
		}
		for _, name := range rule.UDPOrder {
			if _, ok := c.Slots[name]; !ok {
				return fmt.Errorf("правило %q: udpOrder ссылается на неизвестный слот %q", rule.Name, name)
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

// validateStrict отклоняет любую Strict-конфигурацию, в которой остался direct.
//
// Валидатор ничего не исправляет: удаление direct здесь означало бы «починить»
// политику и запустить её, хотя источник конфигурации мог ошибиться. Strict при
// такой ошибке не стартует, и вызывающий не имеет права откатываться к старой
// или обычной конфигурации.
func (c Config) validateStrict() error {
	for name, slot := range c.Slots {
		if strings.EqualFold(name, SlotDirect) {
			return fmt.Errorf("strict: слот %q запрещён", name)
		}
		if strings.EqualFold(slot.Type, SlotDirect) {
			return fmt.Errorf("strict: слот %q имеет тип direct", name)
		}
	}
	assertNoDirect := func(where string, order []string) error {
		for _, name := range order {
			if strings.EqualFold(name, SlotDirect) {
				return fmt.Errorf("strict: direct запрещён в %s", where)
			}
		}
		return nil
	}
	if err := assertNoDirect("defaultOrder", c.DefaultOrder); err != nil {
		return err
	}
	if err := assertNoDirect("defaultUdpOrder", c.DefaultUDPOrder); err != nil {
		return err
	}
	for _, rule := range c.Rules {
		// Эти два правила по определению выражают DIRECT-политику, даже если
		// кто-то вручную переписал их порядок. В Strict их не должно быть.
		if rule.Name == "exclude" || rule.Name == "local-tld" {
			return fmt.Errorf("strict: правило %q запрещено", rule.Name)
		}
		if err := assertNoDirect(fmt.Sprintf("правиле %q (tcpOrder)", rule.Name), rule.tcpOrder()); err != nil {
			return err
		}
		if err := assertNoDirect(fmt.Sprintf("правиле %q (udpOrder)", rule.Name), rule.UDPOrder); err != nil {
			return err
		}
	}
	return nil
}

// OrderFor возвращает порядок слотов: для IP-литерала проверяются CIDR-правила,
// для имени — доменные. Раньше CIDR не поддерживался, и Cronet/YouTube,
// приходящие с IP вместо hostname, всегда падали в defaultOrder (primary).
func (c Config) matchRule(host string) (Rule, string, bool) {
	normalized := normalizeHost(host)
	addr, isIP := parseAddr(normalized)
	for _, rule := range c.Rules {
		if isIP {
			for _, cidr := range rule.CIDRs {
				prefix, err := netip.ParsePrefix(cidr)
				if err == nil && prefix.Contains(addr) {
					return rule, ruleName(rule), true
				}
			}
			continue
		}
		for _, domain := range rule.Domains {
			if matchDomain(normalized, domain) {
				return rule, ruleName(rule), true
			}
		}
	}
	return Rule{}, "default", false
}

func (c Config) OrderFor(host string) ([]string, string) {
	rule, name, ok := c.matchRule(host)
	if !ok {
		return c.DefaultOrder, name
	}
	return rule.tcpOrder(), name
}

// UDPOrderFor возвращает UDP-порядок правила. matched=false означает, что
// правило не найдено и применяется defaultUdpOrder; matched=true с пустым
// order означает явный DROP, а не переход к default.
func (c Config) UDPOrderFor(host string) ([]string, string, bool) {
	rule, name, ok := c.matchRule(host)
	if !ok {
		return c.DefaultUDPOrder, name, false
	}
	return rule.UDPOrder, name, true
}

func (c Config) UDPAllowsQUIC(host string) bool {
	rule, _, ok := c.matchRule(host)
	return ok && rule.AllowQUIC
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
