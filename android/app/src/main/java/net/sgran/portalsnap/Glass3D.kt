package net.sgran.portalsnap

import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

// A real 3D pass for things that have to look solid: Lemonade's glass, so far. It runs inside
// the composite, after the 2D under layer and the face patches and before the over layer, into
// the composite framebuffer with its own depth buffer.

/**
 * The fixed 3D camera over frame space. World units are frame pixels: x right, y down, z away
 * from the viewer. The eye sits DISTANCE in front of the frame's centre with a field of view that
 * puts every point on the z = 0 plane exactly on its frame pixel, so 3D things line up with the
 * 2D layers around them.
 */
object View3D {
    const val DISTANCE = 1400f
    private const val DEPTH = 900f
    val viewProj = FloatArray(16)
    val eye = floatArrayOf(FRAME_W / 2f, FRAME_H / 2f, -DISTANCE)
    private val v4 = FloatArray(4)
    private val c4 = FloatArray(4)

    init {
        // World to GL eye space (y up, looking down -z): flip y and z, then back off the eye.
        val view = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, -1f, 0f, 0f,
            0f, 0f, -1f, 0f,
            -FRAME_W / 2f, FRAME_H / 2f, -DISTANCE, 1f,
        )
        val near = DISTANCE - DEPTH
        val far = DISTANCE + DEPTH
        val hx = FRAME_W / 2f * near / DISTANCE
        val hy = FRAME_H / 2f * near / DISTANCE
        val proj = FloatArray(16)
        Matrix.frustumM(proj, 0, -hx, hx, -hy, hy, near, far)
        Matrix.multiplyMM(viewProj, 0, proj, 0, view, 0)
    }

    /** A world point to frame pixels, into out[0] and out[1]. False when it's behind the eye. */
    fun project(x: Float, y: Float, z: Float, out: FloatArray): Boolean {
        v4[0] = x
        v4[1] = y
        v4[2] = z
        v4[3] = 1f
        Matrix.multiplyMV(c4, 0, viewProj, 0, v4, 0)
        if (c4[3] <= 0f) return false
        out[0] = (c4[0] / c4[3] + 1f) / 2f * FRAME_W
        out[1] = (1f - c4[1] / c4[3]) / 2f * FRAME_H
        return true
    }
}

/** The tumbler, in glass units: the liquid's top radius is 1, y runs down, z away. */
object GlassShape {
    /** The liquid's half-height: it runs from y = -H to y = H. */
    const val H = 1.37f
    /** Width at the liquid's floor over width at its top. */
    const val TAPER = 0.85f
    const val RIM = -1.14f * H
    const val BASE = 1.22f * H
    const val WALL = 0.1f
    /** Where the lemonade's surface crosses the glass's axis. */
    const val LEVEL = -0.95f * H
    private const val FLOOR_CORNER = 0.3f
    private const val BASE_CORNER = 0.4f

    fun innerRadius(y: Float) = 1f + (TAPER - 1f) * (y / H + 1f) / 2f

    private fun arc(pts: MutableList<FloatArray>, cx: Float, cy: Float, r: Float, a0: Float, a1: Float, steps: Int, scale: Float = 1f) {
        for (k in 0..steps) {
            val a = a0 + (a1 - a0) * k / steps
            pts += floatArrayOf((cx + r * cos(a)) * scale, cy + r * sin(a))
        }
    }

    /** The glass itself: floor, inner wall, a rounded lip, outer wall and a thick rounded base. */
    fun glassOutline(): List<FloatArray> {
        val pts = ArrayList<FloatArray>()
        val half = (PI / 2).toFloat()
        pts += floatArrayOf(0f, H)
        arc(pts, TAPER - FLOOR_CORNER, H - FLOOR_CORNER, FLOOR_CORNER, half, 0f, 8)
        val wallFrom = H - FLOOR_CORNER
        for (k in 1..8) {
            val y = wallFrom + (RIM - wallFrom) * k / 8
            pts += floatArrayOf(innerRadius(y), y)
        }
        val rimR = innerRadius(RIM)
        arc(pts, rimR + WALL / 2, RIM, WALL / 2, PI.toFloat() + 0.2f, (2 * PI).toFloat() - 0.2f, 10)
        val baseFrom = BASE - BASE_CORNER
        for (k in 0..8) {
            val y = RIM + (baseFrom - RIM) * k / 8
            pts += floatArrayOf(innerRadius(y) + WALL, y)
        }
        arc(pts, TAPER + WALL - BASE_CORNER, baseFrom, BASE_CORNER, 0.15f, half, 8)
        pts += floatArrayOf(0f, BASE)
        return pts
    }

    /** The lemonade: the inside of the glass, filled to just under the lip. The surface is cut
     *  by a level plane in the shader, so the volume can stay put while the glass tips. */
    fun liquidOutline(): List<FloatArray> {
        val pts = ArrayList<FloatArray>()
        val half = (PI / 2).toFloat()
        val shrink = 0.995f
        pts += floatArrayOf(0f, H)
        arc(pts, TAPER - FLOOR_CORNER, H - FLOOR_CORNER, FLOOR_CORNER, half, 0f, 8, shrink)
        val wallFrom = H - FLOOR_CORNER
        val top = RIM + WALL * 0.3f
        for (k in 1..8) {
            val y = wallFrom + (top - wallFrom) * k / 8
            pts += floatArrayOf(innerRadius(y) * shrink, y)
        }
        pts += floatArrayOf(0f, top)
        return pts
    }
}

/** One glass of lemonade, posed for this frame by Lemonade and drawn by GlassRenderer. */
class Glass3D {
    /** Glass units to world. */
    val model = FloatArray(16)
    /** The lemonade's surface: a world point on it and its upward normal. */
    val planePoint = FloatArray(3)
    val planeNormal = FloatArray(3)
    /** Where the face is in the camera frame, how far out from there the glass's edges reach, and its roll. */
    var hasFace = false
    var faceX = 0f
    var faceY = 0f
    var reachX = 0f
    var reachY = 0f
    var roll = 0f
    var phase = 0f
    /** Model matrices: unit rounded cubes, a unit straw (radius 1, from y = 0 up to y = -1), a unit slice (radius 1, axis y). */
    val cubes = ArrayList<FloatArray>()
    val straw = FloatArray(16)
    val lemon = FloatArray(16)
}

/** A static mesh: interleaved position and normal, indexed triangles. */
class Mesh(data: FloatArray, indices: IntArray) {
    private val vbo: Int
    private val ibo: Int
    private val count = indices.size

    init {
        val ids = IntArray(2)
        GLES20.glGenBuffers(2, ids, 0)
        vbo = ids[0]
        ibo = ids[1]
        val fb = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(data).position(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, data.size * 4, fb, GLES20.GL_STATIC_DRAW)
        val ib = ByteBuffer.allocateDirect(indices.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
        ib.put(indices).position(0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, indices.size * 4, ib, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    fun draw(p: Program) {
        val ap = p.a("aPos")
        val an = p.a("aNormal")
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glEnableVertexAttribArray(ap)
        GLES20.glVertexAttribPointer(ap, 3, GLES20.GL_FLOAT, false, 24, 0)
        if (an >= 0) {
            GLES20.glEnableVertexAttribArray(an)
            GLES20.glVertexAttribPointer(an, 3, GLES20.GL_FLOAT, false, 24, 12)
        }
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, count, GLES20.GL_UNSIGNED_INT, 0)
        GLES20.glDisableVertexAttribArray(ap)
        if (an >= 0) GLES20.glDisableVertexAttribArray(an)
        // The 2D passes draw from client-side arrays, which need no buffer bound.
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
    }
}

object Meshes {
    /** A solid of revolution about y from a closed (r, y) outline. Normals follow the outline. */
    fun lathe(outline: List<FloatArray>, segments: Int): Mesh {
        val n = outline.size
        var area = 0f
        for (i in 0 until n) {
            val a = outline[i]
            val b = outline[(i + 1) % n]
            area += a[0] * b[1] - b[0] * a[1]
        }
        val side = if (area > 0f) 1f else -1f
        val nr = FloatArray(n)
        val ny = FloatArray(n)
        for (i in 0 until n) {
            val p = outline[(i + n - 1) % n]
            val q = outline[(i + 1) % n]
            var tx = q[0] - p[0]
            var ty = q[1] - p[1]
            val len = max(1e-6f, sqrt(tx * tx + ty * ty))
            tx /= len
            ty /= len
            nr[i] = ty * side
            ny[i] = -tx * side
        }
        val rings = segments + 1
        val data = FloatArray(rings * n * 6)
        var o = 0
        for (k in 0 until rings) {
            val th = (2 * PI * k / segments).toFloat()
            val c = cos(th)
            val s = sin(th)
            for (i in 0 until n) {
                val r = outline[i][0]
                data[o++] = r * c
                data[o++] = outline[i][1]
                data[o++] = r * s
                data[o++] = nr[i] * c
                data[o++] = ny[i]
                data[o++] = nr[i] * s
            }
        }
        val idx = IntList()
        for (k in 0 until segments) {
            for (i in 0 until n) {
                val i2 = (i + 1) % n
                val a = k * n + i
                val b = k * n + i2
                val c = (k + 1) * n + i2
                val d = (k + 1) * n + i
                tri(data, idx, a, b, c)
                tri(data, idx, a, c, d)
            }
        }
        return Mesh(data, idx.toArray())
    }

    /** A cube of half-size 1 with rounded edges and corners of the given radius. */
    fun roundedBox(round: Float, steps: Int): Mesh {
        val per = (steps + 1) * (steps + 1)
        val data = FloatArray(6 * per * 6)
        val idx = IntList()
        var o = 0
        var face = 0
        val lim = 1f - round
        val p = FloatArray(3)
        for (axis in 0 until 3) {
            for (sign in floatArrayOf(-1f, 1f)) {
                val start = face * per
                for (j in 0..steps) {
                    for (i in 0..steps) {
                        p[axis] = sign
                        p[(axis + 1) % 3] = -1f + 2f * i / steps
                        p[(axis + 2) % 3] = -1f + 2f * j / steps
                        val ix = p[0].coerceIn(-lim, lim)
                        val iy = p[1].coerceIn(-lim, lim)
                        val iz = p[2].coerceIn(-lim, lim)
                        var dx = p[0] - ix
                        var dy = p[1] - iy
                        var dz = p[2] - iz
                        val len = max(1e-6f, sqrt(dx * dx + dy * dy + dz * dz))
                        dx /= len
                        dy /= len
                        dz /= len
                        data[o++] = ix + dx * round
                        data[o++] = iy + dy * round
                        data[o++] = iz + dz * round
                        data[o++] = dx
                        data[o++] = dy
                        data[o++] = dz
                    }
                }
                for (j in 0 until steps) {
                    for (i in 0 until steps) {
                        val a = start + j * (steps + 1) + i
                        val b = a + 1
                        val c = a + steps + 2
                        val d = a + steps + 1
                        tri(data, idx, a, b, c)
                        tri(data, idx, a, c, d)
                    }
                }
                face++
            }
        }
        return Mesh(data, idx.toArray())
    }

    // Adds a triangle wound so that, seen from where its normals point, it runs counter-clockwise:
    // GL's front face.
    private fun tri(d: FloatArray, idx: IntList, a: Int, b: Int, c: Int) {
        val ux = d[b * 6] - d[a * 6]
        val uy = d[b * 6 + 1] - d[a * 6 + 1]
        val uz = d[b * 6 + 2] - d[a * 6 + 2]
        val vx = d[c * 6] - d[a * 6]
        val vy = d[c * 6 + 1] - d[a * 6 + 1]
        val vz = d[c * 6 + 2] - d[a * 6 + 2]
        val cx = uy * vz - uz * vy
        val cy = uz * vx - ux * vz
        val cz = ux * vy - uy * vx
        val nx = d[a * 6 + 3] + d[b * 6 + 3] + d[c * 6 + 3]
        val ny = d[a * 6 + 4] + d[b * 6 + 4] + d[c * 6 + 4]
        val nz = d[a * 6 + 5] + d[b * 6 + 5] + d[c * 6 + 5]
        if (cx * nx + cy * ny + cz * nz >= 0f) idx.add(a, b, c) else idx.add(a, c, b)
    }

    class IntList {
        private var a = IntArray(1024)
        private var n = 0

        fun add(x: Int, y: Int, z: Int) {
            if (n + 3 > a.size) a = a.copyOf(a.size * 2)
            a[n++] = x
            a[n++] = y
            a[n++] = z
        }

        fun toArray() = a.copyOf(n)
    }
}

object Shaders3D {
    val VERTEX = """
        #version 300 es
        uniform mat4 uModel;
        uniform mat4 uViewProj;
        in vec3 aPos;
        in vec3 aNormal;
        out vec3 vWorld;
        out vec3 vNormal;
        out vec3 vLocal;
        out vec3 vLocalNormal;
        void main() {
            vec4 w = uModel * vec4(aPos, 1.0);
            vWorld = w.xyz;
            vNormal = mat3(uModel) * aNormal;
            vLocal = aPos;
            vLocalNormal = aNormal;
            gl_Position = uViewProj * w;
        }
    """.trimIndent()

    // The face as the lemonade shows it: a few taps of blur, a slow ripple, muted, cast pink.
    private val FACE = """
        uniform sampler2D uFace;
        uniform vec2 uSize;
        uniform vec2 uCentre;
        uniform vec2 uReach;
        uniform float uRoll;
        uniform float uHasFace;
        uniform float uPhase;

        vec2 facePixel(vec2 s) {
            vec2 o = s * uReach;
            float c = cos(uRoll);
            float sn = sin(uRoll);
            return uCentre + vec2(c * o.x - sn * o.y, sn * o.x + c * o.y);
        }

        vec3 frameAt(vec2 px) {
            return texture(uFace, vec2(px.x / uSize.x, 1.0 - px.y / uSize.y)).rgb;
        }

        vec3 lemonade(vec3 c) {
            float lum = dot(c, vec3(0.299, 0.587, 0.114));
            c = mix(c, vec3(lum), 0.45);
            vec3 tint = vec3(0.745, 0.431, 0.51);
            return mix(c, c * tint + tint * 0.22, 0.85);
        }
    """ // not trimmed: it's pasted into indented shaders, which trim the whole

    // A small studio for glass and ice to reflect: warm surroundings, a tall soft light up and to
    // the left in front of the glass, and a narrow one to the right. World directions: y down, z away.
    private val STUDIO = """
        vec3 studio(vec3 r) {
            // The warm room.
            vec3 c = mix(vec3(1.0, 0.72, 0.5), vec3(1.0, 0.5, 0.68), clamp(r.x * 0.5 + 0.5, 0.0, 1.0)) * 0.35;
            // A bright backdrop behind the glass: grazing reflections at its silhouette catch it,
            // which gives the pale edge down each side.
            c += vec3(0.9) * smoothstep(0.1, 0.9, r.z) * smoothstep(1.0, 0.3, abs(r.y));
            // A tall soft light up and to the left in front: one slim streak, off to the side.
            if (r.z < -0.05) {
                vec2 q = r.xy / -r.z;
                c += vec3(0.9) * smoothstep(0.14, 0.04, abs(q.x + 1.6)) * smoothstep(1.8, 1.0, abs(q.y + 0.2));
            }
            return c;
        }
    """ // not trimmed, as FACE

    val GLASS = """
        #version 300 es
        precision highp float;
        in vec3 vWorld;
        in vec3 vNormal;
        in vec3 vLocal;
        in vec3 vLocalNormal;
        uniform vec3 uEye;
        out vec4 outColor;
        $STUDIO
        void main() {
            vec3 n = normalize(vNormal);
            if (!gl_FrontFacing) n = -n;
            vec3 v = normalize(uEye - vWorld);
            float ndv = clamp(dot(n, v), 0.0, 1.0);
            float fresnel = 0.04 + 0.96 * pow(1.0 - ndv, 5.0);
            vec3 light = studio(reflect(-v, n));
            // Clear where it faces you. It shows as a pale rim where it turns away, and as a
            // streak where it catches a light; it never goes opaque.
            float glint = max(max(light.r, max(light.g, light.b)) - 0.55, 0.0);
            float edge = fresnel * 0.55;
            float a = clamp(0.02 + edge + glint * 0.45, 0.0, 0.7);
            vec3 col = mix(vec3(1.0, 0.98, 0.94), light, 0.35) * edge + vec3(glint * 0.6);
            outColor = vec4(col, a);
        }
    """.trimIndent()

    val LIQUID = """
        #version 300 es
        precision highp float;
        in vec3 vWorld;
        in vec3 vNormal;
        in vec3 vLocal;
        in vec3 vLocalNormal;
        uniform vec3 uEye;
        uniform mat4 uViewProj;
        uniform mat4 uModelInv;
        uniform vec3 uPlaneP;
        uniform vec3 uPlaneN;
        uniform float uLevelY;
        uniform float uFloorY;
        uniform float uH;
        out vec4 outColor;
        $FACE
        void main() {
            // Nothing above the lemonade's level surface.
            if (dot(vWorld - uPlaneP, uPlaneN) > 0.0) discard;
            if (gl_FrontFacing) {
                vec3 local = (uModelInv * vec4(vWorld, 1.0)).xyz;
                vec3 eyeLocal = (uModelInv * vec4(uEye, 1.0)).xyz;
                // Where this point sits around the glass as seen from the eye: 0 straight ahead,
                // 1 at the silhouette. Up the glass: -1 at the surface, 1 at the floor.
                vec2 toP = normalize(local.xz);
                vec2 toE = normalize(eyeLocal.xz);
                float sx = -(toP.x * toE.y - toP.y * toE.x);
                float sy = clamp((local.y - 0.5 * (uLevelY + uFloorY)) / (0.5 * (uFloorY - uLevelY)), -1.0, 1.0);
                // The features magnified in the middle, the face smeared out to every edge.
                vec2 s = sin(clamp(vec2(sx, sy), -1.0, 1.0) * 1.5707963);
                vec3 col;
                if (uHasFace > 0.5) {
                    vec2 px = facePixel(s);
                    px.x += sin(px.y * 0.045 + uPhase) * 2.0;
                    vec2 dx = vec2(1.2, 0.0);
                    vec2 dy = vec2(0.0, 1.2);
                    col = lemonade(frameAt(px) * 0.4
                        + (frameAt(px + dx) + frameAt(px - dx) + frameAt(px + dy) + frameAt(px - dy)) * 0.15);
                } else {
                    col = lemonade(vec3(0.85, 0.62, 0.66));
                }
                col *= mix(1.0, 0.75, smoothstep(0.35, 1.0, sy));
                float ndv = clamp(dot(normalize(vNormal), normalize(uEye - vWorld)), 0.0, 1.0);
                col *= 0.8 + 0.2 * sqrt(ndv);
                outColor = vec4(col, 1.0);
                gl_FragDepth = gl_FragCoord.z;
            } else {
                // The far wall under the surface, seen through the cut-away near side: shade it as
                // the surface, at the depth where the view ray actually crosses the surface, and only
                // where that crossing is inside the glass.
                vec3 d = vWorld - uEye;
                float den = dot(d, uPlaneN);
                if (abs(den) < 1e-4) discard;
                float t = dot(uPlaneP - uEye, uPlaneN) / den;
                vec3 hit = uEye + d * t;
                vec3 local = (uModelInv * vec4(hit, 1.0)).xyz;
                float wall = (1.0 - 0.15 * (local.y / uH + 1.0) * 0.5) * 0.99;
                float r = length(local.xz);
                if (t <= 0.0 || t > 1.0 || r > wall) discard;
                vec4 clip = uViewProj * vec4(hit, 1.0);
                gl_FragDepth = clamp(clip.z / clip.w * 0.5 + 0.5, 0.0, 1.0);
                vec3 col = mix(vec3(0.93, 0.74, 0.80), vec3(0.78, 0.55, 0.63), smoothstep(0.3, 1.0, r / wall));
                col += vec3(0.1) * smoothstep(0.25, 0.0, abs(local.x * 0.8 + local.z * 0.6 + 0.35));
                outColor = vec4(col, 1.0);
            }
        }
    """.trimIndent()

    val ICE = """
        #version 300 es
        precision highp float;
        in vec3 vWorld;
        in vec3 vNormal;
        in vec3 vLocal;
        in vec3 vLocalNormal;
        uniform vec3 uEye;
        out vec4 outColor;
        $FACE
        void main() {
            vec3 n = normalize(vNormal);
            if (!gl_FrontFacing) n = -n;
            vec3 v = normalize(uEye - vWorld);
            float ndv = clamp(dot(n, v), 0.0, 1.0);
            float fresnel = 0.1 + 0.9 * pow(1.0 - ndv, 3.0);
            vec3 r = reflect(-v, n);
            vec3 col = vec3(0.84, 0.91, 0.98);
            if (uHasFace > 0.5) {
                // The face in front of the ice, caught in it: looked up by where the reflection
                // points, mirrored as a reflection is.
                vec2 s = clamp(r.xy / max(-r.z, 0.25), -1.0, 1.0);
                vec3 face = frameAt(facePixel(vec2(-s.x, s.y) * 1.2));
                col = mix(col, face, 0.65);
            }
            float glint = pow(max(dot(r, normalize(vec3(-0.5, -0.7, -0.6))), 0.0), 60.0);
            float a = clamp(0.45 + fresnel * 0.5, 0.0, 1.0);
            outColor = vec4(col * a + vec3(glint), min(1.0, a + glint));
        }
    """.trimIndent()

    val STRAW = """
        #version 300 es
        precision highp float;
        in vec3 vWorld;
        in vec3 vNormal;
        in vec3 vLocal;
        in vec3 vLocalNormal;
        uniform vec3 uEye;
        out vec4 outColor;
        void main() {
            vec3 n = normalize(vNormal);
            if (!gl_FrontFacing) n = -n;
            vec3 v = normalize(uEye - vWorld);
            vec3 l = normalize(vec3(-0.5, -0.7, -0.6));
            float diff = max(dot(n, l), 0.0);
            float spec = pow(max(dot(n, normalize(l + v)), 0.0), 40.0);
            vec3 base = vec3(0.72, 0.13, 0.24);
            outColor = vec4(base * (0.45 + 0.6 * diff) + vec3(0.5 * spec), 1.0);
        }
    """.trimIndent()

    val LEMON = """
        #version 300 es
        precision highp float;
        in vec3 vWorld;
        in vec3 vNormal;
        in vec3 vLocal;
        in vec3 vLocalNormal;
        uniform vec3 uEye;
        out vec4 outColor;
        void main() {
            vec3 n = normalize(vNormal);
            if (!gl_FrontFacing) n = -n;
            vec3 v = normalize(uEye - vWorld);
            vec3 l = normalize(vec3(-0.5, -0.7, -0.6));
            float diff = 0.55 + 0.45 * max(dot(n, l), 0.0);
            float spec = pow(max(dot(n, normalize(l + v)), 0.0), 30.0);
            float r = length(vLocal.xz);
            vec3 col;
            if (abs(vLocalNormal.y) < 0.6) {
                col = vec3(0.93, 0.70, 0.08); // the rind's edge
            } else if (r > 0.9) {
                col = mix(vec3(1.0, 0.86, 0.25), vec3(0.87, 0.62, 0.03), smoothstep(0.9, 1.0, r));
            } else if (r > 0.84) {
                col = vec3(1.0, 0.97, 0.86); // pith
            } else {
                float seg = fract(atan(vLocal.z, vLocal.x) / 6.2831853 * 10.0);
                float membrane = 1.0 - smoothstep(0.0, 0.07, seg) * smoothstep(1.0, 0.93, seg);
                float juice = 0.5 + 0.5 * sin(r * 70.0 + seg * 9.0);
                vec3 flesh = mix(vec3(1.0, 0.96, 0.72), vec3(1.0, 0.84, 0.28), smoothstep(0.1, 0.8, r));
                flesh *= 0.94 + 0.06 * juice;
                col = mix(flesh, vec3(1.0, 0.97, 0.86), max(membrane, 1.0 - smoothstep(0.08, 0.14, r)));
            }
            outColor = vec4(col * diff + vec3(0.35 * spec), 1.0);
        }
    """.trimIndent()
}

/**
 * Draws Glass3D glasses into whatever framebuffer is bound, which needs a depth buffer. Opaque
 * things first with depth (the lemonade, the straw, the lemon), then the clear things over them
 * without writing depth (the ice, then the glass, both faces at once).
 */
class GlassRenderer {
    private val pGlass = Program(Shaders3D.VERTEX, Shaders3D.GLASS)
    private val pLiquid = Program(Shaders3D.VERTEX, Shaders3D.LIQUID)
    private val pIce = Program(Shaders3D.VERTEX, Shaders3D.ICE)
    private val pStraw = Program(Shaders3D.VERTEX, Shaders3D.STRAW)
    private val pLemon = Program(Shaders3D.VERTEX, Shaders3D.LEMON)
    private val glass = Meshes.lathe(GlassShape.glassOutline(), 72)
    private val liquid = Meshes.lathe(GlassShape.liquidOutline(), 72)
    private val cube = Meshes.roundedBox(0.4f, 5)
    private val straw = Meshes.lathe(listOf(floatArrayOf(0f, 0f), floatArrayOf(1f, 0f), floatArrayOf(1f, -1f), floatArrayOf(0f, -1f)), 14)
    private val lemon = Meshes.lathe(
        listOf(floatArrayOf(0f, 0.07f), floatArrayOf(0.95f, 0.07f), floatArrayOf(1f, 0f), floatArrayOf(0.95f, -0.07f), floatArrayOf(0f, -0.07f)),
        48,
    )
    private val inverse = FloatArray(16)

    fun draw(glasses: List<Glass3D>, faceTex: Int) {
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glDepthMask(true)
        GLES20.glClearDepthf(1f)
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glDisable(GLES20.GL_BLEND)
        for (g in glasses) {
            Matrix.invertM(inverse, 0, g.model, 0)
            begin(pLiquid, g.model)
            face(pLiquid, g, faceTex)
            GLES20.glUniformMatrix4fv(pLiquid.u("uModelInv"), 1, false, inverse, 0)
            GLES20.glUniform3fv(pLiquid.u("uPlaneP"), 1, g.planePoint, 0)
            GLES20.glUniform3fv(pLiquid.u("uPlaneN"), 1, g.planeNormal, 0)
            GLES20.glUniform1f(pLiquid.u("uLevelY"), GlassShape.LEVEL)
            GLES20.glUniform1f(pLiquid.u("uFloorY"), GlassShape.H)
            GLES20.glUniform1f(pLiquid.u("uH"), GlassShape.H)
            liquid.draw(pLiquid)
            begin(pStraw, g.straw)
            straw.draw(pStraw)
            begin(pLemon, g.lemon)
            lemon.draw(pLemon)
        }
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)
        for (g in glasses) {
            for (m in g.cubes) {
                begin(pIce, m)
                face(pIce, g, faceTex)
                cube.draw(pIce)
            }
            begin(pGlass, g.model)
            glass.draw(pGlass)
        }
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
    }

    private fun begin(p: Program, model: FloatArray) {
        p.use()
        GLES20.glUniformMatrix4fv(p.u("uModel"), 1, false, model, 0)
        GLES20.glUniformMatrix4fv(p.u("uViewProj"), 1, false, View3D.viewProj, 0)
        GLES20.glUniform3fv(p.u("uEye"), 1, View3D.eye, 0)
    }

    private fun face(p: Program, g: Glass3D, tex: Int) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glUniform1i(p.u("uFace"), 0)
        GLES20.glUniform2f(p.u("uSize"), FRAME_W.toFloat(), FRAME_H.toFloat())
        GLES20.glUniform2f(p.u("uCentre"), g.faceX, g.faceY)
        GLES20.glUniform2f(p.u("uReach"), g.reachX, g.reachY)
        GLES20.glUniform1f(p.u("uRoll"), g.roll)
        GLES20.glUniform1f(p.u("uHasFace"), if (g.hasFace) 1f else 0f)
        GLES20.glUniform1f(p.u("uPhase"), g.phase)
    }

    private companion object {
        // Keeps the ES3 import honest: the depth attachment in Fbo uses a 24-bit format.
        @Suppress("unused") const val DEPTH_FORMAT = GLES30.GL_DEPTH_COMPONENT24
    }
}
