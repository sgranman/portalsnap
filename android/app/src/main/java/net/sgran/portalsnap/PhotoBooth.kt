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

private val rng = Random()

private fun rnd(a: Float, b: Float) = a + rng.nextFloat() * (b - a)

private fun rgb(hex: String): FloatArray {
    val c = Color.parseColor(hex)
    return floatArrayOf(Color.red(c) / 255f, Color.green(c) / 255f, Color.blue(c) / 255f)
}

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

// The person as a flat gradient silhouette on a flat colour, stepping through looks on the
// beat — or on a slow clock when nothing is playing.
object PopSilhouette : Filter("pop", "Pop Art", "🎨", Mode.SEGMENT) {
    override val usesOver = false
    override val usesFx = true
    override val wantsMic = true

    // background, person top, person bottom
    private val looks = arrayOf(
        arrayOf(rgb("#b3263a"), rgb("#d9602e"), rgb("#f2b632")),
        arrayOf(rgb("#16807f"), rgb("#ff5c9a"), rgb("#8e4cf0")),
        arrayOf(rgb("#f5c518"), rgb("#2f5bea"), rgb("#33d1ff")),
        arrayOf(rgb("#3b1e6e"), rgb("#9be34a"), rgb("#22b573")),
    )
    private var look = 0
    private var seenBeats = -1
    private var lastSwitch = 0L

    override fun fx(d: Draw, faces: List<Face>, fx: FrameFx) {
        if (d.beats != seenBeats) {
            if (seenBeats >= 0 && d.t - lastSwitch > 350) next(d.t)
            seenBeats = d.beats
        } else if (d.sinceBeatMs > 2500 && d.t - lastSwitch > 2000) {
            next(d.t)
        }
        val l = looks[look]
        fx.kind = FrameFx.POP
        l[0].copyInto(fx.a)
        l[1].copyInto(fx.b)
        l[2].copyInto(fx.c)
        fx.p0 = 0.18f // ghost of the room in the background
        fx.p1 = d.beat * 0.35f // flash on the beat
    }

    private fun next(now: Long) {
        look = (look + 1) % looks.size
        lastSwitch = now
    }
}

/* ------------------------------ Disco Dots ------------------------------ */

// A purple wash and a twinkling LED dot grid that ripples out from the head on the beat,
// with star bursts thrown from the head. Quiet room: a gentler burst on a slow clock.
object DiscoDots : Filter("disco", "Disco", "🪩", Mode.FAST) {
    override val usesFx = true
    override val wantsMic = true

    private val stars = Particles()
    private val starColors = intArrayOf(Color.WHITE, hex("#ff9bf0"), hex("#ffe27a"), hex("#9ff3ff"))
    private val tint = rgb("#b23cff")
    private var seenBeats = -1
    private var lastBurst = 0L
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun update(d: Draw, faces: List<Face>) {
        val head = faces.maxByOrNull { it.eyeDist }
        val beat = d.beats != seenBeats && seenBeats >= 0
        seenBeats = d.beats
        val idle = d.sinceBeatMs > 2500 && d.t - lastBurst > 1100
        if (head != null && (beat || idle)) {
            burst(head, if (beat) 1f else 0.55f)
            lastBurst = d.t
        }
        stars.step(d.dt, gravity = 60f, drag = 1.2f)
    }

    override fun fx(d: Draw, faces: List<Face>, fx: FrameFx) {
        val head = faces.maxByOrNull { it.eyeDist }
        fx.kind = FrameFx.DISCO
        fx.x = head?.cx ?: (d.w / 2)
        fx.y = head?.let { it.cy - it.eyeDist * 0.3f } ?: (d.h / 2)
        fx.p0 = (d.t % 1_000_000L) / 1000f
        fx.p1 = d.beat
        fx.p2 = d.level
        tint.copyInto(fx.a)
        fx.b[0] = min(d.sinceBeatMs, 4000f) / 1000f * 1.1f // ripple radius, in frame heights
    }

    private fun burst(f: Face, strength: Float) {
        val scale = f.eyeDist / 90f
        val count = (16 * strength).toInt()
        val cx = f.cx
        val cy = f.cy - f.eyeDist * 0.6f
        for (i in 0 until count) {
            val a = i * TAU / count + rnd(-0.2f, 0.2f)
            val speed = rnd(260f, 520f) * scale * strength
            stars.add(Particle(cx, cy, cos(a) * speed, sin(a) * speed, rnd(0.7f, 1.1f), rnd(9f, 20f) * scale, starColors[rng.nextInt(starColors.size)], rnd(-3f, 3f)))
        }
    }

    override fun overlay(d: Draw, faces: List<Face>) {
        val c = d.c
        for (p in stars.list) {
            val grow = 0.6f + 0.6f * (p.age / p.life)
            val r = p.size * grow
            paint.shader = null
            paint.color = p.color
            paint.alpha = (255 * p.fade).toInt()
            sparkle(c, p.x, p.y, r, p.rot)
        }
    }

    // A four-point sparkle, the shape that reads as "star" at a glance.
    private fun sparkle(c: Canvas, x: Float, y: Float, r: Float, rot: Float) {
        path.reset()
        for (i in 0 until 8) {
            val a = rot + i * TAU / 8
            val rr = if (i % 2 == 0) r else r * 0.28f
            val px = x + cos(a) * rr
            val py = y + sin(a) * rr
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        c.drawPath(path, paint)
    }
}

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

/* ------------------------------ Peas in a Pod ------------------------------ */

// Three peas in an open pod on a starry lime background, each pea wearing your face. Two
// people take turns down the pod; three get one pea each. The pod rocks like a cradle and
// creaks at each end of the swing; the peas bob out of step and wave bendy arms, and the top
// one has sprouts. The fast tier is enough: only the head box is needed, and it keeps the
// faces at the camera's 30fps.
object PeasInAPod : Filter("peas", "Peas", "🌱", Mode.FAST, voice = 1.25f) {
    override val usesUnder = true
    override val coversCamera = true

    private const val PEAS = 3
    private val outer = Path()
    private val cavity = Path()
    private val rim = Path()
    private val star = Path()
    private val pt = FloatArray(2)
    private var builtFor = 0f

    private fun radius(d: Draw) = d.h * 0.11f

    private fun top(d: Draw) = d.h * 0.04f

    private fun bottom(d: Draw) = d.h * 0.96f

    // Into pt: pea i's centre this frame.
    private fun peaCentre(d: Draw, i: Int) {
        pt[0] = d.w / 2 + sin(d.t / 520f + i * 1.3f) * d.h * 0.006f
        pt[1] = d.h * (0.28f + i * 0.22f) + sin(d.t / 380f + i * 2.1f) * d.h * 0.012f
    }

    private fun owner(i: Int, count: Int) = if (count <= 1) 0 else i % count

    private const val ROCK_DEG = 6f
    private const val ROCK_MS = 1800L // there and back
    private var swingSign = 0

    // The phase from t modulo the period: uptime in a Float is only good to ~8ms.
    private fun rockPhase(d: Draw) = (d.t % ROCK_MS).toFloat() / ROCK_MS * TAU

    /** The pod's tilt this frame in degrees, clockwise positive like Canvas.rotate. */
    private fun rock(d: Draw) = sin(rockPhase(d)) * ROCK_DEG

    // It rocks about a point below the frame, like a cradle on runners, so the base sways too.
    private fun pivotY(d: Draw) = d.h * 1.1f

    // A creak at each end of the swing, as the pod turns back. Only with someone in view, so
    // an empty room doesn't creak forever.
    override fun update(d: Draw, faces: List<Face>) {
        if (faces.isEmpty()) {
            swingSign = 0
            return
        }
        val sign = if (cos(rockPhase(d)) >= 0f) 1 else -1
        if (swingSign != 0 && sign != swingSign) {
            Sfx.play(if (sign > 0) "creak1" else "creak2", 0.7f, 0.92f + rng.nextFloat() * 0.16f)
        }
        swingSign = sign
    }

    // The pod never moves, so its paths are built once. The rim is the pod minus its cavity,
    // drawn over the peas so they sit inside.
    private fun buildPod(d: Draw) {
        if (builtFor == d.h) return
        builtFor = d.h
        val x = d.w / 2
        val t = top(d)
        val b = bottom(d)
        val span = b - t
        val bulge = radius(d) * 1.9f
        fun lens(path: Path, cx: Float, top: Float, bottom: Float) {
            path.reset()
            path.moveTo(x, top)
            path.cubicTo(x + cx, top + span * 0.1f, x + cx, bottom - span * 0.1f, x, bottom)
            path.cubicTo(x - cx, bottom - span * 0.1f, x - cx, top + span * 0.1f, x, top)
            path.close()
        }
        lens(outer, bulge, t, b)
        lens(cavity, bulge * 0.8f, t + span * 0.03f, b - span * 0.03f)
        rim.reset()
        rim.op(outer, cavity, Path.Op.DIFFERENCE)
    }

    override fun scene(d: Draw, faces: List<Face>) {
        buildPod(d)
        val c = d.c
        val p = d.pen
        val w = d.w
        val h = d.h
        val x = w / 2
        val R = radius(d)
        c.drawRect(0f, 0f, w, h, p.fill(RadialGradient(x, h * 0.45f, w * 0.6f, hex("#cdf25e"), hex("#86cc2e"), Shader.TileMode.CLAMP)))
        stars(d)

        // The shadow stays on the ground and slides under the rocking base.
        val b = bottom(d)
        val tilt = rock(d)
        val baseX = x - (b - pivotY(d)) * sin(Math.toRadians(tilt.toDouble()).toFloat())
        val s = c.save()
        c.scale(1f, 0.22f, baseX, b)
        c.drawCircle(baseX, b, R * 2.2f, p.fill(RadialGradient(baseX, b, R * 2.2f, rgba(30, 70, 10, 0.45f), rgba(30, 70, 10, 0f), Shader.TileMode.CLAMP)))
        c.restoreToCount(s)

        val pod = c.save()
        c.rotate(tilt, x, pivotY(d))
        c.drawPath(outer, p.fill(LinearGradient(
            x - R * 1.5f, 0f, x + R * 1.5f, 0f, intArrayOf(hex("#3f9a2c"), hex("#79c94c"), hex("#2f7d22")), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP,
        )))
        c.drawPath(cavity, p.fill(LinearGradient(
            x - R, 0f, x + R, 0f, intArrayOf(hex("#2a6e1d"), hex("#3f8f2b"), hex("#245f18")), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
        )))
        // Pea bodies under the faces, so each patch's soft edge fades into pea green.
        for (i in 0 until PEAS) {
            peaCentre(d, i)
            c.drawCircle(pt[0], pt[1], R, p.fill(hex("#8fd65a")))
        }
        c.restoreToCount(pod)
    }

    private fun stars(d: Draw) {
        for (i in 0 until 26) {
            val x = ((i * 7919 + 131) % 1000) / 1000f * d.w
            val y = ((i * 4583 + 377) % 1000) / 1000f * d.h
            if (abs(x - d.w / 2) < d.h * 0.26f) continue // keep the pod clear
            val tw = 0.5f + 0.5f * sin(d.t / 420f + i * 1.7f)
            val r = d.h * (0.012f + (i % 4) * 0.005f) * (0.6f + 0.4f * tw)
            star.reset()
            for (k in 0 until 8) {
                val a = k * TAU / 8
                val rr = if (k % 2 == 0) r else r * 0.3f
                if (k == 0) star.moveTo(x + cos(a) * rr, y + sin(a) * rr) else star.lineTo(x + cos(a) * rr, y + sin(a) * rr)
            }
            star.close()
            d.c.drawPath(star, d.pen.fill(rgba(255, 255, 255, 0.35f + 0.65f * tw)))
        }
    }

    // The head box out of the camera, upright, with a little room round it. Centred a touch
    // above the box's middle: at the box centre the eyes crowded the top of the pea.
    override fun draw(d: Draw, f: Face) {
        val R = radius(d)
        val hb = headBox(f)
        val sc = hb.halfH * 1.15f / R
        val sy = hb.y - hb.halfH * 0.12f
        // Patches live in frame space, so rock each pea's centre about the pivot, and map
        // through the inverse turn so the face tilts with its pea.
        val th = Math.toRadians(rock(d).toDouble()).toFloat()
        val cs = cos(th)
        val sn = sin(th)
        val pvx = d.w / 2
        val pvy = pivotY(d)
        for (i in 0 until PEAS) {
            if (owner(i, f.count) != f.rank) continue
            peaCentre(d, i)
            val px = pt[0]
            val py = pt[1]
            val wx = pvx + (px - pvx) * cs - (py - pvy) * sn
            val wy = pvy + (px - pvx) * sn + (py - pvy) * cs
            d.patches += Patch(
                wx, wy, R * 0.97f, R * 0.97f, th, 0.86f,
                floatArrayOf(
                    sc * cs, sc * sn, hb.x + sc * (pvx - cs * pvx - sn * pvy - px),
                    -sc * sn, sc * cs, sy + sc * (pvy + sn * pvx - cs * pvy - py),
                ),
            )
        }
    }

    override fun overlay(d: Draw, faces: List<Face>) {
        if (faces.isEmpty()) return
        val c = d.c
        val p = d.pen
        val R = radius(d)
        val x0 = d.w / 2
        val tilt = rock(d)
        val pod = c.save()
        c.rotate(tilt, x0, pivotY(d))
        for (i in 0 until PEAS) {
            peaCentre(d, i)
            val x = pt[0]
            val y = pt[1]
            // Green toward the rim, so a face reads as a pea rather than a photo in a circle.
            c.drawCircle(x, y, R, p.fill(RadialGradient(
                x, y, R, intArrayOf(rgba(143, 214, 90, 0f), rgba(120, 200, 70, 0.55f), hex("#5fae3a")), floatArrayOf(0.62f, 0.88f, 1f), Shader.TileMode.CLAMP,
            )))
            for (side in intArrayOf(-1, 1)) {
                val bx = x + side * R * 0.46f
                val by = y + R * 0.28f
                val br = R * 0.2f
                c.drawCircle(bx, by, br, p.fill(RadialGradient(bx, by, br, rgba(255, 105, 140, 0.55f), rgba(255, 105, 140, 0f), Shader.TileMode.CLAMP)))
            }
            // A shine out on the rim; over the face it read as a smudge on the forehead.
            val save = c.save()
            c.rotate(-40f, x, y)
            c.drawOval(p.rect(x, y - R * 0.8f, R * 0.26f, R * 0.07f), p.fill(rgba(255, 255, 255, 0.3f)))
            c.restoreToCount(save)
        }

        p.lift(d.h * 0.01f)
        c.drawPath(rim, p.fill(LinearGradient(
            x0 - R * 1.5f, 0f, x0 + R * 1.5f, 0f, intArrayOf(hex("#4aa834"), hex("#86d858"), hex("#3a8f28")), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP,
        )))
        p.unlift()

        val tiltRad = Math.toRadians(tilt.toDouble()).toFloat()
        for (i in 0 until PEAS) {
            peaCentre(d, i)
            arms(c, p, pt[0], pt[1], R, d.t, i, tiltRad)
        }
        peaCentre(d, 0)
        sprouts(c, p, pt[0], pt[1], R, d.t)
        c.restoreToCount(pod)
    }

    // Bendy green arms with mitten hands. On each pea one arm waves hello and the other swings
    // loose, alternating down the pod, and both lean against the rock as if holding on.
    private fun arms(c: Canvas, p: Pen, x: Float, y: Float, R: Float, t: Long, i: Int, tilt: Float) {
        val dark = hex("#2f7d22")
        val light = hex("#74cf50")
        for (side in intArrayOf(-1, 1)) {
            val waving = (i + (if (side < 0) 0 else 1)) % 2 == 0
            val swing = sin((t % 100_000L) / (if (waving) 140f else 320f) + i * 1.7f + side)
            // 0 points straight out and negative raises the arm, mirrored per side below.
            val raise = if (waving) -1.1f + swing * 0.45f else 0.45f + swing * 0.3f
            val bend = if (waving) -0.35f - swing * 0.2f else 0.25f + swing * 0.1f
            val L = R * 0.8f
            val s = c.save()
            c.translate(x + side * R * 0.88f, y + R * 0.2f)
            c.scale(side.toFloat(), 1f)
            c.rotate(deg(raise - side * tilt * 1.5f))
            p.newPath().apply {
                moveTo(0f, 0f)
                quadTo(L * 0.5f, L * bend, L, 0f)
            }
            // Outline pass with the shadow, then the light fill over it.
            val arm = p.stroke(dark, R * 0.17f)
            arm.strokeCap = Paint.Cap.ROUND
            p.lift(R * 0.04f)
            c.drawPath(p.path, arm)
            c.drawCircle(L, 0f, R * 0.15f, p.fill(dark))
            c.drawCircle(L - R * 0.05f, -R * 0.13f, R * 0.075f, p.fill(dark))
            p.unlift()
            c.drawPath(p.path, p.stroke(light, R * 0.09f))
            c.drawCircle(L, 0f, R * 0.105f, p.fill(light))
            c.drawCircle(L - R * 0.05f, -R * 0.13f, R * 0.04f, p.fill(light))
            c.restoreToCount(s)
        }
    }

    // Two leafy sprouts on the top pea, standing in for the original's antennae.
    private fun sprouts(c: Canvas, p: Pen, x: Float, y: Float, R: Float, t: Long) {
        for (side in intArrayOf(-1, 1)) {
            val sway = sin(t / 330f + side) * R * 0.06f
            val tx = x + side * R * 0.55f + sway
            val ty = y - R * 1.45f
            val stalk = p.stroke(hex("#3f9a2c"), R * 0.05f)
            stalk.strokeCap = Paint.Cap.ROUND
            p.newPath().apply {
                moveTo(x + side * R * 0.3f, y - R * 0.9f)
                quadTo(x + side * R * 0.2f, y - R * 1.3f, tx, ty)
            }
            c.drawPath(p.path, stalk)
            val s = c.save()
            c.translate(tx, ty)
            c.rotate(deg(side * 0.9f))
            p.newPath().apply {
                moveTo(0f, 0f)
                quadTo(R * 0.12f, -R * 0.2f, 0f, -R * 0.36f)
                quadTo(-R * 0.12f, -R * 0.2f, 0f, 0f)
                close()
            }
            c.drawPath(p.path, p.fill(hex("#6cc84a")))
            c.restoreToCount(s)
        }
    }
}

/* -------------------------------- Lemonade -------------------------------- */

// Your face seen through a glass of pink lemonade: tinted, softly blurred, rippling a little and
// stretched to the glass. The glass floats on a coral-to-pink ground, drifts after you and tilts
// with your head, with ice, a striped straw and a lemon slice. Pucker (or open wide) to blow
// bubbles through the straw, with a bloop for each burst.
object Lemonade : Filter("lemonade", "Lemonade", "🍋", Mode.MESH, voice = 0.9f) {
    override val usesUnder = true
    override val coversCamera = true

    // Glass units: x in top half-widths, y in half-heights, both from the glass's centre.
    private const val SURFACE_Y = -0.8f
    // Up the right-hand side, so neither the straw nor its bubbles cross the face.
    private const val STRAW_FOOT_X = 0.5f
    private const val STRAW_FOOT_Y = 0.72f

    private class Bubble(var x: Float, var y: Float, val r: Float, val vy: Float, val phase: Float)

    private class State {
        val bubbles = ArrayList<Bubble>()
        var fizzDebt = 0f
        var blowDebt = 0f
        var nextBloop = 0L
        var seen = 0L
    }

    private class Glass(val x: Float, val y: Float, val w: Float, val h: Float, val angle: Float)

    private val states = HashMap<Int, State>()
    private val interior = Path()
    private val outer = Path()
    private val walls = Path()
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // One glass per person, side by side for two, each following its head a little.
    private fun glass(d: Draw, f: Face): Glass {
        val n = f.count
        val scale = if (n == 1) 1f else 0.78f
        val slot = d.w * (f.rank + 1) / (n + 1)
        val x = slot + (f.cx / d.w - 0.5f) * d.w * (0.14f / n)
        val bob = sin((d.t % 100_000L) / 700f + f.rank * 2f) * d.h * 0.012f
        val y = d.h * 0.53f + (f.cy / d.h - 0.5f) * d.h * 0.08f + bob
        return Glass(x, y, d.h * 0.23f * scale, d.h * 0.33f * scale, f.angle.coerceIn(-0.5f, 0.5f))
    }

    private fun buildGlass(g: Glass) {
        val W = g.w
        val H = g.h
        val r = W * 0.12f
        fun tumbler(path: Path, top: Float, bottom: Float, wt: Float, wb: Float) {
            path.reset()
            path.moveTo(-wt, top)
            path.lineTo(wt, top)
            path.lineTo(wb, bottom - r)
            path.quadTo(wb, bottom, wb - r, bottom)
            path.lineTo(-wb + r, bottom)
            path.quadTo(-wb, bottom, -wb, bottom - r)
            path.close()
        }
        // The interior matches the patch shader's tumbler mask exactly.
        tumbler(interior, -H, H, W, W * 0.8f)
        val t = W * 0.07f
        tumbler(outer, -H - t * 0.2f, H + t * 2.2f, W + t, W * 0.8f + t)
        walls.reset()
        walls.op(outer, interior, Path.Op.DIFFERENCE)
    }

    override fun scene(d: Draw, faces: List<Face>) {
        d.c.drawRect(0f, 0f, d.w, d.h, d.pen.fill(LinearGradient(0f, 0f, 0f, d.h, hex("#ff8a6b"), hex("#ff6fae"), Shader.TileMode.CLAMP)))
    }

    override fun update(d: Draw, faces: List<Face>) {
        val dt = d.dt / 1000f
        for (f in faces) {
            val s = states.getOrPut(f.id) { State() }
            s.seen = d.t
            // A gentle fizz from the bottom all the time.
            s.fizzDebt += dt * 3f
            while (s.fizzDebt >= 1f) {
                s.fizzDebt -= 1f
                s.bubbles += Bubble(rnd(-0.6f, 0.6f), 0.95f, rnd(0.015f, 0.035f), rnd(0.25f, 0.45f), rnd(0f, TAU))
            }
            val blowing = f.bs("mouthPucker") > 0.45f || f.bs("mouthFunnel") > 0.35f || f.bs("jawOpen") > 0.5f
            if (blowing) {
                s.blowDebt += dt * 28f
                while (s.blowDebt >= 1f) {
                    s.blowDebt -= 1f
                    s.bubbles += Bubble(STRAW_FOOT_X + rnd(-0.06f, 0.06f), STRAW_FOOT_Y, rnd(0.03f, 0.08f), rnd(0.9f, 1.5f), rnd(0f, TAU))
                }
                if (d.t >= s.nextBloop) {
                    s.nextBloop = d.t + 110 + rng.nextInt(60)
                    Sfx.play("bloop", 0.45f, rnd(0.8f, 1.5f))
                }
            } else {
                s.blowDebt = 0f
            }
            val it = s.bubbles.iterator()
            while (it.hasNext()) {
                val b = it.next()
                b.y -= b.vy * dt * 2f
                b.x += sin((d.t % 100_000L) / 180f + b.phase) * dt * 0.08f
                if (b.y < SURFACE_Y) it.remove()
            }
            if (s.bubbles.size > 160) s.bubbles.subList(0, s.bubbles.size - 160).clear()
        }
        if (states.size > 4) states.entries.removeAll { d.t - it.value.seen > 3000 }
    }

    override fun under(d: Draw, f: Face) {
        val c = d.c
        val p = d.pen
        val g = glass(d, f)
        buildGlass(g)
        // A soft shadow on the ground, which doesn't tilt.
        val sy = g.y + g.h * 1.25f
        val s = c.save()
        c.scale(1f, 0.18f, g.x, sy)
        c.drawCircle(g.x, sy, g.w * 1.1f, p.fill(RadialGradient(g.x, sy, g.w * 1.1f, rgba(120, 30, 60, 0.3f), rgba(120, 30, 60, 0f), Shader.TileMode.CLAMP)))
        c.restoreToCount(s)
        // The lemonade behind the face, so the patch's soft edge fades into pink, not ground.
        val s2 = c.save()
        c.translate(g.x, g.y)
        c.rotate(deg(g.angle))
        c.drawPath(interior, p.fill(LinearGradient(0f, -g.h, 0f, g.h, hex("#ffb3cf"), hex("#f56a9c"), Shader.TileMode.CLAMP)))
        c.restoreToCount(s2)
    }

    override fun draw(d: Draw, f: Face) {
        val c = d.c
        val p = d.pen
        val g = glass(d, f)
        buildGlass(g)
        val st = states.getOrPut(f.id) { State() }

        // The head box into the glass, stretched to fill it and upright relative to the glass:
        // src = head centre + R(head) * S * R(-glass) * (dst - glass centre).
        val hb = headBox(f)
        val sx = hb.halfW * 0.95f / (g.w * 0.9f)
        val sy = hb.halfH * 0.92f / g.h
        val ca = cos(f.angle)
        val sa = sin(f.angle)
        val cg = cos(g.angle)
        val sg = sin(g.angle)
        val m00 = ca * sx * cg + sa * sy * sg
        val m01 = ca * sx * sg - sa * sy * cg
        val m10 = sa * sx * cg - ca * sy * sg
        val m11 = sa * sx * sg + ca * sy * cg
        d.patches += Patch(
            g.x, g.y, g.w, g.h, g.angle, 0.94f,
            floatArrayOf(m00, m01, hb.x - (m00 * g.x + m01 * g.y), m10, m11, hb.y - (m10 * g.x + m11 * g.y)),
            shape = Patch.GLASS, tint = rgba(255, 110, 170, 0.7f), blur = 2.5f, wave = 2.5f,
            phase = (d.t % 100_000L) / 1000f * 3f,
        )

        val W = g.w
        val H = g.h
        val save = c.save()
        c.translate(g.x, g.y)
        c.rotate(deg(g.angle))
        val inside = c.save()
        c.clipPath(interior)
        ice(c, p, W, H, d.t)
        straw(c, p, W, H, submerged = true)
        bubbles(c, st, W, H)
        surface(c, p, W, H, g.angle, d.t)
        c.restoreToCount(inside)
        glassFront(c, p, W, H)
        val above = c.save()
        c.clipRect(-W * 3, -H * 3, W * 3, -H)
        straw(c, p, W, H, submerged = false)
        c.restoreToCount(above)
        lemon(c, p, W, H)
        c.restoreToCount(save)
    }

    private fun ice(c: Canvas, p: Pen, W: Float, H: Float, t: Long) {
        // x, y, half-size (in W), turn. Floating high at the surface: any lower and they sit
        // over the eyes.
        val cubes = arrayOf(
            floatArrayOf(-0.42f, -0.76f, 0.2f, 0.3f),
            floatArrayOf(0.22f, -0.8f, 0.19f, -0.4f),
            floatArrayOf(-0.1f, -0.7f, 0.16f, 0.9f),
        )
        for ((k, cube) in cubes.withIndex()) {
            val bob = sin((t % 100_000L) / 600f + k * 2.1f)
            val s = c.save()
            c.translate(cube[0] * W, cube[1] * H + bob * H * 0.015f)
            c.rotate(deg(cube[3] + bob * 0.08f))
            val a = cube[2] * W
            c.drawRoundRect(p.rect(0f, 0f, a, a), a * 0.35f, a * 0.35f, p.fill(rgba(255, 255, 255, 0.28f)))
            c.drawRoundRect(p.rect(0f, 0f, a, a), a * 0.35f, a * 0.35f, p.stroke(rgba(255, 255, 255, 0.75f), a * 0.08f))
            c.drawRoundRect(p.rect(-a * 0.35f, -a * 0.4f, a * 0.3f, a * 0.12f), a * 0.1f, a * 0.1f, p.fill(rgba(255, 255, 255, 0.6f)))
            c.restoreToCount(s)
        }
    }

    // White with pink stripes; fainter where it's under the lemonade.
    private fun straw(c: Canvas, p: Pen, W: Float, H: Float, submerged: Boolean) {
        val width = W * 0.1f
        p.newPath().apply {
            moveTo(STRAW_FOOT_X * W, STRAW_FOOT_Y * H)
            lineTo(0.78f * W, -1.45f * H)
        }
        val body = p.stroke(if (submerged) rgba(255, 255, 255, 0.45f) else Color.WHITE, width)
        body.strokeCap = Paint.Cap.BUTT
        c.drawPath(p.path, body)
        val stripes = p.stroke(if (submerged) rgba(255, 60, 130, 0.45f) else hex("#ff3d82"), width)
        stripes.pathEffect = DashPathEffect(floatArrayOf(width * 0.9f, width * 0.9f), 0f)
        c.drawPath(p.path, stripes)
        stripes.pathEffect = null
    }

    private fun bubbles(c: Canvas, s: State, W: Float, H: Float) {
        for (b in s.bubbles) {
            val x = b.x * W
            val y = b.y * H
            val r = b.r * H
            bubblePaint.style = Paint.Style.FILL
            bubblePaint.color = rgba(255, 255, 255, 0.22f)
            c.drawCircle(x, y, r, bubblePaint)
            bubblePaint.style = Paint.Style.STROKE
            bubblePaint.strokeWidth = r * 0.22f
            bubblePaint.color = rgba(255, 255, 255, 0.75f)
            c.drawCircle(x, y, r, bubblePaint)
            bubblePaint.style = Paint.Style.FILL
            bubblePaint.color = rgba(255, 255, 255, 0.85f)
            c.drawCircle(x - r * 0.35f, y - r * 0.35f, r * 0.25f, bubblePaint)
        }
    }

    // The top of the lemonade stays nearly level while the glass tilts, and sloshes a little.
    // Above it the glass is paler: air, not lemonade.
    private fun surface(c: Canvas, p: Pen, W: Float, H: Float, angle: Float, t: Long) {
        val halfW = W * 1.15f
        val slosh = sin((t % 100_000L) / 400f) * 0.04f
        val s = c.save()
        c.translate(0f, SURFACE_Y * H)
        c.rotate(deg(-angle * 0.85f + slosh))
        c.drawRect(-halfW, -H * 0.6f, halfW, 0f, p.fill(rgba(255, 240, 246, 0.55f)))
        c.drawOval(p.rect(0f, 0f, halfW, W * 0.07f), p.fill(rgba(255, 200, 225, 0.55f)))
        c.drawOval(p.rect(0f, 0f, halfW, W * 0.07f), p.stroke(rgba(255, 255, 255, 0.7f), W * 0.012f))
        c.restoreToCount(s)
    }

    private fun glassFront(c: Canvas, p: Pen, W: Float, H: Float) {
        c.drawPath(walls, p.fill(rgba(255, 255, 255, 0.3f)))
        c.drawPath(outer, p.stroke(rgba(255, 255, 255, 0.85f), W * 0.02f))
        c.drawPath(interior, p.stroke(rgba(255, 255, 255, 0.35f), W * 0.012f))
        // Tall highlights down the left wall.
        p.newPath().apply {
            moveTo(-0.9f * W, -0.85f * H)
            lineTo(-0.8f * W, -0.85f * H)
            lineTo(-0.66f * W, 0.6f * H)
            lineTo(-0.74f * W, 0.6f * H)
            close()
        }
        c.drawPath(p.path, p.fill(rgba(255, 255, 255, 0.35f)))
        p.newPath().apply {
            moveTo(-0.72f * W, -0.8f * H)
            lineTo(-0.68f * W, -0.8f * H)
            lineTo(-0.56f * W, 0.3f * H)
            lineTo(-0.6f * W, 0.3f * H)
            close()
        }
        c.drawPath(p.path, p.fill(rgba(255, 255, 255, 0.2f)))
        // The rim, and a thick glass base.
        c.drawOval(p.rect(0f, -H, W * 1.035f, W * 0.13f), p.stroke(rgba(255, 255, 255, 0.85f), W * 0.025f))
        c.drawRoundRect(p.rect(0f, H + W * 0.08f, W * 0.87f, W * 0.08f), W * 0.06f, W * 0.06f, p.fill(rgba(255, 255, 255, 0.35f)))
    }

    // A slice on the left rim, clear of the straw.
    private fun lemon(c: Canvas, p: Pen, W: Float, H: Float) {
        val x = -0.78f * W
        val y = -H - W * 0.05f
        val R = W * 0.3f
        p.lift(W * 0.03f)
        c.drawCircle(x, y, R, p.fill(hex("#ffd23f")))
        p.unlift()
        c.drawCircle(x, y, R * 0.86f, p.fill(hex("#fff3b0")))
        val seg = p.stroke(hex("#f5c02e"), R * 0.06f)
        for (k in 0 until 8) {
            val a = k * TAU / 8
            c.drawLine(x, y, x + cos(a) * R * 0.8f, y + sin(a) * R * 0.8f, seg)
        }
        c.drawCircle(x, y, R * 0.12f, p.fill(hex("#fff3b0")))
    }
}
