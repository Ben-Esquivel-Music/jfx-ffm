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
| `JDK_HOME` | `${java.home}` | JNI include dirs. **JNI-era only** — deleted when the module is JNI-free |
| `GENSRC_DIR` | `${project.build.directory}/gensrc` | generated-source root |
| `HEADERS_DIR` | `${project.build.directory}/gensrc/headers` | `javac -h` JNI headers **and** the generated `jfxmedia_errors.h` |
| `BIN_DIR` | `${project.build.directory}/native/bin` | output directory for the shared libraries |
| `JFX_VER`, `JFX_FVER`, `JFX_BUILD_ID` | root-pom version properties | Windows version resources |

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
| `avplugin` | `avplugin*` | Linux only | optional: skipped with a `message(STATUS)` when pkg-config finds no libavcodec/libavformat |
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
      -DJDK_HOME="C:/Program Files/OpenJDK/jdk-26" \
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

After the FFM ABI landed, `jfxmedia.dll` exports 108 names: the 53 `jfxm_*` functions plus the 54
`Java_*` entry points and `JNI_OnLoad`, which is the side-by-side state the migration expects.

Deliberately not carried over from the makefiles: the `SOURCE_DATE_EPOCH`-conditional
`/experimental:deterministic` flag (the graphics port omits it too), the `-manifestfile:` path
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
`libavcodec`/`libavformat` (CI installs no ffmpeg development packages).

Built in WSL (Ubuntu 26.04, gcc, Ninja, JDK 25) out of the `/mnt/c` tree:

```
cmake -S modules/javafx.media/native -B /tmp/jfxm-lin -G Ninja -DCMAKE_BUILD_TYPE=Release \
      -DJDK_HOME=/usr/lib/jvm/java-25-openjdk-amd64 -DGENSRC_DIR=<abs>/target/gensrc \
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
    -DJDK_HOME=/usr/lib/jvm/java-25-openjdk-amd64 -DGENSRC_DIR=$R/target/gensrc \
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

Deviations worth knowing: `jfxmediaAvf` also carries the JNI-era JDK include block (its
`AVFMediaPlayer.h` pulls in `jni.h` through the dispatcher header, exactly as the makefile's
`AVF_INCLUDES` did — both blocks disappear together); `-msse2` follows the real target architecture
rather than the makefile's `ARCH` variable, which Gradle set inconsistently and which caused three
libraries to be compiled with `-msse2` for arm64; `gst-plugins-base/gst-libs/gst/interfaces` is
omitted because it does not exist in the tree.

## 6. What is left

* **A macOS build.** `mac.cmake` has never been compiled. CI (or a Mac) must confirm: the five
  dylibs link; the `.S` sources assemble; `otool -D`/`-L` show `@rpath` install names and the
  `libgstreamer-lite`/`libglib-lite` references; the POST_BUILD QuickTime check passes; and
  `nm -gU libjfxmedia.dylib` shows the `jfxm_*` ABI.
* **CI** — `.github/workflows/submit.yml` runs `mvn -B -ntp -fae install` on five platforms, so
  once the three platform files exist the media natives are built there automatically. Until
  `mac.cmake` lands, the two macOS jobs fail at the media CMake step, which is the intended loud
  failure rather than a silent skip. The two Linux jobs gained `libasound2-dev` in their
  `apt-get install` line — `gstreamer-lite` builds the ALSA sink and its `pkg_check_modules(... alsa
  REQUIRED)` would otherwise fail the configure step. `libavcodec-dev`/`libavformat-dev` were
  deliberately **not** added: `avplugin` then stays skipped in CI, which matches what the fork
  shipped before (the Gradle build compiled no media at all) and avoids pinning the `av` wrapper
  sources to whatever ffmpeg version the runner image carries. Windows and macOS need no new
  packages.
* **Retiring the makefiles** — `jfxmedia/projects/**` and `gstreamer/projects/**` (except the
  Windows `.def` files and `src/tools/native/def-*.pl`, which the CMake build still uses) are
  deleted once all three platform files are verified, together with `src/main/native/vs_project`
  and `src/main/native/xcode_project`.
* **JNI-era removals** — when the module is JNI-free: drop `${JDK_HOME}/include*` from the
  `jfxmedia` target (a block marked `JNI-era` in `win.cmake`), the `-h ${project.build.directory}/gensrc/headers`
  compiler argument from the media pom, and the `JDK_HOME` input from `CMakeLists.txt`.
  `HEADERS_DIR` stays: it still carries `jfxmedia_errors.h`.
