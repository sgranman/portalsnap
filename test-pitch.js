// Unit test for the pitch shifter, with no browser and no dependencies: the
// worklet only needs two globals to exist, so it can be driven straight from
// Node over a synthetic sine and its output frequency measured.
//
//   node test-pitch.js
//
// This is the half of the feature a browser test cannot check. Puppeteer can
// confirm the graph is wired up and that a clip still has an audio track; only
// arithmetic can confirm that 1.4 actually means a fifth up.

const fs = require("fs");
const path = require("path");
const vm = require("vm");

const SR = 48000;
const BLOCK = 128;

let fail = 0;
function check(name, ok, extra = "") {
  console.log((ok ? "  PASS  " : "  FAIL  ") + name + (extra ? "   " + extra : ""));
  if (!ok) fail++;
}

// Minimal AudioWorkletGlobalScope: a base class to extend and a registry.
const registry = {};
const sandbox = {
  sampleRate: SR,
  currentTime: 0,
  AudioWorkletProcessor: class { constructor() {} },
  registerProcessor: (name, cls) => { registry[name] = cls; },
  Math, Float32Array, console
};
vm.createContext(sandbox);
const src = fs.readFileSync(path.join(__dirname, "public", "pitch.worklet.js"), "utf8");
vm.runInContext(src, sandbox, { filename: "pitch.worklet.js" });

check("the worklet registers itself", typeof registry["pitch-shift"] === "function");

// Run `seconds` of a sine through the processor at a fixed ratio.
function run(freq, ratio, seconds) {
  const Proc = registry["pitch-shift"];
  const p = new Proc();
  const params = { ratio: [ratio] };
  const total = Math.floor(SR * seconds / BLOCK) * BLOCK;
  const out = new Float32Array(total);
  let phase = 0;
  const inc = 2 * Math.PI * freq / SR;
  for (let off = 0; off < total; off += BLOCK) {
    const inp = new Float32Array(BLOCK);
    for (let i = 0; i < BLOCK; i++) { inp[i] = Math.sin(phase); phase += inc; }
    const o = new Float32Array(BLOCK);
    p.process([[inp]], [[o]], params);
    out.set(o, off);
  }
  return out;
}

// Frequency by zero crossings, over the second half only — the first grain or
// two are the ring filling up and are not meant to be musical.
function measure(buf) {
  const from = Math.floor(buf.length / 2);
  let crossings = 0, prev = buf[from];
  for (let i = from + 1; i < buf.length; i++) {
    const v = buf[i];
    if ((prev <= 0 && v > 0) || (prev >= 0 && v < 0)) crossings++;
    prev = v;
  }
  const seconds = (buf.length - from) / SR;
  return crossings / 2 / seconds;
}

const BASE = 440;

// A ratio of 1 must be bit-exact, not merely close: the filters with no voice
// have to sound like the room.
const flat = run(BASE, 1, 0.25);
let phase = 0, exact = true;
const inc = 2 * Math.PI * BASE / SR;
for (let i = 0; i < flat.length; i++) {
  // fround because the ring and the output are Float32Array: the only rounding
  // allowed here is the one storing a double into 32 bits.
  const want = Math.fround(Math.sin(phase)); phase += inc;
  if (flat[i] !== want) { exact = false; break; }
}
check("a ratio of 1 passes the signal through untouched", exact);
check("...and still measures as the input frequency", Math.abs(measure(flat) - BASE) < 5, measure(flat).toFixed(1) + " Hz");

// The actual claim: output frequency tracks the ratio.
for (const [ratio, label] of [[0.78, "Puppy"], [0.8, "Fancy"], [1.42, "Kitty"], [1.75, "Googly"]]) {
  const got = measure(run(BASE, ratio, 0.5));
  const want = BASE * ratio;
  // 4% covers the crossfade's contribution to the zero-crossing count; the
  // shift itself is either right or wildly wrong, never off by a few percent.
  const ok = Math.abs(got - want) / want < 0.04;
  check("ratio " + ratio + " (" + label + ") shifts " + BASE + "Hz to " + Math.round(want) + "Hz",
    ok, got.toFixed(1) + " Hz");
}

// Silence in, silence out — a NaN or a DC offset here would be an audible click
// at the start of every clip.
const quiet = run(0, 1.42, 0.2);
let worst = 0;
for (const v of quiet) worst = Math.max(worst, Math.abs(v));
check("silence stays silent and finite", worst === 0, "peak " + worst);

// Nothing may leave the processor that a WAV cannot hold.
const loud = run(BASE, 1.75, 0.3);
let peak = 0, finite = true;
for (const v of loud) {
  if (!Number.isFinite(v)) { finite = false; break; }
  peak = Math.max(peak, Math.abs(v));
}
check("output is finite", finite);
check("output does not clip a full-scale input", peak <= 1.001, "peak " + peak.toFixed(3));

// A missing input block happens on the first render quantum, before the mic
// flows. It must not throw, and must not leave the ring poisoned.
const Proc = registry["pitch-shift"];
const p = new Proc();
let threw = null;
try {
  const o = new Float32Array(BLOCK);
  p.process([[]], [[o]], { ratio: [1.42] });
  p.process([], [[o]], { ratio: [1.42] });
} catch (e) { threw = e; }
check("an empty input block is survivable", !threw, threw ? threw.message : "");

console.log(fail ? "\n" + fail + " FAILURE(S)" : "\npitch shifting OK");
process.exit(fail ? 1 : 0);
