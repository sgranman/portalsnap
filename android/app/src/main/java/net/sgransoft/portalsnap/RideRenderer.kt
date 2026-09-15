package net.sgransoft.portalsnap

import android.opengl.GLES20
import android.opengl.Matrix
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

object RideShaders {
    // The low sun: to the right, fairly low, beyond the far end of the path.
    private val LIGHT = """
        const vec3 SUN = vec3(0.6921, 0.4944, -0.5261);
        uniform vec3 uFog;
        uniform float uFogStart;
        uniform float uFogRange;

        vec3 fogged(vec3 col, float d) {
            float f = clamp((d - uFogStart) / uFogRange, 0.0, 1.0);
            return mix(col, uFog, f * (2.0 - f));
        }

        vec3 shade(vec3 base, vec3 n, vec3 v) {
            float sun = max(dot(n, SUN), 0.0);
            vec3 amb = mix(vec3(0.42, 0.38, 0.40), vec3(0.72, 0.72, 0.76), 0.5 + 0.5 * n.y);
            vec3 col = base * (amb + vec3(1.0, 0.86, 0.72) * sun * 0.8);
            // Warm light wrapping round the edges from the sun beyond.
            float rim = pow(1.0 - max(dot(n, v), 0.0), 3.0) * (0.35 + 0.65 * max(dot(-v, SUN), 0.0));
            return col + vec3(1.0, 0.8, 0.55) * rim * 0.35;
        }
    """ // not trimmed: it's pasted into indented shaders, which trim the whole

    val COLOR_VERTEX = """
        #version 300 es
        uniform mat4 uModel;
        uniform mat4 uViewProj;
        in vec3 aPos;
        in vec3 aNormal;
        in vec4 aColor;
        out vec3 vWorld;
        out vec3 vNormal;
        out vec4 vColor;
        void main() {
            vec4 w = uModel * vec4(aPos, 1.0);
            vWorld = w.xyz;
            vNormal = mat3(uModel) * aNormal;
            vColor = aColor;
            gl_Position = uViewProj * w;
        }
    """.trimIndent()

    val COLOR = """
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
            vec3 col = mix(shade(vColor.rgb, n, v), vColor.rgb, vColor.a);
            outColor = vec4(fogged(col, length(vWorld - uEye)), 1.0);
        }
    """.trimIndent()

    // The path, its orange edging, the brick walk beside it, and grass, with soft shadows of trees
    // drifting across. uScroll moves the pattern away down the path.
    val GROUND = """
        #version 300 es
        precision highp float;
        in vec3 vWorld;
        in vec3 vNormal;
        in vec3 vLocal;
        in vec3 vLocalNormal;
        uniform vec3 uEye;
        uniform float uScroll;
        out vec4 outColor;
        $LIGHT
        const float ROAD = ${Park.ROAD};
        const float CURB = ${Park.CURB};
        const float BRICK = ${Park.BRICK};

        float hash(vec2 p) {
            return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
        }

        float noise(vec2 p) {
            vec2 i = floor(p);
            vec2 f = fract(p);
            f = f * f * (3.0 - 2.0 * f);
            return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
        }

        void main() {
            float x = vWorld.x;
            float ax = abs(x);
            float pz = vWorld.z + uScroll;
            float d = length(vWorld - uEye);
            float fw = fwidth(pz) + fwidth(x);
            vec3 col;
            if (ax < ROAD) {
                col = vec3(0.435, 0.445, 0.435) * (0.95 + 0.07 * noise(vec2(x * 4.0, pz * 4.0)));
                col *= 1.0 - 0.06 * smoothstep(ROAD - 0.25, ROAD, ax);
            } else if (ax < ROAD + CURB) {
                col = vec3(0.80, 0.41, 0.21);
            } else if (ax < ROAD + CURB + BRICK) {
                vec2 q = vec2((ax - ROAD - CURB) / 0.15, pz / 0.3);
                float row = floor(q.x);
                float along = q.y + row * 0.5;
                vec2 f = fract(vec2(q.x, along));
                float mortar = step(0.12, f.x) * step(0.07, f.y);
                float tone = hash(vec2(row, floor(along)));
                vec3 brick = mix(vec3(0.58, 0.31, 0.19), vec3(0.66, 0.36, 0.22), tone);
                vec3 grout = vec3(0.5, 0.29, 0.2);
                col = mix(mix(grout, brick, mortar), mix(grout, brick, 0.82), smoothstep(0.03, 0.12, fw));
            } else {
                col = vec3(0.12, 0.30, 0.09) * (0.85 + 0.25 * noise(vec2(x * 0.7, pz * 0.7)));
            }
            // Tree shadows, a soft blob every so often on either side.
            float shadow = 0.0;
            for (int k = -1; k <= 1; k++) {
                float cell = floor(pz / 7.0) + float(k);
                float cz = (cell + hash(vec2(cell, 3.0))) * 7.0;
                float cx = (hash(vec2(cell, 9.0)) - 0.5) * 6.0;
                vec2 e = vec2((x - cx) / 1.9, (pz - cz) / 1.0);
                shadow = max(shadow, (1.0 - smoothstep(0.3, 1.0, length(e))) * step(0.35, hash(vec2(cell, 5.0))));
            }
            col *= 1.0 - 0.32 * shadow;
            outColor = vec4(fogged(col, d), 1.0);
        }
    """.trimIndent()

    // The sky from grey-lilac overhead to peach and pink at the horizon, and a hazy city far
    // behind the trees, taller toward the middle. ES 2 so it can use the shared quad.
    val SKY = """
        precision highp float;
        varying vec2 vUv;
        uniform float uAspect;
        uniform vec3 uFog;

        float hash(float n) {
            return fract(sin(n * 12.9898) * 43758.5453);
        }

        void main() {
            float h = (vUv.y - 0.5) * 2.0;
            vec3 low = vec3(0.95, 0.63, 0.60);
            vec3 peach = vec3(0.97, 0.73, 0.64);
            vec3 top = vec3(0.73, 0.72, 0.72);
            vec3 col = mix(low, peach, smoothstep(0.0, 0.35, h));
            col = mix(col, top, smoothstep(0.35, 1.05, h));
            float x = (vUv.x - 0.5) * uAspect;
            for (int layer = 0; layer < 2; layer++) {
                float fl = float(layer);
                float w = 0.075 - 0.02 * fl;
                float cx = x / w + fl * 0.37 + 11.0;
                float id = floor(cx);
                float r = hash(id + fl * 57.0);
                float tall = mix(0.1, 0.55, r * r) * mix(1.0, 0.4, smoothstep(0.05, 0.9, abs(x - 0.06)));
                if (hash(id * 1.3 + 7.0 + fl) < 0.2) tall *= 0.5;
                float gap = step(0.06, fract(cx)) * step(fract(cx), 0.94);
                float inside = step(h, tall) * gap;
                vec3 bc = layer == 0 ? vec3(0.88, 0.55, 0.57) : vec3(0.76, 0.43, 0.5);
                bc = mix(bc, low, 0.45 * (1.0 - smoothstep(0.0, 0.3, h)));
                col = mix(col, bc, inside);
            }
            if (h < 0.0) col = uFog;
            gl_FragColor = vec4(col, 1.0);
        }
    """

    // Zoom blur away from the vanishing point: the park rushing past, strongest low and at the edges.
    val BLUR = """
        precision highp float;
        varying vec2 vUv;
        uniform sampler2D uScene;
        uniform vec2 uVp;
        uniform float uAmount;
        void main() {
            vec2 d = vUv - uVp;
            float r = length(d * vec2(1.7778, 1.0));
            float below = mix(0.2, 1.0, smoothstep(0.02, -0.12, d.y));
            vec2 s = d * uAmount * smoothstep(0.12, 0.8, r) * below / 7.0;
            vec4 c = vec4(0.0);
            for (int i = 0; i < 8; i++) c += texture2D(uScene, vUv + s * (float(i) - 3.5));
            gl_FragColor = c / 8.0;
        }
    """

    // The helmet: glossy white, a dark band round its lower edge, slots across the top, dark inside.
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
        const vec3 C = vec3(${RideMeshes.HELMET_CX}, ${RideMeshes.HELMET_CY}, ${RideMeshes.HELMET_CZ});
        const vec3 R = vec3(${RideMeshes.HELMET_RX}, ${RideMeshes.HELMET_RY}, ${RideMeshes.HELMET_RZ});
        void main() {
            vec3 n = normalize(vNormal);
            vec3 v = normalize(uEye - vWorld);
            bool inside = dot(n, v) < 0.0;
            if (inside) n = -n;
            vec3 p = (vLocal - C) / R;
            float th = atan(p.x, p.z);
            float rim = ${RideMeshes.RIM_BACK} + (${RideMeshes.RIM_FRONT} - ${RideMeshes.RIM_BACK}) * clamp((cos(th) + 0.5) / 0.85, 0.0, 1.0);
            float above = p.y - (rim - C.y) / R.y;
            float band = 1.0 - smoothstep(0.06, 0.09, above);
            // Slots in lanes fanned across the shell, running from the brow over the top to the
            // back, broken into a few along each lane, shorter toward the sides.
            float across = atan(p.x, max(p.y, 0.0) + 0.35);
            float lane = floor(across / 0.34 + 0.5);
            float slot = abs(across - lane * 0.34);
            float len = 0.8 - 0.2 * abs(lane);
            float breaks = smoothstep(0.1, 0.18, fract(p.z * 1.5 + 0.25 + 0.5 * abs(lane)));
            float vent = (1.0 - smoothstep(0.07, 0.1, slot)) * (1.0 - smoothstep(len - 0.12, len, abs(p.z - 0.12)))
                * step(abs(lane), 2.0) * smoothstep(0.12, 0.22, above) * breaks;
            vec3 white = vec3(0.95, 0.945, 0.94);
            vec3 col = shade(white, n, v) * 1.1 + vec3(0.05);
            float spec = pow(max(dot(n, normalize(SUN + v)), 0.0), 36.0);
            float top = pow(max(n.y, 0.0), 4.0);
            col += vec3(1.0, 0.95, 0.92) * spec * 0.4 + vec3(1.0, 0.86, 0.84) * top * 0.15;
            col = mix(col, vec3(0.2, 0.2, 0.21), vent * 0.9);
            col = mix(col, vec3(0.1, 0.105, 0.1), band * 0.92);
            if (inside) col = vec3(0.12, 0.125, 0.12);
            outColor = vec4(col, 1.0);
        }
    """.trimIndent()

    // The face, lifted off the camera onto the front of the head with a soft rounded edge, in the
    // park's light, shaded under the helmet's brim. Premultiplied.
    val FACE = """
        #version 300 es
        precision highp float;
        in vec3 vWorld;
        in vec3 vNormal;
        in vec3 vLocal;
        in vec3 vLocalNormal;
        uniform vec3 uEye;
        uniform sampler2D uFace;
        uniform vec2 uSize;
        uniform vec2 uCentre;
        uniform vec2 uReach;
        uniform float uRoll;
        out vec4 outColor;
        const vec3 SUN = vec3(0.6921, 0.4944, -0.5261);
        void main() {
            vec2 q = vLocal.xy / vec2(${RideParts.FACE_HALF_W}, ${RideParts.FACE_HALF_H});
            // Rounded at the cheeks and chin, square across the top where the helmet meets it.
            float pw = q.y > 0.0 ? 5.0 : 2.4;
            float d = pow(pow(abs(q.x), pw) + pow(max(q.y, -q.y), pw), 1.0 / pw);
            float mask = 1.0 - smoothstep(0.82, 1.0, d);
            // Nothing above the brim, which the pulled-forward face would otherwise draw over.
            if (mask <= 0.0 || vLocal.y > ${RideMeshes.RIM_FRONT}) discard;
            vec2 o = vec2(q.x, -q.y) * uReach;
            float c = cos(uRoll);
            float s = sin(uRoll);
            vec2 px = uCentre + vec2(c * o.x - s * o.y, s * o.x + c * o.y);
            vec3 face = texture(uFace, vec2(px.x / uSize.x, 1.0 - px.y / uSize.y)).rgb;
            vec3 n = normalize(vNormal);
            face *= vec3(1.02, 0.98, 0.96) * (0.9 + 0.16 * max(dot(n, SUN), 0.0));
            face *= 1.0 - 0.3 * smoothstep(0.45, 1.0, q.y);
            outColor = vec4(face * mask, mask);
        }
    """.trimIndent()

    // The face's window, pulled a few centimetres nearer along each point's own line of sight: the
    // same place on screen, but it wins the depth test against the collar just behind the chin.
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
            gl_Position = uViewProj * vec4(w.xyz + normalize(uEye - w.xyz) * 0.07, 1.0);
        }
    """.trimIndent()

    val SHADOW = """
        #version 300 es
        precision highp float;
        in vec3 vWorld;
        in vec3 vNormal;
        in vec3 vLocal;
        in vec3 vLocalNormal;
        out vec4 outColor;
        void main() {
            float a = 0.4 * (1.0 - smoothstep(0.2, 1.0, length(vLocal.xz)));
            outColor = vec4(vec3(0.04, 0.02, 0.05) * a, a);
        }
    """.trimIndent()
}

/** Fixed meshes for the ride: the ground, the helmet shell, the face window and the shadow disc. */
object RideMeshes {
    // The helmet shell as part of an ellipsoid in head units, open underneath: its edge comes down
    // to the brow at the front and lower round the back.
    const val HELMET_CX = 0f
    const val HELMET_CY = 0.085f
    const val HELMET_CZ = -0.035f
    const val HELMET_RX = 0.158f
    const val HELMET_RY = 0.145f
    const val HELMET_RZ = 0.19f
    const val RIM_FRONT = 0.105f
    const val RIM_BACK = 0.0f

    /** The helmet's edge height round the head: at the brow in front, still high at the sides, low behind. */
    fun rimAt(c: Float) = RIM_BACK + (RIM_FRONT - RIM_BACK) * ((c + 0.5f) / 0.85f).coerceIn(0f, 1f)

    fun helmet(): Mesh {
        val segs = 56
        val rings = 22
        val data = FloatArray((segs + 1) * (rings + 1) * 6)
        val idx = ArrayList<Int>()
        var o = 0
        for (j in 0..segs) {
            val th = (2 * PI * j / segs).toFloat()
            val rimY = rimAt(cos(th))
            val yRim = (rimY - HELMET_CY) / HELMET_RY
            for (i in 0..rings) {
                val t = i.toFloat() / rings
                // Even steps of angle from the crown down to the edge.
                val phiRim = acos(yRim.coerceIn(-1f, 1f))
                val phi = phiRim * t
                val yn = cos(phi)
                val s = sin(phi)
                // A little longer out the back, as helmets are.
                val tail = 1f + 0.14f * max(0f, -cos(th)) * s
                val x = HELMET_RX * s * sin(th)
                val z = HELMET_RZ * tail * s * cos(th)
                data[o++] = HELMET_CX + x
                data[o++] = HELMET_CY + HELMET_RY * yn
                data[o++] = HELMET_CZ + z
                var nx = s * sin(th) / HELMET_RX
                var ny = yn / HELMET_RY
                var nz = s * cos(th) / (HELMET_RZ * tail)
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
                val b = a + 1
                val c = a + rings + 2
                val d = a + rings + 1
                idx += listOf(a, b, c, a, c, d)
            }
        }
        return Mesh(data, idx.toIntArray())
    }

    /** The face's window: a gently domed rounded square facing +z, in head units. */
    fun faceWindow(): Mesh {
        val n = 16
        val data = FloatArray((n + 1) * (n + 1) * 6)
        val idx = ArrayList<Int>()
        var o = 0
        for (i in 0..n) {
            for (j in 0..n) {
                val u = -1f + 2f * j / n
                val v = -1f + 2f * i / n
                data[o++] = u * RideParts.FACE_HALF_W
                data[o++] = v * RideParts.FACE_HALF_H
                data[o++] = 0.055f + 0.045f * (1f - u * u) - 0.02f * v * v
                val nx = 0.09f * u / RideParts.FACE_HALF_W
                val ny = 0.04f * v / RideParts.FACE_HALF_H
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

    /** A flat square from -1 to 1 in x and z at y = 0, facing up. */
    fun flat(x0: Float, x1: Float, z0: Float, z1: Float): Mesh = Mesh(
        floatArrayOf(
            x0, 0f, z0, 0f, 1f, 0f,
            x1, 0f, z0, 0f, 1f, 0f,
            x1, 0f, z1, 0f, 1f, 0f,
            x0, 0f, z1, 0f, 1f, 0f,
        ),
        intArrayOf(0, 1, 2, 0, 2, 3),
    )
}

/**
 * Draws the ride. The park goes into its own framebuffer first (sky, ground, trees, shadows),
 * then onto [target] through the zoom blur, so the riders on top stay sharp; the riders then draw
 * into [target] with a cleared depth buffer: bodies and helmets, faces, then helmet straps.
 */
class RideRenderer {
    private val pColor = Program(RideShaders.COLOR_VERTEX, RideShaders.COLOR)
    private val pGround = Program(Shaders3D.VERTEX, RideShaders.GROUND)
    private val pSky = Program(Shaders.VERTEX, RideShaders.SKY)
    private val pBlur = Program(Shaders.VERTEX, RideShaders.BLUR)
    private val pHelmet = Program(Shaders3D.VERTEX, RideShaders.HELMET)
    private val pFace = Program(RideShaders.FACE_VERTEX, RideShaders.FACE)
    private val pShadow = Program(Shaders3D.VERTEX, RideShaders.SHADOW)
    private val chunks = arrayOf(ColorMesh(Park.build(0)), ColorMesh(Park.build(1)))
    private val ground = RideMeshes.flat(-200f, 200f, -RideView.FAR, 2f)
    private val disc = RideMeshes.flat(-1f, 1f, -1f, 1f)
    private val helmet = RideMeshes.helmet()
    private val face = RideMeshes.faceWindow()
    private val bodies = DynamicColorMesh()
    private val straps = DynamicColorMesh()
    private val scene = Fbo(FRAME_W, FRAME_H).also { it.attachDepth() }
    private val model = FloatArray(16)
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    fun draw(rides: List<Ride3D>, faceTex: Int, target: Fbo) {
        val ride = rides.last()
        scene.bind()
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)

        pSky.use()
        GLES20.glUniform1f(pSky.u("uAspect"), FRAME_W.toFloat() / FRAME_H)
        GLES20.glUniform3f(pSky.u("uFog"), FOG[0], FOG[1], FOG[2])
        pSky.drawQuad()

        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glDepthMask(true)
        GLES20.glClearDepthf(1f)
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT)

        begin(pGround, identity)
        GLES20.glUniform1f(pGround.u("uScroll"), ride.scroll)
        ground.draw(pGround)

        // Chunks nearest first, so the depth test throws away most of the forest behind.
        val laps = kotlin.math.floor(ride.scroll / Park.CHUNK).toInt()
        val into = ride.scroll - laps * Park.CHUNK
        for (k in 1 downTo -2) {
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, 0f, 0f, k * Park.CHUNK - into)
            begin(pColor, model)
            chunks[Math.floorMod(k + laps, 2)].draw(pColor)
        }

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)
        for (s in ride.shadows) {
            begin(pShadow, s)
            disc.draw(pShadow)
        }
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)

        target.bind()
        pBlur.use()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, scene.tex)
        GLES20.glUniform1i(pBlur.u("uScene"), 0)
        GLES20.glUniform2f(pBlur.u("uVp"), 0.5f, 0.5f)
        GLES20.glUniform1f(pBlur.u("uAmount"), BLUR)
        pBlur.drawQuad()

        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT)
        if (ride.body.count > 0) {
            bodies.update(ride.body)
            begin(pColor, identity)
            bodies.draw(pColor)
        }
        for (h in ride.heads) {
            begin(pHelmet, h.helmet)
            helmet.draw(pHelmet)
        }
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)
        for (h in ride.heads) {
            begin(pFace, h.model)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, faceTex)
            GLES20.glUniform1i(pFace.u("uFace"), 0)
            GLES20.glUniform2f(pFace.u("uSize"), FRAME_W.toFloat(), FRAME_H.toFloat())
            GLES20.glUniform2f(pFace.u("uCentre"), h.faceX, h.faceY)
            GLES20.glUniform2f(pFace.u("uReach"), h.reachX, h.reachY)
            GLES20.glUniform1f(pFace.u("uRoll"), h.roll)
            face.draw(pFace)
        }
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
        if (ride.straps.count > 0) {
            straps.update(ride.straps)
            begin(pColor, identity)
            straps.draw(pColor)
        }
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    private fun begin(p: Program, m: FloatArray) {
        p.use()
        GLES20.glUniformMatrix4fv(p.u("uModel"), 1, false, m, 0)
        GLES20.glUniformMatrix4fv(p.u("uViewProj"), 1, false, RideView.viewProj, 0)
        GLES20.glUniform3fv(p.u("uEye"), 1, RideView.eye, 0)
        GLES20.glUniform3f(p.u("uFog"), FOG[0], FOG[1], FOG[2])
        GLES20.glUniform1f(p.u("uFogStart"), FOG_START)
        GLES20.glUniform1f(p.u("uFogRange"), FOG_RANGE)
    }

    private companion object {
        val FOG = floatArrayOf(0.93f, 0.66f, 0.64f)
        const val FOG_START = 18f
        const val FOG_RANGE = 210f
        const val BLUR = 0.075f
    }
}
