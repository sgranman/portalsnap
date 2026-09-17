package net.sgransoft.portalsnap

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLUtils
import android.opengl.Matrix
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// Cat Hat's kitten: FainoDS's textured "Kitten" (CC BY 4.0, see THIRD-PARTY.md), rigged and laid
// down by tools/cat/build_cat.py. The mesh arrives already lying on its belly with its skeleton;
// the app only turns bones from there. Skinning happens on the GPU, 24 bones and up to four per
// vertex, and the lids that blink are made here each frame, a shell rolled down over each eye.

/** The kitten as build_cat.py wrote it, in its own units: x right, y down, z away, facing -z. */
class CatModel private constructor(buf: ByteBuffer) {
    val boneCount: Int
    val parent: IntArray
    /** Each bone's joint in the lying pose, three per bone. */
    val pivot: FloatArray
    val names: Array<String>
    /** (kind, first index, index count) per part: FUR, EYE. */
    val parts: IntArray
    val eyes: Array<Eye>
    val vertexCount: Int
    val vertices: FloatBuffer
    val indexCount: Int
    val indices: ByteBuffer

    /** One eye: its centre, radii across, up and through, the head's resting turn, lid colours. */
    class Eye(val cx: Float, val cy: Float, val cz: Float, val rx: Float, val ry: Float, val rz: Float, val rot: FloatArray, val brow: FloatArray, val edge: FloatArray)

    init {
        buf.order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4).also { buf.get(it) }
        check(String(magic) == "CAT1") { "not a kitten" }
        vertexCount = buf.int
        indexCount = buf.int
        boneCount = buf.int
        val partCount = buf.int
        parent = IntArray(boneCount)
        pivot = FloatArray(boneCount * 3)
        names = Array(boneCount) { "" }
        for (i in 0 until boneCount) {
            parent[i] = buf.int
            for (k in 0 until 3) pivot[i * 3 + k] = buf.float
            val name = ByteArray(buf.get().toInt()).also { buf.get(it) }
            names[i] = String(name)
        }
        parts = IntArray(partCount * 3) { buf.int }
        eyes = Array(2) {
            val c = FloatArray(6) { buf.float }
            val rot = FloatArray(9) { buf.float }
            val brow = FloatArray(2) { buf.float }
            val edge = FloatArray(2) { buf.float }
            Eye(c[0], c[1], c[2], c[3], c[4], c[5], rot, brow, edge)
        }
        val floats = ByteBuffer.allocateDirect(vertexCount * STRIDE * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (i in 0 until vertexCount * STRIDE) floats.put(buf.float)
        floats.position(0)
        vertices = floats
        indices = ByteBuffer.allocateDirect(indexCount * 2).order(ByteOrder.nativeOrder())
        for (i in 0 until indexCount) indices.putShort(buf.short)
        indices.position(0)
    }

    fun bone(name: String) = names.indexOf(name).also { check(it >= 0) { "no bone $name" } }

    companion object {
        /** position 3, normal 3, tangent 4, uv 2, joints 4, weights 4 */
        const val STRIDE = 20
        const val FUR = 0
        const val EYE = 1
        /** The shaders' bone array; the model has this many. */
        const val MAX_BONES = 24

        @Volatile private var loaded: CatModel? = null
        @Volatile private var failed = false

        /** The kitten, read once; null if the asset is missing or broken. */
        fun get(assets: AssetManager?): CatModel? {
            loaded?.let { return it }
            if (failed || assets == null) return null
            synchronized(this) {
                loaded?.let { return it }
                return try {
                    val bytes = assets.open("cat/kitten.bin").use { it.readBytes() }
                    CatModel(ByteBuffer.wrap(bytes)).also {
                        check(it.boneCount <= MAX_BONES)
                        loaded = it
                    }
                } catch (e: Exception) {
                    Log.e("PSNAP", "kitten didn't load", e)
                    failed = true
                    null
                }
            }
        }
    }
}

/** One kitten for this frame, posed by CatHat and drawn by CatRenderer. */
class Cat3D(bones: Int) {
    /** Kitten units to View3D world (frame px). */
    val model = FloatArray(16)
    /** Skinning matrices in kitten units, 16 per bone. */
    val skin = FloatArray(16 * bones)
    /** A blink, both lids, from open (0) to shut (1). */
    var blink = 0f
    /** How far the upper lids hang down on their own, sleepy or content, 0 to 1. */
    var droop = 0f
    /** Roughly where on the frame it is, in frame px: left, top, right, bottom. */
    val bounds = FloatArray(4)
    /** The person's head, a unit sphere to world, drawn into depth so what is behind it hides. */
    val head = FloatArray(16)
}

object CatShaders {
    val VERTEX = """
        #version 300 es
        uniform mat4 uModel;
        uniform mat4 uViewProj;
        uniform mat4 uBones[${CatModel.MAX_BONES}];
        in vec3 aPos;
        in vec3 aNormal;
        in vec4 aTangent;
        in vec2 aUv;
        in vec4 aJoints;
        in vec4 aWeights;
        out vec3 vWorld;
        out vec3 vNormal;
        out vec4 vTangent;
        out vec2 vUv;
        out vec3 vLocal;
        void main() {
            mat4 s = uBones[int(aJoints.x)] * aWeights.x + uBones[int(aJoints.y)] * aWeights.y
                   + uBones[int(aJoints.z)] * aWeights.z + uBones[int(aJoints.w)] * aWeights.w;
            vec4 local = s * vec4(aPos, 1.0);
            vec4 w = uModel * local;
            mat3 m = mat3(uModel) * mat3(s);
            vWorld = w.xyz;
            vNormal = m * aNormal;
            vTangent = vec4(m * aTangent.xyz, aTangent.w);
            vUv = aUv;
            vLocal = local.xyz;
            gl_Position = uViewProj * w;
        }
    """.trimIndent()

    // World y is down and z away. KEY points at a soft light up to the left in front, the way a
    // window or a ceiling light usually is in a living room.
    val FRAGMENT = """
        #version 300 es
        precision highp float;
        in vec3 vWorld;
        in vec3 vNormal;
        in vec4 vTangent;
        in vec2 vUv;
        in vec3 vLocal;
        uniform sampler2D uFur;
        uniform sampler2D uBump;
        uniform vec3 uEye;
        uniform int uKind;
        out vec4 outColor;
        const vec3 KEY = vec3(-0.4364, -0.7274, -0.5298);
        void main() {
            vec3 n = normalize(vNormal);
            vec3 v = normalize(uEye - vWorld);
            if (dot(n, v) < 0.0) n = -n;
            vec3 albedo = texture(uFur, vUv).rgb;
            float up = -n.y;
            vec3 col;
            if (uKind == 1) {
                // Eyes: dark and wet, with the room and a sharp highlight on them.
                vec3 r = reflect(-v, n);
                float fres = 0.04 + 0.5 * pow(1.0 - max(dot(n, v), 0.0), 4.0);
                vec3 room = mix(vec3(0.25, 0.23, 0.22), vec3(0.95, 0.95, 1.0), smoothstep(-0.2, 0.6, -r.y));
                float glint = pow(max(dot(r, KEY), 0.0), 120.0);
                // A crisp catchlight from the same light, so the eyes look wet and alive.
                float catch = smoothstep(0.955, 0.975, dot(n, normalize(v + KEY)));
                col = albedo * 0.55 + room * fres + vec3(1.0) * (glint * 1.4 + catch * 0.9);
            } else {
                if (uKind == 0) {
                    vec3 t = normalize(vTangent.xyz - n * dot(n, vTangent.xyz));
                    vec3 b = cross(n, t) * vTangent.w;
                    vec3 bump = texture(uBump, vUv).xyz * 2.0 - 1.0;
                    n = normalize(t * bump.x + b * bump.y + n * max(bump.z, 0.2));
                }
                // Fur: light wraps round it, a pale sheen where it turns away at the edges, and
                // the underside falls into shadow.
                float wrap = max(0.0, (dot(n, KEY) + 0.45) / 1.45);
                vec3 sky = mix(vec3(0.5, 0.46, 0.43), vec3(0.85, 0.85, 0.87), up * 0.5 + 0.5);
                float edge = pow(1.0 - max(dot(n, v), 0.0), 2.2);
                col = albedo * (sky * 0.8 + vec3(1.0, 0.96, 0.9) * wrap * 0.85);
                col += albedo * vec3(0.95, 0.92, 0.9) * edge * 0.55 * (0.5 + 0.5 * up);
                col += vec3(0.05) * pow(max(dot(reflect(-v, n), KEY), 0.0), 12.0);
                if (uKind == 2) {
                    // A lid: darker along its edge, where the lashes are.
                    col *= mix(1.0, 0.42, smoothstep(0.7, 1.0, vTangent.w));
                }
            }
            outColor = vec4(col, 1.0);
        }
    """.trimIndent()
}

/** Draws Cat3D into whatever framebuffer is bound, which needs a depth buffer. */
class CatRenderer(assets: AssetManager) {
    private val cat = checkNotNull(CatModel.get(assets)) { "no kitten" }
    private val program = Program(CatShaders.VERTEX, CatShaders.FRAGMENT)
    private val pHead = Program(Shaders3D.VERTEX, AviatorShaders.DEPTH_ONLY)
    private val sphere = AviatorShape.sphere()
    private val fur = texture(assets, "cat/fur.jpg")
    private val bump = texture(assets, "cat/fur-normal.jpg")
    private val vbo: Int
    private val ibo: Int
    private val lidVbo: Int
    private val lidIbo: Int
    private val lidCount: Int
    private val lidVerts = FloatArray(LID_VERTS * 4 * CatModel.STRIDE)
    private val lidBuf = ByteBuffer.allocateDirect(lidVerts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val headBone = cat.bone("head")

    init {
        val ids = IntArray(4)
        GLES20.glGenBuffers(4, ids, 0)
        vbo = ids[0]
        ibo = ids[1]
        lidVbo = ids[2]
        lidIbo = ids[3]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, cat.vertexCount * CatModel.STRIDE * 4, cat.vertices, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, cat.indexCount * 2, cat.indices, GLES20.GL_STATIC_DRAW)
        // Lids: four grids (upper and lower, per eye), their triangles fixed, their corners moving.
        val idx = ByteBuffer.allocateDirect(LID_ROWS * LID_COLS * 6 * 4 * 2).order(ByteOrder.nativeOrder())
        for (lid in 0 until 4) {
            val base = lid * LID_VERTS
            for (i in 0 until LID_ROWS) {
                for (j in 0 until LID_COLS) {
                    val a = base + i * (LID_COLS + 1) + j
                    val c = a + LID_COLS + 2
                    for (k in intArrayOf(a, a + 1, c, a, c, a + LID_COLS + 1)) idx.putShort(k.toShort())
                }
            }
        }
        lidCount = idx.position() / 2
        idx.position(0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, lidIbo)
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, lidCount * 2, idx, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    // Fur edges against the picture stair-step badly at the Portal's resolution, so the kitten is
    // drawn into a multisampled framebuffer of its own, resolved, and laid over the frame with
    // the resolved coverage as alpha. Where that isn't available it's drawn straight in.
    private var msaa = 0
    private var resolved: Fbo? = null
    private var msaaFailed = false
    private val pOver = Program(Shaders.VERTEX, Shaders.TEX)
    private val quadPos = FloatArray(16)
    private val quadTex = FloatArray(16)

    private fun multisampled(): Boolean {
        if (msaaFailed) return false
        if (msaa != 0) return true
        val ids = IntArray(3)
        GLES20.glGenFramebuffers(1, ids, 0)
        GLES20.glGenRenderbuffers(2, ids, 1)
        val max = IntArray(1)
        GLES20.glGetIntegerv(GLES30.GL_MAX_SAMPLES, max, 0)
        val samples = min(4, max[0])
        if (samples < 2) {
            msaaFailed = true
            return false
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, ids[0])
        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, ids[1])
        GLES30.glRenderbufferStorageMultisample(GLES20.GL_RENDERBUFFER, samples, GLES30.GL_RGBA8, FRAME_W, FRAME_H)
        GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_RENDERBUFFER, ids[1])
        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, ids[2])
        GLES30.glRenderbufferStorageMultisample(GLES20.GL_RENDERBUFFER, samples, GLES30.GL_DEPTH_COMPONENT24, FRAME_W, FRAME_H)
        GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT, GLES20.GL_RENDERBUFFER, ids[2])
        val ok = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE
        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, 0)
        if (!ok) {
            Log.w("PSNAP", "kitten: no multisampled framebuffer, drawing without")
            GLES20.glDeleteFramebuffers(1, ids, 0)
            GLES20.glDeleteRenderbuffers(2, ids, 1)
            msaaFailed = true
            return false
        }
        msaa = ids[0]
        resolved = Fbo(FRAME_W, FRAME_H)
        Log.i("PSNAP", "kitten: ${samples}x multisampling")
        return true
    }

    private var prepared = false
    private val rect = IntArray(4)

    /**
     * Draws the kittens into their multisampled framebuffer and resolves them, before the
     * composite starts, so a tile-based GPU doesn't have to set the half-drawn composite aside
     * and load it back around them. Only the rectangle the kittens are in is cleared, resolved
     * and laid over.
     */
    fun prepare(list: List<Cat3D>) {
        prepared = false
        if (!multisampled()) return
        // Only the part of the frame the kittens are in.
        var x0 = FRAME_W.toFloat()
        var y0 = FRAME_H.toFloat()
        var x1 = 0f
        var y1 = 0f
        for (c in list) {
            x0 = min(x0, c.bounds[0])
            y0 = min(y0, c.bounds[1])
            x1 = max(x1, c.bounds[2])
            y1 = max(y1, c.bounds[3])
        }
        // GL rows count up from the bottom.
        val gx = x0.toInt().coerceIn(0, FRAME_W)
        val gy = (FRAME_H - y1.toInt() - 1).coerceIn(0, FRAME_H)
        val gw = (x1.toInt() + 1).coerceIn(0, FRAME_W) - gx
        val gh = (FRAME_H - y0.toInt()).coerceIn(0, FRAME_H) - gy
        prepared = true
        rect[0] = gx
        rect[1] = gy
        rect[2] = gw
        rect[3] = gh
        if (gw <= 0 || gh <= 0) return
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, msaa)
        GLES20.glViewport(0, 0, FRAME_W, FRAME_H)
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glScissor(gx, gy, gw, gh)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawCats(list)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, msaa)
        GLES20.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, resolved!!.fbo)
        GLES30.glBlitFramebuffer(gx, gy, gx + gw, gy + gh, gx, gy, gx + gw, gy + gh, GLES20.GL_COLOR_BUFFER_BIT, GLES20.GL_NEAREST)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    /** Into the bound framebuffer: what [prepare] resolved, or the kittens themselves without it. */
    fun draw(list: List<Cat3D>) {
        if (!prepared) {
            drawCats(list)
            return
        }
        prepared = false
        val gx = rect[0]
        val gy = rect[1]
        val gw = rect[2]
        val gh = rect[3]
        if (gw <= 0 || gh <= 0) return
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        // The unit quad over just that rectangle, sampling the same rectangle of the resolve.
        val fw = FRAME_W.toFloat()
        val fh = FRAME_H.toFloat()
        Matrix.setIdentityM(quadPos, 0)
        Matrix.translateM(quadPos, 0, (gx + gw / 2f) / fw * 2 - 1, (gy + gh / 2f) / fh * 2 - 1, 0f)
        Matrix.scaleM(quadPos, 0, gw / fw, gh / fh, 1f)
        Matrix.setIdentityM(quadTex, 0)
        Matrix.translateM(quadTex, 0, gx / fw, gy / fh, 0f)
        Matrix.scaleM(quadTex, 0, gw / fw, gh / fh, 1f)
        pOver.use()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, resolved!!.tex)
        GLES20.glUniform1i(pOver.u("uTexture"), 0)
        pOver.drawQuad(quadPos, quadTex)
    }

    private fun drawCats(list: List<Cat3D>) {
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
        for (c in list) {
            GLES20.glUniformMatrix4fv(pHead.u("uModel"), 1, false, c.head, 0)
            sphere.draw(pHead)
        }
        GLES20.glColorMask(true, true, true, true)

        program.use()
        GLES20.glUniformMatrix4fv(program.u("uViewProj"), 1, false, View3D.viewProj, 0)
        GLES20.glUniform3fv(program.u("uEye"), 1, View3D.eye, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fur)
        GLES20.glUniform1i(program.u("uFur"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bump)
        GLES20.glUniform1i(program.u("uBump"), 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        for (c in list) {
            GLES20.glUniformMatrix4fv(program.u("uModel"), 1, false, c.model, 0)
            GLES20.glUniformMatrix4fv(program.u("uBones[0]"), cat.boneCount, false, c.skin, 0)
            bind(vbo)
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo)
            for (p in 0 until cat.parts.size / 3) {
                GLES20.glUniform1i(program.u("uKind"), cat.parts[p * 3])
                // The offset is in bytes, two per index.
                GLES20.glDrawElements(GLES20.GL_TRIANGLES, cat.parts[p * 3 + 2], GLES20.GL_UNSIGNED_SHORT, cat.parts[p * 3 + 1] * 2)
            }
            if (c.blink > 0.02f || c.droop > 0.02f) {
                buildLids(c.blink, c.droop)
                lidBuf.clear()
                lidBuf.put(lidVerts).flip()
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, lidVbo)
                GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, lidVerts.size * 4, lidBuf, GLES20.GL_DYNAMIC_DRAW)
                bind(lidVbo)
                GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, lidIbo)
                GLES20.glUniform1i(program.u("uKind"), 2)
                GLES20.glDrawElements(GLES20.GL_TRIANGLES, lidCount, GLES20.GL_UNSIGNED_SHORT, 0)
            }
            unbind()
        }

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    private fun bind(buffer: Int) {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffer)
        val stride = CatModel.STRIDE * 4
        for ((name, size, offset) in ATTRIBUTES) {
            val a = program.a(name)
            if (a < 0) continue
            GLES20.glEnableVertexAttribArray(a)
            GLES20.glVertexAttribPointer(a, size, GLES20.GL_FLOAT, false, stride, offset * 4)
        }
    }

    private fun unbind() {
        for ((name, _, _) in ATTRIBUTES) {
            val a = program.a(name)
            if (a >= 0) GLES20.glDisableVertexAttribArray(a)
        }
        // The 2D passes draw from client-side arrays, which need no buffer bound.
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    /**
     * Four lids on shells just outside each eye. A lid is a band of the shell between two
     * latitudes that circle the eye from one corner to the other, measured from straight up
     * toward the front: the upper lid's top edge stays tucked up under the brow and its lower
     * edge rolls down over the eye; the lower lid rises a little to meet it. Open, both bands
     * are too thin to see.
     */
    private fun buildLids(blink: Float, droop: Float) {
        var o = 0
        val upper = max(blink, droop).coerceIn(0f, 1f)
        val lower = blink.coerceIn(0f, 1f)
        for (eye in cat.eyes) {
            // Upper: from up and back under the brow, down past the middle of the eye.
            o = band(eye, o, UPPER_TOP, UPPER_TOP + (UPPER_SHUT - UPPER_TOP) * upper)
            // Lower: from under the eye up to meet the upper lid.
            o = band(eye, o, LOWER_BOTTOM, LOWER_BOTTOM + (LOWER_SHUT - LOWER_BOTTOM) * lower)
        }
    }

    private fun band(eye: CatModel.Eye, start: Int, from: Float, to: Float): Int {
        var o = start
        val r = eye.rot
        for (i in 0..LID_ROWS) {
            val t = i.toFloat() / LID_ROWS
            val a = from + (to - from) * t
            // Rows near the moving edge take the colour just above the eye; the rest take the brow.
            val u = eye.brow[0] + (eye.edge[0] - eye.brow[0]) * t
            val v = eye.brow[1] + (eye.edge[1] - eye.brow[1]) * t
            for (j in 0..LID_COLS) {
                // Round from one corner of the eye (b = 0) to the other (b = pi).
                val b = PI.toFloat() * j / LID_COLS
                val sb = sin(b)
                // Eye-local unit direction: x across, y down, z away; a = 0 is up, pi / 2 is forward.
                val lx = cos(b)
                val ly = -cos(a) * sb
                val lz = -sin(a) * sb
                // The lid is thickest in the middle and meets the eye at the corners.
                val puff = LID_GAP + LID_PUFF * sb
                val px = lx * eye.rx * puff
                val py = ly * eye.ry * puff
                val pz = lz * eye.rz * puff
                lidVerts[o] = eye.cx + r[0] * px + r[3] * py + r[6] * pz
                lidVerts[o + 1] = eye.cy + r[1] * px + r[4] * py + r[7] * pz
                lidVerts[o + 2] = eye.cz + r[2] * px + r[5] * py + r[8] * pz
                val nx = lx / eye.rx
                val ny = ly / eye.ry
                val nz = lz / eye.rz
                lidVerts[o + 3] = r[0] * nx + r[3] * ny + r[6] * nz
                lidVerts[o + 4] = r[1] * nx + r[4] * ny + r[7] * nz
                lidVerts[o + 5] = r[2] * nx + r[5] * ny + r[8] * nz
                lidVerts[o + 6] = 1f
                lidVerts[o + 7] = 0f
                lidVerts[o + 8] = 0f
                // The shader reads the tangent's w on a lid as how near the lash line this row is.
                lidVerts[o + 9] = t
                lidVerts[o + 10] = u
                lidVerts[o + 11] = v
                lidVerts[o + 12] = headBone.toFloat()
                lidVerts[o + 13] = 0f
                lidVerts[o + 14] = 0f
                lidVerts[o + 15] = 0f
                lidVerts[o + 16] = 1f
                lidVerts[o + 17] = 0f
                lidVerts[o + 18] = 0f
                lidVerts[o + 19] = 0f
                o += CatModel.STRIDE
            }
        }
        return o
    }

    companion object {
        private const val LID_ROWS = 6
        private const val LID_COLS = 14
        private const val LID_VERTS = (LID_ROWS + 1) * (LID_COLS + 1)
        /** Latitudes in radians from straight up toward the front. */
        private const val UPPER_TOP = -0.5f
        private const val UPPER_SHUT = 1.75f
        private const val LOWER_BOTTOM = 3.3f
        private const val LOWER_SHUT = 1.7f
        /** Lids sit this far out from the eye's surface, and this much more in the middle. */
        private const val LID_GAP = 1.04f
        private const val LID_PUFF = 0.06f

        private val ATTRIBUTES = listOf(
            Triple("aPos", 3, 0), Triple("aNormal", 3, 3), Triple("aTangent", 4, 6),
            Triple("aUv", 2, 10), Triple("aJoints", 4, 12), Triple("aWeights", 4, 16),
        )

        private fun texture(assets: AssetManager, path: String): Int {
            val tex = genTexture(GLES20.GL_TEXTURE_2D)
            val bmp = assets.open(path).use { BitmapFactory.decodeStream(it) }
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            bmp.recycle()
            GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)
            return tex
        }
    }
}

/** Kitten-space rig maths shared by CatHat: local turns in, skinning matrices out. */
class CatRig(private val cat: CatModel) {
    val boneCount = cat.boneCount
    /** Per bone local rotation (radians about x, then y, then z, in its parent's frame). */
    val rx = FloatArray(cat.boneCount)
    val ry = FloatArray(cat.boneCount)
    val rz = FloatArray(cat.boneCount)
    /** The whole kitten's offset from where it lies, in kitten units. */
    val shift = FloatArray(3)
    private val world = FloatArray(16 * cat.boneCount)
    private val local = FloatArray(16)

    fun reset() {
        rx.fill(0f)
        ry.fill(0f)
        rz.fill(0f)
        shift.fill(0f)
    }

    /** Writes each bone's skinning matrix into [out], 16 per bone. */
    fun solve(out: FloatArray) {
        val pv = cat.pivot
        for (i in 0 until cat.boneCount) {
            val p = cat.parent[i]
            Matrix.setIdentityM(local, 0)
            if (p < 0) {
                Matrix.translateM(local, 0, pv[i * 3] + shift[0], pv[i * 3 + 1] + shift[1], pv[i * 3 + 2] + shift[2])
            } else {
                Matrix.translateM(local, 0, pv[i * 3] - pv[p * 3], pv[i * 3 + 1] - pv[p * 3 + 1], pv[i * 3 + 2] - pv[p * 3 + 2])
            }
            if (rz[i] != 0f) Matrix.rotateM(local, 0, deg(rz[i]), 0f, 0f, 1f)
            if (ry[i] != 0f) Matrix.rotateM(local, 0, deg(ry[i]), 0f, 1f, 0f)
            if (rx[i] != 0f) Matrix.rotateM(local, 0, deg(rx[i]), 1f, 0f, 0f)
            if (p < 0) {
                System.arraycopy(local, 0, world, i * 16, 16)
            } else {
                Matrix.multiplyMM(world, i * 16, world, p * 16, local, 0)
            }
            Matrix.translateM(out, i * 16, world, i * 16, -pv[i * 3], -pv[i * 3 + 1], -pv[i * 3 + 2])
        }
    }

    /** A bone's joint after [solve], in kitten units, into out[0..2]. */
    fun joint(i: Int, out: FloatArray) {
        out[0] = world[i * 16 + 12]
        out[1] = world[i * 16 + 13]
        out[2] = world[i * 16 + 14]
    }
}
