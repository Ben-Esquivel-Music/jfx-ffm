# US-087 — Ship a playable 3D game preview

**Status:** 📋 Ready (filed 2026-11-07 from the game-toolkit design review) · **Epic:** Game toolkit ·
**Blocked by:** US-076 (scene-mode goldens for the character controller), US-078 (the loop and per-tick input),
US-085 (the 3D contract and a native 3D engine), US-086 (the built-in 3D engine) · **Blocks:** none

## Story
As a JavaFX developer building a 3D game,
I want physics bodies to drive JavaFX 3D nodes smoothly, with a character controller, picking and debug drawing that
work on any 3D engine,
so that I can build a playable 3D game on JavaFX 3D as it is today.

## Problem
`godot/` is Godot Engine at commit `c51ff06e88` (4.8-beta, MIT), cloned next to this repository. Fork paths are
relative to `modules/javafx.graphics/src/main/java`.
- What JavaFX 3D offers today:
  - `Material` is sealed to `PhongMaterial` (`javafx/scene/paint/Material.java:48`).
  - A shape is lit by at most three non-ambient lights, the first three found
    (`com/sun/javafx/sg/prism/NGShape3D.java:157-159`, `:187-189`).
  - A vertex is a point and texture coordinates, with or without a normal: no vertex colours or joint weights
    (`javafx/scene/shape/VertexFormat.java:38`, `:43`).
  - Lines exist only as the `DrawMode.LINE` wireframe of a mesh (`javafx/scene/shape/DrawMode.java:41-42`).
  - The software pipeline has no 3D (`com/sun/prism/sw/SWPipeline.java:88-89`).
  - Nothing draws one mesh many times with different transforms: "instancing" and "instanced" occur nowhere in
    `javafx/scene`, `com/sun/prism` or `com/sun/javafx/sg`.
- JavaFX 3D has Y pointing down (`javafx/scene/PerspectiveCamera.java:54-56`). Godot 3D has Y pointing up: gravity is
  along (0, -1, 0) (`godot/servers/physics_3d/physics_server_3d.cpp:555`) and a character's up direction is
  (0, 1, 0) (`godot/scene/3d/physics/character_body_3d.h:152`).
- Godot's 3D character controller, `godot/scene/3d/physics/character_body_3d.cpp`, is 968 lines. It moves only
  through the engine's motion test (`move_and_collide`, `godot/scene/3d/physics/physics_body_3d.cpp:112-113`), and
  it reads a platform body's state and collision layer (`character_body_3d.cpp:77`, `:617`, `:631`, `:635`). Its
  defaults are at `character_body_3d.h:117-119` and `:139-152`.
- Godot builds its shape debug meshes in the resource layer, not in the physics engine
  (`godot/scene/resources/3d/shape_3d.cpp:103`), and asks the engine for contacts
  (`godot/servers/physics_3d/physics_server_3d.h:107-108`).

## Constraints
- Public JavaFX API only, and JavaFX 3D as it is: this story does not wait for US-089, US-090 or US-091.
- Engines are reached only through the 3D contract of US-085, and the character controller uses nothing else.
- The game layer does not require javafx.media.
- The controller is a faithful port that keeps Godot's defaults and quirks. Each ported file keeps its upstream
  notice, is marked as altered, and has its upstream path and commit in the provenance map that a test checks. The
  notices ship through US-075.
- Java 27 final features only: no preview, no incubator such as the Vector API, no `var`. Comments only for a
  non-obvious "why". GPLv2+CPE headers.
- Agents never run `USE_ROBOT` or `tests/system` runs. The owner runs the robot and visual tests, and every visual
  check of 3D rendering is an owner-run robot test.

## Out of scope
- Renderer features: shadows, more than three lights, skinning, instancing, wide lines and new materials (US-090);
  glTF models (US-091); pointer capture and physical keys (US-089); sound (US-092).
- Godot's other 3D scene-layer nodes, such as `VehicleBody3D`, and ragdolls.
- Stepping physics off the FX thread (US-093).

## Acceptance criteria
1. **Bodies drive nodes.** Any JavaFX 3D node in a `SubScene` can follow a body, with its position and rotation
   interpolated on every pulse. A test under a 16 ms pulse at 60 ticks per second shows that a pulse that runs no
   tick draws no repeated pose.
2. **One axis mapping.** One documented mapping between the physics axes and JavaFX 3D's is used by the node
   binding, picking and debug drawing alike. A test shows that a body falling under default gravity moves down the
   screen.
3. **Character controller.** Godot's 3D move-and-slide (floors, walls, ceilings, slopes, moving platforms, snapping
   and the slide limit) is ported. On the built-in engine it is bit-exact against the scene-mode goldens of US-076.
4. **Picking.** A point on a `SubScene` gives a ray from its camera that queries the engine, and the result names
   the body and the hit point. Where each node has the shape of its body, the body found is the one whose node
   JavaFX picks at the same point.
5. **Debug drawing.** The shapes and contacts of any engine can be drawn in 3D. Drawing that is switched off adds no
   nodes and costs nothing per frame.
6. **Both engines.** The controller and every 3D sample run on the built-in engine and on the native engine of
   US-085, with only the engine selection changed. The controller's invariant checks run in the conformance kit,
   and both engines pass them.
7. **No allocation per frame.** In steady state the game layer allocates nothing per frame beyond what an empty
   scene allocates.
8. **A playable sample.** The build compiles at least one 3D sample in which a character, moved by keyboard and
   mouse, walks through a level and pushes dynamic bodies. Each 3D sample states its node count and which renderer
   limits it hit. CI runs each sample's scripted simulation headless; its rendering is checked only by owner-run
   robot tests.
9. **Size.** Every production Java file is at most 900 lines by `wc -l` (tests and shims are exempt), reached
   without putting several statements on a line or going past 120 columns.

## Definition of Done
- All acceptance criteria met, with the evidence (logs, counts, goldens, tables) kept with the change.
- An independent review by `game-toolkit-reviewer`, and by `godot-physics-port-reviewer` for the ported character
  controller, signed off.
- `game-developer` built the sample against the public API only; its friction list and the owner's visual checklist are
  kept with the change.
- The owner runs the robot tests.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without explicit approval from
  the repository owner.**

## Notes
- The owner, 2026-11-07: "Bring your own engine" means physics engines only; the renderer stays JavaFX.
- Related: US-082 (the 2D preview), US-089 (pointer capture for first-person and orbit cameras), US-090 (the
  rendering a 3D game needs), US-091 (glTF models), US-092 (sound).
