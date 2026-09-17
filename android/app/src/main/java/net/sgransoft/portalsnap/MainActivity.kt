package net.sgransoft.portalsnap

import android.Manifest
import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * PortalSnap, native. The same app as public/app.html — a strip of filters, a shutter,
 * a record button and an album — rebuilt on the GPU so the tracker gets the device.
 *
 * Everything a finger can do is also reachable from adb, which is how it is tested:
 *   am start -n net.sgransoft.portalsnap/.MainActivity --ei faces 2 --es filter dog --ez hud true
 *   am start -n net.sgransoft.portalsnap/.MainActivity --es action photo
 *   am start -n net.sgransoft.portalsnap/.MainActivity --ez bench true
 * `faces` 1 or 2 swaps the camera for the test portrait (debug builds); 0 goes back.
 */
class MainActivity : Activity() {
    private lateinit var tracker: Tracker
    private lateinit var painter: Painter
    private lateinit var compositor: Compositor
    private lateinit var camera: CameraSource
    private lateinit var server: Server
    private lateinit var mic: MicHub
    @Volatile private var musicVoice = 0
    @Volatile private var musicToken = 0
    // The selected filter's own soundtrack (Filter.music).
    private var soundtrackVoice = 0
    private var ambienceName: String? = null
    private var ambienceVoice = 0
    private var soundtrackName: String? = null
    private val soundtracks = HashMap<String, Mixer.Clip>()
    private val ui = Handler(Looper.getMainLooper())
    private val exec = Executors.newFixedThreadPool(3)

    private lateinit var surface: SurfaceView
    private lateinit var hintView: TextView
    private lateinit var recBorder: View
    private lateinit var recPill: LinearLayout
    private lateinit var recClock: TextView
    private lateinit var flash: View
    private lateinit var hud: TextView
    private lateinit var loader: LinearLayout
    private lateinit var loadMsg: TextView
    private lateinit var shutter: TextView
    private lateinit var record: TextView
    private lateinit var album: TextView
    private lateinit var review: ReviewPanel
    private lateinit var albumPanel: AlbumPanel
    private lateinit var pair: PairPanel
    private lateinit var settings: SettingsPanel
    private lateinit var captures: Captures
    private lateinit var gear: TextView
    private val chips = ArrayList<Pair<Filter?, LinearLayout>>()
    private val groupChips = ArrayList<Pair<FilterGroup, LinearLayout>>()
    // The second row over the bottom of the picture, and what it's showing: Places or a FilterGroup.
    private lateinit var subBar: HorizontalScrollView
    private lateinit var subRow: LinearLayout
    private lateinit var subWrap: LinearLayout
    private lateinit var subToggle: TextView
    private var subFor: Any? = null
    // Folded away or not. Not remembered across launches: the Portal is a shared thing, and the
    // next person to walk up to it should find the places where they can see them.
    private var subOpen = true

    private var cameraTexture: SurfaceTexture? = null
    private var openedCamera = false
    private var opened: CameraSource.Opened? = null
    private var rotOverride: Int? = null
    private var testFaces = 0
    private var resumed = false
    private var trackerUp = false
    private var trackerBroken = false
    private val startedAt = SystemClock.uptimeMillis()

    private class Capture(val kind: String, val file: File, val ext: String, val poster: ByteArray? = null) {
        var saved: Captures.Item? = null
    }

    private var pending: Capture? = null
    private var recorder: Recorder? = null
    private var recStartedAt = 0L
    private var benchRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        tracker = Tracker(this)
        painter = Painter()
        compositor = Compositor(tracker, painter)
        camera = CameraSource(this)
        server = Server(this)
        captures = Captures(this, server, exec)
        mic = MicHub()
        painter.mic = mic
        Sfx.init()
        rotOverride = getSharedPreferences("device", MODE_PRIVATE).getInt("rot", -1).takeIf { it >= 0 }
        Places.assets = assets
        CatHat.assets = assets
        Places.current = getSharedPreferences("places", MODE_PRIVATE).getInt("place", 0).coerceIn(0, Places.PLACES.size - 1)
        buildUi()

        compositor.start(this) { st -> ui.post { cameraTexture = st; syncSource() } }
        loadMsg.text = "Loading the magic…"
        tracker.select(Mode.FAST) { ok ->
            ui.post {
                trackerUp = ok
                trackerBroken = !ok
            }
        }
        // Loaded on their own thread: the first tap on the puppy or on Places is instant.
        tracker.preload(Mode.MESH, Mode.SEGMENT)

        // Storage is for kept photos and clips, in the shared Pictures and Movies folders. Android 9
        // only mounts shared storage writable for an app holding read as well as write.
        val want = arrayOf(
            Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE,
        )
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (want.isNotEmpty()) requestPermissions(want.toTypedArray(), 1)
        exec.execute { captures.migrate() }

        ui.post(hudTick)
        ui.postDelayed(logTick, 2000)
        applyIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        immersive()
        syncSource()
        syncMic()
        syncSoundtrack()
        syncAmbience()
        captures.uploadPending()
    }

    override fun onPause() {
        resumed = false
        if (recorder != null) stopRec()
        mic.release("filter")
        musicToken++
        musicVoice = 0
        Mixer.stopAll()
        // stopAll ended the soundtrack too; forget it so resuming starts it again.
        soundtrackName = null
        soundtrackVoice = 0
        Soundtrack.startedNs = 0L
        ambienceName = null
        ambienceVoice = 0
        ui.removeCallbacks(retryAmbience)
        ui.removeCallbacks(retryCamera)
        camera.close()
        openedCamera = false
        super.onPause()
    }

    override fun onDestroy() {
        Mixer.shutdown()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            pair.visibility == View.VISIBLE -> pair.close()
            settings.visibility == View.VISIBLE -> closeSettings()
            albumPanel.visibility == View.VISIBLE -> albumPanel.close()
            review.visibility == View.VISIBLE -> closeReview()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        syncSource()
        syncMic()
        exec.execute { captures.migrate() }
    }

    /* ------------------------------ Source ------------------------------ */

    private fun syncSource() {
        compositor.setTestFaces(testFaces)
        if (testFaces > 0) {
            if (openedCamera) {
                camera.close()
                openedCamera = false
            }
            return
        }
        val st = cameraTexture ?: return
        if (!resumed || openedCamera) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val id = camera.ids().firstOrNull() ?: return
        openedCamera = true
        camera.open(id, st, { o ->
            ui.post {
                opened = o
                compositor.sourceW = o.size.width
                compositor.sourceH = o.size.height
                applyRotation()
                if (loadMsg.text.startsWith(CAMERA_PROBLEM)) loadMsg.text = ""
            }
            Log.i(TAG, "camera ${o.id} ${o.size} sensor=${o.sensorOrientation} fps=${o.fps}")
        }, { err ->
            Log.e(TAG, err)
            ui.post {
                openedCamera = false
                // Error 3 is the Portal's privacy button: it lets the camera open, sends no
                // frames, then kills the session. Keep retrying so the preview comes back
                // by itself when the button is pressed again.
                val msg = if (err.endsWith("error 3")) "$CAMERA_PROBLEM — is the privacy button on?" else "$CAMERA_PROBLEM: $err"
                loadMsg.text = msg
                // Past the loading screen the last frame just freezes, so say why over it.
                if (loader.visibility != View.VISIBLE) hint(msg, CAMERA_RETRY_MS + 1000)
                ui.removeCallbacks(retryCamera)
                if (resumed) ui.postDelayed(retryCamera, CAMERA_RETRY_MS)
            }
        })
    }

    private val retryCamera = Runnable { syncSource() }

    // The camera service already turns the buffer by the sensor's mounting (it arrives in the
    // SurfaceTexture matrix — see Compositor.readCameraTransform), so all that is left is to
    // undo the screen's own turn. The gen 1 Portal's panel is portrait-native and runs at
    // ROTATION_270, which makes this 90 — measured on the device, not reasoned: the old
    // sensor+display formula gave 0 and a sideways picture.
    @Suppress("DEPRECATION")
    private fun applyRotation() {
        if (opened == null) return
        val display = when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        compositor.rotation = rotOverride ?: ((360 - display) % 360)
    }

    /* -------------------------------- UI -------------------------------- */

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Palette.BG) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val stage = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            // Some effects change on a tap (Monster / Cutie); the rest ignore it.
            setOnClickListener { painter.active?.poke() }
        }
        surface = SurfaceView(this)
        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                compositor.setWindow(holder.surface, width, height)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) = compositor.releaseWindow()
        })
        stage.addView(surface, lp(MATCH, MATCH))

        // A red frame is the unmissable "we are recording" cue on a shared screen.
        recBorder = View(this).apply {
            background = GradientDrawable().apply { setStroke(dp(5), Palette.REC) }
            visibility = View.GONE
        }
        stage.addView(recBorder, lp(MATCH, MATCH))

        hintView = label("", 20f, bold = true).apply {
            background = rounded(Color.argb(158, 0, 0, 0), dp(999).toFloat())
            setPadding(dp(20), dp(10), dp(20), dp(10))
            alpha = 0f
        }
        stage.addView(hintView, lp(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(18) })

        recClock = label("0:00", 20f, bold = true).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        recPill = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.argb(168, 0, 0, 0), dp(999).toFloat())
            setPadding(dp(16), dp(10), dp(20), dp(10))
            val dot = View(context).apply { background = rounded(Palette.REC, dp(8).toFloat()) }
            addView(dot, LinearLayout.LayoutParams(dp(16), dp(16)).apply { rightMargin = dp(10) })
            addView(recClock)
            visibility = View.GONE
            ObjectAnimator.ofFloat(dot, "alpha", 1f, 0.15f).apply {
                duration = 500
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.REVERSE
                start()
            }
        }
        stage.addView(recPill, lp(WRAP, WRAP, Gravity.TOP or Gravity.START).apply { leftMargin = dp(18); topMargin = dp(18) })

        flash = View(this).apply { setBackgroundColor(Color.WHITE); alpha = 0f }
        stage.addView(flash, lp(MATCH, MATCH))

        hud = label("", 12f, Palette.HUD).apply {
            typeface = Typeface.MONOSPACE
            background = rounded(Color.argb(140, 0, 0, 0), dp(8).toFloat())
            setPadding(dp(9), dp(6), dp(9), dp(6))
            visibility = View.GONE
        }
        stage.addView(hud, lp(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply { rightMargin = dp(10); bottomMargin = dp(10) })
        stage.addView(View(this).apply {
            setOnClickListener { hud.visibility = if (hud.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        }, lp(dp(64), dp(64), Gravity.BOTTOM or Gravity.END))

        // Settings: where captures are kept, and the optional server under Advanced.
        gear = label("⚙️", 26f).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            background = rounded(Color.argb(140, 0, 0, 0), dp(999).toFloat())
            setOnClickListener { openSettings() }
        }
        stage.addView(gear, lp(dp(60), dp(60), Gravity.TOP or Gravity.END).apply { rightMargin = dp(18); topMargin = dp(18) })

        // The second row, over the bottom of the picture: Places' places, or the filters in a group.
        // It folds away with the arrow above it, so a row of backgrounds isn't sitting over the
        // picture once it has been used. The arrow stays behind to bring it back.
        subRow = LinearLayout(this).apply { setPadding(dp(14), dp(10), dp(14), dp(12)) }
        subBar = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(Color.argb(170, 0, 0, 0), Color.TRANSPARENT))
            addView(subRow)
        }
        subToggle = label("", 20f).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            background = rounded(Color.argb(150, 0, 0, 0), dp(999).toFloat())
            setPadding(dp(22), dp(6), dp(22), dp(6))
            setOnClickListener {
                subOpen = !subOpen
                syncSubOpen()
            }
        }
        subWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(subToggle, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(14); bottomMargin = dp(6) })
            addView(subBar, LinearLayout.LayoutParams(MATCH, WRAP))
            visibility = View.GONE
        }
        stage.addView(subWrap, lp(MATCH, WRAP, Gravity.BOTTOM))

        loadMsg = label("Waking up the camera…", 22f, Palette.DIM).apply { gravity = Gravity.CENTER }
        loader = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                colors = intArrayOf(0xFF241F4D.toInt(), Palette.BG)
                gradientRadius = dp(700).toFloat()
                setGradientCenter(0.5f, 0.4f)
            }
            isClickable = true
            addView(ProgressBar(context), LinearLayout.LayoutParams(dp(54), dp(54)))
            addView(label("PortalSnap", 44f, bold = true), LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(14) })
            addView(loadMsg, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(14) })
        }
        stage.addView(loader, lp(MATCH, MATCH))
        column.addView(stage, LinearLayout.LayoutParams(MATCH, 0, 1f))

        column.addView(View(this).apply { setBackgroundColor(Palette.LINE) }, LinearLayout.LayoutParams(MATCH, 1))
        val bar = LinearLayout(this).apply {
            setBackgroundColor(Palette.BAR)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(4), dp(14), dp(4))
        }
        // Room above and below the chips, drawn into: the selected chip rises 3dp, and its outline
        // lost its top edge. Padding alone didn't do it, because a layout clips its children to its
        // padding unless told not to.
        val strip = LinearLayout(this).apply {
            setPadding(0, dp(6), 0, dp(6))
            clipToPadding = false
        }
        strip.addView(chip(null, "🚫", "None"))
        for (f in FILTERS) {
            // A group's one chip stands where its first member would.
            val group = GROUPS.firstOrNull { f in it.members }
            val c = when {
                group == null -> chip(f, emojiOr(f.emoji, EMOJI_FALLBACK[f.id] ?: "✨"), f.name)
                group.members[0] === f -> groupChip(group)
                else -> continue
            }
            strip.addView(c, LinearLayout.LayoutParams(dp(96), dp(96)).apply { leftMargin = dp(10) })
        }
        styleChips(null)
        bar.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(strip)
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        album = bigButton("🖼️", Palette.BIG_ALT, 0xFF6B7290.toInt()).apply { setOnClickListener { openAlbum() } }
        record = bigButton("🎥", Palette.BIG_ALT).apply { setOnClickListener { if (recorder != null) stopRec() else startRec() } }
        shutter = bigButton("📸", Palette.HOT).apply { setOnClickListener { takePhoto() } }
        for (b in listOf(album, record, shutter)) bar.addView(b, LinearLayout.LayoutParams(dp(96), dp(96)).apply { leftMargin = dp(12) })
        column.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(column, lp(MATCH, MATCH))

        review = ReviewPanel(this, onKeep = { keep() }, onAgain = { closeReview() },
            onPlaying = { on -> clipSound("review", on) }).apply { visibility = View.GONE }
        root.addView(review, lp(MATCH, MATCH))
        albumPanel = AlbumPanel(this, captures, exec, onBack = { albumPanel.close() },
            onPlaying = { on -> clipSound("album", on) }).apply { visibility = View.GONE }
        root.addView(albumPanel, lp(MATCH, MATCH))
        settings = SettingsPanel(this, server, captures, exec, onBack = { closeSettings() },
            onPair = { openPair() }).apply { visibility = View.GONE }
        root.addView(settings, lp(MATCH, MATCH))
        pair = PairPanel(this, server, exec, onClose = {
            settings.refresh()
            immersive()
        }).apply { visibility = View.GONE }
        root.addView(pair, lp(MATCH, MATCH))

        setContentView(root)
    }

    private fun chip(f: Filter?, emoji: String, name: String): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(label(emoji, 38f).apply { gravity = Gravity.CENTER; includeFontPadding = false })
            addView(label(name, 13f, Palette.DIM, bold = true).apply { gravity = Gravity.CENTER })
            setOnClickListener { selectFilter(f) }
            layoutParams = LinearLayout.LayoutParams(dp(96), dp(96))
        }
        chips += Pair(f, c)
        return c
    }

    private fun groupChip(g: FilterGroup): LinearLayout {
        val c = chip(null, emojiOr(g.emoji, "✨"), g.name)
        chips.removeAt(chips.size - 1)
        c.setOnClickListener { selectFilter(memberOf(g)) }
        groupChips += Pair(g, c)
        return c
    }

    // The member a group's chip opens: the one used last, remembered between launches.
    private fun memberOf(g: FilterGroup): Filter {
        val id = getSharedPreferences("groups", MODE_PRIVATE).getString(g.id, null)
        return g.members.firstOrNull { it.id == id } ?: g.members[0]
    }

    private fun subChip(emoji: String, name: String, onTap: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(label(emoji, 28f).apply { gravity = Gravity.CENTER; includeFontPadding = false })
        addView(label(name, 12f, Palette.DIM, bold = true).apply { gravity = Gravity.CENTER })
        setOnClickListener { onTap() }
    }

    // Places' places while Places is on, a group's filters while one of them is on, nothing
    // otherwise. The row is rebuilt only when that changes.
    private fun syncSubRow(active: Filter?) {
        val want: Any? = if (active === Places) Places else GROUPS.firstOrNull { active in it.members }
        if (want !== subFor) {
            subFor = want
            subRow.removeAllViews()
            val items: List<Triple<String, String, () -> Unit>> = when (want) {
                is FilterGroup -> want.members.map { f -> Triple(emojiOr(f.emoji, EMOJI_FALLBACK[f.id] ?: "✨"), f.name) { selectFilter(f) } }
                Places -> Places.PLACES.mapIndexed { i, p -> Triple(emojiOr(p.emoji, "📍"), p.name) { selectPlace(i) } }
                else -> emptyList()
            }
            for ((i, item) in items.withIndex()) {
                subRow.addView(subChip(item.first, item.second, item.third), LinearLayout.LayoutParams(dp(96), dp(76)).apply { if (i > 0) leftMargin = dp(8) })
            }
            subBar.scrollTo(0, 0)
        }
        subWrap.visibility = if (want == null) View.GONE else View.VISIBLE
        syncSubOpen()
        styleSubRow(active)
    }

    // Folded or not. The arrow points the way the row will go: down to send it away, up to bring
    // it back. Folded, it also says what is down there — a bare arrow on a bright picture is not
    // much to find again from across a room.
    private fun syncSubOpen() {
        subBar.visibility = if (subOpen) View.VISIBLE else View.GONE
        subToggle.text = if (subOpen) {
            "▼"
        } else {
            "▲  " + when (val f = subFor) {
                is FilterGroup -> f.name
                Places -> "Places"
                else -> ""
            }
        }
    }

    private fun styleSubRow(active: Filter?) {
        val selected = when (val s = subFor) {
            is FilterGroup -> s.members.indexOf(active)
            Places -> Places.current
            else -> -1
        }
        for (k in 0 until subRow.childCount) {
            val c = subRow.getChildAt(k) as LinearLayout
            val on = k == selected
            c.background = rounded(if (on) Palette.CHIP_ON else Color.argb(160, 25, 27, 38), dp(18).toFloat(), dp(3), if (on) Palette.ACCENT else Color.TRANSPARENT)
            (c.getChildAt(1) as TextView).setTextColor(if (on) Color.WHITE else Palette.DIM)
        }
    }

    // Remembered, so Places opens where it was left.
    private fun selectPlace(i: Int) {
        Places.current = i
        getSharedPreferences("places", MODE_PRIVATE).edit().putInt("place", i).apply()
        styleSubRow(painter.active)
    }

    private fun styleChips(active: Filter?) {
        for ((f, c) in chips) styleChip(c, f === active)
        for ((g, c) in groupChips) styleChip(c, active in g.members)
    }

    private fun styleChip(c: LinearLayout, on: Boolean) {
        c.background = rounded(if (on) Palette.CHIP_ON else Palette.CHIP, dp(22).toFloat(), dp(3), if (on) Palette.ACCENT else Color.TRANSPARENT)
        c.translationY = if (on) -dp(3).toFloat() else 0f
        (c.getChildAt(1) as TextView).setTextColor(if (on) Color.WHITE else Palette.DIM)
    }

    private fun bigButton(emoji: String, bg: Int, border: Int = Color.WHITE) = label(emoji, 34f).apply {
        gravity = Gravity.CENTER
        includeFontPadding = false
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(bg)
            setStroke(dp(5), border)
        }
    }

    private fun hint(msg: String, ms: Long = 2200) {
        hintView.text = msg
        hintView.animate().alpha(1f).setDuration(250).start()
        ui.removeCallbacks(hideHint)
        ui.postDelayed(hideHint, ms)
    }

    private val hideHint = Runnable { hintView.animate().alpha(0f).setDuration(250).start() }

    @Suppress("DEPRECATION")
    private fun immersive() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }

    // Pairing is the moment this Portal's album should reach the server — all of it, not just
    // whatever it takes from now on. Both ways in go through here so they behave the same.
    private fun openPair() {
        pair.open { captures.uploadEverything { ui.post { settings.refresh() } } }
    }

    private fun overlayUp() = review.visibility == View.VISIBLE || albumPanel.visibility == View.VISIBLE ||
        pair.visibility == View.VISIBLE || settings.visibility == View.VISIBLE

    private fun live() = compositor.renderFrames.n > 0

    /* ------------------------------ Filters ------------------------------ */

    private fun selectFilter(f: Filter?) {
        styleChips(f)
        syncSubRow(f)
        f?.let { ff -> GROUPS.firstOrNull { ff in it.members }?.let { g -> getSharedPreferences("groups", MODE_PRIVATE).edit().putString(g.id, ff.id).apply() } }
        painter.active = f
        syncMic()
        syncSoundtrack()
        syncAmbience()
        if (f == null || f.tier == tracker.mode || trackerBroken) return
        if (tracker.loadMs(f.tier) == null) {
            hint("Getting ${f.name} ready…", 2500)
        }
        tracker.select(f.tier) { ok -> if (!ok) ui.post { hint("${f.name} is having a nap", 2500) } }
    }

    // A clip on screen carries its own sound, baked in when it was recorded: the soundtrack that
    // was playing, the effect's noises, all of it. The live filter is still running behind the
    // panel, so leaving the mixer on would play the same music a second time, out of step with the
    // clip's copy. It goes quiet for as long as something is playing one back.
    private val playingClips = HashSet<String>()

    private fun clipSound(who: String, playing: Boolean) {
        if (playing) playingClips += who else playingClips -= who
        Mixer.muted = playingClips.isNotEmpty()
    }

    // The mic runs only while an effect listens to it; the recorder takes its own hold.
    private fun syncMic() {
        val wants = resumed && painter.active?.wantsMic == true &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (wants) mic.acquire("filter") else mic.release("filter")
    }

    // The selected filter's soundtrack, looped through Mixer while the app is in front: heard,
    // baked into clips, and (through Soundtrack.startedNs) the clock its cuts follow.
    private fun syncSoundtrack() {
        val want = if (resumed) painter.active?.music else null
        if (want == soundtrackName) return
        Mixer.stop(soundtrackVoice)
        soundtrackVoice = 0
        Soundtrack.startedNs = 0L
        soundtrackName = want
        if (want == null) return
        Thread {
            val clip = synchronized(soundtracks) { soundtracks[want] }
                ?: runCatching { assets.open(want).use { Mixer.wav(it.readBytes()) } }
                    .onFailure { Log.w(TAG, "soundtrack $want", it) }.getOrNull()
                    ?.also { c -> synchronized(soundtracks) { soundtracks[want] = c } }
            if (clip == null) return@Thread
            ui.post {
                // Another filter, or a pause, that arrived while loading wins.
                if (soundtrackName != want || !resumed) return@post
                soundtrackVoice = Mixer.play(clip, 0.75f, loop = true)
                Soundtrack.startedNs = System.nanoTime()
            }
        }.start()
    }

    // The selected filter's ambience (Freefall's wind), looped through Mixer while the app is in
    // front, so it's baked into clips too. Sfx synthesizes at startup, so a try before the sound is
    // ready comes back for another go.
    private val retryAmbience = Runnable { syncAmbience() }

    private fun syncAmbience() {
        ui.removeCallbacks(retryAmbience)
        val want = if (resumed) painter.active?.ambience else null
        if (want == ambienceName && (want == null || ambienceVoice != 0)) return
        Mixer.stop(ambienceVoice)
        ambienceVoice = 0
        ambienceName = want
        if (want == null) return
        ambienceVoice = Sfx.loop(want, 0.45f)
        if (ambienceVoice == 0) ui.postDelayed(retryAmbience, 500)
    }

    // A track for the music-reactive effects, from adb for now:
    // `--es music /sdcard/Android/data/net.sgransoft.portalsnap/files/song.mp3`, or `stop`.
    // It's decoded into Mixer, so it's baked into recordings along with the sound effects.
    private fun playMusic(path: String) {
        val token = ++musicToken
        Mixer.stop(musicVoice)
        musicVoice = 0
        if (path == "stop") return
        Thread {
            val clip = Mixer.decode(path) ?: return@Thread
            Log.i(TAG, "music ${clip.pcm.size / clip.rate}s at ${clip.rate}Hz")
            // A stop (or another track) that arrived while decoding wins.
            if (token == musicToken) musicVoice = Mixer.play(clip, 0.8f, loop = true)
        }.start()
    }

    /* ------------------------------ Capture ------------------------------ */

    private fun takePhoto() {
        if (recorder != null || overlayUp() || !live()) return
        flash.alpha = 0.9f
        flash.animate().alpha(0f).setDuration(320).start()
        compositor.requestPhoto { jpeg ->
            if (jpeg == null) {
                ui.post { hint("That photo didn't come out — try again") }
            } else {
                val f = File(cacheDir, "pic-${System.currentTimeMillis()}.jpg").apply { writeBytes(jpeg) }
                ui.post { showReview(Capture("photo", f, "jpg")) }
            }
        }
    }

    private fun startRec() {
        if (recorder != null || overlayUp() || !live()) return
        val file = File(cacheDir, "vid-${System.currentTimeMillis()}.mp4")
        val micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val rec = try {
            Recorder(file, if (micOk) mic else null)
        } catch (e: Exception) {
            Log.e(TAG, "recorder", e)
            hint("Video recording isn't available here", 3000)
            return
        }
        recorder = rec
        recStartedAt = SystemClock.uptimeMillis()
        compositor.startRecording(rec)
        recordingUi(true)
        val active = painter.active
        hint(
            when {
                !rec.hasAudio -> "Recording (no microphone)"
                active != null && (voiceOf(active, null) != 1f || active.voiceFx != VoiceFx.NONE) -> "Recording — say something in your ${active.name} voice!"
                else -> "Recording — say something!"
            }, 2000,
        )
        ui.post(recTick)
    }

    private fun stopRec(discard: Boolean = false) {
        val rec = recorder ?: return
        recorder = null
        recordingUi(false)
        compositor.stopRecording { poster ->
            val file = rec.stop()
            ui.post {
                when {
                    discard -> file?.delete()
                    file == null -> hint("That clip came out empty")
                    else -> showReview(Capture("video", file, "mp4", poster))
                }
            }
        }
    }

    private val recTick = object : Runnable {
        override fun run() {
            if (recorder == null) return
            val ms = SystemClock.uptimeMillis() - recStartedAt
            val s = ms / 1000
            val cap = MAX_CLIP_MS / 1000
            recClock.text = "%d:%02d / %d:%02d".format(s / 60, s % 60, cap / 60, cap % 60)
            if (ms >= MAX_CLIP_MS) stopRec() else ui.postDelayed(this, 200)
        }
    }

    private fun recordingUi(on: Boolean) {
        recBorder.visibility = if (on) View.VISIBLE else View.GONE
        recPill.visibility = if (on) View.VISIBLE else View.GONE
        record.text = if (on) "⏹" else "🎥"
        record.background = if (on) rounded(Palette.REC, dp(26).toFloat(), dp(5), Color.WHITE) else GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Palette.BIG_ALT)
            setStroke(dp(5), Color.WHITE)
        }
        for (b in listOf(shutter, album, gear)) {
            b.isEnabled = !on
            b.alpha = if (on) 0.35f else 1f
        }
    }

    private fun showReview(c: Capture) {
        discardPending()
        pending = c
        if (c.kind == "video") review.showVideo(c.file) else review.showPhoto(c.file)
    }

    private fun discardPending() {
        // The cache file is only ever the draft: a kept capture was copied into the album.
        pending?.file?.delete()
        pending = null
    }

    private fun closeReview() {
        review.close()
        discardPending()
        immersive()
    }

    // Kept on this Portal first, so a capture never depends on a network. If a server is set up
    // (Settings, under Advanced) it's then sent there in the background, and retried later if it
    // can't go now.
    private fun keep() {
        val c = pending ?: return
        if (c.saved != null) return
        review.saving()
        exec.execute {
            try {
                val item = captures.save(c.file, c.kind, c.poster)
                val where = if (captures.shared) "on this Portal" else "inside the app"
                ui.post {
                    c.saved = item
                    if (pending === c) review.saved("Saved $where ✓")
                }
                if (captures.waiting(item) && server.paired) {
                    ui.post { if (pending === c) review.say("Saved $where ✓  Sending it to your server…", Palette.OK) }
                    captures.send(item) { ok ->
                        ui.post {
                            if (pending !== c) return@post
                            if (ok) {
                                review.say("Saved $where and sent to your server ✓", Palette.OK)
                            } else {
                                review.say("Saved $where ✓  It'll go to your server when it can.", Palette.OK)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "keep", e)
                ui.post { if (pending === c) review.failed(e.message ?: "unknown error") }
            }
        }
    }

    private fun openAlbum() {
        if (recorder != null) return
        albumPanel.open()
    }

    private fun openSettings() {
        if (recorder != null) return
        settings.open()
    }

    private fun closeSettings() {
        settings.close()
        immersive()
    }

    /* ---------------------------- Instruments ---------------------------- */

    private fun snapshot(): JSONObject = JSONObject().apply {
        put("source", if (testFaces > 0) "test$testFaces" else "camera")
        put("rot", compositor.rotation)
        put("rotOverride", rotOverride ?: -1)
        put("filter", painter.active?.id ?: "none")
        put("mode", tracker.mode.name)
        put("delegate", tracker.delegate)
        put("inferP50", r1(tracker.infer.pct(0.5)))
        put("inferP95", r1(tracker.infer.pct(0.95)))
        put("grabP50", r1(tracker.grab.pct(0.5)))
        put("detectFps", r1(tracker.rate.fps()))
        put("cameraFps", r1(compositor.cameraRate.fps()))
        put("renderFps", r1(compositor.renderRate.fps()))
        put("frameMsP50", r1(compositor.frameMs.pct(0.5)))
        put("frameMsP95", r1(compositor.frameMs.pct(0.95)))
        put("paintMsP50", r1(painter.paintMs.pct(0.5)))
        put("recFps", r1(compositor.recRate.fps()))
        put("faces", painter.liveFaces)
        put("jitterPx", r1(painter.jitter.toDouble()))
        put("voice", r1(painter.voice.toDouble()))
        put("micLevel", r1(mic.level.toDouble()))
        put("beats", mic.beats)
        put("micSilent", mic.silent)
        put("segPct", r1(compositor.segShare * 100.0))
        put("segCrop", r1(compositor.segCrop * 100.0))
        put("segModel", tracker.segModelName)
        put("segInput", "${tracker.segInputW}x${tracker.segInputH}")
        // Two decimals, not r1's one: these are dials turned in small steps.
        put("maskStill", "%.2f".format(compositor.maskStill))
        put("maskMove", "%.2f".format(compositor.maskMove))
        put("cut", "%.2f/%.2f/%.0f/%.1f".format(compositor.cutLo, compositor.cutHi, compositor.cutColour, compositor.cutCentre))
        put("segFullFrame", compositor.segFullFrame)
        put("freeze", compositor.freeze)
        put("synced", compositor.synced)
        put("loadMs", JSONObject().apply { Mode.entries.forEach { m -> tracker.loadMs(m)?.let { put(m.name, it) } } })
        put("nativeHeapMB", Debug.getNativeHeapAllocatedSize() / 1_000_000)
        tracker.lastError?.let { put("error", it) }
    }

    private val hudTick = object : Runnable {
        override fun run() {
            if (loader.visibility == View.VISIBLE) {
                val waited = SystemClock.uptimeMillis() - startedAt
                if ((live() && (trackerUp || trackerBroken)) || waited > 15000) {
                    loader.visibility = View.GONE
                    hint(if (trackerBroken || !trackerUp) "Filters are having a nap — camera still works" else "Pick a filter below!", 4000)
                }
            }
            if (hud.visibility == View.VISIBLE) {
                val o = opened
                hud.text = buildString {
                    append("source  ${if (testFaces > 0) "test portrait x$testFaces" else "camera ${o?.id ?: "-"} ${o?.size ?: ""} rot ${compositor.rotation}"}\n")
                    append("mode    ${tracker.mode}  ${tracker.delegate}${if (tracker.mode == Mode.SEGMENT) "  ${tracker.segModelName} ${tracker.segInputW}x${tracker.segInputH}" else ""}\n")
                    append("grab    %.1f ms\n".format(tracker.grab.pct(0.5)))
                    append("infer   %.1f ms  p95 %.1f\n".format(tracker.infer.pct(0.5), tracker.infer.pct(0.95)))
                    append("detect  %.1f fps\n".format(tracker.rate.fps()))
                    append("camera  %.1f fps\n".format(compositor.cameraRate.fps()))
                    append("render  %.1f fps\n".format(compositor.renderRate.fps()))
                    append("frame   %.1f ms  p95 %.1f\n".format(compositor.frameMs.pct(0.5), compositor.frameMs.pct(0.95)))
                    append("paint   %.1f ms\n".format(painter.paintMs.pct(0.5)))
                    append("jit     %.1f px\n".format(painter.jitter))
                    append("faces   ${painter.liveFaces} / ${Anchors.faceCap(tracker.mode)}\n")
                    append("voice   %.2f\n".format(painter.voice))
                    if (painter.active?.wantsMic == true || recorder != null) append("mic     %.2f  beats %d%s\n".format(mic.level, mic.beats, if (mic.silent) "  SILENT (privacy on?)" else ""))
                    if (tracker.mode == Mode.SEGMENT) {
                append("seg     %.0f%% person  crop %.0f%%%s\n".format(compositor.segShare * 100, compositor.segCrop * 100, if (compositor.segFullFrame) "  FULL" else ""))
                append("mask    still %.2f  move %.2f\n".format(compositor.maskStill, compositor.maskMove))
                append("cut     %.2f-%.2f  colour %.0f  centre %.1f\n".format(compositor.cutLo, compositor.cutHi, compositor.cutColour, compositor.cutCentre))
            }
                    if (recorder != null) append("rec     %.1f fps\n".format(compositor.recRate.fps()))
                    tracker.lastError?.let { append("err     ${it.take(60)}") }
                }.trimEnd()
            }
            ui.postDelayed(this, 250)
        }
    }

    // One dial: the value asked for, or [fallback] when it's negative, logged either way so a
    // session of turning them leaves a record of what was tried.
    private fun segDial(i: Intent, name: String, current: Float, fallback: Float): Float {
        val v = i.getFloatExtra(name, -1f)
        val next = if (v < 0f) fallback else v
        Log.i(TAG, "$name $current -> $next")
        return next
    }

    private val logTick = object : Runnable {
        override fun run() {
            Log.i(TAG, "stats " + snapshot())
            ui.postDelayed(this, 2000)
        }
    }

    /* ------------------------------- adb -------------------------------- */

    private fun applyIntent(i: Intent?) {
        i ?: return
        i.getStringExtra("server")?.let { server.base = it }
        // Kept per device: a rotation fixed once from adb should survive the next launch.
        // `--ei rot -1` goes back to the computed value.
        if (i.hasExtra("rot")) {
            val r = i.getIntExtra("rot", -1)
            rotOverride = if (r < 0) null else ((r % 360) + 360) % 360
            getSharedPreferences("device", MODE_PRIVATE).edit().putInt("rot", r).apply()
            applyRotation()
        }
        if (i.hasExtra("faces")) {
            testFaces = if (compositor.hasTestImage || i.getIntExtra("faces", 0) == 0) i.getIntExtra("faces", 0) else i.getIntExtra("faces", 0)
            syncSource()
        }
        i.getStringExtra("filter")?.let { id -> selectFilter(FILTERS.firstOrNull { it.id == id }) }
        // `--es place moon`: which of Places' places.
        i.getStringExtra("place")?.let { id -> Places.PLACES.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { selectPlace(it) } }
        if (i.hasExtra("hud")) hud.visibility = if (i.getBooleanExtra("hud", false)) View.VISIBLE else View.GONE
        i.getStringExtra("music")?.let { playMusic(it) }
        // Sound check: `--es sfx clink1` plays one effect.
        i.getStringExtra("sfx")?.let { Sfx.play(it) }
        // Where the segmenter runs: cpu, gpu or auto (GPU unless it failed before). Takes effect
        // the next time the app starts, and forgets any earlier GPU failure.
        i.getStringExtra("segDelegate")?.let {
            getSharedPreferences("tracker", MODE_PRIVATE).edit()
                .putString("segDelegate", it).putBoolean("segGpuBad", false).putBoolean("segGpuTrying", false).commit()
            Log.i(TAG, "segmenter delegate set to $it; restart to apply")
        }
        // Which segmentation model: landscape (256x144), general (256x256) or multiclass. Applied
        // at once, so two can be compared on the same person before they have moved.
        i.getStringExtra("segModel")?.let {
            getSharedPreferences("tracker", MODE_PRIVATE).edit().putString("segModel", it).commit()
            tracker.reloadSegmenter { ok -> Log.i(TAG, "segmentation model $it loaded=$ok") }
        }
        // The cut-out's dials, live: no restart, so they can be turned with someone standing in
        // front of the Portal instead of guessed at. A negative value puts one back to its default.
        if (i.hasExtra("maskStill")) compositor.maskStill = segDial(i, "maskStill", compositor.maskStill, 0.75f)
        if (i.hasExtra("maskMove")) compositor.maskMove = segDial(i, "maskMove", compositor.maskMove, 0.75f)
        if (i.hasExtra("cutLo")) compositor.cutLo = segDial(i, "cutLo", compositor.cutLo, 0.30f)
        if (i.hasExtra("cutHi")) compositor.cutHi = segDial(i, "cutHi", compositor.cutHi, 0.60f)
        if (i.hasExtra("cutColour")) compositor.cutColour = segDial(i, "cutColour", compositor.cutColour, 60f)
        if (i.hasExtra("cutCentre")) compositor.cutCentre = segDial(i, "cutCentre", compositor.cutCentre, 2f)
        if (i.hasExtra("maskView")) compositor.maskView = i.getBooleanExtra("maskView", false)
        // `--es testRect 0,1,0.35,0.8`: which part of the test portrait fills the frame.
        i.getStringExtra("testRect")?.let { r ->
            val v = r.split(",").mapNotNull { it.trim().toFloatOrNull() }
            if (v.size == 4) compositor.setTestCrop(v[0], v[1], v[2], v[3])
        }
        // `--ez freeze true` holds the picture so a model or a dial can be compared on one frame.
        if (i.hasExtra("freeze")) {
            compositor.freeze = i.getBooleanExtra("freeze", false)
            Log.i(TAG, "freeze ${compositor.freeze}")
        }
        if (i.hasExtra("segFullFrame")) {
            compositor.segFullFrame = i.getBooleanExtra("segFullFrame", false)
            Log.i(TAG, "segmenter sees ${if (compositor.segFullFrame) "the whole frame" else "a crop"}")
        }
        if (i.hasExtra("jaw")) painter.debugJaw = i.getFloatExtra("jaw", -1f).takeIf { it >= 0f }
        // `--ef turn 30`: every head turned this many degrees (Cool's arms); 999 clears.
        // `--ef rock 20`: the test portrait tilts 20 degrees each way and sways; 0 stops it.
        if (i.hasExtra("rock")) compositor.testRock = i.getFloatExtra("rock", 0f)
        if (i.hasExtra("turn")) painter.debugTurn = i.getFloatExtra("turn", 999f).takeIf { it in -90f..90f }
        if (i.hasExtra("rideDist")) BikeRide.debugDist = i.getFloatExtra("rideDist", -1f).takeIf { it > 0f }
        if (i.hasExtra("fallDist")) Freefall.debugDist = i.getFloatExtra("fallDist", -1f).takeIf { it > 0f }
        when (i.getStringExtra("action")) {
            "photo" -> takePhoto()
            "poke" -> painter.active?.poke()
            "record" -> startRec()
            "stop" -> stopRec()
            "keep" -> keep()
            "again" -> closeReview()
            "album" -> openAlbum()
            "settings" -> openSettings()
            "pair" -> openPair()
            "close" -> {
                pair.close()
                settings.close()
                albumPanel.close()
                closeReview()
            }
        }
        if (i.getBooleanExtra("bench", false)) {
            val onCamera = i.getBooleanExtra("benchCamera", false)
            ui.postDelayed({ runBench(onCamera) }, 1000)
        }
    }

    /* ------------------------------ Bench ------------------------------- */

    private class Phase(val name: String, val faces: Int, val filter: String?, val record: Boolean)

    // recdiag.html's idea, on the test portrait so it needs nobody in front of the camera:
    // each row changes one thing. Warm-up discarded, then measured. Results to logcat
    // (PSNAP_BENCH) and to files/bench-*.json.
    // `--ez benchCamera true` runs every row on the live camera instead, for when somebody is
    // sitting in front of it: the numbers that matter, with a real face and real lighting.
    private fun runBench(onCamera: Boolean = false) {
        if (benchRunning) return
        benchRunning = true
        val source = if (compositor.hasTestImage && !onCamera) 1 else 0
        val phases = if (onCamera) listOf(
            Phase("A camera: tracker only (fast)", 0, null, false),
            Phase("B camera: googly (fast)", 0, "googly", false),
            Phase("D camera: puppy (mesh)", 0, "dog", false),
            Phase("F camera: bike ride (fast)", 0, "bike", false),
            Phase("G camera: places (segment)", 0, "places", false),
            Phase("H camera: puppy while recording", 0, "dog", true),
            Phase("I camera: places while recording", 0, "places", true),
        ) else listOf(
            Phase("A tracker only, no filter", source, null, false),
            Phase("B googly, 1 face (fast)", source, "googly", false),
            Phase("C googly, 2 faces (fast)", source * 2, "googly", false),
            Phase("D puppy, 1 face (mesh)", source, "dog", false),
            Phase("E puppy, 2 faces (mesh)", source * 2, "dog", false),
            Phase("F bike ride, 2 faces", source * 2, "bike", false),
            Phase("G places (segment)", source, "places", false),
            Phase("H puppy while recording", source, "dog", true),
            Phase("I places while recording", source, "places", true),
            Phase("J camera, no filter", 0, null, false),
            Phase("K camera, puppy", 0, "dog", false),
        )
        benchStep(phases, 0, JSONArray())
    }

    private fun benchStep(phases: List<Phase>, i: Int, out: JSONArray) {
        if (i >= phases.size) {
            val f = File(getExternalFilesDir(null), "bench-${System.currentTimeMillis()}.json")
            f.writeText(out.toString(2))
            Log.i("PSNAP_BENCH", "done ${f.absolutePath}")
            benchRunning = false
            testFaces = 0
            selectFilter(null)
            syncSource()
            return
        }
        val p = phases[i]
        testFaces = p.faces
        syncSource()
        val filter = FILTERS.firstOrNull { it.id == p.filter }
        selectFilter(filter)
        // No filter keeps whatever tier was last loaded, as the app does; a bench row has to
        // say which tier it measured, so "no filter" means the fast tier here.
        if (filter == null) tracker.select(Mode.FAST)
        ui.postDelayed({
            if (p.record) startRec()
            ui.postDelayed({
                tracker.resetStats()
                compositor.frameMs.clear()
                painter.paintMs.clear()
                val d0 = tracker.detections.n
                val r0 = compositor.renderFrames.n
                val t0 = SystemClock.uptimeMillis()
                ui.postDelayed({
                    val sec = (SystemClock.uptimeMillis() - t0) / 1000.0
                    val row = snapshot().apply {
                        put("phase", p.name)
                        put("detectFps", r1((tracker.detections.n - d0) / sec))
                        put("renderFps", r1((compositor.renderFrames.n - r0) / sec))
                    }
                    Log.i("PSNAP_BENCH", row.toString())
                    out.put(row)
                    if (p.record) stopRec(discard = true)
                    ui.postDelayed({ benchStep(phases, i + 1, out) }, if (p.record) 1500 else 200)
                }, 8000)
            }, 3000)
        }, 1500)
    }

    private companion object {
        const val MAX_CLIP_MS = 60_000L
        const val CAMERA_PROBLEM = "Camera problem"
        const val CAMERA_RETRY_MS = 3000L

        // Android 9's emoji font predates some filters' emoji.
        val EMOJI_FALLBACK = mapOf("mirror" to "👯", "disco" to "✨")
    }
}
