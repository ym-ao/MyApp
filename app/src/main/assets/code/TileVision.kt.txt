package com.example.myapp.ocr

import android.graphics.Bitmap
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Finds the number inside a tile's box, whatever the calibration error, and cuts out
 * an aligned picture of it. Your calibrated points are never exactly on the tile
 * centres (a few pixels off, and differently off in different boxes), and a few
 * pixels is enough to wreck a picture comparison. So we look at a generous area
 * around the point and pick the spot with the most bright, sharp-edged detail in the
 * region where the game prints numbers (white or yellow digits with a dark outline).
 * Tested on a real board: the same level in different boxes matches at 0.94+ even
 * with boxes 8 pixels off, while different levels stay below 0.9.
 */
object TileVision {

    private const val S = 60f              // pixels per cell, after resizing
    private const val SX1 = -0.35f
    private const val SY1 = -0.25f
    private const val SX2 = 0.80f
    private const val SY2 = 0.80f
    private const val SW = 69              // (SX2 - SX1) * S
    private const val SH = 63              // (SY2 - SY1) * S
    private const val LW = 26              // size of a two-digit number
    private const val LH = 19

    const val TW = 36                      // size of the aligned picture that is stored and compared
    const val TH = 28

    // where the centre of the number may be, in cells from the tile centre
    private const val CX_MIN = 0.08f
    private const val CX_MAX = 0.45f
    private const val CY_MIN = 0.12f
    private const val CY_MAX = 0.50f
    private const val BRIGHT = 170f

    class Look(
        val thumb: Bitmap,
        /** [0] is the aligned picture; the others are the same picture shifted one pixel in each direction. */
        val patches: Array<FloatArray>,
        /** Where the number was found, in cells from the tile centre. */
        val centerX: Float,
        val centerY: Float
    )

    fun look(src: Bitmap, cx: Float, cy: Float, cell: Float): Look? {
        val left = cx + SX1 * cell
        val top = cy + SY1 * cell
        val right = cx + SX2 * cell
        val bottom = cy + SY2 * cell
        if (left < 0f || top < 0f || right > src.width || bottom > src.height) return null

        val crop = Bitmap.createBitmap(
            src, left.toInt(), top.toInt(),
            (right - left).toInt().coerceAtLeast(8), (bottom - top).toInt().coerceAtLeast(8)
        )
        val search = Bitmap.createScaledBitmap(crop, SW, SH, true)
        return lookIn(search)
    }

    /**
     * Brings a picture saved by the first version of the library (the plain number window,
     * not aligned) up to date so it can be compared with the new aligned ones.
     */
    fun fromLegacy(old: Bitmap): Bitmap? {
        val offX = ((-0.10f - SX1) * S).roundToInt()
        val offY = ((0.02f - SY1) * S).roundToInt()
        val ow = old.width
        val oh = old.height
        val op = IntArray(ow * oh)
        old.getPixels(op, 0, ow, 0, 0, ow, oh)
        val out = IntArray(SW * SH)
        for (y in 0 until SH) {
            val sy = (y - offY).coerceIn(0, oh - 1)
            for (x in 0 until SW) {
                val sx = (x - offX).coerceIn(0, ow - 1)
                out[y * SW + x] = op[sy * ow + sx]
            }
        }
        val search = Bitmap.createBitmap(out, SW, SH, Bitmap.Config.ARGB_8888)
        return lookIn(search)?.thumb
    }

    private fun lookIn(search: Bitmap): Look? {
        val lum = luminance(search)
        val found = locate(lum) ?: return null
        val x0 = (found.first - TW / 2f).roundToInt().coerceIn(0, SW - TW)
        val y0 = (found.second - TH / 2f).roundToInt().coerceIn(0, SH - TH)
        val thumb = Bitmap.createBitmap(search, x0, y0, TW, TH)

        val patches = ArrayList<FloatArray>(9)
        patches.add(patch(lum, x0, y0))
        for (dy in -1..1) {
            for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val px = (x0 + dx).coerceIn(0, SW - TW)
                val py = (y0 + dy).coerceIn(0, SH - TH)
                patches.add(patch(lum, px, py))
            }
        }
        return Look(thumb, patches.toTypedArray(), found.first / S + SX1, found.second / S + SY1)
    }

    private fun luminance(bmp: Bitmap): FloatArray {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return FloatArray(px.size) {
            val c = px[it]
            ((c shr 16) and 0xFF) * 0.299f + ((c shr 8) and 0xFF) * 0.587f + (c and 0xFF) * 0.114f
        }
    }

    /** A TW x TH piece of the brightness picture, average removed and scaled to length 1. */
    private fun patch(lum: FloatArray, x0: Int, y0: Int): FloatArray {
        val v = FloatArray(TW * TH)
        var sum = 0f
        for (y in 0 until TH) {
            for (x in 0 until TW) {
                val value = lum[(y0 + y) * SW + (x0 + x)]
                v[y * TW + x] = value
                sum += value
            }
        }
        return normalize(v, sum)
    }

    /** Same normalisation, for a picture that is already TW x TH. */
    fun featureOf(thumb: Bitmap): FloatArray {
        val l = luminance(thumb)
        var sum = 0f
        for (value in l) sum += value
        return normalize(l, sum)
    }

    private fun normalize(v: FloatArray, sum: Float): FloatArray {
        val mean = sum / v.size
        var sq = 0f
        for (i in v.indices) {
            v[i] -= mean
            sq += v[i] * v[i]
        }
        val norm = kotlin.math.sqrt(sq) + 1e-6f
        for (i in v.indices) v[i] /= norm
        return v
    }

    /** Centre (in search-picture pixels) of the busiest bright, sharp-edged spot where a number could be. */
    private fun locate(lum: FloatArray): Pair<Float, Float>? {
        val mag = FloatArray(SW * SH)
        for (y in 0 until SH) {
            for (x in 0 until SW) {
                val i = y * SW + x
                val gx = when (x) {
                    0 -> lum[i + 1] - lum[i]
                    SW - 1 -> lum[i] - lum[i - 1]
                    else -> (lum[i + 1] - lum[i - 1]) / 2f
                }
                val gy = when (y) {
                    0 -> lum[i + SW] - lum[i]
                    SH - 1 -> lum[i] - lum[i - SW]
                    else -> (lum[i + SW] - lum[i - SW]) / 2f
                }
                // only count edges that have something bright right next to them
                var brightest = 0f
                for (dy in -1..1) {
                    val yy = (y + dy).coerceIn(0, SH - 1)
                    for (dx in -1..1) {
                        val xx = (x + dx).coerceIn(0, SW - 1)
                        val v = lum[yy * SW + xx]
                        if (v > brightest) brightest = v
                    }
                }
                mag[i] = if (brightest > BRIGHT) hypot(gx, gy) else 0f
            }
        }

        val stride = SW + 1
        val ii = DoubleArray(stride * (SH + 1))
        for (y in 0 until SH) {
            var row = 0.0
            for (x in 0 until SW) {
                row += mag[y * SW + x]
                ii[(y + 1) * stride + x + 1] = ii[y * stride + x + 1] + row
            }
        }

        var best = -1.0
        var bx = 0
        var by = 0
        for (y in 0..(SH - LH)) {
            for (x in 0..(SW - LW)) {
                val cxs = (x + LW / 2f) / S + SX1
                val cys = (y + LH / 2f) / S + SY1
                if (cxs <= CX_MIN || cxs >= CX_MAX || cys <= CY_MIN || cys >= CY_MAX) continue
                val e = ii[(y + LH) * stride + x + LW] - ii[y * stride + x + LW] -
                    ii[(y + LH) * stride + x] + ii[y * stride + x]
                if (e > best) {
                    best = e
                    bx = x
                    by = y
                }
            }
        }
        if (best <= 0.0) return null
        return Pair(bx + LW / 2f, by + LH / 2f)
    }
}
