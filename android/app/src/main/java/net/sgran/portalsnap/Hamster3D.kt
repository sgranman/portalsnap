package net.sgran.portalsnap

import android.opengl.GLES20
import android.opengl.Matrix
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// Hamster's props as real 3D, after its reference video (589562750311569): fuzzy cup ears, a
// glossy pink nose, a chubby ridged carrot with a clover of leaves, and two pink paws with furry
// cuffs holding it. They're drawn in the View3D pass, over the flat blush and whiskers.

/** One person's hamster props for this frame, posed in View3D world space (frame pixels). */
class Hamster3D {
    /** Model matrices, each in its part's own units (see HamsterMeshes). */
    val ears = ArrayList<FloatArray>()
    val nose = FloatArray(16)
    val paws = ArrayList<FloatArray>()
    /** Null once the last bite has gone down. */
    var carrot: FloatArray? = null
    /** How much of the carrot is eaten, in carrot units from its tip (0 to 1). */
    var carrotCut = 0f
    val leaves = ArrayList<FloatArray>()
    var leafAlpha = 1f
}

object HamsterMeshes {
    /** The carrot's radius at its tip and at its wide end, in lengths. */
    const val CARROT_TIP = 0.1f
    const val CARROT_END = 0.4f

    fun carrotRadius(v: Float): Float {
        val tip = sqrt(min(1f, v / 0.06f))
        val end = if (v > 0.78f) sqrt(max(0f, 1f - ((v - 0.78f) / 0.22f).pow(2))) else 1f
        return (CARROT_TIP + (CARROT_END - CARROT_TIP) * v.pow(0.6f)) * tip * end
    }

    /**
     * A closed surface over (angle round, 0..1 from top to bottom), shaped by [shape], which writes
     * a point into its out array. Normals come from neighbouring points and face away from the
     * middle.
     */
    fun surface(cols: Int, rows: Int, shape: (c: Float, s: Float, v: Float, out: FloatArray) -> Unit): Mesh {
        val pts = Array((rows + 1) * (cols + 1)) { FloatArray(3) }
        val e = 0.0015f
        for (i in 0..rows) {
            val v = e + (1f - 2 * e) * i / rows
            for (j in 0..cols) {
                val th = (2 * PI * j / cols).toFloat()
                shape(cos(th), sin(th), v, pts[i * (cols + 1) + j])
            }
        }
        val mid = FloatArray(3)
        for (p in pts) for (k in 0 until 3) mid[k] += p[k] / pts.size
        val data = FloatArray(pts.size * 6)
        val a = FloatArray(3)
        val b = FloatArray(3)
        for (i in 0..rows) {
            for (j in 0..cols) {
                val p = pts[i * (cols + 1) + j]
                val l = pts[i * (cols + 1) + (if (j == 0) cols - 1 else j - 1)]
                val r = pts[i * (cols + 1) + (if (j == cols) 1 else j + 1)]
                val u = pts[max(0, i - 1) * (cols + 1) + j]
                val d = pts[min(rows, i + 1) * (cols + 1) + j]
                for (k in 0 until 3) {
                    a[k] = r[k] - l[k]
                    b[k] = d[k] - u[k]
                }
                var nx = a[1] * b[2] - a[2] * b[1]
                var ny = a[2] * b[0] - a[0] * b[2]
                var nz = a[0] * b[1] - a[1] * b[0]
                if (nx * (p[0] - mid[0]) + ny * (p[1] - mid[1]) + nz * (p[2] - mid[2]) < 0) {
                    nx = -nx
                    ny = -ny
                    nz = -nz
                }
                val len = max(1e-9f, sqrt(nx * nx + ny * ny + nz * nz))
                val o = (i * (cols + 1) + j) * 6
                data[o] = p[0]
                data[o + 1] = p[1]
                data[o + 2] = p[2]
                data[o + 3] = nx / len
                data[o + 4] = ny / len
                data[o + 5] = nz / len
            }
        }
        val idx = ArrayList<Int>()
        for (i in 0 until rows) {
            for (j in 0 until cols) {
                val q = i * (cols + 1) + j
                idx += listOf(q, q + 1, q + cols + 2, q, q + cols + 2, q + cols + 1)
            }
        }
        return Mesh(data, idx.toIntArray())
    }

    // A point on an ellipsoid of radii (rx, ry, rz); y runs down, from -ry at the top.
    private fun sphere(c: Float, s: Float, v: Float, rx: Float, ry: Float, rz: Float, out: FloatArray) {
        val phi = PI.toFloat() * v
        out[0] = sin(phi) * c * rx
        out[1] = -cos(phi) * ry
        out[2] = sin(phi) * s * rz
    }

    /** The nose: a rounded triangle, wide across the top, pointing down. About 1 across. */
    fun nose() = surface(28, 18) { c, s, v, o ->
        sphere(c, s, v, 1f, 0.72f, 0.55f, o)
        o[0] *= 1f - 0.32f * (o[1] / 0.72f)
    }

    /** A paw: an upright oval, three toe bands bulging across its top half. y from -1 to 1. */
    fun paw() = surface(28, 28) { c, s, v, o ->
        sphere(c, s, v, 0.72f, 1f, 0.66f, o)
        val toes = if (o[1] < 0.3f) 1f + 0.05f * max(0f, cos((o[1] + 1f) * 7.0f)) else 1f
        o[0] *= toes
        o[2] *= toes
    }

    /** The carrot: tip at y 0, wide end at y 1, round the y axis. */
    fun carrot() = surface(28, 40) { c, s, v, o ->
        val r = carrotRadius(v)
        o[0] = r * c
        o[1] = v
        o[2] = r * s
    }

    /** One leaf of the clover: a flattened oval reaching from its base at the origin down to y 1. */
    fun leaf() = surface(18, 14) { c, s, v, o ->
        sphere(c, s, v, 0.36f, 0.5f, 0.1f, o)
        o[1] += 0.5f
    }

    /** An ear's furry cup, opening toward -y: its outside, and its rim rolling over. */
    fun earFur(): Mesh {
        val pts = ArrayList<FloatArray>()
        for (k in 0..10) {
            val a = (PI / 2 * k / 10).toFloat()
            pts += floatArrayOf(sin(a), 0.34f * cos(a))
        }
        pts += floatArrayOf(1.0f, -0.04f)
        pts += floatArrayOf(0.93f, -0.06f)
        pts += floatArrayOf(0.86f, -0.02f)
        for (k in 10 downTo 0) {
            val a = (PI / 2 * k / 10).toFloat()
            pts += floatArrayOf(0.86f * sin(a), 0.02f + 0.2f * cos(a))
        }
        return Meshes.lathe(pts, 36)
    }

    /** The pink lining of the cup, just inside the fur. */
    fun earLining(): Mesh {
        val pts = ArrayList<FloatArray>()
        for (k in 0..10) {
            val a = (PI / 2 * k / 10).toFloat()
            pts += floatArrayOf(0.56f * sin(a), 0.03f + 0.15f * cos(a))
        }
        for (k in 10 downTo 0) {
            val a = (PI / 2 * k / 10).toFloat()
            pts += floatArrayOf(0.54f * sin(a), 0.045f + 0.16f * cos(a))
        }
        return Meshes.lathe(pts, 36)
    }
}

object HamsterShaders {
    // Soft, lit from up and to the left in front (world y down, z away), like the pod. One shader
    // for every part, switched by uMat.
    val PROPS = """
        #version 300 es
        precision highp float;
        in vec3 vWorld;
        in vec3 vNormal;
        in vec3 vLocal;
        in vec3 vLocalNormal;
        uniform vec3 uEye;
        uniform int uMat;
        uniform float uCut;
        uniform float uAlpha;
        out vec4 outColor;
        const vec3 KEY = vec3(-0.5767, -0.7340, -0.4719);
        const float TIP = ${HamsterMeshes.CARROT_TIP};
        const float END = ${HamsterMeshes.CARROT_END};

        float hash(vec3 p) {
            p = fract(p * 0.3183099 + 0.1);
            p *= 17.0;
            return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
        }

        float noise(vec3 x) {
            vec3 i = floor(x);
            vec3 f = fract(x);
            f = f * f * (3.0 - 2.0 * f);
            return mix(mix(mix(hash(i), hash(i + vec3(1.0, 0.0, 0.0)), f.x),
                           mix(hash(i + vec3(0.0, 1.0, 0.0)), hash(i + vec3(1.0, 1.0, 0.0)), f.x), f.y),
                       mix(mix(hash(i + vec3(0.0, 0.0, 1.0)), hash(i + vec3(1.0, 0.0, 1.0)), f.x),
                           mix(hash(i + vec3(0.0, 1.0, 1.0)), hash(i + vec3(1.0, 1.0, 1.0)), f.x), f.y), f.z);
        }

        // Brown fur: dark roots and pale tips, speckled.
        vec3 fur(vec3 p) {
            float f = noise(p * 14.0) * 0.5 + noise(p * 40.0) * 0.5;
            return mix(vec3(0.36, 0.22, 0.11), vec3(0.86, 0.68, 0.46), smoothstep(0.32, 0.72, f));
        }

        void main() {
            vec3 n = normalize(vNormal);
            vec3 v = normalize(uEye - vWorld);
            bool back = dot(n, v) < 0.0;
            if (back) n = -n;
            vec3 p = vLocal;
            vec3 base;
            float spec = 0.25;
            float shine = 24.0;
            float fuzz = 0.0;
            if (uMat == 0) {
                base = fur(p);
                spec = 0.04;
                fuzz = 1.0;
            } else if (uMat == 1) {
                base = mix(vec3(1.0, 0.66, 0.70), vec3(0.86, 0.44, 0.52), smoothstep(0.1, 0.85, length(p.xz)));
                spec = 0.12;
            } else if (uMat == 2) {
                base = mix(vec3(1.0, 0.50, 0.56), vec3(0.84, 0.25, 0.36), smoothstep(-0.4, 0.7, p.y));
                spec = 0.95;
                shine = 70.0;
            } else if (uMat == 3) {
                float groove = smoothstep(0.8, 1.0, -cos((p.y + 1.0) * 7.0)) * step(p.y, 0.3) * step(-0.85, p.y);
                base = mix(vec3(1.0, 0.64, 0.72), vec3(0.82, 0.40, 0.50), groove * 0.85);
                float cuff = smoothstep(0.42, 0.6, p.y);
                base = mix(base, fur(p * 1.6), cuff);
                fuzz = cuff;
                spec = 0.35 * (1.0 - cuff);
                shine = 30.0;
            } else if (uMat == 4) {
                float th = atan(p.z, p.x);
                // The bites: scallops round the eaten end.
                if (p.y < uCut + 0.05 * (0.5 + 0.5 * cos(th * 3.0)) * step(0.001, uCut)) discard;
                if (back) {
                    // Inside what's left: paler, wetter carrot.
                    base = mix(vec3(1.0, 0.80, 0.52), vec3(1.0, 0.62, 0.30), smoothstep(0.0, 1.0, length(p.xz) / END));
                    spec = 0.3;
                } else {
                    float ridge = sin(p.y * 44.0 + noise(vec3(th * 1.5, p.y * 3.0, 0.0)) * 4.0);
                    float arc = smoothstep(-0.1, 0.7, sin(th * 2.0 + p.y * 11.0));
                    base = mix(vec3(1.0, 0.56, 0.16), vec3(0.93, 0.36, 0.06), smoothstep(0.0, 1.0, p.y));
                    base *= 1.0 - 0.25 * smoothstep(0.65, 1.0, ridge) * arc;
                    spec = 0.45;
                    shine = 26.0;
                }
            } else {
                if (uAlpha < 1.0 && hash(floor(gl_FragCoord.xyz) + 0.5) > uAlpha) discard;
                float vein = 1.0 - smoothstep(0.0, 0.05, abs(p.x));
                base = mix(vec3(0.50, 0.86, 0.32), vec3(0.20, 0.56, 0.16), smoothstep(0.2, 1.0, p.y + abs(p.x)));
                base *= 1.0 - 0.22 * vein * step(0.1, p.y);
                spec = 0.45;
                shine = 32.0;
            }
            float wrap = dot(n, KEY) * 0.5 + 0.5;
            vec3 col = base * (0.42 + 0.72 * wrap);
            col += vec3(spec) * pow(max(dot(n, normalize(KEY + v)), 0.0), shine);
            // Fur catches the light round its edge.
            col = mix(col, vec3(0.88, 0.74, 0.58), pow(1.0 - max(dot(n, v), 0.0), 2.0) * 0.45 * fuzz);
            outColor = vec4(col, 1.0);
        }
    """.trimIndent()
}

/** Draws Hamster3D props into whatever framebuffer is bound, which needs a depth buffer. */
class HamsterRenderer {
    private val program = Program(Shaders3D.VERTEX, HamsterShaders.PROPS)
    private val earFur = HamsterMeshes.earFur()
    private val earLining = HamsterMeshes.earLining()
    private val nose = HamsterMeshes.nose()
    private val paw = HamsterMeshes.paw()
    private val carrot = HamsterMeshes.carrot()
    private val leaf = HamsterMeshes.leaf()

    fun draw(props: List<Hamster3D>) {
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glDepthMask(true)
        GLES20.glClearDepthf(1f)
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glDisable(GLES20.GL_BLEND)
        program.use()
        GLES20.glUniformMatrix4fv(program.u("uViewProj"), 1, false, View3D.viewProj, 0)
        GLES20.glUniform3fv(program.u("uEye"), 1, View3D.eye, 0)
        GLES20.glUniform1f(program.u("uCut"), 0f)
        GLES20.glUniform1f(program.u("uAlpha"), 1f)
        for (h in props) {
            for (e in h.ears) {
                part(earFur, 0, e)
                part(earLining, 1, e)
            }
            part(nose, 2, h.nose)
            for (w in h.paws) part(paw, 3, w)
            h.carrot?.let {
                GLES20.glUniform1f(program.u("uCut"), h.carrotCut)
                part(carrot, 4, it)
                GLES20.glUniform1f(program.u("uCut"), 0f)
            }
            GLES20.glUniform1f(program.u("uAlpha"), h.leafAlpha.coerceIn(0f, 1f))
            for (l in h.leaves) part(leaf, 5, l)
            GLES20.glUniform1f(program.u("uAlpha"), 1f)
        }
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    private fun part(mesh: Mesh, material: Int, model: FloatArray) {
        GLES20.glUniformMatrix4fv(program.u("uModel"), 1, false, model, 0)
        GLES20.glUniform1i(program.u("uMat"), material)
        mesh.draw(program)
    }
}

/** A transform from a face: frame px at face units (x, y), [toward] px nearer the camera, turned with the head. */
internal fun onFace(f: Face, x: Float, y: Float, toward: Float, out: FloatArray) {
    val p = toPixels(f, x, y)
    Matrix.setIdentityM(out, 0)
    Matrix.translateM(out, 0, p.x, p.y, -toward)
    Matrix.rotateM(out, 0, deg(f.angle), 0f, 0f, 1f)
    // Turned against the head's yaw: the screen is a mirror (see Lemonade).
    Matrix.rotateM(out, 0, -f.yaw * 28f, 0f, 1f, 0f)
}
