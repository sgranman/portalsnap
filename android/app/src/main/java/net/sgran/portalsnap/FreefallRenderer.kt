package net.sgran.portalsnap

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLUtils
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object FallShaders {
    // The sky round the fall, per pixel from the view ray: blue sky, a wall of cloud rushing up
    // past the close-up, and below, the ground photo through haze with a cloud deck between that
    // closes in as we fall. ES 2 so it can use the shared quad.
    val ENV = """
        precision highp float;
        varying vec2 vUv;
        uniform mat4 uInverse;
        uniform sampler2D uNoise;
        uniform sampler2D uGround;
        uniform float uClock;
        uniform float uWall;
        uniform float uGroundShow;
        uniform float uDeckY;
        uniform float uWhite;

        const float GROUND_Y = -3200.0;
        const float GROUND_SPAN = ${FreefallRenderer.GROUND_SPAN};

        float clouds(vec2 p) {
            return texture2D(uNoise, p).r * 0.62 + texture2D(uNoise, p * 2.7 + vec2(0.31, 0.77)).g * 0.38;
        }

        // Lit from above: bright where the cloud thins toward the light, blue-grey in its shade.
        vec3 cloudColor(float lit) {
            return mix(vec3(0.58, 0.69, 0.85), vec3(0.99, 0.995, 1.0), clamp(lit, 0.0, 1.0));
        }

        void main() {
            vec4 w = uInverse * vec4(vUv * 2.0 - 1.0, 1.0, 1.0);
            vec3 d = normalize(w.xyz / w.w);
            vec3 col = mix(vec3(0.66, 0.80, 0.95), vec3(0.27, 0.51, 0.86), clamp(d.y * 0.8 + 0.5, 0.0, 1.0));

            if (d.y < -0.01 && uGroundShow > 0.0) {
                float t = GROUND_Y / d.y;
                vec3 ground = texture2D(uGround, d.xz * t / GROUND_SPAN + 0.5).rgb;
                float haze = 1.0 - exp(-t / 7000.0);
                ground = mix(ground, vec3(0.80, 0.86, 0.92), 0.2 + 0.6 * haze);
                float td = uDeckY / d.y;
                vec2 q = d.xz * td / 700.0 + vec2(0.13, uClock * 0.003);
                float n = clouds(q);
                float near = clamp(1.0 + uDeckY / 900.0, 0.0, 1.0);
                float dens = smoothstep(mix(0.62, 0.15, near), mix(0.92, 0.45, near), n) * clamp(1.0 - td / 20000.0, 0.0, 1.0);
                ground = mix(ground, cloudColor(0.75 + (n - clouds(q + vec2(0.004))) * 20.0), dens);
                col = mix(col, ground, uGroundShow * smoothstep(-0.01, -0.08, d.y));
            }
            if (uWall > 0.0) {
                float h = max(length(d.xz), 0.05);
                vec2 p = vec2(atan(d.x, -d.z) * 0.55, d.y / h * 0.55 - uClock * 0.16);
                float n = clouds(p);
                float above = clouds(p + vec2(0.0, 0.025));
                float dens = smoothstep(0.3, 0.6, n);
                // Gone from rays looking steeply down, where wrapping it round us would swirl.
                col = mix(col, cloudColor(0.55 + (n - above) * 9.0 + (n - 0.5) * 0.8), dens * uWall * smoothstep(-0.9, -0.55, d.y));
                // A nearer, wispier layer: bigger shapes rushing up past it about three times as fast.
                vec2 fp = vec2(atan(d.x, -d.z) * 0.32 + 0.41, d.y / h * 0.32 - uClock * 0.34);
                float fn = clouds(fp);
                float fd = smoothstep(0.55, 0.82, fn) * 0.85;
                col = mix(col, cloudColor(0.8 + (fn - clouds(fp + vec2(0.0, 0.03))) * 8.0), fd * uWall * smoothstep(-0.9, -0.55, d.y));
            }
            gl_FragColor = vec4(mix(col, vec3(1.0), uWhite), 1.0);
        }
    """

    val COPY = """
        precision mediump float;
        varying vec2 vUv;
        uniform sampler2D uImage;
        void main() {
            gl_FragColor = texture2D(uImage, vUv);
        }
    """

    // High sun a little to the left in front, sky-blue fill from above.
    private val LIGHT = """
        const vec3 SUN = vec3(-0.3333, 0.8333, 0.4410);
        uniform float uWhite;
    """ // not trimmed: pasted into indented shaders

    // The suit and gear: colour per vertex, its alpha how glossy.
    val DIVER = """
        #version 300 es
        precision highp float;
        in vec3 vWorld;
        in vec3 vNormal;
        in vec4 vColor;
        uniform vec3 uEye;
        out vec4 outColor;
        $LIGHT
        void main() {
            vec3 n = normalize(vNormal);
            vec3 v = normalize(uEye - vWorld);
            if (dot(n, v) < 0.0) n = -n;
            float diff = max(dot(n, SUN), 0.0);
            vec3 amb = mix(vec3(0.40, 0.44, 0.54), vec3(0.78, 0.84, 0.96), 0.5 + 0.5 * n.y);
            vec3 col = vColor.rgb * (amb * 0.75 + vec3(1.0, 0.98, 0.94) * diff * 0.7);
            float gloss = vColor.a;
            col += vec3(pow(max(dot(n, normalize(SUN + v)), 0.0), 40.0) * 0.6 * gloss);
            col += vec3(0.75, 0.85, 1.0) * pow(1.0 - max(dot(n, v), 0.0), 3.0) * 0.35 * gloss;
            col = mix(col, vec3(0.8, 0.86, 0.92), clamp((length(vWorld - uEye) - 20.0) / 400.0, 0.0, 0.6));
            outColor = vec4(mix(col, vec3(1.0), uWhite), 1.0);
        }
    """.trimIndent()

    // The helmet: a glossy shell of quartered blue and white panels, open at the face and
    // underneath, with a pale trim round the opening, white cheek guards carrying blue plates, and
    // grey padding inside.
    val HELMET = """
        #version 300 es
        precision highp float;
        in vec3 vWorld;
        in vec3 vNormal;
        in vec3 vLocal;
        in vec3 vLocalNormal;
        uniform vec3 uEye;
        out vec4 outColor;
        $LIGHT
        const vec3 C = vec3(0.0, ${FreefallRenderer.HELMET_CY}, ${FreefallRenderer.HELMET_CZ});
        const vec3 R = vec3(${FreefallRenderer.HELMET_RX}, ${FreefallRenderer.HELMET_RY}, ${FreefallRenderer.HELMET_RZ});
        const vec3 BLUE = vec3(0.13, 0.25, 0.85);
        const vec3 WHITE = vec3(0.93, 0.94, 0.96);
        void main() {
            vec3 q = (vLocal - C) / R;
            float th = atan(q.x, q.z);
            // Down to the jaw at the sides, higher behind.
            if (q.y < mix(-0.98, -0.5, smoothstep(1.9, 2.7, abs(th)))) discard;
            vec2 o = vec2(vLocal.x / 0.1, (vLocal.y + 0.035) / 0.132);
            float od = pow(pow(abs(o.x), 2.6) + pow(abs(o.y), 2.6), 1.0 / 2.6);
            if (q.z > 0.0 && (od < 1.0 || (vLocal.y < -0.12 && abs(vLocal.x) < 0.1))) discard;

            vec3 n = normalize(vNormal);
            vec3 v = normalize(uEye - vWorld);
            bool inside = dot(n, v) < 0.0;
            if (inside) n = -n;
            float across = atan(q.x, q.y);
            vec3 base = mod(floor((across + 1.5708) / 0.7854), 2.0) < 0.5 ? BLUE : WHITE;
            if (q.y < -0.22 && abs(th) < 1.9) {
                base = WHITE;
                vec2 pl = vec2(abs(th) - 1.2, q.y + 0.6);
                if (abs(pl.x) < 0.26 && abs(pl.y) < 0.2) {
                    base = BLUE;
                    for (int i = 0; i < 3; i++) {
                        if (length(pl - vec2(0.06, -0.1 + 0.1 * float(i))) < 0.04) base = vec3(0.12);
                    }
                }
            }
            if (q.z > 0.0) base = mix(base, vec3(0.86, 0.87, 0.89), 1.0 - smoothstep(1.0, 1.1, od));
            vec3 col;
            if (inside) {
                col = vec3(0.3, 0.31, 0.33) * (0.75 + 0.25 * max(dot(n, SUN), 0.0));
            } else {
                vec3 amb = mix(vec3(0.45, 0.5, 0.6), vec3(0.82, 0.88, 0.98), 0.5 + 0.5 * n.y);
                col = base * (amb * 0.7 + max(dot(n, SUN), 0.0) * 0.6);
                col += vec3(pow(max(dot(n, normalize(SUN + v)), 0.0), 60.0) * 0.9);
                col += vec3(0.8, 0.88, 1.0) * pow(1.0 - max(dot(n, v), 0.0), 4.0) * 0.4;
            }
            outColor = vec4(mix(col, vec3(1.0), uWhite), 1.0);
        }
    """.trimIndent()

    // The face window, pulled a little nearer along its lines of sight so the padding just behind
    // it never wins the depth test.
    val FACE_VERTEX = """
        #version 300 es
        uniform mat4 uModel;
        uniform mat4 uViewProj;
        uniform vec3 uEye;
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
            gl_Position = uViewProj * vec4(w.xyz + normalize(uEye - w.xyz) * 0.02, 1.0);
        }
    """.trimIndent()

    val FACE = """
        #version 300 es
        precision highp float;
        in vec3 vWorld;
        in vec3 vNormal;
        in vec3 vLocal;
        in vec3 vLocalNormal;
        uniform sampler2D uFace;
        uniform vec2 uSize;
        uniform vec2 uCentre;
        uniform vec2 uReach;
        uniform float uRoll;
        uniform float uTime;
        uniform float uWind;
        out vec4 outColor;
        $LIGHT
        void main() {
            vec2 q = vec2(vLocal.x / ${DiverParts.FACE_HALF_W}, (vLocal.y - ${DiverParts.FACE_Y}) / ${DiverParts.FACE_HALF_H});
            float d = pow(pow(abs(q.x), 2.4) + pow(abs(q.y), 2.4), 1.0 / 2.4);
            float mask = 1.0 - smoothstep(0.84, 1.0, d);
            if (mask <= 0.0) discard;
            // The wind in the mouth: cheeks and lips stretched out toward the helmet, with ripples
            // running back across the cheeks. Sampling nearer the middle spreads the picture outward.
            float cheek = smoothstep(0.12, 0.5, abs(q.x)) * (1.0 - smoothstep(0.05, 0.45, q.y)) * (1.0 - smoothstep(0.75, 1.0, abs(q.x)))
                * (1.0 - smoothstep(0.75, 1.0, -q.y));
            float lips = (1.0 - smoothstep(0.0, 0.25, abs(q.y + 0.46))) * (1.0 - smoothstep(0.25, 0.65, abs(q.x)));
            vec2 st = q;
            st.x *= 1.0 - (0.2 * cheek + 0.14 * lips) * uWind;
            st.x += sin(abs(q.x) * 14.0 - uTime * 30.0) * 0.022 * cheek * sign(q.x) * uWind;
            st.y += sin(abs(q.x) * 11.0 - uTime * 24.0 + 1.3) * 0.018 * cheek * uWind;
            vec2 o = vec2(st.x, -st.y) * uReach;
            float c = cos(uRoll);
            float s = sin(uRoll);
            vec2 px = uCentre + vec2(c * o.x - s * o.y, s * o.x + c * o.y);
            vec3 face = texture(uFace, vec2(px.x / uSize.x, 1.0 - px.y / uSize.y)).rgb;
            face *= 0.92 + 0.14 * max(dot(normalize(vNormal), SUN), 0.0);
            face = mix(face, vec3(1.0), uWhite);
            outColor = vec4(face * mask, mask);
        }
    """.trimIndent()
}

/**
 * Draws the fall. The sky goes into a half-size framebuffer (it's all soft) and up onto [target],
 * then the divers on top with depth: bodies, helmets, faces.
 */
class FreefallRenderer(assets: AssetManager) {
    private val pEnv = Program(Shaders.VERTEX, FallShaders.ENV)
    private val pCopy = Program(Shaders.VERTEX, FallShaders.COPY)
    private val pDiver = Program(RideShaders.COLOR_VERTEX, FallShaders.DIVER)
    private val pHelmet = Program(Shaders3D.VERTEX, FallShaders.HELMET)
    private val pFace = Program(FallShaders.FACE_VERTEX, FallShaders.FACE)
    private val env = Fbo(FRAME_W / 2, FRAME_H / 2)
    private val noise = noiseTexture()
    private val ground = groundTexture(assets)
    private val helmet = ellipsoid(HELMET_CY, HELMET_CZ, HELMET_RX, HELMET_RY, HELMET_RZ)
    private val face = faceWindow()
    private val bodies = DynamicColorMesh()
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    fun draw(falls: List<Fall3D>, faceTex: Int, target: Fbo) {
        val f = falls.last()
        env.bind()
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        pEnv.use()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ground)
        GLES20.glUniform1i(pEnv.u("uGround"), 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, noise)
        GLES20.glUniform1i(pEnv.u("uNoise"), 0)
        GLES20.glUniformMatrix4fv(pEnv.u("uInverse"), 1, false, f.inverse, 0)
        GLES20.glUniform1f(pEnv.u("uClock"), f.clock)
        GLES20.glUniform1f(pEnv.u("uWall"), f.wall)
        GLES20.glUniform1f(pEnv.u("uGroundShow"), f.groundShow)
        GLES20.glUniform1f(pEnv.u("uDeckY"), f.deckY)
        GLES20.glUniform1f(pEnv.u("uWhite"), f.white)
        pEnv.drawQuad()

        target.bind()
        pCopy.use()
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, env.tex)
        GLES20.glUniform1i(pCopy.u("uImage"), 0)
        pCopy.drawQuad()

        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glDepthMask(true)
        GLES20.glClearDepthf(1f)
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT)
        if (f.body.count > 0) {
            bodies.update(f.body)
            begin(pDiver, identity, f)
            bodies.draw(pDiver)
        }
        for (h in f.heads) {
            begin(pHelmet, h.model, f)
            helmet.draw(pHelmet)
        }
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)
        for (h in f.heads) {
            begin(pFace, h.model, f)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, faceTex)
            GLES20.glUniform1i(pFace.u("uFace"), 0)
            GLES20.glUniform2f(pFace.u("uSize"), FRAME_W.toFloat(), FRAME_H.toFloat())
            GLES20.glUniform2f(pFace.u("uCentre"), h.faceX, h.faceY)
            GLES20.glUniform2f(pFace.u("uReach"), h.reachX, h.reachY)
            GLES20.glUniform1f(pFace.u("uRoll"), h.roll)
            GLES20.glUniform1f(pFace.u("uTime"), f.clock)
            GLES20.glUniform1f(pFace.u("uWind"), f.wind)
            face.draw(pFace)
        }
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    private fun begin(p: Program, m: FloatArray, f: Fall3D) {
        p.use()
        GLES20.glUniformMatrix4fv(p.u("uModel"), 1, false, m, 0)
        GLES20.glUniformMatrix4fv(p.u("uViewProj"), 1, false, f.viewProj, 0)
        GLES20.glUniform3fv(p.u("uEye"), 1, FallView.eye, 0)
        GLES20.glUniform1f(p.u("uWhite"), f.white)
    }

    companion object {
        /** The ground photo in the assets, and how many metres across it's laid out. */
        const val GROUND_ASSET = "ground/farmland.jpg"
        const val GROUND_SPAN = 9000f

        // The helmet shell, in head units: an ellipsoid round the head, cut open in its shader.
        const val HELMET_CY = 0.05f
        const val HELMET_CZ = -0.03f
        const val HELMET_RX = 0.168f
        const val HELMET_RY = 0.2f
        const val HELMET_RZ = 0.19f

        private fun ellipsoid(cy: Float, cz: Float, rx: Float, ry: Float, rz: Float): Mesh {
            val segs = 48
            val rings = 32
            val data = FloatArray((segs + 1) * (rings + 1) * 6)
            val idx = ArrayList<Int>()
            var o = 0
            for (j in 0..segs) {
                val th = (2 * PI * j / segs).toFloat()
                for (i in 0..rings) {
                    val phi = (PI * i / rings).toFloat()
                    val s = sin(phi)
                    val yn = cos(phi)
                    data[o++] = rx * s * sin(th)
                    data[o++] = cy + ry * yn
                    data[o++] = cz + rz * s * cos(th)
                    var nx = s * sin(th) / rx
                    var ny = yn / ry
                    var nz = s * cos(th) / rz
                    val l = sqrt(nx * nx + ny * ny + nz * nz)
                    nx /= l
                    ny /= l
                    nz /= l
                    data[o++] = nx
                    data[o++] = ny
                    data[o++] = nz
                }
            }
            for (j in 0 until segs) {
                for (i in 0 until rings) {
                    val a = j * (rings + 1) + i
                    idx += listOf(a, a + 1, a + rings + 2, a, a + rings + 2, a + rings + 1)
                }
            }
            return Mesh(data, idx.toIntArray())
        }

        // A gently domed rounded square facing +z, centred at the face's middle.
        private fun faceWindow(): Mesh {
            val n = 16
            val data = FloatArray((n + 1) * (n + 1) * 6)
            val idx = ArrayList<Int>()
            var o = 0
            for (i in 0..n) {
                for (j in 0..n) {
                    val u = -1f + 2f * j / n
                    val v = -1f + 2f * i / n
                    data[o++] = u * DiverParts.FACE_HALF_W
                    data[o++] = DiverParts.FACE_Y + v * DiverParts.FACE_HALF_H
                    data[o++] = 0.06f + 0.045f * (1f - u * u) - 0.02f * v * v
                    val nx = 0.09f * u / DiverParts.FACE_HALF_W
                    val ny = 0.04f * v / DiverParts.FACE_HALF_H
                    val l = sqrt(nx * nx + ny * ny + 1f)
                    data[o++] = nx / l
                    data[o++] = ny / l
                    data[o++] = 1f / l
                }
            }
            for (i in 0 until n) {
                for (j in 0 until n) {
                    val a = i * (n + 1) + j
                    idx += listOf(a, a + 1, a + n + 2, a, a + n + 2, a + n + 1)
                }
            }
            return Mesh(data, idx.toIntArray())
        }

        // Soft cloud noise that tiles: two fractal value-noise channels, contrast stretched.
        private fun noiseTexture(): Int {
            val size = 256
            val a = fbm(size, 11)
            val b = fbm(size, 29)
            val buf = ByteBuffer.allocateDirect(size * size * 4).order(ByteOrder.nativeOrder())
            for (i in 0 until size * size) {
                buf.put((a[i] * 255).toInt().toByte())
                buf.put((b[i] * 255).toInt().toByte())
                buf.put(0)
                buf.put(-1)
            }
            buf.position(0)
            val tex = genTexture(GLES20.GL_TEXTURE_2D)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, size, size, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
            mipmapped(GLES20.GL_REPEAT)
            return tex
        }

        private fun fbm(size: Int, seed: Int): FloatArray {
            val out = FloatArray(size * size)
            var amp = 0.5f
            for (cells in intArrayOf(4, 8, 16, 32, 64)) {
                val rnd = Random(seed * 131L + cells)
                val lattice = FloatArray(cells * cells) { rnd.nextFloat() }
                for (y in 0 until size) {
                    val fy = y.toFloat() * cells / size
                    val iy = fy.toInt()
                    val ty = fy - iy
                    val sy = ty * ty * (3 - 2 * ty)
                    for (x in 0 until size) {
                        val fx = x.toFloat() * cells / size
                        val ix = fx.toInt()
                        val tx = fx - ix
                        val sx = tx * tx * (3 - 2 * tx)
                        val x1 = (ix + 1) % cells
                        val y1 = (iy + 1) % cells
                        val top = lattice[iy * cells + ix] + (lattice[iy * cells + x1] - lattice[iy * cells + ix]) * sx
                        val bottom = lattice[y1 * cells + ix] + (lattice[y1 * cells + x1] - lattice[y1 * cells + ix]) * sx
                        out[y * size + x] += amp * (top + (bottom - top) * sy)
                    }
                }
                amp *= 0.5f
            }
            var lo = Float.MAX_VALUE
            var hi = -Float.MAX_VALUE
            for (v in out) {
                if (v < lo) lo = v
                if (v > hi) hi = v
            }
            for (i in out.indices) out[i] = (out[i] - lo) / (hi - lo)
            return out
        }

        // The ground photo, or a plain green field if it's missing.
        private fun groundTexture(assets: AssetManager): Int {
            val tex = genTexture(GLES20.GL_TEXTURE_2D)
            val bmp = runCatching { assets.open(GROUND_ASSET).use { BitmapFactory.decodeStream(it) } }.getOrNull()
            if (bmp != null) {
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
                bmp.recycle()
            } else {
                val px = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).put(byteArrayOf(90, 130.toByte(), 70, -1))
                px.position(0)
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px)
            }
            mipmapped(GLES30.GL_MIRRORED_REPEAT)
            return tex
        }

        private fun mipmapped(wrap: Int) {
            GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, wrap)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, wrap)
        }
    }
}
