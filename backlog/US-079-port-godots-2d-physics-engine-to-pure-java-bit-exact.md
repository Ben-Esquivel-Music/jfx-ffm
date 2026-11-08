# US-079 — Port Godot's 2D physics engine to pure Java, bit-exact

**Status:** 📋 Ready (filed 2026-11-07 from the game-toolkit design review) · **Epic:** Game toolkit ·
**Blocked by:** US-075 (the ported code's notices must ship), US-076 (the goldens), US-077 (the speed gate) ·
**Blocks:** US-080, US-081, US-086, US-093, US-094

## Story
As a JavaFX game developer who wants a proven 2D physics engine with no native code,
I want Godot's 2D physics engine ported faithfully to pure Java and proven equal to Godot, step by step,
so that my game gets Godot's 2D behaviour and the same results on every desktop target.

## Problem
Paths are relative to the repository root unless they start with `godot/`. `godot/` is Godot Engine at commit
`c51ff06e88` (4.8-beta, MIT), cloned next to this repository.
- **Size.** `godot/modules/godot_physics_2d` is 11,246 lines of `.cpp` and `.h` (`wc -l`). It also needs core code,
  about 7k lines (inferred), of which the BVH broadphase is 3,727 lines in the 13 files `godot/core/math/bvh*`. Four
  files are over 900 lines: `godot_physics_server_2d.cpp` 1,398, `godot_space_2d.cpp` 1,232,
  `godot_collision_solver_2d_sat.cpp` 1,172 and `godot_shape_2d.cpp` 985. `GodotSpace2D::test_body_motion` alone is
  475 lines (`godot/modules/godot_physics_2d/godot_space_2d.cpp:527-1001`), and HotSpot does not JIT-compile a
  method of more than 8,000 bytecode bytes (knowledge: `-XX:HugeMethodLimit`).
- **It is Godot's 2D engine.** It registers itself as the default 2D server
  (`godot/modules/godot_physics_2d/register_types.cpp:57-58`), and no other module registers one (grep of
  `godot/modules/*/register_types.cpp`). It has eight shape kinds and refuses custom shapes
  (`godot/servers/physics_2d/physics_server_2d_enums.h:37-47`; `godot_physics_server_2d.cpp:70-71`), and three
  joint kinds (`physics_server_2d_enums.h:131-136`).
- **Licences.** Godot's code is Expat (`godot/COPYRIGHT.txt:42-46`). The 2D joints are Chipmunk2D code, also Expat
  (`COPYRIGHT.txt:74-77`; the notice is in `godot/modules/godot_physics_2d/godot_joints_2d.cpp:37-56`).
  `godot/core/math/bvh_logic.inc:43-61` is qu3e code under Zlib, which requires altered sources to be plainly
  marked; `COPYRIGHT.txt` does not list it.
- **Order decides the bits.** `HashMap` iterates in insertion order (`godot/core/templates/hash_map.h:55`),
  `HashSet` moves its last key into an erased slot (`hash_set.h:290-297`), `SelfList::add` prepends
  (`self_list.h:55-70`), and `SortArray` is an introsort (`sort_array.h:202`), which is not stable (knowledge).
  `MIN`, `MAX` and `CLAMP` are plain comparisons (`godot/core/typedefs.h:159-172`) and `Math::abs` is Godot's own
  (`godot/core/math/math_funcs.h:220-225`); Java's `Math.min`, `max` and `abs` differ from them for NaN or -0.0
  (knowledge).
- **Threads.** Constraint setup and island solve run on Godot's worker pool
  (`godot/modules/godot_physics_2d/godot_step_2d.cpp:245-246, 265-266`).
- **Tests.** No Godot test steps physics (a grep of `godot/tests` for `space_create` and `body_test_motion` finds
  nothing). Godot's math types have unit tests in `godot/tests/core/math/test_*.cpp`. The fork's precedent for an
  exact port is US-046, whose corpus covered 96.36 % of the C's lines
  (`backlog/US-046-port-the-pisces-software-compositor-to-java-and-delete-prism-sw.md:79`).

## Constraints
- The engine depends on `java.base` only: no `javafx.*`, no FFM, no native code. It lives in the game toolkit's own
  modules, whose names are clearly the fork's own (not `javafx.*`, not `jfx.incubator.*`).
- Every production file is at most 900 lines (`wc -l`); tests are exempt, and the limit is never met by cramming
  statements onto a line. No method is 8,000 bytecode bytes or more.
- Java 27 final features only: no preview, no incubator (such as the Vector API), no `var`. Comments only for a
  non-obvious "why".
- Each ported file carries the GPLv2+CPE header plus the ported-file block of
  `.claude/skills/openjfx-conventions/SKILL.md:86-89`: its upstream notices kept and an altered mark. A provenance
  map records each ported file's upstream path and commit. The notices ship through US-075.
- Agents never run `USE_ROBOT` or `tests/system`; the owner runs robot and visual tests. This story needs none.

## Out of scope
- The engine contract and its conformance kit (US-080); the character controller, polygon decomposition and tile
  merging (US-081); 3D (US-086); stepping off the FX thread and snapshots (US-093); a newer Godot (US-094).
- Fixing a Godot quirk. Each fix is its own story and goes to Godot first.
- Bit-for-bit parity with stock Godot builds (README, "Non Goals").

## Acceptance criteria
1. **Scope.** Everything `godot/modules/godot_physics_2d` does is ported, with the core code it needs: every shape
   kind, joint kind and body mode, areas, CCD, sleep, queries and the motion test. Godot's server, Variant, RID and
   Callable layers are replaced, not ported. The US-076 scenario files run against the Java engine with no Godot
   and no native code.
2. **Faithful.** Every Godot behaviour is kept, quirks included. Each error Godot reports for an invalid call is
   reproduced as the outcome the golden records.
3. **Bit-exact.** The engine's output equals the US-076 goldens bit for bit over a corpus that covers:
   - every shape pair in both orders, touching, on an edge, parallel, deep and degenerate;
   - stacks, sleep and wake, CCD, kinematic bodies and motion-test sequences;
   - areas, equal priorities included, and the three joint kinds with every parameter and flag;
   - mass, body-mode, one-way, layer, mask and collision-exception settings;
   - objects removed mid-simulation, hostile inputs (NaN, huge coordinates) and seeded operation fuzzing.

   The line coverage of the ported C++ by the corpus is measured and recorded here. If US-076 shows exact capture
   impossible, the comparison uses a tolerance whose bounds were agreed and recorded before the first comparison.
4. **Same everywhere.** The same call history gives bit-identical output (poses, velocities, events, query and
   motion-test results, error outcomes) on the five CI targets (`.github/workflows/submit.yml:68, 211, 354, 419,
   484`), with 1, 2 and 8 worker threads, and with any object pooling on and off.
5. **No allocation.** In steady state a step allocates zero bytes, summed over every thread that takes part in it.
6. **Speed.** The Java/C++ step-time ratio is re-measured as US-077 defines it when the 2D narrowphase is complete
   and again when the full 2D step runs. Both figures are recorded here, and the full-step figure binds. The
   thresholds, the one adjustment round and the outcome of a failure are US-077's: a failure stops the port route
   and the 3D route is chosen again. 3D is never dropped.
7. **Math tests.** Godot's unit tests for the math types the port uses are ported as Java tests and pass.
8. **Checks.** Tests fail on a production file over 900 lines, a method of 8,000 bytecode bytes or more, a
   dependency beyond `java.base`, and a ported file missing from the provenance map or the map naming a file that
   does not exist.

## Definition of Done
- All acceptance criteria met, with the evidence (coverage, ratios, allocation counts, golden comparisons per target
  and worker count) kept with the change.
- An independent review by `godot-physics-port-reviewer` signed off.
- `game-tester` re-ran the golden, determinism and allocation gates independently of the porter.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without explicit approval from
  the repository owner.**

## Notes
- The owner, 2026-11-07: "I do want to have a 3D engine, even if we build it ourselves over time." A failed speed
  gate changes how 3D is reached, never whether.
- US-086 starts only once US-077's gate has passed and this story matches exactly.
