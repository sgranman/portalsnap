package net.sgransoft.portalsnap

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import kotlin.concurrent.thread
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max

/**
 * The one microphone, shared. Android 9 gives the mic to one AudioRecord at a time, and two
 * things want it: music-reactive filters (level and beat) and the recorder (the clip's sound).
 * One capture thread owns it and hands every block to both.
 *
 * Beats are onsets, not tempo: a block whose energy jumps well above the last second's
 * average. That is what a kick drum or a clap looks like through a room, and it is enough to
 * drive "flash on the beat" — which is all the Photo Booth effects did with their music.
 */
class MicHub {
    fun interface Sink {
        fun onBlock(samples: ShortArray, n: Int, ptsUs: Long)
    }

    @Volatile var sink: Sink? = null
    @Volatile var level = 0f
        private set
    @Volatile var beats = 0
        private set
    @Volatile var lastBeatAt = 0L
        private set
    @Volatile var running = false
        private set
    /** Capturing, but nothing louder than hiss in the first seconds: the privacy button is on. */
    @Volatile var silent = false
        private set

    private val users = HashSet<String>()
    private var worker: Thread? = null

    /** Returns whether the mic is actually capturing. */
    @Synchronized
    fun acquire(who: String): Boolean {
        users += who
        if (worker == null) start()
        return running
    }

    @Synchronized
    fun release(who: String) {
        if (!users.remove(who)) return
        if (users.isEmpty()) stop()
    }

    /** 1 on the beat, decaying over ~a quarter second. */
    fun beatPulse(now: Long): Float = if (lastBeatAt == 0L) 0f else exp(-(now - lastBeatAt) / 220f)

    fun sinceBeat(now: Long): Float = if (lastBeatAt == 0L) 1e9f else (now - lastBeatAt).toFloat()

    private fun start() {
        val rec = try {
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            AudioRecord(
                MediaRecorder.AudioSource.CAMCORDER, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(min, BLOCK * 8),
            )
        } catch (e: Exception) {
            Log.w(TAG, "mic unavailable", e)
            null
        }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            rec?.release()
            running = false
            return
        }
        running = true
        worker = thread(name = "mic") { loop(rec) }
        Log.i(TAG, "mic started")
    }

    private fun stop() {
        running = false
        worker?.join(1000)
        worker = null
        level = 0f
        silent = false
        Log.i(TAG, "mic stopped")
    }

    private fun loop(rec: AudioRecord) {
        val buf = ShortArray(BLOCK)
        val mix = FloatArray(BLOCK)
        val history = FloatArray(HISTORY)
        var filled = 0
        var head = 0
        try {
            rec.startRecording()
            Log.i(TAG, "mic effects: aec=${android.media.audiofx.AcousticEchoCanceler.isAvailable()} agc=${android.media.audiofx.AutomaticGainControl.isAvailable()} ns=${android.media.audiofx.NoiseSuppressor.isAvailable()}")
            val t0Us = System.nanoTime() / 1000
            var frames = 0L
            var peak = 0
            val checkAt = SystemClock.uptimeMillis() + SILENCE_CHECK_MS
            var checked = false
            while (running) {
                val n = rec.read(buf, 0, BLOCK)
                if (n <= 0) {
                    if (n < 0) Thread.sleep(20)
                    continue
                }
                if (!checked) {
                    for (i in 0 until n) peak = max(peak, kotlin.math.abs(buf[i].toInt()))
                    if (SystemClock.uptimeMillis() > checkAt) {
                        checked = true
                        silent = peak < SILENT_PEAK
                        if (silent) Log.w(TAG, "mic reads but is silent (peak $peak) — is the Portal's privacy button on?")
                    }
                }
                val pts = t0Us + frames * 1_000_000L / RATE
                frames += n
                sink?.onBlock(buf, n, pts)

                // Beats come from the room plus what the app itself is playing, heard directly,
                // so the Portal's own music drives Disco even where the mic barely hears it.
                java.util.Arrays.fill(mix, 0, n, 0f)
                Mixer.mixInto(mix, n, pts * 1000)
                var sum = 0.0
                for (i in 0 until n) {
                    val s = buf[i] / 32768.0 + mix[i]
                    sum += s * s
                }
                val energy = (sum / n).toFloat()
                val db = (10 * log10(energy + 1e-12)).toFloat()
                val target = ((db + 60f) / 45f).coerceIn(0f, 1f)
                level += (target - level) * (if (target > level) 0.5f else 0.12f)

                if (filled >= 10) {
                    var avg = 0f
                    for (i in 0 until filled) avg += history[i]
                    avg /= filled
                    val now = SystemClock.uptimeMillis()
                    if (energy > avg * ONSET_RATIO && db > -48f && now - lastBeatAt > MIN_BEAT_GAP_MS) {
                        lastBeatAt = now
                        beats++
                    }
                }
                history[head] = energy
                head = (head + 1) % HISTORY
                if (filled < HISTORY) filled++
            }
        } catch (e: Exception) {
            Log.w(TAG, "mic loop", e)
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    }

    companion object {
        const val RATE = 48000
        const val BLOCK = 1024
        private const val HISTORY = 43 // ~1s of blocks
        private const val ONSET_RATIO = 1.6f
        private const val MIN_BEAT_GAP_MS = 180L
        // A muted Portal mic reads peaks of 5-6; a quiet room reads in the hundreds.
        private const val SILENCE_CHECK_MS = 3000L
        private const val SILENT_PEAK = 32
    }
}
