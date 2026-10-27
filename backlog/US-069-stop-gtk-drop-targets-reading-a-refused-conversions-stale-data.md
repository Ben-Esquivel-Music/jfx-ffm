# US-069 — Stop GTK drop targets reading a refused conversion's stale data

**Status:** 📋 Ready (filed 2026-10-27 from the US-001a review) · **Priority:** Low-Medium (wrong data, no crash;
needs a refused conversion after an earlier drag to the same window) · **Estimate:** S ·
**Found:** 2026-10-27, US-001a reentrant-drop regression test (the C of commit 033187ad90, kept unchanged by the
behaviour-neutral migration)

## Story
As a JavaFX app developer whose app accepts drops on Linux,
I want a drop target to get no data when the drag source refuses a conversion,
so that a drop never hands my handler the data of an earlier drag.

## Problem
`modules/javafx.graphics/src/main/native-glass/gtk/glass_dnd.cpp`, `wait_for_selection_data_hook` (the event hook
that `dnd_target_receive_data` installs while it waits for a conversion). The C of commit 033187ad90 did the same;
US-001a changes no behaviour, so it kept both defects.

1. **The refusal check never fires on GTK 3.24.** The hook reads the property only when
   `event->selection.property` is not 0 ("if 0, that we received negative response"). But
   `gdk_x11_display_translate_event` (`gdk/x11/gdkdisplay-x11.c`, case `SelectionNotify`) turns a `SelectionNotify`
   whose property is `None` into one whose property is the target:
   `if (xevent->xselection.property == None) event->selection.property = event->selection.target;`
   So a refusal looks like a reply, and the hook calls `gdk_selection_property_get` on the requestor window. That
   reads the window's `GDK_SELECTION` property without deleting it, and Glass never deletes it either, so the read
   returns whatever the last successful conversion to that window left there.
2. **The hook matches on the window only.** It accepts any `GDK_SELECTION_NOTIFY` whose `selection.window` is the
   requestor; it does not check the event's selection or target against the conversion it is waiting for. That is
   what lets defect 1 hand over the stale data unnoticed.

Measured on GTK 3.24 with two Glass processes and the same target window for two drags: drag 1 offered text and
the target read "reentrant"; drag 2's source offered only a custom mime type, the target asked for `UTF8_STRING`,
the refusal arrived as `SelectionNotify` with property `UTF8_STRING`, and the target was handed "reentrant" again.
The US-001a test `GtkDnDReentrantDropTest` works around this by giving each drag a fresh target window, so that no
`GDK_SELECTION` property is left from an earlier drag.

## Acceptance criteria
1. **A refusal yields no data.** On GTK 3.24, a conversion the source refuses makes `dnd_target_receive_data`
   answer no data, so the Java side gets what the JNI intended for a negative response (`null` data, no mime from
   that conversion), never an earlier drag's data.
2. **Refusal and reply are told apart.** The hook (or `dnd_target_receive_data`) distinguishes a refused
   conversion from a real reply. The implementer chooses how, for example: compare the reported property with the
   requested target (the translated refusal has property == target, a reply has `GDK_SELECTION`); check the
   property's type and length from `gdk_selection_property_get`; delete `GDK_SELECTION` on the requestor after each
   read (or before each conversion); and match the event's selection and target as well as its window. The chosen
   way and why are stated in a code comment.
3. **Regression test with mutant proof.** A test in the GTK display suite, a two-JVM drag like
   `GtkDnDReentrantDropTest` that drops twice on the same target window, where the second drag's source refuses
   the target's conversion; it asserts no data for the second drag. It fails on the current C (recorded in this
   story) and passes with the fix.
4. **Gates green.** `javafx.graphics` module tests and both GTK display runs (no window manager, openbox) pass,
   with no new failures against the baseline before the fix.

## Out of scope
- Other conversions or clipboard reads (`GtkSystemClipboard`); this story is the drop target's wait only.
- The reentrant-wait fix of US-001a, which is already in place.

## Definition of Done
- All acceptance criteria met, with the before/after logs kept with the change.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without
  explicit approval from the repository owner.**

## Notes
- Related: US-001a (its final review named both defects as carried from 033187ad90, outside its diff, no crash);
  filed against the tree at commit 26ed4ba2f2.
