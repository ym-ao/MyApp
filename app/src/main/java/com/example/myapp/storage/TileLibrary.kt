package com.example.myapp.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.sqrt

/** One remembered picture of a tile's number area, and the level it stands for. */
class TileEntry(
    val id: String,
    var value: Int?,          // the level, once known; null = still needs a value
    var hint: Int?,           // what the reader guessed when it wasn't sure
    var locked: Boolean,      // true = you confirmed it; the reader may never change it
    val thumb: Bitmap,
    val feature: FloatArray
)

/**
 * The picture library. Every tile the reader sees is remembered as a small picture.
 * You give pictures a level and lock them; from then on any tile that looks like a
 * locked picture gets that level without any text reading. Several pictures can share
 * one level (the background behind the number changes sometimes).
 *
 * Pictures are compared by correlation of their brightness pattern: about 0.96 for the
 * same level, up to about 0.8 between neighbouring levels in a test, so we only trust
 * a match above [MATCH] and refuse when two different levels are nearly as close.
 */
object TileLibrary {

    const val W = 36
    const val H = 30
    const val MATCH = 0.92f
    const val MARGIN = 0.04f

    private var cache: MutableList<TileEntry>? = null
    private var dirty = false

    private fun dir(context: Context): File {
        val d = File(context.filesDir, "tile_library")
        if (!d.exists()) d.mkdirs()
        return d
    }

    @Synchronized
    fun entries(context: Context): MutableList<TileEntry> {
        cache?.let { return it }
        val list = mutableListOf<TileEntry>()
        val index = File(dir(context), "entries.json")
        if (index.exists()) {
            try {
                val arr = JSONArray(index.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val id = o.getString("id")
                    val file = File(dir(context), "$id.png")
                    if (!file.exists()) continue
                    var bmp = BitmapFactory.decodeFile(file.absolutePath) ?: continue
                    if (bmp.width != W || bmp.height != H) bmp = Bitmap.createScaledBitmap(bmp, W, H, true)
                    list.add(
                        TileEntry(
                            id = id,
                            value = if (o.isNull("value")) null else o.getInt("value"),
                            hint = if (o.isNull("hint")) null else o.getInt("hint"),
                            locked = o.optBoolean("locked", false),
                            thumb = bmp,
                            feature = featureOf(bmp)
                        )
                    )
                }
            } catch (_: Exception) {
                // a damaged index just starts the library over
            }
        }
        cache = list
        return list
    }

    @Synchronized
    fun save(context: Context) {
        val list = cache ?: return
        if (!dirty) return
        val arr = JSONArray()
        for (e in list) {
            val o = JSONObject()
            o.put("id", e.id)
            o.put("value", e.value ?: JSONObject.NULL)
            o.put("hint", e.hint ?: JSONObject.NULL)
            o.put("locked", e.locked)
            arr.put(o)
        }
        File(dir(context), "entries.json").writeText(arr.toString())
        dirty = false
    }

    /** Call after changing an entry's value, hint or lock from a screen. */
    @Synchronized
    fun markChanged(context: Context) {
        dirty = true
        save(context)
    }

    @Synchronized
    fun delete(context: Context, entry: TileEntry) {
        entries(context).remove(entry)
        File(dir(context), "${entry.id}.png").delete()
        markChanged(context)
    }

    /** Brightness pattern with the average removed and the spread scaled to 1. */
    fun featureOf(thumb: Bitmap): FloatArray {
        val n = W * H
        val px = IntArray(n)
        thumb.getPixels(px, 0, W, 0, 0, W, H)
        val v = FloatArray(n)
        var sum = 0f
        for (i in 0 until n) {
            val c = px[i]
            val l = ((c shr 16) and 0xFF) * 0.299f + ((c shr 8) and 0xFF) * 0.587f + (c and 0xFF) * 0.114f
            v[i] = l
            sum += l
        }
        val mean = sum / n
        var sq = 0f
        for (i in 0 until n) {
            v[i] -= mean
            sq += v[i] * v[i]
        }
        val std = sqrt(sq / n) + 1e-6f
        for (i in 0 until n) v[i] /= std
        return v
    }

    private fun correlation(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s / a.size
    }

    /**
     * The level of a locked picture that clearly matches, or null. Refuses (null) when a
     * locked picture of a different level is almost as close.
     */
    fun lookupLocked(list: List<TileEntry>, feature: FloatArray): Int? {
        var bestValue: Int? = null
        var best = -1f
        for (e in list) {
            if (!e.locked || e.value == null) continue
            val c = correlation(feature, e.feature)
            if (c > best) {
                best = c
                bestValue = e.value
            }
        }
        if (bestValue == null || best < MATCH) return null
        for (e in list) {
            if (!e.locked || e.value == null || e.value == bestValue) continue
            if (correlation(feature, e.feature) >= best - MARGIN) return null
        }
        return bestValue
    }

    /**
     * Remembers a tile the reader just looked at. A picture we already know is left alone
     * (an unconfirmed one gets its level refreshed from a confident read); a new picture is
     * added, with a level if the read was confident or a hint if it was not.
     */
    @Synchronized
    fun observe(context: Context, feature: FloatArray, thumb: Bitmap, value: Int?, hint: Int?) {
        val list = entries(context)
        var bestEntry: TileEntry? = null
        var best = -1f
        for (e in list) {
            val c = correlation(feature, e.feature)
            if (c > best) {
                best = c
                bestEntry = e
            }
        }
        val known = bestEntry
        if (known != null && best >= MATCH) {
            if (!known.locked) {
                if (value != null && known.value != value) {
                    known.value = value
                    known.hint = null
                    dirty = true
                } else if (value == null && hint != null && known.value == null && known.hint != hint) {
                    known.hint = hint
                    dirty = true
                }
            }
            return
        }
        val id = UUID.randomUUID().toString().take(8)
        val copy = thumb.copy(Bitmap.Config.ARGB_8888, false)
        File(dir(context), "$id.png").outputStream().use { copy.compress(Bitmap.CompressFormat.PNG, 100, it) }
        list.add(TileEntry(id, value, if (value == null) hint else null, false, copy, featureOf(copy)))
        dirty = true
    }

    /** Make a W x H copy of a crop, the size pictures are stored and compared at. */
    fun makeThumb(crop: Bitmap): Bitmap = Bitmap.createScaledBitmap(crop, W, H, true)
}
