package nova

import (
	"context"
	"errors"
	"fmt"
	"log"
	"net"
	"net/netip"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	usqueapi "github.com/Diniboy1123/usque/api"
	wgtun "github.com/amnezia-vpn/amneziawg-go/v3/tun"
	wgnetstack "github.com/amnezia-vpn/amneziawg-go/v3/tun/netstack"
)

const (
	MasqueSocksDefaultListen = "127.0.0.1:1379"
	MasqueSocksMTU           = 1280
)

// MasqueSocksInstance — отдельный userspace MASQUE-клиент, отдающий локальный
// SOCKS5 на loopback. Системный TUN не создаётся, activeMasque не трогается:
// это слот для MULTI, где основной TUN уже занят tun2proxy.
type MasqueSocksInstance struct {
	tunnel     *masqueTunnel
	tunDev     wgtun.Device
	net        *wgnetstack.Net
	listener   net.Listener
	server     *awgSocksServer
	listenAddr string
	addresses  []netip.Addr
	cancel     context.CancelFunc
	wg         sync.WaitGroup
	closed     atomic.Bool
}

// StartMasqueSocks поднимает MASQUE + wgnetstack + SOCKS5 на loopback.
//
// Ждёт рукопожатия QUIC/CONNECT-IP до открытия SOCKS-порта: если порт не
// ответил, возвращается ошибка, чтобы вызывающий мог попробовать следующий
// endpoint/порт, не оставляя висящий слушатель.
func StartMasqueSocks(
	identityJSON string,
	endpointHost string,
	endpointPort int,
	sni string,
	listenAddr string,
	enabled bool,
) (*MasqueSocksInstance, error) {
	if !enabled {
		return nil, ErrCfProxySlotDisabled
	}
	identity, ok := parseMasqueIdentity(identityJSON)
	if !ok {
		return nil, errors.New("некорректная MASQUE-конфигурация")
	}
	if listenAddr == "" {
		listenAddr = MasqueSocksDefaultListen
	}
	if err := validateLoopbackListen(listenAddr); err != nil {
		return nil, err
	}

	endpointHost = strings.TrimSpace(strings.Trim(endpointHost, "[]"))
	if endpointHost == "" {
		endpointHost = identity.EndpointV4
		if endpointHost == "" {
			endpointHost = identity.EndpointV6
		}
	}
	if endpointHost == "" {
		return nil, errors.New("пустой адрес MASQUE-узла")
	}
	if endpointPort <= 0 || endpointPort > 65535 {
		if len(identity.Ports) > 0 {
			endpointPort = identity.Ports[0]
		} else {
			endpointPort = 443
		}
	}

	var addresses []netip.Addr
	for _, raw := range []string{identity.IPv4, identity.IPv6} {
		addresses = append(addresses, parseAddressList(raw)...)
	}
	addresses = dedupeAddrs(addresses)
	if len(addresses) == 0 {
		addresses = []netip.Addr{netip.MustParseAddr("172.16.0.2")}
	}
	dnses := []netip.Addr{netip.MustParseAddr("1.1.1.1")}

	tunDevice, netStack, err := wgnetstack.CreateNetTUN(addresses, dnses, MasqueSocksMTU)
	if err != nil {
		return nil, fmt.Errorf("netstack: %w", err)
	}

	privKey, peerPubKey, cert, err := prepareMasqueCrypto(identity)
	if err != nil {
		_ = tunDevice.Close()
		return nil, err
	}
	connectSNI := strings.TrimSpace(sni)
	if connectSNI == "" {
		connectSNI = masqueConnectSNI
	}
	tlsConfig, err := usqueapi.PrepareTlsConfig(privKey, peerPubKey, cert, connectSNI, false)
	if err != nil {
		_ = tunDevice.Close()
		return nil, fmt.Errorf("MASQUE TLS: %w", err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	tunnel := &masqueTunnel{
		cancel: cancel,
		done:   make(chan struct{}),
		stats:  &masqueRuntimeStats{},
		tunDev: tunDevice,
	}

	tunnelErr := make(chan error, 1)
	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		defer close(tunnel.done)
		err := runMasqueTunnel(ctx, tunnel, tlsConfig, endpointHost, endpointPort, tunDevice, MasqueSocksMTU, identity)
		select {
		case tunnelErr <- err:
		default:
		}
	}()

	// Рукопожатие ставит lastHandshakeTimeSec; ждём его или раннего отказа,
	// чтобы не отдавать наружу мёртвый SOCKS.
	deadline := time.Now().Add(8 * time.Second)
	for time.Now().Before(deadline) {
		if tunnel.stats.lastHandshakeTimeSec.Load() > 0 {
			break
		}
		select {
		case err := <-tunnelErr:
			cancel()
			tunnel.closeResources()
			wg.Wait()
			if err == nil {
				err = errors.New("MASQUE завершился до рукопожатия")
			}
			return nil, err
		case <-time.After(50 * time.Millisecond):
		}
	}
	if tunnel.stats.lastHandshakeTimeSec.Load() == 0 {
		cancel()
		tunnel.closeResources()
		wg.Wait()
		return nil, errors.New("таймаут рукопожатия MASQUE")
	}

	listener, err := net.Listen("tcp", listenAddr)
	if err != nil {
		cancel()
		tunnel.closeResources()
		wg.Wait()
		return nil, fmt.Errorf("SOCKS listen %s: %w", listenAddr, err)
	}

	server := newAwgSocksServer(listener, netStack.DialContext)
	instance := &MasqueSocksInstance{
		tunnel:     tunnel,
		tunDev:     tunDevice,
		net:        netStack,
		listener:   listener,
		server:     server,
		listenAddr: listener.Addr().String(),
		addresses:  append([]netip.Addr(nil), addresses...),
		cancel:     cancel,
	}
	instance.wg.Add(2)
	go func() {
		defer instance.wg.Done()
		wg.Wait()
	}()
	go func() {
		defer instance.wg.Done()
		server.serve(ctx)
	}()
	log.Printf("MASQUE SOCKS слушает %s через %s:%d (sni=%s)", instance.listenAddr, endpointHost, endpointPort, connectSNI)
	return instance, nil
}

func (i *MasqueSocksInstance) ListenAddr() string { return i.listenAddr }

func (i *MasqueSocksInstance) Addresses() []string {
	out := make([]string, 0, len(i.addresses))
	for _, addr := range i.addresses {
		out = append(out, addr.String())
	}
	return out
}

func (i *MasqueSocksInstance) Close() {
	if !i.closed.CompareAndSwap(false, true) {
		return
	}
	i.cancel()
	if i.server != nil {
		i.server.close()
	}
	if i.listener != nil {
		_ = i.listener.Close()
	}
	if i.tunnel != nil {
		i.tunnel.closeResources()
	}
	i.wg.Wait()
}
