# US-066 — Windows gate (AC 6)

Host: Windows 10 Pro x64 (10.0.19045), Temurin JDK 27+35, Apache Maven 3.10.0, 2026-10-27.

| Run | Tree | Time (EDT) |
| --- | --- | --- |
| Before | `master` at `e3b8b21e6d`, in a separate worktree | 17:46 to 17:58 |
| After | the US-066 working tree on `e3b8b21e6d`, review fixes included | 19:13 to 19:21 |

Commands, from the repository root, in this order for each tree:

```
mvn -B -ntp -pl modules/javafx.graphics -am test "-DskipNative=true" "-Dsurefire.failIfNoSpecifiedTests=false" \
    "-Dtest=DW*Test,DirectWriteMetricsGoldenTest,FontEnumerationGoldenTest" "-Djfx.parity.require=true"
mvn -B -ntp -pl modules/javafx.graphics -am test
find modules/javafx.graphics -maxdepth 1 -name 'hs_err_pid*' -type f
find modules/javafx.graphics/target -name 'hs_err_pid*' -type f
```

## Targeted run (run / failures / errors / skipped)

| Class | Before | After |
| --- | --- | --- |
| `DWNativeTest` | 21 / 0 / 0 / 0 | 21 / 0 / 0 / 0 |
| `DWFontFaceTest` | 20 / 0 / 0 / 0 | 20 / 0 / 0 / 0 |
| `DWLayoutTest` | 25 / 0 / 0 / 0 | 25 / 0 / 0 / 0 |
| `DWRenderTest` | 14 / 0 / 0 / 0 | 14 / 0 / 0 / 0 |
| `DirectWriteMetricsGoldenTest` | 2 / 0 / 0 / 0 | 2 / 0 / 0 / 0 |
| `FontEnumerationGoldenTest` | 2 / 0 / 0 / 0 | 2 / 0 / 0 / 0 |
| **Total** | **84 / 0 / 0 / 0** | **84 / 0 / 0 / 0** |

The final `ffm-reviewer` run on the final tree (2026-10-27, 19:29 to 19:32 EDT) repeated the story's line-count
check, which printed nothing, and the targeted run: 84 / 0 / 0 / 0 with the same counts per class; both `find`
commands printed nothing.

## Full module run (run / failures / errors / skipped)

| Module | Before | After |
| --- | --- | --- |
| `javafx-jslc` | 159 / 0 / 0 / 0 | 159 / 0 / 0 / 0 |
| `javafx-base` | 5,510 / 0 / 0 / 22 | 5,510 / 0 / 0 / 22 |
| `javafx-graphics` | 26,215 / 0 / 0 / 447 | 26,215 / 0 / 0 / 447 |

Both builds succeeded. The sorted per-class lists of the two full runs (911 test classes, run, failures, errors and
skipped per class) are identical: `diff` prints nothing. The after list is
[US-066-windows-full-per-class.tsv](US-066-windows-full-per-class.tsv).

Both `find` commands printed nothing for either tree: no crash file.

## CI

The `windows_x64_build` job of `.github/workflows/submit.yml` runs after the owner pushes; its run and result are
added here and in the story then.

A local replay of the job's build step on this host, on the final tree (2026-10-27, 19:40 to 19:54 EDT), with
`clean` because CI starts from a fresh checkout and `verify` instead of `install` so that `~/.m2` is not overwritten:

```
mvn -B -ntp -fae clean verify "-Djfx.web.skipFfmTests=false"
```

BUILD SUCCESS in all 15 reactor modules, 0 failures and 0 errors; no `hs_err_pid*` under `modules/`.

| Module | Run / skipped |
| --- | --- |
| `javafx-jslc` | 159 / 0 |
| `javafx-base` | 5,510 / 22 |
| `javafx-graphics` | 26,215 / 447 |
| `javafx-controls` | 9,445 / 185 |
| `jfx-incubator-richtext` | 186 / 0 |
| `javafx-fxml` | 123 / 3 |
| `javafx-media` | 33 / 2 |
| `javafx-web` | 194 / 0 |
