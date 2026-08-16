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
const auth = require("./auth.js");
const qrcode = require("./vendor/qrcode.js");

const PORT = Number(process.env.PORT || 8080);
const PUBLIC = path.join(__dirname, "public");
const REPORTS = path.join(__dirname, "reports");
// Both roots are overridable, which is what lets the test suite run against a
// scratch directory instead of the album someone's children are actually in —
// `e2e.mjs` starts by emptying it.
const MEDIA = process.env.PORTALSNAP_MEDIA || path.join(__dirname, "media");
const DATA = process.env.PORTALSNAP_DATA || path.join(__dirname, "data");

// Where this server is reachable from a phone. Only used to print a claim link
// worth tapping; everything else derives its URLs from the request's own Host.
const PUBLIC_URL = (process.env.PUBLIC_URL || "").replace(/\/+$/, "");

// Share links are the one route to a photo that needs no paired device. They
// can be switched off wholesale by a host who wants no such route to exist.
const SHARE_LINKS = process.env.SHARE_LINKS !== "off";
const SHARE_MAX_DAYS = Number(process.env.SHARE_MAX_DAYS || 30);
const SHARE_DEFAULT_DAYS = 7;

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
auth.init({ dataDir: DATA, claim: process.env.PORTALSNAP_CLAIM });

// No single request may take the server down. `decodeURIComponent` throws a
// URIError on a malformed escape — `/s/%` is enough — and an exception thrown
// out of this callback ends the process, which on an unauthenticated route is
// a one-byte denial of service against a machine in someone's living room.
const server = http.createServer((req, res) => {
  try {
    handle(req, res);
  } catch (err) {
    console.error("request failed: " + (err && err.message));
    if (!res.headersSent) send(res, 400, "text/plain", "Bad Request");
    else res.destroy();
  }
});

// Percent-decoding that answers "no" instead of throwing.
function safeDecode(s) {
  try { return decodeURIComponent(s); } catch (e) { return null; }
}

function handle(req, res) {
  const url = new URL(req.url, "http://" + (req.headers.host || "localhost"));

  // The pairing flow and share links are the whole unauthenticated surface.
  if (openRoute(req, res, url)) return;

  // Everything else — the app, the album, the diagnostics pages, the vendored
  // runtime and the models — needs a paired device. Checked here, before any
  // route runs, so there is exactly one place to get this wrong.
  const device = auth.deviceFor(req);
  if (!device) return refuse(req, res);
  // Once a day this restarts the cookie's year. Set here rather than in each
  // route's header block: `writeHead` merges with what is already set, so it
  // survives whichever of them ends up answering.
  if (auth.touch(device)) {
    const fresh = auth.refreshCookie(req);
    if (fresh) res.setHeader("Set-Cookie", fresh);
  }

  if (url.pathname.startsWith("/auth/") || url.pathname === "/share") {
    return authRoute(req, res, url, device);
  }
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
  const rel = safeDecode(url.pathname);
  if (rel === null) return send(res, 400, "text/plain", "Bad Request");
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
}

/* ------------------------------- Access -------------------------------- */

// How an unauthenticated request is turned away, and the distinction matters
// more than it looks. A browser *navigating* somewhere gets sent to the pairing
// page. Anything else — a fetch, an <img>, a worker pulling in the tracker —
// gets a 401 with a JSON body. Redirecting those to an HTML page instead would
// hand `JSON.parse` a login form and surface as "couldn't reach the server",
// and would paint the album as broken images with no clue why.
function refuse(req, res) {
  const mode = req.headers["sec-fetch-mode"];
  const navigating = mode
    ? mode === "navigate"
    : /text\/html/.test(String(req.headers.accept || ""));
  if (navigating && (req.method === "GET" || req.method === "HEAD")) {
    res.writeHead(302, { Location: "/pair", "Cache-Control": "no-store" });
    return res.end();
  }
  send(res, 401, "application/json", JSON.stringify({ error: "unpaired" }));
}

// Reads a JSON body, capped. Returns null to the callback on anything it can't
// parse, and the caller answers 400 — no route should see a half-body.
function readJson(req, done) {
  let body = "";
  req.on("data", chunk => {
    body += chunk;
    if (body.length > 8192) { req.destroy(); done(null); }
  });
  req.on("end", () => {
    try { done(JSON.parse(body || "{}")); } catch (e) { done(null); }
  });
  req.on("error", () => done(null));
}

const json = (res, code, obj) => send(res, code, "application/json", JSON.stringify(obj));

// The pairing page's own origin, as the phone will need to reach it. Taken from
// the request rather than configuration, so a quick tunnel, a LAN address and a
// stable hostname all mint links that work.
function originOf(req) {
  const proto = auth.isSecure(req) ? "https" : "http";
  return proto + "://" + (req.headers.host || "localhost");
}

// Everything reachable without a paired device. Returns true if it handled the
// request. Kept deliberately short: every line here is public internet.
function openRoute(req, res, url) {
  const p = url.pathname;

  if ((req.method === "GET" || req.method === "HEAD") && (p === "/pair" || p === "/pair.html")) {
    fs.readFile(path.join(PUBLIC, "pair.html"), (err, body) => {
      if (err) return send(res, 500, "text/plain", "pairing page missing");
      res.writeHead(200, { "Content-Type": MIME[".html"], "Cache-Control": "no-store" });
      res.end(req.method === "HEAD" ? undefined : body);
    });
    return true;
  }

  // Renders whatever text the pairing page asks for. That page only ever asks
  // for its own pairing URL, and a QR of an attacker's own string tells them
  // nothing they didn't type, so this needs no session — only a length cap.
  if (req.method === "GET" && p === "/auth/qr.svg") {
    const text = String(url.searchParams.get("t") || "");
    if (!text || text.length > 512) {
      json(res, 400, { error: "bad qr text" });
      return true;
    }
    try {
      const q = qrcode(0, "M");
      q.addData(text);
      q.make();
      res.writeHead(200, { "Content-Type": MIME[".svg"], "Cache-Control": "no-store" });
      res.end(q.createSvgTag({ cellSize: 8, margin: 16, scalable: true }));
    } catch (e) {
      json(res, 400, { error: "cannot encode" });
    }
    return true;
  }

  if (req.method === "POST" && p === "/auth/claim") {
    readJson(req, b => {
      if (!b) return json(res, 400, { error: "bad request" });
      const got = auth.claim(b.secret, b.name, req.headers["user-agent"]);
      if (!got) return json(res, 403, { error: "that claim link is no longer valid" });
      const head = {};
      auth.setSession(req, head, got.device, got.token);
      res.writeHead(200, Object.assign({
        "Content-Type": "application/json", "Cache-Control": "no-store"
      }, head));
      res.end(JSON.stringify({ ok: true, device: got.device.name }));
      console.log("paired: " + got.device.name + " (claimed)");
    });
    return true;
  }

  // A device being invited by an already-paired one has no session yet, so
  // redemption has to live out here. The invite secret is the credential.
  if (req.method === "POST" && p === "/auth/invite/redeem") {
    readJson(req, b => {
      if (!b) return json(res, 400, { error: "bad request" });
      const got = auth.redeemInvite(b.id, b.secret, b.name, req.headers["user-agent"]);
      if (!got) return json(res, 403, { error: "that invite has expired" });
      const head = {};
      auth.setSession(req, head, got.device, got.token);
      res.writeHead(200, Object.assign({
        "Content-Type": "application/json", "Cache-Control": "no-store"
      }, head));
      res.end(JSON.stringify({ ok: true, device: got.device.name }));
      console.log("paired: " + got.device.name + " (invited)");
    });
    return true;
  }

  // The Portal asking for a QR to display, and then asking whether anyone has
  // approved it yet. Both are pre-session by definition.
  if (req.method === "POST" && p === "/auth/pair/start") {
    const started = auth.startPairing(req.headers["user-agent"]);
    json(res, 200, {
      id: started.id,
      deviceSecret: started.deviceSecret,
      // What the QR encodes and what gets typed. The secret rides in the
      // fragment so it never reaches a server log or a Referer header.
      approveUrl: originOf(req) + "/pair#approve=" + started.id + "." + started.approvalSecret,
      code: started.code,
      expires: started.expires
    });
    return true;
  }

  if (req.method === "POST" && p === "/auth/pair/poll") {
    readJson(req, b => {
      if (!b) return json(res, 400, { error: "bad request" });
      const got = auth.collect(b.id, b.deviceSecret);
      if (got.gone) return json(res, 404, { error: "expired" });
      if (got.pending) return json(res, 200, { pending: true });
      const head = {};
      auth.setSession(req, head, got.device, got.token);
      res.writeHead(200, Object.assign({
        "Content-Type": "application/json", "Cache-Control": "no-store"
      }, head));
      res.end(JSON.stringify({ ok: true, device: got.device.name }));
      console.log("paired: " + got.device.name + " (approved from another device)");
    });
    return true;
  }

  if (SHARE_LINKS && (req.method === "GET" || req.method === "HEAD") && p.startsWith("/s/")) {
    serveShare(req, res, safeDecode(p.slice(3)));
    return true;
  }

  return false;
}

// Authenticated auth: managing devices, approving a pairing, minting links.
function authRoute(req, res, url, device) {
  const p = url.pathname;

  if (req.method === "GET" && p === "/auth/whoami") {
    return json(res, 200, { device: { id: device.id, name: device.name } });
  }

  if (req.method === "POST" && p === "/auth/logout") {
    const head = {};
    auth.clearSession(req, head);
    res.writeHead(200, Object.assign({
      "Content-Type": "application/json", "Cache-Control": "no-store"
    }, head));
    return res.end(JSON.stringify({ ok: true }));
  }

  if (req.method === "GET" && p === "/auth/devices") {
    return json(res, 200, { devices: auth.listDevices(device) });
  }

  if (req.method === "POST" && p === "/auth/devices/rename") {
    return readJson(req, b => {
      if (!b || !b.id) return json(res, 400, { error: "bad request" });
      if (!auth.renameDevice(b.id, b.name)) return json(res, 404, { error: "no such device" });
      json(res, 200, { ok: true });
    });
  }

  if (req.method === "POST" && p === "/auth/devices/revoke") {
    return readJson(req, b => {
      if (!b || !b.id) return json(res, 400, { error: "bad request" });
      if (!auth.revokeDevice(b.id)) return json(res, 404, { error: "no such device" });
      console.log("revoked device " + b.id);
      // Revoking the last one empties the house. Print the way back in.
      if (!auth.paired()) claimBanner();
      // Revoking yourself is allowed — it is how you sign a borrowed phone out.
      const head = {};
      if (b.id === device.id) auth.clearSession(req, head);
      res.writeHead(200, Object.assign({
        "Content-Type": "application/json", "Cache-Control": "no-store"
      }, head));
      res.end(JSON.stringify({ ok: true, self: b.id === device.id }));
    });
  }

  // Mints the QR that enrols a brand-new device.
  if (req.method === "POST" && p === "/auth/invite") {
    const inv = auth.startInvite();
    return json(res, 200, {
      url: originOf(req) + "/pair#invite=" + inv.id + "." + inv.secret,
      expires: inv.expires
    });
  }

  // Approving a Portal that is sitting there showing a QR: either by having
  // scanned it (the fragment carried the approval secret) or by typing the code.
  if (req.method === "POST" && p === "/auth/pair/approve") {
    return readJson(req, b => {
      if (!b) return json(res, 400, { error: "bad request" });
      let pairing = null;
      if (b.id && b.secret) {
        pairing = auth.pairingByApproval(b.id, b.secret);
        if (!pairing) return json(res, 404, { error: "that pairing has expired" });
      } else if (b.code) {
        const found = auth.pairingByCode(b.code);
        if (found.error) return json(res, 403, { error: found.error });
        pairing = found.pairing;
      } else {
        return json(res, 400, { error: "bad request" });
      }
      auth.approve(pairing, b.name, device);
      console.log("approved a pairing for \"" + (b.name || "New device") + "\"");
      json(res, 200, { ok: true, ua: pairing.ua });
    });
  }

  // What the Portal is waiting to be told about, so the phone can show it.
  if (req.method === "POST" && p === "/auth/pair/describe") {
    return readJson(req, b => {
      if (!b) return json(res, 400, { error: "bad request" });
      const pairing = b.id && b.secret ? auth.pairingByApproval(b.id, b.secret) : null;
      if (!pairing) return json(res, 404, { error: "that pairing has expired" });
      json(res, 200, { ua: pairing.ua, expires: pairing.expires });
    });
  }

  if (p === "/share") {
    if (!SHARE_LINKS) return json(res, 403, { error: "share links are switched off here" });

    if (req.method === "GET") return json(res, 200, { shares: auth.listShares() });

    if (req.method === "POST") {
      return readJson(req, b => {
        if (!b || !MEDIA_NAME.test(String(b.name || ""))) {
          return json(res, 400, { error: "bad name" });
        }
        if (!fs.existsSync(path.join(MEDIA, b.name))) {
          return json(res, 404, { error: "no such capture" });
        }
        const days = Math.min(Number(b.days) || SHARE_DEFAULT_DAYS, SHARE_MAX_DAYS);
        const made = auth.mintShare(b.name, days, device);
        json(res, 200, {
          url: originOf(req) + "/s/" + made.token,
          expires: made.expires
        });
      });
    }

    if (req.method === "DELETE") {
      const id = url.searchParams.get("id");
      if (!auth.revokeShare(id)) return json(res, 404, { error: "no such link" });
      return json(res, 200, { ok: true });
    }
  }

  return json(res, 404, { error: "no such endpoint" });
}

// A share link resolves to exactly one capture and serves it through the same
// ranged reader the album uses, so a shared clip still scrubs.
function serveShare(req, res, token) {
  const name = auth.shareTarget(token);
  if (!name || !MEDIA_NAME.test(name)) {
    return send(res, 404, "text/plain", "This link has expired.");
  }
  const file = path.join(MEDIA, name);
  fs.stat(file, (err, st) => {
    if (err) return send(res, 404, "text/plain", "This link has expired.");
    sendRange(req, res, file, st, {
      "Content-Type": MIME[path.extname(name).toLowerCase()] || "application/octet-stream",
      // Private, so a shared cache between the recipient and here never holds a
      // copy that outlives the link.
      "Cache-Control": "private, max-age=3600",
      "Content-Disposition": "inline; filename=\"portalsnap-" + name + "\""
    });
  });
}

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
  const decoded = safeDecode(url.pathname);
  const name = decoded === null ? "" : path.basename(decoded);
  if (!MEDIA_NAME.test(name)) {
    return send(res, 400, "application/json", JSON.stringify({ error: "bad name" }));
  }
  fs.unlink(path.join(MEDIA, name), err => {
    if (err) return send(res, 404, "application/json", JSON.stringify({ error: "not found" }));
    // The preview goes with the clip, or the album accumulates orphans nobody
    // can see or remove.
    if (/^vid-/.test(name)) fs.unlink(path.join(MEDIA, posterFor(name)), () => {});
    // A share link that outlives its file would 404 anyway. Dropping it keeps
    // the list of what is currently reachable from outside actually true.
    auth.revokeSharesFor(name);
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

  if (!auth.paired()) claimBanner();
});

// The only time a secret is ever printed. Whoever can read the log owns the
// server, which is the intended bootstrap: on a home server that is the person
// who started it. It stops working the moment a device pairs.
function claimBanner() {
  const where = PUBLIC_URL || "https://<your-hostname>";
  console.log("\n  ┌─ Nothing is paired yet ────────────────────────────────");
  console.log("  │  Open this on your phone to claim this server:");
  console.log("  │  " + where + "/pair#claim=" + auth.claimSecret);
  if (!PUBLIC_URL) console.log("  │  (set PUBLIC_URL to have this printed ready to tap)");
  console.log("  └────────────────────────────────────────────────────────\n");
}
