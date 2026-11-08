# US-094 — Re-pin the built-in physics engine to a new Godot release

**Status:** 📋 Ready (filed 2026-11-07 from the game-toolkit design review) · **Epic:** Game toolkit ·
**Blocked by:** US-079 (2D part), US-086 (3D part) · **Blocks:** none

## Story
As the maintainer of the built-in physics engine,
I want to move its Godot pin to a newer stable release through a documented procedure,
so that the engine keeps up with Godot's fixes and every change in its results is traced to an upstream commit.

## Problem
Fork paths are relative to the repository root. `godot/` is Godot Engine at commit `c51ff06e88` (4.8-beta, MIT),
cloned next to this repository.
- Godot keeps changing the code the port takes. From `4.7-stable` (`5b4e0cb0fd`, 2026-06-17, where the 4.7 branch
  leaves master) to `c51ff06e88`, 18 non-merge commits, 25 with merges, touched `godot/modules/godot_physics_2d` or
  `godot/modules/godot_physics_3d` (`git log 4.7-stable..c51ff06e88`). They mix:
  - behaviour fixes, such as `8322a6ae40` (the 2D velocity at a local position ignored a custom centre of mass) and
    `413944b537` (a wrong joint-type check in `slider_joint_get_param`);
  - refactors meant to change nothing, such as `89989ef32f` and `c709e0d2c4` (SAT flags passed at run time instead
    of as template parameters) and `099c7c9492` (server enums and structs moved to their own files);
  - style-only commits, such as `a35932457b`, `1c45c14d7f` and `eb1b8e8eda`.
- A commit's message does not tell whether it moves results. `58ef14369d` ("Use float literals and real_t
  consistently around square values") looks numeric. In a single-precision build, where `real_t` is `float`
  (`godot/core/math/math_defs.h:146`), its body-pair hunk changes no type, and its character-body hunks change a
  result only where a length is exactly `0.01f` (inferred from the diff).
- The port also takes code from outside the physics modules, such as the core math and the character bodies;
  `58ef14369d` touches `godot/scene/2d/physics/character_body_2d.cpp` and
  `godot/scene/3d/physics/character_body_3d.cpp`.
- US-062 attributed every golden step its fixes moved to one of its two fixes, by capturing with each fix alone
  (`.claude/skills/jfx-ffm-testing/SKILL.md:318-320`).
- Godot sets Jolt as the 3D engine of new projects (`godot/editor/editor_node.cpp:8521`), while GodotPhysics3D stays
  the default 3D server (`godot/modules/godot_physics_3d/register_types.cpp:57-58`).

## Constraints
- The new pin is a tagged Godot stable release. Its goldens are captured with the harness of US-076, and the owner
  approves each one before it is committed.
- The engine stays bit-exact against the goldens on the five CI targets, for every worker count and pooling setting
  that US-079 and US-086 test.
- Re-ported code stays a faithful port. A fix to Godot behaviour goes to its own story and to Godot first.
- Notices of any new upstream code ship through US-075.
- Production files are at most 900 lines (`wc -l`); Java 27 final features only (no preview, no incubator modules,
  no `var`); minimal comments; GPLv2+CPE headers plus each ported file's upstream notice.
- Agents never run `USE_ROBOT` or `tests/system` runs; this story needs none.

## Out of scope
- Tracking Godot's master branch or its betas between stable releases.
- The built-in 3D engine after the divergence point of US-095, where Godot parity stops being its gate.

## Acceptance criteria
1. **Re-pin.** The built-in engine moves from its current pin to a newer Godot stable release: the 2D engine once
   US-079 is done, the 3D engine once US-086 is done.
2. **Classified by effect.** Every upstream commit between the two pins that touches a file named in the provenance
   map is listed and classified by how it moves the goldens, not by its message: behaviour change, refactor that
   moves nothing, or style only.
3. **Attributed.** Every golden step that moves between the two pins is attributed to one upstream commit.
4. **Re-ported.** Every behaviour-changing commit is re-ported, and the engine matches the new goldens exactly.
5. **Provenance.** The provenance map names the new pin for every ported file, and its test passes. The commits
   that move nothing are recorded as such.
6. **Procedure.** The re-pin procedure is documented in `.claude/skills/jfx-game-physics-port/SKILL.md`, this re-pin
   followed it, and any step it lacked has been added.
7. **Stands alone.** The goldens and the provenance map stand on their own: if Godot removes GodotPhysics3D, the 3D
   engine can still be tested against its goldens, and each ported file still names the upstream source it came
   from.

## Definition of Done
- All acceptance criteria met, with the evidence (logs, counts, goldens, tables) kept with the change.
- An independent review by `godot-physics-port-reviewer` signed off.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without explicit approval from
  the repository owner.**

## Notes
- Related: US-076 (the harness), US-079 and US-086 (the engines it re-pins), US-095 (the 3D divergence point).
