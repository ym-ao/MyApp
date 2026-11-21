# MyApp

A minimal, working Android Studio project with broad compatibility already configured.

## How to build the APK
1. Install [Android Studio](https://developer.android.com/studio) (free).
2. Open Android Studio → **Open** → select this `MyApp` folder.
3. Let it sync (downloads Gradle wrapper + dependencies automatically — this step needs internet).
4. Click **Run ▶** to install on an emulator/device, or **Build → Build Bundle(s)/APK(s) → Build APK(s)** to get an installable `.apk` in `app/build/outputs/apk/`.

## Compatibility settings (in `app/build.gradle`)
- **minSdk 24** — supports Android 7.0+ (~98% of active devices). Lower to 21 for maximum reach, or raise it if you need newer-only APIs.
- **targetSdk 34** — matches current Google Play requirements.
- **compileSdk 34** — latest stable SDK to build against.
- **supports-screens** in the manifest — declares phone, tablet, and Chromebook support.
- **ConstraintLayout** in the UI — scales across different screen sizes automatically.

## Function #2: Grid Calibration (built)
Tap **Set Up Grid** on the main screen. A profile is made of one or more **regions** —
use one region per uniform section of the board (e.g. `main_board` for the 15x5 grid,
a separate `overflow` region for a strip elsewhere on screen). This is what lets one
profile describe an irregular real board: each region is uniform on its own, even if
the board as a whole isn't.

**Manual Tap mode:** tap each box directly on the image, in the order you want it read.

**Fast Grid mode:** tap the center of the top-left box, then the center of the
bottom-right box, enter row/column counts, tap **Generate** — it fills in every
evenly-spaced position between them. Only works for a uniform, evenly-spaced
rectangle, which is why it's per-region rather than whole-board.

Workflow: load a screenshot → mark a region (either mode) → **Add Region + Start Next**
→ repeat for each remaining section → **Save Grid Profile**.

**Tip:** calibrate the *full* grid (every possible cell, e.g. all 75 in a 15x5 board),
not just cells that are currently filled — empty cells still need a known position for
later OCR to check.

**Note:** calibration is tied to the screenshot's resolution/orientation. Re-calibrate if
you change devices or the game's zoom/scroll state.

## Accessibility Service + OCR read test (built, needs on-device testing)
1. Open the app, tap **Open Accessibility Settings**, find **MyApp automation**, turn it on.
   (Android 13+: if the switch is greyed out, Settings > Apps > MyApp > menu (three dots) > Allow restricted settings.)
2. Pick your saved grid in the spinner and tap **Read Grid Numbers**.
3. Within 5 seconds, open the game with the board visible. The app screenshots it and reads the number on every calibrated box (Android 11+).
4. Come back and tap **View Last Result**. Green = read (at least two of three attempts agreed), orange = unsure (only one attempt got a number), red "?" = a tile is there but no number could be read, grey dash = empty cell. Yellow boxes show the scanned area. The text at the bottom lists the first problem tiles and the raw text each attempt returned.

How reading works: empty cells are skipped (a flat color); for each tile the number area is cropped, processed three different ways, and each copy goes through on-device text recognition. Numbers must be 1-2 digits.

The service also has `tap()` and `drag()` ready for the sort / click / merge functions; they are not wired to any button yet.

## Click points + hold-until-full (built, needs on-device testing)

1. **Set Click Point**: load a screenshot of the game, tap the center of the summon button, name it (e.g. `summon`), save. Only the last tap is kept.
2. **Test Tap (5 s countdown)**: taps the saved point once, to check it lands on the button.
3. **Hold Button Until Grid Full (5 s countdown)**: presses and holds the click point, checking the grid about once a second. It lets go when no empty squares are left, after the max hold time (default 30 s), or when you press **Volume Down** (emergency stop).

Holding is done as back-to-back 1.1 s pieces of one press, so the finger never lifts. Needs Android 11 or newer.

## Saved Grids & Click Points (built, needs on-device testing)

Lists every saved grid and click point with its details. Tap one to use it (as the current grid, summon point or extra click point) or delete it; deleting also clears it from the settings if it was in use.

## Sort passes (built, needs on-device testing)

Loop Settings has **Sort passes per loop** (default 3) and **Most drags in one sort pass** (default 20, 0 = all). A pass plans from the board as it is, does the drags, then the board is read again and the next pass plans from what is really there; it stops early when the board is sorted. If a lag spike makes a drag miss, the next pass corrects it instead of the rest of an old plan running on a board that changed. A smaller number of drags per pass checks the board more often (a missed drag can then affect fewer later drags). If lag spikes are frequent, also try a longer drag duration or a range for the drag and pause times, and consider unticking Swap tiles so every drag lands on an empty square.

## Speed of reading the board

Text reading is slow, so it is avoided: (1) every tile picture already in the Tile Library is recognised without text reading (every remembered picture, locked or not, matches at 0.91 similarity, so a picture that matches the library is never added to it again); a remembered picture that still has no level is reported as unreadable straight away ("library: needs a level") instead of being read again; (2) several tiles with the same new picture on one board are read as text once; (3) a third text reading is only done when the first two disagree. So the first scan on a new board is the slow one; after that, scans only look at new pictures. In Loop Settings, **Merge chain: read the board again after every merge** can be unticked for a much faster chain that trusts its own plan (it can't follow the game's upgrades).

## Tile Library (built, needs on-device testing)

Every tile the reader looks at is remembered as a small picture of its number. In **Tile Library** you give pictures a level and lock them. The reader first finds where the number really is inside the box (calibrated points are a few pixels off, differently in each box), cuts out an aligned picture, and compares it with your locked pictures. A clear match (similarity 0.91 or more, and no other level nearly as close) gives the level straight away, in any box, with no text reading, which also makes scans faster. Only if nothing matches is the number read as text, and the text reading also uses the located spot. Several pictures can share one level, because the background behind the number changes. Confident reads are saved as "not confirmed", unsure or unreadable ones as "needs a level" (with the reader's guess shown). Typical use: tap **Read Grid Numbers**, open Tile Library, fix the "needs a level" pictures, check the rest, and lock them (or **Lock all with a level** once checked). Pictures saved by the first version are re-aligned automatically.

## App layout: tabs, loadouts, Code (built, needs on-device testing)

The app is now one screen with tabs:
- **Run**: service status, loadouts (choose, New, Rename, Delete), the floating panel, Start Loop, and the one-piece tests (Merge Chain, Sort Board, Hold Summon, Test Tap).
- **Setup**: Set Up Grid, Set Click Point, Read Grid Numbers, View Last Result, and the list of saved grids and click points (use or delete).
- **Loadout**: the settings of the active loadout (grid, click points, all times, loop options, sort and merge options, step order). Saves when you leave the tab.
- **Clicks**: the custom clicks and drags of the active loadout.
- **Library**: the Tile Library.
- **Code**: read-only copies of the files that decide how sorting, merging and reading work (BoardPlanner, AutomationService, GridReader, TileVision, TileLibrary) with a **Copy** button.

**Loadouts** are named sets of settings, step order and custom clicks. Your earlier setup is **Loadout 1**: it keeps using the same files, so nothing was copied or lost. **New** makes a copy of the current loadout. Grids, click points and the Tile Library are shared by all loadouts. The floating panel has a **Loadout** button that switches to the next loadout (not while something is running). The files shown in the Code tab are copies kept in `app/src/main/assets/code/`; they are refreshed whenever a build is prepared.

## Times in milliseconds, with random ranges (built, needs on-device testing)

Every time in the app is in milliseconds (drag duration, pause after each move, longest summon hold, pause between loops, the countdown before a test button starts, and the times of each custom click or drag). Each one has a **from** and a **to** box: the same number in both is a fixed time; a range such as 500 and 1000 uses a new random time in that range every time it is needed. Settings saved by earlier versions are converted automatically (seconds became milliseconds).

## Click window, run window and the custom list (built, needs on-device testing)

There are two floating windows, and each switches into the other:
- **Run window**: Loadout (tap to switch to the next loadout), Start, Stop, **Clicks ▸** (opens the click window) and Hide.
- **Click window** (auto-clicker style): a toolbar and a status bar. Toolbar: ▶ run the list, ● add a hold, ＋ add a tap, ↗ add a swipe, ƒ add a step (Sort, Merge, Summon, Wait), − remove the last step, ✓ save (everything is saved as you go), ⚙ open the app, ◉ show or hide the markers, ⇄ switch to the run window, ⟳ switch loadout, ✕ close. Tap the top arrow to collapse it. Status bar: ■ stop, ⏸ pause, loop and step counters, a timer, and the loadout name. Both windows can be dragged.
- **Markers**: numbered circles on the real screen. Drag one onto a spot (a swipe has a and b). **Tap a marker** to open that step's settings (touch and hold time, delay after the step, repetitions, anti-detection offset, delete). Android may refuse to open the settings screen from a floating window; the app then says so, and the same settings are in the Clicks tab.
- **Holds** can be any length (seconds, minutes, hours): the hold is chained from short pieces of one press.
- The list (per loadout) can mix taps, swipes, holds, waits and the app's own **Sort, Merge and Summon** steps, in any order, each with its own repetitions and times (every time is a from/to range in milliseconds). It runs once or repeats as set in the Clicks tab, and it is also a step of the loop.
- The toolbar and markers hide while something runs (they would block touches and show up in screenshots); the small status bar stays. Keep windows off the grid.

## Loop behaviour

A problem inside a loop (a missed drag, a board that hasn't settled after a lag spike, a failed screenshot) does not end the run: the next loop starts with a fresh read. The run ends when you stop it, when the loop count is reached, after the set number of problems in a row (Loadout tab, default 5), or, if you tick it, when nothing was left to do for two loops. The reason it ended stays on the Run tab as "Last run ended ...".

## Loop, step order, settings and floating panel (built, needs on-device testing)

- **Loop Settings & Step Order** (main screen): pick the grid, the summon click point and an optional extra click point; set drag duration, pause after each move, longest summon hold, number of loops (0 = until stopped or nothing is left to do) and the pause between loops; tick and reorder the steps (Merge chain, Sort board, Summon, Extra click). Settings save when you leave the screen.
- **The loop** runs the ticked steps in your order, over and over. It reads the board once, then keeps track of it in memory between merge and sort. With safety checks on, it compares the empty squares on screen with what it expects after every step and stops if they differ. After the summon step (or an extra click) the board is read again when needed. It ends when the loop count is reached, you stop it, nothing was left to do for two loops in a row, or the grid never fills up.
- **Floating panel**: Start / Stop / Hide bar over the game (an accessibility overlay, no extra permission). Drag it by its top label. It keeps the screen on while showing. Keep it off the grid so it doesn't appear in screenshots.
- Volume Down still stops any running job.

## Sort and merge chain (built, needs on-device testing)

Both read the board first, plan the drags, then perform them. Only tiles read confidently (green) are used; unsure and unreadable tiles are left alone. **Preview only** is on by default: it shows the plan in a dialog the next time you open MyApp and moves nothing.

- **Sort Board**: highest level in box 1, lowest at the end, empty squares last. The whole path is worked out from one scan. With **Swap tiles** ticked (Loop Settings, on by default) each fix is one drag, and a drag is only ever aimed at an empty square or at a tile of a different level, never at the same level (that would merge them); the finished plan is checked for this before it is used. Unticked, every drag lands on an empty square (tiles that must trade places go through a spare empty square). A merge during a sort can then only come from a misread tile, which locking pictures in the Tile Library prevents. Preview shows how many drops land on another tile.
- **Merge Chain**: (1) the lowest tile (last in box order) is merged onto its neighbour in the previous box if that has the same level (otherwise onto the last other tile of that level); that result is only a carrier, not the destination. (2) The carrier is merged onto the highest-ranked (lowest box number) tile of the same level; that tile is the destination from now on. (3) While another tile of the destination's current level exists (taken from the end of the board), it is merged onto the destination. The whole chain is planned from one scan, like the sort, and nothing is read while tiles are moving; the level of the destination is carried along (two tiles of level L make one of level L + 1) instead of being read from the screen, because a freshly merged tile is often a picture the reader has never seen. With Safety checks on, the screen is checked after each merge (source box empty) and the chain stops if a merge didn't happen. It ends when no tile of the destination's level is left or you stop it. An optional setting reads the board again after every merge (slower; the game's upgrades can then be followed). Only confidently read tiles are used. Preview shows what the chain would do if nothing else changed.
- Volume Down stops either one. Afterwards the board is read again; open **View Last Result** to check.

## Stable signing key
`app/debug-shared.p12` is a debug-only signing key so every GitHub build is signed the same way and installs over the previous one. Do not use it for a Play Store release.

## Roadmap (not yet built)
- **#1 (rest) - auto-sort:** use the OCR numbers to drag tiles highest to lowest at an adjustable speed
- **#3 - Manual click set:** record arbitrary tap points as a reusable action list
- **#4 - Sequencer:** chain #1/#3/#5 in a user-defined order
- **#5 - Auto-merge:** find the lowest tile, merge it with a matching neighbour, wait an adjustable delay, repeat
- Floating start/stop panel over the game, keep-alive/battery settings, in-app profile management

## Updating the project on your phone

Unzipping over the project adds and replaces files but never deletes files that were removed. When an update removes files (the tab update removed four old screens), clear the source folder first, then unzip, then ship:

    cd ~/storage/downloads && rm -rf ~/MyApp/app/src && unzip -o MyApp.zip -d ~ && ~/ship.sh "what changed"

Nothing you need lives in `app/src` (grids, click points, loadouts and the library are stored on the phone by the app), and git records the deletions.
