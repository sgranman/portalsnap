package net.sgran.portalsnap

import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/** When the current filter's soundtrack started playing (System.nanoTime), or 0. Set by MainActivity. */
object Soundtrack {
    @Volatile var startedNs = 0L
}

// Photo Booth effect 3: Pop Art, rebuilt from reference video 946857547560491 (see PHOTOBOOTH.md).
// It cuts between looks on the beat of its own soundtrack:
//   - the person: a natural cut-out, flat yellow, a gradient, grain-textured, ghosted into the
//     ground, sheer yellow, dark brown with a yellow rim, or a sticker with a paper border
//   - the ground: red grunge, with a stretch of dark grunge now and then, reached through a
//     particle break-up and left through a torn brush wipe
//   - extras: fire and cyan outlines, motion echoes, a glitter dissolve on the face and a
//     halftone cap, all in the FX_POP_ART shader; hand-drawn rings, sweeping arcs and scribbles
//     along the silhouette, drawn here
// The soundtrack plays through Mixer, so it's heard, drives the cuts, and is baked into clips.
// Without it (paused, or no audio), beats come from the mic, then from a steady clock.
object PopSilhouette : Filter("pop", "Pop Art", "🎨", Mode.SEGMENT) {
    override val usesOver = true
    override val usesFx = true
    override val wantsMic = true
    override val music: String? = "music/popart-loop.wav"

    private const val LOOP_S = 438453f / 44100f
    // Onsets in one loop of the soundtrack, measured from the reference clip: every one is a cut.
    private val ONSETS = floatArrayOf(0.02f, 0.27f, 1.21f, 2.10f, 3.08f, 4.02f, 4.65f, 5.28f, 6.21f, 7.15f, 8.08f, 9.05f, 9.57f, 9.80f)

    private const val NATURAL = 0
    private const val FLAT_YELLOW = 1
    private const val GRADIENT = 2
    private const val TEXTURED = 3
    private const val GHOST = 4
    private const val SHEER_YELLOW = 5
    private const val BROWN_RIM = 6
    private const val STICKER = 7

    private const val PARTICLES = 1
    private const val BRUSH = 2
    private const val TRANS_MS = 900L

    private const val RING = 0
    private const val ARC = 1
    private const val SCRIBBLE = 2
    private const val SQUIGGLE = 3

    private class Stroke(
        val kind: Int, val x: Float, val y: Float, val r: Float, val a0: Float, val born: Long, val life: Long,
        val dashed: Boolean, val double: Boolean, val color: Int, val points: FloatArray? = null,
    )

    private var look = NATURAL
    private var holdBeats = 0
    private var dark = false
    private var beatsOnGround = 0
    private var groundHold = 12
    private var trans = 0
    private var transAt = 0L
    private var outline = 0
    private var echo = false
    private var warmEcho = true
    private var dissolve = false
    private var halftone = false
    // Which way textures on the person slide, px per second.
    private var driftX = 70f
    private var driftY = -45f
    private var lastBeatAt = 0L
    private var seenBeats = -1
    private var seenLoop = -1
    private var seenOnset = -1

    private val strokes = ArrayList<Stroke>()
    private var hasHead = false
    private var headX = 0f
    private var headY = 0f
    private var headR = 0f
    // The silhouette's upper outline in frame px, x y pairs, left to right.
    private var contour = FloatArray(0)
    private var contourN = 0

    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val oval = RectF()
    private val line = Path()

    /* ------------------------------ timing ------------------------------ */

    override fun update(d: Draw, faces: List<Face>) {
        readMask(d)
        val now = d.t
        var beat = false
        var strong = false
        val started = Soundtrack.startedNs
        if (started != 0L) {
            val pos = ((System.nanoTime() - started) / 1e9).toFloat()
            val loop = floor(pos / LOOP_S).toInt()
            val inLoop = pos - loop * LOOP_S
            if (loop != seenLoop) {
                seenLoop = loop
                seenOnset = -1
            }
            var passed = -1
            for (i in ONSETS.indices) if (ONSETS[i] <= inLoop) passed = i
            if (passed > seenOnset) {
                beat = true
                strong = passed == 0 || ONSETS[passed] - ONSETS[passed - 1] > 0.45f
                seenOnset = passed
            }
        } else if (seenBeats >= 0 && d.beats != seenBeats && now - lastBeatAt > 250) {
            beat = true
            strong = true
        }
        seenBeats = d.beats
        if (!beat && now - lastBeatAt > (if (started != 0L) 2500L else 1000L)) {
            beat = true
            strong = true
        }
        if (beat) {
            lastBeatAt = now
            onBeat(now, strong)
        }
        if (trans != 0 && now - transAt >= TRANS_MS) trans = 0
        strokes.removeAll { now - it.born > it.life }
    }

    // Strong beats move the show on; every beat may draw a ring.
    private fun onBeat(now: Long, strong: Boolean) {
        if (strong) {
            beatsOnGround++
            if (trans == 0 && beatsOnGround >= groundHold) {
                trans = if (dark) BRUSH else PARTICLES
                transAt = now
                dark = !dark
                beatsOnGround = 0
                groundHold = if (dark) 6 else 10 + rng.nextInt(6)
                holdBeats = 0
            }
            if (--holdBeats <= 0) nextLook()
            outline = if (look == NATURAL || look == STICKER) {
                val r = rng.nextFloat()
                if (r < 0.2f) 1 else if (r < 0.32f) 2 else 0
            } else {
                0
            }
            echo = rng.nextFloat() < 0.35f
            warmEcho = rng.nextBoolean()
            dissolve = (look == NATURAL || look == SHEER_YELLOW || look == STICKER) && rng.nextFloat() < 0.22f
            halftone = look == GHOST && rng.nextFloat() < 0.4f
            val angle = rnd(0f, TAU)
            val speed = rnd(60f, 130f)
            driftX = kotlin.math.cos(angle) * speed
            driftY = sin(angle) * speed
            if (rng.nextFloat() < 0.5f) spawnSquiggle(now)
            if (rng.nextFloat() < 0.2f) spawnArc(now)
            if (rng.nextFloat() < 0.45f && look != FLAT_YELLOW && look != SHEER_YELLOW) spawnScribble(now)
        }
        if (rng.nextFloat() < (if (strong) 0.35f else 0.2f)) spawnRings(now)
    }

    // Weighted, never the same look twice running. The dark ground favours the natural cut-out
    // and the sticker, as the original did.
    private fun nextLook() {
        val weights = if (dark) intArrayOf(4, 0, 1, 0, 1, 0, 0, 5) else intArrayOf(3, 2, 2, 2, 2, 1, 1, 0)
        val total = weights.sum()
        var pick: Int
        do {
            var r = rng.nextInt(total)
            pick = 0
            while (r >= weights[pick]) {
                r -= weights[pick]
                pick++
            }
        } while (pick == look)
        look = pick
        holdBeats = 1 + rng.nextInt(2)
    }

    /* ------------------------------ the mask ------------------------------ */

    // The upper outline (the first person pixel down every third column) and, from it, the head:
    // the part of the silhouette within reach of its highest point.
    private fun readMask(d: Draw) {
        val m = d.mask
        val w = d.maskW
        val h = d.maskH
        if (m == null || w == 0 || h == 0) {
            hasHead = false
            contourN = 0
            return
        }
        val sx = d.w / w
        val sy = d.h / h
        val cols = w / 3 + 1
        if (contour.size < cols * 2) contour = FloatArray(cols * 2)
        var n = 0
        var top = h
        for (x in 0 until w step 3) {
            var y = 0
            while (y < h && m[y * w + x].toInt() == 0) y++
            if (y < h - 2) {
                contour[n * 2] = x * sx
                contour[n * 2 + 1] = y * sy
                n++
                if (y < top) top = y
            }
        }
        contourN = n
        if (n < 3) {
            hasHead = false
            return
        }
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        val reach = top * sy + d.h * 0.12f
        for (i in 0 until n) {
            if (contour[i * 2 + 1] <= reach) {
                minX = minOf(minX, contour[i * 2])
                maxX = max(maxX, contour[i * 2])
            }
        }
        headR = max(40f, (maxX - minX) * 0.55f)
        headX = (minX + maxX) / 2
        headY = top * sy + headR * 0.95f
        hasHead = true
    }

    /* ------------------------------ the shader ------------------------------ */

    override fun fx(d: Draw, faces: List<Face>, fx: FrameFx) {
        fx.kind = FrameFx.POP_ART
        val q = fx.q
        q[0] = look.toFloat()
        q[1] = if (dark) 1f else 0f
        q[2] = trans.toFloat()
        q[3] = if (trans != 0) ((d.t - transAt).toFloat() / TRANS_MS).coerceIn(0f, 1f) else 0f
        q[4] = outline.toFloat()
        q[5] = if (echo) 1f else 0f
        when {
            dark -> { q[6] = 0.5f; q[7] = 0.5f; q[8] = 0.53f }
            warmEcho -> { q[6] = 0.96f; q[7] = 0.55f; q[8] = 0.16f }
            else -> { q[6] = 0.97f; q[7] = 0.8f; q[8] = 0.22f }
        }
        q[9] = headX
        q[10] = headY
        q[11] = if (hasHead) headR else 0f
        q[12] = if (dissolve) 1f else 0f
        q[13] = if (halftone) 1f else 0f
        q[14] = (d.t % 100_000L) / 1000f
        q[15] = d.beat * 0.05f
        q[16] = driftX
        q[17] = driftY
    }

    /* ------------------------------ strokes ------------------------------ */

    private fun spawnRings(now: Long) {
        if (!hasHead) return
        val halo = rng.nextFloat() < 0.25f
        val r = headR * (if (halo) rnd(0.55f, 0.75f) else rnd(0.35f, 0.7f))
        val side = if (rng.nextBoolean()) 1f else -1f
        val x = if (halo) headX else headX + side * headR * rnd(1.0f, 1.8f)
        val y = if (halo) headY - headR * 1.25f else headY - headR * rnd(0.2f, 1.2f)
        strokes += Stroke(RING, x, y, r, rnd(0f, 360f), now, 850L + rng.nextInt(450), rng.nextFloat() < 0.35f, halo || rng.nextFloat() < 0.4f, Color.WHITE)
        if (strokes.size > 24) strokes.removeAt(0)
    }

    private fun spawnArc(now: Long) {
        if (!hasHead) return
        val r = headR * rnd(1.5f, 2.3f)
        strokes += Stroke(ARC, headX + rnd(-0.3f, 0.3f) * headR, headY + headR * 0.4f, r, rnd(170f, 290f), now, 800L, false, false, Color.WHITE)
    }

    // A wiggly line along part of the silhouette's outline, drawn on and then gone.
    private fun spawnScribble(now: Long) {
        val n = contourN
        if (n < 8) return
        val span = (n * rnd(0.3f, 0.6f)).toInt().coerceAtLeast(6).coerceAtMost(n)
        val start = rng.nextInt(max(1, n - span + 1))
        val pts = FloatArray(span * 2)
        for (i in 0 until span) {
            pts[i * 2] = contour[(start + i) * 2]
            pts[i * 2 + 1] = contour[(start + i) * 2 + 1] - 4f + rnd(-3f, 3f)
        }
        val color = if (dark || look == STICKER || rng.nextBoolean()) Color.WHITE else 0xFFF6B73C.toInt()
        strokes += Stroke(SCRIBBLE, 0f, 0f, 0f, 0f, now, 1100L, false, false, color, pts)
    }

    // A loopy hand-drawn line right across the screen: a wave with curls rolled along it, drawn
    // on from one side while its tail follows it off.
    private fun spawnSquiggle(now: Long) {
        val w = FRAME_W.toFloat()
        val h = FRAME_H.toFloat()
        val n = 160
        val leftToRight = rng.nextBoolean()
        val y0 = h * rnd(0.12f, 0.88f)
        val tilt = h * rnd(-0.3f, 0.3f)
        val wave = h * rnd(0.04f, 0.12f)
        val waves = rnd(0.8f, 1.8f)
        // Curls wide enough, and close enough together, that the pen actually loops back on
        // itself rather than scalloping.
        val curls = rnd(8f, 13f)
        val curl = h * rnd(0.06f, 0.09f)
        val phase = rnd(0f, TAU)
        val pts = FloatArray(n * 2)
        for (i in 0 until n) {
            val t = i / (n - 1f)
            val along = -0.05f * w + t * 1.1f * w
            val x = if (leftToRight) along else w - along
            val y = y0 + tilt * (t - 0.5f) + wave * sin(t * TAU * waves + phase)
            val dir = if (leftToRight) 1f else -1f
            pts[i * 2] = x + curl * kotlin.math.cos(t * TAU * curls) * dir
            pts[i * 2 + 1] = y + curl * sin(t * TAU * curls)
        }
        strokes += Stroke(SQUIGGLE, 0f, 0f, 0f, 0f, now, 1500L, false, false, Color.WHITE, pts)
        if (strokes.size > 24) strokes.removeAt(0)
    }

    override fun overlay(d: Draw, faces: List<Face>) {
        val c = d.c
        val now = d.t
        for (s in strokes) {
            val age = (now - s.born).toFloat()
            val fade = 1f - ((age - s.life * 0.65f) / (s.life * 0.35f)).coerceIn(0f, 1f)
            ink.color = s.color
            ink.alpha = (240 * fade).toInt()
            ink.pathEffect = null
            when (s.kind) {
                RING -> {
                    // Drawn round like a pen, then fading.
                    val grow = (age / 260f).coerceIn(0f, 1f)
                    ink.strokeWidth = max(3f, s.r * 0.07f)
                    if (s.dashed) ink.pathEffect = DashPathEffect(floatArrayOf(s.r * 0.4f, s.r * 0.22f), 0f)
                    oval.set(s.x - s.r, s.y - s.r, s.x + s.r, s.y + s.r)
                    c.drawArc(oval, s.a0, 360f * grow, false, ink)
                    if (s.double) {
                        val r2 = s.r * 0.7f
                        oval.set(s.x - r2, s.y - r2, s.x + r2, s.y + r2)
                        c.drawArc(oval, s.a0 + 40f, 360f * grow, false, ink)
                    }
                }
                ARC -> {
                    // A sweep: the head runs ahead and the tail follows it off.
                    val head = (age / 360f).coerceIn(0f, 1f) * 210f
                    val tail = ((age - 260f) / 420f).coerceIn(0f, 1f) * 210f
                    ink.strokeWidth = max(4f, s.r * 0.035f)
                    oval.set(s.x - s.r, s.y - s.r, s.x + s.r, s.y + s.r)
                    if (head > tail) c.drawArc(oval, s.a0 + tail, head - tail, false, ink)
                }
                SQUIGGLE -> {
                    val pts = s.points ?: continue
                    val n = pts.size / 2
                    val head = (n * (age / 800f)).toInt().coerceIn(0, n)
                    val tail = (n * ((age - 500f) / 900f)).toInt().coerceIn(0, n)
                    if (head - tail < 2) continue
                    ink.alpha = 240
                    ink.strokeWidth = 5f
                    line.reset()
                    line.moveTo(pts[tail * 2], pts[tail * 2 + 1])
                    for (i in tail + 1 until head) line.lineTo(pts[i * 2], pts[i * 2 + 1])
                    c.drawPath(line, ink)
                }
                SCRIBBLE -> {
                    val pts = s.points ?: continue
                    val n = pts.size / 2
                    val shown = (n * (age / 420f)).toInt().coerceIn(0, n)
                    if (shown < 2) continue
                    ink.strokeWidth = 3f
                    line.reset()
                    for (i in 0 until shown) {
                        val x = pts[i * 2]
                        val y = pts[i * 2 + 1] + sin(i * 0.9f + age / 70f) * 5f
                        if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
                    }
                    c.drawPath(line, ink)
                }
            }
        }
        ink.pathEffect = null
    }
}
