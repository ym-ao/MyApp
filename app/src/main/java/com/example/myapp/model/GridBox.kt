package com.example.myapp.model

/**
 * A single box position on the game grid, in real device-screen pixel
 * coordinates (same space the Accessibility Service uses for taps/drags).
 */
data class GridBox(
    val id: Int,
    val x: Int,
    val y: Int
)
