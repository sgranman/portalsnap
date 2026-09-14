package net.sgran.portalsnap

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
