# US-093 — Step physics off the FX thread and restore snapshots bit-exactly

**Status:** 📋 Ready (filed 2026-11-07 from the game-toolkit design review) · **Epic:** Game toolkit ·
**Blocked by:** US-078 (the loop that moves off the FX thread), US-079 (the built-in 2D engine that must support
snapshots), US-080 (the engine contract that declares the capability) · **Blocks:** none

## Story
As a JavaFX game developer,
I want physics to step on its own thread and a world's state to be saved and restored exactly,
so that large scenes keep the FX thread free and a game can rewind, replay or reload its physics without drift.

## Problem
`godot/` is Godot Engine at commit `c51ff06e88` (4.8-beta, MIT), cloned next to this repository. Bare `godot_*`
files are in `godot/modules/godot_physics_2d/`.
- Nodes in the scene of a showing window may be changed only on the JavaFX Application Thread
  (`modules/javafx.graphics/src/main/java/javafx/scene/Node.java:222-226`), so a physics thread cannot write poses
  into nodes itself.
- Godot can run 2D physics on a thread of its own (`godot/modules/godot_physics_2d/register_types.cpp:43`, `:48-50`).
- Godot's body state has five members: transform, linear and angular velocity, sleeping and can-sleep
  (`godot/servers/physics_2d/physics_server_2d_enums.h:115-121`,
  `godot/servers/physics_3d/physics_server_3d_enums.h:119-125`).
- Setting a body's transform or velocity wakes it (`godot/modules/godot_physics_2d/godot_body_2d.cpp:301-368`,
  `godot_body_2d.h:289-294`), and a body that wakes goes to the head of the space's active list
  (`godot_body_2d.cpp:133-146`, `godot_space_2d.cpp:1049-1051`, `godot/core/templates/self_list.h:55-70`), which
  changes the order in which the next step visits bodies (inferred). 3D does the same
  (`godot/modules/godot_physics_3d/godot_body_3d.cpp:352-417`).
- State that later steps depend on is not part of it: a body's sleep timer, which adds up over steps
  (`godot_body_2d.h:88`, `godot_body_2d.cpp:712-718`), and each contact's accumulated impulses, which a contact found
  again in the next step starts from (`godot_body_pair_2d.h:61-65`, `godot_body_pair_2d.cpp:64-71`).
- So restoring through a state API shaped like Godot's cannot reproduce an uninterrupted run (inferred).

## Constraints
- The engine contract's threading rules (US-080) keep holding: a world has one owner thread at a time with explicit
  handover, and no engine calls into application code while a step runs.
- The loop keeps the behaviour US-078 requires when physics steps on its own thread: fixed `dt`, the tick cap, time
  scale, pause and single step, and every exception reaching the error handler and stopping the loop.
- The built-in engines stay bit-exact against their goldens (US-076).
- Production Java files are at most 900 lines (`wc -l`); tests and shims are exempt. Java 27 final features only: no
  preview, no incubator such as the Vector API, no `var`. Comments only for a non-obvious "why". GPLv2+CPE headers.
- Agents never run `USE_ROBOT` or `tests/system` runs; the owner runs the robot and visual tests.

## Out of scope
- Networking and rollback netcode. Restoring in the middle of a step.

## Acceptance criteria
1. **Own thread.** Physics can step on a thread of its own. Poses reach the FX thread without tearing: a stress test
   shows that no rendered frame mixes poses of different ticks across bodies.
2. **Picks.** A pick or other input made on the FX thread reaches the physics thread as input of the next tick,
   once, and in the order it was made.
3. **Same results on either thread.** For the same input script, a world gives bit-identical results whether it
   steps on the FX thread or on its own thread.
4. **Snapshot capability.** Snapshot and restore between steps is an optional engine capability declared through
   the engine contract. Asking an engine that does not declare it fails before the engine is called.
5. **Bit-exact restore.** Restoring a snapshot and continuing gives results bit-identical to the uninterrupted run:
   into the same world, into a world built through a different history of calls, and in a fresh JVM from the
   snapshot's saved form. A snapshot from another engine or another version of the engine is refused with an error
   and leaves the world unchanged.
6. **Conformance kit.** The conformance kit checks criteria 4 and 5 for any engine that declares the capability.
7. **Built-in engines.** Every built-in engine declares the capability and passes these checks: the 2D engine of
   US-079, and the 3D engine of US-086 in this story if it has landed, otherwise when it lands.
8. **Cost recorded.** The size of a snapshot and the time to take and restore one are recorded for the kit's
   scenarios.

## Definition of Done
- All acceptance criteria met, with the evidence (logs, counts, goldens, tables) kept with the change.
- An independent review by `game-toolkit-reviewer` signed off, and by `godot-physics-port-reviewer` for the changes
  to the built-in engines.
- `game-tester` ran the snapshot and thread checks, the fresh-JVM restore included.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without explicit approval from
  the repository owner.**

## Notes
- Related: US-085 (the 3D engine contract, through which a 3D engine declares the capability) and US-086 (the
  built-in 3D engine, which criterion 7 covers too).
