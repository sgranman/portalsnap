package net.sgran.portalsnap

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// Every constant below is the web app's, with its measured reason in app.html.
const val PREDICT_ALPHA = 0.55f
const val PREDICT_CONF_ALPHA = 0.5f
const val PREDICT_CLAMP = 0.05f
const val PREDICT_MAX_MS = 140f
const val REACQUIRE_MS = 400L
const val TRACK_GONE_MS = 500L
const val MATCH_GATE = 1.5f
const val EASE_DEADBAND = 1f / 320

/** The six blazeface keypoints, then the dense-model extras — one list serves both tiers. */
val KEYS = listOf(
    "eyeR", "eyeL", "nose", "mouth", "earR", "earL",
    "headTop", "skullR", "skullL", "templeR", "templeL", "browR", "browL",
    "chin", "jawR", "jawL", "noseUnder", "nostrilR", "nostrilL",
    "lipBottom", "mouthR", "mouthL",
)

class MPt(var x: Float, var y: Float)

class Track(val id: Int) {
    var target: FaceAnchors? = null
    var shown: HashMap<String, MPt>? = null
    val shownBlend = HashMap<String, Float>()
    val vel = HashMap<String, MPt>()
    var predConf = 0f
    var cx = 0f
    var cy = 0f
    var lastAt = 0L
}

/**
 * Who is who, frame to frame, and the smoothed copy of each face that gets drawn.
 * A port of matchTracks/adopt/easeTrack; see app.html for why each step is there.
 * Lives on the render thread only.
 */
class Tracks {
    val list = ArrayList<Track>()
    var maxFaces = 3
    private var nextId = 1
    private val moveMags = ArrayDeque<Float>()

    fun reset() {
        list.clear()
        moveMags.clear()
    }

    private fun centre(a: FaceAnchors): Pair<Float, Float> {
        val r = a.points.getValue("eyeR")
        val l = a.points.getValue("eyeL")
        return Pair((r.x + l.x) / 2, (r.y + l.y) / 2)
    }

    private fun adopt(t: Track, face: FaceAnchors, now: Long) {
        val gap = (now - t.lastAt).toFloat()
        val prev = t.target
        if (prev == null || gap > REACQUIRE_MS) {
            t.vel.clear()
            t.predConf = 0f
        } else if (gap > 0) {
            var agree = 0f
            var judged = 0
            for (k in KEYS) {
                val a = prev.points[k] ?: continue
                val b = face.points[k] ?: continue
                val vx = (b.x - a.x) / gap
                val vy = (b.y - a.y) / gap
                val p = t.vel[k]
                if (p != null) {
                    val mv = hypot(vx, vy)
                    val mo = hypot(p.x, p.y)
                    if (mv > 0 && mo > 0) {
                        val cosine = (vx * p.x + vy * p.y) / (mv * mo)
                        agree += max(0f, cosine) * (min(mv, mo) / max(mv, mo))
                        judged++
                    }
                    p.x += (vx - p.x) * PREDICT_ALPHA
                    p.y += (vy - p.y) * PREDICT_ALPHA
                } else {
                    t.vel[k] = MPt(vx, vy)
                }
            }
            if (judged > 0) t.predConf += (agree / judged - t.predConf) * PREDICT_CONF_ALPHA else t.predConf = 0f
        }

        if (prev != null) {
            var far = 0f
            for (k in KEYS) {
                val a = prev.points[k] ?: continue
                val b = face.points[k] ?: continue
                far = max(far, hypot(b.x - a.x, b.y - a.y))
            }
            moveMags.addLast(far)
            if (moveMags.size > 90) moveMags.removeFirst()
        }

        t.target = face
        val (cx, cy) = centre(face)
        t.cx = cx
        t.cy = cy
        t.lastAt = now
    }

    // Nearest-first greedy assignment, gated by head width.
    fun match(faces: List<FaceAnchors>, now: Long) {
        list.removeAll { now - it.lastAt >= TRACK_GONE_MS }

        class Pair3(val d: Float, val i: Int, val j: Int)
        val pairs = ArrayList<Pair3>()
        for (i in faces.indices) {
            val (cx, cy) = centre(faces[i])
            val gate = max(0.05f, Anchors.eyeSpan(faces[i]) * MATCH_GATE)
            for (j in list.indices) {
                val d = hypot(cx - list[j].cx, cy - list[j].cy)
                if (d < gate) pairs += Pair3(d, i, j)
            }
        }
        pairs.sortBy { it.d }
        val tookFace = BooleanArray(faces.size)
        val tookTrack = BooleanArray(list.size)
        for (p in pairs) {
            if (tookFace[p.i] || tookTrack[p.j]) continue
            tookFace[p.i] = true
            tookTrack[p.j] = true
            adopt(list[p.j], faces[p.i], now)
        }
        for (i in faces.indices) {
            if (tookFace[i]) continue
            if (list.size >= maxFaces) break
            val t = Track(nextId++)
            adopt(t, faces[i], now)
            list += t
        }
    }

    fun ease(dt: Float, latencyMs: Float) {
        for (t in list) easeTrack(t, dt, latencyMs)
    }

    private fun easeTrack(t: Track, dt: Float, latencyMs: Float) {
        val target = t.target ?: return
        val shown = t.shown
        if (shown == null) {
            t.shown = HashMap<String, MPt>().also { m -> target.points.forEach { (k, p) -> m[k] = MPt(p.x, p.y) } }
            t.shownBlend.putAll(target.blendshapes)
            return
        }
        val lead = min(PREDICT_MAX_MS, latencyMs) * t.predConf
        for (k in KEYS) {
            val tp = target.points[k] ?: continue
            val sp = shown[k] ?: MPt(tp.x, tp.y).also { shown[k] = it }
            var ax = tp.x
            var ay = tp.y
            val v = t.vel[k]
            if (v != null && lead > 0) {
                var dx = v.x * lead
                var dy = v.y * lead
                val d = hypot(dx, dy)
                if (d > PREDICT_CLAMP) {
                    val s = PREDICT_CLAMP / d
                    dx *= s
                    dy *= s
                }
                ax += dx
                ay += dy
            }
            val dx = ax - sp.x
            val dy = ay - sp.y
            val dist = hypot(dx, dy)
            if (dist < EASE_DEADBAND) continue
            val a = min(1f, (0.18f + dist * 12) * (dt / 16.7f))
            sp.x += dx * a
            sp.y += dy * a
        }
        for ((k, v) in target.blendshapes) {
            val cur = t.shownBlend[k] ?: 0f
            t.shownBlend[k] = cur + (v - cur) * min(1f, 0.25f * (dt / 16.7f))
        }
    }

    fun live(now: Long) = list.filter { it.shown != null && now - it.lastAt < TRACK_GONE_MS }

    /** Median largest anchor step between detections, in frame pixels: the tracker's noise floor. */
    fun jitterPx(): Float {
        if (moveMags.isEmpty()) return 0f
        val s = moveMags.sorted()
        return s[s.size / 2] * FRAME_W
    }
}

/** A face in face space, exactly as the web filters receive it. */
class Face(
    val id: Int,
    val cx: Float,
    val cy: Float,
    val angle: Float,
    val eyeDist: Float,
    val nose: Pt,
    val mouth: Pt,
    val earR: Pt,
    val earL: Pt,
    val earSpan: Float,
    val yaw: Float,
    val blendshapes: Map<String, Float>,
    val dense: Boolean,
    private val extra: Map<String, Pt>,
) {
    var rank = 0
    var count = 1

    operator fun get(k: String): Pt? = extra[k]

    fun bs(k: String) = blendshapes[k] ?: 0f

    // Real head width at the temples when the dense model gives it.
    val headSpan: Float = run {
        val r = extra["templeR"]
        val l = extra["templeL"]
        if (r != null && l != null) max(0.8f, hypot(l.x - r.x, l.y - r.y)) else earSpan
    }

    // The measured 0.84 ratio in fast mode; the highest mesh vertex otherwise.
    val headTopY: Float = extra["headTop"]?.y ?: (-0.84f * abs(mouth.y))
}

fun buildFace(t: Track): Face {
    val a = t.shown!!
    val w = FRAME_W.toFloat()
    val h = FRAME_H.toFloat()
    val er = a.getValue("eyeR")
    val el = a.getValue("eyeL")
    val erx = er.x * w
    val ery = er.y * h
    val elx = el.x * w
    val ely = el.y * h
    val cx = (erx + elx) / 2
    val cy = (ery + ely) / 2
    val dx = elx - erx
    val dy = ely - ery
    val eyeDist = max(1f, hypot(dx, dy))
    val angle = atan2(dy, dx)
    val c = cos(-angle)
    val s = sin(-angle)
    fun toFace(p: MPt): Pt {
        val px = p.x * w - cx
        val py = p.y * h - cy
        return Pt((px * c - py * s) / eyeDist, (px * s + py * c) / eyeDist)
    }
    val nose = toFace(a.getValue("nose"))
    val mouth = toFace(a.getValue("mouth"))
    val earR = toFace(a.getValue("earR"))
    val earL = toFace(a.getValue("earL"))
    val earSpan = max(0.8f, hypot(earL.x - earR.x, earL.y - earR.y))
    val earMidX = (earR.x + earL.x) / 2
    val yaw = ((nose.x - earMidX) / (earSpan / 2)).coerceIn(-1f, 1f)
    val extra = HashMap<String, Pt>()
    for (k in KEYS) {
        if (k in Anchors.KP) continue
        a[k]?.let { extra[k] = toFace(it) }
    }
    return Face(
        t.id, cx, cy, angle, eyeDist, nose, mouth, earR, earL, earSpan, yaw,
        HashMap(t.shownBlend), t.target?.dense == true, extra,
    )
}
