// The full screen button: present when the browser allows it, hidden when not,
// toggling in both directions, and never swallowing the camera controls.
import puppeteer from "puppeteer-core";
const CHROME = process.env.CHROME ||
  "/Users/you/.cache/puppeteer/chrome/mac_arm-150.0.7871.24/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing";
const PORT = process.env.PORT || 8099;
let fail = 0;
const check = (n, ok, x = "") => { console.log((ok ? "  PASS  " : "  FAIL  ") + n + (x ? "   " + x : "")); if (!ok) fail++; };
const sleep = ms => new Promise(r => setTimeout(r, ms));

const b = await puppeteer.launch({ executablePath: CHROME, headless: true,
  args: ["--use-fake-ui-for-media-stream","--use-fake-device-for-media-stream","--autoplay-policy=no-user-gesture-required","--no-sandbox"] });
const p = await b.newPage();
p.on("pageerror", e => { console.log("  [pageerror] " + e.message); fail++; });
await p.setViewport({ width: 1280, height: 644 });

// Record what the page asks the platform for, since headless Chrome may or may
// not honour an actual fullscreen transition.
// Patch the prototypes, not the instances: this runs before there is a
// documentElement to patch.
await p.evaluateOnNewDocument(() => {
  window.__fsCalls = [];
  const req = Element.prototype.requestFullscreen;
  Element.prototype.requestFullscreen = function (...a) {
    window.__fsCalls.push("enter");
    return req.apply(this, a);
  };
  const exit = Document.prototype.exitFullscreen;
  Document.prototype.exitFullscreen = function (...a) {
    window.__fsCalls.push("exit");
    return exit.apply(this, a);
  };
});

await p.goto("http://127.0.0.1:" + PORT + "/app.html", { waitUntil: "domcontentloaded" });
await p.waitForFunction(() => document.getElementById("loader").classList.contains("hidden"), { timeout: 30000 });

const state = () => p.evaluate(() => {
  const fs = document.getElementById("fs");
  const r = fs.getBoundingClientRect();
  return {
    hidden: fs.hidden, label: fs.getAttribute("aria-label"),
    d: document.getElementById("fsIcon").getAttribute("d"),
    box: [Math.round(r.width), Math.round(r.height)],
    calls: window.__fsCalls.slice(),
    inFs: !!document.fullscreenElement
  };
});

let s = await state();
check("the button is shown where fullscreen is supported", !s.hidden);
check("it renders at a tappable size", s.box[0] >= 44 && s.box[1] >= 44, JSON.stringify(s.box));
check("it starts in the enter state", /^M4 9V4/.test(s.d) && s.label === "Full screen", s.label);

await p.click("#fs");
await sleep(700);
s = await state();
check("tapping it asks to enter fullscreen", s.calls[0] === "enter", JSON.stringify(s.calls));
// Only assert the icon flipped if the platform actually went fullscreen; a
// headless refusal is not the page's fault, and the page hints instead.
if (s.inFs) {
  check("the icon flips to exit once fullscreen", /^M9 4v5/.test(s.d) && s.label === "Leave full screen", s.label);
  await p.click("#fs");
  await sleep(700);
  s = await state();
  check("tapping again asks to leave", s.calls.includes("exit"), JSON.stringify(s.calls));
  check("the icon returns to enter", /^M4 9V4/.test(s.d) && s.label === "Full screen", s.label);
} else {
  console.log("  note: headless refused the transition; icon-flip checks skipped");
}

// The camera controls must still be usable — a new button in the bar is exactly
// the sort of thing that quietly covers the shutter.
const clickable = await p.evaluate(() => {
  const out = {};
  for (const id of ["shutter", "record", "album", "fs"]) {
    const r = document.getElementById(id).getBoundingClientRect();
    const at = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
    out[id] = at ? (at.closest("button") || at).id || at.tagName : null;
  }
  return out;
});
check("every bar button is the topmost thing at its own centre",
  ["shutter", "record", "album", "fs"].every(id => clickable[id] === id), JSON.stringify(clickable));

await p.click("#shutter");
await p.waitForSelector("#review.show", { timeout: 10000 });
check("the shutter still works with the new button in the bar", true);

await b.close();
console.log(fail ? "\n" + fail + " FAILURE(S)" : "\nfullscreen button OK");
process.exit(fail ? 1 : 0);
