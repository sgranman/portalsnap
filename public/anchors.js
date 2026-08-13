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

  // How many faces each tier will follow at once.
  //
  // Blazeface reports every face it finds in the one pass it was already making,
  // so a second and third child cost nothing but the drawing. The mesh runs a
  // separate landmark pass per face on top of a budget that is already ~57ms for
  // one, so it stops at two — and that cost only arrives when a second face is
  // actually in frame. The segmenter has no faces at all; its mask covers
  // whoever is in the picture, however many that is.
  const FACE_CAP = { fast: 3, mesh: 2, segment: 0 };

  // Inter-eye distance, the cheapest honest proxy for how close a face is. Used
  // to rank detections when more arrive than the tier will carry: the child
  // leaning into the camera should keep their ears when a passer-by wanders
  // through the back of the shot.
  function eyeSpan(a) {
    return Math.hypot(a.eyeL.x - a.eyeR.x, a.eyeL.y - a.eyeR.y);
  }

  // Every face in the result, biggest first, capped at `max`. Always an array —
  // an empty one when nobody is there, which is what "no face" now means.
  function toFaces(res, mode, max) {
    if (!res) return [];
    const cap = Math.max(1, max || 1);
    const out = [];

    if (mode === "mesh") {
      const faces = res.faceLandmarks || [];
      for (let i = 0; i < faces.length; i++) {
        const lm = faces[i];
        const a = { blendshapes: {}, dense: true };
        let whole = true;
        for (const k in MESH) {
          const p = lm[MESH[k]];
          if (!p) { whole = false; break; }
          a[k] = { x: p.x, y: p.y };
        }
        if (!whole) continue;
        // Named points rather than the raw 478: everything a filter draws
        // against goes through the same smoothing, prediction and deadband as
        // the six core anchors, and a flat mesh array could not. Fifteen extra
        // points cost nothing to ease; 478 would have to be special-cased
        // everywhere.
        for (const k in MESH_EXTRA) {
          const p = lm[MESH_EXTRA[k]];
          if (p) a[k] = { x: p.x, y: p.y };
        }
        // Blendshapes are per face and parallel to the landmark list, so face 1
        // must read index 1. Reading [0] for everyone is how one child's grin
        // would open another child's mouth.
        const bs = res.faceBlendshapes && res.faceBlendshapes[i];
        if (bs) for (const c of bs.categories) a.blendshapes[c.categoryName] = c.score;
        out.push(a);
      }
    } else {
      const dets = res.detections || [];
      for (let i = 0; i < dets.length; i++) {
        const kp = dets[i].keypoints || [];
        if (kp.length < 6) continue;
        const a = { blendshapes: {} };
        for (const k in KP) a[k] = { x: kp[KP[k]].x, y: kp[KP[k]].y };
        out.push(a);
      }
    }

    if (out.length > cap) {
      out.sort((p, q) => eyeSpan(q) - eyeSpan(p));
      out.length = cap;
    }
    return out;
  }

  // `V` is the MediaPipe namespace: the global `Vision` in classic contexts,
  // or the module namespace object on the main-thread fallback path.
  async function createDetector(V, mode, maxFaces) {
    const fileset = await V.FilesetResolver.forVisionTasks("./vendor/mediapipe/wasm");

    // A third tier, for filters that need to know where the *person* is rather
    // than where their face is. It replaces the face model rather than running
    // beside it: a background swap has no use for landmarks, and this device
    // cannot afford two models at once.
    if (mode === "segment") {
      return V.ImageSegmenter.createFromOptions(fileset, {
        baseOptions: { modelAssetPath: "./models/selfie_segmenter_landscape.tflite", delegate: "GPU" },
        runningMode: "VIDEO",
        outputCategoryMask: true,
        outputConfidenceMasks: false
      });
    }

    if (mode === "mesh") {
      return V.FaceLandmarker.createFromOptions(fileset, {
        baseOptions: { modelAssetPath: "./models/face_landmarker.task", delegate: "GPU" },
        runningMode: "VIDEO",
        // The one place the second face has to be paid for: the landmarker runs
        // a mesh pass per face. Whether *asking* for two costs anything when
        // only one is in frame is not known — it is a per-face pass, so it
        // should not, but that is reasoning rather than a measurement. Phases N
        // and O of recdiag.html exist to settle it on the device.
        numFaces: Math.max(1, maxFaces || FACE_CAP.mesh),
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

  root.Anchors = { KP, MESH, MESH_EXTRA, FACE_CAP, eyeSpan, toFaces, createDetector, project };
})(typeof self !== "undefined" ? self : this);
