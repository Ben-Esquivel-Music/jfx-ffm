# US-083 — Prove a native 2D physics engine plugs in through FFM

**Status:** 📋 Ready (filed 2026-11-07 from the game-toolkit design review) · **Epic:** Game toolkit ·
**Blocked by:** US-080 (the 2D contract and its conformance kit) · **Blocks:** none

## Story
As a game developer who wants a native physics engine,
I want proof that a native 2D engine bound through FFM can stand in for the built-in one,
so that I know the 2D contract does not depend on Java engines and can see what changes when I switch.

## Problem
Paths are relative to the repository root.
- **Native access stays where it is.** No module may widen `--enable-native-access` beyond `javafx.graphics`,
  `javafx.media` and `javafx.web`. The game toolkit's modules get none; a native physics engine's own module asks the
  application for it, and a test-only native engine gets it in test scope only, never in the SDK
  (`.claude/skills/openjfx-conventions/SKILL.md:157-164`).
- **The fork's library loader is not open to it.** `NativeLibLoader` is in `com.sun.glass.utils`
  (`modules/javafx.graphics/src/main/java/com/sun/glass/utils/NativeLibLoader.java`), which javafx.graphics exports
  only to javafx.media and javafx.web (`modules/javafx.graphics/src/main/java/module-info.java:72-74`).
- **The SDK takes every native library it finds** in the native output directories of javafx.graphics and
  javafx.media (`sdk/pom.xml:203-208`).
- **House FFM rules.** A native library exports a flat C ABI (`.claude/skills/jni-to-ffm-migration/SKILL.md:150-151`);
  C returns an error code and Java throws (`:276`); every allocation has an arena whose lifetime matches the native
  object (`:181-184`); restricted methods stay in the `*Native` class (`:185`); and an exception that escapes an upcall
  terminates the JVM (`:348`).
- **CI** builds the five desktop targets as five jobs (`.github/workflows/submit.yml:68, 211, 354, 419, 484`).

## Constraints
- The engine is under a GPLv2-compatible licence, and its licence text and notices stay with its sources.
- The binding follows the house FFM rules above. Its Java is at most 900 lines per production file (`wc -l`), uses
  Java 27 final features only (no preview, no incubator, no `var`), carries GPLv2+CPE headers and comments only a
  non-obvious "why".
- Agents never run `USE_ROBOT` or `tests/system`; the owner runs robot and visual tests. This story needs none.

## Out of scope
- A native 3D engine (US-085); shipping a native engine with a game (US-084).
- Recommending a native engine for production use.

## Acceptance criteria
1. **It passes the kit.** A native 2D physics engine, bound through FFM, passes US-080's conformance kit for every
   capability it declares, the kit's hostile-input scenarios included.
2. **Difference table.** The kit's difference table for the engine is published with this story.
3. **Never shipped.** Neither the engine nor its binding is in the SDK or in any jar the fork ships. The SDK's list of
   native libraries is the same before and after.
4. **No new native access.** The fork's shipped modules gain no native access. Any grant is in test scope only, as
   the conventions name it.
5. **No calls into Java from engine threads.** No thread the engine creates calls into Java, and no exception, C++ or
   Java, crosses the FFM boundary.
6. **CI.** The engine builds and passes the kit on the CI targets it supports. This story records which, and why any
   target is left out.
7. **Documented risk.** The contract's documentation states that a native engine can crash the JVM.
8. **Out of draft.** The 2D-only parts of the contract leave draft (US-080, criterion 9).

## Verification
After `mvn install` from the repository root, before and after the change (the two lists are identical):
```
find sdk/target/sdk -type f \( -name '*.dll' -o -name '*.so' -o -name '*.dylib' \) | sort
```

## Definition of Done
- All acceptance criteria met, with the evidence (kit runs, the difference table, CI results per target, the SDK
  lists) kept with the change.
- An independent review by `game-toolkit-reviewer` and, for the binding, `ffm-reviewer` signed off.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without explicit approval from
  the repository owner.**

## Notes
- The owner, 2026-11-07: "bring your own engine" means physics engines only; the renderer stays JavaFX.
- Types the 2D and 3D contracts share stay draft until a 3D engine passes too (US-085).
- Related: US-084 (a native engine's library and native-access grant reach a game's launcher).
