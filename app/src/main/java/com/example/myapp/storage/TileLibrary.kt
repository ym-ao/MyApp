package com.example.myapp.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.myapp.ocr.TileVision
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** One remembered picture of a tile's number, and the level it stands for. */
class TileEntry(
    val id: String,
    var value: Int?,          // the level, once known; null = still needs a value
    var hint: Int?,           // what the reader guessed when it wasn't sure
    var locked: Boolean,      // true = you confirmed it; the reader may never change it
    val thumb: Bitmap,
    val feature: FloatArray
)

/**
 * The picture library. Every tile the reader sees is remembered as a small, aligned
 * picture of its number. You give pictures a level and lock them; from then on any tile
 * that looks like a locked picture gets that level without any text reading, wherever
 * on the board it is. Several pictures can share one level (the background changes).
 *
 * A match needs a similarity of at least [MATCH]; if a locked picture of a different
 * level is nearly as close, we refuse and let the text reader decide.
 */
object TileLibrary {

    const val W = TileVision.TW
    const val H = TileVision.TH
    const val MATCH = 0.91f
    const val MARGIN = 0.03f

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
                    if (bmp.width != W || bmp.height != H) {
                        // a picture from the first version of the library: align it the new way
                        bmp = TileVision.fromLegacy(bmp) ?: Bitmap.createScaledBitmap(bmp, W, H, true)
                        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                    list.add(
                        TileEntry(
                            id = id,
                            value = if (o.isNull("value")) null else o.getInt("value"),
                            hint = if (o.isNull("hint")) null else o.getInt("hint"),
                            locked = o.optBoolean("locked", false),
                            thumb = bmp,
                            feature = TileVision.featureOf(bmp)
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

    /** Similarity of a tile (best of its few one-pixel shifts) to a stored picture. */
    private fun similarity(patches: Array<FloatArray>, feature: FloatArray): Float {
        var best = -1f
        for (p in patches) {
            var s = 0f
            for (i in p.indices) s += p[i] * feature[i]
            if (s > best) best = s
        }
        return best
    }

    /**
     * The level of a locked picture that clearly matches, or null. Refuses (null) when a
     * locked picture of a different level is almost as close.
     */
    fun lookupLocked(list: List<TileEntry>, look: TileVision.Look): Int? {
        var bestValue: Int? = null
        var best = -1f
        for (e in list) {
            if (!e.locked || e.value == null) continue
            val c = similarity(look.patches, e.feature)
            if (c > best) {
                best = c
                bestValue = e.value
            }
        }
        if (bestValue == null || best < MATCH) return null
        for (e in list) {
            if (!e.locked || e.value == null || e.value == bestValue) continue
            if (similarity(look.patches, e.feature) >= best - MARGIN) return null
        }
        return bestValue
    }

    /**
     * Remembers a tile the reader just looked at. A picture we already know is left alone
     * (an unconfirmed one gets its level refreshed from a confident read); a new picture is
     * added, with a level if the read was confident or a hint if it was not.
     */
    @Synchronized
    fun observe(context: Context, look: TileVision.Look, value: Int?, hint: Int?) {
        val list = entries(context)
        var bestEntry: TileEntry? = null
        var best = -1f
        for (e in list) {
            val c = similarity(look.patches, e.feature)
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
        val copy = look.thumb.copy(Bitmap.Config.ARGB_8888, false)
        File(dir(context), "$id.png").outputStream().use { copy.compress(Bitmap.CompressFormat.PNG, 100, it) }
        list.add(TileEntry(id, value, if (value == null) hint else null, false, copy, TileVision.featureOf(copy)))
        dirty = true
    }
}
