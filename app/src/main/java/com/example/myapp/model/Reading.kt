package com.example.myapp.model

/**
 * What OCR saw at one calibrated box. All coordinates are in the pixel space
 * of the screenshot that was read (so they can be drawn straight onto it).
 * value is null when no number could be read.
 */
data class Reading(
    val id: Int,
    val x: Float,
    val y: Float,
    val value: Int?,
    val raw: String,
    val cropLeft: Float,
    val cropTop: Float,
    val cropRight: Float,
    val cropBottom: Float
)
