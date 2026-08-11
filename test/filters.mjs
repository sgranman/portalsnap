// The gap that let a real bug ship: Chrome's fake camera has no face in it, so
// no existing test ever reached the drawing path. This one stubs the tracker
// worker instead, feeding the app synthetic anchors it can drive on demand —
// a still face, a moving face, or no face at all — and then asserts on pixels.
import puppeteer from "puppeteer-core";

const CHROME = process.env.CHROME ||
  "/Users/you/.cache/puppeteer/chrome/mac_arm-150.0.7871.24/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing";
const PORT = process.env.PORT || 8099;

let fail = 0;
const check = (n, ok, x = "") => {
  console.log((ok ? "  PASS  " : "  FAIL  ") + n + (x ? "   " + x : ""));
  if (!ok) fail++;
};
const sleep = ms => new Promise(r => setTimeout(r, ms));

const b = await puppeteer.launch({
  executablePath: CHROME, headless: true,
  args: ["--use-fake-ui-for-media-stream", "--use-fake-device-for-media-stream",
         "--autoplay-policy=no-user-gesture-required", "--no-sandbox"]
});
const p = await b.newPage();
p.on("pageerror", e => { console.log("  [pageerror] " + e.message); fail++; });
let expectFilterThrow = false;
p.on("console", m => {
  if (m.type() === "warning" && /^filter /.test(m.text())) {
    if (expectFilterThrow) return;
    console.log("  [warn] " + m.text());
    fail++;
  }
});
await p.setViewport({ width: 1280, height: 644 });

// Replaces the tracker with a controllable one. Same message contract as
// tracker.worker.js: init -> ready, frame -> result.
await p.evaluateOnNewDocument(() => {
  window.__face = { mode: "still", phase: 0 };
  window.__anchors = () => {
    const f = window.__face;
    if (f.mode === "gone") return null;
    // A drift big enough to be visible, applied only in "move".
    const d = f.mode === "move" ? Math.sin((f.phase += 0.25)) * 0.06 : 0;
    const at = (x, y) => ({ x: x + d, y });
    return {
      eyeR: at(0.45, 0.42), eyeL: at(0.55, 0.42),
      nose: at(0.50, 0.50), mouth: at(0.50, 0.58),
      earR: at(0.40, 0.45), earL: at(0.60, 0.45),
      blendshapes: { jawOpen: 0.2 }
    };
  };
  window.Worker = class {
    postMessage(m) {
      if (m.type === "init") {
        setTimeout(() => this.onmessage && this.onmessage({ data: { type: "ready", mode: m.mode, loadMs: 1 } }), 5);
        return;
      }
      if (m.type === "frame") {
        if (m.bitmap && m.bitmap.close) m.bitmap.close();
        setTimeout(() => this.onmessage && this.onmessage({
          data: { type: "result", anchors: window.__anchors(), inferMs: 12, seq: m.seq }
        }), 12);
      }
    }
    terminate() {}
  };
});

await p.goto("http://127.0.0.1:" + PORT + "/app.html", { waitUntil: "domcontentloaded" });
await p.waitForFunction(() => document.getElementById("loader").classList.contains("hidden"), { timeout: 30000 });

const chips = () => p.evaluate(() => [...document.querySelectorAll(".chip")].map(c => c.textContent));
const pickFilter = name => p.evaluate(n => {
  const c = [...document.querySelectorAll(".chip")].find(x => x.textContent.includes(n));
  if (!c) throw new Error("no chip " + n);
  c.click();
}, name);
const setFace = mode => p.evaluate(m => { window.__face.mode = m; }, mode);

// Fraction of sampled pixels that carry any alpha at all.
const inked = () => p.evaluate(() => {
  const cv = document.getElementById("fx");
  const d = cv.getContext("2d").getImageData(0, 0, cv.width, cv.height).data;
  let n = 0, seen = 0;
  for (let i = 3; i < d.length; i += 4 * 37) { seen++; if (d[i] > 8) n++; }
  return n / seen;
});
// One paint begins with exactly one wipe, whichever form it takes.
const paints = ms => p.evaluate(t => new Promise(res => {
  const c = document.getElementById("fx").getContext("2d");
  const keys = ["reset", "clearRect"].filter(k => typeof c[k] === "function");
  const orig = {};
  let n = 0;
  for (const k of keys) { orig[k] = c[k].bind(c); c[k] = (...a) => { n++; return orig[k](...a); }; }
  setTimeout(() => { for (const k of keys) c[k] = orig[k]; res(n); }, t);
}), ms);
const hud = () => p.evaluate(() => {
  const o = {};
  for (const l of document.getElementById("hud").textContent.split("\n")) {
    const m = /^(\w+)\s+(.*)$/.exec(l.trim()); if (m) o[m[1]] = m[2];
  }
  return o;
});

await p.click("#hudTap");
await sleep(600);
check("the strip lists every filter", (await chips()).length === 7, JSON.stringify(await chips()));
check("a face is being tracked", (await hud()).face === "yes", JSON.stringify(await hud()));

// The regression: with a filter picked and a face in view, something must
// actually appear on the overlay. This is what broke. Cool has no clock of its
// own, so the same selection also exercises the still-face skip below.
await pickFilter("Cool");
await sleep(700);
const ink = await inked();
check("a picked filter draws on the overlay", ink > 0.005, "inked fraction: " + ink.toFixed(4));
check("the HUD agrees it is drawing", (await hud()).drawing === "yes");

// Still face + a filter with no clock of its own: the picture is already right,
// so it should stop being repainted without disappearing.
const stillPaints = await paints(1000);
check("a still face stops repainting", stillPaints <= 3, "paints in 1s: " + stillPaints);
check("the drawing stays on screen while skipped", (await inked()) > 0.005);

// Moving face: repaints resume at the capped rate.
await setFace("move");
await sleep(400);
const movePaints = await paints(1000);
check("a moving face repaints near the 30Hz cap", movePaints >= 24 && movePaints <= 32, "paints in 1s: " + movePaints);

// An animated filter has to keep repainting when the face holds still, but at
// the slower animation rate rather than the full render rate.
await setFace("still");
await pickFilter("Googly");
await sleep(500);
const animPaints = await paints(1000);
check("an animated filter keeps repainting when still, at 15Hz",
  animPaints >= 12 && animPaints <= 17, "paints in 1s: " + animPaints);

// Losing the face clears the overlay rather than leaving a sticker floating.
await setFace("gone");
await sleep(1400);
check("a lost face clears the overlay", (await inked()) < 0.001, "inked: " + (await inked()).toFixed(4));
await setFace("still");
await sleep(600);
check("the overlay comes back when the face does", (await inked()) > 0.005);

// None must clear it too.
await pickFilter("None");
await sleep(300);
check("None clears the overlay", (await inked()) < 0.001);

// A filter that throws part-way through a draw used to leave the context
// transformed, so every later clear wiped a rotated sliver and the overlay
// froze for good. Break one draw on purpose and check the app recovers.
expectFilterThrow = true;
await pickFilter("Cool");
await sleep(400);
await p.evaluate(() => {
  const c = document.getElementById("fx").getContext("2d");
  const o = c.fill.bind(c);
  let armed = true;
  c.fill = (...a) => { if (armed) { armed = false; throw new Error("test: filter blew up"); } return o(...a); };
  setTimeout(() => { c.fill = o; }, 400);
});
await setFace("move");
await sleep(1200);
await setFace("still");
await sleep(400);
expectFilterThrow = false;
check("the overlay survives a filter throwing mid-draw", (await inked()) > 0.004, "inked: " + (await inked()).toFixed(4));
await pickFilter("None");
await sleep(300);
check("clearing still covers the whole canvas afterwards", (await inked()) < 0.001, "inked: " + (await inked()).toFixed(4));

// Every filter has to draw without throwing — including the mesh-tier one,
// which swaps trackers on selection.
for (const name of ["Puppy", "Kitty", "Cool", "Royal", "Googly", "Fancy"]) {
  await pickFilter(name);
  await sleep(900);
  const f = await inked();
  check("filter draws: " + name, f > 0.004, "inked: " + f.toFixed(4));
}

await b.close();
console.log(fail ? "\n" + fail + " FAILURE(S)" : "\nfilters draw OK");
process.exit(fail ? 1 : 0);
