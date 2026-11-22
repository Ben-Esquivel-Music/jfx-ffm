# US-025 — Fix three uninitialized or dangling members in the Java branches of WebKit

**Status:** 🔷 Implemented (2026-11-21, uncommitted; filed 2026-09-30): the three fixes in five WebKit files; on
Windows, native probes linked into private copies of `jfxwebkit` show each fixed and kill every mutant, and the
`modules/javafx.web` tests pass against a local `jfxwebkit` with the change (492 / 0 / 0 / 113: the baseline's 489 plus
the new `CustomCursorTest`); left for the owner: the commit and the PR, the `tests/system` web tests, the manual
on-screen cursor check, a `build-webkit.yml` run and filing the upstream issue ([steps](#left-for-the-owner)) ·
**Found:** 2026-09-30, fixing the `TextureMapper` crash behind the `TextureMapperTest` failure: a read-only sweep of all
573 `PLATFORM(JAVA)` guard sites (264 files) for the same bug class, a member the Java build leaves uninitialized but
still uses

## Story
As a JavaFX app developer who embeds a WebView,
I want the Java branches of WebKit's locale, complex-text and cursor code to initialize every member they use and never
return a reference to a local,
so that turning on date and month inputs, a non-monotonic complex-text run or a custom CSS cursor cannot read freed or
garbage memory or report the wrong cursor.

## Problem
Paths are relative to `modules/javafx.web/src/main/native/Source/WebCore`. The Windows and Linux Java builds use system
malloc, so heap memory is not zero-filled. All three predate the FFM port (upstream OpenJFX has the same code).

1. **`LocaleNone` returns dangling references.** Java is the only port that uses `LocaleNone` (`Locale::create` returns
   it; `SourcesJava.txt:94`). The Java `#else` branches of `monthLabels()` (`platform/text/LocaleNone.cpp:67-81`) and
   `shortMonthLabels()` (`:118-132`) declare a local `Vector<String> m_monthLabels;` / `m_shortMonthLabels;` that
   shadows the member, fill it, and return a `const Vector<String>&` to it, which dangles once the function returns.
   `shortMonthLabels()` also appends the short names into the member `m_monthLabels`, so a later `monthLabels()` call
   returns the short names. Readers: `DateTimeStringBuilder` for `MMM`/`MMMM` (`platform/text/PlatformLocale.cpp:105-108`)
   and `html/shadow/DateTimeEditElement.cpp:136/141`. Not reachable in production today: `LocaleNone`'s own formats are
   numeric (`MM`), and the date and month input types are off for WebKitLegacy (only the color input is switched on, in
   `WebKitLegacy/java/WebCoreSupport/WebPage.cpp`). DumpRenderTree/Internals settings can reach it. Introduced by
   `4a8f3abc8e` (8356982, WebKit 622.1).
2. **`ComplexTextRun::m_stringLength` is uninitialized in the generic constructors.** The Java-only
   `unsigned m_stringLength;` (`platform/graphics/ComplexTextController.h:163-165`) has no initializer. Only the Java
   constructor sets it (`platform/graphics/java/ComplexTextControllerJava.cpp:130`). The generic missing-glyphs
   constructor (`platform/graphics/ComplexTextController.cpp:942`), which the Java port calls at
   `ComplexTextControllerJava.cpp:184/190/217`, and the vector constructor (`:975`) leave it as heap garbage. It is read
   by the Java clamps in `indexAt` (`ComplexTextController.cpp:499-503`) and `setIsNonMonotonic` (`:526-527`); a garbage
   0 turns the clamp into `UINT_MAX` and `Vector::at` crashes. Today's paths do not read it for generic runs
   (missing-glyph runs are monotonic, and `indexAt` is called below the glyph count).
3. **The Java custom `Cursor` constructor leaves the type unset.** `Cursor::Cursor(Image*, const IntPoint&)` in
   `platform/java/CursorJava.cpp:43-65` replaces the generic constructor (`platform/Cursor.cpp:155-162`, under
   `#if !PLATFORM(JAVA)`) but sets only `m_platformCursor`. `m_type` stays `Type::Invalid`, `m_image` null and
   `m_hotSpot` 0,0 (the defaults at `platform/Cursor.h:183-185`). A `cursor: url(...)` cursor reports `Type::Invalid`
   (`testing/Internals.cpp` `getCurrentCursorInfo`), and `Cursor::type()`'s `ASSERT(m_type > Type::Invalid)`
   (`Cursor.h:251`) fires in debug builds. No memory-safety impact.

## Proposed fix
1. In both Java branches, delete the local declarations so the members are filled and persist, and make
   `shortMonthLabels()` fill `m_shortMonthLabels` instead of `m_monthLabels`.
2. Delete the Java-only member and use `stringLength()` (`m_characters.size()`, which the Java constructor sets from the
   same `stringLength`) at `ComplexTextController.cpp:502/526/527` and `ComplexTextControllerJava.cpp:138`. This also
   removes a Java divergence from an upstream header. Minimal alternative: `unsigned m_stringLength { 0 };` plus a value
   in the two generic constructors.
3. Initialize `m_type(Type::Custom), m_image(image), m_hotSpot(determineHotSpot(image, hotspot))` in the Java
   constructor and keep its platform-cursor body. Check first that nothing on the Java side relies on `type()` being
   `Invalid` for custom cursors.

## Acceptance criteria
- `monthLabels()` and `shortMonthLabels()` return the 12 full and the 12 short month names from members that persist
  across calls; a DRT or unit check formats a month with `MMM` and `MMMM`.
- No constructor compiled for Java leaves a `ComplexTextRun` member uninitialized.
- A custom CSS cursor reports `Type::Custom` with its image and hotspot, and still shows on screen (manual check on
  Windows).
- `perl buildtools/ffm-web/verify-no-jni.pl` stays at zero.

## Definition of Done
The fix is merged; the PR names the `jfxwebkit` it was verified with (a local build or `build-webkit.yml`), with the
`tests/system` web tests and the `modules/javafx.web` `default-test` passing against it; an upstream issue is drafted
for all three.

## Implementation (2026-11-21, Windows)
The change is described against `6832730e09`, the baseline ("before"). Every run was made on one host, Windows 10 Pro
(10.0.19045), in the main tree. WebKit was built in the local build tree `build/jfxwebkit-windows-release` (CMake and
Ninja 1.12.1, `PORT=Java`, Release, `clang-cl` 19.1.7 from `build/toolchain/llvm-19.1.7`, MSVC 14.44.35207
`link.exe`, the Visual Studio 2022 `vcvars64` environment, `-j4`; the command is under [Libraries](#libraries)). The
gates ran Temurin 27+35 (their surefire XML: `java.runtime.version` 27+35, `java.vendor` Eclipse Adoptium); the gate
logs do not print the Maven version (`-B` without `-V`), and no run recorded it. Linux and macOS were not built:
`submit.yml` does not compile WebKit, and a `build-webkit.yml` run after the commit is to cover them (both in
`.github/workflows`). Paths that start with `modules/`, `build/`, `buildtools/`, `tests/`, `apps/`, `sdk/`, `backlog/`
or `../caches/` are relative to the repository root, other source paths to
`modules/javafx.web/src/main/native/Source/WebCore`, as in Problem; a bare source file name stands for the path given
for it elsewhere in this file; line numbers are those of the tree with the change. Times are America/New_York.

The work, all on 2026-11-21: an upstream check and an attack on the plan, both read-only (the attack's points on how to
verify were adopted); the baseline `jfxwebkit` from a clean `6832730e09` (13:58 to 15:49) and the baseline gate (15:57
to 16:03); the fix and the first "after" build (16:11 to 16:14); the author's native probes, their mutants and two
mutant libraries (16:18 to 16:55); the first version of the Java test, whose expectation for an out-of-image hotspot was
wrong, and the first after gate, which failed only on it (17:05 to 17:16); the test's rework and its runs against four
libraries (17:23 to 18:33); the second after gate (18:35 to 18:41); an independent review with its own probes and runs
(18:51 to 19:36); the rework of two comments and the final build (19:41 to 19:46); a confirming review; the final gate
(20:00 to 20:06).

Kept with the change:
- [`US-025-native-probes.md`](US-025-native-probes.md): why and how the native probes were made, with the author's and
  the reviewer's commands, sources, mutants and results;
- [`US-025-windows-per-class.tsv`](US-025-windows-per-class.tsv): the per-class lists of the baseline and final gates.

Not kept: the libraries, the build, gate and test logs, the surefire reports, the mutant copies, and the probe bundles,
objects, links and outputs. They are in the temporary scratch directory
`C:/Users/bestq/AppData/Local/Temp/claude/50b9cf433e233190/57a996ef-5884-4236-b898-bc34b697e4df/scratchpad/us025`
until it is cleaned; the final library is also `build/jfxwebkit-windows-release/bin/jfxwebkit.dll` until the next
WebKit build in that tree.

### Choices
- **`LocaleNone`:** the two local declarations are deleted and `shortMonthLabels()` fills its own member. The
  `#if !PLATFORM(JAVA)` / `#else` stays, as the smallest edit to an upstream file (upstream WebKit has no Java branch
  there; it assigns the member).
- **`ComplexTextRun`:** the story's primary option. The Java-only member is deleted and its reads use `stringLength()`,
  that is `m_characters.size()`, which the Java constructor builds from the same `stringLength` argument: a run built by
  Java reads the same values, a generic run its real length instead of garbage. Against the minimal alternative: it is a
  second copy of the length that every constructor would have to remember, and the deletion removes a Java block from
  an upstream header.
- **The custom `Cursor` constructor** sets the type, the image and the hotspot. A null image is still tolerated (the old
  body returned early for it, and `determineHotSpot` would dereference it). The page's raw hotspot still goes to Java:
  the story says to keep the platform-cursor body, Java's `ImageCursor` clamps the hotspot to the image, and
  `CustomCursorTest` pins it. So for a hotspot outside the image the cursor reports another hotspot than Java shows:
  for a page hotspot -2,4 the independent review's probe reports 0,0 and an upcall with -2,4, which `ImageCursor`
  clamps to 0,4 by reading (no run saw this case on the Java side: `CustomCursorTest` uses 3,5 and 20,20).
- **The `Custom` arm of `ensurePlatformCursor` falls back to the pointer** instead of constructing a cursor again.
  Before the change `m_type` was never `Custom` on Java, so a custom cursor without a platform cursor ended in
  `default:`, the pointer. With the type set, the old arm would make a second `cursor_get_custom_id` upcall with the
  corrected hotspot and hand Java 0 or a new id; with the fallback, the id that reaches Java and the number of upcalls
  stay as they were (the reviewer's probe: equal to the baseline's). Assumption: `ENABLE_MOUSE_CURSOR_SCALE` stays off
  for the Java port. Its generic three-argument constructor (`platform/Cursor.cpp:164-172`) never asks Java for a
  platform cursor, and the old arm was the only thing that would have covered it; today no Java build compiles it
  (`OptionsJava.cmake` does not set the option, `WebKitFeatures.cmake:207` defaults it off; both are in
  `modules/javafx.web/src/main/native/Source/cmake`). An optional review finding, recorded here and not changed.
- **Verification.** Nothing on the Java port reaches `LocaleNone`'s month labels or `Cursor::type()`
  ([Corrections](#corrections-to-the-story-as-filed)), so the checks of acceptance criteria 1 and 3 are native probes
  linked into private copies of the library ([evidence](US-025-native-probes.md)). The committed test guards the path
  of a custom cursor to the `WebView`; it passes with and without the fix.
- **Acceptance criterion 4** names `perl buildtools/ffm-web/verify-no-jni.pl`, which US-074 deleted; its successor,
  `java buildtools/CheckNoJni.java .`, was run.
- **Added beyond the story's text:** `CustomCursorTest` (the story asks only for a manual check on screen), and
  [US-129](US-129-give-the-predefined-cursors-of-the-java-webkit-port-their-type.md) for the predefined cursors, whose
  type the Java port does not set either.

### Change
`git diff --stat -- modules/`: 5 files, 12 insertions, 15 deletions; and one new test file.
- `platform/text/LocaleNone.cpp`: in `monthLabels()` (`:67-80`) the Java branch (`:74-79`) no longer declares a local
  `Vector<String> m_monthLabels;`, so `:75-77` fill the member and `:78` returns it. In `shortMonthLabels()`
  (`:117-130`) the Java branch (`:124-129`) no longer declares a local `m_shortMonthLabels`, and `:125` and `:127`
  reserve and append on `m_shortMonthLabels` where they used `m_monthLabels`.
- `platform/graphics/ComplexTextController.h`: `#if PLATFORM(JAVA)`, `unsigned m_stringLength;` and `#endif` are
  deleted (they followed `m_characters`, now `:162`); `stringLength()` is `:126`.
- `platform/graphics/ComplexTextController.cpp`: the Java clamps of `indexAt` (`:502`) and `setIsNonMonotonic`
  (`:526-527`) read `stringLength()`.
- `platform/graphics/java/ComplexTextControllerJava.cpp`: the Java constructor (`:126-176`) loses
  `, m_stringLength(stringLength)`, and its empty-run test (`:137`) reads the parameter `stringLength`, which shadows
  the member function in that body.
- `platform/java/CursorJava.cpp`: the constructor `Cursor(Image*, const IntPoint&)` (`:43-68`) initializes, in the
  declaration order of `platform/Cursor.h:183-185` and `:193`:
  ```cpp
      : m_type(Type::Custom)
      , m_image(image)
      , m_hotSpot(image ? determineHotSpot(image, hotspot) : IntPoint())
      , m_platformCursor(0)
  ```
  Its body is unchanged: the upcall (`:64-66`) still passes `hotspot.x(), hotspot.y()`, under the new comment at `:63`:
  ```cpp
      // Not m_hotSpot: Java's ImageCursor clamps the page's hotspot to the image, determineHotSpot makes one outside 0,0.
  ```
  In `ensurePlatformCursor` (`:80-207`) the `Custom` arm (`:202`) lost `setPlatformCursor(Cursor(m_image.get(),
  m_hotSpot));` and its `break;`, so it falls through to `default:` (`:203-205`), under the comment at `:201`:
  ```cpp
      // A custom cursor gets its platform cursor in the constructor; if that failed, a retry fails too: show the pointer.
  ```
- `modules/javafx.web/src/test/java/test/javafx/scene/web/CustomCursorTest.java`: new, 180 lines
  ([The test](#the-test)); 181 since the [PR #72 review](#pr-72-review-2026-11-22).

The five native files are LF only, with no tab and no trailing blank, before and after; no added line is over 120
columns (the two comments are 120). The Oracle headers of `CursorJava.cpp` and `ComplexTextControllerJava.cpp` already
read 2026 (`2011, 2026` and `2018, 2026`); the three upstream WebKit files keep their headers. `ComplexTextRun`'s
`m_stringLength` was on 6 lines in 3 files and is on none (a plain `git grep -c m_stringLength` over `Source` counts 38
lines before and 32 after: the other 32 belong to unrelated classes). The test is LF only, with no tab, no trailing
blank and no non-ASCII character; its longest line has 118 columns.

### Libraries
Built with this command (Git Bash; the build tree's `bin/jfxwebkit.dll` as the target, so only what it needs is
rebuilt):
```bash
MSYS2_ARG_CONV_EXCL='*' cmd /v:on /c "call C:\PROGRA~1\MICROS~4\2022\COMMUN~1\VC\AUXILI~1\Build\vcvars64.bat 1>NUL: && set PATH=C:\SourceCode\jfx-ffm\build\toolchain\llvm-19.1.7\bin;!PATH!&& set RUBYLIB=/home/bestq/.local/share/gem/ruby/4.0.0/gems/getoptlong-0.2.1/lib&& C:\PROGRA~1\MICROS~4\2022\COMMUN~1\Common7\IDE\COMMON~1\MICROS~1\CMake\Ninja\ninja.exe -C C:\SourceCode\jfx-ffm\build\jfxwebkit-windows-release -j4 bin/jfxwebkit.dll"
```

| Library | sha256 | Bytes | Built | Used by |
| --- | --- | --- | --- | --- |
| Baseline (`dll-before`) | `f0a91dd650e68f75a7e91c32b3f0815fb09d1f1292ca7d175a6603fca02a0e2f` | 100,508,672 | clean `6832730e09`, 13:58:52 to 15:49:34 (1 h 50 min 42 s), 1,607 ninja steps | the baseline gate; `CustomCursorTest` run (b); the review's run against the baseline |
| First after (`dll-after`) | `6c2896316485bcac05b46d3a306c97cac4437069891c7d7a5dbb3dbc86dbabc6` | 100,509,184 | the change as reviewed, incremental, 16:11:39 to 16:14:44, 14 steps (the two binding generators, a header copy, 9 unified bundles, `WebCore.lib`, the DLL) | the first and second after gates; `CustomCursorTest` runs (a) and (e1) to (e5); the review's runs |
| Final (`dll-final`) | `d55e45271ef80eb81aab81875bb877740616246f82dfb8b1f17269d2389f7647` | 100,509,184 | the final change, 19:43:10 to 19:46:07 (177 s), 5 steps (the two binding generators, bundle 28 with `CursorJava.cpp`, `WebCore.lib`, the DLL) | the final gate |
| `dll-mut-noupcall` | `fc93e6d5ca1e64f04bc4a1c06f7a9467fdfd9d311d36e1031f60f84de60d337d` | 100,509,184 | linked at 16:47 outside the build, bundle 28 with `return;` before the upcall ([evidence](US-025-native-probes.md#mutants)) | `CustomCursorTest` run (c) |
| `dll-mut-hotspot` | `7b0924d4cc5a1e3244c856f49e9fb7f82dbe798a19bab128e703231dd2505253` | 100,509,184 | the same, with `m_hotSpot` passed to Java | `CustomCursorTest` run (d) |

- Every library exports 15,249 names, 1,963 of them `wkj_`, and their name lists are identical (`dumpbin /EXPORTS`).
  The baseline has `wkj_abi_version` at ordinal 13287, RVA `03183970`, and `wkj_init` at 15140, `03183980`; the first
  after and the final library `03183A70` and `03183A80`.
- **Final against first after.** The `.text` section is byte-identical (sha256
  `41f50b45d41901b630b6c183645c772e5ad8bf90c82f744299de6ed2a09d6f85`, virtual size 56,129,780), and so are `.data`,
  `.pdata`, the two `__DATA,_` sections, `.rsrc` and `.reloc`. Of the file's bytes 9 differ: 2 in the headers (the COFF
  time stamp) and 7 in `.rdata`: 6 in the debug directory (the time stamps of its three entries, 2 bytes each) and 1 in
  the CodeView record that its first entry points to (the PDB age, 5 and 6). The export tables (ordinal, hint, RVA,
  name) are identical. In the object of bundle 28, the 92 of 1,701 sections that differ are all `.debug$S` (line
  numbers moved by the inserted line, and the source file's MD5); no relocation table differs and none of the 463
  `.text` sections. The rework and the confirming review found this each with its own tools. So the probe and test
  runs on the first after library stand for the final one; apart from the final gate, nothing ran against the final
  library.
- `C:/SourceCode/caches/sdk/bin/jfxwebkit.dll` is an older build (2026-09-30, 100,508,672 bytes, sha256
  `ee1db356c8636f8a7d490cf19f50c9a11259b49ce4b473ae38e8f1025edafe7d`, of `db7cf93efe` plus the then-uncommitted
  `TextureMapper` fix). No run of this story had it on its library path.

### Corrections to the story as filed
The filed sections above are unchanged. What the runs and reads of 2026-11-21 show:

| Filed text | Says | Found |
| --- | --- | --- |
| Problem 1 | "DumpRenderTree/Internals settings can reach it." | They can switch the date and month input types on, but `LocaleNone`'s formats stay numeric (`html/MonthInputType.cpp:158` uses `shortMonthFormat()`, `yyyy-MM`), so no format asks for a label, and `testing/Internals.idl` has no locale hook (the plan attack, by reading). Nothing on the Java port reaches the month labels; the check is a native probe |
| Problem 1, readers | `PlatformLocale.cpp:105-108` (`MMM`/`MMMM`) | Also `LLL`/`LLLL` at `:116` and `:118`; `Locale::formatDateTime`'s only caller is `html/BaseDateAndTimeInputType.cpp:257` |
| Problem 1 | `shortMonthLabels()` appends the short names into `m_monthLabels` | Every call appends another 12: the probe's mutant with only that defect left holds 24 and then 36 entries |
| Problem 1 | introduced by `4a8f3abc8e` | It is the latest upstream commit that touches the file; that the block first appears there was not checked |
| Problem 2 | the vector constructor (`:975`) leaves it as garbage | True, but no code of the Java build calls that constructor (`ENABLE_API_TESTS` is off) |
| Proposed fix 3 | keep the platform-cursor body | With the type set, the `Custom` arm of `ensurePlatformCursor` becomes reachable, and as it was it would make a second upcall and hand Java 0 or a new id instead of the pointer (the plan attack). Handled by [Choices](#choices) |
| AC 1 | "a DRT or unit check formats a month with `MMM` and `MMMM`" | Neither can reach `LocaleNone` (first row); the formatting is checked in the native probes |
| AC 3 | reports `Type::Custom` | On Java only `testing/Internals.cpp:4498` reads the type, and DumpRenderTree is not run; checked in the native probes |
| AC 4 | `perl buildtools/ffm-web/verify-no-jni.pl` | Deleted by US-074 (`buildtools/ffm-web` holds 4 other files); its successor `java buildtools/CheckNoJni.java .` was run |
| Line references | the lines of `6832730e09` | With the change: `LocaleNone.cpp:67-80` and `:117-130`; `ComplexTextControllerJava.cpp:130` is gone, `:138` is `:137`, the missing-glyphs calls `:183`, `:189`, `:216`; `CursorJava.cpp:43-68`; `Cursor.h:183-185` and `:251` unchanged |

### Acceptance criteria and evidence

| Criterion | Evidence | State |
| --- | --- | --- |
| 1. The month lists are 12 full and 12 short names from members that persist; a month is formatted with `MMM` and `MMMM` | Native probes ([evidence](US-025-native-probes.md)). The author's: in both call orders each list comes from the same address inside the locale object on every call (`monthLabels` at +176, `shortMonthLabels` at +160 of 192 bytes), holds 12 entries after every call and equals `WTF::monthFullName` or `WTF::monthName`; `Locale::formatDateTime` gives `March\|March`, `Mar\|Mar`, `September\|September` and `Sep\|Sep` for `MMMM\|LLLL` and `MMM\|LLL`. The reviewer's: 7 calls in each of two orders, the stand-alone variants included, and all 12 months of 2031 with `MMM yyyy` and `MMMM yyyy`; 107 checks | Met, by native probes |
| 2. No constructor compiled for Java leaves a `ComplexTextRun` member uninitialized | The member is deleted. The review's table of the three constructors compiled for Java (the Java one, the missing-glyphs one, the vector one; the CoreText and HarfBuzz ones are in no bundle): every member is set in the initializer list or the body, or has a default initializer, before it is read. The reviewer's probes: 53 value checks and 33 clamp checks; Java-built runs read what `6832730e09` computes, generic runs clamp to their real length | Met |
| 3. A custom CSS cursor reports `Type::Custom` with its image and hotspot | Native probes: the author's, without a host (type `Custom`, the image passed in, hotspot 3,5 for 3,5 and 0,0 for 40,40, a null image and `Image::nullImage()` tolerated, the pointer as the fallback), and the reviewer's, with a fake host (also the upcall count and arguments and the ids, equal to the baseline's) | Met, by native probes |
| 3. ... and still shows on screen (manual check on Windows) | `CustomCursorTest` shows a `cursor: url(...)` reaching the `WebView` as an `ImageCursor` with its image and hotspot, with the final library in the final gate; nobody looked at a screen | Left for the owner ([step 3](#left-for-the-owner)) |
| 4. `perl buildtools/ffm-web/verify-no-jni.pl` stays at zero | The script is gone. `java buildtools/CheckNoJni.java .` after the final gate printed `CheckNoJni: read 28154 files found by git ls-files in 6.4 s (skipped 1193 binary, 405 prose, 2 of its own, 0 listed but missing)` and `CheckNoJni: no JNI found (58 allowlisted hits)`. Its exit code was not recorded: by `buildtools/CheckNoJni.java:289-292` these lines mean exit 0 if no error or stale allowlist entry went to stderr, which the record does not show either; the review's run (19:28, before the comment rework) printed the same counts and recorded `exit=0`. It lists untracked files too (`buildtools/CheckNoJni.java:62`, `:296-297`), so it read the new test | Met, with the successor |
| DoD: merged | – | Left for the owner |
| DoD: the PR names the `jfxwebkit` it was verified with | This local build, sha256 `d55e4527…7647`, or the library of the `build-webkit.yml` run | Left for the owner (the PR text) |
| DoD: the `tests/system` web tests pass against it | Not run: agents do not run them | Left for the owner ([step 2](#left-for-the-owner)) |
| DoD: the `modules/javafx.web` `default-test` passes against it | The final gate, 492 / 0 / 0 / 113 in 51 classes with the final library ([Gates](#gates)) | Met |
| DoD: an upstream issue drafted for all three | One draft, kept outside the repository ([Upstream check](#upstream-check)) | Drafted; filing is the owner's |

### Gates
The command of both gates, in Git Bash; only the last element of `-Djfx.native.librarypath`, the directory that holds
the `jfxwebkit.dll` under test, differs:
```bash
cd /c/SourceCode/jfx-ffm && JDK_JAVA_OPTIONS=-Djavafx.verbose=true mvn -B -ntp -pl modules/javafx.web -am test -Djfx.web.skipTests=false '-Dtest=test/com/sun/webkit/**,test/javafx/scene/web/**' -Dsurefire.failIfNoSpecifiedTests=false '-Djfx.native.librarypath=C:/SourceCode/jfx-ffm/modules/javafx.media/target/native/bin;C:/SourceCode/jfx-ffm/modules/javafx.graphics/target/native/bin;<directory of the jfxwebkit.dll>'
```
`JDK_JAVA_OPTIONS` makes the test fork print which `jfxwebkit.dll` it loaded; the `-Djfx.native.librarypath` value
replaces the root pom's, which would append `../caches/sdk/{bin,lib}`; `-am` builds the javafx.graphics and
javafx.media natives incrementally. The `ffm`-tagged classes stay excluded, as by default.

| | Baseline gate | Final gate |
| --- | --- | --- |
| Library | baseline | final |
| Run | 15:57:11 to 16:03:06, exit 0, BUILD SUCCESS, Maven total 05:53 min | 20:00:13 to 20:06:03, exit 0, BUILD SUCCESS, 05:41 min |
| `javafx-web` `default-test` | 489 / 0 / 0 / 113 in 50 classes | 492 / 0 / 0 / 113 in 51 classes |
| Loaded (`test.com.sun.webkit.WebKitLibraryAbiTest-output.txt`) | `Loaded <scratch>\dll-before\jfxwebkit.dll from java.library.path` | `Loaded <scratch>\dll-final\jfxwebkit.dll from java.library.path` |
| `caches` on `java.library.path` | in none of the 50 `TEST-*.xml` | in none of the 51 |
| Crash files | no `hs_err_pid*` under `modules/javafx.web` | none |

- Per class: the 50 class lines of the baseline are unchanged, and the one new line is `CustomCursorTest`, 3 / 0 / 0 / 0
  ([per class](US-025-windows-per-class.tsv)). The other modules of the reactor ran no surefire test.
- The 113 skipped tests, read from the baseline's surefire XML: 112 upstream `@Disabled` and 1 assumption
  (`LoadTest.loadJrtResource`); the final gate's class lines equal the baseline's, so they were not attributed again.
- Between the two: the first after gate (17:11 to 17:16, first after library) failed with 492 / 1 / 0 / 113, only on
  the first test version's wrong expectation (`hotspot x of the ImageCursor ==> expected: <20.0> but was: <15.0>`);
  the second after gate (18:35 to 18:41, first after library) passed with 492 / 0 / 0 / 113, per class equal to the
  final gate; so did the review's run of the same set (19:28 to 19:33).

### The test
`CustomCursorTest` (`test.javafx.scene.web`, extends `TestBase`, untagged, so in `default-test`) loads a page whose
cursors use a 16x16 PNG made with `ImageIO`, as a data URL; sizes the view and the page to 400x400; waits for the page
load to finish (`TestBase.loadContent`) and then asserts once that an `<img>` of the same URL reports 16x16; and fires
`MOUSE_MOVED` at the `WebView`, repeating the move every 20 ms for at most 5 s until the cursor is an `ImageCursor`
(until WebCore's own request for the image ends, the cursor is built from a null image and reaches the view as the
default cursor).
1. `aCustomCursorWithItsHotspotInsideTheImageReachesTheWebView`: `cursor: url(...) 3 5` reaches the view as an
   `ImageCursor` with a 16x16 image and hotspot 3,5.
2. `aCustomCursorHotspotOutsideTheImageReachesTheWebViewClampedToTheImagesLastPixel`: `cursor: url(...) 20 20`
   reaches it with hotspot 15,15, x and y checked in one `assertAll`. Java gets the page's hotspot and `ImageCursor`
   clamps it (`modules/javafx.graphics/src/main/java/javafx/scene/ImageCursor.java:395-406`); `determineHotSpot`'s 0,0
   would fail it.
3. `aPredefinedCursorAndThenTheDefaultCursorReachTheWebView`: `cursor: pointer` gives `Cursor.HAND`, an element without
   a cursor rule `Cursor.DEFAULT`. A control of the harness that the change cannot affect; its `DEFAULT` assertion is
   weak, since every failure also shows as `DEFAULT` (the review).

It passes with and without the fix: it guards the path of a custom cursor to the `WebView`, not the type, which no Java
code can see. Runs of the class alone, each the gate command with `-Dtest=test/javafx/scene/web/CustomCursorTest` and
the library's directory last in `-Djfx.native.librarypath`; each run's output file names the library it loaded:

| Run | Library | Tests / failures / errors / skipped | Failure |
| --- | --- | --- | --- |
| (a) | first after | 3 / 0 / 0 / 0 | – |
| (b) | baseline | 3 / 0 / 0 / 0 | – |
| (c) | `dll-mut-noupcall` | 3 / 2 / 0 / 0 | tests 1 and 2: `WebView cursor DEFAULT after moving the mouse to Point[x=150, y=150] for PT5S ==> Unexpected type, expected: <javafx.scene.ImageCursor> but was: <javafx.scene.Cursor.StandardCursor>` (test 2 the same at `Point[x=300, y=150]`) |
| (d) | `dll-mut-hotspot` | 3 / 1 / 0 / 0 | test 2, `MultipleFailuresError`: `hotspot x of the ImageCursor ==> expected: <15.0> but was: <0.0>` and `hotspot y of the ImageCursor ==> expected: <15.0> but was: <0.0>` |
| (e1) to (e5) | first after | 3 / 0 / 0 / 0 in each, 5 of 5, five separate Maven runs | – |
| review | baseline | 3 / 0 / 0 / 0 | – |
| review | `RV-CX2`, x and y swapped in the upcall | 3 / 1 / 0 / 0 | test 1: `hotspot x of the ImageCursor ==> expected: <3.0> but was: <5.0>` |
| review | `RV-CX3`, `Cursor(0)` in the `Custom` arm | 3 / 0 / 0 / 0 | survives: 0 and the pointer's id both become `Cursor.DEFAULT` in `WebPageClientImpl.setCursor`; the native probe kills it |
| review | first after | 3 / 0 / 0 / 0 | – |

The first version of test 2 expected 20,20 and failed against the baseline (`expected: <20.0> but was: <15.0>`) and in
the first after gate; the rework changed the expected values to 15,15, the name, one constant and one comment, and
added `assertAll` with its import. `submit.yml` does not run the test: it passes no `-Djfx.web.skipTests=false`, and
the root pom's default is `true`.

### Native probes
- **The author's** ([evidence](US-025-native-probes.md#the-authors-probes)) cover `LocaleNone` and the custom `Cursor`
  without a host, the pointer cursor carrying the sentinel `0x5EED`. The fixed code passes all 38 locale and 17 cursor
  checks. Eight mutants, each killed: `L-M1`, `L-M2` (the local of one function restored) and `L-M0` (the baseline
  file) at step 1, the address check, before any list is read, since their references lie outside the object; `L-M3`
  (the wrong member) at step 2, the size (short list 0, full list 24 and 36); `C-M1` (the baseline initializer list)
  and `C-M0` (the baseline file) at the type, image and hotspot checks (`Invalid`, null, 0,0 for 3,5); `C-M2` (the old
  `Custom` arm) at the platform cursor (0x0 instead of the pointer's 0x5EED); `C-M3` (`m_hotSpot(hotspot)`) at the
  hotspot of an out-of-image hotspot, a null image and `Image::nullImage()`. 47 of the 193 result rows fail, all checks
  of mutants. With `-Werror=return-stack-address` the compiler rejects `L-M1`, `L-M2` and `L-M0` and accepts the fixed
  file and `L-M3`.
- **The reviewer's** ([evidence](US-025-native-probes.md#the-reviewers-probes)) add a fake host and `ComplexTextRun`.
  The fixed code passes all 282 checks (locale 107, cursor 89, `ComplexTextRun` values 53 and clamps 33). The baseline
  fails 12 of 17 locale checks, stopping at the address gate before reading any list, and 19 of 89 cursor checks, all
  of type, image or hotspot; its 12 observations of what reaches Java (the predefined ids, the custom upcalls and their
  arguments for hotspots 3,5, 20,20, -2,4 and 15,15, Java answering 0, no Java frame, the loading image, a null image,
  the reference traffic) equal the fixed code's. Its 11 mutants, none equal to the author's, are all killed: `LX1` (no
  early return) at the size, `LX2` (short names from `monthFullName`) and `LX3` (the short names also put into an empty
  `m_monthLabels`) at the content, `CX1` (no `m_image`) at the image, `CX2` (x and y swapped) at the upcall's
  arguments, `CX3` (`Cursor(0)` in the `Custom` arm) at the platform cursor, `CX4` (`Type::Pointer`) at the type,
  `TX1` and `TX4` at the values of `indexAt` and the glyph count, `TX3` at an end offset, and `TX2` and `TX4` by a
  deterministic fast-fail (`0xC0000409`) of WTF's `Vector` bounds check: `TX2` inside `setIsNonMonotonic`, `TX4` in
  the `endOffsetAt(0)` after it, because `TX4` leaves that run with 0 glyphs and so with no end offsets.
- **Compiler warnings.** The baseline build printed this story's two
  `LocaleNone.cpp(79,12): warning: reference to stack memory associated with local variable 'm_monthLabels' returned
  [-Wreturn-stack-address]` and `LocaleNone.cpp(130,12): warning: reference to stack memory associated with local
  variable 'm_shortMonthLabels' returned [-Wreturn-stack-address]`. The first after build recompiled that bundle with
  no warning at all, and no warning of it or of the final build names one of the five files.

### Independent review, rework and confirming review
- **Independent review** (18:51 to 19:36): pass, no must-fix. It read every Java-compiled site of the three items and
  the paths to Java (no behaviour change found on any path it could drive), checked the test, its timing and the
  acceptance criteria, ran its own probes, `CustomCursorTest` against the baseline, two of its mutant libraries and the
  first after library, the full gate set (492 / 0 / 0 / 113) and `CheckNoJni`. It found each choice above sane, the
  guard test "risky only if presented as the fix's test".

| Optional finding | Done |
| --- | --- |
| The comment above the `Custom` arm ended "as before", which describes the change, not the code | Taken: the comment at `:201` says why there is no second attempt |
| A why-comment that Java gets the page's hotspot, not `m_hotSpot`, would guard the choice against a well-meant "fix" | Taken: `:63` |
| The pointer fallback assumes `ENABLE_MOUSE_CURSOR_SCALE` stays off for Java | Recorded under [Choices](#choices), no code change; to state in the PR |
| `CustomCursorTest` encodes its PNG through AWT `ImageIO`; a literal PNG would drop the `java.desktop` dependency | Declined: `TestBase` already imports `java.awt.Color` |
| No backlog story covers the predefined cursors, whose type is never set | Filed as [US-129](US-129-give-the-predefined-cursors-of-the-java-webkit-port-their-type.md) |

- **Rework** (`CursorJava.cpp` saved 19:41, final build 19:43 to 19:46): one comment line added (`:63`) and one changed
  (`:201`); without comments and blank lines the reviewed and the final file are identical, 419 lines each. Both
  comments were checked against the source: `ImageCursor` clamps (`ImageCursor.java:395-406`), and `determineHotSpot`
  (`platform/Cursor.cpp:40-56`) gives 0,0 for a hotspot outside the image, since the Java decoder never has an intrinsic
  hotspot (`ImageDecoderJava::hotSpot()` returns none). The build, exports and sections: [Libraries](#libraries).
- **Confirming review:** pass, nothing to change. The diff is the two comment hunks; both comments are true (it traced
  each way the constructor can fail to the same failure on a retry); the final library's `.text` and export table
  equal the first after library's by its own PE parser and `dumpbin`, and, as a control, both mutant libraries differ
  in `.text`.

### Deviations
- A probe link started while a foreign Maven `java.exe` was running: the first `P-fixed` link began at 16:42:56, 12 s
  after a Maven run of another project (16:42:44); the command printed the process but did not stop, and the link
  took 10 s. Every later step stopped on the check.
- The owner's Maven build of another project overlapped test run (a): it started at 17:26:11, one second after run (a)
  had passed its check, and its test fork ran from 17:28:13. Run (a) passed, and so did the five repeats (e).
- The first version of the test carried the manager's wrong expectation, hotspot 20,20 for `cursor: url(...) 20 20` on
  a 16x16 image, which `ImageCursor` clamps to 15,15.
- The test's rework went beyond its literal scope with `assertAll` and its import, so that a failing x does not hide
  y; accepted.
- The review's foreign-process check ran once before each compile command, not before each of the five sub-batches
  inside one command.
- Minor, as the agents recorded them: the author's first parallel compile round failed on its own quoting and ran
  `link.exe` once in the build tree's directory, which created no file there; seven idle `MSBuild.exe` processes, not
  on the stop list, were running during the test runs and at the start of the independent review, and were gone before
  its first compile (19:03); no record covers the final build (19:43 to 19:46).

### Upstream check
Read on 2026-11-21 (the commit of openjdk/jfx master was not recorded): all three defects are on master, at
`LocaleNone.cpp:74-80` and `:125-131`, `ComplexTextController.h:163-165` (set only at
`ComplexTextControllerJava.cpp:162`), and `CursorJava.cpp:58`, whose body upcalls through JNI. WebKit `main` has no
Java branch in `LocaleNone.cpp`. No JBS issue or openjdk/jfx pull request about them was found (pull requests searched
with `gh`, one web search; JBS not queried with JQL). The draft for all three, with what is unverified upstream, is
kept outside the repository at
`C:/Users/bestq/AppData/Local/Temp/claude/50b9cf433e233190/57a996ef-5884-4236-b898-bc34b697e4df/scratchpad/us025/upstream/UPSTREAM-009-jbs-webkit-java-port-dangling-and-uninitialized-members.md`,
a temporary directory.

### Left for the owner
1. **The commit,** with the five native files, `CustomCursorTest.java`, `backlog/README.md`, this file,
   `US-025-native-probes.md`, `US-025-windows-per-class.tsv` and
   `US-129-give-the-predefined-cursors-of-the-java-webkit-port-their-type.md`, and the PR, which names the `jfxwebkit`
   it was verified with and states the `ENABLE_MOUSE_CURSOR_SCALE` assumption of [Choices](#choices).
2. **The `tests/system` web tests** (owner only: they take over the screen). First check that the library was not
   rebuilt since: `sha256sum C:/SourceCode/jfx-ffm/build/jfxwebkit-windows-release/bin/jfxwebkit.dll` must print
   `d55e45271ef80eb81aab81875bb877740616246f82dfb8b1f17269d2389f7647` (it did on 2026-11-21 after the final gate).
   Then, in Git Bash in `C:/SourceCode/jfx-ffm`, the robot tests of `tests/system/src/test/java/test/robot/javafx/web`
   (3 files), and the other web system tests of `tests/system/src/test/java/test/javafx/scene/web` (11 files) together
   with the 41 `test.javafx.scene.web` classes of `modules/javafx.web`:
   ```bash
   mvn -pl tests/system -am test '-DFULL_TEST=true' '-DUSE_ROBOT=true' '-Djfx.web.skipTests=false' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dtest=test/robot/javafx/web/**' '-Djfx.native.librarypath=C:/SourceCode/jfx-ffm/modules/javafx.media/target/native/bin;C:/SourceCode/jfx-ffm/modules/javafx.graphics/target/native/bin;C:/SourceCode/jfx-ffm/build/jfxwebkit-windows-release/bin'
   mvn -pl tests/system -am test '-DFULL_TEST=true' '-Djfx.web.skipTests=false' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dtest=test/javafx/scene/web/**' '-Djfx.native.librarypath=C:/SourceCode/jfx-ffm/modules/javafx.media/target/native/bin;C:/SourceCode/jfx-ffm/modules/javafx.graphics/target/native/bin;C:/SourceCode/jfx-ffm/build/jfxwebkit-windows-release/bin'
   ```
   The `-Djfx.native.librarypath` value replaces the default, so the older library in `C:/SourceCode/caches/sdk/bin`
   is not searched. The second command runs the module's classes too: `-am` puts `javafx-web` in the reactor
   (`tests/system` depends on it, `tests/system/pom.xml:114-119`), `-Djfx.web.skipTests=false` un-skips its
   `default-test` (`modules/javafx.web/pom.xml:291-296`), and `-Dtest=test/javafx/scene/web/**` matches its 41
   `test.javafx.scene.web` classes, `CustomCursorTest` among them. The first command's pattern matches no class of the
   module. In `tests/system` the flag only activates the `web-test` profile (`tests/system/pom.xml:432-444`), which
   lifts the exclusion of `test/robot/javafx/web/**` (`:53`, `:268`); `test/javafx/scene/web/**` was never under it.
   The 41 module classes passed against this library in the final gate ([Gates](#gates)); for the `tests/system`
   classes no run of this story gives an expected result.
3. **The manual check on screen** (owner only; it opens a window). Save this page as `C:/SourceCode/cursor-check.html`
   (any folder outside the repository will do; the URL below must match it):
   ```html
   <!DOCTYPE html>
   <html>
   <body style="margin: 0; font-family: sans-serif">
   <img src="file:///C:/SourceCode/jfx-ffm/tests/manual/swt/cursor.png" style="position: absolute; left: 10px; top: 10px">
   <div style="position: absolute; left: 50px; top: 50px; width: 200px; height: 200px; background: #ddd;
               cursor: url('file:///C:/SourceCode/jfx-ffm/tests/manual/swt/cursor.png') 3 5, auto">custom cursor</div>
   <div style="position: absolute; left: 300px; top: 50px; width: 200px; height: 200px; background: #ccf;
               cursor: pointer">cursor: pointer</div>
   </body>
   </html>
   ```
   Then start the repository's `apps/toys/Hello` WebView browser from its source file, in Git Bash:
   ```bash
   "/c/Program Files/OpenJDK/jdk-27+35/bin/java" --module-path C:/SourceCode/jfx-ffm/sdk/target/sdk/lib --add-modules javafx.web '--enable-native-access=javafx.graphics,javafx.media,javafx.web' '-Djavafx.verbose=true' '-Djava.library.path=C:/SourceCode/jfx-ffm/build/jfxwebkit-windows-release/bin' C:/SourceCode/jfx-ffm/apps/toys/Hello/src/main/java/hello/HelloWebView.java file:///C:/SourceCode/cursor-check.html
   ```
   It uses the SDK assembled in `sdk/target/sdk` (its jars are dated 13:10 to 13:29 on 2026-11-21, when `HEAD` was
   `9fd96212a5`, whose tree equals `6832730e09`'s; this story changes no Java code of a shipped module). The SDK's `bin`
   has no `jfxwebkit.dll`, so the library comes from `-Djava.library.path`, and the Git Bash window must show
   `Loaded C:\SourceCode\jfx-ffm\build\jfxwebkit-windows-release\bin\jfxwebkit.dll from java.library.path`. In the
   window that opens, move the mouse onto the grey box: the pointer must become the small image shown at the top left
   of the page (`tests/manual/swt/cursor.png`, 16x16), with its click point 3 pixels right of and 5 below the image's
   top-left corner. On the blue box it must be the hand, elsewhere the arrow. Then close the window. Not run by any
   agent.
4. **`build-webkit.yml`** after the branch is pushed: in the repository's Actions tab open "Build jfxwebkit", click
   "Run workflow", choose the PR's branch, "Platform(s) to build" `all` and "WebKit build configuration" `Release`,
   and start it (or `gh workflow run build-webkit.yml --ref <branch> -f platforms=all -f configuration=Release`). It
   builds Linux x64 and aarch64, macOS x64 and aarch64, which were not built here, and Windows x64, and publishes a
   draft release by default; the PR then names this local build or that run's library. If the cache is to be current,
   replace `C:/SourceCode/caches/sdk/bin/jfxwebkit.dll` (the 2026-09-30 build) with the final library or the run's
   Windows library.
5. **Filing the upstream issue** from the draft named under [Upstream check](#upstream-check). It is in a temporary
   scratch directory: move it, for example next to the other drafts in `C:/Users/bestq/Downloads/copilot_review`,
   before that directory is cleaned.
6. **Done:** set the status to ✅ Done with the date and the PR, and move the row to the done table of
   `backlog/README.md`.

Not run or not verified: anything on Linux or macOS; DumpRenderTree and `Internals.getCurrentCursorInfo`; a Debug
build, so `Cursor::type()`'s `ASSERT` was not exercised; a native `ComplexTextRun` run of the baseline layout (the
header change alters it; parity for Java-built runs rests on reading and on checks derived from the baseline code);
any probe or class run against the final library other than the final gate; the Maven version of the runs; the exit
code of the final `CheckNoJni` run.

## PR #72 review (2026-11-22)

Uncommitted, on top of `f5c28c079d`. Copilot (review 6013848137 of `f5c28c079d`, its one comment 4659479291 on
`CustomCursorTest.java:135`, the `loadContent` line): `String.formatted` formats `%d` with the default format locale, so
under a locale whose digits are not ASCII, such as `ar-EG`, the page's `cursor: url(...) X Y` gets digits that CSS does
not parse, WebCore drops both custom-cursor declarations and the two image-cursor tests time out; it asks to format the
page with `Locale.ROOT`. The finding is real: in jshell on Temurin 27+35, `"%d %d %d".formatted(3, 5, 20)` gives the
code points 1635 32 1637 32 1634 1632 under `ar_EG` and 1779 32 1781 32 1778 1776 under `fa_IR`, ASCII digits under
`en_US` and `hi_IN`; `String.format(Locale.ROOT, "%d %d %d", 3, 5, 20)` gives `3 5 20` under all four.

### What changed
- **Test** (`CustomCursorTest.java`, +2 / -1): `import java.util.Locale;` at `:40`, between `java.util.Base64` and
  `javafx.scene.Cursor`, and the committed `:135` is now `:136`:
  ```diff
  -loadContent(PAGE.formatted(cursorUrl, INSIDE_HOTSPOT_X, INSIDE_HOTSPOT_Y, OUTSIDE_HOTSPOT));
  +loadContent(String.format(Locale.ROOT, PAGE, cursorUrl, INSIDE_HOTSPOT_X, INSIDE_HOTSPOT_Y, OUTSIDE_HOTSPOT));
  ```
  It was the file's only format call; the other text that reaches WebCore, JavaScript or an assertion does not depend on
  the locale (string concatenation, record and `Duration` `toString`, Base64). No comment was added: `Locale.ROOT` on a
  format call says why by itself. Nothing else changed: no native file, no pom, no other test, no production Java.
- The test file has 181 lines (180 at `f5c28c079d`), LF only, with no tab, no trailing blank and no non-ASCII character;
  its longest line still has 118 columns (`:177`, and now `:136` too). Under `en_US` the page text is identical before
  and after, 1050 characters with sha256 `571f09b591f3575fdb410aa64397ddbb9482dff15efa0e7cb99a29f74650f10f`; under
  `ar_EG` and `fa_IR` the fixed page has that hash and the committed page does not.

### Measured (2026-11-22, Windows x64, Temurin 27+35)
Every run used the final library `build/jfxwebkit-windows-release/bin/jfxwebkit.dll`, sha256
`d55e45271ef80eb81aab81875bb877740616246f82dfb8b1f17269d2389f7647` before and after ([Libraries](#libraries)). A class
run is the command of [Gates](#gates) with `-Dtest=test/javafx/scene/web/CustomCursorTest`. Times are America/New_York.

| Run | Test file | Locale of the test JVM | Time | Maven | Tests / failures / errors / skipped | Failure |
| --- | --- | --- | --- | --- | --- | --- |
| R1 (author) | committed (`f5c28c079d`) | `ar-EG` | 15:21:43 to 15:27:35 | exit 1, BUILD FAILURE, 05:49 min | 3 / 2 / 0 / 0 | tests 1 and 2: `WebView cursor DEFAULT after moving the mouse to Point[x=150, y=150] for PT5S ==> Unexpected type, expected: <javafx.scene.ImageCursor> but was: <javafx.scene.Cursor.StandardCursor>` (test 2 the same at `Point[x=300, y=150]`) |
| R2 (author) | fixed | `ar-EG` | 15:28:40 to 15:32:38 | exit 0, BUILD SUCCESS, 03:56 min | 3 / 0 / 0 / 0 | – |
| R3 (author) | fixed | `en-US`, the machine's default, nothing set | 15:33:08 to 15:37:05 | exit 0, BUILD SUCCESS, 03:53 min | 3 / 0 / 0 / 0 | – |
| V1 (review) | fixed | `fa-IR` | 15:42:14 to 15:46:11 | exit 0, BUILD SUCCESS, 03:54 min | 3 / 0 / 0 / 0 | – |
| Gate (review) | fixed | the default, `en-US` | 15:46:50 to 15:52:07 | exit 0, BUILD SUCCESS, 05:14 min | `javafx-web` `default-test` 492 / 0 / 0 / 113 in 51 classes | – |

- In R1, test 3 passed, and so did `loadPage`'s probe assertion: the image loaded, only the cursor rules were dropped,
  the symptom of `dll-mut-noupcall` in run (c) of [The test](#the-test).
- The gate ran after the last code change (the test file's mtime is 15:28:12; of the tracked files only this one is
  newer), and a process check just before it started found no `java`, `ninja`, `cl`, `link` or `cmake` process.
  Per class it equals the after column of [per class](US-025-windows-per-class.tsv): 0 differences, none
  missing, none extra; `CustomCursorTest` 3 / 0 / 0 / 0. Its `Loaded` line names the build-tree library, `caches` is on
  `java.library.path` in none of the 51 `TEST-*.xml`, and there is no `hs_err_pid*` under `modules/javafx.web`.

The locale was given to the test JVM only, not through `JDK_JAVA_OPTIONS`, which would also reach the Maven JVM and the
generators of the `-am` modules. The default surefire `argLine` of `modules/javafx.web/pom.xml` (`:330-338`) has
`"-Djava.library.path=${jfx.native.librarypath}"` in double quotes (`:336`); the runs closed that quote inside the
property value. The last argument of the command, for `ar-EG`:
```bash
'-Djfx.native.librarypath=C:/SourceCode/jfx-ffm/modules/javafx.media/target/native/bin;C:/SourceCode/jfx-ffm/modules/javafx.graphics/target/native/bin;C:/SourceCode/jfx-ffm/build/jfxwebkit-windows-release/bin" -Duser.language=ar -Duser.country=EG "-Dpr72.fork.locale=ar-EG'
```
V1 used `fa` and `IR`, R3 the plain value. The control, in each run's `TEST-test.javafx.scene.web.CustomCursorTest.xml`:
`user.language` and `user.country` as set, the marker `pr72.fork.locale` (R1, R2, V1), `java.library.path` exactly the
three directories; in each `-output.txt` the `Loaded` line names the build-tree library.

### Review
Independent review: pass, no must-fix. The fix is sane, the smallest correct one: JDK 27 has no `String.formatted`
overload with a `Locale`, and `Locale.ROOT` also overrides `user.language.format` and `-u-nu-` extensions. It traced the
cause in this repository's WebCore: `css/parser/CSSTokenizer.cpp:839-844` starts a number only on an ASCII digit;
`css/parser/CSSParserIdioms.h:52-55` treats a non-ASCII code point as a name-start code point, so U+0663 starts an ident
token; `consumeCursor` (`css/parser/CSSPropertyParserConsumer+UI.cpp:52-62`) finds no number after the `url(...)`, then
requires a comma, the ident fails that, and the whole declaration is dropped, so the element keeps `cursor: auto` and
the view reports `DEFAULT`. Its optional finding, taken: [Change](#change) said only "new, 180 lines".

Not run or not verified: anything on Linux or macOS; any locale through Maven but `ar-EG`, `fa-IR` and `en-US` (`hi_IN`
only in jshell); WebCore dropping the declaration, which was traced in the source and inferred from R1 against R2, not
observed; R1 by the review, which checked R1's files; a CI run: `submit.yml` still does not run the test.

This round leaves the owner the commit of `modules/javafx.web/src/test/java/test/javafx/scene/web/CustomCursorTest.java`
and of this file, and the push. No reply was posted on the PR thread.
