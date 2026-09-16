package net.sgransoft.portalsnap

import android.opengl.GLES20
import android.opengl.Matrix
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

// Cool's aviators as real 3D: gold wire frames with a double bridge, teardrop lenses that shade
// from dark at the top to clear-ish brown at the bottom, and temple arms that run back along the
// head to the ears. The arms are the reason this is 3D at all. Before anything is drawn, a head
// shape goes into the depth buffer only, so an arm on the far side of a turned head is hidden by
// the head that is in front of it, and the tips behind the ears never show.

/** One person's aviators for this frame. */
class Aviators3D {
    /** Glasses units to View3D world (frame px): see [AviatorShape]. */
    val model = FloatArray(16)
    /** Half the head's width at the temples, in glasses units: where the arms run. */
    var halfHead = AviatorShape.HALF_HEAD
}

/**
 * The glasses in their own units: one unit is the distance between the outer eye corners, the
 * origin is midway between them, x runs toward the person's left eye (right in the frame), y down
 * and z away from the camera. Sized off a 58 mm aviator on a face 90 mm across the eye corners.
 */
object AviatorShape {
    const val LENS_X = 0.42f
    /** Aviators sit low: the pupil is in the lens's upper half. */
    const val LENS_Y = 0.07f
    const val HALF_W = 0.33f
    const val HALF_H = 0.285f
    /** The lens's inner edge, in front of the eye corners. */
    const val LENS_Z = -0.30f
    const val HALF_HEAD = 0.87f
    private const val RIM = 0.022f
    private const val WIRE = 0.02f
    private const val SAMPLES = 44

    // The right-hand lens's outline as (u, v): u outward to the temple and v down, both -1..1.
    // Straight across the top, a rounded corner at the temple, and deepest on the nose side,
    // which is what makes it an aviator rather than an oval.
    private val CTRL = floatArrayOf(
        -0.80f, -0.93f, -0.20f, -1.00f, 0.50f, -0.98f, 0.92f, -0.84f, 1.00f, -0.45f,
        0.95f, 0.10f, 0.72f, 0.58f, 0.30f, 0.90f, -0.25f, 1.00f, -0.68f, 0.88f,
        -0.93f, 0.50f, -1.00f, 0.00f, -0.97f, -0.56f,
    )

    /** The outline sampled evenly round a closed Catmull-Rom curve, as (u, v) pairs. */
    private val outlineUv: FloatArray by lazy {
        val n = CTRL.size / 2
        val out = FloatArray(SAMPLES * 2)
        for (s in 0 until SAMPLES) {
            val f = s.toFloat() * n / SAMPLES
            val i = f.toInt()
            val t = f - i
            for (a in 0..1) {
                val p0 = CTRL[((i - 1 + n) % n) * 2 + a]
                val p1 = CTRL[(i % n) * 2 + a]
                val p2 = CTRL[((i + 1) % n) * 2 + a]
                val p3 = CTRL[((i + 2) % n) * 2 + a]
                val t2 = t * t
                out[s * 2 + a] = 0.5f * (2 * p1 + (p2 - p0) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (3 * p1 - p0 - 3 * p2 + p3) * t2 * t)
            }
        }
        out
    }

    fun x(side: Int, u: Float) = side * (LENS_X + u * HALF_W)

    fun y(v: Float) = LENS_Y + v * HALF_H

    // The lens bows toward the camera in the middle, wraps back toward the temple and tips its
    // bottom in toward the cheek.
    fun z(u: Float, v: Float) = LENS_Z + 0.05f * (u * u + u) + 0.03f * (v * v + v)

    /** Both lenses: a fan of rings out to the outline, curved as [z]. */
    fun lenses(): Mesh {
        val rings = 5
        val data = ArrayList<Float>()
        val idx = ArrayList<Int>()
        for (side in intArrayOf(-1, 1)) {
            val base = data.size / 6
            fun vertex(u: Float, v: Float) {
                // z = f(x, y): the normal is (-dz/dx, -dz/dy, 1), pointing away from the camera.
                val dzdx = 0.05f * (2 * u + 1) / HALF_W * side
                val dzdy = 0.03f * (2 * v + 1) / HALF_H
                val len = sqrt(dzdx * dzdx + dzdy * dzdy + 1f)
                data += listOf(x(side, u), y(v), z(u, v), -dzdx / len, -dzdy / len, 1f / len)
            }
            vertex(0f, 0f)
            for (r in 1..rings) {
                val k = r.toFloat() / rings
                for (s in 0 until SAMPLES) vertex(outlineUv[s * 2] * k, outlineUv[s * 2 + 1] * k)
            }
            for (s in 0 until SAMPLES) idx += listOf(base, base + 1 + s, base + 1 + (s + 1) % SAMPLES)
            for (r in 1 until rings) {
                val a = base + 1 + (r - 1) * SAMPLES
                val b = a + SAMPLES
                for (s in 0 until SAMPLES) {
                    val s1 = (s + 1) % SAMPLES
                    idx += listOf(a + s, b + s, b + s1, a + s, b + s1, a + s1)
                }
            }
        }
        return Mesh(data.toFloatArray(), idx.toIntArray())
    }

    /** A unit sphere, scaled into the head that hides the arms. */
    fun sphere(): Mesh = HamsterMeshes.surface(24, 16) { c, s, v, o ->
        val phi = Math.PI.toFloat() * v
        o[0] = sin(phi) * c
        o[1] = -cos(phi)
        o[2] = sin(phi) * s
    }

    /**
     * The head, in glasses units: centre and radii for [sphere]. Its sides stop just inside the
     * arms, so from the front they sit on the edge of the head the way real ones do, and its
     * front stops behind the bridge and the nose pads.
     */
    fun head(halfHead: Float, out: FloatArray) {
        Matrix.setIdentityM(out, 0)
        Matrix.translateM(out, 0, 0f, 0.25f, 0.85f)
        Matrix.scaleM(out, 0, halfHead - 0.03f, 1.35f, 0.95f)
    }

    private val ctrl = FloatArray(3 * 64)

    private fun tube(g: ColorGeo, m: FloatArray, r0: Float, r1: Float, sides: Int, vararg p: Float) {
        g.tube(m, p, p.size / 3, r0, r1, sides, 4)
    }

    /** The metal and the temple tips for one pair, straight into world space through its model. */
    fun frame(g: ColorGeo, a: Aviators3D) {
        val m = a.model
        val w = a.halfHead
        for (side in intArrayOf(-1, 1)) {
            g.color(GOLD, METAL)
            // The rim, once round the lens and a little past where it started so the seam is closed.
            var n = 0
            for (k in 0..SAMPLES + 1) {
                val s = k % SAMPLES
                val u = outlineUv[s * 2]
                val v = outlineUv[s * 2 + 1]
                ctrl[n++] = x(side, u)
                ctrl[n++] = y(v)
                ctrl[n++] = z(u, v) - 0.004f
            }
            g.tube(m, ctrl, n / 3, RIM, RIM, 8, 1, caps = false)

            // End piece: from the rim's top corner at the temple, back to the hinge.
            val cu = 0.9f
            val cv = -0.78f
            val hinge = w + 0.01f
            tube(g, m, WIRE * 1.3f, WIRE * 1.2f, 8,
                x(side, cu), y(cv), z(cu, cv),
                side * (hinge - 0.04f), -0.155f, -0.22f,
                side * hinge, -0.15f, -0.13f)
            g.ellipsoid(m, side * hinge, -0.15f, -0.13f, 0.03f, 0.036f, 0.045f, 10, 7)

            // The arm: straight back along the side of the head, over the ear and down behind it.
            g.color(GOLD, METAL)
            tube(g, m, WIRE, WIRE, 8,
                side * hinge, -0.15f, -0.13f,
                side * (w + 0.02f), -0.14f, 0.3f,
                side * (w + 0.02f), -0.12f, 0.7f,
                side * (w - 0.02f), -0.07f, 0.97f,
                side * (w - 0.12f), 0.1f, 1.1f)
            // Its tip, dark plastic over the bend, which tucks in behind the ear out of sight.
            g.color(TIP, 0f)
            tube(g, m, WIRE * 1.4f, WIRE * 1.8f, 10,
                side * (w + 0.02f), -0.11f, 0.84f,
                side * (w - 0.02f), -0.07f, 0.97f,
                side * (w - 0.12f), 0.1f, 1.1f,
                side * (w - 0.18f), 0.32f, 1.12f)

            // Nose pad on a little wire arm from the rim, sitting back against the nose.
            g.color(GOLD, METAL)
            tube(g, m, WIRE * 0.6f, WIRE * 0.6f, 6,
                x(side, -0.98f), y(0.05f), z(-0.98f, 0.05f),
                side * 0.07f, 0.12f, -0.2f,
                side * 0.075f, 0.16f, -0.17f)
            g.color(PAD, 0f)
            g.ellipsoid(m, side * 0.075f, 0.18f, -0.165f, 0.028f, 0.05f, 0.014f, 10, 7)
        }

        // The double bridge: a straight bar across the tops, and an arch below it over the nose.
        g.color(GOLD, METAL)
        val top = y(-0.93f) - 0.01f
        tube(g, m, WIRE, WIRE, 8,
            -0.2f, top, z(-0.8f, -0.93f),
            0f, top - 0.004f, z(-0.8f, -0.93f) - 0.02f,
            0.2f, top, z(-0.8f, -0.93f))
        val lv = -0.35f
        val lz = z(-0.99f, lv)
        tube(g, m, WIRE, WIRE, 8,
            -LENS_X + 0.99f * HALF_W + 0.01f, y(lv), lz,
            -0.055f, y(lv) - 0.06f, lz - 0.025f,
            0f, y(lv) - 0.075f, lz - 0.03f,
            0.055f, y(lv) - 0.06f, lz - 0.025f,
            LENS_X - 0.99f * HALF_W - 0.01f, y(lv), lz)
    }

    private const val GOLD = 0xE8B85A
    private const val TIP = 0x1C1712
    private const val PAD = 0x9C968C
    /** ColorGeo's glow channel, read by the frame shader as "this part is metal". */
    const val METAL = 1f
}

object AviatorShaders {
    // A made-up room for the metal and lenses to reflect, bright above and dark below, and KEY, a
    // light up to one side in front that both shaders take their glints from. World y is down and
    // z away.
    private val ROOM = """
        const vec3 KEY = vec3(-0.4851, -0.7276, -0.4851);
        vec3 room(vec3 d) {
            float up = -d.y;
            vec3 c = up > 0.0
                ? mix(vec3(0.55, 0.53, 0.50), vec3(1.0, 0.98, 0.94), smoothstep(0.0, 0.7, up))
                : mix(vec3(0.55, 0.53, 0.50), vec3(0.10, 0.09, 0.08), smoothstep(0.0, 0.4, -up));
            return c;
        }
    """.trimIndent()

    val FRAME = """
        #version 300 es
        precision highp float;
        in vec3 vWorld;
        in vec3 vNormal;
        in vec4 vColor;
        uniform vec3 uEye;
        out vec4 outColor;
        $ROOM
        void main() {
            vec3 n = normalize(vNormal);
            vec3 v = normalize(uEye - vWorld);
            if (dot(n, v) < 0.0) n = -n;
            vec3 r = reflect(-v, n);
            vec3 col;
            if (vColor.a > 0.5) {
                // Gold: the room tinted by the metal, and a sharp white glint off the window.
                col = vColor.rgb * (0.22 + 0.8 * room(r)) + vec3(1.0, 0.93, 0.78) * pow(max(dot(r, KEY), 0.0), 24.0) * 0.8;
            } else {
                float wrap = dot(n, KEY) * 0.5 + 0.5;
                col = vColor.rgb * (0.45 + 0.7 * wrap) + vec3(0.35) * pow(max(dot(r, KEY), 0.0), 30.0);
            }
            outColor = vec4(col, 1.0);
        }
    """.trimIndent()

    val LENS = """
        #version 300 es
        precision highp float;
        in vec3 vWorld;
        in vec3 vNormal;
        in vec3 vLocal;
        uniform vec3 uEye;
        out vec4 outColor;
        const float TOP = ${AviatorShape.LENS_Y - AviatorShape.HALF_H};
        const float TALL = ${AviatorShape.HALF_H * 2};
        const float LX = ${AviatorShape.LENS_X};
        $ROOM
        void main() {
            vec3 n = normalize(vNormal);
            vec3 v = normalize(uEye - vWorld);
            if (dot(n, v) < 0.0) n = -n;
            vec3 r = reflect(-v, n);
            // Gradient tint: near black at the top, a see-through brown at the bottom.
            float t = clamp((vLocal.y - TOP) / TALL, 0.0, 1.0);
            float g = smoothstep(0.2, 1.0, t);
            vec3 tint = mix(vec3(0.06, 0.05, 0.05), vec3(0.36, 0.24, 0.15), g);
            float a = mix(0.94, 0.6, g);
            float fres = 0.06 + 0.6 * pow(1.0 - max(dot(n, v), 0.0), 3.0);
            // A soft diagonal sweep of the room across each lens, the way a curved lens catches it.
            float across = (abs(vLocal.x) - LX) * sign(vLocal.x) + (vLocal.y - 0.07) * 0.8;
            float sweep = smoothstep(0.13, 0.0, abs(across + 0.1)) * 0.22 + smoothstep(0.05, 0.0, abs(across - 0.1)) * 0.12;
            vec3 refl = room(r) * fres + vec3(sweep) * (1.0 - 0.5 * g);
            float glint = pow(max(dot(r, KEY), 0.0), 200.0) * 1.5;
            vec3 col = tint * a + refl * 0.7 + vec3(glint);
            outColor = vec4(col, clamp(a + glint + sweep * 0.3, 0.0, 1.0));
        }
    """.trimIndent()

    val DEPTH_ONLY = """
        #version 300 es
        precision mediump float;
        out vec4 outColor;
        void main() { outColor = vec4(0.0); }
    """.trimIndent()
}

/** Draws Aviators3D into whatever framebuffer is bound, which needs a depth buffer. */
class AviatorRenderer {
    private val pFrame = Program(RideShaders.COLOR_VERTEX, AviatorShaders.FRAME)
    private val pLens = Program(Shaders3D.VERTEX, AviatorShaders.LENS)
    private val pHead = Program(Shaders3D.VERTEX, AviatorShaders.DEPTH_ONLY)
    private val lenses = AviatorShape.lenses()
    private val sphere = AviatorShape.sphere()
    private val geo = ColorGeo(8192, 16384)
    private val frames = DynamicColorMesh()
    private val head = FloatArray(16)
    private val headModel = FloatArray(16)
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    fun draw(list: List<Aviators3D>) {
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glDepthMask(true)
        GLES20.glClearDepthf(1f)
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glDisable(GLES20.GL_BLEND)

        // The heads, into depth only.
        GLES20.glColorMask(false, false, false, false)
        pHead.use()
        GLES20.glUniformMatrix4fv(pHead.u("uViewProj"), 1, false, View3D.viewProj, 0)
        for (a in list) {
            AviatorShape.head(a.halfHead, head)
            Matrix.multiplyMM(headModel, 0, a.model, 0, head, 0)
            GLES20.glUniformMatrix4fv(pHead.u("uModel"), 1, false, headModel, 0)
            sphere.draw(pHead)
        }
        GLES20.glColorMask(true, true, true, true)

        geo.reset()
        for (a in list) AviatorShape.frame(geo, a)
        frames.update(geo)
        pFrame.use()
        GLES20.glUniformMatrix4fv(pFrame.u("uViewProj"), 1, false, View3D.viewProj, 0)
        GLES20.glUniformMatrix4fv(pFrame.u("uModel"), 1, false, identity, 0)
        GLES20.glUniform3fv(pFrame.u("uEye"), 1, View3D.eye, 0)
        frames.draw(pFrame)

        // Lenses last, blended over the eyes and everything behind them, without hiding the rims.
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)
        pLens.use()
        GLES20.glUniformMatrix4fv(pLens.u("uViewProj"), 1, false, View3D.viewProj, 0)
        GLES20.glUniform3fv(pLens.u("uEye"), 1, View3D.eye, 0)
        for (a in list) {
            GLES20.glUniformMatrix4fv(pLens.u("uModel"), 1, false, a.model, 0)
            lenses.draw(pLens)
        }

        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }
}

/** Poses a pair of aviators on a face: at the eye corners, turned and nodded with the head. */
internal fun aviatorsOn(f: Face): Aviators3D {
    val a = Aviators3D()
    // The mesh's pose when there is one; otherwise the nose's offset, as the other props do.
    val turn = f.turn ?: (-f.yaw * 28f)
    // Eye distance is measured flat, so a turned head's is short by the cosine of the turn.
    val unit = f.eyeDist / max(0.6f, cos(Math.toRadians(turn.toDouble()).toFloat()))
    a.halfHead = (f.headSpan / 2).coerceIn(0.72f, 1.05f)
    val m = a.model
    Matrix.setIdentityM(m, 0)
    Matrix.translateM(m, 0, f.cx, f.cy, 0f)
    Matrix.rotateM(m, 0, deg(f.angle), 0f, 0f, 1f)
    Matrix.rotateM(m, 0, turn, 0f, 1f, 0f)
    Matrix.scaleM(m, 0, unit, unit, unit)
    return a
}
