package com.example.myapp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.example.myapp.automation.BoardAction
import com.example.myapp.automation.BoardPlanner
import com.example.myapp.automation.BoardState
import com.example.myapp.automation.Move
import com.example.myapp.model.ClickPoint
import com.example.myapp.model.GridProfile
import com.example.myapp.model.ReadStatus
import com.example.myapp.ocr.GridReader
import com.example.myapp.storage.ClickPointStorage
import com.example.myapp.storage.GridStorage
import com.example.myapp.storage.LastReadStore
import com.example.myapp.storage.ActionStore
import com.example.myapp.storage.ActionType
import com.example.myapp.storage.AppSettings
import com.example.myapp.storage.CustomAction
import com.example.myapp.storage.LoadoutStore
import com.example.myapp.storage.MsRange
import com.example.myapp.storage.PlanStore
import com.example.myapp.storage.RunLog
import com.example.myapp.storage.SettingsStore
import com.example.myapp.storage.StepType
import kotlin.math.hypot
import kotlin.random.Random

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

    // True from the moment a job (hold, sort, merge) is started until it ends, countdown included.
    @Volatile private var jobActive = false
    @Volatile private var stopRequested = false

    private val mainHandler = Handler(Looper.getMainLooper())

    // The small Start / Stop bar shown over the game.
    private var panelView: View? = null
    private var panelStatusText: TextView? = null
    private var panelLoadoutButton: TextView? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used: we only act when the app asks us to.
    }

    override fun onInterrupt() {}

    /** While a job is running, Volume Down is an emergency stop (and doesn't change the volume). */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (jobActive && event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.action == KeyEvent.ACTION_DOWN) stopRequested = true
            return true
        }
        return false
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        jobActive = false
        hidePanel()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        hidePanel()
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
        toast("Switch to the game now. Reading in $delayMs ms...")
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
            GridReader.read(applicationContext, profile, bitmap) { readings ->
                Thread {
                    LastReadStore.save(applicationContext, profile.name, bitmap, readings)
                    val tiles = readings.count { it.status != ReadStatus.EMPTY }
                    val ok = readings.count { it.status == ReadStatus.READ }
                    toast("Found $tiles tiles, read $ok. Open MyApp > View Last Result.")
                }.start()
            }
        }
    }


    // ---- shared helpers ----

    private enum class HoldEnd { FULL, ALREADY_FULL, STOPPED, TIME_LIMIT, ERROR }

    private fun sameOrientation(point: ClickPoint, bitmap: Bitmap): Boolean =
        (point.screenWidth > point.screenHeight) == (bitmap.width > bitmap.height)

    private fun gridMatchesScreen(profile: GridProfile, bitmap: Bitmap): Boolean =
        (profile.screenWidth > profile.screenHeight) == (bitmap.width > bitmap.height)

    /** Marks a job as running. Floating windows that could block touches or end up in screenshots get out of the way. */
    private fun beginJob() {
        jobActive = true
        stopRequested = false
        hideMarkers()
        setClickToolbarVisible(false)
    }

    private fun endJob(message: String) {
        jobActive = false
        stopRequested = false
        paused = false
        timerRunning = false
        setPanelStatus("Ready")
        setClickToolbarVisible(true)
        RunLog.save(applicationContext, message)
        toast(message)
    }

    /** False (with a message) if a job is already running or Android is too old. */
    private fun preflight(): Boolean {
        if (jobActive) {
            toast("Already running. Press Volume Down, or Stop on the panel.")
            return false
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            toast("This needs Android 11 or newer")
            return false
        }
        return true
    }

    private fun loadGrid(settings: AppSettings): GridProfile? {
        val profile = if (settings.grid.isBlank()) null else GridStorage.load(this, settings.grid)
        if (profile == null) toast("Choose a grid in Loop Settings first")
        return profile
    }

    private fun loadPoint(name: String, what: String): ClickPoint? {
        val point = if (name.isBlank()) null else ClickPointStorage.load(this, name)
        if (point == null) toast("Choose a click point for $what in Loop Settings first")
        return point
    }

    /** Screenshot, read every tile, and describe the board. */
    private fun scanBoard(profile: GridProfile, attemptsLeft: Int = 3, onResult: (BoardState?, String?) -> Unit) {
        captureScreen { bitmap, error ->
            if (bitmap == null) {
                if (attemptsLeft > 1) {
                    // Android allows about one screenshot per second; try again shortly
                    mainHandler.postDelayed({ scanBoard(profile, attemptsLeft - 1, onResult) }, 500L)
                } else {
                    onResult(null, error ?: "Screenshot failed")
                }
                return@captureScreen
            }
            if (!gridMatchesScreen(profile, bitmap)) {
                onResult(null, "Screen orientation doesn't match the saved grid. Rotate and try again.")
                return@captureScreen
            }
            GridReader.read(applicationContext, profile, bitmap) { readings ->
                Thread {
                    LastReadStore.save(applicationContext, profile.name, bitmap, readings)
                }.start()
                onResult(BoardState.build(profile, bitmap.width, bitmap.height, readings), null)
            }
        }
    }

    /**
     * Sorts in passes: plan from the board as it is, do (at most a set number of) the drags, read the
     * board again, and plan again, until it is sorted or the passes are used up. Reading again between
     * passes means a lag spike that made a drag miss is simply corrected by the next pass, instead of
     * leaving the rest of an old plan to run on a board that is no longer what it expected.
     * Calls back with the last board known (null if it must be read again), the number of drags
     * done, and an error message (null when it ended normally).
     */
    private fun sortPasses(
        profile: GridProfile,
        first: BoardState?,
        settings: AppSettings,
        onFinished: (BoardState?, Int, String?) -> Unit
    ) {
        var total = 0

        fun pass(n: Int, board: BoardState?) {
            if (stopRequested) {
                onFinished(board, total, "Stopped after $total sort drag(s)")
                return
            }
            if (board == null) {
                scanBoard(profile) { fresh, error ->
                    if (fresh == null) onFinished(null, total, error ?: "Couldn't read the board")
                    else pass(n, fresh)
                }
                return
            }
            var moves = BoardPlanner.planSort(board.ids, board.values, settings.swap).moves
            if (moves.isEmpty()) {
                onFinished(board, total, null)   // already sorted
                return
            }
            if (settings.sortMovesPerPass > 0 && moves.size > settings.sortMovesPerPass) {
                moves = moves.take(settings.sortMovesPerPass)
            }
            setPanelStatus("sort pass $n of ${settings.sortPasses}: ${moves.size} drags")
            performMoves(profile, board, moves, settings.drag, settings.pause, false) { ok, message ->
                if (!ok) {
                    onFinished(null, total, message)
                    return@performMoves
                }
                total += moves.size
                BoardPlanner.applyMoves(board.ids, board.values, moves)
                if (n < settings.sortPasses) {
                    pass(n + 1, null)   // read the board again before the next pass
                } else if (settings.verify) {
                    mainHandler.postDelayed({
                        checkBoardMatches(profile, board, 3) { good ->
                            if (good) onFinished(board, total, null)
                            else onFinished(null, total, "the board doesn't look the way it should after sorting")
                        }
                    }, settings.pause.pick() + 300L)
                } else {
                    onFinished(board, total, null)
                }
            }
        }
        pass(1, first)
    }

    private class MergeState(var dest: Int? = null, var carrier: Int? = null, var level: Int? = null)

    /**
     * Runs a merge chain one merge at a time: plan ONE merge from the board as it is, drag, wait,
     * read the board again, and ask again. Reading again after every merge is what lets it cope
     * with the game's own upgrades (other tiles change level when tiles merge). It ends when no
     * tile of the destination's level is left. Calls back with the last board that was read, the
     * number of merges done, and an error message (null when it ended normally).
     */
    private fun mergeUntilDone(
        profile: GridProfile,
        first: BoardState,
        settings: AppSettings,
        onFinished: (BoardState?, Int, String?) -> Unit
    ) {
        val state = MergeState()
        var merges = 0

        fun step(board: BoardState) {
            if (stopRequested) {
                onFinished(board, merges, "Stopped after $merges merge(s)")
                return
            }
            if (merges >= 300) {
                onFinished(board, merges, null)
                return
            }
            val next = BoardPlanner.planNextMerge(board.ids, board.values, state.dest, state.carrier, state.level)
            if (next == null) {
                onFinished(board, merges, null)
                return
            }
            setPanelStatus("merge ${merges + 1}")
            performMoves(profile, board, listOf(next.move), settings.drag, settings.pause, false) { ok, message ->
                if (!ok) {
                    onFinished(null, merges, message)
                    return@performMoves
                }
                merges++
                if (!settings.rereadMerge) {
                    // fast mode: trust the plan (the game's own upgrades are not modelled)
                    BoardPlanner.applyMoves(board.ids, board.values, listOf(next.move))
                    state.dest = next.newDest
                    state.carrier = next.newCarrier
                    state.level = next.newLevel
                    if (settings.verify) {
                        verifyEmpty(profile, next.move.from, 3) { good ->
                            if (good) step(board)
                            else onFinished(board, merges, "Merge $merges: box ${next.move.from} still has a tile, so the merge didn't happen")
                        }
                    } else {
                        step(board)
                    }
                    return@performMoves
                }
                scanBoard(profile) { fresh, error ->
                    if (fresh == null) {
                        onFinished(null, merges, error ?: "Couldn't read the board")
                        return@scanBoard
                    }
                    val sourceNow = fresh.values[fresh.ids.indexOf(next.move.from)]
                    if (sourceNow != BoardPlanner.EMPTY) {
                        onFinished(fresh, merges, "Merge $merges: box ${next.move.from} still has a tile, so the merge didn't happen")
                        return@scanBoard
                    }
                    state.dest = next.newDest
                    state.carrier = next.newCarrier
                    state.level = next.newLevel
                    step(fresh)
                }
            }
        }
        step(first)
    }

    /** Performs the drags one after another, pausing between them. */
    private fun performMoves(
        profile: GridProfile,
        board: BoardState,
        moves: List<Move>,
        dragTime: MsRange,
        pauseTime: MsRange,
        verifyEach: Boolean,
        onFinished: (Boolean, String) -> Unit
    ) {
        fun step(i: Int) {
            if (stopRequested) {
                onFinished(false, "Stopped after $i of ${moves.size} moves")
                return
            }
            if (i >= moves.size) {
                onFinished(true, "")
                return
            }
            val m = moves[i]
            val from = board.points[m.from]
            val to = board.points[m.to]
            if (from == null || to == null) {
                onFinished(false, "Box ${m.from} or ${m.to} has no saved position")
                return
            }
            drag(from.first, from.second, to.first, to.second, dragTime.pick()) { ok ->
                if (!ok) {
                    onFinished(false, "A drag was blocked or cancelled at move ${i + 1} of ${moves.size}")
                    return@drag
                }
                mainHandler.postDelayed({
                    if (verifyEach && m.expectSourceEmpty) {
                        verifyEmpty(profile, m.from, 3) { good ->
                            if (good) step(i + 1)
                            else onFinished(false, "Move ${i + 1}: box ${m.from} still has a tile, so the merge didn't happen")
                        }
                    } else {
                        step(i + 1)
                    }
                }, pauseTime.pick())
            }
        }
        step(0)
    }

    /** Looks at the screen and reports whether the given box is empty now. */
    private fun verifyEmpty(
        profile: GridProfile,
        boxId: Int,
        attemptsLeft: Int,
        onResult: (Boolean) -> Unit
    ) {
        captureScreen { bitmap, _ ->
            if (bitmap != null) {
                onResult(boxId in GridReader.emptyIds(profile, bitmap))
            } else if (attemptsLeft > 1) {
                // Android allows about one screenshot per second; try again shortly.
                mainHandler.postDelayed({ verifyEmpty(profile, boxId, attemptsLeft - 1, onResult) }, 400L)
            } else {
                onResult(false)
            }
        }
    }

    /** True when the empty squares on screen are exactly the ones we expect. */
    private fun checkBoardMatches(
        profile: GridProfile,
        board: BoardState,
        attemptsLeft: Int,
        onResult: (Boolean) -> Unit
    ) {
        captureScreen { bitmap, _ ->
            val matches = bitmap != null &&
                GridReader.emptyIds(profile, bitmap) == board.expectedEmptyIds()
            if (matches) {
                onResult(true)
            } else if (attemptsLeft > 1) {
                // animations may still be playing; look again in a moment
                mainHandler.postDelayed({ checkBoardMatches(profile, board, attemptsLeft - 1, onResult) }, 1100L)
            } else {
                onResult(false)
            }
        }
    }

    private class Plan(val moves: List<Move>, val leftover: Int)

    private fun planFor(action: BoardAction, board: BoardState, swap: Boolean): Plan =
        if (action == BoardAction.SORT) {
            val sort = BoardPlanner.planSort(board.ids, board.values, swap)
            Plan(sort.moves, sort.leftover)
        } else {
            Plan(BoardPlanner.simulateMerges(board.ids, board.values), 0)
        }

    private fun describePlan(title: String, plan: Plan, board: BoardState): String {
        val moves = plan.moves
        val known = board.known
        val unknown = board.unknown
        val sb = StringBuilder()
        sb.append("$title: ${moves.size} move(s)\n")
        sb.append("$known tiles read confidently, $unknown unsure or unreadable (left alone).\n")
        if (title == "Merge chain") {
            sb.append("The real run reads the board again after every merge, so steps after the first few depend on what the game does (for example its upgrades). This preview assumes nothing else changes.\n")
        }
        if (title == "Sort") {
            val swaps = BoardPlanner.countDropsOnTiles(board.ids, board.values, moves)
            sb.append("$swaps of these drop onto another tile (always a different level); the rest go to empty squares.\n")
        }
        if (plan.leftover > 0) {
            sb.append("${plan.leftover} square(s) can't be fixed: there is no empty square to move tiles through.\n")
        }
        sb.append("\n")
        moves.take(25).forEachIndexed { i, m ->
            sb.append("${i + 1}. box ${m.from} to box ${m.to}  (${m.note})\n")
        }
        if (moves.size > 25) sb.append("... and ${moves.size - 25} more")
        return sb.toString()
    }

    // ---- test buttons: tap, hold, sort, merge (settings come from Loop Settings) ----

    /** After a countdown, taps a saved click point once. */
    fun tapPointAfterDelay(pointName: String, delayMs: Long) {
        if (!preflight()) return
        val point = ClickPointStorage.load(this, pointName)
        if (point == null) {
            toast("Click point '$pointName' not found")
            return
        }
        toast("Switch to the game now. Tapping in $delayMs ms...")
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

    /** After a countdown, holds the summon point until the grid is full. */
    fun holdSummonAfterDelay(delayMs: Long) {
        if (!preflight()) return
        val settings = SettingsStore.load(this)
        val profile = loadGrid(settings) ?: return
        val point = loadPoint(settings.summonPoint, "the summon button") ?: return
        beginJob()
        toast("Switch to the game now. Holding starts in $delayMs ms. Press Volume Down to stop.")
        mainHandler.postDelayed({
            startHold(profile, point, settings.maxHold.pick()) { _, message -> endJob(message) }
        }, delayMs)
    }

    /** After a countdown: read the board, then show or perform one sort or merge chain. */
    fun runBoardAction(action: BoardAction, delayMs: Long) {
        if (!preflight()) return
        val settings = SettingsStore.load(this)
        val profile = loadGrid(settings) ?: return
        beginJob()
        toast("Switch to the game now. Starting in $delayMs ms. Press Volume Down to stop.")
        mainHandler.postDelayed({
            if (stopRequested) {
                endJob("Stopped")
                return@postDelayed
            }
            toast("Reading the board...")
            scanBoard(profile) { board, error ->
                if (board == null) {
                    endJob(error ?: "Couldn't read the board")
                    return@scanBoard
                }
                val title = if (action == BoardAction.SORT) "Sort" else "Merge chain"
                val plan = planFor(action, board, settings.swap)
                val moves = plan.moves
                if (settings.previewOnly) {
                    PlanStore.save(applicationContext, describePlan(title, plan, board))
                    endJob("Plan ready. Open MyApp to see it. Nothing was moved.")
                    return@scanBoard
                }
                if (moves.isEmpty()) {
                    endJob(
                        when {
                            action == BoardAction.MERGE -> "No pair to merge among the tiles that were read confidently"
                            plan.leftover > 0 -> "Can't sort: there is no empty square to move tiles through"
                            else -> "Nothing to sort: the readable tiles are already in order"
                        }
                    )
                    return@scanBoard
                }
                if (action == BoardAction.MERGE) {
                    mergeUntilDone(profile, board, settings) { _, merges, error ->
                        endJob(error ?: "Merge chain finished: $merges merge(s)")
                    }
                    return@scanBoard
                }
                sortPasses(profile, board, settings) { _, total, error ->
                    if (error != null) {
                        endJob(error)
                    } else {
                        endJob("Sort finished: $total drag(s). Reading the board again to check...")
                        mainHandler.postDelayed({ captureAndRead(profile) }, 900L)
                    }
                }
            }
        }, delayMs)
    }

    // ---- hold: press and hold a click point until the grid is full ----

    private fun startHold(
        profile: GridProfile,
        point: ClickPoint,
        maxMs: Long,
        onFinished: (HoldEnd, String) -> Unit
    ) {
        if (stopRequested) {
            onFinished(HoldEnd.STOPPED, "Stopped")
            return
        }
        captureScreen { bitmap, error ->
            if (bitmap == null) {
                onFinished(HoldEnd.ERROR, error ?: "Screenshot failed")
                return@captureScreen
            }
            if (!gridMatchesScreen(profile, bitmap) || !sameOrientation(point, bitmap)) {
                onFinished(HoldEnd.ERROR, "Screen orientation doesn't match the saved grid/point. Rotate and try again.")
                return@captureScreen
            }
            val empty = GridReader.countEmpty(profile, bitmap)
            if (empty == 0) {
                onFinished(HoldEnd.ALREADY_FULL, "The grid is already full")
                return@captureScreen
            }
            val x = point.x * bitmap.width.toFloat() / point.screenWidth
            val y = point.y * bitmap.height.toFloat() / point.screenHeight
            HoldJob(profile, x, y, maxMs, empty, onFinished).begin()
        }
    }

    /**
     * One long press, built from back-to-back pieces so the finger never lifts.
     * While a piece is playing we take a screenshot and count empty squares; once
     * none are left (or time is up, or a stop was requested) we send a final
     * short piece that lets go.
     */
    @SuppressLint("NewApi")
    private inner class HoldJob(
        val profile: GridProfile,
        val x: Float,
        val y: Float,
        val maxMs: Long,
        startEmpty: Int,
        val onFinished: (HoldEnd, String) -> Unit
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
                when {
                    stopRequested -> release(HoldEnd.STOPPED, "Stopped")
                    gridFull -> release(HoldEnd.FULL, "Grid is full")
                    else -> release(HoldEnd.TIME_LIMIT, "Time limit reached with $lastEmpty empty square(s) left")
                }
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
                        onFinished(HoldEnd.ERROR, "The press was interrupted")
                    }
                },
                null
            )
            if (!started) {
                if (previous != null) release(HoldEnd.ERROR, "Couldn't keep holding")
                else onFinished(HoldEnd.ERROR, "Couldn't press the button")
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

        private fun release(end: HoldEnd, message: String) {
            val previous = lastStroke
            if (previous == null) {
                onFinished(end, message)
                return
            }
            val path = Path().apply { moveTo(x, y) }
            val stroke = previous.continueStroke(path, 0L, 40L, false)
            val started = dispatchGesture(
                GestureDescription.Builder().addStroke(stroke).build(),
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        onFinished(end, message)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        onFinished(end, message)
                    }
                },
                null
            )
            if (!started) onFinished(end, message)
        }
    }

    // ---- the loop: the enabled steps in your order, over and over ----

    /** Starts the loop after a countdown (use 0 when starting from the floating panel). */
    fun startLoopAfterDelay(delayMs: Long) {
        if (!preflight()) return
        val settings = SettingsStore.load(this)
        val profile = loadGrid(settings) ?: return
        val steps = settings.steps.filter { it.enabled }.map { it.type }
        if (steps.isEmpty()) {
            toast("Turn on at least one step in Loop Settings")
            return
        }
        val summon = if (StepType.SUMMON in steps) {
            loadPoint(settings.summonPoint, "the summon step") ?: return
        } else null
        val extra = if (StepType.CLICK in steps) {
            loadPoint(settings.extraPoint, "the extra click step") ?: return
        } else null

        if (StepType.CUSTOM in steps && ActionStore.load(this).actions.isEmpty()) {
            toast("The custom step has no clicks or drags yet. Add some with + Click and + Drag on the floating panel.")
            return
        }
        hideMarkers()   // the markers would block the touches

        beginJob()
        if (delayMs > 0) {
            toast("Switch to the game now. The loop starts in $delayMs ms. Press Volume Down to stop.")
        }
        mainHandler.postDelayed({ LoopJob(settings, profile, steps, summon, extra).start() }, delayMs)
    }

    private inner class LoopJob(
        val settings: AppSettings,
        val profile: GridProfile,
        val steps: List<StepType>,
        val summon: ClickPoint?,
        val extra: ClickPoint?
    ) {
        private var loopNo = 0
        private var board: BoardState? = null
        private var didWork = false
        private var idleLoops = 0
        private var summonTimeouts = 0
        private var failedInRow = 0

        fun start() = nextLoop()

        private fun finish(message: String) = endJob(message)

        /**
         * A problem inside a loop (a missed drag, a board that hasn't settled after a lag spike, a failed
         * screenshot) doesn't end the run: it starts the next loop, which begins with a fresh read of the
         * board. Only a Stop, or too many problems in a row, ends it.
         */
        private fun soft(message: String) {
            if (stopRequested) {
                finish("Stopped during loop $loopNo")
                return
            }
            failedInRow++
            if (failedInRow >= settings.maxFailedLoops) {
                finish("Stopped after $failedInRow problems in a row. Last one: $message")
                return
            }
            toast("Loop $loopNo: $message. Trying the next loop ($failedInRow of ${settings.maxFailedLoops} problems in a row).")
            setPanelStatus("${label()}: problem, retrying")
            mainHandler.postDelayed({ nextLoop() }, settings.loopPause.pick())
        }

        private fun label(): String =
            "Loop $loopNo" + (if (settings.loops > 0) " of ${settings.loops}" else "")

        private fun nextLoop() {
            if (stopRequested) {
                finish("Stopped")
                return
            }
            if (settings.loops > 0 && loopNo >= settings.loops) {
                finish("Finished $loopNo loop(s)")
                return
            }
            loopNo++
            board = null
            didWork = false
            runStep(0)
        }

        private fun runStep(index: Int) {
            if (stopRequested) {
                finish("Stopped during loop $loopNo")
                return
            }
            if (index >= steps.size) {
                endOfLoop()
                return
            }
            when (steps[index]) {
                StepType.MERGE -> mergeStep(index)
                StepType.SORT -> sortStep(index)
                StepType.SUMMON -> summonStep(index)
                StepType.CLICK -> clickStep(index)
                StepType.CUSTOM -> customStep(index)
            }
        }

        /** Uses the board we already know, or reads the screen if we don't. */
        private fun ensureBoard(onReady: (BoardState) -> Unit) {
            val existing = board
            if (existing != null) {
                onReady(existing)
                return
            }
            setPanelStatus("${label()}: reading board")
            scanBoard(profile) { scanned, error ->
                if (scanned == null) {
                    soft(error ?: "Couldn't read the board")
                    return@scanBoard
                }
                board = scanned
                onReady(scanned)
            }
        }

        private fun mergeStep(index: Int) {
            ensureBoard { b ->
                if (stopRequested) {
                    finish("Stopped during loop $loopNo")
                    return@ensureBoard
                }
                if (BoardPlanner.planNextMerge(b.ids, b.values, null, null) == null) {
                    runStep(index + 1)
                    return@ensureBoard
                }
                setPanelStatus("${label()}: merging")
                mergeUntilDone(profile, b, settings) { last, merges, error ->
                    if (merges > 0) didWork = true
                    if (last != null) board = last   // freshly read, so the sort can start from it
                    if (error != null) {
                        soft(error)
                        return@mergeUntilDone
                    }
                    runStep(index + 1)
                }
            }
        }

        private fun sortStep(index: Int) {
            ensureBoard { b ->
                if (stopRequested) {
                    finish("Stopped during loop $loopNo")
                    return@ensureBoard
                }
                setPanelStatus("${label()}: sorting")
                sortPasses(profile, b, settings) { last, total, error ->
                    if (total > 0) didWork = true
                    board = last   // null means: read the board again when the next step needs it
                    if (error != null) {
                        soft(error)
                        return@sortPasses
                    }
                    runStep(index + 1)
                }
            }
        }

        private fun summonStep(index: Int) {
            val point = summon
            if (point == null) {
                finish("No summon click point is set")
                return
            }
            setPanelStatus("${label()}: summoning")
            startHold(profile, point, settings.maxHold.pick()) { end, message ->
                board = null   // new tiles of an unknown level now fill the empty squares
                when (end) {
                    HoldEnd.FULL -> {
                        didWork = true
                        summonTimeouts = 0
                    }
                    HoldEnd.ALREADY_FULL -> summonTimeouts = 0
                    HoldEnd.TIME_LIMIT -> {
                        didWork = true
                        summonTimeouts++
                    }
                    HoldEnd.STOPPED -> {
                        finish("Stopped during loop $loopNo")
                        return@startHold
                    }
                    HoldEnd.ERROR -> {
                        soft(message)
                        return@startHold
                    }
                }
                if (summonTimeouts >= 2) {
                    if (settings.stopWhenIdle) {
                        finish("The grid never fills up (some squares may be blocked). Stopped.")
                        return@startHold
                    }
                    if (summonTimeouts == 2) {
                        toast("The grid isn't filling up (some squares may be blocked). Carrying on.")
                    }
                }
                mainHandler.postDelayed({ runStep(index + 1) }, settings.pause.pick() + 500L)
            }
        }

        private fun customStep(index: Int) {
            setPanelStatus("${label()}: custom actions")
            runCustom { ok, message ->
                if (!ok) {
                    soft(message)
                    return@runCustom
                }
                didWork = true
                board = null   // the clicks and drags may change the board
                mainHandler.postDelayed({ runStep(index + 1) }, settings.pause.pick() + 300L)
            }
        }

        private fun clickStep(index: Int) {
            val point = extra
            if (point == null) {
                finish("No extra click point is set")
                return
            }
            setPanelStatus("${label()}: extra click")
            captureScreen { bitmap, error ->
                if (bitmap == null) {
                    soft(error ?: "Screenshot failed")
                    return@captureScreen
                }
                if (!sameOrientation(point, bitmap)) {
                    soft("Screen orientation doesn't match the extra click point. Rotate and try again.")
                    return@captureScreen
                }
                val x = point.x * bitmap.width.toFloat() / point.screenWidth
                val y = point.y * bitmap.height.toFloat() / point.screenHeight
                tap(x, y, 80L) { ok ->
                    if (!ok) {
                        soft("The extra click was blocked or cancelled")
                        return@tap
                    }
                    didWork = true
                    board = null   // a click may change the board
                    mainHandler.postDelayed({ runStep(index + 1) }, settings.pause.pick() + 300L)
                }
            }
        }

        private fun endOfLoop() {
            failedInRow = 0
            if (didWork) idleLoops = 0 else idleLoops++
            if (settings.stopWhenIdle && idleLoops >= 2) {
                finish("Nothing left to do after $loopNo loop(s)")
                return
            }
            setPanelStatus("${label()} done")
            mainHandler.postDelayed({ nextLoop() }, settings.loopPause.pick())
        }
    }

    // ---- the custom list: clicks, swipes, holds, waits and the app's own steps ----

    private fun screenSize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = (getSystemService(WINDOW_SERVICE) as WindowManager).maximumWindowMetrics.bounds
            Pair(b.width(), b.height())
        } else {
            Pair(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        }

    @Volatile private var paused = false
    private var runStartedAt = 0L
    private var timerRunning = false
    private var counters = "Ready"

    private fun ActionType.usesScreen(): Boolean =
        this == ActionType.CLICK || this == ActionType.DRAG || this == ActionType.HOLD

    /** A position moved by a random amount (anti-detection); amount 0 leaves it alone. */
    private fun fuzz(value: Float, amount: Int): Float =
        if (amount <= 0) value else value + Random.nextInt(-amount, amount + 1)

    /** Performs the whole custom list, repeated as set. Every time in it is a random pick inside its range. */
    private fun runCustom(onFinished: (Boolean, String) -> Unit) {
        val data = ActionStore.load(this)
        if (data.actions.isEmpty()) {
            onFinished(false, "The custom list is empty. Add steps with the click window or in the Clicks tab.")
            return
        }
        val (w, h) = screenSize()
        if (data.actions.any { it.type.usesScreen() } && (data.screenWidth > data.screenHeight) != (w > h)) {
            onFinished(false, "The clicks and drags were recorded in the other screen orientation")
            return
        }
        hideMarkers()   // the markers would block the touches
        setClickToolbarVisible(false)
        val sx = if (data.screenWidth > 0) w.toFloat() / data.screenWidth else 1f
        val sy = if (data.screenHeight > 0) h.toFloat() / data.screenHeight else 1f
        val count = data.actions.size
        val loops = data.repeat.coerceAtLeast(1)
        runStartedAt = SystemClock.uptimeMillis()
        timerRunning = true
        paused = false

        fun finishRun(ok: Boolean, message: String) {
            timerRunning = false
            refreshStatusBar()
            onFinished(ok, message)
        }

        fun act(loop: Int, i: Int) {
            if (stopRequested) {
                finishRun(false, "Stopped")
                return
            }
            if (paused) {
                mainHandler.postDelayed({ act(loop, i) }, 200L)
                return
            }
            if (i >= count) {
                if (loop + 1 >= loops) finishRun(true, "") else act(loop + 1, 0)
                return
            }
            val a = data.actions[i]
            counters = "↻ ${loop + 1}/$loops   ▸ ${i + 1}/$count"
            refreshStatusBar()
            runOne(a, sx, sy) { ok, message ->
                if (!ok) {
                    finishRun(false, "Step ${i + 1}: $message")
                } else {
                    mainHandler.postDelayed({ act(loop, i + 1) }, MsRange(a.waitMin, a.waitMax).pick())
                }
            }
        }
        act(0, 0)
    }

    /** One step of the list, done as many times in a row as its repetitions say. */
    private fun runOne(a: CustomAction, sx: Float, sy: Float, onDone: (Boolean, String) -> Unit) {
        val times = a.repeat.coerceAtLeast(1)

        fun again(n: Int) {
            if (stopRequested) {
                onDone(false, "Stopped")
                return
            }
            if (n >= times) {
                onDone(true, "")
                return
            }
            val hold = MsRange(a.holdMin, a.holdMax).pick().coerceAtLeast(1L)
            val next: (Boolean, String) -> Unit = { ok, message ->
                if (!ok) {
                    onDone(false, message)
                } else if (n + 1 < times) {
                    mainHandler.postDelayed({ again(n + 1) }, MsRange(a.waitMin, a.waitMax).pick())
                } else {
                    onDone(true, "")
                }
            }
            when (a.type) {
                ActionType.CLICK -> tap(fuzz(a.x1 * sx, a.jitter), fuzz(a.y1 * sy, a.jitter), hold) { ok ->
                    next(ok, "the tap was blocked or cancelled")
                }
                ActionType.DRAG -> drag(
                    fuzz(a.x1 * sx, a.jitter), fuzz(a.y1 * sy, a.jitter),
                    fuzz(a.x2 * sx, a.jitter), fuzz(a.y2 * sy, a.jitter), hold
                ) { ok -> next(ok, "the swipe was blocked or cancelled") }
                ActionType.HOLD -> holdFor(fuzz(a.x1 * sx, a.jitter), fuzz(a.y1 * sy, a.jitter), hold) { ok ->
                    next(ok, "the hold was interrupted")
                }
                ActionType.WAIT -> mainHandler.postDelayed({ next(true, "") }, hold)
                ActionType.SORT, ActionType.MERGE, ActionType.SUMMON -> runFeature(a.type) { ok, message ->
                    next(ok, message)
                }
            }
        }
        again(0)
    }

    /**
     * Presses and holds one spot for as long as asked (seconds, minutes, hours). Android limits one touch
     * to about a minute, so the hold is built from back-to-back pieces of one press, as the summon hold is.
     */
    @SuppressLint("NewApi")
    private fun holdFor(x: Float, y: Float, totalMs: Long, onDone: (Boolean) -> Unit) {
        val started = SystemClock.uptimeMillis()
        var last: GestureDescription.StrokeDescription? = null

        fun release() {
            val previous = last
            if (previous == null) {
                onDone(true)
                return
            }
            val stroke = previous.continueStroke(Path().apply { moveTo(x, y) }, 0L, 30L, false)
            val ok = dispatchGesture(
                GestureDescription.Builder().addStroke(stroke).build(),
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        onDone(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        onDone(true)
                    }
                },
                null
            )
            if (!ok) onDone(true)
        }

        fun piece() {
            if (stopRequested) {
                release()
                return
            }
            val remaining = totalMs - (SystemClock.uptimeMillis() - started)
            val finalPiece = remaining <= HOLD_SEGMENT_MS
            val length = (if (finalPiece) remaining else HOLD_SEGMENT_MS).coerceAtLeast(1L)
            val path = Path().apply { moveTo(x, y) }
            val previous = last
            val stroke = if (previous == null) {
                GestureDescription.StrokeDescription(path, 0L, length, !finalPiece)
            } else {
                previous.continueStroke(path, 0L, length, !finalPiece)
            }
            last = stroke
            val ok = dispatchGesture(
                GestureDescription.Builder().addStroke(stroke).build(),
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (finalPiece) onDone(true) else piece()
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        onDone(false)
                    }
                },
                null
            )
            if (!ok) onDone(false)
        }
        piece()
    }

    /** The app's own steps (sort, merge, summon) used as steps of the custom list. */
    private fun runFeature(type: ActionType, onDone: (Boolean, String) -> Unit) {
        val settings = SettingsStore.load(this)
        val profile = loadGrid(settings)
        if (profile == null) {
            onDone(false, "no grid is chosen in the Loadout tab")
            return
        }
        when (type) {
            ActionType.SORT -> scanBoard(profile) { board, error ->
                if (board == null) onDone(false, error ?: "couldn't read the board")
                else sortPasses(profile, board, settings) { _, _, err -> onDone(err == null, err ?: "") }
            }
            ActionType.MERGE -> scanBoard(profile) { board, error ->
                if (board == null) onDone(false, error ?: "couldn't read the board")
                else mergeUntilDone(profile, board, settings) { _, _, err -> onDone(err == null, err ?: "") }
            }
            ActionType.SUMMON -> {
                val point = loadPoint(settings.summonPoint, "the summon step")
                if (point == null) {
                    onDone(false, "no summon click point is chosen")
                    return
                }
                startHold(profile, point, settings.maxHold.pick()) { end, message ->
                    onDone(end != HoldEnd.ERROR && end != HoldEnd.STOPPED, message)
                }
            }
            else -> onDone(true, "")
        }
    }

    /** After a countdown, performs the custom list (all its repeats). */
    fun runCustomAfterDelay(delayMs: Long) {
        if (jobActive) {
            toast("Already running. Press Volume Down, or Stop on the window.")
            return
        }
        beginJob()
        if (delayMs > 0) toast("Switch to the game now. Starting in $delayMs ms. Press Volume Down to stop.")
        mainHandler.postDelayed({
            runCustom { ok, message -> endJob(if (ok) "The custom list finished" else message) }
        }, delayMs)
    }

    private fun startCustomFromWindow() {
        if (jobActive) toast("Already running. Tap ■ to stop.") else runCustomAfterDelay(0L)
    }

    private fun togglePause() {
        if (!jobActive) {
            toast("Nothing is running")
            return
        }
        paused = !paused
        clickPauseButton?.text = if (paused) "▶" else "⏸"
        toast(if (paused) "Paused" else "Continuing")
    }

    // ---- markers: draggable points on the real screen for clicks, swipes and holds ----

    private class Marker(val view: TextView, val params: WindowManager.LayoutParams)

    private val markers = mutableListOf<Marker>()

    /** Used by the app screens after the list was changed or cleared. */
    fun hidePanelMarkers() = hideMarkers()

    /** Draws the markers again (only if they are showing) after the list changed. */
    fun refreshMarkers() {
        mainHandler.post { if (markers.isNotEmpty()) showMarkers() }
    }

    private fun toggleMarkers() {
        if (markers.isEmpty()) showMarkers() else hideMarkers()
    }

    private fun showMarkers() {
        hideMarkers()
        val data = ActionStore.load(this)
        val blue = Color.argb(170, 33, 150, 243)
        val orange = Color.argb(170, 255, 152, 0)
        val teal = Color.argb(170, 0, 150, 136)
        var any = false
        data.actions.forEachIndexed { i, a ->
            when (a.type) {
                ActionType.CLICK -> {
                    addMarker("${i + 1}", a.x1, a.y1, i, false, blue)
                    any = true
                }
                ActionType.HOLD -> {
                    addMarker("${i + 1}", a.x1, a.y1, i, false, orange)
                    any = true
                }
                ActionType.DRAG -> {
                    addMarker("${i + 1}a", a.x1, a.y1, i, false, teal)
                    addMarker("${i + 1}b", a.x2, a.y2, i, true, teal)
                    any = true
                }
                else -> {}
            }
        }
        if (!any) toast("No taps, swipes or holds yet. Add some with the click window.")
    }

    private fun hideMarkers() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        for (m in markers) {
            try {
                wm.removeView(m.view)
            } catch (_: Exception) {
            }
        }
        markers.clear()
    }

    /** A marker: drag it to move the point; tap it to open that step's settings. */
    @SuppressLint("ClickableViewAccessibility")
    private fun addMarker(label: String, cx: Int, cy: Int, actionIndex: Int, isEnd: Boolean, color: Int) {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val density = resources.displayMetrics.density
        val size = (46 * density).toInt()
        val slop = 10 * density
        val view = TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 13f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                setStroke(3, Color.WHITE)
            }
        }
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = cx - size / 2
            y = cy - size / 2
        }

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (hypot(dx, dy) > slop) moved = true
                    if (moved) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        wm.updateViewLayout(v, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) saveMarkerPosition(v, actionIndex, isEnd) else openActionSettings(actionIndex)
                }
            }
            true
        }

        try {
            wm.addView(view, params)
        } catch (e: Exception) {
            toast("Couldn't show a marker: ${e.message}")
            return
        }
        markers.add(Marker(view, params))

        // whatever offset the system adds to window positions, make the marker's centre land on the point
        view.post {
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            val wantLeft = cx - size / 2
            val wantTop = cy - size / 2
            if (loc[0] != wantLeft || loc[1] != wantTop) {
                params.x += wantLeft - loc[0]
                params.y += wantTop - loc[1]
                try {
                    wm.updateViewLayout(view, params)
                } catch (_: Exception) {
                }
            }
        }
    }

    /** Saves where a marker was dropped (its centre, in screen pixels). */
    private fun saveMarkerPosition(view: View, actionIndex: Int, isEnd: Boolean) {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val cx = loc[0] + view.width / 2
        val cy = loc[1] + view.height / 2
        val data = ActionStore.load(this)
        val a = data.actions.getOrNull(actionIndex) ?: return
        if (isEnd) {
            a.x2 = cx
            a.y2 = cy
        } else {
            a.x1 = cx
            a.y1 = cy
            if (a.type != ActionType.DRAG) {
                a.x2 = cx
                a.y2 = cy
            }
        }
        ActionStore.save(this, data)
    }

    /** Opens the settings screen for one step (like the Tap Settings dialog of an auto clicker). */
    private fun openActionSettings(index: Int) {
        ActionSettingsActivity.opened = false
        val intent = Intent(this, ActionSettingsActivity::class.java)
            .putExtra(ActionSettingsActivity.EXTRA_INDEX, index)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            toast("Couldn't open the settings: ${e.message}")
            return
        }
        mainHandler.postDelayed({
            if (!ActionSettingsActivity.opened) {
                toast("Android didn't open the settings from here. Open the app, Clicks tab, and tap step ${index + 1}.")
            }
        }, 1500L)
    }

    private fun openApp() {
        try {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toast("Couldn't open the app: ${e.message}")
        }
    }

    /** Adds a step to the end of the active loadout's custom list. */
    private fun addAction(type: ActionType) {
        val (w, h) = screenSize()
        val data = ActionStore.load(this)
        if (type.usesScreen()) {
            if (data.screenWidth == 0 || data.actions.none { it.type.usesScreen() }) {
                data.screenWidth = w
                data.screenHeight = h
            } else if ((data.screenWidth > data.screenHeight) != (w > h)) {
                toast("The saved clicks and drags were recorded in the other orientation. Rotate back, or clear them in the Clicks tab.")
                return
            }
        }
        val n = data.actions.size
        val cx = w / 2 + (n % 6) * 70
        val cy = h / 2 + (n % 6) * 35
        val action = when (type) {
            ActionType.CLICK -> CustomAction(type, cx, cy, cx, cy, 50L, 50L, 500L, 500L)
            ActionType.HOLD -> CustomAction(type, cx, cy, cx, cy, 1000L, 1000L, 500L, 500L)
            ActionType.DRAG -> CustomAction(type, cx - 150, cy, cx + 150, cy, 300L, 300L, 500L, 500L)
            ActionType.WAIT -> CustomAction(type, 0, 0, 0, 0, 1000L, 1000L, 0L, 0L)
            else -> CustomAction(type, 0, 0, 0, 0, 0L, 0L, 500L, 500L)
        }
        data.actions.add(action)
        ActionStore.save(this, data)
        if (type.usesScreen()) showMarkers()
        toast(
            when (type) {
                ActionType.CLICK -> "Tap ${n + 1} added: drag its marker onto the spot, tap it for settings"
                ActionType.HOLD -> "Hold ${n + 1} added (1000 ms): drag its marker onto the spot, tap it to set the time"
                ActionType.DRAG -> "Swipe ${n + 1} added: put marker a at the start and b at the end"
                ActionType.WAIT -> "Wait added as step ${n + 1} (1000 ms): change it in the Clicks tab"
                ActionType.SORT -> "Sort added as step ${n + 1}"
                ActionType.MERGE -> "Merge added as step ${n + 1}"
                ActionType.SUMMON -> "Summon added as step ${n + 1}"
            }
        )
    }

    private fun removeLastAction() {
        val data = ActionStore.load(this)
        if (data.actions.isEmpty()) {
            toast("The list is empty")
            return
        }
        data.actions.removeAt(data.actions.size - 1)
        ActionStore.save(this, data)
        if (markers.isNotEmpty()) showMarkers()
        toast("Removed the last step (${data.actions.size} left)")
    }

    // ---- loadout switching (works from both windows) ----

    private fun updateLoadoutLabels() {
        val name = LoadoutStore.activeName(this)
        panelLoadoutButton?.text = "Loadout: $name"
        clickLoadoutLabel?.text = name
    }

    /** Called by the app after the active loadout changed, so the windows show the right name. */
    fun refreshPanelLoadout() {
        mainHandler.post { updateLoadoutLabels() }
    }

    fun isBusy(): Boolean = jobActive

    /** Switches to the next loadout. Markers belong to a loadout's own list, so they are hidden. */
    private fun cycleLoadout() {
        if (jobActive) {
            toast("Stop the running job first, then switch loadout")
            return
        }
        val all = LoadoutStore.list(this)
        if (all.size < 2) {
            toast("Only one loadout. Make more in the Run tab of the app.")
            return
        }
        val i = all.indexOfFirst { it.id == LoadoutStore.activeId(this) }
        val next = all[(i + 1) % all.size]
        LoadoutStore.setActive(this, next.id)
        hideMarkers()
        updateLoadoutLabels()
        toast("Loadout: ${next.name}")
    }

    // ---- window helpers ----

    private fun overlayParams(): WindowManager.LayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    /** Lets a window be moved by dragging [handle]; a plain tap on it calls [onTap]. */
    @SuppressLint("ClickableViewAccessibility")
    private fun makeDraggable(handle: View, window: View, params: WindowManager.LayoutParams, onTap: (() -> Unit)? = null) {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val slop = 10 * resources.displayMetrics.density
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (hypot(dx, dy) > slop) moved = true
                    if (moved) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        try {
                            wm.updateViewLayout(window, params)
                        } catch (_: Exception) {
                        }
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) onTap?.invoke()
            }
            true
        }
    }

    // ---- the click window: toolbar + status bar (auto-clicker style) ----

    private var clickToolbarView: View? = null
    private var clickButtonsBox: View? = null
    private var clickFeatureMenu: View? = null
    private var clickStatusView: View? = null
    private var clickStatusText: TextView? = null
    private var clickPauseButton: TextView? = null
    private var clickLoadoutLabel: TextView? = null

    fun isClickWindowShown(): Boolean = clickToolbarView != null

    private fun setClickToolbarVisible(visible: Boolean) {
        mainHandler.post { clickToolbarView?.visibility = if (visible) View.VISIBLE else View.GONE }
    }

    private fun refreshStatusBar() {
        val text = clickStatusText ?: return
        val seconds = if (timerRunning) (SystemClock.uptimeMillis() - runStartedAt) / 1000 else 0L
        val clock = "%02d:%02d:%02d".format(seconds / 3600, (seconds / 60) % 60, seconds % 60)
        text.text = "$counters   $clock"
    }

    private val statusTicker = object : Runnable {
        override fun run() {
            refreshStatusBar()
            if (clickStatusView != null) mainHandler.postDelayed(this, 500L)
        }
    }

    private fun switchToClickWindow() {
        hidePanel()
        showClickWindow()
    }

    private fun switchToRunWindow() {
        hideClickWindow()
        showPanel()
    }

    /**
     * The click window: a compact toolbar (play, hold, tap, swipe, steps, remove, save, open app,
     * show/hide points, switch to the run window, loadout, close) and a status bar (stop, pause,
     * loop and step counters, timer). Tap a marker for the step's settings. Both parts are accessibility
     * overlays, so no "display over other apps" permission is needed. The toolbar hides while something runs.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun showClickWindow() {
        if (clickToolbarView != null) return
        hidePanel()
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val density = resources.displayMetrics.density
        fun px(dp: Int) = (dp * density).toInt()

        val teal = Color.parseColor("#0F5C6E")
        val light = Color.argb(240, 245, 247, 248)
        fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = px(radiusDp).toFloat()
        }
        fun tool(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
            text = label
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(teal)
            setOnClickListener { onClick() }
        }
        val cell = { LinearLayout.LayoutParams(px(44), px(44)) }
        fun row(a: TextView, b: TextView): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(a, cell())
            addView(b, cell())
        }

        // ----- toolbar -----
        val featureMenu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        fun featureItem(label: String, type: ActionType) {
            featureMenu.addView(
                tool(label) {
                    addAction(type)
                    featureMenu.visibility = View.GONE
                }.apply { textSize = 14f },
                LinearLayout.LayoutParams(px(88), px(38))
            )
        }
        featureItem("Sort", ActionType.SORT)
        featureItem("Merge", ActionType.MERGE)
        featureItem("Summon", ActionType.SUMMON)
        featureItem("Wait", ActionType.WAIT)

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        buttons.addView(row(tool("▶") { startCustomFromWindow() }, tool("●") { addAction(ActionType.HOLD) }))
        buttons.addView(row(tool("＋") { addAction(ActionType.CLICK) }, tool("↗") { addAction(ActionType.DRAG) }))
        buttons.addView(row(
            tool("ƒ") { featureMenu.visibility = if (featureMenu.visibility == View.VISIBLE) View.GONE else View.VISIBLE },
            tool("−") { removeLastAction() }
        ))
        buttons.addView(row(
            tool("✓") { toast("Saved in ${LoadoutStore.activeName(this)}. Everything is saved as you go.") },
            tool("⚙") { openApp() }
        ))
        buttons.addView(row(tool("◉") { toggleMarkers() }, tool("⇄") { switchToRunWindow() }))
        buttons.addView(row(tool("⟳") { cycleLoadout() }, tool("✕") { hideClickWindow() }))
        buttons.addView(featureMenu)

        val handle = tool("▾") { }.apply { textSize = 16f }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(light, 12)
            setPadding(px(4), px(2), px(4), px(4))
            addView(handle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(28)))
            addView(buttons)
        }
        val toolbarParams = overlayParams().apply {
            x = px(8)
            y = px(70)
        }
        makeDraggable(handle, toolbar, toolbarParams) {
            buttons.visibility = if (buttons.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            handle.text = if (buttons.visibility == View.VISIBLE) "▾" else "▴"
        }

        // ----- status bar -----
        val stop = tool("■") { onPanelStop() }.apply { setTextColor(Color.parseColor("#C62828")) }
        val pause = tool("⏸") { togglePause() }
        val statusText = TextView(this).apply {
            text = "Ready"
            textSize = 14f
            setTextColor(Color.BLACK)
            setPadding(px(8), 0, px(8), 0)
        }
        val loadoutLabel = TextView(this).apply {
            textSize = 11f
            setTextColor(teal)
            setPadding(px(8), 0, px(8), 0)
        }
        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(statusText)
            addView(loadoutLabel)
        }
        val statusBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(light, 12)
            setPadding(px(4), px(2), px(8), px(2))
            addView(stop, LinearLayout.LayoutParams(px(40), px(40)))
            addView(pause, LinearLayout.LayoutParams(px(40), px(40)))
            addView(textColumn)
        }
        val statusParams = overlayParams().apply {
            x = px(60)
            y = px(8)
        }
        makeDraggable(textColumn, statusBar, statusParams)

        try {
            wm.addView(toolbar, toolbarParams)
            wm.addView(statusBar, statusParams)
        } catch (e: Exception) {
            toast("Couldn't show the click window: ${e.message}")
            try {
                wm.removeView(toolbar)
            } catch (_: Exception) {
            }
            return
        }
        clickToolbarView = toolbar
        clickButtonsBox = buttons
        clickFeatureMenu = featureMenu
        clickStatusView = statusBar
        clickStatusText = statusText
        clickPauseButton = pause
        clickLoadoutLabel = loadoutLabel
        counters = "Ready"
        updateLoadoutLabels()
        refreshStatusBar()
        mainHandler.removeCallbacks(statusTicker)
        mainHandler.postDelayed(statusTicker, 500L)
    }

    fun hideClickWindow() {
        hideMarkers()
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        for (v in listOf(clickToolbarView, clickStatusView)) {
            if (v == null) continue
            try {
                wm.removeView(v)
            } catch (_: Exception) {
            }
        }
        clickToolbarView = null
        clickButtonsBox = null
        clickFeatureMenu = null
        clickStatusView = null
        clickStatusText = null
        clickPauseButton = null
        clickLoadoutLabel = null
        mainHandler.removeCallbacks(statusTicker)
    }

    // ---- the run window (the original floating panel) ----

    fun isPanelShown(): Boolean = panelView != null

    /**
     * The run window: a small draggable bar with the loadout, Start / Stop for the loop, a button that
     * switches to the click window, and Hide. It uses an accessibility overlay, so no "display over
     * other apps" permission is needed, and it keeps the screen on while showing. Drag it by its top
     * label off the grid (for example into the black side bar) so it doesn't end up in the screenshots.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun showPanel() {
        if (panelView != null) return
        hideClickWindow()
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val density = resources.displayMetrics.density
        fun px(dp: Int) = (dp * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(215, 24, 24, 36))
            setPadding(px(6), px(4), px(6), px(6))
        }
        val status = TextView(this).apply {
            text = "MyApp: Ready (drag me)"
            setTextColor(Color.WHITE)
            textSize = 11f
            setPadding(0, 0, 0, px(2))
        }
        fun button(label: String, color: Int, onClick: () -> Unit): TextView = TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            setBackgroundColor(color)
            setPadding(px(10), px(8), px(10), px(8))
            setOnClickListener { onClick() }
        }
        fun row(left: TextView, right: TextView): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = px(4) })
            addView(right, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        val gap = { LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = px(4) } }

        val green = Color.parseColor("#2E7D32")
        val red = Color.parseColor("#C62828")
        val blue = Color.parseColor("#1565C0")
        val grey = Color.parseColor("#546E7A")

        root.addView(status)
        val loadoutButton = button("", Color.parseColor("#00695C")) { cycleLoadout() }
        panelLoadoutButton = loadoutButton
        root.addView(loadoutButton, gap())
        root.addView(row(button("Start", green) { onPanelStart() }, button("Stop", red) { onPanelStop() }), gap())
        root.addView(row(button("Clicks ▸", blue) { switchToClickWindow() }, button("Hide", grey) { hidePanel() }), gap())

        val params = overlayParams().apply {
            x = 0
            y = px(60)
        }
        makeDraggable(status, root, params)

        try {
            wm.addView(root, params)
            panelView = root
            panelStatusText = status
            updateLoadoutLabels()
        } catch (e: Exception) {
            panelLoadoutButton = null
            toast("Couldn't show the run window: ${e.message}")
        }
    }

    fun hidePanel() {
        hideMarkers()
        val view = panelView ?: return
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view)
        } catch (_: Exception) {
        }
        panelView = null
        panelStatusText = null
        panelLoadoutButton = null
    }

    private fun onPanelStart() {
        if (jobActive) {
            toast("Already running. Tap Stop first.")
        } else {
            hideMarkers()
            startLoopAfterDelay(0L)
        }
    }

    private fun onPanelStop() {
        if (jobActive) {
            stopRequested = true
            toast("Stopping...")
        } else {
            toast("Nothing is running")
        }
    }

    private fun setPanelStatus(text: String) {
        mainHandler.post {
            panelStatusText?.text = "MyApp: $text"
            if (clickStatusText != null && !timerRunning) {
                counters = text
                refreshStatusBar()
            }
        }
    }

    private fun toast(message: String) {
        mainHandler.post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }
}