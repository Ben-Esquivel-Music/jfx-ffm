# US-046 — Port the Pisces software compositor from C to Java and delete prism_sw

**Status:** 📋 Ready (drafted 2026-09-30 from a read-only survey; read `prism_sw_api.h`, `PiscesGoldenRenderTest`,
US-013; counts by `wc -l`; the gradient bound and the `pow` tables NOT tested; nothing built) · **Epic:** Less
native code in favour of pure Java (goal 1) · **Blocked by:** US-013

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
2. **Integer core**: integer steps exact on Windows and WSL.
3. **Transform inverse + texture paints** in the C's float order: steps 12-19 exact on Windows x64.
4. **Gradients** in the C's widths and order: exact on Windows x64, or a bound accepted first (`prism_sw_api.h:59`).
5. **LCD gamma tables**: equal to the C tables over the sweep, or a recorded, accepted bound (`:60`).
6. **Flip and delete**: frame-time A/B, flip, delete `native-prism-sw/`, `prismSW` targets, `PiscesNative`(+Test).

## Acceptance criteria
- `PiscesGoldenRenderTest` passes on the Java path with the golden bytes unchanged (or US-013's), on Windows and WSL;
  float-step variance off Windows x64 handled as its class comment says; the frame-time A/B is in the PR.
- No `psw_*` symbol, `prism_sw` library or `native-prism-sw/` file is left.

## Definition of Done
Merged PR, verified on Windows and WSL Linux; the C deleted; `backlog/README.md` updated.

## Risks
| # | Risk | Mitigation |
| --- | --- | --- |
| 1 | Java float order, widths or `pow` differ from the C | Copy types and order (no FMA contraction in Java); the float steps and slice 5 decide |
| 2 | Hot loops are slower in Java | Slice 6 A/B; a regression blocks the flip |

