// Regression test for silent clips.
//
// A MediaStreamAudioSourceNode is collectable the moment script can no longer
// reach it, even while connected and producing sound. Held only by a local
// inside the function that built the graph, it survived a clip or two and then
// disappeared on the next collection: the graph kept running and the recordings
// came out silent. Before the fix, with a collection forced between clips, the
// saved files measured -22dB, -68dB, -inf, -inf.
//
// So this records several clips, forces a garbage collection between each, and
// decodes every saved file back to check it actually contains sound. No ffmpeg:
// the browser can decode its own AAC.
import { BASE, launch } from "./harness.mjs";
const CLIPS = Number(process.env.CLIPS || 4);

let fail = 0;
const check = (n, ok, x = "") => { console.log((ok ? "  PASS  " : "  FAIL  ") + n + (x ? "   " + x : "")); if (!ok) fail++; };
const sleep = ms => new Promise(r => setTimeout(r, ms));

const b = await launch();
const p = await b.newPage();
p.on("pageerror", e => { console.log("  [pageerror] " + e.message); fail++; });
const cdp = await p.createCDPSession();
await p.setViewport({ width: 1280, height: 644 });

await p.goto(BASE + "/app.html", { waitUntil: "domcontentloaded" });
await p.waitForFunction(() => document.getElementById("loader").classList.contains("hidden"), { timeout: 30000 });

// A filter with a voice, so the clip goes through the shifter rather than
// straight from the mic — the bare mic track was never the thing at risk.
await p.evaluate(() => [...document.querySelectorAll(".chip")].find(x => x.textContent.includes("Kitty")).click());
await sleep(300);

const existing = await p.evaluate(async () => {
  const j = await (await fetch("/media/list")).json();
  return (j.items || []).map(i => i.name);
});

for (let i = 1; i <= CLIPS; i++) {
  await p.click("#record");
  await p.waitForFunction(() => /^0:0/.test(document.getElementById("recclock").textContent), { timeout: 8000 });
  await sleep(2500);
  await p.click("#record");
  await p.waitForSelector("#review.show", { timeout: 15000 });
  await p.click("#keep");
  await p.waitForFunction(() => document.getElementById("keep").textContent === "Saved ✓", { timeout: 20000 });
  await p.click("#again");
  await p.waitForFunction(() => !document.getElementById("review").classList.contains("show"), { timeout: 8000 });
  // The whole point of the test.
  await cdp.send("HeapProfiler.collectGarbage");
  await sleep(400);
}

// Decode each new clip in a throwaway context and measure it.
const levels = await p.evaluate(async (known) => {
  const j = await (await fetch("/media/list")).json();
  const fresh = (j.items || []).filter(i => i.kind === "video" && !known.includes(i.name))
    .sort((a, b) => a.at.localeCompare(b.at));
  const ctx = new (window.AudioContext || window.webkitAudioContext)();
  const out = [];
  for (const item of fresh) {
    const buf = await (await fetch(item.url)).arrayBuffer();
    let rms = null, channels = 0;
    try {
      const audio = await ctx.decodeAudioData(buf);
      channels = audio.numberOfChannels;
      const d = audio.getChannelData(0);
      let sum = 0;
      for (let k = 0; k < d.length; k++) sum += d[k] * d[k];
      rms = Math.sqrt(sum / d.length);
    } catch (e) {
      rms = "decode failed: " + ((e && e.message) || e);
    }
    out.push({ name: item.name, channels, rms });
  }
  await ctx.close();
  return out;
}, existing);

check("every clip was saved", levels.length === CLIPS, levels.length + " of " + CLIPS);

// The fake mic's tone lands near 0.07 RMS; true silence decodes as exactly 0.
// A tenth of that is comfortably below anything real and far above nothing.
const FLOOR = 0.005;
levels.forEach((l, i) => {
  const db = typeof l.rms === "number" && l.rms > 0 ? (20 * Math.log10(l.rms)).toFixed(1) + " dB" : String(l.rms);
  check("clip " + (i + 1) + " has audible sound after a collection",
    typeof l.rms === "number" && l.rms > FLOOR, "RMS " + db + ", " + l.channels + "ch");
});

// Not just the last one: the failure was progressive, so a mean would have hidden
// it behind the first good clip.
const quietest = levels.filter(l => typeof l.rms === "number").reduce((a, l) => Math.min(a, l.rms), Infinity);
check("no clip is quieter than a fifth of the loudest",
  quietest * 5 >= levels.reduce((a, l) => Math.max(a, typeof l.rms === "number" ? l.rms : 0), 0),
  "quietest " + quietest.toFixed(4));

await b.close();
console.log(fail ? "\n" + fail + " FAILURE(S)" : "\nclips keep their sound OK");
process.exit(fail ? 1 : 0);
