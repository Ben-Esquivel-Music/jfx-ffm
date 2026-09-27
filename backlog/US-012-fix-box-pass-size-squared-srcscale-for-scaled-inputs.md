# US-012 — Scale the box pass size by the input scale once, not twice

**Status:** ✅ Done (2026-09-26, PR #17) · **Found:** 2026-09-26, review of the software BoxBlur transform fix (scaled `ImageInput` follow-up)

## Story
As a JavaFX app developer blurring or shadowing an `ImageInput` on a scaled node, under a snapshot scale or on a HiDPI
screen,
I want the box blur to extend as far as the effect's width and height say,
so that scaled content is blurred as much as unscaled content (no less when enlarged, no more when shrunk), and the
same on every pipeline.

## Problem
`BoxRenderState.validatePassInput` (`:362-364`) handles an input whose transform is not translate-only, which is what
an `ImageInput` under a scale produces. It maps the pass's unit sample vector back into the input's texel space, where
its length `srcScale` is the number of texels per filter pixel (1/s for a scale s). It then sets:

```java
float pSize = (float) (iSize * srcScale);
pSize *= srcScale;
```

`iSize` is in filter (device) pixels, so the box must be `iSize * srcScale` texels wide, which is `iSize` device
pixels. The second multiply makes it `iSize * srcScale²` texels, i.e. `iSize / s` device pixels. The extent is wrong
by a factor 1/s: half as long when the image is enlarged by s = 2, twice as long when it is shrunk to s = 0.5.
`GaussianRenderState.validatePassInput` (`:433-434`) does the same mapping and multiplies once
(`pRad = iRadius * srcScale`).

- **Fix:** delete `pSize *= srcScale;`. Check that the `maxPassSize` clamp and the `srcScale = maxPassSize / iSize`
  renormalisation below it still hold. By analogy with `GaussianRenderState` they should.
- **Upstream too.** `openjdk/jfx` master has the same lines, so this is not a fork regression.

### Who is affected
- An input that reaches `validatePassInput` with a non-translate but axis-aligned transform. In practice that is an
  `ImageInput` (and other inputs returned without resampling) under a node scale, a `SnapshotParameters` scale, a
  HiDPI render scale, or the CustomSpace input scale of a rotated and scaled node. A node's own content, through
  `NodeEffectInput`, is rendered in device space, so it is translate-only and not affected.
- `BoxBlur`, and `DropShadow`/`InnerShadow`/`Shadow` with the box blur types, on every pipeline:
  - **D3D/ES2:** pass 0 (horizontal) has the wrong extent. Pass 1 is right, because the `LinearConvolve` pass 0
    returns a device-space result with an identity transform. The exception is a box whose pass 0 is a no-op:
    `BoxBlur(0, h, n)`, or a pass 0 of one texel or less as sized before the fix (`iSize / s²` texels, e.g.
    `BoxBlur(3, h)` at s = 2). `getPassPeer` returns no peer for it, so `LinearConvolveCoreEffect.filterImageDatas`
    hands the scaled input itself to pass 1, which then has the wrong extent too (found by the hardware check, sigma
    ratio 0.51 at s = 2 and 1.68 at s = 0.5).
  - **SW:** both passes have the wrong extent when pass 0 runs a box peer (spread 0, one-texel step). Pass 1 then
    also sees the scaled result, because `JSWBoxBlurPeer` and `JSWBoxShadowPeer` carry the input transform on to it. A
    shadow with spread > 0, or a pass 0 that the squared size clamped (e.g. `BoxBlur(30, 30, 3)` at s = 0.5), runs a
    `LinearConvolve` peer, which returns device space, so there SW pass 1 is right, as on the GPU.
  - So SW and GPU disagree on the vertical extent of a scaled `ImageInput` blur. Compared with the same blur of a
    pre-scaled image, GPU is anisotropic and SW is uniformly off by 1/s.

### Measured (2026-09-26, Windows 10, AMD Radeon R7 240; `Node.snapshot`, public API)
The scene is `BoxBlur(9, 9, 3)` over `ImageInput` on a node scaled by 2. The reference is the same `BoxBlur` on an
`ImageView` showing the image at the same scale, whose input is rendered in device space. Kernel variance is in device
px², baseline subtracted:

| | horizontal | vertical | growth L/R, T/B |
| --- | --- | --- | --- |
| reference (D3D) | 87.1 | 87.0 | 24, 24 |
| D3D `ImageInput` | 22.9 | 83.8 | 12, 24 |
| SW `ImageInput` (with the software box peers keeping the input transform) | 23.6 | 24.3 | 12, 12 |

Scale 1.5 and the anisotropic scale (2, 1.5) show the same pattern. At s = 2 the pass size is 4.5 texels (9 device px)
where 9 texels (18 device px) is intended. D3D and ES2 are pixel-identical. No shrinking scale (s < 1) was measured on
hardware; by the same arithmetic its box is too long. D3D and ES2 were pixel-identical on these scenes. The probe
program, the runtimes and the dumps are in the Claude scratchpad of session 84434bd4 (`us007/hw`,
`BoxBlurScaledVisual.java`, `sc-table.md`).

## Acceptance criteria
- A unit test of `BoxRenderState` fails before the fix and passes after. It calls `validatePassInput` with a scaled
  input: s = 2, 1.5 and 0.5, and the anisotropic (2, 1.5). It compares **device-pixel extents**, not texel box sizes:
  the pass size times the device pixels per texel (`passSize / srcScale`) equals `iSize`, the device-pixel size, for
  both passes. Box sizes in texels differ from a pre-scaled input's by the scale, so `getBoxPixelSize` alone cannot be
  compared with a pre-scaled input.
- A hardware check: for the scaled `ImageInput` scenes, at least one enlarging (s > 1) and one shrinking (s < 1) scale,
  the blur extent on D3D, ES2 and SW, horizontal and vertical, matches the `ImageView` reference within the pipelines'
  rounding.
- `DecoraJavaGoldenTest` and `BoxBlurInputTransformTest` stay green. The latter deliberately does not pin the extent of
  scaled inputs. Any golden row that moves is handled as a reviewed test change. **Never regenerate the golden.**

## Notes
- **Open observation, not investigated.** On SW, once the scaled result is drawn at its scale, the left/top edge column
  of `BoxBlur(5, 5, 1)` at s = 2 reads alpha 149, where the mirrored right-edge column reads 64 (D3D 47). It moves the
  alpha centroid by about 0.17 px. This looks like Prism SW texture sampling at the image edge, not Decora. Check it
  while doing the hardware check above, and split it out if it is real.
- Related: US-011 (asymmetric multi-pass box weights), in the same class.

## Resolution (2026-09-26, PR #17)
- **Fix:** `BoxRenderState.validatePassInput` no longer has `pSize *= srcScale;`, so the pass size is
  `iSize * srcScale` texels, as in `GaussianRenderState.validatePassInput`. The `maxPassSize` clamp and the
  `srcScale = maxPassSize / iSize` renormalisation below it are unchanged and still right: a clamped pass spreads its
  taps over the unclamped texel length, which gives the right extent before and after the fix. The fix only moves the
  size at which the clamp starts: the constructor already clamps `iSize`, so only a shrunk input can reach this clamp,
  and it now does so later. Nothing else in production compensated for the squared factor: the peers,
  `getPassResultBounds` and `getInputClip` either read the pass size as it is or work in filter pixels.
- **Tests:**
  - New `test.com.sun.scenario.effect.BoxRenderStateScaledInputTest`, 19 cases, all through public API. For both
    passes it checks that pass size (texels) × tap step (texels, from `getPassVector`) × device pixels per texel
    equals the filter-space size, for inputs scaled by 2, 1.5, 0.5 and (2, 1.5), with the filter transform and the
    input scaled together (what an `ImageInput` on a scaled node produces), and for a shadow. The single-pass pass size
    is the reciprocal of the centre weight. Three-pass boxes are checked on `getBoxPixelSize`/`getPassKernelSize` so
    that the separate multi-pass weight bug (US-011) cannot interfere. Per pass count, one input scaled by 0.5 is
    clamped only when its size is scaled twice. It has the right extent either way, so its unit tap step and box pin
    where the clamp starts. Identity/translate controls and one guard clamped before and after, per pass count,
    pass before and after. Before the fix 14 of 19 failed, exactly the scaled cases: for example the 9/5 px
    single-pass boxes covered 4.5/2.5 device px at s = 2 and 18/10 at s = 0.5. The 40 px box at s = 0.5 used 127 taps
    at a 0.63-texel step where 80 unit-step texels are due. A mutant that keeps the clamp at the squared size fails
    exactly those two cases.
  - `BoxBlurInputTransformTest` stays green (37/37). Its largest centroid shift grew from 0.0082 to 0.0188 device
    px, within its 0.05 tolerance. The javadoc now points to the new test for the extent, instead of saying the extent
    is deliberately not asserted. It also gives the smallest centroid shift of the tested peer mutations for the fixed
    code, 0.79 device px. The earlier 0.17 came from a pass 0 that only the squared size made a no-op.
  - `DecoraJavaGoldenTest` (`-Djfx.parity.require=true`): output byte-identical before and after, on Windows and on
    Linux (WSL, JDK 25). The corpus has no scaled box input. **The golden was not regenerated.**
  - Windows: `test.com.sun.scenario.effect.**` 664 run, 0 failures; full `javafx.graphics` module 25,623 run, 0
    failures, 0 errors, 356 skipped. Linux (WSL): `test.com.sun.scenario.effect.**` 664 run, 0 failures.
- **Hardware check (met):** `Node.snapshot` with a fixed viewport, AMD Radeon R7 240, D3D9Ex, ES2 over WGL and the
  Java SW pipeline, runtimes identical to commit 7b4df941b6 plus the fixed class. The scenes are 55
  `ImageInput`-vs-`ImageView` groups: node scale 2, 1.5, 0.5, 0.75, (2, 1.5), (0.5, 0.75); snapshot scale 2 and 0.5;
  rotate 30 with scale 2 or 0.5. The effects are `BoxBlur(9,9,3)`, `BoxBlur(5,5,1)`, box `Shadow`s and a box
  `DropShadow`, plus clamp and no-op-axis controls. Sigma ratio `ImageInput`/`ImageView` on the same pipeline,
  horizontal / vertical:

  | scene | pipeline | before | after |
  | --- | --- | --- | --- |
  | `BoxBlur(9,9,3)`, s = 2 | SW | 0.55 / 0.53 | 0.96 / 0.96 |
  | same | D3D = ES2 | 0.51 / 0.98 | 0.96 / 0.98 |
  | `BoxBlur(9,9,3)`, s = 0.5 | SW | 1.92 / 1.94 | 0.94 / 0.94 |
  | same | D3D = ES2 | 1.69 / 0.99 | 0.94 / 1.00 |
  | `BoxBlur(0,9,3)`, s = 2 / 0.5 | D3D = ES2 | - / 0.51 ; - / 1.68 | - / 0.96 ; - / 0.94 |

  After the fix every pass covers `iSize` device pixels: the sizes of all 880 passes that ran matched an independent
  prediction. Every main scene lies within 0.05 of a model built from those pass sizes plus each pipeline's box
  rounding. That bound only shows that nothing else moved the extent, and most rows met it before the fix as well,
  against the pass sizes that ran then. What shows the fix is the pass sizes and the ratios: before the fix the ratio
  carried the 1/s signature, and after it is 0.94 to 1.00. The gap that remains is in the references: SW rounds a box
  up to an odd whole pixel, and US-011 inflates the GPU's fractional multi-pass kernels. Across pipelines, the
  references themselves differ by a sigma ratio of 0.95 to 1.01. All `ImageView` reference and unblurred dumps are
  pixel-identical before and after on all three pipelines. ES2 differs from D3D by at most 1. On the GPU a constant
  resampling offset of about −3 px² remains across the pass direction at s = 2, the same before and after: pass 0
  crops the half-texel bilinear fringe of the scaled texture. A small SW box can now be further from its `ImageView`
  reference, which rounds in its own device grid. This is not a regression: `BoxBlur(5,5,1)` at s = 0.75 went from
  1.07 / 1.10 to 0.82 / 0.69, because the `ImageInput` box is now the intended 3.75 device pixels, where the reference
  rounds it up to 5.
- **Open observation, resolved:** it is real, independent of Decora and of this fix, and split out as US-013. The
  native SW texture paint (`PiscesPaint.c` `genTexturePaintTarget`) interpolates the first device column and row
  between texels 0 and 1 with the weights meant for texels −1 and 0. The review of this change found that the same
  path reads past the end of the texture array for images one texel tall or wide, so US-013 covers that too.
- **Evidence:** Claude scratchpad of session 053dac63, `us012/`: `A-notes.md`, the before/after outputs and logs,
  `hw/US-012-hardware-report.md`, `hw/table.md`, `hw/verdict.txt`, and the independent review `review-C.md` with its
  mutants and probes in `reviewC/`. The probe is `hw/src/BoxBlurScaledVisual2.java`.
- **Upstream:** `openjdk/jfx` master still has `pSize *= srcScale;` (checked 2026-09-26).
