# Bunshin UI Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace both UI surfaces — the overlay panel and the editor activity — with one designed system, without changing any automation behaviour.

**Architecture:** androidx (appcompat + material + recyclerview) on a dark Material3 theme, with all sizing in `dimens.xml`. The overlay becomes a draggable dock bar that collapses to a status pill during a run. The editor becomes three screens: profiles → macro → step sheet. One structural refactor is required: `Runner` ownership moves from `Overlay` to `ShadowService` so a run survives the overlay being torn down.

**Tech Stack:** Kotlin, Android API 30+/target 35, androidx appcompat/material/recyclerview, OpenCV 4.14 (unchanged), plain Views (no Compose).

**Spec:** `docs/superpowers/specs/2026-11-25-ui-redesign-design.md`

## Global Constraints

- Target device: OnePlus 13 (CPH2653), 1080 × 2376, density 420 → **411 × 905 dp**. Verify on it.
- **No automation logic changes.** Record, replay, stop rules, foreground pause, scoring and template handling behave exactly as they do now. The two permitted structural changes are named in Task 3 and Task 4.
- **Stop must be reachable in one tap in every state** the panel can be in, armed or running.
- **Nothing new may be drawn on the overlay during a capture.** `HudView.showMarks` is the existing gate; anything new obeys it.
- **The panel is never hidden for a capture.** It must appear in record frames and replay frames alike, or `Templates.kt`'s invariant — "a score shown while tuning is the score replay will get" — breaks.
- Overlay windows keep `FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS`, `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS`, and raw coordinates.
- `MainActivity.editorOpen` must keep gating overlay creation.
- Spacing values: `4, 8, 12, 16, 24, 32` dp only. Gutter 16, row gap 8, 24 above a section header and 8 below. Rows 72 dp, buttons 48–56 dp, nothing below 48 dp.
- Type sizes: `20sp` title, `15sp` row, `13sp` secondary. No others.
- Colours, verbatim: primary `#FF8A3C`, pressed `#E8701A`, recessed `#C96A20`; rec `#FF453A`, no-tap `#FFD426`, watcher `#4C8DFF`, play/hit `#32D74B`; background `#121316`, surface `#1C1E22`, raised `#262A31`, line `#333841`, text `#F2F3F5`, muted `#9AA0A8`. Dark only.
- No raw pixel literals in view code. Every size comes from `dimens.xml`.

## Verification Model

This project has no test source set. Its established pattern is `Macro.selfTest()` — assertions over the logic that has no UI — plus on-device verification over adb, which is how M1–M3 were signed off in `PLAN.md`. This plan uses both:

- **Logic** gets assertions in `selfTest()`. Task 1 makes that readable from the shell.
- **UI** is verified by installing and reading a screenshot: `adb -s $D exec-out screencap -p > shot.png`.
- `$D` is the device serial from `adb devices` (wireless; `adb pair` then `adb connect`).
- **Never run `adb shell uiautomator dump` while a macro is playing.** `PLAN.md` records that it re-binds the service and silently stops the run.

## Review Focus

Five failure modes the spec implies that no task's own deliverable exercises. Each has a test added to the task that owns the code.

1. **Service re-bind during a run.** Moving `Runner` into the service changes what `onUnbind` means. Today it silently kills the run; a rebind happens on any `uiautomator dump`. A run must not die silently — it stops with a stated reason, or survives. *Pinned in Task 4.*
2. **Dock dragged to a screen edge or under the cutout.** If the clamp is wrong, Stop can end up partly offscreen or under the status bar, which is the one thing that must never happen. *Pinned in Task 5.*
3. **`getApplicationIcon` throwing `NameNotFoundException`.** Android 11+ package visibility may hide the target app; the list must render a fallback tile rather than crash. *Pinned in Task 8.*
4. **A macro with zero steps but watchers or no-tap rules recorded.** `Runner.start` already refuses, but the Run button, the tabs and the pill must all cope. *Pinned in Task 9.*
5. **A ten-step macro opened for the first time after recording.** The smoke "appears" trigger must not puff ten times at once. *Pinned in Task 11.*

---

## File Structure

**Created**
- `app/src/main/res/values/colors.xml` — the palette, verbatim from Global Constraints.
- `app/src/main/res/values/dimens.xml` — the spacing scale, row/button heights, type sizes.
- `app/src/main/res/values/themes.xml` — `Theme.Bunshin`, a dark Material3 NoActionBar theme.
- `app/src/main/res/drawable/ic_seal.xml` — the mark.
- `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` + `ic_launcher_background.xml` — adaptive icon.
- `app/src/main/java/com/shadowclone/RunState.kt` — the structured run state the pill reads.
- `app/src/main/java/com/shadowclone/Scores.kt` — the last-score cache.
- `app/src/main/java/com/shadowclone/Dock.kt` — the dock bar and status pill views.
- `app/src/main/java/com/shadowclone/Smoke.kt` — the smoke drawable/view.
- `app/src/main/java/com/shadowclone/ProfilesActivity.kt` — screen 1.
- `app/src/main/java/com/shadowclone/MacroActivity.kt` — screen 2.
- `app/src/main/java/com/shadowclone/StepSheet.kt` — screen 3.
- `app/src/main/java/com/shadowclone/Apps.kt` — app label and icon lookup.

**Modified**
- `app/build.gradle.kts` — androidx dependencies.
- `app/src/main/AndroidManifest.xml` — theme, icon, `<queries>` if needed.
- `ShadowService.kt` — owns `Runner`; logs `selfTest` on connect.
- `Runner.kt` — second structured callback; no logic change.
- `Macro.kt` — `name` field; `selfTest` additions.
- `Overlay.kt` — panel replaced by `Dock`; crop-overlap warning.
- `MainActivity.kt` — becomes `ProfilesActivity` or a thin launcher for it.

---

### Task 1: androidx, dark theme, design tokens, and a readable self-test

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/values/colors.xml`, `dimens.xml`, `themes.xml`
- Modify: `app/src/main/java/com/shadowclone/ShadowService.kt:40-48` (`onServiceConnected`)
- Modify: `app/src/main/java/com/shadowclone/MainActivity.kt:12` (superclass)

**Interfaces:**
- Consumes: nothing.
- Produces: `Theme.Bunshin`; the colour and dimen names every later task uses; `selfTest` output in logcat under tag `Bunshin`.

- [ ] **Step 1: Make `selfTest` readable from the shell**

In `ShadowService.onServiceConnected`, after `OpenCVLoader.initLocal()`, add `Log.i(TAG, Macro.selfTest())`. This is the plan's test harness: every later task's logic assertions are read with one adb command.

- [ ] **Step 2: Verify it fails to appear yet**

Run: `adb -s $D logcat -d -s Bunshin | grep "self-test"`
Expected: no output (the change is not installed).

- [ ] **Step 3: Add the androidx dependencies**

In `app/build.gradle.kts`, add to `dependencies`: `androidx.appcompat:appcompat`, `com.google.android.material:material`, `androidx.recyclerview:recyclerview`. Pick current stable versions. `android.useAndroidX=true` is already set in `gradle.properties`.

- [ ] **Step 4: Write the three resource files**

`colors.xml` and `dimens.xml` carry exactly the values in Global Constraints, no others. `themes.xml` defines `Theme.Bunshin` on a Material3 dark `NoActionBar` parent, with `colorPrimary` = primary, `android:colorBackground` = background, `colorSurface` = surface.

- [ ] **Step 5: Switch the activity and the manifest**

`MainActivity` extends `AppCompatActivity`. The manifest's `android:theme` becomes `@style/Theme.Bunshin`.

- [ ] **Step 6: Build, install, verify**

Run: `./gradlew assembleDebug && adb -s $D install -r app/build/outputs/apk/debug/app-debug.apk`
Run: `adb -s $D logcat -c && adb -s $D shell am force-stop com.shadowclone`, re-enable the service, then `adb -s $D logcat -d -s Bunshin | grep "self-test"`
Expected: `macro self-test PASS`.
Run: launch the editor and screencap.
Expected: the existing editor, unchanged in content, now on a dark background.

- [ ] **Step 7: Commit**

```bash
git add app/build.gradle.kts app/src/main/AndroidManifest.xml app/src/main/res/values app/src/main/java/com/shadowclone/ShadowService.kt app/src/main/java/com/shadowclone/MainActivity.kt
git commit -m "feat(ui): androidx, dark theme and design tokens"
```

---

### Task 2: The app mark

**Files:**
- Create: `app/src/main/res/drawable/ic_seal.xml`, `app/src/main/res/drawable/ic_launcher_foreground.xml`
- Create: `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`, `app/src/main/res/values/ic_launcher_background.xml`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Produces: `@drawable/ic_seal` (monochrome-tintable, 24 dp viewport) for in-app use; `@mipmap/ic_launcher`.

- [ ] **Step 1: Draw the mark**

Two pairs of crossed fingers rotated 20° inside an open ring. Front pair `@color/primary`, pair behind `@color/primary_recessed`, separated by a background-coloured cut. The ring is a stroked circle with a gap, drawn with a round cap. Reference geometry, on a 64-unit viewport: ring `cx=32 cy=32 r=27`, stroke 3.5, dash `125 45`, rotated −58°; fingers 7 units wide, 38 long, at `x=24` / `x=33.5` and `y=24` / `y=33.5`, all rotated 20° about the centre.

- [ ] **Step 2: Wire it up**

Adaptive icon with `ic_launcher_background` = `#121316` and the mark as foreground, inset to the 66 dp safe zone. Manifest gains `android:icon` and `android:roundIcon`.

- [ ] **Step 3: Install and verify**

Run: install, then `adb -s $D shell monkey -p com.shadowclone 1`, screencap the launcher.
Expected: the mark renders on the launcher, the ring is visibly open, and the crossing is legible at launcher size.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/drawable app/src/main/res/mipmap-anydpi-v26 app/src/main/res/values/ic_launcher_background.xml app/src/main/AndroidManifest.xml
git commit -m "feat(ui): clone seal app mark"
```

---

### Task 3: Macro name, score cache, structured run state

**Files:**
- Create: `app/src/main/java/com/shadowclone/RunState.kt`, `app/src/main/java/com/shadowclone/Scores.kt`
- Modify: `app/src/main/java/com/shadowclone/Macro.kt:78-90` (class), `:97-103` (`text`), `:160-172` (`parse`), `:176+` (`selfTest`)
- Modify: `app/src/main/java/com/shadowclone/Runner.kt:16-20` (constructor), `:93-96` (`where`)
- Modify: `app/src/main/java/com/shadowclone/Overlay.kt` (`score`)

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `Macro.name: String?` — persisted under key `"name"`; null means "use the app label".
  - `data class RunState(val step: Int, val steps: Int, val loop: Int, val loops: Int, val score: Double, val paused: Boolean, val pausedFor: String?)`
  - `Runner(svc, macro, onState: (String) -> Unit, onRun: (RunState) -> Unit)`
  - `Scores.put(template: String, score: Double)`, `Scores.get(template: String): Pair<Double, Long>?` returning score and the `System.currentTimeMillis()` it was taken at.

- [ ] **Step 1: Write the failing assertions in `Macro.selfTest()`**

```kotlin
// name survives the JSON round trip, and absence stays absent
val named = Macro(); named.name = "Challenge loop"
if (parse(named.text()).name != "Challenge loop") bad += "name-json"
if (parse(Macro().text()).name != null) bad += "name-absent"
// files written before this field load without one
if (parse("""{"steps":[]}""").name != null) bad += "name-old-json"
// the score cache round trips and ages
Scores.put("t0.png", 0.94)
val got = Scores.get("t0.png")
if (got == null || got.first != 0.94) bad += "scores"
if (Scores.get("missing.png") != null) bad += "scores-absent"
```

- [ ] **Step 2: Run it and watch it fail**

Run: build, install, restart the service, `adb -s $D logcat -d -s Bunshin | grep "self-test"`
Expected: `FAIL` naming `name-json` (it will not compile until `name` and `Scores` exist — add them as empty declarations first if preferred, then watch the assertions fail).

- [ ] **Step 3: Add `Macro.name`**

A `var name: String? = null` on `Macro`, written in `text()` only when non-null, read in `parse` with `optString`. Nothing else reads it yet.

- [ ] **Step 4: Add `Scores`**

An object holding an in-memory `HashMap<String, Pair<Double, Long>>`. In-memory only: a score's value is its freshness, and one that outlives the process is misleading.

- [ ] **Step 5: Add `RunState` and the second callback**

`Runner` gains an `onRun: (RunState) -> Unit` parameter with a no-op default so existing construction keeps compiling. Emit it everywhere `onState` is already emitted, built from the fields `where()` already reads (`i`, `loop`, `macro.loops`, `paused`). `pausedFor` is the package name when the foreground check is what paused it, null otherwise. **No control flow changes.**

- [ ] **Step 6: Write every score through `Scores`**

In `Runner.onFrame` and in `Overlay.score()`, after each `Templates.match`, call `Scores.put(step.template, score)`. Both already compute it.

- [ ] **Step 7: Run the assertions and verify they pass**

Run: build, install, restart the service, grep logcat.
Expected: `macro self-test PASS`.

- [ ] **Step 8: Verify replay still works end to end**

Run: with the existing macro, press Play on the panel and screencap.
Expected: the run completes exactly as before; the readout is unchanged.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/shadowclone/
git commit -m "feat: macro name, score cache and structured run state"
```

---

### Task 4: Runner moves into ShadowService

**Files:**
- Modify: `app/src/main/java/com/shadowclone/ShadowService.kt`
- Modify: `app/src/main/java/com/shadowclone/Overlay.kt:313-340` (`playPause`, `stopAll`)

**Interfaces:**
- Consumes: `RunState` from Task 3.
- Produces:
  - `ShadowService.runner: Runner?` (private) with `fun startRun(): Boolean`, `fun togglePause()`, `fun stopRun(why: String)`, `val runActive: Boolean`, `val runPaused: Boolean`.
  - `ShadowService.onRun: ((RunState) -> Unit)?` — whichever surface is on screen subscribes.

This is a behaviour-preserving refactor. Every acceptance check below is a behaviour `PLAN.md` already records as working; all of them must still pass.

- [ ] **Step 1: Move ownership**

`Runner` is constructed and held by `ShadowService`, not `Overlay`. `Overlay` calls `svc.startRun()` / `svc.togglePause()` / `svc.stopRun()` and renders from `svc.onRun`.

`Overlay.hide()` currently calls `stopAll`, and that is the only thing ending a run when the editor opens. Splitting it:
- **Hide (the button) still stops the run.** The user asked for the panel to go away; leaving a run going with no panel is the anti-goal.
- **`hide()` still disarms recording** in both cases.
- **The editor pauses instead of stopping.**

- [ ] **Step 2: Make "editor open" a pause reason**

Without this the run does not pause while the editor is up, and it is not obvious why. `Runner.onFrame` tests `svc.currentPackage()`, and `ShadowService.ignoredPackages` contains `packageName` — so with our own editor in front, `foregroundPackage` keeps returning the **game**, the foreground guard passes, and the run captures and scores the *editor's* screen. Every template reads near `0.43` (`PLAN.md`'s measured not-on-screen baseline), the step burns its timeout and halts. Throughout, the overlay is gated off and the editor has no Stop.

So: while any editor screen is started, the runner pauses with `pausedFor = "editor open"` — clock held, no capture taken, nothing matched. The existing resume path takes over when the editor closes. This is also what makes Task 9's "Run begins paused" true.

- [ ] **Step 3: Decide what a re-bind does, and write it down**

`onUnbind` currently hides the overlay and takes the run with it. With the runner in the service it would survive the overlay but not a real teardown. Make the behaviour explicit: on `onUnbind`, stop the run with the reason `"stopped: the service was unbound"` and `Log.w` it. A silent stop is the failure mode `PLAN.md` spent two milestones diagnosing. *(Review Focus 1.)*

- [ ] **Step 4: Verify the editor pauses a run rather than killing or corrupting it**

Run: start a run, open the editor via `adb -s $D shell am start -n com.shadowclone/.MainActivity`, wait 10 s, then `adb -s $D logcat -d -s Bunshin | tail -20` and leave the editor.
Expected: the log shows the run paused for "editor open" and **no captures taken** while it was up — no timeout, no "stopped", no scores near 0.43. The run resumes when the editor closes.

- [ ] **Step 5: Verify Hide still stops a run**

Run: start a run, press Hide on the panel.
Expected: the run stops. The panel going away must never leave a run going.

- [ ] **Step 6: Verify the M2/M3 behaviours are intact**

Each of these is recorded in `PLAN.md` as verified; re-run them.
- Record a tap: it is eaten, cropped, saved and re-dispatched (the target app reacts).
- Replay: the step matches, the gesture is shifted to the match, the run reports done.
- A no-tap rule halts the run and the step never fires.
- Foreground pause: starting in the wrong app reports paused; returning resumes it on its own.
- Pause holds the clock: pause at ~4 s of a 15 s timeout, wait 20 s, resume — the readout resumes near 6 s, not 26 s.

- [ ] **Step 7: Verify the re-bind behaviour**

Run: start a run, then `adb -s $D shell uiautomator dump` to force a re-bind, then `adb -s $D logcat -d -s Bunshin | tail -20`.
Expected: the stop is logged with its reason. Not silent.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/shadowclone/ShadowService.kt app/src/main/java/com/shadowclone/Overlay.kt
git commit -m "refactor: service owns the runner so a run outlives the overlay"
```

---

### Task 5: The dock bar

**Files:**
- Create: `app/src/main/java/com/shadowclone/Dock.kt`
- Modify: `app/src/main/java/com/shadowclone/Overlay.kt:35-45` (view fields), `:80-115` (`show`), `:130-155` (`row`, `style`, `button`, `movePanel`), `:200-210` (`labels`), `:395-407` (`status`)

**Interfaces:**
- Consumes: the colours and dimens from Task 1; `svc.startRun()` etc. from Task 4.
- Produces: `Dock.view: View`, `Dock.setArmed(kind: Kind?)`, `Dock.say(message: String)`, `Dock.rect: Rect` (the dock's current screen rectangle, for Task 7).

- [ ] **Step 1: Build the dock**

One horizontal row of five 52 dp keys — Rec, No-Tap, Watch, Play, More — behind a drag grip, on a rounded translucent surface. **Fixed width**, not `WRAP_CONTENT`. Armed state is the key's own colour (rec red, no-tap yellow, watcher blue), replacing the `REC *` label swap in `labels()`.

Build the views from `ContextThemeWrapper(svc, R.style.Theme_Bunshin)` — views made from the bare service context get no theme.

- [ ] **Step 2: Add the message strip**

A fixed-width single-line strip attached to the dock, auto-hiding after ~4 s. It replaces the `readout` TextView as the destination for everything `status()` emits: the recording prompts, `"that area is blank"`, `"too fast"`, `"capture failed"`, `"(not passed through!)"`, the re-record prompt, and the Score table. Nothing that `status()` says today may be dropped — `PLAN.md` records that the re-record prompt has no other reminder anywhere.

- [ ] **Step 3: Build the More sheet**

The fourth and fifth keys of the old 3 × 3 grid have to go somewhere. More opens a small sheet
holding **Score**, **Clear** and **Hide**, the three trigger counts (`7 steps · 2 watch · 2 no-tap`),
the repeat setting, and the target app's icon and label. Score's output renders in the message
strip from Step 2, not in a readout. Clear keeps its existing `Macro.wipe` behaviour. Hide keeps
calling `svc.toggleOverlay()`, and leaving the editor is still how the panel comes back.

- [ ] **Step 4: Free drag with persistence**

Dragging the grip moves the window via `x`/`y` on `WindowManager.LayoutParams` with `Gravity.TOP or Gravity.START`. The old `movePanel()` top/bottom toggle and its `Move` button are deleted. Position persists in `SharedPreferences`.

- [ ] **Step 5: Clamp the drag**

The dock's rect is clamped to the display so no part leaves it, with the top clamped below the status-bar inset and the bottom above the gesture inset. *(Review Focus 2.)*

- [ ] **Step 6: Verify the clamp on device**

Run: drag the dock hard into each of the four edges in turn — `adb -s $D shell input swipe` from the grip towards each edge, screencapping after each.
Expected: in all four screenshots the whole dock is on screen, no key is clipped, and the Play key is fully visible and tappable.

- [ ] **Step 7: Verify touches on the dock are still not recorded**

Run: arm Rec, then tap the dock's own More key.
Expected: no new step appears in `macro.json`. This is the existing guarantee and must survive the rewrite.

- [ ] **Step 8: Verify `setTouchable` still works**

Run: record a tap on a real app.
Expected: the gesture is re-dispatched and the app reacts — the `FLAG_NOT_TOUCHABLE` flip in `Overlay.setTouchable` must operate on the new view the same way.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/shadowclone/Dock.kt app/src/main/java/com/shadowclone/Overlay.kt
git commit -m "feat(overlay): draggable dock bar with a message strip"
```

---

### Task 6: The status pill

**Files:**
- Modify: `app/src/main/java/com/shadowclone/Dock.kt`

**Interfaces:**
- Consumes: `RunState` (Task 3), `ShadowService.onRun` (Task 4), `Dock` (Task 5).
- Produces: `Dock.setRunning(state: RunState?)` — non-null collapses to the pill, null restores the dock.

- [ ] **Step 1: Build the pill**

Progress ring (the mark's ring, filling with `state.step / state.steps`), `2/5 · L3/10 · 0.94` in tabular monospace, and Stop. About 170 × 52 dp. **It occupies the same screen rect the dock did** — only the contents change — so record-time and replay-time frames differ in the same region.

- [ ] **Step 2: Keep Pause reachable**

Tapping the pill body toggles Pause/Resume via `svc.togglePause()`. Stop stays its own button. Both are one tap.

- [ ] **Step 3: Show why a run is paused**

When `state.pausedFor` is non-null, the pill reads `waiting for [icon] <app label>`, using `Apps.label()` from Task 8 (or the raw package until that task lands).

- [ ] **Step 4: Verify the three states**

Run: screencap while idle, while running, and while paused by the foreground check (start a run with the wrong app in front).
Expected: dock, pill with progress, and pill reading "waiting for …" respectively. Stop is visible in all three.

- [ ] **Step 5: Verify pause still holds the clock through the pill**

Run: pause from the pill at ~4 s of a 15 s step, wait 20 s, resume, screencap.
Expected: the step resumes near 6 s, not 26 s. This is M3's recorded behaviour reached through the new control.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/shadowclone/Dock.kt
git commit -m "feat(overlay): status pill with progress, pause and stop"
```

---

### Task 7: Warn when a crop overlaps the dock

**Files:**
- Modify: `app/src/main/java/com/shadowclone/Overlay.kt:300-310` (`crop`), `:211-240` (`saveStep`)
- Modify: `app/src/main/java/com/shadowclone/Macro.kt` (`selfTest`)

**Interfaces:**
- Consumes: `Dock.rect` (Task 5).

- [ ] **Step 1: Write the failing assertion**

```kotlin
// a crop that overlaps the parked dock is flagged; one that clears it is not
if (!Overlay.overlapsPanel(Rect(0, 0, 100, 100), Rect(50, 50, 200, 200))) bad += "overlap-hit"
if (Overlay.overlapsPanel(Rect(0, 0, 100, 100), Rect(100, 100, 200, 200))) bad += "overlap-edge"
```

- [ ] **Step 2: Run it and watch it fail**

Run: build, install, restart the service, grep logcat.
Expected: `FAIL` naming `overlap-hit`.

- [ ] **Step 3: Implement `Overlay.overlapsPanel(crop: Rect, panel: Rect): Boolean`**

A companion function so it is testable without a window. Touching edges do not count as overlap.

- [ ] **Step 4: Say so when it happens**

`Dock.rect` must include the message strip whenever the strip is visible: arming prints a prompt that then sits in record frames for its whole ~4 s life, so a crop taken under it is affected exactly as one taken under a key.

In `saveStep`, after the crop succeeds, if the crop overlaps `Dock.rect`, append to the message strip: `"heads up: this template covers the panel — move the panel and re-record if it scores low"`. The step is still saved; this is a warning, not a refusal.

- [ ] **Step 5: Run the assertion and verify it passes**

Expected: `macro self-test PASS`.

- [ ] **Step 6: Verify on device**

Run: park the dock mid-screen, arm Rec, tap right next to it, screencap.
Expected: the warning appears in the strip and the step is in `macro.json`.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/shadowclone/
git commit -m "feat(overlay): warn when a recorded template covers the panel"
```

---

### Task 8: Profiles screen

**Files:**
- Create: `app/src/main/java/com/shadowclone/ProfilesActivity.kt`, `app/src/main/java/com/shadowclone/Apps.kt`, `app/src/main/java/com/shadowclone/EditorActivity.kt`
- Modify: `app/src/main/java/com/shadowclone/MainActivity.kt`, `app/src/main/java/com/shadowclone/ShadowService.kt`, `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `Macro.name` (Task 3).
- Produces:
  - `Apps.label(ctx, pkg): String`, `Apps.icon(ctx, pkg): Drawable?`, `Apps.primary(macro): String?` — the macro's first package that is not in the ignored set.
  - `EditorActivity` — the base class every editor screen extends, holding the started-activity count. `EditorActivity.anyOpen: Boolean` replaces `MainActivity.editorOpen`.
  - `MainActivity` stays as the launcher alias and does nothing but start `ProfilesActivity`.

- [ ] **Step 1: Write the failing assertion**

```kotlin
// the profile package is the first recorded one that is not the OnePlus game shell
val g = Macro(); g.pkgs += "com.oplus.games"; g.pkgs += "com.reblazing.nbrc"
if (Apps.primary(g) != "com.reblazing.nbrc") bad += "primary-shell"
if (Apps.primary(Macro()) != null) bad += "primary-empty"
```

- [ ] **Step 2: Run it and watch it fail**

Expected: `FAIL` naming `primary-shell`.

- [ ] **Step 3: Implement `Apps`**

`primary` skips the same packages `ShadowService.ignoredPackages` skips — share that set out of the service rather than duplicating the list. `icon` returns null rather than throwing when `PackageManager` cannot see the package. *(Review Focus 3.)*

- [ ] **Step 4: Build the screen**

A `RecyclerView` with two view types: a 48 dp profile header (icon, `Apps.label`, package, macro count) and a 72 dp macro row (first step's template thumbnail, `macro.name ?: Apps.label`, `"7 steps · 2 watch · 2 no-tap"` in tabular monospace, chevron). The service banner renders **only when `ShadowService.instance == null`**. Self-test moves into an overflow menu. **The New-macro FAB is not built** — storage is one slot.

- [ ] **Step 5: Replace `editorOpen` with a started-activity count**

`MainActivity.editorOpen` is a boolean, and with three screens it inverts. Android runs
`B.onStart` **before** `A.onStop`, so moving from the profiles screen into a macro sets it false
and `setOverlayVisible(true)` draws the panel on top of the macro screen — exactly the bug M3
fixed.

`EditorActivity` holds an `AtomicInteger`: increment in `onStart`, decrement in `onStop`, and only
call `setOverlayVisible(true)` when it reaches zero. `ShadowService.setOverlayVisible` checks
`EditorActivity.anyOpen` where it checks `MainActivity.editorOpen` today.

- [ ] **Step 6: Verify the panel stays away across a screen change**

Run: open the profiles screen, navigate into the macro, screencap; then force a re-bind with
`adb -s $D shell uiautomator dump` (no run active) and screencap again.
Expected: no panel in either shot.

- [ ] **Step 7: Verify the icon fallback**

Run: temporarily point `Apps.primary` at a package that is certainly not visible, install, screencap.
Expected: a neutral placeholder tile, no crash. Then check whether the real package resolves; if it throws, add `<queries>` with a MAIN/LAUNCHER intent to the manifest and re-verify.

- [ ] **Step 8: Verify the screen**

Run: install, launch, screencap.
Expected: one profile group with the target app's real icon and label, one macro row beneath it with a template thumbnail, no service banner while the service is on.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/shadowclone/ app/src/main/AndroidManifest.xml
git commit -m "feat(editor): profiles screen grouped by app"
```

---

### Task 9: Macro screen

**Files:**
- Create: `app/src/main/java/com/shadowclone/MacroActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml` — register `MacroActivity`, or it crashes on launch.

**Interfaces:**
- Consumes: `Apps` (Task 8), `EditorActivity` (Task 8 — `MacroActivity` extends it), `svc.startRun()` (Task 4), `RunState` (Task 3).
- Produces: navigation into `StepSheet` (Task 10) by `(Kind, index)`.

- [ ] **Step 1: Build the screen**

Header: the app icon, the macro name (editable), the package. Then Run — the only orange control on the screen, with the ring from Task 6 around it. Then a repeat stepper whose top value is ∞ (`loops = 0`); the magic-0 text field and the Save-repeat button are both deleted, since `onStop` already saves. Then a three-tab strip — Steps / Watchers / No-tap, each with its count — over a `RecyclerView` of 72 dp rows: template thumbnail, `"2 · Tap"`, `"0.85 · 8s · 2.5s"` tabular, drag handle.

- [ ] **Step 2: Reorder by dragging**

`ItemTouchHelper` with drag on the handle only, writing through to `macro.list(kind)` and `Macro.save` on drop. The Up/Down buttons in the old dialog are deleted.

- [ ] **Step 3: Make Run hand off correctly**

Run calls `svc.startRun()` and then **`moveTaskToBack(true)`** — not `finish()`. `finish()` pops back to `ProfilesActivity`, which is still an editor screen, so the run would stay paused for "editor open" for ever and the hand-off would never complete.

The run begins paused — on "editor open" until the task is backgrounded, then on the foreground check until the target app is in front — and the existing resume path starts it when the app appears. That is M2's recorded behaviour, not new code.

- [ ] **Step 4: Run becomes Stop while a run is active**

Subscribing to `svc.onRun`, the button reads Stop whenever `svc.runActive`. Without this there is a state — run active, editor in front — with no Stop anywhere, which is the anti-goal. The pill is not visible here, because the overlay is gated off while an editor screen is up.

- [ ] **Step 5: Handle a macro with no steps**

With `macro.steps.isEmpty()`, Run is disabled with the reason shown, and each empty tab shows an empty state naming the overlay key that fills it ("No watchers yet. Press Watch on the panel, then touch what should be handled whenever it appears."). `Runner.start` already refuses this case; the UI must not offer it. *(Review Focus 4.)*

- [ ] **Step 6: Verify**

Run: install, open the macro, screencap each of the three tabs; drag step 2 above step 1 and `adb -s $D shell run-as com.shadowclone cat files/macros/default/macro.json`.
Expected: tabs show the right counts, and the JSON order changed to match the drag.

- [ ] **Step 7: Verify the empty case**

Run: `Clear` on the panel, then open the macro screen and screencap.
Expected: Run is disabled, and all three tabs show their empty state. No crash.

- [ ] **Step 8: Verify Run hands off**

Run: press Run, screencap immediately, then bring the target app forward and screencap again.
Expected: the first shot shows the task backgrounded with the pill reading "waiting for …" — not
the profiles screen; the second shows the run advancing.

- [ ] **Step 9: Verify Stop is reachable from inside the editor**

Run: start a run, open the macro screen while it is going, screencap, press the button.
Expected: the button reads Stop, and pressing it ends the run. There is no state with a run
active and no Stop on screen.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/shadowclone/MacroActivity.kt
git commit -m "feat(editor): macro screen with tabs, drag reorder and run hand-off"
```

---

### Task 10: Step sheet

**Files:**
- Create: `app/src/main/java/com/shadowclone/StepSheet.kt`
- Modify: `app/src/main/java/com/shadowclone/MainActivity.kt` (delete `edit`, `move`, `field`)

**Interfaces:**
- Consumes: `Scores.get` (Task 3), `Step.clamp` (existing).

- [ ] **Step 1: Build the sheet**

A `BottomSheetDialog`: the template at a readable size, the recorded coordinates, then steppers for threshold, "Give up after", "Wait after" and search area. **Steppers, not text fields** — their ranges are `Step.clamp`'s bounds, so a value can never need silent correction after the fact. Re-record and Delete at the bottom; Delete is the only red thing on the screen and confirms before acting.

- [ ] **Step 2: Show the last score, not a live one**

Under the threshold, render `Scores.get(step.template)` as `"0.94 · 2 min ago · <app label>"`, or `"not scored yet"`. **Do not capture here.** The editor is in the foreground, so a capture would score the editor — every template would read near `0.43`, `PLAN.md`'s measured not-on-screen baseline. Live scoring stays on the overlay.

- [ ] **Step 3: Verify the clamp is unreachable**

Run: hold the threshold stepper's minus button to the bottom of its range on a step, save, and read `macro.json`.
Expected: `0.6`, the `Step.clamp` floor, reached by the stepper rather than corrected after typing.

- [ ] **Step 4: Verify the rest**

Run: open a step, change "Wait after" to 2500, save, read the JSON, then run the macro.
Expected: `postDelayMs` is 2500 and the run visibly holds on that step — the M3 check that proved edits reach the runner.

- [ ] **Step 5: Verify delete**

Run: delete a step, confirm, then list the macro directory.
Expected: the step is gone from the JSON and its PNG is gone from disk.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/shadowclone/
git commit -m "feat(editor): step bottom sheet with steppers and last score"
```

---

### Task 11: Smoke

**Files:**
- Create: `app/src/main/java/com/shadowclone/Smoke.kt`
- Modify: `app/src/main/java/com/shadowclone/MacroActivity.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `Smoke.puff(host: ViewGroup, x: Float, y: Float)`.

- [ ] **Step 1: Implement the effect**

11 blobs, ~640 ms. Each blob is one `drawOval` with a `RadialGradient` paint through a per-blob `Matrix`: pale near-flat core (`#E8ECF2`), turning grey (`#848C98`) at the rim, fading to transparent — the darker edge is part of the fill, not a line around it. Each is stretched along its direction of travel and squashed across it, reaching ≈1.25 aspect at full travel, with a tangential kick so the mass curls rather than spoking outward.

**Held on eights:** each pose holds for 1/8 s and the view is invalidated only when the held pose changes, not every frame. The browser prototype measured 0.08 ms per draw this way. **Do not composite an offscreen buffer per blob per frame** — that was the first attempt and it was visibly laggy.

- [ ] **Step 2: Fire it on delete**

In `MacroActivity`, when a row is removed, puff at the row's centre and fade the row out beneath it.

- [ ] **Step 3: Fire it on appear, once**

Rows are only ever created on the overlay, while the editor is closed, so there is no insert to hook. Steps have no IDs, so the marker is **the set of template filenames seen last time** — those already embed `System.currentTimeMillis()` and are unique. Persist it in `SharedPreferences`; on first bind, puff once for each template not in that set, then store the current set. **Not on every resume, and not for rows that were already there.** *(Review Focus 5.)*

- [ ] **Step 4: Verify the appear case does not storm**

Run: record three steps, open the macro screen and screencap; leave and re-enter, screencap again.
Expected: the first visit puffs for the three new rows; the second visit puffs for none.

- [ ] **Step 5: Verify a long list**

Run: with a macro of ten or more steps already seen, open the macro screen.
Expected: no smoke at all.

- [ ] **Step 6: Verify nothing reaches a captured frame**

Run: delete a step to fire a puff, then immediately press Score on the overlay.
Expected: scores are unaffected — the effect is editor-only and the overlay never draws it.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/shadowclone/
git commit -m "feat(editor): smoke on rows appearing and being deleted"
```

---

## Before Task 5: prove the themed-overlay assumption

The design asserts that Material components render correctly inside a `TYPE_ACCESSIBILITY_OVERLAY` window built from the service context through a `ContextThemeWrapper`. The dock is built out of that assumption and nothing verifies it.

Spend one throwaway build putting a single `MaterialButton` in the existing panel, install, and screencap. If it renders and responds, continue. If it does not, the dock falls back to hand-styled plain views with the same geometry and colours, and no other task changes.
