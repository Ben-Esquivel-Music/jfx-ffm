# US-059 — Fix the remaining software texture-sampling differences from D3D/ES2

**Status:** 📋 Ready (filed 2026-10-03 from US-013; part 1 reproduced with public API on SW against D3D and ES2, parts
2 and 3 read from the code) · **Found:** 2026-10-03, while implementing and hardware-checking US-013

## Story
As a JavaFX app developer whose app runs on the software pipeline,
I want images drawn without smoothing, or scaled down, to pick the same texels and edges as on D3D/ES2,
so that pixel art and minified images neither shift, nor lose a row or column, nor change their edge with the
transform.

## Problem

### 1. Nearest-neighbour samples the pixel corner (measured)
`renderer_setTexture` (`PiscesRenderer.inl`) adds the half-pixel shift to `_texture_m02`/`_texture_m12` only for
linear filtering. So `genTexturePaintTarget` samples a nearest-neighbour pixel at its top-left corner; a GPU samples
the centre.
- **Axis-aligned at a non-integer scale**, SW picks the texel one lower in every column or row where the corner and
  the centre fall in different texels. The 16.16 step adds to this: at s = 1.5, `m00 = 1/1.5` truncates to 0.666656,
  so the corner of x = 13 maps to u = 1.99997 (texel 1) where the centre, 2.333, gives texel 2.
- **Under a rotation (`TEXTURE_TRANSFORM_GENERIC`)**, the first device column is transparent at every scale: its
  corner maps to v = H, so `isInBoundsNoRepeat` paints 0. At s = 1, an exact pixel-aligned transform, SW draws image
  rows 4..1 into x = 36..39, leaves x = 35 transparent and never draws row 0. The whole image moves by one pixel.

Measured 2026-10-03 (Windows 10, AMD Radeon R7 240, `Node.snapshot`). A 7x5 image, one distinct opaque colour per
texel, drawn with `Canvas.drawImage(img, x, y, 7s, 5s)` and `setImageSmoothing(false)`, axis-aligned and under
`rotate(90)`. Identical before and after US-013. ES2 = D3D.

| scene | px opaque on both / SW picks another texel / of those, exact centre ties | SW transparent where D3D is opaque |
| --- | --- | --- |
| axis s1, s2 | 35 / 0, 140 / 0 | 0 |
| axis s1.5 / s2.5 | 70 / 43 / 15, 204 / 83 / 25 | 0 |
| rot90 s1 / s1.5 | 28 / 28 / 0, 70 / 50 / 21 | 7, 10 |
| rot90 s2 / s2.5 | 126 / 56 / 0, 204 / 93 / 35 | 14, 17 |

At exact centre ties the GPU itself is inconsistent: its choice depends on which of the quad's two triangles the
pixel lies in.

### 2. `ImageView.setSmooth(false)` does nothing
`NGImageView.setSmooth(boolean s) {}` carries the comment "JDK-8127343: this method does nothing", so every
`ImageView` is linear-filtered on every pipeline. The US-012 and US-013 probes show `smooth = false` and `true`
snapshots identical on SW, D3D and ES2. This is upstream behaviour. Making it work would change pixels for apps that
set it today. Only `Canvas` with `setImageSmoothing(false)` reaches the nearest path of part 1.

### 3. Minified edges depend on the transform branch (read, not measured)
For a pixel whose sample falls more than one texel outside the drawn source (a partially covered edge pixel when
minifying, or a sub-rectangle's edge):
- `TRANSLATE`/`SCALE_TRANSLATE` clamp it (`checkBoundsNoRepeat`) and paint the edge texel;
- `GENERIC` paints `0x00000000` (`isInBoundsNoRepeat`).
So a clamp-to-edge image scaled to 0.5 gets an edge-coloured fringe pixel when axis-aligned and a transparent one
under a 90-degree rotation. US-013 made the two agree for clamp-to-zero textures only.

## Proposed fix
- **Part 1.** Add the half-pixel shift for nearest sampling too: `m02 += (m00 >> 1) + (m01 >> 1)`, the same for
  `m12`, without the bilinear `- 32768`. Then recheck the bounding (nearest never needs `txMin - 1`) and the
  IDENTITY/TRANSLATE shortcut, which turns interpolation off for integer translations.
- **Part 3.** Measure SW `TRANSLATE`/`SCALE` against `GENERIC` at scale 0.5 and 0.3 first, then pick one rule for
  every branch.
- **Part 2.** Decide after part 1: wire `smooth` through `NGImageView` (with a release note), or keep the no-op and
  document it.

## Acceptance criteria
- A unit test in the style of `PiscesTexturePaintEdgeTest` draws a distinct-colour texture nearest-neighbour at
  s = 1.5 and 2.5 and under a 90-degree rotation at s = 1 and 2. Every pixel's texel equals the texel under its
  centre, computed independently, with ties excluded. The rotated s = 1 case draws every texel exactly once. The test
  fails before the fix.
- A minified draw (scale 0.5) gives the same fringe pixels through `SCALE_TRANSLATE` and `GENERIC` under the chosen
  rule.
- Linear-filtered output is unchanged (`PiscesTexturePaintEdgeTest`). A `PiscesGoldenRenderTest` step that moves is
  re-captured in its own commit with the reason, as US-013 did.
- Hardware re-check: SW matches D3D on the probe above except at exact ties.

## Notes
- **Related hardening, not reproduced.** `psw_renderer_draw_image` samples the caller's array in place but never
  checks `tx_min..tx_max`/`ty_min..ty_max` against `w`/`h`, nor `w > 0`, `stride` or `offset` (it has no
  `data_len`). `psw_renderer_set_texture` does check. Today's callers stay inside the texture: `SWGraphics.drawTexture`
  clamps to the content size. Add a check in `PiscesNative`/`PiscesRenderer.drawImage` while this code is open.
- Related: US-013 (the bilinear edge rule and the out-of-bounds read), US-046 (the Java port must reproduce the
  fixed behaviour).
