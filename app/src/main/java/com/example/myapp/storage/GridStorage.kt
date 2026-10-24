package com.example.myapp.storage

import android.content.Context
import com.example.myapp.model.GridBox
import com.example.myapp.model.GridProfile
import com.example.myapp.model.GridRegion
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Simple JSON-file-backed storage for grid profiles. No database needed —
 * there will only ever be a handful of these.
 */
object GridStorage {

    private fun dir(context: Context): File {
        val d = File(context.filesDir, "grid_profiles")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun save(context: Context, profile: GridProfile) {
        val obj = JSONObject()
        obj.put("name", profile.name)
        obj.put("screenWidth", profile.screenWidth)
        obj.put("screenHeight", profile.screenHeight)

        val regionsArr = JSONArray()
        for (region in profile.regions) {
            val regionObj = JSONObject()
            regionObj.put("name", region.name)

            val boxesArr = JSONArray()
            for (box in region.boxes) {
                val b = JSONObject()
                b.put("id", box.id)
                b.put("x", box.x)
                b.put("y", box.y)
                boxesArr.put(b)
            }
            regionObj.put("boxes", boxesArr)
            regionsArr.put(regionObj)
        }
        obj.put("regions", regionsArr)

        File(dir(context), "${profile.name}.json").writeText(obj.toString())
    }

    fun load(context: Context, name: String): GridProfile? {
        val file = File(dir(context), "$name.json")
        if (!file.exists()) return null

        val obj = JSONObject(file.readText())
        val regions = mutableListOf<GridRegion>()
        val regionsArr = obj.getJSONArray("regions")
        for (i in 0 until regionsArr.length()) {
            val regionObj = regionsArr.getJSONObject(i)
            val boxes = mutableListOf<GridBox>()
            val boxesArr = regionObj.getJSONArray("boxes")
            for (j in 0 until boxesArr.length()) {
                val b = boxesArr.getJSONObject(j)
                boxes.add(GridBox(b.getInt("id"), b.getInt("x"), b.getInt("y")))
            }
            regions.add(GridRegion(regionObj.getString("name"), boxes))
        }

        return GridProfile(
            name = obj.getString("name"),
            screenWidth = obj.getInt("screenWidth"),
            screenHeight = obj.getInt("screenHeight"),
            regions = regions
        )
    }

    fun listProfiles(context: Context): List<String> =
        dir(context).listFiles { f -> f.extension == "json" }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            ?: emptyList()

    fun delete(context: Context, name: String) {
        File(dir(context), "$name.json").delete()
    }
}
