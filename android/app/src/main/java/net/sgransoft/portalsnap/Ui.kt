package net.sgransoft.portalsnap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.VideoView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

fun Context.smallButton(text: String, bg: Int) = actionButton(text, bg).apply {
    textSize = 18f
    setPadding(dp(22), dp(12), dp(22), dp(12))
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

class ReviewPanel(
    ctx: Context,
    onKeep: () -> Unit,
    onAgain: () -> Unit,
    /** Whether a clip is on screen with its own sound, so the app can hush its own. */
    private val onPlaying: (Boolean) -> Unit,
) : LinearLayout(ctx) {
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
        onPlaying(false)
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
        onPlaying(true)
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

    fun saved(text: String) {
        keep.text = "Saved ✓"
        say(text, Palette.OK)
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

    // The VideoView is hidden itself, not just via this panel: on Android 9 a SurfaceView
    // ignores an ancestor going GONE, so its video surface stayed composited over the camera
    // after "Keep it" — the "stuck preview".
    fun close() {
        video.stopPlayback()
        onPlaying(false)
        video.visibility = GONE
        image.setImageDrawable(null)
        visibility = GONE
    }
}

/* -------------------------------- Album --------------------------------- */

/** The photos and clips kept on this Portal, with a viewer and two-tap delete. */
class AlbumPanel(
    ctx: Context,
    private val captures: Captures,
    private val exec: ExecutorService,
    onBack: () -> Unit,
    /** Whether a clip is on screen with its own sound, so the app can hush its own. */
    private val onPlaying: (Boolean) -> Unit,
) : FrameLayout(ctx) {
    private val ui = Handler(Looper.getMainLooper())
    private val grid = GridView(ctx)
    private val note = ctx.label("", 20f, Palette.DIM).apply { gravity = Gravity.CENTER }
    private val help = ctx.label("", 16f, Palette.DIM).apply { gravity = Gravity.CENTER }
    private val viewer = LinearLayout(ctx)
    private val vImage = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val vVideo = VideoView(ctx)
    private val vDelete = ctx.actionButton("Delete", Palette.DANGER)
    private val vPrev = ctx.navArrow("◀")
    private val vNext = ctx.navArrow("▶")
    private var viewing: Captures.Item? = null
    private var items: List<Captures.Item> = emptyList()
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
        col.addView(panelHeader(ctx, "Photos", onBack))
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
                // Last in, so they sit over the picture rather than under it.
                addView(vPrev, lp(WRAP, WRAP, Gravity.LEFT or Gravity.CENTER_VERTICAL).apply { leftMargin = ctx.dp(12) })
                addView(vNext, lp(WRAP, WRAP, Gravity.RIGHT or Gravity.CENTER_VERTICAL).apply { rightMargin = ctx.dp(12) })
            }
            addView(box, LinearLayout.LayoutParams((dm.widthPixels * 0.84f).toInt(), (dm.heightPixels * 0.62f).toInt()))
            addView(LinearLayout(ctx).apply {
                gravity = Gravity.CENTER
                addView(ctx.actionButton("Back", Palette.ALT).apply { setOnClickListener { closeViewer() } })
                addView(vDelete, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = ctx.dp(16) })
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = ctx.dp(18) })
        }
        vDelete.setOnClickListener { deleteViewing() }
        vPrev.setOnClickListener { step(-1) }
        vNext.setOnClickListener { step(1) }
        vVideo.setOnPreparedListener { vVideo.start() }
        vVideo.visibility = GONE
        addView(viewer, lp(MATCH, MATCH))
    }

    fun open() {
        visibility = VISIBLE
        if (grid.adapter == null) grid.adapter = adapter
        refresh()
        // Anything that couldn't reach the server before gets another go.
        captures.uploadPending { ui.post { if (visibility == VISIBLE) adapter.notifyDataSetChanged() } }
    }

    fun close() {
        closeViewer()
        visibility = GONE
    }

    private fun refresh() {
        help.text = if (captures.shared) {
            "Kept on this Portal in Pictures/PortalSnap and Movies/PortalSnap."
        } else {
            "Kept inside the app, because storage permission is off."
        }
        note.text = "Looking…"
        exec.execute {
            val got = captures.list()
            ui.post {
                items = got
                adapter.notifyDataSetChanged()
                syncArrows()
                note.text = if (got.isEmpty()) "Nothing saved yet. Take a photo and tap Keep it!" else ""
            }
        }
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
            cap.text = whenLabel(it.at) + if (captures.waiting(it)) "  ·  waiting to send" else ""
            play.visibility = if (it.kind == Captures.VIDEO) VISIBLE else GONE
            val key = it.file.path
            img.tag = key
            img.setImageDrawable(null)
            val cached = thumbs.get(key)
            if (cached != null) img.setImageBitmap(cached) else loadThumb(it, img)
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

    // A clip's thumbnail is its saved poster, or a frame pulled from the file when there isn't one.
    private fun loadThumb(item: Captures.Item, into: ImageView) {
        val key = item.file.path
        exec.execute {
            val bmp = runCatching {
                if (item.kind == Captures.PHOTO) {
                    decodeFileScaled(item.file, 400)
                } else {
                    captures.poster(item)?.let { decodeFileScaled(it, 400) }
                        ?: @Suppress("DEPRECATION") ThumbnailUtils.createVideoThumbnail(item.file.path, MediaStore.Images.Thumbnails.MINI_KIND)
                }
            }.getOrNull() ?: return@execute
            thumbs.put(key, bmp)
            ui.post { if (into.tag == key) into.setImageBitmap(bmp) }
        }
    }

    private fun openViewer(it: Captures.Item) {
        viewing = it
        vDelete.text = "Delete"
        vDelete.enabledLook(true)
        viewer.visibility = VISIBLE
        syncArrows()
        // Whatever was on stops first: stepping off a clip to a photo leaves the VideoView hidden
        // but alive, and a hidden VideoView goes on playing its sound.
        vVideo.stopPlayback()
        if (it.kind == Captures.VIDEO) {
            vImage.visibility = GONE
            vVideo.visibility = VISIBLE
            vVideo.setVideoURI(Uri.fromFile(it.file))
            onPlaying(true)
        } else {
            vVideo.visibility = GONE
            onPlaying(false)
            vImage.visibility = VISIBLE
            vImage.setImageDrawable(null)
            exec.execute {
                val bmp = runCatching { decodeFileScaled(it.file, 1280) }.getOrNull()
                ui.post { if (viewing === it) vImage.setImageBitmap(bmp) }
            }
        }
    }

    // The arrows walk the same list the grid lays out — photos and clips together, newest first.
    private fun step(delta: Int) {
        val at = indexOfViewing()
        if (at < 0) return
        items.getOrNull(at + delta)?.let { openViewer(it) }
    }

    // By file, not by identity: refresh() rebuilds the list from disk behind the viewer.
    private fun indexOfViewing(): Int {
        val v = viewing ?: return -1
        return items.indexOfFirst { it.file == v.file }
    }

    // Dimmed at the two ends, and out of the way entirely when there is nowhere to step.
    private fun syncArrows() {
        val at = indexOfViewing()
        val many = items.size > 1
        for (a in arrayOf(vPrev, vNext)) a.visibility = if (many) VISIBLE else GONE
        vPrev.enabledLook(at > 0)
        vNext.enabledLook(at >= 0 && at < items.size - 1)
    }

    // Same SurfaceView rule as ReviewPanel.close(): hide the VideoView itself.
    private fun closeViewer() {
        viewing = null
        vVideo.stopPlayback()
        onPlaying(false)
        vVideo.visibility = GONE
        vImage.setImageDrawable(null)
        viewer.visibility = GONE
    }

    // Two taps: the first arms it. A mis-tap on a touchscreen shouldn't cost a photo. A copy
    // already sent to a server stays there.
    private fun deleteViewing() {
        val it = viewing ?: return
        if (vDelete.text != "Really delete?") {
            vDelete.text = "Really delete?"
            return
        }
        vDelete.enabledLook(false)
        exec.execute {
            val ok = captures.delete(it)
            ui.post {
                if (ok) {
                    thumbs.remove(it.file.path)
                    closeViewer()
                    refresh()
                } else {
                    vDelete.text = "Delete failed"
                    vDelete.enabledLook(true)
                }
            }
        }
    }

    private fun whenLabel(at: Long): String = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(at))
}

fun decodeFileScaled(file: File, maxEdge: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
}

// A chevron over the edge of the picture in the album's viewer, dark enough to read over a bright
// photo. Big padding: it is a tap target on a screen people reach for across a table.
private fun Context.navArrow(glyph: String) = label(glyph, 30f, bold = true).apply {
    background = rounded(Color.argb(170, 0, 0, 0), dp(30).toFloat(), dp(2), Palette.LINE)
    setPadding(dp(22), dp(18), dp(22), dp(18))
    gravity = Gravity.CENTER
    isClickable = true
}

// A panel's title with a "Back to camera" button on the right.
private fun panelHeader(ctx: Context, title: String, onBack: () -> Unit) = LinearLayout(ctx).apply {
    gravity = Gravity.CENTER_VERTICAL
    addView(ctx.label(title, 30f, bold = true), LinearLayout.LayoutParams(0, WRAP, 1f))
    addView(ctx.label("Back to camera", 20f, bold = true).apply {
        background = rounded(Palette.ALT, ctx.dp(14).toFloat())
        setPadding(ctx.dp(22), ctx.dp(14), ctx.dp(22), ctx.dp(14))
        setOnClickListener { onBack() }
    })
}

/* ------------------------------- Settings ------------------------------- */

/**
 * Where kept photos and clips live, the app's version, and the optional server tucked under
 * Advanced: its address and pairing, whether new captures are sent there, and sending the ones
 * that aren't there yet.
 */
class SettingsPanel(
    ctx: Context,
    private val server: Server,
    private val captures: Captures,
    private val exec: ExecutorService,
    onBack: () -> Unit,
    private val onPair: () -> Unit,
) : FrameLayout(ctx) {
    private val ui = Handler(Looper.getMainLooper())
    private val storage = ctx.label("", 19f, Palette.DIM)
    private val about = ctx.label("", 19f, Palette.DIM)
    private val advancedToggle = ctx.label("", 22f, bold = true)
    private val advanced = LinearLayout(ctx)
    private val serverState = ctx.label("", 19f, Palette.DIM)
    private val setUp = ctx.smallButton("", Palette.ACCENT)
    private val forget = ctx.smallButton("", Palette.DANGER)
    private val auto = ctx.smallButton("", Palette.ALT)
    private val sendAll = ctx.smallButton("", Palette.ALT)

    init {
        setBackgroundColor(Palette.PANEL)
        isClickable = true
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(28), ctx.dp(18), ctx.dp(28), ctx.dp(28))
        }
        col.addView(panelHeader(ctx, "Settings", onBack))
        col.addView(heading("Photos and clips"), below(20, fill = true))
        col.addView(storage, below(8, fill = true))
        col.addView(heading("About"), below(24, fill = true))
        col.addView(about, below(8, fill = true))
        advancedToggle.setPadding(0, ctx.dp(10), 0, ctx.dp(10))
        advancedToggle.setOnClickListener { showAdvanced(advanced.visibility != VISIBLE) }
        col.addView(advancedToggle, below(24, fill = true))
        advanced.apply {
            orientation = LinearLayout.VERTICAL
            addView(ctx.label(
                "A PortalSnap server is optional. It keeps a shared album your phones can open, " +
                    "and this Portal can send what it keeps there as well as keeping its own copy.",
                17f, Palette.DIM,
            ), below(0, fill = true))
            addView(heading("Server"), below(16, fill = true))
            addView(serverState, below(6, fill = true))
            addView(LinearLayout(ctx).apply {
                addView(setUp)
                addView(forget, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = ctx.dp(12) })
            }, below(12))
            addView(auto, below(12))
            addView(sendAll, below(12))
        }
        col.addView(advanced, below(4, fill = true))
        addView(ScrollView(ctx).apply { addView(col) }, lp(MATCH, MATCH))
        showAdvanced(false)

        setUp.setOnClickListener { onPair() }
        forget.setOnClickListener {
            if (forget.text != "Really forget?") {
                forget.text = "Really forget?"
                return@setOnClickListener
            }
            server.forget()
            refresh()
        }
        auto.setOnClickListener {
            captures.autoUpload = !captures.autoUpload
            refresh()
        }
        sendAll.setOnClickListener {
            sendAll.enabledLook(false)
            sendAll.text = "Sending…"
            exec.execute {
                captures.queueAll()
                captures.uploadPending { ui.post { refresh() } }
            }
        }
    }

    fun open() {
        visibility = VISIBLE
        refresh()
    }

    fun close() {
        visibility = GONE
    }

    fun refresh() {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
        about.text = "PortalSnap $version"
        serverState.text = when {
            !server.configured -> "None set up. Everything stays on this Portal."
            server.paired -> "${server.base}\nPaired ✓"
            else -> "${server.base}\nNot paired yet"
        }
        setUp.text = if (server.configured) "Pair or change server" else "Set up a server"
        forget.text = "Forget server"
        forget.visibility = if (server.configured) VISIBLE else GONE
        auto.text = "Send new photos and clips there: " + if (captures.autoUpload) "On" else "Off"
        auto.visibility = if (server.configured) VISIBLE else GONE
        exec.execute {
            val items = captures.list()
            val photos = items.count { it.kind == Captures.PHOTO }
            val clips = items.size - photos
            val unsent = captures.notSent(items)
            ui.post {
                storage.text = if (captures.shared) {
                    "${count(photos, "photo")} in Pictures/PortalSnap and ${count(clips, "clip")} in Movies/PortalSnap, on this " +
                        "Portal. They stay even if the app is removed. To copy them to a computer:\n" +
                        "adb pull /sdcard/Pictures/PortalSnap\nadb pull /sdcard/Movies/PortalSnap"
                } else {
                    "${count(photos, "photo")} and ${count(clips, "clip")}, kept inside the app because storage permission is " +
                        "off. They'd be deleted if the app were removed."
                }
                sendAll.text = "Send the ${count(unsent, "capture")} not there yet"
                sendAll.visibility = if (server.configured && server.paired && unsent > 0) VISIBLE else GONE
                sendAll.enabledLook(true)
            }
        }
    }

    private fun showAdvanced(on: Boolean) {
        advanced.visibility = if (on) VISIBLE else GONE
        advancedToggle.text = if (on) "Advanced  ▾" else "Advanced  ▸"
    }

    private fun heading(text: String) = context.label(text, 22f, bold = true)

    private fun below(dp: Int, fill: Boolean = false) =
        LinearLayout.LayoutParams(if (fill) MATCH else WRAP, WRAP).apply { topMargin = context.dp(dp) }

    private fun count(n: Int, what: String) = "$n $what" + if (n == 1) "" else "s"
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
            addView(ctx.smallButton("Connect", Palette.ACCENT).apply { setOnClickListener { connect() } },
                LayoutParams(WRAP, WRAP).apply { leftMargin = ctx.dp(12) })
            addView(ctx.smallButton("Not now", Palette.ALT).apply { setOnClickListener { close() } },
                LayoutParams(WRAP, WRAP).apply { leftMargin = ctx.dp(12) })
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
