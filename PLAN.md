# ShadowClone — screen-aware macro tool (Android)

Personal tool. No network, no Play Store. Target device: OnePlus 13 (CPH2653), Android 16 / API 36,
1080x2376, connected over adb. Toolchain present: JDK 17, SDK platform 35, build-tools 35, gradle 8.11.1 cached.

## Verified facts (checked 24 Nov 2026)
- Phone is attached via adb, so I can build and install each milestone myself.
- Installed target of interest: `com.reblazing.nbrc` (Naruto Blazing Revival).
- `X:\Downloads\blazingbot` is read-only reference: SPEC.md, 14 labelled screens at the same
  resolution (good real templates for M1), 430 capture frames, a raw getevent log.

## Architecture

One app, one module, Kotlin, plain Views (no Compose inside the service).

### 1. AccessibilityService = the whole runtime
It is the only component that can do all four things we need, so everything lives behind it:
- **See**: `takeScreenshot()` (API 30+, `canTakeScreenshot="true"`). No per-session consent dialog,
  no MediaProjection foreground service. Rate-limited to ~3 fps, which is plenty for "wait until
  this screen appears". MediaProjection stays unused unless we ever need continuous frames.
- **Act**: `dispatchGesture()` for tap, long-press, swipe.
- **Know the foreground app**: window state events give the package name -> auto-pause.
- **Overlay**: `TYPE_ACCESSIBILITY_OVERLAY` from the service. No SYSTEM_ALERT_WINDOW permission,
  and exempt from untrusted-touch blocking.

### 2. One primitive: Trigger
```
Trigger = template PNG + search region + threshold -> action
Action  = Tap(x,y) | LongPress | Swipe | Stop(notify) | Nothing(sync only)
```
- A **step** is a trigger that the macro waits for, in order, with a timeout.
- A **watcher** is a trigger evaluated on every frame, whatever step we are on.
- A **stop rule** is a trigger whose action is Stop.
Per frame, in this order: **stop rules -> watchers -> current step**. Stop always wins; a stop-rule
match never dispatches a gesture. No separate state-machine engine: blazingbot's state machine is
just steps plus watchers plus two stop rules (stamina popup, any pearl prompt).

### 3. Matching
OpenCV `matchTemplate` with `TM_CCOEFF_NORMED`; its `maxVal` is the match score the editor shows.
One Gradle dependency, `abiFilters "arm64-v8a"` to keep the APK sane. Crop to the search region and
downscale before matching — that is where the speed comes from. Hand-rolled NCC is the fallback if
the Maven artifact misbehaves.

### 4. Storage
JSON + PNG files under `filesDir/macros/<id>/`. No database. Export/import = zip through the
Storage Access Framework. Run logs are JSONL plus a PNG written only on timeout or no-match.

### 5. Profiles (where blazingbot fits)
A profile is per-package data, not code: named screens, watchers, stop rules, default threshold /
timeout / delay, and an ordered list of macros. The Naruto Blazing bot becomes
`profiles/com.reblazing.nbrc.json` plus its template crops. Nothing game-specific in the engine.

### 6. Safety (built in from M2, not bolted on later)
- Stop rules override steps and watchers.
- Nothing recognisable past the timeout -> stop and notify, never guess a tap.
- Target app not in the foreground -> pause.
- Never-tap list is enforced by the stop rule firing first.

## Milestones (each one installs on the phone and is testable)

- **M0 — skeleton.** Empty app, accessibility service enables, overlay bubble appears over any app.
  Test: toggle the service, see the bubble over the game.
- **M1 — see, match, tap.** Grab a frame, crop a template from it (or pick one from
  `blazingbot/screens/`), show a live readout: score, matched box, fps. Button: tap at the matched
  point. Acceptance: the tap lands exactly on the box in the real game, and frames from the game are
  not black (checks FLAG_SECURE).
- **M2 — record and replay.** Record taps, long-presses, swipes with an auto-cropped template per
  touch. Replay: wait-for-match -> gesture -> next step, with per-step timeout. Ships with stop rules,
  foreground pause, and overlay record/play/pause/stop plus step counter.
- **M3 — editor, watchers, loops.** View/reorder/delete/re-record steps; edit threshold, timeout,
  search region, delays; test a step live for its score. Watchers. Loop N times / until stop / manual.
- **M4 — logs, export/import.** Run log list, timeout screenshots, zip export/import.
- **M5 — profiles + the Blazing profile.** Per-package profiles, then port the blazingbot v1 loop
  (Challenge -> Start Mission -> AUTO battle -> Ready! -> boost popup -> results -> Present Box)
  as data, with its stop rules. Mode B (the bot playing turns itself) stays out of scope.

## Install gotchas (one-time, on the phone)
- Android 13+ greys out the accessibility toggle for sideloaded APKs. I install with `adb install`,
  which avoids it; if you ever install by tapping the file, use App info -> ... -> Allow restricted settings.
- Set the app's battery usage to **Unrestricted** in OxygenOS or the service gets killed.

## Deferred on purpose
OCR (reading coin counters, `MAP n/20`), tap randomisation, Mode B battle logic, multi-device
resolution scaling, any cloud or network feature.

## Decisions (24 Nov 2026)
- **Recording: overlay eats the touch and re-dispatches it.** Full-screen `TYPE_ACCESSIBILITY_OVERLAY`
  only while armed for recording; it records the gesture, then replays it with `dispatchGesture` so the
  game still responds. No extra permissions, survives reboots. Known limit: a live drag is reproduced
  after the fact, so drag-heavy games feel laggy while recording.
  `shortcut: drags are re-dispatched, not passed through live; upgrade to Shizuku + getevent passive
  capture (raw /dev/input, as in blazingbot/capture/taps.log) if drag recording proves unusable.`
- **Builds: I build here and `adb install` to the attached phone each milestone.**
- **Blazing: ported as a profile at M5.** Mode B (bot plays battle turns) out of scope.

## Implementation notes M1/M2 depend on
- **Re-dispatch must not hit our own window.** `dispatchGesture` injects at the input layer, so it
  lands on the topmost touchable window — which is the recorder overlay. Order: set
  `FLAG_NOT_TOUCHABLE` via `updateViewLayout` -> wait one frame (the layout change is async) ->
  `dispatchGesture` -> restore touchability in both `onCompleted` and `onCancelled`.
  Limit: touches made inside that window pass through unrecorded. Taps on our own record/stop
  bubble must never become steps.
- **Templates come from a rolling frame, not a screenshot per touch.** `takeScreenshot` fails if
  called inside its ~333 ms interval, so quick taps would lose their crops. While armed, run the
  ~3 fps loop and crop from the latest frame taken *before* touch-down — which is also the screen
  state we want to match at replay.
- **Overlay coordinates must equal screenshot coordinates.** Overlay needs
  `FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS`, `layoutInDisplayCutoutMode = ALWAYS`, and we use
  `rawX/rawY`. Otherwise recordings come out offset by the status bar or cutout even though the M1
  tap test passed.
- **M1 must also check whether our own overlay appears in `takeScreenshot` frames.** If it does, the
  live readout would pollute templates and scores; then hide it for the capture.
- **Foreground pause needs a target package per macro.** Profiles only arrive at M5, so store the
  package on the macro at record time. Ignore our own package, `com.android.systemui` and the IME,
  or touching the overlay or pulling down the shade would pause the run.

## M0 + M1 results (24 Nov 2026, on the OnePlus 13)
Verified on device, driven over wireless adb:
- **Service + overlay work.** Accessibility service enabled, control panel draws over any app.
- **Capture works and the game is not FLAG_SECURE.** Naruto Blazing's title screen captures in full
  colour at 1080x2376. A full-screen match takes **40-45 ms**, so the ~380 ms capture interval is the
  limit, not the matching.
- **Coordinate mapping is exact.** Crop drag (880,1080)-(1020,1220) stored a 140x140 template at
  exactly those coordinates; live match reported centre (950,1150) = the crop centre; the self-test
  reports `PASS score=1.000 off=(0,0)`.
- **Tap at the matched location lands in the real app, the game included.** Matched the YouTube icon
  on the launcher and YouTube opened; then matched Naruto Blazing's Check Encyclopedia button
  (score 1.000 at (150,1780), 53 ms) and the game acted on the injected tap. So accessibility
  gestures drive the game: no Shizuku needed for replay.
- **A template that is not on screen scores 0.428**, a useful negative baseline against the 1.000 hit.

### Fixed during M1
- **Our overlay is captured by takeScreenshot.** The green match box drawn last tick sat in the next
  frame and held the score at 0.940. The HUD is now hidden for the shot (+40 ms), which restored 1.000.
  The control panel is still in captured frames: keep templates and search regions off it, or Move it.
- **The service was likely re-bound mid-session**, which built a second, empty overlay on top
  of the working one. Overlay creation is now idempotent and the template is reloaded from disk.

### Known, not fixed
- Foreground detection reports `com.oplus.games` while Naruto Blazing runs, because the OnePlus game
  shell raises the window events. M2's auto-pause needs the target package matched more carefully.
- Install goes over **wireless adb** (`adb pair` / `adb connect`): the USB link drops mid-transfer.

- Tap refuses to fire below a 0.8 score, so a junk best-match cannot be tapped. That is the same
  "never guess" rule M2's replay needs.

## M2 results (24 Nov 2026, on the OnePlus 13)

Record and replay work end to end. Verified by driving the phone over adb:
- **Record.** Armed with Rec, an injected tap at (947,1142) was eaten by the HUD, classified `TAP`,
  cropped to a 200x200 template at (847,1042), written to `files/macros/default/macro.json`, and then
  re-dispatched — YouTube actually opened. So eat-and-re-dispatch works through a real window.
- **Replay.** From the home screen, Play matched the template, shifted the gesture to where it
  matched, tapped, and reported `done: 1 steps`; YouTube opened again.
- **Self-test.** `macro self-test PASS` on device: gesture classification, the JSON round trip and
  the search-region maths.

### Changed from the M1 plan
- **Templates are captured after touch-up, not from a rolling frame.** The overlay eats the touch,
  so the app has not reacted yet and a frame taken then *is* the screen the user acted on. A rolling
  frame could still show the previous step's screen, which would make the next step match too early.
  `captureWhenAllowed` waits out the ~333 ms screenshot rate limit instead.
- **`canRetrieveWindowContent` is now true.** Window-state events are not enough: returning to the
  OnePlus launcher fires none, so `foregroundPackage` went stale and replay sat paused for ever
  (seen on device). The service now reads `rootInActiveWindow` each tick. Our own package, the
  shade, the IME and `com.oplus.games` are ignored so they cannot pause a run.
- **Stop rules are recorded, not written by hand.** "No-Tap" arms the same recorder; the touch is
  cropped and stored with threshold 0.7, and is deliberately **not** re-dispatched, so recording a
  purchase screen never buys anything.
- **M1's Crop / Watch / Tap / Frame buttons are gone** — the recorder replaces them.

### Known, not fixed
- One macro slot only (`macros/default`). Loops, the step editor and watchers are M3.
- `busy` refuses a second touch while a step is being saved (~400 ms), so fast tapping drops
  touches rather than recording them out of order.
- **The service process gets killed.** Seen on device: the whole process was gone, so the overlay
  vanished and had to be brought back by opening the app. Set the app's battery usage to
  **Unrestricted** in OxygenOS before any long run.
- MainActivity draws its first two rows under the action bar. Cosmetic; the buttons still work.
- Swipe and long-press replay are untested; only a tap has been round-tripped.

### Safety verified on device (24 Nov 2026)
Driven over adb on the home screen, never in a game:
- **A no-tap rule records without acting.** Armed with No-Tap, a touch on the Gmail icon was eaten,
  stored as a `STOP` trigger at threshold 0.7 -- and Gmail did not open.
- **A stop rule halts a run and overrides steps.** With one step (tap YouTube) and that no-tap rule,
  Play matched the no-tap screen at **0.996** and reported `stopped by a no-tap rule`. The step never
  fired: the launcher was still in front afterwards.
- **Foreground pause and resume work.** With the macro pinned to `com.android.launcher`, pressing
  Play inside YouTube gave `paused: app.revanced.android.youtube in front, want com.android.launcher`.
  Pressing HOME resumed it on its own, the step fired, and it finished `done: 1 steps`.
