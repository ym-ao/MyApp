package com.example.myapp.model

/**
 * One saved spot to press, e.g. "summon". x/y are real screen pixels;
 * screenWidth/screenHeight record the screen the screenshot came from so the
 * point can be scaled or rejected if the orientation differs later.
 */
data class ClickPoint(
    val name: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val x: Int,
    val y: Int
)
