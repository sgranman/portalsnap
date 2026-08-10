// Face tracking off the main thread.
//
// CLASSIC worker, deliberately. MediaPipe loads its wasm via `importScripts`
// when it exists and falls back to `document.createElement("script")` when it
// does not — a module worker has neither, so it throws "document is not
// defined". A classic worker has importScripts, which is the path MediaPipe
// wants.

importScripts("./vendor/mediapipe/vision_bundle.js", "./anchors.js");

let detector = null;
let mode = "fast";   // "fast" = blazeface keypoints, "mesh" = full landmarks
let clock = 0;       // VIDEO mode requires strictly increasing timestamps

self.onmessage = async (e) => {
  const msg = e.data;

  if (msg.type === "init") {
    try {
      const t0 = performance.now();
      if (detector) { detector.close(); detector = null; }
      detector = await self.Anchors.createDetector(self.Vision, msg.mode || "fast");
      mode = msg.mode || "fast";
      self.postMessage({ type: "ready", mode, loadMs: Math.round(performance.now() - t0) });
    } catch (err) {
      self.postMessage({ type: "error", fatal: true, message: String((err && err.message) || err) });
    }
    return;
  }

  if (msg.type === "frame") {
    if (!detector) { msg.bitmap.close(); return; }
    const t0 = performance.now();
    let anchors = null;
    try {
      const res = detector.detectForVideo(msg.bitmap, (clock += 34));
      anchors = self.Anchors.toAnchors(res, mode);
    } catch (err) {
      self.postMessage({ type: "error", fatal: false, message: String((err && err.message) || err) });
    } finally {
      msg.bitmap.close();   // free immediately; 2GB of RAM leaves no slack
    }
    self.postMessage({ type: "result", anchors, inferMs: performance.now() - t0, seq: msg.seq });
  }
};
