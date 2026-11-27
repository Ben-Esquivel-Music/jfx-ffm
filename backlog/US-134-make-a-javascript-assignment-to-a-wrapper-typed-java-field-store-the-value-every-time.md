# US-134 — Make a JavaScript assignment to a wrapper-typed Java field store the value every time

**Status:** 📋 Ready (filed 2026-11-27) · **Found:** 2026-11-27, running the `javafx.web` tests on Windows against the
newly installed `jfxwebkit-28-b6`; the failure is intermittent and also occurs with `jfxwebkit-28-b5`, so the new
library did not bring it; the cause is not determined

## Story
As a JavaFX app developer who exposes a Java object to a page,
I want a script's assignment to a public field of type `Number`, `Double`, `Integer`, `Boolean` or `Character` to
store the value in the field every time,
so that the field is never left `null` after the script has run without an error.

## Problem
`JavaScriptBridgeTest.testMethodCallWithWrapperObjects` (JDK-8141386) binds an object with public wrapper-typed fields,
assigns to them from a script and reads them back. In about half of the runs one direct assignment leaves the field
`null`; `executeScript` reports no error, and the test then fails with a `NullPointerException` on the read. The calls
through a setter method (`obj.setNumberVal(1.23)`, ...) made in the same test never failed.

### What was run
Windows 10 10.0.19045, Temurin 27+35, tree `fcfc41cf48`, whose `modules/javafx.web` is that of `5a709c189d`.
- The web module's `default-test`, the DLL being `jfxwebkit-28-b6` (built from `36a5153454`, sha256 `6022c77e...`):
  492 tests, 0 failures, 1 error, 113 skipped. The error: `JavaScriptBridgeTest.java:364`, `"<local2>.n1" is null`,
  after `obj.n1 = 1.23` (`:363`). Command:
  `mvn -B -ntp -pl modules/javafx.web -am test -Djfx.web.skipTests=false
  '-Dtest=test/com/sun/webkit/**,test/javafx/scene/web/**' -Dsurefire.failIfNoSpecifiedTests=false
  '-Djfx.native.librarypath=<media target\native\bin>;<graphics target\native\bin>;<dir of jfxwebkit.dll>'`.
- The class alone (`-Dtest=JavaScriptBridgeTest`, 22 tests), each run a new JVM:

  | DLL | Runs | Failed | Where |
  | --- | --- | --- | --- |
  | `jfxwebkit-28-b6` | 3 | 2 | both at `:381`, `"<local2>.b1" is null`, after `obj.b1 = true` (`:380`) |
  | `jfxwebkit-28-b5` (built from `65947b61b7`, sha256 `8233e6fb...`) | 4 | 2 | both at `:381`, the same message |

  The surefire output of each run names the DLL that was loaded (`-Djavafx.verbose=true`).
- Not run: another machine, Linux, macOS, an older commit, a JDK other than Temurin 27+35. The Windows gate of
  2026-11-21 recorded for US-025 passed this class once (`backlog/US-025-windows-per-class.tsv`).

### The path (read from the code; which step loses the value was not determined)
- `JavaField::setValueToInstance` (`Source/WebCore/bridge/java/jsc/JavaFieldJSC.cpp:160`) converts the script value
  with `convertValueToJavaValue`, holds it in a `JavaValueScope` and calls `javaFieldSet`
  (`Source/WebCore/bridge/java/JavaBridgeUtility.cpp:278-283`), the host slot `field_set`.
- `LiveConnectNative.fieldSet` (`com/sun/webkit/dom/LiveConnectNative.java:567`) stores, for an object-typed field,
  `WebKitNative.lookup(id)` of the id in the value. `WebKitNative.lookup` (`com/sun/webkit/WebKitNative.java:570`)
  answers `null` "for an id that is no longer registered and for a weak id whose object has been collected", and
  `fieldSet` then stores that `null` and reports success.

## Requirement
1. A script's assignment to a public field of a wrapper type of a bound Java object stores the converted value in the
   field whenever `executeScript` returns without an error.
2. The cause of the lost value is found and stated with its evidence; the fix removes that cause.

## Constraints
- No assertion of `JavaScriptBridgeTest` is removed or weakened, and the test is not retried, reordered or disabled.
- The names `jfxwebkit` exports and the names the Java facades bind stay the same unless the cause requires a change;
  a change follows `modules/javafx.web/FFM-ABI-CONTRACT.md`.
- A native change needs a rebuilt `jfxwebkit` on every platform (`build-webkit.yml`); a Java-only fix does not.
- No robot test and nothing under `tests/system`.

## Acceptance criteria
1. **Reproduced before the fix:** the failure rate of `-Dtest=JavaScriptBridgeTest` over at least 10 runs on the
   unfixed tree is recorded, with the machine and the DLL.
2. **Proven cause:** a test or probe fails for the stated cause on the unfixed tree every time, not intermittently,
   and passes after the fix; undoing the fix makes it fail again.
3. **Stable:** `-Dtest=JavaScriptBridgeTest` passes 20 runs in a row on Windows after the fix.
4. **Nothing else changes:** the web module's `default-test` gives its per-class counts of before plus the tests this
   story adds, on Windows and Linux; `submit.yml` is green.

## Definition of Done
- All acceptance criteria met, logs kept, results stated here.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without
  explicit approval from the repository owner.**
