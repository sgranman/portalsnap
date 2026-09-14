package net.sgran.portalsnap

import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// Photo Booth effect 8: your face seen through a glass of pink lemonade. The glass is drawn in
// 3D. It chases your head around the screen on a spring, turns and tips with your head, and
// leans against its own motion. Its contents sit on planes at different depths, so they shift
// against each other as it turns. Through the liquid your face is tinted, blurred, rippling and
// stretched by the cylinder's lens. The ice floats free, sloshing as the glass moves and
// clinking off the wall and each other; the straw sways. Pucker (or open wide) to blow bubbles
// up the straw with a bloop. Every sound is baked into clips.
object Lemonade : Filter("lemonade", "Lemonade", "🍋", Mode.MESH, voice = 0.9f) {
    override val usesUnder = true
    override val coversCamera = true

    // Glass units: x (and depth z) in top half-widths, y in half-heights, from the glass's centre.
    private const val SURFACE_Y = -0.8f
    // Up the right-hand side, so neither the straw nor its bubbles cross the face.
    private const val STRAW_FOOT_X = 0.5f
    private const val STRAW_FOOT_Y = 0.72f
    // How far out an ice cube's edge can reach in the round glass.
    private const val WALL = 0.93f

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
        val cubes = arrayListOf(Cube(-0.4f, -0.2f, 0.2f, 0.3f), Cube(0.25f, 0.25f, 0.19f, -0.4f), Cube(-0.05f, 0.4f, 0.16f, 0.9f))
        val bubbles = ArrayList<Bubble>()
        var fizzDebt = 0f
        var blowDebt = 0f
        var nextBloop = 0L
        var nextClink = 0L
        var seen = 0L
    }

    private val states = HashMap<Int, State>()
    // Far enough back that the depth layers separate without the near ones ballooning.
    private val camera = Camera().apply { setLocation(0f, 0f, -16f) }
    private val layer = Matrix()
    private val inverse = Matrix()
    private val values = FloatArray(9)
    private val interior = Path()
    private val outer = Path()
    private val walls = Path()
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var builtW = 0f
    private var builtH = 0f

    private fun glassW(d: Draw, f: Face) = d.h * 0.23f * (if (f.count == 1) 1f else 0.78f)

    private fun glassH(d: Draw, f: Face) = d.h * 0.33f * (if (f.count == 1) 1f else 0.78f)

    /* ------------------------------ motion ------------------------------ */

    override fun update(d: Draw, faces: List<Face>) {
        val dt = min(d.dt, 50f) / 1000f
        for (f in faces) {
            val W = glassW(d, f)
            val H = glassH(d, f)
            val s = states.getOrPut(f.id) { State(f.cx, f.cy) }
            s.seen = d.t
            move(d, f, s, W, H, dt)
            slosh(d, s, W, dt)
            fizz(d, f, s, dt)
        }
        if (states.size > 4) states.entries.removeAll { d.t - it.value.seen > 3000 }
    }

    // The glass chases the head on a spring, so it swings in after you and overshoots a little.
    private fun move(d: Draw, f: Face, s: State, W: Float, H: Float, dt: Float) {
        val tx = f.cx.coerceIn(W * 1.3f, d.w - W * 1.3f)
        // Low enough that the straw and lemon above the rim stay on screen.
        val ty = (f.cy + H * 0.1f).coerceIn(H * 1.6f, d.h - H * 1.2f)
        val ax = (tx - s.x) * 40f - s.vx * 9f
        val ay = (ty - s.y) * 40f - s.vy * 9f
        s.vx += ax * dt
        s.vy += ay * dt
        s.x += s.vx * dt
        s.y += s.vy * dt
        s.ax += (ax - s.ax) * min(1f, dt * 10f)
        s.ay += (ay - s.ay) * min(1f, dt * 10f)

        // Tip with the head, and lean back against the motion like something being carried.
        val rollTarget = (f.angle - s.vx * 0.0006f).coerceIn(-0.6f, 0.6f)
        s.roll += (rollTarget - s.roll) * min(1f, dt * 8f)
        s.yaw += (f.yaw * 30f - s.yaw) * min(1f, dt * 6f)
        // Pitch relative to where the head usually sits; the pose matrix's sign isn't trusted,
        // and either way reads as the glass tipping.
        var pitchTarget = 0f
        f.pitch?.let { p ->
            if (s.pitchBase.isNaN()) s.pitchBase = p
            s.pitchBase += (p - s.pitchBase) * min(1f, dt * 0.3f)
            pitchTarget = (p - s.pitchBase) * 1.2f
        }
        pitchTarget = (pitchTarget + s.vy * 0.02f).coerceIn(-24f, 24f)
        s.pitch += (pitchTarget - s.pitch) * min(1f, dt * 6f)

        // The straw sways on its own spring, pushed by the glass's acceleration.
        val strawAcc = -s.ax / W * 0.15f - s.straw * 40f - s.strawV * 6f
        s.strawV += strawAcc * dt
        s.straw = (s.straw + s.strawV * dt).coerceIn(-0.35f, 0.35f)
    }

    // Ice floats at the surface of a round glass. It slides with the tilt, lags behind the
    // glass's acceleration, bounces off the wall and other cubes, and clinks when it hits hard.
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
            val lim = WALL - c.size
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

    /* ------------------------------ geometry ------------------------------ */

    private fun build(W: Float, H: Float) {
        if (W == builtW && H == builtH) return
        builtW = W
        builtH = H
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

    // The glass's pose as a matrix for a plane at depth z (px, + is away), centred on the glass.
    private fun pose(s: State, z: Float, m: Matrix) {
        camera.save()
        camera.rotateX(s.pitch)
        camera.rotateY(s.yaw)
        camera.translate(0f, 0f, z)
        camera.getMatrix(m)
        camera.restore()
        m.postRotate(deg(s.roll))
        m.postTranslate(s.x, s.y)
    }

    private inline fun inPlane(c: Canvas, s: State, z: Float, block: () -> Unit) {
        val save = c.save()
        pose(s, z, layer)
        c.concat(layer)
        block()
        c.restoreToCount(save)
    }

    /* ------------------------------ drawing ------------------------------ */

    override fun scene(d: Draw, faces: List<Face>) {
        d.c.drawRect(0f, 0f, d.w, d.h, d.pen.fill(LinearGradient(0f, 0f, 0f, d.h, hex("#ff8a6b"), hex("#ff6fae"), Shader.TileMode.CLAMP)))
    }

    override fun under(d: Draw, f: Face) {
        val s = states[f.id] ?: return
        val c = d.c
        val p = d.pen
        val W = glassW(d, f)
        val H = glassH(d, f)
        build(W, H)
        // A soft shadow on the ground, which stays flat.
        val sy = min(d.h * 0.97f, s.y + H * 1.25f)
        val save = c.save()
        c.scale(1f, 0.18f, s.x, sy)
        c.drawCircle(s.x, sy, W * 1.1f, p.fill(RadialGradient(s.x, sy, W * 1.1f, rgba(120, 30, 60, 0.3f), rgba(120, 30, 60, 0f), Shader.TileMode.CLAMP)))
        c.restoreToCount(save)
        // The far wall, deeper pink, a little behind the face: it peeks out as the glass turns.
        inPlane(c, s, W * 0.45f) {
            c.drawPath(interior, p.fill(LinearGradient(0f, -H, 0f, H, hex("#f58fb6"), hex("#d94f86"), Shader.TileMode.CLAMP)))
            c.drawPath(interior, p.stroke(rgba(255, 255, 255, 0.4f), W * 0.015f))
        }
        // The lemonade at the face's plane, so the patch's soft edge fades into pink.
        inPlane(c, s, 0f) {
            c.drawPath(interior, p.fill(LinearGradient(0f, -H, 0f, H, hex("#ffb3cf"), hex("#f56a9c"), Shader.TileMode.CLAMP)))
        }
    }

    override fun draw(d: Draw, f: Face) {
        val s = states[f.id] ?: return
        val c = d.c
        val p = d.pen
        val W = glassW(d, f)
        val H = glassH(d, f)
        build(W, H)

        // The face lives on the glass's middle plane. `local` takes a frame pixel back through
        // that plane into glass units; `map` then takes glass units to the head, filling the
        // glass and upright in it.
        pose(s, 0f, layer)
        layer.invert(inverse)
        inverse.postScale(1f / W, 1f / H)
        inverse.getValues(values)
        val hb = headBox(f)
        // Wider than the head box: the lens magnifies the middle by 1/0.6.
        val kx = hb.halfW * 1.25f
        val ky = hb.halfH * 0.95f
        val ca = kotlin.math.cos(f.angle)
        val sa = sin(f.angle)
        d.patches += Patch(
            s.x, s.y, W, H, 0f, 0.94f,
            floatArrayOf(ca * kx, -sa * ky, hb.x, sa * kx, ca * ky, hb.y),
            shape = Patch.GLASS, tint = rgba(255, 110, 170, 0.7f), blur = 2.5f, wave = 2.5f,
            phase = (d.t % 100_000L) / 1000f * 3f, local = values.copyOf(),
        )

        val e = sin(Math.toRadians((12f + s.pitch).toDouble()).toFloat()).coerceIn(0.03f, 0.5f)
        val bob = sin((d.t % 100_000L) / 600f)
        val cubes = s.cubes.sortedByDescending { it.z }

        // Far to near: ice behind the face (faded, seen through lemonade), the liquid plane,
        // ice in front, the straw, the glass, then the front highlights.
        for (cube in cubes) if (cube.z > 0f) cube(c, p, s, cube, W, H, bob, 0.55f)
        inPlane(c, s, 0f) {
            val inside = c.save()
            c.clipPath(interior)
            bubbles(c, s, W, H)
            surface(c, p, s, W, H, e, d.t)
            c.restoreToCount(inside)
        }
        for (cube in cubes) if (cube.z <= 0f) cube(c, p, s, cube, W, H, bob, 1f)
        straw(c, p, s, W, H)
        inPlane(c, s, 0f) {
            glassFront(c, p, W, H, e)
            lemon(c, p, W, H)
        }
        inPlane(c, s, -W * 0.45f) { highlights(c, p, W, H) }
    }

    private fun cube(c: Canvas, p: Pen, s: State, cube: Cube, W: Float, H: Float, bob: Float, alpha: Float) {
        inPlane(c, s, cube.z * W) {
            val clip = c.save()
            c.clipPath(interior)
            c.translate(cube.x * W, SURFACE_Y * H + cube.size * W * 0.45f + bob * H * 0.01f)
            c.rotate(deg(cube.spin))
            val a = cube.size * W
            c.drawRoundRect(p.rect(0f, 0f, a, a), a * 0.35f, a * 0.35f, p.fill(rgba(255, 255, 255, 0.42f * alpha)))
            c.drawRoundRect(p.rect(0f, 0f, a, a), a * 0.35f, a * 0.35f, p.stroke(rgba(255, 255, 255, 0.95f * alpha), a * 0.1f))
            c.drawRoundRect(p.rect(-a * 0.35f, -a * 0.4f, a * 0.3f, a * 0.12f), a * 0.1f, a * 0.1f, p.fill(rgba(255, 255, 255, 0.6f * alpha)))
            c.restoreToCount(clip)
        }
    }

    // White with pink stripes, fainter under the lemonade, swaying about its foot.
    private fun straw(c: Canvas, p: Pen, s: State, W: Float, H: Float) {
        inPlane(c, s, -W * 0.2f) {
            val fx = STRAW_FOOT_X * W
            val fy = STRAW_FOOT_Y * H
            val sub = c.save()
            c.clipPath(interior)
            c.rotate(deg(s.straw), fx, fy)
            strawStroke(c, p, W, H, submerged = true)
            c.restoreToCount(sub)
            val top = c.save()
            c.clipRect(-W * 3, -H * 3, W * 3, -H)
            c.rotate(deg(s.straw), fx, fy)
            strawStroke(c, p, W, H, submerged = false)
            c.restoreToCount(top)
        }
    }

    private fun strawStroke(c: Canvas, p: Pen, W: Float, H: Float, submerged: Boolean) {
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

    // The top of the lemonade stays nearly level as the glass tilts, sloshes against its
    // acceleration, and opens up as the glass tips toward you. Above it the glass is paler: air.
    private fun surface(c: Canvas, p: Pen, s: State, W: Float, H: Float, e: Float, t: Long) {
        val halfW = W * 1.15f
        val slosh = (-s.ax / W * 0.004f).coerceIn(-0.3f, 0.3f) + sin((t % 100_000L) / 400f) * 0.03f
        val save = c.save()
        c.translate(0f, SURFACE_Y * H)
        c.rotate(deg(-s.roll * 0.85f + slosh))
        c.drawRect(-halfW, -H * 0.6f, halfW, 0f, p.fill(rgba(255, 240, 246, 0.55f)))
        val ry = max(W * 0.03f, W * e)
        c.drawOval(p.rect(0f, 0f, halfW, ry), p.fill(rgba(255, 200, 225, 0.55f)))
        c.drawOval(p.rect(0f, 0f, halfW, ry), p.stroke(rgba(255, 255, 255, 0.7f), W * 0.012f))
        c.restoreToCount(save)
    }

    private fun glassFront(c: Canvas, p: Pen, W: Float, H: Float, e: Float) {
        c.drawPath(walls, p.fill(rgba(255, 255, 255, 0.3f)))
        c.drawPath(outer, p.stroke(rgba(255, 255, 255, 0.85f), W * 0.02f))
        c.drawPath(interior, p.stroke(rgba(255, 255, 255, 0.35f), W * 0.012f))
        // The rim and the base are circles seen from a little above: ellipses that open as it tips.
        c.drawOval(p.rect(0f, -H, W * 1.035f, max(W * 0.04f, W * 1.035f * e)), p.stroke(rgba(255, 255, 255, 0.85f), W * 0.025f))
        c.drawOval(p.rect(0f, H + W * 0.08f, W * 0.87f, max(W * 0.08f, W * 0.87f * e)), p.fill(rgba(255, 255, 255, 0.35f)))
    }

    // Tall highlights down the left wall, on the glass's near side.
    private fun highlights(c: Canvas, p: Pen, W: Float, H: Float) {
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
            c.drawLine(x, y, x + kotlin.math.cos(a) * R * 0.8f, y + sin(a) * R * 0.8f, seg)
        }
        c.drawCircle(x, y, R * 0.12f, p.fill(hex("#fff3b0")))
    }
}
