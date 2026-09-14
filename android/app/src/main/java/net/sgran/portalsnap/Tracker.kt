package net.sgran.portalsnap

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.framework.image.ByteBufferImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future

class TrackResult(val faces: List<FaceAnchors>, val mask: ByteArray?, val maskW: Int, val maskH: Int, val mode: Mode)

/**
 * MediaPipe on its own thread, one frame in flight — the shape of tracker.worker.js.
 *
 * Unlike the web app, models are kept once loaded: the gen 1 Portal has 3.8GB and the
 * native runtime has no WASM heap to fit into, so switching from the puppy to the beach
 * and back costs nothing after the first time. They load on a separate thread so a
 * preload never stalls detection.
 */
class Tracker(private val ctx: Context) {
    @Volatile var onResult: ((TrackResult) -> Unit)? = null

    private val handler = Handler(HandlerThread("tracker").apply { start() }.looper)
    private val loader = Executors.newSingleThreadExecutor()
    private val models = ConcurrentHashMap<Mode, Future<Model?>>()

    private class Model(val task: Any, val delegate: String, val loadMs: Long) {
        var successes = 0L
    }

    @Volatile var mode = Mode.FAST
        private set
    @Volatile var ready = false
        private set
    @Volatile var busy = false
        private set
    @Volatile var delegate = "-"
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile var lastInferMs = 0.0
        private set
    @Volatile var lastGrabMs = 0.0
        private set

    val infer = Rolling()
    val grab = Rolling()
    val rate = RateMeter()
    val detections = Counter()

    private var current: Model? = null
    private var lastTs = 0L

    fun loadMs(m: Mode): Long? {
        val f = models[m] ?: return null
        return if (f.isDone) runCatching { f.get()?.loadMs }.getOrNull() else null
    }

    fun preload(vararg modes: Mode) {
        for (m in modes) future(m)
    }

    private fun future(m: Mode): Future<Model?> =
        models.computeIfAbsent(m) { loader.submit(Callable { load(m) }) }

    /** Switches tier. Instant when the model is already loaded; onReady runs on the tracker thread. */
    fun select(m: Mode, onReady: (Boolean) -> Unit = {}) {
        ready = false
        val f = future(m)
        handler.post {
            val model = runCatching { f.get() }.getOrNull()
            mode = m
            current = model
            delegate = model?.delegate ?: "none"
            infer.clear()
            grab.clear()
            rate.clear()
            busy = false
            ready = model != null
            onReady(ready)
        }
    }

    fun resetStats() = handler.post {
        infer.clear()
        grab.clear()
        rate.clear()
    }

    /** Returns false (and takes nothing) while a frame is already in flight. */
    fun submit(rgba: ByteBuffer, w: Int, h: Int, grabMs: Double): Boolean {
        if (busy || !ready) return false
        busy = true
        handler.post {
            val m = mode
            val model = current
            val t0 = SystemClock.elapsedRealtimeNanos()
            var faces: List<FaceAnchors> = emptyList()
            var mask: ByteArray? = null
            var mw = 0
            var mh = 0
            try {
                rgba.rewind()
                val img = ByteBufferImageBuilder(rgba, w, h, MPImage.IMAGE_FORMAT_RGBA).build()
                val ts = maxOf(lastTs + 1, SystemClock.uptimeMillis())
                lastTs = ts
                when (val task = model?.task) {
                    is FaceDetector -> faces = Anchors.fromDetections(task.detectForVideo(img, ts), Anchors.faceCap(Mode.FAST))
                    is FaceLandmarker -> faces = Anchors.fromLandmarks(task.detectForVideo(img, ts), Anchors.faceCap(Mode.MESH))
                    is ImageSegmenter -> {
                        // Confidence, not categories: a soft edge the compositor can smooth over
                        // time and the shaders can snap to the picture. The category mask was a
                        // hard 256x144 staircase.
                        val masks = task.segmentForVideo(img, ts).confidenceMasks().orElse(null)
                        val cm = masks?.lastOrNull()
                        if (cm != null) {
                            val fb = ByteBufferExtractor.extract(cm).order(ByteOrder.nativeOrder()).asFloatBuffer()
                            mw = cm.width
                            mh = cm.height
                            val out = ByteArray(mw * mh)
                            for (i in 0 until minOf(out.size, fb.limit())) {
                                out[i] = (fb.get(i).coerceIn(0f, 1f) * 255f).toInt().toByte()
                            }
                            mask = out
                        }
                        masks?.forEach { it.close() }
                    }
                }
                img.close()
                model?.let { it.successes++ }
            } catch (e: Throwable) {
                lastError = e.message
                Log.w(TAG, "inference failed on $delegate", e)
                // A GPU delegate can accept the graph and still fail on its first frame.
                if (model != null && model.delegate == "GPU" && model.successes == 0L) {
                    val cpu = build(m, Delegate.CPU)
                    models[m] = CompletableFuture.completedFuture(cpu)
                    current = cpu
                    delegate = cpu?.delegate ?: "none"
                    if (cpu == null) ready = false
                }
            }
            val ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
            lastInferMs = ms
            lastGrabMs = grabMs
            infer.add(ms)
            grab.add(grabMs)
            rate.tick()
            detections.inc()
            busy = false
            onResult?.invoke(TrackResult(faces, mask, mw, mh, m))
        }
        return true
    }

    // The segmenter runs on the CPU. On the gen 1 Portal's Adreno 540, converting its GPU
    // category mask aborts the whole process (image_frame.cc: "ImageFormat::UNKNOWN !=
    // format_") — a native CHECK, so there is no exception to fall back from. The model is
    // tiny and fed 256x144, so the CPU costs little.
    private fun load(m: Mode): Model? =
        if (m == Mode.SEGMENT) build(m, Delegate.CPU) else build(m, Delegate.GPU) ?: build(m, Delegate.CPU)

    private fun build(m: Mode, d: Delegate): Model? {
        val t0 = SystemClock.elapsedRealtime()
        return try {
            val base = { asset: String -> BaseOptions.builder().setModelAssetPath(asset).setDelegate(d).build() }
            val task: Any = when (m) {
                Mode.FAST -> FaceDetector.createFromOptions(
                    ctx,
                    FaceDetector.FaceDetectorOptions.builder()
                        .setBaseOptions(base("blaze_face_short_range.tflite"))
                        .setRunningMode(RunningMode.VIDEO)
                        .build(),
                )
                Mode.MESH -> FaceLandmarker.createFromOptions(
                    ctx,
                    FaceLandmarker.FaceLandmarkerOptions.builder()
                        .setBaseOptions(base("face_landmarker.task"))
                        .setRunningMode(RunningMode.VIDEO)
                        .setNumFaces(Anchors.faceCap(Mode.MESH))
                        .setOutputFaceBlendshapes(true)
                        // Head pose, for the nod that switches Monster / Cutie.
                        .setOutputFacialTransformationMatrixes(true)
                        .build(),
                )
                Mode.SEGMENT -> ImageSegmenter.createFromOptions(
                    ctx,
                    ImageSegmenter.ImageSegmenterOptions.builder()
                        .setBaseOptions(base("selfie_segmenter_landscape.tflite"))
                        .setRunningMode(RunningMode.VIDEO)
                        .setOutputCategoryMask(false)
                        .setOutputConfidenceMasks(true)
                        .build(),
                )
            }
            val ms = SystemClock.elapsedRealtime() - t0
            Log.i(TAG, "model $m loaded on ${d.name} in ${ms}ms")
            Model(task, d.name, ms)
        } catch (e: Throwable) {
            lastError = e.message
            Log.w(TAG, "model $m failed on ${d.name}", e)
            null
        }
    }
}
