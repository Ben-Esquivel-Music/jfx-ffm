# US-077 — Measure the physics port's speed against Godot before the port is written

**Status:** 📋 Ready (filed 2026-11-07 from the game-toolkit design review) · **Epic:** Game toolkit ·
**Blocked by:** US-076 (captured inputs and the C++ step time) · **Blocks:** US-079, US-086

## Story
As the maintainer of the built-in physics engine,
I want the Java port's speed measured against Godot's C++ on a slice of real code before the bulk of the port is
written,
so that, if the port cannot be fast enough, it stops early and the route to 3D is chosen again with the figures in
hand.

## Problem
Fork paths are relative to the repository root. `godot/` is Godot Engine at commit `c51ff06e88` (4.8-beta, MIT),
cloned next to this repository.
- Java 27 has no value types and no final Vector API: nothing from Valhalla is final, and the Vector API is in its
  twelfth incubator, JEP 537 (`.claude/agents/java-27.agent.md:30`, `:407`). The fork uses final features only
  (`:27-28`).
- Godot's math types are C++ values. The 2D integrator, for one, builds a new `Transform2D` for each body on each
  step (`godot/modules/godot_physics_2d/godot_body_2d.cpp:646`). A faithful Java port allocates such values or reuses
  them by hand; escape analysis cannot be relied on to remove the allocations (knowledge). JBullet, a Java port of
  Bullet, rewrote its bytecode to put math objects on a stack (knowledge).
- HotSpot does not compile a method of more than 8,000 bytecode bytes by default (knowledge: `DontCompileHugeMethods`).
  Godot's motion test is a single function of 475 lines in 2D
  (`godot/modules/godot_physics_2d/godot_space_2d.cpp:527-1001`) and of 380 lines in 3D
  (`godot/modules/godot_physics_3d/godot_space_3d.cpp:637-1016`).
- The 2D SAT solver is a template, `SeparatorAxisTest2D<ShapeA, ShapeB>`, compiled once per shape pair and reached
  through a 5×5 function table (`godot/modules/godot_physics_2d/godot_collision_solver_2d_sat.cpp:174-175`,
  `:1110-1163`). Body-pair setup, pre-solve and solve are at
  `godot/modules/godot_physics_2d/godot_body_pair_2d.cpp:251`, `:363` and `:509`; setup and island solve run on
  Godot's worker pool (`godot/modules/godot_physics_2d/godot_step_2d.cpp:245-266`).
- The fork's last faithful port, the Pisces compositor of US-046, matches its C exactly on every platform
  (`.claude/skills/jfx-ffm-testing/SKILL.md:302-306`).

## Constraints
- The measured code is a faithful port of the C++.
- Both sides are measured on the same scenario and the same host, with one worker thread, in steady state after
  warm-up. Each figure states the number of runs and their spread.
- The Java depends on `java.base` only. Production files are at most 900 lines (`wc -l`); Java 27 final features only
  (no preview, no incubator modules such as the Vector API, no `var`); minimal comments; GPLv2+CPE headers.
- Agents never run `USE_ROBOT` or `tests/system` runs; this story needs none.

## Out of scope
- The rest of the 2D port (US-079) and any 3D code (US-086).
- Absolute time budgets.

## Acceptance criteria
1. **The slice.** Before the bulk of the 2D port is written, the SAT test of two convex polygons and the body-pair
   contact setup, pre-solve and solve are ported in the memory model the full port will use.
2. **Exact.** Fed with inputs captured by US-076, the slice's outputs match the C++ bit for bit.
3. **Ratio.** The ratio of the Java time to the C++ time of the slice is measured under the conditions in
   "Constraints".
4. **Gate.**
   - At or below 2× the gate passes.
   - Above 2×, one adjustment round follows that keeps the code a faithful port and every output bit unchanged, and
     the ratio is measured again. At or below 3× the gate passes.
   - Above 3× after that round, the port route stops. 3D stays a goal: its route is chosen again, and the toolkit
     continues with the engine contract and bring-your-own engines.
5. **Allocation.** The bytes allocated per step in steady state are measured, summed over every thread that steps.
   The target is zero.
6. **Later figures.** The ratio is measured again when the 2D narrowphase is complete and when the full 2D step
   runs, and the full-step figure binds the gate. These measurements are criteria of US-079.
7. **Record.** The figures, the method, the host, the scenario and the number of runs are recorded in this story.

## Definition of Done
- All acceptance criteria met, with the evidence (logs, counts, goldens, tables) kept with the change.
- An independent review by `godot-physics-port-reviewer` signed off.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without explicit approval from
  the repository owner.**

## Notes
- The owner, 2026-11-07: "I do want to have a 3D engine, even if we build it ourselves over time."
- Related: US-079 repeats the measurement; US-086 starts only after this gate has passed and US-079 matches exactly.
