package net.sgran.portalsnap

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * A clip: H.264 from the compositor's frames, AAC from the mic through the pitch shifter,
 * into an mp4. The web app's biggest cost (canvas capture, +14ms a frame) does not exist
 * here — the encoder reads the composited texture straight off the GPU.
 */
class Recorder(private val file: File, wantAudio: Boolean) {
    val inputSurface: Surface
    @Volatile var voiceRatio = 1f
    @Volatile var hasAudio = false
        private set

    private val video: MediaCodec
    private var audio: MediaCodec? = null
    private var mic: AudioRecord? = null
    private var noise: NoiseSuppressor? = null
    private val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

    private val lock = Object()
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxing = false
    private val pending = ArrayList<Sample>()
    private val lastPts = LongArray(2) { -1 }
    private val startUs = System.nanoTime() / 1000
    @Volatile private var stopping = false

    private class Sample(val video: Boolean, val data: ByteArray, val pts: Long, val flags: Int)

    private val videoThread: Thread
    private var audioThread: Thread? = null

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, FRAME_W, FRAME_H).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BPS)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val (codec, surface) = openVideo(format)
        video = codec
        inputSurface = surface
        if (wantAudio) setupAudio()
        videoThread = thread(name = "rec-video") { drain(video, true, blocking = true) }
        if (hasAudio) audioThread = thread(name = "rec-audio") { audioLoop() }
    }

    // Hardware first, software as the last resort. On the gen 1 Portal every encoder —
    // hardware and software alike — was configured with the *decoder* role ("Failed to set
    // standard component role 'video_decoder.avc'", configure -> -1010) until the encoder
    // bit was passed explicitly: CONFIGURE_FLAG_ENCODE plus the "encoder" key that ACodec
    // reads from the format. See asEncoder().
    private fun openVideo(format: MediaFormat): Pair<MediaCodec, Surface> {
        val names = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { info -> info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
            .map { it.name }
            .sortedBy { if (it.startsWith("OMX.google.") || it.startsWith("c2.android.")) 1 else 0 }
        var last: Exception? = null
        for (name in names) {
            val codec = try {
                MediaCodec.createByCodecName(name)
            } catch (e: Exception) {
                last = e
                continue
            }
            try {
                codec.asEncoder(format)
                val surface = codec.createInputSurface()
                codec.start()
                Log.i(TAG, "video encoder: $name")
                return Pair(codec, surface)
            } catch (e: Exception) {
                Log.w(TAG, "video encoder $name refused", e)
                last = e
                codec.release()
            }
        }
        throw last ?: IllegalStateException("no H.264 encoder")
    }

    private fun setupAudio() {
        try {
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord(
                MediaRecorder.AudioSource.CAMCORDER, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, BLOCK * 8),
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                return
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.asEncoder(
                MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 1).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BPS)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, BLOCK * 4)
                },
            )
            codec.start()
            // Chrome also ran noise suppression on the mic; use the platform's where the
            // Portal offers one, so the gain below lifts voices rather than the room.
            if (NoiseSuppressor.isAvailable()) {
                noise = runCatching { NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true } }.getOrNull()
            }
            Log.i(TAG, "audio: noise suppressor ${if (noise?.enabled == true) "on" else "unavailable"}")
            mic = rec
            audio = codec
            hasAudio = true
        } catch (e: Exception) {
            // A missing voice must never cost a recording.
            Log.w(TAG, "audio unavailable", e)
            mic?.release()
            mic = null
            audio = null
            hasAudio = false
        }
    }

    private fun audioLoop() {
        val rec = mic ?: return
        val codec = audio ?: return
        val shifter = PitchShifter()
        val loudness = Loudness()
        val shorts = ShortArray(BLOCK)
        val floats = FloatArray(BLOCK)
        val bytes = ByteBuffer.allocate(BLOCK * 2).order(ByteOrder.LITTLE_ENDIAN)
        rec.startRecording()
        val t0 = System.nanoTime() / 1000
        var frames = 0L
        while (!stopping) {
            val n = rec.read(shorts, 0, BLOCK)
            if (n <= 0) continue
            for (i in 0 until n) floats[i] = shorts[i] / 32768f
            // Gain first, as Chrome's capture-side AGC was, so the voice is shifted at level.
            loudness.process(floats, n)
            shifter.process(floats, n, voiceRatio)
            bytes.clear()
            for (i in 0 until n) bytes.putShort((floats[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort())
            bytes.flip()
            val pts = t0 + frames * 1_000_000L / RATE
            frames += n
            val ib = codec.dequeueInputBuffer(10_000)
            if (ib >= 0) {
                val inBuf = codec.getInputBuffer(ib)!!
                inBuf.clear()
                inBuf.put(bytes)
                codec.queueInputBuffer(ib, 0, n * 2, pts, 0)
            }
            drain(codec, false, blocking = false)
        }
        try {
            rec.stop()
        } catch (_: Exception) {
        }
        val ib = codec.dequeueInputBuffer(50_000)
        if (ib >= 0) codec.queueInputBuffer(ib, 0, 0, t0 + frames * 1_000_000L / RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        drain(codec, false, blocking = true)
    }

    // Blocking drains run to end of stream; non-blocking ones take what is ready.
    private fun drain(codec: MediaCodec, isVideo: Boolean, blocking: Boolean) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, if (blocking) 10_000 else 0)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!blocking) return
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(lock) {
                    val track = muxer.addTrack(codec.outputFormat)
                    if (isVideo) videoTrack = track else audioTrack = track
                    maybeStart()
                }
                idx >= 0 -> {
                    val buf = codec.getOutputBuffer(idx)
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (buf != null && info.size > 0 && !config) write(isVideo, buf, info)
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private fun maybeStart() {
        if (muxing || videoTrack < 0 || (hasAudio && audioTrack < 0)) return
        muxer.start()
        muxing = true
        for (s in pending) writeNow(s.video, ByteBuffer.wrap(s.data), s.pts, s.flags)
        pending.clear()
    }

    private fun write(isVideo: Boolean, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        buf.position(info.offset)
        buf.limit(info.offset + info.size)
        val pts = maxOf(0L, info.presentationTimeUs - startUs)
        synchronized(lock) {
            if (muxing) {
                writeNow(isVideo, buf, pts, info.flags)
                return
            }
            pending += Sample(isVideo, ByteArray(info.size).also { buf.get(it) }, pts, info.flags)
            // If the mic never produces a format, carry on without it rather than lose the clip.
            if (isVideo && hasAudio && audioTrack < 0 && pending.count { it.video } > 45) {
                Log.w(TAG, "no audio format after 1.5s; recording silent")
                hasAudio = false
                maybeStart()
            }
        }
    }

    private fun writeNow(isVideo: Boolean, buf: ByteBuffer, pts: Long, flags: Int) {
        val track = if (isVideo) videoTrack else audioTrack
        if (track < 0) return
        val k = if (isVideo) 0 else 1
        val p = if (pts <= lastPts[k]) lastPts[k] + 1 else pts
        lastPts[k] = p
        val info = MediaCodec.BufferInfo().apply { set(0, buf.remaining(), p, flags) }
        muxer.writeSampleData(track, buf.slice(), info)
    }

    /** Blocks until both streams have flushed. Returns the clip, or null if nothing was written. */
    fun stop(): File? {
        stopping = true
        try {
            video.signalEndOfInputStream()
        } catch (e: Exception) {
            Log.w(TAG, "signalEndOfInputStream", e)
        }
        videoThread.join(4000)
        audioThread?.join(4000)
        var ok = false
        synchronized(lock) {
            try {
                if (muxing) {
                    muxer.stop()
                    ok = true
                }
            } catch (e: Exception) {
                Log.w(TAG, "muxer stop", e)
            }
            try {
                muxer.release()
            } catch (_: Exception) {
            }
        }
        runCatching { video.stop() }
        runCatching { video.release() }
        runCatching { audio?.stop() }
        runCatching { audio?.release() }
        runCatching { noise?.release() }
        runCatching { mic?.release() }
        inputSurface.release()
        return if (ok && file.length() > 0) file else null
    }

    private fun MediaCodec.asEncoder(format: MediaFormat) {
        format.setInteger("encoder", 1)
        configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }

    private companion object {
        const val RATE = 48000
        const val BLOCK = 1024
        // Native H.264 from the GPU is cheap, so this can sit above the web app's 2.5Mbps.
        const val VIDEO_BPS = 4_000_000
        const val AUDIO_BPS = 96_000
    }
}
