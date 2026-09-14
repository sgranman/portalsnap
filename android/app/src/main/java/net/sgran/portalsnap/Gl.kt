package net.sgran.portalsnap

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * One EGL context for the whole app: the screen, the encoder and every offscreen pass
 * share it, so a frame composited once can be handed to all three without a copy.
 * The config is recordable because MediaCodec's input surface refuses any other kind.
 */
class EglCore {
    val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    val config: EGLConfig
    val context: EGLContext
    private val pbuffer: EGLSurface

    init {
        check(display != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        val attrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT or EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) && count[0] > 0) {
            "no recordable EGL config"
        }
        config = configs[0]!!
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext: 0x%x".format(EGL14.eglGetError()) }
        pbuffer = EGL14.eglCreatePbufferSurface(
            display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
        )
        makePbufferCurrent()
    }

    fun windowSurface(surface: Any): EGLSurface {
        val s = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(s != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface: 0x%x".format(EGL14.eglGetError()) }
        return s
    }

    fun makeCurrent(s: EGLSurface) {
        check(EGL14.eglMakeCurrent(display, s, s, context)) { "eglMakeCurrent: 0x%x".format(EGL14.eglGetError()) }
    }

    fun makePbufferCurrent() = makeCurrent(pbuffer)

    fun swap(s: EGLSurface) = EGL14.eglSwapBuffers(display, s)

    fun presentationTime(s: EGLSurface, nanos: Long) = EGLExt.eglPresentationTimeANDROID(display, s, nanos)

    fun release(s: EGLSurface) {
        EGL14.eglDestroySurface(display, s)
    }

    private companion object {
        const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}

class Program(vs: String, fs: String) {
    val id: Int
    private val locations = HashMap<String, Int>()

    init {
        fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "shader: " + GLES20.glGetShaderInfoLog(s) }
            return s
        }
        id = GLES20.glCreateProgram()
        GLES20.glAttachShader(id, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(id, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(id)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "link: " + GLES20.glGetProgramInfoLog(id) }
    }

    fun use() = GLES20.glUseProgram(id)

    fun u(name: String) = locations.getOrPut(name) { GLES20.glGetUniformLocation(id, name) }

    fun a(name: String) = locations.getOrPut("@$name") { GLES20.glGetAttribLocation(id, name) }

    /** Draws the unit quad; positions and texture matrices come from the uniforms. */
    fun drawQuad(pos: FloatArray = IDENTITY, tex: FloatArray = IDENTITY) {
        GLES20.glUniformMatrix4fv(u("uPos"), 1, false, pos, 0)
        GLES20.glUniformMatrix4fv(u("uTex"), 1, false, tex, 0)
        val ap = a("aPos")
        val au = a("aUv")
        GLES20.glEnableVertexAttribArray(ap)
        GLES20.glVertexAttribPointer(ap, 2, GLES20.GL_FLOAT, false, 0, QUAD_POS)
        GLES20.glEnableVertexAttribArray(au)
        GLES20.glVertexAttribPointer(au, 2, GLES20.GL_FLOAT, false, 0, QUAD_UV)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    companion object {
        val IDENTITY = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }
        private val QUAD_POS = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        private val QUAD_UV = floats(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
    }
}

/** A texture-backed framebuffer. Textures keep GL's convention: t = 1 is the top row. */
class Fbo(val w: Int, val h: Int) {
    val tex: Int = genTexture(GLES20.GL_TEXTURE_2D)
    val fbo: Int

    init {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        val ids = IntArray(1)
        GLES20.glGenFramebuffers(1, ids, 0)
        fbo = ids[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    fun bind() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glViewport(0, 0, w, h)
    }

    fun release() {
        GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        GLES20.glDeleteTextures(1, intArrayOf(tex), 0)
    }

    /** Rows come back bottom-first, as glReadPixels returns them. */
    fun read(into: ByteBuffer) {
        bind()
        into.rewind()
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, into)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    fun generateMipmaps() {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES30.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
    }
}

fun genTexture(target: Int): Int {
    val ids = IntArray(1)
    GLES20.glGenTextures(1, ids, 0)
    GLES20.glBindTexture(target, ids[0])
    GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
    GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    return ids[0]
}

fun genOesTexture() = genTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)

fun floats(vararg v: Float): FloatBuffer =
    ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(v)
        position(0)
    }

object Shaders {
    const val VERTEX = """
        attribute vec4 aPos;
        attribute vec4 aUv;
        uniform mat4 uPos;
        uniform mat4 uTex;
        varying vec2 vUv;
        void main() {
            gl_Position = uPos * aPos;
            vUv = (uTex * aUv).xy;
        }
    """

    const val OES = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vUv;
        uniform samplerExternalOES uTexture;
        void main() {
            gl_FragColor = texture2D(uTexture, vUv);
        }
    """

    const val TEX = """
        precision mediump float;
        varying vec2 vUv;
        uniform sampler2D uTexture;
        void main() {
            gl_FragColor = texture2D(uTexture, vUv);
        }
    """

    // A region of the frame, resampled through an affine map and cut to a feathered
    // ellipse. Big Head's zoomed head and the skydiver's face in his helmet are both
    // this. Works in frame pixels with y down, like the filters do.
    const val PATCH = """
        precision highp float;
        uniform sampler2D uTexture;
        uniform vec2 uSize;
        uniform vec2 uCentre;
        uniform vec2 uRadii;
        uniform float uAngle;
        uniform float uFeather;
        uniform mat3 uMap;
        void main() {
            vec2 p = vec2(gl_FragCoord.x, uSize.y - gl_FragCoord.y);
            vec2 d = p - uCentre;
            float c = cos(uAngle), s = sin(uAngle);
            vec2 q = vec2(c * d.x + s * d.y, -s * d.x + c * d.y) / uRadii;
            float r = length(q);
            float a = 1.0 - smoothstep(uFeather, 1.0, r);
            if (a <= 0.0) discard;
            vec3 src = uMap * vec3(p, 1.0);
            gl_FragColor = texture2D(uTexture, vec2(src.x / uSize.x, 1.0 - src.y / uSize.y)) * a;
        }
    """

    // The person, cut out by the segmentation mask. The mask is small and hard-edged;
    // linear upscaling plus a 3x3 tap is the whole of the feathering, as the web app's
    // blur() on the scaled mask was. Taps sit 1.5 mask pixels apart (uTexel is scaled by
    // the caller): at one pixel the 256-wide mask still stair-stepped along shoulders.
    const val MASK = """
        precision mediump float;
        varying vec2 vUv;
        uniform sampler2D uTexture;
        uniform sampler2D uMask;
        uniform vec2 uTexel;
        void main() {
            vec2 m = vec2(vUv.x, 1.0 - vUv.y);
            float a = 0.0;
            for (int i = -1; i <= 1; i++) {
                for (int j = -1; j <= 1; j++) {
                    a += texture2D(uMask, m + vec2(float(i), float(j)) * uTexel).a;
                }
            }
            a /= 9.0;
            gl_FragColor = texture2D(uTexture, vUv) * a;
        }
    """

    // Photo Booth's mirror: the half of the frame that is on the child's left in the
    // (mirrored) preview, reflected onto the other half.
    const val FX_MIRROR = """
        precision mediump float;
        varying vec2 vUv;
        uniform sampler2D uTexture;
        void main() {
            vec2 uv = vUv;
            if (uv.x < 0.5) uv.x = 1.0 - uv.x;
            gl_FragColor = texture2D(uTexture, uv);
        }
    """

    // Pop silhouette: the person a flat gradient, the room a flat colour with a ghost of
    // its own texture left in, feathered at the mask edge like MASK.
    const val FX_POP = """
        precision mediump float;
        varying vec2 vUv;
        uniform sampler2D uTexture;
        uniform sampler2D uMask;
        uniform vec2 uTexel;
        uniform vec3 uBg;
        uniform vec3 uTop;
        uniform vec3 uBottom;
        uniform float uGhost;
        uniform float uFlash;
        void main() {
            vec2 m = vec2(vUv.x, 1.0 - vUv.y);
            float a = 0.0;
            for (int i = -1; i <= 1; i++) {
                for (int j = -1; j <= 1; j++) {
                    a += texture2D(uMask, m + vec2(float(i), float(j)) * uTexel).a;
                }
            }
            a /= 9.0;
            vec3 cam = texture2D(uTexture, vUv).rgb;
            float lum = dot(cam, vec3(0.299, 0.587, 0.114));
            vec3 bg = uBg * (1.0 - uGhost + uGhost * lum * 1.7);
            float g = clamp(m.y * 0.75 + vUv.x * 0.25, 0.0, 1.0);
            vec3 person = mix(uTop, uBottom, g) * (0.94 + 0.12 * lum);
            gl_FragColor = vec4(mix(bg, person, a) + uFlash, 1.0);
        }
    """

    // Disco: a purple wash, and an LED dot grid that twinkles, brightens near the head and
    // rings outward from it on each beat.
    const val FX_DISCO = """
        precision highp float;
        varying vec2 vUv;
        uniform sampler2D uTexture;
        uniform vec2 uSize;
        uniform vec2 uHead;
        uniform float uTime;
        uniform float uBeat;
        uniform float uLevel;
        uniform float uRing;
        uniform vec3 uTint;
        float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
        void main() {
            vec3 cam = texture2D(uTexture, vUv).rgb;
            float lum = dot(cam, vec3(0.299, 0.587, 0.114));
            vec3 col = mix(cam, lum * uTint * 1.5, 0.6);
            vec2 px = vec2(vUv.x, 1.0 - vUv.y) * uSize;
            float cell = uSize.y / 30.0;
            vec2 id = floor(px / cell);
            float d = length(fract(px / cell) - 0.5);
            float r = hash(id);
            float twinkle = 0.5 + 0.5 * sin(uTime * (1.2 + 3.5 * r) + r * 40.0);
            float dist = length(px - uHead) / uSize.y;
            float ring = exp(-pow((dist - uRing) * 9.0, 2.0)) * (0.35 + uBeat);
            float near = exp(-dist * 2.2);
            float bright = 0.15 + 0.5 * twinkle * (0.45 + 0.55 * uLevel) + 0.8 * ring + 0.6 * uBeat * near;
            float dotMask = smoothstep(0.34, 0.2, d);
            vec3 dotCol = mix(uTint * 1.3, vec3(1.0, 0.9, 1.0), r * 0.6);
            gl_FragColor = vec4(col + dotCol * dotMask * bright * 0.8, 1.0);
        }
    """
}
