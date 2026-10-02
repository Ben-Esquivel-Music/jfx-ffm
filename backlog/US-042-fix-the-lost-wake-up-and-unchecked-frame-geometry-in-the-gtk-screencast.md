# US-042 — Fix the lost wake-up and the unchecked frame geometry in the GTK screencast frame path

**Status:** 📋 Ready (filed 2026-09-30 from the read-only survey evidence below; the buffer re-queue and the read
after hand-back were added in the PR #21 review, also by reading the code; impact not measured; nothing built)
· **Epic:** none (correctness of Linux GTK Glass; a prerequisite of US-037) · **Blocked by:** US-038 (the test bed
its acceptance criteria run on)

## Story
As a JavaFX app developer on a Wayland desktop,
I want `Robot` screen capture never to miss the frame-ready signal, never to read past a PipeWire buffer, and never to
read one after giving it back or with the wrong row stride,
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
    wraps the PipeWire memory without copying it. The pixbuf reads `stride × (height − 1) + width × 4` bytes: four
    8-bit channels (`:330-331`), and a last row only as wide as its pixels.
  - The only data check is `!spaBuffer || n_datas < 1 || datas[0].data == NULL` (`:298-300`). `chunk->size`,
    `chunk->offset`, `chunk->stride` and `chunk->flags` are logged (`:315-318`) and never tested, and `maxsize` is
    never read.
  - The vendored SPA header defines the bounds (`libpipewire/include/spa/buffer/buffer.h`). `chunk->size` is the length
    of valid data and "should be clamped to maxsize" (`buffer.h:56-57`). `maxsize` is the size of the memory
    (`buffer.h:88`). `chunk->offset` "should be taken modulo the data maxsize" (`buffer.h:53-55`). `chunk->stride` is an
    `int32_t` (`buffer.h:58`). `SPA_CHUNK_FLAG_CORRUPTED` marks corrupted data (`buffer.h:60`).
  - `chunk->offset` is not applied to `data`.
  - Width and height come from the negotiated format (`:308-309`, parsed at `:251`), not from the chunk. The stream
    offers only `SPA_VIDEO_FORMAT_BGRx` from 1×1 to 8192×8192 (`:437-444`); the parse re-checks neither.
  - The `!!! no data` return (`:301-303`) keeps the dequeued buffer. Only `:387` calls `fp_pw_stream_queue_buffer`.
- **Read after hand-back.** Without scaling or cropping, the stored pixbuf is the zero-copy wrapper (`:381`). The
  buffer goes back to PipeWire at `:387`, and `sc_get_rgb_pixels` reads it later (`:985-1006`) while the stream is
  still active (`:1015-1017`). That reader steps rows by `captureArea.width` (`:1000-1003`), not by the rowstride,
  which for the wrapper is `chunk->stride` (`:334`). The `!spaBuffer` arm dereferences `spaBuffer->n_datas` in its own
  debug line (`:301-302`).
- **Dead declaration.** `extern gboolean glib_version_2_68;` (`:725`) is neither defined nor used.

## Approach
- **Lost wake-up.** Test the predicate under the loop lock
  (`lock; while (!isAllDataReady() && !hasPipewireFailed) wait; unlock`), and keep the failure branch and its
  `doCleanup` as they are.
- **Frame geometry.** Check the chunk before `gdk_pixbuf_new_from_data`, in 64-bit unsigned arithmetic, with `w`
  and `h` the negotiated size (`:308-309`) and 4 bytes per pixel. A frame passes when all of these hold:
  - `chunk` is not NULL, `maxsize > 0`, and `chunk->flags` does not have `SPA_CHUNK_FLAG_CORRUPTED`;
  - `w` and `h` are in 1..8192, the range the stream offers (`:440-444`);
  - `stride > 0`, tested on the signed value, and `stride >= w × 4`;
  - `need = stride × (h − 1) + w × 4` is at most `MIN(chunk->size, maxsize)`;
  - `off = chunk->offset % maxsize`, and `off + need <= maxsize`. A frame that would wrap past the end of the
    memory is rejected.

  From 32-bit inputs, `need` cannot overflow 64 bits. A frame that passes is wrapped at `data + off`. A frame that
  fails prints a `!!! no data` line with the size, offset, stride, flags, `maxsize` and `w × h`, gives its buffer
  back with `fp_pw_stream_queue_buffer`, and returns without setting `captureDataReady` or signalling. The existing
  `!!! no data` return (`:301-303`) gives its buffer back too. A kept buffer is never recycled: once none is left,
  every call ends at `!!! out of buffers` (`:293`), and the wait (`:889-897`) has no timeout.
  `SPA_CHUNK_FLAG_EMPTY` keeps its current treatment.
- **Read after hand-back.** Copy the frame into a new pixbuf before `fp_pw_stream_queue_buffer`, as the crop path does
  (`:359-371`); step rows in `sc_get_rgb_pixels` by `gdk_pixbuf_get_rowstride`; print `n_datas` only when `spaBuffer` is
  not NULL.
- **Dead declaration.** Delete the dead extern.

## Acceptance criteria
- On the test bed, a capture scripted so that the signal fires before the wait completes; before the fix, it blocks.
- On the test bed, each of these frames is rejected with the `!!! no data` debug line and gives its buffer back, and
  the next valid frame completes the capture:
  - a `chunk->size` one byte short of `stride × (h − 1) + w × 4`, inside a larger `maxsize`;
  - `chunk->size` 0, and a chunk flagged `SPA_CHUNK_FLAG_CORRUPTED`;
  - a `chunk->offset` above `maxsize` whose remainder leaves less than the frame before the end of the memory;
  - `stride` `0x40000000` with `w` 1 and `h` 5, where `need` wraps to 4 in 32-bit arithmetic;
  - a stride of `w × 4 − 4`, a stride of 0, and a negative stride.
- On the test bed, a frame with `chunk->offset` equal to `maxsize + 64` is read from `data + 64`.
- On the test bed, an uncropped, unscaled frame with a stride above `w × 4` is captured without shear, and the stub
  overwriting the buffer after it is queued back does not change the captured pixels.
- The existing screencast tests are unchanged and green, and `gtk-screencast-debug-golden.txt` is unchanged. A
  US-038 golden moves only in a scenario this story's fixes target, in its own commit that lists the moved scenarios.

## Definition of Done
Merged and verified in WSL Ubuntu: the regression tests run on US-038's test bed under the rootless-Xvfb recipe, and
the existing screencast tests pass. CI does not run them until US-016. `backlog/README.md` is updated. The PR checks
whether upstream openjdk/jfx shares each defect, and drafts an upstream issue where it does.

