package net.sgran.portalsnap

import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.opengl.Matrix
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// Photo Booth effect 8: a glass of pink lemonade made of your head, rendered as a real 3D glass
// (Glass3D.kt). The glass chases your head around the screen on a spring, leans against its own
// motion, and tips, turns and tilts with your head. The lemonade inside stays level as the glass
// tips and leans when the glass accelerates. Your features float in its middle and your face
// stretches out to fill it, which falls out of where each point sits around the glass as seen
// from the camera. Rounded ice cubes float at the surface, catching the light and reflecting
// your face; they slosh as the glass moves and clink off the wall and each other. There's a
// dark-red straw and a lemon slice on the rim. Pucker (or open wide) to blow bubbles up the
// straw with a bloop. When tracking drops, the glass goes and only the background stays. Every
// sound is baked into clips.
//
// The look follows the original (reference video 919589313478329): a slim tumbler with thick
// clear walls, a rounded lip and a heavy base, filled almost to the rim.
object Lemonade : Filter("lemonade", "Lemonade", "🍋", Mode.MESH, voice = 0.9f) {
    override val usesUnder = true
    override val coversCamera = true
    override val keepsScene = true

    // Tipped toward you so the top of the lemonade shows with the head level. It has to beat
    // the camera looking up at a glass that sits above its eye line: 12° left the surface edge-on.
    private const val BASE_TIP_DEG = 24f
    // Glass units (GlassShape): the liquid's top radius is 1, y down, z away.
    private const val STRAW_FOOT_X = 0.45f
    private const val STRAW_FOOT_Z = 0.35f
    // How far out an ice cube's edge can reach in the round glass.
    private const val WALL = 0.95f
    // How far the surface leans per px/s² of the glass's acceleration.
    private const val SLOSH = 0.00005f

    // x in glass units; y in half-heights, from -1 (surface) to 1 (floor).
    private class Bubble(var x: Float, var y: Float, val r: Float, val vy: Float, val phase: Float)

    private class Cube(var x: Float, var z: Float, val size: Float, var spin: Float) {
        var vx = 0f
        var vz = 0f
        var vs = 0f
    }

    private class State(var x: Float, var y: Float) {
        // The glass on its spring, in frame px, with its smoothed acceleration for the slosh.
        var vx = 0f
        var vy = 0f
        var ax = 0f
        var ay = 0f
        // Its pose: roll in radians, yaw and pitch in degrees.
        var roll = 0f
        var yaw = 0f
        var pitch = 0f
        var pitchBase = Float.NaN
        var straw = 0f
        var strawV = 0f
        val cubes = arrayListOf(Cube(-0.35f, -0.2f, 0.26f, 0.3f), Cube(0.3f, 0.2f, 0.24f, -0.4f), Cube(-0.05f, 0.45f, 0.2f, 0.9f))
        val bubbles = ArrayList<Bubble>()
        var fizzDebt = 0f
        var blowDebt = 0f
        var nextBloop = 0L
        var nextClink = 0L
        var seen = 0L
    }

    private val states = HashMap<Int, State>()
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val v4 = FloatArray(4)
    private val w4 = FloatArray(4)
    private val pa = FloatArray(3)
    private val pb = FloatArray(3)
    private val pw = FloatArray(3)
    private val screen = FloatArray(2)
    private val shadowModel = FloatArray(16)

    private fun glassW(d: Draw, count: Int) = d.h * 0.19f * (if (count <= 1) 1f else 0.8f)

    /* ------------------------------ motion ------------------------------ */

    override fun update(d: Draw, faces: List<Face>) {
        val dt = min(d.dt, 50f) / 1000f
        if (faces.isEmpty()) {
            // Nobody in view: the glass goes, and only the background stays up.
            states.clear()
            return
        }
        for (f in faces) {
            val W = glassW(d, f.count)
            val s = states.getOrPut(f.id) { State(f.cx, f.cy) }
            s.seen = d.t
            move(d, f, s, W, dt)
            slosh(d, s, W, dt)
            fizz(d, f, s, dt)
        }
        if (states.size > 4) states.entries.removeAll { d.t - it.value.seen > 3000 }
    }

    // The glass chases the head.
    private fun move(d: Draw, f: Face, s: State, W: Float, dt: Float) {
        val h = W * GlassShape.H
        val tx = f.cx.coerceIn(W * 1.6f, d.w - W * 1.6f)
        // Low enough that the straw and lemon above the rim stay on screen.
        val ty = (f.cy + h * 0.1f).coerceIn(h * 2.1f, d.h - h * 1.35f)
        // Tilt with the head, and lean back against the motion like something being carried.
        val roll = (f.angle - s.vx * 0.0006f).coerceIn(-0.6f, 0.6f)
        // Nod relative to where the head usually sits. Tipping the head forward reads negative
        // here, and tips the glass's top toward you.
        var pitch = 0f
        f.pitch?.let { p ->
            if (s.pitchBase.isNaN()) s.pitchBase = p
            s.pitchBase += (p - s.pitchBase) * min(1f, dt * 0.3f)
            pitch = (p - s.pitchBase) * 1.2f
        }
        // Turned against the head's yaw: the screen is a mirror, so this turns the glass the way
        // you see yourself turn.
        spring(s, tx, ty, roll, -f.yaw * 30f, pitch, W, dt)
    }

    // On a spring, so it swings in after its target and overshoots a little.
    private fun spring(s: State, tx: Float, ty: Float, roll: Float, yaw: Float, pitch: Float, W: Float, dt: Float) {
        val ax = (tx - s.x) * 40f - s.vx * 9f
        val ay = (ty - s.y) * 40f - s.vy * 9f
        s.vx += ax * dt
        s.vy += ay * dt
        s.x += s.vx * dt
        s.y += s.vy * dt
        s.ax += (ax - s.ax) * min(1f, dt * 10f)
        s.ay += (ay - s.ay) * min(1f, dt * 10f)
        s.roll += (roll - s.roll) * min(1f, dt * 8f)
        s.yaw += (yaw - s.yaw) * min(1f, dt * 6f)
        val pitchTarget = (pitch + s.vy * 0.02f).coerceIn(-24f, 24f)
        s.pitch += (pitchTarget - s.pitch) * min(1f, dt * 6f)
        // The straw sways on its own spring, pushed by the glass's acceleration.
        val strawAcc = -s.ax / W * 0.15f - s.straw * 40f - s.strawV * 6f
        s.strawV += strawAcc * dt
        s.straw = (s.straw + s.strawV * dt).coerceIn(-0.35f, 0.35f)
    }

    // Ice floats at the top of a round glass. It slides with the tilt, lags behind the glass's
    // acceleration, bounces off the wall and other cubes, and clinks when it hits hard.
    private fun slosh(d: Draw, s: State, W: Float, dt: Float) {
        val gx = sin(s.roll) * 2.5f - s.ax / W * 0.08f
        val gz = sin(Math.toRadians(s.pitch.toDouble()).toFloat()) * 2.5f - s.ay / W * 0.05f
        val drag = max(0f, 1f - 1.6f * dt)
        for (c in s.cubes) {
            c.vx = (c.vx + gx * dt) * drag
            c.vz = (c.vz + gz * dt) * drag
            c.x += c.vx * dt
            c.z += c.vz * dt
            c.vs = (c.vs + c.vx * dt * 6f) * drag
            c.spin += c.vs * dt
            // A turned cube's corners reach past its radius, so keep its centre well in.
            val lim = WALL - c.size * 1.25f
            val r = hypot(c.x, c.z)
            if (r > lim) {
                val nx = c.x / r
                val nz = c.z / r
                c.x = nx * lim
                c.z = nz * lim
                val vn = c.vx * nx + c.vz * nz
                if (vn > 0f) {
                    c.vx -= 1.5f * vn * nx
                    c.vz -= 1.5f * vn * nz
                    clink(d, s, vn)
                }
            }
        }
        for (i in s.cubes.indices) {
            for (j in i + 1 until s.cubes.size) {
                val a = s.cubes[i]
                val b = s.cubes[j]
                val dx = b.x - a.x
                val dz = b.z - a.z
                val dist = hypot(dx, dz)
                val reach = a.size + b.size
                if (dist >= reach || dist < 1e-4f) continue
                val nx = dx / dist
                val nz = dz / dist
                val push = (reach - dist) / 2
                a.x -= nx * push
                a.z -= nz * push
                b.x += nx * push
                b.z += nz * push
                // Equal masses: swap the closing speed along the line between them.
                val vn = (a.vx - b.vx) * nx + (a.vz - b.vz) * nz
                if (vn > 0f) {
                    a.vx -= vn * nx
                    a.vz -= vn * nz
                    b.vx += vn * nx
                    b.vz += vn * nz
                    clink(d, s, vn)
                }
            }
        }
    }

    private fun clink(d: Draw, s: State, speed: Float) {
        if (speed < 0.35f || d.t < s.nextClink) return
        s.nextClink = d.t + 70
        Sfx.play("clink" + (1 + rng.nextInt(3)), (speed * 0.5f).coerceIn(0.15f, 0.8f), rnd(0.9f, 1.15f))
    }

    private fun fizz(d: Draw, f: Face, s: State, dt: Float) {
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
                s.bubbles += Bubble(STRAW_FOOT_X + rnd(-0.06f, 0.06f), 0.7f, rnd(0.03f, 0.08f), rnd(0.9f, 1.5f), rnd(0f, TAU))
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
            if (b.y < -0.95f) it.remove()
        }
        if (s.bubbles.size > 160) s.bubbles.subList(0, s.bubbles.size - 160).clear()
    }

    /* ------------------------------ posing ------------------------------ */

    // Glass units to world: to the glass's spot, tilted with the head, tipped (from a little
    // above, plus the nod), turned, and scaled so a unit is the liquid's top radius in px.
    private fun pose(m: FloatArray, s: State, W: Float) {
        Matrix.setIdentityM(m, 0)
        Matrix.translateM(m, 0, s.x, s.y, 0f)
        Matrix.rotateM(m, 0, deg(s.roll), 0f, 0f, 1f)
        Matrix.rotateM(m, 0, BASE_TIP_DEG - s.pitch, 1f, 0f, 0f)
        Matrix.rotateM(m, 0, s.yaw, 0f, 1f, 0f)
        Matrix.scaleM(m, 0, W, W, W)
    }

    private fun world(m: FloatArray, x: Float, y: Float, z: Float, out: FloatArray) {
        v4[0] = x
        v4[1] = y
        v4[2] = z
        v4[3] = 1f
        Matrix.multiplyMV(w4, 0, m, 0, v4, 0)
        out[0] = w4[0]
        out[1] = w4[1]
        out[2] = w4[2]
    }

    // Across the glass, tipping with it, and leaning away from the glass's acceleration as a drink
    // does. A truly level surface hid itself: the glass sits above the camera's eye line, so level
    // liquid is seen from underneath.
    private fun surface(g: Glass3D, s: State) {
        world(g.model, 0f, GlassShape.LEVEL, 0f, g.planePoint)
        world(g.model, 0f, GlassShape.LEVEL - 1f, 0f, pa)
        var nx = pa[0] - g.planePoint[0]
        val ny = pa[1] - g.planePoint[1]
        val nz = pa[2] - g.planePoint[2]
        val up = sqrt(nx * nx + ny * ny + nz * nz)
        nx = nx / up + (s.ax * SLOSH).coerceIn(-0.2f, 0.2f)
        val len = sqrt(nx * nx + (ny / up) * (ny / up) + (nz / up) * (nz / up))
        g.planeNormal[0] = nx / len
        g.planeNormal[1] = ny / up / len
        g.planeNormal[2] = nz / up / len
    }

    // The glass-unit y where the surface crosses the glass's vertical line through (x, z).
    private fun surfaceY(g: Glass3D, x: Float, z: Float): Float {
        world(g.model, x, 0f, z, pa)
        world(g.model, x, 1f, z, pb)
        val n = g.planeNormal
        val along = (pb[0] - pa[0]) * n[0] + (pb[1] - pa[1]) * n[1] + (pb[2] - pa[2]) * n[2]
        if (abs(along) < 1e-4f) return GlassShape.LEVEL
        val gap = (g.planePoint[0] - pa[0]) * n[0] + (g.planePoint[1] - pa[1]) * n[1] + (g.planePoint[2] - pa[2]) * n[2]
        return gap / along
    }

    // Floating: mostly under the surface, a corner and a top standing out, tipped a little.
    private fun cubes(g: Glass3D, s: State) {
        for (c in s.cubes) {
            val m = FloatArray(16)
            System.arraycopy(g.model, 0, m, 0, 16)
            val half = c.size * 0.72f
            Matrix.translateM(m, 0, c.x, surfaceY(g, c.x, c.z) - half * 0.1f, c.z)
            Matrix.rotateM(m, 0, deg(c.spin), 0f, 1f, 0f)
            Matrix.rotateM(m, 0, 18f + 8f * sin(c.spin * 0.7f), 1f, 0f, 1f)
            Matrix.scaleM(m, 0, half, half, half)
            g.cubes += m
        }
    }

    // From the floor at the back, leaning out and up past the rim, swaying on its spring.
    private fun straw(g: Glass3D, s: State) {
        val m = g.straw
        System.arraycopy(g.model, 0, m, 0, 16)
        Matrix.translateM(m, 0, STRAW_FOOT_X, GlassShape.H * 0.7f, STRAW_FOOT_Z)
        Matrix.rotateM(m, 0, deg(0.09f + s.straw), 0f, 0f, 1f)
        Matrix.scaleM(m, 0, 0.055f, GlassShape.H * 2.4f, 0.055f)
    }

    // Slotted onto the rim at the front left, standing edge-on to the rim so it faces you.
    private fun lemon(g: Glass3D) {
        val m = g.lemon
        System.arraycopy(g.model, 0, m, 0, 16)
        val r = GlassShape.innerRadius(GlassShape.RIM) + GlassShape.WALL * 0.5f
        Matrix.translateM(m, 0, -r * 0.94f, GlassShape.RIM + 0.02f, -r * 0.34f)
        Matrix.rotateM(m, 0, -20f, 0f, 1f, 0f)
        Matrix.rotateM(m, 0, 90f, 1f, 0f, 0f)
        Matrix.scaleM(m, 0, 0.42f, 0.42f, 0.42f)
    }

    /* ------------------------------ drawing ------------------------------ */

    // A warm glow that wanders slowly over a pink-to-orange ground. It stays up on its own
    // while nobody is in view (keepsScene).
    override fun scene(d: Draw, faces: List<Face>) {
        val c = d.c
        val p = d.pen
        c.drawRect(0f, 0f, d.w, d.h, p.fill(LinearGradient(0f, 0f, d.w, d.h, hex("#ff5a9e"), hex("#ff8a5c"), Shader.TileMode.CLAMP)))
        val t = (d.t % 1_000_000L).toFloat()
        val gx = d.w * (0.5f + 0.35f * sin(t / 4100f))
        val gy = d.h * (0.5f + 0.3f * cos(t / 5300f))
        c.drawRect(0f, 0f, d.w, d.h, p.fill(RadialGradient(gx, gy, d.w * 0.55f, rgba(255, 176, 60, 0.85f), rgba(255, 176, 60, 0f), Shader.TileMode.CLAMP)))
    }

    // A soft shadow on the ground under the glass's base, which stays flat.
    override fun under(d: Draw, f: Face) {
        val s = states[f.id] ?: return
        val W = glassW(d, f.count)
        pose(shadowModel, s, W)
        world(shadowModel, 0f, GlassShape.BASE, 0f, pw)
        if (!View3D.project(pw[0], pw[1], pw[2], screen)) return
        val c = d.c
        val x = screen[0]
        val y = min(d.h * 0.97f, screen[1] + W * 0.35f)
        val save = c.save()
        c.scale(1f, 0.18f, x, y)
        c.drawCircle(x, y, W * 1.25f, d.pen.fill(RadialGradient(x, y, W * 1.25f, rgba(120, 30, 60, 0.28f), rgba(120, 30, 60, 0f), Shader.TileMode.CLAMP)))
        c.restoreToCount(save)
    }

    override fun draw(d: Draw, f: Face) {
        val s = states[f.id] ?: return
        val W = glassW(d, f.count)
        val g = Glass3D()
        pose(g.model, s, W)
        // The middle of the features: on the eye line's centre, a little under halfway to the
        // mouth. The glass's edges reach the cheeks, brow and chin, never past the skin.
        val centre = toPixels(f, 0f, f.mouth.y * 0.45f)
        g.hasFace = true
        g.faceX = centre.x
        g.faceY = centre.y
        g.reachX = f.eyeDist * 0.7f
        g.reachY = f.eyeDist * 0.85f
        g.roll = f.angle
        g.phase = (d.t % 100_000L) / 1000f * 3f
        surface(g, s)
        cubes(g, s)
        straw(g, s)
        lemon(g)
        d.glasses += g
        bubbles(d, s, g, W)
    }

    // Bubbles show on the near side of the lemonade: placed on the liquid's front surface in 3D,
    // then drawn flat over the 3D pass.
    private fun bubbles(d: Draw, s: State, g: Glass3D, W: Float) {
        val c = d.c
        val n = g.planeNormal
        for (b in s.bubbles) {
            val y = b.y * GlassShape.H
            val rin = GlassShape.innerRadius(y) * 0.97f
            if (abs(b.x) >= rin) continue
            world(g.model, b.x, y, -sqrt(rin * rin - b.x * b.x), pw)
            val above = (pw[0] - g.planePoint[0]) * n[0] + (pw[1] - g.planePoint[1]) * n[1] + (pw[2] - g.planePoint[2]) * n[2]
            if (above > 0f) continue
            if (!View3D.project(pw[0], pw[1], pw[2], screen)) continue
            val x = screen[0]
            val py = screen[1]
            val r = b.r * GlassShape.H * W
            bubblePaint.style = Paint.Style.FILL
            bubblePaint.color = rgba(255, 255, 255, 0.22f)
            c.drawCircle(x, py, r, bubblePaint)
            bubblePaint.style = Paint.Style.STROKE
            bubblePaint.strokeWidth = r * 0.22f
            bubblePaint.color = rgba(255, 255, 255, 0.7f)
            c.drawCircle(x, py, r, bubblePaint)
            bubblePaint.style = Paint.Style.FILL
            bubblePaint.color = rgba(255, 255, 255, 0.8f)
            c.drawCircle(x - r * 0.35f, py - r * 0.35f, r * 0.25f, bubblePaint)
        }
    }
}
