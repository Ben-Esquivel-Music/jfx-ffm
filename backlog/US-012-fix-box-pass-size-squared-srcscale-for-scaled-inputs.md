# US-012 — Scale the box pass size by the input scale once, not twice

**Status:** 📋 Ready (reproduced on D3D/ES2/SW; fix identified) · **Found:** 2026-09-26, review of the software BoxBlur transform fix (scaled `ImageInput` follow-up)

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
    returns a device-space result with an identity transform.
  - **SW:** both passes have the wrong extent. Pass 1 also sees the scaled result, because `JSWBoxBlurPeer` and
    `JSWBoxShadowPeer` carry the input transform on to it.
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
hardware; by the same arithmetic its box is too long. The probe program, the runtimes and the dumps are in the Claude scratchpad of session
84434bd4 (`us007/hw`, `BoxBlurScaledVisual.java`, `sc-table.md`).

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
