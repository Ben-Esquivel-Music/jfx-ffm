# US-061 — Make the golden `drawImage` steps sample the texture

**Status:** 📋 Ready (filed 2026-10-16 from US-059; measured on the golden files and on the paint driven directly,
not fixed) · **Found:** 2026-10-16, while attributing the golden steps that US-059 moved

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
    `pisces-golden-US-059-pending.bin`;
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
