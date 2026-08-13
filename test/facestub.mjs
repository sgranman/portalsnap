// The tracker, replaced by one the test drives.
//
// Chrome's fake camera shows a rolling test pattern with no face in it, so
// MediaPipe detects nothing and the whole drawing path stays unreached — which
// is how a filter regression once shipped with a green suite. This stub speaks
// `tracker.worker.js`'s three messages instead (init -> ready, frame -> result)
// and returns synthetic faces on demand: still, moving, gone, or several at once.
//
// Shared by `filters.mjs` and `multiface.mjs` so there is one contract to keep in
// step with the worker rather than two copies drifting apart.
//
// What the test drives, once installed:
//
//   window.__face          { mode: "still" | "move" | "gone", jitterPx, dense, jawOpen }
//   window.__extra         [{ dx, dy, scale }] further faces beside the first
//   window.__at(k, off)    where landmark k lands in the frame, for fit assertions
//   window.__facesList()   what the worker would post this frame
//
export function installFaceStub(page) {
  return page.evaluateOnNewDocument(() => {
    // The face is MediaPipe's own canonical model, orthographically projected —
    // the geometry the tracker was trained to report, so a filter that fits this
    // fits a person. Twenty-two of its 468 vertices, taken from
    // canonical_face_model.obj (CC-BY 4.0), in canonical units: x right, y up,
    // z out of the face.
    const CANON = {
      eyeR: [-4.446, 2.664], eyeL: [4.446, 2.664],
      nose: [0.000, -1.127], mouth: [0.000, -3.994],
      earR: [-7.664, 0.673], earL: [7.664, 0.673],
      headTop: [0.000, 8.262], skullR: [-5.133, 7.486], skullL: [5.133, 7.486],
      templeR: [-7.743, 2.365], templeL: [7.743, 2.365],
      browR: [-3.987, 5.109], browL: [3.987, 5.109],
      chin: [0.000, -9.403], jawR: [-5.941, -6.224], jawL: [5.941, -6.224],
      noseUnder: [0.000, -2.089], nostrilR: [-1.406, -1.714], nostrilL: [1.406, -1.714],
      lipBottom: [0.000, -4.542], mouthR: [-2.456, -4.343], mouthL: [2.456, -4.343]
    };
    const CORE = ["eyeR", "eyeL", "nose", "mouth", "earR", "earL"];
    const SCALE = 18, CXP = 640, CYP = 331, W = 1280, H = 720;

    // Where a landmark lands in the frame, for the fit assertions to compare
    // against. `off` places a second or third person: `dx`/`dy` shift them across
    // the frame, `scale` makes them nearer or further away.
    window.__at = (k, off) => {
      const o = off || {};
      const s = SCALE * (o.scale || 1);
      return {
        x: (CXP + CANON[k][0] * s) / W + (o.dx || 0),
        y: (CYP - CANON[k][1] * s) / H + (o.dy || 0)
      };
    };

    // `jitterPx` reproduces the thing a fake camera cannot: a real tracker never
    // reports the same point twice, so "still" in the app means "moving by the
    // noise floor". A test with a perfectly motionless face passes on a threshold
    // far too tight to ever fire on the device.
    window.__face = { mode: "still", phase: 0, jitterPx: 0, dense: true, jawOpen: 0.45 };

    // Everyone past the first. Empty is the single-face case every earlier test
    // was written against, and it must stay exactly that.
    window.__extra = [];

    window.__anchors = (off) => {
      const f = window.__face;
      if (f.mode === "gone") return null;
      // A drift big enough to be visible, applied only in "move".
      const d = f.mode === "move" ? Math.sin(f.phase) * 0.06 : 0;
      const n = () => (f.jitterPx ? (Math.random() - 0.5) * 2 * f.jitterPx / 1280 : 0);
      const a = { blendshapes: f.dense ? { jawOpen: f.jawOpen } : {}, dense: !!f.dense };
      for (const k in CANON) {
        if (!f.dense && CORE.indexOf(k) < 0) continue;
        const p = window.__at(k, off);
        a[k] = { x: p.x + d + n(), y: p.y + n() };
      }
      return a;
    };

    // What the worker would post: every face this frame, biggest first. The
    // phase advances once per list rather than once per face, so two faces move
    // together instead of one lagging the other by a frame.
    window.__facesList = () => {
      if (window.__face.mode === "gone") return [];
      if (window.__face.mode === "move") window.__face.phase += 0.25;
      const offs = [{ dx: 0, dy: 0 }].concat(window.__extra);
      return offs.map(o => window.__anchors(o));
    };

    const span = a => Math.hypot(a.eyeL.x - a.eyeR.x, a.eyeL.y - a.eyeR.y);

    window.Worker = class {
      postMessage(m) {
        if (m.type === "init") {
          this._max = m.maxFaces || 1;
          setTimeout(() => this.onmessage && this.onmessage({
            data: { type: "ready", mode: m.mode, maxFaces: this._max, loadMs: 1 }
          }), 5);
          return;
        }
        if (m.type === "frame") {
          if (m.bitmap && m.bitmap.close) m.bitmap.close();
          // Capped here rather than left to the app, because the real tracker is
          // where the cap lives: `toFaces` keeps the nearest faces and drops the
          // rest. A test that let the app do the trimming would be testing the
          // wrong half of the rule.
          const faces = window.__facesList().sort((a, b) => span(b) - span(a));
          if (faces.length > this._max) faces.length = this._max;
          setTimeout(() => this.onmessage && this.onmessage({
            data: { type: "result", faces, inferMs: 12, seq: m.seq }
          }), 12);
        }
      }
      terminate() {}
    };
  });
}
