package net.sgran.portalsnap

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader

/**
 * Places: you, cut out of the room and put somewhere else. The segmentation tier's person mask
 * pastes the camera over a picture of the chosen place, picked from a second row of chips that
 * shows under the picture while Places is on. The artwork is public domain or CC0, prepared at
 * the frame's size (see THIRD-PARTY.md). It replaced the old drawn Beach, Palace and Moon
 * backdrops, which grew out of the Photo Booth's Pink Palace.
 */
object Places : Filter("places", "Places", "🗺️", Mode.SEGMENT) {
    // The Painter only paints a segment filter's backdrop when it says it has one.
    override val usesUnder = true

    class Place(val id: String, val name: String, val emoji: String) {
        val asset = "places/$id.jpg"
    }

    val PLACES = listOf(
        Place("castle", "Castle", "🏰"),
        Place("forest", "Forest", "🌲"),
        Place("waterfall", "Waterfall", "🏞️"),
        Place("circus", "Circus", "🎪"),
        Place("yacht", "Yacht", "🛥️"),
        Place("beach", "Beach", "🏖️"),
        Place("northpole", "North Pole", "❄️"),
        Place("moon", "Moon", "🌕"),
    )

    /** Which place, as an index into [PLACES]: set from the UI thread, read while painting. */
    @Volatile var current = 0

    /** Set by MainActivity at start, so the pictures can be loaded. */
    @Volatile var assets: AssetManager? = null

    // Decoded on first use, on the render thread, and kept for this place and the one before, so
    // flicking between two costs nothing. Each is about 3.7MB at the frame's size.
    private val loaded = LinkedHashMap<String, Bitmap>()
    private val missing = HashSet<String>()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()

    override fun backdrop(d: Draw) {
        val bmp = bitmap(PLACES[current.coerceIn(0, PLACES.size - 1)])
        if (bmp == null) {
            val sky = LinearGradient(0f, 0f, 0f, d.h, intArrayOf(hex("#2f8fd8"), hex("#ffe6b8")), null, Shader.TileMode.CLAMP)
            d.c.drawRect(0f, 0f, d.w, d.h, d.pen.fill(sky))
            return
        }
        // Cover the frame, cropping the middle if a picture isn't the frame's shape.
        val scale = maxOf(d.w / bmp.width, d.h / bmp.height)
        val w = bmp.width * scale
        val h = bmp.height * scale
        dst.set((d.w - w) / 2, (d.h - h) / 2, (d.w + w) / 2, (d.h + h) / 2)
        d.c.drawBitmap(bmp, null, dst, paint)
    }

    private fun bitmap(place: Place): Bitmap? {
        loaded[place.id]?.let { return it }
        if (place.id in missing) return null
        val am = assets ?: return null
        val bmp = runCatching { am.open(place.asset).use { BitmapFactory.decodeStream(it) } }.getOrNull()
        if (bmp == null) {
            missing += place.id
            return null
        }
        while (loaded.size >= 2) loaded.remove(loaded.keys.first())?.recycle()
        loaded[place.id] = bmp
        return bmp
    }
}
