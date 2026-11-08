# US-095 — Grow the built-in 3D engine past its Godot origin

**Status:** 📋 Ready (filed 2026-11-07 from the game-toolkit design review) · **Epic:** Game toolkit ·
**Blocked by:** US-085 (the native 3D engine to measure against), US-086 (the built-in 3D engine at Godot parity) ·
**Blocks:** none

## Story
As the maintainer of the built-in 3D physics engine,
I want to improve it past GodotPhysics3D once it has matched Godot,
so that the fork's 3D physics keeps getting better while Godot puts its 3D effort into Jolt.

## Problem
`godot/` is Godot Engine at commit `c51ff06e88` (4.8-beta, MIT), cloned next to this repository.
- Godot's editor sets Jolt as the engine of new 3D projects (`godot/editor/editor_node.cpp:8521`).
- GodotPhysics3D's continuous collision detection slows a fast body so that it just overlaps on the next step. It
  computes no time of impact, and its own comment warns that a bounce then has less momentum than it should
  (`godot/modules/godot_physics_3d/godot_body_pair_3d.cpp:164-171`).
- Since 2026-01-01, 28 commits have touched `godot/modules/godot_physics_3d`, 23 of them without merges
  (`git log --since=2026-01-01 -- modules/godot_physics_3d` in `godot/`). By their subjects, most are style,
  include or code-move changes (inferred).
- Until a divergence point, US-086 binds the built-in engine to Godot bit for bit: every quirk is kept and fixes go
  to their own stories. US-094 re-pins it to new Godot releases.

## Constraints
- Every gate of US-086 other than parity with Godot keeps holding: the same call history gives identical outputs on
  the five CI targets with 1, 2 and 8 worker threads; nothing is allocated per step in steady state; the engine
  depends on `java.base` only; no method has 8,000 bytecode bytes or more; production Java files are at most 900
  lines by `wc -l` (tests and shims are exempt).
- No change removes a capability the engine declares.
- Code taken from another engine comes under a GPLv2-compatible licence, keeps its notice, is recorded in the
  provenance map, and its notices ship through US-075.
- The goldens, the divergence record and the provenance map stand on their own, without the Godot clone and
  without GodotPhysics3D upstream.
- Java 27 final features only: no preview, no incubator such as the Vector API, no `var`. Comments only for a
  non-obvious "why". GPLv2+CPE headers.
- Agents never run `USE_ROBOT` or `tests/system` runs; nothing here needs them.

## Out of scope
- Soft bodies, a Java port of Jolt, and parity with stock Godot or with any other engine.
- The 2D engine, which stays on Godot parity (US-094).

## Acceptance criteria
1. **Divergence point.** The Godot commit and the golden set at which the 3D engine stops tracking Godot are
   recorded in the repository. From then on, parity with Godot no longer gates the 3D engine, and the recorded
   goldens are its regression gate.
2. **Quality scenarios.** 3D scenarios measure stack stability, penetration depth, jitter, time to sleep and
   tunnelling, each as a number per scenario. They run with one command against any 3D engine. Their first table,
   for the built-in engine and the native engine of US-085, is kept with this story.
3. **Each improvement closes a measured gap.** An improvement names the scenario and the number it improves, where
   the built-in engine does worse than the native engine or than a target recorded beforehand. It records the
   before and after numbers of every scenario it changes.
4. **Goldens move only as recorded behaviour changes.** Each moved golden step is attributed to the improvement
   that moved it, and the owner approves each moved golden.
5. **Gates after each improvement.** The checks behind the first constraint pass, and the engine passes the 3D
   conformance kit of US-085 in exact mode against the recorded goldens. The step time on US-086's speed scenarios is
   re-measured and recorded.
6. **Upstream after the divergence point.** Each later change to GodotPhysics3D upstream is either taken as a
   recorded behaviour change or recorded as not taken, with the reason. US-094's re-pin no longer applies to the 3D
   engine.
7. **First improvement.** At least one improvement lands under criteria 3 to 5.

## Definition of Done
- All acceptance criteria met, with the evidence (logs, counts, goldens, tables) kept with the change.
- An independent review by `godot-physics-port-reviewer` signed off.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without explicit approval from
  the repository owner.**

## Notes
- The owner, 2026-11-07: "I do want to have a 3D engine, even if we build it ourselves over time." The README's
  long-term goals include owning and growing the built-in 3D engine beyond its Godot origin.
- Related: US-085 (the native 3D engine and the conformance kit), US-086 (the port this story grows), US-094
  (re-pinning, which keeps covering the 2D engine).
