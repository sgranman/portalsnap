// Filters are pure draw functions: (ctx, face, t) -> void.
//
// They draw in FACE SPACE: origin between the eyes, +x along the eye line,
// one unit = inter-eye distance. Scale and rotation therefore come free.
//
// Anchor to measured points (face.earR/earL, face.nose, face.mouth,
// face.headTopY), never to invented constants. Measured points already move
// when the head turns, so yaw is handled without any extra maths.

const TAU = Math.PI * 2;

function inFaceSpace(ctx, face, fn) {
  ctx.save();
  ctx.translate(face.cx, face.cy);
  ctx.rotate(face.angle);
  ctx.scale(face.eyeDist, face.eyeDist);
  // Flat art on a turning head should narrow slightly, or it reads as a decal.
  ctx.scale(1 - Math.abs(face.yaw) * 0.22, 1);
  fn(ctx);
  ctx.restore();
}

function ellipse(ctx, x, y, rx, ry, rot) {
  ctx.beginPath();
  if (ctx.ellipse) ctx.ellipse(x, y, Math.abs(rx), Math.abs(ry), rot || 0, 0, TAU);
  else ctx.arc(x, y, Math.abs(rx), 0, TAU);
  ctx.closePath();
}

// Everything sits over live video, so a soft shadow is what separates art from
// background. Cheap here: a handful of shapes per frame.
function lift(ctx, face, k = 0.06) {
  ctx.shadowColor = "rgba(0,0,0,.45)";
  // shadowBlur is applied in device pixels and ignores the transform, so it has
  // to be converted; offsets ARE transformed, so those stay in face units.
  ctx.shadowBlur = k * face.eyeDist;
  ctx.shadowOffsetY = k * 0.3;
}
function unlift(ctx) {
  ctx.shadowColor = "transparent";
  ctx.shadowBlur = 0;
  ctx.shadowOffsetY = 0;
}

// Face space back out to pixels — the inverse of what buildFace did. Filters
// that sample the video need it, because the video is in pixels and everything
// else here is in inter-eye units.
function toPixels(face, p) {
  const c = Math.cos(face.angle), s = Math.sin(face.angle);
  return {
    x: face.cx + (p.x * c - p.y * s) * face.eyeDist,
    y: face.cy + (p.x * s + p.y * c) * face.eyeDist
  };
}

// The whole head, in pixels: centre, and half-extents along the face's own axes.
// Falls back to proportions of the eye distance when the dense model is absent.
function headBox(face) {
  const topY = face.headTop ? face.headTop.y : -0.63;
  const botY = face.chin ? face.chin.y : 1.36;
  const centre = toPixels(face, { x: 0, y: (topY + botY) / 2 });
  return {
    x: centre.x,
    y: centre.y,
    halfW: (face.headSpan / 2) * face.eyeDist * 1.12,
    halfH: ((botY - topY) / 2) * face.eyeDist * 1.12
  };
}

// Where an animal ear attaches to a head.
//
// `face.earR/earL` are the *cheek* silhouette — checked against the canonical
// model, they sit 0.22 face units BELOW the eye line, while the hairline is 0.54
// above. Ears hung off those points grew out of the jaw, which is most of why
// the puppy was the worst-fitting filter in the set.
//
// The hairline alone is too narrow, though: it is at +-0.58 face units where the
// temples are at +-0.87. Real ears attach at the side of the skull, so this
// blends the two — mostly the hairline's height, mostly the temple's width.
// `pull` draws the attachment in toward the middle of the head: upright ears
// want their base on the skull, floppy ones hang off the side of it.
function earPoints(face, pull) {
  if (!face.skullR || !face.templeR) return [face.earR, face.earL];
  const k = 1 - (pull || 0);
  const mix = (s, t) => ({
    x: (t.x * 0.85 + s.x * 0.15) * k,
    y: s.y * 0.95 + t.y * 0.05
  });
  return [mix(face.skullR, face.templeR), mix(face.skullL, face.templeL)];
}

// Run fn once per ear, with the origin on that ear and +x pointing outward,
// so a single drawing works mirrored on both sides.
function perEar(ctx, face, fn, pull) {
  const pair = earPoints(face, pull);
  for (const ear of pair) {
    const out = ear.x < 0 ? -1 : 1;
    ctx.save();
    ctx.translate(ear.x, ear.y);
    ctx.scale(out, 1);
    fn(ctx, out);
    ctx.restore();
  }
}

/* ------------------------------- Dog ------------------------------- */

const dog = {
  // Mesh tier: the tongue keys off jawOpen, and blendshapes exist only there.
  // Kitty, Royal and Big Head are on it too now.
  id: "dog", name: "Puppy", emoji: "🐶", needsMesh: true, voice: 0.78,
  draw(ctx, face, t) {
    inFaceSpace(ctx, face, c => {
      const S = face.headSpan;
      const sway = Math.sin(t / 500) * 0.05;

      // Radii, not diameters — `ellipse` takes radii, and these were written as
      // though it took widths, so every ear was drawn twice the intended size.
      // Rendered against the canonical face, each one stood taller than the whole
      // head. A floppy ear hangs below where it attaches, which is what the
      // downward centre offset is for.
      lift(c, face, 0.05);
      perEar(c, face, (cc) => {
        cc.rotate(0.30 + sway);
        const w = S * 0.115, h = S * 0.235;
        cc.fillStyle = "#7d4f24";
        ellipse(cc, w * 0.25, h * 0.82, w, h);
        cc.fill();
        cc.fillStyle = "#5a3517";
        ellipse(cc, w * 0.30, h * 0.88, w * 0.55, h * 0.72);
        cc.fill();
      });
      unlift(c);

      // Snout seated on measured points rather than offset from the nose tip by
      // a constant: the muzzle spans the nostrils and reaches down to the upper
      // lip, both of which the dense model reports.
      const nx = face.nose.x;
      const snoutTop = face.nose.y;
      const snoutBottom = face.mouth ? face.mouth.y : face.nose.y + 0.3;
      const ny = (snoutTop + snoutBottom) / 2;
      const halfW = face.nostrilR && face.nostrilL
        ? Math.max(S * 0.14, Math.abs(face.nostrilL.x - face.nostrilR.x) * 0.95)
        : S * 0.20;
      const halfH = Math.max(S * 0.10, (snoutBottom - snoutTop) * 0.80);

      lift(c, face, 0.05);
      c.fillStyle = "#d69b5c";
      ellipse(c, nx, ny, halfW, halfH);
      c.fill();
      unlift(c);

      c.fillStyle = "#f0e0cc";
      ellipse(c, nx, ny + halfH * 0.28, halfW * 0.66, halfH * 0.6);
      c.fill();

      // Nose leather on the nose tip itself, sized from the nostril span rather
      // than from head width — noses do not scale with skulls.
      c.fillStyle = "#26190f";
      c.beginPath();
      const nw = halfW * 0.40, nh = halfH * 0.34;
      const lx = nx, ly = snoutTop + nh * 0.85;
      c.moveTo(lx - nw, ly - nh * 0.5);
      c.quadraticCurveTo(lx, ly - nh * 1.25, lx + nw, ly - nh * 0.5);
      c.quadraticCurveTo(lx + nw * 0.8, ly + nh * 0.95, lx, ly + nh * 1.1);
      c.quadraticCurveTo(lx - nw * 0.8, ly + nh * 0.95, lx - nw, ly - nh * 0.5);
      c.closePath();
      c.fill();

      c.fillStyle = "rgba(255,255,255,.5)";
      ellipse(c, lx - nw * 0.35, ly - nh * 0.5, nw * 0.22, nh * 0.16);
      c.fill();

      // The tongue hangs from the real lower lip and is as wide as the real
      // mouth, so it lands in an open mouth instead of near one.
      const open = face.blendshapes.jawOpen || 0;
      if (open > 0.10) {
        const my = face.lipBottom ? face.lipBottom.y : face.mouth.y;
        const mw = face.mouthR && face.mouthL
          ? Math.abs(face.mouthL.x - face.mouthR.x) * 0.34
          : S * 0.11;
        const len = mw * (1.1 + open * 2.2);
        c.fillStyle = "#ef6f8e";
        c.beginPath();
        c.moveTo(nx - mw, my);
        c.lineTo(nx + mw, my);
        c.quadraticCurveTo(nx + mw * 1.15, my + len, nx, my + len);
        c.quadraticCurveTo(nx - mw * 1.15, my + len, nx - mw, my);
        c.closePath();
        c.fill();
        c.strokeStyle = "rgba(190,60,90,.75)";
        c.lineWidth = 0.018;
        c.beginPath();
        c.moveTo(nx, my + len * 0.25);
        c.lineTo(nx, my + len * 0.8);
        c.stroke();
      }
    });
  }
};

/* ------------------------------- Cat ------------------------------- */

const cat = {
  // Mesh tier for the ears: they belong on the skull, and six keypoints cannot
  // say where that is.
  id: "cat", name: "Kitty", emoji: "🐱", needsMesh: true, voice: 1.42,
  draw(ctx, face) {
    inFaceSpace(ctx, face, c => {
      const S = face.headSpan;

      // Ear triangles sized against the head: each is a third of its width and
      // rises a quarter of it. They were half the head wide and ran off the top
      // of the picture.
      lift(c, face, 0.05);
      perEar(c, face, (cc) => {
        cc.rotate(0.12);
        const w = S * 0.115, h = S * 0.30;
        cc.fillStyle = "#55555f";
        cc.beginPath();
        cc.moveTo(-w, 0.06); cc.lineTo(w * 0.35, -h); cc.lineTo(w * 1.1, 0.02);
        cc.closePath(); cc.fill();
        cc.fillStyle = "#f4a6ba";
        cc.beginPath();
        cc.moveTo(-w * 0.45, 0.0); cc.lineTo(w * 0.33, -h * 0.62); cc.lineTo(w * 0.68, 0.0);
        cc.closePath(); cc.fill();
      }, 0.22);
      unlift(c);

      const nx = face.nose.x, ny = face.nose.y;
      c.fillStyle = "#f4a6ba";
      c.beginPath();
      c.moveTo(nx - S * 0.09, ny - S * 0.04);
      c.lineTo(nx + S * 0.09, ny - S * 0.04);
      c.quadraticCurveTo(nx, ny + S * 0.10, nx - S * 0.09, ny - S * 0.04);
      c.closePath(); c.fill();

      // Whiskers stop just past the cheek instead of a third of a head beyond
      // it. `reach` is the real cheek half-width where the dense model gives it.
      const reach = face.earL ? Math.abs(face.earL.x - face.earR.x) * 0.5 : S * 0.5;
      c.strokeStyle = "rgba(255,255,255,.94)";
      c.lineWidth = 0.024;
      c.lineCap = "round";
      lift(c, face, 0.03);
      for (const side of [-1, 1]) {
        for (let i = 0; i < 3; i++) {
          const y = ny + S * (0.02 + i * 0.075);
          c.beginPath();
          c.moveTo(nx + side * S * 0.12, y);
          c.quadraticCurveTo(nx + side * reach * 0.60, y - S * (0.05 - i * 0.03),
                             nx + side * reach * 1.02, y - S * (0.10 - i * 0.07));
          c.stroke();
        }
      }
      unlift(c);
    });
  }
};

/* ---------------------------- Sunglasses ---------------------------- */

const shades = {
  id: "shades", name: "Cool", emoji: "😎", needsMesh: false,
  draw(ctx, face) {
    inFaceSpace(ctx, face, c => {
      const S = face.earSpan;
      const lensW = S * 0.34, lensH = S * 0.26, gap = S * 0.09;

      lift(c, face, 0.05);
      c.fillStyle = "rgba(14,14,20,.9)";
      c.strokeStyle = "#f3c93f";
      c.lineWidth = S * 0.028;
      c.lineJoin = "round";

      for (const side of [-1, 1]) {
        const x = side * (gap / 2 + lensW / 2);
        // Rounded rectangle reads more like modern shades than an oval.
        const r = lensH * 0.42;
        c.beginPath();
        c.moveTo(x - lensW / 2 + r, -lensH / 2);
        c.lineTo(x + lensW / 2 - r, -lensH / 2);
        c.quadraticCurveTo(x + lensW / 2, -lensH / 2, x + lensW / 2, -lensH / 2 + r);
        c.lineTo(x + lensW / 2, lensH / 2 - r);
        c.quadraticCurveTo(x + lensW / 2, lensH / 2, x + lensW / 2 - r, lensH / 2);
        c.lineTo(x - lensW / 2 + r, lensH / 2);
        c.quadraticCurveTo(x - lensW / 2, lensH / 2, x - lensW / 2, lensH / 2 - r);
        c.lineTo(x - lensW / 2, -lensH / 2 + r);
        c.quadraticCurveTo(x - lensW / 2, -lensH / 2, x - lensW / 2 + r, -lensH / 2);
        c.closePath();
        c.fill(); c.stroke();

        c.save();
        c.clip();
        c.fillStyle = "rgba(255,255,255,.22)";
        c.beginPath();
        c.moveTo(x - lensW * 0.5, lensH * 0.5);
        c.lineTo(x - lensW * 0.1, -lensH * 0.5);
        c.lineTo(x + lensW * 0.1, -lensH * 0.5);
        c.lineTo(x - lensW * 0.3, lensH * 0.5);
        c.closePath(); c.fill();
        c.restore();
      }
      unlift(c);

      c.beginPath();
      c.moveTo(-gap / 2, -lensH * 0.12);
      c.quadraticCurveTo(0, -lensH * 0.34, gap / 2, -lensH * 0.12);
      c.stroke();

      // Temples run to the real ear points.
      for (const ear of [face.earR, face.earL]) {
        const side = ear.x < 0 ? -1 : 1;
        c.beginPath();
        c.moveTo(side * (gap / 2 + lensW), -lensH * 0.18);
        c.quadraticCurveTo(side * (gap / 2 + lensW * 1.5), -lensH * 0.3, ear.x * 0.92, ear.y);
        c.stroke();
      }
    });
  }
};

/* ------------------------------ Crown ------------------------------ */

const crown = {
  // Mesh tier: a crown's whole job is to sit on the top of the head, and in
  // fast mode that position is a proportion rather than a measurement.
  id: "crown", name: "Royal", emoji: "👑", needsMesh: true,
  draw(ctx, face, t) {
    inFaceSpace(ctx, face, c => {
      const S = face.headSpan;
      const cx = (face.earR.x + face.earL.x) / 2;
      const w = S * 0.92, h = S * 0.42;

      c.save();
      c.translate(cx, face.headTopY + h * 0.15);

      lift(c, face, 0.06);
      const g = c.createLinearGradient(0, -h, 0, h * 0.5);
      g.addColorStop(0, "#ffeb9c");
      g.addColorStop(0.55, "#f0bc45");
      g.addColorStop(1, "#c98a12");
      c.fillStyle = g;
      c.strokeStyle = "#a9740c";
      c.lineWidth = S * 0.016;
      c.lineJoin = "round";
      c.beginPath();
      c.moveTo(-w / 2, h * 0.42);
      c.lineTo(-w / 2, -h * 0.30);
      c.lineTo(-w / 4, h * 0.05);
      c.lineTo(0, -h * 0.62);
      c.lineTo(w / 4, h * 0.05);
      c.lineTo(w / 2, -h * 0.30);
      c.lineTo(w / 2, h * 0.42);
      c.closePath();
      c.fill(); c.stroke();
      unlift(c);

      // Band grounds the crown; without it the points float.
      c.fillStyle = "#b8860b";
      c.fillRect(-w / 2, h * 0.16, w, h * 0.26);

      const jewels = ["#e8455f", "#4ab5e8", "#5fd47a"];
      jewels.forEach((col, i) => {
        c.fillStyle = col;
        ellipse(c, -w / 4 + i * (w / 4), h * 0.29, S * 0.045, S * 0.045);
        c.fill();
        c.fillStyle = "rgba(255,255,255,.55)";
        ellipse(c, -w / 4 + i * (w / 4) - S * 0.014, h * 0.275 - S * 0.014, S * 0.014, S * 0.012);
        c.fill();
      });

      const tips = [[-w / 2, -h * 0.30], [0, -h * 0.62], [w / 2, -h * 0.30]];
      const [sx, sy] = tips[Math.floor(t / 600) % 3];
      const a = 0.45 + 0.55 * Math.sin(t / 170);
      c.fillStyle = "rgba(255,255,255," + a.toFixed(2) + ")";
      c.beginPath();
      for (let i = 0; i < 8; i++) {
        const r = i % 2 ? S * 0.02 : S * 0.07;
        const ang = (i / 8) * TAU - Math.PI / 2;
        c[i ? "lineTo" : "moveTo"](sx + Math.cos(ang) * r, sy + Math.sin(ang) * r);
      }
      c.closePath(); c.fill();
      c.restore();
    });
  }
};

/* --------------------------- Googly eyes --------------------------- */

// Pupil swing is per person: each pair lags its own head, so two children
// shaking their heads at different moments get different eyes. Keyed by
// `face.id` — held in one module variable, as it was, the second face would
// have overwritten the first's motion every frame and both pairs would have
// swung to whichever head was drawn last.
const swing = new Map();

function pupilLag(face, t) {
  let s = swing.get(face.id);
  if (!s) {
    s = { vx: 0, vy: 0, px: face.cx, py: face.cy };
    swing.set(face.id, s);
    // Track ids only ever climb, so an entry that stops being touched belongs to
    // someone who has left. Swept when the map outgrows what any tier will
    // track, which keeps this to a few numbers rather than one leak per person
    // who ever sat down in front of the camera.
    if (swing.size > 4) for (const [id, e] of swing) if (t - e.seen > 2000) swing.delete(id);
  }
  s.seen = t;
  s.vx = s.vx * 0.86 + (face.cx - s.px) * 0.14;
  s.vy = s.vy * 0.86 + (face.cy - s.py) * 0.14;
  s.px = face.cx; s.py = face.cy;
  return s;
}

const googly = {
  id: "googly", name: "Googly", emoji: "👀", needsMesh: false, voice: 1.75,
  draw(ctx, face, t) {
    // Pupils lag the head, so they swing when you move — the whole joke.
    const s = pupilLag(face, t);

    const S = face.earSpan;
    const cap = S * 0.10;
    const dx = Math.max(-cap, Math.min(cap, -s.vx / face.eyeDist * 0.45));
    const dy = Math.max(-cap, Math.min(cap, -s.vy / face.eyeDist * 0.45 + Math.sin(t / 320) * S * 0.02));

    inFaceSpace(ctx, face, c => {
      const R = S * 0.20;
      lift(c, face, 0.06);
      for (const side of [-1, 1]) {
        const x = side * S * 0.26;
        c.fillStyle = "#fff";
        c.strokeStyle = "#15151c";
        c.lineWidth = S * 0.022;
        ellipse(c, x, 0, R, R);
        c.fill(); c.stroke();
      }
      unlift(c);
      for (const side of [-1, 1]) {
        const x = side * S * 0.26;
        c.fillStyle = "#12121a";
        ellipse(c, x + dx, dy, R * 0.42, R * 0.42);
        c.fill();
        c.fillStyle = "rgba(255,255,255,.85)";
        ellipse(c, x + dx - R * 0.14, dy - R * 0.16, R * 0.10, R * 0.09);
        c.fill();
      }
    });
  }
};

/* ------------------------- Top hat + mustache ------------------------- */

const mustache = {
  id: "mustache", name: "Fancy", emoji: "🎩", needsMesh: false, voice: 0.8,
  draw(ctx, face) {
    inFaceSpace(ctx, face, c => {
      const S = face.headSpan;
      const cx = (face.earR.x + face.earL.x) / 2;

      c.save();
      c.translate(cx, face.headTopY);
      lift(c, face, 0.06);
      c.fillStyle = "#191922";
      const brimW = S * 1.02, brimH = S * 0.07;
      ellipse(c, 0, 0, brimW / 2, brimH);
      c.fill();
      // Was 0.62 of a head width tall, which ran off the top of a 16:9 frame
      // once the brim sat at the real hairline instead of above it.
      const crownW = S * 0.56, crownH = S * 0.34;
      c.fillRect(-crownW / 2, -crownH, crownW, crownH);
      ellipse(c, 0, -crownH, crownW / 2, brimH * 0.8);
      c.fill();
      unlift(c);
      c.fillStyle = "#8e1b2d";
      c.fillRect(-crownW / 2, -crownH * 0.28, crownW, crownH * 0.16);
      c.restore();

      // Half-width from the real mouth corners: the old 0.44 of a head width
      // per side made a mustache as wide as the entire face.
      const mx = face.mouth.x, my = face.mouth.y;
      const M = face.mouthR && face.mouthL
        ? Math.max(S * 0.22, Math.abs(face.mouthL.x - face.mouthR.x) * 0.62)
        : S * 0.30;
      lift(c, face, 0.04);
      c.fillStyle = "#2c1c11";
      c.beginPath();
      c.moveTo(mx, my - M * 0.42);
      c.bezierCurveTo(mx - M * 0.52, my - M * 0.65, mx - M * 1.37, my - M * 0.55,
                      mx - M * 1.43, my + M * 0.07);
      c.bezierCurveTo(mx - M * 0.91, my - M * 0.16, mx - M * 0.36, my - M * 0.03,
                      mx, my + M * 0.07);
      c.bezierCurveTo(mx + M * 0.36, my - M * 0.03, mx + M * 0.91, my - M * 0.16,
                      mx + M * 1.43, my + M * 0.07);
      c.bezierCurveTo(mx + M * 1.37, my - M * 0.55, mx + M * 0.52, my - M * 0.65,
                      mx, my - M * 0.42);
      c.closePath();
      c.fill();
      unlift(c);
    });
  }
};

/* ----------------------------- Big head ----------------------------- */

// Open your mouth and your head balloons; close it and it comes back. The voice
// drops as it grows, so the whole thing is one gesture.
//
// No mesh warp and no WebGL: this clips an ellipse around the head and redraws
// the video zoomed about the middle of the face. Two draws a frame. It only ever
// scales *up*, which matters — shrinking would leave a hole where the head was,
// and filling that convincingly is a much harder problem than this one.
const bighead = {
  id: "bighead", name: "Big Head", emoji: "🤯", needsMesh: true,
  // Deeper the bigger it gets. Clamped by voiceOf, and 1 with a closed mouth.
  voiceFrom: face => 1 - Math.min(1, (face.blendshapes.jawOpen || 0) * 1.15) * 0.32,
  draw(ctx, face, t, video) {
    const open = Math.min(1, (face.blendshapes.jawOpen || 0) * 1.15);
    // A mouth barely open should not wobble the head; below this it does nothing
    // at all, which also means a closed mouth costs one comparison.
    if (open < 0.06) return;

    const grow = 1 + open * 1.15;
    const h = headBox(face);
    const w = ctx.canvas.width, ht = ctx.canvas.height;
    // The clip is wider than the head it contains, so the feather below lands on
    // zoomed background rather than eating the edge of the hair.
    const rx = h.halfW * grow * 1.25, ry = h.halfH * grow * 1.25;

    ctx.save();
    ctx.beginPath();
    if (ctx.ellipse) ctx.ellipse(h.x, h.y, rx, ry, face.angle, 0, TAU);
    else ctx.arc(h.x, h.y, rx, 0, TAU);
    ctx.closePath();
    ctx.clip();

    // Scale the whole frame about the centre of the head. Inside the clip that
    // reads as the head growing; the background it drags along is part of the
    // joke rather than a defect.
    ctx.translate(h.x, h.y);
    ctx.scale(grow, grow);
    ctx.translate(-h.x, -h.y);
    ctx.drawImage(video, 0, 0, w, ht);
    ctx.restore();

    // Feather the boundary. Inside the clip the background is zoomed too, so the
    // ellipse edge is a visible seam against the real background — it reads as a
    // compositing bug rather than an effect. Erasing the outer band with a
    // gradient lets the true picture come back through gradually, which costs one
    // gradient fill and no second canvas. `destination-out` uses the gradient's
    // alpha to subtract, so the transform makes a circular gradient elliptical.
    ctx.save();
    ctx.globalCompositeOperation = "destination-out";
    ctx.translate(h.x, h.y);
    ctx.rotate(face.angle);
    ctx.scale(rx, ry);
    const fade = ctx.createRadialGradient(0, 0, 0.78, 0, 0, 1);
    fade.addColorStop(0, "rgba(0,0,0,0)");
    fade.addColorStop(1, "rgba(0,0,0,1)");
    ctx.fillStyle = fade;
    ctx.fillRect(-1.1, -1.1, 2.2, 2.2);
    ctx.restore();
  }
};

/* ---------------------------- Skydiver ----------------------------- */

// Your face, cut out and pasted onto a cartoon falling through the sky.
//
// The character is a fixed share of the frame rather than scaled to your head:
// built around a real head it came out the size of a bus, with its parachute off
// the top of the picture. So the face is *scaled into* the cartoon's helmet, and
// moving your head steers him instead of resizing him — which is more fun and
// works the same whether a child is leaning in or standing back.
const skydiver = {
  // Fast tier deliberately: it needs a head box and where the head is, both of
  // which the six keypoints give well enough once the face is being scaled into a
  // cartoon helmet anyway. A full-frame effect that repaints every frame wants the
  // detection rate more than it wants precision.
  id: "skydiver", name: "Skydive", emoji: "🪂", needsMesh: false, voice: 1.18,

  // The sky belongs to the picture, not to a head, so it is painted once whoever
  // is in front of it — two children get two skydivers in one sky rather than
  // one child's clouds drawn over the other's.
  scene(ctx, faces, t) {
    const w = ctx.canvas.width, h = ctx.canvas.height;

    const sky = ctx.createLinearGradient(0, 0, 0, h);
    sky.addColorStop(0, "#1f5fc4");
    sky.addColorStop(0.55, "#79b6ef");
    sky.addColorStop(1, "#d7ecff");
    ctx.fillStyle = sky;
    ctx.fillRect(0, 0, w, h);

    // Clouds rushing upward past them, each on its own loop so they never line up.
    ctx.fillStyle = "rgba(255,255,255,.9)";
    for (let i = 0; i < 7; i++) {
      const speed = 110 + i * 46;
      const y = h + 160 - ((t / 1000 * speed + i * 300) % (h + 320));
      const x = (i * 397) % w;
      const r = (22 + (i % 3) * 15) * (h / 720);
      ctx.beginPath();
      ctx.arc(x, y, r, 0, TAU);
      ctx.arc(x + r * 0.9, y + r * 0.15, r * 0.75, 0, TAU);
      ctx.arc(x - r * 0.85, y + r * 0.2, r * 0.65, 0, TAU);
      ctx.fill();
    }
  },

  draw(ctx, face, t, video) {
    const w = ctx.canvas.width, h = ctx.canvas.height;

    // Each jumper gets an equal share of the sky, in the order the children are
    // actually sitting — `rank` is left to right — so nobody is drawn on top of
    // anybody. Alone, the slot is the middle of the frame and this is exactly
    // where the single skydiver always was.
    const n = face.count;
    const slot = w * (face.rank + 1) / (n + 1);

    // They shrink as they crowd, and steer less far, because both a jumper's
    // canopy and their drift have to fit inside a slot that is now a third of
    // the frame rather than all of it. Alone: full size, quarter gain, unchanged.
    const R = h * 0.115 * (n === 1 ? 1 : n === 2 ? 0.8 : 0.66);   // cartoon head radius
    const drift = (face.cx / w - 0.5) * w * (0.25 / n);
    const rise = (face.cy / h - 0.5) * h * 0.18;
    const cx = slot + drift;
    const cy = h * 0.50 + rise;
    const sway = Math.sin(t / 420) * R * 0.12;
    const flap = Math.sin(t / 240) * 0.3;

    // Canopy, breathing, with its lines down to his shoulders.
    // Sized to stay inside a 16:9 frame: a bigger canopy loses its top edge.
    const canR = R * 1.8 + Math.sin(t / 700) * R * 0.06;
    const canY = cy - R * 2.15;
    const shoulder = cy + R * 1.05;
    ctx.strokeStyle = "rgba(20,25,35,.5)";
    ctx.lineWidth = Math.max(1, h / 480);
    for (const side of [-1, -0.4, 0.4, 1]) {
      ctx.beginPath();
      ctx.moveTo(cx + side * canR * 0.9, canY + canR * 0.1);
      ctx.lineTo(cx + sway + side * R * 0.5, shoulder);
      ctx.stroke();
    }
    for (let i = 0; i < 4; i++) {
      ctx.fillStyle = ["#ef4b6b", "#ffd23f", "#3ecf8e", "#4aa8ff"][i];
      ctx.beginPath();
      ctx.moveTo(cx, canY + canR * 0.12);
      ctx.arc(cx, canY + canR * 0.12, canR, Math.PI + (i / 4) * Math.PI, Math.PI + ((i + 1) / 4) * Math.PI);
      ctx.closePath();
      ctx.fill();
    }

    // Jumpsuit: arms and legs out, flapping.
    ctx.strokeStyle = "#e8663f";
    ctx.lineCap = "round";
    ctx.lineWidth = R * 0.36;
    for (const side of [-1, 1]) {
      ctx.beginPath();
      ctx.moveTo(cx + sway, cy + R * 1.35);
      ctx.lineTo(cx + sway + side * R * 1.5, cy + R * (1.0 + flap * side));
      ctx.stroke();
      ctx.beginPath();
      ctx.moveTo(cx + sway, cy + R * 2.1);
      ctx.lineTo(cx + sway + side * R * 0.9, cy + R * (3.2 - flap * side));
      ctx.stroke();
    }
    ctx.fillStyle = "#f4794f";
    ctx.beginPath();
    ctx.ellipse(cx + sway, cy + R * 1.75, R * 0.72, R * 1.0, 0, 0, TAU);
    ctx.fill();
    ctx.fillStyle = "rgba(0,0,0,.16)";
    ctx.fillRect(cx + sway - R * 0.72, cy + R * 1.45, R * 1.44, R * 0.18);

    // Helmet behind the face, so the cut-out sits inside something.
    ctx.fillStyle = "#2f3546";
    ctx.beginPath();
    ctx.arc(cx, cy, R * 1.12, 0, TAU);
    ctx.fill();

    // The face itself: the head box out of the camera, scaled into the helmet.
    // Axis-aligned on purpose — the cartoon head stays upright however you tilt.
    const hb = headBox(face);
    ctx.save();
    ctx.beginPath();
    if (ctx.ellipse) ctx.ellipse(cx, cy, R * 0.86, R * 1.0, 0, 0, TAU);
    else ctx.arc(cx, cy, R * 0.9, 0, TAU);
    ctx.closePath();
    ctx.clip();
    ctx.drawImage(video,
      hb.x - hb.halfW, hb.y - hb.halfH, hb.halfW * 2, hb.halfH * 2,
      cx - R * 0.92, cy - R * 1.06, R * 1.84, R * 2.12);
    ctx.restore();

    // Goggles pushed up onto the forehead, not over the eyes. Over the eyes they
    // hid the one thing the filter exists to show.
    ctx.fillStyle = "rgba(30,36,52,.95)";
    ctx.beginPath();
    ctx.ellipse(cx, cy - R * 0.74, R * 0.86, R * 0.24, 0, 0, TAU);
    ctx.fill();
    ctx.fillStyle = "rgba(160,225,255,.6)";
    for (const side of [-1, 1]) {
      ctx.beginPath();
      ctx.ellipse(cx + side * R * 0.4, cy - R * 0.74, R * 0.3, R * 0.15, 0, 0, TAU);
      ctx.fill();
    }
    ctx.strokeStyle = "#2f3546";
    ctx.lineWidth = R * 0.16;
    ctx.beginPath();
    ctx.arc(cx, cy, R * 1.04, Math.PI * 0.22, Math.PI * 0.78);
    ctx.stroke();
  }
};

/* --------------------------- Backgrounds --------------------------- */

// You, somewhere else. The person comes from the segmentation mask; the place is
// vector art, like the skydiver's sky — no photographs to vendor, no licences to
// track, and a few kilobytes instead of a few hundred.
//
// The order is: video, mask it down to the person, then the scene behind them.
//
//   1. draw the whole video frame
//   2. `destination-in` the mask, which leaves only the person
//   3. `destination-over` the scene, which fills in everywhere they are not
//
// The scene is painted on its own canvas first, in the natural back-to-front
// order. Painting it straight onto the overlay under `destination-over` would
// reverse it — each new shape lands *behind* the last, so the sky covered the
// sea, the sand and the palm tree. One reused offscreen canvas costs a drawImage
// and keeps the scenes readable.
let sceneCanvas = null, sceneCtx = null;

function inScene(ctx, subject, video, paint) {
  const w = ctx.canvas.width, h = ctx.canvas.height;
  if (!subject || !subject.mask) return;

  if (!sceneCanvas || sceneCanvas.width !== w || sceneCanvas.height !== h) {
    sceneCanvas = document.createElement("canvas");
    sceneCanvas.width = w; sceneCanvas.height = h;
    sceneCtx = sceneCanvas.getContext("2d", { alpha: false });
  }
  paint(sceneCtx, w, h);

  ctx.drawImage(video, 0, 0, w, h);
  ctx.globalCompositeOperation = "destination-in";
  // Blurred as it is scaled up: a category mask has hard 0/1 edges, and a hard
  // edge on a cut-out person reads as a sticker. This is the whole of the
  // feathering, and it costs one filtered draw.
  ctx.filter = "blur(" + Math.max(1, Math.round(w / 260)) + "px)";
  ctx.drawImage(subject.mask, 0, 0, w, h);
  ctx.filter = "none";
  ctx.globalCompositeOperation = "destination-over";
  ctx.drawImage(sceneCanvas, 0, 0);
  ctx.globalCompositeOperation = "source-over";
}

// A sun or a moon, with a soft halo.
function disc(ctx, x, y, r, inner, outer) {
  const g = ctx.createRadialGradient(x, y, r * 0.2, x, y, r * 2.4);
  g.addColorStop(0, outer);
  g.addColorStop(1, "rgba(0,0,0,0)");
  ctx.fillStyle = g;
  ctx.beginPath(); ctx.arc(x, y, r * 2.4, 0, TAU); ctx.fill();
  ctx.fillStyle = inner;
  ctx.beginPath(); ctx.arc(x, y, r, 0, TAU); ctx.fill();
}

const beach = {
  id: "beach", name: "Beach", emoji: "🏖️", needsSegment: true,
  draw(ctx, subject, t, video) {
    inScene(ctx, subject, video, (c, w, h) => {
      const sky = c.createLinearGradient(0, 0, 0, h);
      sky.addColorStop(0, "#2f8fd8");
      sky.addColorStop(0.6, "#9fd8f0");
      sky.addColorStop(1, "#ffe6b8");
      c.fillStyle = sky; c.fillRect(0, 0, w, h);
      disc(c, w * 0.78, h * 0.22, h * 0.07, "#fff6d0", "rgba(255,236,170,.55)");

      // Sea, then three rolling lines of surf.
      c.fillStyle = "#1f8ba8"; c.fillRect(0, h * 0.52, w, h * 0.16);
      c.fillStyle = "rgba(255,255,255,.75)";
      for (let i = 0; i < 3; i++) {
        const y = h * (0.55 + i * 0.035) + Math.sin(t / (900 + i * 260)) * h * 0.006;
        c.beginPath();
        c.moveTo(0, y);
        for (let x = 0; x <= w; x += w / 12) {
          c.quadraticCurveTo(x + w / 24, y + Math.sin(x / 90 + t / 700 + i) * h * 0.012, x + w / 12, y);
        }
        c.lineTo(w, y + h * 0.01); c.lineTo(0, y + h * 0.01); c.closePath(); c.fill();
      }

      // Sand.
      const sand = c.createLinearGradient(0, h * 0.66, 0, h);
      sand.addColorStop(0, "#f4dca6"); sand.addColorStop(1, "#e2bd7c");
      c.fillStyle = sand; c.fillRect(0, h * 0.66, w, h * 0.34);

      // A palm on the left, fronds drooping.
      const px = w * 0.13, py = h * 0.7;
      c.strokeStyle = "#8a5a30"; c.lineWidth = h * 0.022; c.lineCap = "round";
      c.beginPath(); c.moveTo(px, py); c.quadraticCurveTo(px - h * 0.03, py - h * 0.22, px + h * 0.02, py - h * 0.42); c.stroke();
      c.fillStyle = "#2f9e5c";
      for (let i = 0; i < 6; i++) {
        const a = -Math.PI / 2 + (i - 2.5) * 0.42 + Math.sin(t / 1400 + i) * 0.05;
        c.save();
        c.translate(px + h * 0.02, py - h * 0.42);
        c.rotate(a);
        c.beginPath();
        c.moveTo(0, 0);
        c.quadraticCurveTo(h * 0.11, -h * 0.05, h * 0.2, h * 0.01);
        c.quadraticCurveTo(h * 0.11, h * 0.03, 0, 0);
        c.fill();
        c.restore();
      }
    });
  }
};

const palace = {
  id: "palace", name: "Palace", emoji: "🏰", needsSegment: true,
  draw(ctx, subject, t, video) {
    inScene(ctx, subject, video, (c, w, h) => {
      const sky = c.createLinearGradient(0, 0, 0, h);
      sky.addColorStop(0, "#40306e");
      sky.addColorStop(0.5, "#9b6fa8");
      sky.addColorStop(1, "#f0b48a");
      c.fillStyle = sky; c.fillRect(0, 0, w, h);

      // Towers, back row darker so it reads as depth.
      const tower = (x, wd, top, body, roof) => {
        c.fillStyle = body;
        c.fillRect(x - wd / 2, top, wd, h - top);
        c.fillStyle = roof;
        c.beginPath();
        c.moveTo(x - wd * 0.78, top);
        c.lineTo(x, top - wd * 1.15);
        c.lineTo(x + wd * 0.78, top);
        c.closePath(); c.fill();
        // Flag, fluttering.
        c.strokeStyle = "#f7e9c8"; c.lineWidth = Math.max(1, h * 0.004);
        c.beginPath(); c.moveTo(x, top - wd * 1.15); c.lineTo(x, top - wd * 1.5); c.stroke();
        c.fillStyle = "#e0455f";
        c.beginPath();
        c.moveTo(x, top - wd * 1.5);
        c.quadraticCurveTo(x + wd * 0.4, top - wd * (1.42 + Math.sin(t / 400) * 0.06), x + wd * 0.62, top - wd * 1.36);
        c.lineTo(x, top - wd * 1.3);
        c.closePath(); c.fill();
        // Windows.
        c.fillStyle = "rgba(255,214,120,.9)";
        for (let i = 0; i < 3; i++) {
          c.fillRect(x - wd * 0.14, top + wd * (0.5 + i * 0.62), wd * 0.28, wd * 0.34);
        }
      };
      tower(w * 0.24, h * 0.12, h * 0.36, "#6d5b8e", "#4d3f6b");
      tower(w * 0.78, h * 0.13, h * 0.30, "#6d5b8e", "#4d3f6b");
      tower(w * 0.5, h * 0.18, h * 0.30, "#8878a8", "#5e4d84");

      // Curtain wall along the bottom, with battlements.
      c.fillStyle = "#7a6a99";
      c.fillRect(0, h * 0.7, w, h * 0.3);
      c.fillStyle = "#6d5b8e";
      for (let x = 0; x < w; x += h * 0.09) c.fillRect(x, h * 0.66, h * 0.05, h * 0.05);
    });
  }
};

const moon = {
  id: "moon", name: "Moon", emoji: "🌘", needsSegment: true, voice: 1.3,
  draw(ctx, subject, t, video) {
    inScene(ctx, subject, video, (c, w, h) => {
      c.fillStyle = "#05060f"; c.fillRect(0, 0, w, h);

      // Stars, twinkling on their own phases. Positioned by a fixed hash so they
      // do not swim about between frames.
      for (let i = 0; i < 90; i++) {
        const x = ((i * 9301 + 49297) % 233280) / 233280 * w;
        const y = ((i * 4021 + 12345) % 190093) / 190093 * h * 0.72;
        const tw = 0.55 + 0.45 * Math.sin(t / 900 + i);
        c.fillStyle = "rgba(255,255,255," + (0.35 + tw * 0.5).toFixed(2) + ")";
        c.fillRect(x, y, Math.max(1, h * 0.003), Math.max(1, h * 0.003));
      }

      // Earth, hanging over the horizon.
      const ex = w * 0.19, ey = h * 0.24, er = h * 0.11;
      disc(c, ex, ey, er, "#2f6fd0", "rgba(80,150,255,.35)");
      c.save();
      c.beginPath(); c.arc(ex, ey, er, 0, TAU); c.clip();
      c.fillStyle = "#3f9e63";
      c.beginPath(); c.ellipse(ex - er * 0.3, ey - er * 0.2, er * 0.42, er * 0.3, 0.4, 0, TAU); c.fill();
      c.beginPath(); c.ellipse(ex + er * 0.35, ey + er * 0.35, er * 0.35, er * 0.22, -0.3, 0, TAU); c.fill();
      c.fillStyle = "rgba(0,0,0,.45)";
      c.beginPath(); c.arc(ex + er * 0.75, ey, er, 0, TAU); c.fill();
      c.restore();

      // Grey ground, with craters.
      c.fillStyle = "#9a9aa6";
      c.beginPath();
      c.moveTo(0, h * 0.82);
      c.quadraticCurveTo(w * 0.3, h * 0.72, w * 0.62, h * 0.8);
      c.quadraticCurveTo(w * 0.85, h * 0.86, w, h * 0.78);
      c.lineTo(w, h); c.lineTo(0, h); c.closePath(); c.fill();
      c.fillStyle = "rgba(0,0,0,.14)";
      for (const [cx, cy, r] of [[0.2, 0.9, 0.05], [0.46, 0.94, 0.032], [0.72, 0.88, 0.042], [0.9, 0.95, 0.028]]) {
        c.beginPath(); c.ellipse(w * cx, h * cy, h * r, h * r * 0.42, 0, 0, TAU); c.fill();
      }
    });
  }
};

export const FILTERS = [dog, cat, shades, crown, googly, mustache, bighead, skydiver, beach, palace, moon];
