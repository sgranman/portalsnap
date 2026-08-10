#!/usr/bin/env node
// Dev server for PortalSnap.
//
// Serves ./public and accepts POST /report, so the Portal — which has no
// devtools and no way to read a JSON blob off its screen — can ship its
// capability report back to this machine.

const http = require("http");
const fs = require("fs");
const path = require("path");

const PORT = Number(process.env.PORT || 8080);
const PUBLIC = path.join(__dirname, "public");
const REPORTS = path.join(__dirname, "reports");

const MIME = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".mjs": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".wasm": "application/wasm",
  ".png": "image/png",
  ".jpg": "image/jpeg",
  ".svg": "image/svg+xml",
  ".webp": "image/webp",
  ".task": "application/octet-stream",  // MediaPipe model bundles
  ".bin": "application/octet-stream"
};

fs.mkdirSync(REPORTS, { recursive: true });

const server = http.createServer((req, res) => {
  const url = new URL(req.url, "http://" + (req.headers.host || "localhost"));

  if (req.method === "POST" && url.pathname === "/report") {
    return receiveReport(req, res);
  }
  if (req.method !== "GET" && req.method !== "HEAD") {
    return send(res, 405, "text/plain", "Method Not Allowed");
  }

  // Resolve inside PUBLIC only — reject any path that escapes it.
  const rel = decodeURIComponent(url.pathname);
  let file = path.join(PUBLIC, rel === "/" ? "index.html" : rel);
  if (!file.startsWith(PUBLIC + path.sep) && file !== PUBLIC) {
    return send(res, 403, "text/plain", "Forbidden");
  }

  fs.stat(file, (err, st) => {
    if (!err && st.isDirectory()) file = path.join(file, "index.html");
    fs.readFile(file, (err2, body) => {
      if (err2) return send(res, 404, "text/plain", "Not found: " + rel);
      res.writeHead(200, {
        "Content-Type": MIME[path.extname(file).toLowerCase()] || "application/octet-stream",
        "Cache-Control": "no-store",
        // Needed later if we run WASM face tracking with threads.
        "Cross-Origin-Opener-Policy": "same-origin",
        "Cross-Origin-Embedder-Policy": "require-corp"
      });
      res.end(req.method === "HEAD" ? undefined : body);
    });
  });
});

function receiveReport(req, res) {
  let body = "";
  req.on("data", chunk => {
    body += chunk;
    if (body.length > 1e6) req.destroy();  // don't buffer unbounded input
  });
  req.on("end", () => {
    const stamp = new Date().toISOString().replace(/[:.]/g, "-");
    const name = "report-" + stamp + ".json";
    fs.writeFile(path.join(REPORTS, name), body, err => {
      if (err) return send(res, 500, "application/json", JSON.stringify({ error: err.message }));
      console.log("\n=== capability report received: reports/" + name + " ===");
      try {
        const r = JSON.parse(body);
        console.log("  UA:        " + (r.env && r.env.userAgent));
        console.log("  Chromium:  " + (r.env && r.env.chromiumMajor));
        console.log("  Secure:    " + (r.env && r.env.secureContext));
        console.log("  Camera:    " + (r.media && (r.media.resolution || r.media.error || "not tested")));
        console.log("  WASM SIMD: " + (r.features && r.features.wasmSIMD));
        console.log("  WebGL2:    " + (r.features && r.features.webgl2));
      } catch (e) { /* log the raw file path and move on */ }
      send(res, 200, "application/json", JSON.stringify({ ok: true, file: name }));
    });
  });
}

function send(res, code, type, body) {
  res.writeHead(code, { "Content-Type": type, "Cache-Control": "no-store" });
  res.end(body);
}

server.listen(PORT, "0.0.0.0", () => {
  console.log("PortalSnap dev server on http://0.0.0.0:" + PORT);
  for (const [name, addrs] of Object.entries(require("os").networkInterfaces())) {
    for (const a of addrs || []) {
      if (a.family === "IPv4" && !a.internal) console.log("  LAN: http://" + a.address + ":" + PORT + "  (" + name + ")");
    }
  }
  console.log("\nNOTE: the Portal needs HTTPS for camera access — see README.");
});
