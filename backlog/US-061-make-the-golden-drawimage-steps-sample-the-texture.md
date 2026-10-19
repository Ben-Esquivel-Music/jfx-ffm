# US-061 — Make the golden `drawImage` steps sample the texture

**Status:** ✅ Done (2026-10-18, ee923ace41, PR #33) · **Found:** 2026-10-16, while attributing the golden steps
that US-059 moved

## Story
As the maintainer of the software pipeline and of its planned Java port (US-046),
I want the `drawImage` steps of `PiscesGoldenRenderTest` to paint pixels sampled from inside their texture,
so that the golden, the baseline for the Java port, covers `drawImage` sampling under a rotated or sheared transform
instead of three steps that paint nothing.

## Problem
`PiscesGoldenRenderTest` builds the transform of its four `drawImage` steps (16 to 19) as a device-to-texture map:
`imageTx = (16 / bw, 0.05, -0.03, 16 / bh, -bx * 16 / bw, -by * 16 / bh)`, that is u = 16 / bw * (x - bx). But
`PiscesRenderer.drawImage` takes the texture-to-device transform and inverts it (`renderer_setTexture`,
`pisces_transform_invert`). So every pixel of the box samples u of about 52 to 89, far outside the 16-texel texture.
- The shear terms make every step take the `GENERIC` branch of `genTexturePaintTarget`.
- Steps 16, 17 and 19 do not repeat. Their pixels lie outside the band that keeps a rotated quad's bounding-box
  corners clear (US-059), so they are transparent and the steps paint nothing.
- Step 18 repeats. It paints 454 pixels, tiled from far outside the image.

### Measured (2026-10-16, Windows x64)
- **Golden files.** Pixels each step changes against the step before:
  - steps 16, 17 and 19 change 0 pixels in `pisces-golden-820a4c35d7.bin` and in the US-059 capture
    `pisces-golden-42a5072596.bin`;
  - step 18 changes 454 in both.
- **Same geometry, drawn directly** through `psw_renderer_draw_image` (US-059 C harness):
  - HEAD `prism_sw` and the US-059 fix paint 0, 0 and 0 pixels for steps 16, 17 and 19;
  - a build that clamps in `GENERIC` with no band paints 525, 525 and 500.
- **Consequence.** No golden step checks in-texture `drawImage` sampling under a non-axis-aligned transform. That
  is the path of every rotated `ImageView` on SW, and the path US-059 changed.

## Proposed fix
- Pass the texture-to-device transform: device = (bw / 16) u + (bx, by), plus the intended shear. Then the four
  steps cover the box with the texture, as `SWGraphics.drawTexture` does.
- The four steps then paint new pixels, and the later steps carry them. Capture the new golden from an unchanged C
  in its own commit, list the moved steps, and rename the file after that commit, as US-013 and US-059 did.

## Acceptance criteria
- Steps 16 to 19 each change pixels inside their box. In steps 16, 17 and 19, a pixel whose centre lies inside the
  drawn quad is sampled from inside the texture.
- The golden is re-captured with no C change, in its own commit. The class comment names the moved steps and why.
- `FLOAT_STEPS` and the class comment still classify every step correctly.

## Notes
- Evidence: Claude scratchpad of session aaa1f9d7, `us059/`. The C harness `ctools/harness/g.c` and its outputs
  are in `notes/C-golden-geometry.txt` and `notes/C-g-r3.txt`; the per-step frame comparison is in
  `j2/golden/steps.pl` and `steps.txt`.
- Related: US-046 (the Java port takes this golden as its baseline), US-059 (the `GENERIC` band).
- Test-only. The paint is right: the transform is wrong in the test.
- Since US-046 the C is gone and the Java compositor reproduces it byte for byte. The golden is therefore
  re-captured from the Java compositor (capture mode, `-Dpisces.golden.capture=<absolute path>`) in its own commit
  that lists the moved steps, and renamed after that commit as before; "no C change" in the criteria now means no
  compositor change. `FLOAT_STEPS` went with the C (the golden is exact on every platform), so the third criterion
  applies to the class comment only. US-046's `PiscesCorpusGoldenTest` does not run this script; should a change
  move one of its steps, the corpus is re-captured the same way in its own commit (`-Dpisces.corpus.capture`, with
  `-Dpisces.corpus.producer` naming the producer in its header, `java` by default).

## Resolution (2026-10-18)
- **Transforms.** Each `drawImage` step has its own texture-to-device transform, taken from one definition that the
  script and the guard share (`ImageStep` and `Box` in `IMAGE_STEPS`). Scale = box size / source size and
  translation = box origin, as `SWPaint.computeDrawTexturePaintTransform` builds the transform that
  `SWGraphics.drawTexture` hands `drawImage`. A shear of 0.05 and -0.03 pixel per texel (`SHEAR_X`, `SHEAR_Y`) keeps
  every step on `GENERIC`: the inverse's off-diagonals are -1648/989 in 16.16 for steps 16 and 17, -6579/3948 for 18
  and -928/557 for 19. Steps 16, 17 and 19 map their sub-rectangle onto their box, step 19 its 12x12 sub-rectangle at
  offset 34, stride 16. Step 18 maps the texture onto a centred tile half as wide and high as its box, so its box
  samples u from -9.1 to 24.1 and v from -7.2 to 23.5, and REPEAT wraps on every side. Step order, names, textures,
  modes, edges, flags, colours and boxes are unchanged, and the compositor is not touched.
- **Golden.** `pisces-golden-ee923ace41.bin` (renamed by US-062 after the landing commit), captured from the Java
  compositor of 410b8036c1 (SHA-256
  `dadef48365b2f438153325485164aa05d295bbcd57a7f9d51f4fa281b84d7e0a`), replaces `pisces-golden-42a5072596.bin`.
  Frames 0 to 15 are byte-identical. Steps 16 to 19 change 488, 525, 457 and 500 pixels against the step before
  (0, 0, 454 and 0 before) and differ from the old golden in 488, 806, 1011 and 1019; steps 20 to 25 carry them
  (20 and 21 paint over some) and differ in 1017 each.
- **C cross-check.** A capture of the US-061 script from the C of 42a5072596 (`prism_sw` built from 7d5ffd66f0, the
  script carried over, the library load logged; without the library the run aborts and captures nothing) is
  byte-identical to the Java capture and to the new golden, so the golden stays a C baseline.
- **Guard** `theDrawImageStepsPaintTheirBoxFromInsideTheTexture`, per step: a pixel of the box changes against the
  frame before; the inverse keeps a 16.16 shear (`GENERIC`); for 16, 17 and 19 every quad corner lies within one
  pixel of its box corner (the shear moves one by at most 0.8 px, a texel spans at least 1.29 px), and every pixel
  clear of the fractional box edges whose centre lies half a texel inside the quad is painted when the step is
  replayed with opaque texels on a transparent surface (436, 436 and 420 pixels); for 18 the box samples u and v
  below 0 and at or above 16. The oracle is geometry in doubles from the `Transform6` ints plus that replay, in
  which the paint leaves a pixel sampled outside its half-texel band transparent; it reuses none of the
  compositor's fixed-point arithmetic. Mutant proof, on copies outside the repository: M1 (the old device-to-texture
  transform, 19 checks fail), M2 and M3 (translation one texel off in u, in v: corner checks), M4 (no shear:
  `GENERIC`), M5 (step 18 on its whole box: no wrap) and M6 (a half-width sub-rectangle: replay) fail; M0, the
  unmutated copy, passes.
- **Criteria.** 1 is met by the guard and the counts above. 2 is met: the golden is re-captured with no compositor
  change, as its own change, and the class comment lists the moved steps and why. 3 is met in the class comment,
  which still classifies every step; `FLOAT_STEPS` went with the C in US-046.
- **Tests** (Windows x64, Maven 3.10.0, JDK 27): `PiscesGoldenRenderTest` 7, 0 failures; `test.com.sun.pisces` and
  `test.com.sun.prism.sw` 289, 0 failures, 0 skipped (`-Xmx6g`); the module run, javafx.graphics 26,028, 0 failures,
  0 errors, 356 skipped, javafx.base 5,510, 0 failures, 22 skipped, jslc 159, 0 failures.
- **Follow-up.** After this lands, a later commit renames the pending file to the landing commit's hash in
  `GOLDEN_RESOURCE` and the class comment, as US-046 did for US-059's golden.
- **Evidence:** Claude scratchpad of session 59a420f9, `us061/` (`notes/A.md`, `golden/`, `mutants/`, `c-check/`,
  `logs/`).
