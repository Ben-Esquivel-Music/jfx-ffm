# US-116 — Keep the build directory out of the RUNPATH of the Linux media libraries

## In short
- **Problem:** The Linux media libraries we ship remember the folder they were built in (for example
  `/home/<user>/.../target/native/bin`) as a place to look for other libraries. On a user's machine that folder is
  not ours; a library planted there would be loaded.
- **Seen failing?** Yes, measured with `readelf` on the built `libjfxmedia.so`.
- **Goal:** The shipped media libraries look for their dependencies only next to themselves.
- **Done when:** `readelf` shows no absolute path in any shipped media library.
- **Order:** Can start now. Small: a CMake setting.

**Status:** 📋 Ready (filed 2026-11-15 from US-040) · **Found:** 2026-11-15, US-040's native build in WSL Ubuntu
(`readelf -d`); the CMake files re-read for this story the same day, nothing built or run for it

## Story
As a maintainer who ships the JavaFX SDK for Linux,
I want the shipped media libraries to look for their dependencies only in directories relative to themselves,
so that no user's machine searches the directory the SDK was built in.

## Problem
`linux.cmake` is `modules/javafx.media/native/linux.cmake`, cited as in the US-040 working tree (uncommitted, on
`29f69dc205`).

### Measured
US-040's native build report (`readelf -d`, WSL Ubuntu) found that the shipped `libjfxmedia.so` of the baseline
`29f69dc205`, built in the clone `~/us040-base`, has the RUNPATH
`[$ORIGIN:/home/<user>/us040-base/modules/javafx.media/target/native/bin]` (user name replaced): after `$ORIGIN`, the
absolute `target/native/bin` of the checkout it was built in. The report says the same of the US-040 build's shipped
library and quotes no line for it. The hooks build of `jfxmedia` that US-040 adds (in `target/native/test-bin`, never
shipped) has `[$ORIGIN:$ORIGIN/../bin]`.

### Where it comes from
`jfxmedia` links `gstreamerLite`, a target of this project that `add_media_library` writes to `BIN_DIR`
(`linux.cmake:472`, `:135-146`), and asks for `$ORIGIN` itself (`linux.cmake:473`). By default CMake puts the
build-tree directory of each linked library target into a target's build RPATH (CMake's documented behaviour, not
tested for this story). Nothing in `modules/javafx.media/native` sets `CMAKE_SKIP_BUILD_RPATH`, `SKIP_BUILD_RPATH` or
`BUILD_WITH_INSTALL_RPATH` except the hooks target (`linux.cmake:486`). The build has no install step:
`sdk/pom.xml:203-207` copies the libraries out of `target/native/bin` as built, on Linux into the SDK's `lib`
(root `pom.xml:458`).

### Not measured
- `libfxplugins.so` and `libavplugin.so` link `gstreamerLite` too (`linux.cmake:390`, `:416`), with no `-rpath` of
  their own; `libgstreamer-lite.so` links only `m` and pkg-config libraries (`linux.cmake:363`). None of the three was
  inspected, and `avplugin` was not built in that WSL: it is built only where pkg-config finds the ffmpeg libraries
  (`linux.cmake:395`).
- The libraries of CI's Linux x64 and aarch64 jobs.

## Requirement
1. **No build path in a shipped library.** No media library that the Linux build writes to `target/native/bin`, and so
   none in the SDK's `lib`, has a `RUNPATH` or `RPATH` entry that names a directory of the build machine. Every entry
   is `$ORIGIN` or relative to it.
2. **Loading unchanged.** `libjfxmedia.so` still finds `libgstreamer-lite.so` beside it, both in `target/native/bin`
   and in an SDK's `lib` wherever that is.

## Constraints
- `libjfxmedia.so` has the RUNPATH `[$ORIGIN]`, the entry `linux.cmake:473` asks for. The hooks library keeps
  `[$ORIGIN:$ORIGIN/../bin]` and stays out of the SDK.
- Nothing else in the libraries changes: `NEEDED`, `SONAME` and the defined exports (`nm -D --defined-only`) of each
  are identical before and after.
- No Windows or macOS build file and no Java loader code (`JfxMediaNative.loadNativeLibraries`) changes.

## Acceptance criteria
On Linux (WSL Ubuntu), before and after the change, with the output of every command recorded here:
1. **readelf.** For each `.so` in `modules/javafx.media/target/native/bin` and each media library in
   `sdk/target/sdk/lib` (`libjfxmedia.so`, `libgstreamer-lite.so`, `libfxplugins.so`, `libavplugin.so` where built),
   `readelf -d <lib> | grep -E 'R(UN)?PATH'` shows no absolute path, and for `libjfxmedia.so` it shows `[$ORIGIN]`;
   before the change `libjfxmedia.so` shows the build path. The hooks library in `target/native/test-bin` shows
   `[$ORIGIN:$ORIGIN/../bin]`. A machine without `avplugin` records its absence.
2. **Nothing else changed.** The `NEEDED` and `SONAME` entries of `readelf -d`, and `nm -D --defined-only`, are
   identical before and after for each library.
3. **Loading from `target/native/bin`.** `mvn -pl modules/javafx.media -am test` passes with the same per-class test
   counts before and after, the hooks tests included.
4. **Loading from the SDK layout.** With a copy of `sdk/target/sdk/lib` in a scratch directory outside the checkout,
   `ldd <copy>/libjfxmedia.so` resolves `libgstreamer-lite.so` to the copy, and a JVM that loads `javafx.media` from the
   copy, with no build-tree directory on `java.library.path`, opens both libraries from the copy (`LD_DEBUG=libs`).
5. **CI.** `submit.yml` is green on all six jobs (`No JNI` and the five builds).

## Out of scope
The javafx.graphics libraries, and the macOS dylibs: `modules/javafx.media/native/mac.cmake:76-78` says CMake's
build-tree RPATH is left enabled there, and no dylib was inspected.

## Definition of Done
- All acceptance criteria met, logs kept, results stated here; an independent review repeated criteria 1 and 4.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without
  explicit approval from the repository owner.**

## Notes
- Found by US-040's native build engineer, who set `SKIP_BUILD_RPATH` on the hooks library for this reason; the owner
  approved filing on 2026-11-15.
