# US-040 — Fix lock, allocation and leak defects in the jfxmedia GStreamer pipeline

**Status:** 📋 Ready (filed 2026-09-30 from the Rust-port survey of `jfxmedia`). The sites were read. Part 2 is
reasoned, not reproduced. Part 4 is documented in the source itself; on 2026-10-01 it was re-read against the code
after the comment, `InitGstMedia`'s owner slots and vendored GLib's floating-object check. Nothing was built or run.
· **Found:** 2026-09-30, Rust-port survey (media) · **Related:** US-035 and US-036 reproduce today's behaviour until
this lands; if it lands first, their goldens encode the fixed behaviour.

## Story
As a JavaFX app developer playing media,
I want the GStreamer playback core to share its stall and EOS state under one lock, to size the spectrum from the
message it receives, to never throw inside a GLib callback, and to release its source element on every failure path,
so that HLS stalls, spectrum band changes and failed media creation cannot race, lose bands, unwind through GLib or
leak.

## Problem
Paths are relative to `modules/javafx.media/src/main/native/jfxmedia/platform/gstreamer`.
1. **Mixed lock discipline.**
   - `m_bLastProgressValueEOS` is written by the bus handler without `m_StallLock`
     (`GstAudioPlaybackPipeline.cpp:1473`).
   - It is read and written under `m_StallLock` elsewhere (`:2049-2056`, `:2125-2131`).
   - It is read after `m_StateLock->Exit()` with no lock at all (`:1624-1631`).
   - `m_bHLSPBFull` follows the same pattern (`:1489`, `:1494`, `:2113-2116`, `:1628`).
   - A data race on a plain `gboolean` is undefined behaviour in C++.
2. **Spectrum lists indexed by the Java-set band count.** The bus handler indexes the magnitude and phase lists of
   the `spectrum` message by the band count Java last set (`:1512-1526`). If the count grows while a message is in
   flight, the handler reads past the message's lists: a GLib critical, and zero bands delivered.
3. **A throwing allocation inside a GLib callback.** `new float[]` runs inside the GLib bus handler (`:1516-1517`).
   A `std::bad_alloc` there would unwind through GLib frames. The header's list of `bad_alloc` escapes
   (`jfxmedia_api.h:34-39`) does not mention it.
4. **The source element leaks on three failure returns.** A comment in `CGstPipelineFactory::CreateSourceElement`
   (`GstPipelineFactory.cpp:284-291`) records that the three failure returns after it unref nothing:
   - `:297` (`gst_bin_new` failed) abandons `javaSource`;
   - `:305` (no buffer element) abandons `javaSource` and the empty bin made at `:295`;
   - `:310` (link failed) abandons the bin, which took `javaSource` and the buffer element at `:307`.

   What each return abandons is still floating, so nothing else will release it: it leaks. The comment also records
   why an unref is now safe: `InitGstMedia` registers an owner slot on each stream-callback adapter across
   `CMediaManager::CreatePlayer` (`modules/javafx.media/src/main/native/jfxmedia/ffi/jfxmedia_api.cpp:308-310`).
   An adapter that the unref frees, through the `close-connection` closure notify, NULLs the local that points at
   it (`:301-303`), so `InitGstMedia`'s cleanup does not free it a second time.

## Proposed fix
1. Choose the owning lock for each field, `m_StallLock` presumably, and take it on every access. Record the chosen
   lock next to each field.
2. Bound the loop by `gst_value_list_get_size` of the message's lists. Deliver the bands the message actually
   carries.
3. Use `new (std::nothrow)`, or a buffer sized once per band-count change, and update the header's note.
4. On each of the three returns, release what it abandons with `gst_object_ref_sink()` and then `gst_object_unref()`,
   because each is still floating: `javaSource` at `:297`; `javaSource` and the empty bin at `:305`; the bin at
   `:310`, which then releases the two elements it holds. A plain unref would finalize a floating object, which a
   debug GLib reports as a critical
   (`modules/javafx.media/src/main/native/gstreamer/3rd_party/glib/gobject/gobject.c:1814-1829`). Read
   `InitGstMedia`'s adapter cleanup first (`jfxmedia_api.cpp:332-374`), as the comment asks.

## Acceptance criteria
- **Part 1:** the PR lists every access of both fields with the lock held there. A ThreadSanitizer run of the media
  tests in WSL is attempted and its result recorded.
- **Part 2:** a test raises the band count while audio plays with the spectrum on. It receives full band arrays with
  no GLib critical on stderr.
- **Part 3:** no throwing `new` remains inside a GLib or GStreamer callback in `jfxmedia` (`git grep` in the PR).
- **Part 4:** a fault-injected failure of each of the three returns finalizes `javaSource`, and at `:305` and `:310`
  the bin, checked with `g_object_weak_ref`. Each release sinks the floating reference before it unrefs.
  `disposingAMediaClosesTheConnectionTheStreamCallbacksNeverClosed` still passes.
- The 33 module tests pass on Windows and WSL, and the export list and `JFXM_ABI_VERSION` are unchanged.

## Definition of Done
Merged and verified on Windows and WSL Ubuntu. `backlog/README.md` is updated. An upstream issue is drafted for
parts 1-3; part 4 was probably present before the fork's FFM port, and the PR checks.
