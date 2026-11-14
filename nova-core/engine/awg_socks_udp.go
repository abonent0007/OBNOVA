package nova

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"strconv"
	"sync"
	"time"

	wgnetstack "github.com/amnezia-vpn/amneziawg-go/v3/tun/netstack"
)

// maxUDPFlowsPerAssociation ограничивает число одновременных UDP-назначений
// в одной SOCKS5-ассоциации. Без предела один клиент может открыть тысячи
// сокетов в netstack и уронить процесс по памяти.
const maxUDPFlowsPerAssociation = 64

// udpFlowIdleTimeout закрывает UDP-назначение, по которому 120 секунд не было
// ни отправки, ни ответа. Иначе 64 неактивных flow навсегда занимают лимит.
const udpFlowIdleTimeout = 120 * time.Second

// udpLookupTimeout ограничивает DNS-разрешение DOMAIN внутри netstack.
// Сам DialUDP локальный и контекст не принимает.
const udpLookupTimeout = 10 * time.Second

// udpAssociation — одна SOCKS5 UDP-ассоциация.
//
// Живёт ровно столько, сколько открыто управляющее TCP-соединение: по RFC 1928
// его закрытие завершает ассоциацию. Relay привязан к адресу клиента из TCP
// control connection, поэтому посторонний локальный процесс не может
// пользоваться чужим сокетом.
type udpAssociation struct {
	server  *awgSocksServer
	relay   *net.UDPConn
	control net.Conn
	ctx     context.Context
	cancel  context.CancelFunc

	mu       sync.Mutex
	client   *net.UDPAddr
	clientIP net.IP
	expected *net.UDPAddr
	flows    map[string]*udpFlow
	pending  int
	closed   bool
	wg       sync.WaitGroup
}

type udpFlow struct {
	key          string
	conn         net.Conn
	remote       *net.UDPAddr
	lastActivity time.Time
	removed      bool
}

func (f *udpFlow) idleFor(now time.Time) time.Duration {
	if f.lastActivity.IsZero() {
		return 0
	}
	return now.Sub(f.lastActivity)
}

// dialAwgUDP открывает connected UDP-сокет внутри userspace-стека AWG.
//
// Для ATYP=DOMAIN имя разрешается через DNS-серверы самого netstack, а не через
// системный DNS Android: иначе имя могло бы уйти мимо Proton. Ошибка DialUDP
// возвращается наверх и приводит к отбрасыванию пакета, а не к direct-fallback.
func dialAwgUDP(ctx context.Context, netStack *wgnetstack.Net, host string, port int) (net.Conn, error) {
	if netStack == nil {
		return nil, errors.New("AWG netstack не инициализирован")
	}
	ip := net.ParseIP(host)
	if ip == nil {
		addrs, err := netStack.LookupContextHost(ctx, host)
		if err != nil {
			return nil, fmt.Errorf("DNS через netstack %q: %w", host, err)
		}
		for _, value := range addrs {
			if parsed := net.ParseIP(value); parsed != nil {
				ip = parsed
				break
			}
		}
		if ip == nil {
			return nil, fmt.Errorf("DNS через netstack: нет адреса для %q", host)
		}
	}
	return netStack.DialUDP(nil, &net.UDPAddr{IP: ip, Port: port})
}

func (s *awgSocksServer) handleUDPAssociate(ctx context.Context, client net.Conn, requestHost, requestPort string) {
	if s.dialUDP == nil {
		_ = writeSocksReply(client, 0x07)
		return
	}
	tcpPeer, ok := client.RemoteAddr().(*net.TCPAddr)
	if !ok || tcpPeer == nil || tcpPeer.IP == nil {
		// Без адреса TCP-клиента нельзя привязать UDP-ассоциацию: отказать,
		// а не оставлять её открытой для любого локального процесса.
		_ = writeSocksReply(client, 0x01)
		return
	}
	loopback := net.IPv4(127, 0, 0, 1)
	if tcpPeer.IP.To4() == nil {
		loopback = net.IPv6loopback
	}
	relay, err := net.ListenUDP("udp", &net.UDPAddr{IP: loopback})
	if err != nil {
		_ = writeSocksReply(client, 0x01)
		return
	}
	local := relay.LocalAddr().(*net.UDPAddr)
	if err := writeSocksBoundReply(client, local); err != nil {
		_ = relay.Close()
		return
	}

	assocCtx, cancel := context.WithCancel(ctx)
	association := &udpAssociation{
		server:  s,
		relay:   relay,
		control: client,
		ctx:     assocCtx,
		cancel:  cancel,
		flows:   make(map[string]*udpFlow),
	}
	defer association.close()
	association.clientIP = cloneIP(tcpPeer.IP)
	association.expected = parseExpectedUDPEndpoint(requestHost, requestPort)

	association.wg.Add(1)
	go association.serve()
	association.wg.Add(1)
	go association.cleanupIdle()

	// Отмена контекста должна разблокировать ожидание control connection.
	controlDone := make(chan struct{})
	association.wg.Add(1)
	go func() {
		defer association.wg.Done()
		select {
		case <-assocCtx.Done():
			_ = client.Close()
		case <-controlDone:
		}
	}()
	_, _ = io.Copy(io.Discard, client)
	close(controlDone)
}

func (a *udpAssociation) cleanupIdle() {
	defer a.wg.Done()
	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-a.ctx.Done():
			return
		case now := <-ticker.C:
			expired := make([]*udpFlow, 0)
			a.mu.Lock()
			for key, flow := range a.flows {
				if flow.idleFor(now) >= udpFlowIdleTimeout {
					flow.removed = true
					delete(a.flows, key)
					expired = append(expired, flow)
				}
			}
			a.mu.Unlock()
			for _, flow := range expired {
				_ = flow.conn.Close()
			}
		}
	}
}

func (a *udpAssociation) serve() {
	defer a.wg.Done()
	buf := make([]byte, 65_535)
	for {
		n, from, err := a.relay.ReadFromUDP(buf)
		if err != nil {
			// Фатальная ошибка relay: закрываем control, чтобы
			// handleUDPAssociate вышел и выполнил общий cleanup.
			_ = a.control.Close()
			return
		}
		a.handleDatagram(buf[:n], from)
	}
}

func (a *udpAssociation) handleDatagram(packet []byte, from *net.UDPAddr) {
	// Сначала проверяем пакет, и только потом фиксируем клиента: иначе
	// посторонний процесс может занять ассоциацию битым UDP-пакетом.
	host, port, payload, frag, err := parseSocksUDPDatagram(packet)
	if err != nil || frag != 0 {
		return
	}

	a.mu.Lock()
	if a.closed {
		a.mu.Unlock()
		return
	}
	if a.expected != nil && (!from.IP.Equal(a.expected.IP) || from.Port != a.expected.Port) {
		a.mu.Unlock()
		return
	}
	if a.client == nil {
		if a.clientIP != nil && !from.IP.Equal(a.clientIP) {
			a.mu.Unlock()
			return
		}
		a.client = cloneUDPAddr(from)
	} else if !sameUDPAddr(a.client, from) {
		a.mu.Unlock()
		return
	}
	a.mu.Unlock()

	flow := a.flow(host, port)
	if flow == nil {
		return
	}
	a.touchFlow(flow)
	n, err := flow.conn.Write(payload)
	if err != nil || n != len(payload) {
		a.removeFlow(flow)
	}
}

func (a *udpAssociation) flow(host string, port int) *udpFlow {
	key := net.JoinHostPort(host, strconv.Itoa(port))

	a.mu.Lock()
	if a.closed {
		a.mu.Unlock()
		return nil
	}
	if existing := a.flows[key]; existing != nil {
		a.mu.Unlock()
		return existing
	}
	if len(a.flows)+a.pending >= maxUDPFlowsPerAssociation {
		a.mu.Unlock()
		return nil
	}
	a.pending++
	a.mu.Unlock()

	// DialUDP может делать DNS через netstack, поэтому не держим mutex.
	lookupCtx, cancel := context.WithTimeout(a.ctx, udpLookupTimeout)
	conn, err := a.server.dialUDP(lookupCtx, host, port)
	cancel()

	a.mu.Lock()
	a.pending--
	if err != nil {
		a.mu.Unlock()
		return nil
	}
	if a.closed {
		a.mu.Unlock()
		_ = conn.Close()
		return nil
	}
	if existing := a.flows[key]; existing != nil {
		a.mu.Unlock()
		_ = conn.Close()
		return existing
	}
	remote, ok := remoteUDPAddr(conn)
	if !ok {
		a.mu.Unlock()
		_ = conn.Close()
		return nil
	}
	created := &udpFlow{key: key, conn: conn, remote: remote}
	created.lastActivity = time.Now()
	a.flows[key] = created
	a.wg.Add(1)
	a.mu.Unlock()

	go a.readFlow(created)
	return created
}

func (a *udpAssociation) readFlow(flow *udpFlow) {
	defer a.wg.Done()
	buf := make([]byte, 65_535)
	for {
		n, err := flow.conn.Read(buf)
		if err != nil {
			a.removeFlow(flow)
			return
		}
		a.touchFlow(flow)
		response, err := buildSocksUDPDatagram(flow.remote.IP.String(), flow.remote.Port, buf[:n])
		if err != nil {
			continue
		}
		a.mu.Lock()
		client := a.client
		closed := a.closed
		a.mu.Unlock()
		if closed || client == nil {
			return
		}
		if _, err := a.relay.WriteToUDP(response, client); err != nil {
			a.removeFlow(flow)
			return
		}
	}
}

func (a *udpAssociation) touchFlow(flow *udpFlow) {
	a.mu.Lock()
	if !flow.removed && a.flows[flow.key] == flow {
		flow.lastActivity = time.Now()
	}
	a.mu.Unlock()
}

func (a *udpAssociation) removeFlow(flow *udpFlow) {
	a.mu.Lock()
	if flow.removed {
		a.mu.Unlock()
		return
	}
	flow.removed = true
	if a.flows[flow.key] == flow {
		delete(a.flows, flow.key)
	}
	a.mu.Unlock()
	_ = flow.conn.Close()
}

func (a *udpAssociation) close() {
	if a.cancel != nil {
		a.cancel()
	}
	a.mu.Lock()
	if a.closed {
		a.mu.Unlock()
		return
	}
	a.closed = true
	flows := make([]*udpFlow, 0, len(a.flows))
	for _, flow := range a.flows {
		flows = append(flows, flow)
	}
	a.flows = make(map[string]*udpFlow)
	a.mu.Unlock()

	_ = a.relay.Close()
	for _, flow := range flows {
		_ = flow.conn.Close()
	}
	a.wg.Wait()
}

func cloneUDPAddr(addr *net.UDPAddr) *net.UDPAddr {
	if addr == nil {
		return nil
	}
	return &net.UDPAddr{IP: cloneIP(addr.IP), Port: addr.Port, Zone: addr.Zone}
}

func cloneIP(ip net.IP) net.IP {
	if ip == nil {
		return nil
	}
	return append(net.IP(nil), ip...)
}

func sameUDPAddr(a, b *net.UDPAddr) bool {
	return a != nil && b != nil && a.Port == b.Port && a.IP.Equal(b.IP)
}

// parseExpectedUDPEndpoint разбирает DST.ADDR:DST.PORT из UDP ASSOCIATE.
// Нулевой/неизвестный endpoint по RFC 1928 допустим: тогда клиент привязывается
// по IP TCP peer и первому валидному UDP-пакету.
func parseExpectedUDPEndpoint(host, port string) *net.UDPAddr {
	ip := net.ParseIP(host)
	if ip == nil || ip.IsUnspecified() {
		return nil
	}
	value, err := strconv.Atoi(port)
	if err != nil || value <= 0 || value > 65_535 {
		return nil
	}
	return &net.UDPAddr{IP: ip, Port: value}
}

func remoteUDPAddr(conn net.Conn) (*net.UDPAddr, bool) {
	addr, ok := conn.RemoteAddr().(*net.UDPAddr)
	if !ok || addr == nil || addr.IP == nil || addr.Port <= 0 || addr.Port > 65_535 {
		return nil, false
	}
	return cloneUDPAddr(addr), true
}

func writeSocksBoundReply(w io.Writer, addr *net.UDPAddr) error {
	if addr == nil {
		return errors.New("nil BND addr")
	}
	ip := addr.IP.To4()
	if ip != nil {
		_, err := w.Write([]byte{5, 0, 0, 1, ip[0], ip[1], ip[2], ip[3], byte(addr.Port >> 8), byte(addr.Port)})
		return err
	}
	ip16 := addr.IP.To16()
	if ip16 == nil {
		return errors.New("BND addr не IP")
	}
	packet := []byte{5, 0, 0, 4}
	packet = append(packet, ip16...)
	packet = append(packet, byte(addr.Port>>8), byte(addr.Port))
	_, err := w.Write(packet)
	return err
}

// parseSocksUDPDatagram разбирает SOCKS5 UDP request:
// RSV(2) | FRAG(1) | ATYP(1) | DST.ADDR | DST.PORT(2) | DATA.
func parseSocksUDPDatagram(packet []byte) (host string, port int, payload []byte, frag byte, err error) {
	if len(packet) < 4 {
		return "", 0, nil, 0, io.ErrUnexpectedEOF
	}
	if packet[0] != 0 || packet[1] != 0 {
		return "", 0, nil, 0, errors.New("SOCKS5 UDP: RSV != 0")
	}
	frag = packet[2]
	offset := 4
	switch packet[3] {
	case 1:
		if len(packet) < offset+4+2 {
			return "", 0, nil, 0, io.ErrUnexpectedEOF
		}
		host = net.IP(packet[offset : offset+4]).String()
		offset += 4
	case 3:
		if len(packet) < offset+1 {
			return "", 0, nil, 0, io.ErrUnexpectedEOF
		}
		length := int(packet[offset])
		offset++
		if length == 0 || len(packet) < offset+length+2 {
			return "", 0, nil, 0, errors.New("SOCKS5 UDP: пустой/битый домен")
		}
		host = string(packet[offset : offset+length])
		offset += length
	case 4:
		if len(packet) < offset+16+2 {
			return "", 0, nil, 0, io.ErrUnexpectedEOF
		}
		host = net.IP(packet[offset : offset+16]).String()
		offset += 16
	default:
		return "", 0, nil, 0, fmt.Errorf("SOCKS5 UDP: ATYP %d", packet[3])
	}
	port = int(binary.BigEndian.Uint16(packet[offset : offset+2]))
	offset += 2
	return host, port, packet[offset:], frag, nil
}

func buildSocksUDPDatagram(host string, port int, payload []byte) ([]byte, error) {
	if port < 0 || port > 65_535 {
		return nil, fmt.Errorf("порт вне диапазона: %d", port)
	}
	ip := net.ParseIP(host)
	var header []byte
	switch {
	case ip != nil && ip.To4() != nil:
		header = []byte{0, 0, 0, 1}
		header = append(header, ip.To4()...)
	case ip != nil:
		header = []byte{0, 0, 0, 4}
		header = append(header, ip.To16()...)
	default:
		if len(host) == 0 || len(host) > 255 {
			return nil, fmt.Errorf("домен не помещается в SOCKS5: %q", host)
		}
		header = []byte{0, 0, 0, 3, byte(len(host))}
		header = append(header, host...)
	}
	header = append(header, byte(port>>8), byte(port))
	return append(header, payload...), nil
}
