package com.example.myapp.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import com.example.myapp.model.ReadStatus
import com.example.myapp.model.Reading

/**
 * Draws OCR results on top of the screenshot that was read: a thin yellow box
 * for the area that was scanned, and at each tile center: the number in green
 * (confident), orange (unsure), a red "?" (tile there but unreadable), or a grey
 * dash (empty cell).
 */
class ResultOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var imageView: ImageView? = null

    var readings: List<Reading> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    private val cropPaint = Paint().apply {
        color = Color.parseColor("#99FFEB3B")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        isAntiAlias = true
    }
    private val textPaint = Paint().apply {
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val iv = imageView ?: return
        val matrix = iv.imageMatrix
        val values = FloatArray(9)
        matrix.getValues(values)
        val scale = values[Matrix.MSCALE_X]
        textPaint.textSize = 24f * scale

        for (r in readings) {
            val center = floatArrayOf(r.x, r.y)
            matrix.mapPoints(center)

            if (r.status == ReadStatus.EMPTY) {
                textPaint.color = Color.GRAY
                canvas.drawText("-", center[0], center[1] + textPaint.textSize / 3f, textPaint)
                continue
            }

            val rect = floatArrayOf(r.cropLeft, r.cropTop, r.cropRight, r.cropBottom)
            matrix.mapPoints(rect)
            canvas.drawRect(rect[0], rect[1], rect[2], rect[3], cropPaint)

            textPaint.color = when (r.status) {
                ReadStatus.READ -> Color.GREEN
                ReadStatus.LOW_CONFIDENCE -> Color.parseColor("#FFA000")
                else -> Color.RED
            }
            canvas.drawText(
                r.value?.toString() ?: "?",
                center[0],
                center[1] + textPaint.textSize / 3f,
                textPaint
            )
        }
    }
}
