// Album layout, at a size that actually overflows.
//
// The in-app album laid its tiles on top of one another, cascading down each
// column like a fanned deck. It only did it once there were enough snaps to
// scroll: a tile's height comes from its aspect-ratio, which depends on the
// column width, which depends on whether a scrollbar is there — and when the
// container really overflows, Chrome resolves that circularity by leaving the
// ratio out of the row's intrinsic size. Rows collapsed to the caption's line
// box, 14px under a 135px tile.
//
// Eight snaps looked perfect and ninety-six were unusable, so this seeds enough
// to scroll and then asserts that no tile overlaps the one below it.
import { BASE, launch, api } from "./harness.mjs";
const SEED = 40;

let fail = 0;
const check = (n, ok, x = "") => { console.log((ok ? "  PASS  " : "  FAIL  ") + n + (x ? "   " + x : "")); if (!ok) fail++; };
const sleep = ms => new Promise(r => setTimeout(r, ms));

// A 2x2 PNG is enough: this is about boxes, not pictures.
const PIXEL = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAFElEQVR4AWP4z8Dwn4GBgYGJgYEBAA4AAv8d0EUAAAAASUVORK5CYII=",
  "base64");

const before = (await (await api("/media/list")).json()).items || [];
const mine = [];
for (let i = 0; i < SEED; i++) {
  const r = await api("/media?ext=png", {
    method: "POST", headers: { "Content-Type": "image/png" }, body: PIXEL
  });
  const j = await r.json();
  if (j.name) mine.push(j.name);
}
check("seeded enough snaps to make the album scroll", mine.length === SEED, mine.length + " of " + SEED);

const b = await launch();

try {
  // The Portal's viewport with browser chrome, and again in full screen.
  for (const [label, w, h] of [["windowed", 1280, 644], ["full screen", 1280, 800]]) {
    const p = await b.newPage();
    p.on("pageerror", e => { console.log("  [pageerror] " + e.message); fail++; });
    await p.setViewport({ width: w, height: h });
    await p.goto(BASE + "/app.html", { waitUntil: "domcontentloaded" });
    await p.waitForFunction(() => document.getElementById("loader").classList.contains("hidden"), { timeout: 60000 });
    await p.click("#album");
    await p.waitForFunction(n => document.querySelectorAll("#grid .tile").length >= n, { timeout: 20000 }, SEED);
    await sleep(800);

    const geo = await p.evaluate(() => {
      const g = document.getElementById("grid");
      const t = [...document.querySelectorAll("#grid .tile")].map(e => {
        const r = e.getBoundingClientRect();
        return { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) };
      });
      return { tiles: t, scrolls: g.scrollHeight > g.clientHeight + 4, clientH: g.clientHeight, scrollH: g.scrollHeight };
    });

    check(label + ": the album actually overflows, or this proves nothing", geo.scrolls,
      geo.scrollH + " > " + geo.clientH);

    // Columns come from the x positions; within one, no tile may start before
    // the previous one ends.
    const cols = {};
    for (const t of geo.tiles) (cols[t.x] = cols[t.x] || []).push(t);
    let worst = null;
    for (const x of Object.keys(cols)) {
      const col = cols[x].sort((a, b) => a.y - b.y);
      for (let i = 1; i < col.length; i++) {
        const overlap = (col[i - 1].y + col[i - 1].h) - col[i].y;
        if (!worst || overlap > worst.overlap) worst = { overlap, x, y: col[i].y, h: col[i].h };
      }
    }
    check(label + ": no tile overlaps the one below it", worst && worst.overlap <= 0,
      worst ? "worst overlap " + worst.overlap + "px on a " + worst.h + "px tile" : "only one row");

    // And the tiles keep their shape rather than being squashed into strips.
    const squashed = geo.tiles.filter(t => t.h < t.w * 0.4);
    check(label + ": tiles keep their 16:9 shape", squashed.length === 0,
      squashed.length + " squashed; first tile " + JSON.stringify(geo.tiles[0]));

    await p.close();
  }

  // Narrow: two columns, not one full-width stack.
  const p = await b.newPage();
  await p.setViewport({ width: 390, height: 780 });
  await p.goto(BASE + "/gallery.html", { waitUntil: "domcontentloaded" });
  await p.waitForFunction(n => document.querySelectorAll(".tile").length >= n, { timeout: 20000 }, SEED);
  await sleep(500);
  const phone = await p.evaluate(() => {
    const t = [...document.querySelectorAll(".tile")].slice(0, 6).map(e => Math.round(e.getBoundingClientRect().x));
    return { columns: new Set(t).size };
  });
  check("a phone gets two columns, not one long stack", phone.columns === 2, phone.columns + " columns");
  await p.close();
} finally {
  await b.close();
  for (const n of mine) await api("/media/" + n, { method: "DELETE" });
  const after = (await (await api("/media/list")).json()).items || [];
  check("the album is left as it was found", after.length === before.length,
    before.length + " before, " + after.length + " after");
}

console.log(fail ? "\n" + fail + " FAILURE(S)" : "\nalbum layout OK");
process.exit(fail ? 1 : 0);
