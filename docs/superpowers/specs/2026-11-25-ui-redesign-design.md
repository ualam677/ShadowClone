# Bunshin UI redesign — design

25 Nov 2026. Covers the editor activity, the overlay panel, the app mark and the visual system.
Changes no automation logic. One structural change is required and specified below: `Runner`
ownership moves from `Overlay` to `ShadowService`.

## Why

Both UI surfaces were built to be testable, not to be used. On the phone today:

- The overlay panel is a **260 × 168 dp** grey block pinned to the bottom-left corner — about a
  sixth of the screen — with nine identical system buttons in a ragged 3 × 3 grid. Its bottom row
  sits in the gesture area. It appears in every `takeScreenshot` frame, so its footprint is also a
  template-quality problem.
- The editor is a stack of full-width caps buttons. "OPEN ACCESSIBILITY SETTINGS" carries the same
  weight as everything else, three sections of "(none)" precede three paragraphs of help text, and
  every list row is a `Button` — which brings 88 dp min-width, caps text and drawable insets, so
  the visible gaps never match the margins the code sets.
- Every padding and size is a raw pixel: `setPadding(48, 24, 0, 4)`, `maxHeight = 400`. On this
  device (density 420, 411 × 905 dp) that is ~18 dp gutters, ~9 dp above a label and ~1.5 dp below.
  Nothing lands on a grid.
- `label()` renders section headers, field labels and the help paragraph at the same 13 sp, so the
  whole screen reads as one flat list.
- `res/values/` contains only `strings.xml`. No colours, no dimens, no styles. The theme is the
  bare platform `Theme.Material.Light.NoActionBar`.

M4 adds multiple macros, run logs and export. Doing that on top of the current screens means
building the same wrong thing twice.

## Goals

- Both surfaces on one visual system: one spacing grid, three type sizes, one palette.
- The overlay shrinks, stops jumping, and goes where the user puts it.
- The editor gains the navigation M4 needs — profiles → macro → step — so M4 adds data, not screens.
- An app mark that works as a launcher icon, a notification icon and a progress indicator.

## Non-goals

- **No change to what the service, runner or matcher *do*.** Record, replay, stop rules, foreground
  pause, scoring and template handling all keep behaving exactly as they do. Two structural
  exceptions, both required by the design and neither altering automation logic:
  - `Runner` reports progress only as a formatted string through `onState`. It gains a second,
    structured callback carrying step *i/n*, loop, last score, and paused-plus-reason. Parsing the
    display strings is the fragile alternative.
  - **`Runner` ownership moves from `Overlay` to `ShadowService`** (see "A run must outlive the
    overlay", below).
- **No new storage.** The model stays one macro (`macros/default`) until M4. The profiles screen is
  the shell; it will show one row.
- Not implementing M4's run logs, export/import or M5's profile data. The screens reserve the
  places those go.

## Anti-goals

Things that would make this a failure even if it looked better:

- **A run or a recording that can be left going with no Stop on screen.** `hide()` exists for this
  reason. Stop stays reachable in one tap in every state the panel can be in.
- **Effects or chrome that land in a captured frame.** Anything drawn on the overlay ends up in
  templates and drags match scores down. This is why M1 added the `showMarks` gate.
- A redesign that makes the overlay larger, or that costs a tap on Play or Stop.

## Constraints

- Target is one device: OnePlus 13 (CPH2653), Android 16, 1080 × 2376 at density 420 → **411 × 905 dp**.
- Overlay windows are built from the **service** context, which carries no Material theme. Any
  themed widget there needs a `ContextThemeWrapper`.
- The overlay must keep `FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS`,
  `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS` and raw coordinates, or recordings come out offset.
- The `setTouchable` flip around gesture dispatch must keep working on whatever the panel becomes.
- Touches on the panel must never be recorded as steps.
- The editor must keep gating overlay creation (`MainActivity.editorOpen`): the service can be
  re-bound at any moment and will otherwise rebuild the panel on top of the editor.

## Approaches

**1 · Plain views, no new dependency.** Keep programmatic views, add `colors.xml` / `dimens.xml` /
`styles.xml` and a dark platform theme. Everything in the design is cheap this way except
drag-to-reorder, which without `RecyclerView` and `ItemTouchHelper` is ~100 lines of touch handling;
rows would get ▲▼ buttons instead.

**2 · Add androidx (appcompat + material + recyclerview). ← chosen.** Drag-to-reorder, a real
bottom sheet, `TabLayout` and a proper FAB all come from the library. Costs the first dependency
beyond OpenCV, moves `MainActivity` from the framework `Activity` to `AppCompatActivity`, and
migrates the theme to Material3 dark. No behaviour changes.

**3 · Compose for the activity.** Rejected. The service must stay plain views, so this carries two
UI toolkits for three screens that need nothing Compose is good at, and it is the largest build cost
of the three.

Chosen: **2**. Reordering steps is something you do constantly while tuning a macro, and arrow
buttons are worse at it than dragging. That one gesture is the whole difference between 1 and 2.

## Visual system

**Palette.** Primary **#FF8A3C** (~8:1 on the background — findable over a game, not glaring at
night), pressed **#E8701A**, recessed **#C96A20**.
Semantic, used identically on both surfaces: rec **#FF453A**, no-tap **#FFD426**, watcher
**#4C8DFF**, play/hit **#32D74B**. No-tap is yellow rather than amber specifically so it cannot be
mistaken for the brand orange.
Surfaces: background **#121316**, surface **#1C1E22**, raised **#262A31**, line **#333841**, text
**#F2F3F5**, muted **#9AA0A8**. Dark only; there is no light theme.

**Spacing.** 4 / 8 / 12 / 16 / 24 / 32 dp and nothing else. Screen gutter 16, gap between rows 8,
24 above a section header and 8 below it — space between groups is always greater than space within
one. List rows 72 dp, buttons 48–56 dp, nothing below the 48 dp touch minimum. All of it in
`dimens.xml`; no raw pixels anywhere.

**Type.** Three sizes: 20 sp titles, 15 sp row titles, 13 sp secondary in muted grey. Numbers
(scores, thresholds, timeouts) are monospace with tabular figures so columns do not jitter.

## The mark

Two pairs of crossed fingers — the Kage Bunshin seal — tilted 20°, inside an open ring. Front pair
#FF8A3C, pair behind #C96A20, separated by a cut rather than by shade. One vector drawable; the
same path serves the adaptive launcher icon and the notification icon.

The ring is also a progress indicator: its gap is the start of the arc, and it fills as a macro
advances. The launcher icon cannot animate, so this appears **in-app only** — on the dock bar's
status pill and on the macro screen's Run button.

## The overlay

A **dock bar**: one horizontal row of five 52 dp keys — Rec, No-Tap, Watch, Play, More — behind a
drag grip, on a rounded translucent surface.

- **Fixed width.** The panel is `WRAP_CONTENT` today, so it resizes as the readout text changes and
  visibly jumps every tick.
- **Free drag,** clamped to the screen, position persisted. Replaces the Move top/bottom toggle.
- **While running it collapses to a status pill**: progress ring, `2/5 · L3/10 · 0.94`, and Stop.
  About **170 × 52 dp** against today's 260 × 168. Stop is on the pill itself, not behind an expand.
- **Pause is not lost.** Play doubles as Pause/Resume today, and M3 verified that a pause holds the
  step clock and keeps the remainder of a post-step delay. On the pill, **tapping the pill body
  toggles Pause/Resume** while Stop stays its own button. A paused run says why: "waiting for
  [icon] Naruto Blazing" when the foreground pause is holding it.
- **A transient message strip** sits with the dock, auto-hiding after a few seconds. The current
  readout carries safety-critical feedback that the dock has nowhere else to put — "touch what must
  NEVER be tapped", "no-tap 2 saved, touch NOT passed through", "that area is blank", "too fast",
  "capture failed" — and this is also where Score results print. It is fixed-width like the dock, so
  it cannot reintroduce the jumping.
- Score, Clear, Hide, the trigger counts and the target app's icon live in the **More** sheet.
  **Live scoring stays here**, on the overlay, because it is the only place the screen being scored
  is the one in front (see the step sheet, below).
- Armed state is the key's own colour, not an asterisk in its label.
- One row of equal keys means there is no grid to be ragged.
- **The panel is never hidden for a capture.** An earlier draft hid the dock for record and Score
  captures; that is wrong and is reversed. Today the panel sits in record frames *and* replay
  frames, so it cancels out, and `Templates.kt` depends on the invariant that a score shown while
  tuning is the score replay will get. Hiding it for Score alone would make tuning optimistic
  relative to replay — a worse bug than the one it fixes.
- **The pill occupies the same screen rect as the dock.** Only its contents change, so record-time
  and replay-time frames differ in the same region rather than in different ones.
- **Recording warns when a crop overlaps the panel.** Free drag means the dock can be parked over
  anything, and a template cropped across it will score low later. The crop box is already computed
  in `Overlay.crop`; intersecting it with the panel's rect and saying so in the message strip is
  cheap and replaces the old standing advice to "keep templates off it, or Move it".
- **No smoke on the overlay.** Nothing new is drawn there, so there is no new way for chrome to
  reach a template.

## The editor

Three screens. androidx `RecyclerView` throughout.

**1 · Profiles.** Macros grouped by the app they automate. Each group is headed by the app's real
launcher icon at 48 dp (`PackageManager.getApplicationIcon`) with its label and package beneath, and
a count. Macros nest under it. The service banner appears **only when the service is off**.
Self-test moves to an overflow menu.

**The New macro FAB is hidden until M4.** Storage is one slot, so the button could only wipe the
existing macro. `Macro` also has no name field today; it gains one — a single string, defaulting to
the app's label — so rows and the macro screen's title have something to show. *Both my calls.*

Each macro row leads with a thumbnail of its **first step's template** — the screen it starts on —
so the list is pictures, not text.

### A run must outlive the overlay

The macro screen has a Run button with a progress ring. As the code stands that cannot work, and
would be unsafe if it did. `MainActivity.onStart` calls `setOverlayVisible(false)` → `Overlay.hide()`
→ `stopAll("stopped")`, and `Runner` is **owned by `Overlay`**, which is then nulled. So opening the
editor stops any run; and a run started from the editor would match and tap against the editor's own
screen, with no overlay and therefore no Stop anywhere — directly against this document's anti-goal.

**`Runner` moves into `ShadowService`.** The service already outlives both surfaces, already tracks
the foreground package, and is where M4's run logs will be written. `Overlay` and the editor both
become views onto a run the service owns. No logic inside `Runner` changes.

Three rules make that safe, because a run that outlives the overlay must not then run *against the
editor*:

- **An open editor pauses the run.** The foreground guard cannot do this on its own: `Runner` tests
  `svc.currentPackage()`, and `ignoredPackages` contains our own package, so with the editor in
  front `foregroundPackage` keeps returning the target app and the guard passes. The run would
  capture and score the editor's own screen — every template near `0.43`, the measured
  not-on-screen baseline — burn the step timeout and halt. So "an editor screen is started" is its
  own pause reason: clock held, no capture taken, nothing matched.
- **Hide still stops the run.** Only the editor pauses. The panel going away must never leave a run
  going with no Stop.
- **While a run is active, the macro screen's Run button reads Stop.** The overlay is gated off
  while the editor is up, so this is the only Stop there is, and the anti-goal requires one.

Run then behaves like this, using machinery already verified on device: pressing Run starts the run
and **backgrounds the task** (`moveTaskToBack`, not `finish()` — finishing merely pops back to the
profiles screen, which is still an editor screen, and the run would stay paused for ever). The run
begins paused, and resumes by itself when the target app comes forward — M2 verified exactly that
("Pressing HOME resumed it on its own, the step fired, and it finished"). The pill shows "waiting
for [icon] Naruto Blazing" until then. No new hand-off flow is needed.

**`MainActivity.editorOpen` becomes a count, not a boolean.** With three screens, Android runs
`B.onStart` before `A.onStop`, so navigating from the profiles screen into a macro would flip it
false and draw the panel over the macro screen — the bug M3 fixed. A shared base class counts
started editor screens and the overlay returns only at zero.

**2 · One macro.** Run is the only orange control on the screen. Repeat is a stepper ending at ∞
(the magic 0 and the Save repeat button both go; `onStop` already saves). The three trigger lists
become three tabs with counts, replacing three stacked sections of "(none)". Rows are 72 dp with the
template thumbnail, the act, and `0.85 · 8s · 500ms` in tabular monospace, plus a drag handle.

**3 · One step.** A bottom sheet, not an `AlertDialog`, so the template is big enough to recognise.
Steppers rather than numeric text fields — no keyboard, no typos, and `Step.clamp` becomes the
stepper's range instead of a silent correction after the fact. Plain-word labels: "Give up after",
"Wait after". Delete is pulled away from Save, is the only red thing on the screen, and confirms.

**Score under the threshold is the last score, not a live one.** The mockup said "scores 0.94 right
now"; that cannot work. `takeScreenshot` captures whatever is in front, and while the step sheet is
open that is the editor, so a live score here would be scoring this screen. Instead the overlay's
Score and the runner each write the score they computed into a small per-trigger cache, and the
sheet shows it with its age and where it came from — `0.94 · 2 min ago · Naruto Blazing`. Live
scoring stays on the overlay, where the app being scored is the one on screen.

## Smoke

A puff of comic smoke when a row **appears** or is **deleted** in the editor. A clone being summoned
or dispelled. Editor only.

"Appears" needs defining, because rows are created on the overlay while the editor is closed and so
are never born in front of the user: a trigger recorded since the editor was last on screen puffs
**once**, when the list first binds. Not on every resume, and not for rows that were already there.

- Each blob is an ellipse squashed across its path and stretched along it, with a second offset lobe
  so the silhouette is never a clean oval, and a tangential kick so the mass curls rather than
  spoking outward. Pale near-flat core turning grey at the rim and fading out, so the darker edge is
  part of the fill rather than a line around it.
- **Stretch: "barely"** (≈1.25 aspect at full travel). 11 blobs, ~640 ms.
- **Held on eights.** Each pose holds for an eighth of a second; the view is only invalidated when
  the held pose changes, not every frame. Measured in the browser prototype at 0.08 ms per draw,
  8 draws a second.
- Implementation: one `RadialGradient` paint drawn through a per-blob `Matrix` — a single
  `drawOval` each, no offscreen buffers. The first version composited a full-size offscreen canvas
  per particle per frame and was visibly laggy; do not do that.
- Nowhere else. Ordinary buttons do not puff.

## Decided along the way, and worth knowing

- **The accessibility service cannot be dropped** in favour of a plain floating window. Only
  `dispatchGesture()` can inject a touch into another app without root or Shizuku; only the
  service's `takeScreenshot()` captures without a MediaProjection consent prompt, a foreground
  service and a permanent cast icon; only the service knows what is in the foreground, which is what
  auto-pause runs on. `TYPE_ACCESSIBILITY_OVERLAY` also needs no "Display over other apps"
  permission and is exempt from Android 12's untrusted-touch rules.
- **Run logs ("Recent runs" on the profiles screen) are deferred to M4**, where the data starts
  existing. The screen reserves the space. *My call — flag it if you want the empty section now.*
- **Grouping by profile is built now**, ahead of M5's profile data, because the user asked for it.
  Until M5, a group is derived from the macro's recorded `pkgs`; a macro spanning two packages is
  filed under the first non-shell one, with `+1` on the header.
- **OnePlus's game shell reports `com.oplus.games`** as the foreground package for Naruto Blazing,
  which is already special-cased in the runner. Icon lookup must apply the same exclusion or the
  badge will show the game-space icon instead of the game's.
- **Package visibility may block the icon lookup.** The app targets SDK 35, and Android 11+ filters
  which packages an app can see, so `getApplicationIcon("com.reblazing.nbrc")` may throw
  `NameNotFoundException`. Whether an accessibility service is exempt is unverified — check on the
  first build, and if it throws, add a `<queries>` element with a MAIN/LAUNCHER intent filter.
  Fall back to a generic tile rather than crashing.
- **Existing templates are not migrated.** The one macro on disk was cropped with the old 260 × 168
  panel in frame. Any of its templates overlapping where that panel sat will score lower against
  the new dock. There is one test macro, so the answer is to re-record it if a score drops — no
  migration step, no compatibility code. *My call.*

## Left to the builder

- File layout for the new resources and view code, and how far to split `MainActivity` into
  per-screen classes or fragments.
- Exact androidx artefact versions.
- Whether the dock's drag position persists in `SharedPreferences` or alongside the macro JSON.
- Stepper increments and ranges per field, within the existing `Step.clamp` bounds.
- Transition and animation durations other than the smoke, which is specified above.
- Whether `RecyclerView` replaces the overlay's views too, or the dock stays hand-built (it is five
  fixed keys, so hand-built is likely right).

**Prove this first, before planning around it:** that Material components render correctly inside a
`TYPE_ACCESSIBILITY_OVERLAY` window built from the service context through a `ContextThemeWrapper`.
This document assumes they do. It is the one part of the androidx approach that is asserted rather
than verified, and the dock is built out of it. A throwaway build with one themed button in the
overlay settles it in minutes; if it misbehaves, the dock falls back to hand-styled plain views and
nothing else in the design changes.
