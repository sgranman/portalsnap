package net.sgransoft.portalsnap

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The photos and clips people keep, stored on the Portal itself. They go in the shared
 * Pictures/PortalSnap and Movies/PortalSnap folders, so they outlive the app and can be copied
 * off with adb or USB. Without the storage permission they fall back to the app's own folders.
 *
 * A server is optional (Settings, under Advanced). When one is set up and sending is on, each
 * kept capture is queued and sent in the background. Anything that didn't make it is retried
 * the next time the app resumes or the album opens.
 */
class Captures(private val ctx: Context, private val server: Server, private val exec: ExecutorService) {
    class Item(val file: File, val kind: String) {
        val at get() = file.lastModified()
        val name: String get() = file.name
        val ext: String get() = file.extension
    }

    private val prefs = ctx.getSharedPreferences("captures", Context.MODE_PRIVATE)
    private val inFlight = HashSet<String>()
    private val sweeping = AtomicBoolean(false)

    /**
     * Whether kept captures go to the shared folders. Android 9 mounts shared storage writable
     * only for an app holding both storage permissions; write alone leaves it unwritable.
     */
    val shared get() = ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
        ctx.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** Send newly kept captures to the server, when one is set up. */
    var autoUpload: Boolean
        get() = prefs.getBoolean("autoUpload", true)
        set(v) = prefs.edit().putBoolean("autoUpload", v).apply()

    /** Copies a capture out of the cache into the album and returns it. [poster] is a clip's first frame. */
    @Throws(IOException::class)
    fun save(src: File, kind: String, poster: ByteArray?): Item {
        val dir = if (shared) sharedDir(kind) else privateDir(kind)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("can't create $dir")
        val dst = unique(dir, Date(), if (kind == VIDEO) "mp4" else "jpg")
        src.copyTo(dst)
        if (poster != null) runCatching { posterFile(dst).writeBytes(poster) }
        scan(dst)
        val item = Item(dst, kind)
        if (autoUpload && server.configured) prefs.edit().putBoolean(QUEUED + item.name, true).apply()
        return item
    }

    /** Everything kept, newest first. */
    fun list(): List<Item> {
        val out = ArrayList<Item>()
        for (kind in arrayOf(PHOTO, VIDEO)) {
            val ext = if (kind == VIDEO) "mp4" else "jpg"
            for (dir in arrayOf(sharedDir(kind), privateDir(kind))) {
                dir.listFiles()?.forEach { f -> if (f.isFile && f.extension.lowercase(Locale.US) == ext) out += Item(f, kind) }
            }
        }
        return out.sortedByDescending { it.at }
    }

    fun poster(item: Item): File? = posterFile(item.file).takeIf { it.exists() }

    fun delete(item: Item): Boolean {
        val ok = item.file.delete()
        posterFile(item.file).delete()
        prefs.edit().remove(SENT + item.name).remove(QUEUED + item.name).apply()
        scan(item.file)
        return ok
    }

    /** On the server that's set up now. */
    fun sent(item: Item) = server.configured && prefs.getString(SENT + item.name, null) == server.base

    /** Queued for the server and not there yet. */
    fun waiting(item: Item) = server.configured && !sent(item) && prefs.getBoolean(QUEUED + item.name, false)

    fun notSent(items: List<Item>) = if (server.configured) items.count { !sent(it) } else 0

    /** Queues everything that isn't on the server yet. */
    fun queueAll() {
        val e = prefs.edit()
        for (item in list()) if (!sent(item)) e.putBoolean(QUEUED + item.name, true)
        e.apply()
    }

    /** Sends one capture on the executor; done(true) once it's on the server. */
    fun send(item: Item, done: (Boolean) -> Unit) {
        exec.execute { done(runCatching { sendNow(item) }.getOrDefault(false)) }
    }

    /** Sends everything queued, one at a time, on the executor. [done] runs either way. */
    fun uploadPending(done: (() -> Unit)? = null) {
        if (!server.configured || !server.paired || !sweeping.compareAndSet(false, true)) {
            done?.invoke()
            return
        }
        exec.execute {
            try {
                for (item in list()) {
                    if (!waiting(item)) continue
                    try {
                        sendNow(item)
                    } catch (_: Server.Unpaired) {
                        break
                    } catch (e: Exception) {
                        Log.w(TAG, "upload ${item.name}: ${e.message}")
                    }
                }
            } finally {
                sweeping.set(false)
                done?.invoke()
            }
        }
    }

    /**
     * Moves captures that older builds kept in the app's private "captures" folder into the
     * album, once the storage permission allows it. With a paired server they were sent when
     * they were kept, so they're marked as sent rather than sent twice.
     */
    fun migrate() {
        if (!shared) return
        val files = ctx.getExternalFilesDir("captures")?.listFiles() ?: return
        val sentTo = if (server.configured && server.paired) server.base else null
        for (f in files) {
            val kind = when (f.extension) {
                "mp4" -> VIDEO
                "jpg" -> PHOTO
                else -> continue
            }
            val dir = sharedDir(kind)
            if (!dir.isDirectory && !dir.mkdirs()) return
            val dst = unique(dir, Date(f.lastModified()), f.extension)
            val moved = runCatching {
                f.copyTo(dst)
                dst.setLastModified(f.lastModified())
                f.delete()
            }.isSuccess
            if (!moved) continue
            if (sentTo != null) prefs.edit().putString(SENT + dst.name, sentTo).apply()
            scan(dst)
        }
    }

    // True once it's on the server, already or just now; false if another send has it in hand.
    // Throws when sending fails.
    private fun sendNow(item: Item): Boolean {
        if (sent(item)) return true
        synchronized(inFlight) { if (!inFlight.add(item.name)) return false }
        try {
            val name = server.upload(item.file, item.ext)
            // The poster is a nicety: a clip that saved is not a failure because its thumbnail wasn't.
            if (item.kind == VIDEO) poster(item)?.let { p -> runCatching { server.upload(p, "jpg", forClip = name) } }
            prefs.edit().putString(SENT + item.name, server.base).remove(QUEUED + item.name).apply()
            return true
        } finally {
            synchronized(inFlight) { inFlight.remove(item.name) }
        }
    }

    private fun sharedDir(kind: String) = File(
        Environment.getExternalStoragePublicDirectory(if (kind == VIDEO) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES),
        FOLDER,
    )

    private fun privateDir(kind: String) = File(
        ctx.getExternalFilesDir(if (kind == VIDEO) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) ?: ctx.filesDir,
        FOLDER,
    )

    // Clip posters are the app's own business, so they stay in its private folder.
    private fun posterFile(f: File): File {
        val dir = ctx.getExternalFilesDir("posters") ?: File(ctx.filesDir, "posters").apply { mkdirs() }
        return File(dir, f.nameWithoutExtension + ".jpg")
    }

    private fun unique(dir: File, at: Date, ext: String): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(at)
        var f = File(dir, "PortalSnap_$stamp.$ext")
        var n = 2
        while (f.exists()) f = File(dir, "PortalSnap_${stamp}_${n++}.$ext")
        return f
    }

    // So the files show up over USB (MTP) and to anything else that reads the media store.
    private fun scan(f: File) {
        MediaScannerConnection.scanFile(ctx, arrayOf(f.path), null, null)
    }

    companion object {
        const val PHOTO = "photo"
        const val VIDEO = "video"
        private const val FOLDER = "PortalSnap"
        private const val SENT = "sent:"
        private const val QUEUED = "queued:"
    }
}
