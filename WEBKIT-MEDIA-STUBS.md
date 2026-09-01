# Web Testing

The web project needs WebKit and Media shared libraries to run tests.

These can be supplied in a number of ways. See sections below.

## Compiled from source

The Maven build in this fork does not yet compile the WebKit and Media native
libraries from source (the former Gradle COMPILE_WEBKIT / COMPILE_MEDIA
switches). Use one of the options below to supply prebuilt libraries.


## Prebuilt libraries

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
