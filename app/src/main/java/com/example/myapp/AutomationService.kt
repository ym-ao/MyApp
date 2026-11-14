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
import com.example.myapp.storage.MsRange
import com.example.myapp.storage.PlanStore
import com.example.myapp.storage.SettingsStore
import com.example.myapp.storage.StepType

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

    private fun endJob(message: String) {
        jobActive = false
        stopRequested = false
        setPanelStatus("Ready")
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
        jobActive = true
        stopRequested = false
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
        jobActive = true
        stopRequested = false
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

        jobActive = true
        stopRequested = false
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

        fun start() = nextLoop()

        private fun finish(message: String) = endJob(message)

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
                    finish(error ?: "Couldn't read the board")
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
                        finish("Loop $loopNo: $error")
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
                        finish("Loop $loopNo: $error")
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
                        finish("Loop $loopNo: $message")
                        return@startHold
                    }
                }
                if (summonTimeouts >= 2) {
                    finish("The grid never fills up (some squares may be blocked). Stopped.")
                    return@startHold
                }
                mainHandler.postDelayed({ runStep(index + 1) }, settings.pause.pick() + 500L)
            }
        }

        private fun customStep(index: Int) {
            setPanelStatus("${label()}: custom actions")
            runCustom { ok, message ->
                if (!ok) {
                    finish("Loop $loopNo: $message")
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
                    finish(error ?: "Screenshot failed")
                    return@captureScreen
                }
                if (!sameOrientation(point, bitmap)) {
                    finish("Screen orientation doesn't match the extra click point. Rotate and try again.")
                    return@captureScreen
                }
                val x = point.x * bitmap.width.toFloat() / point.screenWidth
                val y = point.y * bitmap.height.toFloat() / point.screenHeight
                tap(x, y, 80L) { ok ->
                    if (!ok) {
                        finish("The extra click was blocked or cancelled")
                        return@tap
                    }
                    didWork = true
                    board = null   // a click may change the board
                    mainHandler.postDelayed({ runStep(index + 1) }, settings.pause.pick() + 300L)
                }
            }
        }

        private fun endOfLoop() {
            if (didWork) idleLoops = 0 else idleLoops++
            if (idleLoops >= 2) {
                finish("Nothing left to do after $loopNo loop(s)")
                return
            }
            setPanelStatus("${label()} done")
            mainHandler.postDelayed({ nextLoop() }, settings.loopPause.pick())
        }
    }

    // ---- custom clicks and drags (auto-clicker style) ----

    private fun screenSize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = (getSystemService(WINDOW_SERVICE) as WindowManager).maximumWindowMetrics.bounds
            Pair(b.width(), b.height())
        } else {
            Pair(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        }

    /** Performs the recorded clicks and drags in order, repeated as set. Times are random within their ranges. */
    private fun runCustom(onFinished: (Boolean, String) -> Unit) {
        val data = ActionStore.load(this)
        if (data.actions.isEmpty()) {
            onFinished(false, "No custom clicks or drags yet. Add some with + Click and + Drag on the floating panel.")
            return
        }
        val (w, h) = screenSize()
        if ((data.screenWidth > data.screenHeight) != (w > h)) {
            onFinished(false, "The clicks and drags were recorded in the other screen orientation")
            return
        }
        hideMarkers()   // the markers would block the touches
        val sx = w.toFloat() / data.screenWidth
        val sy = h.toFloat() / data.screenHeight
        val count = data.actions.size
        val total = count * data.repeat

        fun step(n: Int) {
            if (stopRequested) {
                onFinished(false, "Stopped after $n of $total actions")
                return
            }
            if (n >= total) {
                onFinished(true, "")
                return
            }
            val a = data.actions[n % count]
            val hold = MsRange(a.holdMin, a.holdMax).pick().coerceAtLeast(1L)
            val wait = MsRange(a.waitMin, a.waitMax).pick()
            setPanelStatus("action ${n % count + 1} of $count")
            val done: (Boolean) -> Unit = { ok ->
                if (!ok) onFinished(false, "Action ${n % count + 1} was blocked or cancelled")
                else mainHandler.postDelayed({ step(n + 1) }, wait)
            }
            if (a.type == ActionType.CLICK) {
                tap(a.x1 * sx, a.y1 * sy, hold, done)
            } else {
                drag(a.x1 * sx, a.y1 * sy, a.x2 * sx, a.y2 * sy, hold, done)
            }
        }
        step(0)
    }

    /** After a countdown, performs the recorded clicks and drags once (or as often as "repeat" says). */
    fun runCustomAfterDelay(delayMs: Long) {
        if (jobActive) {
            toast("Already running. Press Volume Down, or Stop on the panel.")
            return
        }
        jobActive = true
        stopRequested = false
        toast("Switch to the game now. Starting in $delayMs ms. Press Volume Down to stop.")
        mainHandler.postDelayed({
            runCustom { ok, message -> endJob(if (ok) "Custom clicks and drags finished" else message) }
        }, delayMs)
    }

    private class Marker(val view: TextView, val params: WindowManager.LayoutParams)

    private val markers = mutableListOf<Marker>()

    /** Used by the Custom Clicks & Drags screen after the list was cleared. */
    fun hidePanelMarkers() = hideMarkers()

    private fun toggleMarkers() {
        if (markers.isEmpty()) showMarkers() else hideMarkers()
    }

    /** One draggable marker per click, two (a and b) per drag, so positions can be set on the real screen. */
    private fun showMarkers() {
        hideMarkers()
        val data = ActionStore.load(this)
        if (data.actions.isEmpty()) {
            toast("No clicks or drags yet. Tap + Click or + Drag.")
            return
        }
        data.actions.forEachIndexed { i, a ->
            if (a.type == ActionType.CLICK) {
                addMarker("${i + 1}", a.x1, a.y1, i, false)
            } else {
                addMarker("${i + 1}a", a.x1, a.y1, i, false)
                addMarker("${i + 1}b", a.x2, a.y2, i, true)
            }
        }
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

    @SuppressLint("ClickableViewAccessibility")
    private fun addMarker(label: String, cx: Int, cy: Int, actionIndex: Int, isEnd: Boolean) {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val size = (46 * resources.displayMetrics.density).toInt()
        val view = TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 13f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(170, 33, 150, 243))
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
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - touchX).toInt()
                    params.y = startY + (event.rawY - touchY).toInt()
                    wm.updateViewLayout(v, params)
                }
                MotionEvent.ACTION_UP -> saveMarkerPosition(v, actionIndex, isEnd)
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
            if (a.type == ActionType.CLICK) {
                a.x2 = cx
                a.y2 = cy
            }
        }
        ActionStore.save(this, data)
    }

    private fun addAction(type: ActionType) {
        val (w, h) = screenSize()
        val data = ActionStore.load(this)
        if (data.actions.isEmpty()) {
            data.screenWidth = w
            data.screenHeight = h
        } else if ((data.screenWidth > data.screenHeight) != (w > h)) {
            toast("The saved clicks and drags were recorded in the other orientation. Rotate back, or clear them in Custom Clicks & Drags.")
            return
        }
        val n = data.actions.size
        val cx = w / 2 + (n % 6) * 70
        val cy = h / 2 + (n % 6) * 35
        val action = if (type == ActionType.CLICK) {
            CustomAction(ActionType.CLICK, cx, cy, cx, cy, 50L, 50L, 500L, 500L)
        } else {
            CustomAction(ActionType.DRAG, cx - 150, cy, cx + 150, cy, 300L, 300L, 500L, 500L)
        }
        data.actions.add(action)
        ActionStore.save(this, data)
        showMarkers()
        toast(
            if (type == ActionType.CLICK) "Click ${n + 1} added: drag its marker onto the spot"
            else "Drag ${n + 1} added: put marker a at the start and b at the end"
        )
    }

    // ---- floating panel (shown over the game) ----

    fun isPanelShown(): Boolean = panelView != null

    /**
     * A small draggable bar: Start / Stop for the loop, + Click and + Drag to record custom
     * actions (like an auto clicker), Points to show or hide the draggable markers, and Hide.
     * It uses an accessibility overlay, so no "display over other apps" permission is needed.
     * It keeps the screen on while it is showing. Drag it (by its top label) off the grid, e.g.
     * into the black side bar, so it doesn't end up in the screenshots.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun showPanel() {
        if (panelView != null) return
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
        root.addView(row(button("Start", green) { onPanelStart() }, button("Stop", red) { onPanelStop() }), gap())
        root.addView(row(button("+ Click", blue) { addAction(ActionType.CLICK) }, button("+ Drag", blue) { addAction(ActionType.DRAG) }), gap())
        root.addView(row(button("Points", grey) { toggleMarkers() }, button("Hide", grey) { hidePanel() }), gap())

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = px(60)
        }

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        status.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - touchX).toInt()
                    params.y = startY + (event.rawY - touchY).toInt()
                    wm.updateViewLayout(root, params)
                }
            }
            true
        }

        try {
            wm.addView(root, params)
            panelView = root
            panelStatusText = status
        } catch (e: Exception) {
            toast("Couldn't show the panel: ${e.message}")
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
        mainHandler.post { panelStatusText?.text = "MyApp: $text" }
    }

    private fun toast(message: String) {
        mainHandler.post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }
}
