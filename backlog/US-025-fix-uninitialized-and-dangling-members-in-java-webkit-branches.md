# US-025 — Fix three uninitialized or dangling members in the Java branches of WebKit

**Status:** 📋 Ready (filed 2026-09-30; each confirmed by reading the source, none reachable in production today, nothing built or run for them) · **Found:** 2026-09-30, fixing the `TextureMapper` crash behind the `TextureMapperTest` failure: a read-only sweep of all 573 `PLATFORM(JAVA)` guard sites (264 files) for the same bug class, a member the Java build leaves uninitialized but still uses

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
