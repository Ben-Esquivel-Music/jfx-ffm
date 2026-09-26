# US-010 — Pad the Gaussian input clip by absolute distances

**Status:** 📋 Ready (reproduced at pixel level; fix validated in scratch) · **Found:** 2026-09-25, while fixing US-006 (code reading, then measured)

## Story
As a JavaFX app developer blurring or shadowing a node that is rotated or mirrored (including a right-to-left scene),
I want partial repaints to render the same pixels as a full repaint,
so that dirty-region updates, viewport snapshots and scrolled clips don't leave faded bands along the clip edges.

## Problem
`GaussianRenderState.getInputClip` (`:367-382`) grows the input clip by `padx = (int) Math.ceil(dx0 + dx1)` and
`pady = (int) Math.ceil(dy0 + dy1)` (`:374-375`) **without `Math.abs`**. `FilterEffect.filter` (`:177-187`) passes that
clip to the input effect, so the input (usually `NodeEffectInput`) is rendered over it.

- **Where the signs come from.** The input actually needed is the clip grown by `|dx0| + |dx1|` and `|dy0| + |dy1|`.
  In the RenderSpace branches the sample vectors carry the filter transform's signs:
  - the 2-D constructor sets them to `(mxx, myx, mxy, myy) / scale` (`:221-231`);
  - the MotionBlur constructor sets them to the transformed blur direction (`:316-324`).
- **How the clip goes wrong.** A negative sum makes `Rectangle.grow` **shrink** the clip; opposite signs cancel and
  under-pad. The `(padx | pady) != 0` guard (`:376`) lets negative values through.
- **Worked example** (radius 10, a 100x100 clip), input clip returned:
  - identity: 120x120 (correct);
  - rotate 90 or scaleX = -1: 80x120;
  - rotate 180: 80x80;
  - rotate 45: 100x130, where 130x130 is needed.
- **Where it is fine already.** The same class's `getPassResultBounds` uses `Math.abs` throughout (`:466-486`), as do
  `MotionBlurState.getHPad/getVPad` (`:54-60`). `getInputClip` is the only site in `com.sun.scenario.effect` with the
  missing-`abs` pattern.
- **Upstream too.** The `openjdk/jfx` master copy of `getInputClip` is identical (checked 2026-09-25), so this is not a
  fork regression.

### Who is affected
- **Transforms and effects:**
  - Any mirror (`scaleX = -1`, `scaleY = -1`, and right-to-left node orientation, whose scene-root mirror gives every
    descendant `mxx < 0`), any rotation other than 0, and negative shear, for
    - `GaussianBlur`;
    - `MotionBlur`;
    - `DropShadow`, `InnerShadow` and `Shadow` **only with `BlurType.GAUSSIAN`**, including CSS `dropshadow(gaussian, ...)`.
  - `MotionBlur` is also affected **with no transform at all** when `cos(angle) < 0` or `sin(angle) < 0`. For example the
    -15 degree angle of its own javadoc example, radius 30, pads `pady = -7` where `+8` is needed.
- **Glow and Bloom:** these use an inner `GaussianBlur` and are mostly rescued by the `NodeEffectInput` cache hit on
  the node image their `Blend` renders first (code-derived).
- **Not affected:**
  - The default `THREE_PASS_BOX` shadows and `BoxBlur` (`BoxRenderState.getInputClip` grows by `klen / 2 >= 0`, and
    `BoxRenderState` forces a positive-scale input transform).
  - Kernels whose device radius exceeds `MAX_RADIUS`: 63 on desktop, 31 on embedded, by default
    (`decora.maxLinearConvolveKernelSize`). They take the scaled CustomSpace branch with sample vectors `(1, 0, 0, 1)`.
  - Identity and positive-scale transforms.
  - Unclipped renders.
  - A Gaussian nested under a CustomSpace parent effect, because the parent hands it a positive-scale transform.
  - On D3D/ES2, a `Rectangle` with a `DropShadow`, which takes the `EffectUtil.renderRectDropShadow` fast path.
- **When it shows:** only when the output clip cuts into the effect's input content on the shrunk axis:
  - a dirty-region repaint (`ViewPainter` `g.setClipRect(dirtyRect)` -> `PrEffectHelper.render` `:75`) of a clean
    blurred node, next to a dirty sibling or overlay;
  - `Node.snapshot` with a viewport;
  - a render target smaller than the node.

### Measured (2026-09-25, Windows 10, AMD Radeon R7 240)
- **Method.** A `Node.snapshot` with a viewport cutting 30 px inside a 160x160 checker `ImageView`, compared with the
  full snapshot cropped to the viewport. Radius 10, run on:
  - SW;
  - D3D9Ex;
  - ES2 over WGL (a privately built `prism_es2.dll`).
- **Two runtimes that differ in one class:**
  - the current working-tree build, which includes the US-006 fix;
  - the same build with only `getInputClip` changed to `Math.abs`, compiled with the flags that reproduce the shipped
    class byte-for-byte.

| Effect | Transform | Current: max delta / band | With `Math.abs` |
| --- | --- | --- | --- |
| `GaussianBlur(10)` | rotate 90 | 158-159, left and right edges, 0-17 px deep | GPU 0, SW ≤ 1 |
| `GaussianBlur(10)` | rotate 180 | 162, left, top, bottom (right masked, see below) | GPU 0, SW ≤ 1 |
| `GaussianBlur(10)` | scaleX = -1 / scaleY = -1 | 158-159 on the mirrored axis | GPU 0, SW ≤ 1 |
| `GaussianBlur(10)` | rotate 45 | 41, left edge, 0-6 px deep | GPU 0, SW ≤ 1 |
| `DropShadow(GAUSSIAN, 10)` | rotate 90 / 180 / 270 / mirrors | 125-126 on the affected edges | GPU 0, SW ≤ 2 |
| `MotionBlur(0, 10)` | rotate 180 / 270 / scaleX = -1 | 195-196, left or top edge | GPU 0, SW ≤ 1 |
| controls: identity (all three), `BoxBlur` under all transforms, `MotionBlur` under rotate 90 / 45 / scaleY = -1 | — | GPU 0-1, SW ≤ 2 | same |

- **Consistency.**
  - D3D and ES2 are pixel-identical to each other.
  - Full, unclipped renders are byte-identical between the two runtimes on every pipeline.
  - A diagnostic probe calling `getInputClip` on the render state the peer used returned exactly the predicted shrunk
    rectangles. For example rotate 180 gave `[40,40 80x80]` where `[20,20 120x120]` was needed.
  - Every pixel more than 3 steps off has a kernel footprint that crosses the returned clip.
- **Left/top bands are deterministic; right/bottom are often masked.** `NodeEffectInput` renders the node, unclipped,
  into an `ImagePool` texture whose size is rounded up to 32 (`ImagePool.java:110-111`) or reused from a larger image
  (`:134`). The peers sample the texture's physical size, so the slack at its right and bottom edges usually still holds
  real content, and the band on those edges is hidden fully or partly.
- **SW-only noise.** The SW `DropShadow` differences of at most 2 steps are the known `filterHV` vs `filterVector`
  rounding difference (`JSWLinearConvolvePeer:99-104`, JDK-8092042). They are identical in both runtimes and absent on
  the GPU.
- **Not measured (bounds- or code-derived only):**
  - `InnerShadow(GAUSSIAN)`: expected to show a spurious dark band inside the clip edge rather than a fade, because
    `InvertMask` works in user space and re-grows the clip.
  - `Shadow(GAUSSIAN)`, Glow, Bloom.
  - Right-to-left scenes.
  - HiDPI scales.
  - `MotionBlur` with a negative angle at identity.
  - Rotations other than multiples of 45 degrees, and shear.
  - A clip narrower than `2r` on the shrunk axis: `getInputClip` then returns a negative-width rectangle and
    `ImagePool.checkOut` substitutes a 32x32 image, so the effect loses its input inside that clip.
  - A windowed dirty-region repaint: that path is confirmed by code only.
  - Metal and Linux.

## Proposed fix
In `GaussianRenderState.getInputClip`:
```java
int padx = (int) Math.ceil(Math.abs(dx0) + Math.abs(dx1));
int pady = (int) Math.ceil(Math.abs(dy0) + Math.abs(dy1));
```
- **Bounds.** This is the exact integer bound of the two sampling segments' Minkowski sum, and it is what was measured
  above.
- **Consistency.** With it, the `MotionBlur` input pads equal `MotionBlurState.getHPad/getVPad`, which the effect already
  uses for its bounds and dirty regions. It covers the US-006 pass-0 region.
- **The per-pass form.** The alternative `ceil(|dx0|) + ceil(|dx1|)` is at most 1 px larger per side, and only
  off-axis. Neither form changes identity or axis-aligned transforms.
- **Rounding caveat, pre-existing.** For non-integer radii off-axis, the kernel taps can reach 1 px beyond either
  bound, at a weight of about 4e-4. `getPassResultBounds` has the same property.

## Acceptance criteria
- **A reproducing test first, one that fails before the fix and passes after.**
  - Render-state level: `getInputClip` for identity, rotate 90/180/270/45, both mirrors, a negative shear, and the
    `MotionBlur` constructor with a negative direction at identity. Expect the clip grown by `ceil(|dx0|+|dx1|)` and
    `ceil(|dy0|+|dy1|)`.
  - Pixel level: through the production pass protocol, with the source first cut to `getInputClip(0, clip)` as
    `FilterEffect` and `NodeEffectInput` do (see `GaussianPassClipGrowthTest.Case.sourceBounds`). Clipped vs cropped
    unclipped must be within one step.
    - `DecoraBackend.motion` already reaches the signed `MotionBlur` case at identity, e.g. direction `(-1, 0)`.
    - The mirrored/rotated 2-D cases need a `DecoraBackend` overload that takes a prebuilt `LinearConvolveRenderState`.
    - Assert on the left/top edges, or control the source slack, so pool rounding cannot hide the band.
- The fix as proposed. `DecoraJavaGoldenTest` stays green, and its per-row report is byte-identical before and after.
  `DecoraBackend` never applies `getInputClip`, so no golden row is expected to change.
- Hardware check on D3D and ES2 (and SW) for rotated and mirrored `GaussianBlur`, `DropShadow(GAUSSIAN)` and
  `MotionBlur`: viewport vs cropped full snapshot, before and after. Add `InnerShadow(GAUSSIAN)` to confirm its
  expected symptom.

## Notes
- Found during US-006: a manager observation, confirmed at bounds level by its implementer, at code level by its
  reviewer, and at pixel level by a follow-up measurement with three independent skeptic checks.
- Also present in `openjdk/jfx`; a candidate for an upstream JBS report once fixed here.
- The right-to-left case makes this visible to any RTL application that uses a Gaussian effect, whenever a partial
  repaint cuts the effect's result.
