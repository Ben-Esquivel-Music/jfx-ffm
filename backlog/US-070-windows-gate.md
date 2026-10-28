# US-070 — Windows gate (AC 6)

Host: Windows 10 Pro x64 (10.0.19045), Temurin JDK 27+35, Apache Maven 3.10.0.

| Run | Tree | Time (EDT) |
| --- | --- | --- |
| Before | `master` at `e9d25742bc`, in a separate worktree | 2026-10-27, 22:33 to 22:44 |
| After | the US-070 working tree on `e9d25742bc`, comment pass and the C comment and string edits included | 2026-10-28, 00:27 to 00:37 |

Commands, from the repository root, in this order for each tree:

```
mvn -B -ntp -pl modules/javafx.graphics -am test "-Dtest=test/com/sun/glass/ui/win/**" \
    "-Dsurefire.failIfNoSpecifiedTests=false"
mvn -B -ntp -pl modules/javafx.graphics -am test
find modules/javafx.graphics -maxdepth 1 -name 'hs_err_pid*' -type f
find modules/javafx.graphics/target -name 'hs_err_pid*' -type f
find . -maxdepth 1 -name 'hs_err_pid*' -type f
```

The targeted run builds the natives, so the after run used a glass.dll built from this tree, including the comment
and string edits of `glass_win_api.cpp` and `glass_win_api.h`.

## Targeted run (run / failures / errors / skipped)

| Class | Before | After |
| --- | --- | --- |
| `WinAccessibilityNativeTest` | 16 / 0 / 0 / 0 | 16 / 0 / 0 / 0 |
| `WinAccessibilityReleaseNativeTest` | 3 / 0 / 0 / 0 | 3 / 0 / 0 / 0 |
| `WinApplicationNativeTest` | 19 / 0 / 0 / 0 | 19 / 0 / 0 / 0 |
| `WinClipboardNativeTest` | 22 / 0 / 0 / 0 | 22 / 0 / 0 / 0 |
| `WinCommonDialogsNativeTest` | 7 / 0 / 0 / 0 | 7 / 0 / 0 / 0 |
| `WinCursorPixelsNativeTest` | 29 / 0 / 0 / 0 | 29 / 0 / 0 / 0 |
| `WinDndNativeTest` | 8 / 0 / 0 / 0 | 8 / 0 / 0 / 0 |
| `WinDowncallExceptionReportingTest` | 1 / 0 / 0 / 0 | 1 / 0 / 0 / 0 |
| `WinGlassNativeTest` | 27 / 0 / 0 / 0 | 27 / 0 / 0 / 0 |
| `WinImeCompositionFaultNativeTest` | 15 / 0 / 0 / 0 | 15 / 0 / 0 / 0 |
| `WinMenuNativeTest` | 9 / 0 / 0 / 0 | 9 / 0 / 0 / 0 |
| `WinPreferenceSinksNativeTest` | 10 / 0 / 0 / 1 | 10 / 0 / 0 / 1 |
| `WinPreferencesNativeTest` | 28 / 0 / 0 / 0 | 28 / 0 / 0 / 0 |
| `WinScreenLayoutParityTest` | 5 / 0 / 0 / 0 | 5 / 0 / 0 / 0 |
| `WinScreenNativeTest` | 7 / 0 / 0 / 0 | 7 / 0 / 0 / 0 |
| `WinScreenParityTest` | 3 / 0 / 0 / 0 | 3 / 0 / 0 / 0 |
| `WinTimerJitterTest` | 1 / 0 / 0 / 1 | 1 / 0 / 0 / 1 |
| `WinTimerNativeTest` | 13 / 0 / 0 / 0 | 13 / 0 / 0 / 0 |
| `WinTimerTest` | 5 / 0 / 0 / 0 | 5 / 0 / 0 / 0 |
| `WinToolkitInvokeOrderNativeTest` | 2 / 0 / 0 / 0 | 2 / 0 / 0 / 0 |
| `WinToolkitShutdownHammerTest` | 1 / 0 / 0 / 0 | 1 / 0 / 0 / 0 |
| `WinToolkitWindowGuardNativeTest` | 8 / 0 / 0 / 1 | 8 / 0 / 0 / 1 |
| `WinViewNativeTest` | 28 / 0 / 0 / 0 | 28 / 0 / 0 / 0 |
| `WinWindowNativeTest` | 32 / 0 / 0 / 0 | 32 / 0 / 0 / 0 |
| **Total** | **299 / 0 / 0 / 3** | **299 / 0 / 0 / 3** |

After the implementation review's comment-only fixes (2026-10-28, 00:57 to 01:01 EDT) the story's line-count check
printed only `WinAccessible.java`, and the targeted run, with `-DskipNative=true` because the natives were current,
gave 299 / 0 / 0 / 3 with the same counts per class; the three `find` commands printed nothing.

## Full module run (run / failures / errors / skipped)

| Module | Before | After |
| --- | --- | --- |
| `javafx-jslc` | 159 / 0 / 0 / 0 | 159 / 0 / 0 / 0 |
| `javafx-base` | 5,510 / 0 / 0 / 22 | 5,510 / 0 / 0 / 22 |
| `javafx-graphics` | 26,215 / 0 / 0 / 447 | 26,215 / 0 / 0 / 447 |

Both builds succeeded. The sorted per-class lists of the two full runs (911 test classes, run, failures, errors and
skipped per class) are identical: `diff` prints nothing, so before == after. The after list is
[US-070-windows-full-per-class.tsv](US-070-windows-full-per-class.tsv).

All three `find` commands printed nothing for either tree: no crash file.

## CI

The `windows_x64_build` job of `.github/workflows/submit.yml` runs after the owner pushes; its run and result are
added here and in the story then.

A local replay of the job's build step on this host, on the final tree (the round-4 tree, over `e9d25742bc`), with
`clean` because CI starts from a fresh checkout and `verify` in place of the job's `install` (`submit.yml:534`), so
that `~/.m2` is left as it is:

```
mvn -B -ntp -fae clean verify "-Djfx.web.skipFfmTests=false"
```

2026-10-28, 01:02:56 to 01:19:40 EDT, Temurin 27+35: exit 0, BUILD SUCCESS in all 15 reactor modules in 16:40 min,
0 failures and 0 errors.

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

No new crash file: the three `tests/system/hs_err_pid*.log` that a `find` from the repository root lists are
gitignored leftovers dated 2026-09-14, 2026-09-21 and 2026-09-30, older than this run.
