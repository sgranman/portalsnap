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
let maxFaces = 1;    // how many people this tier will follow at once
let clock = 0;       // VIDEO mode requires strictly increasing timestamps

self.onmessage = async (e) => {
  const msg = e.data;

  if (msg.type === "init") {
    try {
      const t0 = performance.now();
      if (detector) { detector.close(); detector = null; }
      mode = msg.mode || "fast";
      maxFaces = msg.maxFaces || self.Anchors.FACE_CAP[mode] || 1;
      detector = await self.Anchors.createDetector(self.Vision, mode, maxFaces);
      self.postMessage({ type: "ready", mode, maxFaces, loadMs: Math.round(performance.now() - t0) });
    } catch (err) {
      self.postMessage({ type: "error", fatal: true, message: String((err && err.message) || err) });
    }
    return;
  }

  if (msg.type === "frame") {
    if (!detector) { msg.bitmap.close(); return; }
    const t0 = performance.now();

    if (mode === "segment") {
      let mask = null, mw = 0, mh = 0;
      try {
        const res = detector.segmentForVideo(msg.bitmap, (clock += 34));
        const cat = res && res.categoryMask;
        if (cat) {
          mw = cat.width; mh = cat.height;
          // getAsUint8Array is also what forces the GPU to finish: segmentForVideo
          // returns as soon as the work is queued, so timing without this reads
          // half a millisecond and means nothing. Copied because the mask is
          // closed immediately after, and the copy is what gets transferred.
          mask = new Uint8Array(cat.getAsUint8Array());
        }
        if (res && res.close) res.close();
        else if (cat && cat.close) cat.close();
      } catch (err) {
        self.postMessage({ type: "error", fatal: false, message: String((err && err.message) || err) });
      } finally {
        msg.bitmap.close();
      }
      const out = { type: "result", mask, mw, mh, inferMs: performance.now() - t0, seq: msg.seq };
      if (mask) self.postMessage(out, [mask.buffer]);
      else self.postMessage(out);
      return;
    }

    let faces = [];
    try {
      const res = detector.detectForVideo(msg.bitmap, (clock += 34));
      faces = self.Anchors.toFaces(res, mode, maxFaces);
    } catch (err) {
      self.postMessage({ type: "error", fatal: false, message: String((err && err.message) || err) });
    } finally {
      msg.bitmap.close();   // free immediately; 2GB of RAM leaves no slack
    }
    self.postMessage({ type: "result", faces, inferMs: performance.now() - t0, seq: msg.seq });
  }
};
