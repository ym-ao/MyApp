package com.example.myapp.ocr

import android.graphics.Bitmap
import com.example.myapp.model.GridBox
import com.example.myapp.model.GridProfile
import com.example.myapp.model.Reading
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Reads the number printed on each calibrated tile.
 *
 * Calibrated points are tile CENTERS. In Sushi Station the tier number sits in
 * the lower-right of each tile, so we crop a small window there (sized relative
 * to the spacing between tiles, so it works at any screen resolution), enlarge
 * it, and run on-device text recognition on it.
 */
object GridReader {

    private const val UPSCALE = 4

    // Crop window, in "cell" units (1.0 = distance between neighbouring tiles),
    // measured from the tile center. Tweak these if the numbers get clipped.
    private const val CROP_LEFT = -0.10f
    private const val CROP_TOP = 0.02f
    private const val CROP_RIGHT = 0.50f
    private const val CROP_BOTTOM = 0.50f

    fun read(profile: GridProfile, screenshot: Bitmap, onDone: (List<Reading>) -> Unit) {
        val boxes = profile.allBoxes()
        if (boxes.isEmpty()) {
            onDone(emptyList())
            return
        }

        val sx = screenshot.width.toFloat() / profile.screenWidth
        val sy = screenshot.height.toFloat() / profile.screenHeight
        val cell = estimateCell(boxes, profile.screenWidth * 0.04f) * (sx + sy) / 2f
        val w = screenshot.width.toFloat()
        val h = screenshot.height.toFloat()

        val results = arrayOfNulls<Reading>(boxes.size)
        val remaining = AtomicInteger(boxes.size)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        fun complete(index: Int, reading: Reading) {
            results[index] = reading
            if (remaining.decrementAndGet() == 0) {
                recognizer.close()
                onDone(results.filterNotNull())
            }
        }

        boxes.forEachIndexed { index, box ->
            val cx = box.x * sx
            val cy = box.y * sy
            val l = max(0f, cx + CROP_LEFT * cell)
            val t = max(0f, cy + CROP_TOP * cell)
            val r = min(w, cx + CROP_RIGHT * cell)
            val b = min(h, cy + CROP_BOTTOM * cell)
            val cw = (r - l).toInt()
            val ch = (b - t).toInt()

            if (cw < 4 || ch < 4) {
                complete(index, Reading(box.id, cx, cy, null, "(outside screen)", l, t, r, b))
                return@forEachIndexed
            }

            val crop = Bitmap.createBitmap(screenshot, l.toInt(), t.toInt(), cw, ch)
            val big = Bitmap.createScaledBitmap(crop, cw * UPSCALE, ch * UPSCALE, true)

            recognizer.process(InputImage.fromBitmap(big, 0))
                .addOnSuccessListener { text ->
                    val raw = text.text.replace("\n", " ").trim()
                    complete(index, Reading(box.id, cx, cy, parse(raw), raw, l, t, r, b))
                }
                .addOnFailureListener {
                    complete(index, Reading(box.id, cx, cy, null, "(error)", l, t, r, b))
                }
        }
    }

    /** First run of 1-3 digits in the recognized text, or null. */
    private fun parse(raw: String): Int? =
        Regex("\\d{1,3}").find(raw.replace(" ", ""))?.value?.toIntOrNull()

    /** Typical distance between neighbouring tiles (median nearest-neighbour distance). */
    private fun estimateCell(boxes: List<GridBox>, fallback: Float): Float {
        if (boxes.size < 2) return fallback
        val nearest = boxes.map { a ->
            boxes.filter { it.id != a.id }
                .minOf { b -> hypot((a.x - b.x).toFloat(), (a.y - b.y).toFloat()) }
        }.sorted()
        val median = nearest[nearest.size / 2]
        return if (median < 10f) fallback else median
    }
}
