package net.sgran.portalsnap

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// A port of public/filters.js. Same geometry, same constants, same comments where the
// reason for a number would otherwise be lost; see that file for the full history.
// Canvas 2D maps onto android.graphics.Canvas almost call for call. The operations that
// sampled the video (Big Head, the skydiver's face, the backgrounds) become GPU patches,
// because a Canvas cannot read the camera texture.

internal const val TAU = (PI * 2).toFloat()

internal fun deg(rad: Float) = rad * (180f / PI.toFloat())

internal fun rgba(r: Int, g: Int, b: Int, a: Float) = Color.argb((a * 255).toInt(), r, g, b)

internal fun hex(s: String) = Color.parseColor(s)

internal fun sinT(t: Long, div: Float) = sin(t / div)

/** Canvas 2D's state as the filters use it: one fill, one stroke, one shadow. */
class Pen {
    val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG)
    val path = Path()
    private val shadow = rgba(0, 0, 0, 0.45f)
    private val oval = RectF()

    fun reset() {
        fill.reset()
        fill.isAntiAlias = true
        fill.style = Paint.Style.FILL
        stroke.reset()
        stroke.isAntiAlias = true
        stroke.style = Paint.Style.STROKE
    }

    fun fill(color: Int): Paint {
        fill.shader = null
        fill.color = color
        return fill
    }

    fun fill(shader: Shader): Paint {
        fill.color = Color.BLACK
        fill.shader = shader
        return fill
    }

    fun stroke(color: Int, width: Float): Paint {
        stroke.color = color
        stroke.strokeWidth = width
        return stroke
    }

    // Web: shadowBlur = k * eyeDist device px (blur ignores the transform), offset k*0.3
    // face units. Android's shadow radius follows the transform, so in face space the
    // radius is k face units, scaled from Canvas 2D's blur to Skia's sigma.
    fun lift(k: Float) {
        fill.setShadowLayer(k * 0.87f, 0f, k * 0.3f, shadow)
        stroke.setShadowLayer(k * 0.87f, 0f, k * 0.3f, shadow)
    }

    fun unlift() {
        fill.clearShadowLayer()
        stroke.clearShadowLayer()
    }

    fun rect(x: Float, y: Float, rx: Float, ry: Float): RectF {
        oval.set(x - abs(rx), y - abs(ry), x + abs(rx), y + abs(ry))
        return oval
    }

    fun newPath(): Path {
        path.reset()
        return path
    }
}

/** What a filter draws with, for one frame. */
class Draw(val pen: Pen) {
    lateinit var c: Canvas
    var t = 0L
    /** Milliseconds since the last painted frame. */
    var dt = 16f
    /** The shared mic: level 0..1, a beat pulse that decays from 1, and a running beat count. */
    var level = 0f
    var beat = 0f
    var beats = 0
    var sinceBeatMs = 1e9f
    val w = FRAME_W.toFloat()
    val h = FRAME_H.toFloat()
    val patches = ArrayList<Patch>()
    /** Real 3D glasses, drawn after the patches and before the over layer. */
    val glasses = ArrayList<Glass3D>()
    /** Real 3D pea pods, drawn at the same point as glasses. */
    val pods = ArrayList<Pod3D>()
    /** Bike Ride's park and riders, drawn at the same point as glasses. */
    val rides = ArrayList<Ride3D>()
    /** Freefall's sky and divers, drawn at the same point as glasses. */
    val falls = ArrayList<Fall3D>()
    /** Hamster's 3D props, drawn at the same point as glasses. */
    val hamsters = ArrayList<Hamster3D>()
    /** Segment tier: the latest person mask (1 person, 0 not), maskW x maskH, top row first. */
    var mask: ByteArray? = null
    var maskW = 0
    var maskH = 0
}

/**
 * The frame, resampled through [map] (dst px -> src px: a,b,c,d,e,f) inside an ellipse.
 * [feather] is where the fade starts, as a fraction of the radius. A [bulge] above 0 ignores
 * both and magnifies in place instead: 1 / (1 - bulge) at the centre, easing to none at the rim.
 *
 * [shape] GLASS swaps the ellipse for a tumbler (0.8 as wide at the bottom) that can turn in 3D:
 * [local], a row-major projective 3x3, takes a frame pixel into glass units (-1..1 across the
 * top and top to bottom), and [map] then takes glass units to camera pixels. [tint] is an ARGB
 * colour cast whose alpha is its strength; [blur] is a radius in frame px; [wave] ripples the
 * picture sideways by that many px, moved along by [phase].
 */
class Patch(
    val cx: Float, val cy: Float, val rx: Float, val ry: Float, val angle: Float,
    val feather: Float, val map: FloatArray, val bulge: Float = 0f,
    val shape: Int = ELLIPSE, val tint: Int = 0, val blur: Float = 0f, val wave: Float = 0f, val phase: Float = 0f,
    val local: FloatArray? = null,
    /** Below 1, whatever was drawn under the patch shows through it. */
    val opacity: Float = 1f,
    /** GLASS only: how far the front of the liquid's surface dips below its top edge, in half-heights. */
    val surface: Float = 0f,
) {
    companion object {
        const val ELLIPSE = 0
        const val GLASS = 1
    }
}

abstract class Filter(
    val id: String,
    val name: String,
    val emoji: String,
    val tier: Mode,
    val voice: Float = 1f,
) {
    /** Paints something behind the patches. */
    open val usesUnder = false
    /** Paints stickers over the picture. */
    open val usesOver = true
    /** The under layer replaces the camera entirely: a sky, a scene. */
    open val coversCamera = false
    /** Keeps drawing its scene (scene + overlay, with no faces) while nobody is in view. */
    open val keepsScene = false
    /** An asset path for this filter's own soundtrack, looped while it's selected. */
    open val music: String? = null
    /** A synthesized [Sfx] sound looped under everything while it's selected, such as wind. */
    open val ambience: String? = null

    open fun voiceFrom(face: Face): Float? = null

    /** Under layer, once however many people are in frame. */
    open fun scene(d: Draw, faces: List<Face>) {}

    /** Under layer, per face. */
    open fun under(d: Draw, f: Face) {}

    /** Over layer, per face. Patches may be added here too. */
    open fun draw(d: Draw, f: Face) {}

    /** Segment tier: the place the person is pasted into. */
    open fun backdrop(d: Draw) {}

    /** Reacts to the room's sound through the shared mic. */
    open val wantsMic = false

    /** Replaces the camera picture with a full-frame shader; see FrameFx. */
    open val usesFx = false

    open fun fx(d: Draw, faces: List<Face>, fx: FrameFx) {}

    /** Once per frame, before drawing: state, particles, triggers. */
    open fun update(d: Draw, faces: List<Face>) {}

    /** Over layer, once, after every face: particles that belong to the frame. */
    open fun overlay(d: Draw, faces: List<Face>) {}

    /** A tap on the picture, or adb's `--es action poke`. */
    open fun poke() {}
}

fun voiceOf(f: Filter?, face: Face?): Float {
    if (f == null) return 1f
    if (face != null) {
        val r = f.voiceFrom(face)
        if (r != null) return if (r > 0) r.coerceIn(0.5f, 2.5f) else 1f
    }
    return f.voice
}

/* ------------------------------ helpers ------------------------------ */

internal inline fun inFaceSpace(c: Canvas, f: Face, block: () -> Unit) {
    val save = c.save()
    c.translate(f.cx, f.cy)
    c.rotate(deg(f.angle))
    c.scale(f.eyeDist, f.eyeDist)
    // Flat art on a turning head should narrow slightly, or it reads as a decal.
    c.scale(1 - abs(f.yaw) * 0.22f, 1f)
    try {
        block()
    } finally {
        c.restoreToCount(save)
    }
}

internal fun toPixels(f: Face, x: Float, y: Float): Pt {
    val c = cos(f.angle)
    val s = sin(f.angle)
    return Pt(f.cx + (x * c - y * s) * f.eyeDist, f.cy + (x * s + y * c) * f.eyeDist)
}

internal class HeadBox(val x: Float, val y: Float, val halfW: Float, val halfH: Float)

internal fun headBox(f: Face): HeadBox {
    val topY = f["headTop"]?.y ?: -0.63f
    val botY = f["chin"]?.y ?: 1.36f
    val centre = toPixels(f, 0f, (topY + botY) / 2)
    return HeadBox(centre.x, centre.y, (f.headSpan / 2) * f.eyeDist * 1.12f, ((botY - topY) / 2) * f.eyeDist * 1.12f)
}

// Where an animal ear attaches: mostly the hairline's height, mostly the temple's width.
internal fun earPoints(f: Face, pull: Float): List<Pt> {
    val skullR = f["skullR"]
    val skullL = f["skullL"]
    val templeR = f["templeR"]
    val templeL = f["templeL"]
    if (skullR == null || templeR == null || skullL == null || templeL == null) return listOf(f.earR, f.earL)
    val k = 1 - pull
    fun mix(s: Pt, t: Pt) = Pt((t.x * 0.85f + s.x * 0.15f) * k, s.y * 0.95f + t.y * 0.05f)
    return listOf(mix(skullR, templeR), mix(skullL, templeL))
}

// Once per ear, origin on the ear and +x outward, so one drawing works mirrored.
internal inline fun perEar(c: Canvas, f: Face, pull: Float, block: () -> Unit) {
    for (ear in earPoints(f, pull)) {
        val out = if (ear.x < 0) -1f else 1f
        val save = c.save()
        c.translate(ear.x, ear.y)
        c.scale(out, 1f)
        block()
        c.restoreToCount(save)
    }
}

/* -------------------------------- Dog -------------------------------- */

object Dog : Filter("dog", "Puppy", "🐶", Mode.MESH, voice = 0.78f) {
    override fun draw(d: Draw, f: Face) = inFaceSpace(d.c, f) {
        val c = d.c
        val p = d.pen
        val S = f.headSpan
        val sway = sinT(d.t, 500f) * 0.05f

        p.lift(0.05f)
        perEar(c, f, 0f) {
            c.rotate(deg(0.30f + sway))
            val w = S * 0.115f
            val h = S * 0.235f
            c.drawOval(p.rect(w * 0.25f, h * 0.82f, w, h), p.fill(hex("#7d4f24")))
            c.drawOval(p.rect(w * 0.30f, h * 0.88f, w * 0.55f, h * 0.72f), p.fill(hex("#5a3517")))
        }
        p.unlift()

        val nx = f.nose.x
        val snoutTop = f.nose.y
        val snoutBottom = f.mouth.y
        val ny = (snoutTop + snoutBottom) / 2
        val nostrilR = f["nostrilR"]
        val nostrilL = f["nostrilL"]
        val halfW = if (nostrilR != null && nostrilL != null) max(S * 0.14f, abs(nostrilL.x - nostrilR.x) * 0.95f) else S * 0.20f
        val halfH = max(S * 0.10f, (snoutBottom - snoutTop) * 0.80f)

        p.lift(0.05f)
        c.drawOval(p.rect(nx, ny, halfW, halfH), p.fill(hex("#d69b5c")))
        p.unlift()
        c.drawOval(p.rect(nx, ny + halfH * 0.28f, halfW * 0.66f, halfH * 0.6f), p.fill(hex("#f0e0cc")))

        val nw = halfW * 0.40f
        val nh = halfH * 0.34f
        val lx = nx
        val ly = snoutTop + nh * 0.85f
        p.newPath().apply {
            moveTo(lx - nw, ly - nh * 0.5f)
            quadTo(lx, ly - nh * 1.25f, lx + nw, ly - nh * 0.5f)
            quadTo(lx + nw * 0.8f, ly + nh * 0.95f, lx, ly + nh * 1.1f)
            quadTo(lx - nw * 0.8f, ly + nh * 0.95f, lx - nw, ly - nh * 0.5f)
            close()
        }
        c.drawPath(p.path, p.fill(hex("#26190f")))
        c.drawOval(p.rect(lx - nw * 0.35f, ly - nh * 0.5f, nw * 0.22f, nh * 0.16f), p.fill(rgba(255, 255, 255, 0.5f)))

        // The tongue hangs from the real lower lip, as wide as the real mouth.
        val open = f.bs("jawOpen")
        if (open > 0.10f) {
            val my = f["lipBottom"]?.y ?: f.mouth.y
            val mouthR = f["mouthR"]
            val mouthL = f["mouthL"]
            val mw = if (mouthR != null && mouthL != null) abs(mouthL.x - mouthR.x) * 0.34f else S * 0.11f
            val len = mw * (1.1f + open * 2.2f)
            p.newPath().apply {
                moveTo(nx - mw, my)
                lineTo(nx + mw, my)
                quadTo(nx + mw * 1.15f, my + len, nx, my + len)
                quadTo(nx - mw * 1.15f, my + len, nx - mw, my)
                close()
            }
            c.drawPath(p.path, p.fill(hex("#ef6f8e")))
            c.drawLine(nx, my + len * 0.25f, nx, my + len * 0.8f, p.stroke(rgba(190, 60, 90, 0.75f), 0.018f))
        }
    }
}

/* -------------------------------- Cat -------------------------------- */

object Cat : Filter("cat", "Kitty", "🐱", Mode.MESH, voice = 1.42f) {
    override fun draw(d: Draw, f: Face) = inFaceSpace(d.c, f) {
        val c = d.c
        val p = d.pen
        val S = f.headSpan

        p.lift(0.05f)
        perEar(c, f, 0.22f) {
            c.rotate(deg(0.12f))
            val w = S * 0.115f
            val h = S * 0.30f
            p.newPath().apply {
                moveTo(-w, 0.06f); lineTo(w * 0.35f, -h); lineTo(w * 1.1f, 0.02f); close()
            }
            c.drawPath(p.path, p.fill(hex("#55555f")))
            p.newPath().apply {
                moveTo(-w * 0.45f, 0f); lineTo(w * 0.33f, -h * 0.62f); lineTo(w * 0.68f, 0f); close()
            }
            c.drawPath(p.path, p.fill(hex("#f4a6ba")))
        }
        p.unlift()

        val nx = f.nose.x
        val ny = f.nose.y
        p.newPath().apply {
            moveTo(nx - S * 0.09f, ny - S * 0.04f)
            lineTo(nx + S * 0.09f, ny - S * 0.04f)
            quadTo(nx, ny + S * 0.10f, nx - S * 0.09f, ny - S * 0.04f)
            close()
        }
        c.drawPath(p.path, p.fill(hex("#f4a6ba")))

        val reach = abs(f.earL.x - f.earR.x) * 0.5f
        val whisker = p.stroke(rgba(255, 255, 255, 0.94f), 0.024f)
        whisker.strokeCap = Paint.Cap.ROUND
        p.lift(0.03f)
        for (side in intArrayOf(-1, 1)) {
            for (i in 0 until 3) {
                val y = ny + S * (0.02f + i * 0.075f)
                p.newPath().apply {
                    moveTo(nx + side * S * 0.12f, y)
                    quadTo(
                        nx + side * reach * 0.60f, y - S * (0.05f - i * 0.03f),
                        nx + side * reach * 1.02f, y - S * (0.10f - i * 0.07f),
                    )
                }
                c.drawPath(p.path, whisker)
            }
        }
        p.unlift()
    }
}

/* ----------------------------- Sunglasses ----------------------------- */

object Shades : Filter("shades", "Cool", "😎", Mode.FAST) {
    override fun draw(d: Draw, f: Face) = inFaceSpace(d.c, f) {
        val c = d.c
        val p = d.pen
        val S = f.earSpan
        val lensW = S * 0.34f
        val lensH = S * 0.26f
        val gap = S * 0.09f

        p.lift(0.05f)
        val frame = p.stroke(hex("#f3c93f"), S * 0.028f)
        frame.strokeJoin = Paint.Join.ROUND
        for (side in intArrayOf(-1, 1)) {
            val x = side * (gap / 2 + lensW / 2)
            val r = lensH * 0.42f
            val lens = Path().apply {
                moveTo(x - lensW / 2 + r, -lensH / 2)
                lineTo(x + lensW / 2 - r, -lensH / 2)
                quadTo(x + lensW / 2, -lensH / 2, x + lensW / 2, -lensH / 2 + r)
                lineTo(x + lensW / 2, lensH / 2 - r)
                quadTo(x + lensW / 2, lensH / 2, x + lensW / 2 - r, lensH / 2)
                lineTo(x - lensW / 2 + r, lensH / 2)
                quadTo(x - lensW / 2, lensH / 2, x - lensW / 2, lensH / 2 - r)
                lineTo(x - lensW / 2, -lensH / 2 + r)
                quadTo(x - lensW / 2, -lensH / 2, x - lensW / 2 + r, -lensH / 2)
                close()
            }
            c.drawPath(lens, p.fill(rgba(14, 14, 20, 0.9f)))
            c.drawPath(lens, frame)

            val save = c.save()
            c.clipPath(lens)
            p.newPath().apply {
                moveTo(x - lensW * 0.5f, lensH * 0.5f)
                lineTo(x - lensW * 0.1f, -lensH * 0.5f)
                lineTo(x + lensW * 0.1f, -lensH * 0.5f)
                lineTo(x - lensW * 0.3f, lensH * 0.5f)
                close()
            }
            c.drawPath(p.path, p.fill(rgba(255, 255, 255, 0.22f)))
            c.restoreToCount(save)
        }
        p.unlift()

        p.newPath().apply {
            moveTo(-gap / 2, -lensH * 0.12f)
            quadTo(0f, -lensH * 0.34f, gap / 2, -lensH * 0.12f)
        }
        c.drawPath(p.path, frame)

        // Temples run to the real ear points.
        for (ear in listOf(f.earR, f.earL)) {
            val side = if (ear.x < 0) -1 else 1
            p.newPath().apply {
                moveTo(side * (gap / 2 + lensW), -lensH * 0.18f)
                quadTo(side * (gap / 2 + lensW * 1.5f), -lensH * 0.3f, ear.x * 0.92f, ear.y)
            }
            c.drawPath(p.path, frame)
        }
    }
}

/* -------------------------------- Crown ------------------------------- */

object Crown : Filter("crown", "Royal", "👑", Mode.MESH) {
    private val jewels = intArrayOf(hex("#e8455f"), hex("#4ab5e8"), hex("#5fd47a"))

    override fun draw(d: Draw, f: Face) = inFaceSpace(d.c, f) {
        val c = d.c
        val p = d.pen
        val S = f.headSpan
        val cx = (f.earR.x + f.earL.x) / 2
        val w = S * 0.92f
        val h = S * 0.42f

        val save = c.save()
        c.translate(cx, f.headTopY + h * 0.15f)

        p.lift(0.06f)
        val gold = LinearGradient(
            0f, -h, 0f, h * 0.5f,
            intArrayOf(hex("#ffeb9c"), hex("#f0bc45"), hex("#c98a12")), floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        val outline = p.stroke(hex("#a9740c"), S * 0.016f)
        outline.strokeJoin = Paint.Join.ROUND
        p.newPath().apply {
            moveTo(-w / 2, h * 0.42f)
            lineTo(-w / 2, -h * 0.30f)
            lineTo(-w / 4, h * 0.05f)
            lineTo(0f, -h * 0.62f)
            lineTo(w / 4, h * 0.05f)
            lineTo(w / 2, -h * 0.30f)
            lineTo(w / 2, h * 0.42f)
            close()
        }
        c.drawPath(p.path, p.fill(gold))
        c.drawPath(p.path, outline)
        p.unlift()

        c.drawRect(-w / 2, h * 0.16f, w / 2, h * 0.42f, p.fill(hex("#b8860b")))

        for (i in jewels.indices) {
            val jx = -w / 4 + i * (w / 4)
            c.drawOval(p.rect(jx, h * 0.29f, S * 0.045f, S * 0.045f), p.fill(jewels[i]))
            c.drawOval(p.rect(jx - S * 0.014f, h * 0.275f - S * 0.014f, S * 0.014f, S * 0.012f), p.fill(rgba(255, 255, 255, 0.55f)))
        }

        val tips = arrayOf(floatArrayOf(-w / 2, -h * 0.30f), floatArrayOf(0f, -h * 0.62f), floatArrayOf(w / 2, -h * 0.30f))
        val tip = tips[((d.t / 600) % 3).toInt()]
        val a = 0.45f + 0.55f * sinT(d.t, 170f)
        p.newPath().apply {
            for (i in 0 until 8) {
                val r = if (i % 2 == 1) S * 0.02f else S * 0.07f
                val ang = (i / 8f) * TAU - (PI / 2).toFloat()
                val x = tip[0] + cos(ang) * r
                val y = tip[1] + sin(ang) * r
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        c.drawPath(p.path, p.fill(rgba(255, 255, 255, a.coerceIn(0f, 1f))))
        c.restoreToCount(save)
    }
}

/* ----------------------------- Googly eyes ----------------------------- */

object Googly : Filter("googly", "Googly", "👀", Mode.FAST, voice = 1.75f) {
    private class Swing(var vx: Float, var vy: Float, var px: Float, var py: Float, var seen: Long)

    // Per person, keyed by track id: each pair lags its own head.
    private val swing = HashMap<Int, Swing>()

    private fun pupilLag(f: Face, t: Long): Swing {
        var s = swing[f.id]
        if (s == null) {
            s = Swing(0f, 0f, f.cx, f.cy, t)
            swing[f.id] = s
            if (swing.size > 4) swing.entries.removeAll { t - it.value.seen > 2000 }
        }
        s.seen = t
        s.vx = s.vx * 0.86f + (f.cx - s.px) * 0.14f
        s.vy = s.vy * 0.86f + (f.cy - s.py) * 0.14f
        s.px = f.cx
        s.py = f.cy
        return s
    }

    override fun draw(d: Draw, f: Face) {
        val s = pupilLag(f, d.t)
        val S = f.earSpan
        val cap = S * 0.10f
        val dx = (-s.vx / f.eyeDist * 0.45f).coerceIn(-cap, cap)
        val dy = (-s.vy / f.eyeDist * 0.45f + sinT(d.t, 320f) * S * 0.02f).coerceIn(-cap, cap)

        inFaceSpace(d.c, f) {
            val c = d.c
            val p = d.pen
            val R = S * 0.20f
            p.lift(0.06f)
            val rim = p.stroke(hex("#15151c"), S * 0.022f)
            for (side in intArrayOf(-1, 1)) {
                val x = side * S * 0.26f
                c.drawOval(p.rect(x, 0f, R, R), p.fill(Color.WHITE))
                c.drawOval(p.rect(x, 0f, R, R), rim)
            }
            p.unlift()
            for (side in intArrayOf(-1, 1)) {
                val x = side * S * 0.26f
                c.drawOval(p.rect(x + dx, dy, R * 0.42f, R * 0.42f), p.fill(hex("#12121a")))
                c.drawOval(p.rect(x + dx - R * 0.14f, dy - R * 0.16f, R * 0.10f, R * 0.09f), p.fill(rgba(255, 255, 255, 0.85f)))
            }
        }
    }
}

/* -------------------------- Top hat + mustache -------------------------- */

object Mustache : Filter("mustache", "Fancy", "🎩", Mode.FAST, voice = 0.8f) {
    override fun draw(d: Draw, f: Face) = inFaceSpace(d.c, f) {
        val c = d.c
        val p = d.pen
        val S = f.headSpan
        val cx = (f.earR.x + f.earL.x) / 2

        val save = c.save()
        c.translate(cx, f.headTopY)
        p.lift(0.06f)
        val hat = p.fill(hex("#191922"))
        val brimW = S * 1.02f
        val brimH = S * 0.07f
        c.drawOval(p.rect(0f, 0f, brimW / 2, brimH), hat)
        val crownW = S * 0.56f
        val crownH = S * 0.34f
        c.drawRect(-crownW / 2, -crownH, crownW / 2, 0f, hat)
        c.drawOval(p.rect(0f, -crownH, crownW / 2, brimH * 0.8f), hat)
        p.unlift()
        c.drawRect(-crownW / 2, -crownH * 0.28f, crownW / 2, -crownH * 0.12f, p.fill(hex("#8e1b2d")))
        c.restoreToCount(save)

        val mx = f.mouth.x
        val my = f.mouth.y
        val mouthR = f["mouthR"]
        val mouthL = f["mouthL"]
        val M = if (mouthR != null && mouthL != null) max(S * 0.22f, abs(mouthL.x - mouthR.x) * 0.62f) else S * 0.30f
        p.lift(0.04f)
        p.newPath().apply {
            moveTo(mx, my - M * 0.42f)
            cubicTo(mx - M * 0.52f, my - M * 0.65f, mx - M * 1.37f, my - M * 0.55f, mx - M * 1.43f, my + M * 0.07f)
            cubicTo(mx - M * 0.91f, my - M * 0.16f, mx - M * 0.36f, my - M * 0.03f, mx, my + M * 0.07f)
            cubicTo(mx + M * 0.36f, my - M * 0.03f, mx + M * 0.91f, my - M * 0.16f, mx + M * 1.43f, my + M * 0.07f)
            cubicTo(mx + M * 1.37f, my - M * 0.55f, mx + M * 0.52f, my - M * 0.65f, mx, my - M * 0.42f)
            close()
        }
        c.drawPath(p.path, p.fill(hex("#2c1c11")))
        p.unlift()
    }
}

/* ------------------------------- Big head ------------------------------- */

// Open your mouth and your head balloons. The web version clipped an ellipse and redrew
// the video zoomed; here that is one GPU patch with the same feathered edge.
object BigHead : Filter("bighead", "Big Head", "🤯", Mode.MESH) {
    override val usesOver = false

    override fun voiceFrom(face: Face) = 1 - min(1f, face.bs("jawOpen") * 1.15f) * 0.32f

    override fun draw(d: Draw, f: Face) {
        val open = min(1f, f.bs("jawOpen") * 1.15f)
        if (open < 0.06f) return
        val grow = 1 + open * 1.15f
        val h = headBox(f)
        val rx = h.halfW * grow * 1.25f
        val ry = h.halfH * grow * 1.25f
        // src = centre + (p - centre) / grow
        val k = 1 / grow
        d.patches += Patch(
            h.x, h.y, rx, ry, f.angle, 0.78f,
            floatArrayOf(k, 0f, h.x - h.x * k, 0f, k, h.y - h.y * k),
        )
    }
}

// In the bar's order: the Faces group first (its chip stands where Dog is), then Places.
val FILTERS: List<Filter> = listOf(
    Dog, Cat, Shades, Crown, Googly, Mustache, BigHead, Places,
    Mirror, PopSilhouette, DiscoStar, MonsterCutie, PixelHearts, Hamster, Lemonade, PeasInAPod, BikeRide, Freefall)

/**
 * Filters that share one chip in the bar, where the first of them would be; while one of them is
 * on, a second row over the picture picks among them. Each member is still its own filter, with
 * its own id and tier.
 */
class FilterGroup(val id: String, val name: String, val emoji: String, val members: List<Filter>)

val GROUPS: List<FilterGroup> = listOf(
    FilterGroup("faces", "Faces", "🐶", listOf(Dog, Cat, Shades, Crown, Googly, Mustache, BigHead)),
)
