# javafx.media JNI removal — status

Branch `ffm/media`. This file records what is done, what is verified, and — importantly — what is
written but **cannot** be verified on this machine. Regenerate the scoreboard at any time with:

```
perl buildtools/ffm-media/verify-no-jni.pl [--verbose] [--json]
```

It exits non-zero while anything remains, and prints "javafx.media is free of JNI." when the total
reaches zero. The baseline column below was measured by running the same script against a
`git archive` of `master`, so the two columns are directly comparable.

## 1. The scoreboard

| Check | On `master` | Now |
|---|---:|---:|
| Java `native` method declarations | 117 | **0** |
| Java references to a JNI-only helper | 0 | **0** |
| C/C++ including `<jni.h>` | 21 | **0** |
| C/C++ naming a JNI type | 567 | **0** |
| C/C++ exporting a JNI entry point (`JNIEXPORT`) | 180 | **0** |
| C/C++ calling back into Java through JNI | 247 | **0** |
| C/C++ defining `JNI_OnLoad` | 3 | **0** |
| Generated JNI headers still included | 27 | **0** |
| Build files requiring the JDK headers | 7 | **0** |
| Tests referencing JNI | 0 | **0** |
| **Total** | **1169** | **0** |

**`javafx.media` is free of JNI**, and the binary proves it: the `jfxmedia.dll` built from this tree
exports **58 `jfxm_*` and zero `Java_*`/`JNI_OnLoad`** (on `master` it exported 55 JNI entry points
and no `jfxm_*` at all). The Linux library builds with **no JDK on the include path at all** — the
CMake `JDK_HOME` input no longer exists.

One caveat on reading the `master` column. The `JNIEXPORT` row is a **grep-hit** count, which is
what the script measures on both sides, and it flatters the consolidation. `FFM-COVERAGE-MAP.md`
§1.1 reconciles it: 49 of those 180 hits are re-declarations in iOS `.h` files, so the real old C
surface is **131 `JNIEXPORT` definitions** against **117 Java `native` declarations**, which
reconcile to **123 distinct entry points**. The honest ratio is therefore **123 old entry points →
58 exports + 22 callback slots + 2 singleton callbacks**, not "180 → 58".

## 1a. Diff size, and the metric nobody had measured

Everything in this section was recomputed from
`git diff --numstat 939aa61eadfb859f8de1c6690d3f5e9da4cef758..HEAD` — **committed history only**.
The working tree currently carries further uncommitted review-remediation edits; they are excluded
here so that every number below is reproducible from that one command. The eight buckets partition
the diff exactly: their file counts and line counts sum to the branch total.

| Scope | Files | Added | Deleted | Net |
|---|---:|---:|---:|---:|
| **Whole branch** | **199** | **+15,026** | **−19,699** | **−4,673** |
| First-party native sources (`.c/.cpp/.h/.m/.mm`) | 99 | +3,307 | −11,136 | **−7,829** |
| Vendored `gstreamer-lite` / `plugins` / `3rd_party` files | 7 | +500 | −71 | **+429** |
| Old build system (`vs_project`, `xcode_project`, all `projects/**` makefiles) | 35 | 0 | −6,853 | −6,853 |
| New CMake build (`native/CMakeLists.txt` + three platform files) | 4 | +2,063 | 0 | +2,063 |
| Java main | 30 | +3,480 | −1,591 | +1,889 |
| Java test (every file new) | 8 | +2,725 | 0 | +2,725 |
| Markdown (these documents) | 10 | +2,415 | −22 | +2,393 |
| Poms, CI workflow, `verify-no-jni.pl`, `addExports` | 6 | +536 | −26 | +510 |

The three rows after the total are exactly the whole of `src/main/native`: 141 files, `+3,807 / −18,060`.

**The headline result, by the measure this fork actually cares about, is that native code fell by
7,400 lines net** — first-party `−7,829` plus vendored `+429`. The fork's goal is *less native
code*; removing JNI is the means, not the end. The raw `+15,026 / −19,699` obscures that completely,
because it nets Java, tests and documentation against native, and until this revision no document in
the branch measured the thing the branch exists to do.

Two supporting figures, both computed rather than asserted:

* **First-party native**: 63 source files deleted (−10,478 lines), 10 added (+2,555), 26 modified
  (+752 / −658). The deletions are the `jni/` tree except `Logger.*`, the whole `platform/ios` tree,
  `GstJniUtils`, `MediaWarningDispatcher`, `LowLevelPerf`, `JObjectPeers`, `JavaUtils`,
  `MTObjectProxy`, `AutoLock`, `Thread.h`, `WinThread`, `WinDllMain`, `NativeVideoConverter`, and
  `GstMedia.cpp` / `GstMediaPlayer.cpp` / `GstPlatform.cpp` — which, once their JNI entry points had
  gone, held a copyright header and no code at all. `jfxm_media_*`, `jfxm_player_*` and
  `jfxm_platform_init` in `ffi/jfxmedia_api.cpp` do that work now.
* **Build system**: 35 files and 6,853 lines of makefiles and IDE projects replaced by **four** CMake
  files totalling 2,063 lines — 21 makefiles (−3,048) and 14 Visual Studio / Xcode project files
  (−3,805), the largest being `project.pbxproj` (−987), `gstreamer.vcxproj.filters` (−875),
  `gstreamer.vcxproj` (−605) and `glib.vcxproj` (−464). That is a maintainability win in its own
  right, and it is deliberately **not** counted in the −7,400: build inputs are not native code.
  The `.def` files, `def-*.pl` and `HeaderGen.java` are kept because the CMake build still uses them.

Two figures here differ from the branch review, and the difference is stated so that nobody
"corrects" them back. The review describes this as *"ten Makefiles → three CMake files"* and puts the
build-system deletion in an *"other"* bucket of **−6,647**. Counted from `git diff --numstat` it is
**21 Makefiles and 4 CMake files**, and **−6,853** for the 35 old build files. The −6,647 figure could
not be reproduced under any bucketing of this diff; the four largest deletions the review names
(`project.pbxproj` −987, `gstreamer.vcxproj.filters` −875, `gstreamer.vcxproj` −605, `glib.vcxproj` −464)
do match exactly. Nothing in the argument changes either way: the old build system is gone and the
replacement is a quarter of its size.

## 1b. The vendored engine trees: two claims, stated separately

`verify-no-jni.pl` skips `gstreamer/gstreamer-lite`, `gstreamer/3rd_party` and `gstreamer/plugins`.
Two things are true of those trees, and an earlier revision of this document ran them together into
a single sentence that was false:

1. **They contain zero JNI tokens.** `FFM-AUDIT-plugins-libs.md` §1 records the evidence: a grep for
   `jni.h|JNIEnv|JNIEXPORT|jobject|JavaVM|jclass|jmethodID|jfieldID|JNI_OnLoad` returns nothing
   anywhere under `gstreamer/`. The exclusion is therefore **legitimate for its stated purpose**, a
   JNI-residue check, and the clean 0/10 run above is not weakened by it.
2. **This branch nevertheless modified seven files in them.** "Contains no JNI" and "is not touched
   by this migration" are two different claims, and only the first one is true.

The branch review lists **six** of these files (`+499 / −70`) and omits the seventh, `marshal.in` —
which is the one it singles out for praise, correctly, as the `glib-genmarshal` input that was
updated in step with the generated files. It lives in a skipped tree and it was modified, so it
belongs in the manifest; the net is `+429` either way. Counted from
`git diff --numstat 939aa61ea..HEAD` over the three skipped directories:

| File (under `src/main/native/gstreamer/`) | Delta |
|---|---:|
| `gstreamer-lite/gst-plugins-good/sys/directsound/gstdirectsoundnotify.cpp` | +232 / −21 |
| `gstreamer-lite/gst-plugins-good/sys/directsound/gstdirectsoundsink.c` | +104 / −17 |
| `plugins/javasource/javasource.c` | +78 / −7 |
| `gstreamer-lite/gst-plugins-good/sys/directsound/gstdirectsoundnotify.h` | +57 / −1 |
| `plugins/javasource/marshal.c` | +21 / −17 |
| `plugins/javasource/marshal.h` | +7 / −7 |
| `plugins/javasource/marshal.in` (the `glib-genmarshal` input) | +1 / −1 |
| **Total** | **+500 / −71** |

Those are logic changes — flow-control returns, atomics, a notificator lifecycle — in third-party
engine code under its own licence, and **the residue script cannot see any of them**. Each is
declared, with its rationale and its observable delta, in `FFM-ABI-CONTRACT.md` §14.2, which doubles
as the patch manifest a future upstream GStreamer merge will need. No other control in this branch
covers vendored changes: when the script prints a clean scoreboard, read it as "no JNI residue", not
as "nothing else changed".

The review-remediation round added further changes to `gstdirectsoundnotify.cpp` (a no-op `DllMain`
as a link-time assertion, and the removal of an `assert` that could abort a debug build); they are in
§14.2 with everything else, and they will move the deltas in the table above when they are committed.

One caveat on reading the zero in the table: the script skips comment lines, and seven comments in
the tree still say "JNIEnv" or "JNI_OnLoad" while recording what a `jfxm_*` function replaced. That
is deliberate provenance, not residue.

### Why the Linux cross-check earned its keep

Deleting `<jni.h>` broke the build in a way only another compiler could see. `Utils/Singleton.h`
uses `NULL` but never included a header defining it — it had been arriving transitively through
`jni.h`. MSVC still found it via another include and compiled happily; **GCC 15.2 failed** with
`'NULL' was not declared in this scope`. Fixed with an explicit `#include <stddef.h>`. Nothing about
Windows-only verification would have caught it, which is exactly why the unverified macOS paths in
§4 matter.

## 2. The design

`FFM-ABI-CONTRACT.md` is authoritative: the 58-function `jfxm_*` C ABI, the three callback tables,
handle ownership, arena lifetimes, the thread contract per callback slot, and the decisions —
one facade (`JfxMediaNative`), one handle type shared by the GStreamer and AVFoundation backends,
iOS deleted rather than migrated, migration behaviour-neutral with deletions in separate changes.
When the contract and `src/main/native/jfxmedia/jfxmedia_api.h` disagree, the header wins; §14 of
the contract records the deviations the implementation declared.

The evidence behind it is the five-slice read-only audit, kept alongside as
`FFM-AUDIT-{core-jni,gst-platform,osx,ios,plugins-libs}.md`. Two of those (`core-jni`, `ios`) are
the auditors' persisted working notes rather than formatted reports — their runs were cut off by a
usage limit after the substance was on disk.

## 3. What is verified, and by what

| Artefact | Verification |
|---|---|
| The CMake port of the media native build (`native/CMakeLists.txt`, `win.cmake`) | Windows configure + build return 0 in Release **and** Debug (MSVC 19.44). Before the ABI was added, the built `jfxmedia.dll`, `gstreamer-lite.dll`, `glib-lite.dll` and `fxplugins.dll` had export name-sets **identical** to the prebuilt JNI-era libraries in `../caches/sdk/bin`, and identical dependent-DLL sets |
| `native/linux.cmake` | Built in WSL (Ubuntu 26.04, gcc, Ninja) from the **unmodified** file after `libasound2-dev` was installed: configure 0, build 0, zero errors. `libgstreamer-lite.so` links `libasound.so.2`; `libjfxmedia.so` links the system GLib and `libgstreamer-lite.so` and has **no `libjvm` dependency**. Re-run after the review fixes: still 0 errors, 0 warnings from `ffi/`, and `jfxm_event_player_state` present. `avplugin` was skipped in that run (no ffmpeg dev packages then); it has since been built - see the row below. **No export count is quoted here, deliberately.** An earlier revision of this row said `libjfxmedia.so` exports "54 `jfxm_*` + 54 `Java_*` + `JNI_OnLoad`" — a measurement taken while both ABIs were compiled side by side on purpose (migration playbook §7 step 1), left standing in a document whose headline claim is zero JNI, and consequently read by a reviewer as evidence that JNI survived. The Linux library has not been re-measured since the JNI half was deleted; the Windows row below is the current measurement, and a fresh `nm -D --defined-only libjfxmedia.so` on a Linux build is an outstanding item |
| `avplugin` on Linux (the optional libav plugin) | **Built for the first time on any machine available to this project.** A full media build in WSL against a locally unpacked **ffmpeg 8.0.1**: configure rc=0, build rc=0, `libavplugin.so` produced with `gst_plugin_desc` exported. The branch review recorded `avplugin` as never built anywhere, which was correct when written. The dependency split is the load-bearing detail and is asserted rather than assumed: `libavcodec`/`libavformat` are compile **and** link dependencies (`DT_NEEDED ...so.62`), `libavutil` is compile-time by header and resolves transitively at load, and **`libswscale` is header-only** - `videodecoder.c` includes its header but `dlopen`s the library at `:455`, so the plugin carries zero undefined `sws_*` symbols and no `DT_NEEDED` for it. `linux.cmake` checks it with a plain `pkg_check_modules`, never an `IMPORTED_TARGET`, so no `-lswscale` can reach the link line; linking it would convert a recoverable runtime capability check into a hard load-time failure. The Linux CI jobs now install the three `-dev` packages, fail with an `::error::` if `libavplugin.so` is missing, and assert that `libswscale` is absent from its `DT_NEEDED` list |
| `native/mac.cmake` | **Compiled and linked in CI on both macOS architectures, on every push** - `macos_x64_build` and `macos_aarch64_build` run a full `mvn install` with no `-DskipNative`, and `jfxmediaAvf` is in the default target; the most recent run at the time of writing is `34040031100` on `bb80b059e2`, all five jobs `success`, with `Built target jfxmediaAvf` in the aarch64 log. Because `add_media_library` uses `SHARED` with the default `-undefined error`, a clean link there is real symbol-level evidence - see section 4. **Not compiled on this machine, which is Windows with no Xcode**, so the checks below are what was possible locally and they still stand: Statically verified three ways: the source set matches the four mac makefiles exactly (355 makefile sources, 359 listed, difference = the four intended `ffi/*.cpp`); every `-D` and `-framework` is present per target; a scratch project including the file configures **and generates** with RC 0 for arm64 and x86_64 |
| `jfxmedia_api.h` + `ffi/*` (the C ABI) | Compiles clean (no new warnings) into `jfxmedia.dll`; header compiles standalone as C at `/W4`. The surface is counted three ways in the current tree and is consistent 1:1: **58** `JFXM_EXPORT` declarations in `jfxmedia_api.h`, **58** definitions in `ffi/jfxmedia_api.cpp` (zero declared-not-defined), **58** handles bound in `JfxMediaNative.java` (zero bound-not-declared, zero declared-not-bound) — and the built `jfxmedia.dll` carries **58** `jfxm_*` names, zero `Java_*` and no `JNI_OnLoad`. It was 56 until this review round added `jfxm_offsetof_player_callbacks` and `jfxm_offsetof_stream_callbacks` (finding **S1**), which is also what moved `JFXM_ABI_VERSION` from 3 to 4. Stating all three counts is the point: 1:1 consistency is a measurement here, not an assertion |
| The band-pair lifetime fix (review finding 1) | A live smoke test against the built DLL builds a real GStreamer pipeline, takes a real `CGstAudioSpectrum`, and proves pair A is **not** released when pair B replaces it but only when the holder dies, and pair B at `jfxm_media_dispose` — plus release-exactly-once on the NULL-spectrum, NULL-pair and NULL-callback paths (26 checks, all pass) |
| The `SetBands` ownership fix (review round 2, finding 2) | `CAudioSpectrum::SetBands` had no stated owner for the reference it is handed, and the two implementations read it differently: `CGstAudioSpectrum` consumed it, `AVFAudioSpectrumUnit` added one of its own. On AVF that pinned every pair at one reference for ever, so `release` never ran and the facade's registry entry and arena were held for the life of the JVM - a leak this branch introduced, since the contract promises `release` runs exactly once per `set_bands` call. The contract is now written on `SetBands`, `CGstAudioSpectrum` `AddRef`s (net reference count unchanged, so GStreamer behaves exactly as before), the AVF destructor releases what it still holds, `CNullAudioSpectrum` conforms, and the shim ends with one `ReleaseRef` on every path. Verified on Windows: `jfxmedia` rebuilds clean, and `mvn -o -pl modules/javafx.media test -DskipNative=true` against the rebuilt DLL is `Tests run: 20, Failures: 0, Errors: 0, Skipped: 0` - zero skips, so the live-player test really did build a GStreamer pipeline rather than abort. `JfxMediaNativeTest` now pins the registry to its steady state across repeated `setBandCount` calls: it sits at exactly 3 entries and stays there, so each `set_bands` registered one handover and released exactly one superseded one, synchronously, on the calling thread. A second, hardware-free test drives `jfxm_spectrum_set_bands` with a NULL spectrum - the branch that used to `delete` the holder and now shares the common `ReleaseRef` - and asserts the pair comes back exactly once and its registry entry with it. Five consecutive runs, no flakes. **The AVF half is not compiled here** (section 4) |
| Struct layouts | Measured with a C program built by `cl.exe` x64: `sizeof(JfxmFrameInfo)` 120, `JfxmPlayerCallbacks` 104, `JfxmStreamCallbacks` 72; `JfxmFrameInfo` offsets 0, 8, 12, 16, 20, 24, 28, 32, 36, 40, 56, 88 — and `jfxm_offsetof_frame_info` returns the same values from the DLL. **A `sizeof` check cannot cover the two callback tables** (review finding **S1**): every slot is a pointer, so any permutation of the slots has the same `byteSize`, and the Java `StructLayout`s are generated from the Java slot arrays, so the two sides agreed *by construction* rather than by comparison. `jfxm_offsetof_player_callbacks` and `jfxm_offsetof_stream_callbacks` were added for exactly that, and `JfxMediaNativeTest.callbackTableSlotOffsetsMatchTheCompiledStructs` now asserts the Java layout offset against the C compiler's `offsetof` for all 13 + 9 slots, plus `-1` at both out-of-range ends. A scratch Win32 probe over the built DLL reports player offsets 0, 8, … 96 and stream offsets 0, 8, … 64, strictly increasing by 8 with no duplicates. **Residual, recorded rather than hidden:** C exports an *index*, not a slot *name*, so reordering `PLAYER_SLOTS` on the Java side moves both sides of every comparison together and stays green. What the test does pin is that C's index enum agrees with C's struct order — the edit (move a member, forget to renumber the enum) that silently redirects every upcall while `sizeof` stays 104/72 |
| The C ABI end to end | A native smoke program driving the built DLL: **ABI version 4** (this row said "ABI version 1" for three revisions after the constant had moved — the library answers 4 today, and `jfxm_abi_version` is the one thing a stale library cannot fake); log sink receives level + message + `user`; `jfxm_platform_init` idempotent; NULL-handle calls return `ERROR_MEDIA_NULL`; a real GStreamer `jfxm_media_create` invoked `property`/`is_random_access`/`is_seekable`/`need_buffer` synchronously, `jfxm_player_init` succeeded, the pipeline called `read_next_block`, delivered `media_error` through the callback table with the right `user`, called `close_connection`, and `jfxm_media_dispose` tore down cleanly |
| CI package list | `.github/workflows/submit.yml` Linux jobs gained `libasound2-dev` - without it the media configure step fails on the ALSA sink - and, in this review round, `libavcodec-dev libavformat-dev libswscale-dev` so that `avplugin` is built rather than silently skipped. That addition also uncovered a build break the CMake port had introduced and nothing could hit until the optional dependency arrived: `-Werror=deprecated-declarations` had been put in the **shared** compile-option list, but the retired avplugin makefile is the one of the four that pointedly omits it, because the `av` wrappers call `av_init_packet` (deprecated since libavcodec 59). Installing the packages without fixing that would have turned **both Linux jobs red**. It is now a per-library option carried only by `gstreamerLite`, `fxplugins` and `jfxmedia`, as the makefiles had it |
| The module, end to end, JNI-free | `mvn -o -pl modules/javafx.media install` (**with** the native build): HeaderGen → CMake → all four libraries → **`Tests run: 15, Failures: 0, Errors: 0, Skipped: 0`** → jar installed → BUILD SUCCESS. `jfxmedia.dll` exported `jfxm_*` only, zero `Java_*`; the Linux rebuild the same. (Both the test count and the export count are historical: the suite has since grown to 31 tests and the ABI to 58 functions — see the C-ABI row above. Treat the run as evidence that the module builds and tests JNI-free, not as a current count.) No cmake unused-variable warning, and no restricted-method warning |
| Old → new entry-point coverage (`FFM-COVERAGE-MAP.md`) | The one control the review said was missing. Every entry point that existed on `master` is listed once and given exactly one disposition: **123 distinct old entry points → 76 COVERED, 47 DELETED, 0 UNACCOUNTED**. The point of the exercise is the guard column: **no validation guard was lost** — every NULL check, range check, state check and error-return convention on the 76 surviving entry points traces to a named site on the new path, and **six are strengthened** (`CopyBlock`'s formerly unbounded `memcpy`; the pipeline-NULL checks `gstGetAudioEqualizer`/`gstGetAudioSpectrum` lacked; `nativeSetBands`' unvalidated count and buffers; NULL out-pointer tests JNI did not need). This is what upgrades the security result from *"nothing was removed from surviving code paths"* — which is near-vacuous at this consolidation ratio, because a guard whose containing function ceased to exist is not diff-visible as a removal — to *"no capability lost a guard, by source inspection"*. Two lost **error reports** are recorded there and both argued down to INFO: **F1**, `ERROR_MANAGER_LOGGER_INIT` (master's raise site sat in a `<clinit>` where it produced `ExceptionInInitializerError`, never a listener callback), and **F2**, the library-load failure, whose listener-visible signal survives relocated to `GSTPlatform.loadPlatform()` — which runs late enough for a listener to exist and also catches `UnsatisfiedLinkError`, which the old `catch (Exception)` did not. Source evidence only: nothing in it has played a media file |
| The tests have teeth | Every new behavioural test was mutation-checked. Reverting the `NativeMediaManager` fix fails both degradation tests; letting a checked exception out of an upcall target is refused by `Linker.upcallStub` at install time; replacing the `catch (Throwable)` with a rethrow kills the surefire fork outright — which is exactly the invariant the rule exists to protect. The two spectrum guards added for review round 2 were mutation-checked the same way: reinstating the `bands.set(Bands.NONE)` the error path used to do fails the rejected-count guard (expected 64, was 0), and expecting one more registry entry than the steady state fails (expected 4, was 3). Stopping the facade from handing C a release function at all - "C never calls release", the AVF failure this round fixes - fails the NULL-spectrum guard (1 expected, 0 released) and drives the live-player guard to 7 entries against 4 expected, exactly +1 per `setBandCount`, which is the leak signature. Dropping the `unregister` from `onRelease` fails only the registry half, and registering a handover for a null release action fails only the null-action half, so no assertion is carrying another one's weight |

## 4. What is NOT verified here

* **macOS / the AVFoundation backend — the "never compiled" concession this section carried for
  several revisions is false, and the correction is worth more than the deletion.** That sentence was
  repeated by the branch review, which caveated its entire macOS section on it (*"has never been
  compiled anywhere, CI included"*) and treated every AVF finding as unverifiable. It was checked
  against GitHub in this round and it does not hold.
  * **CI compiles and links the AVF backend on every push, on both macOS architectures, and has done
    since 2026-09-04.** `gh run list --branch ffm/media` shows "JavaFX pre-submit tests" on every
    push, all `success`. The latest at the time of writing is run `34040031100` on HEAD
    `bb80b059e2`, 2026-09-06T14:42:52Z, 19m35s, success, with all five jobs green: Linux x64, Linux
    aarch64, Windows x64, **macOS x64** and **macOS aarch64**. The macOS aarch64 log shows
    `Building OBJCXX object CMakeFiles/jfxmediaAvf.dir/.../avf/AVFMediaPlayer.mm.o`, then
    `AVFAudioEqualizer.cpp.o`, `AVFAudioProcessor.mm.o`, `AVFAudioSpectrumUnit.cpp.o` and
    `AVFSoundLevelUnit.cpp.o`, then `Linking CXX shared library .../libjfxmedia_avf.dylib` and
    `Built target jfxmediaAvf`. Those jobs run a full `mvn -B -ntp -fae install` with no
    `-DskipNative`, and `mac.cmake` declares `jfxmediaAvf` through `add_media_library` with no
    `EXCLUDE_FROM_ALL`, so the backend is in the default target by construction.
  * **What that positively establishes, which is stronger than the absence of a false sentence.**
    `add_media_library` uses `SHARED` with the default `-undefined error`, so **macOS is the one
    platform where a dangling symbol is fatal at link**. The review identified exactly this and said
    a clean link would be meaningful evidence *"if one had ever happened"*. It has — on both
    architectures, on every push, repeatedly. Every `jfxm_*` and every `CPlayerEventDispatcher`
    vtable entry `libjfxmedia_avf.dylib` reaches for in `libjfxmedia.dylib` resolves, or the link
    would have failed. That is real **symbol-level** evidence for the AVF backend, including for the
    cross-dylib boundary that §14.3 of the contract calls the fragile one, and it should be read as
    such. CI now additionally asserts the dylib exists and dumps `otool -L` and `nm -gU` for it.
  * **The honest limits are narrower than before and still matter.**
    * **The dispose-fencing changes made in this review round are uncommitted, so they have never
      been compiled by anything.** They are not in run `34040031100` or in any other. That is the
      real residual risk, and it is what the next push resolves. This machine is Windows with no
      Objective-C toolchain and no Foundation or AVFoundation SDK, so not even `-fsyntax-only` was
      possible here; those edits were checked by re-reading both methods, by a brace/paren balance
      check against `HEAD`, and by `git diff -w` showing the `-extractTrackInfo` re-indent removed no
      line. Syntax risk is low and non-zero.
    * **No macOS playback has ever been exercised.** Compiling and linking is not running. Every
      behavioural macOS finding in the review remains a static trace, the `MediaPlaybackTest` audio
      path has only ever been executed on Windows x64, and `CVDisplayLinkStop`'s joining behaviour
      still needs a Mac to settle - which is why **HIGH-M1 remains open on the display-link path**
      while the KVO, track and main-queue paths are fenced (contract §7.1).
    * **The distinction to keep:** on macOS, **symbol-level correctness is now evidenced; behavioural
      correctness is not.**
* **Playback — this is now covered, and the review finding is closed rather than qualified.** The
  branch review's MEDIUM-T1 was *"nothing in the fork ever plays media"*, and it was true when
  written. `modules/javafx.media/src/test/java/test/com/sun/media/jfxmediaimpl/MediaPlaybackTest.java`
  now plays one, in the automated suite. `SineWav.java` generates the fixture programmatically —
  16-bit signed mono PCM, 8 kHz, a 440 Hz tone at −20 dBFS — so no binary asset is committed,
  following the `TinyWav.java` idiom beside it; `AudioOutput.java` and `MediaThreads.java` are the
  shared helpers.
  * `aGeneratedToneIsDecodedAndPlayedToTheEnd` asserts, all behind a bounded `onReady` latch: Ready
    arrives; duration **1.000 s ± 1 ms**; exactly one `AudioTrack`, PCM, 1 channel, 8000 Hz, channel
    mask `FRONT_CENTER`; Playing then Finished **in that order**; zero `media_error` and zero `halt`;
    **at least one `audio_spectrum` event with a band above the −60 dB floor**; and registry size and
    event-queue thread count back to baseline.
  * `playerOverATinyWavFileDisposesWithoutLeaks` — the test the review singled out for asserting
    nothing — now asserts event-queue-thread count back to baseline under a bounded wait, total JVM
    threads no more than baseline + 10 after 20 playbacks, and registry size back to baseline.
  * **Two things make this more than a smoke test, and they are why it is load-bearing.**
    * **The spectrum assertion separates "the callback arrived" from "samples were decoded"** —
      which is exactly the **S1** failure mode, a callback landing on the wrong function pointer. A
      decoder emitting the right number of *zero* buffers would satisfy every other assertion here.
      It was verified to actually fail rather than assumed to: zeroing the tone amplitude produces
      *"every band stayed at the −60.0 dB silence floor across 7 spectrum events"*.
    * **With no audio device there is no player at all, so the no-device path is not a bare skip.**
      The sink opens its device on the synchronous `NULL → READY` leg inside
      `CGstAudioPlaybackPipeline::Init`'s `set_state(PAUSED)`, so a failure there returns
      `GST_STATE_CHANGE_FAILURE`, the constructor throws, and `createMediaPlayer` returns null —
      there is no partial playback to salvage. Three assertions therefore run **before** the abort:
      that `createMediaPlayer` returned null rather than throwing, that the log says why, that the
      registry is exactly where it was found, and that no event-queue thread was left live.
  * **Coverage, stated precisely — this replaces the review's blanket claim.** Linux x64 and aarch64
    run and assert in full: `submit.yml` writes a null `~/.asoundrc`
    (`pcm.!default { type null }`), so the sink always opens. Windows and macOS skip **only** when the
    runner has no device, and the skip message names exactly which slots go uncovered (`state`,
    `audio_track`, `duration_update`, `audio_spectrum`), what is still covered, and which jobs do
    cover them. 28 of 33 tests run everywhere regardless. The suite is **33 run / 0 failures /
    0 errors / 2 skipped**, the two skips being the pre-existing macOS-only
    `OSXLocatorStreamOwnershipTest` cases. That 33 is verified two ways: a full run, and a count of
    `@Test` declarations across the whole module test tree - `HLSConnectionHolderTest` 2,
    `JfxMediaNativeTest` 23, `MediaPlaybackTest` 2, `NativeMediaManagerDegradationTest` 3,
    `NativeVideoBufferOwnershipTest` 1, `OSXLocatorStreamOwnershipTest` 2. Note for anyone repeating
    the count: `HLSConnectionHolderTest` lives in `test.com.sun.media.jfxmedia.locator`, not in
    `test.com.sun.media.jfxmediaimpl` with the other five, and counting only the latter gives 31.
  * **Honest limits, recorded alongside.** Only **Windows x64** has actually been executed; the
    no-device branch was proven by injecting a canned null-player result, not by removing a real
    device; and the build now **emits one second of a quiet tone** on any machine with a sound card,
    because muting is not portable — Linux inserts the `volume` element before the spectrum, Windows
    and macOS after it. Video playback, and playback on macOS and Linux hardware, remain unexercised.
  * *Superseded, kept for the record:* before this test landed, media had been played through the new
    ABI exactly once, by hand — a generated 1-second 440 Hz WAV on Windows reaching `onReady`
    (1000 ms, 1 track) → `onPlaying` → `onEndOfMedia` → `dispose()`, plus 20 `AudioClip.play()`
    cycles exercising the untimed join (contract §14.2) on the FX Application Thread, with no hang, no
    crash and no `hs_err_pid`. That run is what the automated test was built from.
* **Linux `avplugin` — built for the first time, and now covered by CI.** This bullet used to say it
  "stays skipped". It no longer does: a full media build in WSL against a locally unpacked
  **ffmpeg 8.0.1** configured and built with rc=0, producing `libavplugin.so` with `gst_plugin_desc`
  exported. That is the first time `avplugin` has been built on any machine available to this project
  — the branch review recorded it as never built anywhere, and that was correct when written. The CI
  Linux jobs now install `libavcodec-dev libavformat-dev libswscale-dev` and fail with an
  `::error::` if `libavplugin.so` is absent, so a silent skip can no longer pass for a green build.
  * **The libav dependency split is load-bearing; getting it wrong reintroduces a regression.**
    `libavcodec` and `libavformat` are compile **and** link dependencies (`DT_NEEDED` on `...so.62`).
    `libavutil` is compile-time by header only and resolves transitively at load. **`libswscale` is
    header-only**: `videodecoder.c` includes `<libswscale/swscale.h>` but `dlopen`s the library at
    `:455`, so the built plugin has zero undefined `sws_*` symbols and no `DT_NEEDED` for it.
    `linux.cmake` therefore checks it with `pkg_check_modules(JFXM_SWSCALE libswscale)` and
    deliberately **not** as an `IMPORTED_TARGET`, so no `-lswscale` can reach the link line. Linking
    it would turn a recoverable runtime capability check into a hard load-time failure — the
    regression the review's R4 turned on. CI asserts the absence explicitly.
  * **A latent build break this exposed, which is the genuinely interesting part.** The CMake port had
    put `-Werror=deprecated-declarations` in the **shared** option list. The retired avplugin makefile
    is the one of the four that pointedly omits it, because the `av` wrappers call `av_init_packet`,
    deprecated since libavcodec 59. Installing the ffmpeg packages without noticing that would have
    turned **both Linux CI jobs red** the moment `avplugin` started building — a break introduced by
    the port and hidden until the day the optional dependency arrived. It is now a per-library option
    carried only by `gstreamerLite`, `fxplugins` and `jfxmedia`, exactly as the makefiles had it.

## 4a. Pre-existing bugs found while migrating, and deliberately NOT fixed

Behaviour-neutrality outranks tidiness, so these were left exactly as they are. Each is upstream
OpenJFX behaviour, not something this branch introduced; each deserves its own change (and probably
its own JBS issue) with a test.

**Read this table as a to-do list, and keep it honest in both directions.** For three revisions it
listed two `GstAudioSpectrum.cpp` defects as unfixed *after the branch had fixed them* — which is
wrong in the safe direction for the code and the unsafe direction for a reviewer, because it invites
someone to "fix" what is already fixed and to distrust a table whose other rows are accurate. Both
have been moved out; they are now recorded as declared departures in `FFM-ABI-CONTRACT.md` §14.1,
verified at HEAD:

* the **NULL-holder dereference** in `CGstAudioSpectrum::UpdateBands` is guarded —
  `if (holder == NULL) return;` immediately after the `AddRef`, `GstAudioSpectrum.cpp:129-130`
  (master dereferenced the `AddRef` result unconditionally);
* the **non-atomic `SetBands` swap** now runs under `m_BandsLock`, `GstAudioSpectrum.cpp:101-119`,
  with the matching read-and-retain under the same lock in `UpdateBands` at `:124-126` and the
  `ReleaseRef` deliberately outside it (master used a bare `g_atomic_pointer_get` /
  `g_atomic_pointer_set` pair, which cannot make the read and the `AddRef` one step).

| Where | What | Effect |
|---|---|---|
| `platform/osx/avf/AVFAudioSpectrumUnit.cpp:191` | `mBands->UpdateBands(size, magnitudes, magnitudes)` — the third parameter is `phases` | On macOS/AVFoundation an `AudioSpectrumListener` receives **phases identical to magnitudes**. Verified by reading the call against `CBandsHolder::UpdateBands(int, const float*, const float*)` |
| `jni/NativeVideoBuffer.cpp` (`nativeGetPlaneStrides`) | returns `null` for `count < 1` **and** for `count > 4` | Not a bug as such, but the FFM `JfxmFrameInfo` reports `[0,0,0,0]` instead, so the Java side has to reproduce the `null` explicitly — recorded in contract §14.1 |
| `Utils/win32/WinDllMain.cpp` (now deleted) | declared a **two**-parameter `DllMain(DWORD, LPVOID)`; the CRT calls `DllMain(HINSTANCE, DWORD, LPVOID)`, so `dwReason` actually received the module handle and matched neither `DLL_PROCESS_ATTACH` nor `DLL_PROCESS_DETACH` | `OnDllLoad`/`OnDllUnload` never ran. Both were empty, and the CRT's default `DllMain` returns TRUE, so deleting the file is behaviour-neutral |
| `jni/Logger.cpp` `logWarningMsg` | both overloads log at `LOGGER_DEBUG`, not `LOGGER_WARNING` | Every `LOGGER_WARNMSG` in the media natives is emitted at DEBUG level. Unchanged by this work |
| iOS `MediaPlayer.m` (now deleted) | seven overlay setters returned an uninitialised `jint result` when the player was in its initial state; `jlong_to_ptr` cast through `int` (pointer truncation on arm64); `EventDispatcher`'s global ref never released because nothing calls its `dispose` | Moot — that platform was unreachable and is deleted (see contract §1 decision 3), but worth knowing if anyone restores it from history |

## 4b. Is this C ABI a scaffold or the terminal shipping boundary?

**It is a scaffold.** The question was raised in review as the one unanswered thing that leaves
several severity ratings unstable, and it deserves a plain answer in the document reviewers are
pointed at.

This fork's goal is to **shrink the amount of native code in OpenJFX**, keeping native only where a
platform, driver, codec or engine genuinely requires it. Removing JNI is the *means*: JNI forced
every native library to be JVM-aware and forced a hand-written C wrapper around every OS call,
because only C could speak `JNIEnv`. The 58-function `jfxm_*` ABI is what that surface looks like
after the glue has gone — it is an **intermediate boundary**, not a shipping contract this project
intends to freeze. The next moves against it are the ones the migration playbook's §1.1 triage
already names: `LOW-T5`'s three constant-mirror helpers (`jfxm_event_player_state`,
`jfxm_audio_track_channel`, `jfxm_log_level`) are `PURE` switches over constants and belong in Java;
a pure-Java platform for lightweight audio formats needs no ABI at all; and `Utils/ColorConverter.c`
stays only until someone measures its SSE2 path against a Java port.

The consequences for how findings against the ABI should be weighted:

* **A flat, documented, versioned ABI of 58 functions is far better than 180 JNI entry points** — as
  an intermediate state it is the *point*, not a compromise. Judged as a terminal boundary it would
  deserve much more alarm than it gets here.
* **`S1` and the untested ABI functions are transient liabilities, not permanent ones.** They are
  real (which is why `jfxm_offsetof_player_callbacks` / `_stream_callbacks` and their test landed
  anyway), but their cost is bounded by how long the surface survives, and the surface is meant to
  shrink.
* **What does *not* get discounted by this answer:** anything that can corrupt or crash at runtime
  today — a wrong slot, an upcall into a closed arena, a leak keyed to a hot path. A scaffold still
  has to hold weight while it is standing.

## 4c. Operability: reading a failure that crosses the FFM boundary

Nobody had checked this, and for anyone who has to run JavaFX in production it may matter more than
any single finding above. **This section is written from reading the code, not from an observed
crash** — no FFM-boundary failure has been provoked or captured in this fork. Treat it as the map to
check against the first real one, not as a field report.

The thing that changed: **JNI stack traces crossed the boundary with frames on both sides.** A crash
inside `Java_com_sun_media_jfxmediaimpl_NativeMediaPlayer_gstPlay` showed a named C symbol in the
`hs_err` native frames and a matching Java frame above it. FFM's frames are not symmetrical, and
three failure shapes look quite different from each other.

**1. Downcall into a closed arena, or a closed-arena segment — `IllegalStateException`.** This is
the friendly one and the one to hope for. It is a normal Java exception with a normal Java stack
trace: `java.lang.IllegalStateException: Already closed` thrown from inside `MemorySegment`/
`MethodHandle` machinery, with `com.sun.media.jfxmediaimpl.JfxMediaNative.<something>` immediately
below the JDK frames and the real caller below that. Nothing native has been entered yet. If a
dispose-ordering bug is caught here, the trace names the offending call directly. `jfx-ffm-testing`
deliberately asserts on this shape for use-after-free.

**2. Upcall into a *closed* arena — SIGSEGV on an unmapped stub, and this is the hard one.** Closing
an `Arena.ofShared()` unmaps the upcall trampolines it owns. Native code that still holds one of
those addresses — a `JfxmPlayerCallbacks` slot copied by value into a C++ dispatcher, say — jumps to
an address that is no longer mapped. The `hs_err_pid*.log` will show:

* `SIGSEGV` / `EXCEPTION_ACCESS_VIOLATION` with `pc` in an address range belonging to **no library**
  (`[error occurred during error reporting]` or a bare hex `pc` with no `C  [libfoo.so+0x...]`
  attribution), because the stub's page is gone;
* native frames naming the *caller* — `jfxmedia.dll`, `gstreamer-lite.dll`, `libjfxmedia_avf.dylib`,
  or an OS frame such as an AVFoundation KVO thread or the DirectSound apartment thread — and then
  nothing above them;
* **no Java frames at all**, and often "Java frames: (not present)" or a thread the JVM reports as
  `_thread_in_native`.

That is the diagnostic cost of the migration in one paragraph: the failure names the native caller
and the thread, and says nothing about which Java object's arena was closed. The compensating
information is on the *other* side of the same problem — every callback slot is registered by a
Java-assigned id (`void* user`), so the C caller's `user` value, if it can be read from the crash
dump or from the preceding log lines, identifies the peer. **The practical advice is to read the
lines before the crash, not the crash**: `JfxMediaNative` logs every registry install and release
through `com.sun.media.jfxmedia.logging.Logger`, and a stub-lifetime bug is nearly always visible as
a `close`/`unregister` that ran before the last callback arrived.

**3. An exception thrown inside an upcall target — contained, logged, never a crash.** All 24 upcall
targets (13 player slots, 9 stream slots, `onRelease`, `onLog`) catch `Throwable`. Twenty-three of
them funnel into `JfxMediaNative.logUpcallFailure`, which emits
`Logger.ERROR "JfxMediaNative" <slot> "upcall failed: <throwable>"` and returns the slot's documented
failure value (`0` for the player slots, the per-slot default for the stream slots). `onLog` is the
exception and swallows silently, because it *is* the log sink and has nothing to report to. So:

* **`upcall failed:` in the media log is the single string to grep for.** It names the slot, which
  names the boundary, and the value C received is whatever that slot documents as failure.
* This is deliberate and load-bearing: an exception escaping an upcall stub terminates the JVM
  outright, with a `hs_err` of shape 2 and no useful Java context. The suite mutation-checks it —
  replacing a `catch (Throwable)` with a rethrow kills the surefire fork.
* The containment is not free of consequence. A `new_frame` slot that returns 0 reports "the Java
  target threw" and nothing else; the frame is then *not* freed by C (deliberately — see the
  frame-ownership invariant in `FFM-ABI-CONTRACT.md` §14.3), so a storm of `upcall failed: new_frame`
  lines is also a storm of leaked frames.

**What to ask for when a real one arrives**, since none of the above has been observed: the full
`hs_err_pid*.log` (not just the summary), the media log at `Logger.DEBUG` for the seconds before it,
and whether the crashing thread is an FX thread, a GStreamer streaming thread, an AVFoundation KVO
thread, the CVDisplayLink thread, or the DirectSound apartment thread — because for shape 2 the
thread is most of the diagnosis.

## 5. Rules this work follows

* Migration changes are behaviour-neutral, **and every departure from that is declared rather than
  silent**. `FFM-ABI-CONTRACT.md` §14.1 is the register for behaviour departures, §14.2 for the
  vendored-tree patches and §14.3 for the cross-language invariants the code depends on; §4a above
  is the register for pre-existing defects deliberately left alone. The rule this branch broke was
  not "change nothing" — the owner's position is that a change which genuinely fixes a bug is
  welcome, "in the spirit of making it better" — it was **"write down what you changed"**. The
  governance defect the review found was the silence, not the changes; nothing has been reverted and
  nothing split out, and the registers have been filled in instead.
* Deletions (the iOS platform, dead glue such as `GstJniUtils`, `MediaWarningDispatcher`,
  `LowLevelPerf`, `NativeVideoConverter.cpp`) are listed in contract §12 and land separately.
* No C is reimplemented in Java: the audit found no `PURE` function that clears the parity gate and
  is not already a data carrier. `Utils/ColorConverter.c` is `PURE-HOT` with `PARITY: unknown` and
  stays native until someone measures the SSE2 path against the generic C path.
* Nothing in this branch is committed by the automation. The user commits.

## 6. Proposed follow-up: generate this document's counts instead of typing them

Not implemented here — it is a build change and needs the build owner. Recording it because it is the
durable fix for the largest single category of defect this branch's review found.

Documentation staleness was the **most frequent** review category, and three of the six HIGH findings
raised by specialist reviewers traced back to someone trusting a stale sentence in this file. Every
one of the five load-bearing errors was a **number or a state that had moved on**: a file/line
scoreboard from an earlier commit, an ABI version, a function count, an export listing captured
mid-migration, and two defects listed as unfixed after they were fixed. None was a wrong argument;
all were arithmetic that nothing re-ran.

**Proposal.** Add a build step that regenerates §1a's table — and the ABI function count — from the
repository rather than from memory:

* the diff table from `git diff --numstat <merge-base>..HEAD`, bucketed exactly as §1a buckets it,
  with a check that the buckets sum to the branch total (they do today; that check is what makes the
  table trustworthy);
* the three ABI counts from the sources themselves: `JFXM_EXPORT` declarations in `jfxmedia_api.h`,
  definitions in `ffi/jfxmedia_api.cpp`, and bound symbols in `JfxMediaNative.java` — the same three
  numbers §3 now quotes, so a divergence between them fails the build instead of surfacing as a
  reviewer's HIGH finding;
* `JFXM_ABI_VERSION` read from the header, so no document can quote a stale one.

`buildtools/ffm-media/verify-no-jni.pl` is the natural home and already has the shape for it (it
walks the module, has a `--json` mode, and exits non-zero on a discrepancy). The scoreboard in §1 is
already generated this way, and it is the one part of this document that never went stale.
