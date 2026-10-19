# US-060 — Interpolate the samples of the software shadow convolution

**Status:** ✅ Done (2026-10-17, 7d5ffd66f0, PR #31) · **Found:** 2026-10-03, the rotate-45 residual of US-014

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

## Resolution (2026-10-17)
- **Fix:** the preferred fix. `JSWLinearConvolveShadowPeer.filterVector` interpolates each sample's alpha bilinearly
  from the four texels around it. It uses the sample positions and edge tests of `JSWEffectPeer.laccumsample`, the
  sampler of the base `JSWLinearConvolvePeer.filterVector`: texel centres at `i + 0.5`, and a texel outside the source
  counts as zero. Each tap adds the interpolated alpha times its weight.
  - The output packing (`shadowColor` times the clamped sum) and `filterHV` did not change. The TODO is replaced by a
    comment that says what is sampled and why, citing JDK-8090445.
  - `javap -c -p` of the compiled class differs from the class at 4f71e99535 only inside `filterVector`.
  - Why not `laccumsample`'s own arithmetic: it scales each fraction by the weight times 255 and divides by 255,
    which is not exact, even on a texel centre. Tried first, it moved 53 pixels of the golden row instead of 7, and
    289 pixels of a `GaussianInputClipTest` comparison by one step.
  - The chosen form is exact on texel centres. A sample exactly on a centre gets the weights 1 and 0 and reads that
    texel's alpha exactly, as the old loop did. So inputs whose samples land on texel centres render byte-identical,
    and only the one golden row moves.
  - Cost: on the 45-degree test scene a shadow render takes about 22 ms, against 9.5 ms with the nearest texel and
    51 ms for a blur of the same radii through the base peer.
- **Corrections to this story:** none to its findings. The scratch evidence it cites was deleted by temp cleanup, so
  everything here was measured again.
- **Tests, written first:** new `test.com.sun.scenario.effect.LinearConvolveShadowSamplingTest`, 13 tests through
  `DecoraBackend`. The scene is the input an `InnerShadow` over a 160x160 checker of opaque and transparent 20-pixel
  cells hands its Gaussian shadow: the checker's `InvertMask`, padded by the radius 10. It is rotated by 45 degrees
  about the checker's centre, a cell corner, which lands on an integer device point. Every pixel on that diagonal then
  samples exactly on a row boundary between complementary cells. The rotation uses `StrictMath`, so the sample
  positions are the same on every platform.
  - Clipped against full (5 tests): the `top`, `narrowy`, `left`, `narrowx` and `inner` cuts. The clipped render has to
    equal the cropped full render within 2 steps, one per pass.
  - Every top-cut depth from 1 to 100 rows (1 test).
  - A control (1 test): the frozen pre-fix loop still breaks the top cut, so the scene still reaches the tie.
  - The sampler (2 tests): the loop is run directly (new hook `DecoraBackend.filterVectorShadow`) on a 4x3 source
    with one tap of weight 1, over two grids at quarter-texel offsets that reach past every edge. It has to give
    exactly the bilinear value of every sample. Every product is exact in float, so the bound is 0. A sentinel texel
    in each row's scanline padding catches a read past an edge.
  - The blur's alpha (4 tests): a black shadow equals the blur's alpha channel within 2 steps, on the same input,
    rotated by 45 degrees (full and top cut), by the 3-4-5 rotation and sheared. Measured: 1 step on 1,049 to 1,628
    pixels, from the two loops' different float roundings.
  - At 4f71e99535, 9 of the 13 failed: the `top` cut by 54 steps (1,428 pixels above 1), `narrowy` by 50, 80 of the
    100 top-cut depths by 54, both grids on 19 of their 35 samples, and the shadows by 19 to 30 steps from the blur's
    alpha. The other three cuts and the control passed. After the fix every cut is within 1 step and the grids are
    exact.
  - 54 against the story's 38: both are the output of the two-pass shadow convolution, and pass 1 carries about
    0.2147 of the pass-0 jump. In the story's scene that jump was 173 (69 against 242), which gives 38. Here all 21
    taps of a diagonal pixel switch between complementary cells, so the jump is the full 255, which gives 54. Neither
    number is in the test, which asserts the 2-step bound.
  - The javadoc of `LinearConvolveRotatedInputTest`, `GaussianInputClipTest` and `GaussianPassClipGrowthTest` said the
    shadow loop reads the nearest pixel; it is corrected. Their outputs and counts are unchanged.
- **Mutant proof:** each mutant was compiled outside the repository and patched into `javafx.graphics` with
  `--patch-module`, then run against the new class and `DecoraJavaGoldenTest` (396 tests). Failures, with those in the
  new class in brackets:
  - the loop of 4f71e99535 (nearest texel): 14 (9);
  - no half-texel shift of the texel centres: 67 (6), and 48 golden rows among them;
  - outside taps clamped to the edge texel instead of zero: 66 (7);
  - the x and y fractions swapped: 20 (12);
  - the right and bottom fringe dropped (`ix <= srcw` to `ix < srcw`): 63 (6);
  - the top and left fringe dropped: 14 (6);
  - the left edge test off by one (`ix > 0` to `ix >= 0`): 12 (12), an out-of-bounds read;
  - the bottom edge test off by one (`iy < srch` to `iy <= srch`): 75 (12);
  - `laccumsample`'s arithmetic: 4 (0), the pinned golden row and three of its controls.
  - The mutant that applies the tap weight to each texel's fraction survives. It only reassociates float operations:
    it moves no corpus row, and on 4 of 60 random rotated or scaled Gaussian shadows it differs from the fix by at most
    1 step, within every bound.
- **Golden (`DecoraJavaGoldenTest`, `-Djfx.parity.require=true`); the golden was not regenerated:**
  - One row moves: `clip/LinearConvolveShadow/gaussian | radius=3.0 spread=0.5 black clip top only | 257x129`, a
    hash-only row, from b66f2853 to 2657de54, 7 pixels by 1 step. Its unclipped render runs `filterHV` and does not
    move.
  - The golden holds the old sampler because it recorded the native `SSELinearConvolveShadowPeer`, whose
    `filterVector` read the nearest texel as well (deleted in commit 26ce75d02f).
  - Reviewed change, modelled on `KernelDeviation`: `DecoraCorpus.SamplerDeviation` `SHADOW_SAMPLER_BILINEAR`. The row
    is judged three ways. Rendered with a frozen copy of the pre-fix loop (`ShadowSamplers.PreFixNearestShadowPeer`),
    it has to pass every check against the golden, its hash included. The production render has to differ from that
    render. And the production frame has to hash to the pinned SHA-256, which reproduces the hash the story measured
    for that row. Like the golden's own hashes, a frame off the pin fails on x64 and warns elsewhere; other bounds or
    another transform fail everywhere. `DecoraBackend` gains `withPeer` to render with the frozen loop.
  - 8 negative controls: the deviation's row set; a reverted fix; a listed row the sampler does not move; the moved
    row not listed, which is unjudgeable; the bilinear loop taken as the golden's; a frame off the pin; a transform
    off the golden's; and the production render's own self-consistency.
  - The golden resources are byte-identical, and the other 418 renders are identical. An independent whole-frame probe
    found that production differs from the frozen pre-fix render on that row only. The row's report line gains a
    suffix naming the deviation; every other line is byte-identical.
- **Windows and Linux runs** (counts from the Maven logs):
  - Windows, Temurin 27+35, `mvn -B -ntp -pl buildtools/jslc,modules/javafx.base,modules/javafx.graphics clean test
    -Djfx.parity.require=true`, before and after. `javafx.base` is in the reactor because the local Maven repository
    no longer holds the 28-ea jars.
    - graphics: 26,007 run before; after, 26,028 run, 0 failures, 0 errors, 356 skipped. The +21 are the new class
      (13) and `DecoraJavaGoldenTest`, 375 to 383 (8).
    - base 5,510 run, 0 failures, 22 skipped, and jslc 159/0, unchanged.
    - Generated JSL sources and headers were md5-identical, and there was no `hs_err`.
    - The only output difference is the deviation suffix on the row's verdict line.
  - Linux (WSL, Temurin 27+35, a fresh clone of 4f71e99535 with the change): `test.com.sun.scenario.effect.**` ran
    803 tests, 0 failures. All 7 effect output files are identical to Windows once line endings are normalised.
- **Hardware check (met):** `Node.snapshot`, SW and D3D9Ex. Before is a runtime of 4f71e99535; after is the same
  runtime plus the fixed class.
  - SW viewport against full, `InnerShadow` rotated by 45 degrees: the top cut 29 to 2 (95 pixels above 1), `narrowy`
    26 to 2 (16 pixels). The other cuts stay at 2. `DropShadow` 2, `Shadow` 1.
  - SW against D3D, full render at 45 degrees: `InnerShadow` 22 to 3 (375 pixels above 1), `DropShadow` 18 to 3
    (2,327), `Shadow` 23 to 2 (1,095). Viewports at 45 degrees: 2 to 3.
  - Controls: the unrotated scene is byte-identical before and after, and repeat runs are byte-identical.
  - At 30 degrees SW against D3D keeps its maximum, 255 (`InnerShadow`) and 224 (`DropShadow`) at (52,-29). That is
    the content edge, not the shadow sampler. The pixels above 1 drop from 9,112 to 435 and from 12,219 to 6,240.
  - ES2 was not run: the Windows build makes no `prism_es2.dll`. D3D is the GPU reference; US-014 measured ES2 within
    1 step of D3D.
- **Upstream:** JDK-8090445 was open when this story was filed and does not describe the viewport discontinuity. A
  JBS comment on it is a candidate, like those for US-010 to US-014. Nothing was posted; the user decides.
