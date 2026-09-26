# US-006 — Grow the Gaussian pass-0 clip by the vertical radius

**Status:** ✅ Done (2026-09-25, uncommitted) · **Found:** 2026-09-13, impact assessment for the `decora_sse` deletion (code reading, finding B2) · **Deferred from:** the `decora_sse` deletion

## Story
As a JavaFX app developer blurring or shadowing a node with a larger vertical radius than horizontal radius,
I want partial repaints to render the same pixels as a full repaint,
so that dirty-region updates don't leave clipped or faded bands.

## Problem
`GaussianRenderState` (around `:466` at `f9d06d85cc`) grows the pass-0 clip by `radiusX`. The vertical padding that pass 1 needs is `radiusY`.

- It matters when pass 0 runs and `ceil(radiusX) < ceil(radiusY)`, under a clip that cuts the result.
- It is shared by every backend that uses this render state: the Java software peers and the GPU `PPS` peers. The deleted SSE peers had it too.
- Found by code reading only. **No pixel reproduction exists yet.**

## Acceptance criteria
- First, a reproducing test: an anisotropic Gaussian (for example `DropShadow` or `GaussianBlur` with `rY > rX`) rendered clipped vs unclipped-then-cropped through the production peer protocol (`DecoraBackend`). It must show a difference beyond one step near the clip's top and bottom edges. If it can't be reproduced, close the story with the evidence.
- The fix grows the clip by the radius for the pass's direction. The reproducing test passes, and `DecoraJavaGoldenTest` stays green.
- Check the hardware path (D3D, ES2) for the same scene.

## Notes
- Related to the F2 fix already landed in `JSWLinearConvolvePeer` (pass-0 destination bounds). This is the render-state side.

## Resolution (2026-09-25, uncommitted)
- **Reproduced.** Before the fix, a Gaussian with `ceil(rX) < ceil(rY)` rendered through a clip that cuts its top or
  bottom differs from the unclipped render cropped to the clip by 11 to 110 steps per channel. The difference sits in
  the `ceil(rY) - ceil(rX)` rows next to each cut edge. Pass 1 read transparent pixels there, because pass 0 had kept
  only `ceil(rX)` rows beyond the clip.
- **Fix:** `GaussianRenderState.getPassResultBounds`, pass-0 branch. The clip is grown along the pass-1 sample vector
  by `inputRadiusY` instead of by `r`, which is `inputRadiusX` on pass 0:
  `dx = samplevectors[2] * inputRadiusY; dy = samplevectors[3] * inputRadiusY;`.
  - For an untransformed kernel this is `ceil(radiusY)` rows, as `BoxRenderState` grows its pass-0 clip by half the
    pass-1 kernel. The vector form also holds for rotated and skewed filter transforms.
  - The Java peers (`JSWLinearConvolve[Shadow]Peer`) and the GPU peers (`PPSLinearConvolve[Shadow]Peer`) both take
    their pass bounds from this method, so this one change fixes both. The MotionBlur state is unaffected: its
    pass-1 vector and radius are 0.
- **Tests:** new `test.com.sun.scenario.effect.GaussianPassClipGrowthTest`, 240 cases through the production pass
  protocol (`DecoraBackend`), with a fresh backend per render.
  - 60 anisotropic pixel cases: blur, and shadow black or tinted with spread 0 or 0.5; radii 2/8 and 1.5/12; cuts
    top+bottom, top only and bottom only; the corpus pattern and an opaque block.
  - 120 controls over the same matrix: isotropic 5/5, equal ceilings 4.2/4.8, rX > rY 10/1, and a no-op pass 0.
  - 8 cases whose source is first cut to `getInputClip`, as `FilterEffect` and `NodeEffectInput` do in production.
  - 44 loop pins showing which peer loop each pass ran (`filterHV` or `filterVector`, JDK-8092042).
  - 7 render-state cases: pass-0 bounds equal the clip grown by `ceil(rY)` rows. Two of them use rotated filter
    transforms (90 degrees and a 3-4-5 rotation), so an axis-only `grow(0, ceil(rY))` variant fails them.
  - 1 MotionBlur guard.
  - Every clipped render must be within one step of the cropped unclipped render **on every row**, with no edge-row
    exemption. The cut geometry keeps pass 0 on `filterHV` in both renders, so that bound holds by construction.
  - Before the fix, 85 of the 240 fail, including all 68 anisotropic and cut-source pixel cases; all 120 controls
    pass. After the fix, 240 pass. Reverting only the two code lines brings the same failures back.
- **Decora golden:** `test/com/sun/scenario/effect/**` gives 603 run, 0 failures, with `-Djfx.parity.require=true`.
  The golden was **not regenerated**, and no `DecoraCorpus` bound was changed.
  - `DecoraJavaGoldenTest`'s per-row report (343 rows) is byte-identical before and after.
  - At pixel level, the SHA-256 of every golden row's Java frame, its bounds and transform, and the 76 unclipped
    frames is identical with and without the fix. Every clipped golden row is isotropic.
- **Module suite:** `javafx.graphics` on Windows gives 25,544 run, 0 failures, 0 errors, 356 skipped, and no
  `hs_err`. That run used the test at 222 cases; the later changes are test-only and were re-run in the Decora suite.
- **Hardware check.** `Node.snapshot` with a viewport that cuts the effect's result, compared with the full snapshot
  cropped to the viewport. That is the same output-clip path a dirty-region repaint takes (`PrEffectHelper.render`).
  - Scenes: `DropShadow` and `Shadow` 3x41, and `GaussianBlur(20)` on a node scaled `scaleX = 0.1`.
  - Pipelines: D3D (AMD Radeon R7 240, D3D9Ex); ES2 over WGL on the same GPU (a privately built `prism_es2.dll`);
    ES2 over GLX on Mesa llvmpipe under Xvfb in WSL; the SW pipeline on Windows and Linux.
  - Before the fix, every pipeline shows the band: 47 to 105 steps, within 14 rows of each cut edge on the GPU.
  - After the fix, D3D and ES2 are exactly equal to the full render, and SW is within one step. D3D and ES2 are
    pixel-identical to each other and within one step of SW.
  - Unclipped renders are identical before and after on every pipeline.
  - Metal is unverified, because there is no macOS host.
- **tests/system:** a `FULL_TEST` + `USE_ROBOT` run in WSL (JDK 25, natives built from source, a private rootless
  Xvfb with no window manager), on HEAD with the fix and on HEAD itself as the control.
  - Both give 1,074 tests: 875 pass, 24 fail, 11 errors, 164 skipped. **No method's result differs** between the
    two runs, and no `hs_err` was produced.
  - Every failure is environmental and already known: WebKit (no `jfxwebkit` in WSL), iconify/deiconify with no
    window manager, launcher tests, Swing interop and `NullCCLTest`. None involves effects.
  - tests/system has no anisotropic Gaussian scene, so this run is a regression net only. The hardware check above
    is what covers the fix.
- **Behaviour change, for acceptance:**
  - Clipped Gaussians with `ceil(rX) < ceil(rY)` in filter space lose the band. That includes an isotropic
    `GaussianBlur`, `DropShadow` or `Shadow` on a node scaled non-uniformly.
  - With `rX > rY`, pixels inside the clip are unchanged on the GPU. SW is unchanged on the default path too, because
    `getInputClip` already cuts the input `ceil(rY)` rows above the clip, so pass 0 stays on `filterHV`.
  - Only a software input that was not cut that way (a `NodeEffectInput` cache hit from an earlier, larger render)
    with a clip top `ceil(rY) + 1` to `ceil(rX)` rows below the input's top moves pass 0 to `filterVector`, at most
    one more step off the unclipped render.
  - Unclipped renders, MotionBlur and every golden row are unchanged. The pass-0 intermediate grows by up to
    `2 * (ceil(rY) - ceil(rX))` rows, which is what the unclipped render computes anyway.
- **Follow-ups found on the way, not fixed here:**
  - `GaussianRenderState.getInputClip` pads the input clip by `ceil(dx0 + dx1)` and `ceil(dy0 + dy1)` without
    `Math.abs`. Under a 90, 180 or 270 degree rotation or a mirror the input clip **shrinks**; for radius 10 and a
    100x100 clip, rotate 180 gives 80x80. At 45 degrees it under-pads: 0 columns instead of 15. The MotionBlur state
    shares the method. Filed as [US-010](US-010-fix-gaussian-input-clip-sign-under-rotation-mirror.md), with a
    pixel-level reproduction; it is also an upstream report candidate.
  - `DecoraCorpus`'s `edgeRows = ceil(radius)` allowance on the isotropic "gaussian clipped" rows only covered
    finding F2. F2 was fixed in 26ce75d02f, and those rows are now within one step on every row, so the allowance
    could be tightened in a separate change.
  - On the GPU pipelines, a `Rectangle` with a `DropShadow` never reaches Decora
    (`EffectUtil.renderRectDropShadow`). That path draws an isotropic `(rX + rY) / 2` shadow, so GPU and SW differ by
    up to 111 steps for a 3x41 shadow. This is upstream behaviour and unaffected by this fix.
