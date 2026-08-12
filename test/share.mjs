// The Send button, against a browser that lies about sharing.
//
// Measured on the Portal: canShare() returns true for text, links, photos and
// clips, and share() then rejects with AbortError in 16-129ms every time, with
// no sheet ever drawn. AbortError is also what a real cancellation looks like,
// so they are told apart by the clock. This stubs both halves of that API to
// reproduce each case exactly.
import puppeteer from "puppeteer-core";
const CHROME = process.env.CHROME ||
  "/Users/you/.cache/puppeteer/chrome/mac_arm-150.0.7871.24/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing";
const PORT = process.env.PORT || 8099;
const BASE = "http://127.0.0.1:" + PORT;
let fail = 0;
const check = (n, ok, x = "") => { console.log((ok ? "  PASS  " : "  FAIL  ") + n + (x ? "   " + x : "")); if (!ok) fail++; };
const sleep = ms => new Promise(r => setTimeout(r, ms));

// mode: "portal" refuses instantly, "cancel" aborts slowly like a person,
// "works" resolves.
async function run(mode) {
  const b = await puppeteer.launch({ executablePath: CHROME, headless: true,
    args: ["--use-fake-ui-for-media-stream","--use-fake-device-for-media-stream",
           "--autoplay-policy=no-user-gesture-required","--no-sandbox"] });
  const p = await b.newPage();
  p.on("pageerror", e => { console.log("  [pageerror] " + e.message); fail++; });
  await p.setViewport({ width: 1280, height: 644 });
  await p.evaluateOnNewDocument(m => {
    window.__shareCalls = [];
    navigator.canShare = () => true;                 // the Portal's answer, always
    navigator.share = payload => {
      window.__shareCalls.push(Object.keys(payload).sort().join(","));
      if (m === "works") return Promise.resolve();
      const err = new DOMException("Share failed", "AbortError");
      // 40ms is a refusal, 1200ms is a person deciding. Only "cancel" is the
      // slow one; the clipboard case needs a refusal to fall through to it.
      return new Promise((_, rej) => setTimeout(() => rej(err), m === "cancel" ? 1200 : 40));
    };
    // defineProperty, not assignment: navigator.clipboard is a read-only accessor,
    // so `navigator.clipboard = …` silently does nothing and the real one answers.
    Object.defineProperty(navigator, "clipboard", {
      configurable: true,
      get: () => ({
        writeText: () => m === "clipboard"
          ? Promise.resolve()
          : Promise.reject(new Error("no clipboard here"))
      })
    });
  }, mode);
  await p.goto(BASE + "/app.html", { waitUntil: "domcontentloaded" });
  await p.waitForFunction(() => document.getElementById("loader").classList.contains("hidden"), { timeout: 60000 });
  await p.evaluate(() => { try { localStorage.removeItem("portalsnap.shareDead"); } catch (e) {} });

  await p.click("#shutter");
  await p.waitForSelector("#review.show", { timeout: 10000 });
  const offered = await p.evaluate(() => !document.getElementById("share").hidden);
  check(mode + ": the button is offered when canShare says yes", offered);

  await p.click("#keep");
  await p.waitForFunction(() => document.getElementById("keep").textContent === "Saved ✓", { timeout: 20000 });
  await p.click("#share");
  await sleep(mode === "cancel" ? 2200 : 1200);

  const after = await p.evaluate(() => ({
    calls: window.__shareCalls,
    hidden: document.getElementById("share").hidden,
    msg: document.getElementById("saveMsg").textContent,
    dead: (() => { try { return localStorage.getItem("portalsnap.shareDead"); } catch (e) { return null; } })()
  }));
  await b.close();
  return after;
}

// A browser that refuses: try files, then a link, then give up for good.
const portal = await run("portal");
check("portal: tries files first, then a link", portal.calls.join(" | ") === "files,title | title,url",
  JSON.stringify(portal.calls));
check("portal: says where to get the photo instead", /gallery\.html on a phone/.test(portal.msg),
  JSON.stringify(portal.msg));
check("portal: stops offering a button that cannot work", portal.hidden && portal.dead === "1",
  JSON.stringify({ hidden: portal.hidden, dead: portal.dead }));

// A person changing their mind must not retire the feature.
const cancel = await run("cancel");
check("cancel: a real cancellation is not treated as a refusal", cancel.calls.length === 1,
  JSON.stringify(cancel.calls));
check("cancel: the button stays", !cancel.hidden && cancel.dead !== "1",
  JSON.stringify({ hidden: cancel.hidden, dead: cancel.dead }));
check("cancel: nothing is said about it", cancel.msg === "It's in Photos now", JSON.stringify(cancel.msg));

// Where the clipboard survives, a copied link is a better ending than an
// apology — and the button stays, because that route still works.
const clip = await run("clipboard");
check("clipboard: falls back to copying the link", /Link copied/.test(clip.msg), JSON.stringify(clip.msg));
check("clipboard: the button is not retired", !clip.hidden && clip.dead !== "1",
  JSON.stringify({ hidden: clip.hidden, dead: clip.dead }));

// And where sharing works, it just works.
const works = await run("works");
check("works: one call and no complaints", works.calls.length === 1 && !works.hidden,
  JSON.stringify(works.calls));

console.log(fail ? "\n" + fail + " FAILURE(S)" : "\nshare fallbacks OK");
process.exit(fail ? 1 : 0);
