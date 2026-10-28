package com.example.myapp.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.example.myapp.model.GridBox
import com.example.myapp.model.GridProfile
import com.example.myapp.model.ReadStatus
import com.example.myapp.model.Reading
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Reads the number printed on each calibrated tile.
 *
 * 1. Empty cells are skipped: an empty cell is a perfectly flat color, a tile
 *    never is.
 * 2. For each tile we crop the area where the number sits (lower right), and
 *    make three differently-processed copies of it.
 * 3. Each copy goes through on-device text recognition. A number only counts as
 *    "read" when at least two copies agree; one lone answer is "unsure".
 *
 * All sizes are relative to the spacing between tiles, so this works at any
 * screen resolution.
 */
object GridReader {

    // Main window (also the yellow box shown on the result screen),
    // in "cell" units measured from the tile center.
    private const val CROP_LEFT = -0.10f
    private const val CROP_TOP = 0.02f
    private const val CROP_RIGHT = 0.50f
    private const val CROP_BOTTOM = 0.50f

    // Below this brightness variation a cell counts as empty.
    private const val EMPTY_STD = 10.0

    fun read(profile: GridProfile, screenshot: Bitmap, onDone: (List<Reading>) -> Unit) {
        val boxes = profile.allBoxes()
        if (boxes.isEmpty()) {
            onDone(emptyList())
            return
        }

        val sx = screenshot.width.toFloat() / profile.screenWidth
        val sy = screenshot.height.toFloat() / profile.screenHeight
        val cell = estimateCell(boxes, profile.screenWidth * 0.04f) * (sx + sy) / 2f

        val results = arrayOfNulls<Reading>(boxes.size)
        val tilesLeft = AtomicInteger(boxes.size)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        fun finishTile(index: Int, reading: Reading) {
            results[index] = reading
            if (tilesLeft.decrementAndGet() == 0) {
                recognizer.close()
                onDone(results.filterNotNull())
            }
        }

        boxes.forEachIndexed { index, box ->
            val cx = box.x * sx
            val cy = box.y * sy
            val l = max(0f, cx + CROP_LEFT * cell)
            val t = max(0f, cy + CROP_TOP * cell)
            val r = min(screenshot.width.toFloat(), cx + CROP_RIGHT * cell)
            val b = min(screenshot.height.toFloat(), cy + CROP_BOTTOM * cell)

            fun reading(value: Int?, status: ReadStatus, raw: String) =
                Reading(box.id, cx, cy, value, status, raw, l, t, r, b)

            if (isEmpty(screenshot, cx, cy, cell)) {
                finishTile(index, reading(null, ReadStatus.EMPTY, ""))
                return@forEachIndexed
            }

            val variants = makeVariants(screenshot, cx, cy, cell)
            if (variants.isEmpty()) {
                finishTile(index, reading(null, ReadStatus.UNREADABLE, "(outside screen)"))
                return@forEachIndexed
            }

            val texts = arrayOfNulls<String>(variants.size)
            val variantsLeft = AtomicInteger(variants.size)

            variants.forEachIndexed { vi, bitmap ->
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnCompleteListener { task ->
                        texts[vi] = if (task.isSuccessful) task.result?.text ?: "" else ""
                        if (variantsLeft.decrementAndGet() == 0) {
                            val votes = texts.mapNotNull { parse(it ?: "") }
                            val raw = texts.joinToString(" | ") {
                                (it ?: "").replace("\n", " ").trim()
                            }
                            if (votes.isEmpty()) {
                                finishTile(index, reading(null, ReadStatus.UNREADABLE, raw))
                            } else {
                                val top = votes.groupingBy { it }.eachCount()
                                    .maxByOrNull { it.value }!!
                                val status =
                                    if (top.value >= 2) ReadStatus.READ else ReadStatus.LOW_CONFIDENCE
                                finishTile(index, reading(top.key, status, raw))
                            }
                        }
                    }
            }
        }
    }

    /** The ids of calibrated boxes that currently have no tile. */
    fun emptyIds(profile: GridProfile, screenshot: Bitmap): Set<Int> {
        val boxes = profile.allBoxes()
        if (boxes.isEmpty()) return emptySet()
        val sx = screenshot.width.toFloat() / profile.screenWidth
        val sy = screenshot.height.toFloat() / profile.screenHeight
        val cell = estimateCell(boxes, profile.screenWidth * 0.04f) * (sx + sy) / 2f
        return boxes.filter { isEmpty(screenshot, it.x * sx, it.y * sy, cell) }.map { it.id }.toSet()
    }

    /** How many calibrated cells currently have no tile (fast: no text reading involved). */
    fun countEmpty(profile: GridProfile, screenshot: Bitmap): Int {
        val boxes = profile.allBoxes()
        if (boxes.isEmpty()) return 0
        val sx = screenshot.width.toFloat() / profile.screenWidth
        val sy = screenshot.height.toFloat() / profile.screenHeight
        val cell = estimateCell(boxes, profile.screenWidth * 0.04f) * (sx + sy) / 2f
        return boxes.count { isEmpty(screenshot, it.x * sx, it.y * sy, cell) }
    }

    /**
     * A tile number is 1 or 2 digits. Anything longer (e.g. "389") means stray
     * pixels were read as extra digits, so we treat it as unreadable rather
     * than guess.
     */
    private fun parse(raw: String): Int? {
        val tokens = raw.filter { it.isDigit() || it.isWhitespace() }
            .trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val digits = tokens.joinToString("")
        if (digits.isEmpty() || digits.length > 2) return null
        val value = digits.toIntOrNull() ?: return null
        return if (value in 1..99) value else null
    }

    /** Three differently-processed crops of the number area. */
    private fun makeVariants(src: Bitmap, cx: Float, cy: Float, cell: Float): List<Bitmap> {
        val list = mutableListOf<Bitmap>()

        // 1) main window, just enlarged
        crop(src, cx, cy, cell, CROP_LEFT, CROP_TOP, CROP_RIGHT, CROP_BOTTOM)?.let {
            list.add(scale(it, 4))
        }
        // 2) main window, grayscale with stretched contrast, with a margin
        crop(src, cx, cy, cell, CROP_LEFT, CROP_TOP, CROP_RIGHT, CROP_BOTTOM)?.let {
            list.add(pad(stretch(scale(it, 5), false)))
        }
        // 3) window nudged down-right (forgives a slightly off calibration), inverted
        crop(src, cx, cy, cell, -0.05f, 0.05f, 0.55f, 0.55f)?.let {
            list.add(pad(stretch(scale(it, 5), true)))
        }
        return list
    }

    private fun crop(
        src: Bitmap, cx: Float, cy: Float, cell: Float,
        l: Float, t: Float, r: Float, b: Float
    ): Bitmap? {
        val left = max(0f, cx + l * cell)
        val top = max(0f, cy + t * cell)
        val right = min(src.width.toFloat(), cx + r * cell)
        val bottom = min(src.height.toFloat(), cy + b * cell)
        val w = (right - left).toInt()
        val h = (bottom - top).toInt()
        if (w < 4 || h < 4) return null
        return Bitmap.createBitmap(src, left.toInt(), top.toInt(), w, h)
    }

    private fun scale(src: Bitmap, factor: Int): Bitmap =
        Bitmap.createScaledBitmap(src, src.width * factor, src.height * factor, true)

    /** Grayscale + stretch so the darkest/brightest 2% map to black/white. */
    private fun stretch(src: Bitmap, invert: Boolean): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)

        val lum = IntArray(px.size)
        val hist = IntArray(256)
        for (i in px.indices) {
            val c = px[i]
            val l = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
            lum[i] = l
            hist[l]++
        }

        val total = px.size
        var acc = 0
        var lo = 0
        for (v in 0..255) {
            acc += hist[v]
            if (acc >= total * 0.02) { lo = v; break }
        }
        acc = 0
        var hi = 255
        for (v in 0..255) {
            acc += hist[v]
            if (acc >= total * 0.98) { hi = v; break }
        }
        if (hi - lo < 16) hi = lo + 16

        val out = IntArray(px.size)
        for (i in px.indices) {
            var v = ((lum[i] - lo) * 255 / (hi - lo)).coerceIn(0, 255)
            if (invert) v = 255 - v
            out[i] = Color.rgb(v, v, v)
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Adds a plain margin; text recognition does better with space around the text. */
    private fun pad(src: Bitmap, margin: Int = 24): Bitmap {
        val out = Bitmap.createBitmap(
            src.width + 2 * margin, src.height + 2 * margin, Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(out)
        canvas.drawColor(src.getPixel(0, 0))
        canvas.drawBitmap(src, margin.toFloat(), margin.toFloat(), null)
        return out
    }

    /** True when the middle of the cell is a flat color (no tile drawn there). */
    private fun isEmpty(src: Bitmap, cx: Float, cy: Float, cell: Float): Boolean {
        val half = 0.30f * cell
        val x1 = max(0, (cx - half).toInt())
        val y1 = max(0, (cy - half).toInt())
        val x2 = min(src.width, (cx + half).toInt())
        val y2 = min(src.height, (cy + half).toInt())
        val w = x2 - x1
        val h = y2 - y1
        if (w < 4 || h < 4) return false

        val px = IntArray(w * h)
        src.getPixels(px, 0, w, x1, y1, w, h)
        var sum = 0.0
        var sumSq = 0.0
        for (c in px) {
            val l = Color.red(c) * 0.299 + Color.green(c) * 0.587 + Color.blue(c) * 0.114
            sum += l
            sumSq += l * l
        }
        val n = px.size.toDouble()
        val mean = sum / n
        val variance = max(0.0, sumSq / n - mean * mean)
        return sqrt(variance) < EMPTY_STD
    }

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
