# US-013 — Fix the first texel row and column of the SW texture paint: wrong interpolation, out-of-bounds read

**Status:** 📋 Ready (reproduced with public API on SW against D3D and ES2; cause identified in the native Pisces paint) · **Found:** 2026-09-26, hardware check of US-012 (the SW edge-column observation in its Notes); out-of-bounds read found by the US-012 review

## Story
As a JavaFX app developer whose app runs on the software pipeline (no usable GPU, `-Dprism.order=sw`, or the SW
fallback),
I want a scaled or sub-pixel-positioned image to get the same left and top edge as its right and bottom edge, and
never to pick up pixels from outside the image,
so that images, canvases and effect results look the same as on D3D/ES2, are not smeared one texel toward the
top-left, and a stretched one-pixel-tall or one-pixel-wide image does not show garbage.

## Problem
`genTexturePaintTarget` in `native-prism-sw/PiscesPaint.c` samples the texture at `u = (x + 0.5) * m00 - 0.5` for
linear filtering (the half-texel shift is added in `PiscesRenderer.inl:354-355`). If the first device column maps left
of the first texel centre (`u < 0`), then `tx = ltx >> 16 = -1` and `hfrac = ltx & 0xffff` is the weight for the
texel pair (-1, 0).

- The bounds helpers keep `tx = -1` on purpose. `checkBoundsNoRepeat(&tx, &ltx, txMin-1, txMax)` clamps to
  `[txMin - 1, txMax]` in `TEXTURE_TRANSFORM_TRANSLATE` and `TEXTURE_TRANSFORM_SCALE_TRANSLATE`.
  `TEXTURE_TRANSFORM_GENERIC` uses `isInBoundsNoRepeat` (`:924-926`, `:997-999`) instead: it accepts `tx = -1` the
  same way and draws transparent for `tx < -1`.
- `sidx = MAX(0, ty) * txtStride + MAX(0, tx)` then points at texel 0.
- `getPointsToInterpolate` (`:388-396`) takes `data[sidx + 1]`, texel 1, as the right neighbour, and
  `data[sidx + stride]`, row 1, as the lower one.

### Wrong interpolation
The pixel is interpolated between texels 0 and 1 with the weights meant for -1 and 0. It gets the value that belongs
one texel further in: at scale 2 the first device column repeats the third one. `ty = -1` does the same to the first
row.

### Out-of-bounds read
The same path reads past the end of the texture array when the texture is one texel tall or wide.
`getPointsToInterpolate` bounds its neighbours by `txtWidth - 1` / `txtHeight - 1`, and `tx = -1 < 0` and
`ty = -1 < 0` both pass.
- **`ty = -1` on a one-row texture.** `sidx2 = sidx + stride` is row 1, which does not exist. For a `W x 1` image the
  read goes up to `W` ints past the end.
- **`tx = -1` on a one-column texture.** `data[sidx + 1]` is the next row's texel, and on the last row it is one past
  the end.
- **Where the read lands.** SW textures are exact-size (`SWArgbPreTexture.allocateBuffer`,
  `new int[physicalWidth * physicalHeight]`). The array is handed to C as a heap segment
  (`PiscesNative.rendererDrawImage`, `MemorySegment.ofArray(data)`), so the read lands in whatever follows the array
  on the Java heap.
- **Effect.** It is a read, not a write, so the heap is not corrupted. It is still undefined behaviour in native code,
  and it puts foreign colours into the top rows or left columns of any stretched one-pixel-tall or one-pixel-wide
  image on the SW pipeline.
- **REPEAT branches.** `getPointsToInterpolateRepeat` (`:398-406`) has the same `sidx + stride`.

### Right and bottom edge
`getPointsToInterpolate` compares against `txtWidth - 1` and `txtHeight - 1`, the texture's content size, not the drawn
`txMax`/`tyMax`. `SWGraphics.drawTexture` passes `tex.getContentWidth()` (`SWGraphics.java:804`), which for a pooled
Decora texture is the pool's quantised size (`ImagePool.checkOut`).
- An exact-size texture therefore clamps to its edge (`pts = p00`).
- A pooled texture that is larger than its content reads the texel beyond the content, which is transparent because
  the pool clears the image on checkout.
- So the right/bottom rule depends on how the texture was allocated, and the left/top rule is neither of them.
- In the US-012 probe, `ii-s2x15-bb0x9x3` on SW ends at 255 (clamp) before the US-012 fix and at 188 = 0.75 x 251
  (zero beyond) after it, because the result texture was allocated differently.

### Sites
- The interpolating NO_REPEAT branches of `TEXTURE_TRANSFORM_TRANSLATE` (`:522-601`),
  `TEXTURE_TRANSFORM_SCALE_TRANSLATE` (`:702-799`) and `TEXTURE_TRANSFORM_GENERIC` (`:917-1030`), with and without
  alpha. Each range also contains the REPEAT branch between them.
- The REPEAT branches use the same `MAX(0, tx)` index and the same `sidx + stride`. They were not measured.

### Fix direction
- **Left and top.** When `tx == -1` (possible only when `txMin == 0`), use texel 0 for both `p00` and `pts[0]`: clamp
  to edge, as D3D/ES2 do for image textures. When `ty == -1`, use row 0 for both rows. This removes the read of
  texel 1 and row 1, so it fixes the out-of-bounds read too.
- **Sub-rectangles.** When `txMin > 0` (a sub-rectangle), `txMin - 1` is a real texel, so the current pair is correct
  there and must not change.
- **Right and bottom.** Pick one rule for pooled intermediates instead of the allocation-dependent one. D3D uses
  CLAMP_TO_ZERO for Decora textures. One way is to bound the neighbours by the drawn content (`txMax`/`tyMax`) where
  that content ends. Keep the behaviour of sub-rectangle draws: a GPU samples the neighbouring texels there too.
- **REPEAT.** Check the REPEAT twins with the same cases.

### Upstream too
`openjdk/jfx` master has the same lines (checked 2026-09-26). The code dates from 2013 (`839a0d8349`, `f991f09f155`,
`0b789ec687d` and neighbours). The fork changed only this file's includes (#12), so neither defect is a fork
regression. Upstream hands the array over through JNI, and the read goes past its end there too.

### Who is affected
- **The software pipeline only.** Every linear-filtered texture draw whose first device column or row samples left
  of or above the first texel centre of a texture that starts at texel 0:
  - `ImageView` at a scale > 1. `ImageView.setSmooth(false)` does not help: `NGImageView.setSmooth` is a no-op
    (`NGImageView.java:174`), so every `ImageView` is linear-filtered on every pipeline.
  - `ImageView` at a sub-pixel position.
  - `Canvas.drawImage` scaled.
  - Partially covered edges at a scale < 1.
  - Decora results drawn under a scale. Since US-007, that includes a scaled `ImageInput` under `BoxBlur` and the box
    shadows, and a HiDPI render scale on SW.
- **Visible when texel 0 and texel 1 differ:** transparent or antialiased borders, 1-px frames, edge colour stripes,
  and blurred edges. The alpha centroid moves up-left.
- **Out-of-bounds read:** any such draw of an image one texel tall or wide, e.g. a 1-px line or gradient strip
  stretched by an `ImageView` scale or `fitWidth`/`fitHeight`.
- **Not affected:** D3D and ES2 (measured), unscaled integer-aligned draws, and nearest-neighbour texture paints.

### Measured (2026-09-26, Windows 10, AMD Radeon R7 240; `Node.snapshot`, public API)
Minimal repro, `EdgeRepro.java`: a 16x10 `WritableImage` with a 1-px transparent border and an opaque grey
interior, in an `ImageView` with `setScaleX(2)` / `setScaleY(2)`, captured by a default `Node.snapshot` with a
transparent fill. The snapshot is 32x20.

| pipeline | middle row alpha (the middle column is the same) |
| --- | --- |
| SW | **191** 64 191 255 … 255 191 64 0 |
| D3D (D3D9Ex) and ES2 (WGL) | **0** 64 191 255 … 255 191 64 0 |

`EdgeProbe.java` draws the same kind of image four ways: `ImageView`, `ImageView` with `smooth = false`,
`Canvas.drawImage`, and an `ImageInput` set as the node's effect.
- The border-image and stripe rows are identical for all four ways on each pipeline. The scale-3 `Canvas` is clipped
  by its own bounds and is left out.
- The two ramp rows show that D3D uses a different edge rule for image textures and for effect textures.

| case | SW | D3D = ES2 |
| --- | --- | --- |
| border image, scale 2 | first column 191, alpha 481.5, centroid (-0.40, -0.43) px | first column 0, alpha 448.0, centroid exact |
| border image, scale 1.5 | extra first column 212, centroid (-0.44, -0.15) | exact |
| border image, scale 3 | extra columns 170, 255 (fixed-point `u = -ε` gives `tx = -1` with frac ≈ 1), centroid (-0.87, -0.90) | exact |
| border image, scale 1, translate (0.25, 0.25) | extra left pixel 143 (= 0.75 coverage x 191), centroid (-0.30, -0.31) | exact |
| border image, scale 0.5 | extra half-covered top row alpha 64 | none |
| opaque red / blue / green columns, scale 2 | first column 25 % red + 75 % blue | pure red |
| alpha ramp 64 / 160 / 255, scale 2, `ImageInput` effect | first column 136 (= 0.25 x 64 + 0.75 x 160) | 48 (= 0.75 x 64, CLAMP_TO_ZERO) |
| alpha ramp, scale 2, `ImageView` | 136 | 64 (clamp to edge) |

In the US-012 `ImageInput` scenes, SW `BoxBlur(5, 5, 1)` at s = 2 gives:

| | first column (left edge) | last column (right edge) | centroid shift |
| --- | --- | --- | --- |
| before the US-012 fix | 149 106 149 191 234 | 64 106 149 191 234 | -0.17 px |
| after the US-012 fix | 89 64 89 115 | 38 64 89 115 | -0.11 / -0.12 px |

D3D is symmetric in both cases. Mirroring the SW first column and row from the last ones removes the whole
difference. It also removes +2.8 / +2.0 px² from that scene's measured kernel variance, and the SW edge offsets of
the nop-axis controls become the GPU's (-5.12 / -3.12 against -5.14 / -3.14 px²).

A `DropShadow(THREE_PASS_BOX)` over the same scaled `ImageInput` draws its content with opaque edges and corners on
SW, where D3D blends a 0.75-alpha edge texel over the shadow. The maximum SW-vs-D3D difference is 105 (`fff0f0f0`
against `c88795bf` at the bottom-right corner), before and after the US-012 fix.

Out-of-bounds read, `OobProbe.java`: single-colour images, every texel `0xFF00FF00` (opaque green), in an
`ImageView` scaled by 4 on one or both axes, captured by `Node.snapshot`. The result is the same under SerialGC and
under ParallelGC with `-Xmx64m`.

| image, scale | SW | D3D |
| --- | --- | --- |
| 4x1, scaleY 4, rows 0 and 1 | `40004000 40004000 40066804 56104431` / `40004001 40004000 4012b90c 84324c92` | `ff00ff00` x 4 |
| 1x4, scaleX 4, column 1, last row | `40004001` | `ff00ff00` |
| 1x1, scale 4, row 1 | `10001000 10001001 40004000 40004000 20002000` | `ff00ff00` |
| 4x2, scaleY 4 (control) | all `ff00ff00` | all `ff00ff00` |

Interpolating green texels cannot produce red or blue. `84324c92` is not even valid premultiplied colour: blue 0x92
is greater than alpha 0x84. These are bytes of whatever follows the array. Because the garbage depends on the heap
layout, the test asserts "no foreign colour", not particular values.

## Acceptance criteria
- A headless SW test (`-Dprism.order=sw`, `Node.snapshot`) fails before the fix and passes after, for the border
  image at scale 2, 1.5 and 3 and at translate (0.25, 0.25). It includes one `ImageView` case and one
  `Canvas.drawImage` case.
  - At the pure scales, each first device column and row equals the mirrored last one, within 1 per channel at
    scale 3, where the 16.16 fixed-point `m00` is not exactly 1/3.
  - At translate (0.25, 0.25) the first and last columns differ in coverage (0.75 and 0.25) and sample points
    (u = -0.25 and 15.75), so a mirror does not apply. That case asserts the alpha centroid and a comparison with the
    D3D values above instead.
  - In every case, the alpha centroid is the geometric centre within 0.01 px.
- **Out-of-bounds read.** On SW, the 4x1, 1x4 and 1x1 single-colour images at scale 4 produce only premultiplied
  multiples of the image colour: for a green image, red and blue are 0 and green equals alpha. The fully covered rows
  and columns are exactly the image colour. This test fails before the fix, in a heap-dependent way, and passes
  after.
- The colour-stripe image at scale 2: the SW first column is the edge colour, within 1 of D3D.
- **Decide and record the edge rule per texture kind.** Use clamp to edge for image and canvas textures, as D3D/ES2
  do. For Decora intermediates, D3D uses CLAMP_TO_ZERO and SW currently depends on the allocation. Make the
  right/bottom rule independent of pooled texture size.
- A sub-rectangle draw (`txMin > 0`, e.g. an `ImageView` viewport or a 9-slice border) is unchanged by the fix.
- The REPEAT branches are checked with the same cases, and fixed if they read the wrong texel or out of bounds.
- The robot/paint tests on SW (`tests/system` painttest with `-Dprism.order=sw`) and `DecoraJavaGoldenTest` stay
  green. `DecoraJavaGoldenTest` runs the Java peers on heap images and does not use this paint. **Never regenerate a
  golden.**
- Hardware re-check: rerun `EdgeProbe` and `OobProbe` on SW, D3D and ES2. SW matches D3D within 1 per channel on the
  `ImageView` and `Canvas` scenes.

## Notes
- The evidence is in the Claude scratchpad of session 053dac63:
  - `us012/hw`: `EdgeRepro.java`, `EdgeProbe.java`, `edge-{sw,d3d,es2}/`, `edge-summary.txt`, `symedge.pl`.
  - `us012/reviewC/oob`: `OobProbe.java`, `oob-run1.txt`, `oob-run2.txt`.
  - The US-012 hardware report has the context.
- The scratchpad is not durable. The measured sections above describe each repro fully enough to rebuild it.
- Found while checking US-012; independent of it. The artefact and the out-of-bounds read are present before and
  after the US-012 fix, without any Decora filter.
- Related:
  - US-007 exposed this on the Decora path by drawing SW box results at their scale.
  - The fringe crop is a separate, symmetric effect. On D3D/ES2 the first `LinearConvolve` pass crops the half-texel
    bilinear fringe of a scaled `ImageInput` across its pass direction (-3.1 px² vertical at s = 2). It needs no
    change here.
- If the prism_sw paint is later ported to Java, the port has to reproduce the fixed behaviour, not this bug.
