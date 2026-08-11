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

// Run fn once per ear, with the origin on that ear and +x pointing outward,
// so a single drawing works mirrored on both sides.
function perEar(ctx, face, fn) {
  for (const ear of [face.earR, face.earL]) {
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
      const S = face.earSpan;
      const sway = Math.sin(t / 500) * 0.05;

      lift(c, face, 0.05);
      perEar(c, face, (cc) => {
        cc.rotate(0.25 + sway);
        const w = S * 0.30, h = S * 0.62;
        cc.fillStyle = "#7d4f24";
        ellipse(cc, w * 0.15, h * 0.42, w, h);
        cc.fill();
        cc.fillStyle = "#5a3517";
        ellipse(cc, w * 0.18, h * 0.46, w * 0.55, h * 0.72);
        cc.fill();
      });
      unlift(c);

      // Snout on the measured nose point.
      const nx = face.nose.x, ny = face.nose.y;
      lift(c, face, 0.05);
      c.fillStyle = "#d69b5c";
      ellipse(c, nx, ny + 0.08, S * 0.30, S * 0.22);
      c.fill();
      unlift(c);

      c.fillStyle = "#f0e0cc";
      ellipse(c, nx, ny + 0.14, S * 0.20, S * 0.13);
      c.fill();

      // Nose leather: rounded triangle, not an oval — an oval reads as a bruise.
      c.fillStyle = "#26190f";
      c.beginPath();
      const nw = S * 0.135, nh = S * 0.10;
      c.moveTo(nx - nw, ny - nh * 0.5);
      c.quadraticCurveTo(nx, ny - nh * 1.25, nx + nw, ny - nh * 0.5);
      c.quadraticCurveTo(nx + nw * 0.8, ny + nh * 0.95, nx, ny + nh * 1.1);
      c.quadraticCurveTo(nx - nw * 0.8, ny + nh * 0.95, nx - nw, ny - nh * 0.5);
      c.closePath();
      c.fill();

      c.fillStyle = "rgba(255,255,255,.5)";
      ellipse(c, nx - nw * 0.35, ny - nh * 0.5, nw * 0.22, nh * 0.16);
      c.fill();

      const open = face.blendshapes.jawOpen || 0;
      if (open > 0.10) {
        const len = S * (0.16 + open * 0.42);
        const my = face.mouth.y;
        c.fillStyle = "#ef6f8e";
        c.beginPath();
        c.moveTo(nx - S * 0.13, my);
        c.lineTo(nx + S * 0.13, my);
        c.quadraticCurveTo(nx + S * 0.15, my + len, nx, my + len);
        c.quadraticCurveTo(nx - S * 0.15, my + len, nx - S * 0.13, my);
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
  id: "cat", name: "Kitty", emoji: "🐱", needsMesh: false, voice: 1.42,
  draw(ctx, face) {
    inFaceSpace(ctx, face, c => {
      const S = face.earSpan;

      lift(c, face, 0.05);
      perEar(c, face, (cc) => {
        cc.rotate(0.12);
        const w = S * 0.24, h = S * 0.46;
        cc.fillStyle = "#55555f";
        cc.beginPath();
        cc.moveTo(-w, 0.06); cc.lineTo(w * 0.35, -h); cc.lineTo(w * 1.1, 0.02);
        cc.closePath(); cc.fill();
        cc.fillStyle = "#f4a6ba";
        cc.beginPath();
        cc.moveTo(-w * 0.45, 0.0); cc.lineTo(w * 0.33, -h * 0.62); cc.lineTo(w * 0.68, 0.0);
        cc.closePath(); cc.fill();
      });
      unlift(c);

      const nx = face.nose.x, ny = face.nose.y;
      c.fillStyle = "#f4a6ba";
      c.beginPath();
      c.moveTo(nx - S * 0.09, ny - S * 0.04);
      c.lineTo(nx + S * 0.09, ny - S * 0.04);
      c.quadraticCurveTo(nx, ny + S * 0.10, nx - S * 0.09, ny - S * 0.04);
      c.closePath(); c.fill();

      c.strokeStyle = "rgba(255,255,255,.94)";
      c.lineWidth = 0.024;
      c.lineCap = "round";
      lift(c, face, 0.03);
      for (const side of [-1, 1]) {
        for (let i = 0; i < 3; i++) {
          const y = ny + S * (0.02 + i * 0.075);
          c.beginPath();
          c.moveTo(nx + side * S * 0.12, y);
          c.quadraticCurveTo(nx + side * S * 0.38, y - S * (0.05 - i * 0.03),
                             nx + side * S * 0.62, y - S * (0.10 - i * 0.07));
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
  id: "crown", name: "Royal", emoji: "👑", needsMesh: false,
  draw(ctx, face, t) {
    inFaceSpace(ctx, face, c => {
      const S = face.earSpan;
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
      const S = face.earSpan;
      const cx = (face.earR.x + face.earL.x) / 2;

      c.save();
      c.translate(cx, face.headTopY);
      lift(c, face, 0.06);
      c.fillStyle = "#191922";
      const brimW = S * 1.02, brimH = S * 0.07;
      ellipse(c, 0, 0, brimW / 2, brimH);
      c.fill();
      const crownW = S * 0.60, crownH = S * 0.62;
      c.fillRect(-crownW / 2, -crownH, crownW, crownH);
      ellipse(c, 0, -crownH, crownW / 2, brimH * 0.8);
      c.fill();
      unlift(c);
      c.fillStyle = "#8e1b2d";
      c.fillRect(-crownW / 2, -crownH * 0.28, crownW, crownH * 0.16);
      c.restore();

      const mx = face.mouth.x, my = face.mouth.y;
      lift(c, face, 0.04);
      c.fillStyle = "#2c1c11";
      c.beginPath();
      c.moveTo(mx, my - S * 0.13);
      c.bezierCurveTo(mx - S * 0.16, my - S * 0.20, mx - S * 0.42, my - S * 0.17,
                      mx - S * 0.44, my + S * 0.02);
      c.bezierCurveTo(mx - S * 0.28, my - S * 0.05, mx - S * 0.11, my - S * 0.01,
                      mx, my + S * 0.02);
      c.bezierCurveTo(mx + S * 0.11, my - S * 0.01, mx + S * 0.28, my - S * 0.05,
                      mx + S * 0.44, my + S * 0.02);
      c.bezierCurveTo(mx + S * 0.42, my - S * 0.17, mx + S * 0.16, my - S * 0.20,
                      mx, my - S * 0.13);
      c.closePath();
      c.fill();
      unlift(c);
    });
  }
};

export const FILTERS = [dog, cat, shades, crown, googly, mustache];
