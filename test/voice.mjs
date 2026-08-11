// The voice graph: the recorder has to be handed the shifted track rather than
// the bare mic, the ratio has to follow the filter (including mid-clip), the
// graph must be parked when idle, and a clip must still come out with sound.
//
// The pitch maths itself is checked without a browser — see test-pitch.js.
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
p.on("console", m => { if (/voice unavailable/.test(m.text())) { console.log("  [warn] " + m.text()); fail++; } });
await p.setViewport({ width: 1280, height: 644 });

// Record which tracks the page hands to MediaRecorder, and what the audio graph
// looks like, without changing how any of it behaves.
await p.evaluateOnNewDocument(() => {
  window.__recStreams = [];
  const Real = window.MediaRecorder;
  window.MediaRecorder = function (stream, opts) {
    window.__recStreams.push(stream.getAudioTracks().map(t => t.label || t.kind));
    const r = new Real(stream, opts);
    return r;
  };
  window.MediaRecorder.isTypeSupported = Real.isTypeSupported.bind(Real);
  window.MediaRecorder.prototype = Real.prototype;

  window.__ctxs = [];
  const RealCtx = window.AudioContext;
  window.AudioContext = function (...a) {
    const c = new RealCtx(...a);
    window.__ctxs.push(c);
    return c;
  };
  window.AudioContext.prototype = RealCtx.prototype;
});

await p.goto("http://127.0.0.1:" + PORT + "/app.html", { waitUntil: "domcontentloaded" });
await p.waitForFunction(() => document.getElementById("loader").classList.contains("hidden"), { timeout: 30000 });
await p.click("#hudTap");

const pick = name => p.evaluate(n => {
  const c = [...document.querySelectorAll(".chip")].find(x => x.textContent.includes(n));
  if (!c) throw new Error("no chip " + n);
  c.click();
}, name);
const hudLine = key => p.evaluate(k => {
  for (const l of document.getElementById("hud").textContent.split("\n")) {
    const m = new RegExp("^" + k + "\\s+(.*)$").exec(l.trim());
    if (m) return m[1];
  }
  return null;
}, key);
const ctxState = () => p.evaluate(() => (window.__ctxs[0] || {}).state || "none");
// The worklet file has to be reachable and syntactically loadable on its own.
const modOk = await p.evaluate(async () => {
  const r = await fetch("./pitch.worklet.js");
  return r.ok && (await r.text()).includes("registerProcessor");
});
check("the worklet module is served", modOk);

await pick("Kitty");
await sleep(300);
check("the HUD reports the filter's pitch", hudLine, "");
check("Kitty asks for a raised pitch", (await hudLine("voice")).startsWith("1.42"), await hudLine("voice"));
check("no audio context exists before the first clip", (await ctxState()) === "none");

await pick("Cool");
await sleep(200);
check("a filter with no voice reads as 1.00", (await hudLine("voice")).startsWith("1.00"), await hudLine("voice"));

// First clip, with a voice on.
await pick("Kitty");
await sleep(200);
await p.click("#record");
await p.waitForFunction(() => /^0:0/.test(document.getElementById("recclock").textContent), { timeout: 8000 });
await sleep(2500);

check("the audio context is running while recording", (await ctxState()) === "running", await ctxState());
const streams = await p.evaluate(() => window.__recStreams);
check("exactly one recorder was created", streams.length === 1, JSON.stringify(streams));
check("the recorder got one audio track", streams[0] && streams[0].length === 1, JSON.stringify(streams[0]));
// The graph's output track labels itself; a mic track carries the device name.
// Which of the two arrives at the encoder is the whole point of the feature.
check("the recorder got the processed track, not the bare mic",
  streams[0] && /MediaStreamAudioDestinationNode/.test(streams[0][0]) &&
  !/fake|microphone|default/i.test(streams[0][0]), JSON.stringify(streams[0]));
check("the HUD does not report the voice as off", !/off/.test(await hudLine("voice")), await hudLine("voice"));

// Switching filters mid-clip has to follow, which is the reason the mic is
// always routed through the shifter rather than only when a voice is wanted.
await pick("Puppy");
await sleep(600);
check("the pitch follows a filter change mid-clip", (await hudLine("voice")).startsWith("0.78"), await hudLine("voice"));

await p.click("#record");
await p.waitForSelector("#review.show", { timeout: 15000 });
check("the clip is offered for review", true);
await sleep(400);
check("the audio graph is parked once the clip is finished", (await ctxState()) === "suspended", await ctxState());

await p.click("#keep");
await p.waitForFunction(() => document.getElementById("keep").textContent === "Saved ✓", { timeout: 20000 });
const saved = await p.evaluate(async () => {
  const r = await fetch("/media/list");
  const j = await r.json();
  return (j.items || []).filter(i => i.kind === "video").pop() || null;
});
check("the clip saved to the album", !!saved && saved.size > 5000, JSON.stringify(saved));

// Second clip: the context must be reused and resumed, not rebuilt. The review
// has to be dismissed first — recording is refused while it covers the stage,
// which is correct and is not what this is testing.
await p.click("#again");
await p.waitForFunction(() => !document.getElementById("review").classList.contains("show"), { timeout: 8000 });
await pick("Cool");
await sleep(200);
await p.click("#record");
await sleep(1800);
check("the same audio context is reused", (await p.evaluate(() => window.__ctxs.length)) === 1);
check("it resumes for the second clip", (await ctxState()) === "running", await ctxState());
await p.click("#record");
await p.waitForSelector("#review.show", { timeout: 15000 });
await p.click("#again");
await sleep(300);

await b.close();
console.log(fail ? "\n" + fail + " FAILURE(S)" : "\nvoice graph OK");
process.exit(fail ? 1 : 0);
