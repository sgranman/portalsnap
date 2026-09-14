package net.sgran.portalsnap

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.Surface

/**
 * A Canvas the GPU can composite: a hardware-accelerated Surface whose consumer is an
 * OES texture on the render thread. Filters draw into it with the ordinary Canvas API.
 */
class CanvasLayer(handler: Handler) {
    val tex = genOesTexture()
    val matrix = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }
    private val st = SurfaceTexture(tex).apply {
        setDefaultBufferSize(FRAME_W, FRAME_H)
        setOnFrameAvailableListener({ }, handler)
    }
    private val surface = Surface(st)
    private var dirty = false

    inline fun paint(block: (Canvas) -> Unit) {
        val c = lock()
        try {
            c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            block(c)
        } finally {
            post(c)
        }
    }

    fun lock(): Canvas = surface.lockHardwareCanvas()

    fun post(c: Canvas) {
        surface.unlockCanvasAndPost(c)
        dirty = true
    }

    /** Clears once, then stops touching it — an idle layer should cost nothing. */
    fun clear() {
        if (!dirty) return
        paint { }
        dirty = false
    }

    /** Latches whatever buffer HWUI has finished; a no-op if nothing new arrived. */
    fun latch() {
        st.updateTexImage()
        st.getTransformMatrix(matrix)
    }
}

/** What the compositor has to do this frame. */
class Plan {
    var composite = false
    var base = true
    var under = false
    var over = false
    var mask = false
    var patches: List<Patch> = emptyList()

    fun reset() {
        composite = false
        base = true
        under = false
        over = false
        mask = false
        patches = emptyList()
    }
}

/**
 * The web app's render(): smoothing, building faces, and asking the active filter to
 * draw. Runs on the render thread, once per composited frame.
 */
class Painter {
    @Volatile var active: Filter? = null
    @Volatile var voice = 1f
        private set
    @Volatile var liveFaces = 0
        private set
    @Volatile var jitter = 0f
        private set

    val tracks = Tracks()
    val paintMs = Rolling()
    private val pen = Pen()
    private val draw = Draw(pen)
    private val plan = Plan()
    private var lastAt = 0L
    private var mode: Mode = Mode.FAST

    fun onFaces(faces: List<FaceAnchors>, forMode: Mode, now: Long) {
        if (forMode != mode) {
            mode = forMode
            tracks.reset()
        }
        tracks.maxFaces = Anchors.faceCap(forMode).coerceAtLeast(1)
        tracks.match(faces, now)
    }

    fun paint(now: Long, latencyMs: Float, maskFresh: Boolean, under: CanvasLayer, over: CanvasLayer): Plan {
        plan.reset()
        val dt = (now - lastAt).coerceIn(0, 50).toFloat()
        lastAt = now
        val f = active
        val t0 = SystemClock.elapsedRealtimeNanos()
        try {
            if (f == null) {
                idle(under, over)
                voice = 1f
                return plan
            }

            if (f.tier == Mode.SEGMENT) {
                liveFaces = 0
                if (!maskFresh) {
                    idle(under, over)
                    return plan
                }
                pen.reset()
                draw.t = now
                draw.patches.clear()
                under.paint { c ->
                    draw.c = c
                    guarded(f) { f.backdrop(draw) }
                }
                over.clear()
                voice = voiceOf(f, null)
                plan.composite = true
                plan.base = false
                plan.under = true
                plan.mask = true
                return plan
            }

            tracks.ease(dt, latencyMs)
            val live = tracks.live(now)
            liveFaces = live.size
            jitter = tracks.jitterPx()
            if (live.isEmpty()) {
                idle(under, over)
                voice = voiceOf(f, null)
                return plan
            }

            val faces = live.map { buildFace(it) }
            if (faces.size > 1) {
                faces.sortedBy { it.cx }.forEachIndexed { i, face -> face.rank = i }
                faces.forEach { it.count = faces.size }
            }
            voice = voiceOf(f, faces.maxByOrNull { it.eyeDist })

            pen.reset()
            draw.t = now
            draw.patches.clear()
            if (f.usesUnder) {
                under.paint { c ->
                    draw.c = c
                    guarded(f) {
                        f.scene(draw, faces)
                        faces.forEach { f.under(draw, it) }
                    }
                }
            } else {
                under.clear()
            }
            if (f.usesOver) {
                over.paint { c ->
                    draw.c = c
                    guarded(f) { faces.forEach { f.draw(draw, it) } }
                }
            } else {
                // Patch-only filters still run draw() for their patches, on a throwaway.
                over.clear()
                guarded(f) { faces.forEach { f.draw(draw, it) } }
            }

            plan.composite = true
            plan.base = !f.coversCamera
            plan.under = f.usesUnder
            plan.over = f.usesOver
            plan.patches = ArrayList(draw.patches)
            return plan
        } finally {
            paintMs.add((SystemClock.elapsedRealtimeNanos() - t0) / 1e6)
        }
    }

    private fun idle(under: CanvasLayer, over: CanvasLayer) {
        under.clear()
        over.clear()
    }

    // A filter that throws must not take the frame down with it; the canvas is posted
    // either way, and the next paint starts from a cleared layer.
    private inline fun guarded(f: Filter, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            Log.w(TAG, "filter ${f.id} threw", e)
        }
    }
}
