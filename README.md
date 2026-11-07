# OpenJFX FFM

This codebase is a fork of the OpenJFX mainline development branch (version 28-ea).

# Short Term Project Goals

- Switch all JNI code over to FFM API
- Reduce native code as much as (safely) possible in favor of pure java
- Extend the media API to support more formats (.wav, .cdg, etc..) and low-latency, mixed sound effects for games ([plan](backlog/README.md#media-formats))
- Add a [game toolkit](#game-toolkit) for JavaFX

# Long Term Project Goals

- Port native code to Rust
- Serve as a prototype for OpenJFX project to use as a blueprint for making similar improvements
- Own and grow the game toolkit's built-in 3D physics engine beyond its Godot origin

# Non Goals

- Logic changes, or new or changed public API, in the existing `javafx.*` modules outside javafx.media, unless a
  backlog story names the change. The game toolkit lives in new modules of the fork's own.
- Mobile platforms. "Cross-platform" means the five desktop targets CI builds: Windows x64, macOS x64 and aarch64,
  Linux x64 and aarch64.
- For the game toolkit: an editor, an asset build pipeline, ECS, networking or scripting; a Java port of Jolt; soft
  bodies; swapping physics engines at run time; a native physics engine in the SDK; bundling an engine under a
  GPL-incompatible licence; bit-for-bit parity with stock Godot builds.

# Game toolkit

The game toolkit is planned in the [backlog](backlog/README.md#game-toolkit) (US-075 to US-095). It will provide:

- a fixed-step game loop with render interpolation and per-tick input, including gamepads;
- a built-in physics engine in pure Java, ported faithfully from Godot's built-in physics and proven equal to a pinned
  Godot build: 2D first, then 3D rigid bodies;
- a contract that lets applications bring their own physics engine, Java or native, checked by a conformance kit.

The built-in engine is to give bit-identical results on all five desktop targets. Rendering is always JavaFX; only the
physics engine can be swapped. The toolkit is the fork's own work and not part of the OpenJFX blueprint goal.

# Building

The project builds with Apache Maven (3.9+) and a JDK at least as new as
`jfx.build.jdk.version.min` (27) in [build.properties](build.properties) (CI
builds with JDK 27, Eclipse Temurin on Linux and Windows and Oracle on macOS;
the separate `jfx.jdk.target.version` is the runtime target passed to
`javac --release`, not the build JDK):

    mvn install

This compiles all modules, builds the javafx.graphics and javafx.media native
libraries (CMake + MSVC/clang; on macOS this includes the Metal shader library)
and runs the headless unit test suite. Useful flags:

- `-DskipTests` — skip the test suite
- `-DskipNative=true` — skip the native build (tests that need natives will fail)
- `-pl modules/javafx.base -am` — build a single module (plus its dependencies)
- `-DFULL_TEST=true -DUSE_ROBOT=true` — also run the eligible system/robot tests; add
  `-Dsurefire.includes='test/robot/**/*.java'` to select only Robot tests
- `-DHEADLESS_TEST=true` — force headless mode for the system tests
- `-Djfx.web.skipTests=false` — run the web module tests and enable the WebKit-dependent system
  Robot tests (see
  [WEBKIT-MEDIA-STUBS.md](WEBKIT-MEDIA-STUBS.md))

`jfx.web.skipTests` defaults to `true`, which also excludes the WebKit-dependent Robot tests even
when `-DUSE_ROBOT=true` is passed. Setting it to `false` requires an ABI-compatible `jfxwebkit`
rebuilt for the current source tree.

The media native libraries are compiled from source along with the
javafx.graphics ones: `jfxmedia`, `gstreamer-lite` and `fxplugins` on every
platform, plus `glib-lite` on Windows and macOS (Linux links the system GLib
through pkg-config instead) and `jfxmedia_avf` on macOS. Linux also builds
`avplugin`, but only when the system ffmpeg development packages
(`libavcodec`, `libavformat` and `libswscale`) are installed; otherwise it is
skipped. The WebKit library is not compiled from source; see
[WEBKIT-MEDIA-STUBS.md](WEBKIT-MEDIA-STUBS.md) for how to supply a prebuilt
`jfxwebkit`, and why a `jfxmedia` from an OpenJFX SDK cannot be used in its
place any more. The assembled SDK lands in `sdk/target/sdk/`; it holds the
module jars and `javafx.properties` under `lib/` plus the javafx.graphics and
javafx.media native libraries. Legal notices, `src.zip`, jmods, javadoc and
distribution archives are not produced.

javafx.graphics has no JNI on any platform: no class declares a `native`
method, and its native libraries export plain C functions that Java calls
through the Foreign Function & Memory API (`java.lang.foreign`). The
`javac -h` step of the graphics build stays only because Windows `glass`
still includes the constant headers it generates; the Linux and macOS
libraries compile without the JDK include directories and without those
headers.

# Contribute

Please contact BenEsquivelMusic@gmail.com if you would like to contribute to this project.
