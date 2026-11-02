package main

import (
	"encoding/binary"
	"errors"
	"fmt"
	"time"
)

var errHelloLayout = errors.New("неподдерживаемая раскладка ClientHello")

type helloInfo struct {
	extLenPos int // смещение двухбайтового поля длины расширений
	extStart  int
	extEnd    int
}

// parseHello разбирает один TLS-рекорд с целым ClientHello. Если ClientHello
// размазан по нескольким рекордам, возвращаем ошибку — такие соединения
// обрабатываем только грубым делением на рекорды.
func parseHello(rec []byte) (helloInfo, error) {
	// record(5) + handshake(4) + version(2) + random(32) + sidLen(1)
	if len(rec) < 44 {
		return helloInfo{}, errHelloLayout
	}
	if rec[0] != 0x16 || rec[5] != 0x01 {
		return helloInfo{}, errHelloLayout
	}
	hsLen := int(rec[6])<<16 | int(rec[7])<<8 | int(rec[8])
	if 9+hsLen != len(rec) {
		return helloInfo{}, errHelloLayout
	}
	p := 9 + 2 + 32
	sidLen := int(rec[p])
	p += 1 + sidLen
	if p+2 > len(rec) {
		return helloInfo{}, errHelloLayout
	}
	csLen := int(binary.BigEndian.Uint16(rec[p:]))
	p += 2 + csLen
	if p+1 > len(rec) {
		return helloInfo{}, errHelloLayout
	}
	compLen := int(rec[p])
	p += 1 + compLen
	if p+2 > len(rec) {
		return helloInfo{}, errHelloLayout
	}
	extLen := int(binary.BigEndian.Uint16(rec[p:]))
	extStart := p + 2
	if extStart+extLen != len(rec) {
		return helloInfo{}, errHelloLayout
	}
	return helloInfo{extLenPos: p, extStart: extStart, extEnd: len(rec)}, nil
}

// addPadding добавляет extension type 21 (padding) с нулями. Он не меняет
// семантику рукопожатия, но сдвигает реальный SNI за окно, которое DPI часто
// читает первым.
func addPadding(rec []byte, pad int) ([]byte, error) {
	if pad <= 0 {
		return rec, nil
	}
	info, err := parseHello(rec)
	if err != nil {
		return nil, err
	}
	ext := make([]byte, 4+pad)
	binary.BigEndian.PutUint16(ext[0:], 21)
	binary.BigEndian.PutUint16(ext[2:], uint16(pad))

	// Вставляем в начало списка расширений, а не в конец: TLS 1.3 требует,
	// чтобы pre_shared_key (41) оставался последним, иначе сервер отвечает
	// decode_error. Паддингу разрешено стоять где угодно.
	out := make([]byte, 0, len(rec)+len(ext))
	out = append(out, rec[:info.extStart]...)
	out = append(out, ext...)
	out = append(out, rec[info.extStart:]...)

	addExtLen(out, info.extLenPos, len(ext))
	addHandshakeLen(out, len(ext))
	addRecordLen(out, len(ext))
	return out, nil
}

// replaceSNI подменяет имя в расширении server_name, пересчитывая все длины.
// Нужен только для decoy-соединения: настоящий SNI остаётся прежним.
func replaceSNI(rec []byte, name string) ([]byte, error) {
	info, err := parseHello(rec)
	if err != nil {
		return nil, err
	}
	for off := info.extStart; off+4 <= info.extEnd; {
		typ := binary.BigEndian.Uint16(rec[off:])
		l := int(binary.BigEndian.Uint16(rec[off+2:]))
		data := off + 4
		if data+l > info.extEnd {
			return nil, errHelloLayout
		}
		if typ == 0 {
			if l < 5 {
				return nil, errHelloLayout
			}
			listLen := int(binary.BigEndian.Uint16(rec[data:]))
			nameLen := int(binary.BigEndian.Uint16(rec[data+3:]))
			nameStart := data + 5
			if nameStart+nameLen > data+l {
				return nil, errHelloLayout
			}
			delta := len(name) - nameLen
			out := make([]byte, 0, len(rec)+delta)
			out = append(out, rec[:nameStart]...)
			out = append(out, []byte(name)...)
			out = append(out, rec[nameStart+nameLen:]...)

			binary.BigEndian.PutUint16(out[data:], uint16(listLen+delta))
			binary.BigEndian.PutUint16(out[data+3:], uint16(nameLen+delta))
			binary.BigEndian.PutUint16(out[off+2:], uint16(l+delta))
			addExtLen(out, info.extLenPos, delta)
			addHandshakeLen(out, delta)
			addRecordLen(out, delta)
			return out, nil
		}
		off = data + l
	}
	return nil, errors.New("SNI не найден")
}

// splitRecord делит полезную нагрузку рекорда на parts TLS-рекордов с
// корректными заголовками. Клиент и сервер такой поток принимают: TLS
// разрешает произвольное дробление рукопожатия.
func splitRecord(rec []byte, parts int) ([][]byte, error) {
	if parts < 2 || len(rec) < 5+parts {
		return [][]byte{rec}, nil
	}
	payload := rec[5:]
	size := (len(payload) + parts - 1) / parts
	out := make([][]byte, 0, parts)
	for i := 0; i < len(payload); i += size {
		end := i + size
		if end > len(payload) {
			end = len(payload)
		}
		chunk := make([]byte, 5+end-i)
		copy(chunk, rec[:5])
		binary.BigEndian.PutUint16(chunk[3:5], uint16(end-i))
		copy(chunk[5:], payload[i:end])
		out = append(out, chunk)
	}
	return out, nil
}

func splitBytes(data []byte, parts int) [][]byte {
	if parts < 2 || len(data) < parts {
		return [][]byte{data}
	}
	size := (len(data) + parts - 1) / parts
	out := make([][]byte, 0, parts)
	for i := 0; i < len(data); i += size {
		end := i + size
		if end > len(data) {
			end = len(data)
		}
		out = append(out, data[i:end])
	}
	return out
}

func sendDecoy(target string, hello []byte, cfg config) {
	fake, err := replaceSNI(hello, cfg.decoySNI)
	if err != nil {
		logf(cfg, "decoy пропущен: %v", err)
		return
	}
	conn, err := dialTarget("tcp", target)
	if err != nil {
		logf(cfg, "decoy dial: %v", err)
		return
	}
	defer conn.Close()
	_ = conn.SetWriteDeadline(time.Now().Add(time.Second))
	if _, err := conn.Write(fake); err != nil {
		logf(cfg, "decoy write: %v", err)
		return
	}
	logf(cfg, "decoy отправлен %s -> %s", target, cfg.decoySNI)
	if cfg.decoyWait > 0 {
		time.Sleep(cfg.decoyWait)
	}
}

func addExtLen(rec []byte, pos int, delta int) {
	cur := int(binary.BigEndian.Uint16(rec[pos:]))
	binary.BigEndian.PutUint16(rec[pos:], uint16(cur+delta))
}

func addHandshakeLen(rec []byte, delta int) {
	cur := int(rec[6])<<16 | int(rec[7])<<8 | int(rec[8])
	cur += delta
	rec[6] = byte(cur >> 16)
	rec[7] = byte(cur >> 8)
	rec[8] = byte(cur)
}

func addRecordLen(rec []byte, delta int) {
	cur := int(binary.BigEndian.Uint16(rec[3:5]))
	binary.BigEndian.PutUint16(rec[3:5], uint16(cur+delta))
}

func describe(rec []byte) string {
	if len(rec) < 9 {
		return "короткий рекорд"
	}
	return fmt.Sprintf("record=%d handshake=%d", len(rec)-5, int(rec[6])<<16|int(rec[7])<<8|int(rec[8]))
}
