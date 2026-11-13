# US-111 — Fix the CoreText NULL inputs and font-layer defects US-067 left

**Status:** 📋 Ready (filed 2026-11-13 from US-067) ·
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
- An independent review (`ffm-reviewer`) repeated the macOS gate.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without
  explicit approval from the repository owner.**

## Notes
- Related: US-067 (it traced these sites and left them; see its "Deviations").
- Site numbers of US-067's evidence: 1-7 here are its N1-N7, 8 is N8, 9 is N10, 10 is N12, 11 is N9 and 12 is
  N11. Its N13 (the goldens are no oracle on macOS 27.2.1, and `Mac*Test` selects the Glass classes too)
  is not a code site; see "Verification" and "Out of scope".
