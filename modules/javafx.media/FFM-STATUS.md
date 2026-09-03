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

**`javafx.media` is free of JNI**, and the binary proves it: `jfxmedia.dll` and `libjfxmedia.so`
export **54 `jfxm_*` and zero `Java_*`/`JNI_OnLoad`** (they exported 55 JNI entry points and no
`jfxm_*` on `master`). The Linux library builds with **no JDK on the include path at all** — the
CMake `JDK_HOME` input no longer exists.

Measured against `master` for the whole branch: **142 files changed, +1,255 / −19,155 lines**, of which
the native tree alone is **+289 / −17,943**.

Removing the JNI layer deleted **95 files / 16,458 lines** of C/C++/ObjC (the `jni/` tree except
`Logger.*`, the whole `platform/ios` tree, `GstJniUtils`, `MediaWarningDispatcher`, `LowLevelPerf`,
`JObjectPeers`, `JavaUtils`, `MTObjectProxy`, `AutoLock`, `Thread.h`, `WinThread`, `WinDllMain`) and
rewrote 21 more (+288 / −1,411). Retiring the superseded build inputs — the three `jfxmedia`
makefiles, 18 gstreamer project makefiles, and the `vs_project`/`xcode_project` trees — removed a
further ~3,800 lines. `GstMedia.cpp`, `GstMediaPlayer.cpp` and `GstPlatform.cpp` were deleted too
(with their nine CMake source-list entries): after their JNI entry points went, all three contained
a copyright header and no code at all — `jfxm_media_*`, `jfxm_player_*` and `jfxm_platform_init` in
`ffi/jfxmedia_api.cpp` do that work now. **Net: roughly 17,600 lines of C/C++/ObjC and build files gone**, with the
`.def` files, `def-*.pl` and `HeaderGen.java` deliberately kept because the CMake build still uses
them.

The script deliberately ignores `gstreamer/gstreamer-lite`, `gstreamer/3rd_party` and
`gstreamer/plugins`: the audit proved those trees contain **zero** JNI tokens
(`FFM-AUDIT-plugins-libs.md` §3). They are JVM-agnostic engine code and are not touched by this
migration.

One caveat on reading that zero: the script skips comment lines, and seven comments in the tree
still say "JNIEnv" or "JNI_OnLoad" while recording what a `jfxm_*` function replaced. That is
deliberate provenance, not residue.

### Why the Linux cross-check earned its keep

Deleting `<jni.h>` broke the build in a way only another compiler could see. `Utils/Singleton.h`
uses `NULL` but never included a header defining it — it had been arriving transitively through
`jni.h`. MSVC still found it via another include and compiled happily; **GCC 15.2 failed** with
`'NULL' was not declared in this scope`. Fixed with an explicit `#include <stddef.h>`. Nothing about
Windows-only verification would have caught it, which is exactly why the unverified macOS paths in
§4 matter.

## 2. The design

`FFM-ABI-CONTRACT.md` is authoritative: the 54-function `jfxm_*` C ABI, the three callback tables,
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
| `native/linux.cmake` | Built in WSL (Ubuntu 26.04, gcc, Ninja) from the **unmodified** file after `libasound2-dev` was installed: configure 0, build 0, zero errors. `libgstreamer-lite.so` links `libasound.so.2`; `libjfxmedia.so` exports **54 `jfxm_*` + 54 `Java_*` + `JNI_OnLoad`**, links the system GLib and `libgstreamer-lite.so`, and has **no `libjvm` dependency**. Re-run after the review fixes: still 0 errors, 0 warnings from `ffi/`, and `jfxm_event_player_state` present. `avplugin` is skipped (no ffmpeg dev packages) |
| `native/mac.cmake` | **Not compiled — no Mac here.** Statically verified three ways: the source set matches the four mac makefiles exactly (355 makefile sources, 359 listed, difference = the four intended `ffi/*.cpp`); every `-D` and `-framework` is present per target; a scratch project including the file configures **and generates** with RC 0 for arm64 and x86_64 |
| `jfxmedia_api.h` + `ffi/*` (the C ABI) | Compiles clean (no new warnings) into `jfxmedia.dll`; all **54** `jfxm_*` symbols exported alongside the 55 JNI ones. Header compiles standalone as C at `/W4` |
| The band-pair lifetime fix (review finding 1) | A live smoke test against the built DLL builds a real GStreamer pipeline, takes a real `CGstAudioSpectrum`, and proves pair A is **not** released when pair B replaces it but only when the holder dies, and pair B at `jfxm_media_dispose` — plus release-exactly-once on the NULL-spectrum, NULL-pair and NULL-callback paths (26 checks, all pass) |
| Struct layouts | Measured with a C program built by `cl.exe` x64: `sizeof(JfxmFrameInfo)` 120, `JfxmPlayerCallbacks` 104, `JfxmStreamCallbacks` 72; `JfxmFrameInfo` offsets 0, 8, 12, 16, 20, 24, 28, 32, 36, 40, 56, 88 — and `jfxm_offsetof_frame_info` returns the same values from the DLL |
| The C ABI end to end | A native smoke program driving the built DLL: ABI version 1; log sink receives level + message + `user`; `jfxm_platform_init` idempotent; NULL-handle calls return `ERROR_MEDIA_NULL`; a real GStreamer `jfxm_media_create` invoked `property`/`is_random_access`/`is_seekable`/`need_buffer` synchronously, `jfxm_player_init` succeeded, the pipeline called `read_next_block`, delivered `media_error` through the callback table with the right `user`, called `close_connection`, and `jfxm_media_dispose` tore down cleanly |
| CI package list | `.github/workflows/submit.yml` Linux jobs gained `libasound2-dev`; without it the media configure step fails on the ALSA sink |
| The module, end to end, JNI-free | `mvn -o -pl modules/javafx.media install` (**with** the native build): HeaderGen → CMake → all four libraries → **`Tests run: 15, Failures: 0, Errors: 0, Skipped: 0`** → jar installed → BUILD SUCCESS. `jfxmedia.dll` 54 `jfxm_*` / 0 `Java_*`; the Linux rebuild the same. No cmake unused-variable warning, and no restricted-method warning |
| The tests have teeth | Every new behavioural test was mutation-checked. Reverting the `NativeMediaManager` fix fails both degradation tests; letting a checked exception out of an upcall target is refused by `Linker.upcallStub` at install time; replacing the `catch (Throwable)` with a rethrow kills the surefire fork outright — which is exactly the invariant the rule exists to protect |

## 4. What is NOT verified here

* **macOS.** `mac.cmake`, the AVF backend (`jfxm_avf_*` in `OSXPlatform.mm` / `OSXMediaPlayer.mm`),
  the `CPlayerEventDispatcher*` ivar type change in `AVFMediaPlayer`, and the Java `OSXPlatform` /
  `OSXMedia` / `OSXMediaPlayer` flips have never been compiled: this is a Windows machine with no
  Xcode. A macOS build or a CI run must confirm them.
* **Playback.** The fork has no media playback test and no test that exercises a real pipeline end
  to end; the binding tests are hardware-free by design (`jfx-ffm-testing` says to keep them so).
  Verifying that audio and video still actually play needs a manual run.
* **Linux ALSA and `avplugin`.** See §3's caveat; CI covers ALSA now, `avplugin` stays skipped.

## 4a. Pre-existing bugs found while migrating, and deliberately NOT fixed

Behaviour-neutrality outranks tidiness, so these were left exactly as they are. Each is upstream
OpenJFX behaviour, not something this branch introduced; each deserves its own change (and probably
its own JBS issue) with a test.

| Where | What | Effect |
|---|---|---|
| `platform/osx/avf/AVFAudioSpectrumUnit.cpp:191` | `mBands->UpdateBands(size, magnitudes, magnitudes)` — the third parameter is `phases` | On macOS/AVFoundation an `AudioSpectrumListener` receives **phases identical to magnitudes**. Verified by reading the call against `CBandsHolder::UpdateBands(int, const float*, const float*)` |
| `platform/gstreamer/GstAudioSpectrum.cpp:102-107` | `UpdateBands` dereferences the holder after `CBandsHolder::AddRef`, which returns `NULL` when no holder was installed | NULL dereference if a spectrum ever produces data before `setBandCount` installed a holder |
| `jni/NativeVideoBuffer.cpp` (`nativeGetPlaneStrides`) | returns `null` for `count < 1` **and** for `count > 4` | Not a bug as such, but the FFM `JfxmFrameInfo` reports `[0,0,0,0]` instead, so the Java side has to reproduce the `null` explicitly — recorded in contract §14.1 |
| `Utils/win32/WinDllMain.cpp` (now deleted) | declared a **two**-parameter `DllMain(DWORD, LPVOID)`; the CRT calls `DllMain(HINSTANCE, DWORD, LPVOID)`, so `dwReason` actually received the module handle and matched neither `DLL_PROCESS_ATTACH` nor `DLL_PROCESS_DETACH` | `OnDllLoad`/`OnDllUnload` never ran. Both were empty, and the CRT's default `DllMain` returns TRUE, so deleting the file is behaviour-neutral |
| `jni/Logger.cpp` `logWarningMsg` | both overloads log at `LOGGER_DEBUG`, not `LOGGER_WARNING` | Every `LOGGER_WARNMSG` in the media natives is emitted at DEBUG level. Unchanged by this work |
| iOS `MediaPlayer.m` (now deleted) | seven overlay setters returned an uninitialised `jint result` when the player was in its initial state; `jlong_to_ptr` cast through `int` (pointer truncation on arm64); `EventDispatcher`'s global ref never released because nothing calls its `dispose` | Moot — that platform was unreachable and is deleted (see contract §1 decision 3), but worth knowing if anyone restores it from history |

## 5. Rules this work follows

* Migration changes are behaviour-neutral. Deletions (the iOS platform, dead glue such as
  `GstJniUtils`, `MediaWarningDispatcher`, `LowLevelPerf`, `NativeVideoConverter.cpp`) are listed in
  contract §12 and land separately.
* No C is reimplemented in Java: the audit found no `PURE` function that clears the parity gate and
  is not already a data carrier. `Utils/ColorConverter.c` is `PURE-HOT` with `PARITY: unknown` and
  stays native until someone measures the SSE2 path against the generic C path.
* Nothing in this branch is committed by the automation. The user commits.
