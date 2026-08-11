// Shared by the classic worker (via importScripts) and the main-thread fallback
// (via a plain <script> tag), so both paths map results identically.
// Written as a classic script assigning a global — a module worker cannot host
// MediaPipe, and a classic worker cannot `import`.

(function (root) {
  "use strict";

  // Blazeface keypoint order is fixed by the model.
  const KP = { eyeR: 0, eyeL: 1, nose: 2, mouth: 3, earR: 4, earL: 5 };

  // Canonical FaceMesh indices chosen to match those same six anchors, so a
  // filter never learns which tracker tier produced them.
  const MESH = { eyeR: 33, eyeL: 263, nose: 1, mouth: 13, earR: 234, earL: 454 };

  function toAnchors(res, mode) {
    if (!res) return null;

    if (mode === "mesh") {
      const faces = res.faceLandmarks || [];
      if (!faces.length) return null;
      const lm = faces[0];
      const a = { blendshapes: {} };
      for (const k in MESH) {
        const p = lm[MESH[k]];
        if (!p) return null;
        a[k] = { x: p.x, y: p.y };
      }
      if (res.faceBlendshapes && res.faceBlendshapes[0]) {
        for (const c of res.faceBlendshapes[0].categories) a.blendshapes[c.categoryName] = c.score;
      }
      return a;
    }

    const dets = res.detections || [];
    if (!dets.length) return null;
    const kp = dets[0].keypoints || [];
    if (kp.length < 6) return null;
    const a = { blendshapes: {} };
    for (const k in KP) a[k] = { x: kp[KP[k]].x, y: kp[KP[k]].y };
    return a;
  }

  // `V` is the MediaPipe namespace: the global `Vision` in classic contexts,
  // or the module namespace object on the main-thread fallback path.
  async function createDetector(V, mode) {
    const fileset = await V.FilesetResolver.forVisionTasks("./vendor/mediapipe/wasm");

    if (mode === "mesh") {
      return V.FaceLandmarker.createFromOptions(fileset, {
        baseOptions: { modelAssetPath: "./models/face_landmarker.task", delegate: "GPU" },
        runningMode: "VIDEO",
        numFaces: 1,
        // Measured at ~4ms on the Portal — cheap enough to always leave on.
        outputFaceBlendshapes: true
      });
    }

    return V.FaceDetector.createFromOptions(fileset, {
      baseOptions: { modelAssetPath: "./models/blaze_face_short_range.tflite", delegate: "GPU" },
      runningMode: "VIDEO"
    });
  }

  // Project an anchor forward along its recent velocity.
  //
  // A detection describes where the face was when the frame was grabbed, and
  // it arrives grab+infer milliseconds later — ~50ms idle, ~110ms while
  // recording. Easing toward a target that stale can only ever trail the head;
  // it cannot catch up. So lead the target by the latency we can actually
  // measure. This is the half of a one-euro filter the smoothing never had.
  //
  // `clamp` is the safety rail: velocity estimated from detections 100ms apart
  // is noisy, and unclamped extrapolation would fling a hat off the head on a
  // single bad frame. Distances are normalized (fractions of frame size), so
  // a clamp of 0.05 is roughly half an inter-eye distance.
  function project(pt, v, leadMs, clamp) {
    if (!pt || !v || !leadMs) return pt;
    let dx = v.x * leadMs, dy = v.y * leadMs;
    const d = Math.hypot(dx, dy);
    if (d > clamp) { const s = clamp / d; dx *= s; dy *= s; }
    return { x: pt.x + dx, y: pt.y + dy };
  }

  root.Anchors = { KP, MESH, toAnchors, createDetector, project };
})(typeof self !== "undefined" ? self : this);
