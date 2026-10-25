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
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.example.myapp.model.GridProfile
import com.example.myapp.ocr.GridReader
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
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used: we only act when the app asks us to.
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
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
                    val ok = readings.count { it.value != null }
                    toast("Read $ok of ${readings.size} boxes. Open MyApp > View Last Result.")
                }.start()
            }
        }
    }

    private fun toast(message: String) {
        mainHandler.post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }
}
