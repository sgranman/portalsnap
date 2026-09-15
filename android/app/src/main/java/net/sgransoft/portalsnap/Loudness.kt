package net.sgransoft.portalsnap

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Automatic gain for clips. Chrome's getUserMedia has gain control on by default, so the web
 * app's clips came out at -12..-16 LUFS; the raw mic on the same Portal measured -24 LUFS,
 * which is what "the audio isn't very prevalent" was.
 *
 * Block by block: follow the level (fast attack, slow release), steer the gain toward
 * [TARGET_DB], hold it through silence so room hiss is never pumped up, and soft-limit
 * whatever the gain pushes past [KNEE]. The constants were chosen by running this same
 * algorithm over a real clip from the gen 1 Portal and measuring the result with ffmpeg.
 */
class Loudness {
    private var level = Float.NaN
    private var gainDb = 0f
    private var prevGain = 1f

    /** Processes [n] samples of [buf] in place, as one block. */
    fun process(buf: FloatArray, n: Int) {
        if (n <= 0) return
        var sum = 0.0
        for (i in 0 until n) sum += (buf[i] * buf[i]).toDouble()
        val db = (20 * log10(sqrt(sum / n) + 1e-9)).toFloat()
        if (level.isNaN()) level = db
        level += (db - level) * (if (db > level) 0.5f else 0.08f)
        if (level > GATE_DB) {
            val want = (TARGET_DB - level).coerceIn(0f, MAX_GAIN_DB)
            // Back off quickly when someone gets loud; rise slowly when they go quiet.
            gainDb += (want - gainDb) * (if (want < gainDb) 0.5f else 0.15f)
        }
        val gain = 10f.pow(gainDb / 20)
        // Ramped across the block, so a gain change is never a step you can hear.
        for (i in 0 until n) {
            val g = prevGain + (gain - prevGain) * (i + 1) / n
            buf[i] = limit(buf[i] * g)
        }
        prevGain = gain
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun limit(y: Float): Float {
        val a = abs(y)
        if (a <= KNEE) return y
        return sign(y) * (KNEE + (1 - KNEE) * tanh((a - KNEE) / (1 - KNEE)))
    }

    private companion object {
        const val TARGET_DB = -16f
        const val KNEE = 0.8f
        const val GATE_DB = -50f
        const val MAX_GAIN_DB = 24f
    }
}
