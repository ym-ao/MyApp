package com.example.myapp.storage

import android.content.Context

enum class StepType { MERGE, SORT, SUMMON, CLICK }

data class StepConfig(val type: StepType, val enabled: Boolean)

/** Everything the loop and the test buttons need, saved on the phone. */
data class AppSettings(
    val grid: String = "",
    val summonPoint: String = "",
    val extraPoint: String = "",
    val dragMs: Long = 150L,
    val pauseMs: Long = 250L,
    val swap: Boolean = true,
    val verify: Boolean = true,
    val previewOnly: Boolean = false,
    val maxHoldSeconds: Int = 30,
    val loops: Int = 0,          // 0 = keep going until stopped or nothing is left to do
    val loopPauseMs: Long = 1500L,
    val steps: List<StepConfig> = defaultSteps()
) {
    companion object {
        fun defaultSteps() = listOf(
            StepConfig(StepType.MERGE, true),
            StepConfig(StepType.SORT, true),
            StepConfig(StepType.SUMMON, true),
            StepConfig(StepType.CLICK, false)
        )
    }
}

object SettingsStore {

    private const val PREFS = "myapp_settings"

    fun load(context: Context): AppSettings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = AppSettings()
        return AppSettings(
            grid = p.getString("grid", d.grid) ?: d.grid,
            summonPoint = p.getString("summonPoint", d.summonPoint) ?: d.summonPoint,
            extraPoint = p.getString("extraPoint", d.extraPoint) ?: d.extraPoint,
            dragMs = p.getLong("dragMs", d.dragMs),
            pauseMs = p.getLong("pauseMs", d.pauseMs),
            swap = p.getBoolean("swap", d.swap),
            verify = p.getBoolean("verify", d.verify),
            previewOnly = p.getBoolean("previewOnly", d.previewOnly),
            maxHoldSeconds = p.getInt("maxHoldSeconds", d.maxHoldSeconds),
            loops = p.getInt("loops", d.loops),
            loopPauseMs = p.getLong("loopPauseMs", d.loopPauseMs),
            steps = parseSteps(p.getString("steps", null))
        )
    }

    fun save(context: Context, s: AppSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("grid", s.grid)
            .putString("summonPoint", s.summonPoint)
            .putString("extraPoint", s.extraPoint)
            .putLong("dragMs", s.dragMs)
            .putLong("pauseMs", s.pauseMs)
            .putBoolean("swap", s.swap)
            .putBoolean("verify", s.verify)
            .putBoolean("previewOnly", s.previewOnly)
            .putInt("maxHoldSeconds", s.maxHoldSeconds)
            .putInt("loops", s.loops)
            .putLong("loopPauseMs", s.loopPauseMs)
            .putString("steps", s.steps.joinToString(",") { "${it.type.name}:${if (it.enabled) 1 else 0}" })
            .apply()
    }

    /** "MERGE:1,SORT:1,..." back into a list; missing steps are added switched off. */
    private fun parseSteps(text: String?): List<StepConfig> {
        if (text.isNullOrBlank()) return AppSettings.defaultSteps()
        val result = mutableListOf<StepConfig>()
        for (part in text.split(",")) {
            val bits = part.split(":")
            if (bits.size != 2) continue
            val type = runCatching { StepType.valueOf(bits[0]) }.getOrNull() ?: continue
            if (result.any { it.type == type }) continue
            result.add(StepConfig(type, bits[1] == "1"))
        }
        for (type in StepType.values()) {
            if (result.none { it.type == type }) result.add(StepConfig(type, false))
        }
        return result
    }
}
