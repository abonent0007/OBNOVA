//go:build !windows

package nova

import "syscall"

// dupFD дублирует файловый дескриптор Android TUN. На Unix/Android это
// обычный syscall.Dup; отдельная обёртка нужна, чтобы пакет компилировался
// на Windows для unit-тестов SOCKS5 UDP relay.
func dupFD(fd int) (int, error) {
	return syscall.Dup(fd)
}
