# US-078 — Add a fixed-step game loop with interpolation and per-tick input

**Status:** 📋 Ready (filed 2026-11-07 from the game-toolkit design review) · **Epic:** Game toolkit ·
**Blocked by:** none · **Blocks:** US-082, US-087, US-088, US-093

## Story
As a JavaFX game developer,
I want a fixed-step game loop that renders smoothly and gives my game one input snapshot per tick,
so that my game runs the same way on every platform and every run, whatever the pulse of the display.

## Problem
Paths that start with `com/` or `javafx/` are under `modules/javafx.graphics/src/main/java/`. `godot/` is Godot
Engine at commit `c51ff06e88` (4.8-beta, MIT), cloned next to this repository.
- JavaFX has no fixed-step loop. An `AnimationTimer` runs once per pulse, before CSS and layout:
  `com/sun/javafx/tk/quantum/QuantumToolkit.java:590-597` runs the animations before `firePulse()`, and
  `com/sun/scenario/animation/AbstractPrimaryTimer.java:324-346` calls the pulse receivers, then the timers.
- The pulse follows the display only on macOS (`com/sun/glass/ui/mac/MacApplication.java:319-320`). Windows, GTK,
  Monocle and Headless report a refresh period of 0 (`com/sun/glass/ui/win/WinApplication.java:420-421`,
  `com/sun/glass/ui/gtk/GtkApplication.java:497-498`, `com/sun/glass/ui/monocle/MonocleApplication.java:211-212`,
  `com/sun/glass/ui/headless/HeadlessApplication.java:161-162`), so the pulse runs on a millisecond timer of
  `1000 / 60` = 16 ms (`QuantumToolkit.java:214`, `:393-401`, `:920-926`). At 62.5 pulses and 60 ticks a second,
  about one pulse in 25 runs no tick (inferred arithmetic), and a game that draws only ticked poses stutters.
- A failing `AnimationTimer` goes quiet. The first 100 exceptions of a timer reach the uncaught-exception handler,
  later ones are ignored, and the timer keeps firing (`AbstractPrimaryTimer.java:68`, `:373-388`).
- JavaFX input arrives as pushed events. The only key-state query is `Platform.isKeyLocked`, for Caps Lock and Num
  Lock (`javafx/application/Platform.java:376`).
- Godot runs 60 ticks a second and at most 8 per frame (`godot/main/main.cpp:2244-2245`), and drops the ticks over
  that cap (`main.cpp:4999-5001`). Each tick dispatches the previous step's callbacks (`main.cpp:5039-5040`,
  `godot/modules/godot_physics_2d/godot_space_2d.cpp:1111-1123`), runs the user's physics process
  (`main.cpp:5044`) and steps (`main.cpp:5084`). It flushes input per tick only with `agile_event_flushing`, which
  is off by default (`main.cpp:3701`, `:5016-5017`), and it multiplies each tick's `dt` by the time scale
  (`main.cpp:5044`, `:5084`), so slow motion changes the simulated result (inferred).
- Tests can drive pulses deterministically with `StubToolkit.setAnimationTime`
  (`modules/javafx.graphics/src/test/java/test/com/sun/javafx/pgstub/StubToolkit.java:741`).

## Constraints
- The loop and its input live in the game toolkit's modules. The part that needs no JavaFX toolkit depends on
  `java.base` only; the JavaFX driver uses public JavaFX API only and changes nothing in the `javafx.*` modules.
- The loop works with no physics engine at all.
- Production Java files are at most 900 lines (`wc -l`); tests and shims are exempt. Java 27 final features only: no
  preview, no incubator such as the Vector API, no `var`. Comments only for a non-obvious "why". GPLv2+CPE headers.
- Agents never run `USE_ROBOT` or `tests/system` runs; the owner runs the robot and visual tests.

## Out of scope
- Drawing nodes at interpolated poses (US-082 in 2D, US-087 in 3D), gamepads (US-088), pointer capture and physical
  keys (US-089), and stepping physics off the FX thread (US-093).

## Acceptance criteria
1. **Fixed tick.** Every tick advances the game by the same `dt`. The rate defaults to 60 ticks a second and is
   configurable.
2. **Tick cap.** A frame runs at most 8 ticks. Ticks over the cap are dropped, never run later, and counted, and the
   game can read the count.
3. **Smooth rendering.** A render callback runs every frame with an interpolation fraction in [0, 1). A test with
   16 ms pulses and 60 ticks a second shows a body moving at constant velocity at a strictly advancing rendered
   position on every one of at least 10,000 pulses, including the pulses that run no tick: no pose repeats.
4. **Time scale.** The time scale changes how many ticks run per second of real time, never `dt`. A test shows a run
   at time scale 0.5 reaching, tick for tick, the same states as a run at 1.
5. **Pause and single step.** Pausing stops the ticks and freezes the fraction, and resuming runs no burst of
   catch-up ticks. A single step runs exactly one tick.
6. **Two drivers.** One driver runs on the JavaFX pulse. Another advances by times the caller supplies and runs
   without the JavaFX toolkit. Both give the same ticks for the same sequence of frame times.
7. **No silent errors.** Every exception or error that game code throws reaches an error handler the application
   sets, and the loop stops. A test throws from each kind of game callback and from an input binding and shows each
   throwable reaching the handler; none is left to `AnimationTimer`'s exception handling.
8. **Order within a tick.** Each tick freezes the input snapshot, delivers the previous step's physics events, runs
   the game's fixed update, steps physics and reads back the state that rendering uses, in that order. A test
   records the order.
9. **Per-tick input.** The snapshot holds keys, mouse buttons, mouse position and wheel. Every press and release that
   arrives while the loop runs is seen by exactly one tick, also when a frame runs no tick or several. What happens
   to input that arrives during a pause is documented and tested.
10. **Focus and text input.** When the window loses focus, the next snapshot releases every held key and button.
    While a text-input control has focus, the keys it handles stay out of the snapshot. Robot tests that the owner
    runs check both on Windows, macOS and Linux.
11. **Actions.** Game actions bind to inputs. Bindings change at run time and are saved and loaded as data; a loaded
    set equals the saved one.
12. **Same input, same ticks.** The same input script, fed through the caller-driven driver with the same frame times,
    gives bit-identical snapshots and tick outputs on every run and on each CI leg.
13. **No allocation.** In steady state the loop and its input allocate nothing per frame beyond what an empty scene
    allocates, measured over at least 10,000 frames.

## Definition of Done
- All acceptance criteria met, with the evidence (logs, counts, goldens, tables) kept with the change.
- An independent review by `game-toolkit-reviewer` signed off.
- `game-tester` produced or re-ran the criterion-to-test map and the gate figures, kept with the change.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without explicit approval from
  the repository owner.**
- The owner runs the robot tests.

## Notes
- Related: US-082 and US-087 draw with the fraction; US-088 adds gamepads to the snapshot; US-089 adds pointer
  capture and physical keys; US-093 moves stepping off the FX thread.
