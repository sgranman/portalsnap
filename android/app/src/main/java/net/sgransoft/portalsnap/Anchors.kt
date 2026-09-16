package net.sgransoft.portalsnap

import com.google.mediapipe.tasks.vision.facedetector.FaceDetectorResult
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.hypot

/** The three tracker tiers, and the frame size each is fed (the web app's measured sizes). */
enum class Mode(val inputW: Int, val inputH: Int) {
    FAST(320, 180),
    MESH(320, 180),
    SEGMENT(256, 144),
}

class Pt(val x: Float, val y: Float)

/** One face in normalized image coordinates: 0..1, y down, unmirrored. */
class FaceAnchors(
    val points: Map<String, Pt>,
    val blendshapes: Map<String, Float>,
    val dense: Boolean,
    val pitch: Float? = null,
    /** Head turn in degrees about the frame's y axis, as View3D turns things; mesh tier only. */
    val turn: Float? = null,
)

/**
 * Port of public/anchors.js. The indices are copied rather than re-derived: they were
 * checked against canonical_face_model.obj there, and a wrong one is a sticker on the
 * wrong part of a face.
 */
object Anchors {
    val KP = linkedMapOf("eyeR" to 0, "eyeL" to 1, "nose" to 2, "mouth" to 3, "earR" to 4, "earL" to 5)

    val MESH = linkedMapOf("eyeR" to 33, "eyeL" to 263, "nose" to 1, "mouth" to 13, "earR" to 234, "earL" to 454)

    val MESH_EXTRA = linkedMapOf(
        "headTop" to 10, "skullR" to 103, "skullL" to 332, "templeR" to 127, "templeL" to 356,
        "browR" to 105, "browL" to 334, "chin" to 152, "jawR" to 172, "jawL" to 397,
        "noseUnder" to 2, "nostrilR" to 98, "nostrilL" to 327, "lipBottom" to 14,
        "mouthR" to 61, "mouthL" to 291,
        // Inner eye corners, for the Hamster's eye lenses (the outer corners are eyeR/eyeL).
        "eyeInR" to 133, "eyeInL" to 362,
    )

    /** How many faces each tier follows at once; see FACE_CAP in anchors.js. */
    fun faceCap(m: Mode) = when (m) {
        Mode.FAST -> 3
        Mode.MESH -> 2
        Mode.SEGMENT -> 0
    }

    fun eyeSpan(a: FaceAnchors): Float {
        val r = a.points.getValue("eyeR")
        val l = a.points.getValue("eyeL")
        return hypot(l.x - r.x, l.y - r.y)
    }

    fun fromDetections(res: FaceDetectorResult, max: Int): List<FaceAnchors> {
        val out = ArrayList<FaceAnchors>()
        for (d in res.detections()) {
            val kp = d.keypoints().orElse(null) ?: continue
            if (kp.size < 6) continue
            out += FaceAnchors(KP.mapValues { (_, i) -> Pt(kp[i].x(), kp[i].y()) }, emptyMap(), false)
        }
        return capped(out, max)
    }

    fun fromLandmarks(res: FaceLandmarkerResult, max: Int): List<FaceAnchors> {
        val out = ArrayList<FaceAnchors>()
        val shapes = res.faceBlendshapes().orElse(null)
        val poses = res.facialTransformationMatrixes().orElse(null)
        res.faceLandmarks().forEachIndexed { i, lm ->
            if (MESH.values.any { it >= lm.size }) return@forEachIndexed
            val pts = LinkedHashMap<String, Pt>()
            for ((k, idx) in MESH) pts[k] = Pt(lm[idx].x(), lm[idx].y())
            for ((k, idx) in MESH_EXTRA) if (idx < lm.size) pts[k] = Pt(lm[idx].x(), lm[idx].y())
            // Per face and parallel to the landmark list: face 1 reads index 1.
            val bs = shapes?.getOrNull(i)?.associate { it.categoryName() to it.score() } ?: emptyMap()
            // Forward-vector y of the head pose; layout-agnostic up to sign, which Nod ignores.
            val pose = poses?.getOrNull(i)
            val pitch = pose?.let { m -> Math.toDegrees(asin(m[9].coerceIn(-1f, 1f).toDouble())).toFloat() }
            // Column-major (checked on a Portal: the translation is m12..m14, and m12 grows as the
            // face moves right). The face's forward vector is its third column, in a camera space with y up
            // and z toward the viewer. View3D has y down and z away, so (m8, m9, m10) is (m8, -m9,
            // -m10) there, and a turn of a about y takes (0, 0, -1) to (-sin a, 0, -cos a).
            val turn = pose?.let { m -> Math.toDegrees(atan2(-m[8], m[10]).toDouble()).toFloat() }
            out += FaceAnchors(pts, bs, true, pitch, turn)
        }
        return capped(out, max)
    }

    // Biggest first when more arrive than the tier carries: the child leaning in keeps
    // their ears when someone walks through the back of the shot.
    private fun capped(out: List<FaceAnchors>, max: Int): List<FaceAnchors> {
        val cap = maxOf(1, max)
        return if (out.size > cap) out.sortedByDescending { eyeSpan(it) }.take(cap) else out
    }
}
