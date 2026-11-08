# US-082 — Ship a playable 2D game preview

**Status:** 📋 Ready (filed 2026-11-07 from the game-toolkit design review) · **Epic:** Game toolkit ·
**Blocked by:** US-078 (the loop, interpolation and input), US-080 (the engine contract that bodies and the debug view
use), US-081 (the character controller and the tile colliders) · **Blocks:** US-084

## Story
As a JavaFX developer building a 2D platformer,
I want bodies to drive my nodes, a debug view, sprites, a large tile layer and a camera on top of the loop and the
physics contract,
so that I can build and ship a playable 2D game with JavaFX alone.

## Problem
Paths that start with `com/` or `javafx/` are under `modules/javafx.graphics/src/main/java/`. `godot/` is Godot
Engine at commit `c51ff06e88` (4.8-beta, MIT), cloned next to this repository.
- `Node` is sealed (`javafx/scene/Node.java:414-416`): a game cannot extend it directly, only its non-sealed
  subclasses such as `Parent` (`javafx/scene/Parent.java:80`) and `Canvas` (`javafx/scene/canvas/Canvas.java:74`).
- JavaFX 2D and Godot 2D share their units: in JavaFX the y axis increases downwards in pixels
  (`javafx/scene/Node.java:248`), and Godot's default 2D gravity is 980 px/s² along (0, 1)
  (`godot/servers/physics_2d/physics_server_2d.cpp:354-355`).
- Nothing compiles the samples: the Maven module list does not include `apps/` (`pom.xml:37-52`), and its Ant build
  is dormant and wired into nothing (`apps/build.xml:4-10`).
- The Headless Glass platform runs without a display; `-Dglass.platform=headless` selects it
  (`com/sun/glass/ui/Platform.java:43`, `:54-55`).
- Each `AudioClip` play creates a new clip player
  (`modules/javafx.media/src/main/java/com/sun/media/jfxmediaimpl/NativeMediaAudioClipPlayer.java:172`), which
  obtains a new media player when it plays (`:319`), so a clip's start latency includes building a player
  (inferred). US-092 holds the requirements for game sound.

## Constraints
- Public JavaFX API only, and no change to the `javafx.*` modules. The game toolkit's modules do not require
  `javafx.media`.
- Production Java files are at most 900 lines (`wc -l`); tests and shims are exempt. Java 27 final features only: no
  preview, no incubator such as the Vector API, no `var`. Comments only for a non-obvious "why". GPLv2+CPE headers.
- Agents never run `USE_ROBOT` or `tests/system` runs; the owner runs the robot and visual tests.

## Out of scope
- Particles, navigation and a tile-map editor.
- Game sound beyond `AudioClip` (US-092), and 3D (US-087).

## Acceptance criteria
1. **Nodes follow bodies.** Any JavaFX node can follow a physics body and is drawn at the pose interpolated with the
   loop's fraction (US-078). A body that the game moves to a new place, rather than one that the simulation moves,
   jumps there instead of sliding.
2. **Debug view.** The shapes and contacts of a world can be drawn over the game, with any engine that passes the 2D
   conformance kit (US-080). When it is off, it adds no node and allocates nothing.
3. **Sprite animation.** Sprite animations run on the game clock: they pause with the loop, follow its time scale and
   show the same frame for the same tick and fraction on every run.
4. **Large tile layer.** A tile layer draws only the tiles in view. A test shows the per-frame work (nodes in the
   scene and tiles drawn) of a 1,000 × 1,000-tile layer equal to that of a layer just larger than the view.
5. **Camera.** A 2D camera follows a target, stays within bounds the game sets, and zooms. Optional pixel snapping
   puts every sprite and tile on whole device pixels when it is on.
6. **Platformer sample.** A platformer sample uses the character controller and tile colliders of US-081, the debug
   view, sprites, a tile layer and the camera. The Maven build compiles it. CI runs it on the Headless platform on
   every leg, from a fixed input script to a recorded end state.
7. **No allocation.** With the sample running in steady state, the game layer allocates nothing per frame beyond
   what an empty scene allocates, measured over at least 10,000 frames.
8. **Sound.** A sample that plays sound uses `AudioClip`, and its documentation states the latency measured on each
   platform it ran on.
9. **On screen.** Robot tests that the owner runs check the sample on Windows, macOS and Linux.

## Definition of Done
- All acceptance criteria met, with the evidence (logs, counts, goldens, tables) kept with the change.
- An independent review by `game-toolkit-reviewer` signed off.
- `game-developer` built the sample against the public API only; its friction list and the owner's visual checklist are
  kept with the change.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without explicit approval from
  the repository owner.**
- The owner runs the robot tests.

## Notes
- Related: US-092 (low-latency game sound through javafx.media); US-084 ships this sample as a jlink image and a
  jpackage installer.
