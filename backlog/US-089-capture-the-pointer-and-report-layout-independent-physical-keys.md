# US-089 — Capture the pointer and report layout-independent physical keys

**Status:** 📋 Ready (filed 2026-11-07 from the game-toolkit design review) · **Epic:** Game toolkit ·
**Blocked by:** none · **Blocks:** none

## Story
As a JavaFX game developer,
I want to capture the pointer for mouse-look and to bind keys by their position on the keyboard,
so that first-person and orbit cameras work and the same controls work on every keyboard layout.

## Problem
Paths that start with `com/` or `javafx/` are under `modules/javafx.graphics/src/main/java/`, and paths that start
with `native-glass/` are under `modules/javafx.graphics/src/main/`.
- No public API captures the pointer or reports relative motion. `MouseEvent` reports where the pointer is, not how
  far it moved: x, y, z, and screen and scene coordinates (`javafx/scene/input/MouseEvent.java:584-702`), so a
  camera that turns with the mouse stops at the screen edge (inferred).
- No physical key reaches an application. `KeyEvent` carries a character, a text, a key code and four modifiers
  (`javafx/scene/input/KeyEvent.java:189-287`), and Glass passes a key event on as a type, a key code, characters and
  modifiers (`com/sun/glass/ui/View.java:998`).
- macOS Glass derives the key code of a layout-sensitive key from the current keyboard layout
  (`native-glass/mac/GlassKey.m:185-210`, `:319-362`, `:421-423`), so one physical key can report different key
  codes under different layouts (inferred), and a game bound to W, A, S and D loses those positions on a French
  AZERTY layout (inferred).
- GTK Glass sets `GDK_BACKEND=x11` before it loads GTK (`com/sun/glass/ui/gtk/GtkLibraryLoader.java:118`,
  `:150-152`), so in a Wayland session it runs through XWayland (inferred).
- The README's non-goals allow logic changes and new public API in the `javafx.*` modules outside javafx.media only
  where a backlog story names the change (`README.md`, "Non Goals").

## Constraints
- **The change this story names**, for the README's exception: new public API in the javafx.graphics module to
  capture and release the pointer of a window and to receive relative motion while it is captured; a physical key
  and its label in the current layout on key events; and the Glass logic behind them on Windows, macOS and GTK.
  Nothing else in `javafx.*` changes.
- Any native code is bound through FFM under the rules of `.claude/skills/jni-to-ffm-migration/SKILL.md:148-203`.
- Production Java files are at most 900 lines (`wc -l`); tests and shims are exempt. Java 27 final features only: no
  preview, no incubator such as the Vector API, no `var`. Comments only for a non-obvious "why". GPLv2+CPE headers.
- Agents never run `USE_ROBOT` or `tests/system` runs; the owner runs the robot tests.

## Out of scope
- Pointer capture on Monocle and Headless, beyond refusing it (criterion 2).

## Acceptance criteria
1. **Pointer capture.** On Windows, macOS and GTK, an application can capture the pointer for a focused window. The
   pointer is hidden and confined to the window, and motion arrives as relative motion that the screen edge does not
   stop. The application can release it, and it is released when the window loses focus, is hidden or is closed. On
   GTK this works in an X11 session and in a Wayland session, and any difference between the two is documented.
2. **Refused elsewhere.** On a Glass platform without capture, a capture request is refused in a documented way and
   is never ignored silently.
3. **Physical keys.** Every key event carries a physical key that is the same for the same key position under any
   layout, plus that key's label in the current layout. Robot tests show this on Windows, macOS and GTK with at least
   the US QWERTY, French AZERTY, German QWERTZ and Russian layouts.
4. **Nothing else changes.** Key codes, characters, text, mouse events and focus behave as before. The module tests
   pass with the counts of a run before the change, plus the new tests.
5. **Documented.** The new public API has javadoc that passes doclint.

## Definition of Done
- All acceptance criteria met, with the evidence (logs, counts, goldens, tables) kept with the change.
- An independent review by `game-toolkit-reviewer` signed off, and by `ffm-reviewer` for the native parts.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without explicit approval from
  the repository owner.**
- The owner runs the robot tests.

## Notes
- Related: US-087 (the 3D preview, whose first-person and orbit cameras want pointer capture) and US-088 (gamepads,
  the other input that Glass lacks).
