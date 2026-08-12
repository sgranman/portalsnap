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

// This one counts album tiles, so it needs an empty album. Every other browser
// test in here saves a photo, and running them in sequence used to make this
// fail with two tiles where it expected one — twice diagnosed as an app bug
// before being recognised as the harness leaving its litter behind. Clear it
// through the API rather than with rm: the server is live and holds `.part`
// files mid-upload.
{
  const list = await (await fetch(BASE + "/media/list")).json();
  for (const item of list.items || []) {
    await fetch(BASE + "/media/" + item.name, { method: "DELETE" });
  }
  if ((list.items || []).length) log("  (cleared " + list.items.length + " leftover item(s) from the album)");
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

/* ---------------- routing ---------------- */
// The root serves the app. It served the capability probe until the app was
// worth opening directly, and an old /index.html bookmark should not break.
{
  const titleOf = async u => {
    const r = await fetch(BASE + u);
    const m = /<title>([^<]*)<\/title>/.exec(await r.text());
    return { status: r.status, title: m ? m[1] : null };
  };
  const root = await titleOf("/");
  check("the root serves the app", root.status === 200 && /^PortalSnap$/.test(root.title || ""), JSON.stringify(root));
  const idx = await titleOf("/index.html");
  check("an old /index.html bookmark lands on the app too", idx.title === root.title, JSON.stringify(idx));
  const probe = await titleOf("/probe.html");
  check("the probe is still reachable", probe.status === 200 && /Probe/i.test(probe.title || ""), JSON.stringify(probe));
  const missing = await fetch(BASE + "/definitely-not-here.html");
  check("a missing page is still a 404", missing.status === 404, "status " + missing.status);
  const escape = await fetch(BASE + "/..%2fserver.js", { redirect: "manual" });
  check("path traversal is still refused", escape.status === 403 || escape.status === 404, "status " + escape.status);
}

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

// The preview frame is grabbed from the composited canvas as the recording
// stops and uploaded with the clip, so the album never has to decode video to
// show a thumbnail.
const listed = await page.evaluate(async () => (await (await fetch("/media/list")).json()).items);
const clip = listed.find(i => i.kind === "video");
check("the clip was saved with a preview frame", !!(clip && clip.poster), JSON.stringify(clip));
if (clip && clip.poster) {
  const posterOk = await page.evaluate(async u => {
    const r = await fetch(u);
    return { status: r.status, type: r.headers.get("content-type"), size: (await r.blob()).size };
  }, clip.poster);
  check("the preview is a real jpeg", posterOk.status === 200 && /jpeg/.test(posterOk.type) && posterOk.size > 1000,
    JSON.stringify(posterOk));
}
check("previews are not listed as album entries of their own",
  listed.filter(i => /^vid-.*\.jpg$/.test(i.name)).length === 0,
  JSON.stringify(listed.map(i => i.name)));
await page.click("#again");

/* ---------------- album ---------------- */
await page.click("#album");
await page.waitForFunction(() => document.querySelectorAll("#grid .tile").length >= 2, { timeout: 15000 }).catch(() => {});
const album = await page.evaluate(() => ({
  tiles: document.querySelectorAll("#grid .tile").length,
  // A clip with a preview frame is an image tile wearing a play badge; only one
  // without a preview falls back to the bare `.vid` marker.
  videos: document.querySelectorAll("#grid .tile .play").length,
  bare: document.querySelectorAll("#grid .tile.vid").length,
  thumbs: document.querySelectorAll("#grid .tile img").length,
  help: document.getElementById("ghelp").textContent.slice(0, 60)
}));
check("in-app album lists both captures", album.tiles === 2 && album.videos === 1, JSON.stringify(album));
check("the clip shows a preview rather than a placeholder",
  album.bare === 0 && album.thumbs === 2, JSON.stringify(album));

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

// A deleted clip must take its preview with it, or the album quietly fills up
// with orphans nobody can see or remove. The tile deleted just above was the
// clip, so its preview should have gone with it.
//
// Asked from Node rather than from the page: media is served `immutable`, so the
// browser answers 200 out of its own cache long after the file is gone. That is
// correct caching and a useless test.
if (clip && clip.poster) {
  const orphan = await fetch(BASE + clip.poster, { cache: "no-store" });
  check("deleting a clip removes its preview too", orphan.status === 404, "poster fetch " + orphan.status);
}

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
