# US-075 — Evidence

Host: Windows 10 Pro x64 (10.0.19045), Temurin JDK 27+35, Apache Maven 3.10.0; Linux leg in WSL 2 (Ubuntu,
kernel 6.18.58.1), Temurin 27+35 at `~/jdk27`, Maven 3.10.0 at `~/maven310`. Base commit `36123c04a8`; "after" is
that commit plus the US-075 working tree. The Windows builds whose outputs are compared (B, A, the final tree
before the review, R1 and W) passed `-Djfx.build.timestamp=2026-11-13-000000`, so `VersionInfo` and
`javafx.properties` are the same in all of them. All runs 2026-11-13, local time (UTC-5), the CI run of AC 7
included.

Run names: B = before (`36123c04a8`), A = after. "-java" is
`mvn -B -ntp clean verify -DskipNative=true -DskipTests` (the Java-only SDK of `sdk-java-only`). "-native" is the
CI command with verify, `mvn -B -ntp -fae clean verify -Dmaven.test.failure.ignore=true
-Djfx.web.skipFfmTests=false` (natives built from source, module tests, the SDK of `sdk-require-natives`). D1-D6
are the design decisions this change was built to; P1-P6 are the plants below.

After all the runs below, the profile comment in `pom.xml` was corrected (D1). The CI-equivalent build and the
WSL run were then repeated on that tree; see "Final tree before the review". The review then moved
`legal-notices-clean` from prepare-package to initialize and changed the check; every run above "Review" had it at
prepare-package, and R1-R4 under "Review" were run on the tree with the review fixes. W and L under "Final builds",
the CI-equivalent build with natives and tests on Windows and in WSL, were the last local builds, on the tree with
every review fix; only the plant reruns of AC 4, which build nothing, came after them. The owner then committed the
change as `c696e6d3f6` and pushed it, and CI built it (AC 7).

## What changed

| File | Change |
| --- | --- |
| `pom.xml` | Base build: antrun execution `legal-notices-clean` (initialize since the review; prepare-package before) deletes `${project.build.outputDirectory}/META-INF/legal` in every project. New profile `legal-notices`, activated by `<file><exists>${basedir}/src/main/legal</exists></file>`: `legal-notices-copy` (prepare-package) copies `src/main/legal/*` with a plain Ant copy into `META-INF/legal/` of the classes directory; `legal-notices-jar-check` (verify) runs `CheckLegalNotices.java jar` on the module jar. |
| `sdk/pom.xml` | Base build: `sdk-legal-notices` (package) deletes `legal/`, copies the root `LICENSE`, `ADDITIONAL_LICENSE_INFO`, `ASSEMBLY_EXCEPTION` and, through a `regexpmapper` over `modules/*/src/main/legal/*`, every notice to `legal/<module>/<file>`; `sdk-legal-notices-check` (verify) runs `CheckLegalNotices.java sdk`. Description updated. |
| `buildtools/CheckLegalNotices.java` | New source-launcher check, jar mode and SDK mode (below). |
| `README.md` | The SDK paragraph names `legal/`; a new paragraph says where jars and SDK carry the notices, that the build checks both, and that `src.zip`, jmods, javadoc and distribution archives are still not produced. |

No module pom, no workflow and no native file changed.

How the decisions were realised:

- **D1** Notices sit at `META-INF/legal/<file name>`; a jar carries only its own module's notices. The reason given
  for D1 ("the jars carry no ModulePackages attribute") is not right, but the choice is. All ten modular jars
  carry a `ModulePackages` attribute. The `module-info.class` in `target/classes` has none (2 attributes against
  3 in the jar), so it is added when the jar is packaged, and it is derived from the jar's entries: the Windows
  `javafx.graphics` jar lists the resource-only packages `com.sun.prism.d3d.hlsl` and
  `com.sun.scenario.effect.impl.hw.d3d.hlsl`. The JDK `jar` tool does the same. A scratch modular jar holding
  `legal/n.md` and `META-INF/legal/n.md` comes out with `ModulePackages` = `legal`, `p`, and
  `jar --describe-module` prints `contains legal`. So a top-level `legal/` would become a package of each of the
  four modules, a split package, while `META-INF/legal` never does. The comment of the `legal-notices` profile
  gives this reason.
- **D2** As amended in review (D2(a), finding 1 under "Review"): `legal-notices-clean` runs at initialize,
  `legal-notices-copy` at prepare-package and the jar check at verify. In the runs above "Review", the order inside
  prepare-package was `legal-notices-clean`, then `legal-notices-copy` (parent executions come before profile
  executions), e.g. `javafx-graphics`: clean, then `[copy] Copying 4 files to ...\target\classes\META-INF\legal`,
  then the jar; R1 shows the amended order. `legal-notices` was active in exactly
  `javafx.graphics`, `javafx.media`, `javafx.web` and `jfx.incubator.richtext` (`mvn help:active-profiles`, and the
  executions in every build log). The jar-excludes profiles active on Windows were `jar-excludes-win`,
  `jar-excludes-win-noes2` and `jar-excludes-nonull3d` (graphics) and `jar-excludes-win` (media). On Linux,
  the WSL graphics jar holds `GtkApplication.class` and no `WinApplication.class`, as `jar-excludes-linux`
  makes it. None of them drops
  `META-INF/legal`: every pattern is `version.rc`, `**/*.hlsl` or under `com/`, and the jars of both hosts carry
  all their notices.
- **D3** `sdk/target/sdk/legal/` is assembled in the base build of `sdk/pom.xml`. A-java ran it under
  `sdk-java-only` and A-native under `sdk-require-natives` (execution lists in both logs).
- **D4** Jar mode reads the jar with `ZipInputStream`, so duplicate entries are counted, and compares every
  occurrence byte for byte. SDK mode maps each `lib/*.jar` to `modules/<name>` through
  `ModuleFinder.of(jar).findAll()`. That gives the descriptor name, or the automatic-module name for
  `javafx-swt.jar`, which the build log proves on every run: `javafx-swt.jar holds module javafx.swt and carries
  its 0 legal notice(s)`. A run prints either its pass lines (stdout) or its problems (stderr), never both. Ant's
  `exec` pumps the two streams on separate threads, and an earlier version that printed both joined lines in the
  build log (first plant run, `...byte for byteERROR: webkit.md: ...`). The plants P1-P5 below were rerun
  through Maven with the version that writes one stream, before review fixes 2 and 3 changed the check, and rerun
  against the final check, without Maven, after W (see AC 4).
- **D5** README as above.
- **D6** No workflow change. Every leg of `submit.yml` runs `mvn -B -ntp -fae install
  "-Djfx.web.skipFfmTests=false"`, and install includes verify. No step lists, uploads or packages
  `sdk/target/sdk`: the only upload is the GTK display reports of graphics, and the post-build steps read
  `modules/javafx.media/target/native/bin`. `run-gtk-display-tests.sh` runs `mvn ... test`, which never reaches
  prepare-package.

## AC 1 — jars

| Jar (module jar and its SDK copy) | Notices under `META-INF/legal/` | Entries before → after (Java-only / native build) |
| --- | --- | --- |
| `javafx.graphics` | `gcc.md` 128,099 B, `jpeg_fx.md` 1,990 B, `mesa3d.md` 5,732 B, `pipewire.md` 1,479 B | 3289 → 3294 / 3553 → 3558 |
| `javafx.media` | `directshow.md` 1,165 B, `glib.md` 33,174 B, `gstreamer.md` 36,861 B, `libffi.md` 2,684 B | 220 → 225 / 220 → 225 |
| `javafx.web` | `icu_web.md` 23,988 B, `libxml2.md` 3,583 B, `libxslt.md` 6,130 B, `webkit.md` 348,160 B | 697 → 702 / 697 → 702 |
| `jfx.incubator.richtext` | `unicode-fx.md` 15,098 B | 270 → 272 / 270 → 272 |
| the other 7 module jars, the test jars, `javafx-jslc`, `javafx-system-tests` | none | unchanged |

The SHA-256 of all 52 notice entries (13 notices × module jar and SDK jar × the two builds) equals that of its source
file. The path is documented in `README.md`.

## AC 2 — SDK

`sdk/target/sdk/legal/` of both after-builds (identical SHA-256 lists) holds 16 files: `LICENSE` (19,274 B),
`ADDITIONAL_LICENSE_INFO` (2,114 B), `ASSEMBLY_EXCEPTION` (1,522 B), and `javafx.graphics/` (4), `javafx.media/`
(4), `javafx.web/` (4) and `jfx.incubator.richtext/` (1) with the notices above. Each one hash-equals its source.
The per-module directories keep a notice from hiding another of the same name. The WSL SDK has the same tree.

## AC 3 — a module that adds `src/main/legal/`

Plant P6: `modules/javafx.base/src/main/legal/us075-plant.md` (62 B) was added (javafx.base had no legal
directory), with no other change.

| Step | Command | Exit | Time | Result |
| --- | --- | --- | --- | --- |
| with the plant | `mvn -B -ntp verify -DskipNative=true -DskipTests` | 0 | 423 s | `legal-notices` activated in `javafx-base` (`Copying 1 file`, jar check `1 legal notice(s) ... byte for byte`). `javafx-base-28-ea.jar` and `lib/javafx.base.jar` carry `META-INF/legal/us075-plant.md`. The SDK has `legal/javafx.base/us075-plant.md`, and its check reports 14 notices of 5 modules. |
| plant and directory removed, no clean | `mvn -B -ntp verify -DskipNative=true -DskipTests` | 0 | 425 s | `legal-notices-clean` logged `Deleting directory ...javafx.base\target\classes\META-INF\legal` and the jar was rebuilt. No jar, no `target` directory and nothing in the SDK holds `us075-plant.md`, and `legal/javafx.base/` is gone. |

## AC 4 — the check

It runs in the normal build: every build in this file ran `legal-notices-jar-check` in the four modules and
`sdk-legal-notices-check` in `javafx-sdk`, and passed. The Verification snippet of the story, run verbatim after
A-java (it runs its own `mvn ... verify`), took 364 s, exited 0 and printed nothing after Maven's footer. Its loop,
run against the SDK jars of the before build, prints 13 lines, so it is not vacuous.

Plants, each through Maven running the real execution, then removed and the same command rerun. A goal alone
does not resolve the upstream modules from their `target/` directories (no 28-ea artifacts are installed, and
`mvn install` is not used). The `test-compile` phase with `-am` lets the reactor resolve them, and it never
reaches prepare-package or package, so the planted jar and SDK stay as planted (the SHA-256 of the jar and the
SDK was unchanged by a pass run).

- Jar command: `mvn -B -ntp -pl modules/javafx.graphics -am test-compile antrun:run@legal-notices-jar-check
  -DskipNative=true -Dmaven.test.skip=true`
- SDK command: `mvn -B -ntp -pl sdk -am test-compile antrun:run@sdk-legal-notices-check -DskipNative=true
  -Dmaven.test.skip=true`

| Plant | Check | Planted: exit, time, failure lines | Removed: exit, time |
| --- | --- | --- | --- |
| P1 `javafx-graphics-28-ea.jar` rewritten without `META-INF/legal/gcc.md` (original kept and restored, SHA-256 equal) | jar | 1, 118 s: `ERROR: gcc.md: ...\javafx-graphics-28-ea.jar: missing: the jar has no entry META-INF/legal/gcc.md` | 0, 126 s |
| P2 first byte of `META-INF/legal/jpeg_fx.md` in that jar XOR 0x01 | jar | 1, 122 s: `ERROR: jpeg_fx.md: ...\javafx-graphics-28-ea.jar: the entry META-INF/legal/jpeg_fx.md differs from ...\src\main\legal\jpeg_fx.md` | 0, 121 s |
| P3 `sdk/target/sdk/legal/javafx.web/webkit.md` deleted | SDK | 1, 184 s: `ERROR: webkit.md: ...\sdk\legal\javafx.web\webkit.md: missing from the SDK (source ...\javafx.web\src\main\legal\webkit.md)` | 0, 176 s |
| P4 `sdk/target/sdk/lib/javafx.media.jar` rewritten without `META-INF/legal/glib.md` | SDK | 1, 183 s: `ERROR: glib.md: ...\sdk\lib\javafx.media.jar: missing: the jar has no entry META-INF/legal/glib.md` | 0, 182 s |
| P5 `modules/javafx.graphics/src/main/legal/us075-plant.md` added after the build, no rebuild | jar | 1, 127 s: `ERROR: us075-plant.md: ...\javafx-graphics-28-ea.jar: missing: the jar has no entry META-INF/legal/us075-plant.md` | 0, 124 s |
| P5 (same plant) | SDK | 1, 186 s: `ERROR: us075-plant.md: ...\sdk\legal\javafx.graphics\us075-plant.md: missing from the SDK (source ...)` and `ERROR: us075-plant.md: ...\sdk\lib\javafx.graphics.jar: missing: the jar has no entry META-INF/legal/us075-plant.md` | 0, 193 s |

Each failing run also ends with `ERROR: <n> legal notice problem(s); every file of a module's src/main/legal must
ship byte for byte in its jar and in the SDK (US-075)` and Maven's `exec returned: 1`.

These runs (16:48-17:17) used the check as it was before review fixes 2 and 3; fix 3 put the `isDroppedFromJars`
guard in front of the `missing` report, and the check last changed at 19:39. After W, P1-P5 were rerun against the final
check (SHA-256 `59e93220...`) with the source launcher alone, `java buildtools/CheckLegalNotices.java jar|sdk`, on
scratch copies of W's output. P1 and P2 used the graphics jar without `gcc.md` and with the first byte of
`jpeg_fx.md` XOR 0x01. P3 and P4 used copies of `sdk/target/sdk`, one without `legal/javafx.web/webkit.md` and one
whose `lib/javafx.media.jar` lacks `glib.md`. P5 added `us075-plant.md` to a copy of the root licence files and of
every module's `src/main/legal`, checked in jar mode against the W graphics jar and in SDK mode against W's SDK.
Each planted run printed the failure lines of the table (scratch paths aside) and the count line, 1 problem each
and 2 for P5 in SDK mode, and exited 1. Each run with the plant undone, and the SDK check of the tree on W's SDK,
exited 0 with nothing on stderr. Each run took 1.4-3.8 s; the outputs are kept in the session scratchpad at
`us075/final-plants/`. The Maven side has not changed since the plants: both checks are `exec` with
`failonerror="true"`, the root pom changed since only in comments and the phase of `legal-notices-clean`, and
`sdk/pom.xml` not at all.

The other rules of the check were exercised on scratch copies of the SDK (`java buildtools/CheckLegalNotices.java
sdk . <sdk>` and `... jar modules/javafx.media/src/main/legal <scratch>/bad.jar`). One run reports every problem:

```
ERROR: glib.md: <sdk>/legal/javafx.media/glib.md: differs from ./modules/javafx.media/src/main/legal/glib.md
ERROR: javafx.base/: <sdk>/legal/javafx.base: unexpected directory in the SDK: no module has notices of that name
ERROR: javafx.web/extra.txt: <sdk>/legal/javafx.web/extra.txt: unexpected file in the SDK: it is neither a licence file of the repository root nor a notice of a module's src/main/legal
ERROR: META-INF/legal/x.md: <sdk>/lib/javafx.base.jar: has no source: ./modules/javafx.base/src/main/legal holds no x.md
ERROR: gcc.md: <sdk>/lib/javafx.graphics.jar: the entry META-INF/legal/gcc.md occurs 2 times, expected once
ERROR: META-INF/legal/: <sdk>/lib/not.a.module.jar: holds module not.a.module, which has no directory ./modules/not.a.module
ERROR: META-INF/legal/: <sdk>/lib: 2 jars hold module jdk.jsobject: [<sdk>/lib/jdk.jsobject.jar, <sdk>/lib/jsobject-copy.jar]
ERROR: unicode-fx.md: <sdk>/lib: missing: the SDK holds no jar of module jfx.incubator.richtext
ERROR: 8 legal notice problem(s); every file of a module's src/main/legal must ship byte for byte in its jar and in the SDK (US-075)
ERROR: directshow.md: <scratch>/bad.jar: the entry META-INF/legal/directshow.md occurs 2 times, expected once
ERROR: directshow.md: <scratch>/bad.jar: copy 2 of the entry META-INF/legal/directshow.md differs from modules/javafx.media/src/main/legal/directshow.md
ERROR: glib.md: <scratch>/bad.jar: the entry META-INF/legal/glib.md differs from modules/javafx.media/src/main/legal/glib.md
ERROR: libffi.md: <scratch>/bad.jar: missing: the jar has no entry META-INF/legal/libffi.md
ERROR: META-INF/legal/stray.md: <scratch>/bad.jar: has no source: modules/javafx.media/src/main/legal holds no stray.md
ERROR: 5 legal notice problem(s); every file of a module's src/main/legal must ship byte for byte in its jar and in the SDK (US-075)
```

The duplicate entries were made by writing `gcc.mX` and `directshow.mX` and renaming them in the local and central
headers. `unzip -Z1` then lists the name twice.

## AC 5 — unchanged content

`US-075-jar-diff.tsv` compares every jar of both builds (32 per build: 20 module and test jars, `javafx-jslc`,
`javafx-system-tests` and the 11 SDK jars). It compares raw entry names and the SHA-256 of each entry's content.
The only differences are the added `META-INF/legal/` entry plus the module's notices: 5 entries in graphics,
media and web, 2 in richtext, in the module jar and its SDK copy. Nothing was removed or changed, and every
other jar is identical in entries and bytes.

The SDK tree differs only by `legal/` (16 files) and the four jars above, except the 7 Windows DLLs of `bin/`
in the native build. MSVC writes link time stamps. The DLLs of A-native were copied aside, relinked from the
same objects (`cmake --build modules/{javafx.graphics,javafx.media}/target/native/cmake --config Release` after
deleting them, 4 s + 5 s, no compile step), and compared byte for byte. Each DLL differs in exactly 4 bytes: the
COFF `TimeDateStamp` and the `TimeDateStamp` of its POGO debug-directory entry, and nothing else
(`fxplugins`, `glass`, `glib-lite`, `gstreamer-lite`, `javafx_iio`, `jfxmedia`, `prism_d3d`). The A-native DLLs
were then put back. US-075 touches no native source, CMake file or module pom.

Builds of the same source and configuration are otherwise deterministic: before and after differ only as listed.
The two baselines (Java-only against native) differ only where the configuration does. The native build adds 264
shader objects under `com/sun/prism/d3d/hlsl/` and `com/sun/scenario/effect/impl/hw/d3d/hlsl/` to the graphics jar
and to five test jars. Through them it also adds those two packages to the `ModulePackages` of the graphics
`module-info.class`.

## Tests

`mvn -B -ntp -fae clean verify -Dmaven.test.failure.ignore=true -Djfx.build.timestamp=2026-11-13-000000
-Djfx.web.skipFfmTests=false`, before and after, natives built from source, web FFM tests run against the cached
`jfxwebkit.dll` in `../caches/sdk/bin`. Per class (`US-075-windows-per-class.tsv`, from the `Tests run:` lines of
the Maven log): 1,237 rows, none differs. Per module, before = after:

| Module | Classes | Run | Failures | Errors | Skipped |
| --- | --- | --- | --- | --- | --- |
| javafx-jslc | 25 | 196 | 0 | 0 | 3 |
| javafx-base | 290 | 5,510 | 0 | 0 | 22 |
| javafx-graphics | 629 | 26,684 | 0 | 0 | 850 |
| javafx-controls | 224 | 9,445 | 0 | 0 | 185 |
| jfx-incubator-richtext | 11 | 186 | 0 | 0 | 0 |
| javafx-fxml | 34 | 123 | 0 | 0 | 3 |
| javafx-media | 6 | 33 | 0 | 0 | 2 |
| javafx-web (ffm-binding-test) | 18 | 194 | 0 | 0 | 0 |

`jfx-incubator-input` and `javafx-swing` print no test summary and `jdk-jsobject` reports `No tests to run`,
on both sides. No `hs_err_pid*` file under `modules/`.

## AC 6 — README

`README.md` no longer says that legal notices are not produced. It names `META-INF/legal/` for the jars and
`legal/` and `legal/<module>/` for the SDK, says that the build checks both and fails otherwise, and keeps
`src.zip`, jmods, javadoc and distribution archives as not produced.

## AC 7 — CI

Met. The owner committed the change as `c696e6d3f6` ("Ship legal notices in jar", 2026-11-13 21:08 -0500), pushed
it and opened PR #60 (open, not merged). Its `pom.xml`, `sdk/pom.xml`, `README.md` and `CheckLegalNotices.java` are
SHA-256-identical to those of W and L ("Final builds"); the rest of the commit is backlog Markdown and TSVs. The
`submit.yml` run of that push, run 43544611258
(https://github.com/Ben-Esquivel-Music/jfx-ffm/actions/runs/43544611258), is green in all six jobs, the five build
jobs of AC 7 and `No JNI`. It ran from 02:08:28 to 02:29:51 UTC on 2026-11-14 (21:08-21:29 local time on
2026-11-13). Each build job runs `mvn -B -ntp -fae install "-Djfx.web.skipFfmTests=false"`; the test totals are the
per-module `Tests run:` summaries Maven printed in that invocation.

| Job | Result | Job time | Maven | Tests run / failures / errors / skipped |
| --- | --- | --- | --- | --- |
| Windows x64 | success | 12:01 min | BUILD SUCCESS, 11:17 min | 42,371 / 0 / 0 / 1,072 |
| Linux x64 | success | 19:26 min | BUILD SUCCESS, 7:13 min | 42,115 / 0 / 0 / 1,179 |
| Linux aarch64 | success | 21:16 min | BUILD SUCCESS, 8:11 min | 42,115 / 0 / 0 / 1,179 |
| macOS x64 | success | 19:44 min | BUILD SUCCESS, 18:28 min | 42,383 / 0 / 0 / 1,072 |
| macOS aarch64 | success | 8:32 min | BUILD SUCCESS, 7:57 min | 42,383 / 0 / 0 / 1,066 |
| No JNI | success | 0:32 min | none (the `CheckNoJni` self-test and the tree check) | — |

Every build leg ran the four `legal-notices-jar-check` executions and `sdk-legal-notices-check`, and each passed. The
checker printed the lines of W and L, paths aside:

| Leg | Jar checks: graphics, richtext, media, web | SDK check |
| --- | --- | --- |
| Windows x64 | 4, 1, 4 and 4 notices, byte for byte | 3 root files and 13 notices of 4 modules, nothing else; 11 jars |
| Linux x64 | 4, 1, 4 and 4 notices, byte for byte | 3 root files and 13 notices of 4 modules, nothing else; 11 jars |
| Linux aarch64 | 4, 1, 4 and 4 notices, byte for byte | 3 root files and 13 notices of 4 modules, nothing else; 11 jars |
| macOS x64 | 4, 1, 4 and 4 notices, byte for byte | 3 root files and 13 notices of 4 modules, nothing else; 11 jars |
| macOS aarch64 | 4, 1, 4 and 4 notices, byte for byte | 3 root files and 13 notices of 4 modules, nothing else; 11 jars |

On every leg `legal-notices-clean` ran in all 15 projects, `legal-notices-copy` copied 4, 1, 4 and 4 files and
`sdk-legal-notices` 3 and 13, and no log has a `legal notice problem` line. These executions do not differ between
legs: every SDK has 11 jars, and `javafx-swt.jar holds module javafx.swt and carries its 0 legal notice(s)` on macOS
as on Windows and Linux.

On both Linux legs the GTK display step ran `mvn ... test` of `javafx-graphics` twice: without a window manager (274
tests, 12 skipped) and under openbox (247 tests, 29 skipped), with no failures or errors. Each run reached initialize,
so those two logs have 17 `legal-notices-clean` executions. The first run deleted the `target/classes/META-INF/legal`
that install had left, and both runs copied 4,190 resources from the shims to `target/test-classes`, as install did,
so the notices stayed out of the shims (review finding 1). The step "Verify the optional avplugin was built" passed
on both Linux legs, so CI covers the avplugin that L could not build.

CI before is run 43431958537, green in all six jobs, on `c8a3348e66`, the head of PR #59. Its tree `81a41930bb` is
that of the base `36123c04a8`, and its logs have no `legal` line. Per leg and per module, the GTK display runs
included, the test totals of run 43544611258 equal those of run 43431958537. The Linux totals also equal L's. Windows
ran W's 42,371 tests with 1,072 skipped against W's 1,065 (`javafx-graphics`: 857 against 850), and so did the CI
before run. The job logs of both runs are kept in the session scratchpad at `us075/ci/` and `us075/ci/before/`.

The Copilot code review of PR #60 (`copilot-pull-request-reviewer`, 2026-11-14 02:34:37 UTC, state COMMENTED, on
`c696e6d3f6`) says "🟢 Approval recommended — No active review issues block the legal-notice packaging changes. No
open issues", with no inline comments. Its overview notes that "CI completion remains pending in the records": that
was the wording of the pushed commit, which this section replaces.

## WSL Linux

Fresh clone of the branch at `36123c04a8`. The working tree was brought over as a binary `git diff` against that
commit plus a tar of the untracked `buildtools/CheckLegalNotices.java`. The four changed files are
SHA-256-identical to Windows.
`DISPLAY` unset; `mvn -version`: Apache Maven 3.10.0, Java 27 (Eclipse Adoptium, `~/jdk27`), linux amd64. The
Verification snippet, run verbatim, ran `mvn -B -ntp verify -DskipNative=true -DskipTests` in 6:00 min (BUILD
SUCCESS, exit 0, 365 s in total) and printed nothing after Maven's footer. The SDK check passed:
`javafx-swt.jar holds module javafx.swt`, 13 notices of the four modules, 11 jars.

## Final tree before the review

On the tree with the corrected comment, before the review fixes:

- **Windows:** the CI-equivalent build (as B-native) passed in 21:45 min. Per class, the results equal the
  baseline (1,237 rows, none differs). All 32 jars are identical in entries and bytes to those of A-native, and
  the SDK differs from A-native's only in the relinked DLLs of `bin/`. The loop of the Verification snippet,
  run on that SDK, printed nothing.
- **WSL:** a fresh clone with the final patch and the untracked files, 8 files SHA-256-identical to Windows. The
  Verification snippet ran verbatim: `mvn -B -ntp verify -DskipNative=true -DskipTests` passed in 6:50 min
  (exit 0, 413 s in total) and the snippet printed nothing after Maven's footer.
- `java buildtools/CheckNoJni.java .` passes (28,100 files).

## Conventions

`CheckLegalNotices.java` (new, 2026 header, LF): no tab, no trailing whitespace, no line over 120 columns, no
wildcard import, compiles with `javac --release 27 -Xlint:all -Werror`. `pom.xml` (902 lines) and
`sdk/pom.xml` (364 lines) are CRLF on every line, with no tab or trailing whitespace. Both headers already read
`Copyright (c) 2026`. `README.md` and the backlog files are LF. `java buildtools/CheckNoJni.java .` passes.
After the review fixes all of this was checked again (R4 under "Review"); `pom.xml` now has 906 lines and
`CheckLegalNotices.java` 435, after the `package.html` change of review finding 3.

## Timings

| Run | Command | Exit | Time |
| --- | --- | --- | --- |
| B-java | `mvn -B -ntp clean verify -DskipNative=true -DskipTests -Djfx.build.timestamp=2026-11-13-000000` | 0 | 7:31 min (15:24–15:32) |
| B-native | `mvn -B -ntp -fae clean verify -Dmaven.test.failure.ignore=true -Djfx.build.timestamp=2026-11-13-000000 -Djfx.web.skipFfmTests=false` | 0 | 14:16 min (15:32–15:46) |
| A-java | as B-java | 0 | 7:54 min (15:48–15:56) |
| Verification (Windows) | the snippet, verbatim | 0 | 6:01 min |
| Plants P1–P5 | see AC 4 (12 runs) | as listed | 118–193 s each |
| P6 | see AC 3 | 0, 0 | 423 s, 425 s |
| A-native | as B-native | 0 | 17:06 min (17:32–17:49) |
| Verification (WSL) | the snippet, verbatim | 0 | 6:00 min |
| Final tree, Windows | as B-native | 0 | 21:45 min (18:10–18:32) |
| Final tree, WSL | the snippet, verbatim, fresh clone | 0 | 6:50 min |
| R1 (review fixes) | as B-java | 0 | 9:21 min (19:12–19:22) |
| R2 (review fixes) | the snippet, verbatim, right after R1 | 0 | 7:03 min (19:22–19:29) |
| W (final build, Windows) | `mvn -B -ntp -fae clean verify -Djfx.web.skipFfmTests=false -Djfx.build.timestamp=2026-11-13-000000` | 0 | 16:36 min (20:03–20:20) |
| L (final build, WSL) | `mvn -B -ntp -fae verify -Djfx.web.skipFfmTests=false`, fresh clone | 0 | 13:21 min (20:21–20:35) |
| CI (AC 7) | `submit.yml` run 43544611258 on `c696e6d3f6`, five build jobs and `No JNI` | success | 0:32–21:16 min per job (21:08–21:29) |

The new steps cost little: `javafx-sdk` took 2.2–2.4 s against 0.1–0.3 s before, and a jar check is one
source-launcher run.

## Review

Two rounds before the push; after it, the Copilot review of PR #60 recommended approval with no open issues (see
"AC 7 — CI"). First, three general-purpose reviewers stood in for `game-toolkit-reviewer`, which had no agent file
then, one each through the build, the check and the evidence lens, and signed off. A skeptic pass confirmed
exactly three findings, and each got the smallest fix (1-3 below, checked by R1-R4). Then the review the
Definition of Done names, by `game-toolkit-reviewer`, ran twice: first as a general-purpose agent following its
agent definition, on the tree with those fixes, then as the registered agent type, on the final tree after W and L
(see "game-toolkit-reviewer" at the end of this section).

1. **Notices reached the shims of a build without `clean` (minor).** The shim staging of `javafx.graphics`,
   `javafx.web` and `jfx.incubator.richtext` copies every non-class file of `target/classes` into
   `target/shims/<module>` at process-test-sources (`modules/javafx.graphics/pom.xml:298-300`,
   `modules/javafx.web/pom.xml:196-198`, `modules/jfx.incubator.richtext/pom.xml:173-175`), and
   `legal-notices-clean` ran only at prepare-package. So a build without `clean` put the previous build's notices
   into those shims and from there into `target/test-classes` and the `-tests.jar`s. The Verification run after
   A-java shows it: it copied 4 files to the graphics shims, 1 to the richtext shims and 5 to the web shims, and the
   test resources copied from them were 3932 / 248 / 694 against 3928 / 247 / 690 in a clean build. Fix: decision
   D2(a), amended by the manager. The root `pom.xml` binds `legal-notices-clean` to initialize; `legal-notices-copy`
   stays at prepare-package and the jar check at verify. The comments of that execution and of the `legal-notices`
   profile now give the phase and its reason. No module pom changed, and there is no skip property.
2. **Wrong reason in the Javadoc (nit).** `CheckLegalNotices.java` said that `ZipFile` "shows only one of several
   entries with the same name", but on JDK 27 `ZipFile.size()` and `entries()` list every copy. Fix: the comment now
   says that `ZipFile`'s lookup by name (`getEntry`, or `getInputStream` of an entry looked up by name) resolves to
   only one of several entries with the same name, so the check reads the raw entries with `ZipInputStream` to count
   and compare every copy. No other comment or text repeated the claim.
3. **Misleading error for a file the jar plugin drops (nit).** maven-jar-plugin 3.4.2 keeps
   `addDefaultExcludes=true`, while both Ant copies use `defaultexcludes="no"` and the check reads every file of
   `src/main/legal`. A git-ignored junk file there, such as a Finder `.DS_Store` or an editor backup `gcc.md~`, was
   copied but left out of the jar, and both checks failed with
   `missing: the jar has no entry META-INF/legal/.DS_Store`. Fix: where it reads a `src/main/legal` directory, in
   both modes, the check reports such a file as `maven-jar-plugin's default excludes drop this file name from jars;
   delete or rename it`, and the jar check no longer also calls it missing. The names are those of
   `org.codehaus.plexus.util.AbstractScanner.DEFAULTEXCLUDES` in plexus-utils 4.0.1: its 39 patterns as 26
   file-name patterns (the 13 `<name>/**` patterns only add files below a directory, which the check already
   rejects), matched case-sensitively like the plugin's scanner. 4.0.1 is the version the plugin resolves:
   `maven-parent` 42 manages it, and `mvn -X` of `maven-jar-plugin:3.4.2:jar` in a scratch project lists
   `Included: org.codehaus.plexus:plexus-utils:jar:4.0.1` in the plugin realm. No pom of the tree sets
   `addDefaultExcludes` or adds a dependency to the jar plugin. The check also rejects a notice named
   `package.html`, in every module, with `maven-jar-plugin's own default excludes drop this file name from the jar of
   a module that sets no excludes; rename it`: the plugin's own default excludes, `**/package.html` in
   `AbstractJarMojo.DEFAULT_EXCLUDES` of 3.4.2, apply in a module that sets no `<excludes>`, which of the four modules
   with notices is `jfx.incubator.richtext`. With a scratch `src/main/legal` of `unicode-fx.md` and `package.html`
   and a jar holding only `unicode-fx.md`, jar mode prints that line and the count line, no `missing` line, and exits
   1; the R3 `.DS_Store` and `x.md~` inputs print the same lines as before.

Results on the tree with the three fixes (Windows; host, JDK and Maven as above):

- **R1** `mvn -B -ntp clean verify -DskipNative=true -DskipTests -Djfx.build.timestamp=2026-11-13-000000`: BUILD
  SUCCESS in 9:21 min. In all 15 projects `legal-notices-clean` now runs right after `default-clean` and, in the
  root project, the validate execution `verify-build-tools`; in `javafx-swt` it follows `copy-swt-jar`, that
  module's other initialize execution. Leaving `legal-notices-clean` out, the execution order of every project
  equals A-java's, so the copy, the jar and the check keep their places. All 32 jars equal A-java's in raw entries
  and their SHA-256, and as whole files, and all 28 files of `sdk/target/sdk` hash-equal. This clean build copied
  81, 5 and 55 files to the graphics, richtext and web shims, and 3928, 247 and 690 test resources from them.
- **R2** the Verification snippet of the story, verbatim, right after R1 and without `clean`: BUILD SUCCESS in
  7:03 min (exit 0, 428 s in total), and the loop printed nothing. `legal-notices-clean` logged
  `Deleting directory ...\target\classes\META-INF\legal` at initialize in graphics, richtext, media and web. The
  shim staging then copied no file to the graphics and richtext shims (before the fix: 4 and 1, the notices) and
  1 to the web shims (before: 5): `META-INF/jpms.args`, which every compile rewrites. The test resources copied
  from the shims were 3928 / 247 / 690, as in R1 (before: 3932 / 248 / 694). No `target/shims/*/META-INF/legal`
  and no `target/test-classes/META-INF/legal` exists, and each of the 8 `-tests.jar`s has 0 `META-INF/legal`
  entries. Against R1, 23 of the 32 jars are identical and the other 9 differ only in
  `com/sun/javafx/runtime/VersionInfo.class`, the SDK also in `lib/javafx.properties`, because the snippet does not
  pass `-Djfx.build.timestamp`.
- **R3** A scratch `src/main/legal` held `unicode-fx.md`, `.DS_Store` and `x.md~`, and `maven-jar-plugin:3.4.2:jar`
  itself built the jar from a classes directory holding all three under `META-INF/legal/`; it kept only
  `unicode-fx.md`. Jar mode then prints the following and exits 1:
  ```
  ERROR: .DS_Store: <scratch>\legal\.DS_Store: maven-jar-plugin's default excludes drop this file name from jars; delete or rename it
  ERROR: x.md~: <scratch>\legal\x.md~: maven-jar-plugin's default excludes drop this file name from jars; delete or rename it
  ERROR: 2 legal notice problem(s); every file of a module's src/main/legal must ship byte for byte in its jar and in the SDK (US-075)
  ```
  The check before the fix printed `missing: the jar has no entry META-INF/legal/.DS_Store` and the same for
  `x.md~`. SDK mode on a scratch root and SDK with a `.DS_Store` among the richtext notices (in
  `legal/jfx.incubator.richtext/`, as the Ant copy keeps it, and not in the jar) reports only that file, with the
  same reason, and exits 1. On the real output of R2, jar mode passes for the four module jars (4, 4, 4 and 1
  notices, exit 0) and SDK mode for `sdk/target/sdk` (13 notices of 4 modules, 11 jars, exit 0).
- **R4** `pom.xml` (906 lines) and `sdk/pom.xml` (364) are CRLF on every line; `CheckLegalNotices.java`
  (413 lines at R4; 435 after the `package.html` change, rechecked the same way), `README.md` and the backlog
  files are LF; none has a tab outside the TSVs or trailing whitespace; no Java line is over 120 columns;
  `javac --release 27 -Xlint:all -Werror` compiles the check; and
  `java buildtools/CheckNoJni.java .` passes (28,100 files).

The CI-equivalent build with natives and tests, on Windows and in WSL, was rerun on the tree with the three fixes;
see "Final builds". AC 7 (CI) was met later, by the `submit.yml` run on the pushed commit; see "AC 7 — CI".

### game-toolkit-reviewer

`game-toolkit-reviewer` (agent definition `.claude/agents/game-toolkit-reviewer.agent.md`, created for this story)
reviewed the change twice.

The first review, of the tree with the three fixes above, ran as a general-purpose agent that read the agent
definition, because the agent type was not yet registered in the session that created the file. Verdict: sign-off,
with one finding.

- **F1 The records named stand-ins for a review that had not run (minor).** The story's Outcome, the intro of this
  section and the done row of `backlog/README.md` said that general-purpose reviewers stood in for
  `game-toolkit-reviewer` because it had no agent file yet. The agent file was created at 19:48, after R1-R4
  (19:12-19:43), so that reason no longer held, and the story had been set to Done before the review its Definition
  of Done names had run. Fix: the three records now name this review and its verdict and keep the stand-in reviews
  as history, and the line that said the native build and the WSL run were not rerun after the review fixes now
  points to their results under "Final builds". Markdown only: no build file changed, and the jars of W equal those
  of the final tree before the review.

The second review, of the final tree after W and L, ran as the registered `game-toolkit-reviewer` agent type: its
system prompt is the body of the definition and the definition's three skills were preloaded. It ran no build and
changed no file. Verdict: sign-off, with three findings. All three are in the records and were fixed in Markdown
only, so no build file changed and W and L stand.

- **F1 The first review was not the registered agent type (minor).** The intro of this section, this subsection,
  the story's Outcome and the done row of `backlog/README.md` said that `game-toolkit-reviewer` itself had signed
  off, but that pass was a general-purpose agent following the definition. Fix: those records now say how each
  review ran.
- **F2 The plants ran on an earlier check (minor).** D4 said that all plants were rerun with the final version, but
  P1-P5 ran before review fixes 2 and 3 changed the check, including the branch that reports a missing notice.
  Fix: D4 says which version they ran on, and AC 4 records P1-P5 rerun against the final check: each fails, and
  passes once undone. The reviewer's own re-plants on scratch copies agreed.
- **F3 The game-toolkit order still listed US-075 as free to start (nit).** "Order" in `backlog/README.md` said
  that US-075 "also ships the 13 notices that reach no jar today". Fix: the item was marked in place as implemented,
  with CI pending the owner's push; once AC 7 was met it was marked ✅ done.

## Final builds

On the tree with every review fix, one build at a time. The four changed files outside `backlog/` (`pom.xml`,
`sdk/pom.xml`, `README.md`, `CheckLegalNotices.java`) are SHA-256-identical on both hosts. Both runs are the CI
command with verify instead of install, without the `-Dmaven.test.failure.ignore=true` of the earlier native runs,
so a failing test would have failed the build. The logs are kept in the session scratchpad, at
`us075/final2/win/mvn.log` and `us075/final2/jfx-us075-final2-out/mvn.log`.

- **W, Windows:** `mvn -B -ntp -fae clean verify -Djfx.web.skipFfmTests=false
  -Djfx.build.timestamp=2026-11-13-000000`, natives built from source: BUILD SUCCESS, exit 0, 16:36 min. Per class:
  1,237 rows, 42,371 tests, 0 failures, 0 errors, 1,065 skipped, and no row differs from B-native, A-native or the
  final tree before the review. The four jar checks and the SDK check passed: `...\sdk\target\sdk\legal holds
  [LICENSE, ADDITIONAL_LICENSE_INFO, ASSEMBLY_EXCEPTION] and the 13 legal notice(s) of [javafx.graphics,
  javafx.media, javafx.web, jfx.incubator.richtext], byte for byte, and nothing else; the 11 jar(s) of
  ...\sdk\target\sdk\lib carry exactly the notices of their module`. All 32 jars are identical as whole files to
  those of the final tree before the review, and so are 28 of the 35 files of `sdk/target/sdk`. The other 7 are
  the DLLs of `bin/`, each 4 bytes away from A-native's: the COFF `TimeDateStamp` and that of the POGO
  debug-directory entry. Only the four module jars and their SDK copies hold `META-INF/legal/` entries. The
  Verification loop, run on this SDK, printed nothing, and `java buildtools/CheckNoJni.java .` passes (28,100
  files). The log has the 205 `[WARNING]` lines of B-native and one `[ERROR]` line, the SWT POM model message that
  B-native also has. No `hs_err_pid*` file was written.
- **L, WSL Linux:** a fresh clone of `36123c04a8` with `git diff HEAD --binary` and a tar of the untracked files
  applied; Temurin 27+35 at `~/jdk27`, Maven 3.10.0 at `~/maven310`, `DISPLAY` and `WAYLAND_DISPLAY` unset, and the
  null ALSA device of `submit.yml` in `~/.asoundrc`. `mvn -B -ntp -fae verify -Djfx.web.skipFfmTests=false`, natives
  built from source: BUILD SUCCESS, exit 0, 13:21 min. Per class: 1,237 rows, 42,115 tests, 0 failures, 0 errors,
  1,179 skipped. `javafx-graphics` ran 26,428 tests and skipped 967, and `javafx-jslc` skipped none; the other
  modules ran and skipped what they do on Windows. The four jar checks passed, and so did the SDK check:
  `<clone>/sdk/target/sdk/legal holds [LICENSE, ADDITIONAL_LICENSE_INFO, ASSEMBLY_EXCEPTION] and the 13 legal
  notice(s) of [javafx.graphics, javafx.media, javafx.web, jfx.incubator.richtext], byte for byte, and nothing else;
  the 11 jar(s) of <clone>/sdk/target/sdk/lib carry exactly the notices of their module`. The 16 files of
  `sdk/target/sdk/legal` hash-equal those of W, and the SDK holds the 7 Linux libraries in `lib/`. The
  Verification loop printed nothing. No `hs_err_pid*` file was written. WSL lacks the `libavcodec-dev`,
  `libavformat-dev` and `libswscale-dev` that the CI Linux job installs, so CMake logged `javafx.media:
  libavcodec/libavformat not found, skipping avplugin`. CI builds avplugin and checks it in a step after Maven, which
  passed on both Linux legs of run 43544611258 (AC 7). The
  job's other packages are present (`pkg-config` finds `gl`, `x11`, `xxf86vm`, `xt`, `gtk+-3.0`, `freetype2`,
  `pangoft2` and `alsa`, and `ldconfig` finds `libXtst.so.6`).
