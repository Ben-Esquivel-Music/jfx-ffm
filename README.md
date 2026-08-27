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

The project builds with Apache Maven (3.9+) and a JDK matching
`jfx.jdk.target.version` in [build.properties](build.properties):

    mvn install

This compiles all modules, builds the javafx.graphics native libraries
(CMake + MSVC/clang; on macOS this includes the Metal shader library) and runs
the headless unit test suite. Useful flags:

- `-DskipTests` — skip the test suite
- `-DskipNative=true` — skip the native build (tests that need natives will fail)
- `-pl modules/javafx.base -am` — build a single module (plus its dependencies)
- `-DFULL_TEST=true -DUSE_ROBOT=true` — also run the system/robot tests
- `-DHEADLESS_TEST=true` — force headless mode for the system tests
- `-Djfx.web.skipTests=false` — run the web module tests (see
  [WEBKIT-MEDIA-STUBS.md](WEBKIT-MEDIA-STUBS.md))

The media and WebKit native libraries are not compiled from source; see
[WEBKIT-MEDIA-STUBS.md](WEBKIT-MEDIA-STUBS.md) for how to supply prebuilt
libraries. The assembled SDK lands in `sdk/target/sdk/`.

# Contribute

Please contact BenEsquivelMusic@gmail.com if you would like to contribute to this project.
