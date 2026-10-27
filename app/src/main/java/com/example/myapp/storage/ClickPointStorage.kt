package com.example.myapp.storage

import android.content.Context
import com.example.myapp.model.ClickPoint
import org.json.JSONObject
import java.io.File

/** One small JSON file per saved click point. */
object ClickPointStorage {

    private fun dir(context: Context): File {
        val d = File(context.filesDir, "click_points")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun save(context: Context, point: ClickPoint) {
        val obj = JSONObject()
        obj.put("name", point.name)
        obj.put("screenWidth", point.screenWidth)
        obj.put("screenHeight", point.screenHeight)
        obj.put("x", point.x)
        obj.put("y", point.y)
        File(dir(context), "${point.name}.json").writeText(obj.toString())
    }

    fun load(context: Context, name: String): ClickPoint? {
        val file = File(dir(context), "$name.json")
        if (!file.exists()) return null
        val obj = JSONObject(file.readText())
        return ClickPoint(
            name = obj.getString("name"),
            screenWidth = obj.getInt("screenWidth"),
            screenHeight = obj.getInt("screenHeight"),
            x = obj.getInt("x"),
            y = obj.getInt("y")
        )
    }

    fun list(context: Context): List<String> =
        dir(context).listFiles { f -> f.extension == "json" }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            ?: emptyList()

    fun delete(context: Context, name: String) {
        File(dir(context), "$name.json").delete()
    }
}
