# US-046 — Port the Pisces software compositor from C to Java and delete prism_sw

**Status:** ✅ Done (2026-10-18, PR pending) · **Epic:** Less native code in favour of pure Java (goal 1) ·
**Blocked by:** US-013

## Story
As a platform maintainer, I want the software pipeline's compositor in Java, reproducing the C byte for byte on
the golden corpus, so that `prism_sw` and its 22-export ABI are deleted on every platform.

## Why Java, not Rust
Plan of record (`prism_sw_api.h:54-62`): pure computation, no OS call, "a deletion candidate - a Java port", ABI
frozen. PURE/PURE-HOT code is as provable in Java as in Rust (R1), and no hot-path cost is measured (slice 6).

## Current state
- **Code**: 16 files / 5,240 lines (ABI pair 1,240; `PiscesPaint.c` 1,156, `PiscesBlit.c` 1,033), 22 exports,
  `PSW_ABI_VERSION` 1u; used by `PiscesRenderer`, `JavaSurface`, `Transform6` via `PiscesNative`, one render thread.
- **Globals**: the LCD gamma tables (rebuilt on a new `gamma`, `:264-267`) and the OOM flag (`:50-52`); status
  codes keep the JNI-era exception messages (`:87-89`).
- **Harness**: `PiscesGoldenRenderTest` runs a 64x64 script through the public `PiscesRenderer`/`JavaSurface` API
  against `pisces-golden-a544256444.bin`, captured from the JNI build on Windows x64 (`:64-66`); on Windows x64
  every step must match, float steps included (`:84-88`).

| Component | Parity (`prism_sw_api.h:57-60`) | Golden steps (`FLOAT_STEPS`, `:120`) |
| --- | --- | --- |
| Integer core: blits, masks, fill/clear, texture sampling, get/set RGB | exact | all integer steps |
| Texture inverse `pisces_transform_invert` (test comment `:77-82`) | float, inside "texture sampling" | 12-19 |
| Gradient set-up and generators | tolerance, bound not yet tested | 6-11, 24 |
| LCD gamma tables from `pow()` | unknown | 22 (gamma 1.4 only) |

## Interplay with US-013
US-013 fixes `genTexturePaintTarget` (`PiscesPaint.c`) and rules that a Java port reproduces the fix, not the bug
(US-013:194): this port lands **after** it, carrying and fixing nothing itself. If the fix moves steps 12-19 (NOT
checked), US-013 commits the new golden with its reason (`PiscesGoldenRenderTest.java:68-71`); that is the baseline.

## Approach and slices
The Java compositor sits behind the unchanged `PiscesRenderer`/`JavaSurface` API, selected by an internal switch;
the C stays the default and the reference until slice 6, and each slice makes its golden steps pass on Java.
1. **Corpus** from the C at the post-US-013 commit: all composite/image modes, clip edges, 1-px spans, gamma sweep.
   It goes into a new golden file named after that commit; `pisces-golden-a544256444.bin` (or US-013's) is not edited.
2. **Integer core**: integer steps exact on Windows and WSL.
3. **Transform inverse + texture paints** in the C's float order: steps 12-19 exact on Windows x64.
4. **Gradients** in the C's widths and order: exact on Windows x64, or a bound accepted first (`prism_sw_api.h:59`).
5. **LCD gamma tables**: equal to the C tables over the sweep, or a recorded, accepted bound (`:60`).
6. **Flip and delete**: frame-time A/B, flip, delete `native-prism-sw/`, `prismSW` targets, `PiscesNative`(+Test).

## Acceptance criteria
- `PiscesGoldenRenderTest` passes on the Java path with the golden bytes unchanged (or US-013's), on Windows and WSL;
  float-step variance off Windows x64 handled as its class comment says; the frame-time A/B is in the PR.
- The slice-1 corpus matches on the Java path as slices 2-5 require: exact, or within a bound accepted before the port.
- No `psw_*` symbol, `prism_sw` library or `native-prism-sw/` file is left.

## Definition of Done
Merged PR, verified on Windows and WSL Linux; the C deleted; `backlog/README.md` updated.

## Risks
| # | Risk | Mitigation |
| --- | --- | --- |
| 1 | Java float order, widths or `pow` differ from the C | Copy types and order (no FMA contraction in Java); the float steps and slice 5 decide |
| 2 | Hot loops are slower in Java | Slice 6 A/B; a regression blocks the flip |

## Resolution (2026-10-18)
- **What landed.** The compositor is Java: package-private `PiscesSurfaceState`, `PiscesRendererState`, `PiscesBlit`,
  `PiscesPaint`, `PiscesTransform` and `PiscesStatus` in `com.sun.pisces`, one method per C function, on heap
  `int[]`/`byte[]` (no FFM, no `Unsafe`, no preview or incubator API). The public `PiscesRenderer`, `JavaSurface`,
  `AbstractSurface` and `Transform6` API is unchanged, and `SWPipeline` loads no library.
  - It is a faithful port of the C of 42a5072596: every quirk stays, US-062's two edge cases included.
  - Until the flip, a system property chose the C or the Java compositor, and a differential test drove both in one
    JVM: 4,000 seeds of 100 operations against the MSVC C on Windows and the gcc C in WSL, plus a sweep of 24,906
    gammas over the LCD tables. The switch, the differential test and its shim went with the C.
  - Deleted: `native-prism-sw/` (16 files, 5,342 lines), the `prismSW` targets of `native/{win,linux,mac}.cmake`,
    `PiscesNative`, `PiscesNativeShim`, `PiscesNativeTest` (20 tests) and `PiscesNatives`. A from-scratch native
    build on Windows and Linux produces no `prism_sw` library, and no remaining library exports or imports a `psw_*`
    symbol (`dumpbin`, `nm`, `readelf`).
- **Parity tests.**
  - New `test.com.sun.pisces.PiscesCorpusGoldenTest` with `pisces-corpus-42a5072596.bin` (775,099 bytes, 3,817 steps,
    SHA-256 `59c86968aae87c6925860842680514b46b3c312ccf2dbe14d52b3ab4cab6724a`), captured through the public API from
    the C of 42a5072596 on Windows x64. 3,662 steps end normally and 155 in an exception (147
    `IllegalArgumentException`, 4 `OutOfMemoryError`, 2 `NullPointerException`, 2 `ArithmeticException`).
    - Its first 3,778 steps cover 96.36 % of the C's lines and 84.50 % of its branches (gcov in WSL), and a capture
      from the Linux gcc build equals the Windows one. The last 39 steps pin the shortcuts of the optimised Java hot
      loops and the in-range twins of Issue D; appending them left every earlier step byte-identical.
    - It covers in-texture `drawImage` and `setTexture` sampling under rotated, sheared and mirrored transforms,
      which the golden lacks (US-061).
  - The golden `pisces-golden-US-059-pending.bin` is renamed `pisces-golden-42a5072596.bin`, after the commit that
    landed US-059 (PR #29). Its bytes are unchanged (SHA-256
    `69572218d1e84249425919812360b3260c50ce31338bd53e38a20f2329385a29`), and a capture from that C reproduces them.
  - Java-only tests pin outcomes that no corpus step can hold: `PiscesNullArgumentTest` (4),
    `PiscesDeltaRangeAtArrayEndTest` (7), `PiscesNanLcdGammaTest` (3) and `PiscesMultiplyRowGuardTest` (1). The last
    one reaches the row-size guard of the multiply paint and its standard-error line through the new shim
    `PiscesPaintShim`, without the 4.6 GiB heap the corpus needs for that branch, so it runs on CI.
- **Exactness.** No tolerance anywhere: the gradients and the LCD gamma tables are exact too (`StrictMath.pow` with
  C99's `pow(1, y) = 1`, and the x86-64 float-to-int conversion reproduced; the C has no FMA). The corpus and the
  golden are exact and strict, float steps included, on Windows x64 and WSL Linux x64; the golden's `FLOAT_STEPS`
  policy is gone. The compositor uses no platform-dependent math (`Math.sqrt` and `StrictMath.pow` only), so aarch64
  and macOS are verified by CI only. 66 `Node.snapshot` scenes through the real SW pipeline were byte-identical to
  the C, and `SWTextureEdgeSnapshotTest` passes on both.
- **Frame time.** The A/B (Windows x64, SW pipeline, six scenes, criterion fixed before measuring) failed in five
  scenes at first; after one round of pixel-neutral hot-loop changes the java/native medians are S1 1.024, S2 1.008,
  S3 1.038, S4 0.992, S5 0.827 and S6 0.838, all under the 1.05 limit. The full report is in the PR.
- **Behaviour changes.**
  - Where the C crashed or went outside an array, the Java compositor throws, or in one overflow case draws nothing:
    crash classes A to F, Issue D, G and H. US-062 lists them, with the renderer state each leaves behind.
  - The renderer buffers, including the w x h `int` copy that `setTexture` makes, now live on the Java heap. An app
    near its heap limit needs w x h x 4 bytes of headroom per textured paint, or it gets
    `OutOfMemoryError("Allocation of internal renderer buffer failed.")` where the C's `malloc` rendered (measured: a
    320x240 fill with a 2048x2048 `ImagePattern` at `-Xmx64m`). The one-row paint buffers fail with the JVM's own
    `OutOfMemoryError`. Accepted: heap arrays are the port's design, and dropping the copy would change an error
    outcome.
  - `new PiscesRenderer(null)` still throws `NullPointerException`, but the JVM's message names
    `AbstractSurface.state()` where it named `nativeSurface()`. Accepted: the text is generated by the JVM, no caller
    passes null, and no test pinned it.
- **Deviations from this story's text.**
  - The C had 16 files and 5,342 lines, not 5,240: US-013 and US-059 added 102 lines after the survey.
    `PSW_ABI_VERSION` was 2 (since US-013), not 1.
  - Slice 6 deleted more than it names: `PiscesNativeShim`, `PiscesNatives`, the Disposer records of
    `AbstractSurface` and `PiscesRenderer` (the Java compositor holds nothing to dispose) and the status code that no
    call returned.
  - US-061 was not done first, as the README planned: it stays a separate test-only story. The corpus covers the
    sampling it is about, so the parity proof does not depend on it.

