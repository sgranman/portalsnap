// The third tracker tier, with the real model — everything else stubs the worker,
// which would happily pretend a segmenter exists that does not.
//
// It cannot judge the cut-out: the selfie model is trained on photographs and
// reports near enough the whole frame as "person" when shown Chrome's synthetic
// camera. So this checks the plumbing — that the tier loads, swaps, produces
// masks, composites, and swaps back — and the HUD reports the one number that
// says whether the model is behaving on a real face.
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
p.on("console", m => { if (/tracker:|worker unavailable|tracking unavailable/.test(m.text())) { console.log("  [warn] " + m.text()); fail++; } });
await p.setViewport({ width: 1280, height: 644 });
await p.goto("http://127.0.0.1:" + PORT + "/app.html", { waitUntil: "domcontentloaded" });
await p.waitForFunction(() => document.getElementById("loader").classList.contains("hidden"), { timeout: 60000 });
await p.click("#hudTap");
// The HUD is filled in by the render loop, not by the tap: reading it straight
// away sees an empty box and blames the app for it.
await p.waitForFunction(() => /mode\s+\w/.test(document.getElementById("hud").textContent), { timeout: 10000 });

const hud = () => p.evaluate(() => {
  const o = {};
  for (const l of document.getElementById("hud").textContent.split("\n")) {
    const m = /^(\w+)\s+(.*)$/.exec(l.trim()); if (m) o[m[1]] = m[2];
  }
  return o;
});
const pick = n => p.evaluate(nn => {
  const c = [...document.querySelectorAll(".chip")].find(x => x.textContent.includes(nn));
  if (!c) throw new Error("no chip " + nn);
  c.click();
}, n);
const inked = () => p.evaluate(() => {
  const cv = document.getElementById("fx");
  const d = cv.getContext("2d").getImageData(0, 0, cv.width, cv.height).data;
  let n = 0, seen = 0;
  for (let i = 3; i < d.length; i += 4 * 37) { seen++; if (d[i] > 8) n++; }
  return n / seen;
});

check("the app starts on the fast tier", (await hud()).mode === "fast", (await hud()).mode);

// Selecting a background swaps the model. The segmenter is a real download and a
// real GPU init, so this is the slow step.
await pick("Beach");
await p.waitForFunction(() => /segment/.test(document.getElementById("hud").textContent), { timeout: 60000 });
await sleep(2500);
const h = await hud();
check("selecting a background swaps to the segmenter", h.mode === "segment", h.mode);
check("the tracker did not fall over", h.backend === "worker", h.backend);
check("masks are arriving", parseFloat(h.detect) > 1, "detect " + h.detect);
check("inference is being timed", parseFloat(h.infer) > 0, "infer " + h.infer);
check("the HUD reports the person share", /%/.test(h.seg || ""), "seg " + h.seg);
check("the scene covers the frame", (await inked()) > 0.97, "inked " + (await inked()).toFixed(3));

// The other two scenes share the tier, so switching between them must not reload.
await pick("Moon");
await sleep(1200);
check("switching scenes keeps the segmenter", (await hud()).mode === "segment");
check("the moon scene also covers the frame", (await inked()) > 0.97);
await pick("Palace");
await sleep(1200);
check("the palace scene also covers the frame", (await inked()) > 0.97);

// And back: a face filter has to get its face model returned.
await pick("Cool");
await p.waitForFunction(() => /mode\s+fast/.test(document.getElementById("hud").textContent), { timeout: 60000 });
await sleep(1500);
const back = await hud();
check("choosing a face filter swaps back", back.mode === "fast", back.mode);
check("the seg line disappears with the tier", !back.seg, JSON.stringify(back.seg));
check("face tracking still works afterwards", parseFloat(back.detect) > 1, "detect " + back.detect);

// A capture while a background is up must composite the scene, not the raw camera.
await pick("Moon");
await sleep(1800);
await p.click("#shutter");
await p.waitForSelector("#review.show", { timeout: 10000 });
const dark = await p.evaluate(async () => {
  const img = document.getElementById("shot");
  const c = document.createElement("canvas");
  c.width = 160; c.height = 90;
  const x = c.getContext("2d");
  x.drawImage(img, 0, 0, 160, 90);
  const d = x.getImageData(0, 0, 160, 90).data;
  let lum = 0;
  for (let i = 0; i < d.length; i += 4) lum += (d[i] + d[i + 1] + d[i + 2]) / 3;
  return lum / (d.length / 4);
});
// The fake camera is a bright green field; the moon scene is nearly black sky.
check("a photo taken on the moon is not a photo of the room", dark < 110, "mean luminance " + dark.toFixed(0));

await b.close();
console.log(fail ? "\n" + fail + " FAILURE(S)" : "\nsegmentation tier OK");
process.exit(fail ? 1 : 0);
