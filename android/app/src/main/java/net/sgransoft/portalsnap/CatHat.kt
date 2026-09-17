package net.sgransoft.portalsnap

import android.content.res.AssetManager
import android.graphics.RadialGradient
import android.graphics.Shader
import android.opengl.Matrix
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// Cat Hat: a kitten lying on top of your head, the Portal's old AR cat. It rides the head like a
// hat, but it isn't glued there: tilt your head and it leans the other way to stay upright,
// grips with its front paws and lets its hind legs take the weight; move quickly and it sways
// behind the motion and wobbles back; lean forward and it sits back. Its head keeps level and
// keeps looking at you when you turn. Between all that it breathes, blinks (sometimes twice,
// sometimes one slow blink), flicks an ear, swings its tail, glances about and now and then
// kneads with its front paws. A tap startles it into a little hop.
//
// The kitten is a real textured 3D model (Cat3D.kt); this file only decides how it sits and moves.

object CatHat : Filter("cathat", "Cat Hat", "🐈", Mode.MESH) {
    override val usesUnder = true

    /** The app's assets, for the model; set by MainActivity like Places'. */
    @Volatile var assets: AssetManager? = null

    // Kitten units per face unit of head width: the kitten is a little wider than the temples,
    // its hind legs reaching round the sides.
    private const val SIZE = 0.115f
    // Where the kitten's belly touches down, in its own units.
    private const val SEAT_Y = -1.7f
    private const val SEAT_Z = 0.4f

    private class Kitty(now: Long) {
        var seen = now
        // Head motion, in face units, smoothed.
        var px = Float.NaN
        var py = 0f
        var vx = 0f
        var vy = 0f
        var ax = 0f
        var ay = 0f
        var lastAngle = 0f
        var spin = 0f
        var pitchBase = Float.NaN
        /** The head's own nod, radians, positive tipping its top toward the camera. */
        var pitch = 0f
        // Springs: lean (roll against the head, radians), tip (pitch, radians), hop (kitten units up).
        var lean = 0f
        var leanV = 0f
        var tip = 0f
        var tipV = 0f
        var hop = 0f
        var hopV = 0f
        var look = 0f
        var tail = 0f
        var tailV = 0f
        // Fright, 0..1: ears back, tail busy.
        var alarm = 0f
        var calm = 0f
        // Life.
        var blinkAt = now + 800L
        var blinkKind = 0
        var twitchAt = now + 2500L
        var twitchEar = 0
        var glanceAt = now + 3000L
        var glanceTo = 0f
        var glanceTilt = 0f
        var kneadAt = now + 9000L
        val phase = (now % 10000) / 1000f
        var rig: CatRig? = null
        var cat: Cat3D? = null
        /** Frame px: seat x, y; left paw; right paw; one kitten unit along x from the seat; scratch. */
        val shadow = FloatArray(10)
    }

    private val kitties = HashMap<Int, Kitty>()
    @Volatile private var pokedAt = 0L

    private var cat: CatModel? = null
    private val ix = HashMap<String, Int>()

    /** A bone's index by name. */
    private fun b(name: String) = ix.getOrPut(name) { cat!!.bone(name) }

    override fun poke() {
        pokedAt = System.currentTimeMillis()
    }

    override fun update(d: Draw, faces: List<Face>) {
        val model = CatModel.get(assets) ?: return
        cat = model
        val dt = min(d.dt, 50f) / 1000f
        val poked = pokedAt != 0L
        pokedAt = 0L
        for (f in faces) {
            val k = kitties.getOrPut(f.id) { Kitty(d.t) }
            k.seen = d.t
            if (poked) {
                k.hopV = 7f
                k.alarm = 1f
                k.blinkAt = d.t + 900
                k.blinkKind = 1
            }
            move(f, k, dt)
            k.cat = pose(d, f, k, model)
        }
        kitties.entries.removeAll { d.t - it.value.seen > 1500 }
    }

    // The head's motion, and the springs that answer it.
    private fun move(f: Face, k: Kitty, dt: Float) {
        val unit = unitOf(f)
        val x = f.cx / unit
        val y = f.cy / unit
        if (k.px.isNaN()) {
            k.px = x
            k.py = y
            k.lastAngle = f.angle
        }
        val nvx = (x - k.px) / max(dt, 1e-3f)
        val nvy = (y - k.py) / max(dt, 1e-3f)
        val lp = min(1f, dt * 14f)
        val nax = (nvx - k.vx) / max(dt, 1e-3f)
        val nay = (nvy - k.vy) / max(dt, 1e-3f)
        k.vx += (nvx - k.vx) * lp
        k.vy += (nvy - k.vy) * lp
        k.ax += (nax.coerceIn(-80f, 80f) - k.ax) * min(1f, dt * 8f)
        k.ay += (nay.coerceIn(-80f, 80f) - k.ay) * min(1f, dt * 8f)
        k.px = x
        k.py = y
        val w = (f.angle - k.lastAngle) / max(dt, 1e-3f)
        k.lastAngle = f.angle
        k.spin += (w - k.spin) * lp

        // Fright comes from sudden motion and fades over a couple of seconds.
        val jolt = max(abs(k.spin) / 3f, hypot(k.vx, k.vy) / 9f)
        k.alarm = max(k.alarm - dt * 0.5f, ((jolt - 0.5f) * 1.5f).coerceIn(0f, 1f))
        // Calm builds while the head is still, and makes the kitten sleepy-eyed.
        k.calm = if (jolt < 0.15f && k.alarm < 0.05f) min(1f, k.calm + dt / 12f) else max(0f, k.calm - dt * 2f)

        // Lean the other way from the head's tilt, most of the way, and sway behind the motion.
        val leanTo = (-f.angle * 0.8f - k.ax * 0.012f).coerceIn(-0.85f, 0.85f)
        val leanA = (leanTo - k.lean) * 55f - k.leanV * 6.5f
        k.leanV += leanA * dt
        k.lean += k.leanV * dt

        // Sit back as the head tips forward.
        k.pitch = headPitch(f, k, dt)
        val tipTo = (-k.pitch * 0.7f).coerceIn(-0.6f, 0.6f)
        val tipA = (tipTo - k.tip) * 45f - k.tipV * 6f
        k.tipV += tipA * dt
        k.tip += k.tipV * dt

        // Lifted a little when the head drops fast, and settling back with a bounce.
        val hopA = ((-k.ay * 0.02f).coerceIn(0f, 1.2f) - k.hop) * 70f - k.hopV * 7f
        k.hopV += hopA * dt
        k.hop = max(0f, k.hop + k.hopV * dt)
        if (k.hop == 0f && k.hopV < 0f) k.hopV *= -0.3f

        // Keep its face toward the camera when the head turns, a beat behind.
        val turn = Math.toRadians((f.turn ?: (-f.yaw * 28f)).toDouble()).toFloat()
        k.look += (-turn * 0.6f - k.look) * min(1f, dt * 4f)

        // The tail swings out behind sideways motion.
        val tailA = ((-k.vx * 0.04f).coerceIn(-0.8f, 0.8f) - k.tail) * 25f - k.tailV * 3f
        k.tailV += tailA * dt
        k.tail += k.tailV * dt
    }

    private fun headPitch(f: Face, k: Kitty, dt: Float): Float {
        val p = f.pitch ?: return 0f
        if (k.pitchBase.isNaN()) k.pitchBase = p
        k.pitchBase += (p - k.pitchBase) * min(1f, dt * 0.3f)
        // Tipping forward reads negative here (see Lemonade); positive tips the top toward you.
        return Math.toRadians(-(p - k.pitchBase).toDouble()).toFloat().coerceIn(-0.6f, 0.6f)
    }

    // Eye distance is measured flat, so a turned head's is short by the cosine of the turn.
    private fun unitOf(f: Face): Float {
        val turn = f.turn ?: (-f.yaw * 28f)
        return f.eyeDist / max(0.6f, cos(Math.toRadians(turn.toDouble()).toFloat()))
    }

    /* ------------------------------ pose ------------------------------ */

    private fun pose(d: Draw, f: Face, k: Kitty, cat: CatModel): Cat3D {
        val rig = k.rig ?: CatRig(cat).also { k.rig = it }
        rig.reset()
        val t = (d.t % 3_600_000L) / 1000f + k.phase

        // Balance: a quarter of the lean happens where it lies, the rest up the spine; its head
        // stays nearly level and the front legs turn back so the paws keep their grip.
        val lean = k.lean
        rig.rz[b("spine")] = lean * 0.25f
        rig.rz[b("chest")] = lean * 0.25f
        rig.rz[b("neck")] = lean * 0.15f
        val bodyRoll = f.angle + lean * 0.9f
        rig.rz[b("head")] = -bodyRoll * 0.55f
        for (s in SIDES) {
            rig.rz[b("shoulder$s")] = -lean * 0.6f
            rig.rz[b("hip$s")] = -lean * 0.2f
        }
        // The downhill paws dig in further while it leans.
        val grip = (abs(lean) * 1.2f).coerceAtMost(0.5f)
        rig.rx[b(if (lean > 0) "pawL" else "pawR")] += -grip
        rig.rx[b(if (lean > 0) "shoulderL" else "shoulderR")] += grip * 0.4f

        val tip = k.tip
        rig.rx[b("spine")] += tip * 0.25f
        rig.rx[b("chest")] += tip * 0.25f
        rig.rx[b("neck")] += tip * 0.2f
        rig.rx[b("head")] += -(tip * 0.9f) * 0.4f
        for (s in SIDES) rig.rx[b("shoulder$s")] += -tip * 0.5f

        // Breathing.
        val breath = sin(t * TAU / 2.6f)
        rig.rx[b("spine")] += breath * 0.02f
        rig.rx[b("chest")] -= breath * 0.012f
        rig.rx[b("neck")] += breath * 0.008f

        // Looking: back toward the camera as the head turns, plus a glance now and then.
        glance(d, k)
        val g = glanceAmount(d.t, k)
        rig.ry[b("neck")] = k.look * 0.4f + g * k.glanceTo * 0.4f
        rig.ry[b("head")] = k.look * 0.6f + g * k.glanceTo * 0.6f
        rig.rz[b("head")] += g * k.glanceTilt

        // Ears: back when frightened, and a flick now and then.
        val flick = twitch(d, k)
        for ((i, s) in SIDES.withIndex()) {
            val out = if (s == "L") -1f else 1f
            rig.rx[b("ear$s")] = -k.alarm * 0.55f
            rig.rz[b("ear$s")] = out * (k.alarm * 0.3f + if (k.twitchEar == i) flick * 0.45f else 0f)
        }

        // Tail: a slow lazy swing, quicker and wider when frightened, thrown about by motion.
        val swing = 0.16f + k.alarm * 0.25f
        val speed = 1.3f + k.alarm * 3.5f
        rig.ry[b("tail0")] = sin(t * speed) * swing * 0.5f + k.tail * 0.5f
        for (i in 1..4) {
            rig.rz[b("tail$i")] = sin(t * speed - i * 0.7f) * swing + k.tail * (0.3f + i * 0.1f)
        }

        // Kneading: every so often, left, right, left, right.
        val knead = knead(d, k)
        if (knead > 0f) {
            val beat = sin((d.t - k.kneadAt) / 1000f * TAU * 1.4f)
            rig.rx[b("pawL")] += max(0f, beat) * 0.45f * knead
            rig.rx[b("pawR")] += max(0f, -beat) * 0.45f * knead
            rig.rx[b("elbowL")] += max(0f, beat) * 0.2f * knead
            rig.rx[b("elbowR")] += max(0f, -beat) * 0.2f * knead
        }

        rig.shift[1] = -k.hop

        val c = Cat3D(cat.boneCount)
        rig.solve(c.skin)
        blink(d, k, c)

        // Onto the head: its top, a little back from the forehead, turned and tipped with it.
        val unit = unitOf(f)
        val S = f.headSpan
        val turn = f.turn ?: (-f.yaw * 28f)
        val top = headTop(f)
        val m = c.model
        Matrix.setIdentityM(m, 0)
        Matrix.translateM(m, 0, f.cx, f.cy, 0f)
        Matrix.rotateM(m, 0, deg(f.angle), 0f, 0f, 1f)
        Matrix.rotateM(m, 0, turn, 0f, 1f, 0f)
        Matrix.rotateM(m, 0, deg(k.pitch), 1f, 0f, 0f)
        Matrix.scaleM(m, 0, unit, unit, unit)
        Matrix.translateM(m, 0, 0f, top.y, top.z)
        // The part of the lean that happens where it lies.
        Matrix.rotateM(m, 0, deg(lean * 0.25f), 0f, 0f, 1f)
        Matrix.rotateM(m, 0, deg(tip * 0.3f), 1f, 0f, 0f)
        val size = S * SIZE
        Matrix.scaleM(m, 0, size, size, size)
        Matrix.translateM(m, 0, 0f, -SEAT_Y, -SEAT_Z)

        val h = c.head
        Matrix.setIdentityM(h, 0)
        Matrix.translateM(h, 0, f.cx, f.cy, 0f)
        Matrix.rotateM(h, 0, deg(f.angle), 0f, 0f, 1f)
        Matrix.rotateM(h, 0, turn, 0f, 1f, 0f)
        Matrix.rotateM(h, 0, deg(k.pitch), 1f, 0f, 0f)
        Matrix.scaleM(h, 0, unit, unit, unit)
        Matrix.translateM(h, 0, 0f, top.cy, HEAD_Z)
        Matrix.scaleM(h, 0, S * 0.47f, top.ry, HEAD_DEPTH)

        shadow(k, c, rig)
        return c
    }

    private class Top(val y: Float, val z: Float, val cy: Float, val ry: Float)

    // The head as an ellipsoid in face units: from a little above the top of the forehead (for
    // the skull and some hair) down to the chin, HEAD_DEPTH deep behind its front. The kitten
    // lies where that ellipsoid's top is at SEAT_DEPTH.
    private fun headTop(f: Face): Top {
        val topY = f.headTopY - f.headSpan * 0.04f
        val chin = f["chin"]?.y ?: 1.36f
        val cy = (topY + chin) / 2
        val ry = (chin - topY) / 2
        val dz = (SEAT_DEPTH - HEAD_Z) / HEAD_DEPTH
        val y = cy - ry * kotlin.math.sqrt(max(0f, 1 - dz * dz))
        return Top(y, SEAT_DEPTH, cy, ry)
    }

    // Set back from the real forehead, so its front slopes away under the kitten's chest instead
    // of cutting through it, while still hiding the hind feet and tail behind the head.
    private const val HEAD_Z = 1.6f
    private const val HEAD_DEPTH = 1.5f
    private const val SEAT_DEPTH = 0.75f

    /* ------------------------------ life ------------------------------ */

    private fun blink(d: Draw, k: Kitty, c: Cat3D) {
        val since = d.t - k.blinkAt
        var shut = 0f
        if (since >= 0) {
            val ms = since.toFloat()
            shut = when (k.blinkKind) {
                // A slow blink: a cat's way of saying it's comfortable.
                2 -> when {
                    ms < 350 -> ms / 350
                    ms < 800 -> 1f
                    ms < 1300 -> 1 - (ms - 800) / 500
                    else -> -1f
                }
                // Two quick ones.
                1 -> when {
                    ms < 220 -> quick(ms)
                    ms < 260 -> 0f
                    else -> quick(ms - 260)
                }
                else -> quick(ms)
            }
            if (shut < 0f) {
                shut = 0f
                k.blinkKind = when {
                    k.calm > 0.6f && RNG.nextFloat() < 0.5f -> 2
                    RNG.nextFloat() < 0.2f -> 1
                    else -> 0
                }
                k.blinkAt = d.t + 1800 + RNG.nextInt(3500) - (k.alarm * 1200).toLong()
            }
        }
        // Heavy-lidded when it's been still a while; wide awake when startled.
        c.blink = shut
        c.droop = k.calm * 0.3f * (1 - k.alarm)
    }

    private fun quick(ms: Float) = when {
        ms < 0 -> 0f
        ms < 70 -> ms / 70
        ms < 110 -> 1f
        ms < 220 -> 1 - (ms - 110) / 110
        else -> -1f
    }

    private fun twitch(d: Draw, k: Kitty): Float {
        val ms = (d.t - k.twitchAt).toFloat()
        if (ms < 0) return 0f
        if (ms > 420) {
            k.twitchEar = RNG.nextInt(2)
            k.twitchAt = d.t + 2500 + RNG.nextInt(6000)
            return 0f
        }
        // Out fast, back slower, with a second small flick.
        return if (ms < 80) ms / 80 else sin((ms - 80) / 340f * PI.toFloat() * 1.5f).let { abs(it) } * (1 - (ms - 80) / 340f)
    }

    private fun glance(d: Draw, k: Kitty) {
        if (d.t - k.glanceAt < 2600) return
        if (RNG.nextFloat() > 0.02f) return
        k.glanceAt = d.t
        k.glanceTo = (RNG.nextFloat() - 0.5f) * 0.9f
        k.glanceTilt = (RNG.nextFloat() - 0.5f) * 0.35f
    }

    // 0 to 1 and back over a glance: turn, hold, come back.
    private fun glanceAmount(now: Long, k: Kitty): Float {
        val ms = (now - k.glanceAt).toFloat()
        return when {
            ms < 0 -> 0f
            ms < 350 -> smooth(ms / 350)
            ms < 1500 -> 1f
            ms < 2000 -> 1 - smooth((ms - 1500) / 500)
            else -> 0f
        }
    }

    private fun knead(d: Draw, k: Kitty): Float {
        val ms = (d.t - k.kneadAt).toFloat()
        if (ms < 0) return 0f
        if (ms > 2900 || k.alarm > 0.3f) {
            k.kneadAt = d.t + 12000 + RNG.nextInt(12000)
            return 0f
        }
        return min(1f, min(ms / 300, (2900 - ms) / 300))
    }

    private fun smooth(x: Float) = x.coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }

    /* ------------------------------ drawing ------------------------------ */

    private val v4 = FloatArray(4)
    private val w4 = FloatArray(4)
    private val px = FloatArray(2)
    private val joint = FloatArray(3)

    // Where the soft shadows on the head go: under the body and under each front paw.
    private fun shadow(k: Kitty, c: Cat3D, rig: CatRig) {
        fun at(i: Int, x: Float, y: Float, z: Float) {
            v4[0] = x
            v4[1] = y
            v4[2] = z
            v4[3] = 1f
            Matrix.multiplyMV(w4, 0, c.model, 0, v4, 0)
            View3D.project(w4[0], w4[1], w4[2], px)
            k.shadow[i] = px[0]
            k.shadow[i + 1] = px[1]
        }
        at(0, 0f, SEAT_Y, SEAT_Z)
        // Just below each paw, where it presses into the forehead.
        rig.joint(b("pawL"), joint)
        at(2, joint[0], joint[1] + 1.5f, joint[2] + 0.3f)
        rig.joint(b("pawR"), joint)
        at(4, joint[0], joint[1] + 1.5f, joint[2] + 0.3f)
        at(6, 1f, SEAT_Y, SEAT_Z)

        // Where on the frame it is: round every joint, padded by the kitten's thickness.
        val box = c.bounds
        box[0] = Float.MAX_VALUE
        box[1] = Float.MAX_VALUE
        box[2] = -Float.MAX_VALUE
        box[3] = -Float.MAX_VALUE
        for (i in 0 until rig.boneCount) {
            rig.joint(i, joint)
            at(8, joint[0], joint[1], joint[2])
            box[0] = min(box[0], k.shadow[8])
            box[1] = min(box[1], k.shadow[9])
            box[2] = max(box[2], k.shadow[8])
            box[3] = max(box[3], k.shadow[9])
        }
        val pad = hypot(k.shadow[6] - k.shadow[0], k.shadow[7] - k.shadow[1]) * 3.2f
        box[0] -= pad
        box[1] -= pad
        box[2] += pad
        box[3] += pad
    }

    override fun under(d: Draw, f: Face) {
        val k = kitties[f.id] ?: return
        if (k.cat == null) return
        val s = k.shadow
        val unitPx = hypot(s[6] - s[0], s[7] - s[1])
        val fade = 1f / (1f + k.hop * 0.8f)
        val c = d.c
        val save = c.save()
        c.translate(s[0], s[1])
        c.rotate(deg(f.angle))
        c.scale(1f, 0.38f)
        val r = unitPx * 4.6f
        c.drawCircle(0f, 0f, r, d.pen.fill(RadialGradient(
            0f, 0f, r, intArrayOf(rgba(0, 0, 0, 0.38f * fade), rgba(0, 0, 0, 0.2f * fade), rgba(0, 0, 0, 0f)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )))
        c.restoreToCount(save)
        for (i in intArrayOf(2, 4)) {
            val pr = unitPx * 1.5f
            val save2 = c.save()
            c.translate(s[i], s[i + 1])
            c.rotate(deg(f.angle))
            c.scale(1f, 0.6f)
            c.drawCircle(0f, 0f, pr, d.pen.fill(RadialGradient(
                0f, 0f, pr, intArrayOf(rgba(0, 0, 0, 0.42f * fade), rgba(0, 0, 0, 0.18f * fade), rgba(0, 0, 0, 0f)),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
            )))
            c.restoreToCount(save2)
        }
    }

    override fun draw(d: Draw, f: Face) {
        kitties[f.id]?.cat?.let { d.cats += it }
    }

    private val SIDES = arrayOf("L", "R")
    private val RNG = java.util.Random()
}
