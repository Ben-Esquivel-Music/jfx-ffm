# US-109 — Stop GTK drop targets reading past a 16- or 32-bit-format reply

**Status:** 📋 Ready (filed 2026-11-10 from the PR #54 review) · **Priority:** Medium (a native heap over-read into
the app's data, possibly a JVM crash; pre-existing since commit 033187ad90) · **Estimate:** S ·
**Found:** 2026-11-10, the independent review of PR #54 (US-069's review round), by reading `glass_dnd.cpp` against
the GTK 3.24.52 source; not run

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
