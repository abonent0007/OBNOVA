//go:build windows

package nova

import "errors"

// dupFD на Windows не используется: AndroidTUN создаётся только на устройстве.
// Заглушка нужна лишь для того, чтобы пакет и unit-тесты SOCKS5 UDP relay
// собирались на Windows.
func dupFD(int) (int, error) {
	return 0, errors.New("CreateAndroidTUN недоступен на Windows")
}
