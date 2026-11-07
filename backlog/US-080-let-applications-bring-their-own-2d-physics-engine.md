# US-080 — Let applications bring their own 2D physics engine

**Status:** 📋 Ready (filed 2026-11-07 from the game-toolkit design review) · **Epic:** Game toolkit ·
**Blocked by:** US-079 (the built-in engine, the first engine the contract must fit; if US-077's gate stops the
port, not blocked: see criterion 8) ·
**Blocks:** US-081, US-082, US-083, US-085, US-093

## Story
As a game developer choosing which physics engine a game runs on,
I want one public 2D physics API that any engine plugs into, and a kit that shows how each engine behaves,
so that I can change engines without rewriting the game and know beforehand what changes.

## Problem
Paths are relative to the repository root unless they start with `godot/`. `godot/` is Godot Engine at commit
`c51ff06e88` (4.8-beta, MIT), cloned next to this repository.
- **Godot's contract is a header, not a specification.** `PhysicsServer2D` declares 138 pure virtuals over opaque
  handles (`grep -c '= 0;' godot/servers/physics_2d/physics_server_2d.h`), and shape data, area and body parameters
  and body state pass as `Variant` (`physics_server_2d.h:76, 132, 194, 201`). Rules engines must still follow are
  written nowhere: body callbacks run before area callbacks
  (`godot/modules/godot_physics_2d/godot_space_2d.cpp:1111-1123`), which Godot's Jolt backend copies
  (`godot/modules/jolt_physics/spaces/jolt_space_3d.cpp:221-233`); a body's mode, an area's monitorable flag and
  a shape's disabled flag cannot change while those callbacks run (`godot_physics_server_2d.cpp:40-41, 354, 482,
  569, 653, 1316-1324`); and Jolt silences an error only for compatibility with Godot's engine
  (`godot/modules/jolt_physics/objects/jolt_body_3d.cpp:1086-1088`).
- **What leaks when an engine changes.** Godot's Jolt backend warns and ignores 20 of the 22 slider-joint
  parameters (`godot/modules/jolt_physics/joints/jolt_slider_joint_3d.cpp:255-369`), refuses custom shapes
  (`godot/modules/jolt_physics/jolt_physics_server_3d.cpp:130-132`), and reimplements the motion test with
  recovery settings of its own (`godot/modules/jolt_physics/spaces/jolt_physics_direct_space_state_3d.h:58-60, 81`;
  `godot/modules/jolt_physics/jolt_project_settings.cpp:58-60`). Unity says of its two backends: "Simulation
  behavior between Havok Physics for Unity and Unity Physics is similar but not identical"
  (https://docs.unity3d.com/Packages/com.havok.physics@1.2/manual/index.html).
- **The motion test is behaviour, not a signature.** Godot's 2D motion test floors the margin at 0.0001, makes up to
  four recovery passes with a factor of 0.4, searches the cast in 8 steps, reports a collision when the end pose is
  unsafe or when recovery happened and `recovery_as_collision` is set, and returns one collision
  (`godot/modules/godot_physics_2d/godot_space_2d.cpp:40, 565, 588, 709, 811, 882`;
  `godot/servers/physics_2d/physics_server_2d_types.h:150-168`).
- **Godot's fallback hides a missing engine.** It tries the named engine, then the default, then a dummy server
  that disables 2D physics with only a warning (`godot/servers/physics_2d/physics_server_2d_manager.cpp:54-74`).
  A change of engine needs a restart (`:89`).
- **The fork.** The only `ServiceLoader` call under `modules/`
  (`modules/javafx.web/src/main/java/com/sun/webkit/plugin/PluginManager.java:42-43`) is in a module whose
  `module-info.java` declares no `uses`; in a named module that call throws `ServiceConfigurationError` (knowledge,
  the `ServiceLoader` javadoc). The game toolkit's modules get no native access
  (`.claude/skills/openjfx-conventions/SKILL.md:157-164`). The javadoc profile runs doclint `all` with warnings as
  errors (`pom.xml:122-125`).

## Constraints
- The API depends on `java.base` only and exposes no `java.lang.foreign` type. It lives in the game toolkit's own
  modules, whose names are clearly the fork's own (not `javafx.*`, not `jfx.incubator.*`), and adds nothing to
  `javafx.*`.
- Every production file is at most 900 lines (`wc -l`); tests are exempt, and the limit is never met by cramming.
  Java 27 final features only: no preview, no incubator, no `var`. GPLv2+CPE headers; comments only for a
  non-obvious "why".
- The public API's documentation passes doclint as the javadoc profile runs it.
- Agents never run `USE_ROBOT` or `tests/system`; the owner runs robot and visual tests. This story needs none.

## Out of scope
- The native engine that takes the contract out of draft (US-083); the 3D contract (US-085); the character
  controller (US-081); a physics thread and snapshots (US-093); finding the engine in a jlink image (US-084).
- Swapping engines while a game runs (README, "Non Goals").

## Acceptance criteria
1. **One API.** The game toolkit's modules offer a public 2D physics API for worlds, bodies, areas, joints, shapes,
   queries, the motion test and events. Games and every engine, US-079's built-in engine included, meet only there.
2. **Finding an engine.** Engines are found at run time on the module path or the class path, with no change to the
   fork. A game selects one in code or by a system property; with neither, it gets the built-in engine. A missing or
   unusable engine fails with an error that lists the engines found and why each was rejected. No engine is ever
   substituted silently.
3. **One engine per world.** A world never changes engine. One JVM can hold worlds on different engines at once.
4. **Capabilities and tuning.** Engines declare the capabilities they support beyond a baseline every engine meets.
   Using a missing capability fails before the engine is called, and nothing unsupported is silently ignored.
   Engine-specific tuning is separate from the contract, and a world reports any tuning its engine ignored.
5. **Written semantics.** The contract states in prose which calls are legal when, when commands take effect, which
   event orders are guaranteed and which are engine-defined, and the motion test's margin, recovery and
   collision-count semantics. Every normative clause is checked by a kit scenario whose expected outcome comes from a
   US-076 golden.
6. **Validation once.** The API validates every input before an engine sees it. Engines never receive a non-finite
   value or a value outside the ranges the contract documents.
7. **Threads and failure.** A world has one owner thread at a time, handed over explicitly; a call from any other
   thread fails. An error leaves the world unchanged or in a documented failed state. No engine calls into
   application code while a step runs.
8. **Conformance kit.** One command runs the kit outside the repository against an engine, in three modes: exact
   (the built-in engine against the goldens), invariant (any engine) and tolerance (bounds agreed and recorded before
   the comparison). It writes a per-engine difference table. The built-in engine passes all three modes. An
   independently developed pure-Java 2D engine, a pinned test dependency that never ships in the SDK, passes for
   every capability it declares; its difference table is kept with the change. If US-077's gate stopped the port,
   a second independently developed engine takes the built-in engine's place in invariant and tolerance mode, and
   exact mode follows whichever built-in engine the re-chosen route produces.
9. **Draft.** The 2D contract is documented as draft, free to change incompatibly, until a native engine also passes
   the kit (US-083).

## Definition of Done
- All acceptance criteria met, with the evidence (kit runs per mode, difference tables) kept with the change.
- An independent review by `game-toolkit-reviewer` signed off.
- `game-tester` produced the kit runs and difference tables; a `game-developer` probe game used the public API, and its
  friction list is kept with the change.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without explicit approval from
  the repository owner.**

## Notes
- The owner, 2026-11-07: "bring your own engine" means physics engines only; the renderer stays JavaFX.
- US-085 keeps the types the 2D and 3D contracts share in draft until a 3D engine passes too.
- Related: US-093 (snapshot and restore as an optional capability).
