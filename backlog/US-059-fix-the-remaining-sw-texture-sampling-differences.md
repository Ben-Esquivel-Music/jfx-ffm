# US-059 — Fix the remaining software texture-sampling differences from D3D/ES2

**Status:** ✅ Done (2026-10-16, uncommitted) · **Found:** 2026-10-03, while implementing and hardware-checking US-013

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

## Resolution (2026-10-16)
- **Part 1 (decision and fix): nearest-neighbour sampling takes the texel under the pixel centre.**
  - `renderer_setTexture` (`PiscesRenderer.inl`) now adds the half-pixel shift for nearest sampling too:
    `m02 += (m00 >> 1) + (m01 >> 1)` and `m12 += (m10 >> 1) + (m11 >> 1)`, without the bilinear `- 32768`. The two
    bilinear lines are unchanged.
  - The IDENTITY/TRANSLATE shortcut stays as it is. A nearest draw at an integer translation now has the fraction
    0x8000 and classifies as TRANSLATE rather than IDENTITY. IDENTITY falls through to TRANSLATE anyway, and
    floor(x + k + 1/2) = x + k picks the same texels, so the output is identical.
  - The `TRANSLATE` row clamp follows the effective filter (`_texture_interpolate`), which that shortcut turns off
    for a smooth draw at an integer translation. Such a draw now also clamps a box row above the sub-rectangle to
    `tyMin` rather than `tyMin - 1`, as a nearest draw does: a change to smooth-requested output. `SWGraphics` and
    `SWPaint` cannot reach it, because under an integer translation their box maps onto `tyMin..tyMax`.
  - Nearest sampling without REPEAT clamps the texel to the drawn sub-rectangle in every transform branch. It never
    needs `txMin - 1`; bilinear keeps it. The bounds are `[MAX(0, MIN(txMin, txMax)), MAX(0, txMax)]`, and the same
    for y. The `MIN` covers a source of zero width at the right edge: `Canvas.drawImage(img, W, 0, 0, h, ...)` with
    smoothing off reaches `SWGraphics.drawTexture` with `txMin = W`, `txMax = W - 1` and a non-empty box. A literal
    `[txMin, txMax]` clamp would read texel W, which is the next row, or past the array on the last row. With the
    `MIN` the draw reads texel `txMax`, as before.
- **Part 3 (decision): one rule per filter kind, with a band in `GENERIC`. The story's premise was wrong.**
  - **Corrected premise.** The story and the first plan assumed that a pixel outside the drawn quad has no coverage,
    so that its paint value does not matter. That holds for `TRANSLATE`, `SCALE_TRANSLATE` and multiples of 90
    degrees only. Under any other rotation, or a shear, `SWGraphics.drawTexture` fills the axis-aligned bounding box
    of the quad (`dstBBox = tx.transform(srcBBox)`) at full coverage. The transparent fallback of `GENERIC`
    (`isInBoundsNoRepeat`) is what clipped a rotated image to its quad on SW.
  - Clamping everywhere, as the story proposed, would paint those box corners with edge texels. A prototype did:
    - at scale 4, rotated by 30, 10 and 45 degrees, with clamp to edge and either filter, it painted 466, 147 and
      530 pixels whose centre lies more than 1 px outside the quad, against 87, 69 and 80 for HEAD's bilinear draw
      (its half-texel band);
    - the whole box of golden steps 16, 17 and 19 (525, 525 and 500 pixels), where HEAD paints none.
  - **Rule.** `GENERIC` without REPEAT samples a pixel, clamped as the other branches clamp, only while the
    texture coordinate of the pixel centre lies within bu = 1/2 texel of the drawn sub-rectangle in u, and within
    bv = 1/2 texel in v. Further out the pixel is transparent, as before. The test is two comparisons per axis in
    16.16 (`uLo <= ltx < uHi`). With bu = 1/2 the bilinear test is HEAD's footprint bound `txMin - 1..txMax`
    exactly, nearest sampling gets the same band around the pixel centre, and a zero-width source keeps a band one
    texel wide. Quarter turns and mirror diagonals, the `GENERIC` transforms whose device-to-texture matrix has
    `m00 == m11 == 0`, are the only ones whose box is the quad. There the band widens to half the pixel's extent in
    texture space, at least half a texel: bu = max(1/2, |du/dy| / 2) and bv = max(1/2, |dv/dx| / 2).
  - **At multiples of 90 degrees** the band holds every pixel that touches the quad, so on those pixels the rule
    equals the clamp rule of `TRANSLATE` and `SCALE_TRANSLATE`, which is the story's acceptance criterion. A C
    harness ran 800 scenarios: quarter turns and mirror diagonals at scales 0.3, 0.5, 0.7, 1 and 2, five fractional
    placements, both clamp modes, both filters, whole textures and sub-rectangles, each with an exact box. The band
    build was byte-identical to the clamp-everywhere build in all 800, and HEAD differed in 472.
    - The blitter can paint a pixel that does not touch the quad: it paints a box edge 1/65536 px past a pixel
      boundary as an interior column, at the row's coverage (`PiscesBlit.c`, the same at HEAD, filed as US-062).
      The review's probe compared mirror diagonals with their axis-aligned twins: 16 of 5184 cases differ, all
      nearest at scale 0.7 with X = 60.3, where `SCALE_TRANSLATE` clamps such a pixel and `GENERIC` leaves it
      transparent.
  - **At 10, 30 and 45 degrees** the band is HEAD's bilinear bound, so the box corners are clipped as at HEAD and
    linear-filtered output is byte-identical to HEAD (runs of the round-4 C build):
    - a 7x5 ramp at 10, 30 and 45 degrees, scales 0.5, 1, 1.5 and 4, both clamp modes: 24 of 24 linear frames
      identical to HEAD;
    - a 48x48 texture at 10, 30, 45, 60 and 80 degrees, scales 0.5, 1 and 1.5: 15 of 15 linear frames identical;
      at scale 1 they hold HEAD's 88, 92, 66 and 93 opaque pixels whose centre lies outside the quad (10, 30, 45
      and 60 degrees);
    - at scale 4 the band paints 69, 87 and 80 pixels more than 1 px outside the quad with clamp to edge and either
      filter, and 68, 86 and 79 bilinear with clamp to zero.
    - **Review fix.** The first version widened the band to half the pixel's extent at every `GENERIC` transform
      (bu = max(1/2, (|du/dx| + |du/dy|) / 2)). Below a scale of |cos| + |sin|, 1:1 included, that painted more
      opaque pixels outside the quad than HEAD: 104, 128, 130 and 127 at scale 1 in the 48x48 probe, and 5 and 3
      pixels more at 30 and 45 degrees in the band test. Scales 1.5 and 4 were not affected (the band was half a
      texel there). The widening is now kept for quarter turns and mirror diagonals only.
  - **Linear output against HEAD.** It is unchanged axis-aligned (`PiscesTexturePaintEdgeTest`, every SHA-256 pin)
    and under every `GENERIC` transform that is not a quarter turn or a mirror diagonal (above; the HEAD library
    passes all 12 linear band cases of `PiscesTextureSamplingTest`, on both sides of the band edge). It changes only
    where the band widens: at quarter turns and mirror diagonals, minified, with clamp to edge, and with clamp to zero
    where the drawn sub-rectangle lies inside the content (the C harness: 26 of the 82 linear scenarios that differ
    from HEAD); there the fringe pixels now match the axis-aligned twin (8 and 7 differing pixels at scales 0.5 and
    0.3 at HEAD). The whole-texture linear twins with clamp to zero, and those at scale 2, already matched at HEAD and
    are unchanged; REPEAT does not use the band.
  - **Residual.** A pixel whose centre lies outside the band is transparent in `GENERIC` and clamped in
    `TRANSLATE` and `SCALE_TRANSLATE`. Under a rotation other than a quarter turn those are the clipped box corners,
    as at HEAD. At quarter turns and mirror diagonals such a pixel does not touch the quad (its texture-space
    bounds, widened to one texel, miss the sub-rectangle). There a box larger than the drawn quad reaches it (a
    direct `PiscesRenderer` call or a `setTexture` fill, as at HEAD; `SWGraphics` never passes one), and so does the
    blitter's sliver column above.
  - **The nearest band moved with Part 1 (a consequence, not a defect).** At scale 4, rotated by 30, 10 and 45
    degrees, nearest sampling paints 87, 69 and 80 pixels more than 1 px outside the quad, against 100, 64 and 102 at
    HEAD, and now exactly the bilinear band. HEAD tested the texel under the pixel corner, a band offset by half the
    pixel's texture-space extent. Part 1 moved the sample to the centre, and the band moved with it.
  - **The story's measurement, SCALE_TRANSLATE against GENERIC.** Each axis-aligned draw at 0.5, 0.3 and 2 was
    compared with its quarter-turn twin, in both clamp modes and with both filters. At HEAD 8 of the 12 cases
    differed: up to 16 fringe pixels at 0.5 and 7 at 0.3, and 75 pixels at scale 2 with nearest sampling. The fix
    makes all 12 identical. That includes 0.3, whose inverse is not exact in 16.16: the measured maximum is 0, so
    the planned tolerance of 1 per channel was dropped.
- **Part 2 (decision): `ImageView.setSmooth(false)` stays a no-op.** No code change.
  - Wiring it through `NGImageView` would change pixels on D3D, ES2 and SW for every app that sets it today.
  - The no-op is upstream behaviour (JDK-8127343).
  - The fork's goal is that SW matches the GPU pipelines, not that public semantics change.
  - `Canvas` with `setImageSmoothing(false)` remains the only public route to nearest sampling. Part 1 fixes it.
- **Hardening (from the story's Notes).** Before, `inputImageCheck` rejected negative sizes, offsets and strides,
  a stride below the width and a short array, but allowed a width or height of 0. It never looked at the
  sub-rectangle.
  - `psw_renderer_draw_image` returns `PSW_ERR_ARG` for `w <= 0`, `h <= 0`, `tx_min < 0`, `ty_min < 0`,
    `tx_max >= w` or `ty_max >= h`. It checks after the NULL and wrap-mode test and before the surface is bound, so
    a rejected call touches nothing.
  - `PiscesRenderer.drawImage` rejects the same cases first, with an `IllegalArgumentException` and its own
    message.
  - `tx_max < tx_min` stays allowed. `SWGraphics.drawTexture` produces it for a source of zero width (checked:
    `sx1 == sx2 == 3` gives `txMin = 3`, `txMax = 2`).
  - **Empty textures.** `SWGraphics.drawTexture` now returns early when the texture's content is empty.
    `NGSubScene.renderContent` creates such a texture: `rtWidth = (int) ceil(slWidth * scaleX - 1/256)` is 0 for a
    `SubScene` less than 1/256 device pixel wide, `createRTTexture(0, h)` succeeds on SW, and the RTT is drawn
    with `drawTexture`. At HEAD that call reached C with `w = 0` and an empty box and painted nothing. With the new
    check it would throw. Skipping it keeps HEAD's pixels. No test drives `SWGraphics`, so the skip is verified by
    reading only.
  - The other sources checked cannot be empty, and the skip covers every caller of the worker anyway:
    - `com.sun.prism.Image` rejects a size of 0, which covers `ImagePattern` in `SWGraphics.fillRect` and
      `SWPaint`;
    - `NGImageView` and `NGCanvas` return early for an empty size;
    - `ImagePool.checkOut` turns an empty request into 1 x 1;
    - `SWGraphics.readBack` uses `max(1, ...)`;
    - nothing calls `Filterable.setContentWidth`.
  - Every `drawImage` in the pisces tests uses a positive size and an in-range sub-rectangle.
  - `setTexture` with a width or height of 0 still ends in an `OutOfMemoryError`: the guard of
    `psw_renderer_set_texture` returns `PSW_ERR_OOM`, as the JNI-era `setTextureImpl` fell through to its OOM
    error, pinned by `setTextureWithAZeroDimensionReachesTheCGuardAndIsAnOutOfMemoryError`. Accepted, unchanged here.
- **C (`native-prism-sw`, 4 files):**
  - `PiscesRenderer.inl`: the nearest shift in `renderer_setTexture`, and comments on both filters' sample point
    and on the classification.
  - `PiscesPaint.c`, in `genTexturePaintTarget`:
    - the nearest bounds `nxMin..nxMax`/`nyMin..nyMax`, used by the `TRANSLATE` row clamp and x loop and by the
      `SCALE_TRANSLATE` and `GENERIC` NO_REPEAT_NO_INTERPOLATE reads;
    - in the three `GENERIC` NO_REPEAT branches, the band test plus `checkBoundsNoRepeat`, so their bodies match
      `SCALE_TRANSLATE`;
    - `isInBoundsNoRepeat` deleted;
    - the `checkBoundsRepeat` comment corrected: it wraps modulo `max + 1` into `[0, max]`, and every branch uses it.
  - `prism_sw_api.c`: the `psw_renderer_draw_image` check.
  - `prism_sw_api.h`: the Errors paragraph, and the `draw_image` documentation of the nearest rule, the band and
    the check.
  - No signature changed: `PSW_ABI_VERSION` 2 = `PiscesNative.ABI_VERSION` 2. The library still has 22 `psw_*`
    exports and the same imports (6 DLLs, 33 symbols), checked with dumpbin on the Maven-built `prism_sw.dll`.
  - The build gives the same five C4244 warnings as HEAD, all on unchanged expressions.
- **Java:**
  - `PiscesRenderer.drawImage`: the empty-texture and range checks (`textureRangeCheck`) and their Javadoc.
  - `SWGraphics.drawTexture`: the empty-texture skip.
- **Tests:**
  - **New `test.com.sun.pisces.PiscesTextureSamplingTest`, 86 cases.** It drives `PiscesRenderer` directly, as
    `PiscesTexturePaintEdgeTest` does. It draws the story's 7x5 texture with one distinct opaque colour per texel.
    - Centre texel, 15 cases: every fully covered pixel holds the texel under its centre. The texel comes from
      integer arithmetic, and ties are skipped. The placements are axis-aligned at 1 (quarter position), 1.5
      (integer and quarter) and 2.5 (quarter and half), and a quarter turn at 1 and 2 (integer and quarter). They
      go through `drawImage` in all three wrap modes and through `setTexture`.
    - A quarter turn at scale 1 draws every texel exactly once.
    - A tie self-check: the half-pixel placement at 2.5 has ties in columns 13, 18, 23 and rows 10, 15.
    - Branch twins, 36 cases: `SCALE_TRANSLATE` and `GENERIC` agree exactly at 0.5, 0.3 and 2, with the twin
      turned a quarter or mirrored in either diagonal.
    - The fringe rule on the axis-aligned branch, 8 cases.
    - The `GENERIC` band, 24 cases: rotations of 10, 30 and 45 degrees at scales 1 and 4, linear and nearest,
      `CLAMP_TO_EDGE` and `CLAMP_TO_ZERO`. The box is the quad's axis-aligned bounds as `SWGraphics` passes them.
      The oracle runs in doubles on the inverse of the transform the test passes, with bu = bv = 1/2 texel. It
      checks both sides of the band edge on every box pixel. A pixel whose u lies outside
      `[txMin - 1/2, txMax + 3/2)`, or whose v lies outside the matching range, must be transparent. A pixel inside
      the band must have alpha, except with linear clamp-to-zero, whose footprint rule fades the fringe out; there
      only a pixel at least half a texel inside the quad must have alpha. The band is in texels, not device pixels:
      at scale 4 it is 2 px wide.
      - A pixel whose centre lies within the 16.16 error bound, (|x| + |y| + 3) / 65536 texel, of a band edge is
        skipped. The quad's centre (28.34375, 20.5) keeps every box pixel beyond that bound, by at least 7.8e-4
        texel (30 degrees, scale 4), so 0 pixels are skipped in each of the 24 cases (the test prints the count).
      - Box pixels outside the band / inside the band and outside the quad / at least half a texel inside the
        quad:

        | rotation | scale 1 | scale 4 |
        | --- | --- | --- |
        | 10 degrees | 14 / 14 / 25 | 78 / 160 / 382 |
        | 30 degrees | 33 / 13 / 24 | 407 / 189 / 384 |
        | 45 degrees | 32 / 14 / 23 | 480 / 199 / 391 |

        The counts are the same in each wrap mode and with each filter.
    - A zero-width source at the right edge, 1 case (`txMin = W`, `txMax = W - 1`, nearest): the texture has a
      stride of W + 1 and a sentinel colour in column W, and no pixel may show it.
  - **What failed before the fix.**
    - On HEAD, 54 of the 86 fail: centre 15, once 1, branch 22, fringe 4 and band 12. The band failures are the 12
      nearest cases, on both sides of the band edge. At 10, 30 and 45 degrees, 35, 63 and 74 pixels outside the band
      are painted and 55, 75 and 84 inside it are transparent at scale 4, and 0 and 2, 4 and 4, and 0 and 5 at scale
      1. All 12 linear band cases pass on HEAD. The zero-width case passes on HEAD too: HEAD read texel `txMax`
      there.
    - The controls pass on HEAD: the tie self-check, the linear zero-mode branch twins, the scale-2 linear twins
      and the four linear fringe cases.
    - On the clamp-everywhere build, the band cases fail 18 of 24: every case except the 6 linear clamp-to-zero
      ones, whose corners the US-013 zero-footprint rule already clears. Each failure counts exactly the box
      pixels outside the band. The other 68 cases pass there.
    - Negative controls from the review, each a library swapped into `target/native/bin` and checked by SHA-256
      both ways:
      - a C mutant with no band (bu = bv = 0) fails 48: the 18 band cases that are not linear clamp-to-zero, all
        on the inner side (every box pixel inside the band and outside the quad is transparent), and 30 branch
        twins, 10 for each of the three twin transforms;
      - the build with the band widened at every rotation (before the review) fails 6, all on the outer side at
        scale 1: 5 pixels at 30 degrees and 3 at 45, with linear clamp to edge and with nearest in both modes;
      - a C mutant whose nearest bound is `[txMin, txMax]` (no `MIN`) fails only the zero-width case: 25 of its 50
        painted pixels show the sentinel;
      - a C mutant that widens the band at quarter turns but not at mirror diagonals fails 12, all mirror twins:
        both diagonals at 0.5 and 0.3, with clamp to edge and either filter and with nearest clamp to zero. Every
        quarter-turn twin passes.
  - **`PiscesNativeTest`, 18 to 20.**
    - `drawImageRejectsASubRectangleOutsideTheTexture`: 4 ranges rejected with pinned messages, and a zero-size
      source still renders.
    - `drawImageRejectsAnEmptyTexture`: sizes (0, 16), (16, 0) and (0, 0), with a zero-width or zero-height source
      the range check allows, and a 16 x 16 box. A mutant without the Java check fails it with the C's
      `Illegal arguments`, so the C check is live behind the Java one.
  - **`PiscesTexturePaintEdgeTest`**: 175/0, unchanged, and no SHA-256 pin moved. The bilinear arithmetic is
    byte-identical.
- **Golden (`PiscesGoldenRenderTest`), moved: steps 12 to 25 differ, from two own changes.** Frames are cumulative,
  so a step's own change is the pixels that differ at that step and not at the one before.
  - **Step 12**, `setTexture` nearest clamp-to-edge at inverse scale 2: 295 pixels, the fill's columns and rows
    with x or y at most 6, which reach into the texture. An independent model (corner texel against centre texel,
    in doubles and in 16.16) gives the same 295.
  - **Step 14**, `setTexture` nearest REPEAT under the sheared 1.3 scale (`GENERIC`): 502 of its 812 pixels. The
    16.16 model gives 502 (the double model 501, one pixel at a texel boundary).
  - Both are Part 1. The nearest clamp change reads the same texels there.
  - Steps 13 and 15 to 25 add no differing pixel; they carry those of 12 and 14. Steps 15, 18, 20 and 23 paint
    over 135, 1, 5 and 2 of them.
  - Steps 16, 17 and 19 paint no pixel in either golden, so the band moves nothing. The script hands `drawImage` a
    device-to-texture transform: filed as US-061.
  - This matches the C side's expectation (steps 12 and 14). No C mutant was built; the attribution is the
    per-step frame comparison plus the model.
  - The new golden `pisces-golden-US-059-pending.bin` was captured once from the fixed build (Maven-built
    `prism_sw.dll`): 425,984 bytes, 26 steps, SHA-256
    `69572218d1e84249425919812360b3260c50ce31338bd53e38a20f2329385a29`. It replaces
    `pisces-golden-820a4c35d7.bin` (`3b0ba8b0...`).
  - `GOLDEN_RESOURCE` and the class comment name it, list the moved steps and say why. They also say that the file
    is renamed to the hash of the commit that lands this change, as the US-013 golden was named after 820a4c35d7.
  - `FLOAT_STEPS` is unchanged.
- **Windows run** (counts from the Maven logs):
  - The native build, `mvn -B -ntp -pl modules/javafx.graphics process-classes`, rebuilt `prism_sw.dll` from
    `768991f5...` to `71571c87...`, and after the review fix to `454f5bb7...`. The library still has 22 `psw_*`
    exports with the same names and the same imports.
  - The gate, `mvn -B -ntp -pl modules/javafx.graphics test -Djfx.parity.require=true -DskipNative=true`, gave
    graphics 26,007 run, 0 failures, 0 errors, 356 skipped (25,982 before the review fix added the zero-width case,
    25,983 before the mirror twins).
  - In the pisces classes: `PiscesGoldenRenderTest` 6, `PiscesNativeTest` 20, `PiscesTexturePaintEdgeTest` 175
    and `PiscesTextureSamplingTest` 86, all green. The golden and the edge test's pins did not move with the
    narrowed band.
  - There was no `hs_err_pid*` under `modules/javafx.graphics`.
  - Only `PiscesNativeTest` (+2) and the new class (+86) change counts against master. No module run of master
    was made for this story.
  - Not run: Linux (WSL), CI's aarch64 jobs (where FMA contraction meets `FLOAT_STEPS`; the band test skips any
    pixel within the 16.16 error bound of a band edge) and the `FULL_TEST`/`USE_ROBOT` suite on SW. They are left
    to CI and the pre-push check (`openjfx-conventions`).
- **Hardware check (met).** `Canvas` with `setImageSmoothing(false)`, `Node.snapshot`, Windows 10, D3D9Ex. The
  7x5 probe image is drawn axis-aligned at an integer position (10, 6) and a quarter-pixel one (10.25, 6.75), and
  under `rotate(90)`, at s = 1, 1.5, 2 and 2.5.
  - PRE is HEAD's `prism_sw.dll`; POST is the Maven-built fixed library with the tree's classes. The D3D run was
    made once, before the fix, which does not touch `prism_d3d`. POST was re-run on `454f5bb7...` after the review
    fix: all 16 scene dumps are byte-identical, so the table is unchanged (the probe has axis-aligned and
    quarter-turn scenes only).
  - A tie is a centre on a texel boundary in u or in v (`compare.pl`, integer arithmetic).
  - Columns: opaque on both / SW picks another texel / of those, exact centre ties / SW transparent where D3D is
    opaque.

  | scene | PRE | POST |
  | --- | --- | --- |
  | axis s1 int | 35 / 0 / 0 / 0 | 35 / 0 / 0 / 0 |
  | axis s1 quarter | 24 / 24 / 0 / 0 | 24 / 0 / 0 / 0 |
  | axis s1.5 int | 70 / 43 / 20 / 0 | 70 / 15 / 15 / 0 |
  | axis s1.5 quarter | 63 / 33 / 0 / 0 | 63 / 0 / 0 / 0 |
  | axis s2 int | 140 / 0 / 0 / 0 | 140 / 0 / 0 / 0 |
  | axis s2 quarter | 117 / 54 / 0 / 0 | 117 / 0 / 0 / 0 |
  | axis s2.5 int | 204 / 83 / 31 / 0 | 204 / 25 / 25 / 0 |
  | axis s2.5 quarter | 192 / 62 / 0 / 0 | 192 / 0 / 0 / 0 |
  | rot90 s1 int | 28 / 28 / 0 / 7 | 35 / 0 / 0 / 0 |
  | rot90 s1 quarter | 24 / 24 / 0 / 7 | 24 / 0 / 0 / 0 |
  | rot90 s1.5 int | 70 / 50 / 27 / 10 | 70 / 21 / 21 / 0 |
  | rot90 s1.5 quarter | 70 / 35 / 0 / 0 | 70 / 0 / 0 / 0 |
  | rot90 s2 int | 126 / 56 / 0 / 14 | 140 / 0 / 0 / 0 |
  | rot90 s2 quarter | 117 / 52 / 0 / 14 | 117 / 0 / 0 / 0 |
  | rot90 s2.5 int | 204 / 93 / 41 / 17 | 204 / 35 / 35 / 0 |
  | rot90 s2.5 quarter | 204 / 64 / 0 / 0 | 204 / 0 / 0 / 0 |

  - POST: SW picks the D3D texel in every pixel except at exact ties, and no pixel is transparent where D3D is
    opaque.
  - The rotated draw at s = 1 now paints all 35 texels where D3D does: the first column is opaque, and texel row 0
    is drawn.
- **Corrections to this story:**
  - **Tie counts.** A tie is a centre on a texel boundary in u or in v. At HEAD, 20, 31, 27 and 41 of the differing
    pixels are ties (axis s1.5 and s2.5, rot90 s1.5 and s2.5), not the table's 15, 25, 21 and 35. Those four
    numbers are the pixels that still differ after the fix, all of them ties.
  - **The scale-1 translate case.** The table's "axis s1: 35 / 0" holds for an integer position only. At scale 1
    and a quarter-pixel position (the `TRANSLATE` branch), SW was a whole texel off in both axes in all 24 opaque
    pixels. The probe and `PiscesTextureSamplingTest` (`AXIS_1_QUARTER`) now cover it.
  - **Part 3.** "Read, not measured" is now measured (above). Its premise that a pixel outside the quad has no
    coverage holds only up to quarter turns.
  - **Proposed fix, Part 1.** "Nearest never needs `txMin - 1`" holds, but a literal `[txMin, txMax]` clamp is
    out of range for a zero-width source at the right edge; hence the `MIN` above.
- **Filed:** two stories.
  - US-061: the golden's `drawImage` steps 16, 17 and 19 sample outside their texture and paint nothing.
  - US-062, from the review: two pre-existing fixed-point edge cases, the same at HEAD. The blit paints a
    1/65536 px box sliver as an interior column (the 16 mirror-diagonal twin cases above), and `checkBoundsRepeat`
    overflows a `jint` above 32767 texels.
- **Evidence:** Claude scratchpad of session aaa1f9d7, `us059/`:
  - `BRIEF.md` (decisions), `notes/C.md` (the C change log, the harness results, alternative R), `notes/J1.md`
    and `notes/J2.md` (tests, golden, gate, hardware);
  - `review-r1.json` (the two-lens review) and its probe `rev-clens/p.c`;
  - `ctools/harness/` (the C harness: `ab.c`, `rot.c`, `q.c`, `g.c`, `e.c`), `ctools/instructed/` (the
    clamp-everywhere build), `ctools/pre-review/` (the build before the review fix), `ctools/mut-narrow/`,
    `ctools/mut-nxmin/` and `ctools/mut-nomirror/` (the three C mutants);
  - `j2/probe/` and `j2/probe2/` (the band placement scans and prediction), `j2/golden/` (the per-step attribution
    and the model);
  - the runs that fail before the fix: `j2/mvn-sampling-head.log` (HEAD, 61 cases, 36 failures),
    `j2/mvn-sampling-instructed.log` (clamp everywhere, 18) and `j2/mvn-mutant-empty.log` (the Java mutant), all
    with the class before the review; after it, `j2/mvn-sampling-r2-<build>.log` for HEAD, the clamp-everywhere
    build, the pre-review build and the two mutants (`j2/controls-r2.txt` has the swaps and their SHA-256s); with
    the mirror twins, `j2/mvn-sampling-r4-<build>.log` for those and the third mutant (`j2/controls-r4.txt`);
  - `gate/before` (the phase-1 run of the first 37 cases, 28 failures on HEAD), `gate/after` (the module gate,
    `mvn-gate-after-r2.log` after the review fix, `mvn-gate-after-r3.log` with the mirror twins), `hw/` (the probe,
    the runs and `report.md`).
