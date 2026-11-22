# US-129 — Fix the macOS font-layer races, NULL inputs and test gaps US-111 left

**Status:** 📋 Ready (filed 2026-11-22 from US-111) · **Found:** 2026-11-21 and 2026-11-22, the traces, critiques,
slice records and reviews of US-111, on the US-111 tree; every item was read again on that tree for this story;
nothing was built or run for it

## Story
As the maintainer of the macOS font layer,
I want the defects and test gaps that US-111's work found beside its twelve sites to have a defined outcome,
so that concurrent layout and rendering cannot mix glyph images, lose a fallback font or release a font still in
use, no entry point hands NULL to CoreFoundation, CoreGraphics or CoreText, and US-111's tests prove their fixes
wherever the macOS gate runs.

## Problem
Paths are relative to `modules/javafx.graphics/src/main/java/com/sun/javafx/font`; line numbers are those of the
US-111 tree (uncommitted on `abe0943d31`). The records named are those listed under "Logs" in US-111's "Done".
Nothing here was reproduced unless the item says so.

**Races.**

1. **Two threads drawing into the one cached bitmap.** `coretext/CTGlyph.java` (`getImage`, :201-254) is synchronized
   on the glyph. On the cached path it takes the one 256×256 context from `getCachedContext` (:156-163), whose lock
   is released when it returns, then fills, translates, draws into and reads that context (:214-248). Two threads
   that draw two different glyphs at once do this in the same bitmap. Seen by reading (US-111's site 9 trace); its
   race test calls `getCachedContext` alone so that it does not fail for this reason. Every caller of the cached path
   that the trace found is on the render thread, so no production pair of threads was found.
2. **Bounds published before they are filled and clamped.** `coretext/CTGlyph.java` (`checkBounds`, :77-120)
   assigns `bounds = new CGRect()` (:79) to a plain field before it computes the advance (:86-88), fills the box
   (:96-99), applies the matrix (:100-103) and clamps the size (:105-119). Another thread that reads the same glyph
   meanwhile (`getPixelData`, `getAdvance`, `getWidth` and the other readers) finds `bounds != null` (:78) and reads a
   zero, unfilled or unclamped box, or a zero advance; nothing orders the writes for it either. A negative width makes
   `CGBitmapContextCreate` answer NULL (US-111's probe), so the glyph image is empty. Seen by reading (US-111's
   CoreText trace: plausible, not traced to a real pair of threads).
3. **Map and slot writes without the lock their readers take.**
   - `PrismFontFactory.java` (`storeInMap`, :399-410) puts into the `HashMap` `fontResourceMap` (:76) without the
     factory's monitor. `coretext/CTFactory.java` (`createFontFile(String, long)`, :85) calls it on the layout thread
     that found a new fallback font, through `CTGlyphLayout.getFontSlot` and `addFallbackFont`, while
     `getFontResource` (`PrismFontFactory.java:446`, :696) reads the map under that monitor on other threads.
   - `LogicalFont.java` (`addNativeFallback`, :282-306) reads `nativeFallbacks`, copies it with the new font
     appended and writes it back, with no lock; `getSlotForFont` (:259-280) calls it from `CTGlyphLayout.getFontSlot`
     on any layout thread. Two threads that add a font at once can each append to the same old array: one font is
     lost, both get the same slot number, and the glyphs of the lost font are drawn with the font that holds the
     slot. `FallbackResource.java` (`addNativeFallback`, :246-270) does the same on a field that is not volatile,
     which the synchronized `getSlotResource` (:321) reads.
   - Seen by reading (US-111's lifetime trace: plausible, map corruption or a lost slot); `FallbackResource`'s copy
     was read for this story.
4. **One cascade-list thread per racing virtual caller.** `FallbackResource.java` (`getLinkedFonts`, :283-291) checks
   and sets the non-volatile `fallbacks` with no lock, called unsynchronized from `getSlotForFontNoCreate` (:200) and
   `getNumSlots` (:294); `LogicalFont.java` (`getLinkedFonts`, :213-221) checks a volatile field, but not
   atomically. So every thread that lays out a new composite font at once runs `getFallbacks`, then
   `coretext/CTFontFile.getCascadingInfo` (:314-327) and `MacFontFinder.getCascadeList`, and since US-111 each
   virtual one starts its own platform thread (`MacFontFinder.java`, `cascadeListOnAPlatformThread`, :277-288). Seen
   by reading (US-111's concurrency critique, item 4, and its slice D record); not measured.
5. **Error edges of the cascade-list call.** `MacFontFinder.java` (`resultOf`, :296-322) waits for the worker
   because the caller releases the font it lent as soon as `getCascadeList` returns (`coretext/CTFontFile.java`,
   :320-322). Seen by reading the code and the JDK 27 sources (US-111's slice D record and its ownership review);
   none was provoked:
   - A `StackOverflowError` at the call of `resultOf` (:287) ends the wait before `get()` is reached, and one from
     `isDone()` in the spin (:306-308) ends it while the task runs. The font can then be released while the worker
     still reads it in `CTFontCopyDefaultCascadeListForLanguages` (:334).
   - An `OutOfMemoryError` while `get()` builds its `InterruptedException` (`FutureTask.java:482-484` of JDK 27)
     comes after `Thread.interrupted()` cleared the flag, so that interrupt is lost; the caller gets the error once
     the task is done.
   - After any error of `get()` the caller spins on its carrier for the rest of the worker's run.
   - `coretext/ObjCNative.java` (`objc_autoreleasePoolPush`, :93-99): an `OutOfMemoryError` while the token is
     wrapped in a `MemorySegment` leaves the pool that `pooledCascadeList` pushed (`MacFontFinder.java:263`) with no
     pop. The next pop of an outer pool on that thread, or the thread's exit, drains it.

**NULL or 0 handed to CoreFoundation, CoreGraphics or CoreText outside US-111's sites.**

6. **At entry points that only shims reach.**
   - `DFontDecoder.java`: `getCTFontFormat`, `getCTFontTags` and `getCTFontTable` (:113-114, :139-140, :155-156)
     hand font 0 to `CTFontCopyAttribute`, `CTFontCopyAvailableTables` and `CTFontCopyTable`. `decode` refuses font 0
     first (:178-181); `DFontDecoderShim` (:67-80) reaches them with 0.
   - `coretext/OS.java` (`CFStringCreateWithCharacters(long, char[], long)`, :309-314): null `chars` with a count
     above 0 hands NULL to CoreFoundation (a probe of US-111's CoreText trace: SIGSEGV, exit 139), and a count beyond
     the array makes it read past the copy. Production calls it only from `CFStringCreate` (:57-61), with the array's
     length; `OSShim` (:196) reaches it. The four-argument form (:115-125) checks both.
   - `MacFontFinder.java` (`cascadeList`, :328): a NULL font goes to `CTFontCopyDefaultCascadeListForLanguages`
     (:334). `getCascadeList` returns for 0 first (:248-250); `MacFontFinderShim.cascadeList` reaches it with 0.
   - Seen by reading (US-111's finder and CoreText traces, and its slice A review).
7. **From one-time objects that are never checked.**
   - `coretext/CFNative.java` (`boldCFString`, :241-247) makes `CFSTR("bold")` once with `toCFString`, which answers
     NULL when CoreFoundation cannot make the string. The "." branch of `OS.CTFontCreateWithName` hands it to
     `CFStringFind` unchecked (`coretext/OS.java:426`).
   - `coretext/CTGlyph.java` (:51-52): `GRAY_COLORSPACE` and `RGB_COLORSPACE` are made once and go to
     `CGBitmapContextCreate` (:138) unchecked. Whether CoreGraphics can answer NULL for them was not verified.
   - Seen by reading (US-111's crash-and-ownership critique, item 4d, and its CoreText trace).

**Wrong output.**

8. **Slot -1 glyphs drawn with the slot-0 font.** `coretext/CTGlyphLayout.java` (`layout`, :176-181): when
   `getFontSlot` answers -1 for a run, the run's glyph IDs are stored with slot 0, so they are drawn with the slot-0
   font, in which the same IDs are other glyphs. Seen with the System font and Devanagari text:
   `.SF Devanagari Regular`, whose file `/System/Library/Fonts/SFIndia.ttc` holds no face of that name, gets slot -1,
   and its glyphs `7f;40a;f3;d5;40` are drawn with slot 0 (golden row `lay.system.s12.t06.r00`,
   `mac-layout-golden.txt:1265`; US-111's slice C measurement). Upstream behaves the same (US-111's lifetime trace).
9. **The cached context keeps the first strike's settings.** `coretext/CTGlyph.java` (`createContext`, :126-154, and
   `getCachedContext`, :156-163): the one cached 256×256 context is made once, with the colour space and font
   smoothing of its first caller's `lcd` argument and the subpixel positioning and quantization of its first strike
   (:141-145). Every later glyph of every strike that fits draws into it with those settings. Seen by reading
   (US-111's lifetime trace); `MacCoreTextGoldenTest` requires the cache to be empty before its corpus for this
   reason (`MacCoreTextGoldenTest.java:674`). Upstream behaves the same.
10. **An unreadable font file is remembered as a missing face.** `PrismFontFactory.java` (`findFontIndex`, :272-295)
    catches every exception and answers -1 (:289-294), the answer it also gives for a readable file whose faces all
    have other names. Since US-111, `CTFactory.createFontFile` remembers -1 by name (`coretext/CTFactory.java:74-76`)
    and `CTGlyphLayout.getFontSlot` then makes no lookup (:111). A file that could not be read at the first layout,
    for example one being replaced or one opened with no file handle left, is not looked at again in the process,
    where before US-111 the next layout tried again. Seen by reading (US-111's concurrency critique and its stated
    property 8).

**Tests and code.**

11. **Guards that no test pins in full.**
    - Site 4 of US-111: `MacFontBindingStructureTest.aFontNameIsMeasuredBeforeItsFirstCharacterIsRead` proves that
      `CFStringGetLength` is called before `CFStringGetCharacterAtIndex` in `OS.CTFontCreateWithName`, not that the
      read depends on the length: `>= 0` for `> 0` (`coretext/OS.java:424`) passes it and every run-time test
      (US-111's slice B review).
    - Site 12: `layoutAsksForAFallbackFaceBeforeCopyingTheRunFont` proves that `mayFindFace` is called before
      `OS.CTFontCreateCopyWithAttributes` in `getFontSlot` (`coretext/CTGlyphLayout.java:111-112`), not that the copy
      depends on its answer. A body that asks, copies anyway and uses the answer only for `addFallbackFont` passes
      it, passes `aFallbackFaceItsFontFileLacksIsLookedUpOnce` and moves no pin, and leaks one CTFont copy per later
      layout (US-111's tests-and-evidence review).
    - Site 5 (`coretext/CTFontFile.java:212`) and site 3's guarded release of the name string
      (`DFontDecoder.java:54-56`): no test fails when either guard is removed (US-111's R5 and R3, which survived its
      final mutant pass). CoreText answers 0.0 for a NULL font, and nothing makes `toCFString` answer NULL.
12. **Tests that depend on this Mac.** None of these is machine-gated, so CI's macOS jobs run them; none has run on
    CI, on x86_64 or on another macOS release. On the Mac of US-111 (macOS 27.2.1, arm64, an admin account) all
    three pass.
    - `MacFontReleaseTest.aFallbackFaceItsFontFileLacksIsLookedUpOnce` needs a System-font Devanagari fallback whose
      face its file lacks (there `.SF Devanagari Regular` in `SFIndia.ttc`). Where the face is found it fails at its
      precondition (`MacFontReleaseTest.java:271`).
    - The bound of 10 blocks per call of the two cascade-list tests (`MacFontReleaseTest.java:69`) comes from that
      Mac: controls 228.97 to 230.81, the fixed subject at most 0.283, a first round up to 8.7. Elsewhere the control
      check or the bound may fail.
    - `MacFontNullInputTest.aContextCoreGraphicsDidNotMakeIsZeroAndNothingIsSetOnIt` reads the unified log with
      `/usr/bin/log show`. On an account that cannot read it the test aborts, a 9th skip in the gate; where
      CoreGraphics no longer logs the setters on NULL it waits 30 s (`MacFontNullInputTest.java:64`) and aborts.
    - Seen in US-111's records and final reviews.
13. **A condition that is always true.** `coretext/CTGlyph.java` (`createContext`, :146-150): the `catch` releases
    the context only `if (context != 0)`, which is always true since US-111 returns for 0 before the `try` (:139).
    Seen by reading (US-111's slice B record).

## Requirement
Afterwards, item by item:
1. A glyph image holds the pixels of its glyph alone, whatever other threads draw at the same time.
2. Every reader of a glyph's bounds and advances, on any thread, gets the values `checkBounds` computes in full,
   clamped as today.
3. Concurrent layouts that store a font resource or add a native fallback lose no font and corrupt no map: every
   font stored is found by a later lookup, and every slot number handed out holds the font it was handed out for.
4. The platform threads that cascade-list calls start for one composite font do not grow with the number of
   virtual threads that lay it out at once.
5. Whatever error a virtual caller's wait meets, the font it lent is not released while the worker can still read
   it, and an interrupt the wait cleared is set again before the call returns or throws. A cascade-list call leaves
   no autorelease pool of its own pushed on its thread.
6. No entry point of the font layer, shims' reach included, hands NULL or 0 to CoreFoundation, CoreGraphics or
   CoreText for the inputs of item 6; each has a defined outcome that is not a crash.
7. The same for the one-time objects of item 7, whether or not CoreFoundation or CoreGraphics can make them.
8. The glyphs of a run whose font takes no slot are not drawn with another font.
9. A glyph is drawn with the LCD and subpixel settings of its own strike, whatever strike drew first.
10. A face lookup that fails because its file could not be read is tried again at a later layout, while a face that
    a readable file does not hold is still looked up once per process (US-111's criterion 4).
11. Each of these guards fails a test when it is removed or weakened: the length test of site 4, the dependence of
    site 12's copy on `mayFindFace`, site 5's guard and site 3's guarded release.
12. On a healthy tree the macOS gate passes on every macOS host it runs on: this Mac, another macOS release, x86_64
    and CI's macOS jobs. Its result shows, for each of the three tests, whether it proved its fix on that host, and
    none passes without proving it.
13. `createContext`'s `catch` tests no condition that cannot be false.

## Constraints
- **US-111's outcomes stay.** Its twelve sites keep their defined outcomes, its tests keep passing and its mutants
  stay killed. In particular: no context is left without an owner (its criterion 2); no virtual thread pushes or pops
  an autorelease pool, and a pool is popped on the thread that pushed it (its site 10); a fallback face that a
  readable file lacks is looked up once per process (its criterion 4).
- **No other behaviour change.** Single-threaded, healthy paths give today's results, except where items 8, 9 and
  10 change them.
- **Goldens.** No row of the five `mac-*-golden.txt` files changes, except where item 8 or 9 changes a recorded
  value; such a row moves with its reason recorded here. On a machine where the goldens are no oracle, the five
  corpora are compared before and after as US-067 and US-111 did.
- **Facades.** `CFNative`, `CGNative`, `CTNative` and `ObjCNative` change only where a requirement needs it, and the
  facade tests' descriptor tables change with them. Every `MacFontBindings` pin that moves is recorded with its
  reason.
- **Tests.** Every fix has a test that fails without it and passes with it (mutant proof recorded in this story).
  Where no test can provoke a path, the story says so and the review checks it; the story lists those paths. A
  crashing input runs in a child JVM, so that a regression fails the test instead of ending the test fork. No test
  passes without proving its fix.
- **Code rules.** Java 27 final features only, no `var`; a production file that is at most 900 lines (`wc -l`) stays
  so; lines at most 120 columns, never several statements on one line; comments only for a non-obvious "why".

## Acceptance criteria
1. **Requirements 1 to 10 and 13 hold,** each stated in the test that pins it or, where no test can provoke it, on
   the list of paths that rest on review, with its reason. Requirement 11 holds only through tests: for each of its
   four guards, a mutant that removes or weakens the guard and the test it fails are recorded here.
2. **Mutant proof.** For each fix, a mutant that undoes it and the failure it gives are recorded here; a skipped or
   aborted test is not a kill.
3. **Requirement 12 on every host.** The gate's per-class result is recorded on this Mac, on another macOS release,
   on x86_64 and from a `submit.yml` run, with each of the three tests marked as having proved its fix or not.
4. **Goldens and facades** as under "Constraints", with every moved row and pin recorded.
5. **Before and after** results of the "Verification" block are stated here.

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
The US-111 baseline on macOS 27.2.1 (26B131), where no golden is an oracle: the gate runs 506 tests (163 in the
font classes, 343 in the Glass classes `Mac*Test` also selects), with 0 failures and 8 skipped without the flag and
the same 8 failing with it; the module run is 26,720 tests in `javafx.graphics`, 0 failures, 858 skipped.

## Out of scope
- The life of `CTGlyph`'s colour spaces and of its one cached context, kept for the process by design (US-067,
  US-111).
- The start-failure path of `getCascadeList`, which makes the list without a pool, as before US-111 (its deviation
  7).
- The keying of US-111's site 12 memory by name alone, as `storeInMap` keys the faces it found (its stated property
  8).
- `CTFactory.createFontFile`'s `NullPointerException` for a null name whose face is not found, which only a shim can
  pass (US-111's stated property 8).
- Two test-only leftovers of US-111's site 9 child, both harmless: its `CTFontStrikeShim` strike has no disposer
  record, so its CTFont stays allocated until the child JVM exits, and `CacheCall` catches `Exception`, not
  `Throwable`, so an `Error` fails the test later, at `context.made`, with a less direct message.
- Re-capturing the macOS goldens for macOS 27.2.1. They were captured on macOS 27.0.1 (26A434) from the JNI build.
- Any other behaviour change in the font layer; each goes to its own story.

## Definition of Done
- All acceptance criteria met, with the before and after results stated in this story.
- An independent review (`ffm-reviewer`) repeated the macOS gate.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without
  explicit approval from the repository owner.**

## Notes
- Related: US-111 (its traces, slices and reviews found these; see its "Rests on review" and "Deviations and stated
  properties"), US-067.
- US-111's records are in a session scratch directory that is temporary; this story quotes what it needs from them.
