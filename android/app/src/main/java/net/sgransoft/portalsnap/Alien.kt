package net.sgransoft.portalsnap

import android.opengl.GLES20
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// The alien: a swollen cranium, huge eyes, a chin pinched to a point, green skin and a robot
// voice. The look is one warp of the camera picture, not stickers, so it is still the person's
// own face, blinking and talking. Three smooth maps are chained in face space, each easing to
// no change at its edge so nothing needs a feathered seam:
//   - the jaw: the lower face squeezed toward the midline and lifted, tapering out into the neck
//   - the cranium: a wide lens centred above the eyes, so the top of the head balloons and the
//     eyes are pushed down and apart
//   - the eyes: a lens on each, the same shape as Hamster's but much stronger
// Then the skin inside the face's oval goes green, keyed on skin colour so the eyes, lips and
// hair keep theirs.

/** One person's warp for this frame. Face space as in Filters.kt: eye corners at (±0.5, 0), y down. */
class AlienWarp(
    val cx: Float, val cy: Float, val angle: Float, val scale: Float,
    /** Cranium lens: centre y, radius x, radius y, strength (1 / (1 - strength) at its centre). */
    val head: FloatArray,
    /** Jaw: y where the squeeze starts, y where it peaks, y where it is gone, half width. */
    val jaw: FloatArray,
    val squeeze: Float,
    val lift: Float,
    /** Each eye's lens: centre x, centre y, radius x, radius y. */
    val eyeR: FloatArray,
    val eyeL: FloatArray,
    val eyeGrow: Float,
    /** The green: the face's oval (centre y, radius x, radius y) and how strong. */
    val skin: FloatArray,
    /** Frame px the warp can touch: left, top, right, bottom. Everything outside is untouched. */
    val box: FloatArray,
)

object Alien : Filter("alien", "Alien", "👽", Mode.MESH) {
    override val usesOver = false
    override val voiceFx = VoiceFx.ROBOT

    // Tuned on the test portrait. The cranium's strength is the biggest lever on how alien it
    // reads; past about 0.5 the forehead starts to smear.
    private const val HEAD_GROW = 0.45f
    private const val EYE_GROW = 0.7f
    private const val SQUEEZE = 0.9f
    private const val GREEN = 0.85f

    override fun draw(d: Draw, f: Face) {
        val top = f["headTop"]?.y ?: -0.63f
        val chin = f["chin"]?.y ?: 1.36f
        val tall = max(1f, chin - top)
        val half = f.headSpan / 2

        val headY = top * 0.5f - tall * 0.15f
        val head = floatArrayOf(headY, half * 2.3f, (chin - headY) * 0.92f, HEAD_GROW)

        val lift = tall * 0.1f
        val jawEnd = chin + tall * 0.45f
        val jaw = floatArrayOf(f.nose.y, chin - lift * 0.6f, jawEnd, half * 1.3f)

        // Each eye's middle and width from its corners, as Hamster finds them.
        fun eye(key: String, side: Float): FloatArray {
            val inner = f[key]
            val ix = inner?.x ?: (side * 0.18f)
            val iy = inner?.y ?: 0f
            val outer = side * 0.5f
            val rx = abs(outer - ix) * 1.6f
            return floatArrayOf((outer + ix) / 2, iy / 2, rx, rx * 0.85f)
        }
        val eyeR = eye("eyeInR", -1f)
        val eyeL = eye("eyeInL", 1f)

        // Wide enough for the ears and tall enough for the whole forehead; the skin key keeps it off
        // the hair and the room.
        val skin = floatArrayOf(top * 0.5f + chin * 0.5f - tall * 0.12f, half * 1.4f, tall * 0.78f, GREEN)

        // The box around everything above, turned into frame px.
        val reachX = max(head[1], jaw[3]) + 0.05f
        val reachTop = min(headY - head[2], top - tall * 0.2f) - 0.05f
        val reachBottom = jawEnd + 0.05f
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        for (x in floatArrayOf(-reachX, reachX)) {
            for (y in floatArrayOf(reachTop, reachBottom)) {
                val p = toPixels(f, x, y)
                l = min(l, p.x)
                t = min(t, p.y)
                r = max(r, p.x)
                b = max(b, p.y)
            }
        }

        d.aliens += AlienWarp(
            f.cx, f.cy, f.angle, f.eyeDist, head, jaw, SQUEEZE, lift, eyeR, eyeL, EYE_GROW, skin,
            floatArrayOf(l, t, r, b),
        )
    }
}

class AlienRenderer {
    private val program = Program(Shaders.VERTEX, SHADER)

    /** Draws each warp into the bound frame-sized target, reading the camera picture from [tex]. */
    fun draw(list: List<AlienWarp>, tex: Int) {
        val p = program
        p.use()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glUniform1i(p.u("uTexture"), 0)
        GLES20.glUniform2f(p.u("uSize"), FRAME_W.toFloat(), FRAME_H.toFloat())
        // Only the pixels near the head run the shader; the rest of the frame is the camera already.
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        try {
            for (w in list) {
                val x0 = w.box[0].toInt().coerceIn(0, FRAME_W)
                val x1 = (w.box[2].toInt() + 1).coerceIn(0, FRAME_W)
                val y0 = w.box[1].toInt().coerceIn(0, FRAME_H)
                val y1 = (w.box[3].toInt() + 1).coerceIn(0, FRAME_H)
                if (x1 <= x0 || y1 <= y0) continue
                GLES20.glScissor(x0, FRAME_H - y1, x1 - x0, y1 - y0)
                GLES20.glUniform2f(p.u("uCentre"), w.cx, w.cy)
                GLES20.glUniform2f(p.u("uAxis"), cos(w.angle), sin(w.angle))
                GLES20.glUniform1f(p.u("uScale"), w.scale)
                GLES20.glUniform4fv(p.u("uHead"), 1, w.head, 0)
                GLES20.glUniform4fv(p.u("uJaw"), 1, w.jaw, 0)
                GLES20.glUniform2f(p.u("uJawMove"), w.squeeze, w.lift)
                GLES20.glUniform4fv(p.u("uEyeR"), 1, w.eyeR, 0)
                GLES20.glUniform4fv(p.u("uEyeL"), 1, w.eyeL, 0)
                GLES20.glUniform1f(p.u("uEyeGrow"), w.eyeGrow)
                GLES20.glUniform4fv(p.u("uSkin"), 1, w.skin, 0)
                p.drawQuad()
            }
        } finally {
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        }
    }

    private companion object {
        const val SHADER = """
            precision highp float;
            uniform sampler2D uTexture;
            uniform vec2 uSize;
            uniform vec2 uCentre;
            uniform vec2 uAxis;
            uniform float uScale;
            uniform vec4 uHead;
            uniform vec4 uJaw;
            uniform vec2 uJawMove;
            uniform vec4 uEyeR;
            uniform vec4 uEyeL;
            uniform float uEyeGrow;
            uniform vec4 uSkin;

            // Magnifies in place, 1 / (1 - b) at the centre, easing to no change at the rim.
            // Monotonic for any b below 1, so nothing folds.
            vec2 lens(vec2 u, vec2 c, vec2 r, float b) {
                vec2 d = u - c;
                vec2 n = d / r;
                float q = dot(n, n);
                if (q >= 1.0) return u;
                float k = 1.0 - q;
                return c + d * (1.0 - b * k * k);
            }

            float inEllipse(vec2 u, vec2 c, vec2 r) {
                vec2 n = (u - c) / r;
                return dot(n, n);
            }

            void main() {
                vec2 p = vec2(gl_FragCoord.x, uSize.y - gl_FragCoord.y);
                vec2 d = p - uCentre;
                vec2 u = vec2(d.x * uAxis.x + d.y * uAxis.y, -d.x * uAxis.y + d.y * uAxis.x) / uScale;

                // The jaw. Across, x(1 + s h(x)) with h = (1 - x^2/w^2)^2 stays monotonic for s up to
                // about 1. Down, the lift is a bump whose slope stays well under 1, so rows never cross.
                float wy = smoothstep(uJaw.x, uJaw.y, u.y) * (1.0 - smoothstep(uJaw.y, uJaw.z, u.y));
                float h = max(0.0, 1.0 - u.x * u.x / (uJaw.w * uJaw.w));
                h *= h;
                vec2 s = vec2(u.x * (1.0 + uJawMove.x * wy * h), u.y + uJawMove.y * wy * h);

                s = lens(s, vec2(0.0, uHead.x), uHead.yz, uHead.w);
                s = lens(s, uEyeR.xy, uEyeR.zw, uEyeGrow);
                s = lens(s, uEyeL.xy, uEyeL.zw, uEyeGrow);

                vec2 src = uCentre + vec2(s.x * uAxis.x - s.y * uAxis.y, s.x * uAxis.y + s.y * uAxis.x) * uScale;
                vec4 col = texture2D(uTexture, vec2(src.x / uSize.x, 1.0 - src.y / uSize.y));

                // Green where it is skin inside the face's oval, and not in the eyes. Skin is a soft
                // box in chroma (YCbCr, centred on 0), which holds across skin tones better than hue.
                float oval = 1.0 - smoothstep(0.45, 1.0, inEllipse(s, vec2(0.0, uSkin.x), uSkin.yz));
                float eyes = smoothstep(0.0, 0.12, min(inEllipse(s, uEyeR.xy, uEyeR.zw), inEllipse(s, uEyeL.xy, uEyeL.zw)));
                float lum = dot(col.rgb, vec3(0.299, 0.587, 0.114));
                float cb = dot(col.rgb, vec3(-0.169, -0.331, 0.5));
                float cr = dot(col.rgb, vec3(0.5, -0.419, -0.081));
                float skin = smoothstep(-0.01, 0.03, cr) * (1.0 - smoothstep(0.17, 0.23, cr))
                    * smoothstep(-0.25, -0.18, cb) * (1.0 - smoothstep(0.0, 0.04, cb))
                    * smoothstep(0.06, 0.16, lum);
                float g = uSkin.w * oval * eyes * skin;
                if (g < 0.002 && distance(s, u) < 0.0005) discard;
                // A pale, slightly grey green that keeps the skin's own shading, stretched a little so it
                // doesn't read as a flat coat of paint.
                float shade = clamp((lum - 0.45) * 1.25 + 0.5, 0.0, 1.0);
                vec3 alien = mix(vec3(0.10, 0.20, 0.08), vec3(0.72, 0.95, 0.62), shade);
                gl_FragColor = vec4(mix(col.rgb, alien, g), 1.0);
            }
        """
    }
}
