package com.example.myapp.storage

import android.content.Context
import java.io.File

/**
 * Loadouts: named sets of settings, step order and custom clicks/drags. The loadout you were
 * already using (before loadouts existed) simply IS "Loadout 1": it keeps using the same files,
 * so nothing is copied or lost. Grids, click points and the Tile Library are shared by all loadouts.
 */
object LoadoutStore {

    class Loadout(val id: String, val name: String)

    private const val PREFS = "myapp_loadouts"
    const val FIRST = "1"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Where a loadout keeps its settings. Loadout 1 keeps the original file, so it holds the earlier work. */
    fun settingsPrefsName(id: String): String = if (id == FIRST) "myapp_settings" else "myapp_settings_$id"

    /** Where a loadout keeps its recorded clicks and drags. */
    fun actionsFileName(id: String): String = if (id == FIRST) "custom_actions.json" else "custom_actions_$id.json"

    private fun ids(context: Context): List<String> {
        val ids = (prefs(context).getString("ids", null) ?: FIRST).split(",").filter { it.isNotBlank() }
        return if (ids.isEmpty()) listOf(FIRST) else ids
    }

    fun list(context: Context): List<Loadout> {
        val p = prefs(context)
        return ids(context).map { id ->
            Loadout(id, p.getString("name_$id", null) ?: "Loadout $id")
        }
    }

    fun activeId(context: Context): String {
        val ids = ids(context)
        val active = prefs(context).getString("active", FIRST) ?: FIRST
        return if (active in ids) active else ids.first()
    }

    fun activeName(context: Context): String {
        val id = activeId(context)
        return list(context).firstOrNull { it.id == id }?.name ?: "Loadout $id"
    }

    fun setActive(context: Context, id: String) {
        if (id in ids(context)) prefs(context).edit().putString("active", id).apply()
    }

    /** Makes a new loadout as a copy of [copyFrom] (settings, step order and clicks) and returns it. */
    fun create(context: Context, name: String, copyFrom: String): Loadout {
        val p = prefs(context)
        val all = ids(context)
        val next = maxOf(p.getInt("next", 2), (all.mapNotNull { it.toIntOrNull() }.maxOrNull() ?: 1) + 1)
        val id = next.toString()

        // copy the settings
        val src = context.getSharedPreferences(settingsPrefsName(copyFrom), Context.MODE_PRIVATE)
        val dst = context.getSharedPreferences(settingsPrefsName(id), Context.MODE_PRIVATE).edit()
        for ((k, v) in src.all) {
            when (v) {
                is String -> dst.putString(k, v)
                is Int -> dst.putInt(k, v)
                is Long -> dst.putLong(k, v)
                is Boolean -> dst.putBoolean(k, v)
                is Float -> dst.putFloat(k, v)
            }
        }
        dst.apply()

        // copy the recorded clicks and drags
        val from = File(context.filesDir, actionsFileName(copyFrom))
        if (from.exists()) from.copyTo(File(context.filesDir, actionsFileName(id)), overwrite = true)

        val label = name.trim().ifEmpty { "Loadout $id" }
        p.edit()
            .putString("ids", (all + id).joinToString(","))
            .putString("name_$id", label)
            .putInt("next", next + 1)
            .apply()
        return Loadout(id, label)
    }

    fun rename(context: Context, id: String, name: String) {
        val label = name.trim()
        if (label.isNotEmpty() && id in ids(context)) {
            prefs(context).edit().putString("name_$id", label).apply()
        }
    }

    /** Deletes a loadout. The last remaining one can't be deleted. Returns false if it wasn't. */
    fun delete(context: Context, id: String): Boolean {
        val all = ids(context)
        if (all.size <= 1 || id !in all) return false
        val remaining = all - id
        val p = prefs(context)
        val edit = p.edit()
            .putString("ids", remaining.joinToString(","))
            .remove("name_$id")
        if (activeId(context) == id) edit.putString("active", remaining.first())
        edit.apply()
        context.deleteSharedPreferences(settingsPrefsName(id))
        File(context.filesDir, actionsFileName(id)).delete()
        return true
    }
}
