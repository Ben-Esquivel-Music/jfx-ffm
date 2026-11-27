# US-129 — Give the predefined cursors of the Java WebKit port their type

## In short
- **Closed without change on 2026-11-27 (owner's decision).**
  No JavaFX application can see it: the right cursor is shown, and only WebKit's own test tools read the unset field.
  It is not worth a WebKit build.
- **Problem:** Inside the WebKit port, the standard mouse cursors (pointer, hand, text and so on) have their internal
  "type" field left unset. Only WebKit's own test tools read that field.
- **Seen failing?** No. Read from the code. A JavaFX application cannot see it: the right cursor is still shown.
- **Goal:** Each standard cursor carries its type.
- **Done when:** A native probe reads the type of all 43 standard cursors and each is right.

**Status:** ⛔ Closed without change (2026-11-27, owner's decision; the reason is under "In short").
The status as written before:
📋 Ready (filed 2026-11-21 from US-025) · **Found:** 2026-11-21, resolving US-025: its plan trace and its
independent review (optional finding on the predefined cursors) read that the class whose custom-cursor constructor
US-025 fixed builds every predefined cursor the same way; read from the source, nothing built or run for this story

## Story
As a WebKit developer on the Java port who reads a cursor's type (DumpRenderTree and `Internals`, a debug build),
I want every predefined cursor of the Java port to carry the type it stands for,
so that `cursor: pointer` and the like report their type instead of `Invalid`, and a debug build does not assert on
them.

## Problem
Paths are relative to `modules/javafx.web/src/main/native/Source/WebCore`; line numbers are those of the tree with
US-025's change.
- On the Java port every predefined cursor comes from `getPredefinedCursor` (`platform/java/CursorJava.cpp:209-220`),
  which returns `Cursor(PlatformCursor)` (`:70-73`). The 43 accessors from `pointerCursor()` (`:222`) on call it, each
  with its `com_sun_webkit_CursorManager_*` constant, and keep the result in a `static`. That constructor sets only
  `m_platformCursor`, so `m_type` stays `Type::Invalid`, the default at `platform/Cursor.h:183`.
- `testing/Internals.cpp:4498` (`getCurrentCursorInfo`) reports `cursorTypeToString(cursor.type())`, so it reports
  `Invalid` for `cursor: pointer` and every other predefined cursor (read by US-025's review; DumpRenderTree was not
  run). In a debug build `Cursor::type()`'s `ASSERT(m_type > Type::Invalid)` (`platform/Cursor.h:251`) fires.
- `Cursor::ensurePlatformCursor` (`CursorJava.cpp:80-207`) returns at once for a non-zero platform cursor (`:82-83`).
  When a predefined cursor's platform cursor is 0 (no host theme table, `:212-214`), its `switch (m_type)` (`:85`) runs
  on `Invalid` and ends in `default:`, the pointer: the arm of the cursor's own type is never taken.
- No memory-safety impact. Nothing in the Java code of javafx.web reads the type: `WebPageClientImpl.setCursor`
  (`modules/javafx.web/src/main/java/com/sun/javafx/webkit/WebPageClientImpl.java:71-77`) maps the id it gets to a
  JavaFX cursor, and an unknown id to `Cursor.DEFAULT`.
- Outside US-025's three items, which are about the custom constructor; upstream's `CursorJava.cpp` was not checked
  for it.

## Constraints
- **The ids that reach Java stay as they are,** for every predefined cursor: the same `cursor_get_predefined_id`
  argument, the same id handed to Java, and the same number of upcalls (today one per accessor, on its first use).
- **Nothing on the Java side comes to depend on the type:** no Java class of javafx.web, and no slot of the host ABI,
  is given or reads it.
- **US-025's custom cursor stays as it is:** `CustomCursorTest` passes, and the observations of what reaches Java in
  US-025's reviewer probe are unchanged.

## Acceptance criteria
1. **Every predefined cursor has its type.** A native probe built as in
   `US-025-native-probes.md`, with a fake host installed, calls each of the 43 accessors and
   checks that `type()` is the `Cursor::Type` that `Cursor::fromType` (`platform/Cursor.cpp:59`) maps to that accessor.
   Mutant proof: with one accessor's type left unset, the probe fails.
2. **What reaches Java is unchanged.** The same probe records, for every accessor, the argument of
   `cursor_get_predefined_id`, the id returned and the number of upcalls, before and after the change, and the two
   records are equal.
3. **No regression.** `CustomCursorTest` passes against the changed `jfxwebkit`, and the `modules/javafx.web`
   `default-test` gives the per-class list of the final gate in
   `US-025-windows-per-class.tsv`. `java buildtools/CheckNoJni.java .` passes.

## Definition of Done
- All acceptance criteria met, with the probe's sources, mutants and results kept with the change and the results
  stated here.
- The PR names the `jfxwebkit` it was verified with (a local build or `build-webkit.yml`), with the `tests/system` web
  tests passing against it (owner-run).
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree or on a review branch — **no commits, merges or PRs without explicit approval from
  the repository owner.**
