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

  // Everything the dense model knows that six keypoints cannot. Only present in
  // mesh mode, so a filter that reads these must declare `needsMesh`.
  //
  // Every index below was checked against canonical_face_model.obj rather than
  // recalled, because a wrong index here is a sticker on the wrong part of a
  // face and looks exactly like a maths bug. The rank in that model is quoted
  // where it settles the question: `headTop` really is the single highest vertex
  // of 468, and the temples really are the widest.
  //
  // Side naming follows the six above: "R" is negative x in the canonical model,
  // which is what 33 and 234 are, so 127 and 103 belong with them.
  const MESH_EXTRA = {
    headTop: 10,      // highest vertex of the 468. Hats and crowns sit here.
    skullR: 103,      // hairline at the side of the skull, +0.54 face units up:
    skullL: 332,      //   where an animal's ears belong, not the cheeks.
    templeR: 127,     // widest vertices of the model, +-7.74 — true head width,
    templeL: 356,     //   measured at eye height rather than at the jaw.
    browR: 105,
    browL: 334,
    chin: 152,        // lowest vertex of the 468.
    jawR: 172,        // the wide, low corner of the jaw.
    jawL: 397,
    noseUnder: 2,     // underside of the nose, for seating a snout.
    nostrilR: 98,
    nostrilL: 327,
    lipBottom: 14,    // inner lower lip; `mouth` (13) is the inner upper lip.
    mouthR: 61,       // mouth corners.
    mouthL: 291
  };

  function toAnchors(res, mode) {
    if (!res) return null;

    if (mode === "mesh") {
      const faces = res.faceLandmarks || [];
      if (!faces.length) return null;
      const lm = faces[0];
      const a = { blendshapes: {}, dense: true };
      for (const k in MESH) {
        const p = lm[MESH[k]];
        if (!p) return null;
        a[k] = { x: p.x, y: p.y };
      }
      // Named points rather than the raw 478: everything a filter draws against
      // goes through the same smoothing, prediction and deadband as the six
      // core anchors, and a flat mesh array could not. Fifteen extra points cost
      // nothing to ease; 478 would have to be special-cased everywhere.
      for (const k in MESH_EXTRA) {
        const p = lm[MESH_EXTRA[k]];
        if (p) a[k] = { x: p.x, y: p.y };
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

  root.Anchors = { KP, MESH, MESH_EXTRA, toAnchors, createDetector, project };
})(typeof self !== "undefined" ? self : this);
