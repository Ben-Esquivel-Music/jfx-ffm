# US-067 measured evidence

Paths: `REPO` = `/Users/benesquivelmusic/Documents/SourceCode/jfx-ffm`,
`SCRATCH` = `/private/tmp/claude-501/-Users-benesquivelmusic-Documents-SourceCode-jfx-ffm/b184fe71-a1a0-4bc9-a84f-7a9056c40664/scratchpad/us067`,
`LOGS` = `SCRATCH/logs`, `MAIN` = `REPO/modules/javafx.graphics/src/main/java/com/sun/javafx/font`.

## 1. Host, versions, commands

- Host: Apple M6, arm64, macOS 27.2.1 (build 26B131). Apache Maven 3.10.0, JDK 27.0.1.0.2 (Oracle).
- Tree: HEAD `559ca936af` plus the uncommitted US-067 working tree. Runs: 2026-11-12 and 2026-11-13.
- The goldens were captured on macOS 27.0.1 (26A434), so no golden compares on this machine. The gate was therefore
  run twice every time: without the parity flag (the 8 golden comparisons skip) and with `-Djfx.parity.require=true`
  (the same 8 fail in `ParityGate.requireOracle`, "the golden was captured on a different machine"). No golden was
  re-captured.

Commands (from `REPO`, one Maven at a time, each redirected to its log with `> <log> 2>&1; echo "exit=$?"`):

```
# gate, no flag (log <tag>-gate-noflag.log); the flagged run adds -Djfx.parity.require=true (<tag>-gate-require.log)
mvn -pl modules/javafx.graphics -am test -Dsurefire.failIfNoSpecifiedTests=false "-Dtest=Mac*Test,PrismFontFactoryTest,CFNativeTest,CGNativeTest,CTNativeTest,CGPathSinkTest,GlyfTableBoundsTest,CGBitmapReaderTest"
# full module tests (<tag>-module.log)
mvn -pl modules/javafx.graphics -am test
# crash files and goldens (<tag>-checks.log, must be empty)
{ find modules/javafx.graphics -maxdepth 1 -name 'hs_err_pid*' -type f; find modules/javafx.graphics/target -name 'hs_err_pid*' -type f; git diff --stat -- 'modules/javafx.graphics/src/test/resources/test/com/sun/javafx/font/mac-*-golden.txt'; }
# one test (RED, green, mutant, restored runs)
mvn -pl modules/javafx.graphics -am test -DskipNative=true -Dsurefire.failIfNoSpecifiedTests=false "-Dtest=<Class>#<method>"
# A/B dump
mvn -pl modules/javafx.graphics -am test -DskipNative=true -Dsurefire.failIfNoSpecifiedTests=false -Dtest=Us067CorpusDumpTest -Dus067.dump=SCRATCH/ab/<dir>
```

## 2. Results per tag

Format: tests / failures / errors / skipped. The 8 skipped (no flag) and the 8 failed (flag) are in every run exactly
`MacCoreTextGoldenTest.coreTextMatchesTheJniGolden`, `MacDFontGoldenTest.dfontDecoderMatchesTheJniGolden`,
`MacFontFinderGoldenTest.fontFinderMatchesTheJniGolden`, `MacFontProcessGoldenTest.processStateMatchesTheJniGolden`,
`MacGlyphLayoutGoldenTest.layoutMatchesTheJniGolden`, `MacAccessibleNativeTest.theNativesAreThoseOfTheJniBodies`,
`MacClipboardNativeTest.theConversionsAndTheFileReferencePathAreThoseOfTheJniBodies`,
`MacPixelsNativeTest.theAttachChecksAndImagesAreThoseOfTheJniBodies`. Module column: javafx.graphics; in every module
run jslc is 196/0/0/0 and javafx.base 5510/0/0/22. The module run has 1 extra test and 1 extra skip from the
evidence-only `Us067CorpusDumpTest` from fix-01 on (it was created after `before-module.log`).

| Tag | Gate, no flag (exit 0) | Gate, flag (exit 1) | Module (exit 0) | Checks log |
|---|---|---|---|---|
| before | 466/0/0/8 | 466/8/0/0 (`gate-before.log`) | 26680/0/0/858 | empty |
| fix-01 | 467/0/0/8 | 467/8/0/0 | 26682/0/0/859 | empty |
| fix-02 | 469/0/0/8 | 469/8/0/0 | 26684/0/0/859 | empty |
| fix-03 | 472/0/0/8 | 472/8/0/0 | 26687/0/0/859 | empty |
| fix-04 | 473/0/0/8 | 473/8/0/0 | 26688/0/0/859 | empty |
| fix-05 | 474/0/0/8 | 474/8/0/0 | 26689/0/0/859 | empty |
| fix-06 | 475/0/0/8 | 475/8/0/0 | 26690/0/0/859 | empty |
| fix-07 | 476/0/0/8 | 476/8/0/0 | 26691/0/0/859 | empty |
| fix-08 | 478/0/0/8 | 478/8/0/0 | 26693/0/0/859 | empty |
| fix-09 | 479/0/0/8 | 479/8/0/0 | 26694/0/0/859 | empty |
| fix-10a | 480/0/0/8 | 480/8/0/0 | 26695/0/0/859 | empty |
| fix-10b | 481/0/0/8 | 481/8/0/0 | 26696/0/0/859 | empty |
| fix-10c | 482/0/0/8 | 482/8/0/0 | 26697/0/0/859 | empty |
| after (after the mutant pass) | 482/0/0/8 | 482/8/0/0 | 26697/0/0/859 | empty |

- Font classes: 123 tests before, 139 after (+9 `MacFontNullInputTest`, +7 `MacFontReleaseTest`); Glass classes 343
  before and after. Per class: `SCRATCH/story/US-067-macos-gate-per-class.tsv`.
- Module after = before + 17 tests + 1 skip (16 story tests, plus the dump test, which skips without `us067.dump`).
- AC 4: `git diff --stat` over the five `mac-*-golden.txt` files prints nothing in every checks log.
- No `hs_err_pid*` file under `modules/javafx.graphics` or its `target` after any block. No file had to be moved
  (amendment A2): `SCRATCH/hs_err-moved/` was never created.

RED runs before each fix (kept as `LOGS/fix-NN-red.log`, not the proof of record): see the handoff
(`SCRATCH/handoff.md`), one row per fix. Fix 1's crash proof is quoted in the mutant table below.

## 3. Mutant proof of record (final tree, after fix 10c)

Each row: only the named production lines edited (by file edit, never git), the row's test run, the failure quoted,
the lines restored by edit, the same test re-run green. Logs: `LOGS/mutant-<row>.log` and
`LOGS/mutant-<row>-restored.log`. All 17 mutants were killed. Every mutant run: 1 test, 1 failure, Maven exit 1. Every
restored run: 1 test, 0 failures, exit 0.

| Fix | Mutant: the only production edit | Test | Without the fix (failure line) | Restored | Logs |
|---|---|---|---|---|---|
| 1 | `CTGlyphLayout.addFallbackFont`: `newRef == 0` guard deleted | `MacFontNullInputTest#aRunFontCoreTextDidNotCopyTakesNoFallbackSlot` | `:53 a zero copy must be refused before the factory is asked for a font file ==> expected: <-1> but was: <java.lang.NullPointerException>`. Child under the mutant: `slot=-1`, `slot.noFactory=java.lang.NullPointerException` (the child did not die; `slot` stays -1 as the brief predicted, so the first assertion cannot kill this mutant in the final tree). Crash proof for input 1, `fix-01-red.log`: `:51->run:58 child JVM 'null.fallbackCopy' exited with 133; hs_err files: []` (CFRetain(NULL), SIGTRAP) | green, 1/0/0/0 | mutant-1 |
| 2a | `MacFontFinder.getFont`: `font.address() == 0` guard deleted | `...#aUiFontTypeWithoutAFontHasNoName` | `:62->run:155 child JVM 'null.uiFont' exited with 133; hs_err files: []` | green | mutant-2a |
| 2b | `MacFontFinder.familyName`: `family.address() == 0` guard deleted | `...#aFontWithoutAFamilyNameHasNoName` | `:74->run:155 child JVM 'null.familyName' exited with 133; hs_err files: []` | green | mutant-2b |
| 3a | `MacFontFinder.getCascadeList`: `fontRef == 0` and `descriptors` guards deleted | `...#aZeroFontHasAnEmptyCascadeList` | `:83->run:155 child JVM 'null.cascadeFont' exited with 134; hs_err files: [REPO/modules/javafx.graphics/target/mac-font-golden/hs_err/null.cascadeFont/hs_err_pid23874.log]` (cleared by the green re-run) | green | mutant-3a |
| 3b | `CTFontFile.getCascadingInfo`: `ref != 0` condition dropped (try/finally kept) | `...#anEmbeddedFontCoreGraphicsRejectsKeepsTheHardWiredFallbacks` | `:92->run:155 child JVM 'null.cascadeEmbedded' exited with 133; hs_err files: []` (CFRelease(0) in the `finally`) | green | mutant-3b |
| 3c | `MacFontFinder.addCascadeEntry`: `release(displayName)` became `CFNative.CFRelease(displayName)` | `...#aCascadeEntryWithoutADisplayNameHasANullName` | `:107->run:155 child JVM 'null.cascadeName' exited with 133; hs_err files: []` | green | mutant-3c |
| 4 | `DFontDecoder.formatOf`: NULL guard deleted | `...#aFontWithoutAFormatAttributeHasFormatZero` | `:122->run:155 child JVM 'null.format' exited with 134; hs_err files: [.../hs_err/null.format/hs_err_pid24814.log]` (cleared by the green re-run) | green | mutant-4 |
| 5 | `DFontDecoder.getCTFontTable`: NULL guard deleted | `...#aTableTheFontLacksIsNull` | `:131->run:155 child JVM 'null.table' exited with 134; hs_err files: [.../hs_err/null.table/hs_err_pid24999.log]` (cleared by the green re-run) | green | mutant-5 |
| 6 | `OS.rect` and `OS.transform`: null answers `MemorySegment.NULL` (injected regression) | `...#aNullMirrorThrowsBeforeAnyDowncall` | `:148 CGRectApplyAffineTransform with a null rect ==> expected: <java.lang.NullPointerException@com.sun.javafx.font.coretext.OS.rect> but was: <java.lang.IndexOutOfBoundsException@jdk.internal.foreign.AbstractMemorySegmentImpl.throwOutOfBounds>`. Outcome kind: another exception from another top frame, no dead child; `rect.null`, `transform.null` and `fill.null` all read that value, `fill.after=64` | green; `OS.java` diff is that of fix 10b only | mutant-6 |
| 7a | `MacFontFinder.addCascadeEntry`: `release(name)` deleted from the `finally` | `MacFontReleaseTest#theCascadeListReleasesTheNamesAndUrls` | `:79 expected: <[]> but was: <[retain.delta.name = 20, expected 0: the cascade list releases the name it copies]>`. Child: `blocks.perCall=0.0`, `retain.delta.name=20`, control 20, entries 39, urls 38, name.entry 6 | green | mutant-7a |
| 7b | `MacFontFinder.addCascadeEntry`: `release(url)` deleted from the `finally` | `...#theCascadeListReleasesTheNamesAndUrls` | `:79 expected: <[]> but was: <[a cascade list call leaves 74.85 heap blocks in use, for 38 URLs: the URLs are not released]>`. Child: `blocks.perCall=74.85`, `retain.delta.name=0` | green | mutant-7b |
| 8a | `CTGlyphLayout.addFallbackFont`: `OS.CFRetain(newRef);` re-added after the guard | `...#aFallbackFontTakenByReferenceIsOwnedOnce` | `:110 expected: <[]> but was: <[the fallback font .SF Hebrew has retain count 2 where a fresh copy has 1: its font file is not its only owner, the fallback font .SF Arabic has retain count 2 where a fresh copy has 1: ..., the fallback font .ThonburiUI Regular has retain count 2 where a fresh copy has 1: ..., handing a font over as a fallback moved its retain count from 2 to 3: the font file takes the one reference it is handed]>`. Child: fonts=3, each `2\|1`, seam.before=2, seam.after=3, seam.slot=19 | green | mutant-8a |
| 8b | `CTFactory.createFontFile`: release in the `finally` deleted | `...#aFontReferenceIsReleasedUnlessAFontFileTakesIt` | `:121 a reference no font file took is released ==> expected: <1> but was: <2>`. Child: unmatched.before=2, unmatched.after=2, unmatched.made=#null; matched 2/2 CTFontFile | green | mutant-8b |
| 9 | `CTFontFile.getCascadingInfo`: plain assignment under `if (ref != 0)`, no release | `...#theCascadeLookupReleasesItsFont` | `:137 the cascade lookup releases the CTFont it makes ==> expected: <0> but was: <20>` (control.fontPerCall=20) | green | mutant-9 |
| 10a | `GlyfTableBounds.bounds`: `glyf` released after `boundingBox`, no `finally` | `...#aBoundsArrayThatThrowsStillReleasesGlyf` | `:153 a short bounds array leaves no glyf copy behind ==> expected: <0> but was: <20>`. Child: shortBounds=20, nullBounds=20, control 20 | green | mutant-10a |
| 10b | `DFontDecoder.getCTFontTable`: release after `toArray`, no `finally` | `...#aTableTooLargeForTheHeapIsStillReleased` | `:180 a table too large for the heap leaves no copy of it behind ==> expected: <0> but was: <20>`. Child (-Xmx64m): heap.max=67108864, table.length=191134508, control 20, thrown=java.lang.OutOfMemoryError | green | mutant-10b |
| 10c | `CTGlyphLayout.layout`: release after the runs block, no `finally` | `...#aLayoutWhoseRunThrowsStillReleasesItsLine` | `:198 a layout whose run throws leaves no line behind ==> expected: <0> but was: <40>`. Child: control.linePerCall=40, retain.delta.layout=0, retain.delta.shapeThrows=40 | green | mutant-10c |

Every mutant is gone: the cumulative snapshot taken after the pass
(`SCRATCH/patches/after-mutants.cumulative.patch`) is byte-identical to `SCRATCH/patches/fix-10c.cumulative.patch`
(`diff` prints nothing, exit 0; both 128,901 bytes). After the pass both `find ... -name 'hs_err_pid*'` commands print
nothing.

### Guards and paths that rest on review (no test can pin them)

- Fix 1: in the final tree the guard is pinned by the no-factory assertion only; the crash it prevents is shown by
  `fix-01-red.log`.
- Fix 3: the `fontRef == 0` guard of `getCascadeList` taken alone (either guard alone gives the non-crash outcome on
  this macOS, CoreText answers NULL for a NULL font), and the `name == null` half of the `getCascadingInfo` skip.
- Fix 5: the `decode` branch (`IOException("Could not get tables for Dfont")` for a null table).
- Fix 8: the release when `new CTFontFile(...)` or `OS.CTFontCopyURLAttribute` throws, and the `fontRef != 0` half of
  the release condition.
- Every row of fixes 10b and 10c other than D4 (`getCTFontTable`) and L2 (`layout` line).
- The two strike windows of F7b (`new CTStrikeDisposer` and `Disposer.addRecord` throwing): closed in this story
  (section 10), `OutOfMemoryError`-only, no test and no test seam.

## 4. Pin table (`TEST/MacFontBindings.java`)

Each "after" string was copied from the witness failure (`LOGS/fix-NN-pin-before.log`), confirmed with `javap -c -p`
(re-confirmed on the final classes on 2026-11-13) and followed by a witness run skipped by the machine gate, not
failed (`LOGS/fix-NN-pin-after.log`: 2 tests, 0 failures, 1 skipped). Only the listed tokens differ between before
and after; the rest of each string is unchanged.

| Fix | Class | Before | After | javap (final tree) | Reason |
|---|---|---|---|---|---|
| 8 | `com/sun/javafx/font/coretext/CTFactory` | `OS.CTFontCopyURLAttribute` | `OS.CFRelease*2 OS.CTFontCopyURLAttribute` | `OS.CFRelease:` 2 | `createFontFile` releases a reference no font file took. One release in a `finally` whose `try` has one normal exit: javac copies it to the normal exit and the exception handler |
| 8 | `com/sun/javafx/font/coretext/CTGlyphLayout` | `... OS.CFRelease*5 OS.CFRetain OS.CFStringCreateWithCharacters ...` | `... OS.CFRelease*5 OS.CFStringCreateWithCharacters ...` | `OS.CFRetain:` 0 | the extra retain of `addFallbackFont` is deleted |
| 8 | `UNCALLED_ENTRY_POINTS` | `MAC_FONT_FINDER + " getMonospacedFont()Ljava/lang/String;"` | that plus `OS + " CFRetain(J)V"` | - | `OSShim.CFRetain` has no production caller any more (witness: `the shims call com/sun/javafx/font/coretext/OS CFRetain(J)V, which no production caller calls`). `OS.CFRetain`, `OSShim.CFRetain` and `CFNative.CFRetain` are kept: `MacCoreTextGoldenTest` and `MacFontStressChild` use the shim |
| 9 | `com/sun/javafx/font/coretext/CTFontFile` | `MacFontFinder.getCascadeList OS.CFRelease*6 ...` | `MacFontFinder.getCascadeList OS.CFRelease*8 ...` | (8 at fix 9) | the cascade-lookup font is released in a `finally` of a `try` with one normal exit: +2 |
| 10c | `com/sun/javafx/font/coretext/CTFontFile` | `... OS.CFRelease*8 ...` | `... OS.CFRelease*15 ...` | `OS.CFRelease:` 15 | constructor `catch` 1 (a catch body is not copied); `registerFont` 2 finallys x 2 = 4; `createCGFontForEmbeddedFont` 3 finallys x 2 = 6; `getFontRef` psNameRef finally 2; `getCascadingInfo` (fix 9) 2 |
| 10c | `com/sun/javafx/font/coretext/CTFontFile` | `... OS.CGPathRelease*3 ...` | `... OS.CGPathRelease*6 ...` | `OS.CGPathRelease:` 6 | `getBBox`, `getGlyphOutline`, `createGlyphBoundingBox`: one finally each, one exit plus the handler: 3 x 2 |
| 10c | `com/sun/javafx/font/coretext/CTGlyph` | `... OS.CGContextRelease*2 ...` | `... OS.CGContextRelease*5 ...` | `OS.CGContextRelease:` 5 | `createContext` catch 1; `getColorImage` finally 2; `getImage` finally (`if (!cache)`) 2 |
| 10c | `com/sun/javafx/font/coretext/CTGlyphLayout` | `... OS.CFRelease*5 ...` | `... OS.CFRelease*10 ...` | `OS.CFRelease:` 10 | the five releases (paragraphStyleRef, attString, attributes, textRef, lineRef) each moved into a finally of a try with one normal exit: 5 x 2 |

Full strings, final tree:

- CTFactory: `OS.CFRelease*2 OS.CTFontCopyURLAttribute`
- CTFontFile: `MacFontFinder.getCascadeList OS.CFRelease*15 OS.CFURLCreateWithFileSystemPath*2 OS.CGDataProviderCreateWithURL OS.CGFontCreateWithDataProvider OS.CGPathApply OS.CGPathGetPathBoundingBox*2 OS.CGPathRelease*6 OS.CTFontCreateCopyWithAttributes OS.CTFontCreatePathForGlyph*3 OS.CTFontCreateUIFontForLanguage OS.CTFontCreateWithGraphicsFont OS.CTFontCreateWithName OS.CTFontGetAdvancesForGlyphs OS.CTFontGetBoundingRectForGlyphUsingTables OS.CTFontGetBoundingRectForGlyphs OS.CTFontManagerRegisterFontsForURL OS.kCFAllocatorDefault*2`
- CTGlyph: `OS.CGBitmapContextCreate OS.CGBitmapContextGetData*2 OS.CGColorSpaceCreateDeviceGray OS.CGColorSpaceCreateDeviceRGB OS.CGContextFillRect OS.CGContextRelease*5 OS.CGContextSetAllowsAntialiasing OS.CGContextSetAllowsFontSmoothing OS.CGContextSetAllowsFontSubpixelPositioning OS.CGContextSetAllowsFontSubpixelQuantization OS.CGContextSetRGBFillColor*2 OS.CGContextTranslateCTM*4 OS.CGImageContextGetData OS.CGRectApplyAffineTransform OS.CTFontDrawGlyphs*2 OS.CTFontGetAdvancesForGlyphs`
- CTGlyphLayout: `OS.CFArrayGetCount OS.CFArrayGetValueAtIndex OS.CFAttributedStringCreate OS.CFDictionaryAddValue*2 OS.CFDictionaryCreateMutable OS.CFDictionaryGetValue OS.CFRelease*10 OS.CFStringCreateWithCharacters OS.CTFontCopyAttributeDisplayName OS.CTFontCreateCopyWithAttributes OS.CTLineCreateWithAttributedString OS.CTLineGetGlyphCount OS.CTLineGetGlyphRuns OS.CTLineGetTypographicBounds OS.CTParagraphStyleCreate OS.CTRunGetAttributes OS.CTRunGetGlyphs*2 OS.CTRunGetPositions OS.CTRunGetStringIndices OS.kCFAllocatorDefault OS.kCFTypeDictionaryKeyCallBacks OS.kCFTypeDictionaryValueCallBacks OS.kCTFontAttributeName*2 OS.kCTParagraphStyleAttributeName`

`CTFontStrike` has no entry and was never named by the witness. It gained one `OS.CFRelease` when the strike window
was closed (section 10; `javap`: `OS.CFRelease:` 0 before, 1 after); no pin string moved for it. The
entries of `OS`, `MacFontFinder` and `DFontDecoder` were not edited. The witness comment and the
`peerCallSiteProblems` message were reworded in fix 8 (`... where NATIVE_CALL_SITES pins [...]`).

## 5. A/B dump of the five golden corpora (this machine)

- `SCRATCH/ab/before1`, `SCRATCH/ab/before2` (clean tree) and `SCRATCH/ab/after` (final tree, after the mutant pass).
  Each dump run: 1 test, 0 skipped, exit 0 (`LOGS/ab-before1.log`, `ab-before2.log`, `ab-after.log`).
- All five corpora ran to the end before and after (no `FAILED` file): MacCoreTextGoldenTest 2,583 lines,
  MacDFontGoldenTest 260, MacFontFinderGoldenTest 599, MacFontProcessGoldenTest 558, MacGlyphLayoutGoldenTest 1,580.
- `diff -r before1 before2`: empty, so no key is unstable. `diff -r before1 after`: empty (exit 0). No recorded value of
  any corpus moved.

## 6. Sites found while tracing, not fixed in this story (for follow-up stories)

| # | Site | What |
|---|---|---|
| N1 | `MacFontFinder.getSystemFontSize` | `CFRelease(font)` if the UI font is NULL |
| N2 | `MacFontFinder.getFontData` | a NULL collection, array, descriptor or UI font reaches CF (released unguarded inside the new `finally` blocks) |
| N3 | `DFontDecoder.createCTFont`; `releaseCTFont(0)` | NULL collection or array; release of 0 |
| N4 | `OS.CTFontCreateUIFontForLanguage`; `OS.CTFontCreateWithName` | NULL UI font into copy and release; name 0 or empty |
| N5 | `CTFontFile.getAdvanceFromPlatform` | no `fontRef == 0` check |
| N6 | `CTGlyph.createContext` | setters on a 0 context |
| N7 | `MacFontFinder.getCascadeList` | a NULL language-code list reaches CoreText and `CFRelease` |
| N8 | `CTGlyphLayout.layout` | no `Reference.reachabilityFence(strike)` |
| N9 | `GlyfTableBounds.boundingBox`; `OS.rect` | a short `bb` is partly written before the exception; a `CGRect` with a null `origin` throws |
| N10 | `CTGlyph.getCachedContext` | unsynchronised static, two threads can each create a context |
| N11 | `CTFactory.createFontFile` | a fallback whose face is not found is re-created and its file re-parsed on every layout (System with Devanagari) |
| N12 | `MacFontFinder.getCascadeList` | off the AppKit thread, about 232 autoreleased blocks per call wait for the thread to exit |
| N13 | Goldens and gate | no oracle on macOS 27.2.1; `Mac*Test` now selects the Glass classes too (466 tests at baseline, not 123) |

Nothing further was found in the section 7 checklist walk (stage 4) or in the mutant pass. Row F7b (the strike's
`fontRef`) was listed here as deferred by amendment A1; the amendment was withdrawn and the row is closed (section 10).

## 7. File sizes and line lengths (final tree)

`wc -l`: MacFontFinder 259, DFontDecoder 206, OS 476, GlyfTableBounds 118, CTFactory 166, CTFontFile 355,
CTFontStrike 183 (175 at HEAD), CTGlyph 320, CTGlyphLayout 192. All at most 900.

`PrismFontFile.java`: 1,497 lines before the story (HEAD), 1,502 after (the 5 lines of the strike block, section 10).
It was over the 900-line budget before this story and is not split here.

Longest line of every changed or new file is at most 120 columns (max 120: `MacFontBindings.java:116`,
`MacFontStressChild.java:300`), except `OS.java:52`, 154 columns, pre-existing and untouched. No tab, no trailing
blank, no `var`.

## 8. Items not done as the brief wrote them (deviations)

1. Amendment A1 deferred the strike block of fix 10c (`CTFontStrike.createDisposer`, `PrismFontFile.getStrike`). It
   was withdrawn; the block was done after the mutant pass and the `after` verification, as the brief wrote it
   (section 10). The mutant pass and the A/B dump were not repeated after it.
2. Amendment A2: nothing was deleted in `REPO`. No `hs_err` file survived a green re-run, so none was moved.
   `Us067CorpusDumpTest.java` was moved out of `REPO` after all runs (section 9), not left for the owner.
3. Amendment A3 / brief section 1: `before-module.log` ran in the background (alone, awaited); every other Maven run
   in the foreground, one at a time.
4. Edits (mutants included) were made with inline `python3 - <<'EOF'` exact-once string replacement, a few with the
   Edit tool or an inline `sed`; no script file was written. In the mutant pass the restore wrote back the text read
   before the mutation; the snapshot diff in section 3 proves the result.
5. Single-test RED, green, pin, mutant and restored runs used `-DskipNative=true`; gate and module runs did not.
6. Fix 3: its verification block was run twice (a 121-column line in the new `MacFontProbeShim.java` was wrapped; no
   token changed); the logs are those of the second run, same numbers.
7. Fix 6: the injected mutant gives an `IndexOutOfBoundsException` from
   `jdk.internal.foreign.AbstractMemorySegmentImpl.throwOutOfBounds` for all three calls, not a crash (the brief left
   the outcome open).
8. Fix 7: the name walk stops at the first entry whose `cascadeEntryName` is 0 ("0 past the end" cannot be told from
   an entry without a name attribute). On this Mac it watches entry 6.
9. Fix 8: the layout half found three by-reference fallback fonts here (`.SF Hebrew`, `.SF Arabic`,
   `.ThonburiUI Regular`), where the brief's trace measured one.
10. Fix 9 child: when `cgFont` is 0 the scenario writes only `cgFont` and skips the probes; the test's first assertion
    then fails with its own message. Fix 10b child: the same when `table.length <= heap.max`.
11. Fix 10a child: the probe catches `ArrayIndexOutOfBoundsException | NullPointerException` only.
12. Fix 10b: in `DFontDecoder.createCTFont` one continuation line is indented 8 columns past its statement instead of
    aligned under the parenthesis (the aligned form is 122 columns). `addDescriptor` releases through the existing
    `release(MemorySegment)` helper, same objects, same order.
13. `MacFontProbeShim` and `CoreTextPeerShim` were filled in fix by fix (each method in the fix that first needs it).
14. Mutant 10c left the runs block as a bare `{ ... }` block (the `try {` line became `{`) rather than re-indenting it;
    the release sits after the block, as the row says.
15. The "after" block also copied `REPO/modules/javafx.graphics/target/surefire-reports` of the flagged gate run to
    `SCRATCH/after-gate-require-surefire-reports` (a copy, inside `SCRATCH`).

Not verified here (unchanged from the brief's section 13): nothing was run on x86_64, on another macOS version, or on
Windows/Linux; no robot or `tests/system` test was run (owner-only).

## 9. `git status` after all runs

Before the move, `git status --porcelain` showed the 16 modified and 6 untracked story files below plus the untracked
`modules/javafx.graphics/src/test/java/test/com/sun/javafx/font/Us067CorpusDumpTest.java`, the one file that is not
part of the story. After all runs it was moved (one `mv`, full literal paths, amendment A2) to
`/private/tmp/claude-501/-Users-benesquivelmusic-Documents-SourceCode-jfx-ffm/b184fe71-a1a0-4bc9-a84f-7a9056c40664/scratchpad/us067/Us067CorpusDumpTest.java`.
No Maven run followed the move. Its compiled class
`REPO/modules/javafx.graphics/target/test-classes/test/com/sun/javafx/font/Us067CorpusDumpTest.class` is still in the
git-ignored build output (nothing may be deleted in `REPO`); if the next build does not drop it, it shows as 1 skipped
test (module 26697/859 instead of 26696/858).

`git status --porcelain` after the move (only story files; nothing under `backlog/`, no golden):

```
 M modules/javafx.graphics/src/main/java/com/sun/javafx/font/DFontDecoder.java
 M modules/javafx.graphics/src/main/java/com/sun/javafx/font/MacFontFinder.java
 M modules/javafx.graphics/src/main/java/com/sun/javafx/font/coretext/CTFactory.java
 M modules/javafx.graphics/src/main/java/com/sun/javafx/font/coretext/CTFontFile.java
 M modules/javafx.graphics/src/main/java/com/sun/javafx/font/coretext/CTGlyph.java
 M modules/javafx.graphics/src/main/java/com/sun/javafx/font/coretext/CTGlyphLayout.java
 M modules/javafx.graphics/src/main/java/com/sun/javafx/font/coretext/GlyfTableBounds.java
 M modules/javafx.graphics/src/main/java/com/sun/javafx/font/coretext/OS.java
 M modules/javafx.graphics/src/shims/java/com/sun/javafx/font/DFontDecoderShim.java
 M modules/javafx.graphics/src/shims/java/com/sun/javafx/font/FontResourceShim.java
 M modules/javafx.graphics/src/shims/java/com/sun/javafx/font/MacFontFinderShim.java
 M modules/javafx.graphics/src/test/java/test/com/sun/javafx/font/MacCoreTextGoldenTest.java
 M modules/javafx.graphics/src/test/java/test/com/sun/javafx/font/MacDFontGoldenTest.java
 M modules/javafx.graphics/src/test/java/test/com/sun/javafx/font/MacFontBindings.java
 M modules/javafx.graphics/src/test/java/test/com/sun/javafx/font/MacFontFinderGoldenTest.java
 M modules/javafx.graphics/src/test/java/test/com/sun/javafx/font/MacFontStressChild.java
?? modules/javafx.graphics/src/shims/java/com/sun/javafx/font/MacFontProbeShim.java
?? modules/javafx.graphics/src/shims/java/com/sun/javafx/font/coretext/CoreTextPeerShim.java
?? modules/javafx.graphics/src/test/java/test/com/sun/javafx/font/MacFontNullInputChild.java
?? modules/javafx.graphics/src/test/java/test/com/sun/javafx/font/MacFontNullInputTest.java
?? modules/javafx.graphics/src/test/java/test/com/sun/javafx/font/MacFontReleaseChild.java
?? modules/javafx.graphics/src/test/java/test/com/sun/javafx/font/MacFontReleaseTest.java
```

`git diff --stat` (tracked files) at that point: 16 files changed, 513 insertions(+), 288 deletions(-).
`CFNative.java`, `CGNative.java`, `CTNative.java` and the five `mac-*-golden.txt` files have no diff. The list and
the figures above predate section 10, which adds ` M` for `PrismFontFile.java` and `coretext/CTFontStrike.java`.

## 10. Strike window (F7b) closed

Amendment A1 is withdrawn. The strike block of fix 10c was applied on 2026-11-13 exactly as the brief wrote it,
after everything above; nothing else was changed.

**Claims of "Why this is safe on every platform", re-checked against the code before editing (all true):**

- `DWFontStrike.createDisposer` (`MAIN/directwrite/DWFontStrike.java:75-77`) and `FTFontStrike.createDisposer`
  (`MAIN/freetype/FTFontStrike.java:65-68`) return `null`.
- `PrismFontStrike` has three subclasses in the repository: `CTFontStrike`, `DWFontStrike`, `FTFontStrike` (`grep` for
  `extends PrismFontStrike` and `createDisposer` over `modules`, `apps`, `tests`). `createDisposer` is called only by
  `PrismFontStrike.getDisposer` (`:77`), and that only by `PrismFontFile.getStrike`.
- `Disposer.addRecord` (`MAIN/Disposer.java:76-80`) is `new WeakReference(target, queue)`, then
  `records.put(ref, rec)` on a `Hashtable`, then `return ref`: the registration is its last effect.
- `CTStrikeDisposer.dispose` (`MAIN/coretext/CTStrikeDisposer.java:51-70`) is `synchronized`, guarded by `disposed`,
  releases `fontRef` once if it is not 0 and zeroes it; its map lookup removes an entry only when its referent is
  cleared.

**Hunks.** `CTFontStrike.createDisposer`:

```java
        CTFontFile fontResource = getFontResource();
        try {
            return new CTStrikeDisposer(fontResource, desc, fontRef);
        } catch (Throwable t) {
            if (fontRef != 0) {
                OS.CFRelease(fontRef);
                fontRef = 0;
            }
            throw t;
        }
```

`PrismFontFile.getStrike`:

```java
            if (disposer != null) {
                try {
                    ref = Disposer.addRecord(strike, disposer);
                } catch (Throwable t) {
                    disposer.dispose();
                    throw t;
                }
            } else {
```

Header of `CTFontStrike.java`: `2013, 2023` to `2013, 2026`. `PrismFontFile.java` already said `2011, 2026`.

**Pins.** No pin string moved. The witness (`MacFontBindingStructureTest` and the five golden classes, in the gate
runs below) did not name `CTFontStrike` and did not fail; `TEST/MacFontBindings.java` was not edited (its diff against
HEAD is still 17 insertions, 12 deletions). `javap -c -p` on `CTFontStrike`: `OS.CFRelease:` 0 before, 1 after (a
`catch` body is not copied). `javap` on `PrismFontFile`: `DisposerRecord.dispose:` 1.

**Runs (logs `LOGS/final2-*`):**

| Run | Log | Result |
|---|---|---|
| gate, no flag | `final2-gate-noflag.log` | 482 tests, 0 F, 0 E, 8 skipped, exit 0 |
| gate, `-Djfx.parity.require=true` | `final2-gate-require.log` | 482 tests, 8 F (the 8 oracle failures of section 2 of the brief, nothing else), 0 E, 0 skipped, exit 1 |
| module | `final2-module.log` | exit 0. jslc 196/0/0/0, base 5510/0/0/22, graphics 26696/0/0/858 |
| checks | `final2-checks.log` | 0 bytes (no `hs_err_pid*`, no golden diff) |

Snapshot, `modules/` only: `SCRATCH/patches/fix-10d-strike.cumulative.patch` (127,127 bytes, 24 files: 18 tracked,
6 untracked). `git diff --stat HEAD -- modules`: 18 files changed, 529 insertions(+), 291 deletions(-).

**Sizes.** `CTFontStrike.java` 175 to 183 lines. `PrismFontFile.java` 1,497 to 1,502 (over the 900-line budget before
the story: 1,497 at HEAD). Longest added line: 79 columns. No tab, no `var`.

**Rests on review.** Both windows open only on an `Error` such as `OutOfMemoryError` from an allocation; no test
provokes them and no test seam was added. The mutant pass (section 3) and the A/B dump (section 5) were run before
this change and were not repeated.

## 11. CTFontStrike pin

The review's one finding, applied on 2026-11-13 and nothing else. This section supersedes every earlier line that says
`CTFontStrike` has no `NATIVE_CALL_SITES` entry or that `TEST/MacFontBindings.java` was not edited after fix 10c.

**Edit (Edit tool, `TEST/MacFontBindings.java` only).** `NATIVE_CALL_SITES` gained an eleventh pair, so `Map.of`
(ten pairs at most) became `Map.ofEntries` with one `Map.entry(...)` per class; no other string changed. New entry,
between `CTFontFile$SelfDisposerRecord` and `CTGlyph` (the order of `PRODUCTION_CALLERS`):

    Map.entry("com/sun/javafx/font/coretext/CTFontStrike", "OS.CFRelease"),

**Pin string: `OS.CFRelease`** (one call site, no `*n`). Taken from the witness, not predicted: with a deliberately
wrong `OS.CFRelease*9` the witness said `CTFontStrike calls [OS.CFRelease] where NATIVE_CALL_SITES pins
[OS.CFRelease*9]` (`LOGS/pin-strike-discover.log`). `javap -c -p` on the compiled class shows exactly one
`invokestatic ... com/sun/javafx/font/coretext/OS.CFRelease:(J)V` (the `catch` of `createDisposer`; a `catch` body is
not copied). No production file was touched. Longest line of the file 120 columns; no tab, no `var`.

**Deviation from the task text.** The task named `-Dtest=MacFontBindingStructureTest` for the proof.
`MacFontBindingStructureTest` does not call `MacFontBindings.requireSameBinding`, so it cannot see a pin: the peer
check runs only through `MacFontGoldens.HARNESS`, in the five golden classes, before the machine gate. The three runs
therefore used `-Dtest=MacFontBindingStructureTest,MacFontFinderGoldenTest` (the class the brief's 4.3 uses for pins).

| Run | Log | Result |
|---|---|---|
| discover (`*9`) | `pin-strike-discover.log` | exit 1; 10 tests, 1 F: the witness names `CTFontStrike` only |
| green (`OS.CFRelease`) | `pin-strike-green.log` | exit 0; structure 8/0/0/0; `MacFontFinderGoldenTest` 2 tests, 0 F, 1 skipped: the peer check ran and passed, then the machine gate skipped the comparison (as in `fix-NN-pin-after.log`) |
| mutant (`OS.CFRelease*2`) | `pin-strike-mutant.log` | exit 1; 10 tests, 1 F, 0 skipped |
| restored | `pin-strike-restored.log` | exit 0; 10 tests, 0 F, 0 E, 1 skipped |

Mutant failure line (`pin-strike-mutant.log:153`), the only problem the witness listed:

    com/sun/javafx/font/coretext/CTFontStrike calls [OS.CFRelease] where NATIVE_CALL_SITES pins [OS.CFRelease*2]: a call site moved to another binding

**Verification on the final tree (logs `LOGS/final3-*`):**

| Run | Log | Result |
|---|---|---|
| gate, no flag | `final3-gate-noflag.log` | 482 tests, 0 F, 0 E, 8 skipped, exit 0 |
| gate, `-Djfx.parity.require=true` | `final3-gate-require.log` | 482 tests, 8 F, 0 E, 0 skipped, exit 1; the same 8 oracle failures as `final2-gate-require.log` (failing-test lists identical), nothing else |
| module | `final3-module.log` | exit 0. jslc 196/0/0/0, base 5510/0/0/22, graphics 26696/0/0/858 |
| checks | `final3-checks.log` | 0 bytes (no `hs_err_pid*`, no golden diff) |

The module run was started as a background task of the session (one Maven at a time was kept; nothing else ran until it
ended). The gate runs built the natives (no `-DskipNative`); the four pin runs used `-DskipNative=true`.

Snapshot, `modules/` only: `SCRATCH/patches/final.cumulative.patch` (129,683 bytes, 24 files: 18 tracked, 6
untracked). `git diff --stat HEAD -- modules`: 18 files changed, 543 insertions(+), 304 deletions(-) (before this step
529/291). `MacFontBindings.java` against HEAD: 31 insertions, 25 deletions (before 17/12).

**Not done.** The mutant pass of record (section 3) and the A/B dump were not repeated. No robot or tests/system run
(owner-only). No git state change; nothing deleted. `LOGS/final3-cmp-a.txt` is a helper file of this step (the sorted
failing-test list of `final2-gate-require.log`).

## Mutant pass and corpus dump repeated on the final tree

Done on 2026-11-13 on the tree of `SCRATCH/patches/final.cumulative.patch` (after the strike fix and the
`CTFontStrike` pin). This section supersedes every earlier line that says the mutant pass and the A/B dump were not
repeated after those two changes. Same host (macOS 27.2.1, arm64), Maven 3.10.0, JDK 27.0.1.0.2. One Maven at a time,
all in the foreground. Mutants applied and restored with the Edit tool only (no git, no script); after every restore
`shasum -a 256 -c LOGS/mutant2-sha-start.txt` over the nine touched files reported no mismatch before the green re-run.
Mutant and restored runs used `-DskipNative=true` and the brief's single-test command form.

**Start state.** `SCRATCH/patches/pre-mutants2.cumulative.patch` (129,683 bytes): `cmp` with `final.cumulative.patch`
identical (exit 0).

### Mutant table (logs `LOGS/mutant2-<row>.log` and `LOGS/mutant2-<row>-restored.log`)

Every mutant run: Maven exit 1, 1 test, 1 failure, 0 errors (row 18: 10 tests, 1 failure). Every restored run: exit 0,
0 failures, 0 errors. All 17 rows and the pin row were killed; none survived.

| Row | Mutant: the only edit | Test | Failure line under the mutant | Restored | Logs |
|---|---|---|---|---|---|
| 1 | `CTGlyphLayout.addFallbackFont`: `newRef == 0` guard deleted | `MacFontNullInputTest#aRunFontCoreTextDidNotCopyTakesNoFallbackSlot` | `:53 a zero copy must be refused before the factory is asked for a font file ==> expected: <-1> but was: <java.lang.NullPointerException>`. Child: `slot=-1`, `slot.noFactory=java.lang.NullPointerException` (child alive; the first assertion cannot kill this mutant, as before) | 1/0/0/0 | mutant2-1 |
| 2a | `MacFontFinder.getFont`: `font.address() == 0` guard deleted | `...#aUiFontTypeWithoutAFontHasNoName` | `:62->run:155 child JVM 'null.uiFont' exited with 133; hs_err files: []` | 1/0/0/0 | mutant2-2a |
| 2b | `MacFontFinder.familyName`: `family.address() == 0` guard deleted | `...#aFontWithoutAFamilyNameHasNoName` | `:74->run:155 child JVM 'null.familyName' exited with 133; hs_err files: []` | 1/0/0/0 | mutant2-2b |
| 3a | `MacFontFinder.getCascadeList`: `fontRef == 0` and `descriptors` guards deleted | `...#aZeroFontHasAnEmptyCascadeList` | `:83->run:155 child JVM 'null.cascadeFont' exited with 134; hs_err files: [REPO/modules/javafx.graphics/target/mac-font-golden/hs_err/null.cascadeFont/hs_err_pid39451.log]` (cleared by the green re-run) | 1/0/0/0 | mutant2-3a |
| 3b | `CTFontFile.getCascadingInfo`: `if (ref != 0)` became a bare block (try/finally kept) | `...#anEmbeddedFontCoreGraphicsRejectsKeepsTheHardWiredFallbacks` | `:92->run:155 child JVM 'null.cascadeEmbedded' exited with 133; hs_err files: []` | 1/0/0/0 | mutant2-3b |
| 3c | `MacFontFinder.addCascadeEntry`: `release(displayName)` became `CFNative.CFRelease(displayName)` | `...#aCascadeEntryWithoutADisplayNameHasANullName` | `:107->run:155 child JVM 'null.cascadeName' exited with 133; hs_err files: []` | 1/0/0/0 | mutant2-3c |
| 4 | `DFontDecoder.formatOf`: NULL guard deleted | `...#aFontWithoutAFormatAttributeHasFormatZero` | `:122->run:155 child JVM 'null.format' exited with 134; hs_err files: [.../hs_err/null.format/hs_err_pid40113.log]` (cleared by the green re-run) | 1/0/0/0 | mutant2-4 |
| 5 | `DFontDecoder.getCTFontTable`: NULL guard deleted | `...#aTableTheFontLacksIsNull` | `:131->run:155 child JVM 'null.table' exited with 134; hs_err files: [.../hs_err/null.table/hs_err_pid40332.log]` (cleared by the green re-run) | 1/0/0/0 | mutant2-5 |
| 6 | `OS.rect` and `OS.transform`: null answers `MemorySegment.NULL` (injected) | `...#aNullMirrorThrowsBeforeAnyDowncall` | `:148 CGRectApplyAffineTransform with a null rect ==> expected: <java.lang.NullPointerException@com.sun.javafx.font.coretext.OS.rect> but was: <java.lang.IndexOutOfBoundsException@jdk.internal.foreign.AbstractMemorySegmentImpl.throwOutOfBounds>`. Child: all three null probes read that value, `fill.after=64`; no dead child | 1/0/0/0 | mutant2-6 |
| 7a | `MacFontFinder.addCascadeEntry`: `release(name)` deleted from the `finally` | `MacFontReleaseTest#theCascadeListReleasesTheNamesAndUrls` | `:79 expected: <[]> but was: <[retain.delta.name = 20, expected 0: the cascade list releases the name it copies]>`. Child: `blocks.perCall=0.85`, `retain.delta.name=20`, control 20, entries 39, urls 38, name.entry 6 | 1/0/0/0 | mutant2-7a |
| 7b | `MacFontFinder.addCascadeEntry`: `release(url)` deleted from the `finally` | `...#theCascadeListReleasesTheNamesAndUrls` | `:79 expected: <[]> but was: <[a cascade list call leaves 74.56666666666666 heap blocks in use, for 38 URLs: the URLs are not released]>`. Child: `blocks.perCall=74.57`, `retain.delta.name=0` | 1/0/0/0 | mutant2-7b |
| 8a | `CTGlyphLayout.addFallbackFont`: `OS.CFRetain(newRef);` re-added after the guard | `...#aFallbackFontTakenByReferenceIsOwnedOnce` | `:110 expected: <[]> but was: <[the fallback font .SF Hebrew has retain count 2 where a fresh copy has 1: its font file is not its only owner, the fallback font .SF Arabic has retain count 2 where a fresh copy has 1: its font file is not its only owner, the fallback font .ThonburiUI Regular has retain count 2 where a fresh copy has 1: its font file is not its only owner, handing a font over as a fallback moved its retain count from 2 to 3: the font file takes the one reference it is handed]>`. Child: fonts=3, each `2\|1`, seam 2 to 3, slot 19 | 1/0/0/0 | mutant2-8a |
| 8b | `CTFactory.createFontFile`: `OS.CFRelease(fontRef);` deleted from the `finally` (the `if` left empty) | `...#aFontReferenceIsReleasedUnlessAFontFileTakesIt` | `:121 a reference no font file took is released ==> expected: <1> but was: <2>`. Child: unmatched 2/2 `#null`, matched 2/2 `CTFontFile` | 1/0/0/0 | mutant2-8b |
| 9 | `CTFontFile.getCascadingInfo`: plain assignment under `if (ref != 0)`, no release | `...#theCascadeLookupReleasesItsFont` | `:137 the cascade lookup releases the CTFont it makes ==> expected: <0> but was: <20>` (control.fontPerCall=20) | 1/0/0/0 | mutant2-9 |
| 10a | `GlyfTableBounds.bounds`: `glyf` released after `boundingBox`, no `finally` | `...#aBoundsArrayThatThrowsStillReleasesGlyf` | `:153 a short bounds array leaves no glyf copy behind ==> expected: <0> but was: <20>`. Child: shortBounds=20, nullBounds=20, control 20 | 1/0/0/0 | mutant2-10a |
| 10b | `DFontDecoder.getCTFontTable`: release after `toArray`, no `finally` | `...#aTableTooLargeForTheHeapIsStillReleased` | `:180 a table too large for the heap leaves no copy of it behind ==> expected: <0> but was: <20>`. Child (-Xmx64m): heap.max=67108864, table.length=191134508, control 20, thrown=java.lang.OutOfMemoryError | 1/0/0/0 | mutant2-10b |
| 10c | `CTGlyphLayout.layout`: `try {` became `{`, release after the block, no `finally` | `...#aLayoutWhoseRunThrowsStillReleasesItsLine` | `:198 a layout whose run throws leaves no line behind ==> expected: <0> but was: <40>`. Child: control.linePerCall=40, retain.delta.layout=0, retain.delta.shapeThrows=40 | 1/0/0/0 | mutant2-10c |
| 18 (pin) | `TEST/MacFontBindings.java` `NATIVE_CALL_SITES`: the `CTFontStrike` entry `OS.CFRelease` became `OS.CFRelease*2` (no production edit) | `-Dtest=MacFontBindingStructureTest,MacFontFinderGoldenTest` | `mutant2-pin.log:153`, the only problem listed: `com/sun/javafx/font/coretext/CTFontStrike calls [OS.CFRelease] where NATIVE_CALL_SITES pins [OS.CFRelease*2]: a call site moved to another binding` (in `MacFontFinderGoldenTest.fontFinderMatchesTheJniGolden:72`; structure test 8/0/0/0; 10 tests, 1 F, 0 skipped) | exit 0; 10 tests, 0 F, 0 E, 1 skipped (the machine gate) | mutant2-pin |

### Strike fix: one honest mutant (logs `LOGS/mutant2-strike.log`, `LOGS/mutant2-strike-restored.log`)

Mutant: in the `catch` of `CTFontStrike.createDisposer`, `OS.CFRelease(fontRef);` deleted (`fontRef = 0;` and the
rethrow kept). Run: `-Dtest=MacFontReleaseTest,MacFontBindingStructureTest,MacFontFinderGoldenTest`, exit 1, 17 tests,
1 failure.

- `MacFontReleaseTest`: 7/0/0/0. No behaviour test sees the mutant (the window opens only on an `Error` from
  `new CTStrikeDisposer`; there is no test and no seam for it). That still rests on review.
- `MacFontBindingStructureTest`: 8/0/0/0. It does not see it either.
- `MacFontFinderGoldenTest.fontFinderMatchesTheJniGolden:72` FAILS, the pin catches it (`mutant2-strike.log:155`):
  `com/sun/javafx/font/coretext/CTFontStrike calls [] where NATIVE_CALL_SITES pins [OS.CFRelease]: a call site moved to another binding`
- So the release is pinned as a call site (it cannot be dropped unnoticed), not as behaviour: the pin does not prove
  the release runs on the right path or releases the right object.
- Restored: exit 0; 17 tests, 0 F, 0 E, 1 skipped (release 7/0/0/0, structure 8/0/0/0, finder golden 2 tests 1
  skipped by the machine gate).

The other strike window (`PrismFontFile.getStrike`, `disposer.dispose()` when `Disposer.addRecord` throws) was not
mutated (the task asked for one mutant); it has no pin (`PrismFontFile` is not a pinned peer) and rests on review.

### After the pass

- `SCRATCH/patches/after-mutants2.cumulative.patch`: `cmp` with `final.cumulative.patch` identical (exit 0).
- Both `find ... -name 'hs_err_pid*' -type f` commands print nothing. No file was moved; `SCRATCH/hs_err-moved/` was
  not created.

### A/B corpus dump on the final tree

- `Us067CorpusDumpTest.java` copied in with `cp` (sha256 `e8b3f717...beef28d`, the scratch copy), dump run into
  `SCRATCH/ab/final`, log `LOGS/ab-final.log`: exit 0, 1 test run, 0 failures, 0 skipped.
- All five corpora ran to the end (no `FAILED`): MacCoreTextGoldenTest 2,583 lines, MacDFontGoldenTest 260,
  MacFontFinderGoldenTest 599, MacFontProcessGoldenTest 558, MacGlyphLayoutGoldenTest 1,580 (as `before1`).
- `diff -r SCRATCH/ab/before1 SCRATCH/ab/final`: exit 0, `LOGS/ab-final-diff.log` is 0 bytes. No recorded value of any
  corpus moved.
- The test file was then moved back out with one `mv` to `SCRATCH/Us067CorpusDumpTest.java` (same sha256).

### End state

- `SCRATCH/patches/end.cumulative.patch`: `cmp` with `final.cumulative.patch` identical (exit 0); the same comparison
  repeated after the gate run below: identical.
- `git status --porcelain`: the 18 modified and 6 untracked story files under `modules/` (as section 9 plus
  `PrismFontFile.java` and `coretext/CTFontStrike.java`), and under `backlog/` the owner's ` M README.md`,
  ` M US-067-...md`, `?? US-067-evidence/`, `?? US-111-...md`, none of them touched here. No
  `Us067CorpusDumpTest.java`.
- Gate without the flag, natives built (`LOGS/final4-gate-noflag.log`): exit 0, 482 tests, 0 failures, 0 errors,
  8 skipped (the five font golden classes and `MacAccessibleNativeTest`, `MacClipboardNativeTest`,
  `MacPixelsNativeTest`). This run recompiled the tests without the dump test; no `Us067*` class is left in
  `target/test-classes/test/com/sun/javafx/font/`.
- `LOGS/final4-checks.log` (both `hs_err` finds and the golden `git diff --stat`): 0 bytes.

Not done here: the flagged gate and the full module run were not repeated (not asked); no robot or `tests/system`
run (owner-only); no git state change; nothing deleted. Helper file of this step: `LOGS/mutant2-sha-start.txt`.
