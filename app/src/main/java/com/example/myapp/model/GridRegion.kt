package com.example.myapp.model

/**
 * One uniform chunk of a board — e.g. "main_board" (15x5) or "overflow"
 * (a 1x3 strip elsewhere on screen). A GridProfile is made of one or
 * more of these, since real game boards are rarely a single rectangle.
 */
data class GridRegion(
    val name: String,
    val boxes: MutableList<GridBox> = mutableListOf()
)
