# US-067 — Fix the CoreText NULL crashes and leaks kept by the FFM migration

**Status:** ✅ Done (2026-11-13, uncommitted, PR pending; filed 2026-10-24 from the macOS half of US-003; inputs
1-6 have a defined outcome and sites 7-10 release what they create, each pinned by a test with mutant proof
repeated on the final tree; the strike's CTFont window of site 10 has no behaviour test, one of its two releases
is pinned as a call site and the rest is review; the per-fix commits and the comparison on the golden machine
remain for the owner, see "Done" below) ·
**Found:** 2026-10-24, US-003 macOS audit (the C of commit 28b02ef511, kept on purpose by the behaviour-neutral
migration)

## Story
As the maintainer of the macOS font layer,
I want the font code never to hand NULL to CoreFoundation and to release every CF object it creates or copies,
so that a missing font, attribute or table cannot crash the JVM and long runs do not leak CF objects.

## Problem
Paths are relative to `modules/javafx.graphics/src/main/java/com/sun/javafx/font`; each site is named by its
method. The C of commit 28b02ef511 did the same at every site. US-003 kept it so that the migration
stayed behaviour-neutral, and the goldens leave these inputs out (class javadocs of `MacCoreTextGoldenTest`,
`MacFontFinderGoldenTest` and `MacDFontGoldenTest`).

**Crashes: a NULL reaches CoreFoundation or CoreText, which ends the JVM.**

1. `coretext/CTGlyphLayout.java` (`getFontSlot`): `OS.CFRetain(newRef)` when `CTFontCreateCopyWithAttributes`
   answers 0.
2. `MacFontFinder.java` (`getFont`): `CTFontCopyFamilyName`, `CFRelease(family)` and `CFRelease(font)` when
   `CTFontCreateUIFontForLanguage` answers NULL for the type, or the font has no family name.
3. `MacFontFinder.java` (`getCascadeList`): with `fontRef` 0, `CTFontCopyDefaultCascadeListForLanguages` and
   `CFArrayGetCount` get NULL, and `coretext/CTFontFile.java` (`getCascadingInfo`) passes `getFontRef(0f, null)`
   unchecked; `CFRelease(displayName)` when a cascade descriptor has no display name.
4. `DFontDecoder.java` (`getCTFontFormat`): `CFNumberGetValue` and `CFRelease` when the font has no format
   attribute.
5. `DFontDecoder.java` (`getCTFontTable`): `CFDataGetLength` and `CFRelease` when the font lacks the tag.
6. `coretext/OS.java` (`CGRectApplyAffineTransform`, `CGContextFillRect`): a null `CGRect` or `CGAffineTransform`
   mirror. The C crashed; the Java already throws `NullPointerException` before any downcall, but no test pins
   that.

**Leaks: CF objects that are never released.**

7. `MacFontFinder.java` (`getCascadeList`): the `kCTFontNameAttribute` copy, whose result is unused, and the `url`
   copy, for every cascade entry.
8. `coretext/CTGlyphLayout.java` (`getFontSlot`): the extra `CFRetain` ("hope not needed"), one reference per
   fallback font.
9. `coretext/CTFontFile.java` (`getCascadingInfo`): the CTFont that `getFontRef(0f, null)` creates for the cascade
   lookup.
10. Exception paths: a Java exception thrown between a CF create or copy and its release leaks the object. That is
    an `OutOfMemoryError` from a heap allocation in any FFM body (the C had no such path after its allocation
    checks), and `coretext/GlyfTableBounds.java` (`bounds`), where `boundingBox` throws on a short or null `bb`
    before the `glyf` copy is released (the only caller passes `new int[4]`).

## Acceptance criteria
1. **No crash.** None of inputs 1-6 hands NULL to CoreFoundation, CoreGraphics or CoreText. Each has a defined
   outcome that is not a crash, stated in the test that pins it.
2. **No leak.** Every CF object created or copied at sites 7-9 is released exactly once. On the exception paths of
   10 the objects are released before the exception leaves the method; a test covers each path a test can
   provoke, and the review checks every create or copy against its release on every exit path.
3. **One behaviour commit per fix**, each with a test that fails without the fix and passes with it (mutant proof
   recorded in this story). A crashing input runs in a child JVM, so that a regression fails the test instead of
   ending the test fork.
4. **Goldens.** No row of the five `mac-*-golden.txt` files changes, except where a fix changes a recorded crash or
   leak; such a row moves in that fix's commit, with the reason.
5. **Facades frozen.** `CFNative`, `CGNative` and `CTNative` do not change unless a fix needs a descriptor change;
   the facade test's descriptor table then changes with it.
6. **Code rules.** Java 27 final features only, no `var`; production files at most 900 lines (`wc -l`); lines at
   most 120 columns, never several statements on one line; comments only for a non-obvious "why".

## Verification
On a macOS host with JDK 27 and Maven, from the repository root, before and after each fix:
```
# The macOS font gate: 0 failures, 0 errors, 0 skipped (the last flag only on the machine the goldens came from)
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
A leak shows as a retain-count change, as `MacFontStressTest` measures it. The US-003 baseline of the gate is 123
tests, 0 failures, 0 skipped.

## Out of scope
- Objects kept for the life of the process by design: `CTGlyph`'s `GRAY_COLORSPACE`, `RGB_COLORSPACE` and
  `cachedContextRef`.
- The C's stack-array overflow and bitmap over-read, which the Java already turned into no crash and into an
  `IndexOutOfBoundsException` (US-003).
- Any other behaviour change in the font layer; each goes to its own story.

## Definition of Done
- All acceptance criteria met, with the before/after logs kept with the change.
- An independent review (`ffm-reviewer`) repeated the macOS gate.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without
  explicit approval from the repository owner.**

## Notes
- Related: US-003 (its macOS half kept these on purpose; see its "macOS half (2026-10-24)").
- Follow-up: US-111 (the NULL inputs outside inputs 1-6 and the other font-layer sites this story traced and left;
  see "Deviations" under "Done").
- Evidence kept with the change: `backlog/US-067-evidence/` (its `README.md` names each file).

## Done (2026-11-13, uncommitted)

### What changed
Paths as under "Problem". 10 production files, 5 shims (2 new) and 9 test files (4 new);
`git diff --stat HEAD -- modules` over the 18 tracked files: 543 insertions, 304 deletions.
- **Crashes (fixes 1-6).** `CTGlyphLayout.getFontSlot` hands the copy to a new `addFallbackFont`, which refuses 0.
  `MacFontFinder.getFont` and its new `familyName` answer null for a NULL font or name; `getCascadeList` answers an
  empty array for a 0 font or a NULL list, and `addCascadeEntry` releases only what is not NULL.
  `CTFontFile.getCascadingInfo` skips a 0 font and a null name. `DFontDecoder.formatOf` (new) answers 0 and
  `getCTFontTable` null for NULL, and `decode` then throws its `IOException`. Fix 6 changes no production line.
- **Leaks (fixes 7-10).** `addCascadeEntry` releases the name and URL copies. `addFallbackFont` has no extra
  `CFRetain`, and `CTFactory.createFontFile` releases a reference no font file took. `getCascadingInfo` releases its
  CTFont. Every CF or CG create or copy in `GlyfTableBounds`, `DFontDecoder`, `MacFontFinder`, `OS`, `CTFontFile`,
  `CTGlyph` and `CTGlyphLayout` is released in a `finally`, or in a `catch` that rethrows where the object is
  returned on success.
- **The strike's CTFont (item 10).** The CTFont a `CTFontStrike` creates was owned by nothing until
  `PrismFontFile.getStrike` had registered the strike's disposer. Now `CTFontStrike.createDisposer` releases it
  and zeroes `fontRef` when `new CTStrikeDisposer` throws, and `getStrike` (`PrismFontFile.java`) calls
  `disposer.dispose()` when `Disposer.addRecord` throws; both rethrow. `DWFontStrike` and `FTFontStrike` have no
  disposer, so the new `try` of the shared `getStrike` is entered for CoreText only.
- **Tests.** New `MacFontNullInputTest` (9 tests) and `MacFontReleaseTest` (7), each scenario in a child JVM
  (`MacFontNullInputChild`, `MacFontReleaseChild`); new shims `MacFontProbeShim` and `CoreTextPeerShim`, two
  methods in `MacFontFinderShim` (`familyName`, `cascadeEntry`) and one each in `DFontDecoderShim` (`formatOf`) and
  `FontResourceShim` (`newUncachedEmbeddedFontFile`). `MacFontBindings`: the pins and the three other edits below.
  Javadoc only in the three golden tests that listed these inputs as left out; `MacFontStressChild.delta` and `Call`
  are package-private.

### Measured (2026-11-12 and 2026-11-13; Apple M6, arm64, macOS 27.2.1 (26B131), JDK 27.0.1.0.2, Maven 3.10.0)
Before: a clean tree at HEAD `559ca936af`. After: the same commit with the final tree of this change. Commands as
under "Verification"; the gate was run both without and with `-Djfx.parity.require=true`. The logs behind both
columns are in `backlog/US-067-evidence/` and named under "Logs". Run / failures / errors / skipped:

| run | before | after |
| --- | --- | --- |
| gate, no flag (exit 0) | 466 / 0 / 0 / 8 | 482 / 0 / 0 / 8 |
| gate, `-Djfx.parity.require=true` (exit 1) | 466 / 8 / 0 / 0 | 482 / 8 / 0 / 0 |
| module, `javafx.graphics` (exit 0) | 26,680 / 0 / 0 / 858 | 26,696 / 0 / 0 / 858 |
| module, jslc and `javafx.base` | 196 / 0 / 0 / 0, 5,510 / 0 / 0 / 22 | the same |
| `hs_err_pid*` finds, golden `git diff --stat` | nothing | nothing |

- **Why the flagged gate cannot pass here.** The goldens were captured on macOS 27.0.1 (26A434) and this machine
  now runs 27.2.1, so no golden is an oracle on it. The same 8 golden comparisons, one per class, skip without the
  flag and fail with it ("the oracle could not run"), before and after, and nothing else fails:
  `MacCoreTextGoldenTest`, `MacDFontGoldenTest`, `MacFontFinderGoldenTest`, `MacFontProcessGoldenTest`,
  `MacGlyphLayoutGoldenTest`, `MacAccessibleNativeTest`, `MacClipboardNativeTest` and `MacPixelsNativeTest`. No
  golden was re-captured.
- **In place of the oracle.** The five font corpora were dumped on this machine before (twice, identical, so no
  key is unstable) and after: 2,583, 260, 599, 558 and 1,580 lines, `diff -r` empty. No recorded value moved.
  The "after" dump was taken twice: after fix 10c, and again on the final tree, after the strike fix, the
  `CTFontStrike` pin and the second mutant pass (`ab-final.log`: exit 0, 1 test, 0 skipped, all five corpora
  run to the end; `ab-final-diff.log`, the `diff -r` against the first "before" dump, is 0 bytes).
- **123 to 139, and 466 to 482.** The font classes run 123 tests before, the US-003 baseline, and 139 after: +9
  `MacFontNullInputTest`, +7 `MacFontReleaseTest`. `Mac*Test` now also selects the 14 Glass classes of
  `test.com.sun.glass.ui.mac`, 343 tests before and after, so the pattern totals 466 and 482. The module run gains
  the same 16 tests.
- **The whole "Verification" block was run after every fix**, that is after each of the 12 fix steps (1-9, 10a,
  10b, 10c): the gate without and with the flag, the full module run, the two crash-file finds and the golden
  `git diff --stat`. The gate grew from 467 to 482 with the same 8 skipped or failed, the module run had 0
  failures, and each checks log is 0 bytes (`fix-NN-{gate-noflag,gate-require,module,checks}.log`, in the
  session scratch directory named under "Logs"). The block was run again after the strike fix (`final2-*.log`,
  scratch) and after the `CTFontStrike` pin, on the final tree (`final3-*.log`, in the evidence directory): the
  numbers of the "after" column each time.
- After the second mutant pass and the second dump, the gate without the flag and the three checks were run once
  more on the final tree: 482 / 0 / 0 / 8, exit 0, the same 8 classes skipped (`final4-gate-noflag.log`), and
  `final4-checks.log` is 0 bytes. The flagged gate and the module run were not repeated a fourth time.

### Criterion 1: the outcome of inputs 1-6
`MacFontNullInputTest`, one test per row, each in a child JVM. Before its fix the child of rows 1 to 5 died:
exit 133 (SIGTRAP, no `hs_err` file) for 1, 2a, 2b and 3c, exit 134 with an `hs_err` file for 3a, 3b, 4 and 5
(`CFArrayGetCount`, `CFNumberGetValue` and `CFDataGetLength` of NULL).
- **1** A run font CoreText did not copy takes no fallback slot (-1), and its glyphs go to slot 0:
  `aRunFontCoreTextDidNotCopyTakesNoFallbackSlot`.
- **2a** A UI font type without a font has no name (null); `PrismFontFactory` then falls back to Lucida Grande:
  `aUiFontTypeWithoutAFontHasNoName`. **2b** A font without a family name has no name (null):
  `aFontWithoutAFamilyNameHasNoName`.
- **3a** A zero font has an empty cascade list: `aZeroFontHasAnEmptyCascadeList`. **3b** An embedded font that
  CoreGraphics rejects keeps the four hard-wired fallbacks and no other:
  `anEmbeddedFontCoreGraphicsRejectsKeepsTheHardWiredFallbacks`. **3c** A cascade entry without a display name has
  a null name, which the lookup skips: `aCascadeEntryWithoutADisplayNameHasANullName`.
- **4** A font without a format attribute has format 0, so the decoder refuses it:
  `aFontWithoutAFormatAttributeHasFormatZero`.
- **5** A table the font lacks is null, and the decoder then refuses the font: `aTableTheFontLacksIsNull`.
- **6** A null `CGRect` or `CGAffineTransform` mirror throws a `NullPointerException` in `OS.rect` or
  `OS.transform`, before any downcall, and the context still fills afterwards:
  `aNullMirrorThrowsBeforeAnyDowncall`. This only pins what the Java already did.

### Criterion 2: the leaks of sites 7-10
`MacFontReleaseTest`, each in a child JVM. A retain delta is the retain-count change of the watched object over 20
calls, next to a control that leaks one reference per call on purpose (20, or 40 for the layout line).

| row | site | before | after |
| --- | --- | --- | --- |
| 7 | cascade list: name copy | retain delta 20 | 0 |
| 7 | cascade list: URL copies (38 URLs) | 75.42 heap blocks left per call | 0.85 and 0.0 (bound 19) |
| 8a | three fallback fonts taken by reference | retain count 2, a fresh copy has 1 | 1 |
| 8a | a font handed over as a fallback | retain count 2 to 3 | 2 to 2 |
| 8b | a reference no font file took | 2 to 2 | 2 to 1 |
| 9 | CTFont of the cascade lookup | retain delta 20 | 0 |
| 10a | `glyf` copy, short or null `bb` | retain delta 20 | 0 |
| 10b | table copy, `OutOfMemoryError` | retain delta 20 | 0 |
| 10c | layout line, a run that throws | retain delta 40 | 0 |

Tests, in row order: `theCascadeListReleasesTheNamesAndUrls`, `aFallbackFontTakenByReferenceIsOwnedOnce`,
`aFontReferenceIsReleasedUnlessAFontFileTakesIt`, `theCascadeLookupReleasesItsFont`,
`aBoundsArrayThatThrowsStillReleasesGlyf`, `aTableTooLargeForTheHeapIsStillReleased` (a child with `-Xmx64m` and
a table of 191,134,508 bytes) and `aLayoutWhoseRunThrowsStillReleasesItsLine`. The fallback fonts found here are
`.SF Hebrew`, `.SF Arabic` and `.ThonburiUI Regular`. The review walked every create or copy in `MacFontFinder`,
`DFontDecoder` and the `coretext` package against its release on every exit path; see "Rests on review" for what
no test provokes.

**The strike's CTFont window is closed in this story.** No behaviour test covers it: both halves open only on an
`Error` such as an `OutOfMemoryError` from an allocation, so no test provokes them and no test seam was added.
That each release runs on the right path rests on review.
The reviewer traced four paths, one release each: `new CTStrikeDisposer` throws (the `catch` of `createDisposer`;
no record exists), `Disposer.addRecord` throws (the `catch` of `getStrike`; `CTStrikeDisposer.dispose` is
synchronized and guarded by `disposed`, and registration is `addRecord`'s last effect, so no record exists),
`strikeMap.put` throws after registration (the Disposer thread), and success (the Disposer thread). The new
release in `CTFontStrike` is pinned in `NATIVE_CALL_SITES` as a call site, and deleting it fails that check; see
"The `CTFontStrike` pin" below. The `disposer.dispose()` of `getStrike` has no pin.

### Criterion 3: mutant proof
One row at a time: only the named production lines edited, the row's test run, the lines put back, the test run
again. The test of a row is that of the same row above. The pass was taken twice, and all 17 mutants were killed
both times: each mutant run is exit 1, 1 test, 1 failure, and each restored run exit 0, 1 test, 0 failures.
- **First pass, on the tree after fix 10c**, before the strike fix and the `CTFontStrike` pin. After it the tree
  was byte-identical to the one before it (two snapshots of 128,901 bytes, `diff` empty).
- **Second pass, on the final tree** (`final.cumulative.patch`, with the strike fix and the pin). The tree was
  byte-identical to `final.cumulative.patch` before the pass, after it, and at the end of the session's last
  runs (three snapshots of 129,683 bytes, `cmp` identical each time), and both `hs_err_pid*` finds printed
  nothing after it. Logs: `mutant2-<row>.log` and `mutant2-<row>-restored.log`, in the evidence directory.

The table gives the failure lines of the second pass. They are those of the first pass, crash-file names aside,
except row 7b, which read 74.85 heap blocks then. The second pass also mutated the pin and the strike fix; both
are under "The `CTFontStrike` pin" below.

| row | the only production edit | failure line (short) |
| --- | --- | --- |
| 1 | `addFallbackFont`: `newRef == 0` guard deleted | `expected: <-1> but was: <java.lang.NullPointerException>` |
| 2a | `getFont`: `font.address() == 0` guard deleted | `child JVM 'null.uiFont' exited with 133; hs_err files: []` |
| 2b | `familyName`: NULL guard deleted | `child JVM 'null.familyName' exited with 133; hs_err files: []` |
| 3a | `getCascadeList`: both guards deleted | `child JVM 'null.cascadeFont' exited with 134`, one `hs_err` file |
| 3b | `getCascadingInfo`: `ref != 0` dropped | `child JVM 'null.cascadeEmbedded' exited with 133; hs_err files: []` |
| 3c | `addCascadeEntry`: `displayName` released unguarded | `child JVM 'null.cascadeName' exited with 133` |
| 4 | `formatOf`: NULL guard deleted | `child JVM 'null.format' exited with 134`, one `hs_err` file |
| 5 | `getCTFontTable`: NULL guard deleted | `child JVM 'null.table' exited with 134`, one `hs_err` file |
| 6 | `OS.rect`, `OS.transform`: null answers `NULL` | `expected: <...NullPointerException@...OS.rect> but was:` (a) |
| 7a | `addCascadeEntry`: `release(name)` deleted | `retain.delta.name = 20, expected 0` |
| 7b | `addCascadeEntry`: `release(url)` deleted | `a cascade list call leaves 74.56666666666666 heap blocks in use` |
| 8a | `addFallbackFont`: `OS.CFRetain(newRef)` re-added | `has retain count 2 where a fresh copy has 1` (b) |
| 8b | `createFontFile`: release in the `finally` deleted | `expected: <1> but was: <2>` |
| 9 | `getCascadingInfo`: no release | `expected: <0> but was: <20>` |
| 10a | `GlyfTableBounds.bounds`: `glyf` release after, no `finally` | `expected: <0> but was: <20>` |
| 10b | `getCTFontTable`: release after `toArray`, no `finally` | `expected: <0> but was: <20>` |
| 10c | `CTGlyphLayout.layout`: release after the runs, no `finally` | `expected: <0> but was: <40>` |

(a) `<java.lang.IndexOutOfBoundsException@jdk.internal.foreign.AbstractMemorySegmentImpl.throwOutOfBounds>`: another
exception from another top frame, not a dead child. (b) For each of the three fonts, and `moved its retain count
from 2 to 3` for the font handed over.

Row 1's mutant does not crash on either tree (the `CFRetain` that crashed is gone since fix 8): the child
lives and the slot is still -1, so only the test's no-factory assertion kills it. The crash of input 1 is the run
before fix 1: `child JVM 'null.fallbackCopy' exited with 133; hs_err files: []` (`CFRetain` of NULL). The `hs_err`
files of rows 3a, 4 and 5 were cleared by the green re-runs, in both passes.

**Rests on review** (no test pins it):
- Fix 3: the `fontRef == 0` guard of `getCascadeList` taken alone (CoreText answers NULL for a NULL font here, so
  either guard alone gives the outcome), and the `name == null` half of the skip in `getCascadingInfo`.
- Fix 5: `decode`'s `IOException("Could not get tables for Dfont")` for a null table.
- Fix 8: the release when `new CTFontFile(...)` or `OS.CTFontCopyURLAttribute` throws, and the `fontRef != 0` half
  of the release condition.
- Fixes 10b and 10c: every `finally` and `catch` other than the table copy of `getCTFontTable` and the line of
  `layout`. An `OutOfMemoryError` between a create and its release is not provoked site by site.
- The strike fix: that the `catch` of `CTFontStrike.createDisposer` runs on the right path and releases the
  right object (the call site itself is pinned, see below), and the whole `catch` of `PrismFontFile.getStrike`,
  which has no pin and was not mutated.

### The `MacFontBindings` pins that moved
Each new string was copied from the witness failure and confirmed with `javap -c -p` on the final classes. A
release in a `finally` whose `try` has one normal exit counts twice: javac copies it to that exit and to the
exception handler. A `catch` body counts once.

| fix | class | pin | reason |
| --- | --- | --- | --- |
| 8 | `CTFactory` | + `OS.CFRelease*2` | `createFontFile`: one `finally` |
| 8 | `CTGlyphLayout` | - `OS.CFRetain` | the extra retain is deleted |
| 8 | `UNCALLED_ENTRY_POINTS` | + `OS CFRetain(J)V` | only shims call it now; `OS.CFRetain` is kept for them |
| 9 | `CTFontFile` | `OS.CFRelease*6` to `*8` | `getCascadingInfo`: one `finally` |
| 10c | `CTFontFile` | `OS.CFRelease*8` to `*15` | one `catch` (1), six more `finally` (12), fix 9's (2) |
| 10c | `CTFontFile` | `OS.CGPathRelease*3` to `*6` | three methods, one `finally` each |
| 10c | `CTGlyph` | `OS.CGContextRelease*2` to `*5` | `createContext` `catch` (1), two `finally` (4) |
| 10c | `CTGlyphLayout` | `OS.CFRelease*5` to `*10` | the five releases each moved into a `finally` |
| strike | `CTFontStrike` | + `OS.CFRelease` (new entry) | `createDisposer`: one `catch` |

The 15 of `CTFontFile`: the constructor's `catch` 1, `registerFont` 4, `createCGFontForEmbeddedFont` 6,
`getFontRef` 2, `getCascadingInfo` 2. No other entry of `NATIVE_CALL_SITES` or `UNCALLED_ENTRY_POINTS` moved.
`MacFontBindings` has three edits that are not pin strings: `com/sun/javafx/font/MacFontProbeShim` was added to
the observer-shim list (`OBSERVERS`), the message of `peerCallSiteProblems` and the comment above
`NATIVE_CALL_SITES` were reworded, and `Map.of` became `Map.ofEntries` (the table now has 11 pairs, one more
than `Map.of` takes).

**The `CTFontStrike` pin.** The strike fix first went in without it: the class had no entry, so the witness did
not name it. The independent review found the gap (its F1) and the entry was added. The pin table is enforced by
`MacFontBindings.requireSameBinding` in the five golden classes, before their machine gate, and not by
`MacFontBindingStructureTest`, so the proof ran `MacFontBindingStructureTest,MacFontFinderGoldenTest`: with the
pin 10 tests, 0 failures, 1 skipped (the golden comparison); with the pin mutated to `OS.CFRelease*2` 10 tests,
1 failure; restored, 0 failures again (`pin-strike-{green,mutant,restored}.log`). The mutant's failure line:
```
com/sun/javafx/font/coretext/CTFontStrike calls [OS.CFRelease] where NATIVE_CALL_SITES pins [OS.CFRelease*2]:
a call site moved to another binding
```
(one line in the log, wrapped here).

**Both repeated in the second mutant pass, on the final tree.**
- **The pin mutant**, `OS.CFRelease` to `OS.CFRelease*2`, same two classes: exit 1, 10 tests, 1 failure, with the
  failure line above (`mutant2-pin.log`); restored, exit 0, 10 tests, 0 failures, 1 skipped
  (`mutant2-pin-restored.log`).
- **The strike mutant**: `OS.CFRelease(fontRef);` deleted from the `catch` of `CTFontStrike.createDisposer`,
  with `fontRef = 0;` and the rethrow kept. Run with
  `-Dtest=MacFontReleaseTest,MacFontBindingStructureTest,MacFontFinderGoldenTest`: exit 1, 17 tests, 1 failure
  (`mutant2-strike.log`). `MacFontReleaseTest` passed 7 of 7 and `MacFontBindingStructureTest` 8 of 8, so no
  behaviour test sees the mutant. Only the pin check caught it, in
  `MacFontFinderGoldenTest.fontFinderMatchesTheJniGolden`:
  ```
  com/sun/javafx/font/coretext/CTFontStrike calls [] where NATIVE_CALL_SITES pins [OS.CFRelease]:
  a call site moved to another binding
  ```
  (one line in the log, wrapped here). Restored: exit 0, 17 tests, 0 failures, 1 skipped
  (`mutant2-strike-restored.log`).
- So the release is pinned as a call site, and it cannot be dropped unnoticed. The pin does not show that it
  runs on the right path or releases the right object; that rests on review. The second strike release,
  `disposer.dispose()` in `PrismFontFile.getStrike`, was not mutated: `PrismFontFile` is not a pinned class.

### Criteria 4 to 6
- **4, goldens.** `git diff --stat` over the five `mac-*-golden.txt` files prints nothing: no row changed. The
  goldens had no row for a crashing input. "Verification" applies the parity flag only on the machine the goldens
  came from. This machine is not that machine (macOS 27.2.1, goldens from 27.0.1), so the golden comparisons skip
  here, and the corpus dumps, identical before and on the final tree, stand in for them (see "Measured"). A
  comparison on the golden machine remains for the owner.
- **5, facades.** `CFNative`, `CGNative` and `CTNative` have no diff; no fix needed a descriptor change.
- **6, code rules.** `wc -l`: `MacFontFinder` 259, `DFontDecoder` 206, `OS` 476, `GlyfTableBounds` 118, `CTFactory`
  166, `CTFontFile` 355, `CTFontStrike` 183 (175 before), `CTGlyph` 320, `CTGlyphLayout` 192. `PrismFontFile.java`
  went from 1,497 to 1,502 lines; it was over the 900-line budget before this story and is not split here. No
  changed or new file has a line over 120 columns except `OS.java` line 52 (154 columns, older than this story
  and untouched); no `var`, no tab.
  The other three rules rest on the review, not on a tool: the reviewer of the tests and code rules checked "Java
  27 final features only", "never several statements on one line" and "comments only for a non-obvious why". It
  found no `var` and no new multi-statement line: the single-line `if (runRef == 0) continue;` and the
  multi-declarator lines in `CTGlyph` and `CTGlyphLayout` are upstream lines that were only re-indented. The new
  comments are ownership contracts or why-comments.

### Independent review
**The round of record** (`ffm-reviewer`; report: `backlog/US-067-evidence/final-review.md`).
- **On the tree with the strike fix** (18 tracked files, 529 insertions, 291 deletions, and the 6 new files) the
  reviewer read the whole diff: the four strike paths, every CF, CG and CT create or copy against its release on
  every exit, the call order of the healthy path, and criteria 4 to 6. It repeated the gate on that tree, with
  the numbers of the "after" column. Verdict "approve", no defect in production code, two low findings. F1: the
  new release in `CTFontStrike` had no `NATIVE_CALL_SITES` entry. F2: this story and US-111 still described the
  tree before the strike fix. F1 is fixed by the pin above, F2 by this text and by the edit of US-111.
- **On the final tree**, after the pin, the reviewer re-checked the delta and repeated the gate. Its snapshot of
  the tree is byte-identical to `final.cumulative.patch` (129,683 bytes, 24 files); against the tree it had read,
  only lines inside the `NATIVE_CALL_SITES` initialiser of `MacFontBindings.java` changed; `javap -c -p` shows
  exactly one `OS.CFRelease` call in `CTFontStrike`. Gate without the flag 482 / 0 / 0 / 8 (exit 0), with it
  482 / 8 / 0 / 0 (exit 1, the same 8 oracle classes), module 26,696 / 0 / 0 / 858 with jslc 196 and `javafx.base`
  5,510 (22 skipped); both `hs_err_pid*` finds and the golden `git diff --stat` printed nothing
  (`finaltree2-*.log`). Verdict "approve": no finding under `modules/`. It did not repeat the pin's mutant runs.
  The second mutant pass and the second dump came after this review, by the implementer; they changed no file
  (the tree stayed byte-identical to `final.cumulative.patch`).
- **Residual risks it names, not findings.** An `OutOfMemoryError` while the JDK allocates the `MemorySegment`
  for a pointer a create or copy returned leaks that object; nothing at this layer can release it. The
  `PrismFontFile` change is shared by every platform and was checked for Windows and Linux by reading and
  bytecode only.

**The earlier round.** Three reviewers read the tree after the first mutant pass: `ffm-reviewer` on ownership and the
gate, `ffm-reviewer` on behaviour neutrality, and a general reviewer on the tests and the code rules. All three
said "approve"; those verdicts went to the session's manager and are not a written report. The one finding, minor:
the class-level `@SuppressWarnings("restricted")` on the new `MacFontProbeShim` was not needed. The implementer
deleted it and repeated the gate and the module run, with the same numbers.

### Logs
Kept with the change, in `backlog/US-067-evidence/`; its `README.md` names each file and the tree it was taken on.
- **Before:** `gate-before.log` (flag), `before-gate-noflag.log`, `before-module.log`, `before-checks.log`.
- **After, the final tree:** `final3-gate-noflag.log`, `final3-gate-require.log`, `final3-module.log` and
  `final3-checks.log`. They back the "after" column: the two gate rows, the two module rows, and the "nothing"
  of the last row (`final3-checks.log`, 0 bytes).
- **The reviewer, the final tree:** `final-review.md`, `finaltree2-gate-noflag.log`,
  `finaltree2-gate-require.log` and `finaltree2-module.log`.
- **The second mutant pass and dump, the final tree:** the 38 logs `mutant2-<row>.log` and
  `mutant2-<row>-restored.log` (rows 1 to 10c, `pin` and `strike`), `ab-final.log`, `ab-final-diff.log` (0
  bytes), `final4-gate-noflag.log` and `final4-checks.log` (0 bytes).
- **Measurements:** `US-067-measured.md` (the implementer's record; its last section is the second pass) and
  `US-067-macos-gate-per-class.tsv` (34 classes, before and after, both ways). The TSV's "after" columns were
  taken after the first mutant pass; the per-class lines of the two `final3` gate logs are the same.
- **Patches:** `fix-01` to `fix-09`, `fix-10a`, `fix-10b`, `fix-10c`, `fix-10d-strike` and `final`, each
  `.cumulative.patch`: the tree after each fix step, as one patch against HEAD `559ca936af`.

Not copied, and summarised above: the per-fix logs (`fix-NN-{red,green,gate-noflag,gate-require,module,checks}`),
the mutant logs of the first pass (`mutant-<row>.log`), `pin-strike-*.log`, the corpus dumps themselves (`ab/`)
and the intermediate runs (`after-*`, `final-*`, `final2-*`, the reviewer's `finaltree-*`). They are in the
session scratch directory, which is temporary: under
`/private/tmp/claude-501/-Users-benesquivelmusic-Documents-SourceCode-jfx-ffm/`, the directory
`b184fe71-a1a0-4bc9-a84f-7a9056c40664/scratchpad/us067/`.

An evidence-only dump test, `Us067CorpusDumpTest`, was in the tree from fix 1 until after the first mutant
pass. The module runs of those steps therefore show one more test and one more skip (26,697 / 859 after fix
10c), and the patches `fix-01` to `fix-10c` hold it. It was copied in once more for the dump on the final tree
and moved out again straight after. It is not part of the change, and none of `final3`, `final4` and the
reviewer's runs has it.

### Deviations
Decided by the session's manager, for the owner's attention:
1. **The gate was run both ways.** "Verification" asks for 0 skipped under the flag, which only the machine the
   goldens came from can give. Here the flagged gate fails in exactly the 8 oracle classes, before and after.
2. **NULL sites outside inputs 1-6 are not fixed.** The trace found further sites where a NULL can reach
   CoreFoundation, and other font-layer defects; all are listed in US-111.
3. **No commit exists** (criterion 3). Only the repository owner commits; the fixes are kept separable so that
   the owner can commit one per fix or all as one.
4. **The mutant pass and the corpus dump were each taken twice.** The first of each predates the strike fix and
   the pin, so both were repeated on the final tree; see "Criterion 3: mutant proof" and "Measured". In the
   second pass three mutants have another shape than their row names, with the same effect: 3b made
   `if (ref != 0) {` a bare block, 8b deleted only the release and left its `if` empty, and 10c made `try {` a
   bare block with the release after it. While restoring row 1 the implementer applied row 2a's edit by mistake
   and reverted it before any run; a checksum of the nine touched files was verified before every restored run,
   so no run saw two mutants.
5. **Two module runs of the implementer ran as background tasks** (`before-module.log`, `final3-module.log`),
   each alone and awaited, where the session's rules ask for the foreground.

Not run: x86_64, another macOS version, Windows, Linux, and any robot or `tests/system` test (owner-only).

### Acceptance criteria
1. ✅ Inputs 1-6 each have a defined outcome that is not a crash, stated in the javadoc of the test that pins it.
2. ✅ Sites 7-9 release once (measured). The exception paths of 10 release before the exception leaves, the
   strike's CTFont window included: a test for the three paths a test can provoke, review for the rest.
3. **Tests and mutant proof ✅; the commits remain for the owner.** Each of fixes 1 to 10c has a test that fails
   without it (17 of 17 mutants killed, on the tree after fix 10c and again on the final tree) and crashing
   inputs run in a child JVM. The strike fix has no behaviour test, because no test provokes it. Its release in
   `CTFontStrike.createDisposer` is pinned by the call-site check: with the release deleted,
   `MacFontFinderGoldenTest` fails with `CTFontStrike calls [] where NATIVE_CALL_SITES pins [OS.CFRelease]`.
   That it runs on the right path, and the `disposer.dispose()` of `PrismFontFile.getStrike`, rest on review.
   - **Remains for the owner: the commits.** No commit exists, and no agent may make one: only the repository
     owner commits. The fixes are separable: each of fixes 1 to 10c has its own test and its own mutant row, and
     every fix step has a cumulative patch in `backlog/US-067-evidence/`, so that two neighbours differ by one
     fix. The owner decides whether to commit per fix or as one.
4. **✅ on this machine; the comparison on the golden machine remains for the owner.** No golden row changed:
   the golden files have no diff.
   - **Remains for the owner: the flagged golden comparison.** The gate with `-Djfx.parity.require=true` can
     pass only on the macOS 27.0.1 machine the goldens came from, and no agent has that machine. On this one
     (27.2.1) the 8 golden comparisons skip without the flag and fail with it, before and after. In their place
     the dumps of the five corpora, taken before and on the final tree, are identical.
5. ✅ `CFNative`, `CGNative` and `CTNative` unchanged.
6. ✅ Line counts and line lengths as above (measured); the three other rules rest on the review.
