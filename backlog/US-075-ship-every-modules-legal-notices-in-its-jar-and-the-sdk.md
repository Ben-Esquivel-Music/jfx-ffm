# US-075 — Ship every module's legal notices in its jar and the SDK

**Status:** 🚧 Implemented, not merged (2026-11-13, uncommitted on the local branch
`US-075-ship-every-modules-legal-notices-in-its-jar-and-the-sdk`; acceptance criteria 1-6 are met, the final Windows
and WSL builds passed and `game-toolkit-reviewer` signed off; AC 7, the five `submit.yml` jobs, waits on the owner's
push, after which the story becomes Done; filed 2026-11-07 from the game-toolkit design review) · **Epic:** Game
toolkit · **Blocked by:** none · **Blocks:** US-079, US-081, US-084, US-086

## Story
As anyone who redistributes the SDK or an application built on the fork's jars,
I want every jar and the SDK to carry the legal notices of the code inside them,
so that redistributing them meets the licences of that code.

## Problem
Fork paths are relative to the repository root. `godot/` is Godot Engine at commit `c51ff06e88` (4.8-beta, MIT),
cloned next to this repository.
- Four modules carry 13 third-party notices, and none of them reaches a jar or the SDK:
  - `modules/javafx.graphics/src/main/legal/`: `gcc.md`, `jpeg_fx.md`, `mesa3d.md`, `pipewire.md`;
  - `modules/javafx.media/src/main/legal/`: `directshow.md`, `glib.md`, `gstreamer.md`, `libffi.md`;
  - `modules/javafx.web/src/main/legal/`: `icu_web.md`, `libxml2.md`, `libxslt.md`, `webkit.md`;
  - `modules/jfx.incubator.richtext/src/main/legal/`: `unicode-fx.md`.
- None of the 15 `pom.xml` files in the tree mentions `legal`. The SDK holds the module jars and `javafx.properties`
  under `lib/` plus the graphics and media native libraries (`sdk/pom.xml:107-113`, `:203-212`), and the README says
  that legal notices are not produced (`README.md:77-80`). The root `LICENSE`, `ADDITIONAL_LICENSE_INFO` and
  `ASSEMBLY_EXCEPTION` are not in the SDK either. The only place the fork stages licence files, these three among
  them, is the artifact of the prebuilt WebKit library (`.github/workflows/build-webkit.yml:556-575`).
- The licences require the notices in binaries. Expat requires its notice "in all copies or substantial portions of
  the Software" (`godot/COPYRIGHT.txt:1414-1415`). BSD-3-Clause clause 2 requires a binary redistribution to
  reproduce the notice "in the documentation and/or other materials provided with the distribution"
  (`godot/COPYRIGHT.txt:933-935`).
- The game toolkit's ports of Godot (US-079, US-081, US-086) add notices of their own: Godot's Expat
  (`godot/COPYRIGHT.txt:42-46`); Chipmunk2D's Expat for the 2D joints (`:74-77`); Expat and Zlib for the
  Bullet-derived convex hull, GJK/EPA, 3D shapes and joints (`:48-54`, `:79-97`, `:104-110`); ODE's BSD-3-Clause for
  the 3D SAT solver (`:99-102`); and two Zlib notices that appear only inside their files, not in `COPYRIGHT.txt`:
  Bullet's in `godot/core/math/dynamic_bvh.h:42-53` and qu3e's in `godot/core/math/bvh_logic.inc:44-60`.

## Constraints
- Every notice ships byte for byte as it is in `src/main/legal/`.
- Apart from the added notices, the entries of every jar and their content are unchanged.
- Both SDK builds carry the notices: the one with native libraries and the Java-only one of `-DskipNative=true`
  (`sdk/pom.xml:229`, `:280`).
- The five jobs of `.github/workflows/submit.yml` (`:68`, `:211`, `:354`, `:419`, `:484`) stay green.
- Agents never run `USE_ROBOT` or `tests/system` runs; this story needs none.

## Out of scope
- jmods, `src.zip`, javadoc and distribution archives (`README.md:79-80`), and the legal directory of a runtime image
  (US-084).
- Writing the notices of ported code: each porting story adds its notices to its module's `src/main/legal/`.
- The artifact of the prebuilt WebKit library.

## Acceptance criteria
1. **Jars.** Every module jar the build produces carries each file of that module's `src/main/legal/`, under its own
   file name, at one path that is the same in every jar and documented in `README.md`.
2. **SDK.** The SDK carries the notices of every module, with no notice hiding another of the same name, plus
   `LICENSE`, `ADDITIONAL_LICENSE_INFO` and `ASSEMBLY_EXCEPTION`.
3. **New modules.** A module that adds a `src/main/legal/` directory needs no other build change for its notices to
   ship in its jar and the SDK and to be checked by criterion 4.
4. **Check.** A step of the normal build, which CI therefore runs on every leg, fails when a file of any module's
   `src/main/legal/` is missing from that module's jar or from the SDK, or differs from its source. It is shown to
   fail on a planted missing notice in a jar and in the SDK, and to pass once the plant is removed.
5. **Unchanged content.** For every jar, the entries and their bytes before and after the change differ only by the
   added notices.
6. **README.** `README.md` says where the jars and the SDK carry the notices and no longer says that they are not
   produced.
7. **CI.** The five jobs of `.github/workflows/submit.yml` are green.

## Verification
From the repository root, in bash:
```
mvn -B -ntp verify -DskipNative=true -DskipTests
# Every notice is in its module's SDK jar exactly once (prints nothing)
for f in modules/*/src/main/legal/*; do
    m=${f#modules/}; m=${m%%/*}
    unzip -Z1 "sdk/target/sdk/lib/$m.jar" | awk -F/ -v b="${f##*/}" '$NF == b { n++ } END { exit (n != 1) }' \
        || echo "not exactly once in $m.jar: $f"
done
```

## Definition of Done
- All acceptance criteria met, with the evidence (logs, counts, goldens, tables) kept with the change.
- An independent review by `game-toolkit-reviewer` signed off.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without explicit approval from
  the repository owner.**

## Outcome
- Every module jar carries the files of its module's `src/main/legal/` under `META-INF/legal/`. The SDK carries the
  root `LICENSE`, `ADDITIONAL_LICENSE_INFO` and `ASSEMBLY_EXCEPTION` under `legal/` and each module's notices under
  `legal/<module>/`.
- `buildtools/CheckLegalNotices.java` checks every such jar and the SDK at verify and fails the build otherwise.
- [The evidence](US-075-evidence.md) has the runs, the plants, the [jar diff](US-075-jar-diff.tsv) and the
  [per-class test results](US-075-windows-per-class.tsv).
- `game-toolkit-reviewer` (agent definition `.claude/agents/game-toolkit-reviewer.agent.md`, created for this story)
  reviewed the change after three general-purpose stand-in reviews, first as a general-purpose agent following the
  definition and then as the registered agent type on the final tree after the final builds, and signed off both
  times. Its four findings, all about these records, are fixed, as are the three findings of the stand-ins (see
  "Review" in the evidence).
- AC 7, the five `submit.yml` jobs, is pending the owner's push; the macOS legs are covered only by CI.

## Notes
- Related: US-079, US-081 and US-086 add the notices of the code they port; US-084 puts the notices in a game's
  runtime image.
