package net.sgransoft.portalsnap

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.tanh

/**
 * Everything the app plays, filter sound effects and music, is mixed here and sent two places:
 * the speaker, and the recorder, which adds the same samples to the clip after the voice
 * effect. A clip then carries the clink or the song cleanly and at its own pitch, instead of
 * the faint copy the mic hears in the room.
 *
 * The speaker walks each voice with its own cursor, so no sound ever loses its start. The
 * recorder addresses voices by time instead, (moment - start) x rate: its blocks carry mic
 * capture times on the same System.nanoTime clock, so it never has to keep pace with the
 * speaker thread.
 */
object Mixer {
    const val RATE = 48000
    private const val BLOCK = 960 // 20ms
    // A finished voice stays this long so the recorder, a block or two behind, still hears it.
    private const val KEEP_NS = 3_000_000_000L
    private const val MAX_SECONDS = 300

    /** Mono samples at their own rate. */
    class Clip(val pcm: ShortArray, val rate: Int)

    private class Voice(
        val id: Int, val clip: Clip, val startNs: Long, val gain: Float, val speed: Float, val loop: Boolean,
    ) {
        @Volatile var stopNs = Long.MAX_VALUE
        /** The speaker's position, in source samples. Speaker thread only. */
        var cursor = 0.0
        val endNs: Long = if (loop) Long.MAX_VALUE else startNs + (clip.pcm.size / (clip.rate * speed.toDouble()) * 1e9).toLong()
    }

    @Volatile private var voices: List<Voice> = emptyList()
    private val nextId = AtomicInteger(1)
    private val lock = Object()
    private var generation = 0

    /** Starts a sound now. Returns an id for [stop]. */
    fun play(clip: Clip, gain: Float = 1f, speed: Float = 1f, loop: Boolean = false): Int {
        val now = System.nanoTime()
        val v = Voice(nextId.getAndIncrement(), clip, now, gain, speed, loop)
        synchronized(lock) {
            voices = voices.filter { min(it.endNs, it.stopNs) > now - KEEP_NS } + v
            // No live speaker thread (never started, or shut down): start one. An old thread
            // still winding down sees the new generation and leaves.
            if (generation <= 0) {
                val gen = abs(generation) + 1
                generation = gen
                thread(name = "mixer", priority = Thread.MAX_PRIORITY) { speaker(gen) }
            }
            lock.notifyAll()
        }
        return v.id
    }

    fun stop(id: Int) {
        voices.firstOrNull { it.id == id }?.let { it.stopNs = min(it.stopNs, System.nanoTime()) }
    }

    fun stopAll() {
        val now = System.nanoTime()
        for (v in voices) v.stopNs = min(v.stopNs, now)
    }

    /** Stops everything and the speaker thread; the next [play] starts it again. */
    fun shutdown() {
        synchronized(lock) {
            stopAll()
            if (generation > 0) generation = -generation
            lock.notifyAll()
        }
    }

    fun softClip(x: Float): Float {
        val a = abs(x)
        return if (a <= 0.9f) x else Math.copySign(0.9f + 0.1f * tanh((a - 0.9f) / 0.1f), x)
    }

    /** Adds what was playing over the n samples from t0Ns into dst: the recorder's view. */
    fun mixInto(dst: FloatArray, n: Int, t0Ns: Long) {
        val vs = voices
        if (vs.isEmpty()) return
        val blockEnd = t0Ns + n * 1_000_000_000L / RATE
        for (v in vs) {
            if (v.startNs >= blockEnd || min(v.endNs, v.stopNs) <= t0Ns) continue
            val pcm = v.clip.pcm
            val len = pcm.size
            val step = v.clip.rate * v.speed.toDouble() / RATE
            var pos = (t0Ns - v.startNs) / 1e9 * v.clip.rate * v.speed
            val last = if (v.stopNs == Long.MAX_VALUE) n else ((v.stopNs - t0Ns) * RATE / 1_000_000_000L).toInt().coerceIn(0, n)
            for (i in 0 until last) {
                if (pos >= 0) {
                    var p = pos
                    if (v.loop) p %= len else if (p >= len - 1) break
                    val k = p.toInt()
                    val a = pcm[k]
                    val b = pcm[if (k + 1 < len) k + 1 else if (v.loop) 0 else k]
                    dst[i] += (a + (b - a) * (p - k).toFloat()) / 32768f * v.gain
                }
                pos += step
            }
        }
    }

    private fun speakerDone(v: Voice, now: Long) =
        v.stopNs <= now || (!v.loop && v.cursor >= v.clip.pcm.size - 1)

    private fun render(v: Voice, dst: FloatArray, n: Int) {
        val pcm = v.clip.pcm
        val len = pcm.size
        val step = v.clip.rate * v.speed.toDouble() / RATE
        var pos = v.cursor
        for (i in 0 until n) {
            if (v.loop) {
                if (pos >= len) pos -= len
            } else if (pos >= len - 1) {
                break
            }
            val k = pos.toInt()
            val a = pcm[k]
            val b = pcm[if (k + 1 < len) k + 1 else if (v.loop) 0 else k]
            dst[i] += (a + (b - a) * (pos - k).toFloat()) / 32768f * v.gain
            pos += step
        }
        v.cursor = pos
    }

    private fun speaker(gen: Int) {
        val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(min, BLOCK * 2 * 3))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "mixer: no AudioTrack", e)
            synchronized(lock) { if (generation == gen) generation = -gen }
            return
        }
        val mix = FloatArray(BLOCK)
        val out = ShortArray(BLOCK)
        var playing = false
        try {
            while (true) {
                synchronized(lock) { if (generation != gen) return }
                val now = System.nanoTime()
                val live = voices.filter { !speakerDone(it, now) }
                if (live.isEmpty()) {
                    // stop() plays out what's buffered, then idles the output.
                    if (playing) {
                        track.stop()
                        playing = false
                    }
                    synchronized(lock) { if (generation == gen) lock.wait(250) }
                    continue
                }
                if (!playing) {
                    track.play()
                    playing = true
                }
                Arrays.fill(mix, 0f)
                for (v in live) render(v, mix, BLOCK)
                for (i in 0 until BLOCK) out[i] = (softClip(mix[i]) * 32767f).toInt().toShort()
                track.write(out, 0, BLOCK)
            }
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    /** A 16-bit PCM WAV (mono or more channels, mixed down) as one mono clip, or null. */
    fun wav(bytes: ByteArray): Clip? {
        if (bytes.size < 44) return null
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" || String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        var pos = 12
        var channels = 0
        var rate = 0
        var bits = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val len = b.getInt(pos + 4)
            val body = pos + 8
            if (id == "fmt ") {
                channels = b.getShort(body + 2).toInt()
                rate = b.getInt(body + 4)
                bits = b.getShort(body + 14).toInt()
            } else if (id == "data") {
                if (bits != 16 || channels < 1 || rate <= 0) return null
                val frames = minOf(len, bytes.size - body) / (2 * channels)
                val pcm = ShortArray(frames)
                for (f in 0 until frames) {
                    var s = 0
                    for (ch in 0 until channels) s += b.getShort(body + (f * channels + ch) * 2)
                    pcm[f] = (s / channels).toShort()
                }
                return Clip(pcm, rate)
            }
            pos = body + len + (len and 1)
        }
        return null
    }

    /** A whole audio file as one mono clip, capped at five minutes, or null. */
    fun decode(path: String): Clip? {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(path)
            val index = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            ex.selectTrack(index)
            val fmt = ex.getTrackFormat(index)
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0)
            codec.start()
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var out = ShortArray(rate * 30)
            var size = 0
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var idle = 0
            try {
                while (idle < 200) {
                    if (!inputDone) {
                        val ib = codec.dequeueInputBuffer(10_000)
                        if (ib >= 0) {
                            val n = ex.readSampleData(codec.getInputBuffer(ib)!!, 0)
                            if (n < 0) {
                                codec.queueInputBuffer(ib, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(ib, 0, n, ex.sampleTime, 0)
                                ex.advance()
                            }
                        }
                    }
                    val ob = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        ob == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            rate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        ob >= 0 -> {
                            idle = 0
                            val buf = codec.getOutputBuffer(ob)!!.order(ByteOrder.LITTLE_ENDIAN)
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val sb = buf.asShortBuffer()
                            val frames = sb.remaining() / channels
                            if (size + frames > out.size) {
                                out = out.copyOf(minOf(rate * MAX_SECONDS, maxOf(out.size * 2, size + frames)))
                            }
                            val take = minOf(frames, out.size - size)
                            for (f in 0 until take) {
                                var sum = 0
                                for (ch in 0 until channels) sum += sb.get()
                                out[size++] = (sum / channels).toShort()
                            }
                            codec.releaseOutputBuffer(ob, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || size >= rate * MAX_SECONDS) break
                        }
                        inputDone -> idle++
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
            return if (size > 0) Clip(out.copyOf(size), rate) else null
        } catch (e: Exception) {
            Log.w(TAG, "decode $path", e)
            return null
        } finally {
            ex.release()
        }
    }
}
