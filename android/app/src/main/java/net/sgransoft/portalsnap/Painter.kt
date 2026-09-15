package net.sgransoft.portalsnap

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

/**
 * A full-frame shader that replaces the plain camera picture: Mirror, Pop Silhouette, Disco.
 * Filled in by the filter each frame; the fields mean what that kind's shader says they mean.
 */
class FrameFx {
    var kind = NONE
    /** Twenty more parameters for effects that need them (POP_ART, DISCO). */
    val q = FloatArray(20)
    val a = FloatArray(3)
    val b = FloatArray(3)
    val c = FloatArray(3)
    var x = 0f
    var y = 0f
    var p0 = 0f
    var p1 = 0f
    var p2 = 0f

    fun reset() {
        kind = NONE
        x = 0f
        y = 0f
        p0 = 0f
        p1 = 0f
        p2 = 0f
    }

    companion object {
        const val NONE = 0
        const val MIRROR = 1
        const val POP = 2
        const val DISCO = 3
        const val POP_ART = 4
    }
}

/** What the compositor has to do this frame. */
class Plan {
    var composite = false
    var base = true
    var under = false
    var over = false
    var mask = false
    var fx: FrameFx? = null
    var patches: List<Patch> = emptyList()
    var glasses: List<Glass3D> = emptyList()
    var pods: List<Pod3D> = emptyList()
    var rides: List<Ride3D> = emptyList()
    var falls: List<Fall3D> = emptyList()
    var hamsters: List<Hamster3D> = emptyList()

    fun reset() {
        composite = false
        base = true
        under = false
        over = false
        mask = false
        fx = null
        patches = emptyList()
        glasses = emptyList()
        pods = emptyList()
        rides = emptyList()
        falls = emptyList()
        hamsters = emptyList()
    }
}

/**
 * The web app's render(): smoothing, building faces, and asking the active filter to
 * draw. Runs on the render thread, once per composited frame.
 */
class Painter {
    @Volatile var active: Filter? = null
    @Volatile var mic: MicHub? = null
    /** adb's `--ef jaw`: pretend the mouth is this open, for testing on a still portrait. */
    @Volatile var debugJaw: Float? = null
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
    private val fx = FrameFx()
    private var lastAt = 0L
    private var mode: Mode = Mode.FAST

    /** The segmenter's latest mask, 1 for person and 0 for not, for filters that read its shape. */
    fun onMask(mask: ByteArray, w: Int, h: Int) {
        draw.mask = mask
        draw.maskW = w
        draw.maskH = h
    }

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

            pen.reset()
            draw.t = now
            draw.dt = dt
            draw.patches.clear()
            draw.glasses.clear()
            draw.pods.clear()
            draw.rides.clear()
            draw.falls.clear()
            draw.hamsters.clear()
            val m = mic
            draw.level = m?.level ?: 0f
            draw.beat = m?.beatPulse(now) ?: 0f
            draw.beats = m?.beats ?: 0
            draw.sinceBeatMs = m?.sinceBeat(now) ?: 1e9f

            if (f.tier == Mode.SEGMENT) {
                liveFaces = 0
                if (!maskFresh) {
                    idle(under, over)
                    return plan
                }
                guarded(f) { f.update(draw, emptyList()) }
                if (f.usesUnder) {
                    under.paint { c ->
                        draw.c = c
                        guarded(f) { f.backdrop(draw) }
                    }
                } else {
                    under.clear()
                }
                if (f.usesFx) plan.fx = frameFx(f, emptyList())
                if (f.usesOver) {
                    over.paint { c ->
                        draw.c = c
                        guarded(f) { f.overlay(draw, emptyList()) }
                    }
                } else {
                    over.clear()
                }
                voice = voiceOf(f, null)
                plan.composite = true
                plan.base = false
                plan.under = f.usesUnder
                plan.over = f.usesOver
                // A scene pastes the person over a backdrop; a frame shader reads the mask itself.
                plan.mask = !f.usesFx
                return plan
            }

            tracks.ease(dt, latencyMs)
            val live = tracks.live(now)
            liveFaces = live.size
            jitter = tracks.jitterPx()
            // Face filters rest with nobody in view; frame shaders keep playing.
            if (live.isEmpty() && !f.usesFx) {
                voice = voiceOf(f, null)
                // A scene that keeps itself (Lemonade's glass) stays up on its own ground rather
                // than dropping back to the camera the moment tracking blinks.
                if (f.keepsScene) {
                    guarded(f) { f.update(draw, emptyList()) }
                    under.paint { c ->
                        draw.c = c
                        guarded(f) { f.scene(draw, emptyList()) }
                    }
                    over.paint { c ->
                        draw.c = c
                        guarded(f) { f.overlay(draw, emptyList()) }
                    }
                    plan.composite = true
                    plan.base = !f.coversCamera
                    plan.under = true
                    plan.over = true
                    plan.patches = ArrayList(draw.patches)
                    plan.glasses = ArrayList(draw.glasses)
                    plan.pods = ArrayList(draw.pods)
                    plan.rides = ArrayList(draw.rides)
                    plan.falls = ArrayList(draw.falls)
                    plan.hamsters = ArrayList(draw.hamsters)
                    return plan
                }
                idle(under, over)
                return plan
            }

            val faces = live.map { buildFace(it, debugJaw) }
            if (faces.size > 1) {
                faces.sortedBy { it.cx }.forEachIndexed { i, face -> face.rank = i }
                faces.forEach { it.count = faces.size }
            }
            guarded(f) { f.update(draw, faces) }
            voice = voiceOf(f, faces.maxByOrNull { it.eyeDist })
            if (f.usesFx) plan.fx = frameFx(f, faces)

            val underNow = f.usesUnder && faces.isNotEmpty()
            if (underNow) {
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
                    guarded(f) {
                        faces.forEach { f.draw(draw, it) }
                        f.overlay(draw, faces)
                    }
                }
            } else {
                // Patch-only filters still run draw() for their patches, on a throwaway.
                over.clear()
                guarded(f) { faces.forEach { f.draw(draw, it) } }
            }

            plan.composite = true
            plan.base = !f.coversCamera
            plan.under = underNow
            plan.over = f.usesOver
            plan.patches = ArrayList(draw.patches)
            plan.glasses = ArrayList(draw.glasses)
            plan.pods = ArrayList(draw.pods)
            plan.rides = ArrayList(draw.rides)
            plan.falls = ArrayList(draw.falls)
            plan.hamsters = ArrayList(draw.hamsters)
            return plan
        } finally {
            paintMs.add((SystemClock.elapsedRealtimeNanos() - t0) / 1e6)
        }
    }

    private fun frameFx(f: Filter, faces: List<Face>): FrameFx? {
        fx.reset()
        guarded(f) { f.fx(draw, faces, fx) }
        return if (fx.kind == FrameFx.NONE) null else fx
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
