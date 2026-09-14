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

    /** Adds a depth buffer, for 3D passes drawn into this framebuffer. */
    fun attachDepth() {
        val ids = IntArray(1)
        GLES20.glGenRenderbuffers(1, ids, 0)
        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, ids[0])
        GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GLES30.GL_DEPTH_COMPONENT24, w, h)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT, GLES20.GL_RENDERBUFFER, ids[0])
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, 0)
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
        uniform float uBulge;
        uniform mat3 uMap;
        uniform float uShape;
        uniform vec4 uTint;
        uniform float uBlur;
        uniform float uWave;
        uniform float uPhase;
        uniform mat3 uLocal;
        uniform float uOpacity;
        uniform float uSurface;
        vec4 frameAt(vec2 s) {
            return texture2D(uTexture, vec2(s.x / uSize.x, 1.0 - s.y / uSize.y));
        }
        void main() {
            vec2 p = vec2(gl_FragCoord.x, uSize.y - gl_FragCoord.y);
            vec2 d = p - uCentre;
            float c = cos(uAngle), s = sin(uAngle);
            vec2 q = vec2(c * d.x + s * d.y, -s * d.x + c * d.y) / uRadii;
            float r = length(q);
            if (uBulge > 0.0) {
                // A lens that eases to no change at the rim, so it needs no feathered edge
                // and leaves no ghost ring. Monotonic for any bulge below 1: nothing folds.
                if (r >= 1.0) discard;
                float k = 1.0 - r * r;
                vec2 lens = uCentre + d * (1.0 - uBulge * k * k);
                gl_FragColor = texture2D(uTexture, vec2(lens.x / uSize.x, 1.0 - lens.y / uSize.y));
                return;
            }
            float a;
            vec3 src;
            if (uShape > 0.5) {
                // The glass's own plane. uLocal takes a frame pixel into glass units, projectively
                // so the glass can turn in 3D; the mask and the lens live there, and uMap takes
                // glass units on to the camera.
                vec3 l = uLocal * vec3(p, 1.0);
                vec2 g = l.xy / l.z;
                // A tumbler: full width at the top, 0.85 of it at the bottom, with rounded bottom
                // corners (0.38 across, 0.26 up; Lemonade.kt draws the same shape), soft-edged.
                float halfW = mix(1.0, 0.85, (g.y + 1.0) * 0.5);
                float soft = 1.0 - uFeather;
                // The top follows the front edge of the liquid's surface, an ellipse seen from a
                // little above (uSurface is its depth in half-heights), so the face never shows
                // above the lemonade.
                float top = -1.0 + uSurface * sqrt(max(0.0, 1.0 - g.x * g.x));
                a = smoothstep(0.0, soft, halfW - abs(g.x)) * smoothstep(0.0, soft, g.y - top) * smoothstep(0.0, soft, 1.0 - g.y);
                vec2 corner = vec2((abs(g.x) - (halfW - 0.38)) / 0.38, (g.y - 0.74) / 0.26);
                if (corner.x > 0.0 && corner.y > 0.0) a *= 1.0 - smoothstep(1.0 - soft * 3.0, 1.0, length(corner));
                // The face fills the glass: the features sit in the middle, magnified, and the
                // head stretches out to every edge (the slope of sin reaches 0 there), so the edges
                // are smeared skin rather than the room behind.
                g = sin(clamp(g, -1.0, 1.0) * 1.5707963);
                src = uMap * vec3(g, 1.0);
            } else {
                a = 1.0 - smoothstep(uFeather, 1.0, r);
                src = uMap * vec3(p, 1.0);
            }
            if (a <= 0.0) discard;
            // Seen through liquid: a slow ripple, a soft blur, and a colour cast.
            if (uWave > 0.0) src.x += sin(src.y * 0.045 + uPhase) * uWave;
            vec4 col;
            if (uBlur > 0.0) {
                vec2 o = vec2(uBlur, 0.0);
                vec2 v = vec2(0.0, uBlur);
                vec2 d1 = vec2(uBlur * 0.7, uBlur * 0.7);
                vec2 d2 = vec2(uBlur * 0.7, -uBlur * 0.7);
                col = frameAt(src.xy) * 0.2
                    + (frameAt(src.xy + o) + frameAt(src.xy - o) + frameAt(src.xy + v) + frameAt(src.xy - v)) * 0.125
                    + (frameAt(src.xy + d1) + frameAt(src.xy - d1) + frameAt(src.xy + d2) + frameAt(src.xy - d2)) * 0.075;
            } else {
                col = frameAt(src.xy);
            }
            if (uTint.a > 0.0) {
                // Muted first, as through cloudy lemonade, then the colour cast.
                float lum = dot(col.rgb, vec3(0.299, 0.587, 0.114));
                col.rgb = mix(col.rgb, vec3(lum), 0.55 * uTint.a);
                col.rgb = mix(col.rgb, col.rgb * uTint.rgb + uTint.rgb * 0.22, uTint.a);
            }
            gl_FragColor = col * (a * uOpacity);
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
            vec3 here = texture2D(uTexture, vUv).rgb;
            float a = 0.0;
            float wsum = 0.0;
            // The mask's neighbourhood, weighted toward camera pixels coloured like this one, so
            // the low-resolution soft edge snaps to the real edge between person and scene.
            for (int i = -1; i <= 1; i++) {
                for (int j = -1; j <= 1; j++) {
                    vec2 o = vec2(float(i), float(j)) * uTexel;
                    vec3 c = texture2D(uTexture, vUv + vec2(o.x, -o.y)).rgb - here;
                    float w = exp(-dot(c, c) * 60.0) * ((i == 0 && j == 0) ? 2.0 : 1.0);
                    a += w * texture2D(uMask, m + o).a;
                    wsum += w;
                }
            }
            a = smoothstep(0.45, 0.75, a / wsum);
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
    // Pop Art (PopArt.kt): a person look over a grunge ground, chosen per beat. The mask is the
    // segmenter's (1 = person), the echoes are the same mask 90ms apart, newest first. Grounds and
    // grain are procedural. Frame pixel p has y down; uv is the mask's (and the frame's) 0..1.
    const val FX_POP_ART = """
        precision highp float;
        varying vec2 vUv;
        uniform sampler2D uTexture;
        uniform sampler2D uMask;
        uniform sampler2D uEcho0;
        uniform sampler2D uEcho1;
        uniform sampler2D uEcho2;
        uniform vec2 uMaskTexel;
        uniform vec2 uSize;
        uniform float uLook;
        uniform float uBg;
        uniform float uTrans;
        uniform float uTransP;
        uniform float uOutline;
        uniform float uEchoOn;
        uniform vec3 uEchoColor;
        uniform vec3 uHead;
        uniform float uDissolve;
        uniform float uHalftone;
        uniform float uTime;
        uniform float uFlash;
        // The way textures on the person slide, in px per second, changed on the beat.
        uniform vec2 uDrift;

        float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }

        float noise(vec2 p) {
            vec2 i = floor(p);
            vec2 f = fract(p);
            f = f * f * (3.0 - 2.0 * f);
            return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
        }

        float fbm(vec2 p) {
            float v = 0.0;
            float a = 0.5;
            for (int i = 0; i < 4; i++) {
                v += a * noise(p);
                p = p * 2.03 + 17.0;
                a *= 0.5;
            }
            return v;
        }

        // The mask is 256x144 under a 1280x720 frame: blur across its pixels, then pull the edge
        // back to a clean line, so silhouettes aren't staircases.
        float soft(sampler2D t, vec2 uv) {
            vec2 d = uMaskTexel * 1.2;
            float a = texture2D(t, uv).a * 4.0
                + (texture2D(t, uv + vec2(d.x, 0.0)).a + texture2D(t, uv - vec2(d.x, 0.0)).a
                + texture2D(t, uv + vec2(0.0, d.y)).a + texture2D(t, uv - vec2(0.0, d.y)).a) * 2.0
                + texture2D(t, uv + d).a + texture2D(t, uv - d).a
                + texture2D(t, uv + vec2(d.x, -d.y)).a + texture2D(t, uv + vec2(-d.x, d.y)).a;
            return smoothstep(0.3, 0.7, a / 16.0);
        }

        // Red grunge paper: blotchy, grainy, a faint diagonal print, big ghosted shapes.
        vec3 redGround(vec2 p) {
            vec3 c = vec3(0.72, 0.15, 0.19) * (0.86 + 0.2 * fbm(p / 180.0));
            float print = smoothstep(0.55, 0.72, fbm(p / 90.0 + 7.0)) * (0.5 + 0.5 * sin((p.x + p.y) / 12.0));
            c *= 1.0 - 0.03 * print;
            c *= 1.0 - 0.12 * smoothstep(0.55, 0.75, fbm(p / 420.0 + 3.1));
            return c + (hash(floor(p / 2.0)) - 0.5) * 0.035;
        }

        vec3 darkGround(vec2 p) {
            vec3 c = vec3(0.115, 0.11, 0.125) * (0.75 + 0.5 * fbm(p / 160.0));
            return c + (hash(floor(p / 2.0)) - 0.5) * 0.03;
        }

        // 0 red, 1 dark: held, or mid-transition.
        float darkness(vec2 p) {
            if (uTrans > 0.5 && uTrans < 1.5) {
                // The red breaking up: dark spreads in ragged patches, scattering fine specks
                // ahead of it, until the red is gone.
                float patchy = fbm(p / 70.0);
                float specks = hash(floor(p / 3.0));
                return step(patchy * 0.6 + specks * 0.4, uTransP * 1.1);
            }
            if (uTrans > 1.5) {
                // A torn red brush wipe sweeping across the dark, row by row.
                float row = floor(p.y / 64.0);
                float lag = hash(vec2(row, 3.0)) * 0.35;
                float torn = (fbm(vec2(p.x / 40.0, p.y / 10.0)) - 0.5) * 0.14;
                float front = uTransP * 1.45 - lag - p.x / uSize.x + torn;
                return 1.0 - step(0.0, front);
            }
            return uBg;
        }

        // The person, snapped to the picture: the mask's neighbourhood averaged with weights that
        // fall away where the camera's colour differs from this pixel's. The low-resolution soft
        // edge then lands on the real edge between person and room.
        float cutout(vec2 uv, vec3 here) {
            vec2 d = uMaskTexel * 1.6;
            float sum = 0.0;
            float wsum = 0.0;
            for (int j = -1; j <= 1; j++) {
                for (int i = -1; i <= 1; i++) {
                    vec2 o = vec2(float(i), float(j)) * d;
                    vec3 c = texture2D(uTexture, vec2(uv.x + o.x, 1.0 - uv.y - o.y)).rgb - here;
                    float w = exp(-dot(c, c) * 60.0) * ((i == 0 && j == 0) ? 2.0 : 1.0);
                    sum += w * texture2D(uMask, uv + o).a;
                    wsum += w;
                }
            }
            // A little past halfway: the model's soft edge leans outward into the room.
            return smoothstep(0.45, 0.75, sum / wsum);
        }

        void main() {
            vec2 uv = vec2(vUv.x, 1.0 - vUv.y);
            vec2 p = uv * uSize;
            vec2 px = 1.0 / uSize;
            vec3 cam = texture2D(uTexture, vUv).rgb;
            float lum = dot(cam, vec3(0.299, 0.587, 0.114));
            float m = cutout(uv, cam);
            vec2 slide = p - uDrift * uTime;
            float dk = darkness(p);
            vec3 col = mix(redGround(p), darkGround(p), dk);
            vec3 groundTone = mix(vec3(0.72, 0.15, 0.19), vec3(0.3, 0.3, 0.34), dk);

            // Motion echoes: where the person was a moment ago.
            if (uEchoOn > 0.5) {
                col = mix(col, uEchoColor * 0.8, soft(uEcho2, uv) * 0.4);
                col = mix(col, uEchoColor * 0.9, soft(uEcho1, uv) * 0.55);
                col = mix(col, uEchoColor, soft(uEcho0, uv) * 0.7);
            }

            float look = uLook;
            bool natural = look < 0.5;
            bool flatYellow = look > 0.5 && look < 1.5; // not "flat": a reserved word
            bool gradient = look > 1.5 && look < 2.5;
            bool textured = look > 2.5 && look < 3.5;
            bool ghost = look > 3.5 && look < 4.5;
            bool sheer = look > 4.5 && look < 5.5;
            bool brown = look > 5.5 && look < 6.5;
            bool sticker = look > 6.5;

            // Behind the person: drop shadows, rims, the sticker's paper border.
            if (natural || flatYellow || textured) {
                float sh = soft(uMask, uv + vec2(10.0, -10.0) * px);
                col = mix(col, vec3(0.14, 0.04, 0.05), sh * (natural ? 0.4 : 0.85));
            }
            if (gradient) col = mix(col, vec3(1.0, 0.9, 0.72), soft(uMask, uv + vec2(-7.0, 7.0) * px) * 0.85);
            if (brown) col = mix(col, vec3(0.97, 0.78, 0.1), soft(uMask, uv + vec2(-13.0, -3.0) * px));
            if (sticker) {
                vec2 o = uv + vec2(-12.0, -9.0) * px;
                float b = texture2D(uMask, o).a;
                b = max(b, texture2D(uMask, o + vec2(8.0, 0.0) * px).a);
                b = max(b, texture2D(uMask, o - vec2(8.0, 0.0) * px).a);
                b = max(b, texture2D(uMask, o + vec2(0.0, 8.0) * px).a);
                b = max(b, texture2D(uMask, o - vec2(0.0, 8.0) * px).a);
                b = max(b, texture2D(uMask, o + vec2(6.0, 6.0) * px).a);
                b = max(b, texture2D(uMask, o - vec2(6.0, 6.0) * px).a);
                b = max(b, texture2D(uMask, o + vec2(6.0, -6.0) * px).a);
                b = max(b, texture2D(uMask, o - vec2(6.0, -6.0) * px).a);
                col = mix(col, vec3(0.62, 0.62, 0.64) * (0.92 + 0.16 * hash(floor(p / 3.0))), b);
            }

            vec3 person = cam;
            if (flatYellow) person = vec3(0.95, 0.75, 0.16);
            if (gradient) person = mix(vec3(0.98, 0.8, 0.28), vec3(0.91, 0.45, 0.16), clamp(uv.y * 1.2 + 0.05, 0.0, 1.0));
            // Textures on the person slide one way (uDrift), as in the original.
            if (textured) person = mix(vec3(0.4, 0.21, 0.08), vec3(0.85, 0.53, 0.18), fbm(slide / 24.0)) * (0.85 + 0.25 * hash(floor(slide / 3.0)));
            // Sunk into the ground: its colour, darkened, with just the features' light and shade.
            if (ghost) person = groundTone * (0.3 + 0.6 * lum);
            if (sheer) person = vec3(0.97, 0.78, 0.2) * (0.7 + 0.45 * lum);
            if (ghost || sheer || gradient) person *= 0.86 + 0.28 * fbm(slide / 18.0);
            if (brown) person = vec3(0.29, 0.15, 0.09);

            if (uHead.z > 0.0) {
                float inHead = 1.0 - smoothstep(uHead.z * 0.8, uHead.z, length(p - uHead.xy));
                if (uDissolve > 0.5) {
                    vec2 cell = floor(slide / 3.0);
                    float speck = step(0.6, hash(cell + floor(uTime * 12.0)));
                    vec3 glitter = mix(person * 0.5, vec3(1.0, 0.96, 0.88), hash(cell + 7.0));
                    person = mix(person, glitter, speck * inHead * 0.8);
                }
                if (uHalftone > 0.5) {
                    vec2 cell = mod(slide, 10.0) - 5.0;
                    float r = 3.8 * (0.5 + 0.5 * fbm(p / 30.0));
                    float dotted = step(length(cell), r) * inHead * step(p.y, uHead.y);
                    person = mix(person, vec3(0.96, 0.8, 0.15), dotted);
                }
            }

            if (uOutline > 0.5) {
                float d1 = max(max(texture2D(uMask, uv + vec2(3.0, 0.0) * px).a, texture2D(uMask, uv - vec2(3.0, 0.0) * px).a),
                    max(texture2D(uMask, uv + vec2(0.0, 3.0) * px).a, texture2D(uMask, uv - vec2(0.0, 3.0) * px).a));
                // Thresholded like the cut-out, so the outline is a line, not the raw mask's glow.
                d1 = smoothstep(0.45, 0.75, d1);
                float band = clamp(d1 - m, 0.0, 1.0);
                if (uOutline < 1.5) {
                    // Fire: a hot band, with flames licking up off it.
                    float d2 = max(max(texture2D(uMask, uv + vec2(14.0, 0.0) * px).a, texture2D(uMask, uv - vec2(14.0, 0.0) * px).a),
                        texture2D(uMask, uv + vec2(0.0, 16.0) * px).a);
                    float lick = fbm(vec2(p.x / 12.0, p.y / 12.0 + uTime * 3.0));
                    float heat = clamp(max(band, (d2 - m) * lick * 1.4), 0.0, 1.0);
                    col = mix(col, mix(vec3(1.0, 0.4, 0.04), vec3(1.0, 0.92, 0.4), lick), heat);
                } else {
                    col = mix(col, vec3(0.25, 0.82, 1.0), band);
                }
            }

            col = mix(col, person, m);
            gl_FragColor = vec4(col + uFlash, 1.0);
        }
    """

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
