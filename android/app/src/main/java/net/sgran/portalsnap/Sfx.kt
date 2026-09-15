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
        }
    }

    /** Silently does nothing until the sound is ready. */
    fun play(name: String, volume: Float = 1f, rate: Float = 1f) {
        val clip = clips[name] ?: return
        Mixer.play(clip, volume, rate.coerceIn(0.5f, 2f))
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
