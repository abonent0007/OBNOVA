package main

import "testing"

func TestTelegramDC(t *testing.T) {
	cases := map[string]int{
		"149.154.175.50":            1,
		"149.154.167.50":            2,
		"149.154.167.51":            2,
		"149.154.167.41":            2,
		"149.154.162.123":           2,
		"149.154.175.100":           3,
		"149.154.167.91":            4,
		"91.108.56.130":             5,
		"2001:67c:4e8:f002::a":      2,
		"2001:67c:4e8:f005::a":      5,
		"2001:67c:4e8:f002:0:0:0:a": 2,
		"8.8.8.8":                   0,
		"2001:67c:4e8:f009::a":      0,
		"2001:4860:4860::8888":      0,
		"kws2.web.telegram.org":     0,
	}
	for host, want := range cases {
		if got := telegramDC(host); got != want {
			t.Errorf("telegramDC(%q) = %d, want %d", host, got, want)
		}
	}
}
