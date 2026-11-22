# US-111 — Fix the CoreText NULL inputs and font-layer defects US-067 left

**Status:** ✅ Done (2026-11-22, uncommitted, PR pending; filed 2026-11-13 from US-067; sites 1-7 hand none of their
listed inputs to CoreFoundation, CoreGraphics or CoreText as NULL or 0, site 8 keeps the strike reachable, site 9 has
one owner, site 10 drains what CoreText autoreleases on any thread (deviation 7), site 11's outcomes are pinned and
site 12 looks a missing face up once per process, each pinned by a test with mutant proof on the finished tree,
except site 5's guard and site 3's guarded name release, which rest on review; the commit, the robot and system tests
and the comparison on the golden machine remain for the owner, see "Done" below; what the work found and did not fix
is US-129) ·
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

## Out of scope
- Objects kept for the life of the process by design: `CTGlyph`'s `GRAY_COLORSPACE` and `RGB_COLORSPACE`, and the
  one cached context that site 9 keeps.
- Re-capturing the macOS goldens for macOS 27.2.1. They were captured on macOS 27.0.1 (26A434) from the JNI build,
  and no story covers their re-capture yet.
- Any other behaviour change in the font layer; each goes to its own story.

## Definition of Done
- All acceptance criteria met, with the before and after results stated in this story.
  ✅ All nine, with the limits stated under "Done"; the flagged comparison on the golden machine remains for the owner.
- An independent review (`ffm-reviewer`) repeated the macOS gate.
  ✅ Each slice's `ffm-reviewer` repeated it without the flag; the final criteria-and-gate review and the
  confirmation on the final tree repeated it without and with the flag (see "Independent review").
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
  ✅ Status and row set on 2026-11-22; no PR exists yet ("PR pending").
- Changes left in the working tree / a review branch — **no commits, merges or PRs without
  explicit approval from the repository owner.**
  ✅ Left uncommitted in the working tree; nothing was committed, merged or pushed.

## Notes
- Related: US-067 (it traced these sites and left them; see its "Deviations").
- Site numbers of US-067's evidence: 1-7 here are its N1-N7, 8 is N8, 9 is N10, 10 is N12, 11 is N9 and 12 is
  N11. Its N13 (the goldens are no oracle on macOS 27.2.1, and `Mac*Test` selects the Glass classes too)
  is not a code site; see "Verification" and "Out of scope".
- Follow-up: US-129 (the defects and test gaps this story's work found beside its sites and did not fix; see
  "Rests on review" and "Deviations and stated properties" under "Done").

## Done (2026-11-22, uncommitted)

### What changed
Paths as under "Problem". 10 production files (one new, `coretext/ObjCNative.java`), 7 shims (2 new) and 8 test
files (2 new); `git diff --stat` over the 20 tracked files: 1,623 insertions, 88 deletions, of which the 9 tracked
production files 263 and 71. The work ran on 2026-11-21 and 2026-11-22 in four slices, A (sites 1, 2, 3, 7), B (4,
5, 6, 11), C (8, 9, 12) and D (10), then five touch-ups and the test-only fixes of the final review.

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
  lock; the static is read and written nowhere else.
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
- **Site 11.** `GlyfTableBounds.boundingBox` reads the four values into locals and stores index 3 first, with a
  one-line why-comment. `OS.rect` is unchanged.
- **Site 12.** `CTFactory` keeps a `ConcurrentHashMap.newKeySet()` of the names, lower-cased with `Locale.ROOT`, whose
  face `findFontIndex` did not find. `createFontFile` adds the name in a new `else` of its existing
  `if (fIndex != -1)`, with no new `return` in its `try`. `CTGlyphLayout.getFontSlot` asks `mayFindFace` after the
  " Regular Regular" fix-up and after `getSlotForFont` answered -1, and makes no copy when the answer is no. The slot
  stays -1.

**Tests and shims.**
- **24 new tests.** `MacFontNullInputTest` gains 9 (sites 1-7 and 11, each in a child JVM of
  `MacFontNullInputChild`), `MacFontReleaseTest` 7 (sites 9, 12 and 10, each in a child JVM of `MacFontReleaseChild`),
  `MacFontBindingStructureTest` 3 (classfile checks of sites 4, 8 and 12, any OS), `GlyfTableBoundsTest` 2 (site 11,
  any OS), and the new `coretext.MacObjCNativeTest` 3 (the new facade, macOS). The new `FontCallOrder` holds the
  classfile order and receiver checks.
- **Shims the witness reads**, reaching the new helpers by reflection and calling no facade: `MacFontFinderShim`
  (`sizeOf`, `matchingDescriptors`, `fontData`, `addUiFont`, `addOwnedDescriptor`, `cascadeList`, `resultOf`) and
  `DFontDecoderShim` (`matchingDescriptors`, `fontNamed`).
- **Shims it does not read:** `CoreTextPeerShim` (`copyOfUIFont`, `advanceFromPlatform`, `NullHalf`,
  `transformRectWithNull`, `fillRectWithNull`) and `CTGlyphShim` (`createContext`, `getCachedContext`); the new
  `CTFontStrikeShim`, a strike whose `isSubPixelGlyph` runs a hook, and `ObjCNativeShim`, the new facade's registry,
  path and `find`.
- **The observer** `MacFontProbeShim`, with its own bindings and no font class: `fontCollection`,
  `setFillColorOfNoContext`, `setAllowsAntialiasingOfNoContext` and `languageCodes`.

### Measured (2026-11-21 and 2026-11-22; Apple arm64, macOS 27.2.1 (26B131), JDK 27.0.1.0.2, Maven 3.10.0)
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
  these changed (`after/before-gate-noflag.perclass.txt`, `after/gate-noflag.perclass.txt`):

  | class | before | after | the new tests |
  | --- | --- | --- | --- |
  | `MacFontNullInputTest` | 9 | 18 | sites 1, 2, 3, 4 (two), 5, 6, 7 and 11 |
  | `MacFontReleaseTest` | 7 | 14 | sites 9, 12 and 10 (five) |
  | `MacFontBindingStructureTest` | 8 | 11 | sites 4, 8 and 12 |
  | `coretext.GlyfTableBoundsTest` | 10 | 12 | site 11 (two) |
  | `coretext.MacObjCNativeTest` | – | 3 | site 10, the new facade |

- **The module run gains the same 24 tests**, all in `javafx.graphics`, with the same skips; of its 954 classes
  before and 955 after, only these five differ (`after/before-module.perclass.txt`,
  `after/after-module.perclass.txt`). In both module logs the result line of
  `test.javafx.scene.layout.BorderStrokeStyleTest` is interleaved with a JEP 500 warning ("Final field hash in class
  javafx.scene.layout.Border has been mutated reflectively by class test.javafx.scene.layout.BorderTest"), so a
  per-class sum of the console comes out 18 short in both; the class's surefire XML gives 18 / 0 / 0 / 0. The warning
  is there at HEAD and outside the font layer, and no story was filed for it.
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
    settling), which the smallest-round rule absorbs. The figures come from this one machine.
  - Files: `slice-D/unfixed-{1,2,3}.*`, `slice-D/fixed-{1..6}.*` and `slice-D/gate-r2.*`.
- **Site 6, the unified log.** On the unguarded code in a child JVM, CoreGraphics wrote one Error-level line per
  setter ("CGContextSetAllows…: invalid context 0x0", subsystem com.apple.coregraphics) and nothing on stderr;
  `CGBitmapContextCreate` of width 0 logs nothing; `log show` for the child's pid took 0.66 to 0.69 s and needed no
  sudo on this admin account (`slice-B/experiment-unguarded.log`, `slice-B/experiment-log-*.txt`).

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
  CoreText answers 0.0 for a NULL font too, so the guard rests on review (R5).
- **6** A context CoreGraphics did not make is 0 and nothing is set on it:
  `aContextCoreGraphicsDidNotMakeIsZeroAndNothingIsSetOnIt`. `createContext(…, 0, 5)` answers 0 with or without the
  guard, so the test reads the unified log of the child's pid.
  - After the subject the child calls two controls through the observer's own bindings,
    `CGContextSetAllowsAntialiasing(NULL)` and then `CGContextSetRGBFillColor(NULL)`.
  - Any setter line beyond the one control line fails the test. No fill-colour line (the log cannot be read) or no
    setter-control line (the setters no longer log a call on NULL) aborts it, with the reason.
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
    one bitmap is US-129).
  - Before the fix: 2 creations, and the two threads got different contexts, one without an owner. After: 1
    creation, "second blocked", both got the cached context (`slice-C/red-1.release.cachedContext.txt`,
    `slice-C/green-1.release.cachedContext.txt`; three more runs the same, `slice-C/stability-{1,2,3}.log`).
- **8** `MacFontBindingStructureTest.layoutKeepsTheStrikeReachableUntilItsLineHoldsTheFont`, a classfile check: every
  `Reference.reachabilityFence` call in `layout` takes the local that `getFontRef` was called on, and the first
  `createCTLine` comes before the first fence. `javap -c -p` shows the fence at both exits of the `finally`. That
  the fence is enough at run time rests on review.

### Criterion 4: sites 10 and 12
- **10, the pool.** `aCascadeListCallOnAPlatformThreadDrainsWhatItAutoreleased` and
  `aCascadeListCallOnAVirtualThreadDrainsWhatItAutoreleased`: fewer than 10 blocks per call, see "Measured". Each
  requires its control (the same region without a pool: the production `cascadeList`, with the codes from the
  observer) to be above 10, the virtual one also an empty hand-off below 10, and every call to give the same list
  (39 entries).
- **10, the wait.**
  - `anInterruptedVirtualCallerGetsTheWholeCascadeListAndKeepsItsInterrupt`: a virtual caller that interrupted
    itself gets the whole list and keeps its interrupt, end to end.
  - `anInterruptTheWaitClearsDoesNotEndItAndIsSetAgainOnceTheTaskAnswers` (added by the test-only fixes): `resultOf`
    on a task that a latch holds. The caller is seen WAITING with `FutureTask.get` above `MacFontFinder.resultOf`,
    which in JDK 27 means a first `get()` saw the flag, cleared it and threw. Then the task's own array comes back
    and the flag is set again. Its `doneWhenParked=false` is a stated precondition that holds by construction.
  - `anErrorOfTheWaitItselfIsThrownOnlyOnceTheTaskIsDone`: a `FutureTask` whose first `get()` throws an
    `OutOfMemoryError` while the task runs; the caller leaves only once the task is done, and throws that error.
- **10, the facade.** `coretext.MacObjCNativeTest`: both functions are bound from `/usr/lib/libobjc.A.dylib` with
  their descriptors, nested pools pop inner then outer (each in a `finally`), and a missing symbol is named.
- **12.** `aFallbackFaceItsFontFileLacksIsLookedUpOnce`, in a child JVM with `prism.debugfonts=true`: 5 layouts of
  the System font with Devanagari, each a fresh run laid out directly.
  - For each name, its `Fallback font= <name> slot=-1` line shows 5 times and its `createFontFile by ref … index=-1`
    line once, and every layout gives the same glyphs.
  - Before the fix: 5 lookups of `.SF Devanagari Regular` in `/System/Library/Fonts/SFIndia.ttc`; after: 1. Both
    trees give `7f;40a;f3;d5;40`, the slot-0 glyphs of golden row `lay.system.s12.t06.r00`
    (`slice-C/red-1.release.fallbackFace.err`, `slice-C/green-1.release.fallbackFace.err`).
  - `MacFontBindingStructureTest.layoutAsksForAFallbackFaceBeforeCopyingTheRunFont`: in `getFontSlot` the
    `mayFindFace` call comes before the font copy.

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

M10a fails only the platform test: the virtual one passes (0.043 blocks per call), because a worker's pool is
drained when its thread exits; that the worker pushes and pops rests on review.

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

### The `MacFontBindings` pins
None moved. `MacFontBindings.java` has no diff, and `requireSameBinding` passed in all five golden classes in every
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
- Unchanged: the 75 functions and 11 data symbols of the three frameworks, and
  `everyFrameworkSymbolIsBoundOnceInTheFacadeItsPrefixNames` (libobjc is no framework).

### Criteria 7 to 9
- **7, goldens.** `git diff --stat` over the five `mac-*-golden.txt` files and over `src/test/resources` prints
  nothing: no row changed. The goldens skip on this Mac, and the corpus dumps, identical before and on the final
  tree, stand in for them (see "Measured"). A comparison on the golden machine remains for the owner.
- **8, facades.** `CFNative`, `CGNative` and `CTNative` have no diff; no fix needed a descriptor change. A fourth
  facade, `ObjCNative`, was added beside them, with its descriptor table in `MacObjCNativeTest` (see "Deviations
  and stated properties", 7).
- **9, code rules.** `wc -l` now (HEAD): `MacFontFinder` 385 (259), `DFontDecoder` 230 (206), `OS` 483 (476),
  `GlyfTableBounds` 124 (118), `CTFactory` 178 (166), `CTFontFile` 356 (355), `CTGlyph` 324 (320), `CTGlyphLayout`
  202 (192), `ObjCNative` 109 (new). `PrismFontFactory` went from 1,891 to 1,893; it was over 900 before this story,
  so the rule does not apply to it.
  - In the 25 files no line is over 120 columns except `OS.java` line 52 (154 columns, older than this story and
    untouched). No tab, CR, trailing whitespace, `var`, wildcard import or executable bit; every file ends in LF;
    the headers of modified files end in 2026 and new files carry 2026.
  - The other rules rest on the reviews: the features used are final in JDK 27 (flexible constructor bodies,
    `java.lang.classfile`, `Thread.join(Duration)`); no line holds two statements (the one-line `if`s of sites 5 and
    6 match their neighbours); the new production comments are ownership javadoc and why-comments, with no story
    number.

### Rests on review
No test covers these paths; each was checked by reading, in a slice review or a final review.
- **Site 3.** The guarded release of the name string in `createCTFont` (R3 survives): nothing makes `toCFString`
  answer NULL. The same NULL in `fontNamed` is pinned by M3c.
- **Site 3.** `fontNamed` returns 0 for a NULL name inside its `try`, so the descriptor array is still released. A
  guard moved above the `try` would leak the array and pass every test.
- **Sites 1, 2, 3, 4 and 7.** The entry points hand CoreText's answer straight to the helpers. The tests hand NULL to
  the helpers, and the witness proves each helper is the method production calls; that nothing sits in between is
  read. `addCascadeEntry` and the `fontData` loop pass array elements on unchecked: a CF-typed array cannot hold NULL.
- **Site 4.** Both callers allocate the transform before the UI font exists, so an `OutOfMemoryError` there leaks
  nothing; it is not provoked.
- **Site 5.** The guard (R5 survives): CoreText answers 0.0 for a NULL font on this macOS.
- **Site 8.** That the fence is enough at run time: the strike can only be lost inside `layout` in a JIT-compiled
  frame during a Disposer race, and the check pins only where the fence is and what it fences. Also read, with no
  change: `CTGlyph.getImage` and `getColorImage` keep the strike through `this` under the glyph monitor, and
  `CTFontFile.getFontRef` relies on its callers holding the file.
- **Site 9.** The lock order, glyph monitor then `CONTEXT_LOCK`: nothing reached under the lock takes a monitor
  (`isSubPixelGlyph` reads a static final and a field; `OS`, the facades and `CTFontStrike` hold no `synchronized`).
  The test proves one creation, not freedom from deadlock.
- **Site 10, the worker.**
  - That the worker pushes and pops: a worker without a pool is hidden by the drain at thread exit (M10a).
  - That a virtual caller never pushes inline: a change of carrier cannot be provoked on demand.
  - The worker's thread is named, daemon and inherits no thread locals; the pooled region takes no monitor, prints
    nothing and calls no font class but the facades. The only monitor a caller holds across the hand-off is that
    of `FallbackResource.getSlotResource`, and the worker needs no class the caller could be initialising, so there
    is no deadlock; pinning is no worse than HEAD's inline call.
  - The language-codes copy sits inside the pool. No mutant moved it above the push; as a Copy function it probably
    autoreleases nothing, so the heap test would likely not see it.
  - The rethrow of a worker failure, and the cast of its cause to `RuntimeException`, which relies on
    `pooledCascadeList` declaring nothing checked: nothing in the region throws on demand.
  - The start-failure fallback: an `OutOfMemoryError` from `Thread.start()` is not provoked. JDK 27's `start()` runs
    nothing after `start0()`, so no worker can be running beside the fallback.
- **Site 10, the wait's own error.** M10d is killed with an injected failure; the real triggers, an
  `OutOfMemoryError` at `FutureTask.java:489` (`new WaitNode()`) or `:484` (`new InterruptedException()`) and a
  `StackOverflowError` in `get()` or park, are not provoked. Three residuals remain, each needing an error at the
  very edge of the stack or the heap (US-129):
  - a `StackOverflowError` at the call of `resultOf` itself, which ends the wait before `get()`;
  - a `StackOverflowError` from `isDone()` in the spin;
  - an `OutOfMemoryError` while `get()` builds its `InterruptedException`, which loses that interrupt (the caller
    still gets the error, once the task is done).
  After such an error the caller spins on its carrier for the rest of the worker's run.
- **Site 10, the push.** An `OutOfMemoryError` while `objc_autoreleasePoolPush` wraps its token leaves one pool
  pushed with no pop. The next pop of an outer pool on that thread, or the thread's exit, drains it, which is HEAD's
  behaviour without a pool (US-129).
- **Site 12.** Two concurrent first layouts may each parse once, as the found path can; the test is single-threaded.
  The skipped repeat lookup stored nothing: `findFontIndex` parses with `register == false` and adds nothing to the
  font maps.

The tests-and-evidence review found four of these on no list of the slice records: the array release of
`fontNamed`, the worker's thread properties, the purity of the region, and the codes copy inside the pool.

### Independent review
**The slices.** Each slice was implemented by the Java 27 agent and reviewed by `ffm-reviewer`, which repeated the
gate without the flag (`review-slice-{A,B,C,D}/gate-noflag.log`: 486, 494, 498 and 504, each exit 0, the 8 skips).
- **A, "fix".** The slice had no record of its mutant pass (should). The implementer took the pass again with diffs
  and `hs_err` copies (11 of 11 killed, R3 survived) and wrote the record. Notes: the R3 trace; the reading of
  criterion 1; a NULL font through `MacFontFinderShim.cascadeList` (US-129).
- **B, "pass".** Notes: the site 6 test checked the control before the setters (touch-up T3); the site 4 check pins
  the order, not the comparison; a shim javadoc said "identity matrix" for NULL (T4).
- **C, "pass".** Notes: a null name in `createFontFile` now throws (see "Deviations and stated properties", 8); the
  site 12 precondition belongs to this macOS build; the site 9 hook in the child, not the shim, accepted.
- **D, "fix".** An `Error` thrown by `get()` itself ended the wait while the worker could still read the font
  (should). Fixed in a review round: the spin on `isDone()`, a new test and mutant M10d. Notes: the heap bound's
  margin and portability; the worker and region properties (now under "Rests on review"); the fully qualified link
  call (T1); the inner pool token not checked (T5).

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

The records of the test-only fixes, of the after-runs, of the confirmation and of this record's fact check are
`close-fix.json`, `close-measure.json`, `close-confirm.json`, `close-docs.json`, `close-docCheck.json` and
`close-docsFix.json`. The evidence-only `Us111CorpusDumpTest` (sha256 `fde7c5c7…`) was in the tree from before
slice A until after the "after" dump; it is not part of the change. Two of its surefire reports remain in
`modules/javafx.graphics/target/surefire-reports` (git-ignored; the next clean or full run removes them).

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
   pins the order of the two calls, not the comparison: `>= 0` for `> 0` would pass it. Site 12's order check
   likewise pins the order, not the condition (US-129).
5. **Site 5's guard has no observable on this macOS** (R5 survives); it rests on review.
6. **Site 6 is proved through the unified log.** On an account that cannot read it the test aborts, a 9th skip in
   the gate; where CoreGraphics no longer logs the setters on NULL it waits its 30 s and aborts. It never passes
   without the log.
7. **Site 10: a fourth facade, `ObjCNative`** (two libobjc functions), beside the three the story freezes;
   `FACADES` and the initialiser check of `MacFontBindingStructureTest` were extended, and no `MacFontBindings` pin
   moved. A virtual caller gets one platform thread per call, and if none can be started the list is made inline
   without a pool. The wait outlasts an interrupt and an error of `get()` itself. Measured here: about 230 blocks
   per call without the pool (the story said about 232), at most 0.283 with it; the bound of 10 comes from this one
   machine.
8. **Site 12 is keyed by name alone, as `storeInMap` is,** so a later run font with the same display name from
   another file is not looked at again. It remembers `findFontIndex`'s -1 whatever its cause, so a file that could
   not be read at the first layout is not retried in the process (US-129). `CTFactory.createFontFile` with a null
   name and no face now throws `NullPointerException` where it returned null; production cannot pass null
   (`getFontSlot` returns first), only a shim caller can.
9. **Site 11: `OS.rect` needed no change;** its outcome is pinned by a test and by M11b.
10. **Tests whose preconditions belong to this macOS build:** the site 12 fallback face, the heap bound and the log
    read. On another build they fail loudly or abort; none passes wrongly (US-129).
11. **No commit exists.** Only the repository owner commits.

Not run: x86_64, another macOS version, CI (`submit.yml`), Windows and Linux (the classfile checks that run on any
OS ran only here), and any robot or `tests/system` test, which the owner runs.

### Acceptance criteria
1. ✅ Sites 1-7 hand none of their listed inputs to CoreFoundation, CoreGraphics or CoreText as NULL or 0; each
   outcome is stated in the test that pins it, in a child JVM. Site 5's guard rests on review (R5).
2. ✅ Site 9 creates at most one context for the cache, whatever the threads do (measured; the lock order is review).
3. ✅ Site 8 fences the strike until `createCTLine` returns (pinned as bytecode; that it suffices at run time is
   review).
4. ✅ Site 10 leaves under 10 blocks per call where 230 were left, on platform and virtual threads (measured, bound
   from this machine; without a startable thread the call runs as before, deviation 7). Site 12 looks the missing
   face up once in 5 layouts where it did 5 times.
5. ✅ A short or null `bb` is left as it was, and a `CGRect` with a null half throws `NullPointerException` in
   `OS.rect` before any downcall, both pinned.
6. ✅ 27 of 29 mutants killed on the finished tree after the touch-ups, and 3 of 3 after the test-only fixes; R3 and
   R5 survive and are on the list under "Rests on review", with the other paths no test provokes.
7. **✅ on this machine; the comparison on the golden machine remains for the owner.** No golden row changed, and
   the five corpora are identical before and on the final tree.
8. ✅ `CFNative`, `CGNative` and `CTNative` unchanged, a fourth facade beside them (deviation 7), no pin moved.
9. ✅ Line counts and line lengths as above (measured); the other rules rest on the reviews.

**Remains for the owner:** the commit, the robot and `tests/system` runs, and the flagged comparison on the golden
machine.
