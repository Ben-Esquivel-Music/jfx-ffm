# US-006 — Grow the Gaussian pass-0 clip by the vertical radius

**Status:** ✅ Done (2026-09-25, PR #15) · **Found:** 2026-09-13, impact assessment for the `decora_sse` deletion (code reading, finding B2) · **Deferred from:** the `decora_sse` deletion

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

## Resolution (2026-09-25, PR #15)
- **Reproduced.** Before the fix, a Gaussian with `ceil(rX) < ceil(rY)` rendered through a clip that cuts its top or
  bottom differs from the unclipped render cropped to the clip by 11 to 110 steps per channel, in the
  `ceil(rY) - ceil(rX)` rows next to each cut edge: pass 0 had kept only `ceil(rX)` rows beyond the clip.
- **Fix:** `GaussianRenderState.getPassResultBounds`, pass-0 branch, grows the clip along the pass-1 sample vector by
  `inputRadiusY` instead of by `inputRadiusX`. The Java peers (`JSWLinearConvolve[Shadow]Peer`) and the GPU peers
  (`PPSLinearConvolve[Shadow]Peer`) both take their pass bounds from this method, so this one change fixes both.
- **Tests:** new `test.com.sun.scenario.effect.GaussianPassClipGrowthTest`, 240 cases through the production pass
  protocol (`DecoraBackend`). Before the fix 85 of the 240 fail; after it all 240 pass.
- **Decora golden:** not regenerated; `DecoraJavaGoldenTest`'s per-row report (343 rows) is byte-identical before and
  after.
- **Hardware check:** `Node.snapshot` with a viewport, compared with the full snapshot cropped to it, on D3D, ES2 over
  WGL, ES2 over GLX (Mesa llvmpipe in WSL) and SW. Before the fix every pipeline shows the band (47 to 105 steps);
  after it D3D and ES2 equal the full render and SW is within one step. Metal is unverified, because there is no
  macOS host.
- **Follow-up filed:** [US-010](US-010-fix-gaussian-input-clip-sign-under-rotation-mirror.md), the Gaussian input clip
  padded by signed sample distances.
- **Record:** commit d69ce9ad5c changes `GaussianRenderState.java` and adds `GaussianPassClipGrowthTest.java`; the
  full resolution notes are the body of PR #15. That commit also removed this file under the backlog's former rule;
  it was restored when done stories became part of the record.
