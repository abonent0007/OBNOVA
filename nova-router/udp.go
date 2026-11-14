package main

import (
	"encoding/binary"
	"errors"
	"io"
	"net"
	"strconv"
	"sync"
	"time"
)

const socksUDPAssociate = 3

// udpClientIdleTimeout закрывает клиентскую ассоциацию, если по ней 120 секунд
// не было трафика. Это защищает роутер от накопления «мёртвых» UDP-сессий от
// tun2proxy.
const udpClientIdleTimeout = 120 * time.Second

// maxUDPUpstreamFlows ограничивает число назначений в одной upstream-ассоциации.
// Предел совпадает с лимитом AWG-моста: лишние flow отбрасываются, а не
// вытесняют уже работающие.
const maxUDPUpstreamFlows = 64

// maxUDPUpstreamsPerClient ограничивает число upstream-ассоциаций одного
// клиента. Для доменов создаётся отдельная ассоциация на домен, чтобы ответы
// с одинаковым портом не смешивались между разными именами.
const maxUDPUpstreamsPerClient = 32

// serveUDPAssociate обслуживает UDP ASSOCIATE от tun2proxy.
//
// Каждый датаграм содержит собственный адрес назначения. Слот выбирает та же
// политика, что и для TCP (routeOrderFor), но берётся только первый слот из
// правила: если он не объявлен UDP-capable, пакет отбрасывается. Перебора
// других слотов и direct fallback здесь нет.
//
// UDP/443 намеренно отбрасываем: QUIC должен идти по TCP, иначе правила
// маршрутов к нему не применяются.
func serveUDPAssociate(control net.Conn, router Router) error {
	relay, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		_ = writeReply(control, 0x01)
		return err
	}

	association := &udpAssociation{
		router:   router,
		relay:    relay,
		upstream: make(map[string]*udpUpstream),
	}
	defer association.close()

	local := relay.LocalAddr().(*net.UDPAddr)
	reply := []byte{5, 0, 0, 1, 127, 0, 0, 1, byte(local.Port >> 8), byte(local.Port)}
	if _, err := control.Write(reply); err != nil {
		return err
	}
	go func() {
		_, _ = io.Copy(io.Discard, control)
		association.close()
	}()

	buf := make([]byte, 65_535)
	for {
		_ = relay.SetReadDeadline(time.Now().Add(udpClientIdleTimeout))
		n, from, err := relay.ReadFromUDP(buf)
		if err != nil {
			return nil
		}
		host, port, payload, ok := parseUDPDatagram(buf[:n])
		if !ok {
			continue
		}
		if !association.bindClient(from) {
			continue
		}
		// buf переиспользуется следующим ReadFromUDP: payload нельзя
		// передавать в горутину без копии, иначе два одновременных
		// датаграма портят друг другу содержимое.
		data := append([]byte(nil), payload...)
		go association.forward(host, port, data)
	}
}

// udpAssociation — одна клиентская UDP-ассоциация tun2proxy.
type udpAssociation struct {
	router Router
	relay  *net.UDPConn

	mu       sync.Mutex
	client   *net.UDPAddr
	upstream map[string]*udpUpstream
	closed   bool

	closeOnce sync.Once
}

// udpUpstream — SOCKS5 UDP ASSOCIATE к одному слоту.
type udpUpstream struct {
	name    string
	control net.Conn
	relay   *net.UDPConn
	// strict=true: ассоциация обслуживает IP-назначения и принимает ответы
	// только от отправленных host:port. strict=false: ассоциация создана
	// ровно под один домен, поэтому любой ответ из неё принадлежит этому
	// домену.
	strict bool

	mu     sync.Mutex
	flows  map[string]struct{}
	closed bool

	closeOnce sync.Once
}

func (a *udpAssociation) bindClient(from *net.UDPAddr) bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.closed {
		return false
	}
	if a.client == nil {
		a.client = cloneUDPAddr(from)
		return true
	}
	return sameUDPAddr(a.client, from)
}

func (a *udpAssociation) forward(host string, port int, payload []byte) {
	// UDP использует только udpOrder. Если правило совпало, но udpOrder пуст —
	// DROP, без перехода к defaultUdpOrder. defaultUdpOrder применяется
	// только когда ни одно правило не совпало.
	order, routeName, _ := a.router.cfg.UDPOrderFor(host)
	if a.router.stats != nil {
		a.router.stats.recordRoute(routeName)
	}
	// UDP/443 разрешается только явным allowQuic правила. Это порт-политика,
	// а не определение QUIC: SupportsUDP и выбранный слот проверяются дальше.
	if port == 443 && !a.router.cfg.UDPAllowsQUIC(host) {
		return
	}
	if len(order) == 0 {
		return
	}

	// Политика выбрала первый слот. Если он не умеет UDP — drop, без
	// перебора следующих слотов и без direct fallback.
	name := order[0]
	slot, ok := a.router.cfg.Slots[name]
	if !ok || !a.router.cfg.SupportsUDP(name) {
		return
	}

	started := time.Now()
	var err error
	switch slot.Type {
	case SlotSocks5:
		key := name
		strict := true
		if net.ParseIP(host) == nil {
			// Отдельная ассоциация на домен: иначе два имени с одинаковым
			// портом и разными IP неразличимы в ответах AWG.
			key = name + "\x00" + normalizeHost(host)
			strict = false
		}
		var upstream *udpUpstream
		upstream, err = a.upstreamFor(key, slot, strict)
		if err == nil {
			err = upstream.send(host, port, payload)
		}
	case SlotDirect:
		err = a.forwardDirect(host, port, payload)
	default:
		err = errors.New("тип слота не поддерживает UDP-диспетчер")
	}
	if a.router.stats != nil {
		a.router.stats.record(name, time.Since(started), err == nil)
	}
}

func (a *udpAssociation) upstreamFor(key string, slot Slot, strict bool) (*udpUpstream, error) {
	a.mu.Lock()
	if a.closed {
		a.mu.Unlock()
		return nil, net.ErrClosed
	}
	if existing := a.upstream[key]; existing != nil {
		a.mu.Unlock()
		return existing, nil
	}
	if len(a.upstream) >= maxUDPUpstreamsPerClient {
		a.mu.Unlock()
		return nil, errors.New("UDP: превышен лимит ассоциаций клиента")
	}
	a.mu.Unlock()

	created, err := dialSocks5UDP(key, slot.Addr, strict)
	if err != nil {
		return nil, err
	}

	a.mu.Lock()
	if a.closed {
		a.mu.Unlock()
		created.close()
		return nil, net.ErrClosed
	}
	if existing := a.upstream[key]; existing != nil {
		a.mu.Unlock()
		created.close()
		return existing, nil
	}
	a.upstream[key] = created
	a.mu.Unlock()

	go a.readUpstream(created)
	go func() {
		_, _ = io.Copy(io.Discard, created.control)
		created.close()
	}()
	return created, nil
}

func (a *udpAssociation) readUpstream(upstream *udpUpstream) {
	buf := make([]byte, 65_535)
	for {
		n, err := upstream.relay.Read(buf)
		if err != nil {
			break
		}
		host, port, _, ok := parseUDPDatagram(buf[:n])
		if !ok || !upstream.allowed(host, port) {
			continue
		}
		a.mu.Lock()
		client := cloneUDPAddr(a.client)
		closed := a.closed
		a.mu.Unlock()
		if closed || client == nil {
			break
		}
		if _, err := a.relay.WriteToUDP(buf[:n], client); err != nil {
			break
		}
	}
	upstream.close()
	a.mu.Lock()
	if a.upstream[upstream.name] == upstream {
		delete(a.upstream, upstream.name)
	}
	a.mu.Unlock()
}

func (a *udpAssociation) forwardDirect(host string, port int, payload []byte) error {
	addr, err := net.ResolveUDPAddr("udp", net.JoinHostPort(host, strconv.Itoa(port)))
	if err != nil {
		return err
	}
	conn, err := net.DialUDP("udp", nil, addr)
	if err != nil {
		return err
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
	if _, err := conn.Write(payload); err != nil {
		return err
	}
	resp := make([]byte, 65_535)
	n, err := conn.Read(resp)
	if err != nil {
		return err
	}
	packet, err := buildUDPDatagram(host, port, resp[:n])
	if err != nil {
		return err
	}
	a.mu.Lock()
	client := cloneUDPAddr(a.client)
	closed := a.closed
	a.mu.Unlock()
	if closed || client == nil {
		return net.ErrClosed
	}
	_, err = a.relay.WriteToUDP(packet, client)
	return err
}

func (a *udpAssociation) close() {
	a.closeOnce.Do(func() {
		a.mu.Lock()
		a.closed = true
		upstreams := make([]*udpUpstream, 0, len(a.upstream))
		for _, upstream := range a.upstream {
			upstreams = append(upstreams, upstream)
		}
		a.upstream = make(map[string]*udpUpstream)
		a.mu.Unlock()

		_ = a.relay.Close()
		for _, upstream := range upstreams {
			upstream.close()
		}
	})
}

func dialSocks5UDP(name, proxyAddr string, strict bool) (*udpUpstream, error) {
	control, err := net.DialTimeout("tcp", proxyAddr, dialTimeout)
	if err != nil {
		return nil, err
	}
	_ = control.SetDeadline(time.Now().Add(dialTimeout))

	if _, err := control.Write([]byte{5, 1, 0}); err != nil {
		control.Close()
		return nil, err
	}
	greeting := make([]byte, 2)
	if _, err := io.ReadFull(control, greeting); err != nil {
		control.Close()
		return nil, err
	}
	if greeting[0] != 5 || greeting[1] != 0 {
		control.Close()
		return nil, errors.New("SOCKS5 UDP: метод не принят")
	}

	request := []byte{5, socksUDPAssociate, 0}
	request = append(request, encodeAddr("0.0.0.0")...)
	request = append(request, 0, 0)
	if _, err := control.Write(request); err != nil {
		control.Close()
		return nil, err
	}
	reply := make([]byte, 4)
	if _, err := io.ReadFull(control, reply); err != nil {
		control.Close()
		return nil, err
	}
	if reply[0] != 5 || reply[1] != 0 {
		control.Close()
		return nil, errors.New("SOCKS5 UDP: ASSOCIATE отклонён")
	}
	host, port, err := readSocksAddr(control, reply[3])
	if err != nil {
		control.Close()
		return nil, err
	}
	bndIP := net.ParseIP(host)
	if bndIP == nil {
		control.Close()
		return nil, errors.New("SOCKS5 UDP: BND не IP")
	}
	if bndIP.IsUnspecified() {
		// RFC 1928 разрешает 0.0.0.0 в BND; реальный адрес — адрес прокси.
		if remote, ok := control.RemoteAddr().(*net.TCPAddr); ok && remote != nil {
			bndIP = remote.IP
		}
	}
	relay, err := net.DialUDP("udp", nil, &net.UDPAddr{IP: bndIP, Port: port})
	if err != nil {
		control.Close()
		return nil, err
	}
	_ = control.SetDeadline(time.Time{})
	return &udpUpstream{
		name:    name,
		control: control,
		relay:   relay,
		strict:  strict,
		flows:   make(map[string]struct{}),
	}, nil
}

func readSocksAddr(r io.Reader, atyp byte) (string, int, error) {
	var host string
	switch atyp {
	case 1:
		raw := make([]byte, 4)
		if _, err := io.ReadFull(r, raw); err != nil {
			return "", 0, err
		}
		host = net.IP(raw).String()
	case 3:
		l := make([]byte, 1)
		if _, err := io.ReadFull(r, l); err != nil {
			return "", 0, err
		}
		name := make([]byte, int(l[0]))
		if _, err := io.ReadFull(r, name); err != nil {
			return "", 0, err
		}
		host = string(name)
	case 4:
		raw := make([]byte, 16)
		if _, err := io.ReadFull(r, raw); err != nil {
			return "", 0, err
		}
		host = net.IP(raw).String()
	default:
		return "", 0, errors.New("SOCKS5: неизвестный ATYP")
	}
	rawPort := make([]byte, 2)
	if _, err := io.ReadFull(r, rawPort); err != nil {
		return "", 0, err
	}
	return host, int(binary.BigEndian.Uint16(rawPort)), nil
}

func (u *udpUpstream) send(host string, port int, payload []byte) error {
	packet, err := buildUDPDatagram(host, port, payload)
	if err != nil {
		return err
	}
	key := net.JoinHostPort(host, strconv.Itoa(port))

	u.mu.Lock()
	if u.closed {
		u.mu.Unlock()
		return net.ErrClosed
	}
	if len(u.flows) >= maxUDPUpstreamFlows {
		if _, known := u.flows[key]; !known {
			u.mu.Unlock()
			return errors.New("UDP: превышен лимит flow в ассоциации")
		}
	}
	u.flows[key] = struct{}{}
	u.mu.Unlock()

	_, err = u.relay.Write(packet)
	return err
}

func (u *udpUpstream) allowed(host string, port int) bool {
	// Доменная ассоциация создана только под одно имя: ответ с любым IP
	// принадлежит этому имени, иначе два домена на одном порту смешались бы.
	if !u.strict {
		return true
	}
	key := net.JoinHostPort(host, strconv.Itoa(port))
	u.mu.Lock()
	_, ok := u.flows[key]
	u.mu.Unlock()
	return ok
}

func (u *udpUpstream) close() {
	u.closeOnce.Do(func() {
		u.mu.Lock()
		u.closed = true
		u.mu.Unlock()
		_ = u.control.Close()
		_ = u.relay.Close()
	})
}

func cloneUDPAddr(addr *net.UDPAddr) *net.UDPAddr {
	if addr == nil {
		return nil
	}
	return &net.UDPAddr{
		IP:   append(net.IP(nil), addr.IP...),
		Port: addr.Port,
		Zone: addr.Zone,
	}
}

func sameUDPAddr(a, b *net.UDPAddr) bool {
	return a != nil && b != nil && a.Port == b.Port && a.IP.Equal(b.IP)
}

func parseUDPDatagram(pkt []byte) (string, int, []byte, bool) {
	if len(pkt) < 4 || pkt[2] != 0 {
		return "", 0, nil, false
	}
	var host string
	var off int
	switch pkt[3] {
	case 0x01:
		if len(pkt) < 10 {
			return "", 0, nil, false
		}
		host, off = net.IP(pkt[4:8]).String(), 8
	case 0x03:
		if len(pkt) < 5 {
			return "", 0, nil, false
		}
		l := int(pkt[4])
		if len(pkt) < 5+l+2 {
			return "", 0, nil, false
		}
		host, off = string(pkt[5:5+l]), 5+l
	case 0x04:
		if len(pkt) < 22 {
			return "", 0, nil, false
		}
		host, off = net.IP(pkt[4:20]).String(), 20
	default:
		return "", 0, nil, false
	}
	port := int(binary.BigEndian.Uint16(pkt[off:]))
	off += 2
	if off > len(pkt) {
		return "", 0, nil, false
	}
	return host, port, pkt[off:], true
}

func buildUDPDatagram(host string, port int, payload []byte) ([]byte, error) {
	out := []byte{0, 0, 0}
	ip := net.ParseIP(host)
	switch {
	case ip == nil:
		if len(host) == 0 || len(host) > 255 {
			return nil, errors.New("недопустимое имя в UDP-ответе")
		}
		out = append(out, 0x03, byte(len(host)))
		out = append(out, host...)
	case ip.To4() != nil:
		out = append(out, 0x01)
		out = append(out, ip.To4()...)
	default:
		out = append(out, 0x04)
		out = append(out, ip.To16()...)
	}
	out = append(out, byte(port>>8), byte(port))
	out = append(out, payload...)
	return out, nil
}
