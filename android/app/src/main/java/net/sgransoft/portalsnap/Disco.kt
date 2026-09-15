package net.sgransoft.portalsnap

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

// Photo Booth effect 2, rebuilt from its reference video (606034295553604): Disco Star. It cuts
// between three looks every two bars of its music, in a loop:
//  - LED wall: the picture becomes a grid of round lights, each showing the colour under it, over
//    a dimmed wash of the room. Star outlines in brighter lights burst outward from your head
//    on the beat, one after another, and the colour walks amber, pink, purple, red.
//  - Laser star: a star made of five crossing laser beams, centred on your head. Smoky white light
//    with a rainbow cast shines out from behind it, so its edges read clearly, while the middle
//    of the star stays clear because the star blocks the light.
//  - Laser tunnel: neon triangles born at the middle of the screen on the beat, each growing out
//    past the edges, under purple and orange haze.
// The shader in Gl.FX_DISCO draws the room, the lights and the smoke; the laser lines are drawn
// on the canvas. Its music (assets/music/disco-loop.wav) was taken from the reference clip, and
// its sung stretch patched with the same music two bars on. While it plays, beats and cuts follow
// the loop's own grid. Without it, beats come from the mic, with a clock at the same tempo.
object DiscoStar : Filter("disco", "Disco", "🪩", Mode.FAST) {
    override val usesFx = true
    override val wantsMic = true
    override val music: String? = "music/disco-loop.wav"

    private const val DOTS = 0
    private const val STAR = 1
    private const val LASERS = 2
    // Two bars of the music.
    private const val LOOK_MS = 4889L
    private const val BEAT_MS = 611L
    // The loop's grid, measured from it: ten bars, with the first downbeat 0.465s in.
    private const val LOOP_S = 24.4455f
    private const val BEAT_S = LOOP_S / 40
    private const val DOWNBEAT_S = 0.465f
    // How long an LED star burst takes to grow off the screen, and a tunnel triangle.
    private const val BURST_MS = 2200f
    private const val TRI_MS = 1700f

    // The wall's colour walks through these over its look.
    private val TINTS = arrayOf(
        floatArrayOf(1f, 0.62f, 0.28f),
        floatArrayOf(1f, 0.42f, 0.78f),
        floatArrayOf(0.62f, 0.32f, 1f),
        floatArrayOf(1f, 0.3f, 0.38f),
    )
    private val NEON = intArrayOf(hex("#ff4fd8"), hex("#ff9a3c"), hex("#a45cff"), hex("#ff3f7f"))

    private class Tri(val born: Long, val color: Int)

    private var look = DOTS
    private var lookAt = -1L
    private var seenBeats = -1
    private var lastKick = 0L
    private var kick = 0f
    private var seenGridBeat = Long.MIN_VALUE
    private var headX = FRAME_W / 2f
    private var headY = FRAME_H / 2f
    private var gridRot = 0f
    private val bursts = LongArray(3) { Long.MIN_VALUE }
    private var nextBurst = 0
    private var starRot = 0f
    private var starSpin = 0f
    private val tris = ArrayList<Tri>()
    private var nextColor = 0
    private val tips = FloatArray(10)
    private val path = Path()
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val core = Paint(glow)

    /* ------------------------------ timing ------------------------------ */

    override fun update(d: Draw, faces: List<Face>) {
        val now = d.t
        val dt = min(d.dt, 50f) / 1000f
        // The head, eased so the stars glide after it. With nobody in view, the screen's middle.
        val head = faces.maxByOrNull { it.eyeDist }
        val tx = head?.cx ?: (d.w / 2)
        val ty = head?.let { it.cy - it.eyeDist * 0.2f } ?: (d.h / 2)
        val ease = min(1f, dt * 6f)
        headX += (tx - headX) * ease
        headY += (ty - headY) * ease

        if (lookAt < 0) begin(d, DOTS)
        val started = Soundtrack.startedNs
        if (started != 0L) {
            // On the loop's own grid: a beat every beat, harder on the bar, and a new look every two
            // bars. A beat that runs over the loop's end carries on as the next loop's beat -1.
            val pos = ((System.nanoTime() - started) / 1e9).toFloat()
            val loop = floor(pos / LOOP_S).toLong()
            val gridBeat = max(0L, loop * 40 + floor((pos - loop * LOOP_S - DOWNBEAT_S) / BEAT_S).toLong())
            if (gridBeat != seenGridBeat) {
                val first = seenGridBeat == Long.MIN_VALUE
                seenGridBeat = gridBeat
                lastKick = now
                kick = if (Math.floorMod(gridBeat, 4L) == 0L) 1f else 0.7f
                val next = Math.floorMod(Math.floorDiv(gridBeat, 8L), 3L).toInt()
                if (first || next != look) begin(d, next) else onKick(d)
            }
            seenBeats = d.beats
        } else {
            seenGridBeat = Long.MIN_VALUE
            val beat = seenBeats >= 0 && d.beats != seenBeats
            seenBeats = d.beats
            val tick = !beat && d.sinceBeatMs > 2000f && now - lastKick >= BEAT_MS
            if (beat || tick) {
                lastKick = now
                kick = if (beat) 1f else 0.6f
                onKick(d)
            }
            if (now - lookAt >= LOOK_MS) begin(d, (look + 1) % 3)
        }
        kick = max(0f, kick - d.dt / 350f)
        tris.removeAll { now - it.born > TRI_MS }
    }

    private fun begin(d: Draw, next: Int) {
        look = next
        lookAt = d.t
        when (next) {
            DOTS -> {
                gridRot = rnd(-0.25f, 0.25f)
                bursts.fill(Long.MIN_VALUE)
            }
            STAR -> {
                starRot = rnd(0f, TAU)
                starSpin = rnd(0.15f, 0.35f) * if (rng.nextBoolean()) 1f else -1f
            }
            LASERS -> tris.clear()
        }
        onKick(d)
    }

    // On the beat: a new star bursts out of the head, or a new triangle is born in the middle.
    private fun onKick(d: Draw) {
        when (look) {
            DOTS -> {
                bursts[nextBurst] = d.t
                nextBurst = (nextBurst + 1) % bursts.size
            }
            LASERS -> tris += Tri(d.t, NEON[nextColor++ % NEON.size])
        }
    }

    private fun starRadius(d: Draw) = d.h * 0.4f * (1f + 0.06f * kick)

    private fun starAngle(d: Draw) = starRot + starSpin * (d.t - lookAt) / 1000f

    /* ------------------------------ drawing ------------------------------ */

    override fun fx(d: Draw, faces: List<Face>, fx: FrameFx) {
        val q = fx.q
        val p = ((d.t - lookAt).toFloat() / LOOK_MS).coerceIn(0f, 1f)
        fx.kind = FrameFx.DISCO
        q[0] = look.toFloat()
        q[1] = (d.t % 600_000L) / 1000f
        q[2] = kick
        val k = p * (TINTS.size - 1)
        val i = min(k.toInt(), TINTS.size - 2)
        val f = k - i
        for (c in 0 until 3) q[3 + c] = TINTS[i][c] + (TINTS[i + 1][c] - TINTS[i][c]) * f
        q[6] = headX
        q[7] = headY
        q[8] = starRadius(d)
        q[9] = starAngle(d)
        q[10] = gridRot
        // Each burst grows from a small star at the head until it's well off the screen.
        for (b in bursts.indices) {
            val age = if (bursts[b] == Long.MIN_VALUE) 2f else (d.t - bursts[b]) / BURST_MS
            if (age in 0f..1f) {
                q[11 + b] = d.h * (0.12f + 1.45f * age.pow(0.8f))
                // Full strength most of the way out, fading only near the end: an early fade
                // left the bursts too faint to see over a bright picture.
                q[14 + b] = 1f - age * age
            } else {
                q[11 + b] = 0f
                q[14 + b] = 0f
            }
        }
    }

    override fun overlay(d: Draw, faces: List<Face>) {
        when (look) {
            STAR -> laserStar(d)
            LASERS -> tunnel(d)
        }
    }

    // The star's outline in laser beams: ten edges, each running from an inner corner out past
    // its tip and fading there, so the beams cross at the points but nothing crosses the middle.
    // (Five full crossing beams drew a pentagram.) The corners match the shader's star, so the
    // smoky light it holds back lines up: tip k sits at 72°·k from up and inner corner k at
    // 36° + 72°·k, 0.382 of the way out, all turned by the star's angle.
    private fun laserStar(d: Draw) {
        val c = d.c
        val r = starRadius(d)
        val a = starAngle(d)
        val inner = r * 0.382f
        for (k in 0 until 5) {
            val th = k * TAU / 5 - a
            tips[k * 2] = headX + r * sin(th)
            tips[k * 2 + 1] = headY - r * cos(th)
        }
        val w = d.h * 0.005f
        val alpha = 0.85f + 0.15f * kick
        val ext = r * 0.35f
        for (k in 0 until 5) {
            val th = (k + 0.5f) * TAU / 5 - a
            val vx = headX + inner * sin(th)
            val vy = headY - inner * cos(th)
            for (tip in intArrayOf(k, (k + 1) % 5)) {
                val tx = tips[tip * 2]
                val ty = tips[tip * 2 + 1]
                val len = hypot(tx - vx, ty - vy)
                val nx = (tx - vx) / len
                val ny = (ty - vy) / len
                val color = if (tip == k) NEON[0] else NEON[2]
                beam(c, tx + nx * ext, ty + ny * ext, vx, vy, ext / (len + ext), 0f, color, w, alpha)
            }
        }
    }

    // Triangles born small in the middle on the beat, growing out past the edges and thickening
    // as they come, all turning slowly together.
    private fun tunnel(d: Draw) {
        val c = d.c
        val cx = d.w / 2
        val cy = d.h / 2
        val spin = (d.t % 100_000L) / 1000f * 0.25f
        val w = d.h * 0.0045f
        for (t in tris) {
            val age = (d.t - t.born) / TRI_MS
            if (age < 0f || age > 1f) continue
            val size = d.h * (0.04f + 1.9f * age.pow(1.7f))
            val a = min(1f, age * 10f) * (1f - age).pow(0.6f) * (0.85f + 0.15f * kick)
            path.reset()
            for (k in 0 until 3) {
                val th = spin + k * TAU / 3
                val x = cx + size * sin(th)
                val y = cy - size * cos(th)
                if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            neon(c, t.color, w * (0.8f + 1.2f * age), a)
        }
    }

    // A laser line: a soft wide glow, a tighter glow and a pale hot core. It fades in over the
    // first [fadeStart] of its length and out over the last [fadeEnd]; 0 keeps that end solid.
    private fun beam(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, fadeStart: Float, fadeEnd: Float, color: Int, w: Float, a: Float) {
        val stops = floatArrayOf(0f, fadeStart, 1f - fadeEnd, 1f)
        fun ramp(col: Int, alpha: Float) = intArrayOf(
            withAlpha(col, if (fadeStart > 0f) 0f else alpha), withAlpha(col, alpha),
            withAlpha(col, alpha), withAlpha(col, if (fadeEnd > 0f) 0f else alpha),
        )
        glow.color = Color.WHITE
        glow.shader = LinearGradient(x0, y0, x1, y1, ramp(color, a * 0.3f), stops, Shader.TileMode.CLAMP)
        glow.strokeWidth = w * 8f
        c.drawLine(x0, y0, x1, y1, glow)
        glow.shader = LinearGradient(x0, y0, x1, y1, ramp(color, a * 0.7f), stops, Shader.TileMode.CLAMP)
        glow.strokeWidth = w * 3f
        c.drawLine(x0, y0, x1, y1, glow)
        core.color = Color.WHITE
        core.shader = LinearGradient(x0, y0, x1, y1, ramp(lighten(color), a), stops, Shader.TileMode.CLAMP)
        core.strokeWidth = w * 1.2f
        c.drawLine(x0, y0, x1, y1, core)
        glow.shader = null
        core.shader = null
    }

    private fun neon(c: Canvas, color: Int, w: Float, a: Float) {
        glow.color = withAlpha(color, a * 0.22f)
        glow.strokeWidth = w * 9f
        c.drawPath(path, glow)
        glow.color = withAlpha(color, a * 0.55f)
        glow.strokeWidth = w * 3.5f
        c.drawPath(path, glow)
        core.color = withAlpha(lighten(color), a)
        core.strokeWidth = w
        c.drawPath(path, core)
    }

    private fun withAlpha(color: Int, a: Float) =
        Color.argb((a.coerceIn(0f, 1f) * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    private fun lighten(color: Int) =
        Color.rgb((Color.red(color) + 255) / 2, (Color.green(color) + 255) / 2, (Color.blue(color) + 255) / 2)
}
