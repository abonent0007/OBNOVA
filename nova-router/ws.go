package main

import (
	"bufio"
	"crypto/rand"
	"crypto/sha1"
	"crypto/tls"
	"encoding/base64"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Минимальный WebSocket-клиент для реле Telegram.
//
// MTProto — это поток байт, поэтому сообщения не собираются: payload каждого
// binary-фрейма сразу уходит в TCP, а ответы TCP — одним binary-фреймом.
// Клиентские фреймы маскируются, как требует RFC 6455.

const wsDialTimeout = 10 * time.Second

// Сколько ждать первый ответ реле после того, как клиент отправил данные.
// Telegram DC отвечает за 0.3–1 с; молчание дольше — пустой WSS-маршрут.
const wsFirstReplyTimeout = 6 * time.Second

type wsConn struct {
	conn    net.Conn
	reader  *bufio.Reader
	pending []byte
	writeMu sync.Mutex
	// firstWriteOnce — сторож пустого ответа: после первых данных клиента
	// ждём ответ реле не дольше wsFirstReplyTimeout. Если реле промолчало
	// (в логах ПК это «WSS empty response»), соединение закрывается, и
	// Telegram переподключается — вместо бесконечного «Соединение...».
	firstWriteOnce sync.Once
}

// dialWebSocket открывает WSS-соединение и проверяет 101 Switching Protocols.
func dialWebSocket(rawURL, secret string, via string) (*wsConn, error) {
	parsed, err := url.Parse(rawURL)
	if err != nil {
		return nil, fmt.Errorf("плохой URL реле: %w", err)
	}
	if parsed.Scheme != "wss" {
		return nil, fmt.Errorf("реле должно быть wss, а не %q", parsed.Scheme)
	}
	host := parsed.Hostname()
	port := parsed.Port()
	if port == "" {
		port = "443"
	}
	addr := net.JoinHostPort(host, port)
	var raw net.Conn
	if strings.TrimSpace(via) != "" {
		portNum, convErr := strconv.Atoi(port)
		if convErr != nil {
			return nil, fmt.Errorf("плохой порт реле %q", port)
		}
		raw, err = dialSocks5(strings.TrimSpace(via), host, portNum)
		if err != nil {
			return nil, fmt.Errorf("реле через %s: %w", via, err)
		}
	} else {
		dialer := &net.Dialer{Timeout: wsDialTimeout}
		raw, err = dialer.Dial("tcp", addr)
		if err != nil {
			return nil, fmt.Errorf("реле %s: %w", addr, err)
		}
	}
	tlsConn := tls.Client(raw, &tls.Config{
		ServerName: host,
		NextProtos: []string{"http/1.1"},
		MinVersion: tls.VersionTLS12,
	})
	if err := tlsConn.SetDeadline(time.Now().Add(wsDialTimeout)); err != nil {
		_ = raw.Close()
		return nil, err
	}
	if err := tlsConn.Handshake(); err != nil {
		_ = raw.Close()
		return nil, fmt.Errorf("TLS реле: %w", err)
	}

	keyBytes := make([]byte, 16)
	if _, err := rand.Read(keyBytes); err != nil {
		_ = tlsConn.Close()
		return nil, err
	}
	key := base64.StdEncoding.EncodeToString(keyBytes)

	path := parsed.RequestURI()
	if path == "" {
		path = "/"
	}
	var request strings.Builder
	request.WriteString("GET " + path + " HTTP/1.1\r\n")
	request.WriteString("Host: " + parsed.Host + "\r\n")
	request.WriteString("Upgrade: websocket\r\n")
	request.WriteString("Connection: Upgrade\r\n")
	request.WriteString("Sec-WebSocket-Key: " + key + "\r\n")
	request.WriteString("Sec-WebSocket-Version: 13\r\n")
	request.WriteString("Sec-WebSocket-Protocol: binary\r\n")
	if secret != "" {
		request.WriteString("X-Relay-Secret: " + secret + "\r\n")
	}
	request.WriteString("\r\n")
	if _, err := tlsConn.Write([]byte(request.String())); err != nil {
		_ = tlsConn.Close()
		return nil, fmt.Errorf("запрос реле: %w", err)
	}

	reader := bufio.NewReaderSize(tlsConn, 32*1024)
	status, err := reader.ReadString('\n')
	if err != nil {
		_ = tlsConn.Close()
		return nil, fmt.Errorf("ответ реле: %w", err)
	}
	if !strings.Contains(status, " 101 ") {
		_ = tlsConn.Close()
		return nil, fmt.Errorf("реле ответило %q", strings.TrimSpace(status))
	}
	acceptExpected := wsAccept(key)
	acceptSeen := false
	for {
		line, err := reader.ReadString('\n')
		if err != nil {
			_ = tlsConn.Close()
			return nil, fmt.Errorf("заголовки реле: %w", err)
		}
		trimmed := strings.TrimRight(line, "\r\n")
		if trimmed == "" {
			break
		}
		if name, value, ok := strings.Cut(trimmed, ":"); ok {
			if strings.EqualFold(strings.TrimSpace(name), "Sec-WebSocket-Accept") &&
				strings.TrimSpace(value) == acceptExpected {
				acceptSeen = true
			}
		}
	}
	if !acceptSeen {
		_ = tlsConn.Close()
		return nil, fmt.Errorf("реле не подтвердило WebSocket-ключ")
	}
	_ = tlsConn.SetDeadline(time.Time{})
	return &wsConn{conn: tlsConn, reader: reader}, nil
}

func wsAccept(key string) string {
	sum := sha1.Sum([]byte(key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"))
	return base64.StdEncoding.EncodeToString(sum[:])
}

func (c *wsConn) Write(p []byte) (int, error) {
	c.writeMu.Lock()
	defer c.writeMu.Unlock()
	if err := writeWSFrame(c.conn, 0x2, p); err != nil {
		return 0, err
	}
	c.firstWriteOnce.Do(func() {
		_ = c.conn.SetReadDeadline(time.Now().Add(wsFirstReplyTimeout))
	})
	return len(p), nil
}

func (c *wsConn) Read(p []byte) (int, error) {
	if len(c.pending) > 0 {
		n := copy(p, c.pending)
		c.pending = c.pending[n:]
		return n, nil
	}
	for {
		opcode, payload, err := readWSFrame(c.reader)
		if err != nil {
			return 0, err
		}
		switch opcode {
		case 0x2, 0x0:
			if len(payload) == 0 {
				continue
			}
			// Первый ответ пришёл — снимаем сторож пустого маршрута.
			_ = c.conn.SetReadDeadline(time.Time{})
			n := copy(p, payload)
			if n < len(payload) {
				c.pending = payload[n:]
			}
			return n, nil
		case 0x8:
			return 0, io.EOF
		case 0x9:
			c.writeMu.Lock()
			_ = writeWSFrame(c.conn, 0xA, payload)
			c.writeMu.Unlock()
		case 0xA:
			continue
		}
	}
}

func (c *wsConn) Close() error {
	c.writeMu.Lock()
	_ = writeWSFrame(c.conn, 0x8, []byte{0x03, 0xE8})
	c.writeMu.Unlock()
	return c.conn.Close()
}

func (c *wsConn) SetDeadline(t time.Time) error      { return c.conn.SetDeadline(t) }
func (c *wsConn) SetReadDeadline(t time.Time) error  { return c.conn.SetReadDeadline(t) }
func (c *wsConn) SetWriteDeadline(t time.Time) error { return c.conn.SetWriteDeadline(t) }
func (c *wsConn) LocalAddr() net.Addr                { return c.conn.LocalAddr() }
func (c *wsConn) RemoteAddr() net.Addr               { return c.conn.RemoteAddr() }

func writeWSFrame(w io.Writer, opcode byte, payload []byte) error {
	header := make([]byte, 0, 14)
	header = append(header, 0x80|opcode)
	length := len(payload)
	switch {
	case length <= 125:
		header = append(header, byte(length)|0x80)
	case length <= 65535:
		header = append(header, 126|0x80, byte(length>>8), byte(length))
	default:
		header = append(header, 127|0x80)
		var size [8]byte
		binary.BigEndian.PutUint64(size[:], uint64(length))
		header = append(header, size[:]...)
	}
	mask := make([]byte, 4)
	if _, err := rand.Read(mask); err != nil {
		return err
	}
	header = append(header, mask...)
	masked := make([]byte, length)
	for i := 0; i < length; i++ {
		masked[i] = payload[i] ^ mask[i%4]
	}
	if _, err := w.Write(header); err != nil {
		return err
	}
	_, err := w.Write(masked)
	return err
}

func readWSFrame(r *bufio.Reader) (byte, []byte, error) {
	head := make([]byte, 2)
	if _, err := io.ReadFull(r, head); err != nil {
		return 0, nil, err
	}
	opcode := head[0] & 0x0F
	masked := head[1]&0x80 != 0
	length := int(head[1] & 0x7F)
	switch length {
	case 126:
		var ext [2]byte
		if _, err := io.ReadFull(r, ext[:]); err != nil {
			return 0, nil, err
		}
		length = int(binary.BigEndian.Uint16(ext[:]))
	case 127:
		var ext [8]byte
		if _, err := io.ReadFull(r, ext[:]); err != nil {
			return 0, nil, err
		}
		size := binary.BigEndian.Uint64(ext[:])
		if size > 1<<20 {
			return 0, nil, fmt.Errorf("фрейм реле слишком велик: %d", size)
		}
		length = int(size)
	}
	var mask [4]byte
	if masked {
		if _, err := io.ReadFull(r, mask[:]); err != nil {
			return 0, nil, err
		}
	}
	payload := make([]byte, length)
	if _, err := io.ReadFull(r, payload); err != nil {
		return 0, nil, err
	}
	if masked {
		for i := 0; i < length; i++ {
			payload[i] ^= mask[i%4]
		}
	}
	return opcode, payload, nil
}
