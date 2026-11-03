// OBNOVA relay Worker: TCP-over-WebSocket для Telegram.
//
// Клиент (nova-router) открывает WSS на /tcp?host=...&port=...; Worker
// поднимает обычный TCP к дата-центру через Cloudflare и перекладывает байты
// в обе стороны. Для оператора это HTTPS к Cloudflare, а не соединение с
// заблокированным 149.154.x.
//
// Разрешены только сети Telegram: реле не должно стать открытым прокси.
// Секрет обязателен и задаётся секретом Worker'а RELAY_SECRET.

import { connect } from "cloudflare:sockets";

function notFound() {
  return new Response("not found", { status: 404 });
}

function telegramHost(host) {
  // 149.154.160.0/20
  if (/^149\.154\.(16\d|17[0-5])\.\d{1,3}$/.test(host)) return true;
  // 91.108.0.0/16
  if (/^91\.108\.\d{1,3}\.\d{1,3}$/.test(host)) return true;
  // 2001:67c:4e8::/48
  if (/^2001:67c:4e8:/i.test(host)) return true;
  return false;
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const host = (url.searchParams.get("host") || "").trim();
    const port = Number(url.searchParams.get("port") || "443");
    const secret = env.RELAY_SECRET || "";
    if (!secret || request.headers.get("X-Relay-Secret") !== secret) return notFound();

    if (url.pathname !== "/tcp") return notFound();
    if ((request.headers.get("Upgrade") || "").toLowerCase() !== "websocket") {
      return notFound();
    }

    if (!telegramHost(host) || !Number.isInteger(port) || port < 1 || port > 65535) {
      return notFound();
    }

    let socket;
    try {
      socket = connect({ hostname: host, port });
      await socket.opened;
    } catch (e) {
      console.log("connect-failed", host, port, e && e.message);
      return notFound();
    }
    console.log("connected", host, port);

    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    server.accept();

    const writer = socket.writable.getWriter();
    server.addEventListener("message", async (event) => {
      const data = event.data;
      let payload;
      if (typeof data === "string") {
        payload = new TextEncoder().encode(data);
      } else if (data instanceof ArrayBuffer) {
        payload = new Uint8Array(data);
      } else if (ArrayBuffer.isView(data)) {
        payload = data;
      } else if (data && typeof data.arrayBuffer === "function") {
        // Cloudflare отдаёт бинарный кадр как Blob, а не ArrayBuffer.
        payload = new Uint8Array(await data.arrayBuffer());
      } else {
        console.log("unsupported-frame", typeof data);
        return;
      }
      writer.write(payload).catch((e) => console.log("write-failed", e && e.message));
    });
    server.addEventListener("close", () => {
      console.log("ws-close");
      writer.close().catch(() => {});
      socket.close().catch(() => {});
    });
    server.addEventListener("error", () => {
      console.log("ws-error");
      writer.close().catch(() => {});
      socket.close().catch(() => {});
    });

    (async () => {
      try {
        const reader = socket.readable.getReader();
        while (true) {
          const { done, value } = await reader.read();
          if (done) {
            console.log("tcp-eof");
            break;
          }
          server.send(value);
        }
      } catch (e) {
        console.log("read-failed", e && e.message);
      }
      try {
        server.close(1000, "closed");
      } catch {
        // уже закрыт
      }
    })();

    return new Response(null, { status: 101, webSocket: client });
  },
};
