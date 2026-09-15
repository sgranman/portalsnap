package net.sgransoft.portalsnap

import kotlin.math.abs
import kotlin.math.floor

/**
 * Port of public/pitch.worklet.js: a delay-line granular shifter. Two read heads half a
 * grain apart slide through a ring of recent input and are crossfaded so the one at its
 * wrap point is always silent. A faint warble, no FFT — see the worklet for the reasoning.
 */
class PitchShifter {
    private val ring = FloatArray(BUF)
    private var w = 0
    private var phase = 0f

    /** Shifts [n] samples of [buf] in place. A ratio of exactly 1 leaves them untouched. */
    fun process(buf: FloatArray, n: Int, ratio: Float) {
        // Always fill the ring, so switching voice mid-clip reads real audio.
        for (i in 0 until n) ring[(w + i) and MASK] = buf[i]
        if (ratio == 1f) {
            w = (w + n) and MASK
            return
        }
        val half = GRAIN / 2f
        val step = 1 - ratio
        var ph = phase
        for (i in 0 until n) {
            val dA = ph
            val dB = if (ph < half) ph + half else ph - half
            val gA = 1 - abs(1 - 2 * ph / GRAIN)
            val gB = 1 - gA
            val base = (w + i).toFloat()
            buf[i] = tap(base - dA) * gA + tap(base - dB) * gB
            ph += step
            if (ph >= GRAIN) ph -= GRAIN else if (ph < 0) ph += GRAIN
        }
        w = (w + n) and MASK
        phase = ph
    }

    private fun tap(pos: Float): Float {
        val i0 = floor(pos).toInt()
        val frac = pos - i0
        val a = ring[i0 and MASK]
        val b = ring[(i0 + 1) and MASK]
        return a + (b - a) * frac
    }

    private companion object {
        const val GRAIN = 1024
        const val BUF = 8192
        const val MASK = BUF - 1
    }
}
