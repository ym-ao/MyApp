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

## Tile Library (built, needs on-device testing)

Every tile the reader looks at is remembered as a small picture of its number. In **Tile Library** you give pictures a level and lock them. The reader first finds where the number really is inside the box (calibrated points are a few pixels off, differently in each box), cuts out an aligned picture, and compares it with your locked pictures. A clear match (similarity 0.91 or more, and no other level nearly as close) gives the level straight away, in any box, with no text reading, which also makes scans faster. Only if nothing matches is the number read as text, and the text reading also uses the located spot. Several pictures can share one level, because the background behind the number changes. Confident reads are saved as "not confirmed", unsure or unreadable ones as "needs a level" (with the reader's guess shown). Typical use: tap **Read Grid Numbers**, open Tile Library, fix the "needs a level" pictures, check the rest, and lock them (or **Lock all with a level** once checked). Pictures saved by the first version are re-aligned automatically.

## Times in milliseconds, with random ranges (built, needs on-device testing)

Every time in the app is in milliseconds (drag duration, pause after each move, longest summon hold, pause between loops, the countdown before a test button starts, and the times of each custom click or drag). Each one has a **from** and a **to** box: the same number in both is a fixed time; a range such as 500 and 1000 uses a new random time in that range every time it is needed. Settings saved by earlier versions are converted automatically (seconds became milliseconds).

## Custom clicks & drags, auto-clicker style (built, needs on-device testing)

- The floating panel now has **+ Click**, **+ Drag**, **Points** (show or hide the markers), **Start**, **Stop** and **Hide**. Tap **+ Click** and a numbered marker appears; drag it onto the spot you want clicked. **+ Drag** gives two markers, a (start) and b (end). Positions are saved when you let go of a marker.
- **Custom Clicks & Drags** (main screen) shows the list: change each action's press length or drag duration and its wait afterwards (each a from/to range in milliseconds), reorder, delete, set how many times the whole list repeats, and run it.
- The list is also a loop step, **Custom clicks & drags**, in Loop Settings, so it can run before or after the merge, sort and summon steps. Untick the other steps and it works as a plain auto clicker.
- Markers are hidden while anything runs, because a marker sitting on a spot would catch the touch instead of the game. Clicks and drags are only valid in the screen orientation they were recorded in.

## Loop, step order, settings and floating panel (built, needs on-device testing)

- **Loop Settings & Step Order** (main screen): pick the grid, the summon click point and an optional extra click point; set drag duration, pause after each move, longest summon hold, number of loops (0 = until stopped or nothing is left to do) and the pause between loops; tick and reorder the steps (Merge chain, Sort board, Summon, Extra click). Settings save when you leave the screen.
- **The loop** runs the ticked steps in your order, over and over. It reads the board once, then keeps track of it in memory between merge and sort. With safety checks on, it compares the empty squares on screen with what it expects after every step and stops if they differ. After the summon step (or an extra click) the board is read again when needed. It ends when the loop count is reached, you stop it, nothing was left to do for two loops in a row, or the grid never fills up.
- **Floating panel**: Start / Stop / Hide bar over the game (an accessibility overlay, no extra permission). Drag it by its top label. It keeps the screen on while showing. Keep it off the grid so it doesn't appear in screenshots.
- Volume Down still stops any running job.

## Sort and merge chain (built, needs on-device testing)

Both read the board first, plan the drags, then perform them. Only tiles read confidently (green) are used; unsure and unreadable tiles are left alone. **Preview only** is on by default: it shows the plan in a dialog the next time you open MyApp and moves nothing.

- **Sort Board**: highest level in box 1, lowest at the end, empty squares last. The whole path is worked out from one scan. With **Swap tiles** ticked (Loop Settings, on by default) each fix is one drag, and a drag is only ever aimed at an empty square or at a tile of a different level, never at the same level (that would merge them); the finished plan is checked for this before it is used. Unticked, every drag lands on an empty square (tiles that must trade places go through a spare empty square). A merge during a sort can then only come from a misread tile, which locking pictures in the Tile Library prevents. Preview shows how many drops land on another tile.
- **Merge Chain**: takes the lowest tile that has a twin (a lowest tile with no twin is skipped), merges it onto the twin with the lowest box number, and keeps that box as the destination: while another tile of the new level exists (taken from the end of the board) it is dragged on too. With "check each merge" ticked, the app looks at the screen after each merge and stops if the source box still has a tile.
- Volume Down stops either one. Afterwards the board is read again; open **View Last Result** to check.

## Stable signing key
`app/debug-shared.p12` is a debug-only signing key so every GitHub build is signed the same way and installs over the previous one. Do not use it for a Play Store release.

## Roadmap (not yet built)
- **#1 (rest) - auto-sort:** use the OCR numbers to drag tiles highest to lowest at an adjustable speed
- **#3 - Manual click set:** record arbitrary tap points as a reusable action list
- **#4 - Sequencer:** chain #1/#3/#5 in a user-defined order
- **#5 - Auto-merge:** find the lowest tile, merge it with a matching neighbour, wait an adjustable delay, repeat
- Floating start/stop panel over the game, keep-alive/battery settings, in-app profile management
