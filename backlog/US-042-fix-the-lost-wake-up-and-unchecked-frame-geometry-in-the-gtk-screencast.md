# US-042 — Fix the lost wake-up and the unchecked frame geometry in the GTK screencast frame path

**Status:** 📋 Ready (filed 2026-09-30 from the read-only survey evidence below; impact not measured; nothing built)
· **Epic:** none (correctness of Linux GTK Glass; a prerequisite of US-037) · **Blocked by:** — (the
regression tests need US-038)

## Story
As a JavaFX app developer on a Wayland desktop,
I want `Robot` screen capture never to miss the frame-ready signal and never to read past a PipeWire buffer,
so that a capture neither stalls nor reads memory the compositor did not hand over.

## Evidence
All paths are under `modules/javafx.graphics/src/main/native-glass/gtk/`.
- **Lost wake-up.**
  - `while (!isAllDataReady()) { lock; wait; unlock; if (hasPipewireFailed) … }` (`screencast_pipewire.c:889-897`)
    tests the predicate outside the thread-loop lock.
  - `onStreamProcess`, on PipeWire's thread, sets `captureDataReady` (`:384`) and then signals (`:389`).
  - A signal that fires between the test and the `wait` is lost. For that screen, `onStreamProcess` then returns early
    on every later call (`:280-286`), so it never signals again.
  - Both flags are read without the lock, which is a data race; `hasPipewireFailed` is a plain `static gboolean`
    (`:96`).
- **Unchecked frame geometry.**
  - `gdk_pixbuf_new_from_data(spaData.data, …, streamWidth, streamHeight, spaData.chunk->stride, …)` (`:328-336`)
    runs without checking that the chunk covers `stride × height`.
  - `chunk->offset` is logged (`:316`) but not applied to `data`.
- **Dead declaration.** `extern gboolean glib_version_2_68;` (`:725`) is neither defined nor used.

## Approach
- **Lost wake-up.** Test the predicate under the loop lock
  (`lock; while (!isAllDataReady() && !hasPipewireFailed) wait; unlock`), and keep the failure branch and its
  `doCleanup` as they are.
- **Frame geometry.** Treat a chunk whose offset plus `stride × height` exceeds `maxsize` like the existing `!!! no
  data` early return (`:301`), and apply `chunk->offset`.
- **Dead declaration.** Delete the dead extern.

## Acceptance criteria
- On the test bed, a capture scripted so that the signal fires before the wait completes; before the fix, it blocks.
- A short buffer is rejected, with the `!!! no data` debug line.
- The existing screencast tests are unchanged and green, and the debug golden is unchanged.

