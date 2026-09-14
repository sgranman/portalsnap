package net.sgran.portalsnap

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
 * Short sound effects for the filters. They're synthesized on first launch rather than shipped
 * as files, so there's nothing to license and nothing to fetch. They play through a SoundPool,
 * so they can overlap and each play can be pitched a little differently.
 *
 * They go to the speaker only. A recording picks them up through the mic, like any other sound
 * in the room.
 */
object Sfx {
    private const val RATE = 44100
    // Bump when a recipe changes, so the cached WAVs are rebuilt.
    private const val VERSION = 1

    @Volatile private var pool: SoundPool? = null
    private val ids = ConcurrentHashMap<String, Int>()
    private val ready = ConcurrentHashMap.newKeySet<Int>()

    @Synchronized
    fun init(ctx: Context) {
        if (pool != null) return
        val p = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .build()
        p.setOnLoadCompleteListener { _, id, status -> if (status == 0) ready += id }
        pool = p
        val dir = ctx.cacheDir
        thread(name = "sfx") {
            val recipes = linkedMapOf<String, () -> ShortArray>(
                "creak1" to { creak(11L, 0.55f, 390f) },
                "creak2" to { creak(23L, 0.5f, 450f) },
            )
            for ((name, make) in recipes) {
                try {
                    val f = File(dir, "sfx-$name-v$VERSION.wav")
                    if (!f.exists()) writeWav(f, make())
                    ids[name] = p.load(f.path, 1)
                } catch (e: Exception) {
                    Log.w(TAG, "sfx $name", e)
                }
            }
        }
    }

    /** Silently does nothing until the sound has loaded. */
    fun play(name: String, volume: Float = 1f, rate: Float = 1f) {
        val p = pool ?: return
        val id = ids[name] ?: return
        if (id !in ready) return
        p.play(id, volume, volume, 1, 0, rate.coerceIn(0.5f, 2f))
    }

    fun pause() {
        pool?.autoPause()
    }

    fun resume() {
        pool?.autoResume()
    }

    @Synchronized
    fun release() {
        pool?.release()
        pool = null
        ids.clear()
        ready.clear()
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

    private fun writeWav(f: File, pcm: ShortArray) {
        val bytes = pcm.size * 2
        val buf = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()).putInt(36 + bytes).put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
        buf.put("data".toByteArray()).putInt(bytes)
        for (s in pcm) buf.putShort(s)
        val tmp = File(f.path + ".tmp")
        tmp.writeBytes(buf.array())
        tmp.renameTo(f)
    }
}
