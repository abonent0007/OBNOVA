// nova-desync — локальный SOCKS5-прокси, который обходит DPI, разбивая
// TLS ClientHello на несколько TCP-сегментов и TLS-записей.
//
// Запускается как дочерний процесс (по образцу opera-proxy) и слушает петлю.
// В APK упаковывается как libnova-desync.so: Android запрещает exec из
// data-каталога, а nativeLibraryDir исполняемый.
package main

import (
	"flag"
	"log"
	"net"
	"os"
	"os/signal"
	"runtime/debug"
	"syscall"
	"time"
)

type config struct {
	listen      string
	split       int
	delay       time.Duration
	pad         int
	decoySNI    string
	decoyWait   time.Duration
	recordSplit bool
	verbose     bool
}

func main() {
	cfg := config{}
	flag.StringVar(&cfg.listen, "listen", "127.0.0.1:1376", "адрес локального SOCKS5")
	flag.IntVar(&cfg.split, "split", 3, "на сколько TCP-сегментов резать ClientHello (0 — не резать)")
	flag.DurationVar(&cfg.delay, "delay", 12*time.Millisecond, "пауза между сегментами")
	flag.IntVar(&cfg.pad, "pad", 0, "паддинг decoy-пакета (к настоящему ClientHello не применяется: ломает transcript)")
	flag.StringVar(&cfg.decoySNI, "decoy", "", "SNI для decoy-соединения (пусто — выключено)")
	flag.DurationVar(&cfg.decoyWait, "decoy-wait", 80*time.Millisecond, "пауза перед закрытием decoy")
	flag.BoolVar(&cfg.recordSplit, "record-split", true, "резать ClientHello на несколько TLS-записей")
	flag.BoolVar(&cfg.verbose, "v", false, "подробный лог")
	flag.Parse()

	if cfg.split < 0 || cfg.pad < 0 {
		log.Fatal("split и pad не могут быть отрицательными")
	}

	ln, err := net.Listen("tcp", cfg.listen)
	if err != nil {
		log.Fatalf("listen %s: %v", cfg.listen, err)
	}
	log.Printf("nova-desync слушает %s (split=%d delay=%s pad=%d decoy=%q)",
		cfg.listen, cfg.split, cfg.delay, cfg.pad, cfg.decoySNI)

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt, syscall.SIGTERM)
	go func() {
		<-stop
		_ = ln.Close()
	}()

	for {
		conn, err := ln.Accept()
		if err != nil {
			log.Printf("accept: %v", err)
			return
		}
		go func() {
			// Паника в одном соединении не должна уносить весь движок:
			// повреждённый ClientHello приходит снаружи, и цена ошибки —
			// оборванный VPN у всех приложений, а не один неудачный сеанс.
			defer func() {
				if recovered := recover(); recovered != nil {
					log.Printf("panic в сеансе: %v\n%s", recovered, debug.Stack())
				}
			}()
			defer conn.Close()
			if err := serveSOCKS(conn, cfg); err != nil && cfg.verbose {
				log.Printf("сеанс: %v", err)
			}
		}()
	}
}
