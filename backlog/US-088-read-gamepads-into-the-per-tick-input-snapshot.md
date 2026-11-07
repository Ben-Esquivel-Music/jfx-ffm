# US-088 — Read gamepads into the per-tick input snapshot

**Status:** 📋 Ready (filed 2026-11-07 from the game-toolkit design review) · **Epic:** Game toolkit ·
**Blocked by:** US-078 (the per-tick input snapshot and the actions that pads join) · **Blocks:** none

## Story
As a JavaFX game developer,
I want gamepads in the same per-tick input snapshot as keys and the mouse,
so that players can use a controller on Windows, macOS and Linux.

## Problem
Paths that start with `com/` are under `modules/javafx.graphics/src/main/java/`, and paths that start with
`native-glass/` are under `modules/javafx.graphics/src/main/`. `godot/` is Godot Engine at commit `c51ff06e88`
(4.8-beta, MIT), cloned next to this repository.
- The fork reads no game controller. A search of `modules/*/src/main/java` and `native-glass/` for gamepad,
  joystick, XInput, GameController and IOHIDManager finds a comment (`com/sun/glass/ui/monocle/InputDevice.java:49`)
  and two Monocle constants, `BTN_JOYSTICK` and `BTN_GAMEPAD` (`com/sun/glass/ui/monocle/LinuxInput.java:327`,
  `:341`), which only a test shim reads
  (`modules/javafx.graphics/src/shims/java/com/sun/glass/ui/monocle/LinuxInputShim.java:318`, `:332`).
- WebKit's Gamepad API is off: `ENABLE_GAMEPAD` defaults to off
  (`modules/javafx.web/src/main/native/Source/cmake/WebKitFeatures.cmake:171`), and `OptionsJava.cmake` in the same
  directory does not turn it on.
- Glass owns each platform's event loop: `::GetMessage` on Windows (`native-glass/win/glass_win_api.cpp:563`),
  `[NSApp run]` on macOS (`native-glass/mac/GlassApplication.m:725`) and `gtk_main()` on GTK
  (`native-glass/gtk/GlassApplication.cpp:159`).
- Godot reads joypads through SDL 3.4.16, under Zlib, on Windows, macOS and Linux
  (`godot/thirdparty/README.md:1067-1071`, `godot/drivers/SCsub:65-67`), with a mapping database taken from
  SDL_GameControllerDB (`godot/core/input/gamecontrollerdb.txt:1-2`). This is a fact about Godot, not a requirement.
- The game toolkit's modules get no native access (`.claude/skills/openjfx-conventions/SKILL.md:157-164`). Native
  code is bound through FFM under the rules of `.claude/skills/jni-to-ffm-migration/SKILL.md:148-203`.

## Constraints
- Reading pads does not block or replace Glass's event loop on any platform, nor delay or reorder the keyboard, mouse
  and window events it delivers. Existing input behaviour does not change.
- Any native code sits behind a plain C ABI bound through FFM under the house rules. Its licence is GPLv2-compatible,
  and its notices ship the way US-075 ships every module's notices.
- Native access stays within the rule above. Any module this story grants native access, and any change it makes to
  a `javafx.*` module, is named in this story and in that rule before it lands.
- Production Java files are at most 900 lines (`wc -l`); tests and shims are exempt. Java 27 final features only: no
  preview, no incubator such as the Vector API, no `var`. Comments only for a non-obvious "why". GPLv2+CPE headers.
- Agents never run `USE_ROBOT` or `tests/system` runs; the owner runs the robot tests and the tests on real pads.

## Out of scope
- Rumble, motion sensors and touchpads.

## Acceptance criteria
1. **Standard layout.** On Windows, macOS and Linux, the buttons, both sticks, both triggers and the d-pad of a
   standard-layout gamepad appear in the per-tick snapshot of US-078. Every button press and release is seen by
   exactly one tick, as for keys.
2. **Hot-plug.** A pad connected or disconnected while the game runs appears or disappears at the next tick, and the
   game is told.
3. **Player slots.** Several pads work at once, each in a stable player slot: a pad keeps its slot while it is
   connected and gets it back when it reconnects, unless another pad has taken it.
4. **Dead zones.** Stick and trigger dead zones apply before values reach the snapshot. They have documented
   defaults and are configurable per pad.
5. **Mappings.** Common controllers map to the standard layout through a mapping database. The story records which
   controllers were tested on which operating system.
6. **Actions.** Pad inputs bind to the actions of US-078, rebind at run time and save and load as data like keys.
7. **Tests.** CI tests pads through virtual devices where the platform allows it, and the story records which legs
   do. The owner runs the tests on real pads on each operating system.

## Definition of Done
- All acceptance criteria met, with the evidence (logs, counts, goldens, tables) kept with the change.
- An independent review by `game-toolkit-reviewer` signed off, and by `ffm-reviewer` for any native code.
- A `game-developer` probe read pads through the public API; the owner's real-pad checklist is kept with the change.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without explicit approval from
  the repository owner.**
- The owner runs the tests on real pads.

## Notes
- Related: US-089 (pointer capture and physical keys, the other input that Glass lacks).
