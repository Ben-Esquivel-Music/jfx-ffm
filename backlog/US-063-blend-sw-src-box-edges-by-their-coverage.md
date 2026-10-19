# US-063 — Blend a partly covered box edge under `SRC` by its coverage in the software compositor

**Status:** 📋 Ready (filed 2026-10-18 from US-062) · **Found:** 2026-10-18, by the review of US-062 (corpus step 295)

## Story
As a JavaFX app developer whose app runs on the software pipeline,
I want a fill under `CompositeMode.SRC` to blend a pixel that its edge covers only in part by that coverage,
so that the edge of a `SRC` fill over an opaque background mixes the two colours instead of corrupting the pixel.

## Problem
Under `COMPOSITE_SRC` the box blits of `PiscesBlit` (`emitLineSource8888Pre` for a colour paint,
`emitLinePTSource8888Pre` for a texture or gradient paint) blend a partly covered pixel with `blendSrc8888Pre` or
`blendSrc8888PrePre`: `oalpha = div255(255 * aval + dalpha * raaval)`, and each colour channel alike, with the
paint's whole alpha as `aval` and `raaval = 255 - coverage`. A pixel the box covers by c therefore gets
S + (1 - c) D, not c S + (1 - c) D. Where S + (1 - c) D exceeds 255 in a channel, the ninth bit of each colour
channel lands in the channel above it and that of alpha is lost.
- **Probe** (public `PiscesRenderer` API): a 4 x 1 `TYPE_INT_ARGB_PRE` surface of opaque white `FFFFFFFF`,
  `COMPOSITE_SRC`, colour (255, 0, 0, 255), `fillRect` from x = 0.5 to 2.5. Columns 0 and 2, covered by half, are
  `7D7D7F7F` (alpha and red 381, truncated); c S + (1 - c) D is opaque `FFFF7F7F` or `FFFF8080`, half red and half
  white. Column 1 is `FFFF0000` and column 3 `FFFFFFFF`, as they should be.
- **In the corpus**: step 295 of `PiscesCorpusGoldenTest` (`fillRect` #119, `SRC`, colour (20, 130, 250, 255), box
  `lfrac1-tfrac256`) has a 1/65536 px sliver in column 2, coverage 0 in 8 bits, so S + D: its full rows held wrapped
  pixels such as `501C9B21` in the capture from the C, and since US-062 its fractional rows do too (`07148700`).
- **Origin**: the C of 42a5072596 has the same arithmetic (`blendSrc8888_pre`, `emitLineSource8888_pre` in
  `native-prism-sw/PiscesBlit.c`); the Java port of US-046 reproduced it, so it is not a port regression.
  `COMPOSITE_CLEAR` uses the same colour routine with an internal colour of 0, S = 0, and gives the right
  (1 - c) D.
- **Callers**: in `com.sun.prism.sw` only `SWGraphics.setCompositeMode` sets `COMPOSITE_SRC`, for
  `CompositeMode.SRC`.

## Acceptance criteria
- A unit test in `test.com.sun.pisces` fills boxes with fractional edges under `COMPOSITE_SRC` over an opaque
  destination, with a colour and with a texture paint, and checks every pixel against c S + (1 - c) D computed from
  the box's area in doubles, within a tolerance stated in the test. It fails before the fix.
- Mutant proof: reverting the fix in each changed routine fails the test.
- `COMPOSITE_CLEAR` and the pixels a `SRC` box covers in full stay bit-identical.
- A pin or golden that moves is re-pinned or re-captured in its own commit, with the reason.

## Notes
- What moves when this is fixed: the `SRC` routes of `PiscesFixedPointEdgeTest` (US-062), whose oracle models
  today's S + (1 - c) D and whose four `SRC` sliver pins expect S + D; and the corpus steps that fill a fractional box
  edge under `SRC`. The `CLEAR` steps stay.
- Evidence: Claude scratchpad of session 0e6e2804, `us062/notes/R.md` (check 5) and `us062/notes/US-063-probe.txt`
  (the probe and its output).
