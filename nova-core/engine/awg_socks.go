package nova

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	wgdevice "github.com/amnezia-vpn/amneziawg-go/v3/device"
	wgnetstack "github.com/amnezia-vpn/amneziawg-go/v3/tun/netstack"
)

const (
	// AwgSocksTunIPv4 — служебный адрес userspace-стека, если профиль WARP
	// почему-то не содержит Address. Для реального WARP источником обязан быть
	// адрес из профиля (обычно 172.16.0.2/32): Cloudflare дропает чужие source IP.
	AwgSocksTunIPv4       = "10.204.0.1"
	AwgSocksDefaultListen = "127.0.0.1:1378"
	AwgSocksMTU           = 1280

	// CfProxySlotFeatureFlag — имя флага; StartAwgSocks вызывается только при
	// BuildConfig.CF_PROXY_SLOT_ENABLED=true в Kotlin.
	CfProxySlotFeatureFlag = "CF_PROXY_SLOT_ENABLED"
)

var (
	// ErrCfProxySlotDisabled — слот выключен feature-флагом.
	ErrCfProxySlotDisabled = errors.New("CF_PROXY_SLOT_ENABLED=false: слот WARP/MASQUE выключен")

	// awgSocksConfigMu защищает глобальные packet-tweak настройки,
	// которые configToUAPI выставляет для SmartBind. Устройство и listener
	// живут отдельно и не используют activeDevice/stateMu.
	awgSocksConfigMu sync.Mutex
)

// AwgSocksInstance — независимый userspace-экземпляр AmneziaWG, отдающий
// локальный SOCKS5. Он не регистрируется в activeDevice и не создаёт TUN.
type AwgSocksInstance struct {
	device     *wgdevice.Device
	net        *wgnetstack.Net
	listener   net.Listener
	server     *awgSocksServer
	listenAddr string
	addresses  []netip.Addr
	cancel     context.CancelFunc
	wg         sync.WaitGroup
	closed     atomic.Bool
}

// StartAwgSocks поднимает userspace AWG + SOCKS5 на loopback.
//
// enabled — это CF_PROXY_SLOT_ENABLED; при false возвращается ошибка, ничего
// не запускается. listenAddr обязан быть loopback-адресом.
func StartAwgSocks(awgConfig string, listenAddr string, enabled bool) (*AwgSocksInstance, error) {
	if !enabled {
		return nil, ErrCfProxySlotDisabled
	}
	if strings.TrimSpace(awgConfig) == "" {
		return nil, errors.New("пустой AWG-конфиг")
	}
	if listenAddr == "" {
		listenAddr = AwgSocksDefaultListen
	}
	if err := validateLoopbackListen(listenAddr); err != nil {
		return nil, err
	}

	addresses, dnses := parseAwgNetstackConfig(awgConfig)
	if len(addresses) == 0 {
		addresses = []netip.Addr{netip.MustParseAddr(AwgSocksTunIPv4)}
	}
	if len(dnses) == 0 {
		dnses = []netip.Addr{netip.MustParseAddr("1.1.1.1")}
	}

	awgSocksConfigMu.Lock()
	uapi, err := configToUAPI(awgConfig)
	if err != nil {
		awgSocksConfigMu.Unlock()
		return nil, fmt.Errorf("AWG-конфиг: %w", err)
	}

	tunDevice, netStack, err := wgnetstack.CreateNetTUN(addresses, dnses, AwgSocksMTU)
	if err != nil {
		awgSocksConfigMu.Unlock()
		return nil, fmt.Errorf("netstack: %w", err)
	}

	logger := wgdevice.NewLogger(wgdevice.LogLevelVerbose, "[AWG-SLOT] ")
	dev := wgdevice.NewDevice(tunDevice, NewSmartBind(), logger)
	if err := dev.IpcSet(uapi); err != nil {
		dev.Close()
		awgSocksConfigMu.Unlock()
		return nil, fmt.Errorf("AWG IpcSet: %w", err)
	}
	if err := dev.Up(); err != nil {
		dev.Close()
		awgSocksConfigMu.Unlock()
		return nil, fmt.Errorf("AWG Up: %w", err)
	}
	awgSocksConfigMu.Unlock()

	listener, err := net.Listen("tcp", listenAddr)
	if err != nil {
		dev.Close()
		return nil, fmt.Errorf("SOCKS listen %s: %w", listenAddr, err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	server := newAwgSocksServer(listener, netStack.DialContext)
	instance := &AwgSocksInstance{
		device:     dev,
		net:        netStack,
		listener:   listener,
		server:     server,
		listenAddr: listener.Addr().String(),
		addresses:  append([]netip.Addr(nil), addresses...),
		cancel:     cancel,
	}
	instance.wg.Add(1)
	go func() {
		defer instance.wg.Done()
		server.serve(ctx)
	}()
	return instance, nil
}

func validateLoopbackListen(listenAddr string) error {
	host, port, err := net.SplitHostPort(listenAddr)
	if err != nil {
		return fmt.Errorf("listenAddr %q: %w", listenAddr, err)
	}
	ip := net.ParseIP(host)
	if ip == nil || !ip.IsLoopback() {
		return fmt.Errorf("listenAddr %q: разрешён только loopback", listenAddr)
	}
	if port == "" || port == "0" {
		return fmt.Errorf("listenAddr %q: нужен фиксированный порт", listenAddr)
	}
	return nil
}

// parseAwgNetstackConfig читает Address/DNS из [Interface]. Это не UAPI:
// configToUAPI их игнорирует, а userspace-стеку адреса нужны обязательно.
func parseAwgNetstackConfig(conf string) ([]netip.Addr, []netip.Addr) {
	var addresses []netip.Addr
	var dnses []netip.Addr
	section := ""
	for _, raw := range strings.Split(conf, "\n") {
		line := strings.TrimSpace(raw)
		if line == "" {
			continue
		}
		if strings.HasPrefix(line, "[") && strings.HasSuffix(line, "]") {
			section = strings.ToLower(strings.TrimSpace(line[1 : len(line)-1]))
			continue
		}
		if section != "interface" {
			continue
		}
		key, value, ok := strings.Cut(line, "=")
		if !ok {
			continue
		}
		switch strings.ToLower(strings.TrimSpace(key)) {
		case "address":
			addresses = append(addresses, parseAddressList(value)...)
		case "dns":
			dnses = append(dnses, parseAddressList(value)...)
		}
	}
	return dedupeAddrs(addresses), dedupeAddrs(dnses)
}

func parseAddressList(raw string) []netip.Addr {
	var out []netip.Addr
	for _, part := range strings.Split(raw, ",") {
		value := strings.TrimSpace(part)
		if value == "" {
			continue
		}
		if prefix, err := netip.ParsePrefix(value); err == nil {
			out = append(out, prefix.Addr())
			continue
		}
		if addr, err := netip.ParseAddr(value); err == nil {
			out = append(out, addr)
		}
	}
	return out
}

func dedupeAddrs(values []netip.Addr) []netip.Addr {
	seen := make(map[netip.Addr]struct{}, len(values))
	out := make([]netip.Addr, 0, len(values))
	for _, value := range values {
		value = value.Unmap()
		if _, ok := seen[value]; ok {
			continue
		}
		seen[value] = struct{}{}
		out = append(out, value)
	}
	return out
}

func (i *AwgSocksInstance) ListenAddr() string { return i.listenAddr }

func (i *AwgSocksInstance) Addresses() []string {
	out := make([]string, 0, len(i.addresses))
	for _, addr := range i.addresses {
		out = append(out, addr.String())
	}
	return out
}

func (i *AwgSocksInstance) Close() {
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
	if i.device != nil {
		i.device.Close()
	}
	i.wg.Wait()
}

// awgSocksServer — минимальный SOCKS5 CONNECT поверх userspace AWG.
// UDP ASSOCIATE оставлен на следующий этап: сейчас он честно отвечает 0x07,
// а не делает вид, что звонки Telegram уже поддержаны.
type awgSocksServer struct {
	listener net.Listener
	dial     func(context.Context, string, string) (net.Conn, error)
	mu       sync.Mutex
	conns    map[net.Conn]struct{}
}

func newAwgSocksServer(
	listener net.Listener,
	dial func(context.Context, string, string) (net.Conn, error),
) *awgSocksServer {
	return &awgSocksServer{
		listener: listener,
		dial:     dial,
		conns:    make(map[net.Conn]struct{}),
	}
}

func (s *awgSocksServer) serve(ctx context.Context) {
	for {
		conn, err := s.listener.Accept()
		if err != nil {
			select {
			case <-ctx.Done():
				return
			default:
				return
			}
		}
		s.track(conn, true)
		go func(client net.Conn) {
			defer s.track(client, false)
			defer client.Close()
			s.handle(ctx, client)
		}(conn)
	}
}

func (s *awgSocksServer) close() {
	_ = s.listener.Close()
	s.mu.Lock()
	for conn := range s.conns {
		_ = conn.Close()
	}
	s.conns = make(map[net.Conn]struct{})
	s.mu.Unlock()
}

func (s *awgSocksServer) track(conn net.Conn, add bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if add {
		s.conns[conn] = struct{}{}
	} else {
		delete(s.conns, conn)
	}
}

func (s *awgSocksServer) handle(ctx context.Context, client net.Conn) {
	_ = client.SetDeadline(time.Now().Add(10 * time.Second))

	greeting := make([]byte, 2)
	if _, err := io.ReadFull(client, greeting); err != nil || greeting[0] != 5 {
		return
	}
	methods := make([]byte, int(greeting[1]))
	if _, err := io.ReadFull(client, methods); err != nil {
		return
	}
	noAuth := false
	for _, method := range methods {
		if method == 0 {
			noAuth = true
			break
		}
	}
	if !noAuth {
		_, _ = client.Write([]byte{5, 0xff})
		return
	}
	if _, err := client.Write([]byte{5, 0}); err != nil {
		return
	}

	request := make([]byte, 4)
	if _, err := io.ReadFull(client, request); err != nil || request[0] != 5 {
		return
	}
	command := request[1]
	if command != 1 {
		writeSocksReply(client, 0x07)
		return
	}

	host, port, err := readSocksTarget(client, request[3])
	if err != nil {
		writeSocksReply(client, 0x08)
		return
	}
	dialCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	upstream, err := s.dial(dialCtx, "tcp", net.JoinHostPort(host, port))
	cancel()
	if err != nil {
		writeSocksReply(client, 0x05)
		return
	}
	defer upstream.Close()
	if err := writeSocksReply(client, 0x00); err != nil {
		return
	}
	_ = client.SetDeadline(time.Time{})
	pipeSocks(client, upstream)
}

func readSocksTarget(r io.Reader, atyp byte) (string, string, error) {
	switch atyp {
	case 1:
		buf := make([]byte, 4)
		if _, err := io.ReadFull(r, buf); err != nil {
			return "", "", err
		}
		port, err := readSocksPort(r)
		return net.IP(buf).String(), port, err
	case 3:
		length := make([]byte, 1)
		if _, err := io.ReadFull(r, length); err != nil {
			return "", "", err
		}
		host := make([]byte, int(length[0]))
		if _, err := io.ReadFull(r, host); err != nil {
			return "", "", err
		}
		port, err := readSocksPort(r)
		return string(host), port, err
	case 4:
		buf := make([]byte, 16)
		if _, err := io.ReadFull(r, buf); err != nil {
			return "", "", err
		}
		port, err := readSocksPort(r)
		return net.IP(buf).String(), port, err
	default:
		return "", "", fmt.Errorf("ATYP %d", atyp)
	}
}

func readSocksPort(r io.Reader) (string, error) {
	buf := make([]byte, 2)
	if _, err := io.ReadFull(r, buf); err != nil {
		return "", err
	}
	return fmt.Sprint(binary.BigEndian.Uint16(buf)), nil
}

func writeSocksReply(w io.Writer, code byte) error {
	_, err := w.Write([]byte{5, code, 0, 1, 0, 0, 0, 0, 0, 0})
	return err
}

func pipeSocks(client, upstream net.Conn) {
	done := make(chan struct{}, 2)
	go func() {
		_, _ = io.Copy(upstream, client)
		done <- struct{}{}
	}()
	go func() {
		_, _ = io.Copy(client, upstream)
		done <- struct{}{}
	}()
	<-done
	_ = client.Close()
	_ = upstream.Close()
	<-done
}
