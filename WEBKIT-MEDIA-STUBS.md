# Web Testing

The web project needs WebKit and Media shared libraries to run tests.

These can be supplied in a number of ways. See sections below.

## Compiled from source

The Maven build in this fork does not yet compile the WebKit and Media native
libraries from source (the former Gradle COMPILE_WEBKIT / COMPILE_MEDIA
switches). Use one of the options below to supply prebuilt libraries.


## Cached libraries

You can manually place WebKit and Media shared libraries in these folders
(`$projectDir` is the repository root, so `caches` sits *next to* the
repository, outside it — safe from `mvn clean`):

* Unix libraries (*.so or *.dylib files)
````
    $projectDir/../caches/sdk/lib
````

* Windows libraries (*.dll files)
````
    $projectDir/../caches/sdk/bin
````

The Maven build puts both cache folders on `java.library.path` for the
`javafx.web` unit tests and the `tests/system` test and worker JVMs (property
`jfx.native.librarypath` in the root pom), so libraries placed there are picked
up automatically on the next test run.

## Officially released libraries

You can download officially released libraries from
[MavenCentral](https://search.maven.org/search?q=g:org.openjfx%20AND%20a:javafx)
(artifacts `javafx-web` and `javafx-media` with your platform classifier) and
extract the shared libraries into the cache folders above.

Note that these libraries may not be compatible with the source tree you are working with. Always use the [latest version](https://search.maven.org/search?q=g:org.openjfx%20AND%20a:javafx); this may improve your chances of compatibility.


## Skip Web tests

The web module tests are skipped by default in the Maven build (they require
the native `jfxwebkit` library). To run them, pass:

    -Djfx.web.skipTests=false

Note that skipping is fine for local work. But a full test *is* required before submitting a PR, see [CONTRIBUTING.md](https://github.com/openjdk/jfx/blob/master/CONTRIBUTING.md).
