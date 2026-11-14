package com.example.myapp.storage

import android.content.Context
import android.content.SharedPreferences
import kotlin.random.Random

enum class StepType { MERGE, SORT, SUMMON, CLICK, CUSTOM }

data class StepConfig(val type: StepType, val enabled: Boolean)

/**
 * A time in milliseconds, either fixed (min = max) or a range. Every time it is used,
 * [pick] gives a fixed value or a new random value between min and max, so repeated
 * actions don't all take exactly the same time.
 */
data class MsRange(val min: Long, val max: Long) {
    fun pick(): Long = if (max <= min) min else Random.nextLong(min, max + 1)

    override fun toString(): String = if (max <= min) "$min ms" else "$min to $max ms"
}

/** Everything the loop and the test buttons need, saved on the phone. All times are milliseconds. */
data class AppSettings(
    val grid: String = "",
    val summonPoint: String = "",
    val extraPoint: String = "",
    val drag: MsRange = MsRange(150L, 150L),           // how long each drag takes
    val pause: MsRange = MsRange(250L, 250L),          // pause after each move, also the delay per merge
    val swap: Boolean = true,
    val verify: Boolean = true,
    val sortPasses: Int = 3,                           // sort passes per loop; the board is read again between passes
    val sortMovesPerPass: Int = 20,                    // most drags in one pass (0 = all of them)
    val rereadMerge: Boolean = false,                  // read the board again after every merge (slower; off = plan the whole chain from one scan)
    val previewOnly: Boolean = false,
    val maxHold: MsRange = MsRange(30000L, 30000L),    // longest summon hold
    val loops: Int = 0,                                // 0 = keep going until stopped or nothing is left to do
    val loopPause: MsRange = MsRange(1500L, 1500L),    // pause between loops
    val countdown: MsRange = MsRange(5000L, 5000L),    // wait before a test button starts, to switch to the game
    val steps: List<StepConfig> = defaultSteps()
) {
    companion object {
        fun defaultSteps() = listOf(
            StepConfig(StepType.MERGE, true),
            StepConfig(StepType.SORT, true),
            StepConfig(StepType.SUMMON, true),
            StepConfig(StepType.CLICK, false),
            StepConfig(StepType.CUSTOM, false)
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
            drag = range(p, "drag", "dragMs", 1L, d.drag),
            pause = range(p, "pause", "pauseMs", 1L, d.pause),
            swap = p.getBoolean("swap", d.swap),
            verify = p.getBoolean("verify", d.verify),
            sortPasses = p.getInt("sortPasses", d.sortPasses),
            sortMovesPerPass = p.getInt("sortMovesPerPass", d.sortMovesPerPass),
            rereadMerge = p.getBoolean("mergeReread", d.rereadMerge),
            previewOnly = p.getBoolean("previewOnly", d.previewOnly),
            maxHold = range(p, "maxHold", "maxHoldSeconds", 1000L, d.maxHold),
            loops = p.getInt("loops", d.loops),
            loopPause = range(p, "loopPause", "loopPauseMs", 1L, d.loopPause),
            countdown = range(p, "countdown", null, 1L, d.countdown),
            steps = parseSteps(p.getString("steps", null))
        )
    }

    fun save(context: Context, s: AppSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("grid", s.grid)
            .putString("summonPoint", s.summonPoint)
            .putString("extraPoint", s.extraPoint)
            .putLong("dragMin", s.drag.min).putLong("dragMax", s.drag.max)
            .putLong("pauseMin", s.pause.min).putLong("pauseMax", s.pause.max)
            .putBoolean("swap", s.swap)
            .putBoolean("verify", s.verify)
            .putInt("sortPasses", s.sortPasses)
            .putInt("sortMovesPerPass", s.sortMovesPerPass)
            .putBoolean("mergeReread", s.rereadMerge)
            .putBoolean("previewOnly", s.previewOnly)
            .putLong("maxHoldMin", s.maxHold.min).putLong("maxHoldMax", s.maxHold.max)
            .putInt("loops", s.loops)
            .putLong("loopPauseMin", s.loopPause.min).putLong("loopPauseMax", s.loopPause.max)
            .putLong("countdownMin", s.countdown.min).putLong("countdownMax", s.countdown.max)
            .putString("steps", s.steps.joinToString(",") { "${it.type.name}:${if (it.enabled) 1 else 0}" })
            .apply()
    }

    /**
     * Reads "<key>Min" and "<key>Max". Settings saved by older versions used one value under
     * [oldKey] (in milliseconds, or seconds when [oldScale] is 1000); that becomes a fixed range.
     */
    private fun range(p: SharedPreferences, key: String, oldKey: String?, oldScale: Long, default: MsRange): MsRange {
        var old: Long? = null
        if (oldKey != null && p.contains(oldKey)) {
            old = when (val v = p.all[oldKey]) {
                is Int -> v.toLong() * oldScale
                is Long -> v * oldScale
                else -> null
            }
        }
        val min = p.getLong("${key}Min", old ?: default.min)
        val max = p.getLong("${key}Max", old ?: default.max)
        return MsRange(min, if (max < min) min else max)
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
