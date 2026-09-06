# javafx.media native build: the CMake port

Companion to `FFM-ABI-CONTRACT.md`. Scope: how the `javafx.media` native libraries are built now
that the migration needs them rebuilt on demand, and what the build has to lose before the module
is JNI-free.

## 1. Why the build had to change first

Before this branch, **nothing in the repository compiled the media natives**. The Gradle
`COMPILE_MEDIA=true` path was retired with the Maven port, and `modules/javafx.media/pom.xml`
carried only a comment saying so plus a `javac -h` argument emitting JNI headers no build consumed.
The GNU make projects under `src/main/native/jfxmedia/projects/<os>` and
`src/main/native/gstreamer/projects/<os>` were dead weight: they need cygwin, `makedepend` and a
hand-set `JAVA_HOME`/`OUTPUT_DIR`, and no Maven property or profile invoked them. Prebuilt DLLs
were dropped into `../caches/sdk/bin` by hand (`WEBKIT-MEDIA-STUBS.md`).

That is workable while the C never changes. It is not workable for a JNI->FFM migration, where the
whole point is that the library grows a new ABI and then loses its old one: every step needs a
rebuilt `jfxmedia` before it can be run at all. So the first change on this branch is a CMake port
of the media native build, wired into Maven exactly like the javafx.graphics one.

## 2. Layout

```
modules/javafx.media/native/
    CMakeLists.txt      shared entry point: inputs from Maven, source roots, platform dispatch
    win.cmake           Windows/MSVC targets            (built and verified — section 4)
    linux.cmake         Linux/gcc targets               (built and verified in WSL — section 5a)
    mac.cmake           macOS/clang targets             (written, statically verified — section 5b)
```

`CMakeLists.txt` mirrors `modules/javafx.graphics/native/CMakeLists.txt`: `cmake_minimum_required`
3.20, `CMP0091` for the MSVC runtime, `CMAKE_OSX_DEPLOYMENT_TARGET` 11.0, `project(javafxMediaNatives
LANGUAGES C CXX)`, then the cache inputs and an `include()` of the platform file.

| CMake input | Supplied by Maven as | Meaning |
|---|---|---|
| `GENSRC_DIR` | `${project.build.directory}/gensrc` | generated-source root |
| `HEADERS_DIR` | `${project.build.directory}/gensrc/headers` | the generated `jfxmedia_errors.h` |
| `BIN_DIR` | `${project.build.directory}/native/bin` | output directory for the shared libraries |
| `JFX_VER`, `JFX_FVER`, `JFX_BUILD_ID` | root-pom version properties | Windows version resources |
| `CMAKE_BUILD_TYPE` | `${CONF}` | Release or Debug (non-Windows generators) |

**There is no `JDK_HOME` input, and there are no JDK include directories anywhere in this build.**
Earlier revisions of this plan listed `JDK_HOME` (supplied as `${java.home}`) as a CMake input for the
JNI include dirs, described `HEADERS_DIR` as carrying `javac -h` JNI headers, and listed the removal
of both as future work. All of it is done: `CMakeLists.txt` declares no such cache variable, no
platform cmake file adds a JDK include directory to any target, and the media pom passes no `-h` to
`javac`. `HEADERS_DIR` survives, but it now carries exactly one file, `jfxmedia_errors.h`, generated
by the `headergen` tool from `MediaError.java`.

That staleness is itself the evidence the migration worked, which is why it is worth a sentence
rather than a silent deletion: **the JDK includes are gone because nothing in `javafx.media` needs
`jni.h` any more.** `mac.cmake` keeps one line about it in a comment - Gradle folded the JDK include
directories into `MAC.media.compiler`, so every media library used to see the JNI headers whether it
wanted them or not - and the Linux build is the proof, because it compiles `libjfxmedia.so` with no
JDK on the include path at all. The library is now JVM-agnostic in the literal sense: it could be
built and linked on a machine with no JDK installed.

Source roots defined once in `CMakeLists.txt` and used by every platform file: `MEDIA_SRC`,
`JFXMEDIA_SRC`, `GST_SRC` (gstreamer-lite), `GLIB_SRC`, `LIBFFI_SRC`, `BASECLASSES_SRC`,
`PLUGINS_SRC`, `MEDIA_PROJECTS_SRC` (for the Windows `.def` files) and `JFX_VERSION_RC` (the
version resource shared with javafx.graphics).

## 3. Libraries

| Target | Output | Platforms | Notes |
|---|---|---|---|
| `glibLite` | `glib-lite` | Windows, macOS | Linux links the **system** GLib instead |
| `gstreamerLite` | `gstreamer-lite` | all | Windows exports come from `gstreamer/projects/win/gstreamer-lite.def` (ordinals, `NONAME`) |
| `fxplugins` | `fxplugins` | all | Windows adds the DirectShow `baseclasses` archive, `dshowwrapper` and `mfwrapper` |
| `avplugin` | `avplugin*` | Linux only | optional: skipped with a `message(STATUS)` when pkg-config finds no libavcodec/libavformat/libswscale |
| `jfxmedia` | `jfxmedia` | all | the only target that needs the JDK includes today |
| `jfxmediaAvf` | `jfxmedia_avf` | macOS only | links `jfxmedia` plus AVFoundation/CoreMedia/Accelerate/AudioUnit/MediaToolbox |

Source lists are **explicit**, transliterated from the makefiles, not globbed: the build must equal
the makefile build file for file. The makefile sub-projects that produced `lib.exe` archives
(`libffi`, `libglib`, `libmodule`, `libgobject`, `libgthread`, `libgstreamer`, `libgstplugins`,
`baseclasses`) are `STATIC` targets with their own flags (`add_media_archive`) that the DLL targets
link, so the linker pulls in exactly what the `.def` and the DLL code reference, as before.

The new FFM sources (`jfxmedia/ffi/jfxmedia_api.cpp`, `FfiPlayerEventDispatcher.cpp`,
`FfiStreamCallbacks.cpp`, `FfiBandsHolder.cpp`) are listed in the `jfxmedia` target, and
`${JFXMEDIA_SRC}/ffi` is on its include path.

## 4. Windows: verified

```
cmake -S modules/javafx.media/native -B modules/javafx.media/target/native/cmake -A x64 \
      -DGENSRC_DIR=<abs>/target/gensrc -DHEADERS_DIR=<abs>/target/gensrc/headers \
      -DBIN_DIR=<abs>/target/native/bin \
      -DJFX_VER=28 -DJFX_FVER=28,0,0,0 -DJFX_BUILD_ID=28-ea+0
cmake --build modules/javafx.media/target/native/cmake --config Release --parallel
```

Both configure and build return 0 in Release and in Debug (MSVC 19.44, Visual Studio 17 2022,
Windows SDK 10.0.26100). Equivalence with the prebuilt JNI-era libraries in `../caches/sdk/bin`,
checked with `dumpbin`:

| Library | Exports built vs prebuilt | Dependents |
|---|---|---|
| `jfxmedia.dll` | 55 named exports, name sets **identical** (before the ABI was added) | identical apart from an extra `api-ms-win-crt-string` import |
| `gstreamer-lite.dll` | 155 functions, 0 names (ordinal `.def` exports) — identical | identical |
| `glib-lite.dll` | 551 functions, 0 names — identical | identical |
| `fxplugins.dll` | 1 export (`gst_plugin_desc`) — identical | identical |

That comparison was made **before** the JNI half was deleted, and a later revision of this section
recorded the intermediate state as if it were the end state: *"`jfxmedia.dll` exports 108 names: the
53 `jfxm_*` functions plus the 54 `Java_*` entry points and `JNI_OnLoad`."* That was true while both
ABIs were compiled side by side on purpose (migration playbook §7 step 1) and is not true now. The
built `jfxmedia.dll` exports **58 `jfxm_*`, zero `Java_*` and no `JNI_OnLoad`**; the authoritative
count, checked three ways against the header, the implementation and `JfxMediaNative`, is in
`FFM-STATUS.md` §3.

**Reproducible builds: the `/experimental:deterministic` flag is carried over, and slightly extended.**
An earlier revision of this plan listed it under "deliberately not carried over"; it is now in
`win.cmake`, gated on `SOURCE_DATE_EPOCH` being set and non-empty in the environment exactly as the
makefiles gated it, and costing nothing in the default case where it is unset. How it is applied is
one step past the literal old behaviour, deliberately, so do not "restore parity" by narrowing it:

* The retired makefiles added it to **`CFLAGS` and `LDFLAGS`** for the two projects that compiled
  sources themselves (`jfxmedia/projects/win/Makefile`, `gstreamer/projects/win/fxplugins/Makefile`)
  and to **`LDFLAGS` alone** for the two that only linked (`glib-lite`, `gstreamer-lite`) — not as a
  decision, but because those two compiled in sub-makefiles (`Makefile.glib`, `Makefile.gobject`,
  `Makefile.ffi`, …) that were never passed the flag.
* `win.cmake` applies it to **compile in both `add_media_archive` and `add_media_library`, and to
  link in `add_media_library`**, so every target gets it including the static archives. An archive
  full of non-deterministic objects defeats a deterministic link, which is the whole point of the
  flag; reproducing the makefiles' accidental gap would leave `glib-lite` and `gstreamer-lite` no
  more reproducible than before.
* **Nothing was dropped on the graphics side**: `modules/javafx.graphics/native/` never carried this
  flag at all, so there is no divergence there to explain.

Still deliberately not carried over from the makefiles: the `-manifestfile:` path (MSBuild embeds the
manifest) and a `-libpath:` on an archive step that had no effect.
(MSBuild embeds the manifest), and a `-libpath:` on an archive step that had no effect.

## 5. Maven wiring

`modules/javafx.media/pom.xml` gains, modelled on `modules/javafx.graphics/pom.xml`:

1. an execution that generates `jfxmedia_errors.h` from `MediaError.java` with the in-tree
   `src/tools/java/headergen/HeaderGen.java` tool, into `HEADERS_DIR`, before CMake runs. This is
   what keeps the C error codes and `MediaError` from drifting; the Gradle build did the same thing
   and the stale copy under `platform/ios` is not used;
2. `native-win` / `native-linux` / `native-mac` profiles (OS activation plus `skipNative` value
   `!true`) running cmake configure and build in `process-classes` through `exec-maven-plugin`;
3. surefire wiring for the module's first test tree (`--upgrade-module-path` for the base/graphics
   shims, `--add-modules javafx.base,javafx.graphics,javafx.media`, the two `@addExports` argfiles,
   `--enable-native-access=javafx.graphics,javafx.media`, and `-Djava.library.path` pointing at
   `target/native/bin` first).

The root pom prepends `modules/javafx.media/target/native/bin` to `jfx.native.librarypath`, so the
`javafx.web` and `tests/system` runs pick up freshly built media libraries ahead of the caches, and
`sdk/pom.xml` copies them into the assembled SDK next to the graphics ones.

`-DskipNative=true` skips the whole native build (and the header generation), leaving Maven to
compile Java only, exactly as before this branch.

## 5a. Linux: verified

`linux.cmake` transliterates `jfxmedia/projects/linux/Makefile` and
`gstreamer/projects/linux/{gstreamer-lite,fxplugins,avplugin}/Makefile`. Linux uses the **system**
GLib through pkg-config (`glib-2.0 gobject-2.0 gmodule-2.0 gthread-2.0`), so there is no `glibLite`
target, and it adds `avplugin`, which is skipped with a `message(STATUS)` when pkg-config finds no
`libavcodec`/`libavformat`/`libswscale` (the Linux CI jobs install all three).

Built in WSL (Ubuntu 26.04, gcc, Ninja, JDK 25) out of the `/mnt/c` tree:

```
cmake -S modules/javafx.media/native -B /tmp/jfxm-lin -G Ninja -DCMAKE_BUILD_TYPE=Release \
 -DGENSRC_DIR=<abs>/target/gensrc \
      -DHEADERS_DIR=<abs>/target/gensrc/headers -DBIN_DIR=/tmp/jfxm-lin/bin
cmake --build /tmp/jfxm-lin --parallel
```

configure 0, build 0, producing `libgstreamer-lite.so`, `libfxplugins.so` and `libjfxmedia.so`.
`nm -D --defined-only libjfxmedia.so`: **53 `jfxm_*`, 54 `Java_*`, 1 `JNI_OnLoad`** — the same ABI
the Windows DLL exports. `ldd` lists only the system GLib stack, `libgstreamer-lite.so`, libc/libm
and `libatomic`; **no `libjvm`**, as expected. `libfxplugins.so` exports 21 symbols against the
Windows DLL's 1, which is makefile parity, not a defect: the Linux makefiles set no
`-fvisibility`/version script, so every non-static symbol is exported, while Windows exports only
what the `.def` lists.

After `libasound2-dev` was installed, the run above was repeated against the **unmodified**
`linux.cmake` straight from the repository (no scratch copy, no stubbing): configure 0, build 0,
same three libraries, `libgstreamer-lite.so` now linking `libasound.so.2` for the ALSA sink, and
`libjfxmedia.so` still at 53/54/1 exports with no `libjvm`. Reproduce it in one shell — WSL clears
`/tmp` between `wsl.exe` invocations, so build into `$HOME` and verify in the same command:

```
wsl.exe -e bash -lc 'R=/mnt/c/SourceCode/jfx-ffm/modules/javafx.media; B=$HOME/jfxm-lin; \
  cmake -S $R/native -B $B -G Ninja -DCMAKE_BUILD_TYPE=Release \
 -DGENSRC_DIR=$R/target/gensrc \
    -DHEADERS_DIR=$R/target/gensrc/headers -DBIN_DIR=$B/bin && \
  cmake --build $B --parallel && nm -D --defined-only $B/bin/libjfxmedia.so | grep -c " T jfxm_"'
```

`avplugin` is still skipped: `libavcodec-dev`/`libavformat-dev` are not installed, and CI does not
install them either (see §6).

One further note on that target:

* `gstreamer-lite`'s makefile lists three include directories that do not exist in the tree
  (`gstreamer/gst/parse`, `gst-plugins-good/gst-libs`, `gst-plugins-bad/gst-libs`); they are
  omitted. `gst-plugins-good/gst/isomp4` does exist and **is** required — the `qtdemux` sources
  fail with `fatal error: qtdemux.h: No such file or directory` without it.

## 5b. macOS: written, statically verified, never compiled

`mac.cmake` (757 lines) defines six targets: `glibLiteFfi` (the libffi static archive, kept in the
build tree), `glibLite`, `gstreamerLite`, `fxplugins`, `jfxmedia` and `jfxmediaAvf`. It enables
`OBJC`, `OBJCXX` and `ASM` (libffi's `.S` sources), forces `LINKER_LANGUAGE CXX` as Gradle did,
sets `INSTALL_NAME_DIR "@rpath"` with `BUILD_WITH_INSTALL_NAME_DIR` so the build-tree dylibs carry
`@rpath/lib<name>.dylib` (there is no install step — `sdk/pom.xml` copies the built files), and
ports the makefile's QuickTime assertion as a POST_BUILD
`! nm -m <lib> | grep -E "(QTKit|QuickTime)"` on both products.

Three static checks, all green, since nothing here can be compiled on Windows:

* **Source-set diff**: 355 sources extracted from the four mac makefiles (all exist on disk) versus
  359 listed in `mac.cmake`. Nothing is only-in-makefile; the four only-in-cmake entries are exactly
  the deliberate `ffi/*.cpp` additions.
* **Flag coverage**: every `-D` define and every `-framework` of every makefile is present per
  target (glib 15/15, gstreamer-lite 10/10 + 4 frameworks, fxplugins 8/8, jfxmedia 10/10 + 2,
  jfxmediaAvf 15/15 + 7). The only differences are makefile `-lfoo` versus CMake target names.
* **Parse check**: a scratch project that predefines the inputs and `include()`s the file
  configures and generates with RC 0 for both `arm64` and `x86_64`, exercising the real
  `add_library`/`target_*` calls, the generator expressions and the POST_BUILD command.

Deviations worth knowing: `-msse2` follows the real target architecture
rather than the makefile's `ARCH` variable, which Gradle set inconsistently and which caused three
libraries to be compiled with `-msse2` for arm64; `gst-plugins-base/gst-libs/gst/interfaces` is
omitted because it does not exist in the tree.

## 6. What is left

* **A macOS build - DONE, in CI, and it is the one platform where a link proves something.**
  `mac.cmake` has never been compiled *on a developer machine here*, but `macos_x64_build` and
  `macos_aarch64_build` compile and link it on **every push**, with `Built target jfxmediaAvf` in the
  logs. Because `add_media_library` uses `SHARED` with the default `-undefined error`, macOS is the
  only platform where a dangling symbol is fatal at link, so a green macOS job is real symbol-level
  evidence rather than a smoke test. CI also asserts `libjfxmedia_avf.dylib` exists and dumps
  `otool -L` and `nm -gU` for it. Still worth eyeballing on a first Mac: that `otool -D`/`-L` show
  `@rpath` install names and the `libgstreamer-lite`/`libglib-lite` references, and that the
  POST_BUILD QuickTime check passes. **Compiling is not running:** no macOS playback has ever been
  exercised, so nothing behavioural on that platform is verified.
* **CI** — `.github/workflows/submit.yml` runs `mvn -B -ntp -fae install` on five platforms, so
  once the three platform files exist the media natives are built there automatically. Until
  `mac.cmake` lands, the two macOS jobs fail at the media CMake step, which is the intended loud
  failure rather than a silent skip. The two Linux jobs gained `libasound2-dev` in their
  `apt-get install` line — `gstreamer-lite` builds the ALSA sink and its `pkg_check_modules(... alsa
  REQUIRED)` would otherwise fail the configure step. **The paragraph that follows is superseded:**
  `libavcodec-dev`, `libavformat-dev` and `libswscale-dev` have since been added, `avplugin` is
  built and verified in CI, and the reasoning below is kept only as the record of why it was
  originally deferred. See `FFM-STATUS.md` sections 3 and 4 for what the plugin build establishes
  and for the header-only `libswscale` rule that must not be broken. Originally:
  `libavcodec-dev`/`libavformat-dev` were
  deliberately **not** added: `avplugin` then stays skipped in CI, which matches what the fork
  shipped before (the Gradle build compiled no media at all) and avoids pinning the `av` wrapper
  sources to whatever ffmpeg version the runner image carries. Windows and macOS need no new
  packages.
* **Retiring the makefiles — DONE.** `jfxmedia/projects/**` and `gstreamer/projects/**` (except the
  Windows `.def` files and `src/tools/native/def-*.pl`, which the CMake build still uses) are gone,
  together with `src/main/native/vs_project` and `src/main/native/xcode_project`: 35 files and 6,853
  lines removed against 4 CMake files and 2,063 lines added.
* **JNI-era removals — DONE.** `${JDK_HOME}/include*` is gone from the `jfxmedia` target (the block
  `win.cmake` marked `JNI-era`), the `-h ${project.build.directory}/gensrc/headers` compiler argument
  is gone from the media pom, and there is no `JDK_HOME` cache variable in `CMakeLists.txt` for
  anything to supply. `HEADERS_DIR` stays and now carries exactly one file, `jfxmedia_errors.h`. See
  the note under the CMake-input table in section 2 - and note that no platform cmake file adds a JDK
  include directory to *any* target, not just to `jfxmedia`: the whole module is JVM-agnostic.
