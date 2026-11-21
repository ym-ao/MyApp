package com.example.myapp.storage

import android.content.Context
import java.text.DateFormat
import java.util.Date

/** Remembers why the last run ended, so the reason is still there after the toast has gone. */
object RunLog {

    private const val PREFS = "myapp_runlog"

    fun save(context: Context, message: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("message", message)
            .putLong("at", System.currentTimeMillis())
            .apply()
    }

    /** "Last run ended 20:41:03: Stopped after ..." or null if nothing has run yet. */
    fun describe(context: Context): String? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val message = p.getString("message", null) ?: return null
        val at = DateFormat.getTimeInstance().format(Date(p.getLong("at", 0L)))
        return "Last run ended $at: $message"
    }
}
