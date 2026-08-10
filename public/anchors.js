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

  root.Anchors = { KP, MESH, toAnchors, createDetector };
})(typeof self !== "undefined" ? self : this);
