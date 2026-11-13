# US-067 final-tree review (ffm-reviewer, 2026-11-13)

Verdict: **approve** - mergeable. No blocker, no defect in production code. Two low findings (a test pin
gap and stale story text), neither changes behaviour.

Tree reviewed: working tree on HEAD 559ca936af, `git diff --stat HEAD -- modules` = 18 files, 529 insertions,
291 deletions, plus the 6 untracked shim/test files. Nothing in the repository was edited by this review.

## 1. Gate repeated on this tree (all as expected)

| run | log (this directory) | exit | result |
| --- | --- | --- | --- |
| gate, no flag | finaltree-gate-noflag.log | 0 | 482 tests, 0 failures, 0 errors, 8 skipped |
| gate, -Djfx.parity.require=true | finaltree-gate-require.log | 1 | 482 tests, 8 failures, 0 errors, 0 skipped |
| module | finaltree-module.log | 0 | jslc 196/0/0/0, base 5510/0/0/22, graphics 26696/0/0/858 |

- The 8 flagged failures are exactly the oracle classes, each with "the oracle could not run / golden from
  another machine": MacCoreTextGoldenTest, MacDFontGoldenTest, MacFontFinderGoldenTest,
  MacFontProcessGoldenTest, MacGlyphLayoutGoldenTest, MacAccessibleNativeTest, MacClipboardNativeTest,
  MacPixelsNativeTest. Nothing else fails.
- MacFontNullInputTest 9/0, MacFontReleaseTest 7/0, MacFontBindingStructureTest 8/0, MacFontStressTest 2/0.
- Both `hs_err_pid*` finds printed nothing. Golden `git diff --stat` printed nothing.
- No `restricted` warning in the module log.

## 2. What was checked

### Strike window (the newest change) - CONFIRMED correct by trace
`PrismFontFile.getStrike` (PrismFontFile.java:326-337), `CTFontStrike.createDisposer`
(CTFontStrike.java:93-104), `CTStrikeDisposer.dispose`, `Disposer.addRecord`, `PrismFontStrike.getDisposer`.

| path | who releases the strike's CTFont | count |
| --- | --- | --- |
| `new CTStrikeDisposer` throws | catch in createDisposer: CFRelease, strike.fontRef = 0; no record exists | 1 |
| `Disposer.addRecord` throws (WeakReference alloc, Hashtable.put rehash/Entry alloc, class init) | catch in getStrike: `disposer.dispose()` (synchronized, `disposed` flag, field zeroed) | 1 |
| addRecord succeeds, `strikeMap.put` throws | record is registered; strike is unreachable; Disposer thread | 1 |
| success | Disposer thread when the strike is collected | 1 |

- No double release with the Disposer thread: registration (`records.put`) is addRecord's last effect, so a
  throw means no record; and even if a record existed, `CTStrikeDisposer.dispose` is synchronized and
  idempotent.
- The catch-path `dispose()` may remove a stale (cleared) strikeMap entry for the same desc; that is what the
  Disposer thread would do anyway, and the stale strike's own disposer still releases its own CTFont. Harmless.
- A strike whose fontRef is 0 releases nothing on any path.
- DirectWrite/FreeType: `DWFontStrike.createDisposer` and `FTFontStrike.createDisposer` return null, the only
  three PrismFontStrike subclasses are CT/DW/FT and `getDisposer()` has one caller, so the new try is never
  entered for them.
- Healthy path: `javap -c` of getStrike shows the same call sequence (createStrike, getDisposer, addRecord,
  Map.put) with only an exception-table entry added.

### CF/CG/CT ownership, every create/copy in scope (AC 2)
Enumerated with grep over MacFontFinder.java, DFontDecoder.java and coretext/*.java (facades excluded) and
walked each against its release on normal, early-return, zero-result and exception exits:

- MacFontFinder: getFont/familyName, getSystemFontSize (only a double-returning downcall between create and
  release), getFontData (collection, fonts, 2 UI fonts, 2 descriptors), addDescriptor (4 copies),
  getCascadeList (codes, descriptors), addCascadeEntry (name, displayName, url, file). All once.
- DFontDecoder: createCTFont (nameRef, collection, fonts; the per-candidate name copy has only CFStringCompare
  between copy and release), formatOf, getCTFontTags, getCTFontTable, decode (font released on every path).
- OS: CTFontCopyURLAttribute (url, path), CTFontCopyAttributeDisplayName, CTFontCreateUIFontForLanguage,
  CTFontCreateWithName. The plain create wrappers return +1 to callers that were each checked.
- CTFontFile: both constructors (ref-taking one is covered by CTFactory.createFontFile's finally; embedded one
  by its own catch), registerFont, createCGFontForEmbeddedFont, the three path sites, getFontRef,
  getCascadingInfo.
- CTFactory.createFontFile: +1 consumed; released iff no CTFontFile was constructed; storeInMap is outside
  the try so a font the disposer owns is never released here.
- CTGlyphLayout: createCTLine (5 objects), getFontSlot/addFallbackFont (copy at +1, extra CFRetain gone, the
  only owner is the font file's SelfDisposerRecord), layout (line).
- CTGlyph: createContext (catch), getColorImage, getImage (uncached only; cached context kept by design).
- GlyfTableBounds.bounds: loca, glyf.
- Inputs 1-6: each NULL is refused before a CF/CG/CT call (addFallbackFont, getFont, familyName,
  getCascadeList both guards, getCascadingInfo, addCascadeEntry.release, formatOf, getCTFontTable, OS.rect /
  OS.transform NPE before the downcall).

No missing release, no double release found.

### Behaviour neutrality of the healthy path
Call order to CF/CG/CT is unchanged except release ordering that nothing observes (addCascadeEntry releases
after both strings are read; CTFontCreateUIFontForLanguage releases after the arena closes) and the removed
calls that were the leaks (the unused kCTFontNameAttribute copy is still made and now released; the extra
CFRetain is gone). Return values on healthy inputs are the same expressions.

### AC 4 / AC 5 / AC 6
- AC 4: the five mac-*-golden.txt have no diff.
- AC 5: CFNative.java, CGNative.java, CTNative.java have no diff.
- AC 6: no `var`, no tab, no CR, no trailing whitespace, no line over 120 columns on any added line or in the
  6 new files; no new multi-statement line; `wc -l`: DFontDecoder 206, MacFontFinder 259, CTFactory 166,
  CTFontFile 355, CTFontStrike 183, CTGlyph 320, CTGlyphLayout 192, GlyfTableBounds 118, OS 476;
  PrismFontFile 1,502 (1,497 at HEAD, over budget before the story). New production comments are ownership
  contracts or moved upstream comments. Copyright second year is 2026 in every changed file; new files carry
  the header.
- Restricted-method hygiene: no production peer gained a restricted call; MacFontProbeShim binds through
  MacFontOracleShim (test shim, allowed); no pom or --enable-native-access change.

## 3. Findings

### F1 (low, test pin gap, CONFIRMED) - MacFontBindings.java:112-146
`CTFontStrike` now has one native call site (`OS.CFRelease` in the createDisposer catch; javap line
`invokestatic OS.CFRelease:(J)V`), but `NATIVE_CALL_SITES` has no `CTFontStrike` entry, although the class is in
`PRODUCTION_CALLERS` and every other release the story added is pinned. Scenario: someone deletes or
duplicates that release; `MacFontBindingStructureTest` stays green and, the path being OutOfMemoryError-only,
no other test notices. Fix: add `"com/sun/javafx/font/coretext/CTFontStrike", "OS.CFRelease"`; the map already
has 10 entries, so `Map.of` must become `Map.ofEntries`.

### F2 (low, stale story text, CONFIRMED) - backlog/US-067-...md and backlog/US-111-...md
The "Done" section still describes the tree before the strike fix: line 105-106 (8 production files, 16
tracked files, 513/288), line 257 ("CTFontStrike was not edited"), lines 266-268 ("CTFontStrike 175,
unchanged", "PrismFontFile.java ... is unchanged"), Deviation 2 and AC 2 ("the strike's CTFont window is not
closed"), and US-111 item 8 still lists the strike window as open. The tree changes both files (CTFontStrike
183 lines, PrismFontFile 1,502) and closes the window. Fix: update those passages and drop or reword US-111
item 8 before the owner commits.

## 4. Residual risks (not findings)
- The two strike windows, the constructor catch of the embedded CTFontFile, CTGlyph.createContext's catch and
  most `finally` blocks are OutOfMemoryError-only and rest on review, as the story says; no test provokes them.
- An OutOfMemoryError while the JDK allocates the MemorySegment for a pointer returned by a create/copy
  downcall leaks that object; nothing at this layer can release an address it never received.
- With the extra CFRetain gone, a fallback CTFontFile's CTFont is really freed when its SelfDisposerRecord
  runs. `ctFontRef` is private and read only in `getFontRef`, whose callers keep the font file reachable
  (strike field, `primaryResource` used after the call), and the factory map holds fallback font files
  strongly, so I found no use after release; US-111 item 9 (strike reachability in `layout`) is the same
  class of issue and predates this story.
- NULL sites outside inputs 1-6 remain (for example `getFontData` and `createCTFont` with a NULL font
  collection result, `OS.CTFontCreateUIFontForLanguage` with a NULL UI font and a matrix); they are US-111's.
- Not run: robot and tests/system (owner-only), x86_64, other macOS versions, Windows, Linux. The
  PrismFontFile change is shared by all platforms and was verified there by reading and bytecode only.

## 5. Re-review of the final tree (2026-11-13)

Scope: the one step taken after sections 1-4 (the fix for F1). Nothing in the repository was edited.

### Delta since the reviewed tree
- Rebuilt the snapshot in the same format (`git diff HEAD -- modules` plus the 6 untracked files under
  modules) into `review/now.cumulative.patch`: 129,683 bytes, 24 files, byte-identical to
  `patches/final.cumulative.patch`. `git diff --stat HEAD -- modules`: 18 files, 543 insertions, 304 deletions.
- `diff patches/fix-10d-strike.cumulative.patch review/now.cumulative.patch` differs only inside the
  MacFontBindings.java section. The reviewed file was rebuilt in scratch (HEAD blob plus the old hunk,
  `review/delta/`) and diffed against the current file: every changed line is inside the `NATIVE_CALL_SITES`
  initialiser (lines 112-145). No other file under modules moved.
- The table: `Map.of` became `Map.ofEntries` with one `Map.entry` per class (10 pairs was Map.of's limit).
  Parsed both versions: 10 pairs before, 11 after, no duplicate key (Map.ofEntries would throw at class init),
  no existing pin string changed, one pair added: `com/sun/javafx/font/coretext/CTFontStrike` -> `OS.CFRelease`.
  Entry order follows `PRODUCTION_CALLERS`.
- Pin against bytecode: `javap -c -p` of the freshly compiled `CTFontStrike.class` shows exactly one call into
  the pinned owners, `invokestatic com/sun/javafx/font/coretext/OS.CFRelease:(J)V`. The pin is right.
- Style: longest line 120 columns, no tab, no CR, no trailing whitespace, no `var`, no new import (the file
  already imports `java.util.Map`). File diff against HEAD: 31 insertions, 25 deletions.
- F1 is closed. Note, not a finding: the pin table is enforced through `requireSameBinding` in the five golden
  classes (before their machine gate), not by `MacFontBindingStructureTest`; all five ran it in both gate runs
  below and none reported a pin problem. The implementer's mutant logs (`logs/pin-strike-*.log`) were not
  repeated here.

### Gate on this tree (logs in this directory)

| run | log | exit | result |
| --- | --- | --- | --- |
| gate, no flag | finaltree2-gate-noflag.log | 0 | 482 tests, 0 failures, 0 errors, 8 skipped |
| gate, -Djfx.parity.require=true | finaltree2-gate-require.log | 1 | 482 tests, 8 failures, 0 errors, 0 skipped |
| module | finaltree2-module.log | 0 | jslc 196/0/0/0, base 5510/0/0/22, graphics 26696/0/0/858 |

- The 8 failures are the same 8 oracle classes as in section 1, each "golden from another machine"
  (MacCoreTextGoldenTest, MacDFontGoldenTest, MacFontFinderGoldenTest, MacFontProcessGoldenTest,
  MacGlyphLayoutGoldenTest, MacAccessibleNativeTest, MacClipboardNativeTest, MacPixelsNativeTest); the same 8
  are the skips without the flag. Nothing else fails.
- MacFontBindingStructureTest 8/0, MacFontNullInputTest 9/0, MacFontReleaseTest 7/0, MacFontStressTest 2/0.
- Both `hs_err_pid*` finds printed nothing. The golden `git diff --stat` printed nothing. No `restricted`
  warning in the module log. The snapshot rebuilt after the three runs is identical to the one taken before.
- Not run: robot and tests/system (owner-only), other platforms.

### Verdict
No finding under modules/. F2 (story text) is outside this pass; the backlog files are being updated
separately. Mergeable.
