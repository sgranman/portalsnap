package net.sgran.portalsnap

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The existing portalsnap server, spoken to from a native client.
 *
 * Nothing server-side changes. The Portal pairs the way the browser did — the device
 * grant: it shows a QR, a paired phone approves, the Portal collects its cookie by
 * polling — and then sends that cookie on every request, as the browser would.
 */
class Server(ctx: Context) {
    class Unpaired : IOException("this Portal isn't paired")

    class Pairing(val id: String, val deviceSecret: String, val approveUrl: String, val code: String, val expires: Long)

    class Item(val name: String, val url: String, val kind: String, val poster: String?, val at: String)

    private val prefs = ctx.getSharedPreferences("server", Context.MODE_PRIVATE)

    var base: String
        get() = prefs.getString("base", "") ?: ""
        set(v) = prefs.edit().putString("base", v.trim().trimEnd('/')).apply()

    private var cookie: String?
        get() = prefs.getString("cookie", null)
        set(v) = prefs.edit().putString("cookie", v).apply()

    val configured get() = base.isNotEmpty()
    val paired get() = cookie != null

    fun headers(): Map<String, String> = cookie?.let { mapOf("Cookie" to it) } ?: emptyMap()

    /** Forgets the server and this Portal's pairing with it. */
    fun forget() {
        prefs.edit().remove("base").remove("cookie").apply()
    }

    fun absolute(path: String) = if (path.startsWith("http")) path else base + path

    fun startPairing(): Pairing {
        val j = json(call("POST", "/auth/pair/start", "{}".toByteArray(), "application/json"))
        return Pairing(j.getString("id"), j.getString("deviceSecret"), j.getString("approveUrl"), j.getString("code"), j.getLong("expires"))
    }

    /** "pending", "paired" or "expired". */
    fun poll(p: Pairing): String {
        val body = JSONObject().put("id", p.id).put("deviceSecret", p.deviceSecret).toString().toByteArray()
        val r = call("POST", "/auth/pair/poll", body, "application/json", allowError = true)
        if (r.code == 404) return "expired"
        val j = json(r)
        return if (j.optBoolean("ok")) "paired" else "pending"
    }

    fun whoami(): Boolean = try {
        call("GET", "/auth/whoami", null, null)
        true
    } catch (_: Unpaired) {
        false
    }

    /** Uploads a capture and returns its server name. */
    fun upload(file: File, ext: String, forClip: String? = null): String {
        val q = "/media?ext=$ext" + (forClip?.let { "&for=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: "")
        val type = if (ext == "mp4") "video/mp4" else "image/jpeg"
        val j = json(call("POST", q, null, type, upload = file))
        if (!j.optBoolean("ok")) throw IOException(j.optString("error", "upload failed"))
        return j.getString("name")
    }

    fun list(): List<Item> {
        val arr = json(call("GET", "/media/list", null, null)).optJSONArray("items") ?: return emptyList()
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Item(o.getString("name"), o.getString("url"), o.getString("kind"), o.optString("poster").ifEmpty { null }, o.optString("at"))
        }
    }

    fun delete(url: String) {
        call("DELETE", url, null, null)
    }

    fun bytes(path: String): ByteArray = call("GET", path, null, null).body

    class Response(val code: Int, val body: ByteArray)

    private fun json(r: Response) = JSONObject(String(r.body))

    private fun call(
        method: String, path: String, body: ByteArray?, type: String?,
        upload: File? = null, allowError: Boolean = false,
    ): Response {
        if (!configured) throw IOException("no server set")
        val conn = URL(absolute(path)).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 8000
            conn.readTimeout = 60000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            cookie?.let { conn.setRequestProperty("Cookie", it) }
            if (body != null || upload != null) {
                conn.doOutput = true
                type?.let { conn.setRequestProperty("Content-Type", it) }
                if (upload != null) {
                    conn.setFixedLengthStreamingMode(upload.length())
                    conn.outputStream.use { out -> upload.inputStream().use { it.copyTo(out, 64 * 1024) } }
                } else {
                    conn.setFixedLengthStreamingMode(body!!.size)
                    conn.outputStream.use { it.write(body) }
                }
            }
            val code = conn.responseCode
            conn.headerFields["Set-Cookie"]?.forEach { absorb(it) }
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            if (code == 401) {
                cookie = null
                throw Unpaired()
            }
            if (code >= 400 && !allowError) {
                val msg = runCatching { JSONObject(String(bytes)).optString("error") }.getOrNull()
                throw IOException(if (msg.isNullOrEmpty()) "server said $code" else msg)
            }
            return Response(code, bytes)
        } finally {
            conn.disconnect()
        }
    }

    private fun absorb(header: String) {
        val first = header.substringBefore(';').trim()
        if (!first.startsWith("psnap=")) return
        cookie = if (first == "psnap=" || header.contains("Max-Age=0")) null else first
    }

    private companion object {
        const val USER_AGENT = "PortalSnap for Android (Meta Portal)"
    }
}
