#!/usr/bin/env node
// Dev server for PortalSnap.
//
// Serves ./public and accepts POST /report, so the Portal — which has no
// devtools and no way to read a JSON blob off its screen — can ship its
// capability report back to this machine.
//
// It also owns the photo album. The Portal browser refuses file downloads
// outright ("File Downloads are Unavailable on the Portal") and no web API
// can reach the device's own gallery, so captures are POSTed here instead
// and served back from /media — see the album section below.

const http = require("http");
const fs = require("fs");
const path = require("path");

const PORT = Number(process.env.PORT || 8080);
const PUBLIC = path.join(__dirname, "public");
const REPORTS = path.join(__dirname, "reports");
const MEDIA = path.join(__dirname, "media");

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
  ".webm": "video/webm",
  ".mp4": "video/mp4",
  ".task": "application/octet-stream",  // MediaPipe model bundles
  ".bin": "application/octet-stream"
};

// Uploads are accepted by extension, never by the client's Content-Type.
const UPLOAD_EXT = { jpg: "pic", png: "pic", webm: "vid", mp4: "vid" };

// Every stored name is minted by mintName below; anything that doesn't match
// is not ours and is not served or deleted.
const MEDIA_NAME = /^(pic|vid)-[0-9TZ-]+-[a-z0-9]{4}\.(jpg|png|webm|mp4)$/;

// A clip's preview frame is stored beside it under the clip's own name with a
// .jpg extension — `vid-…-a1b2.mp4` is previewed by `vid-…-a1b2.jpg`. Deriving
// it means there is no second namespace to keep in step, and the `vid-` prefix
// already distinguishes a preview from a photo, which is always `pic-`.
const posterFor = name => name.replace(/\.(webm|mp4)$/, ".jpg");
const isPoster = name => /^vid-/.test(name) && name.endsWith(".jpg");

// A 30s clip at the app's 2.5Mbps cap is ~10MB. This is a runaway guard, and
// it sits under the 100MB body limit on a Cloudflare quick tunnel.
const MAX_UPLOAD = 64 * 1024 * 1024;

fs.mkdirSync(REPORTS, { recursive: true });
fs.mkdirSync(MEDIA, { recursive: true });

const server = http.createServer((req, res) => {
  const url = new URL(req.url, "http://" + (req.headers.host || "localhost"));

  if (req.method === "POST" && url.pathname === "/report") {
    return receiveReport(req, res);
  }
  if (req.method === "POST" && url.pathname === "/media") {
    return receiveMedia(req, res, url);
  }
  if (req.method === "GET" && url.pathname === "/media/list") {
    return listMedia(res);
  }
  if (req.method === "DELETE" && url.pathname.startsWith("/media/")) {
    return deleteMedia(res, url);
  }
  if (req.method !== "GET" && req.method !== "HEAD") {
    return send(res, 405, "text/plain", "Method Not Allowed");
  }

  // Two roots: the app under PUBLIC, saved captures under MEDIA. Resolve
  // inside whichever applies and reject any path that escapes it.
  const rel = decodeURIComponent(url.pathname);
  const inMedia = rel.startsWith("/media/");
  const root = inMedia ? MEDIA : PUBLIC;
  // The root is the app. It used to be the capability probe, which was right
  // for the first week and confusing ever since — the Portal's home screen link
  // should open the camera, not a diagnostics page. The probe is still there at
  // /probe.html, and /index.html lands on the app so an old bookmark still works.
  const HOME = "/app.html";
  let file = inMedia
    ? path.join(MEDIA, path.basename(rel))
    : path.join(PUBLIC, rel === "/" || rel === "/index.html" ? HOME : rel);
  if (!file.startsWith(root + path.sep) && file !== root) {
    return send(res, 403, "text/plain", "Forbidden");
  }
  if (inMedia && !MEDIA_NAME.test(path.basename(file))) {
    return send(res, 404, "text/plain", "Not found");
  }

  fs.stat(file, (err, st) => {
    if (!err && st.isDirectory()) { file = path.join(PUBLIC, HOME); st = null; }

    // Vendored runtime and models are content-stable and ~12MB combined —
    // without a long cache the Portal re-downloads them on every launch.
    // Saved captures are immutable too: every name is minted once.
    // App code stays uncached so edits land on reload.
    const immutable = inMedia || rel.startsWith("/vendor/") || rel.startsWith("/models/");
    const head = {
      "Content-Type": MIME[path.extname(file).toLowerCase()] || "application/octet-stream",
      "Cache-Control": immutable ? "public, max-age=31536000, immutable" : "no-store",
      // Needed later if we run WASM face tracking with threads.
      "Cross-Origin-Opener-Policy": "same-origin",
      "Cross-Origin-Embedder-Policy": "require-corp"
    };

    // Video needs byte ranges to be scrubbable, and streaming keeps a 10MB
    // clip out of the server's heap. Everything else is small — read it whole.
    if (inMedia) return sendRange(req, res, file, st, head);

    fs.readFile(file, (err2, body) => {
      if (err2) return send(res, 404, "text/plain", "Not found: " + rel);
      res.writeHead(200, head);
      res.end(req.method === "HEAD" ? undefined : body);
    });
  });
});

/* ----------------------------- Photo album ----------------------------- */

// Streams `file`, honouring a single-range `Range: bytes=…` request so the
// review player and the gallery can seek without refetching the whole clip.
function sendRange(req, res, file, st, head) {
  if (!st || !st.isFile()) return send(res, 404, "text/plain", "Not found");

  const total = st.size;
  let start = 0, end = total - 1, code = 200;

  const m = /^bytes=(\d*)-(\d*)$/.exec(req.headers.range || "");
  if (m && (m[1] || m[2])) {
    if (m[1]) {
      start = Number(m[1]);
      if (m[2]) end = Math.min(Number(m[2]), end);
    } else {
      start = Math.max(0, total - Number(m[2]));   // suffix range: last N bytes
    }
    if (!(start <= end) || start >= total) {
      res.writeHead(416, { "Content-Range": "bytes */" + total });
      return res.end();
    }
    code = 206;
    head["Content-Range"] = "bytes " + start + "-" + end + "/" + total;
  }

  res.writeHead(code, Object.assign({
    "Accept-Ranges": "bytes",
    "Content-Length": end - start + 1
  }, head));
  if (req.method === "HEAD") return res.end();

  const rs = fs.createReadStream(file, { start, end });
  rs.on("error", () => res.destroy());
  res.on("close", () => rs.destroy());   // client navigated away mid-stream
  rs.pipe(res);
}

// Names are minted here, never taken from the client: kind, UTC stamp, and
// four random chars so two captures in the same second can't collide.
function mintName(ext) {
  const stamp = new Date().toISOString().replace(/[:.]/g, "-").replace(/-\d{3}Z$/, "Z");
  const salt = Math.random().toString(36).slice(2, 6).padEnd(4, "0");
  return UPLOAD_EXT[ext] + "-" + stamp + "-" + salt + "." + ext;
}

// Captures arrive as a raw body — the Portal can't download them, so this is
// the only way a photo leaves the device.
function receiveMedia(req, res, url) {
  const ext = String(url.searchParams.get("ext") || "").toLowerCase();
  if (!UPLOAD_EXT[ext]) {
    return send(res, 400, "application/json", JSON.stringify({ error: "unsupported type" }));
  }

  // `?for=<clip>` stores this upload as that clip's preview frame rather than as
  // an album entry of its own. The name is derived from the clip, never taken
  // from the client, so a poster cannot land anywhere unexpected.
  const forClip = url.searchParams.get("for");
  if (forClip !== null) {
    if (ext !== "jpg" || !MEDIA_NAME.test(forClip) || !/^vid-/.test(forClip)) {
      return send(res, 400, "application/json", JSON.stringify({ error: "bad poster target" }));
    }
  }

  // Cheap next to an upload, and it keeps a wiped or freshly remounted volume
  // from silently turning every save into an error.
  try { fs.mkdirSync(MEDIA, { recursive: true }); } catch (e) {}

  const name = forClip ? posterFor(forClip) : mintName(ext);
  const dest = path.join(MEDIA, name);
  // Write to .part and rename: a listing must never show a half-uploaded file.
  const tmp = dest + ".part";
  const out = fs.createWriteStream(tmp);
  let size = 0, failed = false;

  const abort = (code, msg) => {
    if (failed) return;
    failed = true;
    out.destroy();
    fs.unlink(tmp, () => {});
    if (!res.headersSent) send(res, code, "application/json", JSON.stringify({ error: msg }));
    req.destroy();
  };

  req.on("data", chunk => {
    size += chunk.length;
    if (size > MAX_UPLOAD) abort(413, "too big");
  });
  req.on("error", () => abort(400, "upload interrupted"));
  req.on("aborted", () => abort(400, "upload aborted"));
  out.on("error", err => abort(500, err.message));

  req.pipe(out);

  out.on("finish", () => {
    if (failed) return;
    if (size === 0) return abort(400, "empty upload");
    fs.rename(tmp, dest, err => {
      if (err) return abort(500, err.message);
      console.log("saved media/" + name + "  " + (size / 1048576).toFixed(1) + "MB");
      send(res, 200, "application/json",
        JSON.stringify({ ok: true, name, url: "/media/" + name, size }));
    });
  });
}

function listMedia(res) {
  fs.readdir(MEDIA, (err, names) => {
    // A missing album is an empty album, not a server error.
    if (err) return send(res, 200, "application/json", JSON.stringify({ items: [] }));
    const kept = names.filter(n => MEDIA_NAME.test(n));
    const posters = new Set(kept.filter(isPoster));
    const items = [];
    for (const n of kept) {
      if (posters.has(n)) continue;          // a preview is part of its clip, not an entry
      try {
        const st = fs.statSync(path.join(MEDIA, n));
        const video = n.startsWith("vid-");
        const item = {
          name: n,
          url: "/media/" + n,
          kind: video ? "video" : "photo",
          size: st.size,
          at: st.mtime.toISOString()
        };
        if (video && posters.has(posterFor(n))) item.poster = "/media/" + posterFor(n);
        items.push(item);
      } catch (e) { /* deleted between readdir and stat */ }
    }
    items.sort((a, b) => (a.at < b.at ? 1 : -1));   // newest first
    send(res, 200, "application/json", JSON.stringify({ items }));
  });
}

function deleteMedia(res, url) {
  const name = path.basename(decodeURIComponent(url.pathname));
  if (!MEDIA_NAME.test(name)) {
    return send(res, 400, "application/json", JSON.stringify({ error: "bad name" }));
  }
  fs.unlink(path.join(MEDIA, name), err => {
    if (err) return send(res, 404, "application/json", JSON.stringify({ error: "not found" }));
    // The preview goes with the clip, or the album accumulates orphans nobody
    // can see or remove.
    if (/^vid-/.test(name)) fs.unlink(path.join(MEDIA, posterFor(name)), () => {});
    console.log("deleted media/" + name);
    send(res, 200, "application/json", JSON.stringify({ ok: true }));
  });
}

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
