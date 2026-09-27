# US-014 — Step the software linear convolution along the right destination axis for rotated inputs

**Status:** 📋 Ready (reproduced on SW against D3D and ES2; cause identified, a two-line swap validated in scratch) · **Found:** 2026-09-26, hardware check of US-010 (SW `InnerShadow(GAUSSIAN)` under rotation)

## Story
As a JavaFX app developer whose app runs on the software pipeline (no usable GPU, `-Dprism.order=sw`, or the SW
fallback),
I want a Gaussian blur or shadow over a rotated input to look the same as on D3D/ES2,
so that an `InnerShadow(GAUSSIAN)` on a rotated node, or a Gaussian effect chained onto a reflection, displacement or
box blur of a rotated node, is not smeared or displaced.

## Problem
When a pass input carries any transform other than a translation, `JSWLinearConvolvePeer.filter` (`:141-145`) takes
the 8-coordinate branch of `EffectPeer.getTextureCoordinates` and derives the source step per destination column and
per destination row:
```java
dxcol = (srcRect[4] - srcRect[0]) * srcw / dstBounds.width;
dycol = (srcRect[5] - srcRect[1]) * srch / dstBounds.height;   // should divide by dstBounds.width
dxrow = (srcRect[6] - srcRect[0]) * srcw / dstBounds.width;    // should divide by dstBounds.height
dyrow = (srcRect[7] - srcRect[1]) * srch / dstBounds.height;
```
- **The corner table.** The `getTextureCoordinates` javadoc (`EffectPeer.java:266-275`) maps `(dx2, dy1)` to
  `ret[4..5]` and `(dx1, dy2)` to `ret[6..7]`. `ret[4..5] - ret[0..1]` therefore spans the destination **width**, and
  `ret[6..7] - ret[0..1]` the destination **height**. `dycol` and `dxrow` divide by the other dimension.
- **When it bites.** For scales, mirrors and 180-degree rotations the two wrong terms are zero whatever their
  denominator. They are not zero, and the destination's aspect ratio scales them wrongly, whenever the transform has a
  non-zero `mxy` or `myx` (a rotation other than a multiple of 180 degrees, or a shear) AND the pass destination is not
  square.
- **The shadow peer too.** `JSWLinearConvolveShadowPeer` extends `JSWLinearConvolvePeer` and runs the same code.
- **Only this peer.** `JSWLinearConvolvePeer` is the only SW reader of `srcRect[4..7]`:
  - The generated SW peers (`JSWBackend:182-183`, `:196`) use `float[4]` coordinates with an identity transform for
    ordinary samplers.
  - The two `LSAMPLER` peers (`DisplacementMap`, `PerspectiveTransform`) override `getTextureCoordinates` to return 4.
  - The GPU peers (`PPSLinearConvolve(Shadow)Peer` through `PPSOneSamplerPeer:120-129`) pass all four corners to the
    shader.
- **Upstream too.** The lines date from the 2013 import (`b4d6d9772fb`). The native `SSELinearConvolvePeer` this fork
  deleted had identical lines (`26ce75d02f^`, `:135-136`), so `openjdk/jfx` has the defect on its SW pipeline as well.

### Who is affected
- **Needs an input that returns its result with the rotation still in its transform.** A `GaussianRenderState` in
  RenderSpace asks its input for the filter transform. Some inputs hand the Gaussian a rotated `ImageData`:
  - inputs that render in user space: `InvertMask`, `Reflection` and `DisplacementMap`, whose result transform is the
    filter transform;
  - inputs that render in a scaled custom space: `BoxRenderState` under any rotation, and `GaussianRenderState`
    beyond `MAX_RADIUS`, whose result transform is the filter transform without its scale.
  - Device-space results stay rectilinear: `NodeEffectInput`, generated peers, `Merge`, `Blend` and
    `PerspectiveTransform` (its `filter` override returns its peer's device-space result).
  - Measured: `InnerShadow(BlurType.GAUSSIAN, ...)` on a node rotated by 90, 270 or 45 degrees.
  - Code-derived, not measured: `GaussianBlur`, `MotionBlur` or `Shadow(GAUSSIAN)` on a rotated or sheared node, whose
    input is one of:
    - a `Reflection` or a `DisplacementMap`;
    - a `BoxBlur` or a box-type `Shadow`;
    - a `GaussianBlur`/`MotionBlur` whose device radius exceeds `MAX_RADIUS`.
- **Not affected:**
  - the D3D and ES2 peers;
  - `BoxBlur` and the default `THREE_PASS_BOX` shadows themselves (`BoxRenderState` asks for a positive axis-aligned
    scale, so a box pass input is at most scaled);
  - `DropShadow` and `InnerShadow` as the input of another effect (their `Merge`/`Blend` return device space);
  - translations, scales, mirrors and 180-degree rotations;
  - square pass destinations.

### Measured (2026-09-26, Windows 10, AMD Radeon R7 240; `Node.snapshot`, public API)
- **Scene:** a 160x160 checker `ImageView` with `InnerShadow(BlurType.GAUSSIAN, 10)`, rotated about its centre.
  Full snapshot on SW, D3D9Ex and ES2 over WGL.
- **SW is not rotation-consistent:** the rotate-90 and rotate-270 full renders differ from the identity render rotated
  by up to 156 steps on 12,104 of 25,600 pixels. On D3D the same comparison differs on 0 pixels. SW vs D3D at
  rotate 90: up to 156.
- **With the two denominators swapped** (a diagnostic build of the peer only):
  - SW matches D3D within 2 steps and is rotation-consistent to 0 pixels;
  - every rotate-90/270 viewport-vs-full comparison of the same check drops to 0.
- **Residual after the swap, not investigated.** At rotate 45, SW still differs by up to 29 steps on the top viewport
  cut, 26 on a narrow cut and 2 elsewhere, with kernel footprints inside the clip. Find its cause before closing this
  story; it may be a second defect in the same branch.
- **Independent of US-010.** The US-010 fix (`GaussianRenderState.getInputClip`) neither causes nor changes this: SW
  full renders are byte-identical with and without it.

## Proposed fix
In `JSWLinearConvolvePeer.filter`, 8-coordinate branch:
```java
dycol = (srcRect[5] - srcRect[1]) * srch / dstBounds.width;
dxrow = (srcRect[6] - srcRect[0]) * srcw / dstBounds.height;
```
The 4-coordinate branch is correct as it is (`dycol = dxrow = 0`).

## Acceptance criteria
- **A reproducing test first,** failing before the fix and passing after, on `DecoraBackend.convolve` with an input
  `ImageData` whose transform has a non-zero `mxy`/`myx`, into a non-square destination:
  - **Step oracle, every rotation.** A recording `JSWLinearConvolvePeer` / `JSWLinearConvolveShadowPeer` subclass
    asserts the `(dxcol, dycol, dxrow, dyrow)` handed to `filterVector`. They must equal the inverse-transformed
    destination unit steps times the source size, which is exact up to float rounding. Cover a quarter turn, the
    3-4-5 rotation and a shear.
  - **Pixel oracle, quarter turns only,** where the pre-rotation is a pixel permutation: the kernel over the rotated
    input vs the same kernel over the pre-rotated input with an identity transform, within one step (or a documented
    `filterVector` rounding bound).
- The rotate-45 residual above explained, and fixed here or filed separately.
- `DecoraJavaGoldenTest` stays green, with its per-row report byte-identical (its corpus inputs are translate-only).
- **Hardware check** on SW against D3D/ES2, full and viewport snapshots:
  - `InnerShadow(GAUSSIAN)` under rotate 90/270/45;
  - `GaussianBlur(10)` with input `BoxBlur(10, 10, 1)` on a node rotated by 90;
  - `GaussianBlur` over a `Reflection` input on a rotated node.

## Notes
- Found by the US-010 hardware check. The manager confirmed it at code level against the `getTextureCoordinates`
  corner table, and the US-010 reviewer re-traced it and corrected the affected-input list.
- A candidate for an upstream JBS report, like US-010 to US-013.
