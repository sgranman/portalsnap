package net.sgran.portalsnap

import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.opengl.GLES20
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// Photo Booth effect 9, rebuilt in real 3D from the reference video (1434519407443964): an open
// pea pod floating over a lime ground of twinkling stars, with your face on every pea. With nobody
// in view the pod is short and closed round one plain pea. When a face arrives it lands on that
// pea, then the pod stretches and a second pea pops in, then a third. Each pea is a glossy sphere
// with the face laid over its front, blushing cheeks and curly tendrils: hands under the chin on
// the top pea, arms waving out of the pod on the middle one, hands over the eyes (peekaboo) on
// the bottom one. The pod follows the head a little, turns with it and rocks like a cradle. It makes no
// sound, like the original. Two people take turns down the pod. The fast tier is enough: only the eyes and
// mouth are needed, and faces then follow at the camera's 30fps.
object PeasInAPod : Filter("peas", "Peas", "🌱", Mode.FAST, voice = 1.25f) {
    override val usesUnder = true
    override val coversCamera = true
    override val keepsScene = true

    private const val MAX_PEAS = 3
    private const val SECOND_PEA_MS = 700L
    private const val THIRD_PEA_MS = 3000L
    // Tracking blinks this long without the pod closing up.
    private const val LOST_MS = 400L
    // The top leans away, so you look a little down into the pod, and lower peas sit in front.
    private const val TIP_DEG = -8f
    private const val ROCK_DEG = 5f
    private const val ROCK_MS = 3000L // there and back

    private class Sample {
        var ok = false
        var x = 0f
        var y = 0f
        var reachX = 0f
        var reachY = 0f
        var roll = 0f
    }

    private var seenAt = -1L
    private var lastSeen = 0L
    private var peas = 1
    private val popAt = LongArray(MAX_PEAS)
    private val samples = Array(MAX_PEAS) { Sample() }
    private var length = PodShape.length(1f)
    private var lengthV = 0f
    private var x = FRAME_W / 2f
    private var vx = 0f
    private var yaw = 0f
    private var roll = 0f
    private val tubes = TubeBuilder()
    private val leaves = TubeBuilder()
    private val heartAt = FloatArray(3)
    private val screen = FloatArray(2)

    // Each pea's heart: grows, holds, poofs at POOF_AT seconds into its cycle, and stays away
    // until the cycle comes round. The peas' cycles are staggered.
    private const val HEART_MS = 3200L
    private const val POOF_AT = 2.2f
    private const val POOF_S = 0.18f
    private val rel = FloatArray(16)
    private val tmp = FloatArray(16)
    private val v4 = FloatArray(4)
    private val w4 = FloatArray(4)
    private val star = Path()

    /** A pea's radius in frame px: the unit of the pod's own space. */
    private fun unit(d: Draw) = d.h * 0.118f

    /* ------------------------------ motion ------------------------------ */

    override fun update(d: Draw, faces: List<Face>) {
        val dt = min(d.dt, 50f) / 1000f
        val now = d.t
        if (faces.isNotEmpty()) {
            if (seenAt < 0) seenAt = now
            lastSeen = now
        } else if (seenAt >= 0 && now - lastSeen > LOST_MS) {
            // Gone: back to the closed pod straight away, as the original does.
            seenAt = -1
            peas = 1
            length = PodShape.length(1f)
            lengthV = 0f
            for (s in samples) s.ok = false
        }
        if (seenAt >= 0) {
            val since = now - seenAt
            if (peas < 2 && since >= SECOND_PEA_MS) {
                peas = 2
                popAt[1] = now
            }
            if (peas < 3 && since >= THIRD_PEA_MS) {
                peas = 3
                popAt[2] = now
            }
        }
        // The pod stretches to its new length on a spring, a little past it and back.
        lengthV += ((PodShape.length(peas.toFloat()) - length) * 90f - lengthV * 13f) * dt
        length += lengthV * dt

        val main = faces.maxByOrNull { it.eyeDist }
        val tx = if (main != null) d.w / 2 + (main.cx - d.w / 2) * 0.3f else d.w / 2
        vx += ((tx - x) * 18f - vx * 7f) * dt
        x += vx * dt
        // Turned against the head's yaw: the screen is a mirror (see Lemonade).
        yaw += ((main?.let { -it.yaw * 28f } ?: 0f) - yaw) * min(1f, dt * 5f)
        roll += ((main?.let { it.angle * 0.5f } ?: 0f) - roll) * min(1f, dt * 6f)

    }

    private fun rockPhase(d: Draw) = (d.t % ROCK_MS).toFloat() / ROCK_MS * TAU

    private fun owner(i: Int, count: Int) = if (count <= 1) 0 else i % count

    // How big pea i is: the first one bounces as a face lands on it, later ones pop in past full
    // size and settle.
    private fun popScale(i: Int, now: Long): Float {
        if (i == 0) {
            if (seenAt < 0) return 1f
            val t = (now - seenAt) / 1000f
            return 1f + 0.12f * exp(-6f * t) * sin(16f * t)
        }
        val t = (now - popAt[i]) / 1000f
        if (t <= 0f) return 0f
        return max(0f, 1f - exp(-7f * t) * cos(13f * t))
    }

    // A heart's size [p] seconds into its cycle: it pops up past full size and settles, holds
    // with a little breath, swells and poofs away, then stays gone until the next cycle.
    private fun heartSize(p: Float): Float = when {
        p < 0.6f -> max(0f, 1f - exp(-9f * p) * cos(12f * p))
        p < POOF_AT -> 1f + 0.04f * sin((p - 0.6f) * 6f)
        p < POOF_AT + POOF_S -> {
            val q = (p - POOF_AT) / POOF_S
            (1f + 0.35f * q) * (1f - q * q)
        }
        else -> 0f
    }

    // The poof: a pale puff and a ring of tiny sparkles flying out from where the heart was,
    // drawn flat over the 3D pass.
    private fun poof(d: Draw, model: FloatArray, style: Int, p: Float, cycle: Long) {
        val q = (p - POOF_AT) / 0.4f
        if (q < 0f || q >= 1f) return
        Tendrils.heartSpot(style, cycle, heartAt)
        val hx = heartAt[0]
        val hy = heartAt[1]
        v4[0] = hx * 1.03f
        v4[1] = hy * 1.03f
        v4[2] = -sqrt(max(0f, 1f - hx * hx - hy * hy)) * 1.03f
        v4[3] = 1f
        Matrix.multiplyMV(w4, 0, model, 0, v4, 0)
        if (!View3D.project(w4[0], w4[1], w4[2], screen)) return
        val u = unit(d)
        val c = d.c
        val x = screen[0]
        val y = screen[1]
        val puff = u * (0.12f + 0.2f * q)
        c.drawCircle(x, y, puff, d.pen.fill(RadialGradient(x, y, puff, rgba(240, 250, 200, 0.55f * (1f - q)), rgba(240, 250, 200, 0f), Shader.TileMode.CLAMP)))
        val reach = u * 0.32f * q.pow(0.6f)
        for (k in 0 until 6) {
            val a = k * TAU / 6 + hash(style, cycle) * TAU
            drawStar(c, d.pen, x + cos(a) * reach, y + sin(a) * reach, d.h * 0.007f * (1f - 0.5f * q), a, 1f - q)
        }
    }

    // Pod units to world: rocked about a point below the frame like a cradle on runners, tilted
    // a little with the head, floating up and down, turned with the head and leaned back.
    private fun pose(m: FloatArray, d: Draw) {
        val u = unit(d)
        val t = (d.t % 600_000L).toFloat()
        val pivotY = d.h * 1.15f
        Matrix.setIdentityM(m, 0)
        Matrix.translateM(m, 0, x, pivotY, 0f)
        Matrix.rotateM(m, 0, sin(rockPhase(d)) * ROCK_DEG + deg(roll), 0f, 0f, 1f)
        Matrix.translateM(m, 0, 0f, d.h * 0.47f + sin(t / 1100f) * u * 0.08f - pivotY, 0f)
        Matrix.rotateM(m, 0, yaw + sin(t / 1900f) * 7f, 0f, 1f, 0f)
        Matrix.rotateM(m, 0, TIP_DEG, 1f, 0f, 0f)
        Matrix.scaleM(m, 0, u, u, u)
    }

    /* ------------------------------ drawing ------------------------------ */

    // The lime ground with a paler middle, stars twinkling in and out, and the pod's soft shadow.
    override fun scene(d: Draw, faces: List<Face>) {
        val c = d.c
        val p = d.pen
        c.drawRect(0f, 0f, d.w, d.h, p.fill(hex("#c4e665")))
        c.drawRect(0f, 0f, d.w, d.h, p.fill(RadialGradient(d.w / 2, d.h * 0.45f, d.w * 0.6f, rgba(215, 240, 130, 0.6f), rgba(215, 240, 130, 0f), Shader.TileMode.CLAMP)))
        stars(d, ground = false)

        // On the ground under the pod's tip, smaller and fainter as it floats up.
        pose(tmp, d)
        v4[0] = 0f
        v4[1] = length / 2
        v4[2] = 0f
        v4[3] = 1f
        Matrix.multiplyMV(w4, 0, tmp, 0, v4, 0)
        val u = unit(d)
        val sx = w4[0]
        val sy = d.h * 0.915f
        val lift = (sy - w4[1]) / u
        val rx = u * (2.3f - lift * 0.15f)
        val s = c.save()
        c.scale(1f, 0.2f, sx, sy)
        c.drawCircle(sx, sy, rx, p.fill(RadialGradient(sx, sy, rx, rgba(40, 60, 10, (0.75f - lift * 0.05f).coerceIn(0.4f, 0.75f)), rgba(40, 60, 10, 0f), Shader.TileMode.CLAMP)))
        c.restoreToCount(s)
    }

    override fun overlay(d: Draw, faces: List<Face>) {
        val pod = Pod3D()
        pose(pod.model, d)
        pod.length = length
        tubes.reset()
        leaves.reset()
        val t = (d.t % 600_000L).toFloat()
        for (i in 0 until peas) {
            val scale = popScale(i, d.t)
            if (scale < 0.01f) continue
            val pea = PeaPart()
            Matrix.setIdentityM(rel, 0)
            Matrix.translateM(rel, 0, 0f, -length / 2 + PodShape.FIRST + PodShape.SPACING * i + sin(t / 430f + i * 2.1f) * 0.03f, -0.05f)
            Matrix.rotateM(rel, 0, sin(t / 610f + i * 1.3f) * 6f, 0f, 1f, 0f)
            Matrix.rotateM(rel, 0, sin(t / 520f + i * 2.4f) * 3f, 0f, 0f, 1f)
            Matrix.scaleM(rel, 0, scale, scale, scale)
            Matrix.multiplyMM(pea.model, 0, pod.model, 0, rel, 0)

            val sample = samples[i]
            if (seenAt >= 0 && faces.isNotEmpty()) {
                val f = faces.firstOrNull { it.rank == owner(i, faces.size) } ?: faces[0]
                // The middle of the features, a little under halfway from the eyes to the mouth;
                // the face's rounded edge reaches the brow, cheeks and chin.
                val centre = toPixels(f, 0f, f.mouth.y * 0.42f)
                sample.ok = true
                sample.x = centre.x
                sample.y = centre.y
                sample.reachX = f.eyeDist * 1.0f
                sample.reachY = f.eyeDist * 1.22f
                sample.roll = f.angle
            }
            if (sample.ok) {
                pea.hasFace = true
                pea.faceX = sample.x
                pea.faceY = sample.y
                pea.reachX = sample.reachX
                pea.reachY = sample.reachY
                pea.roll = sample.roll
                val clock = d.t + i * 1100L
                val cycle = clock / HEART_MS
                val p = (clock % HEART_MS) / 1000f
                Tendrils.build(tubes, leaves, pea.model, i % MAX_PEAS, t + i * 400f, heartSize(p), cycle)
                poof(d, pea.model, i % MAX_PEAS, p, cycle)
            }
            pod.peas += pea
        }
        pod.tendrils = tubes
        pod.leaves = leaves
        d.pods += pod
        stars(d, ground = true)
    }

    // Stars twinkle in and out at spots that change every cycle: scattered over the ground behind,
    // and a few sparkling round the shadow in front. No state, just a hash of the slot and cycle.
    private fun stars(d: Draw, ground: Boolean) {
        val u = unit(d)
        val count = if (ground) 12 else 34
        val shadowX = if (ground) {
            pose(tmp, d)
            v4[0] = 0f
            v4[1] = length / 2
            v4[2] = 0f
            v4[3] = 1f
            Matrix.multiplyMV(w4, 0, tmp, 0, v4, 0)
            w4[0]
        } else {
            0f
        }
        for (k in 0 until count) {
            val slot = if (ground) 1000 + k else k
            val period = 1400L + (hash(slot, 7L) * 1400f).toLong()
            val shifted = d.t + (hash(slot, 3L) * period).toLong()
            val cycle = shifted / period
            val age = (shifted % period).toFloat() / period
            val a = sin(age * PI.toFloat())
            if (a < 0.03f) continue
            val sx: Float
            val sy: Float
            if (ground) {
                val ang = hash(slot, cycle) * TAU
                val rr = sqrt(hash(slot, cycle + 99))
                sx = shadowX + cos(ang) * u * 2.4f * rr
                sy = d.h * 0.915f + sin(ang) * u * 0.5f * rr - age * u * 0.35f
            } else {
                sx = hash(slot, cycle) * d.w
                sy = hash(slot, cycle + 77) * d.h - age * d.h * 0.02f
            }
            val r = d.h * (0.007f + 0.011f * hash(slot, cycle + 5)) * (0.6f + 0.4f * a)
            drawStar(d.c, d.pen, sx, sy, r, hash(slot, cycle + 11) * TAU, a)
        }
    }

    private fun drawStar(c: Canvas, p: Pen, x: Float, y: Float, r: Float, spin: Float, alpha: Float) {
        c.drawCircle(x, y, r * 1.9f, p.fill(RadialGradient(x, y, r * 1.9f, rgba(255, 255, 255, 0.4f * alpha), rgba(255, 255, 255, 0f), Shader.TileMode.CLAMP)))
        star.reset()
        for (k in 0 until 10) {
            val a = spin + k * PI.toFloat() / 5
            val rr = if (k % 2 == 0) r else r * 0.45f
            if (k == 0) star.moveTo(x + sin(a) * rr, y - cos(a) * rr) else star.lineTo(x + sin(a) * rr, y - cos(a) * rr)
        }
        star.close()
        c.drawPath(star, p.fill(rgba(255, 255, 255, alpha)))
    }

    /** 0..1 from a slot and a cycle, the same every time. */
    internal fun hash(a: Int, b: Long): Float {
        var h = (a.toLong() * 374761393L + b * 668265263L).toInt()
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xffff) / 65535f
    }
}

/* ------------------------------ the tendrils ------------------------------ */

// The curly yellow-green tendrils each pea wears, as round-ended tubes in pea units (radius 1,
// y down, the face on the -z side). Styles follow the reference, top to bottom.
private object Tendrils {
    private val c = FloatArray(3 * 8)

    private val spot = FloatArray(3)
    // Each style's heart home (x, y, turn): top left of the top pea, top right of the middle
    // one, beside the cheek on the bottom one.
    private val HEARTS = floatArrayOf(-0.52f, -0.62f, -0.5f, 0.55f, -0.6f, 0.5f, -0.72f, 0.28f, -0.3f)

    fun build(tb: TubeBuilder, leaves: TubeBuilder, m: FloatArray, style: Int, t: Float, heartSize: Float, heartCycle: Long) {
        when (style) {
            0 -> chinHands(tb, m, t)
            1 -> waving(tb, m, t)
            else -> peekaboo(tb, m, t)
        }
        if (heartSize > 0.01f) {
            heartSpot(style, heartCycle, spot)
            heart(tb, leaves, m, spot[0], spot[1], spot[2], heartSize)
        }
    }

    /** Where heart [style] grows this cycle, into out (x, y, turn): its home, nudged a little each time. */
    fun heartSpot(style: Int, cycle: Long, out: FloatArray) {
        out[0] = HEARTS[style * 3] + (PeasInAPod.hash(style, cycle) - 0.5f) * 0.12f
        out[1] = HEARTS[style * 3 + 1] + (PeasInAPod.hash(style + 7, cycle) - 0.5f) * 0.12f
        out[2] = HEARTS[style * 3 + 2] + (PeasInAPod.hash(style + 13, cycle) - 0.5f) * 0.5f
    }

    // A point on the pea's front at (x, y), lifted off the skin.
    private fun on(k: Int, x: Float, y: Float, lift: Float = 0.035f) {
        val z = -sqrt(max(0f, 1f - x * x - y * y))
        val s = (1f + lift) / max(1e-4f, sqrt(x * x + y * y + z * z))
        c[k * 3] = x * s
        c[k * 3 + 1] = y * s
        c[k * 3 + 2] = z * s
    }

    private fun at(k: Int, x: Float, y: Float, z: Float) {
        c[k * 3] = x
        c[k * 3 + 1] = y
        c[k * 3 + 2] = z
    }

    // Top pea: two tendrils along the jaw, meeting under the chin in little three-fingered hands.
    private fun chinHands(tb: TubeBuilder, m: FloatArray, t: Float) {
        for (side in intArrayOf(-1, 1)) {
            val sx = side.toFloat()
            val wig = sin(t / 240f + side) * 0.02f
            val ex = (0.16f + wig) * sx
            val ey = 0.58f - wig
            on(0, 0.74f * sx, 0.3f)
            on(1, 0.66f * sx, 0.52f)
            on(2, 0.48f * sx, 0.66f)
            on(3, 0.3f * sx, 0.67f)
            on(4, ex, ey)
            tb.tube(m, c, 5, 0.04f, 0.036f)
            hand(tb, m, ex, ey, null, -PI.toFloat() / 2 - 0.5f * sx, sin(t / 200f + side) * 0.15f)
        }
    }

    // Middle pea: arms out over the pod's lips, waving on their own beats.
    private fun waving(tb: TubeBuilder, m: FloatArray, t: Float) {
        for (side in intArrayOf(-1, 1)) {
            val sx = side.toFloat()
            val wave = sin(t / 300f + side * 1.3f) * 0.28f
            val cw = cos(wave)
            val sw = sin(wave)
            on(0, 0.78f * sx, 0.3f, 0.02f)
            val bx = c[0]
            val by = c[1]
            // Offsets from the shoulder, turned about it by the wave, then mirrored per side.
            val off = floatArrayOf(0.22f, 0f, -0.66f, 0.42f, -0.12f, -0.74f, 0.54f, -0.24f, -0.78f)
            for (k in 0 until 3) {
                val ox = off[k * 3]
                val oy = off[k * 3 + 1]
                at(k + 1, bx + (ox * cw - oy * sw) * sx, by + ox * sw + oy * cw, off[k * 3 + 2])
            }
            tb.tube(m, c, 4, 0.043f, 0.036f)
            val a = -0.9f - wave
            hand(tb, m, c[9], c[10], c[11], if (side > 0) a else PI.toFloat() - a, sin(t / 160f + side) * 0.2f)
        }
    }

    // Bottom pea: peekaboo. An arm reaches in from each side just under the eye, and its
    // three-fingered hand spreads up over the eye. The hands peek up and down a little.
    private fun peekaboo(tb: TubeBuilder, m: FloatArray, t: Float) {
        for (side in intArrayOf(-1, 1)) {
            val sx = side.toFloat()
            val peek = sin(t / 450f + side * 0.8f) * 0.02f
            val hx = 0.32f * sx
            val hy = -0.15f + peek
            on(0, 0.84f * sx, -0.06f)
            on(1, 0.64f * sx, -0.1f + peek * 0.3f)
            on(2, 0.46f * sx, -0.13f + peek * 0.7f)
            on(3, hx, hy)
            tb.tube(m, c, 4, 0.03f, 0.036f)
            // Fanned round up-and-in, so one finger points up over the eye and one toward the nose.
            hand(tb, m, hx, hy, null, if (side > 0) -2.16f else -0.98f, sin(t / 210f + side) * 0.12f, size = 1.5f, spread = 0.62f)
        }
    }

    // Three short fingers fanned round a direction in the face's plane, from (x, y) on the skin,
    // or at a given depth when z isn't null.
    private fun hand(tb: TubeBuilder, m: FloatArray, x: Float, y: Float, z: Float?, dir: Float, wiggle: Float, size: Float = 1f, spread: Float = 0.55f) {
        for (f in -1..1) {
            val a = dir + f * spread + wiggle
            val len = (if (f == 0) 0.11f else 0.09f) * size
            if (z == null) {
                on(0, x, y)
                on(1, x + cos(a) * len * 0.5f, y + sin(a) * len * 0.5f)
                on(2, x + cos(a) * len, y + sin(a) * len)
            } else {
                at(0, x, y, z)
                at(1, x + cos(a) * len * 0.5f, y + sin(a) * len * 0.5f, z)
                at(2, x + cos(a) * len, y + sin(a) * len, z)
            }
            tb.tube(m, c, 3, 0.032f, 0.026f)
        }
    }

    // A little heart made of two leaves sprouting from one point, the left one a bit bigger and
    // leaning further out, with a wisp of stem curling away underneath. Up is -y, turned by [turn];
    // [size] 1 is full grown.
    private fun heart(tb: TubeBuilder, leaves: TubeBuilder, m: FloatArray, x: Float, y: Float, turn: Float, size: Float) {
        val up = -PI.toFloat() / 2 + turn
        // Full grown it's about as big as the old two-stroke heart, which the user judged right.
        leaves.leaf(m, x, y, up - 0.6f, 0.17f * size, 0.072f * size, 0.03f)
        leaves.leaf(m, x, y, up + 0.45f, 0.15f * size, 0.064f * size, 0.036f)
        val ct = cos(turn)
        val st = sin(turn)
        val stem = floatArrayOf(0f, 0f, 0.025f, 0.03f, 0.06f, 0.035f)
        for (k in 0 until 3) {
            val sx = stem[k * 2] * size
            val sy = stem[k * 2 + 1] * size
            on(k, x + sx * ct - sy * st, y + sx * st + sy * ct, 0.03f)
        }
        tb.tube(m, c, 3, 0.012f * size, 0.008f * size)
    }
}

/* ------------------------------ 3D parts ------------------------------ */

/** The pod's proportions, in pod units (a pea's radius), y down the pod from its top. */
object PodShape {
    /** The first pea's centre below the pod's top, and the gap between pea centres. */
    const val FIRST = 1.45f
    const val SPACING = 1.6f

    /** The pod's length holding this many peas; fractional while it stretches. */
    fun length(peas: Float) = 3f + 1.05f * peas
}

/** One pea, posed in world space, with the face it wears. */
class PeaPart {
    val model = FloatArray(16)
    var hasFace = false
    var faceX = 0f
    var faceY = 0f
    var reachX = 0f
    var reachY = 0f
    var roll = 0f
}

/** One pod for this frame, posed by PeasInAPod and drawn by PodRenderer. */
class Pod3D {
    /** Pod units to world, with the pod centred on its origin. */
    val model = FloatArray(16)
    var length = PodShape.length(1f)
    val peas = ArrayList<PeaPart>()
    /** Tendril tubes, already in world space. The filter reuses the builder each frame, which is
     *  safe because painting and compositing share the render thread. */
    var tendrils: TubeBuilder? = null
    /** The hearts' leaves, in world space like the tendrils, drawn paler. */
    var leaves: TubeBuilder? = null
}

/**
 * Round-ended tubes through a few control points, smoothed into Catmull-Rom curves and built
 * straight into world space as interleaved position and normal with triangle indices.
 */
class TubeBuilder {
    var data = FloatArray(6 * 4096)
        private set
    var verts = 0
        private set
    var indices = IntArray(6 * 8192)
        private set
    var count = 0
        private set

    private val pts = FloatArray(3 * 64)
    private val v4 = FloatArray(4)
    private val w4 = FloatArray(4)
    private val tan = FloatArray(3)
    private val nrm = floatArrayOf(1f, 0f, 0f)
    private val bin = FloatArray(3)

    fun reset() {
        verts = 0
        count = 0
    }

    /** [n] control points (x, y, z) from [ctrl] in local units, through [m]; radius eases [r0] to [r1]. */
    fun tube(m: FloatArray, ctrl: FloatArray, n: Int, r0: Float, r1: Float, sub: Int = 5) {
        if (n < 2) return
        var np = 0
        for (seg in 0 until n - 1) {
            val i0 = max(seg - 1, 0) * 3
            val i1 = seg * 3
            val i2 = (seg + 1) * 3
            val i3 = min(seg + 2, n - 1) * 3
            for (k in 0 until sub) {
                val t = k.toFloat() / sub
                val t2 = t * t
                val t3 = t2 * t
                for (a in 0 until 3) {
                    val p0 = ctrl[i0 + a]
                    val p1 = ctrl[i1 + a]
                    val p2 = ctrl[i2 + a]
                    val p3 = ctrl[i3 + a]
                    v4[a] = 0.5f * (2 * p1 + (p2 - p0) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (3 * p1 - p0 - 3 * p2 + p3) * t3)
                }
                world(m, np++)
            }
        }
        for (a in 0 until 3) v4[a] = ctrl[(n - 1) * 3 + a]
        world(m, np++)
        val scale = sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2])

        var first = true
        for (i in 0 until np) {
            val a = max(i - 1, 0) * 3
            val b = min(i + 1, np - 1) * 3
            tan[0] = pts[b] - pts[a]
            tan[1] = pts[b + 1] - pts[a + 1]
            tan[2] = pts[b + 2] - pts[a + 2]
            if (!normalize(tan)) continue
            // Carry the ring's frame along the curve so the tube doesn't twist.
            val d = nrm[0] * tan[0] + nrm[1] * tan[1] + nrm[2] * tan[2]
            nrm[0] -= tan[0] * d
            nrm[1] -= tan[1] * d
            nrm[2] -= tan[2] * d
            if (first || !normalize(nrm)) {
                // Any direction across the tangent to start from.
                if (abs(tan[2]) < 0.9f) {
                    nrm[0] = -tan[1]
                    nrm[1] = tan[0]
                    nrm[2] = 0f
                } else {
                    nrm[0] = 0f
                    nrm[1] = tan[2]
                    nrm[2] = -tan[1]
                }
                normalize(nrm)
            }
            bin[0] = tan[1] * nrm[2] - tan[2] * nrm[1]
            bin[1] = tan[2] * nrm[0] - tan[0] * nrm[2]
            bin[2] = tan[0] * nrm[1] - tan[1] * nrm[0]
            val r = (r0 + (r1 - r0) * i / (np - 1)) * scale
            val o = i * 3
            if (first) {
                // A rounded cap behind the first ring.
                ring(pts[o] - tan[0] * r * 0.97f, pts[o + 1] - tan[1] * r * 0.97f, pts[o + 2] - tan[2] * r * 0.97f, r * 0.26f, -0.97f, true)
                ring(pts[o] - tan[0] * r * 0.7f, pts[o + 1] - tan[1] * r * 0.7f, pts[o + 2] - tan[2] * r * 0.7f, r * 0.71f, -0.7f, false)
                first = false
            }
            ring(pts[o], pts[o + 1], pts[o + 2], r, 0f, false)
            if (i == np - 1) {
                ring(pts[o] + tan[0] * r * 0.7f, pts[o + 1] + tan[1] * r * 0.7f, pts[o + 2] + tan[2] * r * 0.7f, r * 0.71f, 0.7f, false)
                ring(pts[o] + tan[0] * r * 0.97f, pts[o + 1] + tan[1] * r * 0.97f, pts[o + 2] + tan[2] * r * 0.97f, r * 0.26f, 0.97f, false)
            }
        }
    }

    /**
     * A flat leaf lying on the unit sphere through [m]: pointed at (x, y) on the sphere's front,
     * rounded at its tip [length] away toward [angle] (in the front's plane, y down), [width] at
     * its widest either side of the midrib, [lift] off the skin.
     */
    fun leaf(m: FloatArray, x: Float, y: Float, angle: Float, length: Float, width: Float, lift: Float) {
        val steps = 8
        if ((verts + (steps + 1) * 2) * 6 > data.size) data = data.copyOf(data.size * 2)
        if (count + steps * 6 > indices.size) indices = indices.copyOf(indices.size * 2)
        val ux = cos(angle)
        val uy = sin(angle)
        val base = verts
        for (k in 0..steps) {
            val s = k.toFloat() / steps
            // Plump: widening fast from the point, widest past halfway, round at the tip.
            val w = width * s.pow(0.45f) * sqrt(max(0f, 1f - s * s * s))
            val cx = x + ux * length * s
            val cy = y + uy * length * s
            for (side in intArrayOf(-1, 1)) {
                val lx = cx - uy * w * side
                val ly = cy + ux * w * side
                val lz = -sqrt(max(0f, 1f - lx * lx - ly * ly))
                val len = max(1e-4f, sqrt(lx * lx + ly * ly + lz * lz))
                val o = verts * 6
                v4[0] = lx / len * (1f + lift)
                v4[1] = ly / len * (1f + lift)
                v4[2] = lz / len * (1f + lift)
                v4[3] = 1f
                Matrix.multiplyMV(w4, 0, m, 0, v4, 0)
                data[o] = w4[0]
                data[o + 1] = w4[1]
                data[o + 2] = w4[2]
                // The sphere's normal there, turned with the pea.
                v4[3] = 0f
                Matrix.multiplyMV(w4, 0, m, 0, v4, 0)
                val nl = max(1e-6f, sqrt(w4[0] * w4[0] + w4[1] * w4[1] + w4[2] * w4[2]))
                data[o + 3] = w4[0] / nl
                data[o + 4] = w4[1] / nl
                data[o + 5] = w4[2] / nl
                verts++
            }
        }
        for (k in 0 until steps) {
            val a = base + k * 2
            indices[count++] = a
            indices[count++] = a + 1
            indices[count++] = a + 3
            indices[count++] = a
            indices[count++] = a + 3
            indices[count++] = a + 2
        }
    }

    private fun world(m: FloatArray, i: Int) {
        v4[3] = 1f
        Matrix.multiplyMV(w4, 0, m, 0, v4, 0)
        pts[i * 3] = w4[0]
        pts[i * 3 + 1] = w4[1]
        pts[i * 3 + 2] = w4[2]
    }

    // A ring of vertices; [along] tips the normals toward the tangent for the caps. Rings after
    // the first of a tube join to the ring before.
    private fun ring(cx: Float, cy: Float, cz: Float, r: Float, along: Float, start: Boolean) {
        if ((verts + SIDES) * 6 > data.size) data = data.copyOf(data.size * 2)
        if (count + SIDES * 6 > indices.size) indices = indices.copyOf(indices.size * 2)
        val across = sqrt(max(0f, 1f - along * along))
        val base = verts
        for (s in 0 until SIDES) {
            val a = s * TAU / SIDES
            val ca = cos(a)
            val sa = sin(a)
            val dx = nrm[0] * ca + bin[0] * sa
            val dy = nrm[1] * ca + bin[1] * sa
            val dz = nrm[2] * ca + bin[2] * sa
            val o = verts * 6
            data[o] = cx + dx * r
            data[o + 1] = cy + dy * r
            data[o + 2] = cz + dz * r
            data[o + 3] = dx * across + tan[0] * along
            data[o + 4] = dy * across + tan[1] * along
            data[o + 5] = dz * across + tan[2] * along
            verts++
        }
        if (start) return
        val prev = base - SIDES
        for (s in 0 until SIDES) {
            val s2 = (s + 1) % SIDES
            indices[count++] = prev + s
            indices[count++] = prev + s2
            indices[count++] = base + s2
            indices[count++] = prev + s
            indices[count++] = base + s2
            indices[count++] = base + s
        }
    }

    private fun normalize(v: FloatArray): Boolean {
        val len = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        if (len < 1e-6f) return false
        v[0] /= len
        v[1] /= len
        v[2] /= len
        return true
    }

    private companion object {
        const val SIDES = 8
    }
}

/** A mesh whose vertices change every frame: interleaved position and normal, indexed. */
class DynamicMesh {
    private val vbo: Int
    private val ibo: Int
    private var fb: FloatBuffer = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var ib: IntBuffer = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer()
    private var count = 0

    init {
        val ids = IntArray(2)
        GLES20.glGenBuffers(2, ids, 0)
        vbo = ids[0]
        ibo = ids[1]
    }

    fun update(tb: TubeBuilder) {
        val floats = tb.verts * 6
        if (fb.capacity() < floats) fb = ByteBuffer.allocateDirect(floats * 8).order(ByteOrder.nativeOrder()).asFloatBuffer()
        if (ib.capacity() < tb.count) ib = ByteBuffer.allocateDirect(tb.count * 8).order(ByteOrder.nativeOrder()).asIntBuffer()
        fb.clear()
        fb.put(tb.data, 0, floats).flip()
        ib.clear()
        ib.put(tb.indices, 0, tb.count).flip()
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, floats * 4, fb, GLES20.GL_DYNAMIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, tb.count * 4, ib, GLES20.GL_DYNAMIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
        count = tb.count
    }

    fun draw(p: Program) {
        if (count == 0) return
        val ap = p.a("aPos")
        val an = p.a("aNormal")
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glEnableVertexAttribArray(ap)
        GLES20.glVertexAttribPointer(ap, 3, GLES20.GL_FLOAT, false, 24, 0)
        if (an >= 0) {
            GLES20.glEnableVertexAttribArray(an)
            GLES20.glVertexAttribPointer(an, 3, GLES20.GL_FLOAT, false, 24, 12)
        }
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, count, GLES20.GL_UNSIGNED_INT, 0)
        GLES20.glDisableVertexAttribArray(ap)
        if (an >= 0) GLES20.glDisableVertexAttribArray(an)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
    }
}

object PodShaders {
    // The pod is one grid bent into shape here, so it can stretch as peas arrive. aPos is
    // (u, v, depth): u runs round the shell from one lip (-1) past the back (0) to the other (1),
    // v runs down the pod (0 to 1), depth is 0 on the outer skin and 1 on the inner. aNormal.x
    // marks the rolled lips (1), where aPos.z instead runs across the roll from outer to inner.
    // Normals come from neighbouring points, so they follow every bulge.
    val POD_VERTEX = """
        #version 300 es
        uniform mat4 uModel;
        uniform mat4 uViewProj;
        uniform float uL;
        uniform float uC0;
        uniform float uCLast;
        uniform float uClose;
        in vec3 aPos;
        in vec3 aNormal;
        out vec3 vWorld;
        out vec3 vNormal;
        out vec2 vSurface;
        out float vInner;
        out float vLip;
        const float PI = 3.14159265;
        const float R = 1.24;
        const float THICK = 0.08;
        const float OPEN = 0.98;

        // Pointed at both ends, round in the middle, pinched a little between the peas.
        float radius(float s) {
            float e = clamp(min(s, uL - s) / 1.55, 0.0, 1.0);
            float r = R * pow(sin(e * PI * 0.5), 0.85);
            if (s > uC0 && s < uCLast) r *= 1.0 - 0.035 * (1.0 - cos((s - uC0) / 1.6 * 2.0 * PI));
            return r;
        }

        // Half the opening's angle: open down past the last pea, narrower at the top, and the
        // lips meeting in a seam below.
        float opening(float s) {
            float top = mix(0.45, 1.0, smoothstep(0.0, 1.2, s));
            float bottom = 1.0 - smoothstep(uCLast + 0.2, uClose, s);
            return OPEN * top * bottom;
        }

        vec3 shell(float u, float s, float depth) {
            float phi = u * (PI - opening(s));
            float lip = smoothstep(0.7, 1.0, abs(u));
            float r = radius(s) * (1.0 + 0.05 * lip - depth * THICK / R);
            return vec3(sin(phi) * r, s - 0.5 * uL, cos(phi) * r);
        }

        vec3 rim(float side, float s, float w) {
            float phi = side * (PI - opening(s));
            vec3 along = side * vec3(cos(phi), 0.0, -sin(phi));
            return shell(side, s, w) + along * sin(w * PI) * 0.6 * THICK * radius(s) / R;
        }

        void main() {
            float s = aPos.y * uL;
            float e = 0.004 * uL;
            vec3 p;
            vec3 ds;
            vec3 dw;
            vec3 want;
            if (aNormal.x < 0.5) {
                p = shell(aPos.x, s, aPos.z);
                ds = shell(aPos.x, s + e, aPos.z) - shell(aPos.x, s - e, aPos.z);
                dw = shell(aPos.x + 0.004, s, aPos.z) - shell(aPos.x - 0.004, s, aPos.z);
                want = vec3(p.x, 0.0, p.z) * (aPos.z > 0.5 ? -1.0 : 1.0);
            } else {
                p = rim(aPos.x, s, aPos.z);
                ds = rim(aPos.x, s + e, aPos.z) - rim(aPos.x, s - e, aPos.z);
                dw = rim(aPos.x, s, aPos.z + 0.02) - rim(aPos.x, s, aPos.z - 0.02);
                float phi = aPos.x * (PI - opening(s));
                want = vec3(sin(phi), 0.0, cos(phi)) * cos(aPos.z * PI) + aPos.x * vec3(cos(phi), 0.0, -sin(phi)) * sin(aPos.z * PI);
            }
            vec3 n = cross(ds, dw);
            float len = length(n);
            n = len > 1e-9 ? n / len : vec3(0.0, sign(s - 0.5 * uL), 0.0);
            if (dot(n, want) < 0.0) n = -n;
            vec4 w = uModel * vec4(p, 1.0);
            vWorld = w.xyz;
            vNormal = mat3(uModel) * n;
            vSurface = aPos.xy;
            vInner = aPos.z;
            vLip = aNormal.x;
            gl_Position = uViewProj * w;
        }
    """.trimIndent()

    // Lit from up and to the left in front (world y down, z away), as in the reference.
    val POD = """
        #version 300 es
        precision highp float;
        in vec3 vWorld;
        in vec3 vNormal;
        in vec2 vSurface;
        in float vInner;
        in float vLip;
        uniform vec3 uEye;
        out vec4 outColor;
        void main() {
            vec3 n = normalize(vNormal);
            vec3 v = normalize(uEye - vWorld);
            if (dot(n, v) < 0.0) n = -n;
            vec3 key = normalize(vec3(-0.55, -0.7, -0.45));
            float wrap = dot(n, key) * 0.5 + 0.5;
            float ndv = max(dot(n, v), 0.0);
            // The skin: fresh green, darker away from the light, with faint veins down its length.
            float vein = 0.5 + 0.5 * sin(vSurface.x * 38.0 + sin(vSurface.y * 17.0) * 1.5);
            vec3 col = mix(vec3(0.17, 0.27, 0.07), vec3(0.52, 0.72, 0.28), smoothstep(0.15, 0.95, wrap));
            col *= 0.95 + 0.05 * vein;
            col += vec3(0.22, 0.26, 0.1) * pow(max(dot(n, normalize(key + v)), 0.0), 28.0);
            // The inside: shaded, and darkest deep at the back.
            float inner = smoothstep(0.3, 0.7, vInner) * (1.0 - vLip);
            vec3 inside = mix(vec3(0.07, 0.12, 0.03), vec3(0.28, 0.4, 0.13), smoothstep(0.2, 0.9, wrap));
            inside *= mix(0.45, 1.0, abs(vSurface.x));
            col = mix(col, inside, inner);
            // The rolled lip catches the light.
            col = mix(col, col * 1.15 + vec3(0.04, 0.06, 0.0), vLip);
            // A little light through the skin round the silhouette.
            col += vec3(0.08, 0.12, 0.03) * pow(1.0 - ndv, 3.0);
            outColor = vec4(col, 1.0);
        }
    """.trimIndent()

    // A glossy pea with the face laid over its front: a rounded-square window onto the camera
    // that fades at its edge, blushing cheeks, shaded with the pea so it sits on the curve.
    val PEA = """
        #version 300 es
        precision highp float;
        in vec3 vWorld;
        in vec3 vNormal;
        in vec3 vLocal;
        in vec3 vLocalNormal;
        uniform vec3 uEye;
        uniform sampler2D uFace;
        uniform vec2 uSize;
        uniform vec2 uCentre;
        uniform vec2 uReach;
        uniform float uRoll;
        uniform float uHasFace;
        out vec4 outColor;

        vec3 frameAt(vec2 px) {
            return texture(uFace, vec2(px.x / uSize.x, 1.0 - px.y / uSize.y)).rgb;
        }

        void main() {
            vec3 ln = normalize(vLocal);
            vec3 n = normalize(vNormal);
            vec3 v = normalize(uEye - vWorld);
            vec3 key = normalize(vec3(-0.55, -0.7, -0.45));
            float wrap = dot(n, key) * 0.5 + 0.5;
            float ndv = max(dot(n, v), 0.0);
            vec3 col = mix(vec3(0.25, 0.32, 0.07), vec3(0.74, 0.84, 0.36), smoothstep(0.12, 0.95, wrap));
            col = mix(col * 0.75, col, smoothstep(0.0, 0.5, ndv));
            float sheen = pow(max(dot(n, normalize(normalize(vec3(0.45, -0.75, -0.5)) + v)), 0.0), 22.0);
            float faceMask = 0.0;
            if (uHasFace > 0.5 && ln.z < 0.0) {
                // About half the pea's width, brow to chin, with pea skin all round it.
                vec2 q = vec2(ln.x / 0.54, (ln.y - 0.08) / 0.62);
                float d = pow(pow(abs(q.x), 2.6) + pow(abs(q.y), 2.6), 1.0 / 2.6);
                float mask = (1.0 - smoothstep(0.8, 1.0, d)) * (1.0 - smoothstep(-0.4, -0.1, ln.z));
                if (mask > 0.0) {
                    vec2 o = q * uReach;
                    float c = cos(uRoll);
                    float sn = sin(uRoll);
                    vec3 face = frameAt(uCentre + vec2(c * o.x - sn * o.y, sn * o.x + c * o.y));
                    float blush = (1.0 - smoothstep(0.05, 0.3, length(q - vec2(0.56, 0.02))))
                        + (1.0 - smoothstep(0.05, 0.3, length(q - vec2(-0.56, 0.02))));
                    face = mix(face, vec3(0.95, 0.42, 0.45), clamp(blush, 0.0, 1.0) * 0.5);
                    float shade = 0.78 + 0.3 * smoothstep(0.12, 0.95, wrap);
                    col = mix(col, face * shade, mask);
                    faceMask = mask;
                }
            }
            // The sheen mostly on the skin: across the face it washed out the forehead.
            col += vec3(0.42, 0.45, 0.2) * sheen * (1.0 - 0.8 * faceMask);
            outColor = vec4(col, 1.0);
        }
    """.trimIndent()

    // The hearts' leaves: pale fresh green, lit with the pea they lie on.
    val LEAF = """
        #version 300 es
        precision highp float;
        in vec3 vWorld;
        in vec3 vNormal;
        in vec3 vLocal;
        in vec3 vLocalNormal;
        uniform vec3 uEye;
        out vec4 outColor;
        void main() {
            vec3 n = normalize(vNormal);
            vec3 v = normalize(uEye - vWorld);
            if (dot(n, v) < 0.0) n = -n;
            vec3 key = normalize(vec3(-0.55, -0.7, -0.45));
            float wrap = dot(n, key) * 0.5 + 0.5;
            vec3 col = mix(vec3(0.72, 0.82, 0.38), vec3(0.94, 0.99, 0.62), smoothstep(0.2, 0.9, wrap));
            outColor = vec4(col, 1.0);
        }
    """.trimIndent()

    // Yellow-green, darkening toward the silhouette so each stroke reads with an outline.
    val TENDRIL = """
        #version 300 es
        precision highp float;
        in vec3 vWorld;
        in vec3 vNormal;
        in vec3 vLocal;
        in vec3 vLocalNormal;
        uniform vec3 uEye;
        out vec4 outColor;
        void main() {
            vec3 n = normalize(vNormal);
            vec3 v = normalize(uEye - vWorld);
            if (dot(n, v) < 0.0) n = -n;
            vec3 key = normalize(vec3(-0.55, -0.7, -0.45));
            float wrap = dot(n, key) * 0.5 + 0.5;
            float ndv = max(dot(n, v), 0.0);
            vec3 col = mix(vec3(0.52, 0.64, 0.06), vec3(0.9, 0.97, 0.45), smoothstep(0.2, 0.9, wrap));
            col *= mix(0.55, 1.0, smoothstep(0.15, 0.55, ndv));
            col += vec3(0.3) * pow(max(dot(n, normalize(key + v)), 0.0), 30.0);
            outColor = vec4(col, 1.0);
        }
    """.trimIndent()
}

/** Draws Pod3D pods into whatever framebuffer is bound, which needs a depth buffer. All opaque. */
class PodRenderer {
    private val pPod = Program(PodShaders.POD_VERTEX, PodShaders.POD)
    private val pPea = Program(Shaders3D.VERTEX, PodShaders.PEA)
    private val pTendril = Program(Shaders3D.VERTEX, PodShaders.TENDRIL)
    private val pLeaf = Program(Shaders3D.VERTEX, PodShaders.LEAF)
    private val pod = podMesh()
    private val sphere = Meshes.lathe((0..28).map { k -> floatArrayOf(sin(PI * k / 28).toFloat(), -cos(PI * k / 28).toFloat()) }, 44)
    private val tendrils = DynamicMesh()
    private val leafMesh = DynamicMesh()
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    fun draw(pods: List<Pod3D>, faceTex: Int) {
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glDepthMask(true)
        GLES20.glClearDepthf(1f)
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glDisable(GLES20.GL_BLEND)
        for (p in pods) {
            val peas = ((p.length - PodShape.length(0f)) / (PodShape.length(1f) - PodShape.length(0f))).coerceIn(1f, 3f)
            val last = PodShape.FIRST + PodShape.SPACING * (peas - 1f)
            begin(pPod, p.model)
            GLES20.glUniform1f(pPod.u("uL"), p.length)
            GLES20.glUniform1f(pPod.u("uC0"), PodShape.FIRST)
            GLES20.glUniform1f(pPod.u("uCLast"), last)
            GLES20.glUniform1f(pPod.u("uClose"), min(p.length - 0.15f, last + 1.7f))
            pod.draw(pPod)
            for (pea in p.peas) {
                begin(pPea, pea.model)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, faceTex)
                GLES20.glUniform1i(pPea.u("uFace"), 0)
                GLES20.glUniform2f(pPea.u("uSize"), FRAME_W.toFloat(), FRAME_H.toFloat())
                GLES20.glUniform2f(pPea.u("uCentre"), pea.faceX, pea.faceY)
                GLES20.glUniform2f(pPea.u("uReach"), pea.reachX, pea.reachY)
                GLES20.glUniform1f(pPea.u("uRoll"), pea.roll)
                GLES20.glUniform1f(pPea.u("uHasFace"), if (pea.hasFace) 1f else 0f)
                sphere.draw(pPea)
            }
            val tb = p.tendrils
            if (tb != null && tb.count > 0) {
                begin(pTendril, identity)
                tendrils.update(tb)
                tendrils.draw(pTendril)
            }
            val lv = p.leaves
            if (lv != null && lv.count > 0) {
                begin(pLeaf, identity)
                leafMesh.update(lv)
                leafMesh.draw(pLeaf)
            }
        }
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    private fun begin(p: Program, model: FloatArray) {
        p.use()
        GLES20.glUniformMatrix4fv(p.u("uModel"), 1, false, model, 0)
        GLES20.glUniformMatrix4fv(p.u("uViewProj"), 1, false, View3D.viewProj, 0)
        GLES20.glUniform3fv(p.u("uEye"), 1, View3D.eye, 0)
    }

    // The grid PodShaders.POD_VERTEX bends into the pod: outer and inner skins, and a strip
    // rolling round each lip between them.
    private fun podMesh(): Mesh {
        val us = 44
        val vs = 80
        val ws = 6
        val data = FloatArray(((us + 1) * (vs + 1) * 2 + (ws + 1) * (vs + 1) * 2) * 6)
        val idx = ArrayList<Int>()
        var o = 0
        var base = 0
        fun grid(cols: Int, fill: (col: Int, row: Int) -> Unit) {
            for (row in 0..vs) for (col in 0..cols) fill(col, row)
            for (row in 0 until vs) {
                for (col in 0 until cols) {
                    val a = base + row * (cols + 1) + col
                    val b = a + 1
                    val c = a + cols + 2
                    val d = a + cols + 1
                    idx += listOf(a, b, c, a, c, d)
                }
            }
            base += (cols + 1) * (vs + 1)
        }
        for (depth in 0..1) {
            grid(us) { col, row ->
                data[o++] = -1f + 2f * col / us
                data[o++] = row.toFloat() / vs
                data[o++] = depth.toFloat()
                data[o++] = 0f
                data[o++] = 0f
                data[o++] = 0f
            }
        }
        for (side in intArrayOf(-1, 1)) {
            grid(ws) { col, row ->
                data[o++] = side.toFloat()
                data[o++] = row.toFloat() / vs
                data[o++] = col.toFloat() / ws
                data[o++] = 1f
                data[o++] = 0f
                data[o++] = 0f
            }
        }
        return Mesh(data, idx.toIntArray())
    }
}
