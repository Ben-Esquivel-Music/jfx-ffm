# US-011 — Build symmetric box-blur kernels for two or more passes

**Status:** 📋 Ready (reproduced by calling the real method; fix identified) · **Found:** 2026-09-26, hardware check of the software BoxBlur transform fix (D3D vs SW snapshots)

## Story
As a JavaFX app developer using `BoxBlur` (default `iterations = 3`) or a box-type shadow on a GPU pipeline,
I want the blur kernel to be the symmetric repeated box the effect promises,
so that blurred content is not skewed toward one side and matches the software pipeline.

## Problem
`BoxRenderState.validateWeights()` builds the kernel for the `LinearConvolve` peers by convolving the pass box with
itself `blurPasses` times, in place, from the end of `ik[]` backwards (`:509-526`). The first loop,
`while (i > klen)`, sums a full `klen`-tap window. The second loop, `while (i > 0)`, sums `ik[0..i]`, which is only
right for `i < klen`. At `i == klen` it adds `klen + 1` taps, one too many, so every kernel with `blurPasses >= 2` is
asymmetric and its normalisation sum is too large.

- **Worked example** (`klen = 3`, 2 passes): the result is `[1 2 3 3 1] / 10`; the correct kernel is `[1 2 3 2 1] / 9`.
- **Fix:** `while (i >= klen)` for the first loop. The second loop then only sees `i < klen`.
- **Upstream too.** The `openjdk/jfx` master copy has the same loops (checked 2026-09-26). The code has been unchanged
  since the 2016 package rename (`c420248b9b`), so this is not a fork regression.

### Who is affected
- **GPU pipelines (D3D, ES2), always:** every `BoxBlur` with `iterations >= 2`, including the default 3, and every
  `DropShadow`, `InnerShadow` and `Shadow` with `BlurType.TWO_PASS_BOX` or `THREE_PASS_BOX`, the default blur type.
  All of them render through `PPSLinearConvolvePeer` / `PPSLinearConvolveShadowPeer`, which read these weights.
- **Software pipeline, sometimes:** only when `getPassPeer` falls through to `LinearConvolve`/`LinearConvolveShadow`
  (`:300-311`). That happens when:
  - a shadow has `spread != 0`;
  - `validatePassInput` finds a pass input not `swCompatible` (`:395-401`), because the pass size had to be clamped
    to `MAX_BOX_SIZES` (`:366-369`, a very large box or scale), or because the input's transform flips, rotates or
    shears the axes.
  Scaled and rotated nodes do not take this route: the constructor asks for a positive axis-aligned scale as the
  input transform (CustomSpace, `:226-235`), and a node's own content is rendered in device space. The hardware probe
  saw `JSWBoxBlurPeer` for every SW scene, rotated and scaled ones included. The common case uses the hand-written
  `JSWBoxBlurPeer` / `JSWBoxShadowPeer`, which iterate the box directly and are symmetric. This is why SW and GPU
  disagree on the same scene.
- **Not affected:** one pass (`iterations = 1`, `ONE_PASS_BOX`), and `GAUSSIAN` blur types.

### Measured (2026-09-26, Windows 10, AMD Radeon R7 240)
- **Weights, from a reflective call of the real method:**
  - 3 x 2 passes: `.1000 .2000 .3000 .3000 .1000` against `[1 2 3 2 1]/9` = `.1111 .2222 .3333 .2222 .1111`.
  - 3 x 3: `.0323 .0968 .1935 .2903 .2258 .1290 .0323` against `.0370 .1111 .2222 .2593 .2222 .1111 .0370`.
  - 9 x 3 and 15 x 2: skewed around the centre tap (9 x 3: `.0731` before it, `.0717` after it).
- **Pixels:** a `Node.snapshot` of `BoxBlur(15, 3, 2)` differs between D3D and SW by 18 steps; `THREE_PASS_BOX`
  shadows differ by 12. D3D and ES2 are pixel-identical, so the difference is the kernel, not a driver.
  - The alpha profile of a D3D blur edge is asymmetric: rows 10..13 read `25 102 178 229` against `229 153 76 25` on
    the far side. SW gives `28 85 170 227`, symmetric.
  - The same scenes on one pass (`5 x 5 x 1`, `ONE_PASS_BOX`) agree within 2 steps.

## Acceptance criteria
- A unit test of `BoxRenderState.getPassWeights()` for several `(size, passes)` pairs, at least 3x2, 3x3, 9x3 and
  15x2, plus 1-pass controls. It fails before the fix and passes after, and asserts:
  - for **odd integer sizes** (no end trimming), the weights equal the n-fold convolution of a box of `size` ones,
    computed independently in the test;
  - for **every size**, including fractional ones such as 4 or 4.5, the weights are symmetric and sum to 1.
- **Decide and record the fractional-size kernel.** The loops trim only the first box's end weights (`:503-507`) and
  convolve it with untrimmed boxes of `klen` ones. After the fix a size-4, 2-pass kernel is therefore
  `[0.5 1 1 1 0.5] ⊛ [1 1 1 1 1]`, not the 2-fold trimmed box. Say whether that is intended. Fixing the tap count is
  this story; changing the trimming would be a separate behaviour change.
- A hardware check, the same `Node.snapshot` scenes on D3D and SW with **odd integer sizes**: after the fix, the GPU
  result of a multi-pass `BoxBlur` matches the SW `JSWBoxBlurPeer` result within the SW rounding bound (3 steps for
  9 x 3 in the transform-fix probe), and its alpha profile is symmetric. `JSWBoxBlurPeer` always uses boxes of
  `ceil(size) | 1`, so for other sizes SW and GPU differ by design.
- `DecoraJavaGoldenTest`: rows whose box state routes to the SW `LinearConvolve` peers (spread != 0 or non-translate)
  may move. If any do, handle them as a reviewed test change with a documented cause. **Never regenerate the golden.**

## Notes
- The evidence, including the probe program, the reflective weights dump (`boxweights.txt`) and the D3D/ES2/SW pixel
  dumps, is in the Claude scratchpad of session 84434bd4 (`us007/hw`).
- A candidate for upstream together with the software box peers' transform fix.
