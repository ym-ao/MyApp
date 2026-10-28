package com.example.myapp.storage

import android.content.Context
import java.io.File

/** Holds a "preview only" plan until the main screen has shown it once. */
object PlanStore {

    private fun file(context: Context) = File(context.filesDir, "pending_plan.txt")

    fun save(context: Context, text: String) {
        file(context).writeText(text)
    }

    fun takePending(context: Context): String? {
        val f = file(context)
        if (!f.exists()) return null
        val text = f.readText()
        f.delete()
        return text
    }
}
