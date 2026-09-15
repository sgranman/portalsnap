package net.sgran.portalsnap

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
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

/* ---------------------------- Monster / Cutie ---------------------------- */

// Hand-drawn face paint in two moods. A nod poofs between them (tap works too), and the
// voice follows: deep for the monster, squeaky for the cutie.
object MonsterCutie : Filter("monster", "Monster", "👹", Mode.MESH) {
    private class State {
        var monster = false
        var changedAt = 0L
        val nod = Nod()
        var seen = 0L
    }

    private val states = HashMap<Int, State>()
    private val poofs = Particles()
    @Volatile private var pokes = 0
    private var seenPokes = 0
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun poke() {
        pokes++
    }

    override fun voiceFrom(face: Face) = if (states[face.id]?.monster == true) 0.72f else 1.6f

    override fun update(d: Draw, faces: List<Face>) {
        val poked = pokes != seenPokes
        seenPokes = pokes
        for (f in faces) {
            val s = states.getOrPut(f.id) { State() }
            s.seen = d.t
            val nodded = f.pitch?.let { s.nod.update(it, d.t) } ?: false
            if (nodded || poked) {
                s.monster = !s.monster
                s.changedAt = d.t
                poof(f)
            }
        }
        if (states.size > 4) states.entries.removeAll { d.t - it.value.seen > 3000 }
        poofs.step(d.dt, gravity = -40f, drag = 3f)
    }

    private fun poof(f: Face) {
        val scale = f.eyeDist / 90f
        for (i in 0 until 18) {
            val a = rnd(0f, TAU)
            val speed = rnd(120f, 320f) * scale
            val shade = if (rng.nextBoolean()) Color.WHITE else hex("#d9d4e6")
            poofs.add(Particle(f.cx + cos(a) * f.eyeDist * 0.4f, f.cy + sin(a) * f.eyeDist * 0.5f, cos(a) * speed, sin(a) * speed, rnd(0.35f, 0.6f), rnd(26f, 46f) * scale, shade))
        }
    }

    override fun draw(d: Draw, f: Face) {
        val s = states[f.id] ?: return
        // The new look pops in: overshoot, then settle.
        val k = ((d.t - s.changedAt) / 260f).coerceIn(0f, 1f)
        val pop = if (s.changedAt == 0L) 1f else 0.75f + 0.25f * (1 + 2.2f * (k - 1) * (k - 1) * (k - 1) + 1.2f * (k - 1) * (k - 1))
        inFaceSpace(d.c, f) {
            d.c.scale(pop, pop, 0f, 0.3f)
            if (s.monster) monster(d.c, f) else cutie(d.c, f)
        }
    }

    override fun overlay(d: Draw, faces: List<Face>) {
        for (p in poofs.list) {
            val r = p.size * (0.7f + 0.8f * (p.age / p.life))
            fill.color = p.color
            fill.alpha = (235 * p.fade).toInt()
            d.c.drawCircle(p.x, p.y, r, fill)
        }
    }

    private fun cutie(c: Canvas, f: Face) {
        val S = f.headSpan
        // Bear ears up on the crown, either side of the parting — on the forehead they read
        // as a second pair of eyes.
        for (side in intArrayOf(-1, 1)) {
            val r = S * 0.15f
            val x = side * S * 0.36f
            val y = f.headTopY + S * 0.06f
            fill.color = Color.WHITE
            c.drawCircle(x, y, r, fill)
            fill.color = hex("#f7a1b0")
            c.drawCircle(x - side * r * 0.12f, y + r * 0.08f, r * 0.48f, fill)
        }
        ink.strokeWidth = 0.035f
        for (side in intArrayOf(-1, 1)) {
            val x = side * 0.5f
            c.drawLine(x + side * 0.22f, -0.02f, x + side * 0.34f, -0.1f, ink)
            c.drawLine(x + side * 0.24f, 0.05f, x + side * 0.38f, 0.02f, ink)
            fill.color = hex("#f7768a")
            fill.alpha = 220
            heart(c, side * 0.62f, 0.62f, 0.25f)
        }
    }

    private fun monster(c: Canvas, f: Face) {
        val S = f.headSpan
        // Horns on the crown, leaning outward.
        for (side in intArrayOf(-1, 1)) {
            val w = S * 0.085f
            val h = S * 0.3f
            val save = c.save()
            c.translate(side * S * 0.27f, f.headTopY + S * 0.12f)
            c.scale(side.toFloat(), 1f)
            path.reset()
            path.moveTo(-w, 0f)
            path.quadTo(-w * 0.6f, -h * 0.6f, w * 0.35f, -h)
            path.quadTo(w * 0.7f, -h * 0.45f, w, 0f)
            path.close()
            fill.color = hex("#f4ecd6")
            c.drawPath(path, fill)
            ink.strokeWidth = 0.04f
            c.drawPath(path, ink)
            c.restoreToCount(save)
        }
        for (side in intArrayOf(-1, 1)) {
            // Angry brows: thick, slanting down toward the nose.
            ink.strokeWidth = 0.12f
            c.drawLine(side * 0.2f, -0.13f, side * 0.82f, -0.4f, ink)
            // Glaring cartoon eyes.
            fill.color = Color.WHITE
            c.drawOval(side * 0.5f - 0.17f, -0.13f, side * 0.5f + 0.17f, 0.13f, fill)
            ink.strokeWidth = 0.045f
            c.drawOval(side * 0.5f - 0.17f, -0.13f, side * 0.5f + 0.17f, 0.13f, ink)
            fill.color = Color.BLACK
            c.drawCircle(side * 0.44f, 0.02f, 0.075f, fill)
            fill.color = Color.WHITE
            c.drawCircle(side * 0.42f, -0.01f, 0.022f, fill)
            // X marks and scribbled blush.
            val bx = side * 0.8f
            ink.strokeWidth = 0.04f
            c.drawLine(bx - 0.07f, 0.45f, bx + 0.07f, 0.59f, ink)
            c.drawLine(bx - 0.07f, 0.59f, bx + 0.07f, 0.45f, ink)
            val scribble = Paint(ink).apply { color = hex("#f7768a"); strokeWidth = 0.045f }
            path.reset()
            val x0 = side * 0.42f
            path.moveTo(x0, 0.52f)
            for (i in 1..6) path.lineTo(x0 + side * i * 0.055f, if (i % 2 == 1) 0.62f else 0.5f)
            c.drawPath(path, scribble)
        }
        // A huge fanged mouth that opens with the real one.
        val open = f.bs("jawOpen")
        val mouthR = f["mouthR"]
        val mouthL = f["mouthL"]
        val w = max(0.55f, if (mouthR != null && mouthL != null) abs(mouthL.x - mouthR.x) * 0.95f else 0.6f)
        val h = 0.28f + open * 0.9f
        val top = f.mouth.y - h * 0.25f
        val bottom = f.mouth.y + h * 0.75f
        fill.color = Color.BLACK
        c.drawRoundRect(f.mouth.x - w, top, f.mouth.x + w, bottom, w * 0.9f, h * 0.6f, fill)
        fill.color = hex("#ef6f8e")
        c.drawOval(f.mouth.x - w * 0.45f, bottom - h * 0.42f, f.mouth.x + w * 0.45f, bottom - h * 0.02f, fill)
        fill.color = hex("#fbf7ec")
        val fangs = 4
        for (i in 0 until fangs) {
            val fx = f.mouth.x - w * 0.7f + i * (w * 1.4f / (fangs - 1))
            path.reset()
            path.moveTo(fx - w * 0.13f, top + 0.02f)
            path.lineTo(fx + w * 0.13f, top + 0.02f)
            path.lineTo(fx, top + 0.02f + h * 0.28f)
            path.close()
            c.drawPath(path, fill)
        }
        for (sx in floatArrayOf(-0.45f, 0.45f)) {
            val fx = f.mouth.x + sx * w
            path.reset()
            path.moveTo(fx - w * 0.11f, bottom - 0.03f)
            path.lineTo(fx + w * 0.11f, bottom - 0.03f)
            path.lineTo(fx, bottom - 0.03f - h * 0.22f)
            path.close()
            c.drawPath(path, fill)
        }
    }

    private fun heart(c: Canvas, x: Float, y: Float, size: Float) {
        val s = size / 2
        path.reset()
        path.moveTo(x, y + s * 0.9f)
        path.cubicTo(x - s * 1.6f, y - s * 0.1f, x - s * 0.7f, y - s * 1.2f, x, y - s * 0.35f)
        path.cubicTo(x + s * 0.7f, y - s * 1.2f, x + s * 1.6f, y - s * 0.1f, x, y + s * 0.9f)
        path.close()
        c.drawPath(path, fill)
    }
}

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
    private val biteEdge = Path()

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

    override fun draw(d: Draw, f: Face) {
        eyes(d, f)
        inFaceSpace(d.c, f) {
            val S = f.headSpan
            ears(d.c, d.pen, f, S)
            cheeks(d.c, d.pen)
            whiskers(d.c, d.pen, f, S)
            carrot(d, f, S)
            nose(d.c, d.pen, f, S)
        }
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

    private fun ears(c: Canvas, p: Pen, f: Face, S: Float) {
        for (side in intArrayOf(-1, 1)) {
            val r = S * 0.16f
            val x = side * S * 0.42f
            val y = f.headTopY + S * 0.04f
            // Fuzz: one path of uneven tufts round the rim, shadowed, under a smooth ear.
            p.newPath().apply {
                for (k in 0 until 22) {
                    val a = k * TAU / 22
                    val tuft = r * (if (k % 3 == 0) 0.13f else 0.09f)
                    addCircle(x + cos(a) * r * 0.97f, y + sin(a) * r * 0.97f, tuft, Path.Direction.CW)
                }
            }
            p.lift(0.04f)
            c.drawPath(p.path, p.fill(hex("#8f5b2e")))
            p.unlift()
            c.drawCircle(x, y, r, p.fill(RadialGradient(
                x - r * 0.3f, y - r * 0.35f, r * 1.35f,
                intArrayOf(hex("#dba36b"), hex("#a8703d"), hex("#7a4b24")), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
            )))
            // The inner ear is a cup: lit low, shadowed toward the top.
            val iw = r * 0.55f
            val ih = r * 0.62f
            val iy = y + r * 0.12f
            c.drawOval(p.rect(x, iy, iw, ih), p.fill(RadialGradient(
                x, iy + ih * 0.35f, ih * 1.3f,
                intArrayOf(hex("#ffc9d5"), hex("#f29bb2"), hex("#c9667f")), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP,
            )))
        }
    }

    private fun cheeks(c: Canvas, p: Pen) {
        for (side in intArrayOf(-1, 1)) {
            val x = side * 0.64f
            val y = 0.55f
            val r = 0.24f
            c.drawCircle(x, y, r, p.fill(RadialGradient(
                x, y, r, intArrayOf(rgba(255, 110, 140, 0.6f), rgba(255, 110, 140, 0f)), null, Shader.TileMode.CLAMP,
            )))
        }
    }

    private fun whiskers(c: Canvas, p: Pen, f: Face, S: Float) {
        val nx = f.nose.x
        val ny = f.nose.y
        val reach = abs(f.earL.x - f.earR.x) * 0.5f
        val whisker = p.stroke(rgba(255, 255, 255, 0.95f), 0.02f)
        whisker.strokeCap = Paint.Cap.ROUND
        p.lift(0.03f)
        for (side in intArrayOf(-1, 1)) {
            for (i in 0 until 3) {
                val y = ny + S * (0.05f + i * 0.06f)
                p.newPath().apply {
                    moveTo(nx + side * S * 0.13f, y)
                    quadTo(
                        nx + side * reach * 0.5f, y - S * (0.03f - i * 0.03f),
                        nx + side * reach * 0.8f, y - S * (0.07f - i * 0.08f),
                    )
                }
                c.drawPath(p.path, whisker)
            }
        }
        p.unlift()
    }

    private fun nose(c: Canvas, p: Pen, f: Face, S: Float) {
        val nx = f.nose.x
        val ny = f.nose.y + S * 0.01f
        val rw = S * 0.08f
        val rh = S * 0.058f
        p.lift(0.04f)
        c.drawOval(p.rect(nx, ny, rw, rh), p.fill(RadialGradient(
            nx - rw * 0.3f, ny - rh * 0.5f, rw * 1.5f,
            intArrayOf(hex("#ffd6e0"), hex("#f27b9b"), hex("#b9435f")), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )))
        p.unlift()
        c.drawOval(p.rect(nx - rw * 0.32f, ny - rh * 0.42f, rw * 0.3f, rh * 0.2f), p.fill(rgba(255, 255, 255, 0.9f)))
    }

    // Tip up at the lower lip, wide end down over the chin, leaves hanging below. Bites come off
    // the tip end, and what's left slides up so the bitten edge stays at the lips.
    private fun carrot(d: Draw, f: Face, S: Float) {
        val c = d.c
        val p = d.pen
        val st = carrots[f.id]
        val eaten = st?.eaten ?: 0f
        val leafAlpha = if (st != null && st.bites == BITES) 1f - (d.t - st.finishedAt).toFloat() / GONE_FADE_MS else 1f
        if (leafAlpha <= 0f) return

        val open = f.bs("jawOpen")
        val lip = f["lipBottom"]?.y ?: f.mouth.y
        val chin = f["chin"]?.y ?: (f.mouth.y + 0.5f)
        val chewing = ((open - 0.08f) * 4f).coerceIn(0f, 1f)
        val wiggle = sin(d.t / 55f) * 0.08f * chewing
        val len = max(S * 0.3f, (chin - lip) * 0.75f + S * 0.1f)
        val tipW = S * 0.04f
        val endW = S * 0.13f
        val cut = len * eaten
        // A fresh carrot pops in from the lips: overshoot, then settle.
        val k = if (st == null || st.bornAt == 0L) 1f else ((d.t - st.bornAt) / 280f).coerceIn(0f, 1f)
        val grow = max(0.01f, 1f + 2.70158f * (k - 1) * (k - 1) * (k - 1) + 1.70158f * (k - 1) * (k - 1))

        val save = c.save()
        c.translate(f.mouth.x, lip - S * 0.02f)
        c.rotate(deg(-0.16f + wiggle))
        c.scale(grow, grow)
        c.translate(0f, -cut)

        p.lift(0.04f)
        val leafLen = S * 0.2f
        val leaf = p.fill(LinearGradient(0f, 0f, 0f, leafLen, hex("#72d65c"), hex("#2e923c"), Shader.TileMode.CLAMP))
        leaf.alpha = (255 * leafAlpha.coerceIn(0f, 1f)).toInt()
        for (i in -1..1) {
            val s = c.save()
            c.translate(0f, len - endW * 0.1f)
            c.rotate(deg(i * 0.5f + wiggle * 0.5f))
            p.newPath().apply {
                moveTo(-S * 0.025f, 0f)
                quadTo(-S * 0.055f, leafLen * 0.6f, 0f, leafLen)
                quadTo(S * 0.055f, leafLen * 0.6f, S * 0.025f, 0f)
                close()
            }
            c.drawPath(p.path, leaf)
            c.restoreToCount(s)
        }
        p.unlift()

        // Only the leaves are left once the last bite has gone down.
        if (eaten >= 0.98f) {
            c.restoreToCount(save)
            return
        }

        val clipped = c.save()
        if (cut > 0f) {
            // What a bite leaves: scallops dipping into the carrot.
            val w = endW * 1.3f
            biteEdge.reset()
            biteEdge.moveTo(-w, len * 2)
            biteEdge.lineTo(-w, cut)
            for (i in 0 until 3) {
                val x0 = -w + i * (2 * w / 3)
                biteEdge.quadTo(x0 + w / 3, cut + S * 0.09f, x0 + 2 * w / 3, cut)
            }
            biteEdge.lineTo(w, len * 2)
            biteEdge.close()
            c.clipPath(biteEdge)
        }

        p.newPath().apply {
            moveTo(-tipW, 0f)
            quadTo(0f, -tipW * 1.8f, tipW, 0f)
            quadTo(endW * 0.9f, len * 0.55f, endW, len)
            quadTo(0f, len + endW * 0.45f, -endW, len)
            quadTo(-endW * 0.9f, len * 0.55f, -tipW, 0f)
            close()
        }
        p.lift(0.04f)
        c.drawPath(p.path, p.fill(LinearGradient(
            -endW, 0f, endW, 0f, intArrayOf(hex("#ffc47e"), hex("#f5872a"), hex("#c8520d")), floatArrayOf(0.1f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )))
        p.unlift()
        if (cut > 0f) {
            // The paler inside shows along the bite.
            c.clipPath(p.path)
            c.drawPath(biteEdge, p.stroke(hex("#ffd8a6"), S * 0.035f))
        }

        val ridge = p.stroke(rgba(160, 64, 8, 0.5f), 0.016f)
        ridge.strokeCap = Paint.Cap.ROUND
        for (i in 0 until 4) {
            val k = 0.22f + i * 0.2f
            val y = len * k
            val half = tipW + (endW - tipW) * k
            val side = if (i % 2 == 0) -1f else 1f
            c.drawLine(side * half * 0.95f, y, side * half * 0.3f, y + S * 0.012f, ridge)
        }
        c.restoreToCount(clipped)

        // Paws on either side of what's left, toes over the carrot's edge.
        val py = cut + (len - cut) * 0.42f
        val half = tipW + (endW - tipW) * (py / len)
        for (side in intArrayOf(-1, 1)) {
            val pw = S * 0.065f
            val ph = S * 0.08f
            val px = side * (half + pw * 0.5f)
            val s = c.save()
            c.translate(px, py)
            c.rotate(deg(-side * 0.45f)) // tops leaning in, as if gripping from below
            p.lift(0.04f)
            c.drawOval(p.rect(0f, 0f, pw, ph), p.fill(RadialGradient(
                -pw * 0.3f, -ph * 0.4f, ph * 1.4f,
                intArrayOf(hex("#ffd9e2"), hex("#f7a3b8"), hex("#d9708c")), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
            )))
            p.unlift()
            val toeLine = p.stroke(hex("#d27891"), 0.01f)
            for (j in -1..1) {
                val tx = -side * pw * 0.78f
                val ty = j * ph * 0.42f - ph * 0.15f
                c.drawCircle(tx, ty, pw * 0.24f, p.fill(hex("#ffd0dc")))
                c.drawCircle(tx, ty, pw * 0.24f, toeLine)
            }
            c.restoreToCount(s)
        }
        c.restoreToCount(save)
    }
}
