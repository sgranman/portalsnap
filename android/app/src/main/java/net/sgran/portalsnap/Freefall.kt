package net.sgran.portalsnap

import android.opengl.Matrix
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Freefall, from reference video 897386285564659. A skydiver in a glossy blue suit and a striped
 * helmet falls through a sky of clouds rushing upward, wearing the face in the helmet's opening,
 * following the head like Bike Ride's rider. Opening the mouth wide sends the diver tumbling away
 * below: the camera tips down after them onto farmland far below, they sink into a cloud deck, the
 * view whites out, and the close-up comes back. Everyone in view gets a diver. Wind plays under
 * it all, with a whoosh on the fall.
 */
object Freefall : Filter("freefall", "Freefall", "☁️", Mode.MESH) {
    override val usesUnder = false
    override val usesOver = false
    override val coversCamera = true
    override val keepsScene = true
    override val ambience = "wind"

    /** How wide the mouth opens to fall, as MediaPipe's jawOpen. */
    private const val JAW = 0.5f
    private const val SHOW_MS = 350L
    private const val FORGET_MS = 3000L
    /** Nearest and furthest the diver comes, m. */
    private const val NEAR = 0.6f
    private const val FAR = 2.2f
    /** The face shows this much bigger than it is, for the reference's close-up. */
    private const val ZOOM = 1.7f

    // The fall, in seconds since the scream: tumbling away, the white-out, back to the close-up
    // (hidden in the white), the white clearing, and ready to go again.
    private const val WHITE_IN = 1.75f
    private const val WHITE_FULL = 2.15f
    private const val BACK = 2.35f
    private const val CLEAR = 3.0f
    private const val READY = 3.3f

    /** adb's `--ef fallDist`: hold every diver this far away, for testing on a still portrait. */
    @Volatile var debugDist: Float? = null
    @Volatile private var poked = false

    private class Diver {
        var seen = 0L
        var ready = false
        var sx = 0f
        var sy = 0f
        var dist = 1.5f
        var yaw = 0f
        var roll = 0f
        var faceX = 0f
        var faceY = 0f
        var reachX = 0f
        var reachY = 0f
        var faceRoll = 0f
        var phase = 0f
        /** Where the head was when the fall began, in camera space. */
        val from = FloatArray(3)
        var spinX = 0f
        var spinZ = 0f
    }

    private val divers = HashMap<Int, Diver>()
    private val fall = Fall3D()
    private var clock = 0f
    private var fallAt = -1L
    private val head = FloatArray(3)
    private val lead = FloatArray(3)

    /** A tap, or `--es action poke`: fall now. */
    override fun poke() {
        poked = true
    }

    override fun update(d: Draw, faces: List<Face>) {
        val dt = min(d.dt, 50f) / 1000f
        val now = d.t
        val k = min(1f, dt * 9f)
        for (f in faces) {
            val r = divers.getOrPut(f.id) { Diver().also { it.phase = (f.id * 1.9f) % TAU } }
            // A turned head shows narrower eyes; don't let that push the diver away.
            val turn = sqrt(max(0.4f, 1f - f.yaw * f.yaw))
            val dist = debugDist
                ?: (FallView.F * DiverParts.FACE_HALF_W * DiverParts.HEAD_SCALE / (ZOOM * f.eyeDist / turn)).coerceIn(NEAR, FAR)
            val centre = toPixels(f, 0f, f.mouth.y * 0.42f)
            val fresh = !r.ready || now - r.seen > SHOW_MS
            val kk = if (fresh) 1f else k
            r.sx += (centre.x - r.sx) * kk
            r.sy += (centre.y - r.sy) * kk
            r.dist += (dist - r.dist) * (if (fresh) 1f else min(1f, dt * 5f))
            r.yaw += (f.yaw * 30f - r.yaw) * kk
            r.roll += (f.angle - r.roll) * kk
            r.faceX = centre.x
            r.faceY = centre.y
            r.reachX = f.eyeDist * 1.05f
            r.reachY = f.eyeDist * 1.4f
            r.faceRoll = f.angle
            r.ready = true
            r.seen = now
        }
        divers.entries.removeAll { now - it.value.seen > FORGET_MS && fallAt < 0 }

        if (fallAt >= 0 && now - fallAt >= (READY * 1000).toLong()) fallAt = -1
        val jump = poked || faces.any { it.bs("jawOpen") > JAW }
        poked = false
        if (fallAt < 0 && jump && divers.values.any { now - it.seen <= SHOW_MS }) {
            fallAt = now
            Sfx.play("whoosh", 0.9f)
            for (r in divers.values) {
                closeHead(r, r.from)
                r.spinX = sin(r.phase * 3.1f + now % 997) * 0.6f
                r.spinZ = 1f
            }
        }
        val t = if (fallAt >= 0) (now - fallAt) / 1000f else -1f
        val falling = t in 0f..BACK

        // The clouds rush past faster once we're falling in earnest.
        clock += dt * (if (falling) 1f + 2.5f * min(1f, t) else 1f)
        fall.clock = clock
        fall.wall = if (falling) 1f - smooth(0.35f, 1.1f, t) else 1f
        fall.groundShow = 1f - fall.wall
        fall.deckY = if (falling) -(1200f + (8f - 1200f) * smooth(1.2f, 2.05f, t)) else -1200f
        fall.wind = if (falling) 1.6f else 1f
        fall.white = when {
            t < 0f -> 0f
            t < BACK -> smooth(WHITE_IN, WHITE_FULL, t)
            else -> 1f - smooth(BACK + 0.05f, CLEAR, t)
        }

        fall.body.reset()
        fall.heads.clear()
        val shown = divers.values.filter { falling || now - it.seen <= SHOW_MS }.sortedByDescending { it.dist }
        var pitch = 0f
        for ((i, r) in shown.withIndex()) {
            if (falling) {
                val drop = 16.7f * max(0f, t - 0.05f).pow(2)
                head[0] = r.from[0]
                head[1] = r.from[1] - drop * DROP_Y
                head[2] = r.from[2] - drop * DROP_Z
            } else {
                closeHead(r, head)
            }
            // The camera follows the nearest diver down.
            if (falling && i == shown.size - 1) head.copyInto(lead)
            pose(r, head, if (falling) t else -1f)
        }
        if (falling && shown.isNotEmpty()) pitch = (atan2(lead[1], -lead[2]) * 0.9f).coerceIn(-1.25f, 0f)
        FallView.look(pitch, fall.viewProj, fall.inverse)
        d.falls += fall
    }

    // Straight down, drifting a little away from the camera.
    private val DROP_Y = 1f / sqrt(1f + 0.32f * 0.32f)
    private val DROP_Z = 0.32f / sqrt(1f + 0.32f * 0.32f)

    private fun smooth(a: Float, b: Float, x: Float): Float {
        val u = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return u * u * (3 - 2 * u)
    }

    // The head where the face is, as far away as the face's size says, in camera space.
    private fun closeHead(r: Diver, out: FloatArray) {
        out[0] = (r.sx - FRAME_W / 2f) / FallView.F * r.dist
        out[1] = -(r.sy - FRAME_H / 2f) / FallView.F * r.dist
        out[2] = -r.dist
    }

    private fun pose(r: Diver, at: FloatArray, t: Float) {
        val m = FloatArray(16)
        Matrix.setIdentityM(m, 0)
        Matrix.translateM(m, 0, at[0], at[1], at[2])
        Matrix.rotateM(m, 0, r.yaw, 0f, 1f, 0f)
        Matrix.rotateM(m, 0, -deg(r.roll), 0f, 0f, 1f)
        if (t > 0f) {
            // Tumbling about the chest, faster and faster.
            val spin = deg(3.4f * max(0f, t - 0.1f).pow(1.4f))
            Matrix.translateM(m, 0, 0f, -0.25f, -0.55f)
            Matrix.rotateM(m, 0, spin, r.spinX, 0.35f, r.spinZ)
            Matrix.translateM(m, 0, 0f, 0.25f, 0.55f)
        }
        val h = DiverHead()
        val s = DiverParts.HEAD_SCALE
        Matrix.scaleM(h.model, 0, m, 0, s, s, s)
        h.faceX = r.faceX
        h.faceY = r.faceY
        h.reachX = r.reachX
        h.reachY = r.reachY
        h.roll = r.faceRoll
        fall.heads += h
        DiverParts.body(fall.body, m, h.model, sin(clock * 11f + r.phase) * 0.012f, DiverParts.BODY_TILT)
    }
}

/** Freefall's camera: at the origin, looking down the -z axis tipped down by a pitch. */
object FallView {
    private const val FOV_Y = 55f
    /** Focal length in frame pixels. */
    val F = FRAME_H / 2f / tan((FOV_Y / 2f) * PI.toFloat() / 180f)
    val eye = floatArrayOf(0f, 0f, 0f)
    private val proj = FloatArray(16).also { Matrix.perspectiveM(it, 0, FOV_Y, FRAME_W.toFloat() / FRAME_H, 0.05f, 20000f) }
    private val view = FloatArray(16)

    /** [pitch] radians, negative looking down; writes the view-projection and its inverse. */
    fun look(pitch: Float, viewProj: FloatArray, inverse: FloatArray) {
        Matrix.setLookAtM(view, 0, 0f, 0f, 0f, 0f, sin(pitch), -cos(pitch), 0f, cos(pitch), sin(pitch))
        Matrix.multiplyMM(viewProj, 0, proj, 0, view, 0)
        Matrix.invertM(inverse, 0, viewProj, 0)
    }
}

/** One diver's head for this frame: helmet and face, in head units through [model]. */
class DiverHead {
    val model = FloatArray(16)
    var faceX = 0f
    var faceY = 0f
    var reachX = 0f
    var reachY = 0f
    var roll = 0f
}

/** Everything the renderer needs for one frame of the fall. */
class Fall3D {
    val viewProj = FloatArray(16)
    val inverse = FloatArray(16)
    /** Seconds of cloud travel. */
    var clock = 0f
    /** How much of the wall of cloud round the close-up shows, and how much of the ground below. */
    var wall = 1f
    var groundShow = 0f
    /** The cloud deck's height below the camera, m (negative). */
    var deckY = -1200f
    /** The white-out, 0 to 1. */
    var white = 0f
    /** How hard the wind flaps the cheeks. */
    var wind = 1f
    /** Divers' bodies, already in world space. Colour alpha is gloss. */
    val body = ColorGeo(8192, 16384)
    val heads = ArrayList<DiverHead>()
}

/**
 * The skydiver, in metres, in diver space: the head's middle at the origin, the face looking
 * along +z, belly down (-y), the body stretching away behind the head along -z. Head units (the
 * helmet, face and chin strap) are before [HEAD_SCALE].
 */
object DiverParts {
    const val HEAD_SCALE = 1.5f
    /** The face window: half its width and height, and its middle's height, in head units. */
    const val FACE_HALF_W = 0.105f
    const val FACE_HALF_H = 0.14f
    const val FACE_Y = -0.035f
    /** Degrees the body tips down behind the head from the neck, so the camera sees the harness and legs. */
    const val BODY_TILT = -32f

    private const val SUIT = 0x2345d6
    private const val CUFF = 0xe6e8ea
    private const val FINGER = 0x1d2c86
    private const val HARNESS = 0x6d6f72
    private const val BUCKLE = 0xb9bdc2
    private const val PACK = 0x2a2b2f
    private const val TRIM = 0xcdb68c
    private const val BOOT = 0x1d2b6e
    private const val STRAP = 0x55575a

    /** The body through [m], the chin strap through [head]; [flap] ruffles the arms in the wind. */
    fun body(g: ColorGeo, diver: FloatArray, head: FloatArray, flap: Float, tilt: Float) {
        // The body pivots at the neck: a negative tilt drops it below the head, into view.
        val m = diver.copyOf()
        Matrix.translateM(m, 0, 0f, -0.2f, -0.12f)
        Matrix.rotateM(m, 0, tilt, 1f, 0f, 0f)
        Matrix.translateM(m, 0, 0f, 0.2f, 0.12f)
        g.color(SUIT, 0.8f)
        g.tube(m, floatArrayOf(0f, -0.22f, -0.06f, 0f, -0.25f, -0.22f), 2, 0.085f, 0.1f, 10)
        g.ellipsoid(m, 0f, -0.25f, -0.58f, 0.2f, 0.12f, 0.36f, 16, 10)
        // The parachute pack on the back.
        g.color(PACK, 0.3f).box(m, 0f, -0.11f, -0.64f, 0.16f, 0.05f, 0.25f)
        g.color(TRIM, 0.2f).tube(m, floatArrayOf(-0.15f, -0.07f, -0.4f, 0.15f, -0.07f, -0.4f), 2, 0.022f, 0.022f, 6)
        // Harness: straps over the shoulders and down the chest, and a chest strap with a buckle.
        g.color(HARNESS, 0.3f)
        for (sd in floatArrayOf(-1f, 1f)) {
            val x = sd * 0.11f
            g.tube(m, floatArrayOf(x, -0.13f, -0.3f, x * 1.15f, -0.31f, -0.26f, x, -0.37f, -0.45f, x * 0.9f, -0.37f, -0.78f), 4, 0.018f, 0.018f, 6, 3)
        }
        g.tube(m, floatArrayOf(-0.17f, -0.33f, -0.33f, 0f, -0.375f, -0.3f, 0.17f, -0.33f, -0.33f), 3, 0.016f, 0.016f, 6, 3)
        g.color(BUCKLE, 0.9f).box(m, 0f, -0.378f, -0.3f, 0.04f, 0.012f, 0.03f)

        for (sd in floatArrayOf(-1f, 1f)) {
            // Arms out wide and swept back, forearms up.
            val sx = sd * 0.2f
            val ex = sd * 0.5f
            val wx = sd * 0.66f
            val wy = 0.1f + flap
            val ey = -0.12f + flap * 0.4f
            g.color(SUIT, 0.8f)
            g.ellipsoid(m, sx, -0.2f, -0.32f, 0.11f, 0.1f, 0.11f, 12, 8)
            g.tube(m, floatArrayOf(sx, -0.2f, -0.32f, ex, ey, -0.55f), 2, 0.1f, 0.085f, 10)
            g.ellipsoid(m, ex, ey, -0.55f, 0.085f, 0.085f, 0.085f, 10, 7)
            g.tube(m, floatArrayOf(ex, ey, -0.55f, wx, wy, -0.42f), 2, 0.085f, 0.065f, 10)
            // Glove: a white cuff, the hand, short fingers spread up and out, a thumb.
            g.color(CUFF, 0.5f).tube(m, floatArrayOf(wx - sd * 0.02f, wy - 0.02f, -0.43f, wx + sd * 0.04f, wy + 0.025f, -0.41f), 2, 0.068f, 0.068f, 10, caps = false)
            g.color(SUIT, 0.7f).ellipsoid(m, wx + sd * 0.1f, wy + 0.06f, -0.4f, 0.075f, 0.05f, 0.065f, 10, 7)
            g.color(FINGER, 0.4f)
            for (k in 0 until 4) {
                val z = -0.45f + k * 0.03f
                g.tube(m, floatArrayOf(wx + sd * 0.15f, wy + 0.08f, z, wx + sd * 0.2f, wy + 0.13f - k * 0.01f, z + (k - 1.5f) * 0.015f), 2, 0.018f, 0.015f, 6)
            }
            g.tube(m, floatArrayOf(wx + sd * 0.09f, wy + 0.05f, -0.35f, wx + sd * 0.14f, wy + 0.07f, -0.31f), 2, 0.017f, 0.015f, 6)
            // Legs trailing behind and splayed, knees bent, shins up, so the boots stick out.
            val hip = floatArrayOf(sd * 0.12f, -0.28f, -0.9f)
            val knee = floatArrayOf(sd * 0.34f, -0.36f, -1.22f)
            val ankle = floatArrayOf(sd * 0.44f, -0.08f - flap, -1.36f)
            g.color(SUIT, 0.8f)
            g.tube(m, floatArrayOf(hip[0], hip[1], hip[2], knee[0], knee[1], knee[2]), 2, 0.09f, 0.075f, 10)
            g.ellipsoid(m, knee[0], knee[1], knee[2], 0.075f, 0.075f, 0.075f, 10, 7)
            g.tube(m, floatArrayOf(knee[0], knee[1], knee[2], ankle[0], ankle[1], ankle[2]), 2, 0.075f, 0.058f, 10)
            g.color(BOOT, 0.5f).ellipsoid(m, ankle[0], ankle[1] + 0.04f, ankle[2] - 0.02f, 0.068f, 0.12f, 0.075f, 10, 7)
        }
        // The helmet's padded chin strap, under the chin.
        g.color(STRAP, 0.2f).tube(
            head,
            floatArrayOf(-0.105f, -0.09f, 0.07f, -0.075f, -0.15f, 0.085f, 0f, -0.172f, 0.095f, 0.075f, -0.15f, 0.085f, 0.105f, -0.09f, 0.07f),
            5, 0.024f, 0.024f, 8, 3,
        )
    }
}
