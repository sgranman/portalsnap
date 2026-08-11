// Pitch shifting for the silly voices, on the audio render thread.
//
// A delay-line granular shifter: hold the recent input in a ring, and read it
// back with a delay that slides linearly. A delay that shrinks by 0.4 samples
// per output sample means the read head advances 1.4 samples per output sample,
// which is playing the voice 1.4x faster — i.e. a fifth higher — without the
// recording getting shorter. The delay has to wrap eventually, and the wrap is
// a discontinuity, so two read heads half a grain apart are crossfaded and the
// one at the wrap point is always the one faded out.
//
// This is not a phase vocoder and does not pretend to be: two correlated copies
// of a voice summed together comb-filter slightly, which reads as a faint
// warble. For a puppy and a kitten that is a feature, and the cost is a handful
// of multiply-adds per sample rather than an FFT — this device is already
// spending everything it has on face detection.
//
// Written as a classic worklet module with no imports, like everything else here.

const GRAIN = 1024;        // ~21ms at 48kHz: short enough to hide, long enough not to buzz
const BUF = 8192;          // power of two so indexing is a mask; comfortably > GRAIN
const MASK = BUF - 1;

class PitchShift extends AudioWorkletProcessor {
  static get parameterDescriptors() {
    // k-rate: the ratio changes when a child taps a different filter, not per
    // sample, and a-rate would cost 128 reads a block for nothing.
    return [{ name: "ratio", defaultValue: 1, minValue: 0.5, maxValue: 2.5, automationRate: "k-rate" }];
  }

  constructor() {
    super();
    this.rings = [];       // one per channel, grown on demand
    this.w = 0;            // write cursor, shared: all channels advance together
    this.phase = 0;        // current delay of the first read head, in samples
  }

  process(inputs, outputs, params) {
    const input = inputs[0], output = outputs[0];
    if (!output || !output.length) return true;
    const n = output[0].length;
    const chans = input && input.length ? input.length : 0;
    while (this.rings.length < Math.max(1, chans)) this.rings.push(new Float32Array(BUF));

    // Always fill the ring, even while bypassing, so that switching to a voice
    // part-way through a clip reads real audio instead of a grain of silence.
    for (let c = 0; c < Math.max(1, chans); c++) {
      const src = chans ? input[Math.min(c, chans - 1)] : null;
      const ring = this.rings[c];
      for (let i = 0; i < n; i++) ring[(this.w + i) & MASK] = src ? src[i] : 0;
    }

    const ratio = params.ratio[0];

    // Exactly 1 has to be exactly untouched: the filters without a voice should
    // sound like the room, not like a processed version of it.
    if (ratio === 1) {
      for (let c = 0; c < output.length; c++) {
        const src = chans ? input[Math.min(c, chans - 1)] : null;
        if (src) output[c].set(src);
        else output[c].fill(0);
      }
      this.w = (this.w + n) & MASK;
      return true;
    }

    const half = GRAIN / 2;
    const step = 1 - ratio;              // negative raises the pitch
    let phase = this.phase;

    for (let i = 0; i < n; i++) {
      const dA = phase;
      const dB = phase < half ? phase + half : phase - half;
      // Triangular crossfade: head A is silent exactly where its delay wraps,
      // and the two gains always sum to 1 so the level never dips.
      const gA = 1 - Math.abs(1 - 2 * phase / GRAIN);
      const gB = 1 - gA;

      for (let c = 0; c < output.length; c++) {
        const ring = this.rings[Math.min(c, this.rings.length - 1)];
        const base = this.w + i;
        output[c][i] = tap(ring, base - dA) * gA + tap(ring, base - dB) * gB;
      }

      phase += step;
      if (phase >= GRAIN) phase -= GRAIN;
      else if (phase < 0) phase += GRAIN;
    }

    this.w = (this.w + n) & MASK;
    this.phase = phase;
    return true;
  }
}

// Fractional read from the ring. Negative indices wrap correctly under `&`,
// since it coerces to a two's-complement int32 first.
function tap(ring, pos) {
  const i0 = Math.floor(pos);
  const frac = pos - i0;
  const a = ring[i0 & MASK];
  const b = ring[(i0 + 1) & MASK];
  return a + (b - a) * frac;
}

registerProcessor("pitch-shift", PitchShift);
