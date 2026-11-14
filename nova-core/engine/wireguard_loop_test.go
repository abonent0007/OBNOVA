package nova

import (
	"crypto/rand"
	"encoding/base64"
	"net"
	"net/netip"
	"strings"
	"testing"
	"time"

	wgdevice "github.com/amnezia-vpn/amneziawg-go/v3/device"
	wgnetstack "github.com/amnezia-vpn/amneziawg-go/v3/tun/netstack"
	"golang.org/x/crypto/curve25519"
)

// TestWireGuardLoopHandshake — контролируемый локальный WireGuard peer:
// проверяет, что StartAwgSocks вообще способен завершить handshake с валидным
// сервером, не выходя в интернет. Временный диагностический тест.
func TestWireGuardLoopHandshake(t *testing.T) {
	serverPriv, serverPub := testKeyPair(t)
	clientPriv, clientPub := testKeyPair(t)

	serverTun, _, err := wgnetstack.CreateNetTUN(
		[]netip.Addr{netip.MustParseAddr("10.99.0.1")},
		[]netip.Addr{netip.MustParseAddr("1.1.1.1")},
		1420,
	)
	if err != nil {
		t.Fatal(err)
	}
	serverDev := wgdevice.NewDevice(serverTun, NewSmartBind(), wgdevice.NewLogger(wgdevice.LogLevelError, "[SRV] "))
	defer serverDev.Close()
	serverUapi, err := configToUAPI("[Interface]\nPrivateKey = " + serverPriv +
		"\nListenPort = 51820\n\n[Peer]\nPublicKey = " + clientPub +
		"\nAllowedIPs = 10.99.0.2/32\n")
	if err != nil {
		t.Fatal(err)
	}
	if err := serverDev.IpcSet(serverUapi); err != nil {
		t.Fatal(err)
	}
	if err := serverDev.Up(); err != nil {
		t.Fatal(err)
	}

	clientConfig := "[Interface]\nPrivateKey = " + clientPriv +
		"\nAddress = 10.99.0.2/32\nDNS = 1.1.1.1\nMTU = 1420\n\n[Peer]\nPublicKey = " +
		serverPub + "\nAllowedIPs = 0.0.0.0/0\nEndpoint = 127.0.0.1:51820\n"
	instance, err := StartAwgSocks(clientConfig, "127.0.0.1:13798", true)
	if err != nil {
		t.Fatal(err)
	}
	defer instance.Close()

	conn, err := instance.net.DialUDP(nil, &net.UDPAddr{IP: net.IPv4(10, 99, 0, 1), Port: 53})
	if err != nil {
		t.Fatal(err)
	}
	_ = conn.SetDeadline(time.Now().Add(2 * time.Second))
	_, _ = conn.Write([]byte{0})

	deadline := time.Now().Add(6 * time.Second)
	for time.Now().Before(deadline) {
		state, _ := serverDev.IpcGet()
		if handshakeSeen(state) {
			t.Log("SERVER_HANDSHAKE_OK")
			return
		}
		time.Sleep(250 * time.Millisecond)
	}
	state, _ := serverDev.IpcGet()
	t.Fatalf("handshake не завершился; server state:\n%s", redactUapi(state))
}

func testKeyPair(t *testing.T) (string, string) {
	t.Helper()
	var priv [32]byte
	if _, err := rand.Read(priv[:]); err != nil {
		t.Fatal(err)
	}
	priv[0] &= 248
	priv[31] &= 127
	priv[31] |= 64
	var pub [32]byte
	curve25519.ScalarBaseMult(&pub, &priv)
	return base64.StdEncoding.EncodeToString(priv[:]), base64.StdEncoding.EncodeToString(pub[:])
}

func handshakeSeen(uapi string) bool {
	for _, line := range strings.Split(uapi, "\n") {
		if strings.HasPrefix(line, "last_handshake_time_sec=") {
			value := strings.TrimSpace(strings.TrimPrefix(line, "last_handshake_time_sec="))
			return value != "" && value != "0"
		}
	}
	return false
}

func redactUapi(uapi string) string {
	var out []string
	for _, line := range strings.Split(uapi, "\n") {
		if strings.HasPrefix(line, "private_key=") {
			out = append(out, "private_key=<redacted>")
			continue
		}
		out = append(out, line)
	}
	return strings.Join(out, "\n")
}
