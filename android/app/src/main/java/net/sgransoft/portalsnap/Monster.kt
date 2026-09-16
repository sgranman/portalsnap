package net.sgransoft.portalsnap

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// Photo Booth effect 4, redrawn from its reference video (936589334645536): Monster / Cutie.
// Doodle face paint in two moods, with the eyes enlarged in both.
//  - Monster: cream horns, thick tapered angry brows with a frown crease, big black pupils with a
//    glint, X marks and a pink zigzag blush, and a fanged frown that becomes a huge black mouth
//    with fangs and a drooping pink tongue when the real mouth opens.
//  - Cutie: fluffy white ears with pink insides, pink hearts on the cheeks, and three little dashes
//    at the corner of each eye.
// A nod (or a tap) switches moods with a poof: a pink cloud swells over the face, opens into a ring
// with the new look showing through, and breaks into specks that drift away, with a poof sound.
// The voice follows: deep for the monster, high for the cutie.
// All art is in face units: the eyes at (±0.5, 0), y down, one unit between the eyes.
object MonsterCutie : Filter("monster", "Monster", "👹", Mode.MESH) {
    // Both voices sit high: three semitones down is plenty of monster without the growl turning
    // into a rumble, and a clean octave up makes the cutie properly squeaky. The reference
    // measured nearer 0.6x and 1.7x with the formants held, but a formant-preserving shifter
    // (TD-PSOLA) at those ratios sounded far worse on a real voice than plain ratios do.
    private const val MONSTER_VOICE = 0.84f
    private const val CUTIE_VOICE = 2f
    private const val EYE_BULGE = 0.62f
    // The new look shows once the cloud covers the face.
    private const val REVEAL_MS = 180L
    private const val POOF_MS = 850L

    private class State {
        var monster = true
        var changedAt = 0L
        val nod = Nod()
        var seen = 0L
    }

    private class Blob(
        var x: Float, var y: Float, var vx: Float, var vy: Float,
        val born: Long, val life: Float, val size: Float, val speck: Boolean,
    )

    private val states = HashMap<Int, State>()
    private val blobs = ArrayList<Blob>()
    @Volatile private var pokes = 0
    private var seenPokes = 0
    private val NO_MAP = FloatArray(6)

    private val INK = Color.rgb(17, 17, 17)
    private val HORN = hex("#f2ebd4")
    private val TOOTH = hex("#fbf8ee")
    private val BLUSH = hex("#f2909f")
    private val TONGUE = hex("#ef89a6")
    private val CREASE = hex("#d45f85")
    private val HEART = hex("#f3868e")
    private val EAR_INNER = hex("#f3b4bd")
    private val CLOUD = hex("#f7a7b7")
    private val CLOUD_LIGHT = hex("#fbd2da")
    private val SPECK = hex("#f48ea1")

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = INK
    }
    private val path = Path()
    private val clip = Path()

    override fun poke() {
        pokes++
    }

    override fun voiceFrom(face: Face) = if (states[face.id]?.monster != false) MONSTER_VOICE else CUTIE_VOICE

    /* ------------------------------ switching ------------------------------ */

    override fun update(d: Draw, faces: List<Face>) {
        val poked = pokes != seenPokes
        seenPokes = pokes
        for (f in faces) {
            val s = states.getOrPut(f.id) { State() }
            s.seen = d.t
            val nodded = f.pitch?.let { s.nod.update(it, d.t) } ?: false
            if ((nodded || poked) && d.t - s.changedAt > POOF_MS) {
                s.monster = !s.monster
                s.changedAt = d.t
                poof(d, f)
                Sfx.play("poof", 0.8f)
            }
        }
        if (states.size > 4) states.entries.removeAll { d.t - it.value.seen > 3000 }
        val dt = min(d.dt, 50f) / 1000f
        val it = blobs.iterator()
        while (it.hasNext()) {
            val b = it.next()
            val age = (d.t - b.born) / 1000f
            if (age > b.life) {
                it.remove()
                continue
            }
            if (age < 0f) continue
            val drag = max(0f, 1f - (if (b.speck) 2.5f else 4f) * dt)
            b.vx *= drag
            b.vy = b.vy * drag - (if (b.speck) 60f else 20f) * dt
            b.x += b.vx * dt
            b.y += b.vy * dt
        }
    }

    // The cloud: big bubbly blobs bursting out from the middle of the face, so they cover it and
    // then open into a ring; and, a moment later, small specks flung further out.
    private fun poof(d: Draw, f: Face) {
        val c = toPixels(f, 0f, 0.35f)
        val e = f.eyeDist
        for (k in 0 until 26) {
            val a = k * TAU / 26 + rnd(-0.12f, 0.12f)
            val r0 = e * rnd(0.05f, 0.3f)
            val speed = e * rnd(1.8f, 2.6f)
            blobs += Blob(c.x + cos(a) * r0, c.y + sin(a) * r0 * 1.1f, cos(a) * speed, sin(a) * speed * 1.1f, d.t, 0.55f, e * rnd(0.2f, 0.32f), false)
        }
        for (k in 0 until 34) {
            val a = rnd(0f, TAU)
            val r0 = e * rnd(0.6f, 0.9f)
            val speed = e * rnd(1.5f, 3.2f)
            blobs += Blob(c.x + cos(a) * r0, c.y + sin(a) * r0, cos(a) * speed, sin(a) * speed - e * 0.4f, d.t + 220, 0.6f, e * rnd(0.035f, 0.09f), true)
        }
    }

    /* ------------------------------ drawing ------------------------------ */

    override fun draw(d: Draw, f: Face) {
        val s = states[f.id] ?: return
        eyes(d, f)
        // Until the cloud covers the face, the old look stays; then the new one pops in.
        val sinceSwitch = d.t - s.changedAt
        val showMonster = if (s.changedAt != 0L && sinceSwitch < REVEAL_MS) !s.monster else s.monster
        val popT = ((sinceSwitch - REVEAL_MS) / 240f).coerceIn(0f, 1f)
        val pop = if (s.changedAt == 0L || sinceSwitch < REVEAL_MS) 1f else 0.85f + 0.15f * (1 - (1 - popT) * (1 - popT)) + 0.06f * sin(popT * PI.toFloat())
        val k = pupils(f)
        inFaceSpace(d.c, f) {
            d.c.scale(pop, pop, 0f, 0.3f)
            if (showMonster) monster(d.c, f, k) else cutie(d.c, k)
        }
    }

    override fun overlay(d: Draw, faces: List<Face>) {
        val c = d.c
        for (b in blobs) {
            val age = (d.t - b.born) / 1000f
            if (age < 0f || age > b.life) continue
            val p = age / b.life
            if (b.speck) {
                fill.color = SPECK
                fill.alpha = (255 * min(1f, (1 - p) * 2.5f)).toInt()
                c.drawCircle(b.x, b.y, b.size * (1f - 0.4f * p), fill)
            } else {
                // Swell fast, then shrink away; each blob is a pink puff with a paler top.
                val grow = min(1f, age / 0.12f)
                val r = b.size * (0.5f + 0.7f * grow) * (1f - 0.75f * max(0f, (p - 0.45f) / 0.55f))
                val a = (255 * min(1f, (1 - p) * 3f)).toInt()
                fill.color = CLOUD
                fill.alpha = a
                c.drawCircle(b.x, b.y, r, fill)
                fill.color = CLOUD_LIGHT
                fill.alpha = (a * 0.8f).toInt()
                c.drawCircle(b.x - r * 0.22f, b.y - r * 0.25f, r * 0.55f, fill)
            }
        }
    }

    // Both moods have big eyes: a lens over each eye, as Hamster's.
    private fun eyes(d: Draw, f: Face) {
        for ((i, key) in arrayOf("eyeInR", "eyeInL").withIndex()) {
            val inner = f[key]
            val side = if (inner != null) (if (inner.x < 0) -1f else 1f) else (if (i == 0) -1f else 1f)
            val ix = inner?.x ?: (side * 0.18f)
            val iy = inner?.y ?: 0f
            val outer = side * 0.5f
            val centre = toPixels(f, (outer + ix) / 2, iy / 2)
            val rx = abs(outer - ix) * f.eyeDist * 1.35f
            d.patches += Patch(centre.x, centre.y, rx, rx * 0.8f, f.angle, 1f, NO_MAP, bulge = EYE_BULGE)
        }
    }

    // Where an eye's middle sits in face units, from its corners.
    private fun eyeCentreX(f: Face, side: Int): Float {
        val inner = f[if (side < 0) "eyeInR" else "eyeInL"]
        val ix = inner?.x ?: (side * 0.18f)
        return (side * 0.5f + ix) / 2
    }

    // The distance between the pupils, in face units (whose unit is the outer eye corners, so
    // about 0.65). The reference art was measured with the pupils one apart, so the art around
    // the eyes is drawn in that unit; the first build used face units and came out 1.5x too big.
    private fun pupils(f: Face) = abs(eyeCentreX(f, 1) - eyeCentreX(f, -1)).coerceIn(0.45f, 0.85f)

    /* ------------------------------ monster ------------------------------ */

    private fun monster(c: Canvas, f: Face, k: Float) {
        val save = c.save()
        c.scale(k, k)
        for (side in intArrayOf(-1, 1)) {
            val s = side.toFloat()
            horn(c, s)
            // Thick brush brows: low at the outer end, up over a peak, then a long slope down to
            // the nose, tapering at the outer end.
            brush(c, s * 0.97f, -0.16f, s * 0.9f, -0.36f, s * 0.45f, -0.31f, s * 0.12f, -0.19f, 0.11f)
            // An X mark out on the cheek.
            ink.strokeWidth = 0.035f
            c.drawLine(s * 0.86f - 0.075f, 0.225f, s * 0.86f + 0.075f, 0.375f, ink)
            c.drawLine(s * 0.86f - 0.075f, 0.375f, s * 0.86f + 0.075f, 0.225f, ink)
            // A pink scribble for blush: rounded zigzags sloping off toward the jaw.
            blushScribble(c, s)
        }
        // The frown crease between the brows.
        brush(c, 0.05f, -0.28f, 0.052f, -0.23f, 0.056f, -0.17f, 0.06f, -0.11f, 0.045f)
        c.restoreToCount(save)
        for (side in intArrayOf(-1, 1)) {
            val s = side.toFloat()
            // A big black pupil on the enlarged eye, a glint up and to the left, a thin lid line under.
            val ex = eyeCentreX(f, side)
            fill.color = INK
            c.drawCircle(ex, 0.01f * k, 0.16f * k, fill)
            fill.color = Color.WHITE
            c.drawCircle(ex - 0.05f * k, -0.045f * k, 0.042f * k, fill)
            ink.strokeWidth = 0.025f * k
            c.drawLine(ex - s * 0.17f * k, 0.18f * k, ex + s * 0.21f * k, 0.16f * k, ink)
        }
        val open = f.bs("jawOpen")
        if (open > 0.18f) openMouth(c, f, open, k) else fangs(c, f, k)
    }

    // A cream horn leaning outward, with a black outline.
    private fun horn(c: Canvas, s: Float) {
        path.reset()
        path.moveTo(s * 0.34f, -0.74f)
        path.quadTo(s * 0.5f, -1.05f, s * 0.75f, -1.3f)
        path.quadTo(s * 0.93f, -0.98f, s * 0.8f, -0.7f)
        path.quadTo(s * 0.57f, -0.63f, s * 0.34f, -0.74f)
        path.close()
        fill.color = HORN
        c.drawPath(path, fill)
        ink.strokeWidth = 0.05f
        c.drawPath(path, ink)
    }

    private fun blushScribble(c: Canvas, s: Float) {
        val pts = floatArrayOf(0.36f, 0.47f, 0.43f, 0.38f, 0.48f, 0.5f, 0.55f, 0.38f, 0.6f, 0.5f, 0.67f, 0.39f, 0.72f, 0.51f, 0.79f, 0.41f, 0.84f, 0.53f)
        path.reset()
        path.moveTo(s * pts[0], pts[1])
        for (i in 1 until pts.size / 2 - 1) {
            val mx = (pts[i * 2] + pts[i * 2 + 2]) / 2
            val my = (pts[i * 2 + 1] + pts[i * 2 + 3]) / 2
            path.quadTo(s * pts[i * 2], pts[i * 2 + 1], s * mx, my)
        }
        path.lineTo(s * pts[pts.size - 2], pts[pts.size - 1])
        val old = ink.color
        ink.color = BLUSH
        // Bold, as in the reference: at 0.045 it read as a faint thread.
        ink.strokeWidth = 0.075f
        c.drawPath(path, ink)
        ink.color = old
    }

    // The closed mouth: a brushy frown along the lips with five fangs hanging from it.
    private fun fangs(c: Canvas, f: Face, k: Float) {
        val mx = f.mouth.x
        val my = f.mouth.y - 0.02f * k
        val w = mouthHalfWidth(f)
        val teeth = floatArrayOf(-0.72f, -0.36f, 0f, 0.36f, 0.72f)
        for (t in teeth) {
            val big = 1f - 0.35f * abs(t)
            val tx = mx + t * w
            val tw = 0.095f * big * k
            val th = 0.22f * big * k
            val ty = my + 0.02f * k - abs(t) * 0.04f * k
            path.reset()
            path.moveTo(tx - tw, ty)
            path.quadTo(tx - tw * 0.8f, ty + th * 0.75f, tx, ty + th)
            path.quadTo(tx + tw * 0.8f, ty + th * 0.75f, tx + tw, ty)
            path.close()
            fill.color = TOOTH
            c.drawPath(path, fill)
            ink.strokeWidth = 0.03f * k
            c.drawPath(path, ink)
        }
        brush(c, mx - w * 1.05f, my + 0.08f * k, mx - w * 0.4f, my - 0.05f * k, mx + w * 0.4f, my - 0.05f * k, mx + w * 1.05f, my + 0.08f * k, 0.09f * k)
    }

    // Wide open: a rounded black cup, broad across the top and round at the bottom, with fangs
    // top and bottom and a pink tongue drooping out over the lower lip.
    private fun openMouth(c: Canvas, f: Face, open: Float, k: Float) {
        val mx = f.mouth.x
        val w = mouthHalfWidth(f) * (1f + 0.25f * open)
        val top = f.mouth.y - 0.2f * k
        val bottom = f.mouth.y + (0.2f + 0.95f * open) * k
        val h = bottom - top
        path.reset()
        path.moveTo(mx - w * 0.92f, top + h * 0.1f)
        path.quadTo(mx, top - h * 0.05f, mx + w * 0.92f, top + h * 0.1f)
        path.cubicTo(mx + w * 1.12f, top + h * 0.3f, mx + w * 0.8f, bottom, mx, bottom)
        path.cubicTo(mx - w * 0.8f, bottom, mx - w * 1.12f, top + h * 0.3f, mx - w * 0.92f, top + h * 0.1f)
        path.close()
        fill.color = INK
        c.drawPath(path, fill)
        ink.strokeWidth = 0.05f * k
        c.drawPath(path, ink)
        clip.set(path)
        val save = c.save()
        c.clipPath(clip)
        // Fangs along the top, the outer pair longer, and two up from the bottom corners.
        for (t in floatArrayOf(-0.8f, -0.42f, -0.14f, 0.14f, 0.42f, 0.8f)) {
            val outer = abs(t) > 0.7f
            tooth(c, mx + t * w, top + h * (if (outer) 0.06f else 0.0f), (if (outer) 0.1f else 0.085f) * k, h * (if (outer) 0.3f else 0.2f), down = true, k = k)
        }
        for (t in floatArrayOf(-0.55f, 0.55f)) tooth(c, mx + t * w, bottom - h * 0.1f, 0.085f * k, h * 0.2f, down = false, k = k)
        c.restoreToCount(save)
        // The tongue: wide, with a crease down the middle and a glint near the tip.
        val tw = w * 0.4f
        val tTop = bottom - h * 0.45f
        val tTip = bottom + (0.35f + 0.35f * open) * k
        path.reset()
        path.moveTo(mx - tw, tTop)
        path.cubicTo(mx - tw * 1.15f, tTip - (tTip - tTop) * 0.25f, mx - tw * 0.6f, tTip, mx, tTip)
        path.cubicTo(mx + tw * 0.6f, tTip, mx + tw * 1.15f, tTip - (tTip - tTop) * 0.25f, mx + tw, tTop)
        path.close()
        fill.color = TONGUE
        c.drawPath(path, fill)
        ink.strokeWidth = 0.045f * k
        c.drawPath(path, ink)
        val old = ink.color
        ink.color = CREASE
        ink.strokeWidth = 0.035f * k
        c.drawLine(mx, tTop + 0.04f * k, mx, tTop + (tTip - tTop) * 0.45f, ink)
        ink.color = old
        fill.color = Color.WHITE
        fill.alpha = 220
        c.drawOval(mx + tw * 0.25f - 0.05f * k, tTip - 0.16f * k, mx + tw * 0.25f + 0.02f * k, tTip - 0.08f * k, fill)
        fill.alpha = 255
    }

    private fun tooth(c: Canvas, x: Float, y: Float, halfW: Float, len: Float, down: Boolean, k: Float) {
        val dir = if (down) 1f else -1f
        path.reset()
        path.moveTo(x - halfW, y)
        path.quadTo(x - halfW * 0.7f, y + dir * len * 0.8f, x, y + dir * len)
        path.quadTo(x + halfW * 0.7f, y + dir * len * 0.8f, x + halfW, y)
        path.close()
        fill.color = TOOTH
        c.drawPath(path, fill)
        ink.strokeWidth = 0.028f * k
        c.drawPath(path, ink)
    }

    private fun mouthHalfWidth(f: Face): Float {
        val r = f["mouthR"]
        val l = f["mouthL"]
        return max(0.34f, if (r != null && l != null) abs(l.x - r.x) * 0.52f else 0.42f)
    }

    // A filled brush stroke along a cubic curve, full width through the middle and tapering to
    // points at both ends, like the reference's inked strokes.
    private fun brush(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float, width: Float) {
        val n = 16
        val lx = FloatArray(n + 1)
        val ly = FloatArray(n + 1)
        val rx = FloatArray(n + 1)
        val ry = FloatArray(n + 1)
        for (i in 0..n) {
            val t = i.toFloat() / n
            val u = 1 - t
            val px = u * u * u * x0 + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t * x3
            val py = u * u * u * y0 + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t * y3
            var dx = 3 * u * u * (x1 - x0) + 6 * u * t * (x2 - x1) + 3 * t * t * (x3 - x2)
            var dy = 3 * u * u * (y1 - y0) + 6 * u * t * (y2 - y1) + 3 * t * t * (y3 - y2)
            val len = max(1e-4f, sqrt(dx * dx + dy * dy))
            dx /= len
            dy /= len
            val half = width / 2 * min(1f, t / 0.3f).let { sqrt(it) } * min(1f, (1 - t) / 0.18f).let { sqrt(it) }
            lx[i] = px - dy * half
            ly[i] = py + dx * half
            rx[i] = px + dy * half
            ry[i] = py - dx * half
        }
        path.reset()
        path.moveTo(lx[0], ly[0])
        for (i in 1..n) path.lineTo(lx[i], ly[i])
        for (i in n downTo 0) path.lineTo(rx[i], ry[i])
        path.close()
        fill.color = INK
        c.drawPath(path, fill)
    }

    /* ------------------------------- cutie ------------------------------- */

    private fun cutie(c: Canvas, k: Float) {
        val save = c.save()
        c.scale(k, k)
        cutieArt(c)
        c.restoreToCount(save)
    }

    private fun cutieArt(c: Canvas) {
        for (side in intArrayOf(-1, 1)) {
            val s = side.toFloat()
            ear(c, s * 0.95f, -1.1f, 0.4f, s)
            // Three little dashes out from the corner of the eye.
            ink.strokeWidth = 0.03f
            for (i in 0 until 3) {
                val y = 0.15f + i * 0.065f
                c.drawLine(s * 0.7f, y, s * 0.85f, y - 0.045f + i * 0.02f, ink)
            }
            heart(c, s * 0.66f, 0.6f, 0.58f)
        }
    }

    // A fluffy white ear: fine fuzz round the rim (big tufts read as a cog), a smooth middle, and a
    // pink inside set low and in.
    private fun ear(c: Canvas, x: Float, y: Float, r: Float, s: Float) {
        fill.color = Color.WHITE
        for (k in 0 until 72) {
            val a = k * TAU / 72
            val tuft = r * (0.028f + 0.018f * hashf(k * 5 + 3))
            c.drawCircle(x + cos(a) * r * 0.985f, y + sin(a) * r * 0.985f, tuft, fill)
        }
        c.drawCircle(x, y, r * 0.97f, fill)
        fill.color = EAR_INNER
        c.drawOval(x - s * r * 0.25f - r * 0.45f, y + r * 0.1f - r * 0.38f, x - s * r * 0.25f + r * 0.45f, y + r * 0.1f + r * 0.38f, fill)
    }

    // A soft pink heart with a grainy, crayoned texture.
    private fun heart(c: Canvas, x: Float, y: Float, size: Float) {
        val h = size / 2
        path.reset()
        path.moveTo(x, y + h * 0.95f)
        path.cubicTo(x - h * 1.7f, y - h * 0.05f, x - h * 0.75f, y - h * 1.25f, x, y - h * 0.35f)
        path.cubicTo(x + h * 0.75f, y - h * 1.25f, x + h * 1.7f, y - h * 0.05f, x, y + h * 0.95f)
        path.close()
        fill.color = HEART
        c.drawPath(path, fill)
        clip.set(path)
        val save = c.save()
        c.clipPath(clip)
        for (k in 0 until 60) {
            // Fixed speckles from a hash, so the grain doesn't crawl.
            val gx = x + (hashf(k * 7 + 1) - 0.5f) * size * 1.1f
            val gy = y + (hashf(k * 13 + 5) - 0.5f) * size
            val light = k % 3 != 0
            fill.color = if (light) Color.WHITE else hex("#d9606d")
            fill.alpha = if (light) 60 else 45
            c.drawCircle(gx, gy, 0.012f + 0.01f * hashf(k * 3 + 11), fill)
        }
        fill.alpha = 255
        c.restoreToCount(save)
    }

    private fun hashf(i: Int): Float {
        var h = i * 374761393 + 668265263
        h = (h xor (h ushr 13)) * 1274126177
        return ((h xor (h ushr 16)) and 0xffff) / 65535f
    }
}
