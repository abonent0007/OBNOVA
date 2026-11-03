package main

import (
	"bufio"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"strconv"
	"time"
)

const (
	socksVersion      = 5
	socksConnect      = 1
	socksUDPAssociate = 3
)

// serveSOCKS принимает одно соединение, договаривается по SOCKS5 и открывает
// исходящий сокет. Для 443 включает TLS-обход, для остальных портов — обычный
// passthrough: ломать произвольный протокол фрагментацией нельзя.
func serveSOCKS(client net.Conn, cfg config) error {
	cmd, host, port, err := socksHandshake(client)
	if err != nil {
		return err
	}
	if cmd == socksUDPAssociate {
		// UDP нужен прежде всего DNS: без него tun2proxy получает
		// CommandNotSupported и имена не разрешаются вовсе.
		return serveUDPAssociate(client)
	}
	target := net.JoinHostPort(host, strconv.Itoa(port))
	upstream, err := dialTarget("tcp", target)
	if err != nil {
		_ = writeSOCKSReply(client, 0x05) // connection refused
		return fmt.Errorf("dial %s: %w", target, err)
	}
	defer upstream.Close()
	if err := writeSOCKSReply(client, 0x00); err != nil {
		return err
	}
	if port == 443 {
		return spliceTLS(client, upstream, target, cfg)
	}
	return pipeBoth(client, upstream)
}

func socksHandshake(conn net.Conn) (byte, string, int, error) {
	hdr := make([]byte, 2)
	if _, err := io.ReadFull(conn, hdr); err != nil {
		return 0, "", 0, err
	}
	if hdr[0] != socksVersion {
		return 0, "", 0, fmt.Errorf("socks version %d", hdr[0])
	}
	methods := make([]byte, int(hdr[1]))
	if _, err := io.ReadFull(conn, methods); err != nil {
		return 0, "", 0, err
	}
	if _, err := conn.Write([]byte{socksVersion, 0x00}); err != nil {
		return 0, "", 0, err
	}

	req := make([]byte, 4)
	if _, err := io.ReadFull(conn, req); err != nil {
		return 0, "", 0, err
	}
	if req[0] != socksVersion {
		return 0, "", 0, errors.New("не SOCKS5")
	}
	if req[1] != socksConnect && req[1] != socksUDPAssociate {
		_ = writeSOCKSReply(conn, 0x07)
		return 0, "", 0, errors.New("поддерживаются CONNECT и UDP ASSOCIATE")
	}

	var host string
	switch req[3] {
	case 0x01:
		addr := make([]byte, 4)
		if _, err := io.ReadFull(conn, addr); err != nil {
			return 0, "", 0, err
		}
		host = net.IP(addr).String()
	case 0x03:
		l := make([]byte, 1)
		if _, err := io.ReadFull(conn, l); err != nil {
			return 0, "", 0, err
		}
		name := make([]byte, int(l[0]))
		if _, err := io.ReadFull(conn, name); err != nil {
			return 0, "", 0, err
		}
		host = string(name)
	case 0x04:
		addr := make([]byte, 16)
		if _, err := io.ReadFull(conn, addr); err != nil {
			return 0, "", 0, err
		}
		host = net.IP(addr).String()
	default:
		_ = writeSOCKSReply(conn, 0x08)
		return 0, "", 0, fmt.Errorf("atyp %d", req[3])
	}
	p := make([]byte, 2)
	if _, err := io.ReadFull(conn, p); err != nil {
		return 0, "", 0, err
	}
	return req[1], host, int(binary.BigEndian.Uint16(p)), nil
}

func writeSOCKSReply(w io.Writer, code byte) error {
	_, err := w.Write([]byte{socksVersion, code, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
	return err
}

func pipeBoth(a net.Conn, b net.Conn) error {
	done := make(chan struct{}, 2)
	go func() {
		_, _ = io.Copy(b, a)
		done <- struct{}{}
	}()
	go func() {
		_, _ = io.Copy(a, b)
		done <- struct{}{}
	}()
	<-done
	return nil
}

// spliceTLS читает первый TLS-рекорд, применяет паддинг, decoy и фрагментацию,
// затем отдаёт остаток обычным двусторонним копированием.
func spliceTLS(client net.Conn, upstream net.Conn, target string, cfg config) error {
	_ = client.SetReadDeadline(time.Now().Add(3 * time.Second))
	reader := bufio.NewReader(client)

	first, err := reader.Peek(1)
	if err != nil {
		return err
	}
	if first[0] != 0x16 {
		// Не TLS: отдаём как есть, иначе сломаем, например, SSH на 443.
		_ = client.SetReadDeadline(time.Time{})
		return pipeBoth(readerConn{reader, client}, upstream)
	}

	hello, err := readTLSRecord(reader)
	if err != nil {
		return err
	}
	_ = client.SetReadDeadline(time.Time{})

	if cfg.decoySNI != "" {
		sendDecoy(target, hello, cfg)
	}

	// Паддинг настоящего ClientHello здесь запрещён: TLS-клиент хеширует
	// отправленные байты, и любая вставка ломает transcript (schannel отвечает
	// SEC_E_MESSAGE_ALTERED). Паддинг возможен только в decoy-пакете.
	if cfg.pad > 0 && cfg.verbose {
		logf(cfg, "паддинг пропущен: меняет transcript настоящего рукопожатия")
	}
	payload := hello

	chunks := [][]byte{payload}
	if cfg.recordSplit {
		if parts, err := splitRecord(payload, cfg.split); err == nil {
			chunks = parts
		}
	}
	if !cfg.recordSplit && cfg.split > 1 {
		chunks = splitBytes(payload, cfg.split)
	}

	for i, chunk := range chunks {
		if _, err := upstream.Write(chunk); err != nil {
			return err
		}
		if i < len(chunks)-1 && cfg.delay > 0 {
			time.Sleep(cfg.delay)
		}
	}

	return pipeBoth(readerConn{reader, client}, upstream)
}

// readerConn склеивает bufio.Reader (в нём уже может лежать начало потока)
// с исходным соединением для io.Copy.
type readerConn struct {
	r *bufio.Reader
	c net.Conn
}

func (rc readerConn) Read(p []byte) (int, error)  { return rc.r.Read(p) }
func (rc readerConn) Write(p []byte) (int, error) { return rc.c.Write(p) }
func (rc readerConn) Close() error                { return rc.c.Close() }
func (rc readerConn) LocalAddr() net.Addr         { return rc.c.LocalAddr() }
func (rc readerConn) RemoteAddr() net.Addr        { return rc.c.RemoteAddr() }
func (rc readerConn) SetDeadline(t time.Time) error {
	return rc.c.SetDeadline(t)
}
func (rc readerConn) SetReadDeadline(t time.Time) error  { return rc.c.SetReadDeadline(t) }
func (rc readerConn) SetWriteDeadline(t time.Time) error { return rc.c.SetWriteDeadline(t) }

func readTLSRecord(r io.Reader) ([]byte, error) {
	hdr := make([]byte, 5)
	if _, err := io.ReadFull(r, hdr); err != nil {
		return nil, err
	}
	if hdr[0] != 0x16 {
		return nil, errors.New("не TLS handshake")
	}
	n := int(binary.BigEndian.Uint16(hdr[3:5]))
	if n <= 0 {
		return nil, errors.New("пустой TLS-рекорд")
	}
	body := make([]byte, n)
	if _, err := io.ReadFull(r, body); err != nil {
		return nil, err
	}
	return append(hdr, body...), nil
}

func logf(cfg config, format string, args ...any) {
	if cfg.verbose {
		println(fmt.Sprintf(format, args...))
	}
}

// serveUDPAssociate обслуживает UDP ASSOCIATE. Без него tun2proxy получает
// CommandNotSupported, и приложения, которые ходят в DNS мимо перехвата,
// остаются без имён. UDP/443 (QUIC) намеренно отбрасываем: в этом узле он
// должен идти по TCP, иначе фрагментация ClientHello не применяется.
func serveUDPAssociate(control net.Conn) error {
	relay, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		_ = writeSOCKSReply(control, 0x01)
		return err
	}
	defer relay.Close()
	local := relay.LocalAddr().(*net.UDPAddr)
	reply := []byte{5, 0, 0, 1, 127, 0, 0, 1, byte(local.Port >> 8), byte(local.Port)}
	if _, err := control.Write(reply); err != nil {
		return err
	}
	// Управляющий TCP-канал: пока он жив, живёт и UDP-релей.
	go func() {
		_, _ = io.Copy(io.Discard, control)
		_ = relay.Close()
	}()

	buf := make([]byte, 65_535)
	for {
		n, from, err := relay.ReadFromUDP(buf)
		if err != nil {
			return nil
		}
		host, port, payload, ok := parseUDPDatagram(buf[:n])
		if !ok || port == 443 {
			continue
		}
		// Ответ обязан уйти на тот же адрес, с которого пришла датаграмма:
		// tun2proxy может держать несколько UDP-сокетов на разные потоки.
		go forwardUDP(relay, from, host, port, payload)
	}
}

func forwardUDP(relay *net.UDPConn, client *net.UDPAddr, host string, port int, payload []byte) {
	ips, err := resolveHost(host)
	if err != nil || len(ips) == 0 {
		return
	}
	addr := &net.UDPAddr{IP: ips[0], Port: port}
	conn, err := net.DialUDP("udp", nil, addr)
	if err != nil {
		return
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
	if _, err := conn.Write(payload); err != nil {
		return
	}
	resp := make([]byte, 65_535)
	n, err := conn.Read(resp)
	if err != nil {
		return
	}
	packet, err := buildUDPDatagram(host, port, resp[:n])
	if err != nil {
		return
	}
	_, _ = relay.WriteToUDP(packet, client)
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
		if len(pkt) < 20 {
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
