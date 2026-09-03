# Web Testing

The web project needs WebKit and Media shared libraries to run tests.

These can be supplied in a number of ways. See sections below.

## Compiled from source

The Maven build in this fork does not compile the WebKit and Media native
libraries from source (the former Gradle COMPILE_WEBKIT / COMPILE_MEDIA
switches).

For WebKit there is a GitHub Actions workflow that does it instead:
`.github/workflows/build-webkit.yml` ("Build jfxwebkit"). It is
`workflow_dispatch` only, and drives the WebKit CMake tree through
`modules/javafx.web/src/main/native/Tools/Scripts/build-webkit` on every
supported platform:

| Platform | Runner | Produces |
|---|---|---|
| linux-x64 | `ubuntu-24.04` | `lib/libjfxwebkit.so` |
| linux-aarch64 | `ubuntu-24.04-arm` | `lib/libjfxwebkit.so` |
| macos-x64 | `macos-15-intel` | `lib/libjfxwebkit.dylib` |
| macos-aarch64 | `macos-15` | `lib/libjfxwebkit.dylib` |
| windows-x64 | `windows-2022` | `bin/jfxwebkit.dll` |

Each job verifies that the library exports the `wkj_*` FFM entry points before
publishing, and the run uploads one zip per platform to the repository's
Releases page. Because the archives contain the `bin/` or `lib/` directory
already, they extract straight into `caches/sdk` (see below).

The build takes hours per platform, so run it only when the WebKit native
sources or the FFM ABI change. `ccache` is enabled and cached between runs.

For Media there is no equivalent yet; use one of the options below.


## Prebuilt libraries

> **The Media libraries are now built from source, and a prebuilt `jfxmedia`
> from an older OpenJFX SDK no longer works.** On the `ffm/media` branch
> `javafx.media` calls a plain C ABI (`jfxm_*`) instead of JNI, and
> `modules/javafx.media/pom.xml` builds `jfxmedia`, `gstreamer-lite`,
> `glib-lite` and `fxplugins` through CMake like the graphics natives (see
> `modules/javafx.media/FFM-BUILD-PLAN.md`); `-DskipNative=true` skips that.
> A JNI-era `jfxmedia` exports `Java_*` entry points but none of the `jfxm_*`
> symbols, so loading one fails with
> `UnsatisfiedLinkError: missing native symbol: jfxm_abi_version` and the media
> stack reports itself unavailable. Delete any stale `jfxmedia*`,
> `gstreamer-lite*`, `glib-lite*` and `fxplugins*` from `../caches/sdk/{bin,lib}`
> rather than letting them shadow the freshly built ones — the root pom puts
> `modules/javafx.media/target/native/bin` first on `java.library.path`, but the
> cache directories are still on it. This note does not apply to `jfxwebkit`,
> which is still supplied prebuilt.

You can manually place the WebKit and Media shared libraries (`*.dll`, `*.so`
or `*.dylib`) in the directory the build already uses as `java.library.path`:

````
    modules/javafx.graphics/target/native/bin
````

This is the same directory the javafx.graphics native build writes to, and the
one passed as `-Djava.library.path` by both the web module tests
(`modules/javafx.web/pom.xml`) and the system tests (`tests/system/pom.xml`).
The SDK assembly also copies every shared library found there into
`sdk/target/sdk`, so libraries dropped in before `mvn install` end up in the
assembled SDK as well.

The web module loads `jfxwebkit`; the media module loads `jfxmedia` together
with its platform dependencies (`glib-lite`, `gstreamer-lite`, `fxplugins`,
and `jfxmedia_avf` on macOS).

The Maven build also puts `../caches/sdk/bin` and `../caches/sdk/lib`
(relative to the repository root) on `java.library.path` for the `javafx.web`
unit tests and the `tests/system` test and worker JVMs (property
`jfx.native.librarypath` in the root pom), so cached libraries are picked up
automatically on the next test run.

## Officially released libraries

You can download officially released libraries from
[MavenCentral](https://search.maven.org/search?q=g:org.openjfx%20AND%20a:javafx)
(artifacts `javafx-web` and `javafx-media` with your platform classifier) and
extract the shared libraries into the folder above.

Note that these libraries may not be compatible with the source tree you are working with. Always use the [latest version](https://search.maven.org/search?q=g:org.openjfx%20AND%20a:javafx); this may improve your chances of compatibility.


## Skip Web tests

The web module tests and the WebKit-dependent Robot tests in `tests/system` are excluded by
default (`jfx.web.skipTests=true`). To run the web module tests, pass:

    -Djfx.web.skipTests=false

To run only the WebKit-dependent Robot tests, use:

    mvn -pl tests/system test -DFULL_TEST=true -DUSE_ROBOT=true -Djfx.web.skipTests=false -Dsurefire.includes='test/robot/javafx/web/**/*.java'

Setting `jfx.web.skipTests=false` requires an ABI-compatible `jfxwebkit` rebuilt for the current
source tree. A released binary from another JavaFX revision may load but still have an incompatible
native ABI.

Note that skipping is fine for local work. But a full test *is* required before submitting a PR, see [CONTRIBUTING.md](https://github.com/openjdk/jfx/blob/master/CONTRIBUTING.md).
