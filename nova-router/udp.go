package main

import (
	"encoding/binary"
	"errors"
	"io"
	"net"
	"strconv"
	"time"
)

const socksUDPAssociate = 3

// serveUDPAssociate обслуживает UDP ASSOCIATE.
//
// Без него tun2proxy получает CommandNotSupported, и приложения, которые
// ходят в DNS или QUIC мимо перехвата ядра, остаются без ответа. UDP/443
// намеренно отбрасываем: QUIC должен идти по TCP, иначе правила маршрутов
// к нему не применяются.
func serveUDPAssociate(control net.Conn) error {
	relay, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		_ = writeReply(control, 0x01)
		return err
	}
	defer relay.Close()

	local := relay.LocalAddr().(*net.UDPAddr)
	reply := []byte{5, 0, 0, 1, 127, 0, 0, 1, byte(local.Port >> 8), byte(local.Port)}
	if _, err := control.Write(reply); err != nil {
		return err
	}
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
		go forwardUDP(relay, from, host, port, payload)
	}
}

func forwardUDP(relay *net.UDPConn, client *net.UDPAddr, host string, port int, payload []byte) {
	addr, err := net.ResolveUDPAddr("udp", net.JoinHostPort(host, strconv.Itoa(port)))
	if err != nil {
		return
	}
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
