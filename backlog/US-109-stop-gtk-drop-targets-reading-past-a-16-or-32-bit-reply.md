# US-109 — Stop GTK drop targets reading past a 16- or 32-bit-format reply

**Status:** ✅ Done (2026-11-11, uncommitted, PR pending; filed 2026-11-10 from the PR #54 review) ·
**Priority:** Medium (a native heap over-read into the app's data, possibly a JVM crash; pre-existing since commit
033187ad90) · **Estimate:** S · **Found:** 2026-11-10, the independent review of PR #54 (US-069's review round), by
reading `glass_dnd.cpp` against the GTK 3.24.52 source; not run

## Story
As a JavaFX app developer whose app accepts drops on Linux,
I want a drop target's read to hand over exactly the bytes of the drag source's reply,
so that a drop never puts the native heap next to that reply into my data, or crashes the JVM.

## Problem
`modules/javafx.graphics/src/main/native-glass/gtk/glass_dnd.cpp`, `dnd_target_get_raw` and `dnd_target_get_image`.
`wait_for_selection_data_hook` stores the answer of `gdk_selection_property_get` in `ctx.length`, and that answer is
already a byte count: `_gdk_x11_display_get_selection_property` (`gdk/x11/gdkselection-x11.c`, GTK 3.24.52) turns
the items into bytes before it copies them:

```
            case 16:
              length = sizeof(short) * nitems;
              break;
            case 32:
              length = sizeof(long) * nitems;
              break;
          ...
          length += 1;

          if (data)
            *data = g_memdup (t, length);
          ...
      return length - 1;
```

Both functions multiply it by the format again (`const gint fmtDiv8 = ctx.format / 8;`). `dnd_target_get_raw` sets
`out->count = ctx.length * fmtDiv8`, which `GtkDndCalls` copies into the app's `ByteBuffer`
(`ByteBuffer.wrap(bounded(data, count).toArray(JAVA_BYTE))`, line 246), and `dnd_target_get_image` hands
`ctx.length * fmtDiv8` bytes to `g_memory_input_stream_new_from_data` for the pixbuf loader. A format-8 reply, which
is what a Glass source sends, is read right; a 16-bit reply is read at twice its size and a 32-bit one at four times.
The JNI of commit 033187ad90 did the same (`jsize length = ctx.length * fmtDiv8;` ... `SetByteArrayRegion(array, 0,
length, ...)`, as the review quotes it), and US-001a's behaviour-neutral migration kept it.

Example: a GTK owner answers a conversion larger than `GTK_SELECTION_MAX_SIZE` (262,144 bytes at most; e.g. an image
dragged from another GTK app) with an INCR header: type INCR, format 32, one item. `ctx.length` is 8 (one `long` on
LP64) and the block 9 bytes, but the read copies 32, so 23 bytes of the neighbouring heap reach the app's
`ByteBuffer` (raw path) or the pixbuf stream (image path). A format-32 reply of n items is read 24n − 1 bytes past
its 8n + 1 byte block, which can cross into unmapped memory and crash the JVM inside the FFM copy. No test reads a
16- or 32-bit reply today.

## Acceptance criteria
1. **Upstream first.** Before any change, search openjdk/jfx (its `master` and JBS) for a fix of, or an open bug
   about, this multiplication in `glass_dnd.cpp`, and record what was found here, with the IDs.
2. **The reply's byte count.** `dnd_target_get_raw` and `dnd_target_get_image` take `ctx.length` as the byte count:
   the `* fmtDiv8` and its `INT_MAX / fmtDiv8` overflow guard go, the `ctx.length <= 0` check stays. A code comment
   says why this differs from commit 033187ad90.
3. **Regression test with mutant proof.** A GTK display test reads a 32-bit-format reply, for example a two-JVM drag
   like `GtkDnDRefusedConversionTest` whose source offers more than 262,144 bytes of a custom mime, so that GTK
   answers with an INCR header, and pins exactly what the read answers (byte count and bytes, never "not 32 bytes").
   It fails on the current C, recorded here, and passes with the fix. If a Glass GTK source cannot make GTK send
   such a reply, this story records why and the test produces one another way.
4. **No new failures.** The `javafx.graphics` module tests and both GTK display runs (no window manager, openbox)
   pass, with no new failure against a run before the change.

## Verification
In WSL on a private Xvfb (see the `jfx-ffm-testing` skill), from the repository root:
```
env -u DISPLAY dbus-run-session -- env DISPLAY=:NN mvn -B -ntp -pl modules/javafx.graphics test -DskipNative=true \
    -DPRIVATE_DISPLAY=true -DUSE_ROBOT=true -Dsurefire.failIfNoSpecifiedTests=false \
    -Dtest='test/com/sun/glass/ui/gtk/GtkDnD*Test'
```
Before the change the new test fails (criterion 3).

## Out of scope
- Reading INCR transfers: Glass reads only the header, so a drop of that size still yields no usable data.
- What the bytes of a 32-bit reply mean to Java (GDK hands each item on as a C `long`, 8 bytes on LP64): only the
  count changes.
- Reads other than the drop target's (`GtkSystemClipboard`).

## Definition of Done
- Criterion 1 is recorded here and criteria 2 to 4 are met, with the before and after results stated in this story.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without
  explicit approval from the repository owner.**

## Notes
- Related: US-069 (its PR #54 review found this outside its scope), US-001a (the migration that kept it).
- Line numbers are of `7ae305cb2a`: `glass_dnd.cpp` 588-597 (`dnd_target_get_image`) and 657-664
  (`dnd_target_get_raw`); `GtkDndCalls.java` 246.

## Done (2026-11-11, uncommitted)

### What changed
`glass_dnd.cpp` (+9 / −7) and a new test class, `GtkDnDWideFormatReplyTest`. `glass_gtk_api.h` is unchanged: its
byte-count contract ("BYTES / IMAGE: byte count") is what the fix now delivers.
- **`dnd_target_get_raw`** takes `ctx.length` as the byte count: `fmtDiv8`, the `* fmtDiv8` and the `fmtDiv8 <= 0`
  and `INT_MAX / fmtDiv8` guard are gone, `if (ctx.length <= 0)` with its `g_free(ctx.data)` stays, and
  `out->count = ctx.length`. A three-line comment says why this differs from commit 033187ad90:
  `gdk_selection_property_get` answers a byte count (`_gdk_x11_display_get_selection_property` counts a 16-bit item
  as a `short` and a 32-bit one as a `long`), and 033187ad90's multiplication by format / 8 read a 16- or 32-bit
  reply at 2 or 4 times its size, past the end of its block.
- **`dnd_target_get_image`** the same: the same check, which keeps its comment on why it frees there, and
  `g_memory_input_stream_new_from_data(ctx.data, ctx.length, (GDestroyNotify)g_free)`, with a one-line comment that
  refers to `dnd_target_get_raw`.

### Criterion 1: upstream
No fix and no bug upstream, searched 2026-11-11.
- openjdk/jfx `master` at `c6413874cc` (2026-11-10) still multiplies, `glass_dnd.cpp` 429-433 (`dnd_target_get_image`:
  `const gint fmtDiv8 = ctx.format / 8;` ... `g_memory_input_stream_new_from_data(ctx.data, ctx.length * fmtDiv8,
  ...)`) and 498-503 (`dnd_target_get_raw`: `jsize length = ctx.length * fmtDiv8;`). The file's last upstream change
  is `bdd0dae617` (8320441, 2023-11-29).
- JBS (anonymous REST search): `text ~ "fmtDiv8"` 0 issues; javafx and `text ~ "dnd_target_get_raw"` 0; javafx and
  `text ~ "gdk_selection_property_get"` 0; javafx and `summary ~ "INCR"` 0; javafx, window-toolkit, drag, linux and
  one of garbage / extra bytes / over-read / heap: 5 issues, none about this (JDK-8275033, JDK-8154040, JDK-8154848,
  JDK-8123093, JDK-8093947).
- Open upstream PRs that touch `glass_dnd.cpp`: openjdk/jfx#2353 (8395594, a ContextMenu after a DnD gesture) does
  not touch these lines; #2197 (8373452) changes no GTK file.

### Criterion 2: the byte count
- Checked before the change in the GTK 3.24.52 source (`gdk/x11/gdkselection-x11.c` of tag 3.24.52, sha256
  `ce49fc15...`): the quote under "Problem" is right, and the ATOM branch answers bytes too
  (`sizeof (GdkAtom) * num_atom + 1`), so the answer is a byte count for every format. Dropping the `fmtDiv8 <= 0`
  test loses nothing: with data the format is 8, 16 or 32, and GDK's error path answers 0, which `ctx.length <= 0`
  catches.
- `libglassgtk3.so` built from source before and after with the same compile command (`-Wextra -Wall ... -Werror`):
  no diagnostic for `glass_dnd.cpp` either way. Exports unchanged: `nm -D` lists 4,455 defined symbols (64 `ggtk_*`
  functions) and 367 undefined ones before and after, with empty diffs; both functions are `static`.
- With the fix the four existing DnD display classes passed 31 of 31 without a window manager (`GtkDnDTargetTest` 8,
  `GtkDnDTraceTest` 6 against its unchanged JNI goldens, `GtkDnDRefusedConversionTest` 12, `GtkDnDReentrantDropTest`
  5): every Glass source answers with format 8, where nothing changes.
- **The image half is checked by reading only.** The regression test covers `dnd_target_get_raw`. A Glass source
  sends images with format 8 (`gtk_selection_data_set_pixbuf`), its only wide image reply is an INCR header, and that
  decodes to no image whether 8 or 32 bytes are read. So a mutant that reverts only the `dnd_target_get_image` hunk
  passes every DnD class; that change rests on the GTK source above.

### Criterion 3: regression test
`GtkDnDWideFormatReplyTest` (`test.com.sun.glass.ui.gtk`, `@EnabledOnOs(LINUX)`, 5 tests): two child JVMs and one
drag, with the drag source, marker handshake and window placement of `GtkDnDReentrantDropTest` (its static helpers,
not copies). The source offers `application/x-jfx-dnd-small`, the bytes `{11, 22, 33}` (format 8, the positive
control), and `application/x-jfx-dnd-large`, 300,000 bytes. At the drop the target reads the small mime, then the
large one. Pinned exactly: the mimes (`application/x-jfx-dnd-large|application/x-jfx-dnd-small`), the small read
(`ByteBuffer 3 [11, 22, 33]`), the large read (`ByteBuffer 8 [-32, -109, 4, 0, 0, 0, 0, 0]`: 300,000 as a
native-order `long`), the drop with `COPY`, and both children's exit 0 without an `hs_err_pid` file. It has no
`requireNoWindowManager`, so it runs in both display passes and the runner's `expected_zero_classes` is unchanged.

The GTK 3.24.52 facts it rests on (`gtk/gtkselection.c`, `gdk/x11/gdkselection-x11.c`, libX11 1.8.13 `GetProp.c`):
`GTK_SELECTION_MAX_SIZE` is `MIN(262144, the maximum request size - 100)` on X11, so 300,000 bytes always go INCR.
`_gtk_selection_request` then writes type INCR, format 32 and one item, the data's length (`data.length /
gtk_selection_bytes_per_item (8)` = 300,000). `XGetWindowProperty` hands a format-32 item on as a C `long`, so
`ctx.length` is 8 and the block 9 bytes. Glass never deletes the property, so GTK sends no chunk, and the large read
is the drop's last conversion.

**On master's C** (`a10949f250`) the large read fails and the small control passes. The gate's mutant m0 (master's
`glass_dnd.cpp` with the new test, no window manager) failed that way in 2 of 2 runs, with the same message both
times (one line in the report, broken here after "but was:"):
```
drag1.drop.large ==> expected: <ByteBuffer 8 [-32, -109, 4, 0, 0, 0, 0, 0]> but was:
<ByteBuffer 32 [-32, -109, 4, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 37, 0, 0, 0, 0, 0, 0, 0]>
```
Bytes 0 to 7 are the header, byte 8 is the block's terminating NUL and bytes 9 to 31 are 23 bytes past the block. Two
earlier runs on that C failed the same way, with other heap bytes at 16 to 23 (`64, -53, 38, -124, -12, 127, 0, 0`,
a user-space pointer, and `-96, 13, 37, 0, -88, 118, 0, 0`). In all 4 runs `aFormat32ReplyAnswersItsBytes` was the
only failure, and no `hs_err_pid` was written.

**With the fix** it passed 5 of 5 in every run: 2 runs without a window manager and 1 under openbox when it was
written, the gate's 2 + 1 targeted runs, and both passes of the display gate below. Each read the 8 header bytes and
the 3 control bytes exactly.

### Criterion 4: no new failures
WSL x64 (Ubuntu 26.04, 8 CPUs), Temurin 27+35, Maven 3.10.0, GTK 3.24.52, Xvfb 21.1.22, Openbox 3.6.1, natives
built from source. Before: a clone of master `a10949f250`. After: the same commit with this change.
- Module run (`mvn -B -ntp -pl buildtools/jslc,modules/javafx.graphics -am test
  -Dsurefire.failIfNoSpecifiedTests=false`, without `DISPLAY`), before and after: jslc 196, base 5,510 (22 skipped),
  graphics 26,390 (950 skipped), 0 failures, 0 errors, equal per method. The new class runs no test there (no
  display), like the other `GtkDnD*` classes.
- GTK display (`bash .github/scripts/run-gtk-display-tests.sh -am`):

| pass | before | after |
| --- | --- | --- |
| no window manager | 39 classes, 269 / 0 / 0 / 4 | 40 classes, 274 / 0 / 0 / 4 |
| openbox | 39 classes, 242 / 0 / 0 / 27 | 40 classes, 247 / 0 / 0 / 27 |

Run / failures / errors / skipped. The only difference is the new class, 5 / 0 / 0 / 0 in both passes; every other
class is equal per class and per method. Under openbox the same 5 classes, the script's expected list, run no test.
No `hs_err_pid` in any run (module run, both passes and their child JVMs). Per class:
[US-109-linux-display-per-class.tsv](US-109-linux-display-per-class.tsv).

### Acceptance criteria
1. ✅ No upstream fix or bug: openjdk/jfx `master` `c6413874cc` still multiplies, no JBS issue is about it, and no
   open PR touches these lines.
2. ✅ Both functions take `ctx.length` as the byte count, with a comment against commit 033187ad90; no new compiler
   diagnostic, exports unchanged. The image half is checked by reading (see criterion 2).
3. ✅ `GtkDnDWideFormatReplyTest` fails on master's C (32 bytes, 4 of 4 runs) and passes with the fix.
4. ✅ The module run and both display passes have no new failure; the only difference is the new class.
