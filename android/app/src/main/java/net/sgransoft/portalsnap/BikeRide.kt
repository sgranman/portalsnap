package net.sgransoft.portalsnap

import android.opengl.Matrix
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Bike Ride, from reference video 3745986732290546. The camera backs down a path through a
 * low-poly park at sunset while a cartoon rider pedals toward it, wearing the face in a white
 * helmet. The rider follows the head: sideways to drift across the path, closer to the Portal to
 * come closer, and away when the face leaves. With nobody in view the empty path keeps rolling.
 * Everyone in view gets a bike. Silent, like the original.
 */
object BikeRide : Filter("bike", "Bike", "🚲", Mode.FAST) {
    override val usesUnder = false
    override val usesOver = false
    override val coversCamera = true
    override val keepsScene = true

    /** How fast the park recedes, m/s. */
    const val SPEED = 4.2f
    private const val CADENCE = 1.2f // pedal turns a second
    private const val SHOW_MS = 350L // a tracking blink keeps the rider
    private const val FORGET_MS = 3000L

    /** Nearest and furthest the rider comes, m. */
    private const val NEAR = 0.85f
    private const val FAR = 3.2f
    /** Where the head's centre may sit, from crouched over the bars to upright. */
    private const val HEAD_LOW = 0.9f
    private const val HEAD_HIGH = 1.2f
    /** A face mid-frame puts the head this far under the camera, which looks down on the rider a little. */
    private const val HEAD_DROP = 0.15f

    private class Rider {
        var seen = 0L
        var ready = false
        var sx = 0f
        var sy = 0f
        var dist = 1.5f
        var bikeX = 0f
        var bikeV = 0f
        var yaw = 0f
        var roll = 0f
        var faceX = 0f
        var faceY = 0f
        var reachX = 0f
        var reachY = 0f
        var faceRoll = 0f
        var phase = 0f
    }

    /** adb's `--ef rideDist`: hold every rider this far away, for testing on a still portrait; negative clears. */
    @Volatile var debugDist: Float? = null

    private val riders = HashMap<Int, Rider>()
    private val ride = Ride3D()
    private var scroll = 0f
    private var crank = 0f
    private var lastT = -1L

    override fun update(d: Draw, faces: List<Face>) {
        val dt = min(d.dt, 50f) / 1000f
        val now = d.t
        scroll = (scroll + SPEED * dt) % Park.LOOP
        crank = (crank + CADENCE * TAU * dt) % TAU
        val k = min(1f, dt * 9f)
        for (f in faces) {
            val r = riders.getOrPut(f.id) { Rider().also { it.phase = (f.id * 1.7f) % TAU } }
            // A turned head shows narrower eyes; don't let that push the rider away.
            val turn = sqrt(max(0.4f, 1f - f.yaw * f.yaw))
            val dist = debugDist ?: (RideView.F * RideParts.FACE_HALF_W * RideParts.HEAD_SCALE / (f.eyeDist / turn)).coerceIn(NEAR, FAR)
            // Brow to chin: the helmet's brim covers the top of the forehead.
            val centre = toPixels(f, 0f, f.mouth.y * 0.42f)
            val fresh = !r.ready || now - r.seen > SHOW_MS
            val kk = if (fresh) 1f else k
            r.sx += (centre.x - r.sx) * kk
            r.sy += (centre.y - r.sy) * kk
            r.dist += (dist - r.dist) * (if (fresh) 1f else min(1f, dt * 5f))
            r.yaw += (f.yaw * 32f - r.yaw) * kk
            r.roll += (f.angle - r.roll) * kk
            r.faceX = centre.x
            r.faceY = centre.y
            r.reachX = f.eyeDist * 1.0f
            r.reachY = f.eyeDist * 1.4f
            r.faceRoll = f.angle
            if (fresh) {
                r.bikeX = headX(r)
                r.bikeV = 0f
            }
            r.ready = true
            r.seen = now
        }
        riders.entries.removeAll { now - it.value.seen > FORGET_MS }

        ride.scroll = scroll
        ride.body.reset()
        ride.straps.reset()
        ride.heads.clear()
        ride.shadows.clear()
        // Furthest first doesn't matter with depth, but nearest last keeps the face blending right.
        val shown = riders.values.filter { now - it.seen <= SHOW_MS }.sortedByDescending { it.dist }
        for (r in shown) pose(r, dt)
        d.rides += ride
        lastT = now
    }

    private fun headX(r: Rider) = (r.sx - FRAME_W / 2f) / RideView.F * r.dist

    private val bike = FloatArray(16)


    private fun pose(r: Rider, dt: Float) {
        val dist = r.dist
        val hx = headX(r)
        val hy = (RideView.CAM_H - HEAD_DROP - (r.sy - FRAME_H / 2f) / RideView.F * dist).coerceIn(HEAD_LOW, HEAD_HIGH)
        val hz = -dist

        // The bike trails the head sideways on a spring, so a quick move leans the rider over.
        r.bikeV += ((hx - r.bikeX) * 26f - r.bikeV * 8f) * dt
        r.bikeX += r.bikeV * dt
        r.bikeX = r.bikeX.coerceIn(hx - 0.28f, hx + 0.28f)
        val t = crank + r.phase
        val lean = atan2(hx - r.bikeX, hy - 0.2f) * 0.55f + sin(t) * 0.025f

        Matrix.setIdentityM(bike, 0)
        Matrix.translateM(bike, 0, r.bikeX, 0f, hz - RideParts.HEAD_AHEAD)
        Matrix.rotateM(bike, 0, -deg(lean), 0f, 0f, 1f)
        Matrix.rotateM(bike, 0, -r.bikeV * 6f, 0f, 1f, 0f)

        val head = RideHead()
        Matrix.setIdentityM(head.model, 0)
        Matrix.translateM(head.model, 0, hx, hy, hz)
        Matrix.rotateM(head.model, 0, r.yaw, 0f, 1f, 0f)
        Matrix.rotateM(head.model, 0, -deg(r.roll) + deg(lean) * 0.3f, 0f, 0f, 1f)
        val s = RideParts.HEAD_SCALE
        Matrix.scaleM(head.model, 0, s, s, s)
        Matrix.multiplyMM(head.helmet, 0, head.model, 0, RideParts.HELMET_TIP, 0)
        head.faceX = r.faceX
        head.faceY = r.faceY
        head.reachX = r.reachX
        head.reachY = r.reachY
        head.roll = r.faceRoll
        ride.heads += head

        RideParts.bike(ride.body, bike, scroll / RideParts.WHEEL_R, t)
        RideParts.rider(ride.body, ride.straps, bike, head.model, hx, hy, hz, t)

        // A soft shadow on the path, thrown a little left and toward the camera by the low sun.
        val shadow = FloatArray(16)
        Matrix.setIdentityM(shadow, 0)
        Matrix.translateM(shadow, 0, r.bikeX - 0.12f, 0.004f, hz - RideParts.HEAD_AHEAD + 0.1f)
        Matrix.scaleM(shadow, 0, 0.42f, 1f, 0.75f)
        ride.shadows += shadow
    }
}

/** The ride's fixed camera: level, at a child's eye height, looking down the path (world -z). */
object RideView {
    const val CAM_H = 1.17f
    private const val FOV_Y = 55f
    const val NEAR = 0.06f
    const val FAR = 260f
    /** Focal length in frame pixels. */
    val F = FRAME_H / 2f / tan((FOV_Y / 2f) * PI.toFloat() / 180f)
    val eye = floatArrayOf(0f, CAM_H, 0f)
    val viewProj = FloatArray(16)

    init {
        val proj = FloatArray(16)
        val view = FloatArray(16)
        Matrix.perspectiveM(proj, 0, FOV_Y, FRAME_W.toFloat() / FRAME_H, NEAR, FAR)
        Matrix.setLookAtM(view, 0, 0f, CAM_H, 0f, 0f, CAM_H, -1f, 0f, 1f, 0f)
        Matrix.multiplyMM(viewProj, 0, proj, 0, view, 0)
    }
}

/** One rider's head for this frame, in head units: the face through [model], the helmet through [helmet]. */
class RideHead {
    val model = FloatArray(16)
    val helmet = FloatArray(16)
    var faceX = 0f
    var faceY = 0f
    var reachX = 0f
    var reachY = 0f
    var roll = 0f
}

/** Everything the renderer needs for one frame of the ride. */
class Ride3D {
    var scroll = 0f
    /** Bikes and bodies, already in world space. */
    val body = ColorGeo(8192, 16384)
    /** Helmet straps, drawn after the faces so they cross their edges. */
    val straps = ColorGeo(1024, 2048)
    val heads = ArrayList<RideHead>()
    /** Model matrices for the unit shadow disc on the ground. */
    val shadows = ArrayList<FloatArray>()
}

/**
 * The rider and the bike, in metres. Bike space: origin on the ground between the wheels, x to the
 * rider's left (the camera's right), y up, z forward toward the camera. Head units are the helmet
 * and face's own, before [HEAD_SCALE]: origin at the middle of the face, which looks along +z.
 */
object RideParts {
    const val HEAD_SCALE = 1.35f
    /** Half the face window's width and height, in head units. */
    const val FACE_HALF_W = 0.105f
    const val FACE_HALF_H = 0.13f
    /**
     * Head units to the helmet's: it tips forward on its brim, showing its vents the way a rider
     * leaning over the bars does, while the face stays square to the camera.
     */
    val HELMET_TIP = FloatArray(16).also {
        Matrix.setIdentityM(it, 0)
        Matrix.translateM(it, 0, 0f, RideMeshes.RIM_FRONT, 0.06f)
        Matrix.rotateM(it, 0, 14f, 1f, 0f, 0f)
        Matrix.translateM(it, 0, 0f, -RideMeshes.RIM_FRONT, -0.06f)
    }

    // Where the straps leave the helmet, on its tipped sides, in head units: front then back strap.
    private val STRAP_TOP = floatArrayOf(0.135f, 0.075f, 0.03f, 0.145f, 0.05f, -0.07f).also { a ->
        val v = FloatArray(4)
        val w = FloatArray(4)
        for (k in 0 until 2) {
            v[0] = a[k * 3]
            v[1] = a[k * 3 + 1]
            v[2] = a[k * 3 + 2]
            v[3] = 1f
            Matrix.multiplyMV(w, 0, HELMET_TIP, 0, v, 0)
            a[k * 3] = w[0]
            a[k * 3 + 1] = w[1]
            a[k * 3 + 2] = w[2]
        }
    }

    /** How far the head leans out ahead of the bike's middle, toward the camera. */
    const val HEAD_AHEAD = 0.24f

    const val WHEEL_R = 0.22f
    private const val BASE = 0.34f // half the wheelbase
    private const val CRANK_Y = 0.23f
    private const val CRANK_Z = -0.03f
    private const val CRANK_L = 0.1f
    private const val HIP_Y = 0.63f
    private const val HIP_Z = -0.17f

    private const val JERSEY = 0x1faf86
    private const val PANTS = 0x28437c
    private const val GLOVE = 0x63c0e6
    private const val SHOE = 0xf3a51c
    private const val FRAME = 0x2f7fe0
    private const val TYRE = 0x1a1a1c
    private const val RIM = 0xc9ced2
    private const val BLACK = 0x222224
    private const val STRAP = 0x2d2f2b

    private val p = FloatArray(4)
    private val q = FloatArray(4)

    private fun world(m: FloatArray, x: Float, y: Float, z: Float, out: FloatArray) {
        p[0] = x
        p[1] = y
        p[2] = z
        p[3] = 1f
        Matrix.multiplyMV(out, 0, m, 0, p, 0)
    }

    /** The bike through [m]; [wheel] is the wheels' turn in radians, [crank] the pedals'. */
    fun bike(g: ColorGeo, m: FloatArray, wheel: Float, crank: Float) {
        val r = WHEEL_R
        val seat = floatArrayOf(0f, 0.55f, -0.18f)
        val headTop = floatArrayOf(0f, 0.6f, 0.2f)
        val headBottom = floatArrayOf(0f, 0.47f, 0.23f)
        for (z in floatArrayOf(-BASE, BASE)) {
            g.color(TYRE).ringX(m, 0f, r, z, r - 0.02f, 0.022f, 28, 7)
            g.color(RIM).ringX(m, 0f, r, z, r - 0.045f, 0.008f, 24, 5)
            for (k in 0 until 10) {
                val a = wheel + TAU * k / 10
                g.color(RIM).tube(m, floatArrayOf(0f, r, z, 0f, r + cos(a) * (r - 0.05f), z + sin(a) * (r - 0.05f)), 2, 0.0025f, 0.0025f, 3, caps = false)
            }
            g.color(BLACK).ellipsoid(m, 0f, r, z, 0.03f, 0.018f, 0.018f, 8, 5)
        }
        g.color(FRAME)
        val tubeR = 0.017f
        fun bar(a: FloatArray, b: FloatArray, rad: Float = tubeR) = g.tube(m, floatArrayOf(a[0], a[1], a[2], b[0], b[1], b[2]), 2, rad, rad, 7, caps = true)
        val crankC = floatArrayOf(0f, CRANK_Y, CRANK_Z)
        bar(crankC, seat)
        bar(crankC, headBottom, 0.02f)
        bar(floatArrayOf(0f, 0.5f, -0.14f), headTop)
        bar(headBottom, headTop, 0.022f)
        for (side in floatArrayOf(-0.04f, 0.04f)) {
            bar(floatArrayOf(side * 0.5f, CRANK_Y, CRANK_Z), floatArrayOf(side, r, -BASE), 0.011f)
            bar(floatArrayOf(side * 0.3f, 0.52f, -0.17f), floatArrayOf(side, r, -BASE), 0.01f)
            bar(floatArrayOf(side * 0.6f, 0.47f, 0.23f), floatArrayOf(side, r, BASE), 0.012f)
        }
        g.color(BLACK)
        bar(seat, floatArrayOf(0f, 0.6f, -0.19f), 0.012f)
        g.ellipsoid(m, 0f, 0.62f, -0.2f, 0.055f, 0.022f, 0.1f, 10, 6)
        bar(headTop, floatArrayOf(0f, 0.66f, 0.2f), 0.014f)
        g.tube(m, floatArrayOf(-0.22f, 0.67f, 0.17f, -0.1f, 0.665f, 0.2f, 0.1f, 0.665f, 0.2f, 0.22f, 0.67f, 0.17f), 4, 0.011f, 0.011f, 6, 3)
        // Cranks and pedals.
        for (side in intArrayOf(-1, 1)) {
            val a = crank + if (side > 0) 0f else PI.toFloat()
            val px = side * 0.085f
            val py = CRANK_Y + cos(a) * CRANK_L
            val pz = CRANK_Z + sin(a) * CRANK_L
            g.color(BLACK).tube(m, floatArrayOf(side * 0.04f, CRANK_Y, CRANK_Z, px, py, pz), 2, 0.009f, 0.009f, 5)
            g.box(m, px + side * 0.025f, py, pz, 0.03f, 0.008f, 0.03f)
        }
        g.color(RIM).ellipsoid(m, 0f, CRANK_Y, CRANK_Z, 0.012f, 0.045f, 0.045f, 10, 6)
    }

    /**
     * The rider: body from the bike's saddle to a head at (hx, hy, hz), arms to the grips, legs to
     * the pedals, and helmet straps into [straps] through [head].
     */
    fun rider(g: ColorGeo, straps: ColorGeo, bike: FloatArray, head: FloatArray, hx: Float, hy: Float, hz: Float, crank: Float) {
        val hip = FloatArray(4)
        world(bike, 0f, HIP_Y, HIP_Z, hip)
        // The neck's base sits under the chin, a little behind it.
        val neck = floatArrayOf(hx, hy - 0.24f, hz - 0.06f)
        val tx = neck[0] - hip[0]
        val ty = neck[1] - hip[1]
        val tz = neck[2] - hip[2]
        val tl = sqrt(tx * tx + ty * ty + tz * tz)
        val want = tl.coerceIn(0.26f, 0.6f)
        neck[0] = hip[0] + tx / tl * want
        neck[1] = hip[1] + ty / tl * want
        neck[2] = hip[2] + tz / tl * want
        val up = floatArrayOf(tx / tl, ty / tl, tz / tl)
        // Across the shoulders, square to the spine and to the camera.
        var sx = up[1]
        var sy = -up[0]
        val sl = max(1e-4f, sqrt(sx * sx + sy * sy))
        sx /= sl
        sy /= sl
        // Toward the camera, square to both.
        val fx = sy * up[2]
        val fy = -sx * up[2]
        val fz = sx * up[1] - sy * up[0]

        g.color(PANTS).ellipsoid(bike, 0f, HIP_Y + 0.01f, HIP_Z, 0.1f, 0.065f, 0.1f, 12, 7)
        val mid = floatArrayOf((hip[0] + neck[0]) / 2 - fx * 0.03f, (hip[1] + neck[1]) / 2 - fy * 0.03f, (hip[2] + neck[2]) / 2 - fz * 0.03f)
        val torso = floatArrayOf(
            sx, sy, 0f, 0f,
            up[0], up[1], up[2], 0f,
            fx, fy, fz, 0f,
            mid[0], mid[1], mid[2], 1f,
        )
        g.color(JERSEY).ellipsoid(torso, 0f, 0.0f, -0.01f, 0.125f, want * 0.55f, 0.08f, 16, 10)
        g.tube(IDENTITY, floatArrayOf(hip[0], hip[1] + 0.03f, hip[2], mid[0], mid[1], mid[2], neck[0], neck[1], neck[2]), 3, 0.085f, 0.09f, 10, 3)
        // A turtleneck up under the chin, and a yoke across the shoulders so they round off smoothly.
        g.tube(IDENTITY, floatArrayOf(neck[0], neck[1], neck[2], hx, hy - 0.16f, hz - 0.04f), 2, 0.06f, 0.05f, 9)
        g.tube(
            IDENTITY,
            floatArrayOf(
                neck[0] - sx * 0.15f - up[0] * 0.05f, neck[1] - sy * 0.15f - up[1] * 0.05f, neck[2] - up[2] * 0.05f,
                neck[0] + up[0] * 0.005f, neck[1] + up[1] * 0.005f, neck[2] + up[2] * 0.005f,
                neck[0] + sx * 0.15f - up[0] * 0.05f, neck[1] + sy * 0.15f - up[1] * 0.05f, neck[2] - up[2] * 0.05f,
            ),
            3, 0.052f, 0.052f, 10, 3,
        )

        val shoulder = FloatArray(3)
        val grip = FloatArray(4)
        val elbow = FloatArray(3)
        val hipS = FloatArray(4)
        val foot = FloatArray(4)
        val knee = FloatArray(3)
        for (side in intArrayOf(-1, 1)) {
            // Arms.
            shoulder[0] = neck[0] + sx * side * 0.15f - up[0] * 0.05f
            shoulder[1] = neck[1] + sy * side * 0.15f - up[1] * 0.05f
            shoulder[2] = neck[2] - up[2] * 0.05f
            world(bike, side * 0.2f, 0.675f, 0.17f, grip)
            // Arms nearly straight down to the bars, elbows easing out and back.
            val reach = sqrt((grip[0] - shoulder[0]).let { it * it } + (grip[1] - shoulder[1]).let { it * it } + (grip[2] - shoulder[2]).let { it * it })
            val bone = (reach * 0.54f).coerceIn(0.14f, 0.3f)
            ik(shoulder, grip, bone, bone, sx * side * 0.6f, -0.2f, -0.6f, elbow)
            g.color(JERSEY)
            g.tube(IDENTITY, floatArrayOf(shoulder[0], shoulder[1], shoulder[2], elbow[0], elbow[1], elbow[2]), 2, 0.05f, 0.04f, 8)
            g.tube(IDENTITY, floatArrayOf(elbow[0], elbow[1], elbow[2], grip[0], grip[1] + 0.02f, grip[2]), 2, 0.04f, 0.032f, 8)
            g.color(GLOVE).ellipsoid(bike, side * 0.2f, 0.69f, 0.175f, 0.036f, 0.032f, 0.042f, 10, 6)
            g.ellipsoid(bike, side * 0.175f, 0.685f, 0.2f, 0.014f, 0.014f, 0.022f, 7, 4)

            // Legs.
            world(bike, side * 0.075f, HIP_Y, HIP_Z + 0.02f, hipS)
            val a = crank + if (side > 0) 0f else PI.toFloat()
            world(bike, side * 0.11f, CRANK_Y + cos(a) * CRANK_L + 0.045f, CRANK_Z + sin(a) * CRANK_L - 0.01f, foot)
            ik(hipS, foot, 0.27f, 0.27f, sx * side * 0.25f, 0.1f, 1f, knee)
            g.color(PANTS).tube(IDENTITY, floatArrayOf(hipS[0], hipS[1], hipS[2], knee[0], knee[1], knee[2]), 2, 0.06f, 0.048f, 8)
            g.tube(IDENTITY, floatArrayOf(knee[0], knee[1], knee[2], foot[0], foot[1], foot[2]), 2, 0.047f, 0.038f, 8)
            g.color(SHOE).ellipsoid(bike, side * 0.11f, CRANK_Y + cos(a) * CRANK_L + 0.02f, CRANK_Z + sin(a) * CRANK_L + 0.035f, 0.045f, 0.038f, 0.085f, 10, 6)
        }

        // Helmet straps, in head units: from the helmet's sides down the cheeks to a buckle under the chin.
        straps.color(STRAP)
        for (side in intArrayOf(-1, 1)) {
            val sd = side.toFloat()
            straps.tube(head, floatArrayOf(sd * STRAP_TOP[0], STRAP_TOP[1], STRAP_TOP[2], sd * 0.118f, 0.0f, 0.065f, sd * 0.1f, -0.075f, 0.085f, sd * 0.05f, -0.128f, 0.1f, 0f, -0.138f, 0.104f), 5, 0.006f, 0.006f, 5, 3)
            straps.tube(head, floatArrayOf(sd * STRAP_TOP[3], STRAP_TOP[4], STRAP_TOP[5], sd * 0.118f, 0.0f, 0.065f), 2, 0.0055f, 0.0055f, 5)
        }
        straps.box(head, 0f, -0.138f, 0.104f, 0.016f, 0.01f, 0.008f)
    }

    private val IDENTITY = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    // Two-bone reach from [a] to [b] with bones [l1] and [l2]; the joint bends toward the hint
    // (px, py, pz). Writes the joint into [out]. Past full reach the limb just straightens.
    private fun ik(a: FloatArray, b: FloatArray, l1: Float, l2: Float, px: Float, py: Float, pz: Float, out: FloatArray) {
        var dx = b[0] - a[0]
        var dy = b[1] - a[1]
        var dz = b[2] - a[2]
        val len = max(1e-4f, sqrt(dx * dx + dy * dy + dz * dz))
        dx /= len
        dy /= len
        dz /= len
        val d = len.coerceIn(kotlin.math.abs(l1 - l2) + 1e-3f, l1 + l2 - 1e-3f)
        val along = (l1 * l1 - l2 * l2 + d * d) / (2 * d)
        val h = sqrt(max(0f, l1 * l1 - along * along))
        val dot = px * dx + py * dy + pz * dz
        var ox = px - dx * dot
        var oy = py - dy * dot
        var oz = pz - dz * dot
        val ol = max(1e-4f, sqrt(ox * ox + oy * oy + oz * oz))
        ox /= ol
        oy /= ol
        oz /= ol
        out[0] = a[0] + dx * along + ox * h
        out[1] = a[1] + dy * along + oy * h
        out[2] = a[2] + dz * along + oz * h
    }
}
