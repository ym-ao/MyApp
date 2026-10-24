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

## Roadmap (not yet built)
- **#1 — OCR auto-sort:** read the number at each calibrated box (ML Kit Text Recognition), then drag tiles into highest→lowest order at an adjustable speed
- **#3 — Manual click set:** record arbitrary tap points as a separate, reusable action list
- **#4 — Sequencer:** chain #1/#2/#3 in a user-defined order
- **#5 — Auto-merge:** find the lowest tile, merge it with a matching neighbor, wait an adjustable delay, repeat

All of #1, #3, and #5 require an Accessibility Service (to read live screen content and inject taps/drags) — that's the next piece to build.
