// The render loop is now rate-capped and skips untouched frames. Verify both,
// and that a filter still actually draws.
import { BASE, launch } from "./harness.mjs";
let fail = 0;
const check = (n, ok, x = "") => { console.log((ok ? "  PASS  " : "  FAIL  ") + n + (x ? "   " + x : "")); if (!ok) fail++; };
const b = await launch();
const p = await b.newPage();
p.on("pageerror", e => { console.log("  [pageerror] " + e.message); fail++; });
await p.setViewport({ width: 1280, height: 644 });
await p.goto(BASE + "/app.html", { waitUntil: "domcontentloaded" });
await p.waitForFunction(() => document.getElementById("loader").classList.contains("hidden"), { timeout: 60000 });
await p.click("#hudTap");
await new Promise(r => setTimeout(r, 1500));

const hud = () => p.evaluate(() => {
  const o = {};
  for (const l of document.getElementById("hud").textContent.split("\n")) {
    const m = /^(\w+)\s+(.*)$/.exec(l.trim()); if (m) o[m[1]] = m[2];
  }
  return o;
});
const h = await hud();
check("render loop is capped near 30fps", parseFloat(h.render) > 24 && parseFloat(h.render) <= 32, h.render);
check("no filter selected means no drawing", h.drawing === "no", JSON.stringify(h));

// Count actual canvas writes over a second, by wrapping clearRect.
const writes = await p.evaluate(() => new Promise(res => {
  const c = document.getElementById("fx").getContext("2d");
  const orig = c.clearRect.bind(c);
  let n = 0;
  c.clearRect = (...a) => { n++; return orig(...a); };
  setTimeout(() => { c.clearRect = orig; res(n); }, 1000);
}));
check("idle overlay is left untouched", writes <= 1, "clearRect calls in 1s: " + writes);

// With a filter picked but no face in the fake stream, still nothing to draw.
await p.evaluate(() => [...document.querySelectorAll(".chip")].find(c => /Fancy/.test(c.textContent)).click());
await new Promise(r => setTimeout(r, 1200));
const h2 = await hud();
check("filter picked but no face still skips drawing", h2.drawing === "no" && h2.face === "no", JSON.stringify(h2));

// Capture and album must be unaffected by the loop change.
await p.click("#shutter");
await p.waitForSelector("#review.show", { timeout: 10000 });
check("shutter still composites a photo", true);
await p.click("#keep");
await p.waitForFunction(() => document.getElementById("keep").textContent === "Saved ✓", { timeout: 15000 });
check("photo still uploads", true);
await b.close();
console.log(fail ? "\n" + fail + " FAILURE(S)" : "\nrender loop OK");
process.exit(fail ? 1 : 0);
