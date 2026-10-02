# US-011 — Build symmetric box-blur kernels for two or more passes

**Status:** ✅ Done (2026-10-02) · **Found:** 2026-09-26, hardware check of the software BoxBlur transform fix (D3D vs SW snapshots)

## Story
As a JavaFX app developer using `BoxBlur` with two or three iterations, or a box-type shadow, on a GPU pipeline,
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
- **GPU pipelines (D3D, ES2), always:** every `BoxBlur` with `iterations >= 2` (the default is 1), and every
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

## Resolution (2026-10-02)
- **Fix:** `BoxRenderState.validateWeights()` convolves the first box with a box of `klen` ones once per further
  pass, in place from the last tap backwards. Its first loop now runs `while (i >= klen)` (was `i > klen`), so at
  `i == klen` it sums the full window `i-klen+1..i`, and the second loop only sees `i < klen`, where taps `0..i` are
  the whole partial window. Two short comments say which taps each loop sums, and that only the first box has trimmed
  end weights. Nothing else changed: the normalisation, the spread, the zero padding to the peer size,
  `getPassKernelSize()` and every caller. Every multi-pass kernel is now exactly symmetric and divided by its true
  sum: 3 x 2 gives `[1 2 3 2 1] / 9` (was `[1 2 3 3 1] / 10`), 3 x 3 gives `[1 3 6 7 6 3 1] / 27` (was
  `[1 3 6 9 7 4 1] / 31`). One pass and the `GAUSSIAN` blur types are unaffected.
- **Corrections to this story:** the FX `BoxBlur` default is 1 iteration (`@defaultValue 1`), not 3, so
  `new BoxBlur()` was never affected. The shadows' default blur type, `THREE_PASS_BOX`, is. The two phrases above that
  said otherwise are corrected. The Problem section's line numbers are from 2026-09-26; the fixed loop is now at
  `BoxRenderState.java:514`.
- **Fractional-size kernel (decision):** for a size that is not an odd integer, the kernel stays what
  `validateWeights()` builds once the tap count is right: the first box with its two end taps trimmed to
  `1 - (klen - s) / 2`, convolved with `passes - 1` UNtrimmed boxes of `klen = ceil(s) | 1` ones. That is not the
  `passes`-fold trimmed box the class javadoc implies when its box is repeated: size 4 over 2 passes gives
  `[0.5 1.5 2.5 3.5 4 3.5 2.5 1.5 0.5] / 20`, against `[0.25 1 2 3 3.5 3 2 1 0.25] / 16`. Nothing in the code says
  that is intended, so it is not taken as intended. It is kept here because changing it is a behaviour change of
  its own, and it is filed as US-055 (trim every pass, keep and document, or round up like SW).
  `BoxRenderStateWeightsTest` pins it exactly, and its javadoc records the decision and points to US-055. Kernel
  variance in px²:

  | size x passes | after the fix | `passes`-fold trimmed box | SW, boxes of `ceil(s) \| 1` | before the fix |
  | --- | --- | --- | --- | --- |
  | 4 x 2 | 3.500 | 3.000 | 4.000 | 3.438 (mean +0.024 px) |
  | 4 x 3 | 5.500 | 4.500 | 6.000 | 5.417 (mean +0.019 px) |
  | 4.5 x 2 | 3.778 | 3.556 | 4.000 | 3.687 (mean +0.032 px) |
  | 4.5 x 3 | 5.778 | 5.333 | 6.000 | 5.658 (mean +0.026 px) |
- **Tests:**
  - New `test.com.sun.scenario.effect.BoxRenderStateWeightsTest`, 27 tests through public API. It drives the real
    `BoxRenderState` with different h and v sizes and checks pass 0 and pass 1 **bitwise** against independent
    nested-loop convolutions (every value before the final division is a multiple of 2^-24 below 2^17, so it is
    exact in `double`):
    - odd sizes 3 x 2, 15 x 2, 25 x 2, the largest 2-pass box 63, 3 x 3, 5 x 3, 9 x 3 and the largest 3-pass box
      43: the `passes`-fold box of ones;
    - non-odd sizes 4, 4.5, 2.5, 7.25, 4/6 and 42.5/7.3 with 2 and 3 passes: the pinned kernel above;
    - every size: exact symmetry, a sum of 1 within 2^-23, and zero padding up to `getPassWeightsArrayLength() * 4`.

    Before the fix 20 of the 27 failed, exactly the multi-pass cases on both passes; the 1-pass controls (5, 9, 127,
    4/4.5) passed. After it, 27/27 pass.
  - Mutant proof: 21 mutants of the fixed method, patched into the module from outside the repo.
    - 17 fail the weights test. Among them: the original bug, `k <= i`, the n-fold trimmed box (only the non-odd pin
      catches it), no trimming, a wrong sum, swapped pass sizes, a shifted kernel, an even `klen`, and a float
      division (only the 42.5/7.3 case catches it).
    - A dropped spread passes the weights test, which sets no spread, and fails 42 golden checks.
    - Three are equivalent, bitwise identical to the fix over 7,360 size/pass/spread combinations: the mirrored
      kernel; `while (i >= klen - 1)`, because at `klen - 1` the full window is the partial window; and
      `while (i >= 0)` in the second loop, because `i == 0` sums `ik[0]` alone.
  - New test helper `BoxKernels`: the oracles, a frozen copy of the old loop, and a test-only `BoxRenderState`
    subclass that hands the peers a test-side kernel through a new `DecoraBackend.withBoxStates` hook. Production
    code has no hook.
  - `BoxBlurInputTransformTest` (37) and `BoxRenderStateScaledInputTest` (19) stay green and print byte-identical
    output. Neither reads multi-pass weights.
- **Golden (`DecoraJavaGoldenTest`, `-Djfx.parity.require=true`); the golden was not regenerated:**
  - 36 rows read box weights on SW (measured with a probe build): the `LinearConvolveShadow/box` spread rows and the
    `clip/LinearConvolveShadow/box` rows. With the fix alone, the 28 multi-pass ones failed and the 8 one-pass ones
    did not move. The 14 64x48 full-frame rows were 1 to 5 steps off on 4 to 1,784 pixels; the 14 257x129 rows,
    stored as hashes, became unjudgeable (1 to 5 steps on 4 to 12,149 pixels against the pre-fix class).
  - The golden holds the old kernel because it recorded the native SSE peers, and `SSELinearConvolvePeer.filter`
    read `getPassWeights()` from this same Java state (deleted in 26ce75d02f); `validateWeights` was unchanged from
    c420248b9b until this fix.
  - Reviewed change, in the style of `TransformDeviation`: `DecoraCorpus.KernelDeviation` `BOX_KERNEL_TAP_COUNT`
    lists the 28 keys. On each row (i) the recipe rendered with the frozen pre-fix kernel must pass every golden
    check at the row's own bound, (ii) the production render must differ from it, and (iii) the production render
    must equal the render with the independently computed kernel exactly. Six new tests (10 runs) show that the
    golden judgement, `KERNEL_UNCHANGED` and `KERNEL_ORACLE` branches can fail. Run against the pre-fix class, the
    final test fails exactly the 28 rows, each with only `KERNEL_UNCHANGED` and `KERNEL_ORACLE`, plus the six
    controls that assume the fix: 34 of 368. A clipped row's production render is also held to
    `checkSelfConsistency`, which, like every self-consistency check of the class, has no negative control (filed as
    US-058). The 28 rows' report lines gain a suffix naming the deviation; every other line is byte-identical to the
    baseline.
- **Windows and Linux runs** (counts from the Maven logs, per class):
  - Windows, `mvn -B -ntp -pl buildtools/jslc,modules/javafx.graphics clean test -Djfx.parity.require=true`: before
    (new test, no fix) graphics 25,705 run, 20 failures, all in the new test, 356 skipped; after 25,715 run,
    0 failures, 0 errors, 356 skipped; jslc 159/0.
  - Linux (WSL, JDK 25, a fresh clone with the change, `-am`, no parity flag): graphics 25,529 run, 0 failures, 500
    skipped, against 25,492/0/500 for plain HEAD; base 5,510/0/22.
  - On both, only `BoxRenderStateWeightsTest` (+27) and `DecoraJavaGoldenTest` (358 to 368) changed counts. No
    `hs_err`.
- **Hardware check (met):** `Node.snapshot` with a fixed viewport, AMD Radeon R7 240, D3D9Ex and the Java SW
  pipeline. PRE is a runtime of the 900c40e41a build. POST is the same runtime plus the fixed class, which differs
  from the shipped one in one bytecode (`if_icmple` to `if_icmplt`). It is instruction-identical to the class built
  from the tree: `javap -c -p` is equal, and only the line-number table of the two comments differs. ES2 over WGL was
  run too, and was bit-identical to D3D in every dump. It used a private `prism_es2.dll` of the same ABI, built
  2026-09-25 in an earlier session, and that build's shaders, because the Windows build does not make ES2. The bound
  was fixed before comparing: SW's own distance from the exact float result (2 to 3 steps, because SW truncates its
  stores) plus D3D's distance on the 1-pass controls (1 step).

  | scene (odd sizes) | D3D vs SW (max steps), before | D3D vs SW, after | D3D mirror asymmetry, before → after |
  | --- | --- | --- | --- |
  | `BoxBlur(15,3,2)` | 18 | 2 | 26 → 0 |
  | `BoxBlur(3,3,2)` | 21 | 2 | 26 → 0 |
  | `BoxBlur(3,3,3)` | 16 | 2 | 17 → 0 |
  | `BoxBlur(7,7,2)` | 4 | 2 | 5 → 0 |
  | `BoxBlur(5,5,3)` | 5 | 3 | 4 → 0 |
  | `BoxBlur(9,9,3)` | 3 | 3 | 1 → 0 |

  - After the fix D3D is never further from SW than SW is from the exact result, and D3D itself is within 1 step of
    the exact n-fold box everywhere. The alpha profile is mirror-symmetric (0 steps on every channel) and the
    alpha centroid is exactly the source centre; before, it sat at minus the kernel's mean tap offset (-0.10 px for
    3 x 2). The edge US-007 saw on `BoxBlur(15,3,2)` now reads 28 85 170 227 on both sides, equal to SW (before:
    25 102 178 229 against 25 76 153 229).
  - Box shadows (`Shadow` 9x3, 3x3 and 7x2, and a default `DropShadow`, box 7x3): D3D vs SW at most 2 after the fix
    (up to 16 before), symmetric. A `DropShadow` with spread 0.5, which runs the weights on SW too, moved on both
    pipelines towards the exact result (SW 4 → 3, D3D 2 → 1 steps) and became symmetric.
  - Controls: every 1-pass scene is identical before and after on SW, D3D and ES2; every spread-0 SW scene is
    identical, since the SW box peers never read the weights. Repeat runs showed no GPU wobble.
  - Not verified here: Metal (macOS) and ES2 on Linux GPUs, whose `LinearConvolve` peers read the same weights.
- **Robot suite not run (waiver).** The change moves GPU pixels of every multi-pass box blur and box shadow,
  including the default `DropShadow` and `InnerShadow` (`THREE_PASS_BOX`) and Modena's tooltip and colour-dialog
  shadows. No robot test uses a `BoxBlur`, `DropShadow`, `InnerShadow`, `Shadow` or `BlurType`. The one system test
  that does, `ShapeCacheTest`, compares two renders of the same shadow with each other. A `FULL_TEST`/`USE_ROBOT`
  run on Windows takes over the desktop for about 45 minutes, so it is left to the pre-push check
  (`openjfx-conventions`): `mvn -pl tests/system test -DFULL_TEST=true -DUSE_ROBOT=true`.
- **Found on the way and filed:** US-055 (the non-odd kernel, above), US-056 (`BoxRenderState` never sets its
  weights-cache keys, so it rebuilds the kernel on every read), US-057 (`javafx.base` gets the module version
  `28-ea` where the other modules get `28-internal`) and US-058 (the Decora golden test's self-consistency checks
  have no negative controls).
- **Evidence:** Claude scratchpad of session a165c130, `us011/`: `A-notes.md`, `newtest-*.txt`,
  `golden-after-fix-only.txt`, `harness/` (launcher, mutants, sweeps), `gate/` (Windows and WSL gates),
  `hw/US-011-hardware-report.md` with the probe and the dumps, and the independent review `review-C.md`.
- **Upstream:** `openjdk/jfx` master still has `while (i > klen)` (checked 2026-10-02). A candidate for upstream
  together with the US-007 and US-012 fixes.
