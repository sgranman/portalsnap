// recdiag.html now has twelve phases: A-H decompose the preview, I-L test the
// candidate fixes. Verify the DOM really matches what each phase claims, since a
// phase that quietly measures the wrong thing is worse than no measurement.
import puppeteer from "puppeteer-core";
const CHROME = process.env.CHROME ||
  "/Users/you/.cache/puppeteer/chrome/mac_arm-150.0.7871.24/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing";
const PORT = process.env.PORT || 8099;
let fail = 0;
const check = (n, ok, x = "") => { console.log((ok ? "  PASS  " : "  FAIL  ") + n + (x ? "   " + x : "")); if (!ok) fail++; };
const b = await puppeteer.launch({ executablePath: CHROME, headless: true,
  args: ["--use-fake-ui-for-media-stream","--use-fake-device-for-media-stream","--autoplay-policy=no-user-gesture-required","--no-sandbox"] });
const p = await b.newPage();
p.on("pageerror", e => { console.log("  [pageerror] " + e.message); fail++; });
await p.setViewport({ width: 1280, height: 644 });
await p.goto("http://127.0.0.1:" + PORT + "/recdiag.html", { waitUntil: "domcontentloaded" });
await p.click("#go");
console.log("  running fifteen phases (~4 min)…");

const seen = [];
const watch = setInterval(async () => {
  try {
    const s = await p.evaluate(() => {
      const st = document.getElementById("stage"), fx = document.getElementById("fx");
      const rc = document.getElementById("rc"), v = document.getElementById("v");
      const txt = document.getElementById("status").textContent || "";
      const m = /measuring/.test(txt) ? /\) ([A-M]) /.exec(txt) : null;
      return { id: m && m[1], on: st.classList.contains("on"), mir: st.classList.contains("mir"),
               fxOff: fx.classList.contains("off"), fxw: fx.width,
               rcOff: rc.classList.contains("off"), vOff: v.classList.contains("off"),
               uiHidden: document.getElementById("ui").classList.contains("running") };
    });
    if (s.id) seen.push(s);
  } catch (e) {}
}, 700);

await p.waitForFunction(() => /Sent|Couldn't send|Failed/.test(document.getElementById("status").textContent), { timeout: 400000 });
clearInterval(watch);

const st = await p.evaluate(() => document.getElementById("status").textContent);
check("report reached the server", /^Sent/.test(st), st);

const data = await p.evaluate(() => JSON.parse(document.getElementById("raw").textContent));
const row = id => data.rows.find(r => r.id === id) || {};
check("all fifteen phases reported", data.rows.length === 15, "rows=" + data.rows.length);
check("every phase produced samples", data.rows.every(r => r.samples > 0),
  JSON.stringify(data.rows.map(r => r.id + ":" + r.samples)));
check("no phase errors", data.rows.every(r => !r.errors.length),
  JSON.stringify(data.rows.flatMap(r => r.errors)));

const at = id => seen.filter(s => s.id === id);
check("phase A hides the stage", at("A").length && at("A").every(s => !s.on), JSON.stringify(at("A")[0]));
check("phase B shows it unmirrored", at("B").length && at("B").every(s => s.on && !s.mir), JSON.stringify(at("B")[0]));
check("phase C mirrors it", at("C").length && at("C").every(s => s.on && s.mir), JSON.stringify(at("C")[0]));
check("phase D shows the overlay layer", at("D").length && at("D").every(s => !s.fxOff), JSON.stringify(at("D")[0]));
check("phase G halves the overlay canvas", at("G").length && at("G").every(s => s.fxw === 640), JSON.stringify(at("G")[0]));
check("phase I both caps and halves", row("I").hz === 30 && at("I").length && at("I").every(s => s.fxw === 640),
  JSON.stringify(at("I")[0]) + " hz=" + row("I").hz);
check("phase J records its 15fps cap", row("J").hz === 15);
check("phase K caps the composite rate", row("K").compHz === 15 && row("K").composite);
check("phase L displays the composite, not the video",
  at("L").length && at("L").every(s => s.vOff && !s.rcOff && s.fxOff), JSON.stringify(at("L")[0]));
check("phase L is not double-mirrored", at("L").length && at("L").every(s => !s.mir));
check("the video is visible again in every other phase",
  seen.filter(s => s.id !== "L").every(s => !s.vOff));
check("UI is hidden while measuring", seen.length && seen.every(s => s.uiHidden));
check("only H, K, L and M composite", data.rows.filter(r => r.composite).map(r => r.id).join("") === "HKLM",
  data.rows.filter(r => r.composite).map(r => r.id).join(""));
check("every recording phase negotiated a codec", ["H","K","L","M"].every(id => !!row(id).mime), row("H").mime);
check("a capped composite really composites less", row("K").compN > 0 && row("K").compN < row("H").compN * 0.8,
  "H=" + row("H").compN + " K=" + row("K").compN);
check("L reports how many frames it composited", typeof row("L").compN === "number", "L compN=" + row("L").compN);
check("M caps the composite at the rate the app ships", row("M").compHz === 20 && row("M").compN > row("K").compN,
  "K=" + row("K").compN + " M=" + row("M").compN);
// N and O price the second face on the tier that pays for it. They are only
// worth reading against each other, so each must really be the tracker it claims
// — a mesh row that silently ran blazeface would look like a free second face.
check("phases N and O run the mesh tier", row("N").tracker === "mesh" && row("O").tracker === "mesh",
  row("N").tracker + " / " + row("O").tracker);
check("N asks for one face and O for two", row("N").maxFaces === 1 && row("O").maxFaces === 2,
  row("N").maxFaces + " / " + row("O").maxFaces);
check("every other phase is back on the fast tier",
  data.rows.filter(r => r.id !== "N" && r.id !== "O").every(r => r.tracker === "fast"),
  JSON.stringify(data.rows.map(r => r.id + ":" + r.tracker)));

check("verdict names a dominant cost", /Dominant preview cost/.test(data.verdict || ""));
check("verdict reads the rate curve", /Redraw rate/.test(data.verdict || ""));
check("verdict judges the recording variations", /^K \(/m.test(data.verdict || ""));

console.log("\n  " + "config".padEnd(42) + "p50  p95  detect  compN");
for (const r of data.rows)
  console.log("  " + (r.id + " " + r.name).padEnd(42) + String(r.inferP50).padEnd(5) +
    String(r.inferP95).padEnd(5) + String(r.detectFps).padEnd(8) + (r.compN || ""));
console.log("\n" + data.verdict);
await b.close();
console.log(fail ? "\n" + fail + " FAILURE(S)" : "\npreview diagnostic OK");
process.exit(fail ? 1 : 0);
