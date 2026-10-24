package com.example.myapp.model

/**
 * A named, saved board layout — e.g. "sushi_board". Made of one or more
 * regions, since most real boards are several uniform sections rather
 * than one single grid (e.g. the main board + a separate overflow strip).
 *
 * screenWidth/screenHeight record the device screen the calibration
 * screenshot was taken on, so we can warn if it's reused on a
 * different device/resolution later.
 */
data class GridProfile(
    val name: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val regions: MutableList<GridRegion> = mutableListOf()
) {
    /** All boxes across all regions, for code that just needs every position. */
    fun allBoxes(): List<GridBox> = regions.flatMap { it.boxes }
}
