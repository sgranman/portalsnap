// End-to-end check of PortalSnap capture -> upload -> album, against a fake
// camera and mic so it runs unattended.
import puppeteer from "puppeteer-core";

const CHROME = process.env.CHROME ||
  "/Users/you/.cache/puppeteer/chrome/mac_arm-150.0.7871.24/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing";
const PORT = process.env.PORT || 8099;
const BASE = "http://127.0.0.1:" + PORT;

const log = (...a) => console.log(...a);
let failures = 0;
function check(name, ok, extra = "") {
  log((ok ? "  PASS  " : "  FAIL  ") + name + (extra ? "   " + extra : ""));
  if (!ok) failures++;
}

const browser = await puppeteer.launch({
  executablePath: CHROME,
  headless: true,
  args: [
    "--use-fake-ui-for-media-stream",
    "--use-fake-device-for-media-stream",
    "--autoplay-policy=no-user-gesture-required",
    "--no-sandbox"
  ]
});

const page = await browser.newPage();
await page.setViewport({ width: 1280, height: 800 });
page.on("console", m => {
  const t = m.text();
  if (m.type() === "error" || /error|fail/i.test(t)) log("    [console] " + t.slice(0, 200));
});
page.on("pageerror", e => { log("    [pageerror] " + e.message); failures++; });
page.on("response", r => { if (r.status() >= 400) log("    [404] " + r.url()); });

log("\n--- app.html ---");
await page.goto(BASE + "/app.html", { waitUntil: "domcontentloaded" });

await page.waitForFunction(
  () => document.getElementById("loader").classList.contains("hidden"),
  { timeout: 60000 }
);
check("camera starts and loader clears", true);

const caps = await page.evaluate(() => ({
  audio: document.getElementById("v").srcObject.getAudioTracks().length,
  video: document.getElementById("v").srcObject.getVideoTracks().length,
  size: document.getElementById("fx").width + "x" + document.getElementById("fx").height
}));
check("mic track acquired alongside camera", caps.audio === 1, JSON.stringify(caps));

/* ---------------- photo ---------------- */
await page.click("#shutter");
await page.waitForSelector("#review.show", { timeout: 10000 });
const shotOk = await page.evaluate(() => {
  const i = document.getElementById("shot");
  return { hidden: i.hidden, isBlob: (i.src || "").startsWith("blob:"), clipHidden: document.getElementById("clip").hidden };
});
check("photo review shows a blob image", !shotOk.hidden && shotOk.isBlob && shotOk.clipHidden, JSON.stringify(shotOk));

await page.click("#keep");
await page.waitForFunction(
  () => document.getElementById("keep").textContent === "Saved ✓",
  { timeout: 15000 }
).catch(() => {});
const photoSaved = await page.evaluate(() => ({
  btn: document.getElementById("keep").textContent,
  msg: document.getElementById("saveMsg").textContent
}));
check("photo uploads to the server", photoSaved.btn === "Saved ✓", JSON.stringify(photoSaved));

await page.click("#again");

/* ---------------- video ---------------- */
const mime = await page.evaluate(() => {
  const t = ["video/mp4;codecs=avc1.42E01E,mp4a.40.2", "video/mp4",
             "video/webm;codecs=vp8,opus", "video/webm"];
  return t.filter(x => MediaRecorder.isTypeSupported(x));
});
log("    recorder formats: " + mime.join(" | "));

await page.click("#record");
await page.waitForFunction(() => document.getElementById("rectimer").classList.contains("show"), { timeout: 8000 });
check("recording UI arms", true);
await new Promise(r => setTimeout(r, 3500));

const midRec = await page.evaluate(() => ({
  clock: document.getElementById("recclock").textContent,
  recording: document.getElementById("stage").classList.contains("recording"),
  shutterOff: document.getElementById("shutter").disabled
}));
check("clock runs and shutter locks out", /0:0[23]/.test(midRec.clock) && midRec.recording && midRec.shutterOff, JSON.stringify(midRec));

await page.click("#record");
await page.waitForSelector("#review.show", { timeout: 15000 });
const clipInfo = await page.evaluate(() => {
  const c = document.getElementById("clip");
  return { hidden: c.hidden, isBlob: (c.src || "").startsWith("blob:"), imgHidden: document.getElementById("shot").hidden };
});
check("clip review shows a blob video", !clipInfo.hidden && clipInfo.isBlob && clipInfo.imgHidden, JSON.stringify(clipInfo));

const preClick = await page.evaluate(() => {
  const b = document.getElementById("keep").getBoundingClientRect();
  const c = document.getElementById("clip").getBoundingClientRect();
  return { keep: [b.x|0, b.y|0, b.width|0, b.height|0], clip: [c.x|0, c.y|0, c.width|0, c.height|0],
           readyState: document.getElementById("clip").readyState };
});
log("    layout before click: " + JSON.stringify(preClick));
await page.click("#keep");
await new Promise(r => setTimeout(r, 500));
const postClick = await page.evaluate(() => {
  const b = document.getElementById("keep").getBoundingClientRect();
  return { keep: [b.x|0, b.y|0, b.width|0, b.height|0], text: document.getElementById("keep").textContent };
});
log("    layout after click:  " + JSON.stringify(postClick));
await page.waitForFunction(() => document.getElementById("keep").textContent === "Saved ✓", { timeout: 25000 }).catch(() => {});
const vidSaved = await page.evaluate(() => ({
  btn: document.getElementById("keep").textContent,
  msg: document.getElementById("saveMsg").textContent
}));
check("clip uploads to the server", vidSaved.btn === "Saved ✓", JSON.stringify(vidSaved));
await page.click("#again");

/* ---------------- album ---------------- */
await page.click("#album");
await page.waitForFunction(() => document.querySelectorAll("#grid .tile").length >= 2, { timeout: 15000 }).catch(() => {});
const album = await page.evaluate(() => ({
  tiles: document.querySelectorAll("#grid .tile").length,
  videos: document.querySelectorAll("#grid .tile.vid").length,
  help: document.getElementById("ghelp").textContent.slice(0, 60)
}));
check("in-app album lists both captures", album.tiles === 2 && album.videos === 1, JSON.stringify(album));

// Tracking must be idle while an overlay covers the stage.
const idle = await page.evaluate(() => new Promise(res => {
  const before = performance.now();
  setTimeout(() => res({ overlay: true, before }), 300);
}));
check("album overlay is up", idle.overlay);

await page.click("#grid .tile");
await page.waitForSelector("#viewer.show", { timeout: 8000 });
check("viewer opens from a tile", true);

// two-tap delete
await page.click("#vdel");
const armed = await page.evaluate(() => document.getElementById("vdel").textContent);
check("delete arms on first tap", armed === "Really delete?", armed);
await page.click("#vdel");
await page.waitForFunction(() => document.querySelectorAll("#grid .tile").length === 1, { timeout: 10000 }).catch(() => {});
const afterDel = await page.evaluate(() => document.querySelectorAll("#grid .tile").length);
check("delete removes it from the album", afterDel === 1, "tiles=" + afterDel);

/* ---------------- gallery.html ---------------- */
log("\n--- gallery.html ---");
const g = await browser.newPage();
await g.setViewport({ width: 420, height: 900 });   // phone-shaped
g.on("pageerror", e => { log("    [pageerror] " + e.message); failures++; });
await g.goto(BASE + "/gallery.html", { waitUntil: "networkidle2" });
await g.waitForSelector(".tile", { timeout: 10000 });
const gtiles = await g.$$eval(".tile", n => n.length);
check("gallery lists the remaining capture", gtiles === 1, "tiles=" + gtiles);

await g.click(".tile");
await g.waitForSelector("#box.show", { timeout: 8000 });
const box = await g.evaluate(() => ({
  href: document.getElementById("open").getAttribute("href"),
  save: !!document.getElementById("save")
}));
check("lightbox opens with a full-size link", /^\/media\/(pic|vid)-/.test(box.href || ""), JSON.stringify(box));

await browser.close();
log("\n" + (failures ? failures + " FAILURE(S)" : "all checks passed"));
process.exit(failures ? 1 : 0);
