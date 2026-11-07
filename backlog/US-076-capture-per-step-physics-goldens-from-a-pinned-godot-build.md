# US-076 — Capture per-step physics goldens from a pinned Godot build

**Status:** 📋 Ready (filed 2026-11-07 from the game-toolkit design review) · **Epic:** Game toolkit ·
**Blocked by:** none · **Blocks:** US-077, US-079, US-081, US-086, US-087

## Story
As the maintainer of the built-in physics engine,
I want the state of any physics scenario captured step by step from a pinned, recorded Godot build,
so that the Java port can be proven equal to Godot, step by step and bit for bit.

## Problem
Fork paths are relative to the repository root. `godot/` is Godot Engine at commit `c51ff06e88` (4.8-beta, MIT),
cloned next to this repository.
- Nothing in Godot records what its physics does step by step: no test under `godot/tests` calls `space_create` or
  `body_test_motion` (grep finds none). Godot's test host does create the default 2D and 3D physics servers and a
  SceneTree for `[SceneTree]` test cases (`godot/tests/test_main.cpp:182-238`).
- Each physics tick of Godot's main loop runs `sync` and `flush_queries`, then the scene's physics process, then
  `end_sync` and `step` (`godot/main/main.cpp:5013-5085`).
- Stock builds can give different results on different platforms (inferred). `Math::sin`, `Math::cos` and
  `Math::atan2` call the C library in both their `double` and `float` overloads (`godot/core/math/math_funcs.h:41-52`,
  `:120-125`), and the 2D engine takes an `atan2` and a `sin`/`cos` pair for each rigid body it integrates, on every
  step (`godot/modules/godot_physics_2d/godot_body_2d.cpp:637-646`, through `godot/core/math/transform_2d.cpp:82-83`
  and `:97-99`). Floating-point contraction is off on the three desktop platforms (`-ffp-contract=off` or
  `/fp:strict`: `godot/platform/macos/detect.py:118`, `godot/platform/linuxbsd/detect.py:213`,
  `godot/platform/windows/detect.py:387`, `:722`).
- Java's `StrictMath` gives the results of the fdlibm algorithms on every platform (knowledge), so a Java port can be
  exact only against a capture build whose transcendental functions return the same bits.
- The server API exposes five values of a body's state: its transform, its two velocities, sleeping and can-sleep
  (`godot/servers/physics_2d/physics_server_2d_enums.h:115-121`). Internal state is not among them: the accumulated
  contact impulses (`godot/modules/godot_physics_2d/godot_body_pair_2d.h:61-65`) and the sleep timer
  (`godot/modules/godot_physics_2d/godot_body_2d.h:88`), which carry into the next step, and the biased velocities
  (`:48-49`), which each step clears at its start (`godot/modules/godot_physics_2d/godot_body_2d.cpp:604-605`).
- The fork's parity precedent captures goldens from the code to be replaced, records the commit and platform, and
  compares exactly by default or against a tolerance agreed in advance
  (`.claude/skills/jfx-ffm-testing/SKILL.md:268-285`; US-046, US-062).
- Building Godot needs SCons 4.4 or later and Python 3.10 or later (`godot/SConstruct:4-5`). On 2026-11-07 the
  owner's Mac had no SCons and Python 3.9.6.
  The clone is a 4.8 beta (`godot/version.py:3-6`), and its newest 4.x release tag is `4.7.2-stable`.

## Constraints
- The capture harness lives outside the Maven reactor (`pom.xml:37-52`).
- Patches to Godot are minimal and change no behaviour, except the routing of the transcendental functions and
  read-only accessors of internal state.
- Godot is pinned at a recorded commit. A golden that gates a port comes from a tagged Godot release or release
  candidate.
- Nothing writes into the owner's Godot clone.
- Agents install no software; the owner installs SCons and a new enough Python.
- The owner approves each golden before it is committed.
- Agents never run `USE_ROBOT` or `tests/system` runs; this story needs none.

## Out of scope
- Porting any engine code, and the Java side of the comparison (US-079, US-086).
- Soft bodies (a README non-goal) and Godot's Jolt module.

## Acceptance criteria
1. **Scenarios.** Any scenario written in a language-neutral scenario file, with every float given as its bit
   pattern, can be captured in two modes: server mode, which drives the 2D or the 3D physics server directly with one
   query flush per step, and scene mode, which moves a 2D or 3D character with move-and-slide. At least one scenario
   of each mode and dimension is captured.
2. **What a step records.** For each step: every body's transform, linear and angular velocity, biased velocities,
   sleep state and sleep timer; contacts with their accumulated impulses; area enter and exit events in the order Godot
   reports them; query and motion-test results; and the error outcome of every invalid call. A long run may store a
   hash chain plus checkpoints instead of every step.
3. **Header.** Every golden records the Godot commit or tag, the build options, the compiler and its flags, the host
   platform, and the hashes of every patch and of every source file used.
4. **Transcendental functions.** In the capture build, every C library transcendental function that the captured
   code reaches, through `godot/core/math/math_funcs.h` or directly (as `godot/core/math/basis.cpp:479-559` does),
   returns in its `double` and `float` forms the bits of Java's `StrictMath` method of the same name (rounded to
   `float` for the `float` form). This is shown on at least 1,000,000 inputs per function and form.
5. **Repeatable.** Capturing a scenario twice gives byte-identical goldens.
6. **Second host.** A recapture on a host of another OS or architecture is equal, or every difference is explained.
7. **Negative control.** A deliberately changed golden fails the comparison.
8. **C++ step time.** The time of the C++ step is recorded per scenario on the capture host, with the number of
   worker threads stated.
9. **Stock Godot.** The scenarios also run on an unpatched build of the same commit, and the difference from the
   goldens is reported against a tolerance. It does not gate anything.
10. **Coverage.** The line coverage, by the scenario corpus, of the Godot C++ that the ports take is measured and
    recorded.

## Definition of Done
- All acceptance criteria met, with the evidence (logs, counts, goldens, tables) kept with the change.
- An independent review by `godot-physics-port-reviewer` signed off.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without explicit approval from
  the repository owner.**

## Notes
- Related: US-077 uses the C++ step times; US-079, US-081, US-086 and US-087 are gated by these goldens; US-094
  re-pins with the same harness.
