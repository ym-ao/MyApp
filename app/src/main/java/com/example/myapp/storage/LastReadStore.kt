package com.example.myapp.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.myapp.model.Reading
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Keeps the most recent OCR test (screenshot + what was read) so it can be reviewed. */
object LastReadStore {

    data class LastRead(
        val profileName: String,
        val screenshot: Bitmap,
        val readings: List<Reading>
    )

    fun save(context: Context, profileName: String, screenshot: Bitmap, readings: List<Reading>) {
        File(context.filesDir, "last_read.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }

        val arr = JSONArray()
        for (r in readings) {
            val o = JSONObject()
            o.put("id", r.id)
            o.put("x", r.x.toDouble())
            o.put("y", r.y.toDouble())
            o.put("value", r.value ?: JSONObject.NULL)
            o.put("raw", r.raw)
            o.put("l", r.cropLeft.toDouble())
            o.put("t", r.cropTop.toDouble())
            o.put("r", r.cropRight.toDouble())
            o.put("b", r.cropBottom.toDouble())
            arr.put(o)
        }
        val root = JSONObject()
        root.put("profile", profileName)
        root.put("readings", arr)
        File(context.filesDir, "last_read.json").writeText(root.toString())
    }

    fun load(context: Context): LastRead? {
        val png = File(context.filesDir, "last_read.png")
        val json = File(context.filesDir, "last_read.json")
        if (!png.exists() || !json.exists()) return null

        val bitmap = BitmapFactory.decodeFile(png.absolutePath) ?: return null
        val root = JSONObject(json.readText())
        val arr = root.getJSONArray("readings")
        val list = mutableListOf<Reading>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                Reading(
                    id = o.getInt("id"),
                    x = o.getDouble("x").toFloat(),
                    y = o.getDouble("y").toFloat(),
                    value = if (o.isNull("value")) null else o.getInt("value"),
                    raw = o.getString("raw"),
                    cropLeft = o.getDouble("l").toFloat(),
                    cropTop = o.getDouble("t").toFloat(),
                    cropRight = o.getDouble("r").toFloat(),
                    cropBottom = o.getDouble("b").toFloat()
                )
            )
        }
        return LastRead(root.getString("profile"), bitmap, list)
    }
}
