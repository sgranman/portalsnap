// Authorization for PortalSnap: trusted devices, no accounts.
//
// The device that has to stay logged in is a Portal — no usable keyboard, no
// downloads, and a browser nobody should be typing a password into. So there
// are no passwords anywhere. Instead every device holds a random 256-bit token
// in a cookie, and a device gets that token in exactly one of three ways:
//
//   claim    the one-time secret printed to the server log while no device is
//            paired yet. Bootstraps the very first phone.
//   invite   an already-paired device mints a QR; whoever scans it is enrolled.
//   pair     the OAuth device-grant shape, for a screen that can't be typed on:
//            the Portal displays a QR, a paired phone scans it and approves,
//            and the Portal collects its token by polling.
//
// Cookies rather than an Authorization header, and this is forced, not chosen:
// the face tracker loads through `importScripts` in a worker, the pitch shifter
// through `audioWorklet.addModule`, and the album through `<img src>`. None of
// those three can carry a header. All of them send a same-origin cookie.

const crypto = require("crypto");
const fs = require("fs");
const path = require("path");

const COOKIE = "psnap";
const SESSION_DAYS = 365;          // a kiosk that logs itself out is a broken kiosk
const TOUCH_MS = 24 * 3600 * 1000; // refresh lastSeen/expiry at most daily — see touch()
const PAIR_TTL_MS = 5 * 60 * 1000;
const INVITE_TTL_MS = 5 * 60 * 1000;
const CODE_ATTEMPTS = 10;          // per pairing, then it's dead
const CODE_BURST = 30;             // per minute, server-wide — see pairingByCode()
const MAX_PAIRINGS = 200;          // pending at once — see startPairing()

// Crockford base32 minus the letters that a tired parent misreads off a screen
// across the room: I/L/O against 1/0, and U so no six-letter code can offend.
const CODE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

let DIR = null;
let FILE = null;
let store = { version: 1, devices: [], shares: [] };

// Pairings and invites are deliberately memory-only. Both die in five minutes,
// and a restart cancelling one that was in flight is the correct outcome — the
// Portal simply draws a fresh QR.
const pairings = new Map();
const invites = new Map();

let claimSecret = null;
let codeHits = [];                 // timestamps, for the server-wide code burst cap

/* ------------------------------- plumbing ------------------------------- */

const rand = n => crypto.randomBytes(n).toString("base64url");
const sha = s => crypto.createHash("sha256").update(String(s)).digest("hex");

// Both arguments are hex digests of the same length, so the length check can
// never leak anything a timing-safe compare would have hidden.
function same(a, b) {
  if (typeof a !== "string" || typeof b !== "string" || a.length !== b.length) return false;
  return crypto.timingSafeEqual(Buffer.from(a), Buffer.from(b));
}

function userCode() {
  const bytes = crypto.randomBytes(6);
  let out = "";
  for (let i = 0; i < 6; i++) out += CODE_ALPHABET[bytes[i] % CODE_ALPHABET.length];
  return out;
}

// Written tmp-then-rename, the same way an upload lands in media/: a crash
// mid-write must not leave a half-parsed device list that locks everyone out.
function save() {
  const tmp = FILE + ".part";
  fs.writeFileSync(tmp, JSON.stringify(store, null, 2));
  fs.renameSync(tmp, FILE);
}

function load() {
  try {
    const parsed = JSON.parse(fs.readFileSync(FILE, "utf8"));
    if (parsed && Array.isArray(parsed.devices)) {
      store = { version: 1, devices: parsed.devices, shares: parsed.shares || [] };
    }
  } catch (e) { /* no file yet, or an unreadable one: start empty and rewrite */ }
}

// Anything that has aged out is dropped on the next touch of the store rather
// than on a timer, so an idle server does no work and a busy one stays tidy.
function sweep() {
  const now = Date.now();
  for (const [id, p] of pairings) if (now > p.expires) pairings.delete(id);
  for (const [id, i] of invites) if (now > i.expires) invites.delete(id);
  const live = store.shares.filter(s => now < s.expires);
  if (live.length !== store.shares.length) { store.shares = live; save(); }
}

/* -------------------------------- startup -------------------------------- */

function init(opts) {
  opts = opts || {};
  DIR = opts.dataDir || path.join(__dirname, "data");
  FILE = path.join(DIR, "auth.json");
  fs.mkdirSync(DIR, { recursive: true });
  load();

  // The claim secret exists only while there is nobody to let anyone else in.
  // It is regenerated on every restart until then, and discarded for good the
  // moment device #1 enrols — there is no standing credential to leak.
  //
  // PORTALSNAP_CLAIM pins it, which is how the test harness enrols without a
  // human reading the log. Pinning it in production is harmless but pointless:
  // it stops working as soon as the first device pairs.
  if (!store.devices.length) claimSecret = opts.claim || rand(32);
  return { claimSecret };
}

const paired = () => store.devices.length > 0;

/* -------------------------------- sessions ------------------------------- */

function parseCookies(header) {
  const out = {};
  for (const part of String(header || "").split(";")) {
    const eq = part.indexOf("=");
    if (eq < 0) continue;
    const raw = part.slice(eq + 1).trim();
    // A malformed escape must read as "not a session", not as a thrown
    // URIError: `Cookie: psnap=%` is otherwise a 400 on every route at once.
    let value;
    try { value = decodeURIComponent(raw); } catch (e) { value = raw; }
    out[part.slice(0, eq).trim()] = value;
  }
  return out;
}

// The device this request belongs to, or null. Never throws — a malformed
// cookie is just an unauthenticated request.
function deviceFor(req) {
  const raw = parseCookies(req.headers.cookie)[COOKIE];
  if (!raw) return null;
  const dot = raw.indexOf(".");
  if (dot < 0) return null;
  const id = raw.slice(0, dot), token = raw.slice(dot + 1);
  const device = store.devices.find(d => d.id === id);
  if (!device || !same(device.hash, sha(token))) return null;
  return device;
}

// Whether the response can carry a `Secure` cookie. Setting the flag on a plain
// HTTP response means the browser silently discards the cookie, so guessing
// wrong here is a login that never sticks. cloudflared and every sane proxy set
// X-Forwarded-Proto; PORTALSNAP_SECURE_COOKIE=1 forces it for one that doesn't.
function isSecure(req) {
  if (process.env.PORTALSNAP_SECURE_COOKIE === "1") return true;
  const fwd = String(req.headers["x-forwarded-proto"] || "").split(",")[0].trim();
  if (fwd) return fwd === "https";
  return !!req.socket.encrypted;
}

function cookieFor(req, value, days) {
  return COOKIE + "=" + encodeURIComponent(value) +
    "; Path=/; HttpOnly; SameSite=Lax; Max-Age=" + Math.round(days * 86400) +
    (isSecure(req) ? "; Secure" : "");
}

function setSession(req, headers, device, token) {
  headers["Set-Cookie"] = cookieFor(req, device.id + "." + token, SESSION_DAYS);
}

function clearSession(req, headers) {
  headers["Set-Cookie"] = COOKIE + "=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0" +
    (isSecure(req) ? "; Secure" : "");
}

// Records that a device is alive, at most once a day, and says so — the caller
// takes that as its cue to re-issue the cookie, which is what makes the year
// sliding rather than fixed. A device in daily use never has to pair again.
//
// Once a day rather than every request because opening a forty-tile album is
// forty authenticated requests, and forty disk writes and forty Set-Cookie
// headers for one glance at the photos would be absurd.
function touch(device) {
  const now = Date.now();
  if (now - (device.lastSeen || 0) < TOUCH_MS) return false;
  device.lastSeen = now;
  save();
  return true;
}

// The same session, with its year restarted.
function refreshCookie(req) {
  const raw = parseCookies(req.headers.cookie)[COOKIE];
  return raw ? cookieFor(req, raw, SESSION_DAYS) : null;
}

/* -------------------------------- devices -------------------------------- */

// The token is returned exactly once, here. Only its hash is stored, so a
// stolen auth.json is a list of names and dates, not a ring of keys. There is
// no need for scrypt on top: the token is 256 bits of randomness, not a
// password someone chose, so there is nothing to grind through.
function enrol(name, ua) {
  const token = rand(32);
  const device = {
    id: crypto.randomBytes(6).toString("hex"),
    name: String(name || "").trim().slice(0, 40) || "A device",
    hash: sha(token),
    created: Date.now(),
    lastSeen: Date.now(),
    ua: String(ua || "").slice(0, 200)
  };
  store.devices.push(device);
  claimSecret = null;              // the bootstrap door closes behind the first device
  save();
  return { device, token };
}

function listDevices(current) {
  return store.devices.map(d => ({
    id: d.id, name: d.name, created: d.created, lastSeen: d.lastSeen, ua: d.ua,
    current: !!current && d.id === current.id
  }));
}

function renameDevice(id, name) {
  const d = store.devices.find(x => x.id === id);
  if (!d) return false;
  d.name = String(name || "").trim().slice(0, 40) || d.name;
  save();
  return true;
}

// Revoking the last device is allowed on purpose: it is how you start over
// after a phone is lost or stolen. Doing so re-arms the claim secret there and
// then, rather than making someone restart the server to be let back into
// their own house — the log was already the root of trust, so this gives away
// nothing that a restart wouldn't.
function revokeDevice(id) {
  const before = store.devices.length;
  store.devices = store.devices.filter(d => d.id !== id);
  if (store.devices.length === before) return false;
  save();
  if (!store.devices.length) claimSecret = rand(32);
  return true;
}

/* ---------------------------- claim and invite ---------------------------- */

function claim(secret, name, ua) {
  if (!claimSecret || paired()) return null;
  if (!same(sha(secret), sha(claimSecret))) return null;
  return enrol(name, ua);
}

function startInvite() {
  sweep();
  const id = crypto.randomBytes(6).toString("hex");
  const secret = rand(24);
  invites.set(id, { hash: sha(secret), expires: Date.now() + INVITE_TTL_MS });
  return { id, secret, expires: Date.now() + INVITE_TTL_MS };
}

function redeemInvite(id, secret, name, ua) {
  sweep();
  const inv = invites.get(id);
  if (!inv || !same(inv.hash, sha(secret))) return null;
  invites.delete(id);              // single use
  return enrol(name, ua);
}

/* --------------------------------- pairing -------------------------------- */

// Two secrets, and the split is the whole point. `device` never leaves the
// Portal and is the only thing that can collect the token. `approval` is what
// the QR and the typed code carry, and it can do nothing but approve.
//
// Without the split, anyone who merely *sees* the QR — over a video call, or
// across the room — could poll alongside the Portal and race it for the token
// the moment a parent approved. This is why the OAuth device grant has both a
// device code and a user code, and the reason holds here.
function startPairing(ua) {
  sweep();
  // Starting a pairing needs no session — it is what an unpaired Portal does
  // before it has one — so it is the one open endpoint that can be made to
  // allocate. Five minutes of a script hammering it would otherwise be five
  // minutes of unbounded memory. Past the cap the oldest pending pairing is
  // dropped, which at worst makes one Portal redraw its QR.
  if (pairings.size >= MAX_PAIRINGS) {
    const oldest = [...pairings.entries()].sort((a, b) => a[1].expires - b[1].expires)[0];
    if (oldest) pairings.delete(oldest[0]);
  }
  const id = crypto.randomBytes(6).toString("hex");
  const device = rand(24), approval = rand(24);
  const code = userCode();
  const p = {
    id,
    deviceHash: sha(device),
    approvalHash: sha(approval),
    code,
    ua: String(ua || "").slice(0, 200),
    attempts: 0,
    approved: null,
    expires: Date.now() + PAIR_TTL_MS
  };
  pairings.set(id, p);
  return { id, deviceSecret: device, approvalSecret: approval, code, expires: p.expires };
}

function pairingByApproval(id, secret) {
  sweep();
  const p = pairings.get(id);
  if (!p || !same(p.approvalHash, sha(secret))) return null;
  return p;
}

// The typed fallback, for when the camera won't focus. A six-character code out
// of a 32-character alphabet is a billion possibilities and lives five minutes,
// but guessing is capped anyway: ten tries per pairing, and a server-wide burst
// limit because behind a tunnel every request shares one source address, so
// rate limiting by IP would be limiting one bucket for the whole internet.
function pairingByCode(code) {
  sweep();
  const now = Date.now();
  codeHits = codeHits.filter(t => now - t < 60000);
  if (codeHits.length >= CODE_BURST) return { error: "too many attempts, wait a minute" };
  codeHits.push(now);

  const want = String(code || "").toUpperCase().replace(/[^0-9A-Z]/g, "");
  for (const p of pairings.values()) {
    if (p.attempts >= CODE_ATTEMPTS) continue;
    if (same(sha(p.code), sha(want))) return { pairing: p };
  }
  // Charge the attempt against every live pairing: a wrong guess is a guess at
  // all of them, and there is normally only one.
  for (const p of pairings.values()) p.attempts++;
  return { error: "that code doesn't match" };
}

function approve(p, name, by) {
  p.approved = { name: String(name || "").trim().slice(0, 40) || "New device", by: by.id };
  return true;
}

// Collection. The device is created here rather than at approval, so a pairing
// that is approved and then abandoned leaves nothing behind.
function collect(id, deviceSecret) {
  sweep();
  const p = pairings.get(id);
  if (!p || !same(p.deviceHash, sha(deviceSecret))) return { gone: true };
  if (!p.approved) return { pending: true };
  pairings.delete(id);
  return enrol(p.approved.name, p.ua);
}

/* ------------------------------- share links ------------------------------ */

// A capability URL for exactly one file, for the grandparent who has no paired
// device. The token is fresh randomness and has nothing to do with the
// filename: names are `pic-<UTC second>-<four base36 chars>`, which is small
// enough to guess at, and a share token must not inherit that weakness.
function mintShare(file, days, by) {
  sweep();
  const id = crypto.randomBytes(6).toString("hex");
  const secret = rand(24);
  const share = {
    id,
    hash: sha(secret),
    file,
    created: Date.now(),
    expires: Date.now() + Math.round(days * 86400000),
    by: by.id
  };
  store.shares.push(share);
  save();
  return { id, token: id + "." + secret, expires: share.expires };
}

// Returns the filename this token may read, or null.
function shareTarget(token) {
  sweep();
  const dot = String(token || "").indexOf(".");
  if (dot < 0) return null;
  const id = token.slice(0, dot), secret = token.slice(dot + 1);
  const share = store.shares.find(s => s.id === id);
  if (!share || !same(share.hash, sha(secret))) return null;
  if (Date.now() > share.expires) return null;
  return share.file;
}

function listShares() {
  sweep();
  return store.shares.map(s => ({
    id: s.id, file: s.file, created: s.created, expires: s.expires, by: s.by
  }));
}

function revokeShare(id) {
  const before = store.shares.length;
  store.shares = store.shares.filter(s => s.id !== id);
  if (store.shares.length === before) return false;
  save();
  return true;
}

// Called when a capture is deleted. A link that outlives its file would 404
// anyway; dropping it keeps the list honest and the file un-resurrectable.
function revokeSharesFor(file) {
  const before = store.shares.length;
  store.shares = store.shares.filter(s => s.file !== file);
  if (store.shares.length !== before) save();
}

module.exports = {
  init, paired, deviceFor, setSession, clearSession, touch, refreshCookie, isSecure,
  enrol, listDevices, renameDevice, revokeDevice,
  claim, startInvite, redeemInvite,
  startPairing, pairingByApproval, pairingByCode, approve, collect,
  mintShare, shareTarget, listShares, revokeShare, revokeSharesFor,
  get claimSecret() { return claimSecret; }
};
