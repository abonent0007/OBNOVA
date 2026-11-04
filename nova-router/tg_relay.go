package main

import (
	"errors"
	"fmt"
	"io"
	"net"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Прозрачный релей Telegram: клиент говорит obfuscated2, релей нарезает поток
// на MTProto-пакеты и отправляет их в KWS по WSS (по одному пакету на кадр),
// как TgRelay в Kaliopa. Первый кадр — ровно 64-байтовый init.
//
// Соединение ленивое: SOCKS-клиенту отвечаем сразу, а WSS открываем, когда
// пришли init и первый пакет — только тогда можно проверить маршрут первым
// ответным кадром.

const tgFirstResponseTimeout = 5 * time.Second

type tgRelayConn struct {
	slot Slot
	host string
	port int

	mu        sync.Mutex
	clientBuf []byte
	splitter  *tgSplitter
	info      *tgInit
	ws        *wsConn
	pending   []byte
	err       error

	ready     chan struct{}
	readyOnce sync.Once
	closed    bool
}

func newTGRelayConn(slot Slot, host string, port int) net.Conn {
	return &tgRelayConn{
		slot:  slot,
		host:  host,
		port:  port,
		ready: make(chan struct{}),
	}
}

func (c *tgRelayConn) signalReady() {
	c.readyOnce.Do(func() { close(c.ready) })
}

func (c *tgRelayConn) Write(p []byte) (int, error) {
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		return 0, io.ErrClosedPipe
	}
	if c.ws != nil {
		splitter := c.splitter
		ws := c.ws
		c.mu.Unlock()
		for _, packet := range splitter.split(p) {
			if _, err := ws.Write(packet); err != nil {
				return 0, err
			}
		}
		return len(p), nil
	}

	c.clientBuf = append(c.clientBuf, p...)
	if c.splitter == nil {
		if len(c.clientBuf) < 64 {
			c.mu.Unlock()
			return len(p), nil
		}
		fallbackDC, fallbackMedia := fallbackDCForIP(c.host)
		info, err := parseTGInit(c.clientBuf[:64], fallbackDC, fallbackMedia)
		if err != nil {
			c.err = err
			c.signalReady()
			c.mu.Unlock()
			return 0, err
		}
		splitter, err := newTGSplitter(c.clientBuf[:64], info.proto)
		if err != nil {
			c.err = err
			c.signalReady()
			c.mu.Unlock()
			return 0, err
		}
		c.info = info
		c.splitter = splitter
		c.clientBuf = c.clientBuf[64:]
	}
	packets := c.splitter.split(c.clientBuf)
	c.clientBuf = nil
	if len(packets) == 0 {
		c.mu.Unlock()
		return len(p), nil
	}
	first := packets[0]
	rest := packets[1:]
	info := c.info
	c.mu.Unlock()

	ws, firstResp, err := c.dialRelay(info, first)
	if err != nil {
		c.mu.Lock()
		c.err = err
		c.signalReady()
		c.mu.Unlock()
		return 0, err
	}
	c.mu.Lock()
	c.ws = ws
	c.pending = firstResp
	c.signalReady()
	c.mu.Unlock()
	for _, packet := range rest {
		if _, err := ws.Write(packet); err != nil {
			return 0, err
		}
	}
	return len(p), nil
}

func (c *tgRelayConn) Read(p []byte) (int, error) {
	<-c.ready
	c.mu.Lock()
	if len(c.pending) > 0 {
		n := copy(p, c.pending)
		c.pending = c.pending[n:]
		c.mu.Unlock()
		return n, nil
	}
	ws := c.ws
	err := c.err
	c.mu.Unlock()
	if err != nil {
		return 0, err
	}
	if ws == nil {
		return 0, io.EOF
	}
	return ws.Read(p)
}

func (c *tgRelayConn) Close() error {
	c.mu.Lock()
	c.closed = true
	ws := c.ws
	c.mu.Unlock()
	if ws != nil {
		return ws.Close()
	}
	return nil
}

func (c *tgRelayConn) LocalAddr() net.Addr  { return c.slotAddr() }
func (c *tgRelayConn) RemoteAddr() net.Addr { return c.slotAddr() }

func (c *tgRelayConn) slotAddr() net.Addr {
	if c.slot.Via != "" {
		if addr, err := net.ResolveTCPAddr("tcp", c.slot.Via); err == nil {
			return addr
		}
	}
	return &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1)}
}

func (c *tgRelayConn) SetDeadline(t time.Time) error {
	if ws, ok := c.currentWS(); ok {
		return ws.SetDeadline(t)
	}
	return nil
}

func (c *tgRelayConn) SetReadDeadline(t time.Time) error {
	if ws, ok := c.currentWS(); ok {
		return ws.SetReadDeadline(t)
	}
	return nil
}

func (c *tgRelayConn) SetWriteDeadline(t time.Time) error {
	if ws, ok := c.currentWS(); ok {
		return ws.SetWriteDeadline(t)
	}
	return nil
}

func (c *tgRelayConn) currentWS() (*wsConn, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.ws, c.ws != nil
}

type tgRouteResult struct {
	ws   *wsConn
	resp []byte
	err  error
}

func (c *tgRelayConn) dialRelay(info *tgInit, first []byte) (*wsConn, []byte, error) {
	urls := c.candidates(info)
	if len(urls) == 0 {
		return nil, nil, errors.New("нет маршрутов KWS")
	}
	results := make(chan tgRouteResult, len(urls))
	stop := make(chan struct{})
	var once sync.Once
	for _, url := range urls {
		go func(target string) {
			select {
			case <-stop:
				results <- tgRouteResult{err: errors.New("маршрут отменён")}
				return
			default:
			}
			ws, resp, err := c.tryRoute(target, info, first)
			results <- tgRouteResult{ws: ws, resp: resp, err: err}
		}(url)
	}
	var lastErr error
	for range urls {
		result := <-results
		if result.err == nil {
			once.Do(func() { close(stop) })
			// Проигравшие закроются сами: их результат уже в канале.
			go func() {
				for remaining := 1; remaining < len(urls); remaining++ {
					r := <-results
					if r.ws != nil {
						_ = r.ws.Close()
					}
				}
			}()
			return result.ws, result.resp, nil
		}
		lastErr = result.err
	}
	if lastErr == nil {
		lastErr = errors.New("все маршруты KWS отказали")
	}
	return nil, nil, lastErr
}

func (c *tgRelayConn) candidates(info *tgInit) []string {
	dc := info.dc
	if dc < 1 || dc > 5 {
		if fallback, _ := fallbackDCForIP(c.host); fallback > 0 {
			dc = fallback
		} else {
			dc = 2
		}
	}
	template := strings.TrimSpace(c.slot.URL)
	if !strings.Contains(template, "{dc}") {
		template = "wss://kws{dc}.web.telegram.org/apiws"
	}
	main := strings.ReplaceAll(template, "{dc}", strconv.Itoa(dc))
	alias := strings.ReplaceAll(template, "{dc}", fmt.Sprintf("%d-1", dc))
	if info.media {
		return []string{alias, main}
	}
	return []string{main, alias}
}

func (c *tgRelayConn) tryRoute(url string, info *tgInit, first []byte) (*wsConn, []byte, error) {
	ws, err := dialWebSocket(url, c.slot.Secret, c.slot.Via)
	if err != nil {
		return nil, nil, fmt.Errorf("%s: %w", url, err)
	}
	if _, err := ws.Write(info.raw); err != nil {
		_ = ws.Close()
		return nil, nil, fmt.Errorf("%s: init: %w", url, err)
	}
	if _, err := ws.Write(first); err != nil {
		_ = ws.Close()
		return nil, nil, fmt.Errorf("%s: первый пакет: %w", url, err)
	}
	_ = ws.SetReadDeadline(time.Now().Add(tgFirstResponseTimeout))
	buf := make([]byte, 32*1024)
	n, err := ws.Read(buf)
	if err != nil {
		_ = ws.Close()
		return nil, nil, fmt.Errorf("%s: первый ответ: %w", url, err)
	}
	if n == 0 {
		_ = ws.Close()
		return nil, nil, fmt.Errorf("%s: пустой первый ответ", url)
	}
	_ = ws.SetReadDeadline(time.Time{})
	return ws, append([]byte(nil), buf[:n]...), nil
}
