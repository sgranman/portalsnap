// anchors.js is a classic script assigning onto a global — load it as one.
const fs = require("fs"), vm = require("vm");
const ctx = { self: {} };
vm.createContext(ctx);
vm.runInContext(fs.readFileSync("public/anchors.js", "utf8"), ctx);
const { project } = ctx.self.Anchors;

let fail = 0;
const near = (a, b, eps = 1e-9) => Math.abs(a - b) < eps;
function check(name, ok, extra = "") {
  console.log((ok ? "  PASS  " : "  FAIL  ") + name + (extra ? "   " + extra : ""));
  if (!ok) fail++;
}

const P = { x: 0.5, y: 0.5 };

check("no velocity returns the point untouched", project(P, null, 100, 0.05) === P);
check("zero lead returns the point untouched", project(P, { x: 1, y: 1 }, 0, 0.05) === P);
check("null point is passed through", project(null, { x: 1, y: 1 }, 100, 0.05) === null);

// 0.0002 units/ms for 100ms = 0.02, comfortably inside a 0.05 clamp
let r = project(P, { x: 0.0002, y: 0 }, 100, 0.05);
check("leads along velocity when under the clamp", near(r.x, 0.52) && near(r.y, 0.5), JSON.stringify(r));

// leading must not mutate the input
check("does not mutate the source point", P.x === 0.5 && P.y === 0.5);

// 0.002 units/ms for 100ms = 0.2, five times the clamp
r = project(P, { x: 0.002, y: 0 }, 100, 0.05);
check("clamps a wild velocity to the rail", near(r.x, 0.55) && near(r.y, 0.5), JSON.stringify(r));

// the clamp is on distance, not per-axis: a diagonal must stay on the circle
r = project(P, { x: 0.002, y: 0.002 }, 100, 0.05);
const d = Math.hypot(r.x - P.x, r.y - P.y);
check("clamps diagonals by distance, not per-axis", near(d, 0.05, 1e-12), "d=" + d);
check("clamping preserves direction", near(r.x - P.x, r.y - P.y), JSON.stringify(r));

// negative velocity leads backwards by the same amount
r = project(P, { x: -0.0002, y: 0 }, 100, 0.05);
check("leads backwards for negative velocity", near(r.x, 0.48), JSON.stringify(r));

// exactly at the clamp boundary should not be scaled
r = project(P, { x: 0.0005, y: 0 }, 100, 0.05);
check("a velocity exactly at the rail is untouched", near(r.x, 0.55), JSON.stringify(r));

console.log(fail ? "\n" + fail + " FAILURE(S)" : "\nprojection math OK");
process.exit(fail ? 1 : 0);
