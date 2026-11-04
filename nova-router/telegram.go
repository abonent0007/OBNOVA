package main

import (
	"net/netip"
	"strings"
)

// telegramDC возвращает номер дата-центра по адресу, к которому подключился
// клиент. Официальное реле `kwsN.web.telegram.org` обслуживает только свой DC,
// поэтому номер обязан совпасть: иначе MTProto-ключ не найдётся и Telegram
// закроет соединение.
//
// Таблица — опубликованные адреса DC и наблюдаемые в логах tgnet варианты.
// Неизвестный адрес возвращает 0: слот ws откажется, и сработает запасной путь.
func telegramDC(host string) int {
	addr, err := netip.ParseAddr(strings.TrimSpace(host))
	if err != nil {
		return 0
	}
	addr = addr.Unmap()
	if addr.Is4() {
		switch addr.String() {
		case "149.154.175.50", "149.154.175.53":
			return 1
		case "149.154.167.50", "149.154.167.51", "149.154.167.41", "149.154.162.123":
			return 2
		case "149.154.175.100":
			return 3
		case "149.154.167.91":
			return 4
		case "91.108.56.130":
			return 5
		}
		return 0
	}
	if addr.Is6() {
		// DC-адреса живут в 2001:67c:4e8:f00N::/64, где N — номер DC.
		const prefix = "2001:67c:4e8:f00"
		text := addr.String()
		if strings.HasPrefix(text, prefix) && len(text) > len(prefix) {
			digit := text[len(prefix)]
			if digit >= '1' && digit <= '5' {
				return int(digit - '0')
			}
		}
	}
	return 0
}
