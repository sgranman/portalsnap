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
// leans against its own motion. Its contents sit at different depths, so they shift against
// each other as it turns. Through the liquid your head shows tinted, blurred, rippling and a
// little stretched, with the room around it, and the straw shows through too. The ice cubes
// are real little 3D blocks with a shine and a tiny reflection of your face; they float free,
// slosh as the glass moves and clink off the wall and each other. Pucker (or open wide) to
// blow bubbles up the straw with a bloop. When tracking drops, the glass stays, empties of
// the face and settles in the middle. Every sound is baked into clips.
//
// The glass follows the original's look (reference video 919589313478329): thick clear walls
// with a bright outer edge, a fainter inner edge and a sheen down each side; a rounded lip all
// the way round the rim; heavily rounded bottom corners on a thick base; and the lemonade
// filled almost to the rim.
object Lemonade : Filter("lemonade", "Lemonade", "🍋", Mode.MESH, voice = 0.9f) {
    override val usesUnder = true
    override val coversCamera = true
    override val keepsScene = true

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
    private const val STRAW_TOP_Y = -1.95f
    // How far out an ice cube's edge can reach in the round glass.
    private const val WALL = 0.95f
    // The face is seen through the lemonade, not painted on it.
    private const val FACE_OPACITY = 0.8f

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
    // The glass that stays up when nobody is in view: the last main one seen.
    private var resting: State? = null
    // Far enough back that the depth layers separate without the near ones ballooning.
    private val camera = Camera().apply { setLocation(0f, 0f, -16f) }
    private val layer = Matrix()
    private val vertexLayer = Matrix()
    private val inverse = Matrix()
    private val values = FloatArray(9)
    private val liquid = Path()
    private val cavity = Path()
    private val cavityOnScreen = Path()
    private val outer = Path()
    private val walls = Path()
    private val outline = Path()
    private val innerEdges = Path()
    private val sheens = Path()
    private val lipOuter = Path()
    private val lipInner = Path()
    private val lip = Path()
    private val poly = Path()
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val corners = FloatArray(16) // an ice cube's 8 projected corners, x y
    private val cornerZ = FloatArray(8)
    private val faceDepth = FloatArray(6)
    private val faceOrder = IntArray(6)
    private val pt = FloatArray(2)
    private var builtW = 0f
    private var builtH = 0f

    // Corners are numbered by bits: x (1), y (2), z (4) set means the + side.
    private val CUBE_FACES = arrayOf(
        intArrayOf(0, 2, 6, 4), intArrayOf(1, 5, 7, 3), // -x, +x
        intArrayOf(0, 4, 5, 1), intArrayOf(2, 3, 7, 6), // -y (top), +y
        intArrayOf(0, 1, 3, 2), intArrayOf(4, 6, 7, 5), // -z (near), +z
    )
    private val CUBE_NORMALS = arrayOf(
        floatArrayOf(-1f, 0f, 0f), floatArrayOf(1f, 0f, 0f),
        floatArrayOf(0f, -1f, 0f), floatArrayOf(0f, 1f, 0f),
        floatArrayOf(0f, 0f, -1f), floatArrayOf(0f, 0f, 1f),
    )
    private const val TOP_FACE = 2

    private fun glassW(d: Draw, count: Int) = d.h * 0.19f * (if (count <= 1) 1f else 0.8f)

    private fun glassH(d: Draw, count: Int) = d.h * 0.26f * (if (count <= 1) 1f else 0.8f)

    /* ------------------------------ motion ------------------------------ */

    override fun update(d: Draw, faces: List<Face>) {
        val dt = min(d.dt, 50f) / 1000f
        if (faces.isEmpty()) {
            // Nobody in view: the last glass empties of the face and settles back to the middle.
            states.clear()
            val W = glassW(d, 1)
            val H = glassH(d, 1)
            val s = resting ?: State(d.w / 2, d.h * 0.55f).also { resting = it }
            spring(s, d.w / 2, d.h * 0.55f, (-s.vx * 0.0006f).coerceIn(-0.6f, 0.6f), 0f, 0f, W, dt)
            slosh(d, s, W, dt)
            fizz(d, null, s, dt)
            return
        }
        for (f in faces) {
            val W = glassW(d, f.count)
            val H = glassH(d, f.count)
            // Coming back, the resting glass is picked up again rather than a new one popping in.
            val s = states.getOrPut(f.id) {
                resting?.takeIf { r -> states.values.none { it === r } } ?: State(f.cx, f.cy)
            }
            s.seen = d.t
            move(d, f, s, W, H, dt)
            slosh(d, s, W, dt)
            fizz(d, f, s, dt)
        }
        faces.maxByOrNull { it.eyeDist }?.let { resting = states[it.id] }
        if (states.size > 4) states.entries.removeAll { d.t - it.value.seen > 3000 }
    }

    // The glass chases the head.
    private fun move(d: Draw, f: Face, s: State, W: Float, H: Float, dt: Float) {
        val tx = f.cx.coerceIn(W * 1.6f, d.w - W * 1.6f)
        // Low enough that the straw and lemon above the rim stay on screen.
        val ty = (f.cy + H * 0.1f).coerceIn(H * 2.1f, d.h - H * 1.35f)
        // Tip with the head, and lean back against the motion like something being carried.
        val roll = (f.angle - s.vx * 0.0006f).coerceIn(-0.6f, 0.6f)
        // Pitch relative to where the head usually sits; the pose matrix's sign isn't trusted,
        // and either way reads as the glass tipping.
        var pitch = 0f
        f.pitch?.let { p ->
            if (s.pitchBase.isNaN()) s.pitchBase = p
            s.pitchBase += (p - s.pitchBase) * min(1f, dt * 0.3f)
            pitch = (p - s.pitchBase) * 1.2f
        }
        spring(s, tx, ty, roll, f.yaw * 30f, pitch, W, dt)
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

    private fun fizz(d: Draw, f: Face?, s: State, dt: Float) {
        s.fizzDebt += dt * 3f
        while (s.fizzDebt >= 1f) {
            s.fizzDebt -= 1f
            s.bubbles += Bubble(rnd(-0.6f, 0.6f), 0.95f, rnd(0.015f, 0.035f), rnd(0.25f, 0.45f), rnd(0f, TAU))
        }
        val blowing = f != null &&
            (f.bs("mouthPucker") > 0.45f || f.bs("mouthFunnel") > 0.35f || f.bs("jawOpen") > 0.5f)
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

    // A warm glow that wanders slowly over a pink-to-orange ground. With nobody in view the
    // resting glass is drawn here and in overlay(), since there's no face to draw it for.
    override fun scene(d: Draw, faces: List<Face>) {
        val c = d.c
        val p = d.pen
        c.drawRect(0f, 0f, d.w, d.h, p.fill(LinearGradient(0f, 0f, d.w, d.h, hex("#ff5a9e"), hex("#ff8a5c"), Shader.TileMode.CLAMP)))
        val t = (d.t % 1_000_000L).toFloat()
        val gx = d.w * (0.5f + 0.35f * sin(t / 4100f))
        val gy = d.h * (0.5f + 0.3f * cos(t / 5300f))
        c.drawRect(0f, 0f, d.w, d.h, p.fill(RadialGradient(gx, gy, d.w * 0.55f, rgba(255, 176, 60, 0.85f), rgba(255, 176, 60, 0f), Shader.TileMode.CLAMP)))
        if (faces.isEmpty()) resting?.let { underGlass(d, it, glassW(d, 1), glassH(d, 1)) }
    }

    override fun under(d: Draw, f: Face) {
        val s = states[f.id] ?: return
        underGlass(d, s, glassW(d, f.count), glassH(d, f.count))
    }

    override fun draw(d: Draw, f: Face) {
        val s = states[f.id] ?: return
        overGlass(d, s, glassW(d, f.count), glassH(d, f.count), f)
    }

    override fun overlay(d: Draw, faces: List<Face>) {
        if (faces.isEmpty()) resting?.let { overGlass(d, it, glassW(d, 1), glassH(d, 1), null) }
    }

    private fun underGlass(d: Draw, s: State, W: Float, H: Float) {
        val c = d.c
        val p = d.pen
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
        // The lemonade at the face's plane: all there is in an empty glass, and what the face
        // is seen through otherwise.
        inPlane(c, s, 0f) {
            c.drawPath(liquid, p.fill(LinearGradient(0f, -H, 0f, H, hex("#cf97a8"), hex("#9e6377"), Shader.TileMode.CLAMP)))
        }
    }

    private fun overGlass(d: Draw, s: State, W: Float, H: Float, f: Face?) {
        val c = d.c
        val p = d.pen
        build(W, H)
        pose(s, 0f, layer)
        cavityOnScreen.set(cavity)
        cavityOnScreen.transform(layer)
        val hb = f?.let { headBox(it) }
        if (f != null && hb != null) facePatch(d, s, W, H, f, hb)

        val e = sin(Math.toRadians((16f + s.pitch).toDouble()).toFloat()).coerceIn(0.05f, 0.5f)
        val cubes = s.cubes.sortedByDescending { it.z }

        // Far to near: the straw at the back, ice behind the face (dimmed by the lemonade), the
        // liquid plane, ice in front, the glass, then the near-side highlight.
        straw(c, p, s, W, H)
        for (cube in cubes) if (cube.z > 0f) iceCube(d, s, cube, W, H, hb, 0.6f)
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
        for (cube in cubes) if (cube.z <= 0f) iceCube(d, s, cube, W, H, hb, 1f)
        inPlane(c, s, 0f) {
            glass(c, p, W, H)
            lemon(c, p, W, H)
            rim(c, p, W, H, e)
        }
        inPlane(c, s, -W * 0.45f) { highlight(c, p, W, H) }
    }

    // The face lives on the glass's middle plane. `local` takes a frame pixel back through that
    // plane into glass units; `map` then takes glass units to the camera, centred on the head.
    private fun facePatch(d: Draw, s: State, W: Float, H: Float, f: Face, hb: HeadBox) {
        layer.invert(inverse) // layer holds the middle plane's pose here
        inverse.postScale(1f / W, 1f / H)
        inverse.getValues(values)
        // Zoomed out, so the head sits in the middle with some of the room around it rather
        // than skin from rim to rim.
        val kx = hb.halfW * 2.3f
        val ky = hb.halfH * 1.45f
        val ca = cos(f.angle)
        val sa = sin(f.angle)
        d.patches += Patch(
            s.x, s.y, W, H, 0f, 0.94f,
            floatArrayOf(ca * kx, -sa * ky, hb.x, sa * kx, ca * ky, hb.y),
            shape = Patch.GLASS, tint = rgba(190, 110, 130, 0.9f), blur = 4f, wave = 2.5f,
            phase = (d.t % 100_000L) / 1000f * 3f, local = values.copyOf(), opacity = FACE_OPACITY,
        )
    }

    // A real little block of ice: eight corners turned by its spin and a tilt that shows its
    // top, each projected through the glass's pose at its own depth. Its faces are clear and
    // drawn far to near, so the back ones show through the front; the top catches the light,
    // with a streak and a glint. The nearest side carries a tiny mirrored reflection of the face.
    private fun iceCube(d: Draw, s: State, cube: Cube, W: Float, H: Float, hb: HeadBox?, alpha: Float) {
        val c = d.c
        val p = d.pen
        // Big enough for the shine and the reflection to read at Portal distance.
        val a = cube.size * W * 0.85f
        val cx = cube.x * W
        // Mostly in the lemonade, tops just breaking the surface: any higher and the rim clips
        // them flat.
        val cy = -H + a * 0.25f
        val cz = cube.z * W
        val th = cube.spin
        val ph = 0.42f + sin(cube.spin * 0.7f) * 0.08f
        val cth = cos(th)
        val sth = sin(th)
        val cph = cos(ph)
        val sph = sin(ph)
        for (i in 0 until 8) {
            val ox = if ((i and 1) != 0) a else -a
            val oy = if ((i and 2) != 0) a else -a
            val oz = if ((i and 4) != 0) a else -a
            val y1 = oy * cph - oz * sph
            val z1 = oy * sph + oz * cph
            val x2 = ox * cth + z1 * sth
            val z2 = -ox * sth + z1 * cth
            cornerZ[i] = cz + z2
            pose(s, cornerZ[i], vertexLayer)
            pt[0] = cx + x2
            pt[1] = cy + y1
            vertexLayer.mapPoints(pt)
            corners[i * 2] = pt[0]
            corners[i * 2 + 1] = pt[1]
        }
        for (k in 0 until 6) {
            val q = CUBE_FACES[k]
            faceDepth[k] = (cornerZ[q[0]] + cornerZ[q[1]] + cornerZ[q[2]] + cornerZ[q[3]]) / 4
            faceOrder[k] = k
        }
        // Far faces first.
        for (i in 1 until 6) {
            var j = i
            while (j > 0 && faceDepth[faceOrder[j - 1]] < faceDepth[faceOrder[j]]) {
                val t = faceOrder[j]
                faceOrder[j] = faceOrder[j - 1]
                faceOrder[j - 1] = t
                j--
            }
        }

        val save = c.save()
        c.clipPath(cavityOnScreen)
        var near = -1
        for (k in faceOrder) {
            val q = CUBE_FACES[k]
            poly.reset()
            poly.moveTo(corners[q[0] * 2], corners[q[0] * 2 + 1])
            for (j in 1 until 4) poly.lineTo(corners[q[j] * 2], corners[q[j] * 2 + 1])
            poly.close()
            // Lit from above: how much this face points up, after the cube's turn.
            val n = CUBE_NORMALS[k]
            val ny = n[1] * cph - n[2] * sph
            val light = (0.5f - 0.5f * ny).coerceIn(0f, 1f)
            val fill = rgba((196 + 59 * light).toInt(), (220 + 35 * light).toInt(), 248, (0.3f + 0.3f * light) * alpha)
            c.drawPath(poly, p.fill(fill))
            val edge = p.stroke(rgba(255, 255, 255, (0.3f + 0.45f * light) * alpha), max(1f, a * 0.06f))
            edge.strokeJoin = Paint.Join.ROUND
            c.drawPath(poly, edge)
            if (k != TOP_FACE && k != 3) near = k // sides only; the last drawn is nearest
        }

        // The shine: a streak across the top face and a glint at its corner.
        val q = CUBE_FACES[TOP_FACE]
        val mx = (corners[q[0] * 2] + corners[q[1] * 2] + corners[q[2] * 2] + corners[q[3] * 2]) / 4
        val my = (corners[q[0] * 2 + 1] + corners[q[1] * 2 + 1] + corners[q[2] * 2 + 1] + corners[q[3] * 2 + 1]) / 4
        val sx1 = mx + (corners[q[0] * 2] - mx) * 0.6f
        val sy1 = my + (corners[q[0] * 2 + 1] - my) * 0.6f
        val sx2 = mx + (corners[q[1] * 2] - mx) * 0.6f
        val sy2 = my + (corners[q[1] * 2 + 1] - my) * 0.6f
        val streak = p.stroke(rgba(255, 255, 255, 0.85f * alpha), max(1.5f, a * 0.12f))
        streak.strokeCap = Paint.Cap.ROUND
        c.drawLine(sx1, sy1, sx2, sy2, streak)
        c.drawCircle(mx + (corners[q[0] * 2] - mx) * 0.78f, my + (corners[q[0] * 2 + 1] - my) * 0.78f, max(1.5f, a * 0.1f), p.fill(rgba(255, 255, 255, 0.95f * alpha)))
        c.restoreToCount(save)

        // A tiny mirrored face in the nearest side, as a GPU patch under the clear ice.
        if (hb != null && near >= 0) {
            val f4 = CUBE_FACES[near]
            val x0 = corners[f4[0] * 2]
            val y0 = corners[f4[0] * 2 + 1]
            val x1 = corners[f4[1] * 2]
            val y1 = corners[f4[1] * 2 + 1]
            val x2 = corners[f4[2] * 2]
            val y2 = corners[f4[2] * 2 + 1]
            val x3 = corners[f4[3] * 2]
            val y3 = corners[f4[3] * 2 + 1]
            val fx = (x0 + x1 + x2 + x3) / 4
            val fy = (y0 + y1 + y2 + y3) / 4
            val across = hypot((x0 + x1) / 2 - (x2 + x3) / 2, (y0 + y1) / 2 - (y2 + y3) / 2)
            val down = hypot((x1 + x2) / 2 - (x3 + x0) / 2, (y1 + y2) / 2 - (y3 + y0) / 2)
            val r = min(across, down) * 0.36f
            if (r > 3f) {
                val k = hb.halfH * 1.2f / r
                d.patches += Patch(
                    fx, fy, r, r, 0f, 0.5f,
                    floatArrayOf(-k, 0f, hb.x + k * fx, 0f, k, hb.y - k * fy),
                    tint = rgba(225, 240, 255, 0.35f), blur = 0.8f, opacity = 0.85f * alpha,
                )
            }
        }
    }

    // A plain dark-red straw at the back, swaying about its foot. Under the lemonade it shows
    // through, shifted sideways where it enters the liquid, the way a straw in a drink looks
    // bent.
    private fun straw(c: Canvas, p: Pen, s: State, W: Float, H: Float) {
        inPlane(c, s, W * 0.35f) {
            val fx = STRAW_FOOT_X * W
            val fy = STRAW_FOOT_Y * H
            p.newPath().apply {
                moveTo(fx, fy)
                lineTo(0.74f * W, STRAW_TOP_Y * H)
            }
            val width = W * 0.065f
            val sub = c.save()
            c.clipPath(liquid)
            c.rotate(deg(s.straw), fx, fy)
            c.translate(W * 0.05f, 0f)
            c.drawPath(p.path, p.stroke(rgba(165, 35, 62, 0.6f), width))
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

    // A real slice hooked on the left rim, behind the front of the lip: a waxy rind darkening to
    // its edge, a ring of white pith, ten translucent segments paler at the core and parted by
    // pith, a few juice streaks, a pale centre, and a gloss across the top.
    private fun lemon(c: Canvas, p: Pen, W: Float, H: Float) {
        val x = -0.92f * W
        val y = RIM_Y * H - W * 0.02f
        val R = W * 0.42f
        p.lift(W * 0.03f)
        c.drawCircle(x, y, R, p.fill(RadialGradient(
            x - R * 0.2f, y - R * 0.25f, R * 1.25f,
            intArrayOf(hex("#ffe45c"), hex("#f7c21a"), hex("#d99a06")), floatArrayOf(0.55f, 0.85f, 1f), Shader.TileMode.CLAMP,
        )))
        p.unlift()
        c.drawCircle(x, y, R * 0.9f, p.stroke(rgba(230, 170, 15, 0.6f), R * 0.025f))
        c.drawCircle(x, y, R * 0.88f, p.fill(hex("#fff6d8")))
        val segments = 10
        val flesh = p.fill(RadialGradient(
            x, y, R * 0.8f,
            intArrayOf(hex("#fff4bd"), hex("#ffe27a"), hex("#f8cc3c")), floatArrayOf(0.12f, 0.6f, 1f), Shader.TileMode.CLAMP,
        ))
        for (k in 0 until segments) {
            val a0 = k * TAU / segments + 0.045f
            val a1 = (k + 1) * TAU / segments - 0.045f
            val mid = (a0 + a1) / 2
            p.newPath().apply {
                moveTo(x + cos(mid) * R * 0.13f, y + sin(mid) * R * 0.13f)
                arcTo(x - R * 0.8f, y - R * 0.8f, x + R * 0.8f, y + R * 0.8f, deg(a0), deg(a1 - a0), false)
                close()
            }
            c.drawPath(p.path, flesh)
            // The "fill" paint is shared, so the streaks come after each segment is drawn.
            val streak = p.stroke(rgba(255, 252, 225, 0.45f), R * 0.02f)
            for (j in -1..1) {
                val a = mid + j * 0.09f
                c.drawLine(x + cos(a) * R * 0.3f, y + sin(a) * R * 0.3f, x + cos(a) * R * 0.66f, y + sin(a) * R * 0.66f, streak)
            }
        }
        c.drawCircle(x, y, R * 0.12f, p.fill(hex("#fff8dc")))
        val gloss = p.stroke(rgba(255, 255, 255, 0.5f), R * 0.08f)
        gloss.strokeCap = Paint.Cap.ROUND
        c.drawArc(x - R * 0.93f, y - R * 0.93f, x + R * 0.93f, y + R * 0.93f, 200f, 55f, false, gloss)
    }
}
