# US-062 — Fix two fixed-point edge cases in `prism_sw`: a box sliver painted as a column, and REPEAT past 32767 texels

**Status:** 📋 Ready (filed 2026-10-16 from US-059's review; one probed by the reviewer, one read; both the same
at HEAD, not fixed) · **Found:** 2026-10-16, by the C review of US-059

## Story
As the maintainer of the software pipeline and of its planned Java port (US-046),
I want a box edge a fraction of a pixel past a pixel boundary, and REPEAT on a wide texture, to be computed without
fixed-point slips,
so that SW paints a box's edge column at the coverage it has, and wraps every texture width it accepts.

## Problem

### 1. A 1/65536 px box sliver is painted as an interior column (probed)
The fractional-row blit in `PiscesBlit.c` scales the edge fractions by the row's coverage as
`lfrac = (_el_lfrac * frac) >> 16` (and `rfrac` alike). So a box sliver of 1/65536 px is treated as an interior
column and painted at the row's coverage.
- The review of US-059 drew mirror diagonals (`GENERIC`) and their exact axis-aligned twins (`SCALE_TRANSLATE`)
  with the transposed texture, the same 16.16 entries and the same box. 16 of 5184 nearest cases differ, all at
  scale 0.7 with X = 60.3, where the truncated box edge is 3997695 = 61 * 65536 - 1. `SCALE_TRANSLATE` paints the
  corner pixels (column 60, top and bottom row) with alpha up to 0x88; `GENERIC` leaves them transparent, because
  their sample lies outside its band (US-059). A clamp-everywhere build gives 0 differing cases.

### 2. REPEAT overflows a `jint` above 32767 texels (read, not measured)
`checkBoundsRepeat` (`PiscesPaint.c`) wraps with `lmod(*la, (max+1) << 16)`. `max + 1` is a `jint`, so the shift
overflows for a sub-rectangle wider or taller than 32767 texels, and the wrap uses a wrong modulus. Whether a SW
texture can be that large was not checked.

## Proposed fix
- Part 1: decide whether an edge column is fractional before the row scaling truncates its fraction to 0, so a
  sliver keeps a coverage of about its own width times the row's.
- Part 2: shift a `jlong`: `((jlong) max + 1) << 16`.

## Acceptance criteria
- A unit test in `test.com.sun.pisces` draws a box whose edge lies 1/65536 px past a pixel boundary and pins the
  edge column's coverage; the review's twin geometry (scale 0.7, X = 60.3) gives identical `SCALE_TRANSLATE` and
  `GENERIC` frames. The test fails before the fix.
- REPEAT wraps correctly on a sub-rectangle wider than 32767 texels (a unit test), or the story records the size
  limit that makes it unreachable.
- `PiscesTexturePaintEdgeTest`, `PiscesTextureSamplingTest` and `PiscesGoldenRenderTest` pass unchanged; a pin or
  golden step that moves is re-captured in its own commit with the reason.

## Notes
- Both pre-existing: the same at HEAD (c9883c820b) and untouched by US-059.
- Evidence: Claude scratchpad of session aaa1f9d7, `us059/review-r1.json` (the C lens: finding C2 and its
  out-of-scope line) and the probe `rev-clens/p.c`, mode `twin`.
- Related: US-059 (its Residual names part 1), US-046 (the Java port reproduces the paint, and these with it).
