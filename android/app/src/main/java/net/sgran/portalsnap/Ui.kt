package net.sgran.portalsnap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.VideoView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import kotlin.math.roundToInt

// The web app's palette and type scale (44 / 22 / 13), carried over as-is.
object Palette {
    const val BG = 0xFF0E0F16.toInt()
    const val BAR = 0xFF191B26.toInt()
    const val LINE = 0xFF2B2E3D.toInt()
    const val DIM = 0xFFA2A8BD.toInt()
    const val ACCENT = 0xFF6D5BF5.toInt()
    const val HOT = 0xFFFF5C8A.toInt()
    const val OK = 0xFF4ADE80.toInt()
    const val BAD = 0xFFFF8FA8.toInt()
    const val CHIP = 0xFF262A3A.toInt()
    const val CHIP_ON = 0xFF322C62.toInt()
    const val BIG_ALT = 0xFF2B3350.toInt()
    const val ALT = 0xFF343850.toInt()
    const val DANGER = 0xFF7C2340.toInt()
    const val REC = 0xFFFF3B5C.toInt()
    const val PANEL = 0xFF0B0C13.toInt()
    const val HUD = 0xFF7DD3A0.toInt()
}

fun Context.dp(v: Number) = (v.toFloat() * resources.displayMetrics.density).roundToInt()

fun rounded(color: Int, radius: Float, strokeWidth: Int = 0, strokeColor: Int = Color.TRANSPARENT) =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
    }

fun Context.label(text: String, sp: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
    this.text = text
    textSize = sp
    setTextColor(color)
    if (bold) typeface = Typeface.DEFAULT_BOLD
}

fun Context.actionButton(text: String, bg: Int) = label(text, 22f, bold = true).apply {
    background = rounded(bg, dp(16).toFloat())
    setPadding(dp(30), dp(18), dp(30), dp(18))
    gravity = Gravity.CENTER
    isClickable = true
}

fun View.enabledLook(on: Boolean) {
    isEnabled = on
    alpha = if (on) 1f else 0.5f
}

/** Android 9's emoji font predates some of the filters' emoji (the parachute is Emoji 12). */
fun emojiOr(emoji: String, fallback: String) = if (Paint().hasGlyph(emoji)) emoji else fallback

fun lp(w: Int, h: Int, gravity: Int = Gravity.NO_GRAVITY) = FrameLayout.LayoutParams(w, h, gravity)

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/* -------------------------------- Review -------------------------------- */

class ReviewPanel(ctx: Context, onKeep: () -> Unit, onAgain: () -> Unit) : LinearLayout(ctx) {
    private val image = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val video = VideoView(ctx)
    private val keep = ctx.actionButton("Keep it", Palette.ACCENT)
    private val msg = ctx.label("", 19f, Palette.DIM).apply { gravity = Gravity.CENTER }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(Palette.PANEL)
        isClickable = true
        val dm = ctx.resources.displayMetrics
        val box = FrameLayout(ctx).apply {
            background = rounded(Color.BLACK, ctx.dp(16).toFloat(), ctx.dp(3), Color.WHITE)
            setPadding(ctx.dp(3), ctx.dp(3), ctx.dp(3), ctx.dp(3))
            addView(image, lp(MATCH, MATCH))
            addView(video, lp(MATCH, MATCH, Gravity.CENTER))
        }
        addView(box, LayoutParams((dm.widthPixels * 0.78f).toInt(), (dm.heightPixels * 0.56f).toInt()))
        val acts = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER
            addView(keep)
            addView(ctx.actionButton("Take another", Palette.ALT).apply { setOnClickListener { onAgain() } },
                LayoutParams(WRAP, WRAP).apply { leftMargin = ctx.dp(16) })
        }
        keep.setOnClickListener { onKeep() }
        addView(acts, LayoutParams(WRAP, WRAP).apply { topMargin = ctx.dp(18) })
        addView(msg, LayoutParams(MATCH, WRAP).apply { topMargin = ctx.dp(18) })
        video.setOnPreparedListener { it.isLooping = true; video.start() }
    }

    fun showPhoto(file: File) {
        video.stopPlayback()
        video.visibility = GONE
        image.visibility = VISIBLE
        image.setImageBitmap(BitmapFactory.decodeFile(file.absolutePath))
        reset()
    }

    fun showVideo(file: File) {
        image.setImageDrawable(null)
        image.visibility = GONE
        video.visibility = VISIBLE
        video.setVideoURI(Uri.fromFile(file))
        reset()
    }

    private fun reset() {
        keep.text = "Keep it"
        keep.enabledLook(true)
        say("")
        visibility = VISIBLE
    }

    fun saving() {
        keep.text = "Saving…"
        keep.enabledLook(false)
        say("")
    }

    fun saved() {
        keep.text = "Saved ✓"
        say("It's in Photos now", Palette.OK)
    }

    fun failed(why: String) {
        keep.text = "Try again"
        keep.enabledLook(true)
        say("Couldn't save it: $why", Palette.BAD)
    }

    fun say(text: String, color: Int = Palette.DIM) {
        msg.text = text
        msg.setTextColor(color)
    }

    fun close() {
        video.stopPlayback()
        image.setImageDrawable(null)
        visibility = GONE
    }
}

/* -------------------------------- Album --------------------------------- */

class AlbumPanel(
    ctx: Context,
    private val server: Server,
    private val exec: ExecutorService,
    onBack: () -> Unit,
    private val onUnpaired: () -> Unit,
) : FrameLayout(ctx) {
    private val ui = Handler(Looper.getMainLooper())
    private val grid = GridView(ctx)
    private val note = ctx.label("", 20f, Palette.DIM).apply { gravity = Gravity.CENTER }
    private val help = ctx.label("", 16f, Palette.DIM).apply { gravity = Gravity.CENTER }
    private val viewer = LinearLayout(ctx)
    private val vImage = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val vVideo = VideoView(ctx)
    private val vDelete = ctx.actionButton("Delete", Palette.DANGER)
    private var viewing: Server.Item? = null
    private var items: List<Server.Item> = emptyList()
    private val thumbs = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    init {
        setBackgroundColor(Palette.PANEL)
        isClickable = true
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(18), ctx.dp(18), ctx.dp(18), ctx.dp(18))
        }
        val top = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ctx.label("Photos", 30f, bold = true), LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(ctx.label("Back to camera", 20f, bold = true).apply {
                background = rounded(Palette.ALT, ctx.dp(14).toFloat())
                setPadding(ctx.dp(22), ctx.dp(14), ctx.dp(22), ctx.dp(14))
                setOnClickListener { onBack() }
            })
        }
        col.addView(top)
        grid.numColumns = GridView.AUTO_FIT
        grid.columnWidth = ctx.dp(200)
        grid.horizontalSpacing = ctx.dp(12)
        grid.verticalSpacing = ctx.dp(12)
        grid.stretchMode = GridView.STRETCH_COLUMN_WIDTH
        grid.setOnItemClickListener { _, _, pos, _ -> openViewer(items[pos]) }
        val body = FrameLayout(ctx).apply {
            addView(grid, lp(MATCH, MATCH))
            addView(note, lp(MATCH, WRAP, Gravity.TOP))
        }
        col.addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = ctx.dp(14) })
        col.addView(help, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ctx.dp(10) })
        addView(col, lp(MATCH, MATCH))

        viewer.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Palette.PANEL)
            isClickable = true
            visibility = GONE
            val dm = ctx.resources.displayMetrics
            val box = FrameLayout(ctx).apply {
                background = rounded(Color.BLACK, ctx.dp(16).toFloat(), ctx.dp(3), Color.WHITE)
                setPadding(ctx.dp(3), ctx.dp(3), ctx.dp(3), ctx.dp(3))
                addView(vImage, lp(MATCH, MATCH))
                addView(vVideo, lp(MATCH, MATCH, Gravity.CENTER))
            }
            addView(box, LinearLayout.LayoutParams((dm.widthPixels * 0.84f).toInt(), (dm.heightPixels * 0.62f).toInt()))
            addView(LinearLayout(ctx).apply {
                gravity = Gravity.CENTER
                addView(ctx.actionButton("Back", Palette.ALT).apply { setOnClickListener { closeViewer() } })
                addView(vDelete, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = ctx.dp(16) })
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = ctx.dp(18) })
        }
        vDelete.setOnClickListener { deleteViewing() }
        vVideo.setOnPreparedListener { vVideo.start() }
        addView(viewer, lp(MATCH, MATCH))
    }

    fun open() {
        visibility = VISIBLE
        if (grid.adapter == null) grid.adapter = adapter
        help.text = "To get these onto a phone, open ${server.base}/gallery.html there — " +
            "a new phone pairs at ${server.base}/pair."
        items = emptyList()
        adapter.notifyDataSetChanged()
        note.text = "Looking…"
        exec.execute {
            try {
                val got = server.list()
                ui.post {
                    items = got
                    adapter.notifyDataSetChanged()
                    note.text = if (got.isEmpty()) "Nothing saved yet. Take a photo and tap Keep it!" else ""
                }
            } catch (_: Server.Unpaired) {
                ui.post { onUnpaired() }
            } catch (e: Exception) {
                ui.post { note.text = "Can't reach the album right now." }
            }
        }
    }

    fun close() {
        closeViewer()
        visibility = GONE
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val it = items[position]
            val tile = (convertView as? FrameLayout) ?: tile(parent)
            val w = (parent as GridView).columnWidth.takeIf { c -> c > 0 } ?: context.dp(200)
            tile.layoutParams = ViewGroup.LayoutParams(MATCH, w * 9 / 16)
            val img = tile.getChildAt(0) as ImageView
            val play = tile.getChildAt(1)
            val cap = tile.getChildAt(2) as TextView
            cap.text = whenLabel(it.at)
            play.visibility = if (it.kind == "video") VISIBLE else GONE
            val src = if (it.kind == "video") it.poster else it.url
            img.tag = src
            img.setImageDrawable(null)
            if (src != null) {
                val cached = thumbs.get(src)
                if (cached != null) img.setImageBitmap(cached) else loadThumb(src, img)
            }
            return tile
        }
    }

    private fun tile(parent: ViewGroup): FrameLayout {
        val ctx = parent.context
        return FrameLayout(ctx).apply {
            background = rounded(0xFF1C1F2C.toInt(), ctx.dp(14).toFloat(), ctx.dp(2), Palette.LINE)
            clipToOutline = true
            addView(ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }, lp(MATCH, MATCH))
            addView(ctx.label("▶", 40f).apply {
                gravity = Gravity.CENTER
                setShadowLayer(10f, 0f, 2f, Color.argb(180, 0, 0, 0))
                setBackgroundColor(Color.argb(46, 0, 0, 0))
            }, lp(MATCH, MATCH))
            addView(ctx.label("", 13f, bold = true).apply {
                gravity = Gravity.CENTER
                setBackgroundColor(Color.argb(150, 0, 0, 0))
                setPadding(0, ctx.dp(6), 0, ctx.dp(6))
            }, lp(MATCH, WRAP, Gravity.BOTTOM))
        }
    }

    private fun loadThumb(src: String, into: ImageView) {
        exec.execute {
            val bmp = runCatching { decodeScaled(server.bytes(src), 400) }.getOrNull() ?: return@execute
            thumbs.put(src, bmp)
            ui.post { if (into.tag == src) into.setImageBitmap(bmp) }
        }
    }

    private fun openViewer(it: Server.Item) {
        viewing = it
        vDelete.text = "Delete"
        vDelete.enabledLook(true)
        viewer.visibility = VISIBLE
        if (it.kind == "video") {
            vImage.visibility = GONE
            vVideo.visibility = VISIBLE
            vVideo.setVideoURI(Uri.parse(server.absolute(it.url)), server.headers())
        } else {
            vVideo.visibility = GONE
            vImage.visibility = VISIBLE
            vImage.setImageDrawable(null)
            exec.execute {
                val bmp = runCatching { decodeScaled(server.bytes(it.url), 1280) }.getOrNull()
                ui.post { if (viewing === it) vImage.setImageBitmap(bmp) }
            }
        }
    }

    private fun closeViewer() {
        viewing = null
        vVideo.stopPlayback()
        vImage.setImageDrawable(null)
        viewer.visibility = GONE
    }

    // Two taps: the first arms it. A mis-tap on a touchscreen shouldn't cost a photo.
    private fun deleteViewing() {
        val it = viewing ?: return
        if (vDelete.text != "Really delete?") {
            vDelete.text = "Really delete?"
            return
        }
        vDelete.enabledLook(false)
        exec.execute {
            val ok = runCatching { server.delete(it.url) }.isSuccess
            ui.post {
                if (ok) {
                    closeViewer()
                    open()
                } else {
                    vDelete.text = "Delete failed"
                    vDelete.enabledLook(true)
                }
            }
        }
    }

    private fun whenLabel(iso: String): String = try {
        val p = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(p.parse(iso.take(19))!!)
    } catch (_: Exception) {
        ""
    }
}

fun decodeScaled(bytes: ByteArray, maxEdge: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}

/* --------------------------------- Pair --------------------------------- */

/**
 * The web app's /pair device grant, drawn natively: this Portal shows a QR and a code, a
 * phone that is already paired approves it, and the Portal collects its cookie.
 */
class PairPanel(
    ctx: Context,
    private val server: Server,
    private val exec: ExecutorService,
    private val onClose: () -> Unit,
) : LinearLayout(ctx) {
    private val ui = Handler(Looper.getMainLooper())
    private val qr = ImageView(ctx)
    private val code = ctx.label("", 44f, bold = true).apply {
        typeface = Typeface.MONOSPACE
        letterSpacing = 0.12f
    }
    private val how = ctx.label("", 20f, Palette.DIM)
    private val status = ctx.label("", 19f, Palette.DIM)
    private val url = EditText(ctx).apply {
        textSize = 20f
        setTextColor(Color.WHITE)
        setHintTextColor(Palette.DIM)
        hint = "https://your-portalsnap-server"
        inputType = InputType.TYPE_TEXT_VARIATION_URI
        imeOptions = EditorInfo.IME_ACTION_DONE
        isSingleLine = true
        background = rounded(Palette.CHIP, ctx.dp(12).toFloat(), ctx.dp(2), Palette.LINE)
        setPadding(ctx.dp(16), ctx.dp(12), ctx.dp(16), ctx.dp(12))
    }
    private var pairing: Server.Pairing? = null
    private var generation = 0
    private var afterPair: (() -> Unit)? = null

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(Palette.PANEL)
        isClickable = true
        setPadding(ctx.dp(24), ctx.dp(18), ctx.dp(24), ctx.dp(18))
        addView(ctx.label("Pair this Portal", 30f, bold = true), LayoutParams(WRAP, WRAP).apply { gravity = Gravity.CENTER_HORIZONTAL })
        val row = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(qr, LayoutParams(ctx.dp(300), ctx.dp(300)))
            addView(LinearLayout(ctx).apply {
                orientation = VERTICAL
                addView(code)
                addView(how, LayoutParams(ctx.dp(520), WRAP).apply { topMargin = ctx.dp(10) })
                addView(status, LayoutParams(ctx.dp(520), WRAP).apply { topMargin = ctx.dp(14) })
            }, LayoutParams(WRAP, WRAP).apply { leftMargin = ctx.dp(28) })
        }
        addView(row, LayoutParams(WRAP, WRAP).apply { topMargin = ctx.dp(14) })
        val serverRow = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(url, LayoutParams(ctx.dp(560), WRAP))
            addView(ctx.actionButton("Connect", Palette.ACCENT).apply {
                textSize = 18f
                setPadding(ctx.dp(22), ctx.dp(12), ctx.dp(22), ctx.dp(12))
                setOnClickListener { connect() }
            }, LayoutParams(WRAP, WRAP).apply { leftMargin = ctx.dp(12) })
            addView(ctx.actionButton("Not now", Palette.ALT).apply {
                textSize = 18f
                setPadding(ctx.dp(22), ctx.dp(12), ctx.dp(22), ctx.dp(12))
                setOnClickListener { close() }
            }, LayoutParams(WRAP, WRAP).apply { leftMargin = ctx.dp(12) })
        }
        addView(serverRow, LayoutParams(WRAP, WRAP).apply { topMargin = ctx.dp(18) })
        url.setOnEditorActionListener { _, _, _ -> connect(); true }
    }

    fun open(then: (() -> Unit)? = null) {
        afterPair = then
        visibility = VISIBLE
        url.setText(server.base)
        if (server.configured) start() else {
            qr.setImageDrawable(null)
            code.text = ""
            how.text = "Type the address of your PortalSnap server, then tap Connect."
            status.text = ""
        }
    }

    fun close() {
        generation++
        pairing = null
        visibility = GONE
        onClose()
    }

    private fun connect() {
        val v = url.text.toString().trim()
        if (v.isEmpty()) return
        server.base = if (v.startsWith("http")) v else "https://$v"
        start()
    }

    private fun start() {
        val gen = ++generation
        status.text = "Asking the server…"
        status.setTextColor(Palette.DIM)
        exec.execute {
            try {
                if (server.paired && server.whoami()) {
                    ui.post { if (gen == generation) paired() }
                    return@execute
                }
                val p = server.startPairing()
                val bmp = qrBitmap(p.approveUrl, context.dp(300))
                ui.post {
                    if (gen != generation) return@post
                    pairing = p
                    qr.setImageBitmap(bmp)
                    code.text = p.code
                    how.text = "On a phone that already uses PortalSnap, scan this — or open " +
                        "${server.base}/pair and type the code."
                    status.text = "Waiting for a phone to say yes…"
                    ui.postDelayed({ poll(gen) }, 2000)
                }
            } catch (e: Exception) {
                ui.post {
                    if (gen != generation) return@post
                    status.text = "Couldn't reach ${server.base}: ${e.message}"
                    status.setTextColor(Palette.BAD)
                }
            }
        }
    }

    private fun poll(gen: Int) {
        val p = pairing ?: return
        if (gen != generation) return
        exec.execute {
            val state = runCatching { server.poll(p) }.getOrDefault("pending")
            ui.post {
                if (gen != generation) return@post
                when (state) {
                    "paired" -> paired()
                    "expired" -> start()   // pairings live five minutes; draw a fresh QR
                    else -> ui.postDelayed({ poll(gen) }, 2000)
                }
            }
        }
    }

    private fun paired() {
        status.text = "Paired ✓"
        status.setTextColor(Palette.OK)
        val then = afterPair
        afterPair = null
        ui.postDelayed({
            close()
            then?.invoke()
        }, 700)
    }

    private fun qrBitmap(text: String, size: Int): Bitmap {
        val m = QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, size, size,
            mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
        )
        val px = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) px[y * size + x] = if (m[x, y]) Color.BLACK else Color.WHITE
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }
}
