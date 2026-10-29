# US-001a — Migrate `glass/gtk` from JNI to FFM (Linux)

| Field | Value |
| --- | --- |
| **Status** | ✅ **Done** (2026-10-27; landed 2026-10-27 as `e3b8b21e6d`, PR #40). Actions run 40584664686 on the PR's head `fa1461d98e`, which has the tree of `e3b8b21e6d`, is green in all five jobs, the GTK display step of `linux_x64_build` and `linux_aarch64_build` included (see "Verified in CI"). The JNI went in PR #12 (`26ce75d02f`, 2026-09-24): no Java `native` method in `com.sun.glass.ui.gtk`, no `JNIEXPORT` or `jni.h` in `native-glass/gtk`, `libglass.so` no longer built. This change met AC 9 (`glassgtk3` builds without `HEADERS_DIR` and the JDK include directories), fixed a hang that PR #12 brought to the drop target (see Notes), and recorded AC 5, AC 6 and AC 10 |
| **Parent** | [US-001](US-001-descope-glass-gtk-glass-mac-prism-mtl-ffm-migration.md), split by OS on 2026-10-27; the macOS half is [US-001b](US-001b-migrate-glass-mac-and-prism-mtl-jni-to-ffm-on-macos.md) |
| **Branch of record** | `ffm/graphics` |
| **Repository** | `Ben-Esquivel-Music/jfx-ffm` |
| **Priority** | Medium |
| **Estimate** | S for what was left (one CMake target, 11 constant headers in 9 source files) |
| **Blocked by** | Nothing. WSL builds and tests the Linux natives (unblocked 2026-09-15); the GTK display tests run in CI on Xvfb since `c666773a96` (PR #35) |

---

## Story

> **As** a JavaFX platform maintainer,
> **I want** the Linux Glass layer (`glass/gtk`) to use the Java 22+ Foreign Function & Memory API instead of JNI,
> **so that** `javafx.graphics` has no JNI on Linux and the `glassgtk3` build needs neither `jni.h` nor the
> `javac -h` headers.

---

## Scope

Measured on `7dcae7baeb` (2026-10-27):

| Area | Then (de-scoping, 2026-09-07) | Now |
| --- | ---: | ---: |
| Java `native` declarations in `com.sun.glass.ui.gtk` | 96 (99 in the PR #12 hand-off) | 0 |
| `JNIEXPORT` in `native-glass/gtk` | — | 0 |
| Files in `native-glass/gtk` that include `jni.h` | 25 contained JNI | 0 |
| `Call*Method` upcall sites | 102 | 0 (9 matches, all in comments that cite the JNI) |

The C ABI is `glass_gtk_api.h` (four callback tables) and `screencast_api.h` (one callback slot), both free of
JNI types; the Java side is `com.sun.glass.ui.gtk.GtkGlassNative`.

Out of scope: the screencast port to Rust and its defects (US-037, US-038, US-042), the GTK bounds faults
(US-065), and any change of behaviour. The deviations from `033187ad90` that were accepted are listed under Notes.

---

## Acceptance criteria

The criteria of US-001 that apply to Linux, with their state on 2026-10-27:

| # | Criterion | State |
| --- | --- | --- |
| 1 | No `JNIEXPORT`, no `#include <jni.h>`, no `JNIEnv*` parameter in `native-glass/gtk` | ✅ Met (measured; `JNIEnv`, `jobject` and `jclass` appear only in comments, in 4 files) |
| 2 | No Java `native` declaration in `com.sun.glass.ui.gtk` | ✅ Met (0) |
| 3 | A plain C ABI with primitive-typed parameters | ✅ Met: `glass_gtk_api.h`, `screencast_api.h` |
| 4 | Every upcall goes through a callback table registered from Java; no Java exception crosses the native boundary | ✅ Met in PR #12 (`GtkCallbackTableTest`, `GtkNullSlotsTest`, `GtkUpcallExceptionTest`) |
| 5 | GTK calls stay on the GTK main-loop thread; upcall stubs outlive every native use | ✅ Met: PR #12's tests for it, `GtkInvokeLaterNativeTest` (6 tests), `GtkViewLifetimeTest` (3) and `GtkPeerRegistryTest` (5), ran and passed in both runs of the final gate's GTK display tests (no window manager, and openbox); `GtkPeerRegistryTest` also passed in its module gate. See "AC 6 — the final gate" |
| 6 | Module tests and `tests/system` on Linux equal the JNI baseline; nothing deleted or `@Disabled` | ✅ Met on Linux x64 (WSL2, 2026-10-27), see "AC 6 — the final gate". Against `033187ad90`, no module test class that both trees have fails or runs fewer tests, and every `tests/system` test that fails on this tree fails at `033187ad90` too: 25 need `jfxwebkit`, which this WSL lacks, and 2 failed at `033187ad90` only when rerun. No test was `@Disabled` between `033187ad90` and this tree; the only test deleted is `PiscesNativeTest`, which went with the Pisces C (US-046). In CI, Linux x64 and aarch64 (run 40584664686): module tests and GTK display tests green; against run 40502077586 on the tree of `26ed4ba2f2`, every class both runs have gives the same counts, and the two new classes are the only change, see "Verified in CI". CI runs no `tests/system`, and no aarch64 run compares with `033187ad90` |
| 7 | Metal pixel parity | Not applicable (macOS only, US-001b) |
| 8 | Accessibility checked against a real screen reader | Not applicable: GTK Glass has no accessibility bridge. `native-glass/gtk` has no ATK code, and `com.sun.glass.ui.gtk` does not override `Application.createAccessible()`, which returns `null` |
| 9 | `linux.cmake` no longer gives the GTK target `HEADERS_DIR` or the JDK include directories, and the target needs no `javac -h` step | ✅ Met, see "AC 9 — how it was done": `glassgtk3` includes `native-glass/gtk/constants` instead, and it builds with `HEADERS_DIR` and `JDK_HOME` pointing nowhere and with the `javac -h` output deleted |
| 10 | `ffm-reviewer` sign-off on the diff | ✅ Signed off, see "AC 10 — the reviews": three passes from `033187ad90` to this tree. The first two approved with findings, each of which is now fixed or recorded as an accepted deviation under Notes; the final pass approved with no findings |

### AC 9 — how it was done

- `modules/javafx.graphics/src/main/native-glass/gtk/constants/` holds 11 hand-kept headers, one per `javac -h`
  header the GTK C includes, with javac's file names (`com_sun_glass_ui_Window_Level.h` for `Window$Level`),
  macro names and literal spelling (`<decimal>L`; `-1L` for `Cursor.CURSOR_NONE`). Each has the GPLv2+Classpath
  header, a comment, an include guard and `#define`s, and no `jni.h`, `extern "C"` or `#undef`. They define 206
  constants: the 201 that the C uses, and all five of `DndEvent`, whose only includer, `glass_dnd.cpp`, uses none
  of them (kept so that the header is not empty). The AC 9 change edits none of the 9 sources that include them:
  their `#include` lines now resolve to this directory, so merges from upstream are unaffected.
- `linux.cmake`: `glassgtk3` gets `"${GRAPHICS_SRC}/native-glass/gtk/constants"` in place of
  `"${HEADERS_DIR}" "${JDK_HOME}/include" "${JDK_HOME}/include/linux"`. The comment above the target and the input
  list at the top of the file say that no Linux target reads `JDK_HOME`, `GENSRC_DIR` or `HEADERS_DIR`.
  `CMakeLists.txt` and the poms are unchanged: they still pass and require these inputs, which `win.cmake` and
  `mac.cmake` use. Windows `glass` and macOS (US-001b) still use the `javac -h` step, so it stays in the build.
- `test.com.sun.glass.ui.gtk.GtkConstantsHeaderTest` (12 tests: one per header, and one that the directory holds
  headers) checks every `#define` against the class file of its Java class, read with the ClassFile API, which
  initializes no class: the field exists and is a `static final` constant, the values are equal, and the literal
  is spelled as `javac -h` spells the field's type. It is pure Java and runs on every OS: it passed on Windows and
  in the Linux gates. Mutant proof: 15 mutants, each reverted after its run, each failed with a message that names
  the file (or the directory) and what was expected and found: a changed value
  (`com_sun_glass_events_KeyEvent_PRESS 112L`, "expected 111L, actual 112L"), a macro for a field that does not
  exist, a header emptied of its `#define`s, an `#include <jni.h>`, a header that maps to no class, an empty
  directory, a macro of another class, `211` without `L`, a `#define` without a value, a `String` constant, an
  instance field, an `#undef`, an `extern "C"`, an unterminated comment and a wrong `byte` value. The mutants ran
  while the `DndEvent` header still had no `#define`, so that header failed as well in every mutant run that read
  it; with the header as it is now, the same test passes 12 of 12.
- Identity, in one WSL clone: `libglassgtk3.so` built at `26ed4ba2f2` and with the AC 9 change (the headers,
  `linux.cmake` and a comment in `glass_gtk_api.h`) are byte-identical (`cmp`, sha256 `c734bd574a82…`). The compile
  commands differ only in the include directories: `-I` of `target/gensrc/headers/javafx.graphics`, of
  `<JDK>/include` and of `<JDK>/include/linux` became `-I` of `native-glass/gtk/constants`. The `glass_dnd.cpp` fix
  under Notes then changed the library (sha256 `70978339131a…`): 16 of its 17 objects are byte-identical to the
  AC 9 build, and the needed libraries and the 4,445 defined dynamic symbols (64 `ggtk_*`, 11 `sc_*`, no `Java_*`
  or `JNI_*`) are the same by name and type. The other three Linux libraries (`libjavafx_iio.so`,
  `libprism_es2.so`, `libprism_es2_monocle.so`) are byte-identical to those of `26ed4ba2f2`.
- No JDK and no `javac -h` output needed: a private CMake configure and build of the `glassgtk3` target alone, with
  `-DHEADERS_DIR=/nonexistent/us001a -DJDK_HOME=/nonexistent/us001a`, builds the same library, with and without the
  `glass_dnd.cpp` fix; at `26ed4ba2f2` the same build fails (`GlassView.cpp:25:10: fatal error:
  com_sun_glass_events_ViewEvent.h: No such file or directory`). With `target/gensrc/headers` deleted, a full
  rebuild of `glassgtk3` with the AC 9 change succeeds and gives the same library; none of its dependency files
  names the JDK, `gensrc` or `jni.h`, and 9 name `constants/`.

### AC 6 — the final gate (2026-10-27)

Linux x64 only: WSL2 (kernel 6.18.54.1, 8 CPUs, 11 GB), Temurin 27+35, Maven 3.10.0, GTK 3.24.52, a rootless
Xvfb. Each tree was built in a fresh clone. "This change" is `26ed4ba2f2` plus the working tree's diff and new
files; in its clone the 19 changed and new paths equal the working tree by sha256. Nothing was installed into the
shared `~/.m2`. Counts come from the Maven log, and in every step the per-class sums equal Maven's totals. No run
left an `hs_err_pid` file.

| Gate | Trees | Command |
| --- | --- | --- |
| Module tests | `033187ad90`, `26ed4ba2f2`, this change | `env -u DISPLAY -u WAYLAND_DISPLAY mvn -B -ntp -pl buildtools/jslc,modules/javafx.base,modules/javafx.graphics clean test`; at `033187ad90` with `-Dproject.build.outputTimestamp=-`, without which Maven 3.10 cannot build that commit |
| GTK display tests | `26ed4ba2f2`, this change | `bash .github/scripts/run-gtk-display-tests.sh -am`: both runs, without a window manager and under openbox |
| `tests/system` | `033187ad90`, this change | `mvn -B -ntp -Dmaven.repo.local=<private> -Dproject.build.outputTimestamp=- -pl tests/system -am install -DskipTests`, then, on Xvfb `-screen 0 1920x1080x24` under openbox, `env -u DISPLAY dbus-run-session -- env DISPLAY=:96 mvn -B -ntp -Dmaven.repo.local=<private> -Dproject.build.outputTimestamp=- -pl tests/system test -DFULL_TEST=true -DUSE_ROBOT=true` |

Module tests, run / failed / errors / skipped:

| Module | `033187ad90` | `26ed4ba2f2` | This change |
| --- | ---: | ---: | ---: |
| `javafx-jslc` | no tests | 159/0/0/0 | 159/0/0/0 |
| `javafx-base` | 5,510/0/0/22 | 5,510/0/0/22 | 5,510/0/0/22 |
| `javafx-graphics` | 25,004/0/0/482 | 25,993/0/0/606 | 26,005/0/0/606 |
| Test classes | 797 | 909 | 911 |

- Against `26ed4ba2f2`: 31,034 test methods have the same result, 628 skip on both sides, and 12 are new, those of
  `GtkConstantsHeaderTest`. The other new class, `GtkDnDReentrantDropTest`, runs no test without a display (its
  `@BeforeAll` assumes `-DUSE_ROBOT=true`).
- Against `033187ad90`, the 796 classes both trees have: none fails or runs fewer tests. `PrismFontFactoryTest`
  skips one test more, its macOS-only method from US-003; it does so at `26ed4ba2f2` too.

GTK display tests, classes / tests / failures / errors / skipped:

| Run | `26ed4ba2f2` | This change |
| --- | --- | --- |
| No window manager | 34 / 210 / 0 / 0 / 0 | 36 / 227 / 0 / 0 / 0 |
| Openbox | 34 / 183 / 0 / 0 / 3 | 36 / 200 / 0 / 0 / 3 |

The 17 more tests of each run are those of `GtkConstantsHeaderTest` (12) and `GtkDnDReentrantDropTest` (5); every
other test has the same result. Under openbox, on both trees, the 5 classes that need a display without a window
manager run no test, as the script expects.

`tests/system`, per test, from `033187ad90` (267 classes, 1,074 tests: 15 failures, 11 errors, 164 skipped) to
this change (268 classes, 1,092 tests: 16 failures, 11 errors, 164 skipped):

| Same result | Skipped on both | New | Fail on both | Fixed | Fail only on this change |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 882 | 164 | 18 | 25 | 1 | 2 |

- New: `SWTextureEdgeSnapshotTest` (18 tests), added after `033187ad90` by US-013.
- Fail on both, with the same messages: 25 tests that need `jfxwebkit`, which this WSL lacks: `test.javafx.scene.web.*`,
  `LocalStorageAccessTest`, `MainThreadTest`, `NullCCLTest` (2), `SceneGraphHTMLEditorTest`, `JSCallbackMemoryTest`,
  `JFXPanelNPETest`, and the five `ModuleLauncherTest.testModuleJSCallback*` tests, whose test app builds a
  `WebView`.
- Fixed: `DrawAfterDeiconifyTest.stageRedrawsAfterDeiconify(StageStyle)[1]`, which failed at `033187ad90`
  ("expected:rgba(0,255,0,255) but was:rgba(0,0,0,255)").
- Fail only on this change, in the full run. Both fail at `033187ad90` too when rerun alone (each rerun is one
  Maven run, on Xvfb under openbox):
  - `DialogRepeatedShowHideTest.dialogSizeOnReShownTest`: see Notes.
  - `TreeTableViewResizeColumnToFitContentTest.resizeColumnToFitContentTest` is flaky at the same rate on both
    trees: "resizeColumnToFitContent failed ==> expected: <true> but was: <false>" in 6 of 15 reruns at
    `033187ad90` and in 8 of 21 on this change, alone and after `TreeTableViewChangeRootTest`, the class before it
    in the full run.

### AC 10 — the reviews

Three review passes covered the GTK glass library from `033187ad90` to this tree: `native-glass/gtk`,
`com.sun.glass.ui.gtk` (with `screencast`), `native/linux.cmake`, and their tests and shims.

| Pass | Diff | Sign-off | Findings, and what each became |
| --- | --- | --- | --- |
| ABI conformance | `033187ad90..26ed4ba2f2` | Approve with findings | Minor: the `GGTK_DND_VALUE_FILES` comment in `glass_gtk_api.h` still gave the index of `033187ad90`. Rewritten to what the C and the Java do (comment only; the library stays byte-identical). Minor: the file-URI index fix. Accepted as a deviation (Notes) |
| Behaviour, threads, upcall exceptions, lifetimes, native necessity | `033187ad90..26ed4ba2f2`, and the AC 9 diff | Approve with findings; the AC 9 diff clean | Minor: a drop ends the drag, and two small C fixes came with it. Accepted as deviations (Notes). Checking this finding showed that the same change can hang a drop-target read: fixed in `glass_dnd.cpp`, with a regression test (Notes). Nit: `GtkCommonDialogs.java` and `GtkSystemClipboard.java` had their first copyright year changed to 2011; restored to `2010, 2026`. Nit: the `GtkGlassNative.getUIScale` Javadoc said that C code still calls the C `getUIScale`; reworded: no production C calls it, and it is kept as the oracle of `GtkRobotParityTest` |
| Final | The uncommitted diff on `26ed4ba2f2`: AC 9 and the fixes above | Approve, no findings | — |

Checked and clean, besides the findings: the 63 `ggtk_*` downcalls and the 4 shim hooks against `glass_gtk_api.h`,
and the 11 `sc_*` downcalls against `screencast_api.h`; the ABI versions and constants; the layouts of the four
callback tables and of the DnD structs; the 144 GTK, GDK, GLib, X11 and libc functions that the facades bind
directly (and 108 in the shims) against the system headers; the 306 `invokeExact` call sites. Every upcall entry
point catches `Throwable`; every slot and GLib callback runs on the thread that iterates the default main context,
as before; the upcall stubs live for the whole run, and the peer registry entries live exactly as the JNI's global
references did.

---

## Notes

### Accepted deviations from `033187ad90`

These came with PR #12 and are kept on purpose. The owner can overrule each one.

- **File URIs in a URI list.** For a URI list read as files (the clipboard's `application/x-java-file-list`, and
  the DnD target), the JNI's `uris_to_java` used a file URI's position in the whole list as its index. A file URI
  after a non-file one therefore landed at the wrong index, or past the end of the array, where it was lost: an
  `ArrayIndexOutOfBoundsException` was reported and the element stayed `null`. It is now stored at its ordinal
  among the file URIs. Pinned by `GtkClipboardNativeTest.readMixedUriList` and `GtkDnDTargetTest`.
- **A drop ends the drag.** A drop, accepted or rejected, ends the in-drag state: `process_dnd_target_drop_start`
  clears `enter_ctx.ctx`, which `033187ad90` left set until the next drag entered. After it, `mimesFromSystem`,
  `popFromSystem` and `supportedSourceActionsFromSystem` throw the `IllegalStateException` of
  `check_state_in_drag`, where `033187ad90` answered from the ended drag. That access is outside the `DragEvent`
  contract ("No dragboard access can happen after" `setDropCompleted`). Pinned by `GtkDnDTargetTest`.
- **Two small C fixes that came with it.** `dnd_target_receive_data` answers `FALSE` when its event hook cannot be
  allocated (`033187ad90` answered `TRUE`), and `dnd_target_get_image` frees a selection block it cannot use
  (`033187ad90` leaked it).

### The reentrant wait in `glass_dnd.cpp`

The clear above could hang the FX thread. `dnd_target_receive_data` runs the main loop until the `SELECTION_NOTIFY`
of its conversion arrives, and `wait_for_selection_data_hook` recomputed the requestor window from `enter_ctx.ctx`
at every event. A drop dispatched inside that wait set the context to `NULL`, so the wait never ended;
`033187ad90`, which left the context set, did not hang there. This change fixes it in `glass_dnd.cpp` (+18/-2; no
ABI or Java change):

- `selection_data_ctx.requestor` keeps the window that `dnd_target_receive_data` converted against, and the hook
  matches and reads against it;
- `dnd_target_receive_data` answers `FALSE` once the drag has ended (`enter_ctx.ctx == NULL`), which happens only
  for a further conversion that the same read starts after such a drop: `gdk_drag_get_selection(NULL)` answers
  `GDK_NONE`, and `gdk_selection_convert` would send nothing.

Unless a drag enters or drops inside a wait, both behave exactly as `033187ad90`. When a drop arrives inside one,
the read whose wait it ends returns its data, as at `033187ad90`; a further conversion of the same read answers no
value, where `033187ad90` converted against the ended drag; and when the drag moves into another Glass window
during a wait, the wait matches the window its own conversion targeted, where `033187ad90` switched to the window
of the new drag.

A regression test pins it: `GtkDnDReentrantDropTest` (5 tests) runs the source and the target in two child JVMs, so
the order of the X events follows the order of each client's requests and nothing depends on timing. (In one
process, GDK hands the target's `XdndFinished` to the source before the source reads the conversion request, which
then goes unanswered whichever C runs.) The first drag pins the captured window, the second the `NULL` check.
Without a window manager and under openbox, the test fails on the unfixed C, which is the library of `26ed4ba2f2`
(5 of 5), without the captured window (5 of 5) and without the `NULL` check (3 of 5, the tests of the second
drag). On the fixed C it passed 10 of 10 repeated runs and both GTK display runs of the final gate.

### A `tests/system` failure that predates this change

`DialogRepeatedShowHideTest.dialogSizeOnReShownTest` failed in the full `tests/system` run of this change ("Dialog
height should remain the same ==> expected: <300.0> but was: <304.0>") and passed in the full run at
`033187ad90`. Run alone, it fails the same way at `033187ad90` (3 of 3) and on this change (3 of 3), so this
change does not cause it. Why the full runs differ was not investigated.

### Verified in CI

Linux aarch64 is built and tested only in CI. Two runs of `submit.yml`, each green in all five jobs; the
`linux_x64_build` and `linux_aarch64_build` jobs run the GTK display tests on a private Xvfb after their build step.
CI runs no `tests/system`.

| Run | Actions run | Commit | Linux x64 job | Linux aarch64 job |
| --- | --- | --- | --- | --- |
| Before | 40502077586 | `c6d6beb38d`, the head of PR #39 | 122181986938 | 122181986775 |
| After | 40584664686 | `fa1461d98e`, the head of PR #40 | 122472839394 | 122472839373 |

PR #39 and PR #40 were squash-merged as `26ed4ba2f2` and `e3b8b21e6d`, each with its head's tree (`5cc7d2add2`,
`929b2beae3`), and no run exists on either merge. Both runs used Temurin JDK 27+35, Maven 3.10.0 on x64 and 3.9.12
on aarch64, and `libgtk-3-0t64` 3.24.41-4ubuntu1.3; the display step's test JVMs recorded GTK 3.24.41 and the X
server `The X.Org Foundation 12101011`. Between the runs the x64 image `ubuntu-24.04` moved from 20261012.334.1 to
20261025.353.1; the aarch64 image `ubuntu-24.04-arm` stayed at 20261012.149.1. In each of the four jobs every step
succeeded except "Upload the GTK display test reports", which runs on failure only and was skipped. No log contains
`hs_err_pid` or `A fatal error has been detected`.

Counts from the Maven logs, run / failures / errors / skipped, the same in the x64 and the aarch64 job of each run:

| Tests | Before | After |
| --- | --- | --- |
| `javafx-graphics` | 25,993 / 0 / 0 / 606 | 26,005 / 0 / 0 / 606 |
| `test.com.sun.glass.ui.gtk`, build step | 34 classes, 31 / 0 / 0 / 0 | 36 classes, 43 / 0 / 0 / 0 |
| Display, no window manager | 34 classes, 210 / 0 / 0 / 8 | 36 classes, 227 / 0 / 0 / 8 |
| Display, openbox | 34 classes, 183 / 0 / 0 / 5 | 36 classes, 200 / 0 / 0 / 5 |

The other modules give the same totals in both runs: `javafx-jslc` 159 / 0 / 0 / 0, `javafx-base`
5,510 / 0 / 0 / 22, `javafx-controls` 9,445 / 0 / 0 / 185, `jfx-incubator-richtext` 186 / 0 / 0 / 0, `javafx-fxml`
123 / 0 / 0 / 3, `javafx-media` 33 / 0 / 0 / 2 and `javafx-web` (`ffm-binding-test`) 194 / 0 / 0 / 0.

In both jobs the per-class lists of the two runs differ only by the two new classes: `GtkConstantsHeaderTest`, 12
tests in the build step and in each display pass, and `GtkDnDReentrantDropTest`, 0 in the build step, as in the WSL
module run, and 5 in each display pass. The build step has 1,202 test classes before and 1,204 after. No class was
removed, and every class both runs have gives the same counts. Every display pass printed `passed`, Maven exit code
0; under openbox the same 5 classes run no test in both runs (`GtkTraceGolden.requireNoWindowManager`). The x64 and
aarch64 jobs give the same lists as each other.

CI skips more display tests than the WSL gate above, on both trees: 8, not 0, with no window manager, and 5, not 3,
under openbox. The display script reports the reasons: trace goldens and expectations captured with GTK 3.24.52,
where the runners have 3.24.41, and, under openbox, expectations captured with no window manager.

---

## Notes carried over from US-001

- **Wayland and X11.** US-001 asked for both to be verified separately. Glass sets `GDK_BACKEND=x11` before GTK
  starts (`GtkGlassNative.Loader.queryLibrary`), so its windows always use GDK's X11 backend, which is XWayland on
  a Wayland session. The one exception is a deployment that renames `glassgtk3` to `libglass.so`, which skips the
  `putenv` as upstream's `GlassApplication.cpp` did. The only Wayland-specific path is the screencast portal
  (`XdgDesktopPortal`), which US-038, US-042 and US-037 cover. The X11 path runs in CI on Xvfb (US-016) and was
  exercised on WSLg's XWayland by US-017.
- **Thread rule.** GTK calls stay on the GTK main-loop thread, as in US-001 risk 1 for AppKit.

---

## Definition of Done

- AC 9 met, and the AC 6 and AC 10 rows above filled in with what was run. Done (2026-10-27).
- Module tests and the GTK display tests green on WSL and in CI, with counts equal to the gate before the change.
  WSL: done (the final gate). CI: done, run 40584664686 against run 40502077586 on Linux x64 and aarch64, equal
  apart from the two new classes ("Verified in CI").
- Changes left in the working tree or a review branch; no commits, merges or PRs without the owner's approval.
  Done: the owner landed it on 2026-10-27 as `e3b8b21e6d`, PR #40.

---

## References

- `033187ad90` — the JNI baseline
- `26ce75d02f` — PR #12, the GTK JNI removal
- `modules/javafx.graphics/native/linux.cmake:135-161` — the `glassgtk3` target
- `modules/javafx.graphics/src/main/native-glass/gtk/constants/` — the hand-kept constants headers
- `modules/javafx.graphics/src/main/native-glass/gtk/glass_gtk_api.h`, `screencast_api.h` — the C ABI
- `modules/javafx.graphics/src/main/native-glass/gtk/glass_dnd.cpp` — `dnd_target_receive_data`,
  `wait_for_selection_data_hook`
- `modules/javafx.graphics/src/main/java/com/sun/glass/ui/gtk/GtkGlassNative.java` — the facade
- `modules/javafx.graphics/src/test/java/test/com/sun/glass/ui/gtk/GtkConstantsHeaderTest.java`,
  `GtkDnDReentrantDropTest.java` — the tests this change added
