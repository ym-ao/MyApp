package com.example.myapp.model

/** What happened when we tried to read one tile. */
enum class ReadStatus {
    READ,            // at least two reading attempts agreed on the number
    LOW_CONFIDENCE,  // only one attempt produced a number
    EMPTY,           // no tile in this cell
    UNREADABLE       // a tile is there but no number could be read
}

/**
 * The result for one calibrated box. All coordinates are in the pixel space of
 * the screenshot that was read, so they can be drawn straight onto it.
 * value is null unless status is READ or LOW_CONFIDENCE.
 */
data class Reading(
    val id: Int,
    val x: Float,
    val y: Float,
    val value: Int?,
    val status: ReadStatus,
    val raw: String,
    val cropLeft: Float,
    val cropTop: Float,
    val cropRight: Float,
    val cropBottom: Float
)
