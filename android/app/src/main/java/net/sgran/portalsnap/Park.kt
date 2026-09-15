package net.sgran.portalsnap

import android.opengl.Matrix
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Bike Ride's park, in metres: x right, y up, z toward the camera, the path running along z. It's
 * built in [CHUNK]-long stretches (z from -CHUNK to 0) that the renderer lays end to end, two
 * variants alternating, so the ride never visibly repeats. The ground is one shader
 * (RideShaders.GROUND) that knows these widths.
 */
object Park {
    const val CHUNK = 80f
    /** Half the asphalt's width, then the orange edging and the brick path beside it. */
    const val ROAD = 1.4f
    const val CURB = 0.12f
    const val BRICK = 0.6f
    const val GRASS = ROAD + CURB + BRICK
    /** Scroll wraps here, a whole number of chunk pairs, to keep floats small. */
    const val LOOP = CHUNK * 2 * 50

    private const val TRUNK = 0x9c5a37
    private const val PINE_A = 0x3e9d6d
    private const val PINE_B = 0x2c8660
    private const val PINE_UNDER = 0x245c56
    private val LEAVES = intArrayOf(0x7ccf42, 0x6cc23a, 0x8fd84f, 0x62b636)
    private const val BUSH = 0x8bd653
    private const val WOOD = 0xae7646
    private const val IRON = 0x3a302b
    private const val POLE = 0x4b3727
    private const val LAMP = 0xfff1b4
    private const val CAN = 0x39a7df
    private const val CAN_RIM = 0x2885b6
    private const val RECYCLE = 0x32a152
    private const val RECYCLE_LID = 0x267f42

    private class Spot(val x: Float, val z: Float, val r: Float)

    fun build(variant: Int): ColorGeo {
        val g = ColorGeo(60_000, 90_000)
        val rnd = Random(1234L + variant * 7919L)
        val taken = ArrayList<Spot>()
        fun free(x: Float, z: Float, r: Float) = taken.none { hypot(it.x - x, it.z - z) < it.r + r }
        fun take(x: Float, z: Float, r: Float) = taken.add(Spot(x, z, r))
        fun f(a: Float, b: Float) = a + (b - a) * rnd.nextFloat()

        // Rest stops: a bench and a bin on the left, a lamp and a recycling bin on the right, as the
        // reference passes them, and an extra lamp between stops.
        val stopOffset = if (variant == 0) 0f else 20f
        for (k in 0 until 2) {
            val z0 = -18f - stopOffset - k * 40f
            if (z0 > -2f || z0 < -CHUNK + 2f) continue
            bench(g, -(GRASS + 0.55f), z0, facing = 1f)
            take(-(GRASS + 0.55f), z0, 1.3f)
            trashCan(g, -(GRASS + 0.4f), z0 + 1.6f)
            take(-(GRASS + 0.4f), z0 + 1.6f, 0.5f)
            lamp(g, ROAD + CURB + BRICK * 0.75f, z0 - 3f)
            recycleBin(g, GRASS + 0.6f, z0 + 2.2f)
            take(GRASS + 0.6f, z0 + 2.2f, 0.7f)
        }
        lamp(g, ROAD + CURB + BRICK * 0.75f, -40f + stopOffset * 0.5f)

        for (side in intArrayOf(-1, 1)) {
            // Bushes just off the path.
            repeat(4) {
                val x = side * f(GRASS + 0.8f, GRASS + 1.6f)
                val z = f(-CHUNK, 0f)
                val r = f(0.45f, 0.7f)
                if (free(x, z, r)) {
                    take(x, z, r)
                    g.color(BUSH).ellipsoid(IDENTITY, x, r * 0.75f, z, r, r * 0.85f, r, 14, 9)
                }
            }
            // The row along the path, then a looser second row.
            var z = -f(0f, 3f)
            while (z > -CHUNK) {
                val x = side * f(GRASS + 1.3f, GRASS + 2.8f)
                if (free(x, z, 0.9f)) {
                    take(x, z, 0.9f)
                    if (rnd.nextFloat() < 0.55f) broadleaf(g, rnd, x, z, f(4.2f, 6.2f)) else pine(g, rnd, x, z, f(5.5f, 8f))
                }
                z -= f(4.2f, 6.8f)
            }
            z = -f(0f, 3f)
            while (z > -CHUNK) {
                val x = side * f(GRASS + 4f, GRASS + 8.5f)
                if (free(x, z, 1.2f)) {
                    take(x, z, 1.2f)
                    if (rnd.nextFloat() < 0.5f) broadleaf(g, rnd, x, z, f(4.5f, 7f)) else pine(g, rnd, x, z, f(6f, 9.5f))
                }
                z -= f(3.5f, 6f)
            }
            // The forest behind, mostly pines, filling the view down the path.
            repeat(70) {
                val x = side * f(GRASS + 9f, 48f)
                val zz = f(-CHUNK, 0f)
                if (free(x, zz, 1.6f)) {
                    take(x, zz, 1.6f)
                    if (rnd.nextFloat() < 0.25f) broadleaf(g, rnd, x, zz, f(5f, 8f)) else pine(g, rnd, x, zz, f(7f, 12f))
                }
            }
        }
        return g
    }

    private val IDENTITY = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    private fun at(x: Float, z: Float, spinDeg: Float): FloatArray {
        val m = FloatArray(16)
        Matrix.setIdentityM(m, 0)
        Matrix.translateM(m, 0, x, 0f, z)
        Matrix.rotateM(m, 0, spinDeg, 0f, 1f, 0f)
        return m
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = ((a shr s and 255) + ((b shr s and 255) - (a shr s and 255)) * t).toInt().coerceIn(0, 255)
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    // Three stacked faceted cones on a short trunk, [s] tall.
    private fun pine(g: ColorGeo, rnd: Random, x: Float, z: Float, s: Float) {
        val m = at(x, z, rnd.nextFloat() * 360f)
        g.color(TRUNK).cylinder(m, 0f, 0f, 0f, 0.04f * s, 0.03f * s, 0.2f * s, 7, caps = false)
        val body = mix(PINE_A, PINE_B, rnd.nextFloat())
        for (k in 0 until 3) {
            val y = s * (0.14f + 0.25f * k)
            val r = s * (0.29f - 0.065f * k)
            val h = s * (0.46f - 0.05f * k)
            g.color(body).cone(m, 0f, y, 0f, r, h, 9, PINE_UNDER, spin = k * 0.35f)
        }
    }

    // A bendy trunk with a branch or two, each ending in a ball of leaves, under a crown of balls
    // with an egg-shaped one on top. [s] tall.
    private fun broadleaf(g: ColorGeo, rnd: Random, x: Float, z: Float, s: Float) {
        val m = at(x, z, rnd.nextFloat() * 360f)
        val lean = (rnd.nextFloat() - 0.5f) * 0.14f * s
        val leaf = LEAVES[rnd.nextInt(LEAVES.size)]
        g.color(TRUNK).tube(m, floatArrayOf(0f, 0f, 0f, lean * 0.2f, 0.3f * s, 0f, lean, 0.66f * s, 0f), 3, 0.05f * s, 0.03f * s, 7, 3, caps = false)
        val branches = 1 + rnd.nextInt(2)
        for (k in 0 until branches) {
            val a = rnd.nextFloat() * TAU
            val y0 = s * (0.28f + 0.13f * k)
            val dx = cos(a)
            val dz = sin(a)
            val out = s * (0.17f + 0.06f * rnd.nextFloat())
            val bx = lean * y0 / (0.66f * s)
            g.color(TRUNK).tube(
                m,
                floatArrayOf(bx, y0, 0f, bx + dx * out * 0.75f, y0 + 0.02f * s, dz * out * 0.75f, bx + dx * out, y0 + 0.15f * s, dz * out),
                3, 0.022f * s, 0.014f * s, 6, 3, caps = false,
            )
            g.color(mix(leaf, 0xffffff, 0.06f)).ellipsoid(m, bx + dx * out, y0 + 0.22f * s, dz * out, 0.085f * s, 0.085f * s, 0.085f * s, 12, 8, top = 1.35f)
        }
        val cy = 0.74f * s
        g.color(leaf).ellipsoid(m, lean, cy, 0f, 0.2f * s, 0.18f * s, 0.2f * s, 16, 10)
        val puffs = 2 + rnd.nextInt(2)
        for (k in 0 until puffs) {
            val a = TAU * k / puffs + rnd.nextFloat()
            val rr = s * (0.11f + 0.04f * rnd.nextFloat())
            g.color(mix(leaf, 0x2d6a1c, 0.12f * rnd.nextFloat())).ellipsoid(m, lean + cos(a) * 0.17f * s, cy - 0.05f * s + 0.08f * s * rnd.nextFloat(), sin(a) * 0.17f * s, rr, rr, rr, 12, 8)
        }
        g.color(mix(leaf, 0xffffff, 0.08f)).ellipsoid(m, lean + 0.03f * s, cy + 0.2f * s, 0.02f * s, 0.1f * s, 0.1f * s, 0.1f * s, 12, 8, top = 1.7f)
    }

    // A slatted park bench whose seat faces +x when [facing] is 1.
    private fun bench(g: ColorGeo, x: Float, z: Float, facing: Float) {
        val m = at(x, z, if (facing > 0) 0f else 180f)
        val half = 0.9f
        g.color(WOOD)
        for (k in 0 until 3) g.box(m, -0.16f + k * 0.14f, 0.45f, 0f, 0.055f, 0.018f, half)
        for (k in 0 until 3) g.box(m, -0.27f - k * 0.02f, 0.6f + k * 0.13f, 0f, 0.016f, 0.045f, half)
        g.color(IRON)
        for (end in floatArrayOf(-half + 0.1f, half - 0.1f)) {
            g.box(m, 0.08f, 0.22f, end, 0.02f, 0.22f, 0.025f)
            g.box(m, -0.24f, 0.45f, end, 0.02f, 0.45f, 0.025f)
            g.box(m, -0.08f, 0.42f, end, 0.2f, 0.018f, 0.025f)
            g.box(m, -0.06f, 0.64f, end, 0.2f, 0.016f, 0.025f)
        }
    }

    // A park lamp: a dark post and a glowing lantern with a little roof.
    private fun lamp(g: ColorGeo, x: Float, z: Float) {
        val m = at(x, z, 20f)
        g.color(POLE).cylinder(m, 0f, 0f, 0f, 0.1f, 0.08f, 0.3f, 10)
        g.cylinder(m, 0f, 0.3f, 0f, 0.05f, 0.04f, 2.75f, 8, caps = false)
        g.cylinder(m, 0f, 3.02f, 0f, 0.1f, 0.12f, 0.05f, 6)
        g.color(LAMP, glow = 0.85f).cylinder(m, 0f, 3.07f, 0f, 0.1f, 0.15f, 0.36f, 6, caps = false)
        g.color(POLE)
        for (k in 0 until 6) {
            val a = TAU * k / 6
            g.tube(m, floatArrayOf(cos(a) * 0.1f, 3.07f, sin(a) * 0.1f, cos(a) * 0.15f, 3.43f, sin(a) * 0.15f), 2, 0.012f, 0.012f, 4, caps = false)
        }
        g.cone(m, 0f, 3.43f, 0f, 0.21f, 0.17f, 6, POLE)
        g.ellipsoid(m, 0f, 3.63f, 0f, 0.035f, 0.035f, 0.035f, 8, 5)
    }

    private fun trashCan(g: ColorGeo, x: Float, z: Float) {
        val m = at(x, z, 0f)
        g.color(CAN).cylinder(m, 0f, 0f, 0f, 0.2f, 0.23f, 0.72f, 14)
        g.color(CAN_RIM).cylinder(m, 0f, 0.72f, 0f, 0.245f, 0.245f, 0.05f, 14)
    }

    private fun recycleBin(g: ColorGeo, x: Float, z: Float) {
        val m = at(x, z, -12f)
        g.color(RECYCLE).box(m, 0f, 0.42f, 0f, 0.3f, 0.42f, 0.3f)
        g.color(RECYCLE_LID).box(m, 0f, 0.87f, 0f, 0.33f, 0.035f, 0.33f)
        // The recycling mark: three little white arrows' worth of bars, facing the path.
        g.color(0xe8f3ea)
        for (k in 0 until 3) {
            val a = TAU * k / 3 + 0.5f
            g.box(m, -0.305f, 0.45f + sin(a) * 0.08f, cos(a) * 0.08f, 0.004f, 0.02f + abs(sin(a)) * 0.02f, 0.02f + abs(cos(a)) * 0.02f)
        }
    }
}
