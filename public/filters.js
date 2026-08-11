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
  // The only mesh-tier filter: the tongue keys off jawOpen, and blendshapes
  // exist only in mesh mode. Costs ~17fps tracking instead of 30.
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

const googly = {
  id: "googly", name: "Googly", emoji: "👀", needsMesh: false, voice: 1.75,
  draw(ctx, face, t) {
    // Pupils lag the head, so they swing when you move — the whole joke.
    googly._vx = (googly._vx || 0) * 0.86 + (face.cx - (googly._px ?? face.cx)) * 0.14;
    googly._vy = (googly._vy || 0) * 0.86 + (face.cy - (googly._py ?? face.cy)) * 0.14;
    googly._px = face.cx; googly._py = face.cy;

    const S = face.earSpan;
    const cap = S * 0.10;
    const dx = Math.max(-cap, Math.min(cap, -googly._vx / face.eyeDist * 0.45));
    const dy = Math.max(-cap, Math.min(cap, -googly._vy / face.eyeDist * 0.45 + Math.sin(t / 320) * S * 0.02));

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

export const FILTERS = [dog, cat, shades, crown, googly, mustache];
