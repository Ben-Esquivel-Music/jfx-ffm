# US-085 — Let a native 3D physics engine plug into a 3D engine contract

**Status:** 📋 Ready (filed 2026-11-07 from the game-toolkit design review) · **Epic:** Game toolkit ·
**Blocked by:** US-080 (the 2D contract and conformance kit that this story extends to 3D) ·
**Blocks:** US-086, US-087, US-091, US-095

## Story
As a game developer who needs production-grade 3D physics before the built-in 3D engine lands,
I want a 3D engine contract that a native engine can plug into through FFM,
so that I can build a 3D game now and later run it on the built-in engine without rewriting it.

## Problem
`godot/` is Godot Engine at commit `c51ff06e88` (4.8-beta, MIT), cloned next to this repository. Fork paths are
relative to the repository root.
- Godot's 3D engine contract is 196 pure virtual functions over opaque handles (`grep -c '= 0;'` on
  `godot/servers/physics_3d/physics_server_3d.h`). Bodies are static, kinematic, rigid or rigid with rotation locked
  (`godot/servers/physics_3d/physics_server_3d_enums.h:94-97`). There are nine shape kinds besides soft-body and
  custom shapes (`:37-48`) and five joint kinds: pin, hinge, slider, cone-twist and 6DOF (`:139-143`). The queries
  are ray, point, shape overlap, shape cast, collide-shape and rest information
  (`godot/servers/physics_3d/direct_states/physics_direct_space_state_3d.h:67-72`). The motion test reports at most
  32 collisions (`godot/servers/physics_3d/physics_server_3d_types.h:175`).
- Godot's editor sets Jolt as the engine of new 3D projects (`godot/editor/editor_node.cpp:8521`). GodotPhysics3D
  stays the registered default (`godot/modules/godot_physics_3d/register_types.cpp:57-58`).
- Godot's Jolt backend shows what a contract written for one engine does to a second one:
  - it warns about and ignores 20 of the 22 slider-joint parameters when they are set away from their defaults
    (`godot/modules/jolt_physics/joints/jolt_slider_joint_3d.cpp:255-364`);
  - it refuses custom shapes (`godot/modules/jolt_physics/jolt_physics_server_3d.cpp:130-132`);
  - when an overlap begins and ends between two flushes, Jolt reports an enter and then an exit
    (`godot/modules/jolt_physics/objects/jolt_area_3d.cpp:142-156`), where GodotPhysics3D reports nothing
    (`godot/modules/godot_physics_3d/godot_area_3d.cpp:245-251`).
- Jolt's cross-platform determinism is a build option, `JPH_CROSS_PLATFORM_DETERMINISTIC`
  (`godot/thirdparty/jolt_physics/Jolt/Core/Core.h:18`), which also turns FMA off (`:133`, `:175`). Godot's build
  does not define it (`godot/modules/jolt_physics/SCsub:150-157`). So whether a native engine is deterministic
  across platforms depends on how it is built (inferred).
- The fork's shipped modules get no native access, and a test-only native engine gets it in test scope only, never
  in the SDK (`.claude/skills/openjfx-conventions/SKILL.md:157-164`). The fork's library loader is exported only to
  javafx.media and javafx.web (`modules/javafx.graphics/src/main/java/module-info.java:72-74`). An exception that
  escapes an upcall ends the JVM (`.claude/skills/jni-to-ffm-migration/SKILL.md:348`).

## Constraints
- The contract and the kit live in the game toolkit's own modules, depend on `java.base` only and expose no FFM types.
- Any native-access grant the native engine needs is test scope only.
- Java 27 final features only: no preview, no incubator such as the Vector API, no `var`. Comments only for a
  non-obvious "why". GPLv2+CPE headers.
- Agents never run `USE_ROBOT` or `tests/system` runs; nothing here needs them.

## Out of scope
- The built-in 3D engine (US-086), and the 3D character controller and scene binding (US-087).
- Soft bodies, custom shapes and swapping engines in a running world.
- Shipping any native engine in the SDK.

## Acceptance criteria
1. **3D contract.** The toolkit's public API covers 3D worlds, the four body modes, areas with their overlap events
   and gravity and damping overrides, every shape kind of Godot's 3D server except soft-body and custom shapes, the
   six query kinds, a motion test that reports up to 32 collisions, and body and area events. Each of the five
   joint kinds is a capability that an engine declares.
2. **The 2D rules hold.** Every requirement US-080 sets for the 2D contract and kit holds for the 3D ones, among
   them: engines are found at run time on the module path or the class path with no change to the fork; they are
   selected in code or by a system property; a missing engine fails with an error listing the engines found and
   why each was rejected, never with a fallback engine; no engine is swapped in a running world; a missing
   capability fails before the engine is called, and nothing unsupported is silently ignored; engine-specific
   tuning stays out of the contract, and a world reports the tuning it ignored; input is validated once, so engines
   never see non-finite or out-of-range values; a world has one owner thread at a time, handed over explicitly; an
   error leaves a world unchanged or in a defined failed state; and no engine calls into application code while a
   step runs.
3. **Written semantics, checked.** The 3D contract's semantics are written in prose: which calls are legal when,
   when commands take effect, the event-order guarantees, the motion test's margin, recovery and collision count,
   and the axes and units. Every normative clause is checked against a 3D golden captured by US-076.
4. **3D conformance kit.** The kit runs 3D scenarios in its three modes (exact against goldens, invariants for any
   engine, and tolerance with bounds agreed before any comparison). It runs outside the repository with one
   command and prints a difference table per engine. Its 3D scenarios include hostile inputs.
5. **A native engine passes.** A native 3D engine under a GPLv2-compatible licence, bound through FFM, passes the
   kit in invariant mode for every capability it declares, hostile-input scenarios included. Its difference table
   is published with this story. It builds and passes on the CI legs it supports, and this story records which
   they are.
6. **Determinism claims are proven.** If the native engine declares cross-platform determinism, the kit shows
   identical outputs for the same call history on every CI leg it builds on.
7. **Native boundaries.** The engine and its binding are not in the SDK, the fork's shipped modules gain no native
   access, and no engine thread calls into Java. The contract's documentation says that a native engine can crash
   the JVM.
8. **Draft status.** Types that the 2D and 3D contracts share leave draft only after a 3D engine passes the kit
   (criterion 5). The 3D contract stays draft until the built-in 3D engine passes it too (US-086).
9. **Size and docs.** Every production Java file is at most 900 lines by `wc -l` (tests and shims are exempt),
   reached without putting several statements on a line or going past 120 columns. The public API docs pass
   doclint.

## Definition of Done
- All acceptance criteria met, with the evidence (logs, counts, goldens, tables) kept with the change.
- An independent review by `game-toolkit-reviewer`, and by `ffm-reviewer` for the FFM binding, signed off.
- `game-tester` produced the kit runs and difference tables; a `game-developer` probe game used the public API, and its
  friction list is kept with the change.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without explicit approval from
  the repository owner.**

## Notes
- The owner, 2026-11-07: "I do want to have a 3D engine, even if we build it ourselves over time." "Bring your own
  engine" means physics engines only; the renderer stays JavaFX.
- Related: US-080 (the 2D contract), US-083 (a native 2D engine under the same native rules), US-086 (the built-in
  3D engine, which takes the 3D contract out of draft), US-087 (the 3D preview runs on both engines), US-095 (the
  quality scenarios run against this story's engine too).
