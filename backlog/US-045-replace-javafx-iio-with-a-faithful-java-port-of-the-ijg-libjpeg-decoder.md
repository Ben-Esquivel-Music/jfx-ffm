# US-045 — Replace javafx_iio with a faithful pure-Java port of the IJG libjpeg 10 decoder (Rust fallback)

**Status:** 📋 Ready (drafted 2026-09-30 from a read-only survey; read `iio_api.h`, the `iio_api.c` entry points
and error path, libjpeg config/defaults, the test harness; NOT read: the source-manager and ICC bodies of
`iio_api.c`, most libjpeg bodies; no benchmark exists; nothing built. 2026-10-01, after PR review: FFM memory is
permitted for the off-heap buffers; read `JPEGImageLoader.java:244-357` and the `ImageStorage` callers of `dispose()`
to choose the arena kind. The same day, with US-044's correction: the upsampling paths, the kept-file counts and the
SmartScale corpus requirement were corrected from `jdmaster.c`, `jdmerge.c`, `jdsample.c` and the SOF and SOS
headers of the 9 corpus members. An independent review the same day led to a second round: the merged-path members
moved to US-054, the SmartScale row was completed so that all 32 kernels have a member, and block sizes 9 to 16 are
built by header surgery, not from fixtures) · **Epic:** Less native code (goal 1);
fallback under the Rust port (goal 3) · **Blocked by:** US-044 (it fixes the ported
subset); US-054 part 1 (slice 1 builds on its members); the Rust fallback only: US-027

## Story
As a JavaFX app developer who loads JPEGs from untrusted sources (WebView pages, remote URLs),
I want the JPEG decoder to be memory-safe Java that yields exactly the pixels, warnings, progress events and
exceptions of today's libjpeg build,
so that a malformed image can at worst throw, never corrupt memory, and `javafx_iio` disappears from every
platform.

## Why Java, not Rust or the C (R1–R6)
- **Must stay native (R1): no.**
  - libjpeg is PURE: it links only libc (`jmemnobs.c` malloc/free), and bytes arrive through callbacks.
  - The used path is integer-only:
    - the ISLOW kernels (`jidctint.c:192`);
    - chroma upsampled by a larger IDCT where the sampling ratio allows it (`jdmaster.c:123-142`), by replication
      otherwise (`jdsample.c:180-270`);
    - table colour conversion (`jdcolor.c`);
    - the merged upsampler and converter for block sizes 9 to 16 (`jdmerge.c`; see US-044, Current state).

    A faithful port is therefore provable by goldens plus a live differential test against the C.
  - The audit's "PARITY: unprovable" applies to a *different* decoder, not a faithful port.
- **Hot path.** Per-pixel loops run on the FX thread for synchronous loads (`Image.java:878-896`). No measurement
  exists, and IJG 10 is scalar C (no SIMD). Slice 4 measures it and is the decision point.
- **Owned (R2): no.** This is vendored IJG code (`UPDATING.txt:1-45`), maintained upstream (10, 2026-01-25),
  and OpenJFX re-imports it about every two years (26 files changed in the 10 import).
  - Porting forks it, in Java exactly as in Rust. It is accepted here because:
    - the port is memory-safe on untrusted input (`WCImageDecoderImpl.java:181`);
    - the fork's behaviour contract is its goldens;
    - the maintainer signs this off in slice 1.
- **Buildable and testable (R3).** It is plain Java, tested on Windows and WSL. macOS runs the same bytecode
  (unverified, no host).
- **Benefit (R4).**
  - Memory safety on web and URL image bytes.
  - `setjmp`/`longjmp` removed (`iio_api.c:40-50`, `:165-170`).
  - The upcall sentinel/pending-exception protocol removed (`iio_api.h:79-86`).
  - One native library removed on all three platforms (`native/{win,linux,mac}.cmake`).
- **Binding (R5).** None needed, only the JDK.
- **Why not Rust.** Equal provability. No evidenced hot-path cost. Rust would keep a native library, the FFM
  facade and a toolchain dependency. It stays the fallback if slice 4 fails.

## Current state
- **Native code.** After the deletion story: 19 `.c` (16,015 lines) + 9 `.h` (3,324) of libjpeg, plus
  `iio_api.c` (870) and `iio_api.h` (270).
  - `jidctint.c` alone is 5,496 lines of 32 kernels.
  - `jmemmgr.c`/`jmemnobs.c` (1,236) become Java allocation, not ported code.
  - `jdmerge.c` (437) is one of the 19. US-044 keeps it, because a JPEG with a block size of 9 to 16 sampled 2h1v
    or 2h2v reaches it at 1/1. US-054 part 2 may remove it from the C before slice 6; until that passes its gate,
    it is ported.
- **ABI and Java.** 9 exports, `IIO_ABI_VERSION 2` (`iio_api.h:134`, `:221-264`). Callbacks: `read`/`skip`/
  `emit_warning`/`update_progress` (`:157-182`). The Java facade is `JPEGNative.java` (725 lines), called from
  `JPEGImageLoader.java:286`.
- **Threads.** Calls run on the caller's thread, with no locking (`iio_api.h:105-106`).
- **Observable contract** (all of it is behaviour):
  - IOException texts from `jerror.h` via `format_message`;
  - 7 ICC messages and 3 OutOfMemoryError messages (`iio_api.h:46-62`);
  - the warning, progress and EOF sequences (`iio_api.h:87-103`), including "first libjpeg warning only";
  - the Adobe/CMYK/YCCK colour-space fixups (`iio_api.c:580-647`);
  - the scale choice (`iio_api.c:732-746`, a float compare).
- **Tests.**
  - `jpeg-goldens.txt` holds 113 keys, captured by `JpegCorpusGenerator` (opt-in `-Djfx.iio.jpeg.capture=true`)
    from the JNI build on Windows 10 amd64: `capture.provenance`, `images` and 111 member keys. Per member it
    records the file's length and SHA-256, the decoded type, geometry and SHA-256, or the exception class and
    message, plus the ordered listener events.
  - The members cover baseline 4:2:0, gray, progressive, odd sizes, ICC (valid and invalid), Adobe unknown, CMYK,
    corrupt input and a truncated stream, at 1/1. The baseline member is also decoded at 1/2 and to 40x30, smooth
    and rough (`JpegTestSupport.java:151-156`).
  - Read from the SOF and SOS headers of the 9 members: 8 are SOF0, and the one SOF2 member's first SOS names 3
    components, so every block size is 8 (`jdinput.c:243-247`). The 7 three-component members are all 2h2v; gray
    and CMYK are 1h1v. There is no 2h1v member, and none reaches the merged upsampler: at block size 8 the 2h2v
    chroma is upsampled by the IDCT.
  - The module tests are listed in US-044. The gaps a parity gate must close, with each member captured on Windows
    **and** WSL:

  | Area | Corpus members needed |
  | --- | --- |
  | Sampling | 4:4:4, 4:2:2, 4:4:0, 4:1:1 and a factor-3 member (only 4:2:0 and gray exist) |
  | Stream structure | restart intervals (DRI/RSTn); every member at 1/1, 1/2, 1/4 and 1/8 |
  | ICC | a multi-chunk profile, plus one member per ICC error message (7) |
  | Colour | Adobe CMYK transform 0 and YCCK transform 2 (the JDK writer may refuse 4-band, `JpegCorpusGenerator.java:190-203`) |
  | Truncation and corruption | truncated baseline *and* truncated progressive (block smoothing); corrupt markers and Huffman data, with exact warning/error text and order. The SOF1 file cut after the `Ss` byte of its SOS, which makes the block size 16, is a member of US-054 part 1 |
  | Rejected inputs | arithmetic SOF (`JERR_ARITH_NOTIMPL`); 12-bit precision; dimensions at and beyond the limits (IOException or `OutOfMemoryError("Reading JPEG Stream")`, `iio_api.h:60-62`) |
  | Block size (SmartScale) | required, not optional: `jdmerge.c` and the kernels of every size other than 1, 2, 4, 8 and 16 run only for these. The 2h1v and 2h2v members at block sizes 9 to 16 are those of US-054 part 1 and are not specified again here. Added here: 1h2v at block sizes 10, 12 and 14 (the only members that select the 5x10, 6x12, 7x14 and 3x6 kernels, at 1/2 and 1/4); one 1h1v member at a block size of 9 or more; a block size below 8. Every SmartScale member is decoded at 1/1, 1/2, 1/4 and 1/8 (3x3 and 6x3 need 1/4) |
  | Edge cases | 16-bit quantizer tables with extreme coefficients (risk 3) |
  | Mutation corpus | a deterministic, seeded mutation corpus over all members |

## Approach
- **Package.** Add a new package `com.sun.javafx.iio.jpeg.ijg` (name proposed), a line-faithful port of the used
  IJG modules. It keeps libjpeg's module structure, so a reviewer can diff each Java file against its `.c` file.
- **Same arithmetic.**
  - `int` where IJG's `INT32` stays in range.
  - The same tables, the same `RANGE_MASK`/`range_limit` clamping, and the same message catalogue (`jerror.h`
    texts, first-warning-only).
- **Memory.** Large intermediate buffers stay **off-heap**, so that progressive decodes do not move to the Java heap.
  Their whole-image coefficient arrays are malloc'd today. They live in a confined `Arena` that `load` opens for one
  decode and closes in its `finally`, on the thread that ran the decode (`JPEGImageLoader.java:268-312`). It is not
  one arena per decoder closed on dispose: `dispose()` is public, synchronized and skips while a `load` holds
  `accessLock` (`:247-257`), so it is written for a second thread, where closing a confined arena throws
  `WrongThreadException`. The production callers dispose on the loading thread (`ImageStorage.java:339-343`,
  `:487-490`).
- **Errors.** Exceptions replace `error_exit`/`longjmp`. The public behaviour of `JPEGImageLoader` stays the same
  (IOException/OutOfMemoryError messages, listener events).
- **Staying dead until the switch.** The Java decoder is not selected until slice 7, so slices 2-6 are
  behaviour-neutral. Each is gated by a differential test that runs the C (through `JPEGNative`) and the Java
  decoder on the same bytes and compares:
  - outcome, exception class and message;
  - geometry and pixel SHA-256;
  - the listener event trace.

### Slices
1. **Corpus and goldens from the C.**
   - Extend `JpegCorpusGenerator` and the goldens with every gap in the table above. Members the JDK writer
     cannot produce (YCCK, 4:1:1, SmartScale) are built by byte-level surgery in the generator, or committed as
     small fixtures with written provenance.
   - SmartScale members:
     - Block sizes 9 to 16 are built by header surgery in the generator, as US-054 part 1 describes: from 8 up the
       entropy data keeps the 64-coefficient layout (`jdinput.c:285-329`), so only the headers change. The 1h2v
       members need a 4:4:0 source; that the JDK writer can produce one was not verified.
     - Only a block size below 8 needs an encoder or a committed fixture: `lim_Se` is then below 63
       (`jdinput.c:250-284`), which changes the entropy layout, and after US-044 the tree has no encoder. IJG's
       encoder writes such files when `block_size` is set (US-044's scratch probe used the `jc*.c` files US-044
       deletes); the switches of the stock `cjpeg` were not checked.
     - A test predicts the IDCT kernel of each component of each member at each scale from its SOF and SOS headers
       (`jdinput.c:57-190`, `jdmaster.c:123-148`). The prediction is confirmed once by an instrumented scratch
       run recorded in the provenance, as in US-054 part 1.
   - Add the seeded mutation corpus (flips, truncations, marker splices) with its outcome goldens.
   - Capture on Windows **and** WSL, and record commit and platform. A Windows/Linux difference is a finding (see
     risk 3), never averaged.
   - The existing keys keep their values, except `images`, which lists the new members after the existing ones
     (today's 9 and those of US-054 part 1), and `capture.provenance`, which names the new capture. The generator
     rewrites the whole file in insertion order and needs `-Djfx.iio.jpeg.regenerate=true` to do so
     (`JpegCorpusGenerator.java:99-105,116-120`), so the PR reviews the diff key by key.
   - The maintainer approves the R2 trade-off and the slice-4 budget here.
2. **Headers.** Port the marker reader, input controller, API state machine and error/message catalogue
   (`jdmarker`, `jdinput`, `jdapimin`, `jerror`/`jerror.h`), plus the source-manager semantics and ICC
   reassembly from `iio_api.c`.
   - Gate: header outcomes (`IioImageInfo` fields, ICC bytes, messages) are exact against the C for the whole
     corpus.
3. **Baseline decode.**
   - Port Huffman (`jdhuff`), the single-pass coefficient controller, `jddctmgr` with the ISLOW kernels, the
     DCT-domain upsampling selection (`jdmaster.c:123-142`), `jdsample`, `jdcolor` (with the JFX CMYK/YCCK
     converters), and the main and post controllers.
   - The kernels this slice needs are the 8x8 one and those the selection picks at block size 8: 16x16 for 2h2v
     chroma at 1/1, 16x8 and 8x16 for 2h1v and 1h2v, and their halves down to 1x1 at 1/2 to 1/8.
   - The merged upsampler is not in this slice: no block-size-8 member reaches it (`jdmaster.c:78-84`).
   - Gate: every baseline member at 1/1 to 1/8 is exact.
4. **Benchmark gate — the decision point.**
   - Add an in-tree decode benchmark (a flag-gated JUnit harness) that runs large baseline images (4:2:0, 4:4:4,
     gray) for C vs Java on Windows and WSL.
   - Measure both cold (the first decode in a fresh JVM) and warm.
   - The budget is agreed in slice 1, before any numbers exist. Proposal: warm ≤ 1.5× C, cold ≤ 3× C.
   - **If the budget is missed, stop.** Slices 5-8 are replaced by the Rust fallback below, and the corpus from
     slice 1 carries over.
5. **Progressive.** Port the progressive Huffman paths, the multi-scan coefficient buffering (off-heap) and
   `decompress_smooth_data`.
   - Gate: the progressive and truncated-progressive members are exact.
6. **The rest of the input space.** All 32 `jidctint` kernels (SmartScale sizes), the merged upsampler
   (`jdmerge`: block sizes 9 to 16 sampled 2h1v or 2h2v, at 1/1) and every remaining error path.
   - `jdmerge` is ported unless US-054 part 2 has passed its gate and removed it from the C first. The port never
     drops it on its own: the Java decoder follows whatever the C does at that point.
   - Gate: every SmartScale member, those of US-054 part 1 included, and the whole mutation corpus are exact. A
     test asserts that each of the 32 kernel sizes is selected by at least one member (the prediction of slice 1).
7. **Switch.** `JPEGImageLoader` uses the Java decoder, with no user-visible property.
   - Gate: all module tests, `tests/system` JPEG tests, and goldens exact on Windows and WSL.
8. **Delete the native library.**
   - Delete `native-iio/` and `JPEGNative.java`.
   - Delete the `iio` targets in `native/{win,linux,mac}.cmake` (`:262`, `:241`, `:217`) and any packaging
     reference to `javafx_iio` (`git grep`).
   - Delete the native-only tests (`JPEGNativeTest`, `JpegNatives`, and the ABI parts of
     `JpegStreamCallbackTest`). The goldens become the only oracle.
   - **Keep** `legal/jpeg_fx.md`, reworded as "a Java port of IJG libjpeg 10". The IJG licence still applies to a
     derivative.

## Acceptance criteria
- Goldens:
  - slice 1 keeps the value of each of the 111 member keys of today's `jpeg-goldens.txt` (`image.*`, `decode.*`,
    `events.*`) and of the keys US-054 part 1 added; `images` lists the new members after the existing ones, and
    `capture.provenance` names the new capture;
  - after slice 1, `jpeg-goldens.txt` is byte-identical to the slice-1 capture;
  - the extended corpus and mutation goldens are exact on Windows and WSL;
  - the members of US-054 part 1, whose shapes reach the merged upsampler while it is compiled in, are exact
    against whichever path the C has at that point (merged, or separate after US-054 part 2);
  - each of the 32 `jidctint` kernel sizes is selected by at least one member;
  - event traces are identical.
- The slice-4 budget is met on both platforms, recorded in the PR with its hardware.
- No `native` method, no downcall or upcall (`Linker`, `SymbolLookup`), no other restricted method and no load of
  the `javafx_iio` library (named at `JPEGNative.java:173` today) remains in `com.sun.javafx.iio`. The only
  `java.lang.foreign` use left is the decoder's `Arena`/`MemorySegment` buffers. Allocating and accessing them is
  not restricted, so the decoder needs no native code and no native access.
- The listed native files and CMake targets are deleted, and the legal notice is kept.
- Peak Java heap for the largest progressive corpus member does not exceed today's by more than the output buffer.

## Definition of Done
Merged PRs (one per slice); verified on Windows and WSL; macOS unverified, but it no longer has native JPEG code;
`backlog/README.md` Rust-port table row set to JAVA with this story linked.

## Fallback: faithful Rust port behind `iio_api.h` (only if slice 4 fails)
- A crate in the location the toolchain story fixes, built as a `staticlib` linked into `add_jfx_library(iio …)`.
  It exports the 9 `iio_*` symbols unchanged, uses no dependencies, and guards every export with `catch_unwind`.
- Slices, leaf kernels first:
  1. IDCT, colour and upsampling. `error_exit` is never raised while they are on the stack.
  2. Huffman and the coefficient controllers.
  3. Last and **as one slice**: the marker reader, master control, source manager and `iio_api.c`. Here
     `Result` replaces `setjmp`/`longjmp` (`iio_api.c:134-139`, `:165-170`), because a C `ERREXIT` must never
     longjmp over Rust frames.
- The same corpus and goldens from slice 1 apply. The C is deleted per slice after acceptance.

## Risks
| # | Risk | Mitigation |
| --- | --- | --- |
| 1 | Java is too slow on the FX thread (cold JIT) | Slice 4 gates before progressive work; Rust fallback |
| 2 | Progressive buffers move to the heap, so `-Xmx` OOMs where C succeeded | Off-heap buffers in a confined per-decode `Arena`; huge-dimension members pin the outcome |
| 3 | `INT32` is `long` (`jmorecfg.h:252`): 32-bit on Windows, 64-bit on LP64 Linux, so ISLOW intermediates (`jidctint.c:196-198`) can differ by platform for adversarial coefficients | Capture on both; if they differ, the maintainer picks one behaviour, recorded in the test |
| 4 | OOM messages that only native allocation failure produces ("Initializing Reader", `iio_api.h:58`) | Decide per message in slice 1; never silently different |
| 5 | Corpus blind spots (SmartScale, YCCK). Today no member reaches `jdmerge.c` or a kernel of a size other than 1, 2, 4, 8 and 16 | The SmartScale members of US-054 part 1 and of slice 1 are required, not optional, and a test asserts that all 32 kernel sizes are selected; byte-surgery generator and fixtures for the rest; differential mutation test while the C exists |
| 6 | Owning a forked decoder: future IJG fixes need a manual re-port | Record the followed IJG version; review each IJG release's change log |
| 7 | Upstream OpenJFX libjpeg updates conflict after slice 8 | Documented resolution in the PR: drop them, and review for security content |

