# Updating the JavaFX Release Version

Here are the instructions for updating the JavaFX release version number
for a feature release or security (dot-dot) release.
See [JDK-8226365](https://bugs.openjdk.org/browse/JDK-8226365)
for a recent example.

## Incrementing the feature version

Here are the steps to increment the JavaFX release version number to a new
feature version (for example, from 13 to 14).

* In `.jcheck/conf`, modify the `version` property in the `[general]`
section to increment the JBS version number from `jfx$N` to `jfx$N+1`.

* In `build.properties`, modify the following properties to increment the
feature version number from `N` to `N+1`:

```
    jfx.release.major.version
```

* In `pom.xml`, make the same change to the `jfx.release.major.version`
property and update `jfx.release.version` to match. The Maven build reads its
version properties from `pom.xml`, not from `build.properties`, so the two
files must be kept in sync.

* Update the Maven project version (the `<version>` element in `pom.xml` and
the `<version>` in the `<parent>` block of every module `pom.xml`) to the new
release version, for example from `$N-ea` to `$N+1-ea`.

* In
`modules/javafx.base/src/test/java/test/com/sun/javafx/runtime/VersionInfoTest.java`,
modify the `FEATURE` variable to increment the feature version number
from `N` to `N+1`.

## Incrementing the security version

Here are the steps to increment the JavaFX release version number to a new
security version (for example, from 13 to 13.0.1).

* In `.jcheck/conf`, modify the `version` property in the `[general]`
section to increment the JBS version number from `jfx$N` to `jfx$N.0.1`
or from `jfx$N.0.M` to `jfx$N.0.$M+1`.

* In `build.properties`, modify the `jfx.release.security.version` property
to increment the security version number from `M` to `M+1`.

* In `pom.xml`, make the same change to the `jfx.release.security.version`
property and update `jfx.release.version` to match (it is the dot-separated
concatenation of the four `jfx.release.*.version` values with trailing zero
fields removed).

* Update the Maven project version (the `<version>` element in `pom.xml` and
the `<version>` in the `<parent>` block of every module `pom.xml`) to the new
release version.
