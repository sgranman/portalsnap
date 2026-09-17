package net.sgransoft.portalsnap

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
import android.graphics.Matrix as GfxMatrix
import android.opengl.Matrix as GlMatrix

const val FRAME_W = 1280
const val FRAME_H = 720

private val IDENTITY_3X3 = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

// The camera's buffers arrive mirrored with no flip in their transform (gen 1 Portal, camera 0).
private const val CAMERA_MIRRORS = true

/** GLSL's smoothstep: 0 below [a], 1 above [b], eased between. */
private fun smoothstep(a: Float, b: Float, x: Float): Float {
    val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

// How far each segmentation result moves the smoothed mask (1 = no smoothing). Two of them, so a
// still edge could be averaged harder than a moving one: a still edge that moves would be the
// model boiling rather than the person. Measured on a frozen frame (`--ez freeze`), it does not
// boil — the mask is identical frame to frame, and what looked like boiling was the subject
// moving. So both start at the same rate and the mechanism sits idle, ready if a noisier camera
// ever needs it; `--ef maskStill` turns it down. MASK_MOVE_LO and _HI are the confidence gap the
// two are chosen between.
private const val MASK_SMOOTH_STILL = 0.75f
private const val MASK_SMOOTH_MOVE = 0.75f
private const val MASK_MOVE_LO = 0.25f
private const val MASK_MOVE_HI = 0.60f
// A pixel already counted as person keeps the benefit of the doubt by this much, and one that
// isn't has to clear it. Stops the edge's pixels toggling across the halfway mark frame by frame,
// which is what walks the crop's bounds about.
private const val MASK_HYSTERESIS = 0.08f

// Cut-out filters show each frame only once its own mask is back, so the cut-out never trails
// the picture; if a mask takes longer than this, the frame goes out anyway.
private const val SYNC_CUTOUT = true
private const val SYNC_TIMEOUT_MS = 250L

// The segmenter's crop: never tighter than this share of the frame on either side; the person's
// bounds grown by these; the model's view stretched no more than this; and the whole frame when
// fewer than this many (every-other) mask pixels are person.
private const val SEG_ROI_MIN = 0.4f
private const val SEG_ROI_MARGIN_X = 1.35f
private const val SEG_ROI_MARGIN_Y = 1.15f
private const val SEG_ROI_MAX_STRETCH = 1.6f
private const val SEG_ROI_MIN_PIXELS = 60

/**
 * The render thread. Everything GL lives here.
 *
 * Every picture is kept in one space — the upright, unmirrored 1280x720 frame — and
 * only the screen pass mirrors. That is the web app's split (mirrored stage, unmirrored
 * captures) without its cost: the frame that reaches the screen, the encoder, a photo
 * and the tracker is composited once, on the GPU, and never copied to the CPU except
 * for the tracker's 320x180 and a still.
 */
class Compositor(private val tracker: Tracker, private val painter: Painter) {
    private val thread = HandlerThread("render").apply { start() }
    private val handler = Handler(thread.looper)
    private val jpegs = Executors.newSingleThreadExecutor()

    private lateinit var egl: EglCore
    private lateinit var pOes: Program
    private lateinit var pTex: Program
    private lateinit var pPatch: Program
    private lateinit var pMask: Program
    private lateinit var pMirror: Program
    private lateinit var pPop: Program
    private lateinit var pDisco: Program
    private lateinit var pPopArt: Program
    private lateinit var pPopGround: Program
    // The camera draws into frame. With a cut-out filter, each frame waits (held) for its own
    // mask while the camera moves on to the other buffer; composites read shown, the frame going
    // out.
    private lateinit var frame: Fbo
    private lateinit var frameA: Fbo
    private lateinit var frameB: Fbo
    private lateinit var shown: Fbo
    private var held: Fbo? = null
    private var heldAt = 0L
    private var lastOutAt = 0L
    private var freshSinceSubmit = false
    /** True while cut-out frames wait for their own masks. */
    @Volatile var synced = false
        private set
    private lateinit var comp: Fbo
    private lateinit var under: CanvasLayer
    private lateinit var over: CanvasLayer
    private var small: Fbo? = null
    private var smallBuf: ByteBuffer? = null
    private var lastOut: Fbo? = null
    // Built on first use. If it ever throws (a shader the driver rejects), 3D stays off rather
    // than failing every frame after it.
    private var glassRenderer: GlassRenderer? = null
    private var podRenderer: PodRenderer? = null
    private var rideRenderer: RideRenderer? = null
    private var fallRenderer: FreefallRenderer? = null
    private var hamsterRenderer: HamsterRenderer? = null
    private var aviatorRenderer: AviatorRenderer? = null
    private var catRenderer: CatRenderer? = null
    private var alienRenderer: AlienRenderer? = null
    private var alienFailed = false
    /** The app's assets, for 3D passes that load pictures (Freefall's ground). */
    private lateinit var assets: AssetManager
    private var glassFailed = false

    private var camTex = 0
    private var camSt: SurfaceTexture? = null
    private val camMatrix = FloatArray(16)
    private val user = FloatArray(16)
    private val texM = FloatArray(16)
    private val posM = FloatArray(16)

    private var window: EGLSurface? = null
    private var winW = 0
    private var winH = 0

    private var encoder: EGLSurface? = null
    private var recorder: Recorder? = null

    private var testTex = 0
    private var testAspect = 0f
    private val testCrop = FloatArray(16)
    private var testTicking = false

    private var maskTex = 0
    private var maskW = 0
    private var maskH = 0
    private var maskAt = 0L
    private var maskBuf: ByteBuffer? = null
    // Pop Art's red and dark grunge, drawn once on the first frame that asks for them. They are
    // functions of the pixel alone, so they never change; see FX_POP_GROUND.
    private var groundRed: Fbo? = null
    private var groundDark: Fbo? = null

    // The same mask as a moment ago, three times over, for motion echoes: refreshed every 90ms.
    private val echoTex = IntArray(3)
    private val echoAt = LongArray(3)
    private var echoLast = 0L
    private var personMask = ByteArray(0)
    private var maskEma = FloatArray(0)
    private var maskInverted = false
    // adb's dials (MainActivity.applyIntent), so these can be turned with someone actually
    // standing in front of the Portal. The defaults are the constants above.
    @Volatile var maskStill = MASK_SMOOTH_STILL
    @Volatile var maskMove = MASK_SMOOTH_MOVE
    /**
     * Where the cut-out's edge falls, and how hard camera colour snaps it: see Shaders.MASK.
     * The edge used to run 0.45-0.75, chosen to keep the model's soft edge from leaning out into
     * the room. That erodes: a raised hand lost its thumb, because a thing one or two mask texels
     * wide never reaches 0.45 once its neighbourhood is averaged in. Measured on a frozen frame,
     * moving the edge down to 0.30 pushes a strong boundary (hair against a window) out by only
     * three to six pixels, which is far less than it gives back on thin things.
     */
    @Volatile var cutLo = 0.30f
    @Volatile var cutHi = 0.60f
    @Volatile var cutColour = 60f
    /**
     * How much a pixel's own mask sample outweighs its neighbours. Raising it was meant to hold
     * thin things together; measured, it does the opposite — the edge gets smoother and loses
     * detail (1659px of boundary at 1, 1539px at 8), because the colour-weighted neighbourhood
     * is where the detail was coming from. Left at what it always was.
     */
    @Volatile var cutCentre = 2f
    /** Show the segmenter the whole frame instead of a crop around the person. */
    @Volatile var segFullFrame = false
    /**
     * Hold the picture still while everything downstream keeps running: the segmenter goes on
     * re-reading the same frame, so two models or two settings can be compared on one input
     * instead of on a person who has moved between the shots. The frame is kept in its own
     * buffer because the camera's two swap under it.
     */
    @Volatile var freeze = false
    /** Draw the cut-out itself, white on black, instead of the picture. For measuring it. */
    @Volatile var maskView = false
    private var frozen: Fbo? = null
    // The part of the frame the segmenter looks at next (x, y, w, h in 0..1, y down): around the
    // person, so the model's few pixels go to their outline instead of the room.
    private val segRoi = floatArrayOf(0f, 0f, 1f, 1f)
    private val cropM = FloatArray(16)
    /** How much of the frame's width the segmenter's crop covers, 0..1. */
    @Volatile var segCrop = 1f
        private set

    private var photo: ((ByteArray?) -> Unit)? = null

    @Volatile var rotation = 0
    @Volatile var sourceW = FRAME_W
    @Volatile var sourceH = FRAME_H
    /** Debug: rocks the test portrait side to side this many degrees, swaying it as it goes. */
    @Volatile var testRock = 0f
    /** 0 means the camera; 1 or 2 feeds the test portrait instead. */
    @Volatile var testFaces = 0
        private set
    @Volatile var hasTestImage = false
        private set
    @Volatile var segShare = 0f
        private set

    val cameraRate = RateMeter()
    val renderRate = RateMeter()
    val recRate = RateMeter()
    val renderFrames = Counter()
    val frameMs = Rolling()

    init {
        tracker.onResult = { r -> handler.post { onTrack(r) } }
    }

    fun start(context: Context, onCameraTexture: (SurfaceTexture) -> Unit) {
        assets = context.applicationContext.assets
        handler.post {
            egl = EglCore()
            pOes = Program(Shaders.VERTEX, Shaders.OES)
            pTex = Program(Shaders.VERTEX, Shaders.TEX)
            pPatch = Program(Shaders.VERTEX, Shaders.PATCH)
            pMask = Program(Shaders.VERTEX, Shaders.MASK)
            pMirror = Program(Shaders.VERTEX, Shaders.FX_MIRROR)
            pPop = Program(Shaders.VERTEX, Shaders.FX_POP)
            pDisco = Program(Shaders.VERTEX, Shaders.FX_DISCO)
            pPopArt = Program(Shaders.VERTEX, Shaders.FX_POP_ART)
            pPopGround = Program(Shaders.VERTEX, Shaders.FX_POP_GROUND)
            frameA = Fbo(FRAME_W, FRAME_H)
            frameB = Fbo(FRAME_W, FRAME_H)
            frame = frameA
            shown = frameA
            comp = Fbo(FRAME_W, FRAME_H)
            comp.attachDepth()
            under = CanvasLayer(handler)
            over = CanvasLayer(handler)
            maskTex = genTexture(GLES20.GL_TEXTURE_2D)
            for (i in echoTex.indices) echoTex[i] = genTexture(GLES20.GL_TEXTURE_2D)

            camTex = genOesTexture()
            val st = SurfaceTexture(camTex)
            st.setOnFrameAvailableListener({ onCameraFrame() }, handler)
            camSt = st

            loadTestImage(context)
            onCameraTexture(st)
        }
    }

    fun setTestFaces(n: Int) = handler.post {
        testFaces = if (hasTestImage) n.coerceIn(0, 2) else 0
        if (testFaces > 0 && !testTicking) {
            testTicking = true
            handler.post(testTick)
        }
    }

    fun setWindow(surface: Surface, w: Int, h: Int) = handler.post {
        if (window == null) window = egl.windowSurface(surface)
        winW = w
        winH = h
    }

    /** Blocks: the surface is gone the moment this returns. */
    fun releaseWindow() {
        val done = CountDownLatch(1)
        handler.post {
            window?.let {
                egl.makePbufferCurrent()
                egl.release(it)
            }
            window = null
            done.countDown()
        }
        done.await(2, TimeUnit.SECONDS)
    }

    fun requestPhoto(cb: (ByteArray?) -> Unit) = handler.post { photo = cb }

    fun startRecording(rec: Recorder) = handler.post {
        encoder = egl.windowSurface(rec.inputSurface)
        recorder = rec
        recRate.clear()
    }

    /** Detaches the encoder, then hands back the last composited frame as the clip's poster. */
    fun stopRecording(onPoster: (ByteArray?) -> Unit) = handler.post {
        encoder?.let {
            egl.makePbufferCurrent()
            egl.release(it)
        }
        encoder = null
        recorder = null
        val out = lastOut
        if (out != null) capture(out, 72, onPoster) else jpegs.execute { onPoster(null) }
    }

    private fun onCameraFrame() {
        cameraRate.tick()
        if (testFaces == 0) {
            render()
        } else {
            camSt?.updateTexImage()
        }
    }

    // Fixed-rate, like a camera: scheduling 33ms after each frame finished ran at ~25fps.
    private var nextTick = 0L
    private val testTick = object : Runnable {
        override fun run() {
            if (testFaces == 0) {
                testTicking = false
                return
            }
            render()
            val now = SystemClock.uptimeMillis()
            nextTick = if (now - nextTick > 100) now + 33 else nextTick + 33
            handler.postAtTime(this, nextTick)
        }
    }

    private fun onTrack(r: TrackResult) {
        val now = SystemClock.uptimeMillis()
        val mask = r.mask
        if (mask != null) {
            uploadMask(mask, r.maskW, r.maskH, now)
            val h = held
            if (h != null) {
                held = null
                try {
                    // The newest frame that came in while that one waited goes to the segmenter
                    // first, so its inference runs while this one composites and waits on the
                    // screen. Doing them one after the other halved Pop Art's frame rate.
                    if (freshSinceSubmit && syncWanted()) submitHeld(now)
                    output(h, now)
                } catch (e: Throwable) {
                    Log.e(TAG, "render failed", e)
                }
            }
        } else {
            painter.onFaces(r.faces, r.mode, now)
        }
    }

    private fun render() {
        val t0 = SystemClock.elapsedRealtimeNanos()
        val now = SystemClock.uptimeMillis()
        try {
            val win = window
            if (win != null) egl.makeCurrent(win) else egl.makePbufferCurrent()

            // 1. The upright, unmirrored frame. Frozen, the first one is kept and copied into
            //    every frame after it, so both swap buffers carry the same picture.
            val keep = if (freeze) frozen ?: Fbo(FRAME_W, FRAME_H).also {
                it.bind()
                if (testFaces > 0) drawTest(now) else drawCamera()
                frozen = it
                Log.i(TAG, "frame frozen")
            } else null
            frame.bind()
            if (keep != null) {
                GLES20.glDisable(GLES20.GL_BLEND)
                drawTexture(keep.tex, Program.IDENTITY, Program.IDENTITY)
            } else {
                frozen?.let {
                    it.release()
                    frozen = null
                    Log.i(TAG, "frame unfrozen")
                }
                if (testFaces > 0) drawTest(now) else drawCamera()
            }

            // 2. With a cut-out filter, this frame waits for its own mask and onTrack shows it,
            //    so the cut-out always matches the picture; frames that arrive while the
            //    segmenter is busy are dropped. Otherwise the tracker gets a copy when it's idle,
            //    and the frame goes out now with the latest results.
            if (syncWanted()) {
                synced = true
                freshSinceSubmit = true
                val h = held
                if (h == null) {
                    submitHeld(now)
                    // Nothing waiting and the segmenter still busy (a slow model, or a result
                    // that never came): don't freeze; show this frame with the last mask.
                    if (held == null && now - lastOutAt > SYNC_TIMEOUT_MS) output(frame, now)
                } else if (now - heldAt > SYNC_TIMEOUT_MS) {
                    // The segmenter is slow or stuck: show the held frame rather than freeze.
                    held = null
                    output(h, now)
                    submitHeld(now)
                }
                return
            }
            synced = false
            held = null
            feedTracker()
            output(frame, now)
        } catch (e: Throwable) {
            Log.e(TAG, "render failed", e)
        } finally {
            frameMs.add((SystemClock.elapsedRealtimeNanos() - t0) / 1e6)
        }
    }

    // 3 to 5 for one camera frame: filters, then the screen and the encoder.
    private fun output(src: Fbo, now: Long) {
        shown = src
        lastOutAt = now
        val win = window
        if (win != null) egl.makeCurrent(win) else egl.makePbufferCurrent()

        // 3. Filters.
        val latency = (tracker.lastGrabMs + tracker.lastInferMs).toFloat()
        val plan = painter.paint(now, latency, now - maskAt < 800, under, over)
        val out = if (plan.composite) {
            composite(plan)
            comp
        } else {
            src
        }
        lastOut = out

        photo?.let {
            photo = null
            capture(out, 92, it)
        }

        // 4. Screen, mirrored like a mirror.
        if (win != null) {
            screen(out)
            egl.swap(win)
        }

        // 5. Encoder, unmirrored, the way the room looked.
        val enc = encoder
        if (enc != null) {
            egl.makeCurrent(enc)
            GLES20.glViewport(0, 0, FRAME_W, FRAME_H)
            GLES20.glDisable(GLES20.GL_BLEND)
            drawTexture(out.tex, Program.IDENTITY, Program.IDENTITY)
            egl.presentationTime(enc, System.nanoTime())
            egl.swap(enc)
            recRate.tick()
            recorder?.voiceRatio = painter.voice
            recorder?.voiceFx = painter.voiceFx
        }

        renderRate.tick()
        renderFrames.inc()
    }

    private fun syncWanted() =
        SYNC_CUTOUT && painter.active?.tier == Mode.SEGMENT && tracker.mode == Mode.SEGMENT && tracker.ready

    // Sends the frame just drawn to the segmenter and holds it until its mask comes back; the
    // camera draws its next frames into the other buffer.
    private fun submitHeld(now: Long) {
        if (!feedTracker()) return
        held = frame
        heldAt = now
        freshSinceSubmit = false
        frame = if (frame === frameA) frameB else frameA
    }

    private fun drawCamera() {
        val st = camSt ?: return
        st.updateTexImage()
        st.getTransformMatrix(camMatrix)
        readCameraTransform()
        val (cx, cy) = cover(FRAME_W, FRAME_H)
        GlMatrix.setIdentityM(user, 0)
        GlMatrix.translateM(user, 0, 0.5f, 0.5f, 0f)
        GlMatrix.rotateM(user, 0, rotation.toFloat(), 0f, 0f, 1f)
        // Undo any mirror in the buffers, so the frame is the room as it is. The Portal's smart
        // camera mirrors its picture without saying so in the transform (flip=false), which
        // left the screen pass un-mirroring it: book spines read normally on screen, and you
        // moved the opposite way to your reflection. So that hidden mirror is undone as well.
        GlMatrix.scaleM(user, 0, if (stFlip != CAMERA_MIRRORS) -cx else cx, cy, 1f)
        GlMatrix.translateM(user, 0, -0.5f, -0.5f, 0f)
        GlMatrix.multiplyMM(texM, 0, camMatrix, 0, user, 0)
        GLES20.glDisable(GLES20.GL_BLEND)
        pOes.use()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, camTex)
        GLES20.glUniform1i(pOes.u("uTexture"), 0)
        pOes.drawQuad(Program.IDENTITY, texM)
    }

    // The camera service tags front-camera buffers with a transform: the sensor's quarter
    // turn and a horizontal flip (CameraUtils::getRotationTransform). SurfaceTexture folds
    // that into the matrix, so the texture is already turned and mirrored before our own
    // rotation applies. Read both off the matrix rather than assuming either.
    private var stSwap = false
    private var stFlip = false
    private var stLogged = ""

    private fun readCameraTransform() {
        val m00 = camMatrix[0]
        val m10 = camMatrix[1]
        val m01 = camMatrix[4]
        val m11 = camMatrix[5]
        stSwap = kotlin.math.abs(m00) < 0.5f && kotlin.math.abs(m11) < 0.5f
        stFlip = m00 * m11 - m01 * m10 < 0
        val summary = "swap=$stSwap flip=$stFlip m=[%.1f %.1f %.1f %.1f]".format(m00, m01, m10, m11)
        if (summary != stLogged) {
            stLogged = summary
            Log.i(TAG, "camera transform $summary rotation=$rotation")
        }
    }

    // Crop factors that fill a target from the upright picture: the buffer's own
    // dimensions, swapped once for each quarter turn (the transform's and ours).
    private fun cover(outW: Int, outH: Int): Pair<Float, Float> {
        val upright = stSwap == (rotation % 180 != 0)
        val upW = if (upright) sourceW else sourceH
        val upH = if (upright) sourceH else sourceW
        val target = outW.toFloat() / outH
        val source = upW.toFloat() / upH
        return if (source > target) Pair(target / source, 1f) else Pair(1f, source / target)
    }

    // A stand-in for the camera: the portrait drifting and breathing, so easing,
    // prediction and multi-face matching all have something to do.
    private fun drawTest(now: Long) {
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glClearColor(0.10f, 0.11f, 0.14f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        pTex.use()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, testTex)
        GLES20.glUniform1i(pTex.u("uTexture"), 0)
        val n = testFaces
        for (i in 0 until n) {
            val dh = FRAME_H * (if (n == 1) 0.92f else 0.74f) * (1 + 0.04f * sin(now / 1700f + i))
            val dw = dh * testAspect
            val rock = testRock
            val x = FRAME_W * (i + 1f) / (n + 1) + sin(now / 900f + i * 2) * 36f + sin(now / 1500f) * rock * 5f
            val y = FRAME_H / 2f + sin(now / 1300f + i) * 14f
            GlMatrix.setIdentityM(posM, 0)
            GlMatrix.translateM(posM, 0, x / FRAME_W * 2 - 1, 1 - y / FRAME_H * 2, 0f)
            // In pixels, so turning it doesn't shear it: frame px to clip, the turn, then its size.
            GlMatrix.scaleM(posM, 0, 2f / FRAME_W, 2f / FRAME_H, 1f)
            if (rock != 0f) GlMatrix.rotateM(posM, 0, sin(now / 1100f) * rock, 0f, 0f, 1f)
            GlMatrix.scaleM(posM, 0, dw / 2, dh / 2, 1f)
            pTex.drawQuad(posM, testCrop)
        }
    }

    private fun feedTracker(): Boolean {
        if (tracker.busy || !tracker.ready) return false
        val m = tracker.mode
        val t0 = SystemClock.elapsedRealtimeNanos()
        // The segmenter's input follows its model; the face tiers keep their measured sizes.
        val iw = if (m == Mode.SEGMENT) tracker.segInputW else m.inputW
        val ih = if (m == Mode.SEGMENT) tracker.segInputH else m.inputH
        var target = small
        if (target == null || target.w != iw || target.h != ih) {
            target?.release()
            target = Fbo(iw, ih)
            small = target
            smallBuf = ByteBuffer.allocateDirect(iw * ih * 4).order(ByteOrder.nativeOrder())
        }
        // Mipmapped, so a 4x downscale averages pixels instead of skipping them.
        frame.generateMipmaps()
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
        target.bind()
        GLES20.glDisable(GLES20.GL_BLEND)
        // Flipped, so row 0 of the readback is the top of the picture. The segmenter gets its
        // crop: texture u = x + s w, t = 1 - (y + v h), since row 0 must be the crop's top.
        val crop = m == Mode.SEGMENT
        if (crop) {
            GlMatrix.setIdentityM(cropM, 0)
            cropM[0] = segRoi[2]
            cropM[5] = -segRoi[3]
            cropM[12] = segRoi[0]
            cropM[13] = 1f - segRoi[1]
        }
        drawTexture(frame.tex, Program.IDENTITY, if (crop) cropM else FLIP_V)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frame.tex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        val buf = smallBuf!!
        target.read(buf)
        return tracker.submit(buf, target.w, target.h, (SystemClock.elapsedRealtimeNanos() - t0) / 1e6, if (crop) segRoi else FULL_FRAME)
    }

    private fun composite(plan: Plan) {
        if (plan.fx?.kind == FrameFx.POP_ART) bakePopGround()
        // Cat Hat's kittens are drawn off to one side first, so the composite isn't interrupted.
        if (plan.cats.isNotEmpty() && !glassFailed) {
            try {
                val r = catRenderer ?: CatRenderer(assets).also { catRenderer = it }
                r.prepare(plan.cats)
            } catch (e: Throwable) {
                glassFailed = true
                Log.e(TAG, "kitten pass failed; turning 3D off", e)
                GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
            }
        }
        comp.bind()
        val fx = plan.fx
        if (fx != null) {
            drawFx(fx)
        } else if (plan.base) {
            GLES20.glDisable(GLES20.GL_BLEND)
            drawTexture(shown.tex, Program.IDENTITY, Program.IDENTITY)
        } else {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        }
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        under.latch()
        over.latch()
        if (plan.under) drawLayer(under)

        if (plan.mask && maskW > 0) {
            pMask.use()
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTex)
            GLES20.glUniform1i(pMask.u("uMask"), 1)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, shown.tex)
            GLES20.glUniform1i(pMask.u("uTexture"), 0)
            GLES20.glUniform2f(pMask.u("uTexel"), 1.5f / maskW, 1.5f / maskH)
            GLES20.glUniform4f(pMask.u("uCut"), cutLo, cutHi, cutColour, cutCentre)
            GLES20.glUniform1f(pMask.u("uShowMask"), if (maskView) 1f else 0f)
            pMask.drawQuad()
        }

        for (p in plan.patches) {
            pPatch.use()
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, shown.tex)
            GLES20.glUniform1i(pPatch.u("uTexture"), 0)
            GLES20.glUniform2f(pPatch.u("uSize"), FRAME_W.toFloat(), FRAME_H.toFloat())
            GLES20.glUniform2f(pPatch.u("uCentre"), p.cx, p.cy)
            GLES20.glUniform2f(pPatch.u("uRadii"), p.rx, p.ry)
            GLES20.glUniform1f(pPatch.u("uAngle"), p.angle)
            GLES20.glUniform1f(pPatch.u("uFeather"), p.feather)
            GLES20.glUniform1f(pPatch.u("uBulge"), p.bulge)
            GLES20.glUniform1f(pPatch.u("uShape"), p.shape.toFloat())
            val tint = p.tint
            GLES20.glUniform4f(
                pPatch.u("uTint"),
                ((tint shr 16) and 255) / 255f, ((tint shr 8) and 255) / 255f, (tint and 255) / 255f, ((tint ushr 24) and 255) / 255f,
            )
            GLES20.glUniform1f(pPatch.u("uBlur"), p.blur)
            GLES20.glUniform1f(pPatch.u("uWave"), p.wave)
            GLES20.glUniform1f(pPatch.u("uPhase"), p.phase)
            GLES20.glUniform1f(pPatch.u("uOpacity"), p.opacity)
            GLES20.glUniform1f(pPatch.u("uSurface"), p.surface)
            val l = p.local ?: IDENTITY_3X3
            GLES20.glUniformMatrix3fv(pPatch.u("uLocal"), 1, false, floatArrayOf(l[0], l[3], l[6], l[1], l[4], l[7], l[2], l[5], l[8]), 0)
            val m = p.map
            // Column-major: src = M * (x, y, 1).
            GLES20.glUniformMatrix3fv(pPatch.u("uMap"), 1, false, floatArrayOf(m[0], m[3], 0f, m[1], m[4], 0f, m[2], m[5], 1f), 0)
            pPatch.drawQuad()
        }

        if (plan.aliens.isNotEmpty() && !alienFailed) {
            try {
                val r = alienRenderer ?: AlienRenderer().also { alienRenderer = it }
                r.draw(plan.aliens, shown.tex)
            } catch (e: Throwable) {
                alienFailed = true
                Log.e(TAG, "alien warp failed; turning it off", e)
            }
        }

        if ((plan.glasses.isNotEmpty() || plan.pods.isNotEmpty() || plan.rides.isNotEmpty() || plan.falls.isNotEmpty() || plan.hamsters.isNotEmpty() || plan.aviators.isNotEmpty() || plan.cats.isNotEmpty()) && !glassFailed) {
            try {
                if (plan.glasses.isNotEmpty()) {
                    val r = glassRenderer ?: GlassRenderer().also { glassRenderer = it }
                    r.draw(plan.glasses, shown.tex)
                }
                if (plan.pods.isNotEmpty()) {
                    val r = podRenderer ?: PodRenderer().also { podRenderer = it }
                    r.draw(plan.pods, shown.tex)
                }
                if (plan.rides.isNotEmpty()) {
                    val r = rideRenderer ?: RideRenderer().also { rideRenderer = it }
                    r.draw(plan.rides, shown.tex, comp)
                }
                if (plan.falls.isNotEmpty()) {
                    val r = fallRenderer ?: FreefallRenderer(assets).also { fallRenderer = it }
                    r.draw(plan.falls, shown.tex, comp)
                }
                if (plan.hamsters.isNotEmpty()) {
                    val r = hamsterRenderer ?: HamsterRenderer().also { hamsterRenderer = it }
                    r.draw(plan.hamsters)
                }
                if (plan.aviators.isNotEmpty()) {
                    val r = aviatorRenderer ?: AviatorRenderer().also { aviatorRenderer = it }
                    r.draw(plan.aviators)
                }
                if (plan.cats.isNotEmpty()) {
                    val r = catRenderer ?: CatRenderer(assets).also { catRenderer = it }
                    r.draw(plan.cats)
                }
            } catch (e: Throwable) {
                glassFailed = true
                Log.e(TAG, "3D pass failed; turning it off", e)
                GLES20.glDisable(GLES20.GL_DEPTH_TEST)
                GLES20.glDepthMask(true)
                GLES20.glEnable(GLES20.GL_BLEND)
                GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            }
        }

        if (plan.over) drawLayer(over)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    // Pop Art's ground, drawn once. Sixty-odd sin per pixel for an image that is the same every
    // frame was the whole cost of the filter on a gen 2 Portal, whose Adreno 615 has roughly half
    // the gen 1's shading power. One slow frame here buys every frame after it.
    private fun bakePopGround() {
        if (groundRed != null) return
        val red = Fbo(FRAME_W, FRAME_H)
        val dark = Fbo(FRAME_W, FRAME_H)
        pPopGround.use()
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUniform2f(pPopGround.u("uSize"), FRAME_W.toFloat(), FRAME_H.toFloat())
        for ((target, isDark) in arrayOf(red to 0f, dark to 1f)) {
            target.bind()
            GLES20.glUniform1f(pPopGround.u("uDark"), isDark)
            pPopGround.drawQuad()
        }
        groundRed = red
        groundDark = dark
        Log.i(TAG, "pop art ground baked ${FRAME_W}x$FRAME_H")
    }

    // A frame shader standing in for the plain camera picture.
    private fun drawFx(fx: FrameFx) {
        GLES20.glDisable(GLES20.GL_BLEND)
        val p = when (fx.kind) {
            FrameFx.MIRROR -> pMirror
            FrameFx.POP -> pPop
            FrameFx.DISCO -> pDisco
            FrameFx.POP_ART -> pPopArt
            else -> {
                drawTexture(shown.tex, Program.IDENTITY, Program.IDENTITY)
                return
            }
        }
        p.use()
        if (fx.kind == FrameFx.POP) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTex)
            GLES20.glUniform1i(p.u("uMask"), 1)
            GLES20.glUniform2f(p.u("uTexel"), 1.5f / maxOf(1, maskW), 1.5f / maxOf(1, maskH))
            GLES20.glUniform3fv(p.u("uBg"), 1, fx.a, 0)
            GLES20.glUniform3fv(p.u("uTop"), 1, fx.b, 0)
            GLES20.glUniform3fv(p.u("uBottom"), 1, fx.c, 0)
            GLES20.glUniform1f(p.u("uGhost"), fx.p0)
            GLES20.glUniform1f(p.u("uFlash"), fx.p1)
        }
        if (fx.kind == FrameFx.DISCO) {
            val q = fx.q
            GLES20.glUniform2f(p.u("uSize"), FRAME_W.toFloat(), FRAME_H.toFloat())
            GLES20.glUniform1f(p.u("uLook"), q[0])
            GLES20.glUniform1f(p.u("uTime"), q[1])
            GLES20.glUniform1f(p.u("uBeat"), q[2])
            GLES20.glUniform3f(p.u("uTint"), q[3], q[4], q[5])
            GLES20.glUniform2f(p.u("uCentre"), q[6], q[7])
            GLES20.glUniform1f(p.u("uStarR"), q[8])
            GLES20.glUniform1f(p.u("uStarRot"), q[9])
            GLES20.glUniform1f(p.u("uGridRot"), q[10])
            GLES20.glUniform3f(p.u("uBurstR"), q[11], q[12], q[13])
            GLES20.glUniform3f(p.u("uBurstA"), q[14], q[15], q[16])
        }
        if (fx.kind == FrameFx.POP_ART) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTex)
            GLES20.glUniform1i(p.u("uMask"), 1)
            // Echo textures newest first.
            val order = echoTex.indices.sortedByDescending { echoAt[it] }
            val names = arrayOf("uEcho0", "uEcho1", "uEcho2")
            for ((k, i) in order.withIndex()) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE2 + k)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, echoTex[i])
                GLES20.glUniform1i(p.u(names[k]), 2 + k)
            }
            GLES20.glActiveTexture(GLES20.GL_TEXTURE5)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, groundRed?.tex ?: 0)
            GLES20.glUniform1i(p.u("uGroundRed"), 5)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE6)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, groundDark?.tex ?: 0)
            GLES20.glUniform1i(p.u("uGroundDark"), 6)
            GLES20.glUniform4f(p.u("uCut"), cutLo, cutHi, cutColour, cutCentre)
            GLES20.glUniform2f(p.u("uMaskTexel"), 1f / maxOf(1, maskW), 1f / maxOf(1, maskH))
            GLES20.glUniform2f(p.u("uSize"), FRAME_W.toFloat(), FRAME_H.toFloat())
            val q = fx.q
            GLES20.glUniform1f(p.u("uLook"), q[0])
            GLES20.glUniform1f(p.u("uBg"), q[1])
            GLES20.glUniform1f(p.u("uTrans"), q[2])
            GLES20.glUniform1f(p.u("uTransP"), q[3])
            GLES20.glUniform1f(p.u("uOutline"), q[4])
            GLES20.glUniform1f(p.u("uEchoOn"), q[5])
            GLES20.glUniform3f(p.u("uEchoColor"), q[6], q[7], q[8])
            GLES20.glUniform3f(p.u("uHead"), q[9], q[10], q[11])
            GLES20.glUniform1f(p.u("uDissolve"), q[12])
            GLES20.glUniform1f(p.u("uHalftone"), q[13])
            GLES20.glUniform1f(p.u("uTime"), q[14])
            GLES20.glUniform1f(p.u("uFlash"), q[15])
            GLES20.glUniform2f(p.u("uDrift"), q[16], q[17])
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, shown.tex)
        GLES20.glUniform1i(p.u("uTexture"), 0)
        p.drawQuad()
    }

    private fun drawLayer(layer: CanvasLayer) {
        pOes.use()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, layer.tex)
        GLES20.glUniform1i(pOes.u("uTexture"), 0)
        pOes.drawQuad(Program.IDENTITY, layer.matrix)
    }

    private fun screen(out: Fbo) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, winW, winH)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        // Contain, not cover: the preview shows exactly what a capture will hold.
        val s = min(winW.toFloat() / FRAME_W, winH.toFloat() / FRAME_H)
        val vw = (FRAME_W * s).toInt()
        val vh = (FRAME_H * s).toInt()
        GLES20.glViewport((winW - vw) / 2, (winH - vh) / 2, vw, vh)
        GLES20.glDisable(GLES20.GL_BLEND)
        drawTexture(out.tex, Program.IDENTITY, MIRROR_X)
    }

    private fun drawTexture(tex: Int, pos: FloatArray, texMatrix: FloatArray) {
        pTex.use()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glUniform1i(pTex.u("uTexture"), 0)
        pTex.drawQuad(pos, texMatrix)
    }

    // Read back on this thread, encode off it: glReadPixels has to happen here, the
    // JPEG does not.
    private fun capture(out: Fbo, quality: Int, cb: (ByteArray?) -> Unit) {
        val buf = ByteBuffer.allocateDirect(out.w * out.h * 4).order(ByteOrder.nativeOrder())
        out.read(buf)
        val w = out.w
        val h = out.h
        jpegs.execute {
            val bytes = try {
                val raw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                buf.rewind()
                raw.copyPixelsFromBuffer(buf)
                val upright = Bitmap.createBitmap(raw, 0, 0, w, h, GfxMatrix().apply { preScale(1f, -1f) }, false)
                raw.recycle()
                ByteArrayOutputStream().use { os ->
                    upright.compress(Bitmap.CompressFormat.JPEG, quality, os)
                    upright.recycle()
                    os.toByteArray()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "capture failed", e)
                null
            }
            cb(bytes)
        }
    }

    // Which category means "person" is read from the corners, as takeMask() does:
    // the model's convention has changed between releases.
    private fun uploadMask(bytes: ByteArray, w: Int, h: Int, now: Long) {
        if (w == 0 || h == 0 || bytes.size < w * h) return
        val n = w * h
        // The model's person confidence, 0..255. The top corners are nearly always room: if both
        // read as person, this model's mask runs the other way round. Decided only on clear
        // evidence, so someone filling a corner can't flip it frame to frame.
        val topCorners = ((bytes[0].toInt() and 255) + (bytes[w - 1].toInt() and 255)) / 2
        if (topCorners > 200) maskInverted = true else if (topCorners < 55) maskInverted = false
        var buf = maskBuf
        if (buf == null || buf.capacity() != n) {
            buf = ByteBuffer.allocateDirect(n)
            maskBuf = buf
        }
        buf!!.clear()
        if (personMask.size != n) personMask = ByteArray(n)
        // Smoothed over time, so the edge holds still between results instead of boiling. How
        // hard is decided per pixel: see MASK_SMOOTH_STILL.
        val fresh = maskEma.size != n || now - maskAt > 500
        if (maskEma.size != n) maskEma = FloatArray(n)
        val still = maskStill
        val move = maskMove
        var person = 0
        for (i in 0 until n) {
            var v = (bytes[i].toInt() and 255) / 255f
            if (maskInverted) v = 1f - v
            val was = maskEma[i]
            val k = if (fresh) {
                1f
            } else {
                val d = abs(v - was)
                still + (move - still) * smoothstep(MASK_MOVE_LO, MASK_MOVE_HI, d)
            }
            val e = was + (v - was) * k
            maskEma[i] = e
            buf.put((e * 255f).toInt().toByte())
            // Hysteresis: what counted as person last time has an easier bar than what didn't.
            val on = if (personMask[i].toInt() != 0) e > 0.5f - MASK_HYSTERESIS else e > 0.5f + MASK_HYSTERESIS
            if (on) {
                personMask[i] = 1
                person++
            } else {
                personMask[i] = 0
            }
        }
        buf.flip()
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTex)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_ALPHA, w, h, 0, GLES20.GL_ALPHA, GLES20.GL_UNSIGNED_BYTE, buf)
        if (now - echoLast >= 90) {
            var oldest = 0
            for (i in 1 until echoTex.size) if (echoAt[i] < echoAt[oldest]) oldest = i
            buf.position(0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, echoTex[oldest])
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_ALPHA, w, h, 0, GLES20.GL_ALPHA, GLES20.GL_UNSIGNED_BYTE, buf)
            echoAt[oldest] = now
            echoLast = now
        }
        painter.onMask(personMask, w, h)
        maskW = w
        maskH = h
        maskAt = now
        segShare = person.toFloat() / (w * h)
        nextSegRoi(personMask, w, h)
    }

    // Where the segmenter looks next: the person's bounds, with room to move, square in 0..1 so
    // the crop keeps the frame's 16:9. It grows at once (an arm swinging in must not be cut off)
    // and shrinks or pans gently. With nobody found, the whole frame.
    private fun nextSegRoi(mask: ByteArray, w: Int, h: Int) {
        // The crop comes from the last mask, and everything outside it is taken to be room, so a
        // mask that is wrong makes a crop that keeps it wrong. This pins it open to tell that
        // apart from the model simply being wrong.
        if (segFullFrame) {
            segRoi[0] = 0f
            segRoi[1] = 0f
            segRoi[2] = 1f
            segRoi[3] = 1f
            segCrop = 1f
            return
        }
        var minX = w
        var maxX = -1
        var minY = h
        var maxY = -1
        var count = 0
        for (y in 0 until h step 2) {
            val row = y * w
            for (x in 0 until w step 2) {
                if (mask[row + x].toInt() != 0) {
                    count++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        var cw = 1f
        var ch = 1f
        var cx = 0f
        var cy = 0f
        if (count >= SEG_ROI_MIN_PIXELS) {
            val bx0 = minX / w.toFloat()
            val bx1 = (maxX + 2) / w.toFloat()
            val by0 = minY / h.toFloat()
            val by1 = (maxY + 2) / h.toFloat()
            // Width and height apart: someone seated runs from head to the frame's bottom, and a
            // crop that kept 16:9 would then always be the whole frame. The model's view is
            // stretched a little instead, never by more than SEG_ROI_MAX_STRETCH.
            cw = (bx1 - bx0) * SEG_ROI_MARGIN_X + 0.06f
            ch = (by1 - by0) * SEG_ROI_MARGIN_Y + 0.05f
            // Touching the crop's edge (where that isn't also the frame's edge) means part of the
            // person may be outside it: look wider.
            val r = segRoi
            val e = 0.02f
            if ((bx0 <= r[0] + e && r[0] > e) || (bx1 >= r[0] + r[2] - e && r[0] + r[2] < 1f - e)) cw *= 1.3f
            if ((by0 <= r[1] + e && r[1] > e) || (by1 >= r[1] + r[3] - e && r[1] + r[3] < 1f - e)) ch *= 1.3f
            // The crop's natural shape is the model's input shape, in 0..1 units: 16:9 is 1,
            // square is 0.5625.
            val natural = tracker.segInputW.toFloat() / tracker.segInputH * FRAME_H / FRAME_W
            cw = maxOf(cw, ch * natural / SEG_ROI_MAX_STRETCH).coerceIn(SEG_ROI_MIN, 1f)
            ch = maxOf(ch, cw / (natural * SEG_ROI_MAX_STRETCH)).coerceIn(SEG_ROI_MIN, 1f)
            cx = ((bx0 + bx1) / 2 - cw / 2).coerceIn(0f, 1f - cw)
            cy = (by0 - (by1 - by0) * 0.12f - 0.03f).coerceIn(0f, 1f - ch)
        }
        // Grow at once (an arm swinging in must not be cut off); shrink and pan gently.
        val kx = if (cw > segRoi[2]) 1f else 0.3f
        val ky = if (ch > segRoi[3]) 1f else 0.3f
        segRoi[2] += (cw - segRoi[2]) * kx
        segRoi[3] += (ch - segRoi[3]) * ky
        segRoi[0] = (segRoi[0] + (cx - segRoi[0]) * kx).coerceIn(0f, 1f - segRoi[2])
        segRoi[1] = (segRoi[1] + (cy - segRoi[1]) * ky).coerceIn(0f, 1f - segRoi[3])
        segCrop = segRoi[2]
    }

    // Head and shoulders only: the full portrait puts the face at ~12px of the tracker's
    // 320px input, smaller than any child in front of a Portal and too small for two.
    private fun loadTestImage(context: Context) {
        val bmp = try {
            context.assets.open("test/portrait.jpg").use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) {
            null
        } ?: return
        testTex = genTexture(GLES20.GL_TEXTURE_2D)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        testW = bmp.width
        testH = bmp.height
        bmp.recycle()
        setTestCrop(0.15f, 0.85f, 0f, 0.5f)
        hasTestImage = true
    }

    private var testW = 0
    private var testH = 0

    /**
     * Which part of the test portrait fills the frame, in 0..1 of the bitmap. The default is its
     * head and shoulders; `--es testRect` reaches further down it, to the folded hands, which is
     * where a cut-out's fine detail can actually be judged.
     */
    fun setTestCrop(u0: Float, u1: Float, v0: Float, v1: Float) = handler.post {
        // The bitmap's top row is t = 0, so a quad's bottom edge samples v1.
        GlMatrix.setIdentityM(testCrop, 0)
        GlMatrix.translateM(testCrop, 0, u0, v1, 0f)
        GlMatrix.scaleM(testCrop, 0, u1 - u0, -(v1 - v0), 1f)
        testAspect = (testW * (u1 - u0)) / (testH * (v1 - v0))
        Log.i(TAG, "test crop u %.2f-%.2f v %.2f-%.2f aspect %.2f".format(u0, u1, v0, v1, testAspect))
    }

    private companion object {
        val FLIP_V = FloatArray(16).also {
            GlMatrix.setIdentityM(it, 0)
            GlMatrix.translateM(it, 0, 0f, 1f, 0f)
            GlMatrix.scaleM(it, 0, 1f, -1f, 1f)
        }
        val MIRROR_X = FloatArray(16).also {
            GlMatrix.setIdentityM(it, 0)
            GlMatrix.translateM(it, 0, 1f, 0f, 0f)
            GlMatrix.scaleM(it, 0, -1f, 1f, 1f)
        }
    }
}
