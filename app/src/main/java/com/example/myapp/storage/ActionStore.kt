package com.example.myapp.storage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class ActionType { CLICK, DRAG, HOLD, WAIT, SORT, MERGE, SUMMON }

/**
 * One step of the custom list. CLICK, DRAG and HOLD use real screen pixels (a click and a hold only
 * x1/y1). hold = how long the finger stays down (a drag's duration, a hold's length, a WAIT's length);
 * wait = pause after the step. Each is a range in milliseconds: a random value in it is used every time.
 * repeat = how many times the step is done in a row; jitter = a random offset in pixels (anti-detection).
 * SORT, MERGE and SUMMON are the app's own steps and use no positions.
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
    var waitMax: Long,
    var repeat: Int = 1,
    var jitter: Int = 0
)

class CustomActions(
    var screenWidth: Int,
    var screenHeight: Int,
    var repeat: Int,
    val actions: MutableList<CustomAction>
)

/** The user's recorded clicks and drags, kept in one small file. */
object ActionStore {

    private fun file(context: Context, loadoutId: String) =
        File(context.filesDir, LoadoutStore.actionsFileName(loadoutId))

    fun load(context: Context, loadoutId: String = LoadoutStore.activeId(context)): CustomActions {
        val f = file(context, loadoutId)
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
                        waitMin = a.optLong("wMin", 500L), waitMax = a.optLong("wMax", 500L),
                        repeat = a.optInt("rep", 1).coerceAtLeast(1), jitter = a.optInt("jit", 0).coerceAtLeast(0)
                    )
                )
            }
            CustomActions(o.optInt("w", 0), o.optInt("h", 0), o.optInt("repeat", 1).coerceAtLeast(1), list)
        } catch (_: Exception) {
            CustomActions(0, 0, 1, mutableListOf())
        }
    }

    fun save(context: Context, data: CustomActions, loadoutId: String = LoadoutStore.activeId(context)) {
        val arr = JSONArray()
        for (a in data.actions) {
            val o = JSONObject()
            o.put("t", a.type.name)
            o.put("x1", a.x1).put("y1", a.y1).put("x2", a.x2).put("y2", a.y2)
            o.put("hMin", a.holdMin).put("hMax", a.holdMax)
            o.put("wMin", a.waitMin).put("wMax", a.waitMax)
            o.put("rep", a.repeat).put("jit", a.jitter)
            arr.put(o)
        }
        val root = JSONObject()
        root.put("w", data.screenWidth).put("h", data.screenHeight)
        root.put("repeat", data.repeat)
        root.put("actions", arr)
        file(context, loadoutId).writeText(root.toString())
    }
}
