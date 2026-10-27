package com.example.myapp.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import com.example.myapp.model.GridBox

enum class CalibrationMode { MANUAL, FAST_GRID }

/**
 * Sits on top of an ImageView showing the calibration screenshot.
 *
 * MANUAL mode: every tap adds one box, in the order tapped.
 *
 * FAST_GRID mode: the first two taps set the top-left and bottom-right
 * cell centers of a uniform grid; call generateFastGrid(rows, cols) to
 * fill in every evenly-spaced position between them.
 *
 * All box coordinates are in the original bitmap's pixel space, which
 * — since the screenshot was taken at native device resolution — is the
 * same coordinate space the Accessibility Service uses for taps/drags.
 */
class GridOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var imageView: ImageView? = null
    var mode: CalibrationMode = CalibrationMode.MANUAL

    /** When true, each tap replaces the previous one (used to record a single click point). */
    var singlePoint: Boolean = false

    /** Called after every tap that changes the marks (lets a screen update its status line). */
    var onChanged: (() -> Unit)? = null

    val boxes = mutableListOf<GridBox>()
    private var nextId = 1

    // FAST_GRID anchors, in bitmap pixel space
    private val anchors = mutableListOf<Pair<Float, Float>>()

    private val fillPaint = Paint().apply {
        color = Color.parseColor("#CCE53935")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val ringPaint = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }
    private val anchorPaint = Paint().apply {
        color = Color.parseColor("#CC2196F3")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 30f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return true
        val iv = imageView ?: return true
        val inverse = Matrix()
        if (!iv.imageMatrix.invert(inverse)) return true

        val pts = floatArrayOf(event.x, event.y)
        inverse.mapPoints(pts)

        when (mode) {
            CalibrationMode.MANUAL -> {
                if (singlePoint) {
                    boxes.clear()
                    nextId = 1
                }
                boxes.add(GridBox(nextId++, pts[0].toInt(), pts[1].toInt()))
            }
            CalibrationMode.FAST_GRID -> {
                if (anchors.size >= 2) anchors.clear()
                anchors.add(pts[0] to pts[1])
            }
        }
        invalidate()
        onChanged?.invoke()
        return true
    }

    /** Call once two anchors are set, with the desired row/column count. */
    fun generateFastGrid(rows: Int, cols: Int): Boolean {
        if (anchors.size < 2 || rows < 1 || cols < 1) return false
        val (x1, y1) = anchors[0]
        val (x2, y2) = anchors[1]

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val fx = if (cols == 1) x1 else x1 + (x2 - x1) * c / (cols - 1)
                val fy = if (rows == 1) y1 else y1 + (y2 - y1) * r / (rows - 1)
                boxes.add(GridBox(nextId++, fx.toInt(), fy.toInt()))
            }
        }
        anchors.clear()
        invalidate()
        return true
    }

    fun undoLast() {
        if (boxes.isNotEmpty()) {
            boxes.removeAt(boxes.size - 1)
            invalidate()
        }
    }

    /** Clears the current region's boxes only — earlier committed regions in this
     *  profile keep their IDs, so nextId is NOT reset here. */
    fun clearAll() {
        boxes.clear()
        anchors.clear()
        invalidate()
    }

    /** Full reset for a brand-new screenshot/profile — this is the only place
     *  the ID counter goes back to 1. */
    fun fullReset() {
        boxes.clear()
        anchors.clear()
        nextId = 1
        invalidate()
    }

    /**
     * Clears this region's boxes to start the next one. Deliberately does NOT
     * reset nextId — box IDs must stay unique across every region in the same
     * profile, since later functions reference a box by its ID alone.
     */
    fun resetForNewRegion() {
        boxes.clear()
        anchors.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val iv = imageView ?: return
        val matrix = iv.imageMatrix

        for (box in boxes) {
            val pts = floatArrayOf(box.x.toFloat(), box.y.toFloat())
            matrix.mapPoints(pts)
            canvas.drawCircle(pts[0], pts[1], 24f, fillPaint)
            canvas.drawCircle(pts[0], pts[1], 24f, ringPaint)
            canvas.drawText(box.id.toString(), pts[0], pts[1] + 10f, textPaint)
        }

        anchors.forEachIndexed { i, (ax, ay) ->
            val pts = floatArrayOf(ax, ay)
            matrix.mapPoints(pts)
            canvas.drawRect(pts[0] - 18f, pts[1] - 18f, pts[0] + 18f, pts[1] + 18f, anchorPaint)
            canvas.drawText(if (i == 0) "TL" else "BR", pts[0], pts[1] + 10f, textPaint)
        }
    }
}
