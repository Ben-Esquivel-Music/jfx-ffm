# US-081 — Port Godot's 2D character controller and collider tools above the engine contract

**Status:** 📋 Ready (filed 2026-11-07 from the game-toolkit design review) · **Epic:** Game toolkit ·
**Blocked by:** US-075 (the ported code's notices must ship), US-076 (scene-mode goldens), US-079 (the built-in
engine the goldens run on), US-080 (the contract the controller is limited to) · **Blocks:** US-082

## Story
As a developer of a 2D platformer,
I want Godot's character controller and its polygon and tile collider tools in Java, working on any engine,
so that my character moves, slides and lands as it does in Godot and runs across tiles without catching.

## Problem
Paths are relative to the repository root unless they start with `godot/`. `godot/` is Godot Engine at commit
`c51ff06e88` (4.8-beta, MIT), cloned next to this repository.
- **The controller is scene code above the motion test.** `godot/scene/2d/physics/character_body_2d.cpp` is 774
  lines. Every move goes through `move_and_collide`, which calls the server's `body_test_motion`
  (`godot/scene/2d/physics/physics_body_2d.cpp:74-79`; calls at `character_body_2d.cpp:93, 150, 305, 357, 401`). It
  also reads a platform's velocity at a point and the platform's collision layer from its body state
  (`character_body_2d.cpp:62-65, 427-446`), and it treats a collider that is another character differently
  (`:421`). Its defaults are in `character_body_2d.h:115-130`, among them a margin of 0.08, 4 slides, a 45° floor
  angle and a floor snap of 1.
- **Solid polygons.** A collision polygon in solids mode is decomposed into convex polygons
  (`godot/scene/2d/physics/collision_polygon_2d.cpp:47-61`) by polypartition's `ConvexPartition_HM`
  (`godot/core/math/geometry_2d.cpp:102`). polypartition is 2,229 lines under Expat
  (`godot/thirdparty/misc/polypartition.cpp` and `.h`; `godot/COPYRIGHT.txt:505-509`).
- **Tiles.** A tile map layer merges the collision polygons of each physics quadrant with Clipper2's union, simplifies
  the result and decomposes it again into convex shapes (`godot/modules/tilemap/tile_map_layer.cpp:847, 927-935`;
  `godot/core/math/geometry_2d.cpp:44-58`). Clipper2 is 9,869 lines of `.cpp` and `.h` (`godot/thirdparty/clipper2`)
  under BSL-1.0 (`COPYRIGHT.txt:231-234`), which the FSF lists as "compatible with the GNU GPL"
  (https://www.gnu.org/licenses/license-list.en.html#boost).
- **An engine's own motion test moves the character differently.** Godot's Jolt backend reimplements the motion
  test as recover, cast and collide, with recovery settings of its own
  (`godot/modules/jolt_physics/spaces/jolt_physics_direct_space_state_3d.h:58-60`;
  `godot/modules/jolt_physics/jolt_project_settings.cpp:58-60`), so the same controller gives different results on
  each engine (inferred).

## Constraints
- The controller and the collider tools use only the public engine contract of US-080. What they need that the
  contract lacks, such as a platform's velocity at a point, is added to the contract and its kit, never reached
  around it.
- As US-079: pure Java on `java.base` only, in the game toolkit's own modules; a faithful port with every Godot
  behaviour kept (fixes are their own stories and go to Godot first); production files at most 900 lines
  (`wc -l`, no cramming) and no method of 8,000 bytecode bytes or more; Java 27 final features only (no preview, no
  incubator, no `var`); comments only for a non-obvious "why".
- Each ported file carries the GPLv2+CPE header plus the ported-file block of
  `.claude/skills/openjfx-conventions/SKILL.md:86-89`, and is listed in the provenance map with its upstream path
  and commit. The Godot, polypartition and Clipper2 notices ship through US-075.
- Agents never run `USE_ROBOT` or `tests/system`; the owner runs robot and visual tests. This story needs none.

## Out of scope
- The 3D character controller (US-087); Godot's other scene nodes, such as ray casts, shape casts and vehicles.
- Debug drawing, the tile layer's rendering and the platformer sample (US-082); a tile-map editor.

## Acceptance criteria
1. **Move and slide.** Godot's move-and-slide is ported with its defaults: grounded and floating modes, floors,
   walls, ceilings, slopes, moving platforms, floor snapping and the slide limit.
2. **Bit-exact on the built-in engine.** On US-079's engine the controller's per-step output equals US-076's
   scene-mode goldens bit for bit, on all five CI targets, over scenarios that exercise each behaviour of criterion 1.
   If US-076 shows exact capture impossible, US-079's agreed tolerance applies.
3. **Any engine.** The conformance kit gains invariant checks of the controller that run against every engine, and
   their results appear in each engine's difference table.
4. **Solid polygons.** A solid polygon becomes the same convex shapes Godot's decomposition gives for the same input.
5. **Seam-free tiles.** Solid tiles merge into colliders as Godot's tile map layer merges them, with the same
   results for the same tiles, and a character sliding along a row of tiles never catches on a tile edge, on the
   built-in engine and in the kit for any engine.
6. **Licences.** The notices of every ported source, BSL-1.0 for Clipper2 included, reach the jar and the SDK through
   US-075's mechanism, and its check passes.

## Definition of Done
- All acceptance criteria met, with the evidence (golden comparisons per target, kit results, difference tables)
  kept with the change.
- An independent review by `godot-physics-port-reviewer` signed off.
- `game-tester` re-ran the golden, determinism and allocation gates independently of the porter.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without explicit approval from
  the repository owner.**

## Notes
- Related: US-082 (the 2D preview that uses the controller and the colliders).
