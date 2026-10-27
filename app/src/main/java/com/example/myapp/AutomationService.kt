package com.example.myapp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.example.myapp.model.ClickPoint
import com.example.myapp.model.GridProfile
import com.example.myapp.model.ReadStatus
import com.example.myapp.ocr.GridReader
import com.example.myapp.storage.ClickPointStorage
import com.example.myapp.storage.GridStorage
import com.example.myapp.storage.LastReadStore

/**
 * The part of the app that is allowed to look at the screen and perform
 * taps/drags. Android only lets this run after YOU switch it on in
 * Settings > Accessibility. It does nothing until the app asks it to.
 */
class AutomationService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AutomationService? = null
            private set

        fun isRunning(): Boolean = instance != null

        /** Length of each piece of a long press, in milliseconds. */
        private const val HOLD_SEGMENT_MS = 1100L
    }

    // True from the moment a hold-summon is started until it ends (including its countdown).
    @Volatile private var holdActive = false
    @Volatile private var stopRequested = false

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used: we only act when the app asks us to.
    }

    override fun onInterrupt() {}

    /** While a hold is running, Volume Down is an emergency stop (and doesn't change the volume). */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (holdActive && event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.action == KeyEvent.ACTION_DOWN) stopRequested = true
            return true
        }
        return false
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        holdActive = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // ---- gestures (used by the sort / click / merge functions later) ----

    fun tap(x: Float, y: Float, durationMs: Long = 50L, onDone: ((Boolean) -> Unit)? = null) {
        val path = Path().apply { moveTo(x, y) }
        dispatch(path, durationMs, onDone)
    }

    fun drag(
        fromX: Float, fromY: Float, toX: Float, toY: Float,
        durationMs: Long, onDone: ((Boolean) -> Unit)? = null
    ) {
        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(toX, toY)
        }
        dispatch(path, durationMs, onDone)
    }

    private fun dispatch(path: Path, durationMs: Long, onDone: ((Boolean) -> Unit)?) {
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(1L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val started = dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    onDone?.invoke(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    onDone?.invoke(false)
                }
            },
            null
        )
        if (!started) onDone?.invoke(false)
    }

    // ---- screen capture (Android 11+) ----

    @SuppressLint("NewApi")
    fun captureScreen(onResult: (Bitmap?, String?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onResult(null, "Reading the screen needs Android 11 or newer")
            return
        }
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    val buffer = screenshot.hardwareBuffer
                    val hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                    val soft = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                    hardwareBitmap?.recycle()
                    buffer.close()
                    if (soft != null) onResult(soft, null)
                    else onResult(null, "Couldn't copy the screenshot")
                }

                override fun onFailure(errorCode: Int) {
                    onResult(null, "Screenshot failed (code $errorCode)")
                }
            }
        )
    }

    // ---- OCR test: wait, screenshot, read every calibrated box ----

    fun readGridAfterDelay(profileName: String, delayMs: Long) {
        val profile = GridStorage.load(this, profileName)
        if (profile == null) {
            toast("Grid '$profileName' not found")
            return
        }
        toast("Switch to the game now. Reading in ${delayMs / 1000} seconds...")
        mainHandler.postDelayed({ captureAndRead(profile) }, delayMs)
    }

    private fun captureAndRead(profile: GridProfile) {
        captureScreen { bitmap, error ->
            if (bitmap == null) {
                toast(error ?: "Screenshot failed")
                return@captureScreen
            }
            val savedLandscape = profile.screenWidth > profile.screenHeight
            val shotLandscape = bitmap.width > bitmap.height
            if (savedLandscape != shotLandscape) {
                toast("Screen orientation doesn't match the saved grid. Rotate and try again.")
                return@captureScreen
            }
            GridReader.read(profile, bitmap) { readings ->
                Thread {
                    LastReadStore.save(applicationContext, profile.name, bitmap, readings)
                    val tiles = readings.count { it.status != ReadStatus.EMPTY }
                    val ok = readings.count { it.status == ReadStatus.READ }
                    toast("Found $tiles tiles, read $ok. Open MyApp > View Last Result.")
                }.start()
            }
        }
    }


    // ---- click points: test tap and hold-until-the-grid-is-full ----

    /** Test button: after a countdown, taps a saved click point once. */
    fun tapPointAfterDelay(pointName: String, delayMs: Long) {
        val point = ClickPointStorage.load(this, pointName)
        if (point == null) {
            toast("Click point '$pointName' not found")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            toast("This needs Android 11 or newer")
            return
        }
        toast("Switch to the game now. Tapping in ${delayMs / 1000} seconds...")
        mainHandler.postDelayed({
            captureScreen { bitmap, error ->
                if (bitmap == null) {
                    toast(error ?: "Screenshot failed")
                    return@captureScreen
                }
                if (!sameOrientation(point, bitmap)) {
                    toast("Screen orientation doesn't match the saved point. Rotate and try again.")
                    return@captureScreen
                }
                val x = point.x * bitmap.width.toFloat() / point.screenWidth
                val y = point.y * bitmap.height.toFloat() / point.screenHeight
                tap(x, y, 80L) { ok ->
                    toast(if (ok) "Tapped '$pointName'" else "The tap was blocked or cancelled")
                }
            }
        }, delayMs)
    }

    /**
     * Presses and holds a saved click point (e.g. the summon button) until every
     * calibrated square has a tile in it, then lets go. Stops early on Volume Down
     * or after maxSeconds.
     */
    fun holdSummonAfterDelay(gridName: String, pointName: String, delayMs: Long, maxSeconds: Int) {
        if (holdActive) {
            toast("Already running. Press Volume Down to stop it.")
            return
        }
        val profile = GridStorage.load(this, gridName)
        if (profile == null) {
            toast("Grid '$gridName' not found")
            return
        }
        val point = ClickPointStorage.load(this, pointName)
        if (point == null) {
            toast("Click point '$pointName' not found")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            toast("This needs Android 11 or newer")
            return
        }
        holdActive = true
        stopRequested = false
        toast("Switch to the game now. Holding starts in ${delayMs / 1000} seconds. Press Volume Down to stop.")
        mainHandler.postDelayed({ startHold(profile, point, maxSeconds * 1000L) }, delayMs)
    }

    private fun startHold(profile: GridProfile, point: ClickPoint, maxMs: Long) {
        if (stopRequested) {
            endHold("Stopped")
            return
        }
        captureScreen { bitmap, error ->
            if (bitmap == null) {
                endHold(error ?: "Screenshot failed")
                return@captureScreen
            }
            val savedLandscape = profile.screenWidth > profile.screenHeight
            if (!sameOrientation(point, bitmap) || savedLandscape != (bitmap.width > bitmap.height)) {
                endHold("Screen orientation doesn't match the saved grid/point. Rotate and try again.")
                return@captureScreen
            }
            val empty = GridReader.countEmpty(profile, bitmap)
            if (empty == 0) {
                endHold("The grid is already full")
                return@captureScreen
            }
            val x = point.x * bitmap.width.toFloat() / point.screenWidth
            val y = point.y * bitmap.height.toFloat() / point.screenHeight
            HoldJob(profile, x, y, maxMs, empty).begin()
        }
    }

    private fun endHold(message: String) {
        holdActive = false
        stopRequested = false
        toast(message)
    }

    private fun sameOrientation(point: ClickPoint, bitmap: Bitmap): Boolean =
        (point.screenWidth > point.screenHeight) == (bitmap.width > bitmap.height)

    /**
     * One long press, built from back-to-back pieces so the finger never lifts.
     * While a piece is playing we take a screenshot and count empty squares; once
     * none are left (or time is up, or Volume Down was pressed) we send a final
     * short piece that lets go.
     */
    @SuppressLint("NewApi")
    private inner class HoldJob(
        val profile: GridProfile,
        val x: Float,
        val y: Float,
        val maxMs: Long,
        startEmpty: Int
    ) {
        private val startedAt = SystemClock.uptimeMillis()
        private var lastStroke: GestureDescription.StrokeDescription? = null
        private var shotPending = false
        private var gridFull = false
        private var lastEmpty = startEmpty

        fun begin() = nextPiece()

        private fun nextPiece() {
            val timedOut = SystemClock.uptimeMillis() - startedAt > maxMs
            if (stopRequested || gridFull || timedOut) {
                release(
                    when {
                        stopRequested -> "Stopped"
                        gridFull -> "Grid is full"
                        else -> "Time limit reached with $lastEmpty empty square(s) left"
                    }
                )
                return
            }

            val path = Path().apply { moveTo(x, y) }
            val previous = lastStroke
            val stroke = if (previous == null) {
                GestureDescription.StrokeDescription(path, 0L, HOLD_SEGMENT_MS, true)
            } else {
                previous.continueStroke(path, 0L, HOLD_SEGMENT_MS, true)
            }

            val started = dispatchGesture(
                GestureDescription.Builder().addStroke(stroke).build(),
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        nextPiece()
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        endHold("The press was interrupted")
                    }
                },
                null
            )
            if (!started) {
                if (previous != null) release("Couldn't keep holding") else endHold("Couldn't press the button")
                return
            }
            lastStroke = stroke
            checkGrid()
        }

        private fun checkGrid() {
            if (shotPending) return
            shotPending = true
            captureScreen { bitmap, _ ->
                shotPending = false
                if (bitmap != null) {
                    lastEmpty = GridReader.countEmpty(profile, bitmap)
                    if (lastEmpty == 0) gridFull = true
                }
            }
        }

        private fun release(message: String) {
            val previous = lastStroke
            if (previous == null) {
                endHold(message)
                return
            }
            val path = Path().apply { moveTo(x, y) }
            val stroke = previous.continueStroke(path, 0L, 40L, false)
            val started = dispatchGesture(
                GestureDescription.Builder().addStroke(stroke).build(),
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        endHold(message)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        endHold(message)
                    }
                },
                null
            )
            if (!started) endHold(message)
        }
    }

    private fun toast(message: String) {
        mainHandler.post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }
}
