// The preview is mirrored, captures are not.
//
// A selfie view is how you expect to see yourself, so the stage stays flipped;
// a saved photo or clip should look the way the room actually looked, which is
// what every phone does. Those two facts are easy to state and impossible to
// check against Chrome's fake camera, whose test pattern is near enough
// symmetrical to hide a flip.
//
// So this feeds the browser a camera of its own: a y4m with a bright left half
// and a dark right half, written here as raw I420 — a y4m is a text header and
// three planes, and generating one costs twenty lines and no ffmpeg.
import puppeteer from "puppeteer-core";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

const CHROME = process.env.CHROME ||
  "/Users/you/.cache/puppeteer/chrome/mac_arm-150.0.7871.24/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing";
const PORT = process.env.PORT || 8099;
const BASE = "http://127.0.0.1:" + PORT;

let fail = 0;
const check = (n, ok, x = "") => { console.log((ok ? "  PASS  " : "  FAIL  ") + n + (x ? "   " + x : "")); if (!ok) fail++; };
const sleep = ms => new Promise(r => setTimeout(r, ms));

// ---- a camera with a left and a right ----
const W = 640, H = 360, FRAMES = 60;
const y4m = path.join(os.tmpdir(), "portalsnap-mirror-" + W + "x" + H + ".y4m");
{
  const head = Buffer.from("YUV4MPEG2 W" + W + " H" + H + " F30:1 Ip A1:1 C420jpeg\n", "ascii");
  const frame = Buffer.from("FRAME\n", "ascii");
  const y = Buffer.alloc(W * H);
  for (let r = 0; r < H; r++) {
    for (let c = 0; c < W; c++) y[r * W + c] = c < W / 2 ? 210 : 40;
  }
  const u = Buffer.alloc((W / 2) * (H / 2), 128);
  const v = Buffer.alloc((W / 2) * (H / 2), 128);
  const parts = [head];
  for (let i = 0; i < FRAMES; i++) parts.push(frame, y, u, v);
  fs.writeFileSync(y4m, Buffer.concat(parts));
}

const b = await puppeteer.launch({ executablePath: CHROME, headless: true,
  args: ["--use-fake-ui-for-media-stream", "--use-fake-device-for-media-stream",
         "--use-file-for-fake-video-capture=" + y4m,
         "--autoplay-policy=no-user-gesture-required", "--no-sandbox"] });
const p = await b.newPage();
p.on("pageerror", e => { console.log("  [pageerror] " + e.message); fail++; });
await p.setViewport({ width: 1280, height: 644 });
await p.goto(BASE + "/app.html", { waitUntil: "domcontentloaded" });
await p.waitForFunction(() => document.getElementById("loader").classList.contains("hidden"), { timeout: 60000 });

// The camera really is asymmetric, or nothing below means anything.
const source = await p.evaluate(() => {
  const v = document.getElementById("v");
  const c = document.createElement("canvas");
  c.width = 160; c.height = 90;
  const x = c.getContext("2d");
  x.drawImage(v, 0, 0, 160, 90);
  const mean = (x0, x1) => {
    const d = x.getImageData(x0, 0, x1 - x0, 90).data;
    let s = 0;
    for (let i = 0; i < d.length; i += 4) s += d[i];
    return s / (d.length / 4);
  };
  return { left: Math.round(mean(0, 60)), right: Math.round(mean(100, 160)) };
});
check("the test camera has a bright left and a dark right", source.left - source.right > 80,
  JSON.stringify(source));

// The stage is still a selfie view.
const flipped = await p.evaluate(() => {
  const m = getComputedStyle(document.querySelector("#stage > .mirror")).transform;
  // matrix(a, …) with a negative horizontal scale is a mirror.
  const a = /matrix\(([-\d.]+)/.exec(m);
  return { transform: m, horizontal: a ? parseFloat(a[1]) : 1 };
});
check("the preview is still mirrored", flipped.horizontal < 0, JSON.stringify(flipped));

// Same measurement on a saved capture. Unmirrored means bright stays left.
const sides = blob => p.evaluate(async src => {
  const img = await new Promise((res, rej) => {
    const i = new Image();
    i.onload = () => res(i);
    i.onerror = rej;
    i.src = src;
  });
  const c = document.createElement("canvas");
  c.width = 160; c.height = 90;
  const x = c.getContext("2d");
  x.drawImage(img, 0, 0, 160, 90);
  const mean = (x0, x1) => {
    const d = x.getImageData(x0, 0, x1 - x0, 90).data;
    let s = 0;
    for (let i = 0; i < d.length; i += 4) s += d[i];
    return s / (d.length / 4);
  };
  return { left: Math.round(mean(0, 60)), right: Math.round(mean(100, 160)) };
}, blob);

await p.click("#shutter");
await p.waitForSelector("#review.show", { timeout: 10000 });
const photo = await sides(await p.evaluate(() => document.getElementById("shot").src));
check("a saved photo is not mirrored", photo.left > photo.right + 80, JSON.stringify(photo));
await p.click("#again");
await sleep(300);

// And the same for a clip, read back out of the recorded file.
await p.click("#record");
await p.waitForFunction(() => document.getElementById("rectimer").classList.contains("show"), { timeout: 8000 });
await sleep(2500);
await p.click("#record");
await p.waitForSelector("#review.show", { timeout: 15000 });
const clipSides = await p.evaluate(async () => {
  const v = document.getElementById("clip");
  await new Promise(res => {
    if (v.readyState >= 2) return res();
    v.onloadeddata = res;
  });
  v.currentTime = 0.2;
  await new Promise(res => { v.onseeked = res; setTimeout(res, 3000); });
  const c = document.createElement("canvas");
  c.width = 160; c.height = 90;
  const x = c.getContext("2d");
  x.drawImage(v, 0, 0, 160, 90);
  const mean = (x0, x1) => {
    const d = x.getImageData(x0, 0, x1 - x0, 90).data;
    let s = 0;
    for (let i = 0; i < d.length; i += 4) s += d[i];
    return s / (d.length / 4);
  };
  return { left: Math.round(mean(0, 60)), right: Math.round(mean(100, 160)) };
});
check("a recorded clip is not mirrored", clipSides.left > clipSides.right + 80, JSON.stringify(clipSides));

// Video and overlay must stay in register. Painting a mark on the overlay and
// finding it on the same side of the saved photo tests that directly, without
// needing a face: if the composite flipped one layer and not the other, the mark
// would come back on the far side from the camera's bright half.
await p.click("#again");
await sleep(300);
const markSrc = await p.evaluate(async () => {
  const fx = document.getElementById("fx");
  const c = fx.getContext("2d");
  c.fillStyle = "#ff0000";
  c.fillRect(0, 0, fx.width * 0.25, fx.height);      // left quarter of the overlay
  document.getElementById("shutter").click();
  await new Promise(r => setTimeout(r, 1200));
  return document.getElementById("shot").src;
});
const mark = await p.evaluate(async src => {
  const img = await new Promise((res, rej) => {
    const i = new Image(); i.onload = () => res(i); i.onerror = rej; i.src = src;
  });
  const c = document.createElement("canvas");
  c.width = 160; c.height = 90;
  const x = c.getContext("2d");
  x.drawImage(img, 0, 0, 160, 90);
  const redness = (x0, x1) => {
    const d = x.getImageData(x0, 0, x1 - x0, 90).data;
    let n = 0;
    for (let i = 0; i < d.length; i += 4) if (d[i] > 150 && d[i + 1] < 90 && d[i + 2] < 90) n++;
    return Math.round(100 * n / (d.length / 4));
  };
  return { left: redness(0, 40), right: redness(120, 160) };
}, markSrc);
check("the overlay lands on the same side of the capture as the camera does",
  mark.left > 80 && mark.right === 0, JSON.stringify(mark));

await b.close();
fs.unlink(y4m, () => {});
console.log(fail ? "\n" + fail + " FAILURE(S)" : "\nmirroring OK");
process.exit(fail ? 1 : 0);
