package net.sgran.portalsnap

import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.cos
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
//
// The glass follows the original's look (reference video 919589313478329): thick clear walls
// with a bright outer edge, a fainter inner edge and a highlight down each side; a rounded lip
// all the way round the rim; heavily rounded bottom corners on a thick base; and the lemonade
// filled almost to the rim.
object Lemonade : Filter("lemonade", "Lemonade", "🍋", Mode.MESH, voice = 0.9f) {
    override val usesUnder = true
    override val coversCamera = true

    // Glass units: x (and depth z) in the liquid's top half-width W, y in its half-height H,
    // from the liquid's centre. The liquid runs y -1..1; the glass goes on above and below.
    private const val TAPER = 0.85f // bottom width over top width; matches the shader mask
    private const val CORNER_X = 0.38f // bottom corner radius, in W; matches the shader
    private const val CORNER_Y = 0.26f // the same corner, in H
    private const val RIM_Y = -1.14f
    private const val BASE_Y = 1.22f
    private const val WALL_T = 0.1f // wall thickness, in W
    private const val STRAW_FOOT_X = 0.45f
    private const val STRAW_FOOT_Y = 0.7f
    // How far out an ice cube's edge can reach in the round glass.
    private const val WALL = 0.95f

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
        val cubes = arrayListOf(Cube(-0.35f, -0.2f, 0.28f, 0.3f), Cube(0.3f, 0.2f, 0.26f, -0.4f), Cube(-0.05f, 0.45f, 0.22f, 0.9f))
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
    private val liquid = Path()
    private val cavity = Path()
    private val outer = Path()
    private val walls = Path()
    private val outline = Path()
    private val innerEdges = Path()
    private val sheens = Path()
    private val lipOuter = Path()
    private val lipInner = Path()
    private val lip = Path()
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var builtW = 0f
    private var builtH = 0f

    private fun glassW(d: Draw, f: Face) = d.h * 0.19f * (if (f.count == 1) 1f else 0.8f)

    private fun glassH(d: Draw, f: Face) = d.h * 0.26f * (if (f.count == 1) 1f else 0.8f)

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
        val tx = f.cx.coerceIn(W * 1.6f, d.w - W * 1.6f)
        // Low enough that the straw and lemon above the rim stay on screen.
        val ty = (f.cy + H * 0.1f).coerceIn(H * 1.85f, d.h - H * 1.35f)
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
            if (b.y < -0.95f) it.remove()
        }
        if (s.bubbles.size > 160) s.bubbles.subList(0, s.bubbles.size - 160).clear()
    }

    /* ------------------------------ geometry ------------------------------ */

    private fun halfWidthAt(yUnits: Float, W: Float) = W * (1f + (TAPER - 1f) * (yUnits + 1f) / 2f)

    private fun build(W: Float, H: Float) {
        if (W == builtW && H == builtH) return
        builtW = W
        builtH = H
        val t = WALL_T * W
        val side = halfWidthAt(1f - CORNER_Y, W)
        val foot = TAPER * W
        val rx = CORNER_X * W
        val ry = CORNER_Y * H

        fun tumbler(path: Path, top: Float, topHalf: Float, bottom: Float, sideHalf: Float, footHalf: Float, cx: Float, cy: Float) {
            path.reset()
            path.moveTo(-topHalf, top)
            path.lineTo(topHalf, top)
            path.lineTo(sideHalf, bottom - cy)
            path.quadTo(footHalf, bottom, footHalf - cx, bottom)
            path.lineTo(-(footHalf - cx), bottom)
            path.quadTo(-footHalf, bottom, -sideHalf, bottom - cy)
            path.close()
        }
        // The liquid is exactly the shader's mask; the cavity is the same shape carried up to
        // the rim; the outer skin adds the wall and a thick base.
        tumbler(liquid, -H, W, H, side, foot, rx, ry)
        val rimTop = RIM_Y * H
        val rimHalf = halfWidthAt(RIM_Y, W)
        tumbler(cavity, rimTop, rimHalf, H, side, foot, rx, ry)
        tumbler(outer, rimTop, rimHalf + t, BASE_Y * H, side + t, foot + t, rx * 1.2f, ry * 1.3f)
        walls.reset()
        walls.op(outer, cavity, Path.Op.DIFFERENCE)

        // The silhouette without its top edge: the rim is drawn as a lip, not a line.
        val baseY = BASE_Y * H
        outline.reset()
        outline.moveTo(-(rimHalf + t), rimTop)
        outline.lineTo(-(side + t), baseY - ry * 1.3f)
        outline.quadTo(-(foot + t), baseY, -(foot + t - rx * 1.2f), baseY)
        outline.lineTo(foot + t - rx * 1.2f, baseY)
        outline.quadTo(foot + t, baseY, side + t, baseY - ry * 1.3f)
        outline.lineTo(rimHalf + t, rimTop)

        innerEdges.reset()
        for (sgn in floatArrayOf(-1f, 1f)) {
            innerEdges.moveTo(sgn * rimHalf, rimTop)
            innerEdges.lineTo(sgn * side, H - ry)
            innerEdges.quadTo(sgn * foot, H, sgn * (foot - rx), H)
        }

        // A thin bright line down the middle of each wall.
        sheens.reset()
        for (sgn in floatArrayOf(-1f, 1f)) {
            sheens.moveTo(sgn * (halfWidthAt(-1.05f, W) + t * 0.45f), -1.05f * H)
            sheens.lineTo(sgn * (halfWidthAt(0.75f, W) + t * 0.45f), 0.75f * H)
        }
    }

    // The glass's pose as a matrix for a plane at depth z (px, + is away), centred on the liquid.
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

    // A warm glow that wanders slowly over a pink-to-orange ground.
    override fun scene(d: Draw, faces: List<Face>) {
        val c = d.c
        val p = d.pen
        c.drawRect(0f, 0f, d.w, d.h, p.fill(LinearGradient(0f, 0f, d.w, d.h, hex("#ff5a9e"), hex("#ff8a5c"), Shader.TileMode.CLAMP)))
        val t = (d.t % 1_000_000L).toFloat()
        val gx = d.w * (0.5f + 0.35f * sin(t / 4100f))
        val gy = d.h * (0.5f + 0.3f * cos(t / 5300f))
        c.drawRect(0f, 0f, d.w, d.h, p.fill(RadialGradient(gx, gy, d.w * 0.55f, rgba(255, 176, 60, 0.85f), rgba(255, 176, 60, 0f), Shader.TileMode.CLAMP)))
    }

    override fun under(d: Draw, f: Face) {
        val s = states[f.id] ?: return
        val c = d.c
        val p = d.pen
        val W = glassW(d, f)
        val H = glassH(d, f)
        build(W, H)
        // A soft shadow below, which stays flat.
        val sy = min(d.h * 0.97f, s.y + H * 1.45f)
        val save = c.save()
        c.scale(1f, 0.18f, s.x, sy)
        c.drawCircle(s.x, sy, W * 1.2f, p.fill(RadialGradient(s.x, sy, W * 1.2f, rgba(120, 30, 60, 0.28f), rgba(120, 30, 60, 0f), Shader.TileMode.CLAMP)))
        c.restoreToCount(save)
        // The far wall, a little behind the face: it peeks out as the glass turns.
        inPlane(c, s, W * 0.45f) {
            c.drawPath(cavity, p.fill(LinearGradient(0f, -H, 0f, H, rgba(214, 150, 168, 0.9f), rgba(160, 96, 118, 0.9f), Shader.TileMode.CLAMP)))
        }
        // The lemonade at the face's plane, so the patch's soft edge fades into it.
        inPlane(c, s, 0f) {
            c.drawPath(liquid, p.fill(LinearGradient(0f, -H, 0f, H, hex("#cf97a8"), hex("#9e6377"), Shader.TileMode.CLAMP)))
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
        // liquid and upright in it.
        pose(s, 0f, layer)
        layer.invert(inverse)
        inverse.postScale(1f / W, 1f / H)
        inverse.getValues(values)
        val hb = headBox(f)
        // Wider than the head box because the lens magnifies the middle; shorter, so the face
        // stands tall in the glass as in the original.
        val kx = hb.halfW * 1.25f
        val ky = hb.halfH * 0.85f
        val ca = cos(f.angle)
        val sa = sin(f.angle)
        d.patches += Patch(
            s.x, s.y, W, H, 0f, 0.94f,
            floatArrayOf(ca * kx, -sa * ky, hb.x, sa * kx, ca * ky, hb.y),
            shape = Patch.GLASS, tint = rgba(190, 110, 130, 0.9f), blur = 4f, wave = 2.5f,
            phase = (d.t % 100_000L) / 1000f * 3f, local = values.copyOf(),
        )

        val e = sin(Math.toRadians((16f + s.pitch).toDouble()).toFloat()).coerceIn(0.05f, 0.5f)
        val cubes = s.cubes.sortedByDescending { it.z }

        // Far to near: the straw at the back, ice behind the face (dimmed by the lemonade), the
        // liquid plane, ice in front, the glass, then the near-side highlight.
        straw(c, p, s, W, H)
        for (cube in cubes) if (cube.z > 0f) cube(c, p, s, cube, W, H, 0.6f)
        inPlane(c, s, 0f) {
            val inside = c.save()
            c.clipPath(liquid)
            bubbles(c, s, W, H)
            // The lemonade deepens toward the bottom, then its floor shows through the base as a
            // lighter oval.
            c.drawRect(-W * 1.2f, H * 0.55f, W * 1.2f, H * 1.05f, p.fill(LinearGradient(0f, H * 0.55f, 0f, H, rgba(90, 45, 60, 0f), rgba(90, 45, 60, 0.4f), Shader.TileMode.CLAMP)))
            c.drawOval(p.rect(0f, H * 0.97f, TAPER * W * 0.92f, TAPER * W * 0.92f * e), p.fill(rgba(255, 228, 236, 0.35f)))
            c.restoreToCount(inside)
            liquidTop(c, p, s, W, H, e, d.t)
        }
        for (cube in cubes) if (cube.z <= 0f) cube(c, p, s, cube, W, H, 1f)
        inPlane(c, s, 0f) {
            glass(c, p, W, H)
            lemon(c, p, W, H)
            rim(c, p, W, H, e)
        }
        inPlane(c, s, -W * 0.45f) { highlight(c, p, W, H) }
    }

    // Grey cubes with a bright top, sitting high: most of each one stands above the lemonade.
    private fun cube(c: Canvas, p: Pen, s: State, cube: Cube, W: Float, H: Float, alpha: Float) {
        inPlane(c, s, cube.z * W) {
            val clip = c.save()
            c.clipPath(cavity)
            val a = cube.size * W
            c.translate(cube.x * W, -H - a * 0.1f)
            c.rotate(deg(cube.spin * 0.3f))
            c.drawRoundRect(p.rect(0f, 0f, a, a * 0.85f), a * 0.3f, a * 0.3f, p.fill(rgba(196, 192, 204, 0.85f * alpha)))
            c.drawRoundRect(p.rect(0f, -a * 0.45f, a * 0.92f, a * 0.35f), a * 0.25f, a * 0.25f, p.fill(rgba(255, 255, 255, 0.9f * alpha)))
            c.drawRoundRect(p.rect(0f, 0f, a, a * 0.85f), a * 0.3f, a * 0.3f, p.stroke(rgba(255, 255, 255, 0.55f * alpha), a * 0.06f))
            c.restoreToCount(clip)
        }
    }

    // A plain dark-red straw, dimmed under the lemonade, swaying about its foot.
    private fun straw(c: Canvas, p: Pen, s: State, W: Float, H: Float) {
        // At the back of the glass, as in the original, poking only a little above the rim.
        inPlane(c, s, W * 0.35f) {
            val fx = STRAW_FOOT_X * W
            val fy = STRAW_FOOT_Y * H
            p.newPath().apply {
                moveTo(fx, fy)
                lineTo(0.72f * W, -1.6f * H)
            }
            val width = W * 0.065f
            val sub = c.save()
            c.clipPath(liquid)
            c.rotate(deg(s.straw), fx, fy)
            // Behind the face, so only a ghost of it shows through the lemonade.
            c.drawPath(p.path, p.stroke(rgba(150, 30, 55, 0.18f), width))
            c.restoreToCount(sub)
            val top = c.save()
            c.clipRect(-W * 3, -H * 3, W * 3, -H)
            c.rotate(deg(s.straw), fx, fy)
            val body = p.stroke(hex("#b8233f"), width)
            body.strokeCap = Paint.Cap.BUTT
            c.drawPath(p.path, body)
            // A lit edge along one side, so it reads as a tube.
            c.translate(-width * 0.25f, 0f)
            c.drawPath(p.path, p.stroke(rgba(255, 150, 160, 0.55f), width * 0.25f))
            c.restoreToCount(top)
        }
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
            bubblePaint.color = rgba(255, 255, 255, 0.7f)
            c.drawCircle(x, y, r, bubblePaint)
            bubblePaint.style = Paint.Style.FILL
            bubblePaint.color = rgba(255, 255, 255, 0.8f)
            c.drawCircle(x - r * 0.35f, y - r * 0.35f, r * 0.25f, bubblePaint)
        }
    }

    // The top of the lemonade, just below the rim, sloshing a little against the acceleration.
    private fun liquidTop(c: Canvas, p: Pen, s: State, W: Float, H: Float, e: Float, t: Long) {
        val slosh = (-s.ax / W * 0.004f).coerceIn(-0.25f, 0.25f) + sin((t % 100_000L) / 400f) * 0.03f
        val save = c.save()
        c.translate(0f, -H)
        c.rotate(deg(slosh))
        c.drawOval(p.rect(0f, 0f, W * 0.98f, W * 0.98f * e), p.fill(rgba(226, 168, 186, 0.6f)))
        c.drawOval(p.rect(0f, 0f, W * 0.98f, W * 0.98f * e), p.stroke(rgba(255, 255, 255, 0.35f), W * 0.012f))
        c.restoreToCount(save)
    }

    // Thick clear walls and base: a faint body, a soft glow and a bright edge outside, a
    // fainter edge inside, and a sheen down each wall.
    private fun glass(c: Canvas, p: Pen, W: Float, H: Float) {
        c.drawPath(walls, p.fill(rgba(255, 255, 255, 0.3f)))
        // Only a hint of glow: any more and the edge reads as neon rather than glass.
        val glow = p.stroke(rgba(250, 255, 235, 0.07f), W * 0.05f)
        glow.strokeJoin = Paint.Join.ROUND
        c.drawPath(outline, glow)
        c.drawPath(outline, p.stroke(rgba(245, 255, 228, 0.92f), W * 0.016f))
        // Where the light bends inside the wall: a faint dark line, then the pale inner edge.
        c.drawPath(innerEdges, p.stroke(rgba(90, 55, 70, 0.28f), W * 0.022f))
        c.drawPath(innerEdges, p.stroke(rgba(255, 255, 255, 0.45f), W * 0.009f))
        val sheen = p.stroke(rgba(255, 255, 255, 0.6f), W * 0.012f)
        sheen.strokeCap = Paint.Cap.ROUND
        c.drawPath(sheens, sheen)
    }

    // A rounded lip all the way round: a pale band between two ellipses, faint along the back
    // and bright along the front.
    private fun rim(c: Canvas, p: Pen, W: Float, H: Float, e: Float) {
        val y = RIM_Y * H
        val rIn = halfWidthAt(RIM_Y, W)
        val rOut = rIn + WALL_T * W
        lipOuter.reset()
        lipOuter.addOval(-rOut, y - rOut * e, rOut, y + rOut * e, Path.Direction.CW)
        lipInner.reset()
        lipInner.addOval(-rIn, y - rIn * e, rIn, y + rIn * e, Path.Direction.CW)
        lip.reset()
        lip.op(lipOuter, lipInner, Path.Op.DIFFERENCE)
        c.drawPath(lip, p.fill(rgba(255, 255, 255, 0.28f)))
        c.drawArc(-rOut, y - rOut * e, rOut, y + rOut * e, 180f, 180f, false, p.stroke(rgba(255, 255, 255, 0.4f), W * 0.012f))
        c.drawArc(-rIn, y - rIn * e, rIn, y + rIn * e, 0f, 180f, false, p.stroke(rgba(255, 255, 255, 0.5f), W * 0.01f))
        c.drawArc(-rOut, y - rOut * e, rOut, y + rOut * e, 0f, 180f, false, p.stroke(rgba(255, 250, 230, 0.95f), W * 0.018f))
    }

    // A soft broad highlight on the near side of the left wall.
    private fun highlight(c: Canvas, p: Pen, W: Float, H: Float) {
        p.newPath().apply {
            moveTo(-0.88f * W, -1.0f * H)
            lineTo(-0.74f * W, -1.0f * H)
            lineTo(-0.62f * W, 0.7f * H)
            lineTo(-0.72f * W, 0.7f * H)
            close()
        }
        c.drawPath(p.path, p.fill(rgba(255, 255, 255, 0.14f)))
    }

    // A big slice hooked on the left rim, behind the front of the lip.
    private fun lemon(c: Canvas, p: Pen, W: Float, H: Float) {
        val x = -0.92f * W
        val y = RIM_Y * H - W * 0.02f
        val R = W * 0.42f
        p.lift(W * 0.03f)
        c.drawCircle(x, y, R, p.fill(hex("#ffd12e")))
        p.unlift()
        c.drawCircle(x, y, R * 0.86f, p.fill(hex("#ffe977")))
        val seg = p.stroke(hex("#f2b51c"), R * 0.05f)
        for (k in 0 until 9) {
            val a = k * TAU / 9
            c.drawLine(x, y, x + cos(a) * R * 0.8f, y + sin(a) * R * 0.8f, seg)
        }
        c.drawCircle(x, y, R * 0.1f, p.fill(hex("#fff3b0")))
    }
}
