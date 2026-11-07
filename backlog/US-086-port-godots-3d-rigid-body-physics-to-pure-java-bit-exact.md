# US-086 — Port Godot's 3D rigid-body physics to pure Java, bit-exact

**Status:** 📋 Ready (filed 2026-11-07 from the game-toolkit design review) · **Epic:** Game toolkit ·
**Blocked by:** US-075 (the notices must ship before any ported file does), US-076 (the 3D goldens), US-077 (its
speed gate has passed), US-079 (the 2D port matches exactly; it brings the shared core code and BVH), US-085 (the 3D
contract and kit) · **Blocks:** US-087, US-094, US-095

## Story
As a JavaFX game developer who wants proven 3D physics with no native code,
I want Godot's 3D rigid-body physics ported faithfully to Java and proven bit-exact against a pinned Godot build,
so that my 3D game behaves the same on every desktop target with nothing native to ship.

## Problem
`godot/` is Godot Engine at commit `c51ff06e88` (4.8-beta, MIT), cloned next to this repository. Bare file names are
in `godot/modules/godot_physics_3d`.
- `godot/modules/godot_physics_3d` is 20,559 lines of `.cpp` and `.h` (`wc -l`). 1,611 of them are the soft body
  (`godot_soft_body_3d.cpp`, `.h`), which calls `RenderingServer` from the physics thread, a call the code marks as
  able to deadlock (`godot_soft_body_3d.cpp:140-144`).
- 3D needs more core code than 2D, among it `convex_hull.cpp` (2,340 lines), `basis.cpp` (1,054) and
  `geometry_3d.cpp` (985), all in `godot/core/math`. Both dimensions use the core BVH template
  (`godot/modules/godot_physics_2d/godot_broad_phase_2d_bvh.h:35`, `godot_broad_phase_3d_bvh.h:35`).
- Licences (`godot/COPYRIGHT.txt`):
  - Expat for all of Godot (`:42-46`);
  - Bullet code under Expat and Zlib: `gjk_epa.cpp`, the pin, hinge, slider and 6DOF joints and their Jacobian
    entry, `godot_shape_3d.cpp` and `.h`, and the soft body (`:79-97`); the cone-twist joint (`:104-110`);
    `godot/core/math/convex_hull.*` (`:48-54`);
  - ODE's BSD-3-Clause for the SAT solver (`:99-102`), whose in-file notice offers LGPL-2.1+ or BSD
    (`godot_collision_solver_3d_sat.cpp:50-61`);
  - qu3e under Zlib for the BVH's balancing, noticed only in the file (`godot/core/math/bvh_logic.inc:43-61`,
    included at `godot/core/math/bvh_tree.h:446`). Zlib requires altered versions to be plainly marked
    (`godot/core/math/bvh_logic.inc:58-59`).
- Files over 900 lines: `godot_collision_solver_3d_sat.cpp` 2,358, `godot_shape_3d.cpp` 2,254,
  `godot_physics_server_3d.cpp` 1,835, `godot_space_3d.cpp` 1,265, `gjk_epa.cpp` 1,025, `godot_body_pair_3d.cpp` 990,
  and `convex_hull.cpp`, `basis.cpp` and `geometry_3d.cpp` in `godot/core/math`. `GodotSpace3D::test_body_motion`
  alone is 380 lines (`godot_space_3d.cpp:637-1016`). HotSpot does not JIT-compile a method over 8,000 bytecode
  bytes by default (knowledge).
- Constraint setup and island solve run on Godot's worker pool, and pre-solve runs serially
  (`godot_step_3d.cpp:347-348`, `:358-361`, `:367-368`).
- 3D computes a body's new rotation, with its sine and cosine, only when its angular velocity is not near zero
  (`godot_body_3d.cpp:716-718`); 2D does so for every active body
  (`godot/modules/godot_physics_2d/godot_body_2d.cpp:637-646`).
- Godot 3D is Y up in metres: the default gravity is 9.8 along (0, -1, 0)
  (`godot/servers/physics_3d/physics_server_3d.cpp:554-555`).
- Godot's 3D math tests are in `godot/tests/core/math` (`test_basis.cpp`, `test_quaternion.cpp` and others).
- Precedent: US-046's faithful port matched goldens captured from the C, with 96.36 % of the C's lines covered
  (`backlog/US-046-port-the-pisces-software-compositor-to-java-and-delete-prism-sw.md:79`).

## Constraints
- A faithful port: Godot's structure, numerics and every quirk are kept, and a fix goes to its own story. The
  server, Variant, RID and Callable layers are replaced by the 3D contract (US-085), not ported.
- Godot's 3D conventions (Y up, metres, Godot's defaults) are kept, so the goldens apply unchanged.
- Depends on `java.base` only: no javafx, no FFM, no native code.
- Each ported file keeps its upstream notices, is marked as altered, and has its upstream path and commit in a
  provenance map that a test checks. The notices ship through US-075.
- Production Java files are at most 900 lines by `wc -l` (tests and shims are exempt), reached without putting
  several statements on a line or going past 120 columns. No method has 8,000 bytecode bytes or more.
- Java 27 final features only: no preview, no incubator such as the Vector API, no `var`. Comments only for a
  non-obvious "why". GPLv2+CPE headers.
- Agents never run `USE_ROBOT` or `tests/system` runs; nothing here needs them.

## Out of scope
- Soft bodies, and custom shapes, which GodotPhysics3D cannot create (`godot_physics_server_3d.cpp:101-103`).
- The 3D character controller and other scene-layer nodes (US-087).
- Snapshot and restore (US-093; criterion 9 covers the case where it lands first), and stepping off the FX thread
  (US-093); re-pinning to a later Godot (US-094); behaviour
  past Godot's (US-095).
- Parity with stock Godot builds: US-076 reports it, and nothing gates on it.

## Acceptance criteria
1. **Scope.** A pure-Java 3D engine ported from `godot_physics_3d` and the core code it needs: bodies in all four
   modes, every shape kind except soft-body and custom shapes, areas, the five joint kinds, the queries and the
   motion test. Godot's 3D math tests are ported as Java tests of the math layer.
2. **Bit-exact.** Every step equals the US-076 goldens bit for bit over a corpus that covers: every shape pair the
   solver handles, in both orders, touching, on an edge, parallel, deep and degenerate; stacks; sleep and wake;
   CCD; kinematic bodies; motion-test sequences up to 32 collisions; areas, equal priorities included; the five
   joint kinds with their limits, motors and springs; mass, axis-lock, layer, mask and exception settings; objects
   removed mid-simulation; hostile inputs such as NaN and huge coordinates; and seeded operation fuzzing. The
   errors Godot reports for invalid calls are reproduced as recorded outcomes. The corpus's line coverage of the
   ported C++ is measured and recorded.
3. **Tolerance only if agreed first.** If US-076 shows that exact 3D capture is impossible, a tolerance whose bounds
   were agreed and recorded before any comparison replaces exactness where it must, and this story records where.
4. **Deterministic.** The same call history gives identical outputs on the five CI targets, with 1, 2 and 8 worker
   threads, and with any pooling the engine has switched on and off.
5. **No allocation.** No bytes are allocated per step in steady state, summed over every thread that steps.
6. **Speed.** The Java/C++ ratio of the 3D step is measured as US-077 measures 2D: same scenario and host, one
   worker, steady state after warm-up. It is measured when the 3D narrowphase is complete and when the full step
   runs, and the full-step figure binds. At most 2x passes. Between 2x and 3x, one adjustment round within the
   faithful-port rules is followed by a re-measurement. Above 3x after that round, the port route stops and the
   route to 3D physics is chosen again; 3D physics stays a goal. The figures are recorded in this story.
7. **Conformance.** The engine passes the 3D conformance kit of US-085 in exact mode, and the 3D contract leaves
   draft.
8. **Provenance.** The provenance test passes, and the notices of every ported file ship through US-075.
9. **Snapshots, if US-093 has landed.** The engine passes US-093's snapshot and restore checks before this story is
   done.

## Definition of Done
- All acceptance criteria met, with the evidence (logs, counts, goldens, tables) kept with the change.
- An independent review by `godot-physics-port-reviewer` signed off.
- `game-tester` re-ran the golden, determinism and allocation gates independently of the porter.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without explicit approval from
  the repository owner.**

## Notes
- The owner, 2026-11-07: "I do want to have a 3D engine, even if we build it ourselves over time."
- Related: US-079 (the 2D port), US-087, US-093 (snapshots), US-094 (re-pinning), US-095 (growth past Godot).
