# US-045 — Replace javafx_iio with a faithful pure-Java port of the IJG libjpeg 10 decoder (Rust fallback)

**Status:** 📋 Ready (drafted 2026-09-30 from a read-only survey; read `iio_api.h`, the `iio_api.c` entry points
and error path, libjpeg config/defaults, the test harness; NOT read: the source-manager and ICC bodies of
`iio_api.c`, most libjpeg bodies; no benchmark exists; nothing built. 2026-10-01, after PR review: FFM memory is
permitted for the off-heap buffers; read `JPEGImageLoader.java:244-357` and the `ImageStorage` callers of `dispose()`
to choose the arena kind) · **Epic:** Less native code (goal 1);
fallback under the Rust port (goal 3) · **Blocked by:** US-044 (it fixes the ported
subset); the Rust fallback only: US-027

## Story
As a JavaFX app developer who loads JPEGs from untrusted sources (WebView pages, remote URLs),
I want the JPEG decoder to be memory-safe Java that yields exactly the pixels, warnings, progress events and
exceptions of today's libjpeg build,
so that a malformed image can at worst throw, never corrupt memory, and `javafx_iio` disappears from every
platform.

## Why Java, not Rust or the C (R1–R6)
- **Must stay native (R1): no.**
  - libjpeg is PURE: it links only libc (`jmemnobs.c` malloc/free), and bytes arrive through callbacks.
  - The used path is integer-only (ISLOW `jidctint.c:192`, table colour conversion, replication upsampling).
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
- **Native code.** After the deletion story: 18 `.c` (15,578 lines) + 9 `.h` (3,324) of libjpeg, plus
  `iio_api.c` (870) and `iio_api.h` (270).
  - `jidctint.c` alone is 5,496 lines of 32 kernels.
  - `jmemmgr.c`/`jmemnobs.c` (1,236) become Java allocation, not ported code.
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
  - The module tests are listed in US-044. The gaps a parity gate must close, with each member captured on Windows
    **and** WSL:

  | Area | Corpus members needed |
  | --- | --- |
  | Sampling | 4:4:4, 4:2:2, 4:4:0, 4:1:1 and a factor-3 member (only 4:2:0 and gray exist) |
  | Stream structure | restart intervals (DRI/RSTn); every member at 1/1, 1/2, 1/4 and 1/8 |
  | ICC | a multi-chunk profile, plus one member per ICC error message (7) |
  | Colour | Adobe CMYK transform 0 and YCCK transform 2 (the JDK writer may refuse 4-band, `JpegCorpusGenerator.java:190-203`) |
  | Truncation and corruption | truncated baseline *and* truncated progressive (block smoothing); corrupt markers and Huffman data, with exact warning/error text and order |
  | Rejected inputs | arithmetic SOF (`JERR_ARITH_NOTIMPL`); 12-bit precision; dimensions at and beyond the limits (IOException or `OutOfMemoryError("Reading JPEG Stream")`, `iio_api.h:60-62`) |
  | Edge cases | a SmartScale/non-8 block size, if one can be produced; 16-bit quantizer tables with extreme coefficients (risk 3) |
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
   - Add the seeded mutation corpus (flips, truncations, marker splices) with its outcome goldens.
   - Capture on Windows **and** WSL, and record commit and platform. A Windows/Linux difference is a finding (see
     risk 3), never averaged.
   - The existing keys keep their values, except `images`, which lists the new members after the existing 9, and
     `capture.provenance`, which names the new capture. The generator rewrites the whole file in insertion order and
     needs `-Djfx.iio.jpeg.regenerate=true` to do so (`JpegCorpusGenerator.java:99-105,116-120`), so the PR reviews
     the diff key by key.
   - The maintainer approves the R2 trade-off and the slice-4 budget here.
2. **Headers.** Port the marker reader, input controller, API state machine and error/message catalogue
   (`jdmarker`, `jdinput`, `jdapimin`, `jerror`/`jerror.h`), plus the source-manager semantics and ICC
   reassembly from `iio_api.c`.
   - Gate: header outcomes (`IioImageInfo` fields, ICC bytes, messages) are exact against the C for the whole
     corpus.
3. **Baseline decode.**
   - Port Huffman (`jdhuff`), the single-pass coefficient controller, `jddctmgr` with the ISLOW kernels, the
     DCT-domain upsampling selection (`jdmaster.c:128-137`), `jdsample`, `jdcolor` (with the JFX CMYK/YCCK
     converters), and the main and post controllers.
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
6. **The rest of the input space.** All 32 `jidctint` kernels (SmartScale sizes) and every remaining error path.
   - Gate: the whole mutation corpus is exact.
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
    `events.*`); `images` lists the new members after the existing 9, and `capture.provenance` names the new capture;
  - after slice 1, `jpeg-goldens.txt` is byte-identical to the slice-1 capture;
  - the extended corpus and mutation goldens are exact on Windows and WSL;
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
| 5 | Corpus blind spots (SmartScale, YCCK) | Byte-surgery generator and fixtures; differential mutation test while the C exists |
| 6 | Owning a forked decoder: future IJG fixes need a manual re-port | Record the followed IJG version; review each IJG release's change log |
| 7 | Upstream OpenJFX libjpeg updates conflict after slice 8 | Documented resolution in the PR: drop them, and review for security content |

