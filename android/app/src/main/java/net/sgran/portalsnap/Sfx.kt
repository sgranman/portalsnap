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
            clips["creak1"] = Mixer.Clip(creak(11L, 0.55f, 390f), RATE)
            clips["creak2"] = Mixer.Clip(creak(23L, 0.5f, 450f), RATE)
            clips["bloop"] = Mixer.Clip(bloop(), RATE)
        }
    }

    /** Silently does nothing until the sound is ready. */
    fun play(name: String, volume: Float = 1f, rate: Float = 1f) {
        val clip = clips[name] ?: return
        Mixer.play(clip, volume, rate.coerceIn(0.5f, 2f))
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

    // A wooden creak is stick-slip: a quick train of tiny impacts, each ringing the wood's few
    // resonances. The impacts speed up then slow down over the sound (the "errrk"), and the
    // pitch rises a little under load.
    private fun creak(seed: Long, seconds: Float, baseHz: Float): ShortArray {
        val rng = Random(seed)
        val n = (RATE * seconds).toInt()
        val out = FloatArray(n)
        val modes = floatArrayOf(1f, 2.63f, 5.1f)
        val decayS = floatArrayOf(0.022f, 0.011f, 0.005f)
        val gains = floatArrayOf(1f, 0.55f, 0.3f)
        var t = 0.01f
        while (t < seconds - 0.03f) {
            val env = sin(PI * t / seconds).toFloat()
            val start = (t * RATE).toInt()
            val hit = env.pow(0.7f) * (0.6f + 0.4f * rng.nextFloat())
            val bend = 1f + 0.12f * env
            for (m in modes.indices) {
                val w = (2 * PI * baseHz * modes[m] * bend * (0.97f + 0.06f * rng.nextFloat()) / RATE).toFloat()
                val tau = decayS[m] * RATE
                val phase = rng.nextFloat() * 6.2832f
                val len = min(n - start, (tau * 5).toInt())
                for (j in 0 until len) out[start + j] += hit * gains[m] * exp(-j / tau) * sin(w * j + phase)
            }
            val scrape = min(n - start, RATE / 400)
            for (j in 0 until scrape) out[start + j] += hit * 0.25f * (rng.nextFloat() * 2 - 1) * (1f - j.toFloat() / scrape)
            val clicksPerS = 28f + 85f * env.pow(1.5f)
            t += (1f / clicksPerS) * (0.8f + 0.4f * rng.nextFloat())
        }
        var peak = 1e-6f
        for (v in out) peak = max(peak, abs(v))
        return ShortArray(n) { (out[it] / peak * 0.8f * 32767f).toInt().toShort() }
    }
}
