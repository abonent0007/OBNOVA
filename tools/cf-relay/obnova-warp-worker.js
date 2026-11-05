/**
 * OBNOVA WARP relay — Cloudflare Worker.
 *
 * Deploy as `obnova-warp` (dashboard → Workers & Pages → Create → Worker).
 *
 * Routes:
 *   /health  — liveness JSON;
 *   /reg     — proxy to Cloudflare WARP registration (bypasses RU SNI block);
 *   /        — ready WARP config from the public generator, with mirrors.
 *
 * The generator's first endpoints rate-limit by egress IP (HTTP 429 / 1015),
 * so the worker walks all mirrors and caches the first good answer per isolate.
 */

const MIRRORS = [
  "https://warp-vercel-chi.warp-api.cfd",
  "https://warp-vercel-murex.warp-api.cfd",
  "https://warp-netlify.warp-api.cfd",
  "https://warp-workers.warp-api.cfd",
  "https://warp-sub.warp-api.cfd",
];

let cachedBody = "";
let cachedAt = 0;

export default {
  async fetch(request) {
    const url = new URL(request.url);

    if (url.pathname === "/health") {
      return Response.json({ ok: true, service: "obnova-warp" });
    }

    if (url.pathname === "/reg") {
      const body = request.method === "GET" ? undefined : await request.text();
      const upstream = await fetch("https://api.cloudflareclient.com/v0a4471/reg", {
        method: request.method,
        headers: {
          "content-type": "application/json",
          "user-agent": "okhttp/3.12.1",
        },
        body,
      });
      return new Response(upstream.body, {
        status: upstream.status,
        headers: { "content-type": "application/json", "access-control-allow-origin": "*" },
      });
    }

    // One minute of cache per isolate: the generator is rate-limited per IP.
    if (cachedBody && Date.now() - cachedAt < 60_000) {
      return json(cachedBody, 200);
    }

    let lastError = "no mirror answered";
    for (const mirror of MIRRORS) {
      try {
        const upstream = await fetch(mirror, { headers: { "X-Client": "WARP" } });
        const text = await upstream.text();
        if (upstream.ok && text.includes('"privKey"')) {
          cachedBody = text;
          cachedAt = Date.now();
          return json(text, 200);
        }
        lastError = `${mirror}: HTTP ${upstream.status}`;
      } catch (error) {
        lastError = `${mirror}: ${error.message}`;
      }
    }
    return json(JSON.stringify({ error: lastError }), 502);
  },
};

function json(body, status) {
  return new Response(body, {
    status,
    headers: { "content-type": "application/json", "access-control-allow-origin": "*" },
  });
}
