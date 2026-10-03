# US-060 — Interpolate the samples of the software shadow convolution

**Status:** 📋 Ready (filed 2026-10-03 from US-014; reproduced on SW against D3D, cause proven by two single-variable
interventions, a bilinear patch measured in scratch) · **Found:** 2026-10-03, the rotate-45 residual of US-014

## Story
As a JavaFX app developer whose app runs on the software pipeline,
I want a Gaussian `InnerShadow`, `DropShadow` or `Shadow` on a rotated node to look the same as on D3D/ES2, and the
same whether the node is drawn whole or partly,
so that a shadow does not jump by up to 29 steps along a diagonal when the node scrolls into view.

## Problem
`JSWLinearConvolveShadowPeer.filterVector` (`:68-75`) samples the nearest texel: `int ix = (int) sampx;
int iy = (int) sampy;`, under the comment `TODO: Usine linear interpolation here... (JDK-8090445)`. The base
`JSWLinearConvolvePeer.filterVector` interpolates bilinearly (`laccumsample`), and so do the D3D/ES2 shaders.
- **Origin-dependent at exact ties.** A rotated shadow input reaches Gaussian pass 0 with the rotation in its
  transform, so pass 0 runs `filterVector` with the 8-coordinate steps (fixed by US-014). For `InnerShadow`
  rotated 45 degrees about an integer device point, every device pixel centre on the diagonal maps exactly to a
  texel row boundary (user y = 80.000). There `(int) sampy` follows float noise of up to 9.2e-5 texel. That noise
  comes from the clipped origin's normalisation and from the `+=` step accumulation, so it differs between a full
  render and a viewport.
  - In the top cut, all 21 taps of a diagonal pixel then read row 79 instead of row 80. The pass-0 alpha jumps by
    up to 173 (69 vs 242).
  - Pass 1 (diagonal vector, also `filterVector`) reads the same texels in both renders and only carries the jump
    on: about 0.2147 x 173 = 37 (measured 38). `SRC_ATOP` over the content then gives 29.
  - Which cuts break depends on the rounding sign: top and narrowy break, inner, left, q32 and narrowx do not.
- **SW vs GPU.** At rotate 45, nearest sampling is most of the gap between SW and D3D full renders: bilinear takes
  `InnerShadow` from 22 to 3, `DropShadow` from 18 to 3 and `Shadow` from 23 to 2. At rotate 30 bilinear cuts the
  pixels that differ by more than 1 (`DropShadow` 12,219 to 6,240, `InnerShadow` 9,112 to 435) but not the max
  (224 and 255), which therefore does not come from the sampler.
- **Not affected:**
  - quarter turns and translate-only inputs (samples on texel centres);
  - plain `GaussianBlur` (base peer, bilinear);
  - box shadows;
  - D3D/ES2.
- **Upstream:** JDK-8090445 "JSWLinearConvolveShadowPeer.filterVector: Use linear interpolation" is open (P5
  enhancement, 2012, unassigned). It does not describe the viewport discontinuity.

### Measured (2026-10-03, Windows 10, AMD Radeon R7 240, `Node.snapshot`; HEAD 25c9ec02a7 + US-014 fix)
- **Scene:** the US-010/US-014 scene, a 160x160 checker `ImageView` with `InnerShadow(BlurType.GAUSSIAN, 10)`
  rotated 45 degrees about its centre.
- **Viewport vs cropped full render (max steps):**
  - SW: inner 2, left 2, **top 29** (654 px > 1), q32 2, narrowx 2, **narrowy 26** (130 px > 1);
  - D3D: 0-1 on every cut.
  - Unchanged by US-013.
- **Full render, SW vs D3D (max):**
  - `InnerShadow` r45: 22 (10,557 px > 1);
  - `DropShadow` r45: 18;
  - shadow r45: 23.
- **Cause isolated by two single-variable interventions:**
  - On hardware, changing only the sampler to bilinear brings every cut to max 2 (the 2s are content-edge alpha,
    outside the shadow chain) and every pass to max 1.
  - In a `DecoraBackend` harness, bilinear alone gives at most 1. Keeping nearest but computing each pixel's
    positions in double from its absolute device centre gives 0 on every cut and on 100-offset sweeps of the top and
    left cuts.
  - Evidence: Claude scratchpad of session c1b89658, `us014/r45/` (`r45-hw/notes.md`, `r45-unit/notes.md`,
    `r45-skeptic/notes.md`).

## Proposed fix
- **Preferred: bilinear alpha in `filterVector`.** Texel centres at `i + 0.5` and the edge tests of
  `JSWEffectPeer.laccumsample`. A tested patch of about 20 lines in one method was measured (US-014 scratchpad
  `r45/r45-hw/bilinear-shadow.diff`):
  - every r45 cut is <= 2;
  - SW vs D3D full drops from 22 to 3 (`DropShadow` 18 to 3, shadow 23 to 2);
  - the 419 golden-corpus renders are byte-identical except one row (below). A tinted twin of that row, which is not
    in the corpus, also moves;
  - each tap costs 4 reads instead of 1.
- **Golden-neutral alternative:** keep nearest and compute each output pixel's sample start in double from its
  absolute device pixel centre. It removes the discontinuity (0 of 419 corpus renders move), but it is a second
  geometry path, and it keeps the SW-vs-GPU gap.
- `filterHV` (the shadow peer's axis-aligned loop) samples texel centres and needs no change.

## Acceptance criteria
- **A test first:** a `DecoraBackend` shadow convolve of a hard-edged input rotated 45 degrees about an integer point.
  The clipped render (top cut) equals the cropped full render within 2 steps. It fails before the fix (38 at the
  shadow-pass level).
- **Golden:** the bilinear fix moves exactly one `DecoraJavaGoldenTest` row:
  - `clip/LinearConvolveShadow/gaussian | radius=3.0 spread=0.5 black clip top only | 257x129`
    (`decora-sse-win-golden.txt:357`, tier H, sha `b66f2853...` to `2657de54...`);
  - the move is 7 px by 1 step, while its unclipped twin does not move;
  - on amd64 the row would become UNJUDGEABLE, which fails the test.

  Record it as a reviewed deviation (like `KernelDeviation`/`TransformDeviation` in `DecoraCorpus`), with a negative
  control. Never regenerate the golden.
- The other 418 corpus renders are byte-identical.
- **Hardware check:** SW vs D3D/ES2 for `InnerShadow`/`DropShadow` at r45, full and viewport. Within 3 steps
  (measured 3 with the scratch patch).

## Notes
- Found while explaining US-014's rotate-45 residual. With the US-014 swap reverted, the main bug (157/110 on the same
  cuts) hides it.
- A candidate for an upstream JBS comment on JDK-8090445 (the tie discontinuity), like US-010 to US-014.
