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
  // The face is MediaPipe's own canonical model, orthographically projected —
  // the geometry the tracker was trained to report, so a filter that fits this
  // fits a person. Twenty-two of its 468 vertices, taken from
  // canonical_face_model.obj (CC-BY 4.0), in canonical units: x right, y up,
  // z out of the face.
  const CANON = {
    eyeR: [-4.446, 2.664], eyeL: [4.446, 2.664],
    nose: [0.000, -1.127], mouth: [0.000, -3.994],
    earR: [-7.664, 0.673], earL: [7.664, 0.673],
    headTop: [0.000, 8.262], skullR: [-5.133, 7.486], skullL: [5.133, 7.486],
    templeR: [-7.743, 2.365], templeL: [7.743, 2.365],
    browR: [-3.987, 5.109], browL: [3.987, 5.109],
    chin: [0.000, -9.403], jawR: [-5.941, -6.224], jawL: [5.941, -6.224],
    noseUnder: [0.000, -2.089], nostrilR: [-1.406, -1.714], nostrilL: [1.406, -1.714],
    lipBottom: [0.000, -4.542], mouthR: [-2.456, -4.343], mouthL: [2.456, -4.343]
  };
  const CORE = ["eyeR", "eyeL", "nose", "mouth", "earR", "earL"];
  const SCALE = 18, CXP = 640, CYP = 331, W = 1280, H = 720;
  // Where a landmark lands in the frame, for the fit assertions to compare against.
  window.__at = k => ({
    x: (CXP + CANON[k][0] * SCALE) / W,
    y: (CYP - CANON[k][1] * SCALE) / H
  });

  // `jitterPx` reproduces the thing a fake camera cannot: a real tracker never
  // reports the same point twice, so "still" in the app means "moving by the
  // noise floor". A test with a perfectly motionless face passes on a threshold
  // far too tight to ever fire on the device.
  window.__face = { mode: "still", phase: 0, jitterPx: 0, dense: true, jawOpen: 0.45 };
  window.__anchors = () => {
    const f = window.__face;
    if (f.mode === "gone") return null;
    // A drift big enough to be visible, applied only in "move".
    const d = f.mode === "move" ? Math.sin((f.phase += 0.25)) * 0.06 : 0;
    const n = () => (f.jitterPx ? (Math.random() - 0.5) * 2 * f.jitterPx / 1280 : 0);
    const a = { blendshapes: f.dense ? { jawOpen: f.jawOpen } : {}, dense: !!f.dense };
    for (const k in CANON) {
      if (!f.dense && CORE.indexOf(k) < 0) continue;
      const p = window.__at(k);
      a[k] = { x: p.x + d + n(), y: p.y + n() };
    }
    return a;
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
const setJitter = px => p.evaluate(v => { window.__face.jitterPx = v; }, px);
const setJaw = v => p.evaluate(x => { window.__face.jawOpen = x; }, v);

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
check("the strip lists every filter", (await chips()).length === 9, JSON.stringify(await chips()));
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

// The realistic case: a face that is "still" but whose anchors never repeat,
// because the tracker regresses them afresh from every frame. This is what the
// device does, and what made the skip a no-op there.
await setJitter(3);
await sleep(600);
const jitterPaints = await paints(1000);
const jitHud = (await hud()).jit;
// A deadband against zero-mean noise is a slope, not a cliff — some steps clear
// it, and a repaint fires if ANY tracked point does. The dense tier follows 22
// points where blazeface follows 6, so it clears the deadband oftener and this
// bound is correspondingly loose. What matters is that it stays a fraction of the
// ~30 the same face produced before the deadband existed.
check("a still face under tracker noise still stops repainting", jitterPaints <= 20,
  "paints in 1s: " + jitterPaints + ", jit " + jitHud);
// jit is the largest of six anchors' steps between two independently-noisy
// detections, so it reads well above the per-anchor amplitude injected here.
check("the HUD reports a plausible noise floor", parseFloat(jitHud) > 1 && parseFloat(jitHud) < 9, jitHud);
check("the drawing survives the noise", (await inked()) > 0.005);

// Noise well past the deadband is real movement as far as the app can tell, and
// must not be swallowed — that would be a frozen sticker, not a saving.
await setJitter(14);
await sleep(600);
const loudPaints = await paints(1000);
check("movement above the deadband is not swallowed", loudPaints >= 12, "paints in 1s: " + loudPaints);
await setJitter(0);

// Moving face: repaints resume at the capped rate.
await setFace("move");
await sleep(400);
const movePaints = await paints(1000);
// Against the loop's own rate rather than a fixed number: the claim is "a moving
// face is not being skipped", and how fast the loop runs depends on the machine.
// The 30Hz cap is checked separately in render.mjs.
const renderFps = parseFloat((await hud()).render);
check("a moving face repaints at nearly every render frame",
  movePaints >= renderFps * 0.8 && movePaints <= 32,
  "paints in 1s: " + movePaints + " against render " + renderFps);

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

// ---- Fit ----
//
// "It draws something" was all the old tests checked, and a puppy whose ears
// grew out of its cheeks passed that easily. These compare where the ink
// actually lands against where the landmarks are, which is the only way a
// misplaced sticker can fail a test rather than a person's eyes.
//
// Ink is summarised as a bounding box and a centroid in normalised frame
// coordinates, per horizontal band, so a claim like "the ears are above the eyes"
// becomes arithmetic.
const inkStats = () => p.evaluate(() => {
  const cv = document.getElementById("fx");
  const w = cv.width, h = cv.height;
  const d = cv.getContext("2d").getImageData(0, 0, w, h).data;
  let n = 0, sx = 0, sy = 0, minX = 1, maxX = 0, minY = 1, maxY = 0;
  let aboveEye = 0, belowChin = 0;
  const eyeY = window.__at("eyeR").y, chinY = window.__at("chin").y;
  for (let y = 0; y < h; y += 2) {
    for (let x = 0; x < w; x += 2) {
      if (d[(y * w + x) * 4 + 3] <= 8) continue;
      const nx = x / w, ny = y / h;
      n++; sx += nx; sy += ny;
      if (nx < minX) minX = nx;
      if (nx > maxX) maxX = nx;
      if (ny < minY) minY = ny;
      if (ny > maxY) maxY = ny;
      if (ny < eyeY) aboveEye++;
      if (ny > chinY) belowChin++;
    }
  }
  if (!n) return null;
  return { n, cx: sx / n, cy: sy / n, minX, maxX, minY, maxY,
           aboveEye: aboveEye / n, belowChin: belowChin / n };
});
const at = k => p.evaluate(kk => window.__at(kk), k);

await setJitter(0);
await setFace("still");
const eye = await at("eyeR"), chin = await at("chin"), top = await at("headTop");
const temR = await at("templeR"), temL = await at("templeL");
const headW = temL.x - temR.x;

await pickFilter("Puppy");
await sleep(1200);
{
  const s = await inkStats();
  check("puppy: most of the drawing is not below the chin", s && s.belowChin < 0.12,
    s ? "below chin " + (s.belowChin * 100).toFixed(0) + "%" : "no ink");
  // The bug: ears drawn on the cheek anchors, 0.22 face units below the eye line.
  check("puppy: ears reach above the eye line", s && s.minY < eye.y - 0.02,
    s ? "top of ink " + s.minY.toFixed(3) + " vs eye " + eye.y.toFixed(3) : "no ink");
  check("puppy: nothing floats above the top of the head", s && s.minY > top.y - 0.06,
    s ? "top of ink " + s.minY.toFixed(3) + " vs head top " + top.y.toFixed(3) : "no ink");
  check("puppy: ears stay near the width of the head", s && s.maxX - s.minX < headW * 1.35,
    s ? "ink width " + (s.maxX - s.minX).toFixed(3) + " vs head " + headW.toFixed(3) : "no ink");
  check("puppy: the drawing is centred on the face", s && Math.abs(s.cx - 0.5) < 0.03,
    s ? "centroid x " + s.cx.toFixed(3) : "no ink");
}

await pickFilter("Kitty");
await sleep(1200);
{
  const s = await inkStats();
  check("kitty: ears rise above the head top", s && s.minY < top.y + 0.01,
    s ? "top of ink " + s.minY.toFixed(3) + " vs head top " + top.y.toFixed(3) : "no ink");
  check("kitty: ears do not run off the frame", s && s.minY > 0.01, s ? s.minY.toFixed(3) : "no ink");
  check("kitty: whiskers stay within half a head of the face",
    s && s.maxX - s.minX < headW * 1.6,
    s ? "ink width " + (s.maxX - s.minX).toFixed(3) + " vs head " + headW.toFixed(3) : "no ink");
}

await pickFilter("Royal");
await sleep(1200);
{
  const s = await inkStats();
  check("royal: the crown sits above the brows, not on the face",
    s && s.aboveEye > 0.95, s ? "above eye line " + (s.aboveEye * 100).toFixed(0) + "%" : "no ink");
  check("royal: the crown does not float off the head",
    s && s.maxY > top.y - 0.02, s ? "bottom of ink " + s.maxY.toFixed(3) + " vs head top " + top.y.toFixed(3) : "no ink");
}

await pickFilter("Cool");
await sleep(900);
{
  const s = await inkStats();
  check("cool: the lenses sit on the eye line", s && Math.abs(s.cy - eye.y) < 0.04,
    s ? "centroid y " + s.cy.toFixed(3) + " vs eye " + eye.y.toFixed(3) : "no ink");
}

await pickFilter("Fancy");
await sleep(900);
{
  const s = await inkStats();
  check("fancy: the hat clears the head and the mustache is on the lip",
    s && s.minY < top.y + 0.02 && s.maxY > eye.y, JSON.stringify(s && { minY: +s.minY.toFixed(3), maxY: +s.maxY.toFixed(3) }));
  check("fancy: the mustache is not as wide as the whole head",
    s && s.maxX - s.minX < headW * 1.25,
    s ? "ink width " + (s.maxX - s.minX).toFixed(3) + " vs head " + headW.toFixed(3) : "no ink");
}

// ---- The video-sampling filters ----
//
// These two resample the camera rather than drawing over it, which is a
// different contract: they take the video as a fourth argument, and they must be
// repainted every frame because the pixels change even when the face does not.

await pickFilter("Big Head");
await setJaw(0.85);
await sleep(1200);
{
  const s = await inkStats();
  check("big head: the zoomed head covers a large part of the frame",
    s && s.n > 0 && (s.maxX - s.minX) > headW * 1.4,
    s ? "ink width " + (s.maxX - s.minX).toFixed(3) + " vs head " + headW.toFixed(3) : "no ink");
  check("big head: it stays centred on the face", s && Math.abs(s.cx - 0.5) < 0.06,
    s ? "centroid x " + s.cx.toFixed(3) : "no ink");
}
// A closed mouth must be *nothing*, not a faint copy of the picture.
await setJaw(0);
await sleep(700);
check("big head: a closed mouth draws nothing at all", (await inked()) < 0.001,
  "inked " + (await inked()).toFixed(4));
// And it has to keep repainting while the face holds still, or it freezes a
// stale frame of video on screen.
await setJaw(0.85);
await sleep(500);
const videoPaints = await paints(1000);
check("big head: repaints every frame despite a still face", videoPaints >= 24,
  "paints in 1s: " + videoPaints);

await pickFilter("Skydive");
await sleep(1200);
{
  const s = await inkStats();
  check("skydive: the scene fills the frame", s && s.n > 0 && s.minX < 0.02 && s.maxX > 0.98 && s.maxY > 0.98,
    s ? JSON.stringify({ minX: +s.minX.toFixed(3), maxX: +s.maxX.toFixed(3), maxY: +s.maxY.toFixed(3) }) : "no ink");
  check("skydive: the picture is opaque, not a wash over the camera",
    (await inked()) > 0.97, "inked " + (await inked()).toFixed(3));
}
const scenePaints = await paints(1000);
check("skydive: animates at the render rate", scenePaints >= 24, "paints in 1s: " + scenePaints);
await setJaw(0.45);

// Every filter has to draw without throwing — including the mesh-tier one,
// which swaps trackers on selection.
for (const name of ["Puppy", "Kitty", "Cool", "Royal", "Googly", "Fancy", "Big Head", "Skydive"]) {
  await pickFilter(name);
  await sleep(900);
  const f = await inked();
  check("filter draws: " + name, f > 0.004, "inked: " + f.toFixed(4));
}

await b.close();
console.log(fail ? "\n" + fail + " FAILURE(S)" : "\nfilters draw OK");
process.exit(fail ? 1 : 0);
