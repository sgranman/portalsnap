package net.sgran.portalsnap

import android.content.Context
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
import kotlin.math.min
import kotlin.math.sin
import android.graphics.Matrix as GfxMatrix
import android.opengl.Matrix as GlMatrix

const val FRAME_W = 1280
const val FRAME_H = 720

private val IDENTITY_3X3 = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

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
    private lateinit var frame: Fbo
    private lateinit var comp: Fbo
    private lateinit var under: CanvasLayer
    private lateinit var over: CanvasLayer
    private var small: Fbo? = null
    private var smallBuf: ByteBuffer? = null
    private var lastOut: Fbo? = null

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

    private var photo: ((ByteArray?) -> Unit)? = null

    @Volatile var rotation = 0
    @Volatile var sourceW = FRAME_W
    @Volatile var sourceH = FRAME_H
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
        handler.post {
            egl = EglCore()
            pOes = Program(Shaders.VERTEX, Shaders.OES)
            pTex = Program(Shaders.VERTEX, Shaders.TEX)
            pPatch = Program(Shaders.VERTEX, Shaders.PATCH)
            pMask = Program(Shaders.VERTEX, Shaders.MASK)
            pMirror = Program(Shaders.VERTEX, Shaders.FX_MIRROR)
            pPop = Program(Shaders.VERTEX, Shaders.FX_POP)
            pDisco = Program(Shaders.VERTEX, Shaders.FX_DISCO)
            frame = Fbo(FRAME_W, FRAME_H)
            comp = Fbo(FRAME_W, FRAME_H)
            under = CanvasLayer(handler)
            over = CanvasLayer(handler)
            maskTex = genTexture(GLES20.GL_TEXTURE_2D)

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
        if (mask != null) uploadMask(mask, r.maskW, r.maskH, now) else painter.onFaces(r.faces, r.mode, now)
    }

    private fun render() {
        val t0 = SystemClock.elapsedRealtimeNanos()
        val now = SystemClock.uptimeMillis()
        try {
            val win = window
            if (win != null) egl.makeCurrent(win) else egl.makePbufferCurrent()

            // 1. The upright, unmirrored frame.
            frame.bind()
            if (testFaces > 0) drawTest(now) else drawCamera()

            // 2. The tracker's copy, only when it is idle.
            feedTracker()

            // 3. Filters.
            val latency = (tracker.lastGrabMs + tracker.lastInferMs).toFloat()
            val plan = painter.paint(now, latency, now - maskAt < 800, under, over)
            val out = if (plan.composite) {
                composite(plan)
                comp
            } else {
                frame
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
            }

            renderRate.tick()
            renderFrames.inc()
        } catch (e: Throwable) {
            Log.e(TAG, "render failed", e)
        } finally {
            frameMs.add((SystemClock.elapsedRealtimeNanos() - t0) / 1e6)
        }
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
        // Undo the camera service's front-camera mirror, so the frame is the room as it is.
        GlMatrix.scaleM(user, 0, if (stFlip) -cx else cx, cy, 1f)
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
            val x = FRAME_W * (i + 1f) / (n + 1) + sin(now / 900f + i * 2) * 36f
            val y = FRAME_H / 2f + sin(now / 1300f + i) * 14f
            GlMatrix.setIdentityM(posM, 0)
            GlMatrix.translateM(posM, 0, x / FRAME_W * 2 - 1, 1 - y / FRAME_H * 2, 0f)
            GlMatrix.scaleM(posM, 0, dw / FRAME_W, dh / FRAME_H, 1f)
            pTex.drawQuad(posM, testCrop)
        }
    }

    private fun feedTracker() {
        if (tracker.busy || !tracker.ready) return
        val m = tracker.mode
        val t0 = SystemClock.elapsedRealtimeNanos()
        var target = small
        if (target == null || target.w != m.inputW || target.h != m.inputH) {
            target?.release()
            target = Fbo(m.inputW, m.inputH)
            small = target
            smallBuf = ByteBuffer.allocateDirect(m.inputW * m.inputH * 4).order(ByteOrder.nativeOrder())
        }
        // Mipmapped, so a 4x downscale averages pixels instead of skipping them.
        frame.generateMipmaps()
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
        target.bind()
        GLES20.glDisable(GLES20.GL_BLEND)
        // Flipped, so row 0 of the readback is the top of the picture.
        drawTexture(frame.tex, Program.IDENTITY, FLIP_V)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frame.tex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        val buf = smallBuf!!
        target.read(buf)
        tracker.submit(buf, target.w, target.h, (SystemClock.elapsedRealtimeNanos() - t0) / 1e6)
    }

    private fun composite(plan: Plan) {
        comp.bind()
        val fx = plan.fx
        if (fx != null) {
            drawFx(fx)
        } else if (plan.base) {
            GLES20.glDisable(GLES20.GL_BLEND)
            drawTexture(frame.tex, Program.IDENTITY, Program.IDENTITY)
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
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frame.tex)
            GLES20.glUniform1i(pMask.u("uTexture"), 0)
            GLES20.glUniform2f(pMask.u("uTexel"), 1.5f / maskW, 1.5f / maskH)
            pMask.drawQuad()
        }

        for (p in plan.patches) {
            pPatch.use()
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frame.tex)
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
            val l = p.local ?: IDENTITY_3X3
            GLES20.glUniformMatrix3fv(pPatch.u("uLocal"), 1, false, floatArrayOf(l[0], l[3], l[6], l[1], l[4], l[7], l[2], l[5], l[8]), 0)
            val m = p.map
            // Column-major: src = M * (x, y, 1).
            GLES20.glUniformMatrix3fv(pPatch.u("uMap"), 1, false, floatArrayOf(m[0], m[3], 0f, m[1], m[4], 0f, m[2], m[5], 1f), 0)
            pPatch.drawQuad()
        }

        if (plan.over) drawLayer(over)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    // A frame shader standing in for the plain camera picture.
    private fun drawFx(fx: FrameFx) {
        GLES20.glDisable(GLES20.GL_BLEND)
        val p = when (fx.kind) {
            FrameFx.MIRROR -> pMirror
            FrameFx.POP -> pPop
            FrameFx.DISCO -> pDisco
            else -> {
                drawTexture(frame.tex, Program.IDENTITY, Program.IDENTITY)
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
            GLES20.glUniform2f(p.u("uSize"), FRAME_W.toFloat(), FRAME_H.toFloat())
            GLES20.glUniform2f(p.u("uHead"), fx.x, fx.y)
            GLES20.glUniform1f(p.u("uTime"), fx.p0)
            GLES20.glUniform1f(p.u("uBeat"), fx.p1)
            GLES20.glUniform1f(p.u("uLevel"), fx.p2)
            GLES20.glUniform1f(p.u("uRing"), fx.b[0])
            GLES20.glUniform3fv(p.u("uTint"), 1, fx.a, 0)
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frame.tex)
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
        val corners = listOf(bytes[0], bytes[w - 1], bytes[(h - 1) * w], bytes[h * w - 1])
        val bg = corners.groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key
        var buf = maskBuf
        if (buf == null || buf.capacity() != w * h) {
            buf = ByteBuffer.allocateDirect(w * h)
            maskBuf = buf
        }
        buf!!.clear()
        var person = 0
        for (i in 0 until w * h) {
            if (bytes[i] != bg) {
                buf.put(255.toByte())
                person++
            } else {
                buf.put(0)
            }
        }
        buf.flip()
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTex)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_ALPHA, w, h, 0, GLES20.GL_ALPHA, GLES20.GL_UNSIGNED_BYTE, buf)
        maskW = w
        maskH = h
        maskAt = now
        segShare = person.toFloat() / (w * h)
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
        val u0 = 0.15f
        val u1 = 0.85f
        val v0 = 0f
        val v1 = 0.5f
        // The bitmap's top row is t = 0, so a quad's bottom edge samples v1.
        GlMatrix.setIdentityM(testCrop, 0)
        GlMatrix.translateM(testCrop, 0, u0, v1, 0f)
        GlMatrix.scaleM(testCrop, 0, u1 - u0, -(v1 - v0), 1f)
        testAspect = (bmp.width * (u1 - u0)) / (bmp.height * (v1 - v0))
        bmp.recycle()
        hasTestImage = true
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
