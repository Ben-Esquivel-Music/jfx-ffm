# OpenJFX FFM

This codebase is a fork of the OpenJFX mainline development branch (version 28-ea).

# Short Term Project Goals

- Switch all JNI code over to FFM API
- Reduce native code as much as (safely) possible in favor of pure java
- Extend the media API to support more formats (.wav, .cdg, etc..)

# Long Term Project Goals

- Port native code to Rust
- Serve as a prototype for OpenJFX project to use as a blueprint for making similar improvements

# Non Goals

- It is not the goal of this project to make any logic changes outside of the media module

# Building

The project builds with Apache Maven (3.9+) and a JDK at least as new as
`jfx.build.jdk.version.min` (25) in [build.properties](build.properties) (CI
builds with JDK 26.0.2; the separate `jfx.jdk.target.version` is the runtime
target passed to `javac --release`, not the build JDK):

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

The media native libraries (`jfxmedia`, `gstreamer-lite`, `glib-lite`,
`fxplugins`, and `jfxmedia_avf` on macOS) are compiled from source along with
the javafx.graphics ones. The WebKit library is not; see
[WEBKIT-MEDIA-STUBS.md](WEBKIT-MEDIA-STUBS.md) for how to supply a prebuilt
`jfxwebkit`, and why a `jfxmedia` from an OpenJFX SDK cannot be used in its
place any more. The assembled SDK lands in `sdk/target/sdk/`; it holds the
module jars and `javafx.properties` under `lib/` plus the javafx.graphics and
javafx.media native libraries. Legal notices, `src.zip`, jmods, javadoc and
distribution archives are not produced.

# Contribute

Please contact BenEsquivelMusic@gmail.com if you would like to contribute to this project.
