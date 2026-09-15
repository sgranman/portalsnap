package net.sgran.portalsnap

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// Coloured geometry for scenes made of many small solid parts: Bike Ride's park, bikes and riders.
// Every vertex carries its own colour, so a whole stretch of forest is one draw call.

/**
 * Triangles with a colour on every vertex (position, normal, rgb and glow: [STRIDE] floats), built
 * straight into world space. Transforms must be rigid or evenly scaled, since normals go through
 * them as plain directions. Winding isn't kept: the shaders light whichever side faces the eye.
 */
class ColorGeo(verts: Int = 4096, tris: Int = 8192) {
    var data = FloatArray(STRIDE * verts)
        private set
    var verts = 0
        private set
    var indices = IntArray(3 * tris)
        private set
    var count = 0
        private set

    private var r = 1f
    private var g = 1f
    private var b = 1f
    private var glow = 0f

    private val pts = FloatArray(3 * 256)
    private val tan = FloatArray(3)
    private val nrm = floatArrayOf(1f, 0f, 0f)
    private val bin = FloatArray(3)

    fun reset() {
        verts = 0
        count = 0
    }

    /** The colour for the parts that follow, as 0xRRGGBB. [glow] 1 ignores the lighting. */
    fun color(rgb: Int, glow: Float = 0f): ColorGeo {
        r = ((rgb shr 16) and 255) / 255f
        g = ((rgb shr 8) and 255) / 255f
        b = (rgb and 255) / 255f
        this.glow = glow
        return this
    }

    private fun room(nv: Int, ni: Int) {
        if ((verts + nv) * STRIDE > data.size) data = data.copyOf(max(data.size * 2, (verts + nv) * STRIDE))
        if (count + ni > indices.size) indices = indices.copyOf(max(indices.size * 2, count + ni))
    }

    /** A vertex already in world space. */
    fun put(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float): Int {
        room(1, 0)
        val o = verts * STRIDE
        val len = max(1e-6f, sqrt(nx * nx + ny * ny + nz * nz))
        data[o] = x
        data[o + 1] = y
        data[o + 2] = z
        data[o + 3] = nx / len
        data[o + 4] = ny / len
        data[o + 5] = nz / len
        data[o + 6] = r
        data[o + 7] = g
        data[o + 8] = b
        data[o + 9] = glow
        return verts++
    }

    /** A vertex at local (x, y, z) with local normal (nx, ny, nz), through the column-major [m]. */
    fun vertex(m: FloatArray, x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float): Int = put(
        m[0] * x + m[4] * y + m[8] * z + m[12],
        m[1] * x + m[5] * y + m[9] * z + m[13],
        m[2] * x + m[6] * y + m[10] * z + m[14],
        m[0] * nx + m[4] * ny + m[8] * nz,
        m[1] * nx + m[5] * ny + m[9] * nz,
        m[2] * nx + m[6] * ny + m[10] * nz,
    )

    fun tri(a: Int, b: Int, c: Int) {
        room(0, 3)
        indices[count++] = a
        indices[count++] = b
        indices[count++] = c
    }

    // Quads between (rows + 1) x (cols + 1) vertices laid out row by row from [base].
    private fun grid(base: Int, rows: Int, cols: Int) {
        room(0, rows * cols * 6)
        for (i in 0 until rows) {
            for (j in 0 until cols) {
                val a = base + i * (cols + 1) + j
                val c = a + cols + 2
                tri(a, a + 1, c)
                tri(a, c, a + cols + 1)
            }
        }
    }

    /** An ellipsoid centred on local (cx, cy, cz). [top] stretches its upper half into an egg. */
    fun ellipsoid(
        m: FloatArray, cx: Float, cy: Float, cz: Float, rx: Float, ry: Float, rz: Float,
        segs: Int = 14, rings: Int = 9, top: Float = 1f,
    ) {
        room((rings + 1) * (segs + 1), rings * segs * 6)
        val base = verts
        for (i in 0..rings) {
            val phi = PI.toFloat() * i / rings
            val sy = cos(phi)
            val sr = sin(phi)
            val ryy = if (sy > 0f) ry * top else ry
            for (j in 0..segs) {
                val th = TAU * j / segs
                val ux = sr * cos(th)
                val uz = sr * sin(th)
                vertex(m, cx + ux * rx, cy + sy * ryy, cz + uz * rz, ux / rx, sy / ryy, uz / rz)
            }
        }
        grid(base, rings, segs)
    }

    /** A cone of flat facets standing on local (cx, cy, cz), with a flat underside coloured [under]. */
    fun cone(m: FloatArray, cx: Float, cy: Float, cz: Float, radius: Float, height: Float, sides: Int, under: Int, spin: Float = 0f) {
        room(sides * 4 + 1, sides * 6)
        val slope = radius / height
        for (k in 0 until sides) {
            val a0 = spin + TAU * k / sides
            val a1 = spin + TAU * (k + 1) / sides
            val am = (a0 + a1) / 2
            val nx = cos(am)
            val nz = sin(am)
            val a = vertex(m, cx + cos(a0) * radius, cy, cz + sin(a0) * radius, nx, slope, nz)
            val b = vertex(m, cx + cos(a1) * radius, cy, cz + sin(a1) * radius, nx, slope, nz)
            val t = vertex(m, cx, cy + height, cz, nx, slope, nz)
            tri(a, b, t)
        }
        val sr = r
        val sg = g
        val sb = b
        val sgl = glow
        color(under)
        val c = vertex(m, cx, cy, cz, 0f, -1f, 0f)
        val first = verts
        for (k in 0 until sides) {
            val a = spin + TAU * k / sides
            vertex(m, cx + cos(a) * radius, cy, cz + sin(a) * radius, 0f, -1f, 0f)
        }
        for (k in 0 until sides) tri(c, first + k, first + (k + 1) % sides)
        r = sr
        g = sg
        b = sb
        glow = sgl
    }

    /** A smooth upright cylinder from local (cx, cy, cz), radius [r0] at the bottom to [r1] at [h]. */
    fun cylinder(m: FloatArray, cx: Float, cy: Float, cz: Float, r0: Float, r1: Float, h: Float, sides: Int, caps: Boolean = true) {
        room((sides + 1) * 2 + (sides + 1) * 2, sides * 12)
        val base = verts
        val ny = (r0 - r1) / h
        for (row in 0..1) {
            val rr = if (row == 0) r0 else r1
            for (j in 0..sides) {
                val a = TAU * j / sides
                vertex(m, cx + cos(a) * rr, cy + h * row, cz + sin(a) * rr, cos(a), ny, sin(a))
            }
        }
        grid(base, 1, sides)
        if (!caps) return
        for (row in 0..1) {
            val rr = if (row == 0) r0 else r1
            val y = cy + h * row
            val n = if (row == 0) -1f else 1f
            val c = vertex(m, cx, y, cz, 0f, n, 0f)
            val first = verts
            for (j in 0 until sides) {
                val a = TAU * j / sides
                vertex(m, cx + cos(a) * rr, y, cz + sin(a) * rr, 0f, n, 0f)
            }
            for (j in 0 until sides) tri(c, first + j, first + (j + 1) % sides)
        }
    }

    /** A box centred on local (cx, cy, cz) with half-sizes (hx, hy, hz). */
    fun box(m: FloatArray, cx: Float, cy: Float, cz: Float, hx: Float, hy: Float, hz: Float) {
        room(24, 36)
        for (axis in 0 until 3) {
            for (sign in intArrayOf(-1, 1)) {
                val n = FloatArray(3).also { it[axis] = sign.toFloat() }
                val u = FloatArray(3).also { it[(axis + 1) % 3] = 1f }
                val v = FloatArray(3).also { it[(axis + 2) % 3] = 1f }
                val base = verts
                for (k in 0 until 4) {
                    val su = if (k == 1 || k == 2) 1f else -1f
                    val sv = if (k >= 2) 1f else -1f
                    val px = n[0] + u[0] * su + v[0] * sv
                    val py = n[1] + u[1] * su + v[1] * sv
                    val pz = n[2] + u[2] * su + v[2] * sv
                    vertex(m, cx + px * hx, cy + py * hy, cz + pz * hz, n[0], n[1], n[2])
                }
                tri(base, base + 1, base + 2)
                tri(base, base + 2, base + 3)
            }
        }
    }

    /** A tyre-like ring round local x through (cx, cy, cz): [big] to the tube's middle, [small] across it. */
    fun ringX(m: FloatArray, cx: Float, cy: Float, cz: Float, big: Float, small: Float, segs: Int, sides: Int) {
        room((segs + 1) * (sides + 1), segs * sides * 6)
        val base = verts
        for (i in 0..segs) {
            val th = TAU * i / segs
            val ey = cos(th)
            val ez = sin(th)
            for (j in 0..sides) {
                val ph = TAU * j / sides
                val nx = sin(ph)
                val ny = ey * cos(ph)
                val nz = ez * cos(ph)
                vertex(m, cx + nx * small, cy + ey * big + ny * small, cz + ez * big + nz * small, nx, ny, nz)
            }
        }
        grid(base, segs, sides)
    }

    /**
     * A tube through [n] local control points (x, y, z) in [ctrl], smoothed as a Catmull-Rom curve,
     * its radius easing from [r0] to [r1]. [caps] rounds both ends.
     */
    fun tube(m: FloatArray, ctrl: FloatArray, n: Int, r0: Float, r1: Float, sides: Int = 8, sub: Int = 4, caps: Boolean = true) {
        if (n < 2) return
        var np = 0
        val lx = FloatArray(3)
        fun emit() {
            pts[np * 3] = m[0] * lx[0] + m[4] * lx[1] + m[8] * lx[2] + m[12]
            pts[np * 3 + 1] = m[1] * lx[0] + m[5] * lx[1] + m[9] * lx[2] + m[13]
            pts[np * 3 + 2] = m[2] * lx[0] + m[6] * lx[1] + m[10] * lx[2] + m[14]
            np++
        }
        for (seg in 0 until n - 1) {
            val i0 = max(seg - 1, 0) * 3
            val i1 = seg * 3
            val i2 = (seg + 1) * 3
            val i3 = min(seg + 2, n - 1) * 3
            val steps = if (n == 2) 1 else sub
            for (k in 0 until steps) {
                val t = k.toFloat() / steps
                val t2 = t * t
                val t3 = t2 * t
                for (a in 0 until 3) {
                    val p0 = ctrl[i0 + a]
                    val p1 = ctrl[i1 + a]
                    val p2 = ctrl[i2 + a]
                    val p3 = ctrl[i3 + a]
                    lx[a] = 0.5f * (2 * p1 + (p2 - p0) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (3 * p1 - p0 - 3 * p2 + p3) * t3)
                }
                emit()
            }
        }
        for (a in 0 until 3) lx[a] = ctrl[(n - 1) * 3 + a]
        emit()
        val scale = sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2])

        var first = true
        for (i in 0 until np) {
            val a = max(i - 1, 0) * 3
            val bb = min(i + 1, np - 1) * 3
            tan[0] = pts[bb] - pts[a]
            tan[1] = pts[bb + 1] - pts[a + 1]
            tan[2] = pts[bb + 2] - pts[a + 2]
            if (!normalize(tan)) continue
            // Carry the ring's frame along the curve so the tube doesn't twist.
            val d = nrm[0] * tan[0] + nrm[1] * tan[1] + nrm[2] * tan[2]
            nrm[0] -= tan[0] * d
            nrm[1] -= tan[1] * d
            nrm[2] -= tan[2] * d
            if (first || !normalize(nrm)) {
                if (abs(tan[2]) < 0.9f) {
                    nrm[0] = -tan[1]
                    nrm[1] = tan[0]
                    nrm[2] = 0f
                } else {
                    nrm[0] = 0f
                    nrm[1] = tan[2]
                    nrm[2] = -tan[1]
                }
                normalize(nrm)
            }
            bin[0] = tan[1] * nrm[2] - tan[2] * nrm[1]
            bin[1] = tan[2] * nrm[0] - tan[0] * nrm[2]
            bin[2] = tan[0] * nrm[1] - tan[1] * nrm[0]
            val rad = (r0 + (r1 - r0) * i / (np - 1)) * scale
            val o = i * 3
            if (first) {
                if (caps) {
                    ring(pts[o] - tan[0] * rad * 0.97f, pts[o + 1] - tan[1] * rad * 0.97f, pts[o + 2] - tan[2] * rad * 0.97f, rad * 0.26f, -0.97f, false, sides)
                    ring(pts[o] - tan[0] * rad * 0.7f, pts[o + 1] - tan[1] * rad * 0.7f, pts[o + 2] - tan[2] * rad * 0.7f, rad * 0.71f, -0.7f, true, sides)
                    ring(pts[o], pts[o + 1], pts[o + 2], rad, 0f, true, sides)
                } else {
                    ring(pts[o], pts[o + 1], pts[o + 2], rad, 0f, false, sides)
                }
                first = false
            } else {
                ring(pts[o], pts[o + 1], pts[o + 2], rad, 0f, true, sides)
            }
            if (i == np - 1 && caps) {
                ring(pts[o] + tan[0] * rad * 0.7f, pts[o + 1] + tan[1] * rad * 0.7f, pts[o + 2] + tan[2] * rad * 0.7f, rad * 0.71f, 0.7f, true, sides)
                ring(pts[o] + tan[0] * rad * 0.97f, pts[o + 1] + tan[1] * rad * 0.97f, pts[o + 2] + tan[2] * rad * 0.97f, rad * 0.26f, 0.97f, true, sides)
            }
        }
    }

    // A ring of vertices across the current frame; [along] tips the normals toward the tangent for
    // the caps. With [join] it's stitched to the ring before.
    private fun ring(cx: Float, cy: Float, cz: Float, rad: Float, along: Float, join: Boolean, sides: Int) {
        room(sides, sides * 6)
        val across = sqrt(max(0f, 1f - along * along))
        val base = verts
        for (s in 0 until sides) {
            val a = s * TAU / sides
            val ca = cos(a)
            val sa = sin(a)
            val dx = nrm[0] * ca + bin[0] * sa
            val dy = nrm[1] * ca + bin[1] * sa
            val dz = nrm[2] * ca + bin[2] * sa
            put(cx + dx * rad, cy + dy * rad, cz + dz * rad, dx * across + tan[0] * along, dy * across + tan[1] * along, dz * across + tan[2] * along)
        }
        if (!join) return
        val prev = base - sides
        for (s in 0 until sides) {
            val s2 = (s + 1) % sides
            tri(prev + s, prev + s2, base + s2)
            tri(prev + s, base + s2, base + s)
        }
    }

    private fun normalize(v: FloatArray): Boolean {
        val len = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        if (len < 1e-6f) return false
        v[0] /= len
        v[1] /= len
        v[2] /= len
        return true
    }

    companion object {
        const val STRIDE = 10
    }
}

private fun drawColored(p: Program, vbo: Int, ibo: Int, count: Int) {
    if (count == 0) return
    val ap = p.a("aPos")
    val an = p.a("aNormal")
    val ac = p.a("aColor")
    val stride = ColorGeo.STRIDE * 4
    GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
    GLES20.glEnableVertexAttribArray(ap)
    GLES20.glVertexAttribPointer(ap, 3, GLES20.GL_FLOAT, false, stride, 0)
    if (an >= 0) {
        GLES20.glEnableVertexAttribArray(an)
        GLES20.glVertexAttribPointer(an, 3, GLES20.GL_FLOAT, false, stride, 12)
    }
    if (ac >= 0) {
        GLES20.glEnableVertexAttribArray(ac)
        GLES20.glVertexAttribPointer(ac, 4, GLES20.GL_FLOAT, false, stride, 24)
    }
    GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo)
    GLES20.glDrawElements(GLES20.GL_TRIANGLES, count, GLES20.GL_UNSIGNED_INT, 0)
    GLES20.glDisableVertexAttribArray(ap)
    if (an >= 0) GLES20.glDisableVertexAttribArray(an)
    if (ac >= 0) GLES20.glDisableVertexAttribArray(ac)
    // The 2D passes draw from client-side arrays, which need no buffer bound.
    GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
}

/** ColorGeo uploaded once. */
class ColorMesh(geo: ColorGeo) {
    private val vbo: Int
    private val ibo: Int
    private val count = geo.count

    init {
        val ids = IntArray(2)
        GLES20.glGenBuffers(2, ids, 0)
        vbo = ids[0]
        ibo = ids[1]
        val floats = geo.verts * ColorGeo.STRIDE
        val fb = ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(geo.data, 0, floats).position(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, floats * 4, fb, GLES20.GL_STATIC_DRAW)
        val ib = ByteBuffer.allocateDirect(geo.count * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
        ib.put(geo.indices, 0, geo.count).position(0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, geo.count * 4, ib, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    fun draw(p: Program) = drawColored(p, vbo, ibo, count)
}

/** ColorGeo re-uploaded every frame. */
class DynamicColorMesh {
    private val vbo: Int
    private val ibo: Int
    private var fb: FloatBuffer = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var ib: IntBuffer = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer()
    private var count = 0

    init {
        val ids = IntArray(2)
        GLES20.glGenBuffers(2, ids, 0)
        vbo = ids[0]
        ibo = ids[1]
    }

    fun update(geo: ColorGeo) {
        val floats = geo.verts * ColorGeo.STRIDE
        if (fb.capacity() < floats) fb = ByteBuffer.allocateDirect(floats * 8).order(ByteOrder.nativeOrder()).asFloatBuffer()
        if (ib.capacity() < geo.count) ib = ByteBuffer.allocateDirect(geo.count * 8).order(ByteOrder.nativeOrder()).asIntBuffer()
        fb.clear()
        fb.put(geo.data, 0, floats).flip()
        ib.clear()
        ib.put(geo.indices, 0, geo.count).flip()
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, floats * 4, fb, GLES20.GL_DYNAMIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, geo.count * 4, ib, GLES20.GL_DYNAMIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
        count = geo.count
    }

    fun draw(p: Program) = drawColored(p, vbo, ibo, count)
}
