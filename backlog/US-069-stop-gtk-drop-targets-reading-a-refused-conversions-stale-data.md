# US-069 — Stop GTK drop targets reading a refused conversion's stale data

**Status:** ✅ Done (2026-11-09; committed as `7ae305cb2a`, PR #54, whose review fixes of 2026-11-10 are uncommitted;
filed 2026-10-27 from the US-001a review; see "Done" and "PR #54 review" below) · **Priority:** Low-Medium (wrong
data, no crash; needs a refused conversion after an earlier drag to the same window) · **Estimate:** S ·
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

## Done (2026-11-09, uncommitted)

### What changed
- **Native** (`modules/javafx.graphics/src/main/native-glass/gtk`). `selection_data_ctx` also records the selection
  and the target that `dnd_target_receive_data` passes to `gdk_selection_convert`. `wait_for_selection_data_hook`
  takes the first `GDK_SELECTION_NOTIFY` (`!ctx->received`) whose window, selection and target are those of its
  conversion, and reads the property only when the event names `GDK_SELECTION` (`SELECTION_PROPERTY_ATOM`, interned
  in `init_target_atoms`), the property GDK's X11 backend always asks the owner to write to. A refusal, which
  `gdk_x11_display_translate_event` hands on with property = target, ends the wait with no data, and
  `dnd_target_receive_data` answers `FALSE`. Nothing deletes `GDK_SELECTION`. The comment above the hook gives the
  way and why, that commit 033187ad90 tested property 0, which GDK never delivers, and two limits (see Notes). The
  reentrant-wait behaviour of US-001a (captured requestor, the `enter_ctx.ctx == NULL` return) is unchanged.
  `glass_gtk_api.h` (`ggtk_dnd_target_get_data`, `ggtk_dnd_target_get_mimes`) and the `enter_ctx.mime_adds` comment
  say that a refused conversion yields no data, including a refused `text/uri-list` in the mimes walk, which now adds
  no file-list or uri-list mime. Exports unchanged (64 `ggtk_*`); glass_dnd.cpp builds with `-Werror`, no warning.
- **Tests** (`test.com.sun.glass.ui.gtk`).
  - New `GtkDnDRefusedConversionTest`, 10 tests: two child JVMs, one target window, three drags, with and without
    a window manager. Drag 1 offers text and the drop's `text/plain` read answers it. Drag 2 offers only a custom
    mime: at the drop `text/plain` answers `null`, the custom mime its bytes, `text/plain` again `null`. Drag 3
    offers the custom mime with other bytes: the target releases the button inside its `dragOver` and reads
    `text/plain` (refused), the drop runs inside that read's wait and reads the custom mime, which must answer its
    own bytes, and the outer read answers `null`. Pinned as well: the same native window at every drop, only drop 3
    inside a read, the mimes of each drag, and both children's exits.
  - `GtkDnDTraceTest`: the two `text/html` reads answer `null` (the source offers `text/html` but cannot serve it).
    The goldens, captured from the JNI build of 033187ad90, are not touched: the golden-only rule
    `nativeHtmlReadsAnswerNothing` rewrites their native `text/html` data lines to `null`, and the run's trace is
    compared as recorded. The throwing-target golden gets no rule.
  - `GtkTraceRulesTest`: new `theGoldenRulesRewriteExactlyTheRefusedHtmlReads` (the rule's reach and idempotence).
  - `GtkDnDReentrantDropTest`: its source scenario becomes `dragSource(out, drags, content)`, shared with the new
    class; its drags, its 5 tests and its separate window per drag are unchanged. Two comments changed: the class
    javadoc's window-per-drag paragraph no longer describes the stale read as current, and the
    `ReentrantTargetHooks` javadoc no longer says the outer reply ends both waits; `targetScenario`'s is as it was.
- **Script**: `.github/scripts/run-gtk-display-tests.sh` no longer counts the robot classes in a comment.

### Decisions
- The way of AC 2: match window, selection and target, and read only a reply that names `GDK_SELECTION`. No
  `gdk_property_delete`: a matched reply names a property the owner wrote (`PropModeReplace`) before it sent the
  reply, so a leftover value is read only from an owner that breaks the protocol.
- The received guard (the first matching event wins): without it a second matching event, from a nested read of
  the same mime, read again and leaked the first block.
- Drag 3's outer read is a refused one: an answered outer read is dispatched inside the nested wait and read from
  the property when the hook runs, so whether it answered its own data or the nested conversion's would be a race.
  With a refusal nothing is read, and the window-only match of 033187ad90 is caught every time.
- The goldens stay as captured (capture is refused on any build but the JNI one); the one deliberate deviation is a
  rule applied to the golden only. It changes exactly the in-process golden's lines 187, 189, 302, 304
  (`~ # peer data 0|2 text/html`) and the between-processes golden's lines 153, 155, 262, 264
  (`~ # target data 0|2 text/html`), and nothing on a second application; the in-process `# target data` lines
  (the source's own data in one process) keep the fragment.

### Measured (2026-11-09, WSL x64 Ubuntu 26.04, Temurin 27+35, Maven 3.10.0, GTK 3.24.52, Xvfb 21.1.22)
Natives built from source. The fix runs and the mutants ran on the fix's code; the native changes after them were
comment-only (diffed). The gates ran on the final tree, applied to a fresh clone of master `4799088609`.

| gate | before (master) | after |
| --- | --- | --- |
| G1 `-pl buildtools/jslc,modules/javafx.base,modules/javafx.graphics clean test`, no display | 938 classes; jslc 196 / 0 / 0 / 0, base 5,510 / 0 / 0 / 22, graphics 26,389 / 0 / 0 / 950 | 939 classes; graphics 26,390 / 0 / 0 / 950, the rest equal; new: `GtkDnDRefusedConversionTest` (0 run, no display) and one `GtkTraceRulesTest` method |
| G2 GTK display suite, no window manager | 38 classes, 256 / 0 / 0 / 4 | 39 classes, 267 / 0 / 0 / 4 |
| G2 GTK display suite, openbox | 38 classes, 229 / 0 / 0 / 27 | 39 classes, 240 / 0 / 0 / 27 |

Run / failures / errors / skipped. Per class and per method the after runs equal the before runs except for the new
class's 10 methods and the new `GtkTraceRulesTest` method; per-class sums equal Maven's totals; no `hs_err_pid`.
Per-class display results: [US-069-linux-display-per-class.tsv](US-069-linux-display-per-class.tsv).

`GtkDnDRefusedConversionTest` with the fix: 10 / 0 / 0 / 0 in 3 of 3 runs without a window manager and 3 of 3 under
openbox; `GtkDnDTargetTest` 8/8, `GtkDnDReentrantDropTest` 5/5, `GtkDnDTraceTest` 6/6 (goldens compared). Mutants,
each built from the fix plus one change, no window manager:

| C | runs | failing tests of `GtkDnDRefusedConversionTest` |
| --- | --- | --- |
| master's C (033187ad90's hook) | 3 of 3 fail | the 4 of AC 3 |
| m1: property test back to "not 0" | 1 of 1 fails | the 3 refused reads |
| m2: match on the window only | 3 of 3 fail | only `aReadNestedInAnotherReadsWaitAnswersItsOwnConversion`: `expected: <ByteBuffer [4, 5, 6]> but was: <null>` |
| m3: never reads the property | 1 of 1 fails | the 3 answered reads (`but was: <null>`) |
| m4: no received guard | 1 of 1 passes | none; the other 3 DnD classes and `GtkTraceRulesTest` pass too (see Notes) |

On master's C, `GtkDnDTraceTest` fails on exactly the 8 `text/html` golden lines and the `text/html` assertion of
`theOtherProcessReadsWhatTheSourceOffered`.

### Acceptance criteria
1. ✅ A refusal yields no data: every refused read of the new class answers `null`, in every run, and so do the two
   `text/html` reads of `GtkDnDTraceTest` in both processes. The "no mime from that conversion" half follows from
   the same `FALSE` answer, established from the code: `dnd_target_collect_mimes` adds the file-list and uri-list
   mimes only when `dnd_target_receive_data` answers `TRUE`, which a refusal no longer does; no test runs a refused
   `text/uri-list` in the mimes walk.
2. ✅ Refusal and reply are told apart by the property the event names, and the event is matched on window,
   selection and target; the hook's comment says how and why.
3. ✅ `GtkDnDRefusedConversionTest` drops three times on one window. On master's C it fails in 3 of 3 runs:
   `aRefusedConversionAnswersNoData` `expected: <null> but was: <String first drag>`;
   `aRefusedConversionAfterAnAnsweredOneAnswersNoData` `expected: <null> but was: <String \u0001\u0002\u0003>`;
   `aReadNestedInAnotherReadsWaitAnswersItsOwnConversion` `expected: <ByteBuffer [4, 5, 6]> but was:
   <ByteBuffer [1, 2, 3]>`; `theRefusedReadADropInterruptedAnswersNoData` `drag3.over.text/plain ==> expected:
   <null> but was: <String \u0001\u0002\u0003>`. With the fix it passes 6 of 6; mutant proof above.
4. ✅ G1 and both G2 runs pass, with no new failure against master (above).

### Notes
- Accepted and documented, not stories: m4 survives, because no test nests a read of the same mime inside a pending
  wait and the guard's only effect, an overwritten and leaked block holding the same data, is not visible from Java;
  the selection half of the match has no mutant of its own, because Glass only ever converts the XDND selection; two
  pending conversions of one selection and target on a window cannot be told apart (an event carries no more than
  them, a property and the request's `GDK_CURRENT_TIME`); and nested conversions share the one property
  `gdk_selection_convert` offers, `GDK_SELECTION`, so an outer reply dispatched inside a nested wait can answer the
  nested conversion's data. The last two are stated in the hook comment.
- Pre-existing, outside this story, not filed: the GTK drag source offers `text/html` but `dnd_source_set_raw`
  cannot serve it (`gtk_selection_data_set_text` refuses a target that is not a text target), as documented in
  `GtkDnDTraceTest` since 033187ad90.
- GDK sources read (GTK 3.24.52): `gdk/x11/gdkselection-x11.c:183-201` (`XConvertSelection` into `GDK_SELECTION`)
  and `:222-231` (`XGetWindowProperty` without delete), `gdk/x11/gdkdisplay-x11.c:1124-1138` (a refusal's property
  `None` becomes the target).
- An independent review (C semantics, test validity, comment truth) found no behaviour or test defect; its six
  comment and documentation items are fixed.

## PR #54 review (2026-11-10)

Uncommitted, on top of `7ae305cb2a`. Copilot (review 5848901687 of `7ae305cb2a`, on the
`event->selection.property == SELECTION_PROPERTY_ATOM` test of `wait_for_selection_data_hook`): a refused read of the
mime `GDK_SELECTION` still read leftover bytes. `ggtk_dnd_target_get_data` converts a mime it has no rule for as the
target of that name, so that read converts the target `GDK_SELECTION`, whose refusal GDK hands on with property =
target = `GDK_SELECTION`, the property a reply names. After an answered conversion on the same window the read
answered what that one had left there: on `7ae305cb2a`'s C the new refused read below answers `ByteBuffer [1, 2, 3]`.

### What changed
- **Native** (`glass_dnd.cpp`). `dnd_target_receive_data` deletes the requestor's `GDK_SELECTION` property
  (`gdk_property_delete`) right before `gdk_selection_convert` when the target is `GDK_SELECTION` itself
  (`SELECTION_PROPERTY_ATOM`), and for no other target. For that target the property's absence is what tells a
  refusal from a reply: a refusal reads no property (`gdk_selection_property_get` answers `NULL`), a reply reads what
  the owner wrote. The delete and the conversion go out in order on the one X connection, so the server runs the
  delete before the owner gets the `SelectionRequest`. "Nothing deletes `GDK_SELECTION`" and the decision "No
  `gdk_property_delete`" in "Done" above describe `7ae305cb2a`. The hook comment gives the reason and, in its limit
  sentence, what the delete leaves: a nested conversion of that target deletes the property under an outer reply not
  yet read, which then reads nothing unless the owner has already written the nested reply; and a refusal of that
  target reads whatever was written after its delete, a nested conversion's reply or the next piece of an earlier INCR
  answer that Glass neither reads nor finishes. `glass_gtk_api.h` is unchanged: its "a conversion the source refuses
  yields no data" now holds for that target too. One new import (`gdk_property_delete`), exports unchanged (64
  `ggtk_*`), no diagnostic under `-Werror`.
- **Tests** (`GtkDnDRefusedConversionTest`, 10 → 12 tests). Drag 1's source also offers the mime `GDK_SELECTION`
  (bytes `[7, 8, 9]`), and its drop reads it before `text/plain`, so the window's `GDK_SELECTION` still holds the
  text for drag 2's first refused read. New: `anAnsweredConversionOfTheTargetGdkSelectionAnswersTheSourcesData`
  (drag 1's read answers `ByteBuffer [7, 8, 9]`: the delete does not reject an answered conversion of that format)
  and `aRefusedConversionOfTheTargetGdkSelectionAnswersNoData` (drag 2's fourth read, after its answered custom-mime
  read left `[1, 2, 3]`, answers `null`). Both drags' mimes stay pinned exactly, so drag 1 offers the target
  `GDK_SELECTION` and drag 2 does not. `TEXT_MIMES` became `FIRST_MIMES`
  (`GDK_SELECTION|STRING|UTF8_STRING|text/plain`), `theFirstDragsReadAnswersItsText` checks the drop's second read,
  and the class javadoc describes the new reads.

Not chosen: deleting the property after every read, which would change every conversion and still read what a reply
nobody read left behind (the second of two pending conversions of one selection and target); refusing the target
`GDK_SELECTION` outright, which rejects answered conversions of that format.

### Measured (2026-11-10, WSL x64, Temurin 27+35, Maven 3.10.0, GTK 3.24.52, as above)
The gate ran on a fresh clone of `7ae305cb2a` with the tree's whole diff, natives built from source
(`libglassgtk3.so` imports `gdk_property_delete`), on a private Xvfb without a window manager and under openbox
(`_NET_SUPPORTING_WM_CHECK` present before and after each run). The comment and javadoc fixes of the review below
came after it: the C was rebuilt with no diagnostic, and the test class compiled.

| class | no window manager | openbox |
| --- | --- | --- |
| `GtkDnDRefusedConversionTest` | 12 / 0 / 0 / 0, 3 of 3 runs | 12 / 0 / 0 / 0, 3 of 3 runs |
| `GtkDnDTargetTest` | 8 / 0 / 0 / 0 | 0 run (needs no window manager, by design) |
| `GtkDnDReentrantDropTest` | 5 / 0 / 0 / 0 | 5 / 0 / 0 / 0 |
| `GtkDnDTraceTest` | 6 / 0 / 0 / 0 (goldens unchanged) | 0 run (as `GtkDnDTargetTest`) |

Run / failures / errors / skipped; no `hs_err_pid`. With `7ae305cb2a`'s tests the fix also passed the four classes
(29 of 29, no window manager). The module gate and the whole GTK display suite were not run again. Mutants, with the
final test, no window manager:

| C | runs | failing tests of `GtkDnDRefusedConversionTest` |
| --- | --- | --- |
| `7ae305cb2a`'s C (no delete) | 2 of 2 fail | only `aRefusedConversionOfTheTargetGdkSelectionAnswersNoData`: `expected: <null> but was: <ByteBuffer [1, 2, 3]>` |
| the fix, but the target `GDK_SELECTION` is never read | 1 of 1 fails | only `anAnsweredConversionOfTheTargetGdkSelectionAnswersTheSourcesData`: `expected: <ByteBuffer [7, 8, 9]> but was: <null>` |

The test's author saw the first row's failure on `7ae305cb2a`'s C too, once without a window manager and once under
openbox. The fix without the delete is `7ae305cb2a`'s code, so it has no row of its own.

### Review
An independent review (the C against GTK 3.24.52, the test's validity, the comments' truth) found the Copilot item
resolved and four more items:
- Pre-existing since 033187ad90, outside this item: `dnd_target_get_raw` and `dnd_target_get_image` read a 16- or
  32-bit-format reply past its block. Filed as US-109.
- The hook's limit sentence named too narrow a case for a nested conversion of the target `GDK_SELECTION` (only the
  owner's refusal of it) and left out the reverse case. Reworded, as above.
- The same sentence left out an owner still sending an earlier answer by INCR, which answers the delete with its next
  piece. Added, as above.
- The `FIRST_MIMES` javadoc spoke of any mime and any target; it now speaks of this drag's mimes.
No code changed after the gate.
