// nova-router — локальный SOCKS5-диспетчер между tun2proxy и слотами.
//
// tun2proxy отдаёт сюда TCP-соединения; роутер по домену выбирает порядок
// слотов из конфигурации (её собирает Kotlin-политика) и пробует их по очереди.
// Это Android-замена PAC: одно соединение — одно решение — цепочка слотов.
package main

import (
	"encoding/binary"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"
)

func main() {
	listen := flag.String("listen", "127.0.0.1:1373", "адрес локального SOCKS5")
	configPath := flag.String("config", "", "путь к JSON-конфигурации маршрутов")
	statusPath := flag.String("status", "", "куда писать JSON со статистикой слотов")
	verbose := flag.Bool("v", false, "подробный лог")
	flag.Parse()

	if *configPath == "" {
		log.Fatal("нужен -config")
	}
	cfg, err := LoadConfig(*configPath)
	if err != nil {
		log.Fatalf("конфигурация: %v", err)
	}
	router := Router{cfg: cfg, stats: NewStats()}
	listenPort := 0
	if _, portText, splitErr := net.SplitHostPort(*listen); splitErr == nil {
		if parsed, parseErr := strconv.Atoi(portText); parseErr == nil {
			listenPort = parsed
		}
	}
	if *statusPath != "" {
		stats := router.stats
		go func() {
			ticker := time.NewTicker(3 * time.Second)
			defer ticker.Stop()
			for range ticker.C {
				stats.writeFile(*statusPath, listenPort)
			}
		}()
	}

	ln, err := net.Listen("tcp", *listen)
	if err != nil {
		log.Fatalf("listen %s: %v", *listen, err)
	}
	log.Printf("nova-router слушает %s, слотов %d, правил %d", *listen, len(cfg.Slots), len(cfg.Rules))

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt, syscall.SIGTERM)
	go func() {
		<-stop
		_ = ln.Close()
	}()

	for {
		conn, err := ln.Accept()
		if err != nil {
			return
		}
		go func() {
			defer conn.Close()
			if err := serveSOCKS(conn, router, *verbose); err != nil && *verbose {
				log.Printf("сеанс: %v", err)
			}
		}()
	}
}

func serveSOCKS(client net.Conn, router Router, verbose bool) error {
	cmd, host, port, err := handshake(client)
	if err != nil {
		return err
	}
	if cmd == socksUDPAssociate {
		return serveUDPAssociate(client)
	}
	upstream, slot, err := router.Dial(host, port)
	if err != nil {
		_ = writeReply(client, 0x05)
		if verbose {
			log.Printf("%s:%d — нет слота: %v", host, port, err)
		}
		return nil
	}
	defer upstream.Close()
	if verbose {
		log.Printf("%s:%d → %s", host, port, slot)
	}
	if err := writeReply(client, 0x00); err != nil {
		return err
	}
	pipeBoth(client, upstream)
	return nil
}

func handshake(conn net.Conn) (byte, string, int, error) {
	hdr := make([]byte, 2)
	if _, err := io.ReadFull(conn, hdr); err != nil {
		return 0, "", 0, err
	}
	if hdr[0] != 5 {
		return 0, "", 0, fmt.Errorf("SOCKS version %d", hdr[0])
	}
	if _, err := io.ReadFull(conn, make([]byte, int(hdr[1]))); err != nil {
		return 0, "", 0, err
	}
	if _, err := conn.Write([]byte{5, 0}); err != nil {
		return 0, "", 0, err
	}
	req := make([]byte, 4)
	if _, err := io.ReadFull(conn, req); err != nil {
		return 0, "", 0, err
	}
	if req[0] != 5 || (req[1] != 1 && req[1] != socksUDPAssociate) {
		_ = writeReply(conn, 0x07)
		return 0, "", 0, fmt.Errorf("поддерживаются CONNECT и UDP ASSOCIATE")
	}
	var host string
	switch req[3] {
	case 1:
		raw := make([]byte, 4)
		if _, err := io.ReadFull(conn, raw); err != nil {
			return 0, "", 0, err
		}
		host = net.IP(raw).String()
	case 3:
		l := make([]byte, 1)
		if _, err := io.ReadFull(conn, l); err != nil {
			return 0, "", 0, err
		}
		name := make([]byte, int(l[0]))
		if _, err := io.ReadFull(conn, name); err != nil {
			return 0, "", 0, err
		}
		host = string(name)
	case 4:
		raw := make([]byte, 16)
		if _, err := io.ReadFull(conn, raw); err != nil {
			return 0, "", 0, err
		}
		host = net.IP(raw).String()
	default:
		_ = writeReply(conn, 0x08)
		return 0, "", 0, fmt.Errorf("ATYP %d", req[3])
	}
	rawPort := make([]byte, 2)
	if _, err := io.ReadFull(conn, rawPort); err != nil {
		return 0, "", 0, err
	}
	return req[1], host, int(binary.BigEndian.Uint16(rawPort)), nil
}

func writeReply(w io.Writer, code byte) error {
	_, err := w.Write([]byte{5, code, 0, 1, 0, 0, 0, 0, 0, 0})
	return err
}

func pipeBoth(a, b net.Conn) {
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
}
