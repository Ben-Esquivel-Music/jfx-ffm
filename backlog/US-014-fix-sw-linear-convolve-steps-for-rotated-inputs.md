# US-014 — Step the software linear convolution along the right destination axis for rotated inputs

**Status:** ✅ Done (2026-10-03) · **Found:** 2026-09-26, hardware check of US-010 (SW `InnerShadow(GAUSSIAN)` under rotation)

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

## Resolution (2026-10-03)
- **Fix:** exactly the proposed fix. In `JSWLinearConvolvePeer.filter`'s 8-coordinate branch, `dycol` now divides by
  `dstBounds.width` and `dxrow` by `dstBounds.height`. A two-line comment cites the `getTextureCoordinates` corner
  table. Nothing else in production changed. `javap -c -p` of the compiled class differs from HEAD's in exactly two
  `getfield` instructions (`height`/`width`), both inside `filter`. The shadow peer inherits the fix.
- **Corrections to this story:**
  - The line references are right: `EffectPeer.java:266-275` is the instance `getTextureCoordinates` javadoc. The
    static overload carries a copy of the table at `:321-330`.
  - Step-oracle wording: in source texels the steps are exactly `T^-1 (1, 0)` and `T^-1 (0, 1)`. "Times the source
    size" only cancels the division of `srcRect` by the source size.
- **Tests:** new `test.com.sun.scenario.effect.LinearConvolveRotatedInputTest`, 12 cases, on `DecoraBackend.convolve`
  with a 48x16 input at (5, 7) whose `ImageData` carries the transform. `DecoraBackend` gains one hook,
  `data(Image, Rectangle, BaseTransform)`. Production code has no test hook.
  - **Step oracle (8):** blur and shadow states x rotate 90, rotate 270, the 3-4-5 rotation and a shear, each into a
    non-square pass destination (for example 16x58 at rotate 90). Recording subclasses of both peers capture the
    steps and start point handed to `filterVector` in both passes.
    - On HEAD all 8 fail, on `dycol` and `dxrow` only. At rotate 90, `dycol` is -0.275862 instead of -1 and `dxrow`
      3.625 instead of 1.
    - Fixed, the step error is at most 1.27e-7 (tolerance 1e-5) and the start point within 1.25e-6.
  - **Pixel oracle (4):** blur and shadow at rotate 90 and 270, against the same kernel over the pre-rotated input
    with an identity transform.
    - HEAD: max 255 (blur) and 204 (shadow) on 706-798 of 1,276 pixels.
    - Fixed: max 1 step, on 44 pixels of blur rotate 270. That is the bilinear sampling of float-rounded positions
      next to pixel centres; the bound and its reason are in the test.
  - **Mutant proof:** 7 mutants of the peer, each re-introducing one error class. Every one fails tests, and no test
    had to be tightened.
    - The 12/12 mutants: each wrong denominator alone (`dycol` / height, `dxrow` / width), each sign (`dycol`,
      `dxrow`), and a start-point offset (`srcx0 + 1`, max 62 steps in the pixel cases).
    - The 4/12 mutants: the two other denominators (`dxcol` / height, `dyrow` / width) fail the four step cases of
      the 3-4-5 rotation and the shear. At a quarter turn those two terms are zero whatever the denominator.
  - Effect tests (`test.com.sun.scenario.effect.**`, `-Djfx.parity.require=true`): 768 run, 0 failures. All five
    effect `-output.txt` files are byte-identical to HEAD's, including `DecoraJavaGoldenTest` (396 lines). The golden
    was not regenerated.
- **Windows and Linux runs** (counts from the Maven logs):
  - Windows, `mvn -B -ntp -pl buildtools/jslc,modules/javafx.graphics clean test -Djfx.parity.require=true`: before
    (HEAD) graphics 25,893 run, 0 failures, 356 skipped; after 25,905 run, 0 failures, 0 errors, 356 skipped; jslc
    159/0.
    - Only the new class changed counts (+12).
    - Generated JSL sources and headers were md5-identical, and there was no `hs_err`.
  - Linux (WSL, JDK 25, a fresh clone with the change, `-am`, no parity flag): graphics 25,719 run, 0 failures,
    500 skipped, against 25,707/0/500 for plain HEAD; base 5,510/0/22; jslc 159/0. No `hs_err`.
  - `DecoraJavaGoldenTest` output was byte-identical to HEAD's on both, and so were the other effect tests' outputs.
- **Hardware check (met).** `Node.snapshot`, AMD Radeon R7 240, D3D9Ex, ES2 over WGL and SW, full and viewport (the
  six US-010 cuts). PRE is a HEAD runtime; POST is the same runtime plus the fixed class.
  - `InnerShadow(GAUSSIAN, 10)` rotate 90/270: PRE reproduces the story (SW rotation inconsistency 156 on 12,104
    pixels). POST:
    - SW rotation consistency 0 pixels, as on D3D and ES2;
    - SW vs D3D full 2 (28 pixels, the same as at identity), was 156;
    - SW viewport vs full 0 on all 12 cuts, was up to 206.
  - `InnerShadow` rotate 45: the left and narrowx cuts drop from 157 and 110 to 2. The top (29) and narrowy (26)
    cuts keep the residual explained below.
  - `GaussianBlur(10)` over `BoxBlur(10, 10, 1)`:
    - SW rotation consistency 129 on 31,651 pixels to 0;
    - viewport vs full: rotate 90 from 133 to 0, rotate 45 from 133 to 1;
    - SW vs D3D full at rotate 90: 132 to 7, the box-width floor below.
  - `GaussianBlur(10)` over a `Reflection`:
    - SW rotation consistency 163 on 49,178 pixels to 0;
    - viewport vs full: rotate 90 from 194 to 0, rotate 45 from 176 to 1;
    - SW vs D3D full: 164 to 2.
  - Controls: D3D and ES2 are pixel-identical PRE and POST (70 dumps each), ES2 vs D3D at most 1, and the GPU
    viewport vs full at most 1.
  - After the fix, SW vs GPU is 7 on every scene with the `BoxBlur(10, 10, 1)` input. That includes identity and the
    rotate-45 full render, which were already 7 before the fix. The 7 comes from the box pass: SW boxes are
    `ceil(s) | 1` = 11 wide, the GPU's box is trimmed to 10. US-011 records this as by design for sizes that are not
    odd whole numbers, and US-055 tracks it. With `BoxBlur(11, 11, 1)` the box alone gives 2 and the Gaussian over
    it 3.
- **The rotate-45 residual (explained, filed as US-060).** It is not a second defect in the 8-coordinate branch.
  `JSWLinearConvolveShadowPeer.filterVector` (`:68-75`) samples the nearest texel (`(int) sampx`, the open
  JDK-8090445), and at rotate 45 it hits exact ties:
  - Every device pixel centre on the X = Y diagonal maps to user y = 80.000, a texel row boundary.
  - There `(int) sampy` follows float noise of up to 9.2e-5 texel. The noise comes from the clipped origin's
    normalisation and from the `+=` step accumulation, so a full render and a viewport pick different rows.
  - In the top cut all 21 taps of a diagonal pixel read row 79 instead of 80, and the pass-0 alpha jumps by up to
    173. Pass 1, a diagonal vector pass and also `filterVector`, carries about 0.2147 x 173 = 37 (measured 38).
    `SRC_ATOP` over the content gives the measured 29.
  - Two single-variable interventions prove it:
    - On hardware, a bilinear sampler alone brings every cut to 2. The remaining 2s are content-edge alpha, outside
      the shadow chain.
    - In a `DecoraBackend` harness, a bilinear sampler alone gives at most 1. Nearest sampling with each pixel's
      positions computed in double gives 0.
  - Nothing else is at fault. Pass 1 runs `filterVector` in every render, so no render switches loops, and the
    fixed 8-coordinate geometry is exact to 6e-5 texel.

  Bilinear sampling would also cut SW vs D3D for a full render at rotate 45 from 22 to 3. But it moves one
  translate-only `DecoraJavaGoldenTest` row (`clip/LinearConvolveShadow/gaussian | radius=3.0 spread=0.5 black clip
  top only | 257x129`, 7 pixels by 1 step), and this story requires the golden to stay byte-identical. So it is filed
  as US-060, with that row as a reviewed deviation.
- **Evidence:** Claude scratchpad of session c1b89658, `us014/`:
  - `A-notes.md`, `step1-head*` and `step2-*` (the HEAD and fixed runs), `fixcls/` (the class and its `javap` diff),
    `mutants/`, and `gate/` (Windows and WSL);
  - `r45/` (`r45-hw`, `r45-unit`, `r45-skeptic`) for the residual, with the bilinear patch;
  - `hw/US-014-hardware-report.md` with the probes and dumps;
  - the independent review `review/`.
- **Upstream:** `openjdk/jfx` master still has the swapped denominators and the nearest-sampling shadow
  `filterVector` (checked 2026-10-03).
