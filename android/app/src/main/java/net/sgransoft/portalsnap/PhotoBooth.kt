package net.sgransoft.portalsnap

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.opengl.Matrix
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// Rebuilds of Meta's Portal Photo Booth effects — see android/PHOTOBOOTH.md for what each
// original looked like and how it behaved. New art throughout; nothing of Meta's is reused.

internal val rng = Random()

internal fun rnd(a: Float, b: Float) = a + rng.nextFloat() * (b - a)

/* ------------------------------ particles ------------------------------ */

class Particle(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    val life: Float,
    val size: Float,
    val color: Int,
    val spin: Float = 0f,
) {
    var age = 0f
    var rot = rnd(0f, TAU)

    val fade get() = 1f - age / life
}

/** Frame-space particles: they keep going after the head that made them has moved on. */
class Particles(private val cap: Int = 400) {
    val list = ArrayList<Particle>()

    fun add(p: Particle) {
        if (list.size < cap) list += p
    }

    fun step(dtMs: Float, gravity: Float = 0f, drag: Float = 0f) {
        val dt = dtMs / 1000f
        val it = list.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.age += dt
            if (p.age >= p.life) {
                it.remove()
                continue
            }
            p.vy += gravity * dt
            if (drag > 0) {
                val k = max(0f, 1 - drag * dt)
                p.vx *= k
                p.vy *= k
            }
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.rot += p.spin * dt
        }
    }
}

/* ------------------------------- Mirror ------------------------------- */

// The left half of the picture (as the child sees it) reflected onto the right.
object Mirror : Filter("mirror", "Mirror", "🪞", Mode.FAST) {
    override val usesOver = false
    override val usesFx = true

    override fun fx(d: Draw, faces: List<Face>, fx: FrameFx) {
        fx.kind = FrameFx.MIRROR
    }
}

/* ---------------------------- Pop Silhouette ---------------------------- */

// Rebuilt from its reference video in PopArt.kt.

/**
 * A nod: head pitch swings away from its resting angle and comes back within about a second.
 * Direction-agnostic on purpose — a nod down-up and a flick up-down both count, and the sign
 * of MediaPipe's pose matrix does not have to be trusted.
 */
internal class Nod {
    private var base = Float.NaN
    private var sign = 0
    private var at = 0L
    private var last = 0L

    fun update(pitch: Float, now: Long): Boolean {
        if (base.isNaN()) {
            base = pitch
            return false
        }
        val dev = pitch - base
        // Rest follows slowly, and barely at all mid-nod.
        base += (pitch - base) * (if (abs(dev) < 6f) 0.05f else 0.004f)
        if (sign == 0) {
            if (abs(dev) > THRESHOLD_DEG) {
                sign = if (dev > 0) 1 else -1
                at = now
            }
            return false
        }
        if (now - at > 1100) {
            sign = 0
            return false
        }
        if (abs(dev) < 4f || dev * sign < 0) {
            sign = 0
            if (now - last > 800) {
                last = now
                return true
            }
        }
        return false
    }

    private companion object {
        const val THRESHOLD_DEG = 10f
    }
}

/* ------------------------------ Pixel Hearts ------------------------------ */

// 8-bit hearts on the cheeks, and a stream of them pouring out of an open mouth.
object PixelHearts : Filter("hearts", "Hearts", "💖", Mode.MESH) {
    private val hearts = Particles(600)
    private val colors = intArrayOf(hex("#f23a4d"), hex("#ff5cae"), hex("#3a8dff"), hex("#ffc93c"))
    private val debt = HashMap<Int, Float>()
    // While hearts pour out, a piano walks down the white keys, a note every NOTE_MS, and starts
    // again from the top when it runs out of keyboard or after the mouths have been shut a moment.
    // It plays quietly: a note every 85ms is a lot of notes, and it sits under the room, not over it.
    private const val NOTE_MS = 85L
    private const val NOTE_GAIN = 0.22f
    private var noteAt = 0L
    private var noteKey = 0
    private var pouringAt = -1_000_000L
    private val pix = Paint() // no anti-aliasing: pixel art wants hard edges

    // 7 x 6, '#' body, '+' highlight.
    private val shape = arrayOf(
        ".##.##.",
        "#+#####",
        "#######",
        ".#####.",
        "..###..",
        "...#...",
    )

    override fun update(d: Draw, faces: List<Face>) {
        for (f in faces) {
            val open = f.bs("jawOpen")
            if (open < 0.2f) {
                debt[f.id] = 0f
                continue
            }
            var owed = (debt[f.id] ?: 0f) + d.dt / 1000f * (16f + open * 45f)
            val lip = toPixels(f, f.mouth.x, f["lipBottom"]?.y ?: f.mouth.y)
            val scale = f.eyeDist / 90f
            while (owed >= 1f) {
                owed -= 1f
                hearts.add(Particle(lip.x + rnd(-10f, 10f) * scale, lip.y, rnd(-110f, 110f) * scale, rnd(30f, 170f) * scale, rnd(1.1f, 1.8f), rnd(3.2f, 6.2f) * scale, colors[rng.nextInt(colors.size)]))
            }
            debt[f.id] = owed
        }
        if (debt.size > 6) debt.clear()
        if (faces.any { it.bs("jawOpen") >= 0.2f }) {
            if (d.t - pouringAt > 400) noteKey = 0
            pouringAt = d.t
            if (d.t - noteAt >= NOTE_MS) {
                noteAt = d.t
                Sfx.play("key$noteKey", NOTE_GAIN)
                noteKey = (noteKey + 1) % Sfx.HEART_KEYS.size
            }
        }
        hearts.step(d.dt, gravity = 460f)
    }

    override fun draw(d: Draw, f: Face) {
        val px = f.eyeDist * 0.03f
        for (side in intArrayOf(-1, 1)) {
            val p = toPixels(f, side * 0.66f, 0.42f)
            pixelHeart(d.c, p.x, p.y, px, hex("#ffc93c"), 255)
        }
    }

    override fun overlay(d: Draw, faces: List<Face>) {
        for (p in hearts.list) {
            val alpha = (255 * min(1f, p.fade * 3f)).toInt()
            pixelHeart(d.c, p.x, p.y, p.size, p.color, alpha)
        }
    }

    private fun pixelHeart(c: Canvas, x: Float, y: Float, s: Float, color: Int, alpha: Int) {
        val light = Color.rgb(
            (Color.red(color) + 255) / 2, (Color.green(color) + 255) / 2, (Color.blue(color) + 255) / 2,
        )
        val ox = x - s * 3.5f
        val oy = y - s * 3f
        for (row in shape.indices) {
            val line = shape[row]
            for (col in line.indices) {
                val ch = line[col]
                if (ch == '.') continue
                pix.color = if (ch == '+') light else color
                pix.alpha = alpha
                c.drawRect(ox + col * s, oy + row * s, ox + (col + 1) * s + 0.5f, oy + (row + 1) * s + 0.5f, pix)
            }
        }
    }
}

/* -------------------------------- Hamster -------------------------------- */

// Big shiny eyes (a real magnifying lens on each eye), round fuzzy ears, a glossy pink nose,
// whiskers, and a carrot held up to the mouth in two pink paws. The carrot rides the lower
// lip and wiggles while the mouth works. Open and close to take a bite: three bites and
// it's gone, then a fresh one pops in.
object Hamster : Filter("hamster", "Hamster", "🐹", Mode.MESH, voice = 1.45f) {
    private val NO_MAP = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f)
    // 2.2x at the middle of each eye. At 0.45 (1.8x) it was real but read as "normal eyes"
    // at Portal viewing distance.
    private const val EYE_BULGE = 0.55f

    // A bite is an open then a close. The gap between the two thresholds keeps a jaw hovering
    // near one of them from chattering through a whole carrot.
    private const val BITES = 3
    private const val OPEN_AT = 0.3f
    private const val CLOSED_AT = 0.12f
    private const val GONE_FADE_MS = 400L
    private const val REFILL_MS = 1300L

    private class Carrot {
        var bites = 0
        var open = false
        /** The eaten fraction as drawn, easing toward bites / BITES. */
        var eaten = 0f
        var finishedAt = 0L
        var bornAt = 0L
        var seen = 0L
    }

    // Per person, keyed by track id, so two kids eat at their own pace.
    private val carrots = HashMap<Int, Carrot>()
    private val crumbs = Particles(200)
    private val crumbColors = intArrayOf(hex("#f5872a"), hex("#ffb35c"), hex("#e06a14"))
    private val crumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun update(d: Draw, faces: List<Face>) {
        for (f in faces) {
            val s = carrots.getOrPut(f.id) { Carrot() }
            s.seen = d.t
            val jaw = f.bs("jawOpen")
            if (!s.open && jaw > OPEN_AT) {
                s.open = true
            } else if (s.open && jaw < CLOSED_AT) {
                s.open = false
                if (s.bites < BITES) {
                    s.bites++
                    crumble(f)
                    if (s.bites == BITES) s.finishedAt = d.t
                }
            }
            if (s.bites == BITES && d.t - s.finishedAt > REFILL_MS) {
                s.bites = 0
                s.eaten = 0f
                s.bornAt = d.t
            }
            s.eaten += (s.bites.toFloat() / BITES - s.eaten) * min(1f, d.dt / 70f)
        }
        if (carrots.size > 4) carrots.entries.removeAll { d.t - it.value.seen > 3000 }
        crumbs.step(d.dt, gravity = 900f, drag = 0.5f)
    }

    private fun crumble(f: Face) {
        val lip = toPixels(f, f.mouth.x, (f["lipBottom"]?.y ?: f.mouth.y) + f.headSpan * 0.03f)
        val scale = f.eyeDist / 90f
        for (i in 0 until 12) {
            crumbs.add(Particle(
                lip.x + rnd(-14f, 14f) * scale, lip.y + rnd(-4f, 8f) * scale,
                rnd(-150f, 150f) * scale, rnd(-220f, -40f) * scale,
                rnd(0.55f, 0.95f), rnd(3f, 7f) * scale, crumbColors[rng.nextInt(crumbColors.size)], rnd(-8f, 8f),
            ))
        }
    }

    override fun overlay(d: Draw, faces: List<Face>) {
        val c = d.c
        for (p in crumbs.list) {
            crumbPaint.color = p.color
            crumbPaint.alpha = (255 * min(1f, p.fade * 2.5f)).toInt()
            val save = c.save()
            c.translate(p.x, p.y)
            c.rotate(deg(p.rot))
            c.drawRect(-p.size, -p.size * 0.7f, p.size, p.size * 0.7f, crumbPaint)
            c.restoreToCount(save)
        }
    }

    override val usesUnder = true

    // Blush and whiskers are flat on the face, under the 3D props (Hamster3D.kt).
    override fun under(d: Draw, f: Face) {
        inFaceSpace(d.c, f) {
            cheeks(d.c, d.pen)
            whiskers(d.c, d.pen, f, f.headSpan)
        }
    }

    override fun draw(d: Draw, f: Face) {
        eyes(d, f)
        d.hamsters += props(d, f)
    }

    // Each eye's middle and width from its two corners. The outer corners sit at the face's
    // (±0.5, 0) by construction; the inner ones come from the mesh.
    private fun eyes(d: Draw, f: Face) {
        for ((i, key) in arrayOf("eyeInR", "eyeInL").withIndex()) {
            val inner = f[key]
            val side = if (inner != null) (if (inner.x < 0) -1f else 1f) else (if (i == 0) -1f else 1f)
            val ix = inner?.x ?: (side * 0.18f)
            val iy = inner?.y ?: 0f
            val outer = side * 0.5f
            val centre = toPixels(f, (outer + ix) / 2, iy / 2)
            val rx = abs(outer - ix) * f.eyeDist * 1.15f
            d.patches += Patch(centre.x, centre.y, rx, rx * 0.8f, f.angle, 1f, NO_MAP, bulge = EYE_BULGE)
        }
    }

    // Soft pink ovals across the cheeks, low enough to stay clear of the eye lenses.
    private fun cheeks(c: Canvas, p: Pen) {
        for (side in intArrayOf(-1, 1)) {
            val save = c.save()
            c.translate(side * 0.6f, 0.56f)
            c.scale(1.5f, 1f)
            c.drawCircle(0f, 0f, 0.22f, p.fill(RadialGradient(
                0f, 0f, 0.22f, intArrayOf(rgba(255, 96, 120, 0.62f), rgba(255, 96, 120, 0.3f), rgba(255, 96, 120, 0f)),
                floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP,
            )))
            c.restoreToCount(save)
        }
    }

    // Two short white whiskers a side, starting just past the blush and fanning out.
    private fun whiskers(c: Canvas, p: Pen, f: Face, S: Float) {
        val whisker = p.stroke(rgba(255, 255, 255, 0.95f), 0.018f)
        whisker.strokeCap = Paint.Cap.ROUND
        p.lift(0.02f)
        for (side in intArrayOf(-1, 1)) {
            for (i in 0 until 2) {
                val x0 = side * S * 0.42f
                val y0 = f.nose.y + S * (0.03f + i * 0.05f)
                val a = -0.12f + i * 0.28f
                c.drawLine(x0, y0, x0 + side * cos(a) * S * 0.17f, y0 + sin(a) * S * 0.17f, whisker)
            }
        }
        p.unlift()
    }

    // The 3D props for one face: ears, nose, and the carrot in its paws.
    private fun props(d: Draw, f: Face): Hamster3D {
        val h = Hamster3D()
        val S = f.headSpan
        val sPx = S * f.eyeDist
        for (side in intArrayOf(-1, 1)) {
            val m = FloatArray(16)
            // Half moons: tall narrow cups cut flat at the base, openings turned in toward the face.
            onFace(f, side * S * 0.42f, f.headTopY - S * 0.08f, 0f, m)
            Matrix.rotateM(m, 0, side * 16f, 0f, 0f, 1f)
            Matrix.rotateM(m, 0, side * 24f, 0f, 1f, 0f)
            Matrix.rotateM(m, 0, 72f, 1f, 0f, 0f)
            val r = sPx * 0.12f
            Matrix.scaleM(m, 0, r * 0.72f, r, r * 1.35f)
            h.ears += m
        }
        onFace(f, f.nose.x, f.nose.y + S * 0.005f, sPx * 0.06f, h.nose)
        val nr = sPx * 0.075f
        Matrix.scaleM(h.nose, 0, nr, nr, nr)
        carrot(d, f, h)
        return h
    }

    // Tip up at the lower lip, wide end down over the chin, a clover of leaves below, two paws
    // holding it. Bites come off the tip, and what's left slides up so the bitten edge stays at the
    // lips; once it's gone the paws are left holding the leaves until they fade.
    private fun carrot(d: Draw, f: Face, h: Hamster3D) {
        val S = f.headSpan
        val u = f.eyeDist
        val st = carrots[f.id]
        val eaten = st?.eaten ?: 0f
        h.leafAlpha = if (st != null && st.bites == BITES) 1f - (d.t - st.finishedAt).toFloat() / GONE_FADE_MS else 1f

        val open = f.bs("jawOpen")
        val lip = f["lipBottom"]?.y ?: f.mouth.y
        val chin = f["chin"]?.y ?: (f.mouth.y + 0.5f)
        val chewing = ((open - 0.08f) * 4f).coerceIn(0f, 1f)
        val wiggle = sin(d.t / 55f) * 0.08f * chewing
        val lenPx = max(S * 0.3f, (chin - lip) * 0.75f + S * 0.1f) * u
        // A fresh carrot pops in from the lips: overshoot, then settle.
        val k = if (st == null || st.bornAt == 0L) 1f else ((d.t - st.bornAt) / 280f).coerceIn(0f, 1f)
        val grow = max(0.01f, 1f + 2.70158f * (k - 1) * (k - 1) * (k - 1) + 1.70158f * (k - 1) * (k - 1))

        val base = FloatArray(16)
        onFace(f, f.mouth.x, lip - S * 0.02f, S * u * 0.14f, base)
        Matrix.rotateM(base, 0, deg(-0.16f + wiggle), 0f, 0f, 1f)
        Matrix.scaleM(base, 0, grow, grow, grow)

        val left = 1f - eaten
        val whole = eaten < 0.98f
        if (whole) {
            val m = base.copyOf()
            Matrix.translateM(m, 0, 0f, -eaten * lenPx, 0f)
            Matrix.scaleM(m, 0, lenPx, lenPx, lenPx)
            h.carrot = m
            h.carrotCut = eaten
        }
        if (h.leafAlpha > 0f) {
            val ls = S * u * 0.085f
            for (i in -1..1) {
                val m = base.copyOf()
                Matrix.translateM(m, 0, 0f, left * lenPx * 0.97f, -ls * 0.2f)
                Matrix.rotateM(m, 0, i * 50f + deg(wiggle) * 0.5f, 0f, 0f, 1f)
                Matrix.scaleM(m, 0, ls, ls, ls)
                h.leaves += m
            }
        }
        val pawH = S * u * 0.13f
        val py = if (whole) 0.55f * left * lenPx else lenPx * 0.2f
        val half = if (whole) HamsterMeshes.carrotRadius(eaten + 0.55f * left) * lenPx else pawH * 0.35f
        for (side in intArrayOf(-1, 1)) {
            val m = base.copyOf()
            Matrix.translateM(m, 0, side * (half + pawH * 0.15f), py, -pawH * 0.6f)
            Matrix.rotateM(m, 0, -side * 12f, 0f, 0f, 1f)
            // Mirrored so each paw's fingers reach in toward the carrot.
            Matrix.scaleM(m, 0, -side * pawH, pawH, pawH)
            h.paws += m
        }
    }
}
