package com.example.myapp.storage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class ActionType { CLICK, DRAG }

/**
 * One recorded click or drag. Positions are real screen pixels. For a click only x1/y1 are used.
 * hold = how long the finger stays down (a drag's duration); wait = pause after the action.
 * Each is a range in milliseconds: a random value in it is used every time.
 */
class CustomAction(
    val type: ActionType,
    var x1: Int,
    var y1: Int,
    var x2: Int,
    var y2: Int,
    var holdMin: Long,
    var holdMax: Long,
    var waitMin: Long,
    var waitMax: Long
)

class CustomActions(
    var screenWidth: Int,
    var screenHeight: Int,
    var repeat: Int,
    val actions: MutableList<CustomAction>
)

/** The user's recorded clicks and drags, kept in one small file. */
object ActionStore {

    private fun file(context: Context) = File(context.filesDir, "custom_actions.json")

    fun load(context: Context): CustomActions {
        val f = file(context)
        if (!f.exists()) return CustomActions(0, 0, 1, mutableListOf())
        return try {
            val o = JSONObject(f.readText())
            val arr = o.getJSONArray("actions")
            val list = mutableListOf<CustomAction>()
            for (i in 0 until arr.length()) {
                val a = arr.getJSONObject(i)
                list.add(
                    CustomAction(
                        type = runCatching { ActionType.valueOf(a.getString("t")) }.getOrDefault(ActionType.CLICK),
                        x1 = a.getInt("x1"), y1 = a.getInt("y1"),
                        x2 = a.optInt("x2", a.getInt("x1")), y2 = a.optInt("y2", a.getInt("y1")),
                        holdMin = a.optLong("hMin", 50L), holdMax = a.optLong("hMax", 50L),
                        waitMin = a.optLong("wMin", 500L), waitMax = a.optLong("wMax", 500L)
                    )
                )
            }
            CustomActions(o.optInt("w", 0), o.optInt("h", 0), o.optInt("repeat", 1).coerceAtLeast(1), list)
        } catch (_: Exception) {
            CustomActions(0, 0, 1, mutableListOf())
        }
    }

    fun save(context: Context, data: CustomActions) {
        val arr = JSONArray()
        for (a in data.actions) {
            val o = JSONObject()
            o.put("t", a.type.name)
            o.put("x1", a.x1).put("y1", a.y1).put("x2", a.x2).put("y2", a.y2)
            o.put("hMin", a.holdMin).put("hMax", a.holdMax)
            o.put("wMin", a.waitMin).put("wMax", a.waitMax)
            arr.put(o)
        }
        val root = JSONObject()
        root.put("w", data.screenWidth).put("h", data.screenHeight)
        root.put("repeat", data.repeat)
        root.put("actions", arr)
        file(context).writeText(root.toString())
    }
}
