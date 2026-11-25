# US-111 — Fix the CoreText NULL inputs and font-layer defects US-067 left

**Status:** ✅ Done (2026-11-22, PR 71, commit `40e53db102` and its CI fix `f352c6008c`; the further fixes committed as
`e1de3dd32a`, and the fixes of the Copilot review on it and of its review as `2c9003b909` on 2026-11-24; filed
2026-11-13 from US-067; sites 1-7 hand none of their listed inputs to CoreFoundation, CoreGraphics or CoreText as NULL
or 0, site 8 keeps the strike reachable, site 9 has one owner, site 10 drains what CoreText autoreleases on any thread
(deviation 7), site 11's outcomes are pinned and site 12 looks a missing face up once per file while that file is
unchanged, by one caller per name and file at a time, each pinned by a test with mutant proof; CI run 45022158121 failed
three checks of `MacFontReleaseTest` on both macOS 15 runners, where the region without a pool leaves about 6 heap
blocks per call and the fallback-face child printed no lookup of a face missing from its file, and the test-only CI fix
has them decide through a verdict ("The CI fix" below): on CI run 45041254324, green on all six jobs, they answer
CANNOT_SHOW there and skip with their figures; the fourteen defects and test gaps found beside the twelve sites while
PR 71 was reviewed are fixed in this PR ("The further fixes" below), as are five findings of the Copilot review of PR 71
on them ("The Copilot review" below), whose sixth, the DirectWrite rows of 8c, could not be edited by hand, and four
findings of an independent review of those fixes, R1 to R4, with five items of a review that confirmed them; the further
fixes passed CI run 45278364431 but for its Linux x64 job, which compiled nothing, and `2c9003b909` passed CI run
45456403357 on all six jobs, its Linux x64 job on the second attempt; on 2026-11-24 the DirectWrite golden was captured
again on its Windows machine, exactly its 609 rows of 8c moving, thirteen macOS slot tests were enabled on Windows and
Linux (one of them on Linux only) and two tests added, in five test files that are uncommitted and have run nowhere on
macOS ("Windows and Linux" below); the commit and a CI run of that round, the robot and system tests and the comparison
on the macOS golden machine remain for the owner, see "Done" below) ·
**Found:** 2026-11-12 and 2026-11-13, the traces and the exit-path review of US-067, on the US-067 tree

## Story
As the maintainer of the macOS font layer,
I want the font sites that US-067 traced and left to have a defined outcome and a single owner for every CF object,
so that no remaining input can crash the JVM, leak a CF object or release a CTFont that is still in use.

## Problem
Paths are relative to `modules/javafx.graphics/src/main/java/com/sun/javafx/font`; each site is named by its
method, on the US-067 tree. US-067 fixed the six inputs and four leaks of its own list and nothing else; these are
the sites its traces found beside them.

**Crashes: a NULL reaches CoreFoundation, CoreGraphics or CoreText.**

1. `MacFontFinder.java` (`getSystemFontSize`): `CTFontGetSize` and `CFRelease(font)` when
   `CTFontCreateUIFontForLanguage` answers NULL.
2. `MacFontFinder.java` (`getFontData`): `CFRelease`, `CFArrayGetCount` and `CTFontCopyFontDescriptor` when the
   font collection, its descriptor array, a UI font or its descriptor is NULL. The `finally` blocks US-067 added
   release each of them unguarded.
3. `DFontDecoder.java` (`createCTFont`): the same for a NULL collection or descriptor array; `releaseCTFont` hands
   a 0 font to `CFRelease`.
4. `coretext/OS.java` (`CTFontCreateUIFontForLanguage`, `CTFontCreateWithName`): a NULL UI font goes into
   `CTFontCreateCopyWithAttributes` and `CFRelease`; a 0 or empty `name` goes into
   `CFStringGetCharacterAtIndex(name, 0)`.
5. `coretext/CTFontFile.java` (`getAdvanceFromPlatform`): the strike's `fontRef` goes into
   `CTFontGetAdvancesForGlyphs` without a check for 0 (the `CTFontStrike` constructor leaves it 0 when no CTFont
   could be made).
6. `coretext/CTGlyph.java` (`createContext`): the four `CGContextSetAllows*` calls on a 0 context when
   `CGBitmapContextCreate` answers 0.
7. `MacFontFinder.java` (`getCascadeList`): a NULL answer of `CFLocaleCopyISOLanguageCodes` goes into
   `CTFontCopyDefaultCascadeListForLanguages` and `CFRelease`.

**Leaks and lifetime.**

8. `coretext/CTGlyphLayout.java` (`layout`): nothing keeps the strike reachable while the method uses the strike's
   `fontRef`, so the strike's disposer can release the CTFont before the last use.
9. `coretext/CTGlyph.java` (`getCachedContext`): the static `cachedContextRef` is read and written without
   synchronisation, so two threads can each create a context, and one of the two is never released.
10. `MacFontFinder.java` (`getCascadeList`): called off the AppKit thread, each call leaves about 232 autoreleased
    heap blocks that wait for the thread to exit (US-067's trace).

**Other defects.**

11. `coretext/GlyfTableBounds.java` (`boundingBox`): a `bb` shorter than four elements is partly written before the
    exception leaves. `coretext/OS.java` (`rect`): a `CGRect` whose `origin` or `size` is null throws a
    `NullPointerException`; no test pins either outcome.
12. `coretext/CTFactory.java` (`createFontFile(String, long)`): a fallback font whose face is not found in its file
    is created again, and its file parsed again, on every layout (seen with the System font and Devanagari text).

## Acceptance criteria
1. **No crash.** None of sites 1-7 hands NULL or 0 to CoreFoundation, CoreGraphics or CoreText. Each has a defined
   outcome that is not a crash, stated in the test that pins it. A crashing input runs in a child JVM, so that a
   regression fails the test instead of ending the test fork.
2. **One owner.** At site 9 no context is left without an owner, whatever the threads do.
3. **Alive while used.** At site 8 the strike's CTFont is not released before the method's last use of it.
4. **No growth per call.** A `getCascadeList` call off the AppKit thread leaves no autoreleased block behind when it
   returns (site 10). Repeated layouts of the same text do not create the fallback font of site 12, or parse its
   file, more than once.
5. **Defined outcomes.** At site 11 a `bb` that makes `boundingBox` throw is left as it was, and the outcome of a
   `CGRect` with a null `origin` or `size` is stated and pinned.
6. **Tests.** Every fix has a test that fails without it and passes with it (mutant proof recorded in this story).
   Where no test can provoke a path, the story says so and the review checks it; the story lists those paths.
7. **Goldens.** No row of the five `mac-*-golden.txt` files changes, except where a fix changes a recorded value;
   such a row moves with the reason recorded here. On a machine where the goldens are no oracle, the five corpora
   are compared before and after as US-067 did.
8. **Facades frozen.** `CFNative`, `CGNative` and `CTNative` do not change unless a fix needs a descriptor change;
   the facade test's descriptor table then changes with it. Every `MacFontBindings` pin that moves is recorded with
   its reason.
9. **Code rules.** Java 27 final features only, no `var`; a production file that is at most 900 lines (`wc -l`)
   today stays so; lines at most 120 columns, never several statements on one line; comments only for a
   non-obvious "why".

## Verification
On a macOS host with JDK 27 and Maven, from the repository root, before and after:
```
# The macOS gate. On the machine the goldens came from: with the flag, 0 failures, 0 errors, 0 skipped.
# On any other machine: run it without the flag and with it, and record which classes skip and fail.
T='Mac*Test,PrismFontFactoryTest,CFNativeTest,CGNativeTest,CTNativeTest'
T="$T,CGPathSinkTest,GlyfTableBoundsTest,CGBitmapReaderTest"
T="$T,CompositeFontLocksTest,CompositeSlotRaceTest,FontSharedStateRulesTest"
mvn -pl modules/javafx.graphics -am test -Dsurefire.failIfNoSpecifiedTests=false "-Dtest=$T" \
    -Djfx.parity.require=true
# Full module tests, then no crash files (both print nothing)
mvn -pl modules/javafx.graphics -am test
find modules/javafx.graphics -maxdepth 1 -name 'hs_err_pid*' -type f
find modules/javafx.graphics/target -name 'hs_err_pid*' -type f
# Golden files unchanged unless a fix moved a row (prints nothing otherwise)
git diff --stat -- 'modules/javafx.graphics/src/test/resources/test/com/sun/javafx/font/mac-*-golden.txt'
```
The US-067 baseline on macOS 27.2.1 (26B131), where no golden is an oracle: the gate runs 482 tests (139 in the
font classes, 343 in the Glass classes `Mac*Test` also selects), with 0 failures and 8 skipped without the flag and
the same 8 failing with it; the module run is 26,696 tests, 0 failures, 858 skipped. A leak shows as a retain-count
or heap-block change, as `MacFontReleaseTest` measures it.

The third `T` line is the further fixes': `CompositeFontLocksTest`, `CompositeSlotRaceTest` and
`FontSharedStateRulesTest` are new and `Mac*Test` does not select them; `Mac*Test` already selects the other new
classes, `MacFontNullInputVerdictTest`, `MacFontSharedStateTest`, `MacGlyphImageTest` and `MacGlyphImageRulesTest`,
and the Copilot review's `MacFontFaceLookupTest`.
The font package run of "Measured" is
`-Dtest='test/com/sun/javafx/font/**/*Test,**/ParityGateTest'`, which holds every font class of the gate.

Where the goldens are no oracle, criterion 7's before and after comparison is a dump of the five corpora taken with
`Us111CorpusDumpTest`, US-067's dump test renamed, run with `-Dtest=Us111CorpusDumpTest -Dus111.dump=<directory>`.
It is evidence only, not part of the change and not in the tree: its copy is kept with the logs (sha256
`fde7c5c7…`), and it was placed in the tree only for the dumps.

On a CI run, each verdict-based check prints one line, `<class>.<method> <PROVED|REFUTED|CANNOT_SHOW>: <figures>`, in
the macOS jobs' console, twelve in all: the cascade-list drain checks on a platform and on a virtual thread, the three
fallback-face checks, the two slot checks and the lent-font check of `MacFontReleaseTest`, the log check of
`MacFontNullInputTest`, the sub-pixel check of `MacGlyphImageTest`, and the two checks of `MacFontFaceLookupTest`
that lay a face out again once its file or its path changed. On a healthy tree each is `PROVED`, or
`CANNOT_SHOW` with the precondition the host does not meet. `REFUTED` on a healthy tree needs one of three host
behaviours that no host has shown (see "Rests on review"):
- the worker's call leaving 10 or more blocks per call, which the virtual drain check refutes on every host: on macOS
  15 a pooled platform call left about 4, and on CI run 45278364431 the worker's call left 4.02 (arm64) and 4.05 (x64)
  (item 14);
- a lent font's retain count that moves again over the task's list after two baselines in a row agreed;
- CoreGraphics keeping state, beyond the four options set before a draw, from the options a context was made with or
  first drawn under.

A drain check is `PROVED` by the heap where the region without a pool leaves more than 10 blocks per call, or by the
pool tokens where the region without a pool moved them in 10 of 10 cycles (`tokens.control`) and neither an empty
region (`tokens.empty`) nor a call (`tokens.subject`) moved them. The lent-font check is `CANNOT_SHOW` where its two
baselines differ.

Nine macOS tests depend on the host and print no line: eight of `CompositeFontLocksTest` and
`PrismFontFactoryTest.aFontAddedToACompositeIsInTheSlotItIsGiven` need room for one or two more slots under the 0x7E
slot cap in the host's composite, and where there is none they skip through `ParityGate.requireOracle` (a failure
under `-Djfx.parity.require=true`). On a CI run, read those two classes' skip counts in the macOS jobs: 0 on this Mac.
On CI run 45456403357, of `2c9003b909`, they were 0 on both macOS 15 runners. Since 2026-11-24 the nine run on Windows
and Linux too, beside the new `aCompositeWhoseFallbackListIsFullHasNoSlotForAnotherFont`, which needs room for one font
("Windows and Linux" under "Done"); read the two classes' skip counts in every job: with that round,
`CompositeFontLocksTest` skips 1 on Windows (the test kept off it) and 0 on Linux, and `PrismFontFactoryTest` 3 on
both (its macOS tests).

Since 2026-11-24 the slot tests and the DirectWrite golden run on Windows and Linux too. On Windows 10 19045.6466, the
golden's capture machine, and in WSL Ubuntu (a clone in the Linux home, JDK 27, Maven 3.10, `DISPLAY` and
`WAYLAND_DISPLAY` unset), from the repository root:
```
# Windows: with the flag; the DirectWrite golden is compared only on its capture machine
mvn -B -ntp -pl modules/javafx.graphics -am test -Djfx.parity.require=true
# WSL: without the flag; the Linux font goldens are another machine's and skip at their machine gate
mvn -B -ntp -pl modules/javafx.graphics -am test
# The round's four classes on either, with the flag on Windows only
mvn -B -ntp -pl modules/javafx.graphics -am test -Dsurefire.failIfNoSpecifiedTests=false \
    "-Dtest=PrismFontFactoryTest,CompositeFontLocksTest,DirectWriteMetricsGoldenTest,DirectWriteNoSlotLayoutTest"
```
The baseline, `javafx.graphics` run / failures / errors / skipped: Windows at `2c9003b909` 26,875 / 1 / 0 / 917, the one
failure the golden's 609 differences; WSL at `abe0943d31` 26,428 / 0 / 0 / 967 and at `2c9003b909` 26,619 / 0 / 0 /
1,034. With the round: Windows 26,877 / 0 / 0 / 905, WSL 26,621 / 0 / 0 / 1,022, and the four classes 33 / 0 / 0 / 4 on
Windows and 33 / 0 / 0 / 6 in WSL.

## Out of scope
- Objects kept for the life of the process by design: `CTGlyph`'s `GRAY_COLORSPACE` and `RGB_COLORSPACE`, and the
  one cached context that site 9 keeps.
- Re-capturing the macOS goldens for macOS 27.2.1. They were captured on macOS 27.0.1 (26A434) from the JNI build,
  whose C is deleted (US-003), so a re-capture here would record this code's own output in place of the C's; they
  are compared on the machine they came from, ten hand-edited rows included (deviation 16), and the corpus dumps
  stand in for them elsewhere.
- Any other behaviour change in the font layer. The defects found beside the twelve sites while PR 71 was reviewed
  are not out of scope: the owner ruled on 2026-11-22 that this PR fixes them, item 8 with the behaviour changes
  recorded under "Deviations and stated properties".

## Definition of Done
- All acceptance criteria met, with the before and after results stated in this story.
  ✅ All nine, with the limits stated under "Done"; the flagged comparison on the golden machine remains for the owner.
  Since 2026-11-24 criteria 6 and 7 are met on Windows and Linux too ("Windows and Linux").
- An independent review (`ffm-reviewer`) repeated the macOS gate.
  ✅ Each slice's `ffm-reviewer` repeated it without the flag; the final criteria-and-gate review and the
  confirmation on the final tree repeated it without and with the flag (see "Independent review"). The gate as such
  has not been run on the CI fix or on the further fixes; on the further fixes the font package, which holds every
  font class of the gate, ran without and with the flag (see "Measured"). On the fixes of the Copilot review the gate
  and the font package ran without and with the flag, and the module run once (see "The Copilot review"). The Windows
  and Linux round of 2026-11-24 had an independent review and two confirmations, which ran its four classes on Windows
  with the flag and in WSL without it, the review and the first confirmation the font package too; none ran the macOS
  gate, as no macOS host was available.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
  ✅ Status and row set on 2026-11-22 and updated on 2026-11-23; the row gives PR 71, open, with the further fixes
  committed as `e1de3dd32a` and the fixes of its Copilot review uncommitted. Updated on 2026-11-24: those fixes are
  committed as `2c9003b909`, and the Windows and Linux round is uncommitted.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without
  explicit approval from the repository owner.**
  ✅ The owner committed the change as `40e53db102`, the CI fix as `f352c6008c` and the further fixes as
  `e1de3dd32a` on PR 71, which is not merged; the fixes of the Copilot review are uncommitted in the working tree.
  Since 2026-11-24 they are committed as `2c9003b909`; the five test files of the Windows and Linux round are
  uncommitted in the working tree.

## Notes
- Related: US-067 (it traced these sites and left them; see its "Deviations").
- Site numbers of US-067's evidence: 1-7 here are its N1-N7, 8 is N8, 9 is N10, 10 is N12, 11 is N9 and 12 is
  N11. Its N13 (the goldens are no oracle on macOS 27.2.1, and `Mac*Test` selects the Glass classes too)
  is not a code site; see "Verification" and "Out of scope".
- The defects and test gaps found beside the twelve sites while PR 71 was reviewed are fixed in this PR: see "The
  further fixes" under "Done".

## Done (2026-11-22, PR 71; the further fixes 2026-11-23)

### What changed
Paths as under "Problem". 10 production files (one new, `coretext/ObjCNative.java`), 7 shims (2 new) and 8 test
files (2 new); `git diff --stat` over the 20 tracked files: 1,623 insertions, 88 deletions, of which the 9 tracked
production files 263 and 71. The work ran on 2026-11-21 and 2026-11-22 in four slices, A (sites 1, 2, 3, 7), B (4,
5, 6, 11), C (8, 9, 12) and D (10), then five touch-ups and the test-only fixes of the final review.
The owner committed it as `40e53db102` on `abe0943d31`: "the final tree" below is that commit's code (the 25 files
of `after/final.sha256` are byte-identical to it), and in the sections written for it "HEAD" is `abe0943d31`. After
CI run 45022158121 the CI fix changed 2 shims and 5 test files and added 2 test files, and no production file
(against `40e53db102`, `git diff --stat` over the 7 tracked files: 501 insertions, 66 deletions; the new files 198 and
412 lines); the owner committed it as `f352c6008c`. The further fixes, made on `f352c6008c` and committed as
`e1de3dd32a`, are under "The further fixes" below; where they change what this section or the criteria sections say,
those say so.

**Production.**
- **Site 1.** `MacFontFinder.getSystemFontSize` hands the UI font to a new `sizeOf`. It answers 13 for NULL, the size
  `PrismFontFactory.getSystemFontSize` uses for the other desktops and what this Mac reports; otherwise it reads the
  size and releases the font in a `finally`.
- **Site 2.** `getFontData` is `fontData(matchingDescriptors(collection))`. A NULL collection gives a NULL array and a
  NULL array no collection entries; `addUiFont` and `addOwnedDescriptor` skip a NULL UI font or descriptor. The two
  UI fonts are still listed (an array of 6), so `populateFontFileNameMap` still fills the font maps. The release
  order is HEAD's.
- **Site 3.** `DFontDecoder.createCTFont` is `fontNamed(matchingDescriptors(collection), nameRef)`, with its own
  `matchingDescriptors`. A NULL collection, array or name string gives font 0, so `decode` throws its existing
  `IOException("Failure creating CTFont")`. The name string is released only when it is not NULL, and
  `releaseCTFont(0)` does nothing.
- **Site 4.** A new `OS.copyOfUIFont` takes the +1 UI font, answers 0 for NULL, and otherwise copies it and releases
  it in a `finally`. `CTFontCreateUIFontForLanguage` with a matrix and the "." branch of `CTFontCreateWithName` both
  use it, each with its transform allocated before the UI font is made; without a matrix the UI font is answered as
  before (0 for NULL). `CTFontCreateWithName` answers 0 for name 0 and reads the name's length before its first
  character, so the empty name takes the ordinary path (Helvetica).
- **Site 5.** `CTFontFile.getAdvanceFromPlatform` answers 0 for a strike without a CTFont, inside its existing fence
  `try`.
- **Site 6.** `CTGlyph.createContext` returns 0 at once when `CGBitmapContextCreate` answers 0. Its callers already
  answer `new byte[0]`, and the cache stays 0 and is tried again.
- **Site 7.** A new `MacFontFinder.cascadeList(font, codes)` answers the empty list for NULL language codes without
  asking CoreText, and releases the codes on every other exit.
- **Site 8.** In `CTGlyphLayout.layout` the `createCTLine` call is in a `try` whose `finally` calls
  `Reference.reachabilityFence(strike)` on the slot strike, with a one-line why-comment.
- **Site 9.** The body of `CTGlyph.getCachedContext` is in `synchronized (CONTEXT_LOCK)`, a new private static final
  lock; the static is read and written nowhere else. Since the further fixes (item 1) the cached branch of
  `getImage` holds the same lock from `getCachedContext` through the whole draw.
- **Site 10.** A fourth facade, `coretext/ObjCNative` (109 lines), binds `objc_autoreleasePoolPush` and
  `objc_autoreleasePoolPop` from `/usr/lib/libobjc.A.dylib`, with `CFNative`'s linker and failure handling.
  `PrismFontFactory`'s initialiser calls `ObjCNative.link()` right after `CTNative.link()` (one import, one line).
  - `getCascadeList` keeps its `fontRef == 0` return. A platform caller runs `pooledCascadeList`: push a pool, copy
    the language codes, `cascadeList`, pop in a `finally`.
  - A virtual caller never pushes. It runs `pooledCascadeList` on a new named daemon platform thread that inherits
    no thread locals, through a `FutureTask` around a lambda, and waits for it in `resultOf`.
  - The wait outlasts an interrupt, whose status is set again before it returns, and anything `get()` itself throws:
    it spins on `isDone()` and then throws that. What the task threw is rethrown.
  - If no thread can be started, the caller makes the list inline without a pool, as before this story.
  - Since the further fixes (items 4, 5 and 14) a virtual caller hands its call to one long-lived platform thread
    and parks until its task is done; `resultOf`, the `FutureTask` and the spin are gone, and the pool token is a
    `long`. Since the Copilot review (F3) a virtual caller that cannot start that thread gets the error, and no
    virtual caller makes a list itself.
- **Site 11.** `GlyfTableBounds.boundingBox` reads the four values into locals and stores index 3 first, with a
  one-line why-comment. `OS.rect` is unchanged.
- **Site 12.** `CTFactory` keeps a `ConcurrentHashMap.newKeySet()` of the names, lower-cased with `Locale.ROOT`, whose
  face `findFontIndex` did not find. `createFontFile` adds the name in a new `else` of its existing
  `if (fIndex != -1)`, with no new `return` in its `try`. `CTGlyphLayout.getFontSlot` asks `mayFindFace` after the
  " Regular Regular" fix-up and after `getSlotForFont` answered -1, and makes no copy when the answer is no. The slot
  stays -1. Since the further fixes (item 10) a file that cannot be read is not remembered: `findFontIndex` throws
  what reading it throws, and the next layout looks again. Since the Copilot review (F5, F6) the set is a map of each
  name to the file the lookup read and that file's version, which holds only while a run font of the name reports that
  file in that version, and one caller at a time looks a name up, the others sharing its outcome.

**Tests and shims.**
- **24 new tests.** `MacFontNullInputTest` gains 9 (sites 1-7 and 11, each in a child JVM of
  `MacFontNullInputChild`), `MacFontReleaseTest` 7 (sites 9, 12 and 10, each in a child JVM of `MacFontReleaseChild`),
  `MacFontBindingStructureTest` 3 (classfile checks of sites 4, 8 and 12, any OS), `GlyfTableBoundsTest` 2 (site 11,
  any OS), and the new `coretext.MacObjCNativeTest` 3 (the new facade, macOS). The new `FontCallOrder` holds the
  classfile order and receiver checks.
- **Shims the witness reads**, reaching the new helpers by reflection and calling no facade: `MacFontFinderShim`
  (`sizeOf`, `matchingDescriptors`, `fontData`, `addUiFont`, `addOwnedDescriptor`, `cascadeList`, `resultOf`, which
  the further fixes deleted, deviation 13) and `DFontDecoderShim` (`matchingDescriptors`, `fontNamed`).
- **Shims it does not read:** `CoreTextPeerShim` (`copyOfUIFont`, `advanceFromPlatform`, `NullHalf`,
  `transformRectWithNull`, `fillRectWithNull`) and `CTGlyphShim` (`createContext`, `getCachedContext`); the new
  `CTFontStrikeShim`, a strike whose `isSubPixelGlyph` runs a hook, and `ObjCNativeShim`, the new facade's registry,
  path and `find`.
- **The observer** `MacFontProbeShim`, with its own bindings and no font class: `fontCollection`,
  `setFillColorOfNoContext`, `setAllowsAntialiasingOfNoContext` and `languageCodes`.

**The CI fix** (test-only; committed as `f352c6008c`, run on CI as run 45041254324, see "Measured").
- **Three checks decide through a verdict.** The two heap checks of site 10 and the fallback-face check of site 12
  need a machine that can show their defect. The new pure class `MacFontReleaseVerdict` decides each from the
  child's figures and lines: `PROVED`, `REFUTED`, or `CANNOT_SHOW`, which `ParityGate` makes a skip, or a failure
  under `-Djfx.parity.require=true`.
  - The bound stays 10. A control above it, and for the virtual check an empty hand-off below it, are the
    precondition under which a check decides; before, both were failures. A call at or above the bound is
    `REFUTED`, its message starting with the old one; the call is judged against the bound alone, and the hand-off
    is not subtracted.
  - The fallback face: a face missing from its font file is the precondition. Other glyphs, an unresolved count other
    than the layouts, or a second lookup is `REFUTED`. The check first asserts that the child ran with
    `prism.debugfonts` (its new `debugFonts` key).
  - The probes' structural checks run first, unchanged. Each check prints one line,
    `MacFontReleaseTest.<method> <OUTCOME>: <figures>`, to the test JVM's file descriptor 2, which surefire copies to
    the Maven console; a skip's reason reaches only the XML report, which the macOS CI jobs do not keep.
- **36 more tests.** `MacFontReleaseVerdictTest`, new, 30 on any OS: the rules fed the recorded figures of this Mac,
  of mutants M10a, M10b and M12a here and of both macOS 15 runners where there are any, and invented ones, labelled
  so in the test, for the rest (the macOS 15 platform call and hand-off, which CI did not print, the edges of each
  rule and a machine whose face is found). `MacFontReleaseTest` 1, `aFaceItsFontFileLacksIsRememberedByItsName`
  (site 12's memory, child scenario `release.faceMemory`). `MacFontBindingStructureTest` 4 class-file checks of
  site 10, and its site 12 check also pins the guard. `coretext.MacObjCNativeTest` 1,
  `aPoppedPoolReleasesWhatWasAutoreleasedIntoIt`.
- **Helpers and shims.** `FontCallOrder.assertGuards`: the first branch after a condition call is an `ifeq`, each
  guarded call lies on its fall-through and each other named call at or after its target.
  `assertReleasedOnEveryExit`: a release call lies between each return after a region's first call and the
  region's last call before that return, and a handler that catches everything and covers the region's call makes
  the release before it throws. `assertReturnsFirstCallOf`: the instruction after a call is a return.
  `assertNeverCalls`: none of the named calls is made. `MacFontProbeShim` gains `mutableData`, `retain` and
  `autorelease` (`CFDataCreateMutable`, `CFRetain`, `CFAutorelease`), and `CoreTextPeerShim` gains `mayFindFace`.
  `AUTORELEASED_BLOCKS_PER_CALL` is package-private, still 10.0.

### The further fixes
Paths as under "Problem". Reading US-111's traces, critiques, slice records and reviews again while PR 71 was
reviewed, on 2026-11-21 and 2026-11-22, found fourteen defects and test gaps beside the twelve sites. On 2026-11-22
the owner ruled that this PR fixes every one of them, with tests. They were fixed in six clusters, one at a time in the
order A (`CTGlyph`: items 1, 2, 7, 9 and 13), D (NULL inputs and guards: items 6, 7, 11 and 12), C (the cascade-list
call: items 4, 5, 11, 12 and 14), B (font maps and fallback slots: items 3, 4 and 8), E (layout output and face
lookup: items 8, 10, 11 and 12) and F (the font layer's other shared state, beside items 1 to 4), then a final tidy
step (FT1 to FT5), one fix round after a review of the whole tree, a hardening round of the tests (rulings L1 to
L6) with one fix round of its own, and a closing round of the tests (rulings K0 to K3). Each cluster was designed by
one agent, its design checked by an independent reviewer and ruled on by the session's manager, and its
implementation reviewed until accepted (see "Independent review").

All of it was made on `f352c6008c` on 2026-11-23 and committed as `e1de3dd32a`. `git diff --stat` over the 37 tracked
files, against `f352c6008c`: 5,092
insertions, 932 deletions (17 production files 518 and 323, 6 shims 300 and 16, 13 test files 4,256 and 583,
`mac-layout-golden.txt` 18 and 10); 11 files are new, 1 shim and 10 test files (`impl2/CLOSE/docs/r0/diffstat.txt`).
Mutant names are those of the cluster records; where clusters reuse a name, the cluster is named (cluster A's M1a is
not cluster F's M1a). "Any OS" marks a class-file, reflection or pure-Java test that loads no platform font factory
and touches no native, and so is enabled on the Linux and Windows CI hosts too (it has not run there as of
2026-11-23); "macOS" marks `@EnabledOnOs(OS.MAC)`. The mutants are tabled under "Criterion 6".

**Races.**
- **1. Two threads drew into the one cached bitmap** (A). `coretext/CTGlyph.getImage` took the one 256×256 context
  from `getCachedContext`, whose lock was released when it returned, then filled, drew into and read it, so two
  threads drawing two glyphs at once did so in the same bitmap.
  - Now the cached branch holds `CONTEXT_LOCK` from `getCachedContext` through `setStrikeOptions` and the whole draw,
    whose body moved verbatim into a private `drawImage`; `getCachedContext` keeps its own lock, entered again under
    the outer one, so site 9's test still decides. Only the render thread takes the cached path today, so the fix
    is preventive.
  - `MacGlyphImageTest.twoGlyphsDrawnAtOnceEachGetOnlyTheirOwnPixels` (macOS, child JVM): two platform threads draw
    'W' and 'o', the first held after its draw until the second is BLOCKED in `CTGlyph.getImage` or has returned.
    Each thread's image is compared with its glyph drawn alone a second time into the cached context (`again.first`,
    `again.second`), so that each compared draw finds the context last drawn with the other glyph; the first pass
    alone keeps its preconditions (both glyphs have images, they differ, they fit, the cached context was made).
    Without the lock the hold reads "second finished" and the first image is `525;716a9ab8…` where `again.first` is
    `525;40a58278…` (`impl2/LAST/M1a-last.cachedDraw.txt`, `M1b-last.cachedDraw.txt`). Mutants M1a and M1b (A);
    US-111's M9 is still killed.
- **2. Bounds were published before they were filled and clamped** (A). `checkBounds` stored a new `CGRect` in a
  plain field before it computed the advance, filled, transformed and clamped it, so another reader of the glyph could
  get a zero, unfilled or unclamped box.
  - Now `bounds` is volatile and filled once, under the glyph's monitor, by `measureBounds` from a local box, and each
    reader takes one box. A measurement that throws stores an empty box and rethrows, so the glyph throws once and is
    empty afterwards, as before.
  - `MacGlyphImageTest.aGlyphReadWhileItsBoundsAreMeasuredGetsThemInFull` and
    `aGlyphWhoseMeasurementFailsThrowsOnceAndIsEmptyAfterwards` (macOS, child JVMs);
    `MacGlyphImageRulesTest.aGlyphPublishesItsBoundsOnlyOnceTheyAreMeasured` (any OS: volatile, each store after
    `measuredBox` or `CGRect.<init>`, reads only in `checkBounds` and `measureBounds`). The box's input,
    `PrismFontFile.bbCache`, is cluster F's (below). Mutants M2a to M2h (A).
- **3. Map and slot writes without the lock their readers take** (B).
  - `PrismFontFactory.storeInMap` put into `fontResourceMap` without the factory's monitor, from layout threads, while
    `getFontResource` read it under that monitor. Now `storeInMap`, `getDefaultFontResource` and
    `populateFontFileNameMapGeneric` (a reentry for its one production caller) are `synchronized`, so every use of
    `fontResourceMap`, `compResourceMap` and `fileNameToFontResourceMap` is under the factory monitor.
    `CompositeFontLocksTest.aFontStoredWhileItsFactoryIsBusyIsFoundOnceItIsFree` (any OS, a factory of no platform)
    and `theFontMapsOfTheFactoryAreUsedOnlyUnderItsMonitor` (any OS, the class files of the factory's nest). Mutants
    B-M1 to B-M5 and B-M29 to B-M31.
  - `LogicalFont.addNativeFallback` and `FallbackResource.addNativeFallback` read, copied and wrote back
    `nativeFallbacks` without a lock, so two fonts added at once could take one slot, one of them lost and its glyphs
    drawn with the other, and one font two. Now each is `private synchronized`, looks the name up again under the
    lock, and publishes a fresh copy only once it is filled; `FallbackResource`'s four slot lists are volatile, and
    once the fallbacks are known the readers other than the synchronized `getSlotResource` take no lock.
    `CompositeFontLocksTest` (macOS: two fonts added at once take two slots, one font added twice takes one, a reader
    answers while the lock is held, each decided by thread state against a monitor the test holds, never by time; any
    OS: `theSlotListsOfEachCompositeAreVolatile`, `eachCompositePublishesTheFontsItAddsOnlyOnceFilled` and
    `noCompositeDropsTheFontsItAdded`, the last through `FontCallOrder.assertNeverStoresNull`: no method of either
    composite stores null into `nativeFallbacks`, since no reader makes it again). Mutants B-M6 to B-M10, B-M15 to
    B-M18, MNF1 to MNF5, LAST-L4a and LAST-L4b.
  - `LogicalFont.getSlot0Resource` and `getSlotResource` could store null and read their field again. Now each reads
    its field once into a local and stores once (since cluster F through a volatile field and a filled copy, below).
    `aLogicalFontReadsAndFillsEachSlotOnceAndAnswersWhatItReadOrFilled` (any OS). Mutants B-M11 to B-M14 and B-M32 to
    B-M36.
- **4. Every racing caller looked up a composite's fallbacks, and every racing virtual caller started a thread**
  (B, C).
  - Both `getLinkedFonts` checked and set `fallbacks` with no lock. Now each checks it again under the composite's
    monitor and stores it last, after the names and files. `CompositeFontLocksTest`: three tests on macOS, in which
    racing callers wait for one look-up, and `eachCompositeLooksUpItsFallbacksOnlyWhileTheyAreUnknownUnderItsLock`
    and `eachCompositeSetsItsFallbacksAfterTheirNamesAndFiles` (any OS). Mutants B-M20 to B-M28.
  - Since US-111 each virtual caller of `MacFontFinder.getCascadeList` started a platform thread of its own. Now one
    daemon platform thread, "JavaFX font cascade list", started by the first virtual caller and found through a
    volatile fast path, makes the lists of all virtual callers: `queueCascadeListTask` adds the task to a
    `ConcurrentLinkedQueue` and unparks the thread after every add, and the thread polls and runs, or parks and
    clears its interrupt. Since the Copilot review the thread outlives anything a task throws (F2), and a caller
    that cannot start it gets the error (F3). `MacFontReleaseTest` (macOS):
    `virtualCallersAtOnceShareOnePlatformThread`,
    `aVirtualCallerWaitsForThePlatformThreadToMakeItsList`, `theCascadeListThreadIsAPlatformThreadThatInheritsNothing`
    and `anInterruptOfTheCascadeListThreadNeitherEndsItNorKeepsItFromParking`. `MacFontBindingStructureTest` (any OS):
    S4, `theHandOffQueuesATaskForThePlatformThreadAndNeverRunsItItself` (the hand-off and its queue helper never run
    a task, a task enters the queue only by `add` in `queueCascadeListTask`, and each add is followed by the unpark
    with nothing between them that branches, switches, returns, throws or begins a handler; since the Copilot review
    on the path the add's return takes, F4), and
    `theCascadeListThreadTakesNoFontLayerMonitor`. Mutants C-M1, C-M2a, C-M2b, C-M12 to C-M14, C-M16 to C-M19, C-M21,
    C-M32 to C-M34 and C-M37 to C-M40, and US-111's M10b, M10h and M10l.
- **5. The error edges of the cascade-list call** (C). The caller released the font it lent as soon as
  `getCascadeList` returned, so `resultOf` had to outlast every error of the wait, and did not: a `StackOverflowError`
  at its call or from `isDone()` ended it while the worker read the font, an `OutOfMemoryError` while `FutureTask.get`
  built its `InterruptedException` lost the interrupt, after any error of `get()` the caller spun on its carrier, and
  an `OutOfMemoryError` while the pool token was boxed left a pool pushed.
  - Now `CascadeListTask` retains the lent font on the caller before the task is queued; on the worker it keeps what
    the read throws for the caller, then releases its reference inside a pool of its own, sets `done` and unparks the
    caller. The caller's release is then safe at any time, and `resultOf`, the `FutureTask` and the spin are gone.
    Since the Copilot review the hand-off takes that reference for the task right before the add and releases it
    again if the task does not enter the queue (F4), and a failure of the release is kept for the caller too, who is
    woken either way (F2).
  - `CascadeListTask.awaitList` parks until `done`, records an interrupt before it clears it, and sets it again in a
    `finally` before it returns the list or rethrows the task's failure.
  - `ObjCNative` binds the pool token as `JAVA_LONG` (deviation 12).
  - `MacFontReleaseTest` (macOS): `theTaskHoldsAReferenceOfItsOwnToTheLentFontUntilItHasRun` (the task's reference
    while it waits a plain assertion, its release a verdict, item 12),
    `anInterruptTheWaitClearsDoesNotEndItAndIsSetAgainOnceTheTaskAnswers` and
    `anInterruptedVirtualCallerGetsTheWholeCascadeListAndKeepsItsInterrupt`; `MacObjCNativeTest` (macOS, the long
    tokens and descriptors); `MacFontBindingStructureTest` (any OS): S7,
    `theWaitRecordsAnInterruptBeforeClearingItAndSetsItAgainOnEveryExit`, S8,
    `aTaskReleasesItsOwnReferenceToTheFontOnEveryPathBeforeItWakesTheCaller`, and
    `aTaskKeepsWhatItsReadThrowsForItsCallerAndNeverThrowsIt` (since the Copilot review
    `aTaskKeepsWhatItsReadAndItsReleaseThrowAndNeverThrowsIt`). US-111's
    `anErrorOfTheWaitItselfIsThrownOnlyOnceTheTaskIsDone` and its mutant M10d are retired (deviation 13). Mutants
    C-M5 to C-M11, C-M20, C-M22 to C-M26 and C-M28 to C-M30.

**NULL or 0 handed to CoreFoundation, CoreGraphics or CoreText outside the twelve sites.**
- **6. At entry points only shims reach** (D).
  - `DFontDecoder.getCTFontFormat`, `getCTFontTags` and `getCTFontTable` handed font 0 to CoreText; each now answers
    0, null and null for font 0 first. `MacFontNullInputTest.aZeroDfontFontHasNoFormatTagsOrTable` (macOS, child JVM,
    with Helvetica controls) and `MacFontBindingStructureTest.aZeroDfontFontReachesNoCoreTextCall` (any OS); CoreText
    answers 0 and no table for a NULL font on macOS 27.2.1, so only the class-file check sees a deleted guard. Mutants
    D1 to D5, Xa, Xh-t and Xs-t.
  - `OS.CFStringCreateWithCharacters(long, char[], long)` handed NULL to CoreFoundation for null characters with a
    count above 0 (SIGSEGV in a probe) and read past the copy for a count beyond the array. It now answers 0 for a
    negative count or one beyond the array, null counting as empty; (null, 0) still makes the empty string (golden row
    `ct.str.nullEmpty`). Production passes the array's length, so its results do not change.
    `aCountTheCharactersDoNotHoldMakesNoString` (macOS, child JVM). Mutants D6 to D9.
  - `MacFontFinder.cascadeList` handed a NULL font to `CTFontCopyDefaultCascadeListForLanguages`. It now asks CoreText
    nothing for a NULL font, inside its existing `try`, and still releases the codes.
    `aNullFontHasAnEmptyCascadeListAndItsCodesAreReleased` (macOS) and `aNullFontAsksCoreTextForNoCascadeList` (any
    OS). Mutants D10 to D12.
- **7. One-time objects that were never checked** (D, A).
  - The "." branch of `OS.CTFontCreateWithName` handed `CFNative.boldCFString()` to `CFStringFind` unchecked. It now
    searches only for a bold string CoreFoundation made. `aFontNameIsSearchedOnlyForABoldStringCoreFoundationMade`
    (any OS). Mutants D13, D14 and Xe.
  - `CTGlyph`'s `GRAY_COLORSPACE` and `RGB_COLORSPACE` went to `CGBitmapContextCreate` unchecked. A new private
    `bitmapContext` answers 0 for a colour space of 0, so the image is empty, site 6's outcome.
    `MacGlyphImageTest.aColourSpaceCoreGraphicsDidNotMakeGivesNoContext` (macOS). Mutant M7 (A).

**Wrong output.**
- **8. A run whose font takes no slot was drawn with another font** (E, B). Each part changes what layout gives
  (deviation 15); ten rows of `mac-layout-golden.txt` moved with them (deviation 16); deviations 18 and 19 are two
  outcomes of layout that stay as stated.
  - 8a. `CTGlyphLayout.layout` stored the glyph IDs of a run for which `getFontSlot` answered -1 with slot 0, so they
    were drawn with the slot-0 font, in which the same IDs are other glyphs: the System font's Devanagari run in
    `.SF Devanagari Regular`, glyphs `7f;40a;f3;d5;40`, was drawn with SF. Now each glyph of such a run is
    `CharToGlyphMapper.MISSING_GLYPH`, except entries that are `INVISIBLE_GLYPH_ID`. A run in the strike's own font
    keeps the strike's slot whatever its name: `layout` hands `getFontRef`'s answer to `createCTLine` and to
    `getFontSlot`, whose address guard sits below the name lookup, so wherever a slot exists today it is kept.
    `MacFontReleaseTest.aRunWhoseFontTakesNoSlotIsLaidOutWithTheMissingGlyph` and
    `aRunInTheStrikesOwnFontKeepsItsSlotWhateverItsName` (macOS, verdicts) and
    `MacFontBindingStructureTest.layoutHandsTheSlotLookupTheFontItsLineWasMadeWith` (any OS). Mutants M8a1 to M8a9,
    M8g1 to M8g7 (M8g7 survives) and MV4 to MV6.
  - 8b. `PrismCompositeFontResource.getSlotForFont` turned "no slot" (-1) into slot 0, and the class had no
    `addSlotFont`. Both now answer -1 for no slot and one above the fallback's slot otherwise, on every platform.
    `PrismFontFactoryTest.aCompositeSlotIsOneAboveItsFallbackSlotAndNoSlotStaysNone` and
    `aCompositeAnswersItsFallbackListsSlotsThroughTheMapping` (any OS),
    `aCompositeHasNoSlotForAFontItsFallbacksCannotHold` and `aFontAddedToACompositeIsInTheSlotItIsGiven` (macOS).
    Mutants M8b1 to M8b4.
  - 8c. `FallbackResource.addNativeFallback` answered `ns + 1` for the font it stored at index `ns`, so the glyphs of
    the first font a font file's composite added at layout carried a slot one too high; upstream has it since
    JDK-8246104. It now answers `ns`. The slot tests of `CompositeFontLocksTest`,
    `aFontAddedToACompositeIsInTheSlotItIsGiven` and `aRunInTheStrikesOwnFontKeepsItsSlotWhateverItsName` (macOS).
    Mutants B-M19, M8c1 and M8c2.
- **9. The cached context kept the first strike's settings** (A). The one cached context was made with the sub-pixel
  positioning and quantization of the first strike that drew into it, and every later glyph that fit drew with them.
  Now each draw into it sets its own strike's options (`setStrikeOptions`) under `CONTEXT_LOCK` before it draws; a
  fresh context gets exactly the calls it got before.
  `MacGlyphImageRulesTest.eachGlyphDrawnIntoTheCachedContextSetsItsOwnStrikesOptions` (any OS, the order of the
  calls) and `MacGlyphImageTest.aGlyphIsDrawnWithTheSubpixelOptionsOfItsOwnStrike` (macOS, a verdict, `CANNOT_SHOW` on
  a machine that draws the glyph alike with and without sub-pixel positioning; each of its three images is the second
  draw into its context, item 12). The LCD half is not a defect: `lcdEnabled` is set once, in `PrismFontFactory`'s
  static initialiser, and `PrismFontStrike` asks for grey-scale unless LCD text is on, so every glyph asks for the
  colour space the context has; `aStrikeIsLcdOnlyWhereLcdTextIsOn` (macOS) pins that rule. No row moved: all 5,575
  rows of the five corpora, the rows the design named among them, are byte-identical before and after cluster A on
  this Mac (deviation 24). Mutants M9a, M9b and M9lcd (A).
- **10. An unreadable font file was remembered as a missing face** (E). `PrismFontFactory.findFontIndex` caught every
  exception and answered -1, as for a readable file without the face, and since US-111 `CTFactory.createFontFile`
  remembered -1 by name, so a file that could not be read at the first layout was never read again in the process.
  - Now `findFontIndex` throws what reading the file throws (`throws Exception`, no catch-all), and `createFontFile`
    remembers a name only for a readable file without the face or a font that reports no file. A read failure reaches
    `getFontSlot`, whose catch gives slot -1, and the next layout looks again. A face a readable file lacks is still
    looked up once per process (since the Copilot review, once while its file is unchanged, F5). Nothing is read from
    disk under the factory monitor, as before.
  - `PrismFontFactoryTest.findFontIndexThrowsWhatMakingTheFontFileThrows` (any OS),
    `findFontIndexThrowsWhatReadingTheFileThrows` and `findFontIndexAnswersForAReadableFile` (macOS);
    `MacFontReleaseTest.aFaceLookupThatCannotReadItsFileIsMadeAgainAtTheNextLayout` and
    `aFallbackFontThatReportsNoFileIsLookedUpOnce` (macOS, verdicts). Mutants M10a to M10d and M10f (E) and US-111's
    M12a.

**Tests and code.**
- **11. Guards no test pinned in full** (D, E, C).
  - Site 3's guarded release of the name string, which R3 left alive:
    `aDfontNameStringIsReleasedOnlyWhereCoreFoundationMadeIt` (any OS) puts both copies of the release on the
    fall-through of the test of the string. Mutants R3, D17, D18 and Xd.
  - Site 3's return for a NULL name inside the `try` of `fontNamed`, so that the descriptor array is still released:
    the child of `aDfontWithoutACollectionOrANameHasNoFont` (macOS) reads the array's retain count. Mutant F1 (D).
  - Site 4's length test: `aFontNameIsMeasuredBeforeItsFirstCharacterIsRead` (any OS) now also requires that the
    first character is read only where the length is not 0, and `aZeroFontNameHasNoFontAndTheEmptyNameIsHelvetica`
    (macOS) compares an empty name over a "." with CoreText's own answer. Mutants D15 (`>= 0` for `> 0`), US-111's
    M4c and Xc.
  - Site 5's guard, which R5 left alive: `aStrikeWithoutACtFontAsksCoreTextForNoAdvance` (any OS). Mutants R5, D16
    and Xb; at run time CoreText still answers 0.0 for a NULL font on macOS 27.2.1.
  - Site 6: `aContextCoreGraphicsDidNotMakeIsNotConfigured` (any OS) pins the four setters to `setStrikeOptions`, its
    callers to `createContext` and `getImage`, and each call to where the context is not 0. Mutants US-111's M6, M6g,
    Xf and Xg.
  - Site 12: `layoutAsksForAFallbackFaceBeforeCopyingTheRunFont` (any OS) kills a body that asks `mayFindFace`, copies
    the run's font anyway and uses the answer only for `addFallbackFont`; the weakened guards it lets pass fail at run
    time. A run-time check of the copy was withdrawn (deviation 20). Mutants M12g, M12h and M12i (E), US-111's M12b
    and M12f, and Ga (D). Since the Copilot review the copy is made in `lookUpFallbackFont`, and
    `layoutCopiesTheRunFontOnlyWhereTheFactoryMayFindItsFace` replaces this check (F5).
  - Site 10: S5, `onlyThePooledRegionAndTheThreadlessFallbackReadTheCascadeList` (any OS; since the Copilot review
    `onlyThePooledRegionReadsTheCascadeList`, the fallback being gone, F3), allows
    `CTFontCopyDefaultCascadeListForLanguages` only in `MacFontFinder.cascadeList`, over every class file of the font
    package; S4, S7, S8 and the keep check above read what US-111's checks read only by call order. The worker no
    longer exits, so its thread's exit no longer drains a task that pushes no pool: the virtual drain check now fails
    US-111's M10a and M10g, and C-M4. Mutants C-M3, C-M3b, C-M4, C-M15, C-M15b, C-M27 and C-M31, and US-111's M10a,
    M10g and M10k.
- **12. Tests that depended on the machine** (C, E, D). On CI run 45041254324 both macOS 15 runners answered
  `CANNOT_SHOW` for the two heap checks and the fallback-face check (see "Measured"). Now:
  - The two drain checks also read the pool tokens: the child reads the token a fresh push answers around each of 10
    cycles on a platform thread and 10 on the worker, with an empty region as a control. A check is `PROVED` by the
    heap where the region without a pool leaves more than 10 blocks per call, or by the tokens where no call and no
    empty region moved them and the region without a pool moved them in every cycle; `CANNOT_SHOW` where neither can
    show; `REFUTED` by a call that leaves 10 or more, on any host. `MacFontReleaseVerdictTest` (any OS) carries CI run
    45041254324's macOS 15 figures, labelled recorded, and labels every other figure invented.
  - The fallback-face check no longer depends on a system font: the child copies Ahem into a scenario directory, makes
    the run's font and a line from it, replaces the file with Helvetica.ttc, measures its preconditions after the swap,
    and lays out 5 times. An unmet precondition is `CANNOT_SHOW`; an answer other than -1, an unresolved count other
    than the layouts, or other than one lookup naming the file is `REFUTED`. `MacFontReleaseVerdictTest` carries the
    macOS 15 lines of CI run 45041254324 as recorded: `CANNOT_SHOW`.
  - The log check of site 6 decides through `MacFontNullInputTest.contextVerdict`: a log the account cannot read, or
    setters that no longer log a call on NULL, are `CANNOT_SHOW`. `MacFontNullInputVerdictTest` (7, any OS) pins the
    verdict.
  - Since the hardening round (rulings L1 to L5) no macOS run-time check compares two figures the host decides that
    were taken differently, with a plain assertion or with a verdict that refutes a mismatch. The round changed the
    three below, and a search of every test the PR adds or changes found no other.
  - The lent-font check of item 5 takes its baseline twice in a row: the pooled region, run on the cascade-list thread
    after that thread's first list, moves the font's retain count by `query.delta` over its second list and by
    `query.again.delta` over its third. `retain.afterRun` is the change over the caller's call, which spans the task's
    list, the fourth: from before the task's `CFRetain` to after its own release. The rule `lentFontReleased` of
    `MacFontReleaseVerdict` answers `CANNOT_SHOW` where the two baselines differ, `PROVED` where they are equal and
    `retain.afterRun` equals them, and otherwise `REFUTED`, naming the references the task kept, or released beyond the
    one it took; its javadoc and both messages name that window ("the caller's call, from before its task took its
    reference to after the task had run, moved the font's retain count by …", ruling K3). On this Mac all three are 0.
    `MacFontReleaseVerdictTest` (any OS) pins the rule with this Mac's figures, C-M6's (0, 0 and 1 on this Mac), and
    invented ones: a task that released more than it took, baselines settled at 1, and baselines that differ in either
    order.
  - `twoGlyphsDrawnAtOnceEachGetOnlyTheirOwnPixels` compares each thread's image with a second draw alone (item 1),
    where it compared it with the draw that made the cached context.
  - In the sub-pixel check the probe draws the other strike's 'A' at sub-pixel index 0 into each of its two contexts,
    with `CTGlyphShim.drawInto`, and discards the image, as production drew that glyph into the cached context before
    the subject; so `own`, `other` and the subject are each the second draw into their context. The verdict rule is
    unchanged.
  - `MacFontReleaseVerdict.decide` is the one gate of `MacFontReleaseTest`, `MacFontNullInputTest` and
    `MacGlyphImageTest`: it prints the check's line on the fork's fd 2, then requires the verdict through `ParityGate`.
    `MacFontNullInputVerdictTest.assertDecidesThroughItsVerdictAndAssumesNothing` pins, on any OS, that the log check,
    the sub-pixel check and the lent-font check (the last from
    `MacFontReleaseVerdictTest.theLentFontCheckDecidesThroughItsVerdictAndAssumesNothing`) call it and make no
    assumption; the seven other verdict checks of `MacFontReleaseTest` have no such pin.
  - Mutants T1 to T3, L1, L2, L2b, C-T35, C-T36, MV1 to MV3, LAST-L1T, FR1-A to FR1-C and K3-M1 to K3-M3; T-sanity
    is a control. The rows of items 1, 4, 5 and 9 whose killing checks the hardening round changed were run again: by
    that round, and US-111's M10b and M10h and C-M32 by the closing round (see "Criterion 6").
- **13. A condition that was always true** (A). The catch of `CTGlyph.createContext` released the context only
  `if (context != 0)`, always true since US-111 returns for 0 first. It now releases unconditionally.
  `MacGlyphImageRulesTest.aContextWhoseOptionsFailIsReleasedUnconditionally` (any OS). Mutants M13 and M13b (A).

**Cost.**
- **14. A virtual caller's call cost more heap on macOS 15** (C). There a virtual caller's call left 10.73 (arm64) and
  10.94 (x64) heap blocks per call, more than the region without a pool, with a thread started per call. With one
  thread per process the per-thread cost is paid once (item 4). `virtualCallersAtOnceShareOnePlatformThread` (macOS)
  pins the cause; the virtual drain check refutes a call that leaves 10 or more on every host, and
  `MacFontReleaseVerdictTest.aVirtualCallOfMacOs15ThatStartsAThreadPerCallIsTheDefect` refutes CI's 10.73 and 10.94.
  On CI run 45278364431 (`e1de3dd32a`) the worker's call left 4.02 (arm64) and 4.05 (x64) blocks per call on macOS 15,
  and the virtual drain check was `PROVED` there by the pool tokens.

**Beside items 1 to 4: the font layer's other shared state** (F). Cluster F's design found more state that layout and
rendering threads share without a lock or a safe publication. Now:
- `PrismFontFile.bbCache` and `PrismFontStrike.glyphMap` are final `ConcurrentHashMap`s: a miss is measured or made
  outside the map and stored with `putIfAbsent`, and the first stored is kept. `computeIfAbsent` is not used: the
  measurements take monitors or call DirectWrite, and on the per-glyph path it can lock a bin on a hit.
- `CompositeGlyphMapper`: `glyphMap` is a `ConcurrentHashMap`, `slotMappers` is volatile and replaced only by filled
  `Arrays.copyOf` copies, and `charToGlyph` is volatile, read once and published once filled.
- `CompositeStrike`: `slot0Strike` and `strikeSlots` are volatile, read once and replaced only by filled copies; its
  `metrics` is volatile.
- The lazily made glyph mappers of `LogicalFont`, `FallbackResource`, `PrismCompositeFontResource` and
  `PrismFontFile`, the `metrics` of `PrismFontStrike`, and `PrismFontFile.styleMetrics` and `PrismMetrics.styleMetrics`
  are volatile and read once; `getStyleMetrics` fills a local array and publishes it once filled.
- `LogicalFont.slot0FontResource` is volatile, and `getSlotResource` reads `fallbacks` and `nativeFallbacks` once
  each and publishes a filled copy of `fallbacks`; neither it nor `getSlot0Resource` enters a monitor in its own code
  (what they call can: deviation 17).
- `PrismFontFactory`'s three name methods are `synchronized`, and its shutdown hook runs the private `synchronized`
  `disposeFontsOnShutdown`, so `embeddedFonts` and the two name lists are used only under the factory monitor.
- Single-threaded results do not change: the corpus dumps before and after cluster F are byte-identical. The lock
  order is deviation 17; deviations 21 and 25 state what cluster F leaves as it was and what it adds beyond its
  rulings.
- `FontSharedStateRulesTest` (11, any OS, class files), `CompositeSlotRaceTest` (2, any OS, pure Java with a fake
  composite), the class-file tests of `CompositeFontLocksTest` (any OS) and `MacFontSharedStateTest` (3, macOS). The
  final tidy pinned that `getStyleMetrics` and `getAsciiGlyphCode` publish a new array only once filled, that
  `PrismMetrics.styleMetrics` holds only what `getStyleMetrics` answers, and that the methods that read a published
  slot array write no element of it. Mutants M1a to M7g and F8a to F8k (F), and FT's.

**Tests, helpers and shims.** 116 more tests in `javafx.graphics`, all in `test.com.sun.javafx.font`: 81 on any OS
and 35 on macOS only. The column `f352c6008c` is CI run 45041254324's macOS arm64 job (`ci2/job-137266001302.log`);
"now" is the font package run on this Mac (`impl2/CLOSE/evidence/font.log`):

| class | `f352c6008c` | now | hosts |
| --- | --- | --- | --- |
| `CompositeFontLocksTest` | – | 20 | 9 any OS, 11 macOS |
| `CompositeSlotRaceTest` | – | 2 | any OS |
| `FontSharedStateRulesTest` | – | 11 | any OS |
| `MacFontNullInputVerdictTest` | – | 7 | any OS |
| `MacFontSharedStateTest` | – | 3 | macOS |
| `MacGlyphImageRulesTest` | – | 8 | any OS |
| `MacGlyphImageTest` | – | 6 | macOS, 4 in child JVMs |
| `MacFontBindingStructureTest` | 15 | 27 | 26 any OS, 1 macOS |
| `MacFontNullInputTest` | 18 | 21 | macOS, child JVMs |
| `MacFontReleaseTest` | 15 | 23 | macOS, child JVMs; one test deleted (deviation 13) |
| `MacFontReleaseVerdictTest` | 30 | 59 | any OS |
| `PrismFontFactoryTest` | 2 | 9 | 4 any OS (upstream's `testGetFontFactory` makes the platform's factory), 5 macOS |
| `coretext.MacObjCNativeTest` | 4 | 4 | macOS |

- `FontCallOrder` (376 lines at `f352c6008c`, 1,782 now) has 29 class-file helpers, 23 of them new:
  `assertCallFollowsUnconditionally`, `assertKeepsWhatItThrows`, `assertCallsOnlyWhereAnswerIsNotZero`,
  `assertCallsOnlyWhereParameterIsNotZero`, `assertArgumentIsAnswerOf`, `assertHandlerReleasesUnconditionally`,
  `assertFieldStoresFollow`, `assertFieldReadOnlyIn`, `assertCalledOnlyIn`, `assertCalledOnlyInHandler`,
  `assertWrittenAfter`, `assertGuardStoredBetween`, `assertTakesNoMonitor`, `assertReachedOnlyUnderTheMonitor`,
  `assertCalledOnlyWhileNullUnderLock`, `assertStoredLastUnderLock`, `assertReadOnceAndStoredOnce`,
  `assertFieldReadOnceIn`, `assertPublishesOnlyFilledCopies`, `assertPublishesNewArrayOnceFilled`,
  `assertNeverStoresNull`, `assertStoresNoArrayElement` and `assertFieldIs` (what each pins is in
  `jfx-ffm-testing`). A method may be named `name(descriptor)` where several share the name. `assertGuards` gained
  the dominance rule (nothing reaches a guarded call except through the guard's branch), and
  `assertReleasedOnEveryExit` reads every return and throw reached from the entry and every handler that covers the
  region's call.
- `MacFontReleaseVerdict` gained `decide`, the pool-token rule, the verdicts of items 8, 10 and 12 and the lent-font
  rule `lentFontReleased`; `MacGlyphImageVerdict`, `MacGlyphImageChild` and `RaceThread` are new helpers.
- Shims: `FontResourceShim` gains `compositeSlotOf`, `newFallbackResource`, `newMapOnlyFactory` and `storeInMap`;
  `MacFontFinderShim` gains `pooledCascadeList`, `cascadeListWorker` and `cascadeListHandOff` and loses `resultOf`;
  `MacFontProbeShim` gains `emptyStringOver`, `fontWithName`, `fontOfFile` and `fontOfData`; `CTFontStrikeShim` hooks
  every call the two-thread tests hold at; `CTGlyphShim` gains `drawInto` and `bitmapContext`; `CoreTextPeerShim`
  gains `line` and `getFontSlot`; the new `FontSharedStateShim` has `newCompositeStrike` and `loadEmbeddedFont`.

### The Copilot review
Copilot's review of PR 71 on the further fixes (`e1de3dd32a`) found three defects in `MacFontFinder`'s cascade-list
call, named F2 to F4 as the review numbers them, and two in site 12's face lookup, F5 and F6; all five are accepted,
with the decisions below, and fixed on 2026-11-23 on `e1de3dd32a` (committed since as `2c9003b909`), in two rounds:
F2 to F4 first, then F5 and F6. After the first round, `git diff --stat` over the 6 tracked files of code: 579
insertions, 100 deletions (`MacFontFinder` 76 changed lines, `MacFontFinderShim` 54, `FontCallOrder` 196,
`MacFontBindingStructureTest` 141, `MacFontReleaseChild` 145, `MacFontReleaseTest` 67); no file is new. After the
second, over the 10 tracked files of code, both rounds together: 821 insertions, 158 deletions, adding `CTFactory`
109 changed lines, `CTGlyphLayout` 59, `CoreTextPeerShim` 23 and `MacFontBindings` 10, with
`MacFontBindingStructureTest` 201, `MacFontReleaseChild` 182 and `MacFontReleaseTest` 69 in all and `FontCallOrder`
unchanged by the second round; new are `CTFactoryShim`, `MacFontFaceLookupChild` and `MacFontFaceLookupTest`. After
the fourth round (R1 to R4), over the same 10 files: 888 insertions, 158 deletions, with `CTFactory` 136 changed
lines, `CTGlyphLayout` 60, `FontCallOrder` 226 and `MacFontBindingStructureTest` 210; the new files are
`CTFactoryShim` 143 lines, `MacFontFaceLookupChild` 632 and `MacFontFaceLookupTest` 382. Classes as under
"Criterion 6"; "S4" and "S8" as there.
Its first comment, F1, asks for the rows of `directwrite-metrics-golden.txt` that 8c moves; it is accepted, and a third
round found that those rows cannot be edited by hand, so the golden stays as captured and the owner re-captures it
(below). That round changed no file of code or test. The fixes of all rounds are committed as `2c9003b909`; since
2026-11-24 the golden is captured again on its Windows machine, and the slot tests run on Windows and Linux ("Windows
and Linux" below).

- **F1. The DirectWrite golden still has the slots of `ns + 1`** (high). Accepted: on its capture machine the
  comparison of `DirectWriteMetricsGoldenTest` fails until the 609 rows of deviation 16 move. The rule for an edit by
  hand, as for the ten rows of `mac-layout-golden.txt`, is that every new value is the old one with only the slot byte
  of a glyph code, a key or a code list changed, read from the same font by the same capture code. That holds for the
  9 layout rows and not for the 300 glyph rows that come in place of the 300 that go, so the file is not edited.
  - The capture, `captureFont` (`DirectWriteMetricsGoldenTest.java:201-243`), records `map.*`, then lays the text out
    (`captureLayout`, `:227`), then records `slots.*` (`:229-237`), and only then every glyph code of the map and the
    runs through `captureGlyph` (`:240-242`), which draws each with the font of its slot byte:
    `CompositeStrike.getGlyph` (`CompositeStrike.java:176-179`) asks `getStrikeSlot(code >>> 24)` for the strike and
    hands it the code without the slot. The code a run gets is `slot << 24` with the glyph ID DirectWrite drew
    (`DWGlyphLayout.java:390-392`), and the slot is `composite.getSlotForFont` (`:321`). At the capture commit
    `26ce75d02f` that was `fallbackResource.getSlotForFont(name) + 1` (`PrismCompositeFontResource.java:65-70` there),
    where a font the fallback resource did not hold yet was added at index `ns` and answered as `ns + 1`
    (`FallbackResource.java:270` there), so the first lookup of each added font gave the composite slot after the
    font's own; later lookups find it by name and answer its own (`getSlotForFontNoCreate`). The 24 pt capture of
    each font reuses the 12 pt composite (`PrismFontFactory.createFont`, `getFontResource` through `compResourceMap`,
    `:615-625` and `:700-702`), whose added fonts it finds by name, so its codes carry each font's own slot and,
    glyph ID for glyph ID, name the font of each 12 pt glyph.
  - The 9 layout rows (`layout.run.5.glyphs` and `run.6.glyphs` of arial, segoeui and timesnewroman at 12 pt, `run.4`
    to `run.6` of consolas) are re-keys: their glyph IDs equal the 24 pt rows', each code that differs is one slot
    higher, and every other `layout.*` row comes from DirectWrite's advances and offsets, not from the slot (the CJK
    run's positions advance 12.0 per glyph, Yu Gothic UI's full width, under the old slots too).
  - The 300 `glyph.<code>.*` rows that go hold data of another font than the one their new code names. In arial,
    segoeui and timesnewroman the old slot 13 was Nirmala UI once the run after the CJK run had added it (`slots.13`),
    so the rows of Yu Gothic UI's CJK glyphs 3286, 3724 and 1954 were drawn in Nirmala UI (for 3286 advance 15.550781
    and 134 outline segments of one sha256 in all four fonts, consolas' old slot 14 included, where Yu Gothic UI's
    glyph advances 24.0 at 24 pt, with 31 segments); the old slot 14 was past `slots.count` = 14, where
    `FallbackResource.getSlotResource` answers null (`FallbackResource.java:333-337`) and
    `PrismCompositeFontResource.getSlotResource` the primary (`PrismCompositeFontResource.java:83-93`), so Nirmala
    UI's glyph 407 was drawn in the primary (advances 6.673828, 7.201172 and 5.326172, one per font; Nirmala UI's 407
    advances 17.214844 at 24 pt). In consolas the old slots 13, 14 and 15 drew Segoe UI's Arabic glyphs in Yu Gothic
    UI (advance 12.0 each), Yu Gothic UI's in Nirmala UI and Nirmala UI's 407 in Consolas.
  - Of the 300 rows that come, those of 16 glyphs, 240 rows, are nowhere in the file: Yu Gothic UI's 3286, 3724 and
    1954 and Nirmala UI's 407 at 12 pt, in each of the four fonts. Their advance, bounding box, texture, mask and
    outline need DirectWrite to draw them at 12 pt; the 24 pt rows do not scale to them (a hinted mask and its sha256
    do not). Only consolas' four Segoe UI glyphs, 60 rows, have a 12 pt row of the same font, file and glyph ID, in
    segoeui's own capture.
  - So the golden is not edited, and the comment stays with the owner's re-capture on its Windows 10 19045 machine
    (deviation 16). A script that reads the golden and asserts each of these facts, `round3/scripts/dw_derive.py`,
    found exactly the 609 rows of deviation 16: 9 layout rows, 300 glyph rows out and 300 in, no new key already in
    the file, every new slot byte below `slots.count` (see "Logs").
  - The comment also asks for validation on Windows and Linux. CI run 45278364431 (`.github/workflows/submit.yml`,
    the push of `e1de3dd32a`, which holds fix 8c) passed its Windows x64, Linux aarch64, macOS aarch64, macOS x64 and
    No JNI jobs. Its Linux x64 job failed before it compiled anything: Maven Central answered HTTP 429 for
    `org.apache.maven.plugins:maven-enforcer-plugin:pom:3.5.0`, so the root project `openjfx` failed at its plugin
    resolution and every module was skipped. That job says nothing of the code, and needs a run again (read with
    `gh pr checks 71` and `gh run view 45278364431 --job 138055973171 --log-failed`, `round5/`). Windows x64 runs
    `DirectWriteMetricsGoldenTest`'s capture but skips its comparison at the machine gate, so it shows that 8c lays
    the fonts out without failing, not that the golden's rows match. Since then CI run 45456403357, of `2c9003b909`,
    passed all six jobs, its Linux x64 job on a second attempt after the same HTTP 429 on the first.
  - No other golden of `src/test/resources/test/com/sun/javafx/font/` depends on `ns + 1`. In `linux-pango-golden.txt`
    a font added at layout is appended after the fallback list, and the glyphs of its first lookup carry a slot byte
    past its own, at the list's end. The composite cases use slot bytes 0, 1 and 5 only. `dvs.comp.size0`, a fresh
    composite whose layouts use slot 0 only and so added nothing, holds the same ten slots as `dvs.comp`; `lato.comp`
    holds those nine fallbacks after Lato and never uses a byte past 5; the other cases use slot 0 only. So no case
    added a font and `addNativeFallback` never ran. `sys.logical` is a `LogicalFont`, whose `addNativeFallback` has
    answered `ns` since before `26ce75d02f`. `mac-layout-golden.txt` had the only other rows, the four of 8c among its
    ten (deviation 16); no slot byte of its runs reaches its font's slot count now, where `lay.pingfangsc` reached 33
    of 33 before US-111. The layout rows of `linux-font-process-golden.txt` (7) and `mac-coretext-golden.txt` (19)
    carry no slot byte, and the other goldens lay nothing out through a composite. The five macOS corpora were compared
    before and after on this Mac (criterion 7).
  - Since 2026-11-24 the re-capture is done, on the capture machine ("Windows and Linux" below), and it measured what
    this reading predicted: before it the comparison differed in 609 rows; it moved exactly the 9 layout rows and the
    300 glyph rows out and 300 in that the script found, every new slot byte below `slots.count`; the old codes had
    drawn Yu Gothic UI's CJK glyphs with Nirmala UI, Nirmala UI's 407 with the primary and consolas' Segoe UI glyphs
    with Yu Gothic UI; and the 16 glyphs no row held were drawn at 12 pt with the fonts their new codes name, Yu Gothic
    UI's 3286 with advance 12.0 and 31 outline segments, Nirmala UI's 407 with 8.607422 and 91, each consistent with
    the JNI build's 24 pt row of its code. On the Linux host the trace of `addNativeFallback` over the four corpora
    printed nothing: no case added a font there, as read above.

- **F2. A release that threw ended the cascade-list thread** (high). `CascadeListTask.run` released its reference
  inside a pool of its own with nothing around it, so a push, `CFRelease` or pop that threw (a `StackOverflowError`,
  an `OutOfMemoryError`, or a linkage error where `ObjCNative` or `CFNative` failed to bind) left `run` before
  `done = true` and the unpark. The caller parked for ever, the exception ended the one worker, and every later
  virtual caller queued onto the dead thread, which `cascadeListWorker` still named, and parked for ever too.
  - Now the release is a private `releaseFont`, called in a `try` whose catch keeps what it caught: as the task's
    failure where the read answered; where the read threw, the read's failure stays and the release's is attached to
    it as suppressed, unless they are one object, inside a catch of its own, so an attach that fails changes nothing
    (and one the JVM disabled on a preallocated error is a no-op). `done` and the unpark follow on every path, after
    the release attempt. `runCascadeListTasks` runs each task in a `try` that catches everything, so no task ends the
    thread. The interrupt handling of the wait and of the loop is unchanged.
  - `MacFontReleaseTest.aTaskWhoseReleaseThrowsStillWakesItsCallerWithTheFailure` (macOS, child JVM, scenario
    `release.cascadeListReleaseFails`): a heap segment, which no downcall takes (`IllegalArgumentException: Heap
    segment not allowed`, before any native call), is the font of a task made through
    `MacFontFinderShim.cascadeListTask` and queued through production's hand-off with no reference taken, each waited
    for by a virtual caller of its own. At address 0 the read asks CoreText nothing and answers the empty list, so
    only the release throws, and the caller is woken with the release's failure; at address 8 the read throws too, in
    `CTFontCopyDefaultCascadeListForLanguages`, and the caller gets the read's failure with the release's suppressed.
    Then the same live thread makes a virtual caller's list of Helvetica.
    `aTaskThatThrowsDoesNotEndTheCascadeListThread` (macOS, child JVM, `release.cascadeListTaskThrows`): after a task
    that throws an `AssertionError` and one that throws an `IllegalStateException`, the same thread answers the next
    caller.
  - `MacFontBindingStructureTest` (any OS): S8 reads the release in `releaseFont` (the push before `CFRelease`, a pop
    on both exits) and requires the unpark on every exit of `run` after `releaseFont`, through a handler that catches
    everything; the keep check, renamed `aTaskKeepsWhatItsReadAndItsReleaseThrowAndNeverThrowsIt`, requires of the
    handlers over `releaseFont` what it requires of those over the read, and that nothing `addSuppressed` throws
    leaves `run`; the new `theCascadeListThreadOutlivesWhatATaskThrows`, that the loop catches everything
    `Runnable.run` throws, throws none of it and has no return. `theCascadeListThreadTakesNoFontLayerMonitor` reads
    `releaseFont` too.
  - No run-time test makes the push or the pop throw (the heap segment fails `CFRelease` alone), nor
    `addSuppressed`; the class file pins both handlers.
- **F3. A virtual caller that could not start the thread made its list without a pool** (medium). When
  `startCascadeListWorker` threw an `OutOfMemoryError`, `cascadeListOnAPlatformThread` made the list on the virtual
  caller, unpooled, leaving the about 230 autoreleased blocks per call that criterion 4 forbids.
  - Now that fallback is deleted and the error reaches the caller. An empty list in its place would be worse:
    `CTFontFile.getCascadingInfo` feeds `CTFactory.getFallbacks`, and `FallbackResource.getLinkedFonts` (and
    `LogicalFont`'s) stores `fallbacks` only after `getFallbacks` returns (`FallbackResource.java:287-299`), so an
    `Error` leaves it null and the next layout asks again, where an empty list would stay the composite's fallbacks
    for its life after a passing failure to start a thread. `startCascadeListWorker` stores `cascadeListWorker` only
    after `start` returns, so a start that throws leaves it null and the next virtual caller tries again; that was so
    before, and is now pinned. No virtual thread calls CoreText for the cascade list outside the worker, and
    `getCascadeList`'s javadoc says that a virtual caller that cannot start the thread gets the error.
  - `MacFontBindingStructureTest` (any OS): S5, renamed `onlyThePooledRegionReadsTheCascadeList`, allows
    `cascadeList` and the copy of the language codes only in `pooledCascadeList`; the new
    `aVirtualCallerGetsWhatStartingTheThreadThrowsAndTheNextOneTriesAgain` requires that no handler of the hand-off
    covers its call of `startCascadeListWorker`, and that `cascadeListWorker` is stored only where `Thread.start` has
    returned and no handler around it reaches.
  - No test can make the start fail: a platform thread fails to start only where the process can make no more
    threads, which would stop the test JVM's carrier threads too.
- **F4. A reference taken for a task that never reached the queue leaked** (medium). `CascadeListTask`'s constructor
  retained the font on the caller, and nothing released that reference when the task did not enter the queue: an
  `OutOfMemoryError` while the add allocated its node, or a `StackOverflowError` at the call of
  `queueCascadeListTask` or of the add.
  - Now `queueCascadeListTask(worker, task, font)` takes the reference for the task itself, right before the add, so
    only the add lies between the retain and the queue, and a handler that catches everything the add throws
    releases it once and rethrows. The caller still holds the font it lent, so that release is never the last and
    needs no pool (the method's one why-comment). Once the add has returned, the reference is the task's: the helper
    releases it nowhere else, and no handler covers the unpark, so a failed unpark releases nothing. `font` is NULL
    for the tests' own tasks, which hold none; production's is never 0. The task's constructor only stores the font.
  - S4's rule is corrected, not weakened. The add now ends a `try`, so javac puts a `goto` over the handler, and the
    handler, between the add and the unpark in code order (the add at instruction 10, the `goto` at 12, the handler
    at 13 to 17 and the unpark at 19), which the code-order rule of `FontCallOrder.assertCallFollowsUnconditionally`
    fails. The helper now follows the path the add's return takes, through fall-through and unconditional gotos, and
    fails at a conditional branch, switch, return, throw or handler start on it, so whatever can lose the wake-up still
    fails it: C-M33, C-M34 and C-M37, run again on the new shape, are killed by S4.
  - `MacFontReleaseTest.aTaskTheQueueRefusesGetsNoReferenceToTheFont` (macOS, child JVM, `release.cascadeListRefused`):
    a null task, which `ConcurrentLinkedQueue.add` refuses with a `NullPointerException`, stands in for an add that
    cannot allocate its node; the hand-off with Helvetica leaves the font's retain count where it was and rethrows the
    queue's exception. The child holds a second reference of its own, so a release too many shows as -1 and frees
    nothing. `MacFontBindingStructureTest.aTaskOwnsItsReferenceToTheFontOnlyOnceItIsQueued` (any OS): `CFRetain`
    before the add and nowhere else in the nest; a handler that catches everything the add throws releases with no
    condition before the release and then throws, never returning; the helper's only release is in that handler; and
    no handler covers the unpark.
  - No run-time test makes the add run out of memory or the unpark throw.
- **F5. A face its file lacked was never looked up again** (medium). US-111's `CTFactory` kept a set of the
  lower-cased names whose lookup found no face, for the life of the process, and `mayFindFace` said no for such a name
  for good, so a font file installed, updated or replaced so that it now holds the face was never read for it again. The
  regression was US-111's own.
  - Now `CTFactory` maps each lower-cased name and the path the run font reports (none where it reports no file) to the
    version of the file the lookup read, the `fileKey()`, `lastModifiedTime()` and `size()` of `Files.readAttributes`,
    since a rename over the file can keep its time and size. One record per name and path, which a new version of that
    file replaces, so the map grows with the pairs, never with their versions; the second round kept one record per
    name, which R2 replaced. `mayFindFace(name, file)` says no only while a stat of that file gives the version the miss
    was read in; another path, no path where one was read, another version, or a stat that fails (the file gone or
    unreadable) is looked up again. A font that reports no file is remembered with no path and no version for the life
    of the process, as before, with its comment. A file whose read throws is not remembered, as since item 10.
    `createFontFile` takes the version before `findFontIndex` reads the file, and remembers the miss only where a stat
    after the read gives the same version.
  - `CTGlyphLayout.getFontSlot` copies the URL of the run's own font, `OS.CTFontCopyURLAttribute(actualFont)`, and
    hands it and that borrowed font to `addFallbackFont(factory, fr, fontName, file, runFont)`, which asks `mayFindFace`
    before anything else; the run's font is copied only inside the lookup (F6). A remembered miss costs one URL copy and
    one stat per layout of its run, and no copy of the font; neither creates or parses a face. `CTFontCopyURLAttribute`
    leaves the font's ownership as it was: it calls `CTFontCopyAttribute` and `CFURLCopyFileSystemPath`, both under the
    Copy rule, and releases each answer in a `finally` (`OS.java:127-146`). `createFontFile(name, filename, fontRef)`
    takes the file from its caller and still consumes its +1 reference on every path. The handling of a 0 copy moved
    with the copy into `fontFileOfCopy`, which answers null for 0 before it reaches the factory;
    `CoreTextPeerShim.addFallbackFont` builds the old hand-over on it, so `MacFontNullInputChild` and the seam scenario
    of `MacFontReleaseChild` are unchanged. `CTFactory`'s constructor is package-private, so that a shim can subclass
    it.
  - `MacFontFaceLookupTest.aMissHoldsOnlyWhileTheRunFontReportsTheSameFileInTheSameVersion` (macOS, child JVM, scenario
    `lookup.missVersion`, plain assertions): a factory of the child's own (`CTFactoryShim.newFactory`) remembers a miss
    of an invented name in a copy of Helvetica.ttc. The same path unchanged, in any case, is not looked up again; a hard
    link to the same file (another path, the same version), no path, a new time, a new size, a new file key (a copy
    renamed over the file with its time set back) and the file gone and back are. Each change is first checked to
    change only what it is meant to, and after each a new lookup is remembered again. A lookup during whose read the
    factory's read hook sets the file's time is not remembered (`CTFactoryShim.remembersAMiss`), nor a file of 64 zero
    bytes, whose read throws; a font that reports no file is remembered, reads no file, and another font of that name
    that reports one is looked up.
  - `aFaceItsFileLackedIsFoundOnceTheFileHoldsIt` (macOS, verdict, `lookup.changedFile`): Ahem, made from a file of the
    child's own that then holds Helvetica.ttc, laid out twice in a System font (one lookup, no face), then Ahem copied
    back over the file, a new version: the next layout looks it up, finds it and adds it, one lookup of each printed.
    `aFaceItsFileLackedIsLookedUpInAnotherFileOfItsName` (macOS, verdict, `lookup.movedFile`): the same two layouts,
    then a second Ahem made from a copy in another directory, which the same System font looks up and adds. Both are
    `PROVED` here; their preconditions are those of the swapped face of `MacFontReleaseTest`, and the files CoreText
    reports for the copied-back and the second font.
  - `MacFontBindingStructureTest.layoutCopiesTheRunFontOnlyWhereTheFactoryMayFindItsFace` (any OS) replaces
    `layoutAsksForAFallbackFaceBeforeCopyingTheRunFont`, whose copy moved out of `getFontSlot`: `getFontSlot` copies the
    URL of the font `CFDictionaryGetValue` answered, hands that URL and that font to `addFallbackFont`, and copies no
    font; `mayFindFace` guards `lookUpFallbackFont` in `addFallbackFont`, and the copy and `fontFileOfCopy` in
    `lookUpFallbackFont`; and of `CTGlyphLayout` and `CTFactory` only `lookUpFallbackFont` copies a font.
  - Changed for the new signature, no assertion weakened: `MacFontReleaseChild.faceMemory` asks `mayFindFace` with the
    file CoreText reports for Helvetica, which the copy it looked up in reports too, and `unreadableFaceFile` with the
    child's file; `CoreTextPeerShim.mayFindFace(name, file)`, and its `createFontFile(name, ref)` hands the factory the
    file the reference reports.
- **F6. Lookups of one missing face at once each copied and parsed** (medium). Two threads laying out text that needs
  the same uncached fallback face could both pass `mayFindFace`, both copy the run's font and both parse its file,
  against criterion 4.
  - Now at most one lookup per lower-cased name and reported path is in flight (per name until R2).
    `CTGlyphLayout.lookUpFallbackFont` registers a `CTFactory.FaceLookup`, which makes its key when it is made, with
    `beginLookup` (`ConcurrentHashMap.putIfAbsent`, covered since R1); a caller that finds one in flight returns what
    its `await` answers, and creates and parses nothing. The caller whose lookup it is asks again, now that no other
    lookup of the name in that file can run, whether one that ended after its own checks left the face in the factory's
    map (`getFontResource(name, null, false)`, the question `getSlotForFont` asks) or a miss of that file in that
    version (`mayFindFace`), and only then copies the run's font and calls `createFontFile`. Its `finally` calls
    `endLookup`, which removes the entry and then, in a `finally` of its own, hands the outcome to the waiters: the
    entry goes on every way out, an `Error` included, before any waiter wakes, so a caller that comes afterwards begins
    a lookup of its own.
  - Found: each caller adds the one font file to its own composite with `addSlotFont`; later callers find it through
    `getSlotForFont`, since `createFontFile` stored it. Missing: the miss is remembered before the entry goes, so later
    callers do not look while the file is unchanged. Failed (the read throws, or any exception or error): nothing is
    remembered, the waiters get no slot for this layout, and the next layout looks again; the leader's exception reaches
    `getFontSlot`'s catch and its debug output as before.
  - `FaceLookup.await` loops over `CountDownLatch.await`: it holds no monitor, so a virtual caller unmounts, and an
    interrupt does not end the wait and is set again after it, as in `CascadeListTask.awaitList`. The outcome is written
    before the count-down, which publishes it. No thread and no pool is added. No caller waits for its own lookup:
    nothing the lookup runs lays text out. The lock trace is under "Rests on review".
  - `MacFontFaceLookupTest` (macOS, child JVM `MacFontFaceLookupChild`, plain assertions; the child's factory reads
    font files through a hook that counts the reads and holds the first, and counts the copies handed to
    `createFontFile`). `callersThatNeedAFaceAtOnceShareOneLookupAndItsFontFile` (`lookup.sharedFound`): with the first
    read held, a platform and a virtual caller are seen WAITING in `CTFactory$FaceLookup.await`; the platform one is
    interrupted and seen waiting again with its interrupt clear; once released, the three calls read the file once and
    copy the font once, each caller's own System font holds the leader's very font file, the platform caller's interrupt
    is set again, and no lookup is left in flight. `callersThatNeedAFaceItsFileLacksAtOnceShareOneLookupAndItsMiss`
    (`lookup.sharedMissing`): the same over Helvetica.ttc, every slot -1, the miss remembered, and a fourth caller reads
    and copies nothing. `aLookupThatFailsLeavesNothingBehindAndTheNextCallerLooksAgain` (`lookup.sharedFailed`): the
    held read fails, once through the file deleted under it (`FileNotFoundException`) and once through an
    `OutOfMemoryError` the hook throws; the waiters get -1 and no failure, nothing is remembered, no lookup is left in
    flight, and a fourth caller on a thread of its own ends, reads the file again and adds the face.
    `aCallerThatTakesUpALookupOnceAnotherHasEndedUsesWhatItLeft` (`lookup.lateLeader`): a caller held, through a
    `mayFindFace` hook, right after the factory first said yes, until a second caller's whole call has run, takes up a
    lookup that reads nothing: it adds the second caller's font file, or, where the file lacks the face, finds the miss.
  - `MacFontBindingStructureTest.aLookupOfAFallbackFaceIsEndedOnEveryWayOutAndSharedByTheCallersThatWait` (any OS):
    `beginLookup` before `await`, whose answer `lookUpFallbackFont` returns at once; `endLookup` before each return or
    throw after the lookup's first call and in a handler over it that catches everything; in `endLookup` the removal
    before `finish`, and `finish` on every way out of the removal; `await` calls no `Object.wait`; and `layout`,
    `getFontSlot`, `addFallbackFont`, `lookUpFallbackFont`, `mayFindFace`, `beginLookup`, `endLookup`, `await` and
    `finish` take no monitor.
  - No run-time test makes the removal throw or fails the entry of `endLookup`; the class file pins the first.

An independent review of the whole uncommitted diff then found four more, fixed in a fourth round, R1 to R4 (the third
round, F1, changed no code).
- **R1. A registration that threw could leave a name in flight for good.** `beginLookup` ran before the `try` whose
  `finally` ends the lookup, and `ConcurrentHashMap.putVal` links the node before it counts it, which can allocate
  (`addCount`, `fullAddCount`, `transfer`), so an `OutOfMemoryError` or `StackOverflowError` after the insert left a
  `FaceLookup` in the map that nothing would finish: every later caller of the name would park in `await` for good,
  the FX and render threads included. And `endLookup` made the key inside its `try` (`toLowerCase` allocates), so an
  `OutOfMemoryError` there woke the waiters but left the finished entry, whose outcome every later caller then got.
  - Now a `FaceLookup` makes its key when it is made, before it is registered, and `endLookup` reads it and makes
    nothing. `beginLookup` covers `putIfAbsent` with a handler that catches everything, ends its own lookup (the
    value-conditional removal, safe whether the insert happened or not, and `finish(null)`) and rethrows.
  - `aLookupOfAFallbackFaceIsEndedOnEveryWayOutAndSharedByTheCallersThatWait` (any OS) also requires the handler over
    `putIfAbsent` to call `endLookup` with no condition before it throws, never returning
    (`assertHandlerReleasesUnconditionally`), and `endLookup` to call no `faceKey`, no `FaceKey` and no `String`
    method (`assertNeverCalls`). No run-time test makes `putIfAbsent` or the removal throw.
- **R2. Lookups and misses were keyed by name alone.** Two installed fonts can report one display name from files A
  and B. A caller whose run reports B, which holds the face, waited for a lookup in A, which lacks it, got null and drew
  the missing glyph; and runs that alternated between A and B replaced the one record of the name each time and parsed
  each file again.
  - Now both maps are keyed by the lower-cased name and the path the run font reports, null where it reports none
    (`CTFactory.FaceKey`). Callers with one key share one lookup; a caller of another file runs its own. A new version
    of a file replaces that file's miss, so the miss map grows with the pairs, never with versions. The re-check of the
    factory's map stays by name: a face of that name found in any file is that face.
  - `MacFontFaceLookupTest.facesOfOneNameInTwoFilesAreLookedUpAndRememberedApart` (macOS, child JVM,
    `lookup.twoFiles`, plain assertions): with a lookup of Ahem in a copy of Helvetica.ttc held in its read, a caller
    whose file is a copy of Ahem ends while it is held, having read its own file (2 reads) and added the face; released,
    the first has no slot, and only its file's miss is remembered. Then a factory of its own lays a System font out in
    3 rounds over 2 files, two copies of Helvetica.ttc, 6 layouts in all: 2 reads, every slot -1.
    `CTFactoryShim.remembersAMiss` takes the file.
- **R3. The order that publishes a lookup's outcome was not pinned.** A waiter reads `FaceLookup.font` only because
  `finish` stores it before `done.countDown()`; swapped, the race window is too small for a run-time test.
  - `FontCallOrder.assertWrittenBefore`, new: every store of a field comes before a method's first call of a call.
    The check above requires it of `finish`, `FaceLookup.font` and `CountDownLatch.countDown`.
  - The confirming review of the fourth round found the pin weaker than its claim: it read code order alone, so a
    store that a backward branch carried past the call passed, and nothing kept the field from being stored elsewhere.
    Since the fifth round `assertWrittenBefore` also requires the method to be straight-line code, with no branch,
    switch or exception handler at all, so that code order is the order of execution; and a new helper,
    `assertFieldStoredOnlyIn`, requires that `FaceLookup.font` is stored in `finish` and nowhere else in
    `CTFactory$FaceLookup`, constructor included: no initialiser or constructor stores it today.
- **R4. Statements of the story and the README row that the code no longer bore out**: criterion 4's summary still
  described the thread-less fallback F3 deleted; "Remains for the owner" still named the commit of the further fixes;
  "The `MacFontBindings` pins" opened "None moved"; the README row kept every pin unchanged. Corrected, with the other
  statements the four rounds made untrue (commit state, the gate's runs, deviation 8, 30 and the site 12 entries of
  "Rests on review").

**Tests, helpers and shims.** F2 to F4: 6 more tests in `javafx.graphics`: `MacFontBindingStructureTest` 27 to 30 (any
OS; 3 new, and S4, S5, S8 and the keep check changed, two of them renamed) and `MacFontReleaseTest` 23 to 26 (3 new,
macOS, child JVMs). `MacFontReleaseChild` has the three scenarios and a `TaskCall`. `FontCallOrder` (1,782 to 1,914
lines) has four new helpers, `assertNoHandlerCovers`, `assertNeverThrowsFrom`, `assertNeverReturns` and
`assertStoredOnlyOnceCallReturns`; `assertCallFollowsUnconditionally` reads the path (F4), and
`assertHandlerReleasesUnconditionally` also requires the handler to throw and never return, which its other user, the
check of `CTGlyph.createContext`, still passes. `MacFontFinderShim` hands production's `queueCascadeListTask` a NULL
font in `cascadeListHandOff`, and gains `queueCascadeListTask(task, font)`, `cascadeListTask` and `awaitList`.

F5 and F6: 8 more tests: `MacFontBindingStructureTest` 30 to 31 (any OS; one new, and the site 12 check replaced) and
the new `MacFontFaceLookupTest` 7 (macOS, child JVMs of the new `MacFontFaceLookupChild`, 6 scenarios). The new shim
`CTFactoryShim` makes a factory of a test's own, a subclass of `CTFactory`, whose `findFontIndex` and `mayFindFace`
go through a test's hooks (`FaceRead`, `FaceQuery`) and which counts the copies handed to `createFontFile`; it reaches
`addFallbackFont`, `createFontFile` and `mayFindFace` with that factory, and reads by reflection whether a miss is
remembered and how many lookups are in flight. `MacFontReleaseChild` lends the new child its swapped-face helpers,
now package-private, and `MacFontReleaseTest` its `swappedFacePreconditions`. `FontCallOrder` is unchanged.
`MacFontBindings` moves one pin (see "The `MacFontBindings` pins").

R1 to R4: 1 more test, `MacFontFaceLookupTest` 7 to 8 (R2, scenario `lookup.twoFiles`), and three more requirements in
`aLookupOfAFallbackFaceIsEndedOnEveryWayOutAndSharedByTheCallersThatWait` (R1, R3). `FontCallOrder` has one new
helper, `assertWrittenBefore`; `CTFactoryShim.remembersAMiss` takes the file and reads the key record's components by
reflection. The fifth round added no test: `assertWrittenBefore` reads straight-line code only, and a new helper,
`assertFieldStoredOnlyIn`, is one more requirement of the same check (R3).

**Measured** on this Mac (macOS 27.2.1 arm64, JDK 27.0.1.0.2, Maven 3.10.0), on the final tree of each round:

| run | the further fixes | the Copilot review, F2 to F4 | F2 to F6 | R1 to R4 |
| --- | --- | --- | --- | --- |
| font package, no flag (exit 0) | 548 / 0 / 0 / 156 | 554 / 0 / 0 / 156 | 562 / 0 / 0 / 156 | 563 / 0 / 0 / 156 |
| font package, `-Djfx.parity.require=true` (exit 1) | 548 / 5 / 0 / 151 | 554 / 5 / 0 / 151 | 562 / 5 / 0 / 151 | 563 / 5 / 0 / 151 |
| the gate of "Verification", no flag (exit 0) | not run | 664 / 0 / 0 / 8 | 672 / 0 / 0 / 8 | 673 / 0 / 0 / 8 |
| the gate, `-Djfx.parity.require=true` (exit 1) | not run | 664 / 8 / 0 / 0 | 672 / 8 / 0 / 0 | 673 / 8 / 0 / 0 |
| module, `javafx.graphics` (exit 0) | 26,872 / 0 / 0 / 858 | not run | 26,886 / 0 / 0 / 858 | 26,887 / 0 / 0 / 858 |

- R1 to R4: one more test in each run, the new one, `facesOfOneNameInTwoFilesAreLookedUpAndRememberedApart`, which
  the font package, the gate (`Mac*Test`) and the module all select; the same 5 and 8 classes fail with the flag, at
  their machine gates, and the twelve verdict lines are `PROVED` in both font-package runs. The module run took 01:15
  min, `javafx-graphics` 53.6 s of it. Its console lists 639 test classes, 606 top-level and 33 `@Nested`, whose
  times add up to 34.8 s and whose tests to 26,887; the 606 `TEST-*.xml` reports, one per top-level class, add up to
  26,138 tests and 34.4 s, because surefire 3.5.4 writes no report of its own for a `@Nested` class and the 749 tests
  of the 33 are on the console only. Of the 537 test sources that declare a test, the 4 with no console line are the
  abstract bases of `test.com.sun.javafx.test` (`CssMethodsTestBase`, `ObjectMethodsTestBase`,
  `OnInvalidateMethodsTestBase`, `PropertiesTestBase`), whose tests their subclasses run, so every test class ran
  (`round4/surefire-check.txt`).
- The fifth round changed no production file and added no test: the font package ran 563 / 0 / 0 / 156 and the gate
  673 / 0 / 0 / 8 and, with the flag, 673 / 8 / 0 / 0, the same 8 classes, as in the R1 to R4 column; twelve verdict
  lines `PROVED`.
- The third round (F1) ran on the same code as F2 to F6, unchanged: the module run of "Verification" with no
  filter, `mvn -pl modules/javafx.graphics -am test` (01:16 min; `javafx-jslc` 437 / 0 / 0 / 0 and `javafx-base`
  5,510 / 0 / 0 / 22 besides), 14 more tests than with the further fixes, the 14 new ones, and its twelve verdict lines
  `PROVED`; then the gate with the flag, 672 / 8 / 0 / 0, the same 8 classes, each at its machine gate.
- With the flag the font package fails exactly the five `Mac*GoldenTest` comparisons at their machine gate, and the
  gate those five and `MacAccessibleNativeTest`, `MacClipboardNativeTest` and `MacPixelsNativeTest`, the same 8
  classes as before; without it they skip. The ten verdict lines are `PROVED` in both font-package runs of F2 to F4,
  and the twelve of F2 to F6 and of R1 to R4 in both of theirs.
- F2 to F6: the 8 more tests are the new ones. The first font-package run, before `MacFontBindings` moved its pin,
  failed the five golden classes at their binding witness without the flag (562 / 5 / 0 / 151, "a call site moved to
  another binding"); the runs of the table are after the move. No golden file changed (criterion 7); the corpora were
  not dumped again: F5 and F6 change which caller looks a face up and whether a miss holds once its file changes, and
  no dump changes a file during a run. R1 and R3 change nothing a single-threaded run sees; R2 looks a face up again
  for each further file that run fonts of its name report, which can move a row only where one fallback name comes
  from two files and the first lacks the face. That no corpus row does is not checked: the corpora were not dumped
  again, and the goldens are no oracle on this Mac.
- No `hs_err_pid*` file; `git diff --check` clean. CI was not run on any round, nor the module run on the first.
  Since then CI run 45456403357 ran their commit, `2c9003b909`, on all six jobs ("Windows and Linux" below).

**Mutants.** One hand edit of `MacFontFinder` per row, the classes S and R run (S alone where the row says S only),
the edit undone by hand and the file checked against the round's checksum after each undo; all 25 killed, with
failures only, no error. Cluster C's C-M7, C-M20, C-M33, C-M34 and C-M37 are its rows made on the new shape.

| row | fix | the only edit | killed by |
| --- | --- | --- | --- |
| F2-M1 | F2 | the `try` and catch around `releaseFont()` deleted | S8, S keep; R release |
| F2-M2 | F2 | the release's failure stored even where the read failed | R release |
| F2-M3 | F2 | `addSuppressed` replaced by another call | S keep; R release |
| F2-M4 | F2 | the catch around `addSuppressed` deleted | S keep only |
| F2-M5 | F2 | the release's catch rethrows what it kept | S8, S keep; R release |
| F2-M6 | F2 | `done = true`, the unpark and a return moved into the `try` | S8; R release |
| F2-W1 | F2 | the loop runs `task.run()` bare | S outlives; R throws |
| F2-W2 | F2 | the loop catches `RuntimeException` only | S outlives; R throws |
| F2-W3 | F2 | the loop's catch returns | S outlives; R throws |
| C-M20 | F2 | `releaseFont` without its pool | S8 (S only) |
| C-M7 | F2 | `done = true` before the release | S8 (S only) |
| F3-M1 | F3 | the unpooled fallback restored | S5, S start |
| F3-M2 | F3 | the fallback answers the empty list | S start |
| F3-M3 | F3 | `cascadeListWorker` stored before `start` | S start |
| F3-M4 | F3 | `cascadeListWorker` stored in a `finally` around `start` | S start |
| F3-M5 | F3 | an `OutOfMemoryError` of `start` caught, then the store | S start (S only) |
| F4-M1 | F4 | the handler around the add deleted | S owner; R refused |
| F4-M2 | F4 | the handler rethrows without releasing | S owner; R refused |
| F4-M3 | F4 | the handler releases twice | R refused only |
| F4-M4 | F4 | the handler releases and does not rethrow | S owner; R refused |
| F4-M5 | F4 | the unpark moved into the `try` | S owner only |
| F4-M6 | F4 | the retain moved back into the task's constructor | S owner; R refused, R release |
| C-M33 | F4 | the unpark only `if (size() == 1)` | S4 (S only) |
| C-M34 | F4 | the unpark in a `switch` | S4 (S only) |
| C-M37 | F4 | a return before the unpark for a `CascadeListTask` | S4 (S only) |

- **S keep**, `aTaskKeepsWhatItsReadAndItsReleaseThrowAndNeverThrowsIt`: F2-M1 "no handler that catches everything
  covers its call of … releaseFont at instruction 11"; F2-M3 "it never calls java/lang/Throwable addSuppressed";
  F2-M4 "no handler that catches everything covers its call at instruction 28"; F2-M5 "the handler of
  java/lang/Throwable from instruction 10 to 12, handled at 13, … never returns and throws at instruction 32".
- **S8**: F2-M1 the same of the unpark; F2-M5 and F2-M6 "the handler … reaches the throw at instruction 32" or "the
  return at instruction 37 with no call of … unpark"; C-M20 "releaseFont never calls … objc_autoreleasePoolPush";
  C-M7 "stores it at instruction 12, before that call at instruction 14".
- **R release**, `aTaskWhoseReleaseThrowsStillWakesItsCallerWithTheFailure`: F2-M1, F2-M5 and F2-M6 "a caller whose
  task's release threw was not woken ==> expected: <true> but was: <false>"; F2-M2 "where the read threw, the caller
  gets the read's failure, not the release's ==> expected: <read> but was: <release>"; F2-M3 "the release's failure
  is attached to the read's ==> expected: <release> but was: <>"; F4-M6 "what the caller of a task whose release
  threw gets ==> expected: <java.lang.IllegalArgumentException> but was: <java.lang.IllegalStateException>", the
  constructor's retain of the heap segment throwing first.
- **S outlives**, `theCascadeListThreadOutlivesWhatATaskThrows`: F2-W1 and F2-W2 "no handler that catches everything
  covers its call at instruction 11"; F2-W3 "must never return … it returns at instruction 14". **R throws**,
  `aTaskThatThrowsDoesNotEndTheCascadeListThread`: "the cascade-list thread did not run the two throwing tasks",
  since the first task's throw ended it.
- **S5**, `onlyThePooledRegionReadsTheCascadeList`: F3-M1 "… cascadeListOnAPlatformThread calls it at instructions
  [6]". **S start**, `aVirtualCallerGetsWhatStartingTheThreadThrowsAndTheNextOneTriesAgain`: F3-M1 and F3-M2 "the
  handler of java/lang/OutOfMemoryError from instruction 0 to 2, handled at 3, covers its call at instruction 0";
  F3-M3 and F3-M4 "stores it at instruction 22" (28) ", which no return of that call reaches"; F3-M5 "instruction 29,
  which a handler around that call reaches".
- **S owner**, `aTaskOwnsItsReferenceToTheFontOnlyOnceItIsQueued`: F4-M1, F4-M2 and F4-M4 "no handler that catches
  everything covers its call of … add at instruction 10 and calls … release unconditionally before it throws,
  reaching no return"; F4-M5 "the handler of java/lang/Throwable from instruction 8 to 14, handled at 15, covers its
  call at instruction 13"; F4-M6 "never calls … CFRetain, which must come before its call of … add". **R refused**,
  `aTaskTheQueueRefusesGetsNoReferenceToTheFont`: F4-M1 and F4-M2 "a refused task's reference to the font is released
  once ==> expected: <0> but was: <1>"; F4-M3 and F4-M6 the same with -1; F4-M4 "what the queue threw ==> expected:
  <java.lang.NullPointerException> but was: <#null>".
- **S4**: C-M33 "if_icmpne at instruction 21 comes on the way from its call of … add at instruction 10 to a call of
  … unpark"; C-M34 "lookupswitch at instruction 20 …"; C-M37 "ifeq at instruction 20 …".

F4-M3's first run ended the child with exit 134 in the
scenario's own last release, which then freed the font a second time; the scenario now keeps one reference to the
end, and the row was run again. After the last row S and R ran 30 / 0 / 0 / 0 and 26 / 0 / 0 / 0 on the restored
tree. The round has had no independent review yet.

**Mutants of F5 and F6.** One hand edit of `CTFactory` or `CTGlyphLayout` per row, with the Edit tool, the classes S, L
(`MacFontFaceLookupTest`) and R run (S and L alone where the row says so), the edit undone by hand and both files
checked against the round's checksums after each undo; all 27 killed, with failures only, no error. M5-11a's undo was
checked together with M5-11b's, whose edit touched other lines and was made before the check; M5-6 to M5-8 each
replaced the same line in turn and were undone once, then checked. S is the two new checks: "S copy" is
`layoutCopiesTheRunFontOnlyWhereTheFactoryMayFindItsFace`, "S lookup"
`aLookupOfAFallbackFaceIsEndedOnEveryWayOutAndSharedByTheCallersThatWait`.

| row | fix | the only edit | killed by |
| --- | --- | --- | --- |
| M5-1 | F5 | the version check deleted: `mayFindFace` says no for the same path | L miss; L changed |
| M5-2 | F5 | the path ignored: no comparison of the remembered file | L miss (the child's own `NullPointerException`) |
| M5-2b | F5 | the path compared only for null: keyed by name and version (S, L only) | L miss |
| M5-3 | F5 | the version taken after the read | L miss |
| M5-4 | F5 | the comparison after the read deleted (S, L only) | L miss only |
| M5-5 | F5 | a read that throws taken as no face, and remembered | L miss, L failed; R unreadable |
| M5-6 | F5 | the file key left out of the version (S, L only) | L miss |
| M5-7 | F5 | the time left out of the version (S, L only) | L miss |
| M5-8 | F5 | the size left out of the version (S, L only) | L miss |
| M5-9 | F5 | a font that reports no file not remembered | L miss; R no file |
| M5-10 | F5 | a file whose stat fails taken as unchanged | L miss |
| M5-11a | F5 | the run's font copied, and released, in `getFontSlot` before the miss check | S copy only |
| M5-11b | F5 | the copy in `lookUpFallbackFont` made before the re-check, released where unused | S copy only |
| M5-12 | F5 | `getFontSlot` hands the lookup no file | S copy; L changed, L moved; R face, R unreadable |
| M5-13 | F5 | the miss check of `addFallbackFont` deleted | S copy (L late, through its hook) |
| M6-1 | F6 | no lookup registered: each caller runs its own (S, L only) | S lookup; L found, L missing, L failed |
| M6-2 | F6 | the entry ended only on an `Exception`, not on an `Error` (S, L only) | S lookup; L failed |
| M6-3 | F6 | a waiter waits, then runs a lookup of its own (S, L only) | S lookup; L failed |
| M6-3b | F6 | a waiter waits, then copies and parses (S, L only) | S lookup, S copy; L found, missing, failed |
| M6-4 | F6 | the interrupt cleared, not set again, after the wait (S, L only) | L found, L missing, L failed |
| M6-5 | F6 | an interrupt ends the wait with no font (S, L only) | L found, L missing, L failed |
| M6-6 | F6 | the waiters woken before the entry is removed (S, L only) | S lookup only |
| M6-7 | F6 | the removal and the wake-up in a row, no `finally` (S, L only) | S lookup only |
| M6-8a | F6 | the re-check of the factory's map dropped (S, L only) | L late |
| M6-8b | F6 | the re-check of the remembered miss dropped (S, L only) | S copy; L late |
| M6-9 | F6 | `await` `synchronized` (S, L only) | S lookup; L found, L missing, L failed |
| M6-10 | F6 | the waiters handed no font (S, L only) | L found only |

- **L miss**, `aMissHoldsOnlyWhileTheRunFontReportsTheSameFileInTheSameVersion`: M5-1 "the file is looked up again once
  its time changed ==> expected: <true> but was: <false>", M5-6 the same of its key, M5-7 of its time, M5-8 of its size;
  M5-2 "child JVM 'lookup.missVersion' exited with 1", a `NullPointerException` in `mayFindFace` when a no-file miss is
  asked with a file; M5-2b "a run font that reports another file is looked up ==> expected: <true> but was: <false>",
  the hard link; M5-3 and M5-4 "a file that changed while it was read ==> expected: <false> but was: <true>"; M5-5 "a
  file whose read threw is not remembered ==> expected: <false> but was: <true>"; M5-9 "a font that reports no file is
  remembered by its name ==> expected: <false> but was: <true>"; M5-10 "a file that is gone is looked up again ==>
  expected: <true> but was: <false>". M5-4 is the one row whose answers `mayFindFace` gives alike: a miss stored with
  the version before the read no longer holds once the file changed; the record itself, read through the shim, kills it.
- **L changed**, `aFaceItsFileLackedIsFoundOnceTheFileHoldsIt`, and **L moved**,
  `aFaceItsFileLackedIsLookedUpInAnotherFileOfItsName`: `REFUTED`, "found = -1, expected a slot of 1 or more: the face
  is looked up again and added; … the child printed 0 lookups of Ahem in …/face.ttf that found it" (M5-1, M5-12).
- **L found**, `callersThatNeedAFaceAtOnceShareOneLookupAndItsFontFile`, **L missing**,
  `callersThatNeedAFaceItsFileLacksAtOnceShareOneLookupAndItsMiss`, and **L failed**,
  `aLookupThatFailsLeavesNothingBehindAndTheNextCallerLooksAgain`: M6-1 and M6-9 "a caller that came meanwhile waits
  for it ==> expected: <true> but was: <false>" (under M6-1 the found case read 2 times and the missing one 3; under
  M6-9 the second waiter is BLOCKED on the monitor, and the failed child ran past its 60 s); M6-2 "error.every caller's
  call ended ==> expected: <true> but was: <false>", with one lookup left in flight and the next caller never ending;
  M6-3 "unreadable.the callers read the file once ==> expected: <1> but was: <3>" (found and missing pass: the waiter's
  own lookup finds what the first left, through the re-check), M6-3b the same in all three; M6-4 "the interrupt is set
  again ==> expected: <true> but was: <false>"; M6-5 "an interrupt does not end the wait ==> expected: <true> but was:
  <false>"; M6-10 "platform has the face as a slot: -1"; M5-5 "unreadable. what the lookup threw ==> expected:
  <java.io.FileNotFoundException> but was: <#null>".
- **L late**, `aCallerThatTakesUpALookupOnceAnotherHasEndedUsesWhatItLeft`: M6-8a "found. the file is read once ==>
  expected: <1> but was: <2>", M6-8b "missing. the file is read once ==> expected: <1> but was: <2>"; under M5-13 the
  late caller's hook comes to sit inside the lookup it runs, so the leader waits for it: "found. the leader's call ended
  ==> expected: <true> but was: <false>", which is the hook's shape, not a pin of M5-13.
- **S copy**: M5-11a "getFontSlot must call none of … OS CTFontCreateCopyWithAttributes … but it calls … at
  instructions [76]"; M5-11b, M6-3b and M6-8b "lookUpFallbackFont must call … CTFontCreateCopyWithAttributes and …
  fontFileOfCopy only where … mayFindFace answers true"; M5-12 "getFontSlot must hand each of its calls of …
  addFallbackFont, as argument 3, what its first call of … CTFontCopyURLAttribute answers"; M5-13 "addFallbackFont must
  call … lookUpFallbackFont only where … mayFindFace answers true … but it never calls … mayFindFace".
- **S lookup**: M6-1 "lookUpFallbackFont never calls … beginLookup, which must come before its call of …
  FaceLookup await"; M6-2 "lookUpFallbackFont must call … endLookup before each return or throw after its first call of
  … getFontResource, and in each handler that covers that call, one of which catches everything"; M6-3 and M6-3b "must
  return what its first call of … FaceLookup await answers … but instruction 13 … is pop, not a return"; M6-6
  "endLookup calls … FaceLookup finish before java/util/concurrent/ConcurrentHashMap remove"; M6-7 "endLookup must call
  … finish before each return or throw after its first call of … ConcurrentHashMap remove, and in each handler that
  covers that call, one of which catches everything"; M6-9 "FaceLookup.await may take no monitor … but it is
  synchronized".
- **R**: M5-5 `aFaceLookupThatCannotReadItsFileIsMadeAgainAtTheNextLayout` `REFUTED`, "the child printed 1 lookups of
  Ahem that found no face, where the file either could not be read or had it"; M5-9
  `aFallbackFontThatReportsNoFileIsLookedUpOnce` `REFUTED`, "Ahem was looked up with filename=null 5 times over 5
  layouts"; M5-12 ("R face") `aFallbackFaceItsFontFileLacksIsLookedUpOnce` `REFUTED`, "Ahem was looked up with
  filename=…/face.ttf 0 times over 5 layouts", and the unreadable check `REFUTED`, "answer.readable = -1".

Structure only, where no run-time test sees the change: M5-11a and M5-11b (a copy made and released moves no retain
count, deviation 20), M5-13 (the lookup's own re-check answers the same), M6-6 (a caller that comes between the wake-up
and the removal joins an ended lookup and gets its outcome at once) and M6-7 (the removal does not throw). After the
last row S, L and R ran 31 / 0 / 0 / 0, 7 / 0 / 0 / 0 and 26 / 0 / 0 / 0 in the font-package runs on the restored tree.
The second round has had no independent review yet.

**Mutants of R1 to R3.** Made, undone and checked as above, against the fourth round's checksums; the classes S and L
run (R too where the row says so). All 13 killed. The first six rows are new; the other seven are rows of F5 and F6 run
again on the code R1 and R2 reshaped (`mayFindFace`, the no-file miss, `beginLookup`, `endLookup`). M6-1-r4's undo was
checked together with M6-2-r4's, made before the check on other lines of the same method, and R4-1 and R4-2, M6-6-r4
and M6-7-r4, M5-1-r4 and M5-10-r4 each edited one region in turn and were undone once, then checked.

| row | fix | the only edit | killed by |
| --- | --- | --- | --- |
| R4-1 | R1 | no handler around `putIfAbsent` in `beginLookup` | S lookup only |
| R4-2 | R1 | the handler rethrows without ending the lookup | S lookup only |
| R4-3 | R1 | `endLookup` makes the key again (`faceKey`) | S lookup only |
| R4-4 | R2 | the lookup's key made without the file | L two |
| R4-5 | R2 | the miss read and stored under the name alone (`""` for any file) | L two, L miss, L missing |
| R4-6 | R3 | `finish` counts down before it stores the outcome | S lookup only |
| M5-1-r4 | F5 | the version check deleted | L miss; L changed |
| M5-9-r4 | F5 | a font that reports no file not remembered (S, L, R) | L miss; R no file |
| M5-10-r4 | F5 | a failed stat taken as unchanged | L miss |
| M6-1-r4 | F6 | no lookup registered | S lookup; L found, L missing, L failed |
| M6-2-r4 | F6 | the lookup ended only on an `Exception` | S lookup; L failed |
| M6-6-r4 | F6 | the waiters woken before the removal | S lookup only |
| M6-7-r4 | F6 | the removal and the wake-up in a row, no `finally` | S lookup only |

- **S lookup**: R4-1 and R4-2 "CTFactory.beginLookup must call … CTFactory endLookup, with no condition before it, in a
  handler that catches everything its first call of java/util/concurrent/ConcurrentHashMap putIfAbsent throws, before
  the handler throws, never returning"; R4-3 "CTFactory.endLookup must call none of … CTFactory faceKey, … FaceKey,
  java/lang/String … but it calls … CTFactory faceKey at instructions [8]"; R4-6 "CTFactory$FaceLookup.finish may
  store … font only before its first call of java/util/concurrent/CountDownLatch countDown … but it stores it at
  instruction 5, after that call at instruction 2"; the reruns the messages of their rows above.
- **L two**, `facesOfOneNameInTwoFilesAreLookedUpAndRememberedApart`: R4-4 "a caller of another file waits for nothing
  ==> expected: <true> but was: <false>"; R4-5 "the file that lacks the face is remembered ==> expected: <true> but
  was: <false>", with `alternating.reads` 6 where 2 is right, the alternation the review described. R4-5 also fails
  "a run font that reports another file is looked up" (L miss) and "the miss is remembered" (L missing).
- The reruns gave the messages of their rows above: M5-1-r4 "…once its time changed", M5-9-r4 "filename=null 5
  times" and "remembered by its name", M5-10-r4 "a file that is gone is looked up again", M6-1-r4 "a caller that came
  meanwhile waits for it", M6-2-r4 "error.every caller's call ended".

Structure only: R4-1 to R4-3 (no test makes `putIfAbsent` or the removal throw) and R4-6 (the race window). A
confirming review passed the fourth round's code and left the five items of the fifth round.

**Mutants of the fifth round.** Made, undone and checked as above, against the fifth round's checksums (the same
production files as the fourth round's final ones); the class S run. All 4 killed, all structure only: no run-time test
sees the order in which one thread stores and counts down.

| row | fix | the only edit | killed by |
| --- | --- | --- | --- |
| R5-1 | R3 | `finish` loops twice, storing only on the second pass, after the first pass's count-down | S lookup only |
| R5-2 | R3 | `await` stores `font` (to itself) after the wait | S lookup only |
| R5-3 | R3 | the constructor stores `font` (null) | S lookup only |
| R4-6-r5 | R3 | R4-6 again: `finish` counts down before it stores | S lookup only |

- R5-1: "CTFactory$FaceLookup.finish may store … font only before its first call of … countDown, in straight-line code,
  so that a waiter the latch lets through reads the outcome; but if_icmpge at instruction 4 can carry a store past the
  call". Its `javap` (`round5/mutants/R5-1.javap.txt`) has the store at instruction 14 before the count-down at 21 in
  code order, with `goto 2` carrying the second pass's store past the first count-down, so the code-order rule of the
  fourth round alone passed it.
- R5-2 and R5-3: "CTFactory$FaceLookup.font may only be stored in finish, so that a waiter the latch lets through
  reads the outcome; but await stores it at instruction 8" and "… but <init> stores it at instruction 15".
- R4-6-r5: "… but it stores it at instruction 5, after that call at instruction 2".

### Windows and Linux (2026-11-24)
Item 8 of the further fixes changed code that Windows and Linux run too: 8b, `PrismCompositeFontResource`'s
`getSlotForFont` and `addSlotFont` answer -1 for no slot through `slotAfterThePrimary`, where `getSlotForFont` answered
slot 0; 8c, `FallbackResource.addNativeFallback` answers `ns`, where it answered `ns + 1`; and both `addNativeFallback`
methods are `synchronized` and look the font up again under the lock. `directwrite-metrics-golden.txt` still held the
slots of `ns + 1`, and Copilot's F1, the one thread of six on PR 71 left unresolved (on `FallbackResource.java`, comment
4673071231), asked: "Include a reviewed Windows recapture and Windows/Linux validation, and update the PR's macOS-only
scope or split out the shared slot fix." On Windows and Linux only the class-file checks and the any-OS tests of the
mapping covered 8b and 8c ("on macOS the slot tests catch a wrong index, on Linux and Windows nothing does", "Rests on
review"), and no test showed what 8b changes in a layout. This round ran on `2c9003b909` on Windows 10 Pro
10.0.19045.6466, the golden's capture machine, and in WSL Ubuntu; nothing of it ran on macOS, and it is uncommitted.

**What changed.** Five test files, no production file, pom, native file, shim, CI file or other golden
(`git diff --quiet HEAD -- modules/javafx.graphics/src/main` holds). `git diff --numstat`, insertions and deletions:
- `DirectWriteMetricsGoldenTest.java`, 24 and 6: `header()` and the class Javadoc say why the golden was captured again
  and which 609 rows moved (R1 below), and the Javadoc says the golden records, "as first captured from the JNI build",
  what crosses the native boundary (R5). The method name `directWriteMetricsMatchTheJniGolden` is unchanged.
- `directwrite-metrics-golden.txt`, 322 and 313: captured again (below); 8,409 lines, sha256 `b16182fd...`.
- `PrismFontFactoryTest.java`, 58 and 5: two macOS tests enabled on Windows and Linux; the new
  `aCompositeWhoseFallbackListIsFullHasNoSlotForAnotherFont`; and the package-private `fill` and `record Refusal`, which
  it and `DirectWriteNoSlotLayoutTest` share.
- `CompositeFontLocksTest.java`, 58 and 27: eleven macOS tests enabled, ten on Windows and Linux and one on Linux only;
  the fixture of one chosen by OS; `SlotFont` package-private, for the shared `fill`.
- `DirectWriteNoSlotLayoutTest.java`, new, 139 lines, `@EnabledOnOs(OS.WINDOWS)`.

**The re-capture.** On this machine, with the header written first, as the round's decisions D1 and D2 had it.
- The machine gate: the sha256 of `C:/WINDOWS/System32/dwrite.dll`, `arial.ttf`, `segoeui.ttf`, `times.ttf` and
  `consola.ttf` equal the golden's `machine.*` rows, and `ver` gives 10.0.19045.6466, as `info.windows.version` does
  (checked before the round began); under `-Djfx.parity.require=true` the comparison passed the gate and ran.
- Before: the comparison of the golden as committed failed with "609 difference(s) against the golden captured from the
  JNI build (first 60 shown):", in the Windows gate of `2c9003b909` and in the implementer's run (`impl/1b-parity.log`).
- `header()` was edited before the capture, so that the file a capture writes is the committed file byte for byte. Its
  first 3 lines became 11, and since R1 12; the `Capture`, `Regenerate` and `Keys` lines are unchanged.
- The capture, `-Djfx.font.golden.capture=true -Djfx.font.golden.regenerate=true` in place of the flag (2 / 0 / 0 / 1,
  the abort): 8,408 lines where there were 8,400, sha256 `3e87a1f3...`, `git diff --numstat` 321 and 313.
- The proof, inline perl over the old and the new file (`impl/capture-diff.txt`): 8,378 key=value rows in each; the 8
  `machine.*` rows equal; 300 keys only in the old file and 300 only in the new, each a `glyph.<code>.*` row of a 12 pt
  capture (20 codes on each side); of the other rows, `capture.provenance` changed (the JVM `26+35-2893` to
  `27+35`) and exactly 9 more, the 12 pt `layout.run.5.glyphs` and `run.6.glyphs` of arial, segoeui and
  timesnewroman and `run.4` to `run.6.glyphs` of consolas, in each of which every code is unchanged or 16777216, one
  slot, lower with the same low 24 bits. So exactly the 609 rows of deviation 16 moved.
- The new rows hold the data of the fonts their codes name. All 214 glyph codes of the 12 pt captures have a slot byte
  below `slots.count` and no null slot; `slots.count` is 14 for arial, segoeui and timesnewroman (12 Yu Gothic UI
  Regular, 13 Nirmala UI) and 15 for consolas (12 Segoe UI, 13 Yu Gothic UI Regular, 14 Nirmala UI). Consolas' four
  Segoe UI glyphs, 2925, 2932, 2933 and 2945 (60 rows), equal segoeui's own 12 pt rows of the same glyph IDs, which the
  JNI build captured, in all 14 rows beside `.code`, and in `.code` too. The other 16 glyphs agree row for row across
  the four fonts, and the review found each consistent with the JNI build's 24 pt row of the same code (advance and
  bounding box exactly half, the same code, LCD flag, outline class, winding, segments and type count: 177 of 177
  pairs):

  | font | glyph ID | advance at 12 pt | outline segments |
  | --- | --- | --- | --- |
  | Yu Gothic UI Regular | 1954 | 12.0 | 149 |
  | Yu Gothic UI Regular | 3286 | 12.0 | 31 |
  | Yu Gothic UI Regular | 3724 | 12.0 | 58 |
  | Nirmala UI | 407 | 8.607422 | 91 |

- The old codes had drawn Yu Gothic UI's CJK glyphs 3286, 3724 and 1954 with Nirmala UI (old slot 13; consolas' 14),
  Nirmala UI's 407 with the primary (old slot 14, and 15 in consolas, past `slots.count`) and consolas' Segoe UI
  glyphs with Yu Gothic UI (old slot 13), as F1 under "The Copilot review" read them (the review's own perl).
- Two further captures came out byte-identical, sha256 `3e87a1f3...` before and after: the implementer's second
  (`impl/1f-capture2.log`) and the review's own (`review/logs/w-capture.log`).
- The header correction, R1: the review found "the first native fallback a font file's composite adds took the next
  slot" untrue, since every native fallback's first lookup did so (consolas' three all moved). The rework changed that
  sentence in `header()` and the Javadoc and captured again: lines 3 to 9 of the golden became lines 3 to 10, and
  nothing else changed. The 8,378 lines that are not comments are byte-identical before and after (`cmp`), the file has
  8,409 lines, sha256 `b16182fd...`, and `git diff --numstat` is 322 and 313. The rework's second capture and the
  confirming review's capture came out byte-identical (`b16182fd...`).

**The slot tests on Windows and Linux** (D3). Thirteen run-time slot tests that were `@EnabledOnOs(OS.MAC)` only
because they had run nowhere else now run on Windows and Linux, each Javadoc saying so:
- `PrismFontFactoryTest`: `aCompositeHasNoSlotForAFontItsFallbacksCannotHold` and
  `aFontAddedToACompositeIsInTheSlotItIsGiven`.
- `CompositeFontLocksTest`: `twoFontsAddedAtOnceToALogicalFontTakeTwoSlots`,
  `oneFontAddedTwiceAtOnceToALogicalFontTakesOneSlot`, `aLogicalFontAnswersWhatItKnowsWithoutItsLock`, their three
  `FallbackResource` twins, `aFontAddedToAFallbackResourceIsAtTheSlotItIsHandedOutFor`,
  `aFontACompositeAddsIsAtTheSlotItIsHandedOutFor`, `callersOfANewLogicalFontWaitForOneLookUpOfItsFallbacks`,
  `callersOfANewFallbackResourceWaitForOneLookUpOfItsFallbacks` and
  `aNewFallbackResourceLooksUpItsFallbacksOnceForThreeCallers`.
- Kept off Windows, measured: `aNewFallbackResourceLooksUpItsFallbacksOnceForThreeCallers` is
  `@EnabledOnOs({OS.MAC, OS.LINUX})`. Its control failed on Windows with "a look-up of a fallback list reads its
  primary's style once: the count this test relies on ==> expected: <2> but was: <1>" (`impl/run1-win.log`):
  `DWFactory.getFallbacks` makes the list without reading the primary. It passes on Linux.
- The fixture by OS: `aFontACompositeAddsIsAtTheSlotItIsHandedOutFor` takes Helvetica.ttc on macOS, as before, and the
  repository's Ahem (`MacFontGoldens.pinned("ahem")`) on Windows and Linux, chosen with `if (OS.current() == OS.MAC)`.
- What macOS executes: the 12 other bodies are unchanged; in this one the macOS branch holds the old three statements,
  with `composite` declared before the `if` and the Helvetica line wrapped, and macOS runs the OS check besides. Beside
  that branch and the new cap test, macOS executes the statements of `2c9003b909`; that rests on reading the diff (the
  review and both confirmations).
- No slot test skipped at its room check: `CompositeFontLocksTest` 20 / 0 / 0 / 1 on Windows (the one kept off) and
  20 / 0 / 0 / 0 in WSL. An invented name has no font in the process factory on either system: the composite answers
  -1 for "US-111 No Such Font". The slot counts the tests met, from the mutants' messages: `LogicalFont` System 12 on
  Windows and 10 on Linux, a `FallbackResource` over a stub 11 and 9, the composite over Ahem 12 and 10.

**Two new tests.**
- `DirectWriteNoSlotLayoutTest.aRunWhoseFallbackFontGetsNoSlotIsLaidOutWithTheMissingGlyph` (D4, Windows only) shows 8b
  end to end through `DWGlyphLayout`: a run DirectWrite draws with a fallback font the composite has no slot for is laid
  out with glyph code 0, not with that font's glyph IDs in slot 0. Each composite is its own, over Arial's file.
  - The text is `"e\u0301 "` and the CJK run of `DirectWriteMetricsGoldenTest.TEXT`: CJK text alone is no complex run,
    so it is shaped from the composite's glyph mapper and never reaches `DWGlyphLayout` ("the CJK run is laid out by
    DirectWrite ==> expected: <true> but was: <false>", `impl/cjk-alone-win.log`); the combining mark makes it complex.
  - Precondition, a control on a first composite: its CJK glyphs take slot 12, after its 12 slots, and that slot holds
    Yu Gothic UI Regular (code 201329878, slot 12 and glyph 3286), which a later lookup finds at 12. Where DirectWrite
    draws the run with a font the composite already holds, the test skips through `ParityGate.requireOracle`, or fails
    under `-Djfx.parity.require=true`.
  - The subject: a second composite filled through `addSlotFont` until it refuses a font, then laid out: every glyph
    code 0, the run's width above 0 and the slot count unchanged; then the count 0x7F and the refusal -1. A negative
    control (the fill's answer replaced by -1, then restored and checked with `cmp`) fails it: "a glyph code of the CJK
    run in a composite with no slot left for its font ==> expected: <0> but was: <201329878>" (`impl/nc-win.log`).
  - It does not pin `DWGlyphLayout.renderShape`'s `slot >= 0` (line 391) by itself:
    `DWTextRendererObject.textRendererGetGlyphIndices` returns before it copies an ID for `slot < 0` (line 254), so
    either place leaves the codes 0, as its Javadoc says (R2; DW1 survives).
- `PrismFontFactoryTest.aCompositeWhoseFallbackListIsFullHasNoSlotForAnotherFont` (R4; macOS, Windows and Linux): a
  composite over the repository's Ahem, filled by `fill` with fonts the machine does not have until it refuses one,
  answers -1 for that font, has 0x7F slots (the list's 0x7E and the primary's), answers -1 to a lookup of the refused
  font's name and keeps its slot count. Its precondition is its neighbour's room check,
  `slots - 1 < FALLBACK_SLOT_CAP`, through `ParityGate.requireOracle`. It fills a 12-slot composite with 115 fonts on
  Windows and a 10-slot one with 117 on Linux. It has not run on macOS; on CI run 45456403357 its neighbour, with the
  same check over the same composite, skipped on neither macOS 15 runner (`PrismFontFactoryTest` 9 / 0 / 0 / 0).

**Gates.** Counted from the Maven log, run / failures / errors / skipped; every class in the kept tables
[`US-111-windows-per-class.tsv`](US-111-windows-per-class.tsv) and
[`US-111-wsl-per-class.tsv`](US-111-wsl-per-class.tsv).

Windows, `mvn -B -ntp -pl modules/javafx.graphics -am test -Djfx.parity.require=true` in the main tree: the committed
`2c9003b909` before any edit, and the final tree. Master does not run here: a second Windows tree cannot live in the
scratch directory (paths over 260 characters), and nothing is written elsewhere outside the repository; CI compares it.

| module | `2c9003b909` (exit 1, 05:05 min) | final (exit 0, 04:49 min) |
| --- | --- | --- |
| `javafx-jslc` | 437 / 0 / 0 / 3 | 437 / 0 / 0 / 3 |
| `javafx-base` | 5,510 / 0 / 0 / 22 | 5,510 / 0 / 0 / 22 |
| `javafx-graphics` | 26,875 / 1 / 0 / 917 | 26,877 / 0 / 0 / 905 |

- Of 965 and 966 classes four differ: `DirectWriteMetricsGoldenTest` 2 / 1 / 0 / 0 to 2 / 0 / 0 / 0 (the 609
  differences, then none), `PrismFontFactoryTest` 9 / 0 / 0 / 5 to 10 / 0 / 0 / 3, `CompositeFontLocksTest`
  20 / 0 / 0 / 11 to 20 / 0 / 0 / 1, and the new `DirectWriteNoSlotLayoutTest` 1 / 0 / 0 / 0.
- The final gate ran twice, before and after `fill` moved (O1 below), with no per-class difference; the table holds
  the second. No `hs_err_pid*` newer than its start in `modules/javafx.graphics`, and no other `java.exe` at the start
  or the end.

WSL Ubuntu, `mvn -B -ntp -pl modules/javafx.graphics -am test` without the flag, each in a fresh clone in the Linux
home, the final one with the five files carried over and sha256-checked, natives built from source. The flag is left
out because the four Linux font goldens are another machine's and skip at their machine gate here, which the flag
turns into failures.

| module | `abe0943d31` (08:35 min) | `2c9003b909` (09:24 min) | final (06:18 min) |
| --- | --- | --- | --- |
| `javafx-jslc` | 437 / 0 / 0 / 0 | 437 / 0 / 0 / 0 | 437 / 0 / 0 / 0 |
| `javafx-base` | 5,510 / 0 / 0 / 22 | 5,510 / 0 / 0 / 22 | 5,510 / 0 / 0 / 22 |
| `javafx-graphics` | 26,428 / 0 / 0 / 967 | 26,619 / 0 / 0 / 1,034 | 26,621 / 0 / 0 / 1,022 |

- Each exit 0, no `hs_err_pid*` in the clone; the `dpkg` versions of FreeType, Pango, fontconfig, HarfBuzz, glibc and
  the DejaVu fonts and the libc sha256 equal at the start and the end.
- `abe0943d31` to `2c9003b909`: 955 to 965 classes, 15 differ, each a class US-111 adds or changes (191 more run, 67
  more skipped). `2c9003b909` to final: three, `CompositeFontLocksTest` 20 / 0 / 0 / 11 to 20 / 0 / 0 / 0,
  `PrismFontFactoryTest` 9 / 0 / 0 / 5 to 10 / 0 / 0 / 3 and the new `DirectWriteNoSlotLayoutTest` 1 / 0 / 0 / 1
  (Windows only). The final gate ran twice here too, before and after O1, with no per-class difference.

CI, `submit.yml`: run 44961158059 of PR 70's head `a6d6446711`, the baseline, against run 45456403357 of
`2c9003b909`; `javafx.graphics` totals:

| job | run 44961158059 | run 45456403357 |
| --- | --- | --- |
| Windows x64 | 26,684 / 0 / 0 / 857 | 26,875 / 0 / 0 / 924 |
| Linux x64 | 26,428 / 0 / 0 / 967 | 26,619 / 0 / 0 / 1,034, on the second attempt |
| Linux aarch64 | 26,428 / 0 / 0 / 967 | 26,619 / 0 / 0 / 1,034 |
| macOS aarch64 | not read | 26,887 / 0 / 0 / 858 |
| macOS x64 | not read | 26,887 / 0 / 0 / 864 |

- Attempt 1 (2026-11-24 18:09:19Z to 18:29:58Z) failed only its Linux x64 job, 138666905973, before anything
  compiled: Maven Central answered HTTP 429 for `org.apache.maven.plugins:maven-enforcer-plugin:pom:3.5.0`, and no test
  ran. Attempt 2 (18:31:31Z to 18:54:58Z) ran Linux x64 alone again, job 138681290152: it compiled and ran the tests,
  639 classes, each equal to Linux aarch64's and to the WSL gate's of `2c9003b909`. The other five jobs keep attempt
  1's results; `gh pr checks 71` passes all six.
- Per class, Windows x64 and Linux aarch64 against the baseline: 629 classes at the baseline and 639 in the run, the
  same 15 differ as in WSL, 191 more run and 67 more skipped on each, no class gone. On the Windows runner
  `DirectWriteMetricsGoldenTest` skips its comparison at the machine gate, 2 / 0 / 0 / 1 as at the baseline, so CI does
  not verify the golden.
- Both macOS jobs print the twelve verdict lines of "Verification", all `PROVED`, and run `CompositeFontLocksTest`
  20 / 0 / 0 / 0 and `PrismFontFactoryTest` 9 / 0 / 0 / 0: no slot test skipped at its room check there.
- That run is of `2c9003b909`; the five files of this round have run on no CI host.

**Linux, before and after by dump.** The four Linux font goldens cannot be captured from this build, as their harness
refuses: "refusing to capture: libjavafx_font.so is not on java.library.path (...). The Linux goldens record the JNI
build of commit 7b43255b30 and are never captured from anything else." They skip at their machine gate in WSL, so no
gate compares a Linux font row. In their place an evidence-only `LinuxFontGoldenDumpTest`, not in the tree (sha256
`87d2df47...`, kept with the logs), built the four corpora's maps as `FontGoldenHarness.captureOrVerify` builds them in
verify mode, through each golden test's own `capture`, and wrote them out, in the `abe0943d31` clone and in the
`2c9003b909` clone.
- Compared: 4,704 rows (`linux-fontconfig` 1,925, `linux-freetype` 1,498, `linux-pango` 928, `linux-font-process`
  353), none different; the 33 `machine.*` rows of each file equal; the four dump files byte-identical.
- Method check: two runs per clone, byte-identical, so no row is unstable and none was left out.
- Control: `slotAfterThePrimary` returning `fallbackSlot + 2`, in the `2c9003b909` clone only, moved 9 rows of
  `linux-pango`, all `pg.layout.lato.comp.*`, in the slot byte of their glyph codes only (01 to 02), and no row of the
  other three. The slot-5 glyphs of `s09`, in runs that take their slot from `CompositeGlyphMapper`, did not move.
- Trace: a print on entry to both `addNativeFallback` methods printed 0 lines over the four corpora, in the test JVM
  and its child JVMs: no Pango layout on this host added a native fallback, as F1 under "The Copilot review" read it.
- The limit: the dumps reach only the slot path where the font is in the fallback list, never 8b's -1 or 8c's `ns`.
  "No row moved" covers the locking, volatile and cache changes on the paths they reach, not the values 8b and 8c
  answer; the control shows sensitivity on the Pango path only; the result holds for this WSL Ubuntu x86_64 host and
  its packages; and it compares `abe0943d31` with `2c9003b909`, whose Linux code this round does not change.

**Mutants.** One production edit at a time, a copy of the original file with that one edit put in place (one file in
`git diff --numstat`), the classes run, the original copied back and
`git diff --quiet -- modules/javafx.graphics/src/main` checked after each; on Windows with `-Djfx.parity.require=true`,
in WSL without it. Every row is killed by test failures unless it says otherwise. G is `DirectWriteMetricsGoldenTest`,
NS `DirectWriteNoSlotLayoutTest`; of `PrismFontFactoryTest`, P.noSlot is
`aCompositeHasNoSlotForAFontItsFallbacksCannotHold`, P.added `aFontAddedToACompositeIsInTheSlotItIsGiven`, P.full the
new cap test, P.none `aCompositeSlotIsOneAboveItsFallbackSlotAndNoSlotStaysNone` and P.through
`aCompositeAnswersItsFallbackListsSlotsThroughTheMapping` (both any OS); of `CompositeFontLocksTest`, for the fallback
list (FB) and the logical font (LF), L.added is `aFontAddedToAFallbackResourceIsAtTheSlotItIsHandedOutFor`, L.comp
`aFontACompositeAddsIsAtTheSlotItIsHandedOutFor`, L.noLock the `...AnswersWhatItKnowsWithoutItsLock` tests, L.one the
`oneFontAddedTwiceAtOnceTo...` tests, L.two the `twoFontsAddedAtOnceTo...` tests, L.wait the
`callersOfANew...WaitForOneLookUpOfItsFallbacks` tests, L.once
`aNewFallbackResourceLooksUpItsFallbacksOnceForThreeCallers`, and the any-OS class-file checks L.lookup
`eachCompositeLooksUpItsFallbacksOnlyWhileTheyAreUnknownUnderItsLock` and L.last
`eachCompositeSetsItsFallbacksAfterTheirNamesAndFiles`.

The implementer's 9 (`impl/mut-win-M*.log`, `impl/mut-linux-M*.log`), all killed on both systems; FB is
`FallbackResource`, LF `LogicalFont`:

| row | the only edit | Windows | Linux |
| --- | --- | --- | --- |
| M1 | FB `ns + 1` (8c reverted) | G, NS, P.added, L.added, L.comp, L.noLockFB, L.oneFB, L.twoFB | not G, NS |
| M2 | LF `addNativeFallback` answers `ns + 1` | L.noLockLF, L.oneLF, L.twoLF | the same |
| M3 | `slotAfterThePrimary` `fallbackSlot + 1` (8b reverted) | NS, P.noSlot, P.none; G passes | P.noSlot, P.none |
| M4 | FB `addNativeFallback` not `synchronized` | L.oneFB, L.twoFB | the same |
| M5 | LF `addNativeFallback` not `synchronized` | L.oneLF, L.twoLF | the same |
| M6 | FB `getLinkedFonts` without its `synchronized` block | L.waitFB, L.lookup, L.last | the same, L.once |
| M7 | the same in LF | L.waitLF, L.lookup, L.last | the same |
| M8 | FB `addNativeFallback` without the lookup under the lock | L.oneFB | the same |
| M9 | the same in LF | L.oneLF | the same |

- M1 failed G with "609 difference(s)" and NS's control with "the slot of a glyph of the font added to a composite of
  12 slots ==> expected: <12> but was: <13>"; M3 failed NS's subject with "... expected: <0> but was: <3286>", and G
  passed under it: 8b moves no golden row.

The independent review's 77 runs, 39 on Windows and 38 on Linux, where `DWGlyphLayout` is not used (`review/logs/`,
`w-<row>.log` and `l-<row>.log`; FB, LF and PC edit `FallbackResource`, `LogicalFont` and
`PrismCompositeFontResource`):

| rows | the only edit | Windows | Linux |
| --- | --- | --- | --- |
| FB1, FB2 | `ns + 1` (8c), `ns - 1` | L.added, L.comp, L.noLockFB, L.oneFB, L.twoFB, G, NS, P.added | not G, NS |
| FB3 to FB5 | the cap `0x7E` to `0x7F`, to `0x7D`; the cap's `-1` to 0 | NS | survived |
| FB6 | `getSlotForFont`'s not-found -1 to 0 | L.added, L.oneFB, L.twoFB, NS, P.added, P.noSlot | the same but NS |
| FB7 | `getSlotForFontNoCreate` from index 1 | L.added, L.comp, L.noLockFB, L.oneFB, G, NS, P.added | not G, NS |
| FB8 | `getNumSlots` counts one added font | L.oneFB, L.twoFB, G, NS, P.added | not G, NS |
| FB9 | `getSlotResource` one index off | L.added, L.comp, L.oneFB, L.twoFB, G, NS, P.added | not G, NS |
| FBL1 | `addNativeFallback` not `synchronized` | L.oneFB, L.twoFB | the same |
| FBL2 | no lookup again under the lock | L.oneFB | the same |
| FBL3 | `getLinkedFonts` without `synchronized (this)` | L.lookup, L.last, L.waitFB | the same, L.once |
| FBL4 | `getLinkedFonts`' inner null check always true | L.lookup only | L.lookup, L.once |
| FBL5 | `getNumSlots` `synchronized` | L.noLockFB | the same |
| PC1, PC4 | `fallbackSlot + 1` (8b reverted); no slot -2 | NS, P.none, P.noSlot | P.none, P.noSlot |
| PC2, PC3 | `fallbackSlot + 2`, `+ 0` | L.comp, G, NS, P.added, P.none | L.comp, P.added, P.none |
| PC5 to PC7 | `getNumSlots` +2, +0; `getSlotResource(slot)` | L.comp, G, NS, P.added | L.comp, P.added |
| PC8 | the primary's slot 1 | P.noSlot | the same |
| DW1 | `renderShape`'s `slot >= 0` to `slot >= -1` | survived | not run |
| LF1, LF6 to LF8, LF10, LF11 | `ns + 1`; slot index and count arithmetic | L.noLockLF, L.oneLF, L.twoLF | the same |
| LF2, LF9 | `return ns - 1`; `getNumSlots` `+ 0` | L.noLockLF, L.oneLF, L.twoLF, G | L.noLockLF, L.oneLF, L.twoLF |
| LF3 to LF5 | the cap `0x7E` to `0x7F`, to `0x7D`; the cap's `-1` to 0 | survived | survived |
| LFL1 | `addNativeFallback` not `synchronized` | L.oneLF, L.twoLF | the same |
| LFL2 | no lookup again under the lock | L.oneLF | the same |
| LFL3 | `getLinkedFonts` without `synchronized (this)` | L.lookup, L.last, L.waitLF | the same |
| LFL4 | `getLinkedFonts`' inner null check always true | L.lookup only | L.lookup only |
| LFL5 | `getNumSlots` `synchronized` | L.noLockLF | the same |

- Survivors: DW1 on Windows; FB3 to FB5 on Linux, where only NS saw the cap; LF3 to LF5 on both. Killed only by a
  class-file check: FBL4 on Windows, where the count test does not run, and LFL4 on both. Under LF2 and LF9 G failed
  in 16 `slots.*` rows that an earlier class left in the process factory's cache (R6, US-129).

The rework's rows after R4 (`rework/logs/`), killed on both systems; NC is a negative control, a test edit:

| row | the only edit | Windows | Linux |
| --- | --- | --- | --- |
| FB3 | the cap `ns >= 0x7E` to `0x7F` | P.full ("... expected: <127> but was: <128>"), NS | P.full alone |
| FB4 | the cap to `0x7D` | P.full (<127> but was <126>), NS | P.full alone |
| FB5 | the cap's `return -1` to `return 0` | P.full ("... expected: <-1> but was: <1>"), NS | P.full alone |
| PC1 | `fallbackSlot + 1` (8b reverted) | P.full, P.noSlot, P.none, NS (<0> but was <3286>) | the same but NS |
| NC | the shared fill stops one font early | P.full (<-1> but was <126>), NS | P.full (NS skipped) |

The confirming review's own rows (`confirm/logs/`), 16 runs, none survived; C1 to C5 are the review's FB3, FB4, FB5,
PC1 and PC5:

| row | the only edit | Windows | Linux |
| --- | --- | --- | --- |
| C1 to C3 | the cap `0x7F`, `0x7D`; the cap's `-1` to 0 | P.full, NS | P.full alone |
| C4 | `fallbackSlot + 1` (8b reverted) | P.full, P.noSlot, P.none, NS | P.full, P.noSlot, P.none |
| C5 | the composite's `getNumSlots` +2 | P.full, P.added, NS (its room check, under the flag) | P.full, P.added |
| C6 | the cap's branch stores the refused font before `return -1` | P.full, NS | P.full alone |
| C7 | the composite's lookup `+ 1` again, not its add | P.full, P.through, P.noSlot, NS | P.full, P.through, P.noSlot |
| FB1 | `return ns` to `ns + 1` (8c reverted) | P.full, P.added, NS | P.full, P.added |

After O1 moved `fill` and `Refusal`, FB3 and PC1 ran again on both systems (`rework2/logs/`), killed with the same
messages, and the move's confirmation (`confirm2/`) split 8b into its two callers: W1, the composite's `addSlotFont`
`+ 1` again (Windows: P.full, P.through, NS), W2, its `getSlotForFont` `+ 1` again (Windows: NS "... expected: <0> but
was: <3286>", the fallback font's glyph ID in slot 0, P.full, P.noSlot, P.through), and L1, the review's FB3 (Linux:
P.full alone). All killed.

**The reviews.** An independent review of the implementation, verdict "must_fix"; a confirming review of the rework,
"pass"; a confirmation of the move O1, "pass" with no finding. The manager's decisions, each with its reason:
- R1, must: the header and the Javadoc said that the first native fallback a composite adds took the next slot; on
  master `addNativeFallback` answered `ns+1` for every font it added. Accepted: reworded, captured again (above).
- R2, should: DW1 survives `DirectWriteNoSlotLayoutTest`. Taken in part: its Javadoc names both places that leave the
  codes 0. Declined: a second test pinning the branch through glyph offsets. Reason: the branch is upstream code PR 71
  did not change, and the test's purpose, the composite's answer seen end to end, it shows (it kills 8b reverted). DW1
  is recorded as surviving.
- R3, should: the story was not updated. This section and the edits it names.
- R4, optional: the cap and its -1 were pinned on Windows only, inside the layout test. Taken: the cross-platform cap
  test, with one shared fill. Reason: the cap is where a composite really answers "no slot", it is plain Java, and FB3
  to FB5 survived on Linux.
- R5, optional: "records what crossed the JNI boundary" held for 8,069 of the 8,378 rows, 309 coming from the FFM
  build. Taken: reworded.
- R6, outside the story: the golden test captures through the process factory's cached composites and fonts, so its
  `slots.*` file-name rows depend on which earlier test class cached a linked font first. Filed as US-129.
- Declined: a `LogicalFont` cap test, though LF3 to LF5 survive on both systems. Reason: `LogicalFont`'s cap and its -1
  are upstream code PR 71 did not change, and its -1 always reached the layouts.
- The review's verdicts on the decisions: D1 sane, D2 sane as a mechanism (with R1's error in its text), D3 sane, D4
  risky, D5 sane. D4's "risky" is answered by R4 and by recording that Pango's `slot != -1` branch has no run-time test
  on any system.
- O1, optional, from the confirming review: the cross-platform cap test called `fill` and `Refusal` of the Windows-only
  layout test. Taken: both moved into `PrismFontFactoryTest`, their bodies byte-identical, and the layout test calls
  them there. Reason: the dependency pointed from a test every system runs to a class that describes a DirectWrite
  test. It changed code after the final gates, so both final gates ran again; the tables above are those runs.
- Beside the header's (R1) and "no mutant went unkilled" (true of its own 9 only), the review contradicted two claims
  of the implementer's record: 32 lines of the golden's body are over 120 columns, data rows, as at `2c9003b909`, where
  the record said none; and the layout test does store in the process factory (its layout caches the fallback's
  physical font, `storeInMap`), where the record said nothing is, which holds of the fill.
- Order: the review ran the font package in file-system and reverse alphabetical order, 552 / 0 / 0 / 190 twice on
  Windows and 552 / 0 / 0 / 257 twice in WSL, per class identical; the confirming review, with the cap test, 553 / 0 /
  0 / 190 in both orders on Windows (in reverse order the cap test's class 16th, the golden 51st) and 553 / 0 / 0 /
  257 in WSL.

**What rests on review after this round.**
- macOS: nothing of this round ran there. That the 13 enabled tests execute the statements of `2c9003b909` there,
  except the one OS branch, and that the cap test takes only steps existing macOS tests take, rests on reading (the
  review and both confirmations). The cap test and the OS branch first run on macOS on CI after the push.
- CI: the five files have run on no runner; the enabled tests and the two new ones first run there after the push. The
  Windows runner skips the golden's comparison at its machine gate, and the new layout test's precondition has not been
  seen on a runner; where it does not hold, the test skips.
- DW1: `DWGlyphLayout.renderShape`'s `slot >= 0` is pinned by no test on its own (R2).
- Pango: `PangoGlyphLayout`'s `slot != -1` branch (line 179) has no run-time test on any system. No Pango layout of the
  corpora on the WSL host added a native fallback, since fontconfig's list for the primary already holds Pango's
  fallback fonts (D4); that no other Linux host would show it is a claim about hosts, not a proof (the review).
- `LogicalFont`'s cap and its -1: no test fills a logical font to its cap (LF3 to LF5), and no run-time test counts a
  logical font's look-ups on any system.
- The dump: not the values 8b and 8c answer, not the sensitivity of the `CompositeGlyphMapper` path, and this host
  only.
- What the round's tests leave in the process: by reading, the cap test stores nothing in the factory's maps, and the
  layout test caches the fallback's physical font; the order runs above pass. That the golden's `slots.*` rows can
  depend on the classes before it is US-129.

**Upstream.** Read on 2026-11-24 at openjdk/jfx `master`, `94168cc51f` ("8397455: Skip failing tests on Ubuntu 26.04",
2026-11-23): both defects are there. `FallbackResource.addNativeFallback` ends `return ns+1;` (line 270), since
`a1add2e3d5`, JDK-8246104 (2023-04-07, PR 1067, jfx21), whose `LogicalFont.addNativeFallback` got `return ns;`.
`PrismCompositeFontResource.getSlotForFont` ends `return fallbackResource.getSlotForFont(fontName) + 1;` (lines 65 to
70), older than JDK-8246104 (it is in `c420248b9b`, 2016). Upstream's `PrismCompositeFontResource` has no `addSlotFont`;
the fork's is the fork's own. JBS searches (`addNativeFallback` 0 hits, `getSlotForFont` 2, `FallbackResource` 7, none
of them this defect) and a pull-request search found no issue or PR for either. A draft report, one issue in two parts
with a proposed patch, is kept outside the repository and is not filed.

**Logs.** On Windows, in the session scratch directory, which is temporary:
`C:/Users/bestq/AppData/Local/Temp/claude/50b9cf433e233190/afff22c1-2a08-459a-b423-9eb87c739ded/scratchpad/`. Nothing of
it is in the repository. The WSL clones `~/jfx-us111-before`, `-head`, `-impl`, `-review`, `-final` and `-final2` stay
in the Linux home; the first two hold the dump test.
- `BRIEF.md` and `DECISIONS.md` (the round's brief and the manager's decisions with their reasons), and `results/`, the
  agents' reports: `win-head.md`, `wsl-gates.md`, `ci.md`, `ci-rerun.md`, `impl.md`, `review.md`, `linux-dump.md`,
  `rework.md`, `confirm.md`, `win-final.md`, `wsl-final.md`, `rework2.md`, `confirm2.md`, `win-final2.md`,
  `wsl-final2.md` and `upstream.md` (the upstream draft), with `pr-comments.json` and `pr-reviews.json`.
- `win-head/` (the Windows gate of `2c9003b909`: `gate.log`, `per-class.tsv`, the golden test's report); `wsl-gates/`
  (the WSL gates of `abe0943d31` and `2c9003b909`, the refused captures, the per-class tables and their difference,
  `machine-start.txt`, `machine-end.txt`); `ci/` and `ci2/` (the job logs of runs 45456403357 and 44961158059 and their
  per-class tables, `ci2/run_lax2.log` the Linux x64 job of the second attempt).
- `impl/` (`1b-parity.log`, `1d-capture.log`, `1f-capture2.log`, `capture-diff.txt`, `golden-before.txt`,
  `run1-win.log`, `cjk-alone-win.log`, `nc-win.log`, the mutants' `mut-win-M*.log` and `mut-linux-M*.log`,
  `final-win.log`, `final-linux.log`, `final.sha256`); `linux-dump/` (the dump test and its sha256, the seven runs'
  dumps and logs, `compare-before1-head1.txt`, `control-diff.txt`, `step7-head1-pango.txt`, `machine-*.txt`);
  `review/` (`logs/w-*.log` and `logs/l-*.log`: its capture, the 77 mutant runs and the order runs; `mutant-table.md`,
  `NOTES.md`).
- `rework/` (`logs/`: the two captures, the proof runs, FB3 to FB5, PC1 and NC on both systems;
  `golden-before-capture.txt`, `golden-after-capture1.txt`, `body-before.txt`, `body-after.txt`, `final.sha256`);
  `confirm/` (`logs/`: its capture, C1 to C7 and FB1 on both systems, the order runs); `rework2/` and `confirm2/` (the
  move's extracts and diffs, its runs and mutants).
- `win-final/` and `win-final2/` (`gate.log`, `per-class.tsv`, `files-at-gate.sha256`); `wsl-final/` and
  `wsl-final2/` (`final-gate.log` and `final2-gate.log`, `final-per-class.tsv`, `machine-start.txt`,
  `machine-end.txt`).
- `writeup/pr-drafts.md`: drafts of a reply to Copilot's F1 thread and of the PR description's scope, not posted.

The owner's Maven builds of another project ran on this machine during some targeted runs of the implementation and
the review; the final gates found no other `java.exe` at their start and end. The implementer stopped one process of
its own in WSL, a wait loop that matched its own command line, after `ps` showed it was its own.

**Not done.** Nothing of this round ran on macOS. No robot or `tests/system` test was run. The round is uncommitted:
the five files are in the working tree on `2c9003b909`. For the owner: the commit, the push and a `submit.yml` run of
it, read for `PrismFontFactoryTest` and `CompositeFontLocksTest` in every job and for both macOS jobs' verdict lines;
the reply to Copilot's F1 thread and the PR description (drafts only); the upstream report; the robot and
`tests/system` runs; and the comparison of the macOS goldens on their capture machine.

### Measured (2026-11-21 to 2026-11-23; Apple arm64, macOS 27.2.1 (26B131), JDK 27.0.1.0.2, Maven 3.10.0; and CI)
Before: a clean tree at HEAD `abe0943d31`. After: the same commit with the final tree of this change (25 files,
`after/final.sha256`). Commands as under "Verification"; the gate was run both without and with
`-Djfx.parity.require=true`. The logs are named under "Logs". Run / failures / errors / skipped:

| run | before | after |
| --- | --- | --- |
| gate, no flag (exit 0) | 482 / 0 / 0 / 8 | 506 / 0 / 0 / 8 |
| gate, `-Djfx.parity.require=true` (exit 1) | 482 / 8 / 0 / 0 | 506 / 8 / 0 / 0 |
| module, `javafx.graphics` (exit 0) | 26,696 / 0 / 0 / 858 | 26,720 / 0 / 0 / 858 |
| module, jslc and `javafx.base` | 437 / 0 / 0 / 0, 5,510 / 0 / 0 / 22 | the same |
| `hs_err_pid*` finds, golden `git diff --stat` | nothing | nothing |

- **The logs of the table.** Before: `before-gate-noflag.log`, `before-gate-require.log`, `before-module.log` (01:04
  min) and `before-checks.log` (0 bytes). After: `after/gate-noflag.log`, `after/gate-require.log` and
  `after/module.log` (01:10 min). The after checks printed nothing after the module run and again after the
  confirmation's gates; those two records kept no log file of them.
- **Why the flagged gate cannot pass here.** The goldens were captured on macOS 27.0.1 (26A434) and this Mac runs
  27.2.1 (26B131), so no golden is an oracle on it. The same 8 comparisons, one per class, skip without the flag and
  fail with it, before and after, and nothing else fails:
  - `MacCoreTextGoldenTest`, `MacDFontGoldenTest`, `MacFontFinderGoldenTest`, `MacFontProcessGoldenTest` and
    `MacGlyphLayoutGoldenTest`: "the golden was captured on a different machine, OS build or font set" (the OS
    version and build, the CoreFoundation, CoreGraphics, CoreText and dyld shared-cache UUIDs and the font-set
    hashes differ). Each fails only after its binding witness has passed.
  - `MacAccessibleNativeTest`, `MacClipboardNativeTest` and `MacPixelsNativeTest`: "golden describes another machine".
  - The sorted failing-class lists of before and after are equal (`after/before-require-failing.txt`,
    `after/after-require-failing.txt`). No golden was re-captured.
- **In place of the oracle.** An evidence-only `Us111CorpusDumpTest` (US-067's dump test, renamed) dumped the five
  corpora on HEAD twice and once on the final tree: 2,583, 260, 599, 558 and 1,580 lines, no `FAILED` row.
  - The two "before" dumps are byte-identical (`slice-0/dump-before-diff.log`, 0 bytes), so no row is unstable.
  - `diff -r` of the first and the "after" dump is empty (`after/dump-diff.log`, 0 bytes); every file has the same
    sha256 (`after/dump-after.sha256`). No recorded value moved.
  - The rows the changed code feeds are among them: `ct.font.name.n10` and its four `ct.font.name.n10.*` rows (site
    4's empty name), `lay.system.s12.t06.r00` (site 12) and the 520 `mff.getFontData` rows (site 2).
  - Each dump run is 1 / 0 / 0 / 0, exit 0 (`slice-0/dump-before-1.log`, `slice-0/dump-before-2.log`,
    `after/dump-after.log`). The test was then deleted with its class; no module run had it, because the "before"
    run predates it and the "after" run follows its removal.
- **139 to 163, and 482 to 506.** `Mac*Test` also selects the 14 Glass classes of `test.com.sun.glass.ui.mac`, 343
  tests before and after; the font classes run 139 before and 163 after. Of 34 classes before and 35 after, only
  these changed (`after/before-gate-noflag.perclass.txt`, `after/gate-noflag.perclass.txt`). The column "CI fix" is
  the module run with the CI fix (`final/V4.log`); the gate selects all six classes, but it was not run on the fix:

  | class | before | after | CI fix | the new tests |
  | --- | --- | --- | --- | --- |
  | `MacFontNullInputTest` | 9 | 18 | 18 | sites 1, 2, 3, 4 (two), 5, 6, 7 and 11 |
  | `MacFontReleaseTest` | 7 | 14 | 15 | sites 9, 12 (two) and 10 (five) |
  | `MacFontBindingStructureTest` | 8 | 11 | 15 | sites 4, 8, 12 and 10 (four) |
  | `coretext.GlyfTableBoundsTest` | 10 | 12 | 12 | site 11 (two) |
  | `coretext.MacObjCNativeTest` | – | 3 | 4 | site 10, the new facade and a pop |
  | `MacFontReleaseVerdictTest` | – | – | 30 | the verdicts of sites 10 and 12, any OS |

- **The module run gains the same 24 tests**, all in `javafx.graphics`, with the same skips; of its 954 classes
  before and 955 after, only these five differ (`after/before-module.perclass.txt`,
  `after/after-module.perclass.txt`). In both module logs the result line of
  `test.javafx.scene.layout.BorderStrokeStyleTest` is interleaved with a JEP 500 warning ("Final field hash in class
  javafx.scene.layout.Border has been mutated reflectively by class test.javafx.scene.layout.BorderTest"), so a
  per-class sum of the console comes out 18 short in both; the class's surefire XML gives 18 / 0 / 0 / 0. The warning
  is there at HEAD and outside the font layer, and no story was filed for it.
- **With the CI fix** (`final/V4.log`, 01:10 min): `javafx.graphics` 26,756 / 0 / 0 / 858, 36 more than after,
  with the same skips; jslc and `javafx.base` as before; no `hs_err_pid*` file and no golden change. The font tests
  and `ParityGateTest` ran 432 / 0 / 0 / 156 (`final/V1.log`). The gate was not run, without or with the flag. A
  rehearsal on the fix's final tree of a machine that cannot show the defects, with two temporary test edits (bound
  1000, Latin text), printed three `CANNOT_SHOW` lines and skipped the three checks, `MacFontReleaseTest` 15 / 0 /
  0 / 3, and with the flag failed exactly them, 15 / 3 / 0 / 0, each message starting "-Djfx.parity.require=true
  says this machine owns the oracle of MacFontReleaseTest, and the oracle could not run" (`final/R1.log`,
  `final/R2.log`).
- **The gate without the flag after each step**, each exit 0 with the same 8 classes skipped: HEAD 482
  (`before-gate-noflag.log`), slice A 486 (`slice-A/gate2-noflag.log`), B 494 (`slice-B/gate-noflag.log`), C 498
  (`slice-C/gate-noflag.log`), D 504 and, after its review round, 505 (`slice-D/gate-noflag.log`,
  `slice-D/gate-r2-noflag.log`), the touch-ups 505 (`touchups/gate-noflag.log`), the test-only fixes 506
  (`final-fix/gate-noflag.log`). Each slice's reviewer repeated it on the tree it reviewed, with the same number
  (486, 494, 498, and 504 for slice D's first round). The flagged gate and the module run were taken before and
  after only, plus the reviews' flagged gates under "Independent review".
- **Site 10 was measured before it was fixed.** In a child JVM: the change of `malloc_zone_statistics` blocks in use
  over 300 calls of `getCascadeList` (Helvetica 12) on a fresh thread, one warm-up round, three measured rounds, the
  smallest counted.
  - Before, 3 runs: 229.03 to 230.38 blocks per call, on a platform thread and from a virtual one. The story said
    about 232; two probes of the design had disagreed (about 230 and about 5).
  - After, 7 runs: at most 0.283 on a platform thread and 0.037 from a virtual one. The no-pool controls read 228.97
    to 230.81 throughout, and an empty hand-off at most 0.66 in any round, so nothing is subtracted.
  - The bound is 10 blocks per call, inside the 0.57 to 114.5 that "at least twice the largest fixed figure and at
    most half the smallest control" allows. The first measured round alone reads up to 8.7 after the fix (the heap
    settling), which the smallest-round rule absorbs. The bound and these figures come from this one machine.
  - Files: `slice-D/unfixed-{1,2,3}.*`, `slice-D/fixed-{1..6}.*` and `slice-D/gate-r2.*`.
  - With the CI fix, 6 runs here (`impl/v1.log`, `impl/V4.log`, `fix/W1.log`, `fix/W3.log`, `final/V1.log`,
    `final/V4.log`): at most 0.283 on a platform thread and 0.037 from a virtual one, controls 229.50 to 230.15, the
    empty hand-off -0.45 to -0.26.
- **CI run 45022158121, on `40e53db102`** (`ci/mac-aarch64.log`, `ci/mac-x64.log`): macOS 15.8.2 arm64 (runs-on
  `macos-15`, image `macos-15-arm64`) and 15.7.9 x64 (runs-on `macos-15-intel`, image `macos-15`), Oracle JDK 27,
  `mvn -B -ntp -fae install -Djfx.web.skipFfmTests=false`.
  - `javafx.graphics` 26,720 / 3 / 0 / 858 on arm64 and 26,720 / 3 / 0 / 864 on x64; `MacFontReleaseTest` 14 / 3 /
    0 / 0 on both, and no other test failed. The modules after `javafx.graphics` did not run there. Linux x64, Linux
    aarch64 and Windows x64 passed (`MacFontBindingStructureTest` 11 run, 1 skipped there).
  - The region without a pool left 6.01 blocks per call on a platform thread on both (rounds 6.59; 6.01; 6.03 and
    6.58; 6.02; 6.01) and, on a virtual thread's carrier, 6.01 on arm64 (6.50; 6.04; 6.01) and 5.99 on x64 (6.52;
    6.04; 5.99). That is below the bound, so the heap there cannot show a missing pool: both heap checks failed at
    their control.
  - A virtual caller's call left 10.67 (12.71; 11.62; 10.67) and 11.26 (13.10; 12.38; 11.26), above the bound and
    more than the region without a pool (item 14 of the further fixes). The platform caller's call and the empty
    hand-off left less than 10 on both; their values were not printed.
  - The fallback-face child printed no lookup that found no face in its file, so the check cannot show site 12's
    defect there; it failed at that precondition. What it looked up and laid out was not printed.
- **Site 6, the unified log.** On the unguarded code in a child JVM, CoreGraphics wrote one Error-level line per
  setter ("CGContextSetAllows…: invalid context 0x0", subsystem com.apple.coregraphics) and nothing on stderr;
  `CGBitmapContextCreate` of width 0 logs nothing; `log show` for the child's pid took 0.66 to 0.69 s and needed no
  sudo on this admin account (`slice-B/experiment-unguarded.log`, `slice-B/experiment-log-*.txt`).
- **CI run 45041254324, on `f352c6008c`** (`ci2/job-137266001302.log`: macOS 15.8.2 arm64, runs-on `macos-15`, image
  `macos-15-arm64`; `ci2/job-137266001554.log`: macOS 15.7.9 x64, runs-on `macos-15-intel`, image `macos-15`), the
  same command: green on all six jobs, macOS 15 arm64 and x64, Linux x64 and aarch64, Windows x64 and No JNI.
  - `javafx.graphics` 26,756 / 0 / 0 / 861 on arm64 and 26,756 / 0 / 0 / 867 on x64, and every module after it ran;
    `MacFontReleaseTest` 15 / 0 / 0 / 3 on both, `MacFontNullInputTest` 18 / 0 / 0 / 0 (the log check passed),
    `MacFontReleaseVerdictTest` 30 and `MacFontBindingStructureTest` 15, without failure.
  - The three checks of the CI fix answered `CANNOT_SHOW` and skipped with their lines; figures in blocks per call,
    arm64 then x64, the smallest round counted:
    - virtual caller: the region without a pool 5.94 (rounds 6.56; 5.94; 5.99) and 6.01 (6.49; 6.02; 6.01), not
      above 10; the call, a thread started per call, 10.73 (12.93; 11.97; 10.73) and 10.94 (12.99; 12.07; 10.94); a
      platform thread started and waited for 1.40 (1.71; 1.40; 1.49) and 1.66 (1.75; 1.66; 1.66);
    - platform caller: the region without a pool 5.91 (6.53; 5.91; 6.04) and 6.01 (6.59; 6.01; 6.01); the call 4.06
      (4.80; 4.09; 4.06) and 4.01 (4.28; 4.07; 4.01);
    - fallback face: no fallback face of the System font's Devanagari text is missing from its font file there; the
      child looked up `.Zither India Regular` in `/System/Library/Fonts/ZitherIndia.otf` once, found it at index 0,
      and gave it slot 35 in each of the 5 layouts.
  - So on macOS 15 a pooled call on a platform caller leaves about 4 blocks per call, under the bound of 10; the heap
    there cannot show a missing pool, and the System font's Devanagari text reaches no missing face.
- **The further fixes, on this Mac** (2026-11-23; logs under `impl2/CLOSE/evidence/`): `f352c6008c` with the 48 files
  of the further fixes (`final-all.sha256`), the final tree; the dump test is not in it. The column `f352c6008c` is
  the CI fix's final tree, whose code `f352c6008c` is (`final/V1.log`, `final/V4.log`):

  | run | `f352c6008c` | the further fixes |
  | --- | --- | --- |
  | font package, no flag | 432 / 0 / 0 / 156 | 548 / 0 / 0 / 156 (`font.log`) |
  | font package, `-Djfx.parity.require=true` (exit 1) | not run | 548 / 5 / 0 / 151 (`font-require.log`) |
  | module, `javafx.graphics` (exit 0) | 26,756 / 0 / 0 / 858 | 26,872 / 0 / 0 / 858 (`module.log`) |
  | module, jslc and `javafx.base` | 437 / 0 / 0 / 0, 5,510 / 0 / 0 / 22 | the same |

  - With the flag the 5 failures are exactly the golden comparisons of the five `Mac*GoldenTest` classes at their
    machine gate (golden from macOS 27.0.1 26A434, this Mac 27.2.1 26B131), the 5 skips without it; the 151 skips
    are the Linux, Windows and DirectWrite classes ("Disabled on operating system: Mac OS X").
  - What CI runs, with `verify` for `install` (`reactor.log`): all 15 reactor modules SUCCESS in 02:25 min, no test
    failure or error; `javafx.controls` 9,445 / 0 / 0 / 183, the rich text incubator 186, `javafx.fxml` 123 / 0 /
    0 / 3, `javafx.media` 41 / 0 / 0 / 6, the web FFM binding tests 194.
  - Ten verdict lines, all `PROVED` (`font.log` lines 182, 205 to 212 and 275, copied to `font-verdicts-log.txt`; the
    same ten in the other three logs). Virtual drain check: the call -0.043 blocks per call, the region without a pool
    229.90, a no-op hand-off 0.017, a platform caller's call 0.49; the tokens moved in 0 of 10 cycles for an empty
    region and for the call and in 10 of 10 for the region without a pool; 10 of 10 callers waited for the platform
    thread. Platform drain check: the call 0.0033, the region without a pool 229.59, the tokens the same. The lent
    font: "the caller's call, from before its task took its reference to after the task had run, moved the font's
    retain count by 0, as each of the two lists before it did". The run in no slot: runs Kohinoor Devanagari Regular
    33, Ahem (the line's font) 23, Kohinoor Devanagari Regular 33 and Apple Color Emoji ffff gave `0;23;0;ffff`. The
    fallback face and the font without a file: Ahem looked up once over 5 layouts, slot -1 each time. The unreadable
    file: -1, then 35 once the file could be read, looked up again. The own-font slot: plain strike 0 (1 for its
    strike slot), another line's run -1, the composite 43 as added, the logical font 1 where it was -1 before. The log
    check: both controls' lines and no setter line beyond the control's. The sub-pixel check: the glyph drawn into the
    cached context is the one drawn with its own strike's options (`399;a7c6d7f8…`, where the other strike's options
    give `399;1e9bfb93…`).
  - The corpus dump was taken on the final tidy's tree (`impl2/FT/evidence/dump.log`). Every production file, shim
    and the golden are byte-identical on the final tree: of `impl2/FT/evidence/final-all.sha256` 40 entries hold, and
    the 9 others are 8 test files of the hardening and closing rounds and the dump test, moved out
    (`ft-shasum-check.log`). So it is the dump of the final tree. It equals cluster E's after-dump (`diff -r` empty),
    and its 1,579 `lay.*` rows equal the golden's byte for byte (`golden-diff.log`, 0 bytes). Against `f352c6008c` the
    corpora moved exactly the ten rows of deviation 16, in the glyph field only: 4 by cluster B (the slot byte of
    `lay.pingfangsc.s12` t02, t03, t04 and t07) and 6 by cluster E; the dumps before and after clusters A, D, C and F
    are byte-identical.
  - No `hs_err_pid*` file; `git diff --check` clean; the checksums of the 48 files held after every run
    (`close-shasum-check.log`). Of the hardening round's evidence (`impl2/LAST/evidence/final-all.sha256`) 46 entries
    hold; the two others, `MacFontReleaseVerdict.java` and `MacFontReleaseVerdictTest.java`, differ only in comment
    lines and in the two messages and six asserted strings of ruling K3 (`last-shasum-check.log`, `w2b-verdict.diff`,
    `w2b-verdicttest.diff`). Before this evidence, the hardening round ran the seven classes around its changes 134 /
    0 / 0 / 0 without and with `-Djfx.parity.require=true` (`impl2/LAST/fix-r1/touched-fix-r1-last.log`,
    `touched-require-fix-r1-last.log`), and the closing round ran `MacFontReleaseVerdictTest` and `MacFontReleaseTest`
    82 / 0 / 0 / 0 both ways (`impl2/CLOSE/k3/touched-k3.log`, `touched-require-k3.log`).
  - The fixes of the Copilot review were measured after these, on the same Mac: see "The Copilot review".
  - Until CI run 45278364431, the push of `e1de3dd32a`, the further fixes had run only on this Mac (macOS 27.2.1 arm64).
    That run passed them on macOS 15 (arm64 and x64; `javafx-graphics` 26,872 tests on each), Linux aarch64 and
    Windows x64, with all ten verdict lines `PROVED` on both macOS 15 runners; its Linux x64 job stopped at an HTTP 429
    of Maven Central before it compiled anything (F1 under "The Copilot review").

### Criterion 1: sites 1 to 7
`MacFontNullInputTest`, one test per row, each in a child JVM, so a crash fails the test instead of ending the
fork; each also asserts its precondition. Criterion 1 is read as "no NULL object of the inputs the story lists" (see
"Deviations and stated properties", 1). With its guard removed (the mutant rows below) the child of sites 1, 2, 3, 4
and 7 dies: exit 133 (SIGTRAP, no `hs_err` file) at a `CFRelease(NULL)` or the first CoreText call, or 134 with an
`hs_err` file (`CFArrayGetCount`, `CFStringCompare` and `CFStringGetLength` of NULL).
- **1** A system font size without a UI font is 13: `aSystemFontSizeWithoutAUiFontIsThirteen` (`sizeOf` of a real UI
  font equals `getSystemFontSize()`).
- **2** A font list without collection entries still lists the two UI fonts:
  `aFontListWithoutCollectionEntriesStillListsTheTwoUiFonts` (`fontData(0)` has 6 entries, the last two triples of
  the real list; a NULL UI font or descriptor leaves index and array as they were).
- **3** A dfont without a collection, an array or a name has no font: `aDfontWithoutACollectionOrANameHasNoFont`
  (the control `createCTFont("Helvetica")` is not 0).
- **4** A UI font CoreText did not make has no copy: `aUiFontCoreTextDidNotMakeHasNoCopy`. A zero font name has no
  font and the empty name is Helvetica, today's golden outcome: `aZeroFontNameHasNoFontAndTheEmptyNameIsHelvetica`.
  That the length is read before the first character is a classfile check of `MacFontBindingStructureTest`,
  `aFontNameIsMeasuredBeforeItsFirstCharacterIsRead`, because `CFStringGetCharacterAtIndex("", 0)` answers 0 on
  this macOS and no run-time test can tell. The release of a copied UI font is measured by `MacFontStressTest`
  against a leaking control (2 / 0 / 0 / 0 in every gate).
- **5** A strike without a CTFont has advance 0: `aStrikeWithoutACtFontHasAdvanceZero` (US-067's Ahem without
  `glyf`; strike `fontRef` 0 and an hmtx advance other than 0 (12.0 here) asserted, so 0 is a distinct outcome).
  CoreText answers 0.0 for a NULL font too, so the guard rested on review (R5) until the further fixes pinned it in
  the class file (item 11).
- **6** A context CoreGraphics did not make is 0 and nothing is set on it:
  `aContextCoreGraphicsDidNotMakeIsZeroAndNothingIsSetOnIt`. `createContext(…, 0, 5)` answers 0 with or without the
  guard, so the test reads the unified log of the child's pid.
  - After the subject the child calls two controls through the observer's own bindings,
    `CGContextSetAllowsAntialiasing(NULL)` and then `CGContextSetRGBFillColor(NULL)`.
  - Any setter line beyond the one control line fails the test. No fill-colour line (the log cannot be read) or no
    setter-control line (the setters no longer log a call on NULL) aborts it, with the reason. Since the further
    fixes (item 12) those two are `CANNOT_SHOW` through `MacFontReleaseVerdict.decide`, a skip with its line or a
    failure under `-Djfx.parity.require=true`.
  - On the final tree the child's log holds exactly one line of each control (the test-only fixes; the
    confirmation, pid 20604).
- **7** No language codes give an empty cascade list: `noLanguageCodesGiveAnEmptyCascadeList` (Helvetica's list
  through `getCascadeList` is not empty).

### Criteria 2 and 3: sites 9 and 8
- **9** `twoThreadsThatFindTheContextCacheEmptyCreateOneContext`, in a child JVM (`MacCoreTextGoldenTest` requires
  the cache to be empty in the test JVM).
  - A `CTFontStrikeShim` strike runs a hook in `isSubPixelGlyph`, the only call between the create and the store.
    The first thread waits there until the second is in the hook too ("second creating") or BLOCKED with top frame
    `CTGlyph.getCachedContext` ("second blocked"); a 20 s time-out fails the test.
  - The threads call the private `getCachedContext` by reflection and draw nothing (two threads drawing into the
    one bitmap is item 1 of the further fixes).
  - Before the fix: 2 creations, and the two threads got different contexts, one without an owner. After: 1
    creation, "second blocked", both got the cached context (`slice-C/red-1.release.cachedContext.txt`,
    `slice-C/green-1.release.cachedContext.txt`; three more runs the same, `slice-C/stability-{1,2,3}.log`).
- **8** `MacFontBindingStructureTest.layoutKeepsTheStrikeReachableUntilItsLineHoldsTheFont`, a classfile check: every
  `Reference.reachabilityFence` call in `layout` takes the local that `getFontRef` was called on, and the first
  `createCTLine` comes before the first fence. `javap -c -p` shows the fence at both exits of the `finally`. That
  the fence is enough at run time rests on review.

### Criterion 4: sites 10 and 12
- **10, the pool.** `aCascadeListCallOnAPlatformThreadDrainsWhatItAutoreleased` and
  `aCascadeListCallOnAVirtualThreadDrainsWhatItAutoreleased`: fewer than 10 blocks per call, see "Measured". Every
  call must give the same list (39 entries here).
  - Since the CI fix each decides through `MacFontReleaseVerdict`. Its control (the same region without a pool: the
    production `cascadeList`, with the codes from the observer) above 10, and for the virtual one an empty hand-off
    below 10, is the precondition under which it decides.
  - Elsewhere it is skipped with the figures, or fails under `-Djfx.parity.require=true`. On the macOS 15 runners the
    control is about 6, and both skipped there on CI run 45041254324 (see "Measured"). Since the further fixes
    (item 12) each also reads the pool tokens, which can prove the drain where the heap cannot.
- **10, on every machine** (the CI fix; `MacFontBindingStructureTest` on any OS, `MacObjCNativeTest` on any macOS).
  - `theCascadeListIsReadInsideAnAutoreleasePool`: in `pooledCascadeList` the push comes before the language-codes
    copy, and `cascadeList` before the pop.
  - `thePoolOfTheCascadeListIsPoppedOnBothExits`: a pop lies between `cascadeList` and each return after it, and a
    handler that catches everything `cascadeList` throws pops before it throws: javac's two copies of the `finally`.
  - `aVirtualCallerHandsTheCascadeListToAPlatformThread`: in `getCascadeList`, `isVirtual` comes before the hand-off
    and the hand-off before `pooledCascadeList`; the first branch after `isVirtual` is an `ifeq` whose fall-through
    holds the hand-off, and `pooledCascadeList` is at or after its target; the instruction after the hand-off is a
    return.
  - `theHandOffStartsAPlatformThreadAndNeverRunsTheTaskItself`: `cascadeListOnAPlatformThread` calls none of
    `Thread.run`, `FutureTask.run`, `Runnable.run` and `pooledCascadeList`, and calls `Thread.ofPlatform` before
    `Thread.start` and `start` before `resultOf`. The task's body, a lambda, is a method of its own, which no check
    reads. Since the further fixes (item 4) it is S4,
    `theHandOffQueuesATaskForThePlatformThreadAndNeverRunsItItself`, over the new hand-off, and S5, S7, S8 and the
    keep check read the task's body.
  - `aPoppedPoolReleasesWhatWasAutoreleasedIntoIt`: on the test's platform thread a `CFMutableData` retained and
    autoreleased into a pushed pool holds one more reference until the pop, and none after it.
- **10, the wait.**
  - `anInterruptedVirtualCallerGetsTheWholeCascadeListAndKeepsItsInterrupt`: a virtual caller that interrupted
    itself gets the whole list and keeps its interrupt, end to end.
  - `anInterruptTheWaitClearsDoesNotEndItAndIsSetAgainOnceTheTaskAnswers` (added by the test-only fixes): `resultOf`
    on a task that a latch holds. The caller is seen WAITING with `FutureTask.get` above `MacFontFinder.resultOf`,
    which in JDK 27 means a first `get()` saw the flag, cleared it and threw. Then the task's own array comes back
    and the flag is set again. Its `doneWhenParked=false` is a stated precondition that holds by construction.
  - `anErrorOfTheWaitItselfIsThrownOnlyOnceTheTaskIsDone`: a `FutureTask` whose first `get()` throws an
    `OutOfMemoryError` while the task runs; the caller leaves only once the task is done, and throws that error.
  - Since the further fixes (item 5) the wait is `CascadeListTask.awaitList`: the two interrupt tests run through it,
    the second with the worker held, and the third test is deleted with `resultOf` (deviation 13).
- **10, the facade.** `coretext.MacObjCNativeTest`: both functions are bound from `/usr/lib/libobjc.A.dylib` with
  their descriptors, nested pools pop inner then outer (each in a `finally`), and a missing symbol is named.
- **12.** `aFallbackFaceItsFontFileLacksIsLookedUpOnce`, in a child JVM with `prism.debugfonts=true`: 5 layouts of
  the System font with Devanagari, each a fresh run laid out directly.
  - For each name, its `Fallback font= <name> slot=-1` line shows 5 times and its `createFontFile by ref … index=-1`
    line once, and every layout gives the same glyphs.
  - Before the fix: 5 lookups of `.SF Devanagari Regular` in `/System/Library/Fonts/SFIndia.ttc`; after: 1. Both
    trees give `7f;40a;f3;d5;40`, the slot-0 glyphs of golden row `lay.system.s12.t06.r00`
    (`slice-C/red-1.release.fallbackFace.err`, `slice-C/green-1.release.fallbackFace.err`).
  - Since the CI fix it decides through `MacFontReleaseVerdict`: a face missing from its font file is the
    precondition. Where the child prints no lookup of such a face, as on both macOS 15 runners, it is skipped with
    what the child printed and laid out (so on CI run 45041254324), or fails under `-Djfx.parity.require=true`.
  - Since the further fixes (item 12) the child makes the missing face itself from Ahem and Helvetica.ttc, and item
    8a lays the run out with the missing glyph, so `lay.system.s12.t06.r00` is `0;0;0;0;0` (deviation 16).
  - `MacFontBindingStructureTest.layoutAsksForAFallbackFaceBeforeCopyingTheRunFont`: in `getFontSlot` the
    `mayFindFace` call comes before the font copy. Since the CI fix it also pins the guard on any OS: the first
    branch after `mayFindFace` is an `ifeq` whose fall-through holds every call of `OS.CTFontCreateCopyWithAttributes`
    and of `addFallbackFont`.
  - `MacFontReleaseTest.aFaceItsFontFileLacksIsRememberedByItsName` (the CI fix, any macOS): an invented name looked
    up in a copy of Helvetica's font finds no face and is remembered after it, in either case; "Helvetica", whose
    face is found, is not.
  - Since the Copilot review (F5, F6) `MacFontFaceLookupTest` pins that the miss holds only for the same file in the
    same version, that callers of one name and file at once share one lookup, found, missing or failed, and since R2
    that two files of a name are looked up and remembered apart; see "The Copilot review".

### Criterion 5: site 11
- `GlyfTableBoundsTest.aBoxTooShortForFourValuesIsLeftAsItWas`: `int[1]`, `int[2]` and `int[3]` sentinels are
  unchanged after the `ArrayIndexOutOfBoundsException`, where the same `glyf` with `int[4]` gives true and
  `{1, 2, 3, 4}`. `aNullBoxThrowsNullPointerException` pins the null box.
- `MacFontNullInputTest.aRectWithoutAnOriginOrASizeThrowsBeforeAnyDowncall`: a `CGRect` with a null `origin` or
  `size` throws `NullPointerException` with top frame `OS.rect`, in `CGRectApplyAffineTransform` and in
  `CGContextFillRect`, and the bitmap is unchanged, where a control fill changes it. No production line changed for
  it.

### Criterion 6: mutant proof
**The final pass** ran 29 rows on the finished tree after the touch-ups, from 2026-11-21 23:44 to 2026-11-22 00:08,
by an agent that had not written the code (`final-mutants/`).
- One row at a time: one hand edit, the same as its slice record's diff; the row's test class run; the edit undone
  by hand; `shasum -a 256 -c` of all 26 files of the tree OK after every undo. At the end the 10 production files
  were `cmp`-identical to the fixed copies and `git status` was unchanged.
- 27 rows were killed, each exit 1 with exactly 1 failure, 0 errors and 0 skipped, in the test of its site named
  above. None is a skip, an abort or a compile error. R3 and R5 survived, as the slice records predict.
- Logs `final-mutants/<row>.log` and `<row>.diff`. The three `hs_err` copies show SIGSEGV in `__CF_IS_OBJC` from
  `CFArrayGetCount`, `CFStringCompare` and `CFStringGetLength`. After the pass the four story classes ran 54 / 0 / 0 /
  0 on the restored tree (`final-mutants/restored-green.log`).
- Every slice had run its own mutants before, with the same kills: slice A twice, because its first pass kept no
  diffs and lost its `hs_err` files, and slice D again after its review round.

Classes: N = `MacFontNullInputTest`, R = `MacFontReleaseTest`, S = `MacFontBindingStructureTest`, G =
`GlyfTableBoundsTest`. 133 and 134 are the child's exit codes.

| row | site | the only production edit | test | failure (short) |
| --- | --- | --- | --- | --- |
| M1 | 1 | `sizeOf`: NULL guard deleted | N | child `null.systemFontSize` exited with 133 |
| M2a | 2 | `matchingDescriptors`: collection guard deleted | N | child `null.fontData` exited with 133 |
| M2b | 2 | `fontData`: count read without its NULL check | N | `null.fontData` 134, one `hs_err` file |
| M2b-release | 2 | `fontData`: `release(fonts)` made `CFNative.CFRelease(fonts)` | N | `null.fontData` 133 |
| M2c | 2 | `addUiFont`: NULL guard deleted | N | `null.fontData` 133 |
| M2d | 2 | `addOwnedDescriptor`: NULL guard deleted | N | `null.fontData` 133 |
| M3a | 3 | `DFontDecoder.matchingDescriptors`: collection guard deleted | N | child `null.dfont` exited with 133 |
| M3b | 3 | `fontNamed`: array guard deleted | N | `null.dfont` 133 |
| M3c | 3 | `fontNamed`: name guard deleted | N | `null.dfont` 134, one `hs_err` file |
| M3d | 3 | `releaseCTFont`: `font != 0` test dropped | N | `null.dfont` 133 |
| R3 | 3 | `createCTFont`: name string released unguarded | N | survived: exit 0, 18 / 0 / 0 / 0 |
| M7 | 7 | `cascadeList`: codes guard deleted | N | child `null.codes` exited with 133 |
| M4a | 4 | `copyOfUIFont`: NULL guard deleted | N | child `null.uiFontCopy` exited with 133 |
| M4b | 4 | `CTFontCreateWithName`: name-0 guard deleted | N | `null.fontName` 134, one `hs_err` file |
| M4c | 4 | `CTFontCreateWithName`: length test dropped | S | `… never calls … CFStringGetLength, which must come …` |
| M6 | 6 | `createContext`: 0 guard deleted | N | the four `CGContextSetAllows*` lines, a failure |
| M11a | 11 | `boundingBox`: stores in ascending order | G | `a box of 1 is left as it was … but was: <1>` |
| M11b | 11 | `OS.rect`: a null half read as zeros | N | NPE at `OS.copy`, expected at `OS.rect` |
| R5 | 5 | `getAdvanceFromPlatform`: 0 guard deleted | N | survived: exit 0, 18 / 0 / 0 / 0, `advance=0.0` |
| M8a | 8 | `layout`: fence deleted | S | `… never calls … reachabilityFence on local 3, the receiver …` |
| M8b | 8 | `layout`: fence moved before `createCTLine` | S | `… calls … reachabilityFence before … createCTLine` |
| M9 | 9 | `synchronized (CONTEXT_LOCK)` made a bare block | R | `expected: <1> but was: <2>`, `context.shared=false` |
| M12a | 12 | `createFontFile`: name not remembered | R | `… 5 times over 5 layouts … expected: <1> but was: <5>` |
| M12b | 12 | `getFontSlot`: copy moved above `mayFindFace` | S | the font copy is called before `mayFindFace` |
| M10a | 10 | `pooledCascadeList`: push and pop deleted | R | `leaves 229.72 autoreleased blocks per call …` |
| M10b | 10 | `getCascadeList`: a virtual caller runs it inline | R | `leaves 229.99 autoreleased blocks per call …` |
| M10c | 10 | `resultOf`: `interrupted = true` made a throw | R | `but was: <…IllegalStateException…>` (`failure`) |
| M10d | 10 | `resultOf`: the spin on `isDone()` deleted | R | `the caller left its wait while the task still ran` |
| X1 | 10 | `PrismFontFactory`: `ObjCNative.link()` deleted | S | `PrismFontFactory.<clinit> links libobjc after …` |

M10a fails only the platform test: the virtual one passes (0.043 blocks per call), because a worker thread's exit
drains what the thread's own pool still holds. Since the CI fix the class file pins the push and a pop on each exit of
`pooledCascadeList` on any OS, which M10a fails (below); that the worker's task runs `pooledCascadeList` rested on
review until the further fixes pinned it (S5, S8, the keep check and the virtual drain check, C-M4; item 11).

**After the test-only fixes** (`final-fix/`). The fixes changed six test and shim files and no production file:
the 10 production files were OK against the final pass's checksums before the rows, after each undo and at the end.
M6 and M10c, whose tests had changed, were run again, and M10e is new; each failed as a failure, not an abort. The
other 26 rows were not run again: the fixes changed none of the tests that fail for them.

| row | site | the only production edit | test | failure (short) |
| --- | --- | --- | --- | --- |
| M10e | 10 | `resultOf`: the restoring `interrupt()` deleted | R, 14 / 2 | new test `:358`, end to end `:333` |
| M10c | 10 | as above | R, 14 / 2 | new test `:350`, end to end `:331` |
| M6 | 6 | as above | N, 18 / 1 | `:313`, four setter lines beyond the control's |

- M10e: the new test fails with `the interrupt the wait cleared is set again ==> expected: <true> but was: <false>`
  after every earlier assertion passed; the end-to-end test with `the caller's interrupt status is still set`.
- M10c: the new test fails with `the caller was not seen parked in its wait for the running task`, the caller in
  state TERMINATED with `java.lang.IllegalStateException: java.lang.InterruptedException`; the end-to-end test as
  in the final pass. Before the fixes it was killed by the end-to-end `failure` assertion alone, and the restore of
  the interrupt had no row.
- M6: the subject's four setter lines, then the control's setter and fill-colour lines, in the log of pid 18432
  (`final-fix/M6.unified-log.txt`).
- Logs `final-fix/{M10e,M10c,M6}.log` and `.diff`; no row left an `hs_err` file. The three classes then ran 35 / 0 /
  0 / 0 on the restored tree (`final-fix/restored-green.log`).

**After CI run 45022158121** (the CI fix, on its final tree; `final/`). One hand edit per row, then R, S and
`coretext.MacObjCNativeTest` (O), 34 tests. The first eight rows had been run on the fix's earlier trees; they were
run again here from the same diffs, byte for byte, and M10j to M10m are new. After every undo `src/main` was
identical to `40e53db102` (`git diff --quiet`) and the nine test and shim files of the fix matched their checksums
(`final/final.sha256`). All 12 were killed as expected, with failures only: no error, no abort, no survivor.

| row | site | the only production edit | failed |
| --- | --- | --- | --- |
| M10a | 10 | as above | 3: R platform heap, S pool, S exits |
| M10b | 10 | as above | 2: R virtual heap, S hand-off |
| M10g | 10 | `ObjCNative.objc_autoreleasePoolPop` does nothing | 2: R platform heap, O pop |
| M12a | 12 | as above | 2: R fallback face, R face memory |
| M12f | 12 | `getFontSlot`: `mayFindFace` negated | 1: S guard; R fallback face skipped |
| M10h | 10 | `getCascadeList`: `isVirtual` negated | 1: S hand-off |
| M10i | 10 | `getCascadeList`: the platform branch without the pool | 2: R platform heap, S hand-off |
| M10f | 10 | `pooledCascadeList`: the `finally` made a `catch (Throwable)` that pops | 2: R platform heap, S exits |
| M10j | 10 | `pooledCascadeList`: as M10f, `catch`es of `RuntimeException` and `Error` | 2: R platform heap, S exits |
| M10k | 10 | `pooledCascadeList`: no `try`; push, read, pop, return | 1: S exits |
| M10l | 10 | `getCascadeList`: the hand-off's answer dropped, so the caller goes on | 1: S hand-off |
| M10m | 10 | `cascadeListOnAPlatformThread`: `task.run()` for `worker.start()` | 1: S thread |

- **R heap** is `REFUTED`, "a cascade list call leaves … autoreleased blocks per call": 230.38 (M10a), 230.08
  (M10b, virtual), 229.38 (M10g), 229.52 (M10i), 230.33 (M10f) and 229.45 (M10j). **R fallback face** under M12a:
  "… was looked up in its font file 5 times over 5 layouts, where once is enough"; **R face memory**: "a name its
  font file has no face of is remembered ==> expected: <false> but was: <true>".
- **S pool** (`theCascadeListIsReadInsideAnAutoreleasePool`), M10a: "… pooledCascadeList never calls …
  objc_autoreleasePoolPush". **S exits** (`thePoolOfTheCascadeListIsPoppedOnBothExits`), M10f and M10j: "… but the
  return at instruction 7 follows its call of … cascadeList at instruction 6 with no call of …
  objc_autoreleasePoolPop between them"; M10a the same at instructions 5 and 4, since javac drops the emptied
  `finally`; M10k: "… but no handler that catches everything covers its call of … cascadeList at instruction 6 and
  calls … objc_autoreleasePoolPop before it throws; its handlers are []".
- **S hand-off** (`aVirtualCallerHandsTheCascadeListToAPlatformThread`): "… getCascadeList no longer calls …
  cascadeListOnAPlatformThread" (M10b) or "… pooledCascadeList" (M10i), "so this order check no longer pins
  anything"; M10h: "… the first branch after its first call of java/lang/Thread isVirtual is ifne at instruction 12,
  not ifeq"; M10l: "… must return what its first call of … cascadeListOnAPlatformThread answers, …; but instruction
  15, after that call at instruction 14, is pop, not a return". **S thread**
  (`theHandOffStartsAPlatformThreadAndNeverRunsTheTaskItself`), M10m: "… must call none of java/lang/Thread run,
  java/util/concurrent/FutureTask run, java/lang/Runnable run, … pooledCascadeList, …; but it calls
  java/util/concurrent/FutureTask run at instructions [16]". **S guard**
  (`layoutAsksForAFallbackFaceBeforeCopyingTheRunFont`), M12f: the same of `mayFindFace`, "ifne at instruction 62".
  **O pop**, M10g: "popping the pool releases the reference autoreleased into it ==> expected: <1> but was: <2>".
- The virtual heap check passes M10a, M10g, M10f and M10j (0.053, 0.043, 0.243 and 0.037 blocks per call), because
  a worker thread's exit drains what its pool, or the thread's own, still holds. Under M10f and M10j
  `theCascadeListIsReadInsideAnAutoreleasePool` passes too, since a handler's pop follows the list.
- Under M10h, M10k, M10l and M10m all 15 tests of R pass here, the interrupt and wait tests included (virtual heap
  0.34, 0.03, 0.47 and 0.003; platform -0.003, 0.003, 0.03 and 0.003); only S sees them, on any OS. Under M12f
  layout looks no face up, so the fallback-face check finds nothing missing and is skipped, here as on a machine
  without a missing face (the child printed the 5 unsupported-name and 5 `slot=-1` lines and no lookup); only S
  fails, on any OS.
- Against the earlier trees, two outcomes differ: M10a also fails S exits, which was added after M10a was first
  run, and S exits now names the return without a pop, where under M10f it counted the pops ("at instructions [10],
  where it needs exactly 2 calls").

**The further fixes** (`impl2/<cluster>/`, `impl2/FT/` and `impl2/FT/fix-r1/`, each row's `.diff` and `.log` named
by its row). One hand edit per row, then the test classes it can reach, the edit undone by hand and the tree checked
against the cluster's checksums; a row run again later is recorded with its last run. A row is killed, by test
failures, unless it says otherwise. Classes as above, and I = `MacGlyphImageTest`, IR = `MacGlyphImageRulesTest`,
NV = `MacFontNullInputVerdictTest`, RV = `MacFontReleaseVerdictTest`, L = `CompositeFontLocksTest`, P =
`PrismFontFactoryTest`, SR = `FontSharedStateRulesTest`, CR = `CompositeSlotRaceTest` and MS =
`MacFontSharedStateTest`. S, IR, NV, RV, SR and CR run on any OS, and N, R, O, I and MS on macOS only; "any" and
"mac" mark which tests of L and P failed. The item column is the item under "The further fixes".

Cluster A (`CTGlyph`):

| row | item | the only edit | killed by |
| --- | --- | --- | --- |
| M1a | 1 | `getImage`: the cached branch's `synchronized (CONTEXT_LOCK)` made a plain block | I |
| M1b | 1 | `getImage`: the draw made after the locked block | I |
| M2a | 2 | `measureBounds` publishes an empty `CGRect` before the measured box | I |
| M2b | 2 | `volatile` dropped from `bounds` | IR |
| M2c | 2 | `measuredBox` stores `bounds` right after the advances | IR, I |
| M2d | 2 | `bounds` stored after the fill and the matrix, before the clamp | IR |
| M2e | 2 | `getPixelData` reads `bounds.origin.x` from the field | IR |
| M2f | 2 | `measureBounds` not `synchronized` | I |
| M2g | 2 | `measureBounds` without its catch | I |
| M2h | 2 | the catch stores the empty box and does not rethrow | I |
| M7 | 7 | `bitmapContext`: the return for colour space 0 deleted | I |
| M9a | 9 | the cached branch without `setStrikeOptions` | IR; I `REFUTED` |
| M9b | 9 | `setStrikeOptions` passes `true` for `isSubPixelGlyph()` | IR; I skips (`CANNOT_SHOW`) |
| M9lcd | 9 | `CTFontStrike.getAAMode` answers the mode asked for | I |
| M13 | 13 | `if (context != 0)` put back around the catch's release | IR |
| M13b | 13 | the catch's release deleted | IR |
| M9 | site 9 | US-111's, on the new `CTGlyph` | R |

M9a and M9b were run again after the sub-pixel check moved to `MacFontReleaseVerdict.decide` (M9a-t, M9b-t). M1a, M1b
and M9a were run again on the hardening round's tests (`impl2/LAST/M1a-last.log`, `M1b-last.log`, `M9a-last.log`):
M1a and M1b fail the hold assertion of I, and their first image differs from `again.first` too; M9a is `REFUTED`,
its subject equal to `other`.

Cluster D (NULL inputs and guards):

| row | item | the only edit | killed by |
| --- | --- | --- | --- |
| D1 | 6 | `getCTFontFormat`: the font-0 guard deleted | S |
| D2 | 6 | `getCTFontTags`: the same | S |
| D3 | 6 | `getCTFontTable`: the same | S |
| D4 | 6 | `getCTFontTags` answers `new int[0]` for font 0 | N |
| D5 | 6 | `getCTFontFormat`'s guard negated | S, N |
| Xa | 6 | `getCTFontTable`'s guard `font == 0 && tag != 0` | S |
| Xh-t | 6 | the guard inside a `try` whose catch swallows | S, the handler half |
| Xs-t | 6 | the guard inside a `switch` whose trailing `case 0` lands past it | S, the switch half |
| Xh-ctl-t | 6 | Xh-t, with the handler half of the dominance rule deleted from `FontCallOrder` | control: survives |
| Xs-ctl-t | 6 | Xs-t, with the switch half deleted | control: survives |
| D6 | 6 | `CFStringCreateWithCharacters`: the count guard deleted | N, child exit 133 |
| D7 | 6 | null characters counted as `Integer.MAX_VALUE` | N, child exit 134 |
| D8 | 6 | `numChars > length + 1` | N |
| D9 | 6 | negative counts let through | N, child exit 133 |
| D10 | 6 | `cascadeList`: the NULL-font guard deleted | S |
| D11 | 6 | the guard an early return before the `try`, so the codes leak | N |
| D12 | 6 | the guard negated | S, N |
| D13 | 7 | the bold-string guard deleted | S |
| D14 | 7 | the guard negated | S |
| Xe | 7 | the guard tests the name, not the bold string | S |
| D15 | 11 | site 4: `> 0` made `>= 0` | S, N |
| Xc | 11 | site 4: `(size < 0 \|\| length > 0) && …` | S |
| M4c | 11 | US-111's: the length test dropped | S, N |
| D16 | 11 | site 5: `fontRef == 0` made `fontRef < 0` | S |
| Xb | 11 | site 5: `fontRef == 0 && glyphCode != 0` | S |
| R5 | 11 | US-111's: site 5's guard deleted | S (it survived US-111's pass) |
| D17 | 11 | site 3: the release guard negated | S |
| D18 | 11 | site 3: `!= 0` made `>= 0` | S |
| Xd | 11 | site 3: `fontName.isEmpty() \|\| nameRef.address() != 0` | S |
| R3 | 11 | US-111's: the name string released unguarded | S (it survived US-111's pass) |
| F1 | 11 | `fontNamed`: the NULL-name return moved above the `try` | N |
| M6 | 11 | US-111's, on cluster A's `CTGlyph` | S, N |
| M6g | 11 | `getImage`: its return for context 0 deleted | S |
| Xf | 11 | `CGContextSetAllowsAntialiasing` moved before `createContext`'s 0 guard | S, N |
| Xg | 11 | `getColorImage` sets the options before its test of 0 | S |
| Ga | 11 | `getFontSlot`: `debugFonts \|\| mayFindFace(…)` | S, R |
| T1 | 12 | `contextVerdict`: no fill-colour line is `PROVED` | NV |
| T2 | 12 | `contextVerdict`: no setter-control line is `PROVED` | NV |
| T3 | 12 | `contextVerdict` never `REFUTED` | NV |
| L1 | 12 | the log check: an `assumeTrue` before `decide` | NV |
| L2 | 12 | the log check: `require(…, ParityGate.required())` for `decide` | does not compile |
| L2b | 12 | the log check: `require(…, Boolean.getBoolean(…))` for `decide` | NV |

Cluster C (the cascade-list call):

| row | item | the only edit | killed by |
| --- | --- | --- | --- |
| C-M1 | 4 | `startCascadeListWorker` starts a worker per call | R |
| C-M2a | 4 | `task.run()` on the caller before the hand-off | S, R |
| C-M2b | 4 | an inline `Executor` for `queueCascadeListTask` | S, R |
| C-M12 | 4 | the worker a virtual thread | S, R |
| C-M13 | 4 | `inheritInheritableThreadLocals(false)` removed | R |
| C-M14 | 4 | `.daemon()` removed | equivalent: a virtual caller's threads are daemons |
| C-M16 | 4 | the worker's loop without `Thread.interrupted()` | S, R |
| C-M17 | 4 | the loop returns on an interrupt | R |
| C-M18 | 4 | `synchronized (MacFontFinder.class)` around the read in `run` | S |
| C-M19 | 4 | a `PrismFontFactory.getFontSizeLimit()` call in `run` | S |
| C-M21 | 4 | the unpark deleted | S, R |
| C-M32 | 4 | `task.run()` first in `queueCascadeListTask` | S; R, child crashes and time-outs |
| C-M33 | 4 | the unpark only `if (size() == 1)` | S |
| C-M34 | 4 | the unpark in one arm of a `switch` | S |
| C-M37 | 4 | an add and a `return` before the unpark | S |
| C-M38 | 4 | the unpark in one arm of an `if` only | S |
| C-M5 | 5 | no `CFRetain` in the task's constructor | S, R |
| C-M6 | 5 | the task's reference never released | S, R |
| C-M7 | 5 | `done` set before the release | S |
| C-M20 | 5 | the release without a pool of its own | S |
| C-M23 | 5 | the release moved into the catch | S, R |
| C-M24 | 5 | the release's `finally` made a catch | S, R |
| C-M25 | 5 | the task's catch made a `finally` | S |
| C-M26 | 5 | `failure = t` deleted | S |
| C-M29 | 5 | a `RuntimeException` catch that returns before the release | S |
| C-M30 | 5 | a `RuntimeException` catch that rethrows | S |
| C-M8 | 5 | the interrupt not recorded | S |
| C-M9 | 5 | the interrupt not set again | S, R |
| C-M10 | 5 | a throw on an interrupt | S, R |
| C-M22 | 5 | `Thread.interrupted()` before the record | S |
| C-M28 | 5 | the restore only in a catch and before `return list` | S |
| C-M11 | 5 | the pool descriptors back to `ADDRESS` | O, R |
| C-M3 | 11 | a discarded `cascadeList` call in the virtual branch | S, R |
| C-M3b | 11 | the same in `cascadeListOnAPlatformThread` before the start | S, R |
| C-M4 | 11 | the task calls `cascadeList`, not `pooledCascadeList` | S, R |
| C-M15 | 11 | `CTFontCopyDefaultCascadeListForLanguages` called from a new helper | S |
| C-M15b | 11 | the same from `PrismFontUtils` | S |
| C-M27 | 11 | `pooledCascadeList` pops only in a catch | S, R |
| C-M31 | 11 | a `RuntimeException` handler that returns without a pop | S |
| C-T35 | 12 | an `assumeTrue` in `MacGlyphImageTest`'s sub-pixel check | IR |
| C-T36 | 12 | `require` for `decide` there | IR |
| T-sanity | 12 | the child's token probe pushes without popping | control: survives; RV pins the rule |
| M10a | site 10 | US-111's: `pooledCascadeList` without push and pop | S, R (both drain checks) |
| M10b | site 10 | US-111's | S, R |
| M10g | site 10 | US-111's: the pop does nothing | O, R (both drain checks) |
| M10h | site 10 | US-111's | S, R |
| M10k | site 10 | US-111's | S |
| M10l | site 10 | US-111's | S |
| M10d | site 10 | US-111's | retired with its test (deviation 13) |

C-M28 to C-M31 survived the release and keep checks before those read every exit; C-M37 and C-M38 survived S4
before it read every add. D's L1 was run again after the wiring pin moved into `MacFontNullInputVerdictTest`.

The rows that the lent-font check of R kills were run again once the hardening round had changed that check: by the
hardening round against the check alone (`impl2/LAST/<row>-last.log`; C-M11 and C-M21 as
`impl2/LAST/fix-r1/C-M11-fix-r1-last.log` and `C-M21-fix-r1-last.log`, control `C-M11-M21-control-last.log`,
`PROVED`), and US-111's M10b and M10h and C-M32 by the closing round against R and S, after a control of 50 / 0 / 0 /
0 with the lent font `PROVED` (`impl2/CLOSE/K2-control-close.log`, `M10b-close.log`, `M10h-close.log`,
`C-M32-close.log`):
- C-M6 and C-M23 are `REFUTED` by its verdict: the task kept 1 reference (0, 0 and 1).
- C-M1 and C-M2b fail its plain `parked` assertion; C-M5 and C-M2a end its child with exit 134.
- C-M11 and C-M21 end its child with exit 1 before any line the round changed: a `WrongMethodTypeException` at the
  first `getCascadeList`, and "a virtual caller did not get its list within 20 s" at the warm virtual call.
- US-111's M10b and M10h each fail 6 of the 50 tests, the same 6 as in their cluster C runs (`impl2/C/M10b.log`,
  `M10h.log`). The check fails at its plain `parked` assertion (`MacFontReleaseTest.java:599`, "the caller was not
  seen waiting behind the holding task"), before its verdict is built, and prints no line. S hand-off fails
  (`aVirtualCallerHandsTheCascadeListToAPlatformThread`), and the virtual drain check is `REFUTED` ("a virtual caller
  did not wait for the platform thread to make its list"); `aVirtualCallerWaitsForThePlatformThreadToMakeItsList` (8
  callers got a list while the thread was held, where M10b's cluster C run counted 1),
  `anInterruptTheWaitClearsDoesNotEndItAndIsSetAgainOnceTheTaskAnswers` and `virtualCallersAtOnceShareOnePlatformThread`
  fail too. The platform drain check stays `PROVED`.
- C-M32 fails 9, the same 9 as in its cluster C runs (`impl2/C/C-M32-r5.log`, `C-M32-t.log`). The check's child exits
  with 134 at `MacFontReleaseTest.java:595`, before any assertion and before its verdict; its `hs_err` file, read
  right after the run and since cleared by a later run, shows a SIGSEGV in CoreText's `TFont::CreateDefaultCascadeList`
  under the child's first baseline. S4 fails, two more children exit with 134 and five do not finish within 60 s.
  That the crash follows from the child's warm virtual call, whose task the edit ran twice, inline and on the worker,
  releasing its one reference to the font twice, is a trace, not a log finding.

Cluster B (font maps and fallback slots):

| row | item | the only edit | killed by |
| --- | --- | --- | --- |
| B-M1 | 3 | `storeInMap` not `synchronized` | L any |
| B-M2 | 3 | `getDefaultFontResource` not `synchronized` | L any |
| B-M3 | 3 | `storeInMap` unsynchronized, its put under the map's monitor | L any |
| B-M4 | 3 | `populateFontFileNameMapGeneric` not `synchronized` | L any |
| B-M5 | 3 | the shutdown hook looks a resource up | L any, SR |
| B-M29 | 3 | `findFontIndex` makes a composite resource | L any |
| B-M30 | 3 | a nested class reads `fileNameToFontResourceMap` | L any |
| B-M31 | 3 | a nested class calls `storeInMap` | L any |
| B-M6 | 3 | `LogicalFont.addNativeFallback` not `synchronized` | L mac |
| B-M7 | 3 | its full-name check back in `getSlotForFont` | L mac |
| B-M8 | 3 | it returns `ns + 1` | L mac |
| B-M9 | 3 | `LogicalFont.getSlotForFont` `synchronized` | L mac |
| B-M10 | 3 | `LogicalFont.getSlotResource` `synchronized` | L any, mac |
| B-M15 | 3 | `FallbackResource.addNativeFallback` not `synchronized` | L mac |
| B-M16 | 3 | it skips the full-name check under the lock | L mac |
| B-M17 | 3 | `FallbackResource.getNumSlots` `synchronized` | L mac |
| B-M18 | 3 | `FallbackResource.fallbacks` not volatile | L any |
| B-M11 | 3 | `getSlot0Resource` as at `f352c6008c` | L any |
| B-M12 | 3 | `getSlot0Resource` returns a second read | L any |
| B-M13 | 3 | `getSlotResource`'s fill as at `f352c6008c` | L any |
| B-M14 | 3 | `getSlotResource` returns `fallbacks[slot]` | L any |
| B-M32 | 3 | an early return on a fill, a second read otherwise | L any |
| B-M33 | 3 | a return inside the fill, `fallbacks[slot]` on a hit | L any |
| B-M34 | 3 | slot 0 answered from a second read | L any |
| B-M35 | 3 | the hit path reads again through a second local | L any |
| B-M36 | 3 | `slot0FontResource` read in a loop | L any |
| B-M20 | 4 | `LogicalFont.getLinkedFonts` without a lock | L any, mac |
| B-M21 | 4 | `FallbackResource.getLinkedFonts` without a lock | L any, mac |
| B-M22 | 4 | `FallbackResource.getLinkedFonts` without the inner check | L any, mac |
| B-M23 | 4 | `LogicalFont.getLinkedFonts` without the inner check | L any |
| B-M24 | 4 | `LogicalFont.getLinkedFonts` a `synchronized` method | L any, mac |
| B-M25 | 4 | the same in `FallbackResource` | L any, mac |
| B-M26 | 4 | `LogicalFont` stores `fallbacks` before the names and files | L any |
| B-M27 | 4 | the same in `FallbackResource` | L any |
| B-M28 | 4 | `LogicalFont.getLinkedFonts` always locks | L any, mac |
| B-M19 | 8 | `FallbackResource.addNativeFallback` returns `ns + 1` | L mac |

B-M30 survived the monitor check before it read the nest, and B-M32 to B-M36 the read-once check before it counted
reads. B-M11 to B-M14 and B-M32 to B-M36 were rebuilt on cluster F's `LogicalFont`, and 24 rows were run again on
the final tree of cluster F, each killed as before.

Cluster E (layout output and face lookup). Its M10a to M10f are item 10's, not US-111's site 10 rows of those names:

| row | item | the only edit | killed by |
| --- | --- | --- | --- |
| M8a1 | 8 | the run's glyph IDs kept | R |
| M8a2 | 8 | the run filled with `INVISIBLE_GLYPH_ID` | R |
| M8a3 | 8 | the fill starts at `runStart + 1` | R |
| M8a4 | 8 | the fill over the whole array | R |
| M8a5 | 8 | `INVISIBLE_GLYPH_ID` entries filled too | R |
| M8a6 | 8 | `getFontSlot` handed 0 for the line's font | S |
| M8a7 | 8 | `fontRef = 0` after the line is made | S |
| M8a8 | 8 | the line handed for the line's font | S |
| M8a9 | 8 | the line's font handed only for a plain strike | S |
| M8g1 | 8 | `ownSlot = -1` | R |
| M8g2 | 8 | the address guard reversed | R |
| M8g3 | 8 | the guard an early return above the name lookup | R |
| M8g4 | 8 | `ownSlot` 0 for the strike's font | R |
| M8g5 | 8 | a composite's -1 not given `ownSlot` | R |
| M8g6 | 8 | `fr == null` returns -1 | R |
| M8g7 | 8 | `fontName == null` returns -1 | survives (deviation 20) |
| MV4 | 8 | the verdict classifies runs by name only | RV |
| MV5 | 8 | the identity precondition dropped | RV |
| MV6 | 8 | 0xFFFF no longer expected to stay | RV, R |
| M8b1 | 8 | `getSlotForFont` back to `+ 1` | P any, mac |
| M8b2 | 8 | the `addSlotFont` override removed | P any, mac |
| M8b3 | 8 | `slotAfterThePrimary` always `+ 1` | P any, mac |
| M8b4 | 8 | `fallbackSlot <= 0 ? -1 : …` | P any |
| M8c1 | 8 | `FallbackResource.addNativeFallback` returns `ns + 1` | P mac, R, L mac |
| M8c2 | 8 | it returns `ns - 1` | P mac, R, L mac |
| M10a | 10 | `findFontIndex`'s catch-all back | P any, mac; R |
| M10b | 10 | `createFontFile` catches the throw and remembers the name | R |
| M10c | 10 | the branch for a font without a file dropped | R |
| M10d | 10 | a font without a file not remembered | R |
| M10f | 10 | `findFontIndex` throws only `FileNotFoundException` | P any, mac |
| M12a | 10 | US-111's (E's M10e): the name not remembered | R |
| M12b | 11 | US-111's: the copy above `mayFindFace` | S |
| M12f | 11 | US-111's: `mayFindFace` negated | S, R |
| M12g | 11 | asks, copies anyway, uses the answer only for `addFallbackFont` | S only (deviation 20) |
| M12h | 11 | `mayFindFace(fontName) \| true` | R |
| M12i | 11 | `mayFindFace` asked for the wrong name | R |
| MV1 | 12 | `lookedUpOnce` ignores an unmet precondition | RV |
| MV2 | 12 | `lookedUpOnce` takes `< 1` for `!= 1` | RV |
| MV3 | 12 | `checked` weighs the problems before the preconditions | RV |

M8a6 survived every test, 128 / 0, before `layoutHandsTheSlotLookupTheFontItsLineWasMadeWith` existed. M8g1, M10c,
M8b1 and M10a were run again after the final tidy changed the names no font has, killed as before.

Cluster F (the font layer's other shared state; all under item "Beside items 1 to 4"):

| row | the only edit | killed by |
| --- | --- | --- |
| M1a | `PrismFontFile.bbCache` a `HashMap` | SR |
| M1b | `putIfAbsent` made `put` | SR, MS |
| M1c | the box another thread stored first ignored | MS |
| M1d | the miss made `computeIfAbsent` | SR, MS |
| M1e | `final` dropped from `bbCache` | SR |
| M1f | `getGlyphBoundingBox` `synchronized` | SR, MS |
| M2a | `PrismFontStrike.glyphMap` a `HashMap` | SR |
| M2b | its `putIfAbsent` made `put` | SR, MS |
| M2c | `getGlyph(int)` made `computeIfAbsent` | SR, MS |
| M2d | the glyph another thread stored first ignored | MS |
| M2e | `final` dropped from `glyphMap` | SR |
| M2f | `getGlyph(int)` `synchronized` | SR, MS |
| M3a | `CompositeGlyphMapper.getSlotMapper` as at `f352c6008c` | SR, CR |
| M3b | `volatile` dropped from `slotMappers` | SR |
| M3c | a ternary copy that writes in place when the slot fits | SR |
| M3d | the mapper read as `slotMappers[slot]` | SR |
| M3e | `glyphMap` a `HashMap` | SR |
| M3f | `volatile` dropped from `charToGlyph` | SR |
| M3g | `getAsciiGlyphCode` returns `charToGlyph[index]` | SR |
| M3h | an `if`-guarded copy | SR |
| M3i, M3k, M3l | `getGlyphCode`, `getSlotMapper`, `convertToGlyph` `synchronized` | SR, CR |
| M3j | `getAsciiGlyphCode` `synchronized` | SR |
| M3m | `slotMappers` stored before the slot is filled | SR |
| M4a | `CompositeStrike.getStrikeSlot`'s slot path as at `f352c6008c` | SR, CR |
| M4b, M4c | `volatile` dropped from `strikeSlots`, `slot0Strike` | SR |
| M4d | written in place when the slot fits | SR |
| M4e | `clearDesc` as at `f352c6008c` | SR |
| M4f | the slot-0 branch returns `slot0Strike` | SR |
| M4g | an `if`-guarded copy | SR |
| M4h | `getStrikeSlot` `synchronized` | SR, CR |
| M4i | `getGlyph(int)` `synchronized` | SR |
| M4j | the copy published before it is filled | SR |
| M5a | `volatile` dropped from `LogicalFont.mapper` | SR |
| M5b | the same in `FallbackResource` | SR |
| M5c | the same in `PrismCompositeFontResource` | SR |
| M5d | the same in `PrismFontFile` | SR |
| M5e | `LogicalFont.getGlyphMapper` returns `mapper` | SR |
| M5f | `PrismFontFile.getGlyphMapper` as at `f352c6008c` | SR |
| M6a | `synchronized` dropped from `getFontFullNames(String)` | SR, MS |
| M6b, M6c | `synchronized` dropped from `getFontFamilyNames`, `getFontFullNames()` | SR |
| M6d | `synchronized` dropped from `disposeFontsOnShutdown` | SR |
| M6e | the hook back to a lambda that walks `embeddedFonts` | SR |
| M6f | an unsynchronized method assigns `fontResourceMap` | L any |
| M6g | an unsynchronized method stores `allFontNames` | SR |
| M6h | a constructor stores `allFontNames` | SR |
| M7a, M7b | `volatile` dropped from the `metrics` of `PrismFontStrike`, `CompositeStrike` | SR |
| M7c, M7d | `volatile` dropped from `PrismFontFile.styleMetrics`, `PrismMetrics.styleMetrics` | SR |
| M7e | `getStyleMetrics` scales from `styleMetrics[i]` | SR |
| M7f | `PrismFontStrike.getMetrics` returns `metrics` | SR |
| M7g | `styleMetrics` stored right after `new float[]` | SR, since FT1; it survived cluster F |
| F8a | `LogicalFont.getSlotResource` fills `fallbacks` in place | L any |
| F8b | `getAdvance` reads `fallbacks[slot - 1]` | L any |
| F8c | `volatile` dropped from `slot0FontResource` | L any |
| F8d | `getSlotResource` returns `nativeFallbacks[slot]` | L any |
| F8e | `LogicalFont.getNumSlots` reads `nativeFallbacks` twice | SR |
| F8f | `FallbackResource.getSlotForFontNoCreate` reads it twice | SR |
| F8g | `getNumSlots` stores a clone of `linkedFontNames` | L any |
| F8h | the copy of `fallbacks` published before it is filled | L any |
| F8i | a `synchronized (this)` block around the fill | L any |
| F8j | `getSlot0Resource` `synchronized` | L any, mac |
| F8k | `getNumSlots` publishes a filled copy of `fallbacks` | L any |

M3m, M4j and F8h survived the copy-on-write check before it required the fill first; F8i, M6h and F8k survived
until the checks of F's last round; B-M10 and F8j were killed only on macOS until then.

The final tidy (FT1 to FT3) and the fix round after the whole-tree review (item F: beside items 1 to 4):

| row | item | the only edit | killed by |
| --- | --- | --- | --- |
| M7g2 | F | `styleMetrics` stored after the strikethrough block, before the rest of the fill | SR |
| M7g3 | F | an element of `styleMetrics` written after its publication | SR |
| M3n | F | `charToGlyph` stored right after `new char[]` | SR |
| M3n2 | F | `charToGlyph` stored inside the fill loop | SR |
| M7h | F | `PrismMetrics` stores a new array and copies into it | SR |
| F8l | F | `LogicalFont.getNumSlots` writes `fallbacks[0]` | L any |
| F8m | F | `getLinkedFonts` writes `fallbacks[0] = fallbacks[0]` | L any |
| M4k | F | `clearDesc` writes `slots[i] = slots[i]` | SR |
| C-M39 | 4 | `queueCascadeListTask` offers, and returns, while the queue is not empty | S |
| C-M40 | 4 | a second method adds a task without the unpark | S |
| MNF1 | 3 | `LogicalFont.addNativeFallback` publishes before it fills | L any |
| MNF2 | 3 | the same in `FallbackResource` | L any |
| MNF3 | 3 | `LogicalFont.getSlotForFontNoCreate` writes an element | L any, mac |
| MNF4 | 3 | `FallbackResource.getSlotForFontNoCreate` writes an element | L any, mac |
| MNF5 | 3 | `FallbackResource.getNumSlots` writes an element | L any, mac |

M7g, M7g2, M7g3, M3n, M7h, F8l, F8m, M4k and C-M39 survived on the tree before the final tidy (F8l and F8m the
macOS tests of L too); M3n2 and C-M40 had no such run. MNF1 and MNF2 survived the whole font package, 541 / 0, before
the fix round, and MNF3 to MNF5 were killed there only on macOS: by L's macOS tests, and also by `MacFontStressTest`
(MNF3), P's macOS test (MNF4 and MNF5) and R (MNF4); the rows give their last runs, of L and SR in the fix round.

The hardening round (rulings L1 to L5) and its fix round (`impl2/LAST/`, `impl2/LAST/fix-r1/`). LAST-L1T and FR1-A to
FR1-C edit test code, the instrument:

| row | item | the only edit | killed by |
| --- | --- | --- | --- |
| LAST-L4a | 3 | `LogicalFont.addNativeFallback`: `nativeFallbacks = null;` right after the publication | L any, mac |
| LAST-L4b | 3 | the same in `FallbackResource.addNativeFallback` | L any, mac |
| LAST-L1T | 12 | the lent-font check asserts `PROVED` where it called `MacFontReleaseVerdict.decide` | RV |
| FR1-A | 12 | `lentFontReleased`: `kept = afterRun`, the baseline not subtracted | RV |
| FR1-B | 12 | `lentFontReleased`: `CANNOT_SHOW` where the first baseline is not 0 | RV |
| FR1-C | 12 | `lentFontReleased`: `CANNOT_SHOW` only where the first baseline is above the second | RV |

- LAST-L4a and LAST-L4b fail `noCompositeDropsTheFontsItAdded` at instruction 57 on any OS, and 3 and 5 of L's macOS
  tests; `eachCompositePublishesTheFontsItAddsOnlyOnceFilled` passes under both, the gap the new pin closes. L ran
  20 / 0 / 0 / 0 on the unchanged tree before each (`L4-control1-last.log`, `L4-control2-last.log`). Logs
  `L4a-last.log`, `L4b-last.log` and `L1-T-last.log`.
- FR1-A to FR1-C each pass the rule tests as they were before the fix round and the run-time lent-font check
  (`PROVED`, 0, 0 and 0). FR1-A is killed by the two tests of baselines settled at 1, FR1-B by those two and by
  baselines 0 then 1, FR1-C by baselines 0 then 1 alone. RV and the lent-font check ran 60 / 0 / 0 / 0 on the
  restored tree (`fix-r1/FR1-control-last.log`). Logs `fix-r1/FR1-A-last.log`, `FR1-B-last.log` and `FR1-C-last.log`.

The closing round (rulings K0 to K3; `impl2/CLOSE/k3/`). K3-M1 to K3-M3 edit test code, the instrument's messages:

| row | item | the only edit | killed by |
| --- | --- | --- | --- |
| K3-M1 | 12 | `lentFontReleased`: the `PROVED` message back to "its list moved the font's retain count by" | RV |
| K3-M2 | 12 | the same for the `REFUTED` message | RV |
| K3-M3 | 12 | the `REFUTED` message back to the old wording for an over-release only | RV |

- Each fails 2 of the 82 tests of RV and R (`K3-M1-close.log`, `K3-M2-close.log`, `K3-M3-close.log`): K3-M1 the two
  `PROVED` rule tests, K3-M2 the two tests of a kept reference at their kept case, and K3-M3 the same two tests at
  their over-release case, which K3-M2 does not reach; so each of the six asserted strings pins the new text. The
  run-time lent-font check passes under all three: it decides on the outcome, not the text. RV and R ran 82 / 0 / 0
  / 0 on the tree without a mutant, without and with `-Djfx.parity.require=true` (`touched-k3.log`,
  `touched-require-k3.log`).

**Totals of the further fixes.** 256 mutants: A 16, D 36, C 41, B 36, E 36, F 67, the final tidy 10, the fix round 5,
the hardening round 6 and the closing round 3. 253 were killed, each on this Mac (macOS 27.2.1 arm64), on the tree of
its cluster or, for a row run again, of the later step that ran it (named under the tables); that each is still
killed on the final tree rests on the argument under "What ran where". L2 does not compile, C-M14 is equivalent, and
M8g7 survives, unpinnable (deviation 20). The hardening round ran 11 of the rows again, each killed as before: C-M1,
C-M2a, C-M2b, C-M5, C-M6, C-M11, C-M21, C-M23, M1a, M1b and M9a; the closing round ran C-M32 again, killed as before.
The Copilot review's 25 rows are under "The Copilot review"; all were killed. Besides them, 14 of US-111's rows
were run again in the clusters, on this Mac, and were killed, R3 and R5 for the
first time: M9, R3, R5, M4c, M6, M10a, M10b, M10g, M10h, M10k, M10l, M12a, M12b and M12f; the closing round ran M10b
and M10h again, killed as before. M10d is retired with the test it proved; M10c, M10e and M10m edit code that cluster
C replaced, where C-M10, C-M9 and C-M2a make the same edits. US-111's other rows were not run again. Xh-ctl-t,
Xs-ctl-t and T-sanity are controls that survive by design.

### The `MacFontBindings` pins
The original change moved none (the Copilot review's F5 moved one, at the end of this section). `MacFontBindings.java`
had no diff, and `requireSameBinding` passed in all five golden classes in every
gate, before their machine check ("native methods left: OS 0, MacFontFinder 0, DFontDecoder 0").
- The `OS` call sites of the peers are those of HEAD in `javap -c -p`: `CTFactory` `OS.CFRelease*2` and
  `OS.CTFontCopyURLAttribute`, `CTGlyphLayout` `OS.CFRelease*10` and one `OS.CTFontCreateCopyWithAttributes`,
  `CTGlyph` `OS.CGContextRelease*5`. The guards of sites 5 and 6 and site 8's `finally` hold no `OS` call, and no
  `return` went into a `try` whose `finally` holds one.
- The new helpers have new names, none overloaded, each called by its own class, the reflective shim names
  included. `MacFontFinderShim`, `DFontDecoderShim` and `OSShim` reference no facade. `ObjCNative` is called only by
  `MacFontFinder` and `PrismFontFactory`, so it is no delegate owner, and `ObjCNativeShim` is not a witness shim.

What changed in `MacFontBindingStructureTest` (any OS):
- `FACADES` is `[CF, CG, CT, ObjC]`, so the restricted-call, linkage-toolkit and dependency rules cover
  `ObjCNative`.
- `theFontFactoryInitialiserLinksTheBindings` also requires `ObjCNative link()V` after `CTNative link()V` (X1).
- Three new tests, on `FontCallOrder`: the order checks of sites 4 and 12 and the receiver check of site 8.
- The CI fix: four more tests, of site 10's pool, its exits, the virtual hand-off and the hand-off's thread, and
  the guard check in site 12's test, on `assertFirstCallBefore` and the new `assertGuards`,
  `assertReleasedOnEveryExit`, `assertReturnsFirstCallOf` and `assertNeverCalls`. It moved no pin:
  `MacFontBindings.java` has no diff, and `MacFontBindingsTest` and the witness of each golden class passed in its
  module run.
- Unchanged: the 75 functions and 11 data symbols of the three frameworks, and
  `everyFrameworkSymbolIsBoundOnceInTheFacadeItsPrefixNames` (libobjc is no framework).
- The further fixes: 12 more tests (see "The further fixes"), and S4,
  `theHandOffQueuesATaskForThePlatformThreadAndNeverRunsItItself`, in place of
  `theHandOffStartsAPlatformThreadAndNeverRunsTheTaskItself`. `FACADES` is unchanged.

The further fixes moved no pin either: `MacFontBindings.java` has no diff against `f352c6008c`, `CTGlyph` still has
5 call sites of `OS.CGContextRelease`, `CTGlyphLayout` still calls `OS.CTRunGetGlyphs` twice and `CTFontFile`'s call
sites did not move, and `MacFontBindingsTest` and every other class of the font package passed on the final tree
(see "Measured").

The Copilot review's F5 moves one pin, with the reason in the comment of `NATIVE_CALL_SITES`: the call site of
`OS.CTFontCopyURLAttribute` goes from `CTFactory` (`createFontFile`) to `CTGlyphLayout` (`getFontSlot`), which copies
the run font's URL before it asks for a remembered miss and before any copy of the font. `CTFactory` pins
`OS.CFRelease*2` alone and `CTGlyphLayout` gains `OS.CTFontCopyURLAttribute`; every other entry is unchanged, and so
is the binding: both classes call the same `OS` method the shims bind. Before the move the five golden classes failed
at the witness, "a call site moved to another binding", and after it they pass it in both font-package runs and both
gates of F2 to F6.

### Criteria 7 to 9
- **7, goldens.** `git diff --stat` over the five `mac-*-golden.txt` files and over `src/test/resources` prints
  nothing: no row changed. The goldens skip on this Mac, and the corpus dumps, identical before and on the final
  tree, stand in for them (see "Measured"). A comparison on the golden machine remains for the owner. Since the
  further fixes `mac-layout-golden.txt` has ten rows edited by hand, in the glyph field only, and 8 header lines that
  name them (deviation 16); no other golden file changed. Over the dumps taken before and after each cluster,
  exactly those ten rows moved (see "Measured"). Since 2026-11-24 `directwrite-metrics-golden.txt` is captured again
  on its Windows machine, exactly its 609 rows of 8c moving ("Windows and Linux").
- **8, facades.** `CFNative`, `CGNative` and `CTNative` have no diff; no fix needed a descriptor change. A fourth
  facade, `ObjCNative`, was added beside them, with its descriptor table in `MacObjCNativeTest` (see "Deviations
  and stated properties", 7). Since the further fixes `CFNative` differs from `f352c6008c` in one modifier, `CFRetain`
  made public (deviation 22), and `ObjCNative`'s two descriptors take and answer `JAVA_LONG` (deviation 12), with
  `MacObjCNativeTest`'s table; `CGNative` and `CTNative` have no diff.
- **9, code rules.** `wc -l` on `40e53db102` (`abe0943d31`): `MacFontFinder` 385 (259), `DFontDecoder` 230 (206),
  `OS` 483 (476), `GlyfTableBounds` 124 (118), `CTFactory` 178 (166), `CTFontFile` 356 (355), `CTGlyph` 324 (320),
  `CTGlyphLayout` 202 (192), `ObjCNative` 109 (new). `PrismFontFactory` went from 1,891 to 1,893; it was over 900
  before this story, so the rule does not apply to it.
  - In the 25 files no line is over 120 columns except `OS.java` line 52 (154 columns, older than this story and
    untouched). No tab, CR, trailing whitespace, `var`, wildcard import or executable bit; every file ends in LF;
    the headers of modified files end in 2026 and new files carry 2026. The 9 test and shim files of the CI fix
    hold the same, with no line over 120 columns.
  - The other rules rest on the reviews: the features used are final in JDK 27 (flexible constructor bodies,
    `java.lang.classfile`, `Thread.join(Duration)`); no line holds two statements (the one-line `if`s of sites 5 and
    6 match their neighbours); the new production comments are ownership javadoc and why-comments, with no story
    number.
  - The further fixes, `wc -l` now (`f352c6008c`): `CompositeGlyphMapper` 163 (158), `CompositeStrike` 230 (225),
    `DFontDecoder` 239 (230), `FallbackResource` 392 (382), `LogicalFont` 585 (569), `MacFontFinder` 456 (385),
    `PrismCompositeFontResource` 248 (236), `PrismFontStrike` 268 (261), `PrismMetrics` 145 (144), `CFNative` 418
    (418), `CTFactory` 180 (178), `CTGlyph` 355 (324), `CTGlyphLayout` 215 (202), `OS` 491 (483), `ObjCNative` 111
    (109). `PrismFontFactory` 1,891 (1,893) and `PrismFontFile` 1,507 (1,502) were over 900 before this story.
  - In the 48 files of the further fixes no line they add is over 120 columns outside the golden, whose 841 long
    lines are those of `f352c6008c`; `OS.java` line 52 is still the one longer line, untouched. No tab, CR or
    trailing whitespace; the seven modified files whose header carried an older year now end in 2026, and the 11 new
    files carry their sibling's header with 2026 (checked on the tree on 2026-11-23; `git diff --check` clean).
  - The Copilot review: `MacFontFinder` 492 lines (456 at `e1de3dd32a`). In the 6 files it changes no line is over
    120 columns and none holds a tab or trailing whitespace (`git diff --check` clean); their headers already end in
    2026. The two empty catches name their throwable `_` (JEP 456, final since JDK 22), each with a why-comment.
  - F5 and F6: `CTFactory` 267 lines (180 at `e1de3dd32a`), `CTGlyphLayout` 250 (215); after R1 to R4 294 and 251,
    with the same rules checked on the 13 files again. In the 10 files of code both
    rounds change and the 3 new ones (`CTFactoryShim`, `MacFontFaceLookupChild`, `MacFontFaceLookupTest`) no line is
    over 120 columns and none holds a tab or trailing whitespace; the new files carry the 2026 header, the others
    already ended in 2026. No `var`. The two catches of `CTFactory` that keep nothing name their throwable `_`.

### Rests on review
No test covers these paths in full; each was checked by reading, in a review. The list is what is open on the final
tree of the further fixes (2026-11-23): an entry that a later step pinned is gone from it, and the pin is named under
"The further fixes" (site 3's guarded release and its NULL name in `fontNamed`, site 5's guard, the cascade-list
worker's task, hand-off and branch, the wait's own errors, the boxed pool token, `MacGlyphImageTest`'s wiring, the
bounds input of `CTGlyph`, the publication of `LogicalFont`'s slot fonts, `getStyleMetrics`' fill, the arrays
written in place, the lent-font check's wiring, and a null stored over the fonts a composite added).

**US-111's sites.**
- **Sites 1, 2, 3, 4 and 7.** The entry points hand CoreText's answer straight to the helpers. The tests hand NULL to
  the helpers, and the witness proves each helper is the method production calls; that nothing sits in between is
  read. `addCascadeEntry` and the `fontData` loop pass array elements on unchecked: a CF-typed array cannot hold NULL.
- **Site 4.** Both callers allocate the transform before the UI font exists, so an `OutOfMemoryError` there leaks
  nothing; it is not provoked.
- **Site 8.** That the fence is enough at run time: the strike can only be lost inside `layout` in a JIT-compiled
  frame during a Disposer race, and the check pins only where the fence is and what it fences. Also read, with no
  change: `CTGlyph.getImage` and `getColorImage` keep the strike through `this` under the glyph monitor, and
  `CTFontFile.getFontRef` relies on its callers holding the file.
- **Site 9 and items 1 and 2, the lock order.** The glyph monitor, then `CONTEXT_LOCK`, entered again inside
  `getCachedContext`; or the glyph monitor, then the font file's monitor or `PrismFontFactory.class` inside
  `measureBounds`. Nothing reached under `CONTEXT_LOCK` takes another monitor (`isSubPixelGlyph` reads a static final
  and a field; `OS`, the facades and `CTFontStrike` hold no `synchronized`), nothing that holds those monitors takes
  a glyph monitor, and none of it waits for the render lock or runs on the cascade-list thread. The tests prove one
  creation and one image per draw, not freedom from deadlock.
- **Site 10, the cascade-list thread** (items 4, 5 and 11).
  - The class-file exit checks (`thePoolOfTheCascadeListIsPoppedOnBothExits`, S7, S8 and the keep check) read the
    returns and throws reached from the entry and the handlers that cover the region's first call; an exception that
    a call throws outside those handlers is not an exit they read. On the healthy tree such calls are the releases
    themselves (`pooledCascadeList`'s pop, `awaitList`'s `Thread.interrupt`) and, in `CascadeListTask.run`, the
    unpark. Since the Copilot review (F2) what the push, `CFRelease` or pop of the task's own pool throws is kept
    for the caller, who is woken; that the unpark, and the entry of `run` and of `releaseFont`, throw nothing on the
    worker rests on reading: its stack is fixed and shallow, the long token allocates nothing, and
    `VirtualThread.unpark` retries an `OutOfMemoryError` itself. If the unpark did throw, its caller would park for
    ever; the worker would not end.
  - Since the Copilot review a heap segment for a font makes the read and the release throw at run time
    (`aTaskWhoseReleaseThrowsStillWakesItsCallerWithTheFailure`), which shows the read's failure kept, the release
    made and the caller woken; that the pool is popped and the interrupt set again before the rethrow on that path is
    still pinned only in the class file.
  - A `StackOverflowError` at the entry of `Thread.interrupt()` in `awaitList`'s `finally`, or of
    `objc_autoreleasePoolPop` in `pooledCascadeList`'s `finally` on a platform caller, is inherent to the JVM and is
    not provoked. That nothing is allocated between the native push and the `try` that pops it rests on the
    `JAVA_LONG` descriptor, which `MacObjCNativeTest` pins; no `OutOfMemoryError` is provoked there.
  - Since the Copilot review (F4) `queueCascadeListTask` retains the font right before the add, and a handler over
    the add releases it and rethrows; a refused add is provoked with a null task, an add that runs out of memory is
    not. Two windows stay, both leaks of one reference to the CTFont and never a use after free: an
    `OutOfMemoryError` while `CFNative.CFRetain` boxes its answer, after the native retain (its descriptor answers an
    `ADDRESS`, and a handler over it could not tell that failure from one before the retain, whose release would free
    a font still in use), and a `StackOverflowError` at the add's call, whose handler's release then most likely
    overflows too and replaces the error. A failure at the entry of the unpark is a lost wake-up, which the next add's
    unpark repairs; the task keeps its reference.
  - The worker never ends: since the Copilot review (F2) its loop catches everything a task throws, pinned in the
    class file and at run time; that poll, park and `Thread.interrupted` throw nothing rests on reading. No run-time
    test sees a wake-up made conditional: C-M33, C-M34, C-M37 and C-M38 pass `MacFontReleaseTest`, so S4 is its only
    pin. The hand-off's memory visibility rests on the Java Memory Model: an add to the `ConcurrentLinkedQueue`
    happens before the poll that sees it, and `done` is volatile and read before the list and the failure.
  - S4 and S5 match calls by owner and name: a queue insertion through another static type (`Queue.offer` or
    `Collection.add` on an upcast reference), and a read of the cascade list through reflection, a method handle or a
    second facade method over the same handle, are not seen.
  - `theCascadeListThreadTakesNoFontLayerMonitor` reads the worker's own methods, not the facade methods they call
    (`CFNative`, `CTNative`, `ObjCNative`); that those take no monitor rests on `javap` (no `monitorenter`, no
    `synchronized`). A caller may hold a composite's monitor across the hand-off (item 4); the worker takes no
    font-layer monitor and needs no class a caller could be initialising, so it cannot deadlock with it: the monitors
    are pinned, the class initialisation rests on reading.
  - C-M14 is equivalent and `hold.daemon` cannot fail: the worker's production starter is always a virtual thread,
    whose threads are daemons. Since the Copilot review (F3) a failed start of the thread reaches the virtual caller
    and leaves no thread recorded; no test can make the start fail, and only the class file pins it.
  - The language-codes copy sits inside the pool; the class file pins the push before it, and no mutant moved it.
    Whether the last `CFRelease` on the error path autoreleases anything is not observed; it runs inside the task's
    own pool, so whatever it autoreleases is drained.
  - The lent-font check's rule (ruling L1) takes two equal baselines in a row, over the 2nd and 3rd lists on the
    cascade-list thread, as what CoreText's own references move the count by over the task's list, the 4th. A host
    whose count moves again at the 4th list would refute a healthy tree. Nothing on macOS 15 has measured these
    lists: `MacFontStressTest`, green on both macOS 15 jobs of CI run 45041254324 (`ci2/job-137266001302.log` line
    2788, `ci2/job-137266001554.log` line 2818), shows only that a pooled call on a platform thread moves the count by
    0 over 20 calls after 20 others.
  - The check's plain `retain.whileQueued == 1` is taken as exact: the task's `CFRetain` is the only change before the
    caller is seen parked, and the worker is held; no CoreText thread touching the font in that window has been looked
    for on macOS 15. No standing test pins the order in which the check hands its three figures to the rule: all three
    are 0 on this Mac, and only C-M6's `REFUTED` (0, 0 and 1) shows that `retain.afterRun` reaches the third.
  - That the javadoc of `lentFontReleased`, the javadocs of its rule tests and its two messages name the window
    `retain.afterRun` spans, the caller's call with the task's `CFRetain` and its own release, rests on reading
    `MacFontReleaseChild.cascadeListLentFont`: `MacFontReleaseVerdictTest` pins the messages' text, not that it is
    true, and the run-time check decides on the outcome alone, so under K3-M1 it printed the old wording and passed.
- **Site 12.** The skipped repeat lookup stored nothing: `findFontIndex` parses with `register == false` and adds
  nothing to the font maps. A copy of the run's font made before the miss check, and released (M5-11a, M5-11b), is
  pinned only by the class-file check: no run-time test sees a copy made and then released or leaked (deviation 20).
  Since the Copilot review (F6) callers that need a face in one file at once share one lookup, held at a hook in the
  tests; what rests on reading follows. Since R2 two lookups of one name in two files can run at once; where both
  files hold the face, each makes a font file and `storeInMap` keeps the later under the name, while each caller adds
  the one its lookup made: two faces of one name, as two installed copies are. That `finish` publishes the outcome
  through the latch rests on the Java Memory Model and on the class-file pin of the order (R3): no run-time test sees
  a swap.
- **Site 12, the lock trace** (F6). A caller waits for a lookup in flight with no monitor held. `GlyphLayout.layout`
  has one production caller, `PrismTextLayout.shape` (`PrismTextLayout.java:897`), reached from its `layout()` and the
  break-run path, which take no monitor; the text package's only `synchronized` blocks are `CACHE_SIZE_LOCK` around the
  string cache's put, after layout, and the static `ScriptMapper.getScript`. Every class that makes a text layout
  (`Text`, `TextFlow`, `NGCanvas`, `PrismTextLayoutFactory`, `TextLayoutFactory`, the controls' `Utils` and
  `TextFieldSkin`, web's `TextUtilities` and `WCGraphicsPrismContext`) has no `synchronized`; `Toolkit`'s and
  `QuantumToolkit`'s guard listeners, hooks and the pulse timer, none around a layout. `WCFontImpl.getTextRuns` and
  `getGlyphCodes` call `TextUtilities.createLayout` outside `synchronized (FONT_MAP)`, which covers only the filling of
  the family map (`WCFontImpl.java:51-67`). No method of the font package lays text out: it uses `GlyphLayout` only as
  the type its factories make, so no font-layer monitor (`LogicalFont`, `FallbackResource`, the factory, a font file,
  `FileRefCounter`, `FontFileReader`) is held at `getFontSlot`; `CTGlyphLayout`'s own methods down to the wait take none
  (pinned). The lookup that runs takes the factory's monitor only inside `getFontResource` and `storeInMap`, and a font
  file's and its reader's inside `CTFontFile`'s constructor, each released before it returns, in deviation 17's order;
  it waits for nothing but its own file reads, so no cycle passes through a lookup in flight. It reads the file and
  stats it under no monitor. The render thread, which lays out `NGCanvas` text, may hold the render lock, a
  `ReentrantLock`, while it waits: nothing a lookup runs takes that lock (as deviation 17 has it for the font layer's
  monitors), so the lookup it waits for is not blocked by it. A virtual caller unmounts while it waits
  (`CountDownLatch`, pinned to take no monitor and call no `Object.wait`); no virtual-thread test checks unmounting.
- **Site 12, what no test provokes.** A `StackOverflowError` at the entry of `endLookup`, or a removal that throws,
  would leave the name and file in flight for good: later callers would join a lookup that never ends (the first) or
  one that ended (the second; its `finally` still wakes the waiters, whose outcome later callers then get). The removal
  can allocate: a `ConcurrentHashMap.remove` of a key and value counts the removal down, and under contention its
  `fullAddCount` can allocate counter cells, so an `OutOfMemoryError` there is the second case. Since R1 the key is
  made before the registration and `endLookup` makes none, and a registration that throws ends its own lookup: an
  `OutOfMemoryError` from `putIfAbsent` leaves nothing in flight. A `StackOverflowError` inside `putVal` and
  `addCount`, after the node is linked, is not covered so: the handler's own `endLookup` and its removal run at about
  the same stack depth and can overflow again, and the entry then stays registered and unfinished, so later callers of
  that name and file park in `await` for good. A `StackOverflowError` inside `finish`, in `countDown` before its
  compare-and-set, in `endLookup`'s `finally`, leaves the latch closed after the entry is removed: the waiters already
  parked stay parked for good, while later callers begin a lookup of their own. A bounded wait would end both, but
  would change the design; none of these throws is provoked, and the class file pins the handler and the key. The
  late leader's re-check asks `getFontResource`, which can read a font file under the factory's monitor only for a name
  the factory's file maps hold, which `getSlotForFont` asked about a moment before.
- **Site 12, the version.** A file rewritten in place with the same size within the file system's time resolution
  keeps its version; on APFS the time has nanoseconds. `fileKey()` is the device and inode on macOS; a file system that
  gives none compares time and size only. The version follows a symbolic link to its file. Each layout of a run
  whose face is remembered missing copies the run font's URL and stats the file once; neither was measured.

**The NULL guards and the verdicts** (items 6, 7, 11 and 12).
- What `bold` becomes when the bold string is NULL: the class-file pin checks only that `CFStringFind` is skipped, so
  a `bold = true` else-branch passes every test.
- The zero-test helpers read the shape of the code, not its data: a store into the tested local between the test and
  the call is not seen. The switch and handler halves of the dominance rule are proved by mutants (Xs-t, Xh-t) for
  the early-return shape only; for `assertGuards` and the fall-through shape they rest on reading, because no
  compilable Java puts a case label or a catch block inside an `if`'s body while its `switch` or `try` starts outside.
- D1 to D3, D10 and R5 are killed only by class-file pins: CoreText on macOS 27.2.1 answers NULL, 0 or 0.0 for a NULL
  font in those calls. The run-time kills of D15 and M4c need a CoreFoundation that reads an empty mutable string's
  buffer past its length, as macOS 27.2.1 does; elsewhere only the class-file pin kills them.
- Seen on macOS 27.2.1 only: CoreFoundation makes the empty string from
  `CFStringCreateMutableWithExternalCharactersNoCopy(NULL, units, 0, 1, kCFAllocatorNull)` and from zero characters
  whatever the pointer (`nullEmpty = string:`); `codes.released` and `fontNamed.nullName.released` rest on
  `CFGetRetainCount` being exact for a fresh `CFMutableData` on macOS 15 too; the Helvetica controls of
  `aZeroDfontFontHasNoFormatTagsOrTable` rest on Helvetica.ttc having a format and a `head` table on every macOS, as
  `helveticaDecodes` shows on CI's macOS 15. Under D9 the child dies before it writes its map, so its `wrapped` key
  is not observed.
- Item 7: whether CoreGraphics ever answers NULL for the device colour spaces cannot be shown on any machine; the
  test drives the private `bitmapContext` through the shim. Its control asserts that CoreGraphics makes a 3×3
  alpha-only context with a NULL colour space, the documented legal case, not verified on macOS 15.
- The wiring pins of the log check, the sub-pixel check and the lent-font check read the order of the calls in the
  check and every `Assumptions` call in the class, not data flow: that the verdict handed to `decide` is the one the
  verdict method answered rests on reading. A conditional return between the verdict call and `decide` keeps that
  order and passes the pin, so the check would end without deciding, even under `-Djfx.parity.require=true`; that no
  such return exists rests on reading. The seven other verdict checks of `MacFontReleaseTest` have no wiring pin. The
  `CANNOT_SHOW` path of the log check has never run on an account that cannot read the unified log; only the pure
  verdict is tested, with invented figures labelled so.
- That no plain assertion of the macOS tests is left that compares two figures the host decides, taken differently
  (ruling L5), rests on reading. The comparisons left are answers of the same call with the same inputs (the
  `sameList` keys, the empty name over a "." against CoreText's own answer, a glyph's first measurement on two fresh
  strikes), values Java decides, retain counts of a fresh object nothing else holds, and comparisons present at
  `f352c6008c` that passed on both macOS 15 runners of CI run 45041254324.

**`CTGlyph`** (items 1, 2, 9 and 13).
- That no production pair of threads reaches the cached path, which only the render thread takes, rests on reading
  the callers of `getPixelData`.
- The catch of `measureBounds` is pinned through a `RuntimeException` from a shim hook; production reaches it only
  through an `OutOfMemoryError` or a `StackOverflowError` in `measuredBox`. M2d, a store between the fill and the
  clamp, is pinned only in the class file: no strike call lies there to hold a thread at.
- The two-thread tests depend on where their hooks sit (`isLCDGlyph` after `CTFontDrawGlyphs`, `getFontResource`
  after the advances); a moved call fails them with their designed messages, never passes them silently.
- That `getImage` sets the strike's options while it holds the cache's lock: the class-file check reads call order,
  and `twoGlyphsDrawnAtOnceEachGetOnlyTheirOwnPixels` draws two glyphs of one strike, whose options are the same.
- Item 9's LCD half relies on `lcdEnabled` being written once, in `PrismFontFactory`'s static initialiser, and on
  `CTFactory` not overriding `isLCDTextSupported`; the rule itself is pinned.
- No image the two `MacGlyphImageTest` checks compare is a draw into a new context set against a draw into a used one
  (rulings L2 and L3). `twoGlyphsDrawnAtOnceEachGetOnlyTheirOwnPixels` compares each thread's image with its glyph's
  second draw alone, each draw finding the cached context last drawn with the other glyph; the race's context has
  had two more draws before, which CoreGraphics is taken to ignore. `MacFontStressTest`, green on both macOS jobs of
  CI run 45041254324, shows that cached-context draws there do not depend on earlier draws; neither check of
  `MacGlyphImageTest` has run on macOS 15.
- In the sub-pixel check the subject, `own` and `other` are each 'A' drawn half a pixel over as the second draw into
  a 256×256 grey context, after the other strike's 'A' at sub-pixel index 0. The subject's cached context was made
  with the other strike's options and first drawn into under them, and `getImage` sets the own strike's four options
  before the subject's draw; the context of `other` was made, and drawn into twice, with the other strike's options,
  and that of `own` with the own strike's. That only the four options in force at the draw matter rests on reading:
  `drawImage` fills the w×h region it reads white before it draws. A host whose CoreGraphics keeps other state from
  the options a context was made with or first drawn under would answer `REFUTED` on a healthy tree; where `own` and
  `other` are equal, as on a machine that draws the glyph alike with and without sub-pixel positioning, the check is
  `CANNOT_SHOW`.
- Item 9's neutrality on the golden machine is untested: only this Mac's dumps were compared. The four setter calls
  per cached draw, per glyph per frame in the software pipeline, are judged acceptable without a measurement.

**Shared state, locks and publication** (items 3 and 4, and beside them).
- The memory-model effect of every volatile field, of publishing only filled copies and of reading each field once
  rests on class-file pins of javac's shapes; no run-time test can show a weak-memory reordering.
- The lock order as a whole (deviation 17). Its parts are pinned: the cascade-list thread takes no font-layer
  monitor, the factory's maps are used only under its monitor, and the hot reads enter none in their own code. That
  nothing holding the factory monitor takes a composite's, and that no font-layer monitor holder waits for the render
  lock, rest on reading `createFont`, `removeEmbeddedFont`, `CTFactory.getFallbacks` and `getCascadingInfo`, and the
  text, prism, sg, quantum and webkit packages; no test pins the absence of the reverse edge. That the CoreText and
  CoreGraphics calls made under a composite's or the factory's monitor never wait for the AppKit main thread rests on
  Apple's implementation.
- `assertTakesNoMonitor` reads only the method's own code. `getSlotResource` calls `getLinkedFonts`, which enters the
  composite's monitor while `fallbacks` is null (by design, and pinned), and on a miss the factory's synchronized
  `getFontResource`; so "no monitor" is pinned for `LogicalFont`'s own monitor in `getSlotResource` and
  `getSlot0Resource` only.
- The factory's monitor check sees map access only through `PrismFontFactory`'s nest and
  `new PrismCompositeFontResource`, whose constructor puts into `compResourceMap`; access through reflection is not
  seen, and its exemption of initialisers does not check that a constructor stores into `this`. That
  `populateFontFileNameMapGeneric`'s `synchronized` is a reentry for its one production caller rests on reading.
- The copy-on-write check does not check which array `Arrays.copyOf` copies, or the length; such a copy passes only
  inside `LogicalFont.getSlotResource`, since any other store of `fallbacks` fails the stored-last check.
- `assertPublishesNewArrayOnceFilled` reads code order with no stack model. It sees neither an element store into
  the published array after the next allocation, nor the array escaping before its publication, nor, for
  `nativeFallbacks`, whether the store before the `putfield` writes the added font into the last element: on macOS
  the slot tests catch a wrong index, on Linux and Windows nothing does (since 2026-11-24 the same slot tests run on
  Windows and Linux, "Windows and Linux"; no mutant of that store was run there). It reads one method, so a store of
  `styleMetrics` or `charToGlyph` from another method of its class is not checked, and it allows further stores of
  null in the method: harmless for `styleMetrics` and `charToGlyph`, whose readers make the array again when they find
  null. For `nativeFallbacks`, which no reader makes again, `noCompositeDropsTheFontsItAdded` pins on every OS that
  no method of either composite stores null (`assertNeverStoresNull`). That helper sees only `aconst_null` directly
  before a `putfield` of the field in the class's own methods: a null through a local, a ternary, a callee,
  reflection or another class is not seen.
- The no-element-store pins cover exactly the methods they list (`LogicalFont.getLinkedFonts`, `getNumSlots` and
  `getSlotForFontNoCreate`, `FallbackResource.getNumSlots` and `getSlotForFontNoCreate`, `CompositeStrike.clearDesc`);
  a new reader of a published slot array fails the read lists first, and the two lists are kept in step by review.
  `assertStoresNoArrayElement` sees array-element stores only, not a write through `System.arraycopy`, `Arrays.fill`,
  a callee or an alias (ruling L6).
  Beyond the read-once pin, nothing on Linux or Windows checks that the lock-free readers iterate only the local
  they read.
- `FallbackResource.getSlotResource` writes `fallbacks[slot]` in place under the composite's monitor, as upstream
  does; that its lock-free readers never read an element of `fallbacks` rests on reading
  `FallbackResource.java:202-219` and `:303-311`.
- `PrismMetrics.styleMetrics` is pinned only to store what `getStyleMetrics` answers; that the answer is filled
  before the return, and that `PrismMetrics` writes no element of it, rest on reading. The glyph mappers and the
  strikes' `metrics` are filled by their constructors before the store; that no method changes them once published,
  and that `getGlyphBoundingBox` writes no element of a cached box after `putIfAbsent`, rest on reading.
- A slot fill of `LogicalFont.getSlotResource` lost to another thread's copy is looked up again, and the factory
  answers the font it cached (`PrismFontFactory.java:239-241`, `:307`, `:360`, `:700-703`); no test provokes it.
- The JVM's exit hook can wait for the holder of the factory monitor; no test reaches `addFileCloserHook` or
  `disposeFontsOnShutdown` at run time, only the class-file monitor rule. `asciiCacheOK` stays a plain field: it only
  turns from true to false, and either value answers right.

**Layout output and face lookup** (items 8, 10 and 12).
- The face-lookup children replace Ahem's file with Helvetica.ttc, or delete and restore it, while a CTFont made from
  it is in use. On macOS 27.2.1 CoreText then reports the name and URL their preconditions check, and does not
  crash; on macOS 15 neither has been seen. An unmet precondition gives `CANNOT_SHOW`; a crash fails the child, and
  the check with it.
- The address guard assumes that CoreText keeps the attribute's CTFont object in the runs that use it, seen only on
  macOS 27.2.1; elsewhere the identity precondition gives `CANNOT_SHOW`.
  `layoutHandsTheSlotLookupTheFontItsLineWasMadeWith` reads javac's shape: copying `fontRef` into another local
  before the call, or computing a later argument by arithmetic, `invokedynamic` or a ternary, fails it with a message
  naming the cause.
- The fill that keeps 0xFFFF has a run-time kill (M8a5) only where CoreText gives the lone joiner 0xFFFF, seen on
  macOS 27.2.1 only; the verdict's own 0xFFFF rule is pinned on every OS. That an unmet `own.run` precondition gives
  `CANNOT_SHOW` with only its true reason is shown by one-off host simulations (`impl2/E/H1-after-r2.log`); no
  permanent test pins it.
- That 8b now hands -1 to DirectWrite's `slot >= 0` and Pango's `slot != -1` branches is checked by no test on
  Windows or Linux. On CI's Windows runner `DirectWriteMetricsGoldenTest` runs its capture, which lays out the
  fallback runs that items 8b and 8c change, before its machine gate; only its comparison is skipped there, so
  nothing is compared. That no row of `linux-pango-golden.txt` or `directwrite-metrics-golden.txt` moves through 8b
  rests on reading the code and the goldens' slot bytes; the 609 DirectWrite rows of 8c were computed from the golden,
  since the Copilot review (F1) by a script that asserts each step, and that a re-capture moves exactly those, and
  draws the 16 glyphs that no row of the file holds with the fonts their new codes name, rests on reading
  `DirectWriteMetricsGoldenTest.captureFont` and the slot path. Since 2026-11-24 ("Windows and Linux"): on Windows
  `DirectWriteNoSlotLayoutTest` shows a run whose font gets no slot laid out as glyph 0 through `DWGlyphLayout`, but
  not `renderShape`'s `slot >= 0` by itself, since `textRendererGetGlyphIndices` also leaves the IDs 0 for a slot below
  0 (DW1 survives); with 8b reverted the DirectWrite comparison still passes; the re-capture on the capture machine
  moved exactly the 609 rows and drew the 16 glyphs with those fonts, measured; on Linux a dump of the four corpora is
  unchanged between `abe0943d31` and `2c9003b909`, though no corpus reaches 8b's -1. Pango's `slot != -1` branch is
  still checked by no test, on any system.
- That `findFontIndex` is reached under no font-layer monitor, so nothing is read from disk under the factory's,
  rests on reading its one caller chain (`CTFactory.createFontFile`, from `CTGlyphLayout.addFallbackFont` in
  `getFontSlot`, from `layout`; since the Copilot review through `fontFileOfCopy` and `lookUpFallbackFont` between
  them, and the stats of `versionOf`, from `mayFindFace` and `createFontFile`, under no monitor either).

**What ran where.**
- Until CI run 45278364431 the further fixes had run only on this Mac (macOS 27.2.1 arm64); that run passed them on
  Linux aarch64 and Windows x64, while its Linux x64 job compiled nothing. That their any-OS tests are safe on Linux x64
  rests on that and on reading: they read class files through the module, use class literals, reflection or
  a factory of no platform, load no platform factory and touch no native, as the class-file checks of
  `MacFontBindingStructureTest` already do on CI. `MacFontNullInputVerdictTest` loads the macOS-gated
  `MacFontNullInputTest` there, whose static initialiser only compiles a regular expression (ruling L6). This Mac's
  class-initialisation traces of `CompositeSlotRaceTest` and `FontSharedStateRulesTest` show no `PrismFontFactory`;
  the second was taken before that test called `MacFontBindingStructureTest.nest`. Since CI run 45456403357, of
  `2c9003b909`, whose Linux x64 job ran on its second attempt, they have run on Linux x64 too: `javafx.graphics`
  26,619 / 0 / 0 / 1,034, every class equal to Linux aarch64's and to the WSL gate's ("Windows and Linux").
- On CI run 45278364431 every verdict line was `PROVED` on both macOS 15 runners and every macOS test passed; before
  it, how the macOS tests decide on macOS 15 was predicted by reading: `PROVED`, or `CANNOT_SHOW` at a precondition.
  `REFUTED` on a healthy tree needs one of the three host behaviours named under "Verification"; a failed plain
  assertion, or a child that fails, needs one of the host behaviours the entries above name as unseen on macOS 15: a
  two-thread image that depends on more of the cached context's history than its last draw; a CoreText thread that
  touches the lent font while its task waits; CoreGraphics refusing an alpha-only context without a colour space
  (item 7's control); CoreFoundation making no empty string from a NULL buffer, or retain counts that are not exact
  for a fresh object (`nullEmpty`, `codes.released`, `fontNamed.nullName.released`); or CoreText crashing when a
  face's file is replaced or deleted under a CTFont in use. The virtual drain check keeps its hard rule, a call that
  leaves 10 or more blocks per call refuted on every host (ruling L6): on macOS 15 at `f352c6008c` a pooled call on a
  platform caller left 4.06 (arm64) and 4.01 (x64) blocks per call, and the thread per virtual call that this tree no
  longer starts 10.73 and 10.94. On CI run 45278364431 the worker's call left 4.02 (arm64) and 4.05 (x64). On CI run
  45456403357, of `2c9003b909`, all twelve verdict lines were `PROVED` on both macOS 15 runners.
- Nine macOS tests depend on the host's composites: eight of the 11 macOS tests of `CompositeFontLocksTest` and
  `PrismFontFactoryTest.aFontAddedToACompositeIsInTheSlotItIsGiven` need room for one or two more slots under the
  0x7E slot cap. Where the host's composite has none they skip through `ParityGate.requireOracle`, or fail under
  `-Djfx.parity.require=true`, and print no console line, so on the CI macOS jobs such a skip shows only in the
  class's count. The slot counts on macOS 15 are unknown; on this Mac all nine ran. On CI run 45456403357 none of
  the nine skipped on either macOS 15 runner. Since 2026-11-24 the nine run on Windows and Linux too, with the new
  cap test, and none skipped there: the slot counts were 12, 11 and 12 on Windows and 10, 9 and 10 in WSL ("Windows
  and Linux").
- The class-file pins read the shapes javac 27 emits, and have run only against classes this Mac's JDK 27.0.1
  compiled; that Temurin 27+35 and Oracle JDK 27 on CI emit the same is assumed: they are the same OpenJDK javac. The
  hardening round's pins rely on two more shapes: `field = null;` as `aconst_null` directly before the `putfield`, and
  a verdict method's call before the call of `decide` that takes its answer.
- Mutants not run again on the final tree keep their outcome because the code they edit and the checks that killed
  them are byte-identical since, or the checks were only appended to; each cluster's record gives the argument per
  row for the clusters after it. The final tidy and the fix round changed test files only (`impl2/FT/tests-ft.diff`,
  `impl2/FT/fix-r1/round.diff`); the tidy ran four rows of cluster E again (`impl2/FT/*-r2-ft.log`), and its
  argument for the other rows is in its record (`impl2/records/final-run.json`, `.tidy`). The hardening round and its
  fix round changed test files only too (`impl2/LAST/round.diff`, `impl2/LAST/fix-r1/fix-r1-last.diff`) and ran again
  the rows whose killing check they changed, except M10b, M10h and C-M32, which the closing round ran again (see
  "Criterion 6"); the other checks they touched were only appended to, and no `FontCallOrder` helper changed what it
  accepts. The closing round changed `MacFontReleaseVerdict.java` and `MacFontReleaseVerdictTest.java` only: comment
  lines and, under ruling K3, the two messages of `lentFontReleased` and the six strings of its rule tests that assert
  them (`impl2/CLOSE/k1-close.diff`, `impl2/CLOSE/k3/k3-close.diff`). It ran M10b, M10h and C-M32 on its tree before
  K3, which differs from the final tree only in those strings, on which no outcome of R or S depends.

US-111's tests-and-evidence review found four entries on no list of the slice records: the array release of
`fontNamed`, the worker's thread properties, the purity of the region, and the codes copy inside the pool. Since the
further fixes the first two are pinned (items 11 and 4), and of the third the monitors it takes and the font classes
it calls (`theCascadeListThreadTakesNoFontLayerMonitor`).

### Independent review
**The slices.** Each slice was implemented by the Java 27 agent and reviewed by `ffm-reviewer`, which repeated the
gate without the flag (`review-slice-{A,B,C,D}/gate-noflag.log`: 486, 494, 498 and 504, each exit 0, the 8 skips).
- **A, "fix".** The slice had no record of its mutant pass (should). The implementer took the pass again with diffs
  and `hs_err` copies (11 of 11 killed, R3 survived) and wrote the record. Notes: the R3 trace; the reading of
  criterion 1; a NULL font through `MacFontFinderShim.cascadeList` (fixed since, item 6 of the further fixes).
- **B, "pass".** Notes: the site 6 test checked the control before the setters (touch-up T3); the site 4 check pins
  the order, not the comparison; a shim javadoc said "identity matrix" for NULL (T4).
- **C, "pass".** Notes: a null name in `createFontFile` now throws (see "Deviations and stated properties", 8); the
  site 12 precondition belongs to this macOS build; the site 9 hook in the child, not the shim, accepted.
- **D, "fix".** An `Error` thrown by `get()` itself ended the wait while the worker could still read the font
  (should). Fixed in a review round: the spin on `isDone()`, a new test and mutant M10d. Notes: the heap bound's
  margin and portability; the worker and region properties (the worker's since pinned, item 4; what is open of the
  region's under "Rests on review"); the fully qualified link call (T1); the inner pool token not checked (T5).

**The touch-ups T1 to T5** (`report-touchups.md`; 505 / 0 / 0 / 8, `touchups/gate-noflag.log`): `ObjCNative`
imported in `PrismFontFactory`; the `getCascadeList` javadoc states the start-failure case; the site 6 setter
assertion before the control assumption; the shim javadoc; the inner token asserted non-zero.

**Three final reviews**, each on its own, of the tree after the touch-ups and the final mutant pass:
- **Criteria and gate.** Repeated both gates: 505 / 0 / 0 / 8, exit 0, and 505 / 8 / 0 / 0, exit 1, exactly the 8
  baseline classes, each golden after its witness passed; then the checks (`review-final/gate-noflag.log`,
  `gate-require.log`, `step3-checks.log`). Read every production change against the design. Verdict "fix", one
  must: criterion 7's "after" dump had not been taken. Notes: the 9th skip where the log cannot be read; the bound's
  figures from one machine; the touch-ups had only checksums and a gate log as their record.
- **Ownership and concurrency.** Ran no Maven; traced every exit of the changed methods against `git show HEAD:`,
  `javap` and the JDK 27 sources. Verdict "pass". Its notes are under "Rests on review" (the push token, the
  monitors and class initialisation across the hand-off, every way the wait ends, the lock order, the site 12 set),
  and one more: the pools of `MacObjCNativeTest` were not popped in a `finally`.
- **Tests and evidence.** Ran no Maven; opened all 29 logs and diffs and rebuilt 13 mutants from the diffs, with the
  recorded sha256 prefixes (`final-review-tests/`). Verdict "fix", one should: the interrupt restore of `resultOf`
  had no mutant row, and the interrupt test did not show that the wait saw the interrupt. Notes: no positive control
  for the site 6 setters; the order checks pin order, not the condition; four review paths on no list; three tests
  depend on this macOS build; the after-runs missing. One disagreement with a decided item: site 5 could have had a
  classfile check instead of resting on review.

**Sceptics.** The must and the should each went to a sceptic who tried to refute it (`verified-final.json`): both
were confirmed real, the must as an unmet criterion and the should at its severity.

**What closed them.**
- The must: the "after" dump and the rest of the after-runs on the final tree (`after/`; see "Measured").
- The should: test-only fix F1, the new `resultOf` test, with M10e. With it: F2, the setter control of site 6; F3,
  `MacObjCNativeTest` pops inner then outer in nested `finally` blocks, through a helper that skips a 0 token,
  because popping NULL would end the surefire fork; F4, the virtual test's javadoc claims only what it measures.
  Six test and shim files changed (`final-fix/tests.diff`), no production file; the gate 506 / 0 / 0 / 8.

**The confirmation**, by the reviewer, on the final tree:
- `shasum -c` of `after/final.sha256`: 25 of 25 OK; the 10 production files identical to the final pass's copies.
- Repeated both gates: 506 / 0 / 0 / 8, exit 0, and 506 / 8 / 0 / 0, exit 1, its failing-class list equal to HEAD's
  (`review-confirm/gate-noflag.log`, `review-confirm/gate-require.log`); both `hs_err` finds and the golden and
  facade diffs printed nothing.
- Read the after module log and its per-class diff, the three post-fix mutant logs (the mutants rebuilt from their
  diffs, with the recorded sha256), the dump diff and the JDK 27 sources behind the new interrupt test.
- Verdict "pass, mergeable", no must or should. Notes: a file it wrote into the shared scratch directory
  (`before-gate-require.log.failing.txt`), the two stale surefire reports of the dump test, `doneWhenParked` a
  precondition by construction, and how the site 6 test behaves on another machine.

**The further fixes.** Each cluster was designed by one agent and the design checked by an independent reviewer
(`design-review/design-<letter>.md`, `critique-<letter>.md`); the session's manager ruled on each critique
(`design-review/manager-rulings-C-B-E.md` and `manager-rulings-F.md`; the rulings on A and D and the final tidy's
are in the workflow scripts under `workflows/`). Each implementation was reviewed by an independent reviewer until
it was accepted, every finding fixed in the tree:
- **A:** two reviews with one should each, then "accept".
- **D:** three reviews with 3, 1 and 1 shoulds, then a tidy step on the manager's rulings T1 to T5 and "accept".
- **C:** four reviews with 3, 2, 1 and 1 shoulds, then a tidy step whose first review had one should, then "accept".
- **B:** two reviews with 2 and 1 shoulds, then "accept". **E:** two reviews with 2 and 1 shoulds, then "accept".
- **F:** three reviews with one should each, then "accept".
- **The final tidy** (FT1 to FT5, on the manager's rulings FT1 to FT7) closed the notes the last reviews had left,
  in test code only.
- **The whole tree** was then reviewed once, by four reviewers at the same time, by reading (the macOS 15 hosts, the
  Linux and Windows hosts, concurrency, conformance; working files in `final-review/conformance/`, `linux-windows/`
  and `macos15/`): two shoulds, `nativeFallbacks` published with no pin that it is filled first, and invented figures
  of `MacFontReleaseVerdictTest` not labelled so. One fix round fixed both, in test code only (MNF1 to MNF5,
  `impl2/FT/fix-r1/round.diff`); the confirming review ran `CompositeFontLocksTest`, `MacFontReleaseVerdictTest` and
  `FontSharedStateRulesTest` (83 / 0 / 0 / 0, `impl2/FT/fix-r1/review-touched-rv.log`), found no must or should
  left, and gave "mergeable". What those reviews left open is under "Rests on review" and deviations 27 and 28.
- **The hardening round** (the manager's rulings L1 to L6, test code only): the whole-tree reviews and a review of
  this record found three macOS run-time tests that compared two figures the host decides, taken differently: two
  with a plain assertion, and the sub-pixel check through a verdict that refuted a mismatch (item 12). Its first
  review had one should, the lent-font rule pinned only with baselines of 0, which one fix round fixed (FR1-A to
  FR1-C; C-M11 and C-M21 run again). Its second review, which repeated the seven classes around the round with
  `-Djfx.parity.require=true` (134 / 0 / 0 / 0), gave "accept" with two notes, which the closing round closed: M10b
  and M10h not run again, and the javadoc of `lentFontReleased`, which left the task's own release out of what
  `afterRun` spans.
- **The closing round** (the manager's rulings K0 to K3, test code only): it ran M10b, M10h and C-M32 again after a
  control (see "Criterion 6"), and the javadoc of `lentFontReleased` and four rule-test javadocs of
  `MacFontReleaseVerdictTest` that called `afterRun` the change over the task's list now say that it spans the
  caller's call, from before its task took its reference to after the task had run, its own release included. Each
  of its four reviews had one should, the rule's `PROVED` and `REFUTED` messages, which still said "its list moved
  the font's retain count by …"; the first had a second, a skill line on the same window missing from the round's
  record, which its fix round added. K0 allowed comment lines only, so three fix rounds left the messages; ruling K3
  changed them, and the six strings of `MacFontReleaseVerdictTest` that assert them, to "the caller's call, from
  before its task took its reference to after the task had run, moved the font's retain count by …" (mutants K3-M1
  to K3-M3). Its review repeated `MacFontReleaseVerdictTest` and `MacFontReleaseTest` with
  `-Djfx.parity.require=true` (82 / 0 / 0 / 0, `impl2/CLOSE/k3-review/review-require-k3.log`), found nothing and
  gave "accept".
- **The final evidence** on the final tree is under "Measured" (`impl2/CLOSE/evidence/`).

### Logs
In the session scratch directory, which is temporary: under
`/private/tmp/claude-501/-Users-benesquivelmusic-Documents-SourceCode-jfx-ffm/`, the directory
`1f2e48d7-9085-406f-83cd-63c632340d91/scratchpad/us111/`. Nothing of it is in the repository.
- **Before (HEAD `abe0943d31`):** `before-gate-noflag.log`, `before-gate-require.log`, `before-module.log`,
  `before-checks.log` (0 bytes); the dumps `dump-before-1/` and `dump-before-2/`, with `slice-0/dump-before-1.log`,
  `slice-0/dump-before-2.log` and `slice-0/dump-before-diff.log` (0 bytes).
- **After (the final tree):** `after/gate-noflag.log`, `after/gate-require.log`, `after/module.log`,
  `after/dump-after.log`, `after/dump-after.sha256`, `after/dump-diff.log` (0 bytes), `after/final.sha256`,
  `after/step1-shasum.log` and the per-class files; the dump `dump-after/`.
- **The slices:** `slice-A/` to `slice-D/` (gates, mutant diffs and logs, `hs_err` copies, child figures, fixed
  copies and checksums) and the records `report-slice-0.md`, `report-slice-A.md`, `report-slice-B.md`,
  `report-slice-C.md` and `fix-slice-D.md` (slice D's final record; `report-slice-D.md` is its round 1).
- **The touch-ups:** `touchups/` and `report-touchups.md`.
- **The final mutant pass:** `final-mutants/` and `report-final-mutants.md`.
- **The test-only fixes:** `final-fix/` (`tests.diff`, the three rows' diffs, logs and child outputs,
  `M6.unified-log.txt`, `gate-noflag.log`, `restored-green.log`, the checksums).
- **The reviews:** `review-slice-{A,B,C,D}.json` with `review-slice-{A,B,C,D}/`; `review-final-criteria-gate.json`,
  `review-final-ownership-concurrency.json`, `review-final-tests-evidence.json` with `review-final/` and
  `final-review-tests/`; `verified-final.json`; `review-confirm/`.
- **The design:** `design.md`, the tracer reports `trace-*.md` and the critiques `critique-*.md`.
- **The CI fix:** under the same parent, the directory `7c84aabb-09af-4669-b4e4-19d317cb24e2/scratchpad/`, which the
  names `ci/`, `impl/`, `fix/` and `final/` above refer to. `ci/mac-aarch64.log` and `ci/mac-x64.log` are the macOS
  jobs of CI run 45022158121. `final/` is the fix's final tree: `V1.log`, the module run `V4.log`, the rehearsal
  `R1.log` and `R2.log`, `final.sha256`, and the `.diff` and `.log` of each of the 12 mutants, named by its row.
  `impl/` and `fix/` are its two earlier trees: the runs `v1.log` and `V4.log`, and `W1.log` and `W3.log`, and their
  first mutant runs.
- **CI run 45041254324:** under the same parent, `ci2/job-137266001302.log` (macOS 15 arm64) and
  `ci2/job-137266001554.log` (macOS 15 x64).
- **The further fixes:** under the same parent, `design-review/` (the designs, critiques and rulings);
  `workflows/` (the workflow scripts, which hold the rulings on clusters A and D, on D's tidy step and on the final
  tidy); `impl2/A/` to `impl2/F/` (each cluster's runs, mutant diffs and logs, dumps and checksums) and
  `impl2/records/` (each cluster's last record with its reviews: `A-final.json`, `D-tidy-final.json`, `C-final.json`,
  `C-tidy-final.json`, `B-final.json`, `E-final.json`, `F-final.json`); `impl2/FT/` (the final tidy's runs, diffs
  and mutants), `impl2/FT/fix-r1/` (the fix round's, and the confirming review's run `review-touched-rv.log`) and
  `impl2/FT/evidence/` (the evidence of the final tidy's tree: `font.log`, `font-require.log`, `font-verdicts.txt`,
  `module.log`, `reactor.log`, `dump.log` and `dump/`, the dump of the final tree, `golden-diff.log`,
  `final-all.sha256`, `shasum-check.log`); `impl2/LAST/` (the hardening round's runs, `round.diff`, `final.sha256`
  and each mutant's `.diff` and `.log`), `impl2/LAST/fix-r1/` (its fix round's) and `impl2/LAST/evidence/` (the
  evidence of its tree: `font.log`, `font-require.log`, `font-verdicts.txt`, `module.log`, `reactor.log`,
  `golden-diff.log`, `final-all.sha256`, `shasum-check.log`, `status-final.txt`); `impl2/CLOSE/` (the closing round:
  `k1-close.diff`, `K2-control-close.log`, the `.diff` and `.log` of M10b, M10h and C-M32 named `<row>-close`,
  `child-javap-close.txt`, `touched-close.log`, `touched-require-close.log`, `status-start.txt`, `status-end.txt`,
  `final.sha256` and the copies `pre/` and `pre-mutant/`), `impl2/CLOSE/fix-r1/` to `fix-r3/` (its three fix rounds';
  `fix-r3/trial-close.diff` is the change ruling K3 applied), `impl2/CLOSE/k3/` (ruling K3's step: `k3-close.diff`,
  `touched-k3.log`, `touched-require-k3.log`, `require-flag-in-fork.txt`, the `.diff` and `.log` of K3-M1 to K3-M3,
  `final-before-k3.sha256`, `status-start.txt`, `status-end.txt`), `impl2/CLOSE/k3-review/review-require-k3.log` (its
  review's run) and `impl2/CLOSE/evidence/` (the final evidence: `font.log`, `font-require.log`,
  `font-verdicts-log.txt`, `module.log`, `reactor.log`, `golden-diff.log`, `final-all.sha256`,
  `close-shasum-check.log`, `ft-shasum-check.log`, `last-shasum-check.log`, `w2b-verdict.diff`,
  `w2b-verdicttest.diff`, `status-final.txt`); `final-review/conformance/`, `final-review/linux-windows/` and
  `final-review/macos15/` (the whole-tree review's working files); `docwriter/diffstat.txt`,
  `impl2/LAST/docs/r0/diffstat.txt` and `impl2/CLOSE/docs/r0/diffstat.txt`. The records of the final tidy, of the
  whole-tree reviews, of their fix round and its confirming review, of the final tidy's evidence and of this record's
  reviews are `impl2/records/final-run.json`; those of the hardening round, of its reviews and fix round, of its
  evidence and of its documentation are `impl2/records/last-run.json`; those of the closing round's first step (K1 and
  K2), of its four reviews and of its three fix rounds are `impl2/records/close-run.json`. The records of ruling K3's
  step, of its review and of the final evidence are not in the scratch directory, and their runs, diffs and logs are
  the files above.
- **The Copilot review:** under the same parent, the directory `b0b9aae3-5178-4650-8523-65d1e0e4fbdf/scratchpad/`:
  `font.log`, `font-require.log`, `gate-noflag.log` and `gate-require.log` (the runs of its table), `run1.log`,
  `fixed.sha256` (the round's `MacFontFinder`), and per row `mutants/<row>.diff`, `.log` and `.summary`, each diff
  against `e1de3dd32a` and so holding the round's fix with the row's edit; `mutants/restored-green.log`. F4-M3's
  first run, whose child the scenario's own release crashed, was overwritten by its second.
- **The Copilot review, F5 and F6:** in the same directory, under `round2/`: `font.log`, `font-require.log`,
  `gate-noflag.log` and `gate-require.log` (the runs of its column), `final-font.log`, `final-font-require.log`,
  `final-gate-noflag.log` and `final-gate-require.log` (the same four again once the story was written, on the same
  code), `font-before-pin-move.log` (the run that failed the golden witness before the pin moved), `font-verdicts.txt`,
  `checks.log` (no `hs_err_pid*`, no golden diff, `git diff --check`), `iter1.log` to `iter5.log` (the runs while the
  tests were written), `fixed.sha256` (the round's `CTFactory` and `CTGlyphLayout`), `mutant.sh`, and per row
  `mutants/<row>.diff`, `.log` and `.summary`, each diff of those two files against `e1de3dd32a` and so holding the
  round's fix with the row's edit.
- **The Copilot review, F1:** in the same directory, under `round3/`: the read-only scripts `scripts/dw_derive.py`,
  `dw_slots.py`, `pango_slots.py` and `mac_slots.py` with their output `dw-derive.log` (the 609 rows, each glyph's
  old and new font and the 16 glyphs no row holds), `dw-slots.log`, `pango-slots.log` and `mac-slots.log` (the last
  also over `mac-layout-before-us111.txt`, the golden at `abe0943d31`); `module.log` and `gate-require.log` (the runs
  of the third round); `checks.log` (no `hs_err_pid*`, no golden diff, `git diff --check`, the tree's checksums);
  `start.sha256` and `status-start.txt`.
- **The Copilot review, R1 to R4:** in the same directory, under `round4/`: `font.log`, `font-require.log`,
  `gate-noflag.log`, `gate-require.log` and `module.log` (the runs of its column), `runs.status`, `surefire-check.txt`
  (the count of the module run's test classes against its reports), `checks.log`, `iter1.log` (the run while the tests
  were written), `fixed.sha256` (the round's `CTFactory` and `CTGlyphLayout` for its mutants and runs),
  `fixed-final.sha256` (the same after one comment line of `CTGlyphLayout`, 121 columns, was re-wrapped within its
  three lines once the runs were done; `class-before.sha256` and `recompile.log` show the recompiled class
  byte-identical), `mutant.sh`, and per row `mutants/<row>.diff`, `.log` and `.summary`, each diff of those two files
  against `e1de3dd32a`.
- **The Copilot review, the fifth round:** in the same directory, under `round5/`: `font.log`, `gate-noflag.log` and
  `gate-require.log` (its runs), `runs.status`, `iter1.log`, `fixed.sha256` (the same production files as
  `round4/fixed-final.sha256`), `mutant.sh`, per row `mutants/<row>.diff`, `.log` and `.summary` and
  `mutants/R5-1.javap.txt`; and the read-only CI evidence `pr-checks.txt` (`gh pr checks 71`), `run-view.json`,
  `linux-x64-failed.log` (`gh run view 45278364431 --job 138055973171 --log-failed`) and the macOS jobs' logs
  `job-138055973422.log` (arm64) and `job-138055973285.log` (x64), with their verdict lines.

The records of the test-only fixes, of the after-runs, of the confirmation and of this record's fact check are
`close-fix.json`, `close-measure.json`, `close-confirm.json`, `close-docs.json`, `close-docCheck.json` and
`close-docsFix.json`. The evidence-only `Us111CorpusDumpTest` (sha256 `fde7c5c7…`) was in the tree from before
slice A until after the "after" dump; it is not part of the change. Two of its surefire reports stayed in
`modules/javafx.graphics/target/surefire-reports` (git-ignored) after it; since the module runs of the CI fix they
are no longer there. For the further fixes it was in the tree again, untracked, for the clusters' dumps, and was
moved out after the final dump to `impl2/FT/evidence/Us111CorpusDumpTest.java` (the same sha256); the three surefire
reports of that dump are in `modules/javafx.graphics/target/surefire-reports` (git-ignored).

### Deviations and stated properties
Decided by the session's manager, for the owner's attention:
1. **Criterion 1 is read as "no NULL object of the inputs the story lists".** The deliberate nullable default
   arguments are kept: `CTFontCreateUIFontForLanguage(type, size, NULL language)`,
   `CTFontCollectionCreateFromAvailableFonts(NULL options)`,
   `CTFontCreateWithFontDescriptor(descriptor, 0, NULL matrix)`, and a NULL matrix where the caller passes no
   transform.
2. **Site 7: NULL language codes give the empty list without asking CoreText.** That takes criterion 1 literally,
   though CoreText declares the parameter nullable; the C passed NULL to CoreText and then crashed at `CFRelease`.
3. **Site 3 guards one input more than the story names:** a NULL name string from `toCFString`.
4. **Site 4: the empty name stays on the ordinary path** (golden rows `ct.font.name.n10.*`). The classfile check
   pinned the order of the two calls, not the comparison, until the further fixes; since then it also requires that
   the first character is read only where the length is not 0, so `>= 0` for `> 0` fails it (D15). Site 12's check,
   which pinned the order only, pins the condition too since the CI fix: the copy and `addFallbackFont` lie on the
   fall-through of the `ifeq` after `mayFindFace` (M12f).
5. **Site 5's guard has no observable at run time on macOS 27.2.1** (R5 survived US-111's pass); since the further
   fixes a class-file check pins it on any OS (R5, D16, Xb).
6. **Site 6 is proved through the unified log.** On an account that cannot read it, or where CoreGraphics no longer
   logs the setters on NULL (after its 30 s wait), the check answers `CANNOT_SHOW` since the further fixes: a skip
   with its line in the gate, a failure under `-Djfx.parity.require=true`. It never passes without the log. A
   class-file check pins the site on any OS.
7. **Site 10: a fourth facade, `ObjCNative`** (two libobjc functions), beside the three the story freezes;
   `FACADES` and the initialiser check of `MacFontBindingStructureTest` were extended, and no `MacFontBindings` pin
   moved. A virtual caller got one platform thread per call; since the further fixes it hands its call to the one
   cascade-list thread (deviation 14), and if that cannot be started the list is made inline without a pool; since
   the Copilot review (F3) the caller gets the error instead, and the next one tries again. The wait outlasts an
   interrupt; since the further fixes the task holds its own reference to the font, so the wait need not outlast an
   error (deviation 13). Measured here: about 230 blocks per call without the pool (the story
   said about 232), at most 0.283 with it; the bound of 10 comes from this one machine. On the macOS 15 runners the
   region without a pool leaves about 6, below the bound, so the heap there cannot show the defect. Since the CI fix
   the two heap checks are skipped there with their figures (so on CI run 45041254324), or fail under
   `-Djfx.parity.require=true`; since the further fixes the pool tokens can prove the drain there, which CI run
   45278364431 showed on both macOS 15 runners.
8. **Site 12's found faces are keyed by name alone, as `storeInMap` is; its misses and lookups by name and file.** A
   face of a name found in any file is used for every run font of that name, whatever file it reports. A miss is kept
   per name and the file the run font reports, with the version the file was read in (F5 and R2), and a lookup in
   flight is shared only by callers of the same name and file (F6 and R2), so a run font of that name that reports
   another file is looked up in it, and one that reports no file where one was read, or the same file changed or
   gone, is looked up again. Until the further fixes a miss was remembered by name alone, whatever `findFontIndex`'s
   -1 meant, so a file that could not be read at the first layout was not read again in the process; since item 10 a
   read failure is not remembered (deviation 23), and until R2 a miss was kept by name with one file. `CTFactory`'s
   `createFontFile` with a null name and no face throws `NullPointerException` where it returned null; production
   cannot pass null (`getFontSlot` returns first), only a shim caller can.
9. **Site 11: `OS.rect` needed no change;** its outcome is pinned by a test and by M11b.
10. **Tests whose preconditions belong to the machine:** the site 12 fallback face, the heap bound and the log
    read. On the macOS 15 runners of CI run 45022158121 the fallback-face check and both heap checks failed at their
    preconditions, and the log read passed. Since the CI fix the fallback-face check and both heap checks are skipped
    with their figures where the machine cannot show the defect, or fail under `-Djfx.parity.require=true`, as CI run
    45041254324 shows on both macOS 15 runners. Since the further fixes every verdict-based check of the PR, the log
    read included, decides through `MacFontReleaseVerdict.decide`, and the fallback-face check makes its own missing
    face. None passes without proving its fix.
11. **The owner committed the change** as `40e53db102`, the CI fix as `f352c6008c` and the further fixes as
    `e1de3dd32a` (PR 71); the fixes of the Copilot review are not committed. Only the repository owner commits. Since
    2026-11-24 they are, as `2c9003b909`; the Windows and Linux round of that day is not.
12. **The autorelease-pool token is a `long`** (ruling C2). `ObjCNative` binds `objc_autoreleasePoolPush` as
    `FunctionDescriptor.of(JAVA_LONG)` and `objc_autoreleasePoolPop` as `ofVoid(JAVA_LONG)`, where the C prototypes
    have `void *`: an `ADDRESS` return is boxed into a segment after libobjc has pushed the pool, and an
    `OutOfMemoryError` there would leave the pool pushed. A pointer and a 64-bit integer pass alike on arm64 and
    x86_64. It deviates from "the descriptor matches the C prototype exactly" and from `MemorySegment` below `OS`;
    `MacObjCNativeTest` pins the descriptors (C-M11).
13. **US-111's `anErrorOfTheWaitItselfIsThrownOnlyOnceTheTaskIsDone` and its mutant M10d are deleted** (ruling C3),
    with the child scenario, `FailingWait`, `WaitCall` and `MacFontFinderShim.resultOf`. The task now holds its own
    reference to the font, so the caller's release is safe at any time and the wait need not outlast an error;
    `resultOf`, which the test and the mutant exercised, is gone. What they protected, that the font is not released
    while the worker reads it and that the interrupt is kept, is pinned by
    `theTaskHoldsAReferenceOfItsOwnToTheLentFontUntilItHasRun`, S7 and S8. US-111's M10c, M10e and M10m edit code
    that is gone too; C-M10, C-M9 and C-M2a make the same edits on the new code. The verdict rules of items 5 and 14
    changed on purpose: a call that leaves 10 blocks or more is `REFUTED` on every host, "at or above the bound of
    10.0 fixed for every host", a hand-off at the bound no longer shields a call at the bound, and M10a with the
    worker is `REFUTED`.
14. **One long-lived daemon platform thread, "JavaFX font cascade list", makes the cascade lists of all virtual
    callers, one after another.** It is started by the first virtual caller and never ends, whatever a task throws
    (the Copilot review, F2); a virtual caller that cannot start it gets that error, and the next one tries again
    (F3). A CoreText call that hangs inside one list stalls every later virtual caller; platform callers make their
    lists themselves and are not affected.
15. **Item 8 changes what layout gives, on purpose** (rulings B2, E2 and E3).
    - 8a: a run whose font takes no slot is laid out as missing-glyph codes, where it drew another font's glyphs
      under the same IDs; `INVISIBLE_GLYPH_ID` entries stay.
    - 8b: `PrismCompositeFontResource` answers "no slot" (-1) on every platform, where it answered slot 0.
      DirectWrite (`DWGlyphLayout`, `slot >= 0`) and Pango (`PangoGlyphLayout`, `slot != -1`) then lay out glyph 0
      where they drew the fallback font's IDs with the primary; advances and widths are still read. By reading, no
      Linux or Windows golden row moves through it: in the DirectWrite captures the slot bytes beyond a composite's
      slots list are 8c's first added fonts, and the others lie inside the lists. Since 2026-11-24 measured on
      Windows: with 8b reverted alone the DirectWrite comparison passes, and `DirectWriteNoSlotLayoutTest` shows glyph
      0 where the composite has no slot; on Linux the dump of the four corpora is unchanged between `abe0943d31` and
      `2c9003b909`, though no corpus reaches 8b's -1 ("Windows and Linux").
    - 8c: `FallbackResource.addNativeFallback` answers `ns`, the new font's 0-based index, where upstream answers
      `ns + 1`, an off-by-one since JDK-8246104 that openjdk/jfx `master` still has (read again on 2026-11-24, at
      `94168cc51f`).
    - Items 9 and 10 change results too, on purpose: a glyph drawn into the cached context has its own strike's
      sub-pixel options (no golden row moved), and a file that could not be read is read again at the next layout
      (deviation 23). Every other change of the further fixes leaves single-threaded, healthy paths as they were.
16. **The goldens of item 8.** Ten rows of `mac-layout-golden.txt` are edited by hand, in the glyph field only,
    derived from this Mac's corpus dump and not captured: `lay.pingfangsc.s12` t02, t03, t04 and t07 (8c, a slot
    byte one lower), `lay.pingfangsc.s12.t06` (8b, then 8a), and `lay.system.s12.t06`, `lay.systembold.s12.t06` and
    `t07`, `lay.systemitalic.s12.t06` and `lay.times.s12.t06` (8a, glyph 0 throughout), each `.r00`. 8 header lines
    name them and their cause; `capture.*` still describes the capture. At `f352c6008c` the golden's `lay.*` rows
    equalled this Mac's dump, and in each edited row only the glyph codes moved. No host available to this work
    compares them: the golden skips at its machine gate on this Mac and on CI's macOS 15; the comparison on the golden
    machine (macOS 27.0.1, 26A434) remains for the owner. `directwrite-metrics-golden.txt` is not edited, and 8c
    moves 609 of its rows: arial, segoeui and timesnewroman 12 pt `layout.run.5.glyphs` and `run.6.glyphs`,
    consolas 12 pt `run.4` to `run.6`, and 300 `glyph.<code>.*` rows out and 300 in. They cannot be edited by hand as
    the ten were (the Copilot review, F1): the 9 layout rows are re-keys, but the old glyph rows were drawn with
    other fonts than their new codes name, and 16 of the 20 glyphs that come, 240 rows, are drawn at 12 pt nowhere in
    the file. Only its Windows 10 19045 capture machine can re-capture them, and the owner does so there before the
    merge, from the repository root:
    1. `mvn -pl modules/javafx.graphics -am test -Dtest=DirectWriteMetricsGoldenTest
       -Dsurefire.failIfNoSpecifiedTests=false -Djfx.parity.require=true` is expected to fail with "609 difference(s)
       against the golden", the first 60 listed, all of them keys of the rows above, so that every other row still
       equals the JNI capture. Any other count or key, or a failure at the machine gate (the machine has changed since
       the capture, and a re-capture would replace every row), is a finding: stop there.
    2. The same command with `-Djfx.font.golden.capture=true -Djfx.font.golden.regenerate=true` in place of
       `-Djfx.parity.require=true` writes the golden and aborts its test ("a capture run verifies nothing").
    3. `git diff` of the golden shows only those 609 rows and `info.*` or `capture.*` rows.
    4. The header the capture writes is the test's, which says the file comes from the JNI build; lines naming the 609
       rows and 8c as their cause go into it, as the ten rows have theirs.
    Until then the comparison fails on that machine; CI's Windows runner skips it at its machine gate. 8a and 8b add no
    DirectWrite row, and `linux-pango-golden.txt` does not move.
    Since 2026-11-24 this is done, on that machine ("Windows and Linux" under "Done"): step 1 gave "609 difference(s)
    against the golden captured from the JNI build (first 60 shown):"; the capture of step 2 changed exactly those 609
    rows, `capture.provenance` and the header (step 3); and the lines of step 4 went into the test's `header()` before
    the capture, so that the file a capture writes is the committed one. The comparison passes there with
    `-Djfx.parity.require=true`.
17. **The lock order, and the render thread as a layout thread** (rulings B4, B5, F-R5 and F-R6). {`LogicalFont`,
    `FallbackResource`} before the factory instance before a font file's monitor before {`FileRefCounter`,
    `FontFileReader`, `DWDisposer`}, and the factory instance before {`PrismFontFactory.class`,
    `FontConfigManager.class`}; `WCFontImpl.FONT_MAP` before the factory, as before, and the factory never takes
    `FONT_MAP`. Nothing that holds the factory monitor takes a composite's, the cascade-list thread takes no font-layer
    monitor, and no font-layer monitor holder waits for the render lock. The hot reads enter no monitor in their own
    code: `PrismFontFile.getGlyphBoundingBox`, `PrismFontStrike.getGlyph`, `CompositeGlyphMapper.getGlyphCode`,
    `getAsciiGlyphCode`, `getSlotMapper` and `convertToGlyph`, `CompositeStrike.getStrikeSlot` and `getGlyph`, and
    `LogicalFont.getSlotResource` and `getSlot0Resource`, each pinned with `assertTakesNoMonitor`. What they call can
    take one; among others, a composite's `getLinkedFonts` enters its monitor until the fallbacks are known, a lookup
    that misses enters the factory's, `FallbackResource.getSlotResource` is synchronized, as upstream and unchanged, and
    `CTGlyph` holds the glyph's monitor over each image, as upstream, and over its one measurement (item 2), the
    cached draw `CONTEXT_LOCK` too (item 1). The render thread is also a layout thread, since `NGCanvas` lays out
    Canvas text on it, so it can block on a composite's monitor for the length of one `getFallbacks` call: accepted,
    and no deadlock. The JVM's exit can wait for the holder of the factory monitor, which the shutdown hook now takes;
    `embeddedFonts` stays a `HashMap`, for its iteration order. A slot fill of `LogicalFont.getSlotResource` lost to
    another thread's copy is looked up again, and the factory answers the same font. Since the Copilot review (F6) a
    layout thread can also wait, holding no monitor, for the one lookup of a fallback face in flight; that lookup takes
    the monitors above only in this order and releases each before it returns, and waits for nothing else, so the order
    is unchanged (the trace is under "Rests on review"). The render thread can wait there too, for the length of one
    lookup.
18. **A fallback that is a variable-font named instance draws slot 0's missing-glyph boxes** (ruling E5), where it
    drew unrelated glyphs: on macOS 27 `.SF Devanagari Regular` and `Bold` (System, System Bold, System Italic and
    Times Devanagari) and `.ThonburiUI Regular Bold` (System Bold Thai). Drawing them needs fvar named-instance
    support in `PrismFontFile`, which no CI host can show.
19. **`CTFontFile.getFontRef` still gives the system UI font to every face whose PostScript name starts with "."**
    (ruling E6): PingFang SC on macOS 27 is drawn with SF. It is upstream behaviour that the goldens record; changing
    it needs new production bindings and would move every `pingfangsc` row to values nobody can capture.
    `MacGlyphLayoutGoldenTest`'s javadoc says so.
20. **Site 12's run-time copy check is withdrawn** (ruling FT6). On this Mac a copy of a CTFont moves neither the run
    font's retain count nor its CGFont's (0 and 0 over 20 copies, `impl2/E/probe-copies-child.txt`), so such a check
    could only answer `CANNOT_SHOW`. The class-file check `layoutAsksForAFallbackFaceBeforeCopyingTheRunFont`, which
    runs on every host, is the pin of M12g. M8g7, a run font without a display name, is accepted as unpinnable: no
    test can make CoreText give one.
21. **The lazy state of `FTGlyph` and `DWGlyph` is not changed** (ruling F-R3): `FTGlyph.init`,
    `DWGlyph.checkMetrics` and `DWGlyph`'s lazily filled `pixelData`, item 2's twins on FreeType and DirectWrite. They
    are other platforms' glyph classes, need a design of cluster A's size, and cannot be exercised on this Mac.
22. **`CFNative` changes in one modifier:** `CFRetain` is public, so that `MacFontFinder`, in another package, can
    retain the lent font for the task. No descriptor changed, so `CFNativeTest`'s table did not.
23. **Item 10's outcomes.** A file that cannot be read, or is corrupt, is parsed again at every layout, as before
    US-111; a font that reports no file is remembered at once by its name; `findFontIndex` is `throws Exception` with
    no catch-all, and its throwaway `PrismFontFile` now allocates an empty `ConcurrentHashMap`, the field initialiser
    of `bbCache`.
24. **`CTGlyph`'s stated properties** (rulings A2 and A5). A glyph whose measurement throws throws once and is empty
    afterwards, as before; production reaches that only through an `OutOfMemoryError` or a `StackOverflowError`.
    Item 9's LCD half is not a defect: every production glyph asks for the colour space the context has, and
    `aStrikeIsLcdOnlyWhereLcdTextIsOn` pins the rule. Each draw into the cached context makes four setter calls, and
    each `getGlyphMapper` call one volatile read (`GlyphLayout` calls it once per code point); neither was measured.
25. **Cluster F beyond its rulings:** `PrismMetrics.styleMetrics` is volatile too; `getNumGlyphs` needs no change,
    since `numGlyphs` is computed the same way each time from the synchronized `readTable`, nor `upem`, which is set
    while the font file is constructed, before it is published; `asciiCacheOK` stays plain.
26. **Two test-only properties of site 9's child are kept.** Its `CTFontStrikeShim` strike has no disposer record, so
    its CTFont stays allocated until the child JVM exits. `CacheCall` catches `Exception`, not `Throwable`, so an
    `Error` in a thread's call fails the test later, at `context.made` or `context.shared`, with a less direct
    message. Both are harmless: neither reaches production, and neither lets a defect pass.
27. **Some monitors are held longer** (the whole-tree concurrency review). Each is uncontended, or makes a thread wait
    only for work it did itself at `f352c6008c`; none was measured.
    - `CTGlyph.measureBounds` holds the glyph's monitor over the glyph's first measurement, which can read the font
      file under the file's monitor, and the cached branch of `getImage` holds `CONTEXT_LOCK` over the fill, the
      draw, the read and item 9's four setters, where only `getCachedContext` held it. In production the cached draw
      is reached only from `GlyphCache` and `SWGraphics.drawGlyph` (the software pipeline), on the render thread. The
      measurement is reached from those and from `Graphics.drawColorGlyph`, which draws a colour glyph through
      `getColorImage`, without `CONTEXT_LOCK`: on the render thread, and on the print job thread where
      `PrismPrintGraphics` prints a colour glyph. Layout takes no glyph monitor: it measures through the font
      resource (`getGlyphBoundingBox`, `getAdvance`) and the strike's `getOutline`, and the style metrics read
      `CTGlyph.getBBox`, which takes none.
    - On a composite's first use `getLinkedFonts` holds the composite's monitor over `factory.getFallbacks` (on macOS
      the cascade-list call, which a virtual caller hands off and parks for; on Linux fontconfig; on Windows the
      registry), and `LogicalFont`'s also over `getSlot0Resource`, which can wait for the factory's monitor;
      `addNativeFallback` takes it on every add. Other threads wait only while one thread does what each did itself
      before, and once the fallbacks are known the readers take no lock.
    - `storeInMap` (once per new fallback face per process), `getDefaultFontResource` (once per logical font whose
      physical font is missing) and the three name methods (per `Font.getFamilies` or `Font.getFontNames` call) now
      take the factory's monitor. The same layout already took it at `f352c6008c`, in `getFontResource`, and the name
      methods entered it on every call through `getFullNameToFileMap`.
28. **A lookup runs twice on one single-threaded path** (the whole-tree concurrency review). Where `getSlotForFont`
    answers -1 because the factory does not know the name, `addSlotFont` goes on to `addNativeFallback`, which now
    scans the added fonts once more with `getSlotForFontNoCreate`, under the lock. And `CompositeStrike.getStrikeSlot`
    calls `getNumSlots` again on each call while it has published no slot strike, which lasts only while no slot it
    was asked for has a font; `f352c6008c` stored the empty array once. Both stay in memory, make no factory lookup or
    native call, and answer what `f352c6008c` answered.
29. **The hardening round's test adaptations** (rulings L1 to L4). The lent-font check calls
    `MacFontReleaseVerdict.decide` directly, where the other verdict checks of `MacFontReleaseTest` use the class's
    private helper, so that the existing wiring pin fits it unchanged. Its `query.sameList` requires both baseline
    regions to give the font's cascade list, so the second baseline is known to have run. The `REFUTED` figures that
    `MacFontReleaseVerdictTest` labels as C-M6's on this Mac come from an exploratory run of C-M6 before the round's
    checksums were taken (`impl2/LAST/C-M6-explore-last.log`); the recorded run of C-M6 gave the same figures. The
    null stores are pinned by a new helper, `assertNeverStoresNull`, not by a variant of
    `assertPublishesNewArrayOnceFilled`, whose callers keep what it accepts; one sentence of its javadoc points to the
    new helper.
30. **The face lookup of the Copilot review** (F5 and F6). Each layout of a run whose face is remembered missing now
    copies the run font's URL and stats its file, where it did nothing; neither creates or parses a face, and neither
    was measured. The miss map holds one record per name and file (R2), replaced by a miss of a new version of that file
    and kept once the face is found, which `getSlotForFont` then finds by name first; it grows with the distinct pairs
    of name and file layout meets, never with versions, and has no other bound, as the set it replaces had none. Callers
    of the same name and file that come while a lookup runs wait for it, the render thread included; where it fails they
    lay their run out with no slot for this layout, as the leader does, and the next layout looks again. The lookup's
    own re-check, once it is the one in flight, asks the factory's map through `getFontResource`, the question
    `getSlotForFont` asked just before. `CTFactory`'s constructor is package-private, for the test factory of
    `CTFactoryShim`; `getFactory` stays its one production caller.

CI run 45022158121 ran `40e53db102` and CI run 45041254324 ran `f352c6008c`, both on macOS 15 (arm64 and x64),
Linux x64 and aarch64 and Windows x64: see "Measured". CI run 45278364431 ran the further fixes, `e1de3dd32a`, green
on macOS 15 (arm64 and x64), Linux aarch64, Windows x64 and No JNI, its Linux x64 job stopped before it compiled
anything (F1 under "The Copilot review"). Not run: the Copilot review's fixes anywhere but this Mac (macOS 27.2.1
arm64), so their outcomes on the CI hosts are predicted; the further fixes on Linux x64; the gate as such on the CI fix
or the further fixes alone; and any robot or `tests/system` test, which the owner runs. Since then CI run 45456403357
ran `2c9003b909`, which holds the Copilot review's fixes, green on all six jobs, its Linux x64 job on the second
attempt; the Windows and Linux round of 2026-11-24 ran on Windows and in WSL, on no CI host and nowhere on macOS.

### Acceptance criteria
1. ✅ Sites 1-7 hand none of their listed inputs to CoreFoundation, CoreGraphics or CoreText as NULL or 0; each
   outcome is stated in the test that pins it, in a child JVM. Site 5's guard rested on review (R5) until the
   further fixes pinned it in the class file; they also guard the NULL inputs of item 6 and 7, outside the sites.
2. ✅ Site 9 creates at most one context for the cache, whatever the threads do, and since the further fixes two
   threads drawing at once each get only their own pixels (measured; the lock order is review).
3. ✅ Site 8 fences the strike until `createCTLine` returns (pinned as bytecode; that it suffices at run time is
   review).
4. ✅ Site 10 leaves under 10 blocks per call where 230 were left, on platform and virtual threads (measured, bound from
   this machine; since the Copilot review (F3) a virtual caller that cannot start the thread gets the error and no
   caller makes a list without a pool, deviation 7). Site 12 looks the missing face up once in 5 layouts where it did 5
   times, and since the further fixes a file it could not read is looked at again at the next layout. On the macOS 15
   runners neither can be shown by the checks of the CI fix (a region without a pool leaves about 6 blocks per call, and
   the child printed no lookup of a face missing from its file): on CI run 45041254324 the three checks skipped there
   with their figures, and the sites are pinned by the class-file checks (any OS) and the pop and face-memory tests (any
   macOS). The further fixes add the pool tokens and a missing face the child makes itself, which can prove both there,
   and on CI run 45278364431 both proved them on both macOS 15 runners. Since the Copilot review (F5, F6) the missing
   face is looked up once while the run font reports the same file in the same version, and again once the file or its
   path changes, and callers that need it in one file at once share one lookup: one read and one copy for three callers,
   found, missing or failed (measured with a held read); since R2 each file of a name is looked up and remembered apart
   (two files read once each over 6 alternating layouts).
5. ✅ A short or null `bb` is left as it was, and a `CGRect` with a null half throws `NullPointerException` in
   `OS.rect` before any downcall, both pinned.
6. ✅ 27 of 29 mutants killed on the finished tree after the touch-ups, where R3 and R5 survived, 3 of 3 after the
   test-only fixes and 12 of 12 on the CI fix's final tree. Of the further fixes' 256 mutants 253 were killed, and so
   were the 14 of US-111's rows run again, R3 and R5 among them, each on this Mac on the tree of its cluster or of the
   later step that ran it again; that they are still killed on the final tree rests on the argument under "What ran
   where". L2 does not compile, C-M14 is equivalent and M8g7 survives (deviation 20). The Copilot review's 25 rows of F2
   to F4 and 27 of F5 and F6 were all killed, the second round's on its final tree, and so were the 6 rows of R1 to R3
   and 7 rows of F5 and F6 run again on R1 to R3's shape, and the fifth round's 3 rows and R4-6 again. The paths no test
   provokes are under "Rests on review". On Windows and Linux (2026-11-24, "Windows and Linux"): the implementer's 9
   rows were killed on both systems; of the review's 77 runs, 39 on Windows and 38 on Linux, DW1 survived on Windows
   (`renderShape`'s guard, which PR 71 did not change), FB3 to FB5 on Linux, killed there since by the new cap test, and
   LF3 to LF5 on both (`LogicalFont`'s cap, for which a test was declined); the rework's 4 rows on both systems, the
   confirming review's 8 on both, the 2 run again after the move on both and the move's confirmation's 3 were all
   killed, and the rework's negative control failed the tests on both.
7. **✅ on this machine; the comparison on the golden machine remains for the owner.** No golden row changed for
   the twelve sites, and the five corpora are identical before and on the final tree. Item 8 of the further fixes
   moves ten rows of `mac-layout-golden.txt`, edited by hand with the reason (deviation 16), and 609 rows of
   `directwrite-metrics-golden.txt`, which the owner re-captures on its machine, since 240 of them cannot be derived
   from the file (the Copilot review, F1). The Copilot review changes no golden file. Since 2026-11-24 the DirectWrite
   golden is captured again on its Windows machine: exactly those 609 rows moved, and its comparison passes there with
   the flag. On Linux, where the goldens are no oracle, a dump of the four corpora is unchanged between `abe0943d31`
   and `2c9003b909` ("Windows and Linux").
8. ✅ `CFNative`, `CGNative` and `CTNative` unchanged, a fourth facade beside them (deviation 7), no pin moved; since
   the further fixes `CFNative.CFRetain` is public (deviation 22) and `ObjCNative`'s token a `long` (deviation 12),
   and still no pin moved. The Copilot review's F5 moves one call-site pin, `OS.CTFontCopyURLAttribute` from
   `CTFactory` to `CTGlyphLayout`, recorded with its reason under "The `MacFontBindings` pins"; no facade changed.
9. ✅ Line counts and line lengths as above (measured); the other rules rest on the reviews.

**Remains for the owner:** the commit of the Windows and Linux round of 2026-11-24 (five test files, "Windows and
Linux" under "Done") and a `submit.yml` run of it, read for the verdict lines of both macOS jobs (see "Verification")
and for the counts of `PrismFontFactoryTest` and `CompositeFontLocksTest` in every job, since the new cap test and the
OS branch of one slot test first run on macOS there; the reply to Copilot's F1 thread and the PR description's scope
(drafts kept outside the repository); the upstream report (a draft kept outside the repository); the robot and
`tests/system` runs; and the flagged comparison on the macOS golden machine, which now includes the ten rows edited by
hand. The further fixes are committed as `e1de3dd32a` and the Copilot review's fixes (F2 to F6 and R1 to R4) as
`2c9003b909`, which CI run 45456403357 ran on all six jobs, its Linux x64 job on the second attempt; the DirectWrite
golden is captured again (deviation 16).
