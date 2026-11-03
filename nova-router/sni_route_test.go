package main

import (
	"testing"
)

// SNI-маршрут должен побеждать CIDR: иначе исключение для Play Services
// не сработает, ведь запрос приходит на IP из сетей Google.
func TestSNIRuleBeatsCIDR(t *testing.T) {
	cfg := Config{
		Slots: map[string]Slot{
			"primary":   {Type: SlotDirect},
			"secondary": {Type: SlotDirect},
		},
		Rules: []Rule{
			{
				Name:    "apis",
				Domains: []string{"apis.google.com", "gvt1.com"},
				Order:   []string{"primary"},
			},
			{
				Name:  "youtube-ip",
				CIDRs: []string{"142.250.0.0/15", "172.217.0.0/16"},
				Order: []string{"secondary", "primary"},
			},
		},
		DefaultOrder: []string{"primary", "direct"},
	}
	if got := orderOf(cfg, "142.251.144.5"); got != "secondary,primary" {
		t.Fatalf("без SNI должен работать CIDR: %s", got)
	}
	router := Router{cfg: cfg}
	order, name := router.routeOrderFor("142.251.144.5", "android.apis.google.com")
	if name != "apis" || len(order) != 1 || order[0] != "primary" {
		t.Fatalf("SNI-исключение не сработало: %s %v", name, order)
	}
	// youtubei.googleapis.com обязан остаться на SECONDARY.
	order, name = router.routeOrderFor("142.251.144.5", "youtubei.googleapis.com")
	if name != "youtube-ip" || len(order) == 0 || order[0] != "secondary" {
		t.Fatalf("youtubei ушёл не туда: %s %v", name, order)
	}
}
