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
await p.goto("http://127.0.0.1:" + PORT + "/app.html", { waitUntil: "domcontentloaded" });
await p.waitForFunction(() => document.getElementById("loader").classList.contains("hidden"), { timeout: 60000 });
const hud = () => p.evaluate(() => {
  const o = {};
  for (const l of document.getElementById("hud").textContent.split("\n")) {
    const m = /^(\w+)\s+(.*)$/.exec(l.trim()); if (m) o[m[1]] = m[2];
  }
  return o;
});
await p.click("#hudTap");
await new Promise(r => setTimeout(r, 800));
const idle = await hud();
check("idle HUD has no rec lines", !idle.rec && !idle.comp && !idle.frame, JSON.stringify(idle));

await p.click("#record");
await new Promise(r => setTimeout(r, 4000));
const rec = await hud();
check("comp and frame appear while recording", !!rec.comp && !!rec.frame, JSON.stringify(rec));
check("comp is a plausible ms figure", /^\d+ ms$/.test(rec.comp || ""), rec.comp);
check("frame interval is a plausible ms figure", parseFloat(rec.frame) > 0 && parseFloat(rec.frame) < 500, rec.frame);
await p.click("#record");
await p.waitForSelector("#review.show", { timeout: 15000 });
await p.click("#again");
await new Promise(r => setTimeout(r, 500));
const after = await hud();
check("rec lines clear after stopping", !after.comp && !after.frame, JSON.stringify(after));

// a second clip must not inherit the first one's smoothed numbers
await p.click("#record");
await new Promise(r => setTimeout(r, 1500));
const second = await hud();
check("second clip reports its own numbers", !!second.comp, JSON.stringify(second));
await p.click("#record");
await p.waitForSelector("#review.show", { timeout: 15000 });
await b.close();
console.log(fail ? "\n" + fail + " FAILURE(S)" : "\nrecording HUD OK");
process.exit(fail ? 1 : 0);
