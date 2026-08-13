// Two children in front of the camera, which is what this device is for.
//
// The tracker reports a fresh unordered list of faces every detection and never
// says whose face is whose; deciding that is the app's job, and the failure it
// produces is specific: a crown that hops between two heads, or one child's
// sticker dragged toward the other. Pixels alone cannot catch it — two hats in
// the right two places look identical whether or not they swapped — so these
// assertions read both the ink and the track identities behind it.
import puppeteer from "puppeteer-core";
import { installFaceStub } from "./facestub.mjs";

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
p.on("console", m => {
  if (m.type() === "warning" && /^filter /.test(m.text())) {
    console.log("  [warn] " + m.text());
    fail++;
  }
});
await p.setViewport({ width: 1280, height: 644 });
await installFaceStub(p);

await p.goto("http://127.0.0.1:" + PORT + "/app.html", { waitUntil: "domcontentloaded" });
await p.waitForFunction(() => document.getElementById("loader").classList.contains("hidden"), { timeout: 30000 });

const pickFilter = name => p.evaluate(n => {
  const c = [...document.querySelectorAll(".chip")].find(x => x.textContent.includes(n));
  if (!c) throw new Error("no chip " + n);
  c.click();
}, name);
const setExtra = list => p.evaluate(l => { window.__extra = l; }, list);
const tracked = () => p.evaluate(() => window.__tracks());
const hud = () => p.evaluate(() => {
  const o = {};
  for (const l of document.getElementById("hud").textContent.split("\n")) {
    const m = /^(\w+)\s+(.*)$/.exec(l.trim()); if (m) o[m[1]] = m[2];
  }
  return o;
});

// Ink inside a vertical strip of the frame, as a fraction of the pixels sampled
// there plus its centroid — enough to say "there is a sticker over that head,
// and it is on the eye line" for each head separately.
const band = (x0, x1) => p.evaluate(([a, z]) => {
  const cv = document.getElementById("fx");
  const w = cv.width, h = cv.height;
  const d = cv.getContext("2d").getImageData(0, 0, w, h).data;
  let n = 0, seen = 0, sy = 0, sx = 0;
  for (let y = 0; y < h; y += 2) {
    for (let x = Math.round(a * w); x < Math.round(z * w); x += 2) {
      seen++;
      if (d[(y * w + x) * 4 + 3] <= 8) continue;
      n++; sy += y / h; sx += x / w;
    }
  }
  return { frac: seen ? n / seen : 0, cx: n ? sx / n : 0, cy: n ? sy / n : 0 };
}, [x0, x1]);

// Where the skydiver's jumpsuit is. The sky is a blue gradient and the canopy is
// four flat primaries, so its orange is unambiguous. Reported as a count and a
// centroid per half of the frame plus overall, because "two jumpers" against
// "one jumper" is a question about *where* the orange is: a single jumper sits
// astride the centre line and would put pixels in both halves too.
const suitStats = () => p.evaluate(() => {
  const cv = document.getElementById("fx");
  const w = cv.width, h = cv.height;
  const d = cv.getContext("2d").getImageData(0, 0, w, h).data;
  const s = { n: 0, cx: 0, minX: 1, maxX: 0, leftN: 0, leftCx: 0, rightN: 0, rightCx: 0 };
  for (let y = 0; y < h; y += 2) {
    for (let x = 0; x < w; x += 2) {
      const i = (y * w + x) * 4;
      const r = d[i], g = d[i + 1], bl = d[i + 2];
      if (!(r > 200 && g > 85 && g < 165 && bl > 40 && bl < 115)) continue;
      const nx = x / w;
      s.n++; s.cx += nx;
      if (nx < s.minX) s.minX = nx;
      if (nx > s.maxX) s.maxX = nx;
      if (nx < 0.5) { s.leftN++; s.leftCx += nx; } else { s.rightN++; s.rightCx += nx; }
    }
  }
  if (s.n) s.cx /= s.n;
  if (s.leftN) s.leftCx /= s.leftN;
  if (s.rightN) s.rightCx /= s.rightN;
  return s;
});

await p.click("#hudTap");
await sleep(400);

// ---- Two faces ----
//
// A second person at a quarter of the frame to the right: far enough apart that
// two heads' worth of sticker cannot overlap, close enough to be a real pair of
// children on one sofa.
const RIGHT = 0.25;
await pickFilter("Cool");
await setExtra([{ dx: RIGHT }]);
await sleep(900);

{
  const t = await tracked();
  check("both faces are tracked", t.length === 2, JSON.stringify(t));
  check("the HUD counts them against the tier's cap", (await hud()).faces === "2 / 3",
    JSON.stringify((await hud()).faces));

  const eyeY = await p.evaluate(() => window.__at("eyeR").y);
  const left = await band(0.36, 0.64), right = await band(0.61, 0.89);
  check("the left face gets a sticker", left.frac > 0.01, "inked " + left.frac.toFixed(3));
  check("the right face gets a sticker", right.frac > 0.01, "inked " + right.frac.toFixed(3));
  check("each sticker sits on its own eye line",
    Math.abs(left.cy - eyeY) < 0.04 && Math.abs(right.cy - eyeY) < 0.04,
    "left y " + left.cy.toFixed(3) + ", right y " + right.cy.toFixed(3) + " vs eye " + eyeY.toFixed(3));
  // The failure this whole test exists for: art drawn between two faces rather
  // than on each of them, which is what one shared smoothing state produces —
  // a single pair of glasses averaging the two heads. The gap is narrow, because
  // the shades' arms reach all the way to each face's own ears: the left face's
  // ear is at 0.608 and the right face's at 0.642, so this is the ~30px of frame
  // that genuinely belongs to neither of them.
  const middle = await band(0.615, 0.637);
  check("nothing is drawn in the gap between them", middle.frac < 0.02,
    "inked " + middle.frac.toFixed(3));
}

// ---- Identity ----
//
// Ids have to survive people moving, including moving toward each other. Nothing
// on screen can show this: two hats in the right two places look the same whether
// or not the tracks behind them swapped.
{
  const before = (await tracked()).map(t => t.id);
  for (let dx = RIGHT; dx > 0.12; dx -= 0.01) {
    await setExtra([{ dx }]);
    await sleep(60);
  }
  const after = await tracked();
  check("ids survive one face moving toward the other",
    after.length === 2 && after.map(t => t.id).join() === before.join(),
    JSON.stringify(before) + " -> " + JSON.stringify(after.map(t => t.id)));
  // And they are still two separate people, not one track being dragged between
  // two positions: the second face really did move, so its centre must have.
  const moved = after.find(t => t.id === before[1]);
  check("the moved face is the one whose position changed",
    moved && Math.abs(moved.x - (0.5 + 0.12)) < 0.03, moved ? moved.x.toFixed(3) : "gone");
}

// ---- Someone leaves ----
{
  await setExtra([]);
  await sleep(800);
  const t = await tracked();
  check("a face that leaves is dropped", t.length === 1, JSON.stringify(t));
  const right = await band(0.61, 0.89);
  check("their sticker goes with them", right.frac < 0.005, "inked " + right.frac.toFixed(3));
  const left = await band(0.36, 0.64);
  check("the one who stayed keeps theirs", left.frac > 0.01, "inked " + left.frac.toFixed(3));
}

// The one who stayed must keep their identity too — a filter holding per-person
// state would otherwise reset every time somebody else walked out of shot.
{
  const kept = (await tracked())[0].id;
  await setExtra([{ dx: -0.28 }]);
  await sleep(700);
  const t = await tracked();
  check("a new arrival does not disturb who was already there",
    t.length === 2 && t.some(x => x.id === kept), "kept " + kept + " in " + JSON.stringify(t.map(x => x.id)));
  check("the newcomer is a new identity",
    t.length === 2 && t.some(x => x.id !== kept), JSON.stringify(t.map(x => x.id)));
}

// ---- The cap ----
//
// More faces than the tier will carry: the nearest ones win, because a child
// leaning into the camera matters more than someone crossing the room behind.
{
  await setExtra([
    { dx: -0.30, scale: 0.95 },
    { dx: -0.15, scale: 0.55 },
    { dx: 0.17, scale: 0.90 },
    { dx: 0.32, scale: 0.50 }
  ]);
  await sleep(1200);
  const t = await tracked();
  check("the fast tier stops at three faces", t.length === 3, JSON.stringify(t.map(x => +x.x.toFixed(2))));
  // 0.5 (scale 1), 0.20 (0.95) and 0.67 (0.90) are the three biggest; the two
  // small ones at 0.35 and 0.82 must be the ones dropped.
  const xs = t.map(x => x.x).sort((a, z) => a - z);
  check("it keeps the nearest three, not the first three reported",
    xs.length === 3 && Math.abs(xs[0] - 0.20) < 0.03 && Math.abs(xs[1] - 0.50) < 0.03 &&
    Math.abs(xs[2] - 0.67) < 0.03, JSON.stringify(xs.map(v => +v.toFixed(2))));
  check("the HUD reports the cap it is running under", (await hud()).faces === "3 / 3",
    JSON.stringify((await hud()).faces));
}

// The mesh runs a landmark pass per face, so it carries two rather than three —
// and swapping tiers has to re-cap, not keep the three it already had.
{
  await pickFilter("Puppy");
  await sleep(2000);
  const t = await tracked();
  check("the mesh tier stops at two faces", t.length === 2, JSON.stringify(t.map(x => +x.x.toFixed(2))));
  check("the HUD follows the tier's cap", (await hud()).faces === "2 / 2",
    JSON.stringify((await hud()).faces));
  const l = await band(0.10, 0.32), r = await band(0.36, 0.64);
  check("both remaining faces get ears", l.frac > 0.008 && r.frac > 0.008,
    "left " + l.frac.toFixed(3) + ", right " + r.frac.toFixed(3));
}

// ---- Filters that own the whole frame ----
//
// Skydive paints a sky rather than a sticker. Two faces must not mean two skies
// drawn over each other, nor one jumper wearing both faces: one sky, two jumpers,
// each in their own share of it.
{
  await pickFilter("Cool");            // back to the fast tier
  await sleep(400);
  await setExtra([{ dx: RIGHT }]);
  await sleep(600);
  await pickFilter("Skydive");
  await sleep(1200);

  const two = await suitStats();
  check("two faces put a jumper in each half of the sky", two.leftN > 400 && two.rightN > 400,
    "suit pixels left " + two.leftN + ", right " + two.rightN);
  // Each in their own third of the frame — the slots are at 1/3 and 2/3, and the
  // right-hand child's lean nudges theirs a little further right still.
  check("each jumper is in their own share of it",
    Math.abs(two.leftCx - 0.333) < 0.08 && Math.abs(two.rightCx - 0.70) < 0.08,
    "centroids " + two.leftCx.toFixed(2) + " and " + two.rightCx.toFixed(2));
  check("the two jumpers are drawn at a similar size",
    Math.min(two.leftN, two.rightN) / Math.max(two.leftN, two.rightN) > 0.6,
    "left " + two.leftN + ", right " + two.rightN);

  await setExtra([]);
  await sleep(900);
  const one = await suitStats();
  // Alone he goes back to the middle of the frame at full size, exactly where a
  // single skydiver has always been — the slot maths must collapse to that. He
  // straddles the centre line, so this is a question about spread rather than
  // about halves: one character is one narrow band of orange, not two.
  check("alone, there is one jumper in the middle",
    one.n > 400 && Math.abs(one.cx - 0.5) < 0.05 && one.maxX - one.minX < 0.4,
    "centroid " + one.cx.toFixed(2) + ", spans " + one.minX.toFixed(2) + "-" + one.maxX.toFixed(2));
  check("and he is bigger alone than crowded", one.n > Math.max(two.leftN, two.rightN) * 1.2,
    "solo " + one.n + " vs one of two " + Math.max(two.leftN, two.rightN));
}

// Big Head zooms each head inside its own clip, so two faces is two heads
// growing independently rather than one zoom of the pair.
{
  await pickFilter("Big Head");
  await setExtra([{ dx: RIGHT }]);
  await p.evaluate(() => { window.__face.jawOpen = 0.85; });
  await sleep(2000);
  const l = await band(0.30, 0.55), r = await band(0.60, 0.85);
  check("big head grows both heads", l.frac > 0.2 && r.frac > 0.2,
    "left " + l.frac.toFixed(2) + ", right " + r.frac.toFixed(2));
  await p.evaluate(() => { window.__face.jawOpen = 0; });
  await sleep(600);
  const shut = await band(0.10, 0.90);
  check("closed mouths draw nothing at all", shut.frac < 0.002, "inked " + shut.frac.toFixed(4));
}

await b.close();
console.log(fail ? "\n" + fail + " FAILURE(S)" : "\nmultiple faces OK");
process.exit(fail ? 1 : 0);
