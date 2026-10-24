# US-067 — Fix the CoreText NULL crashes and leaks kept by the FFM migration

**Status:** 📋 Ready (filed 2026-10-24 from the macOS half of US-003) ·
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
