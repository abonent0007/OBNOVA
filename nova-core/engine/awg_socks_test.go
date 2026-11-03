package nova

import (
	"context"
	"encoding/base64"
	"fmt"
	"io"
	"net"
	"strings"
	"sync"
	"testing"
	"time"
)

func testAwgConfig(address string) string {
	key := base64.StdEncoding.EncodeToString(bytesOf(0x11))
	peer := base64.StdEncoding.EncodeToString(bytesOf(0x22))
	return fmt.Sprintf(`[Interface]
PrivateKey = %s
Address = %s
DNS = 1.1.1.1
MTU = 1280

[Peer]
PublicKey = %s
AllowedIPs = 0.0.0.0/0
Endpoint = 127.0.0.1:1
`, key, address, peer)
}

func bytesOf(value byte) []byte {
	out := make([]byte, 32)
	for i := range out {
		out[i] = value
	}
	return out
}

func freeLoopbackPort(t *testing.T) string {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	address := listener.Addr().String()
	_ = listener.Close()
	return address
}

func TestStartAwgSocksRejectsDisabledFlag(t *testing.T) {
	instance, err := StartAwgSocks(testAwgConfig("10.204.0.1/24"), freeLoopbackPort(t), false)
	if instance != nil {
		t.Fatal("экземпляр не должен создаваться при выключенном флаге")
	}
	if err == nil || !strings.Contains(err.Error(), CfProxySlotFeatureFlag) {
		t.Fatalf("ожидалась ошибка про %s, получено %v", CfProxySlotFeatureFlag, err)
	}
}

func TestStartAwgSocksRejectsNonLoopbackListen(t *testing.T) {
	_, err := StartAwgSocks(testAwgConfig("10.204.0.1/24"), "0.0.0.0:1378", true)
	if err == nil || !strings.Contains(err.Error(), "loopback") {
		t.Fatalf("ожидалась ошибка loopback, получено %v", err)
	}
}

func TestStartAwgSocksLifecycle(t *testing.T) {
	instance, err := StartAwgSocks(testAwgConfig("10.204.0.1/24"), freeLoopbackPort(t), true)
	if err != nil {
		t.Fatalf("StartAwgSocks: %v", err)
	}
	if instance.ListenAddr() == "" {
		t.Fatal("пустой listen address")
	}
	found := false
	for _, address := range instance.Addresses() {
		if address == AwgSocksTunIPv4 {
			found = true
		}
	}
	if !found {
		t.Fatalf("в адресах нет %s: %v", AwgSocksTunIPv4, instance.Addresses())
	}
	instance.Close()
}

func TestStartAwgSocksRejectsBrokenConfig(t *testing.T) {
	_, err := StartAwgSocks("[Interface]\nPrivateKey = not-base64\n", freeLoopbackPort(t), true)
	if err == nil {
		t.Fatal("ожидалась ошибка разбора AWG-конфига")
	}
}

func TestParseAwgNetstackConfig(t *testing.T) {
	addresses, dnses := parseAwgNetstackConfig(`[Interface]
Address = 172.16.0.2/32, fd01::2/128
DNS = 1.1.1.1, 1.0.0.1
`)
	if len(addresses) != 2 || addresses[0].String() != "172.16.0.2" || addresses[1].String() != "fd01::2" {
		t.Fatalf("addresses: %v", addresses)
	}
	if len(dnses) != 2 || dnses[0].String() != "1.1.1.1" {
		t.Fatalf("dns: %v", dnses)
	}
}

func TestAwgSocksServerConnect(t *testing.T) {
	echoListener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer echoListener.Close()
	go func() {
		conn, acceptErr := echoListener.Accept()
		if acceptErr != nil {
			return
		}
		defer conn.Close()
		_, _ = io.Copy(conn, conn)
	}()

	socksListener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	server := newAwgSocksServer(socksListener, func(ctx context.Context, network, address string) (net.Conn, error) {
		return (&net.Dialer{}).DialContext(ctx, network, address)
	})
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go server.serve(ctx)
	defer server.close()

	client, err := net.Dial("tcp", socksListener.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	_ = client.SetDeadline(time.Now().Add(5 * time.Second))

	if _, err := client.Write([]byte{5, 1, 0}); err != nil {
		t.Fatal(err)
	}
	greeting := make([]byte, 2)
	if _, err := io.ReadFull(client, greeting); err != nil {
		t.Fatal(err)
	}
	if greeting[0] != 5 || greeting[1] != 0 {
		t.Fatalf("greeting: %v", greeting)
	}

	echoPort := echoListener.Addr().(*net.TCPAddr).Port
	request := []byte{5, 1, 0, 1, 127, 0, 0, 1, byte(echoPort >> 8), byte(echoPort)}
	if _, err := client.Write(request); err != nil {
		t.Fatal(err)
	}
	reply := make([]byte, 10)
	if _, err := io.ReadFull(client, reply); err != nil {
		t.Fatal(err)
	}
	if reply[1] != 0 {
		t.Fatalf("connect reply: %v", reply)
	}

	payload := []byte("awg-socks-echo")
	if _, err := client.Write(payload); err != nil {
		t.Fatal(err)
	}
	got := make([]byte, len(payload))
	if _, err := io.ReadFull(client, got); err != nil {
		t.Fatal(err)
	}
	if string(got) != string(payload) {
		t.Fatalf("echo: %q", got)
	}
}

func TestAwgSocksServerRejectsUdpAssociate(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	server := newAwgSocksServer(listener, func(ctx context.Context, network, address string) (net.Conn, error) {
		return (&net.Dialer{}).DialContext(ctx, network, address)
	})
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go server.serve(ctx)
	defer server.close()

	client, err := net.Dial("tcp", listener.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	_ = client.SetDeadline(time.Now().Add(5 * time.Second))
	_, _ = client.Write([]byte{5, 1, 0})
	greeting := make([]byte, 2)
	if _, err := io.ReadFull(client, greeting); err != nil {
		t.Fatal(err)
	}
	_, _ = client.Write([]byte{5, 3, 0, 1, 127, 0, 0, 1, 0, 80})
	reply := make([]byte, 10)
	if _, err := io.ReadFull(client, reply); err != nil {
		t.Fatal(err)
	}
	if reply[1] != 0x07 {
		t.Fatalf("ожидался 0x07 для UDP ASSOCIATE, получено %v", reply)
	}
}

func TestAwgSocksCloseUnblocksConnections(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	server := newAwgSocksServer(listener, func(ctx context.Context, network, address string) (net.Conn, error) {
		return (&net.Dialer{}).DialContext(ctx, network, address)
	})
	ctx, cancel := context.WithCancel(context.Background())
	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		server.serve(ctx)
	}()
	server.close()
	cancel()
	wg.Wait()
}
