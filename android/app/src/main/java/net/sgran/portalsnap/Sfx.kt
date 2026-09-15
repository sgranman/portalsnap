package net.sgran.portalsnap

import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Short sound effects for the filters. They're synthesized when the app starts rather than
 * shipped as files, so there's nothing to license and nothing to fetch. They play through
 * [Mixer], which also bakes them into recordings.
 */
object Sfx {
    private const val RATE = 44100
    private val clips = ConcurrentHashMap<String, Mixer.Clip>()
    @Volatile private var started = false

    fun init() {
        if (started) return
        started = true
        thread(name = "sfx") {
            clips["bloop"] = Mixer.Clip(bloop(), RATE)
            clips["poof"] = Mixer.Clip(poof(), RATE)
            clips["clink1"] = Mixer.Clip(clink(5L, 2400f), RATE)
            clips["clink2"] = Mixer.Clip(clink(7L, 2900f), RATE)
            clips["clink3"] = Mixer.Clip(clink(13L, 3500f), RATE)
            clips["whoosh"] = Mixer.Clip(whoosh(), RATE)
            clips["wind"] = Mixer.Clip(wind(), RATE)
            for ((i, f0) in HEART_KEYS.withIndex()) clips["key$i"] = Mixer.Clip(pianoNote(f0), RATE)
        }
    }

    /** Silently does nothing until the sound is ready. */
    fun play(name: String, volume: Float = 1f, rate: Float = 1f) {
        val clip = clips[name] ?: return
        Mixer.play(clip, volume, rate.coerceIn(0.5f, 2f))
    }

    /** Loops a sound until [Mixer.stop] with the id returned; 0 if it isn't ready yet. */
    fun loop(name: String, volume: Float = 1f): Int {
        val clip = clips[name] ?: return 0
        return Mixer.play(clip, volume, loop = true)
    }

    // Two-pole low-pass noise whose cutoff and level follow [cut] and [env] over time, for air.
    private inline fun air(n: Int, seed: Long, cut: (Float) -> Float, env: (Float) -> Float): FloatArray {
        val rng = Random(seed)
        val out = FloatArray(n)
        var lp1 = 0f
        var lp2 = 0f
        for (i in 0 until n) {
            val t = i.toFloat() / RATE
            val k = exp(-2f * PI.toFloat() * cut(t) / RATE)
            lp1 = (1 - k) * (rng.nextFloat() * 2 - 1) + k * lp1
            lp2 = (1 - k) * lp1 + k * lp2
            out[i] = lp2 * env(t)
        }
        return out
    }

    /** The keys Pixel Hearts walks down, top first: the white keys from C6 to C4, all in C major. */
    val HEART_KEYS = floatArrayOf(
        1046.50f, 987.77f, 880.00f, 783.99f, 698.46f, 659.26f, 587.33f, 523.25f,
        493.88f, 440.00f, 392.00f, 349.23f, 329.63f, 293.66f, 261.63f,
    )

    // One bright little piano note: a handful of partials, stretched a touch sharp the way a stiff
    // string's are, dying away at their own rates after a felt tick of hammer. Every note comes out
    // at the same loudness, and nothing is ever played at another rate, so it all stays in key.
    private fun pianoNote(f0: Float): ShortArray {
        val n = (RATE * 0.7f).toInt()
        val out = FloatArray(n)
        val rng = Random(f0.toLong())
        for (k in 1..8) {
            val fk = f0 * k * sqrt(1f + 0.0004f * k * k)
            if (fk > RATE / 2.2f) break
            val amp = 1f / k.toFloat().pow(1.3f)
            val tau = 0.45f / k.toFloat().pow(0.8f)
            val w = (2 * PI * fk / RATE).toFloat()
            for (j in 0 until n) {
                val t = j.toFloat() / RATE
                out[j] += amp * exp(-t / tau) * sin(w * j) * min(1f, t / 0.002f)
            }
        }
        val tick = RATE / 200
        for (j in 0 until tick) out[j] += 0.15f * (rng.nextFloat() * 2 - 1) * (1f - j.toFloat() / tick)
        var peak = 1e-6f
        for (v in out) peak = max(peak, abs(v))
        return ShortArray(n) { (out[it] / peak * 0.6f * 32767f).toInt().toShort() }
    }

    // Freefall's wind: dark rushing air with slow gusts, a brighter layer breathing on top of it.
    // Six seconds, with its end crossfaded into its start so the loop has no seam.
    private fun wind(): ShortArray {
        val body = 6f
        val fade = 0.5f
        val n = (RATE * (body + fade)).toInt()
        val low = air(n, 41L, { t -> 380f + 120f * sin(t * 1.3f) }) { t -> 0.8f + 0.2f * sin(t * 0.9f + 1f) }
        val high = air(n, 43L, { t -> 1400f + 500f * sin(t * 0.7f + 2f) }) { t -> 0.18f + 0.12f * sin(t * 2.1f) }
        val mixed = FloatArray(n) { low[it] * 3f + high[it] * 2f }
        val len = (RATE * body).toInt()
        val f = (RATE * fade).toInt()
        val out = FloatArray(len) { mixed[it] }
        for (i in 0 until f) {
            val a = i.toFloat() / f
            out[i] = mixed[len + i] * (1 - a) + mixed[i] * a
        }
        var peak = 1e-6f
        for (v in out) peak = max(peak, abs(v))
        return ShortArray(len) { (out[it] / peak * 0.7f * 32767f).toInt().toShort() }
    }

    // The fall: air swelling and brightening as the diver drops away, then rushing off darker.
    private fun whoosh(): ShortArray {
        val n = (RATE * 1.8f).toInt()
        val out = air(n, 47L, { t -> 300f + 2600f * exp(-((t - 0.45f) / 0.35f).pow(2)) }) { t ->
            min(1f, t / 0.35f).pow(2) * exp(-max(0f, t - 0.5f) / 0.45f)
        }
        var peak = 1e-6f
        for (v in out) peak = max(peak, abs(v))
        return ShortArray(n) { (out[it] / peak * 0.8f * 32767f).toInt().toShort() }
    }

    // Ice on glass: a bright, inharmonic ping that dies fast, with a tick of noise on the strike.
    private fun clink(seed: Long, baseHz: Float): ShortArray {
        val rng = Random(seed)
        val n = (RATE * 0.35f).toInt()
        val out = FloatArray(n)
        val ratios = floatArrayOf(1f, 1.47f, 2.09f, 2.56f, 3.2f)
        val decayS = floatArrayOf(0.12f, 0.08f, 0.05f, 0.035f, 0.02f)
        val gains = floatArrayOf(1f, 0.6f, 0.45f, 0.3f, 0.2f)
        for (m in ratios.indices) {
            val w = (2 * PI * baseHz * ratios[m] * (0.99f + 0.02f * rng.nextFloat()) / RATE).toFloat()
            val tau = decayS[m] * RATE
            val phase = rng.nextFloat() * 6.2832f
            for (i in 0 until n) out[i] += gains[m] * exp(-i / tau) * sin(w * i + phase)
        }
        val tick = RATE / 500
        for (i in 0 until tick) out[i] += 0.5f * (rng.nextFloat() * 2 - 1) * (1f - i.toFloat() / tick)
        var peak = 1e-6f
        for (v in out) peak = max(peak, abs(v))
        return ShortArray(n) { (out[it] / peak * 0.7f * 32767f).toInt().toShort() }
    }

    // Monster/Cutie's poof: a soft puff of air. Noise swells in over 15ms and dies away over about
    // a quarter of a second, darkening as it goes, over a faint low thump. No tones: the first
    // version had a cluster of them and a chime, and the user heard it as metallic.
    private fun poof(): ShortArray {
        val rng = Random(31L)
        val n = (RATE * 0.5f).toInt()
        val out = FloatArray(n)
        var lp1 = 0f
        var lp2 = 0f
        var thump = 0.0
        for (i in 0 until n) {
            val t = i.toFloat() / RATE
            val env = min(1f, t / 0.015f) * exp(-t / 0.09f)
            val cut = 250f + 2200f * exp(-t / 0.06f)
            val k = exp(-2f * PI.toFloat() * cut / RATE)
            lp1 = (1 - k) * (rng.nextFloat() * 2 - 1) + k * lp1
            lp2 = (1 - k) * lp1 + k * lp2
            out[i] = lp2 * env * 3f
            thump += 2 * PI * (90f + 60f * exp(-t / 0.03f)) / RATE
            out[i] += sin(thump).toFloat() * min(1f, t / 0.004f) * exp(-t / 0.05f) * 0.35f
        }
        var peak = 1e-6f
        for (v in out) peak = max(peak, abs(v))
        return ShortArray(n) { (out[it] / peak * 0.8f * 32767f).toInt().toShort() }
    }

    // A bubble's bloop: a sine that sweeps up as the bubble's cavity shrinks, with a quick
    // attack and decay.
    private fun bloop(): ShortArray {
        val seconds = 0.09f
        val n = (RATE * seconds).toInt()
        var phase = 0.0
        return ShortArray(n) { i ->
            val t = i.toFloat() / RATE
            val hz = 260f + 900f * (t / seconds).pow(0.6f)
            phase += 2 * PI * hz / RATE
            val env = min(1f, t / 0.004f) * exp(-t / 0.03f)
            (sin(phase) * env * 0.7f * 32767f).toInt().toShort()
        }
    }
}
