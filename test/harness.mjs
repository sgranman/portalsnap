// One place that knows how to start a browser and how to get past the door.
//
// Before authorization existed, every test in here carried its own copy of the
// Chrome path, the port, and the four launch flags. That was tolerable while
// the copies agreed. Now there is a fourteenth thing to keep in step — a
// session cookie — and fourteen copies of it would be fourteen chances to
// write a test that passes against a server nobody is guarding.
//
// There is deliberately no way to switch authorization off. A test suite that
// runs against an unlocked server proves nothing about the locked one people
// actually deploy, and "skip auth on loopback" would be worse than useless
// here: the README's own dev recipe is `cloudflared tunnel --url
// http://localhost:8080`, where every request from the open internet arrives
// on loopback.
import puppeteer from "puppeteer-core";
import fs from "fs";
import path from "path";
import os from "os";
import { fileURLToPath } from "url";

// No Chrome is vendored, so find one. `npx puppeteer browsers install chrome`
// puts a Chrome for Testing under the puppeteer cache, which is the build these
// tests are written against; a normal Chrome works too. Set CHROME to skip all
// of this and point at one directly.
function findChrome() {
  if (process.env.CHROME) return process.env.CHROME;

  const home = os.homedir();
  const cache = process.env.PUPPETEER_CACHE_DIR || path.join(home, ".cache", "puppeteer", "chrome");
  const leaf = {
    darwin: "chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing",
    linux: "chrome-linux64/chrome",
    win32: "chrome-win64/chrome.exe"
  }[process.platform];

  // Newest installed Chrome for Testing first — the directory names sort by version.
  if (leaf && fs.existsSync(cache)) {
    const builds = fs.readdirSync(cache).sort().reverse();
    for (const b of builds) {
      const p = path.join(cache, b, leaf);
      if (fs.existsSync(p)) return p;
      // macOS x64 builds use a different directory name than arm64.
      const alt = p.replace("chrome-mac-arm64", "chrome-mac-x64");
      if (fs.existsSync(alt)) return alt;
    }
  }

  const installed = {
    darwin: ["/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"],
    linux: ["/usr/bin/google-chrome", "/usr/bin/chromium", "/usr/bin/chromium-browser"],
    win32: ["C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe"]
  }[process.platform] || [];
  for (const p of installed) if (fs.existsSync(p)) return p;

  throw new Error(
    "No Chrome found. Run `npx puppeteer browsers install chrome`, " +
    "or set CHROME to the browser you want these tests to drive."
  );
}

export const CHROME = findChrome();
export const PORT = process.env.PORT || 8099;
export const BASE = "http://127.0.0.1:" + PORT;

const HERE = path.dirname(fileURLToPath(import.meta.url));
const SESSION_FILE = path.join(HERE, ".session");
const CLAIM = process.env.PORTALSNAP_CLAIM || "test-only";

const ARGS = [
  "--use-fake-ui-for-media-stream",
  "--use-fake-device-for-media-stream",
  "--autoplay-policy=no-user-gesture-required",
  "--no-sandbox"
];

let cookie = null;   // the raw `psnap` cookie value, once we have one

function setCookieValue(res) {
  for (const line of res.headers.getSetCookie ? res.headers.getSetCookie() : []) {
    const m = /^psnap=([^;]*)/.exec(line);
    if (m) return decodeURIComponent(m[1]);
  }
  return null;
}

async function accepted(value) {
  const res = await fetch(BASE + "/auth/whoami", { headers: { Cookie: "psnap=" + value } });
  return res.ok;
}

// A session, reused across runs. The server keeps its device list on disk, so
// claiming works exactly once per data directory — cache the cookie or the
// second `node test/e2e.mjs` of the day would find the door already claimed
// and no way through it.
export async function session() {
  if (cookie) return cookie;

  try {
    const cached = fs.readFileSync(SESSION_FILE, "utf8").trim();
    if (cached && await accepted(cached)) return (cookie = cached);
  } catch (e) { /* no cached session, or a stale one: claim a new one */ }

  const res = await fetch(BASE + "/auth/claim", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ secret: CLAIM, name: "Test harness" })
  });
  const got = res.ok && setCookieValue(res);
  if (!got) {
    throw new Error(
      "the harness could not pair with the server at " + BASE + ".\n" +
      "  Start it with a scratch data directory and a known claim secret:\n" +
      "    PORTALSNAP_DATA=$TMPDIR/psnap-test PORTALSNAP_CLAIM=test-only PORT=" + PORT +
      " node server.js\n" +
      "  (a server that is already paired cannot be claimed again — that is the point of claiming)"
    );
  }
  fs.writeFileSync(SESSION_FILE, got);
  return (cookie = got);
}

// A browser that is already signed in. The cookie goes on the browser rather
// than the page, so every page, worker and worklet it opens inherits it —
// which matters more than it sounds: the face tracker is pulled in by
// `importScripts` inside a worker, and that request carries cookies or nothing.
export async function launch(extra = {}) {
  const value = await session();
  const browser = await puppeteer.launch({
    executablePath: CHROME,
    headless: true,
    ...extra,
    args: [...ARGS, ...(extra.args || [])]
  });
  await browser.setCookie({ name: "psnap", value, url: BASE });
  return browser;
}

// A browser with no session, for the tests that check the door is shut.
export function launchAnonymous(extra = {}) {
  return puppeteer.launch({
    executablePath: CHROME,
    headless: true,
    ...extra,
    args: [...ARGS, ...(extra.args || [])]
  });
}

// `fetch` for the Node side of a test: same signature, plus the cookie.
export async function api(target, init = {}) {
  const value = await session();
  const url = target.startsWith("http") ? target : BASE + target;
  return fetch(url, {
    ...init,
    headers: { ...(init.headers || {}), Cookie: "psnap=" + value }
  });
}

// Empties the album through the API. `e2e.mjs` counts tiles and needs to start
// from nothing; doing it with `rm` would race the server's own `.part` files.
export async function clearAlbum() {
  const list = await (await api("/media/list")).json();
  for (const item of list.items || []) {
    await api("/media/" + item.name, { method: "DELETE" });
  }
  return (list.items || []).length;
}
