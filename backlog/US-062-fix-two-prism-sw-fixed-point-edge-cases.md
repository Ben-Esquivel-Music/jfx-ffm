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
- `PiscesTexturePaintEdgeTest`, `PiscesTextureSamplingTest`, `PiscesGoldenRenderTest` and `PiscesCorpusGoldenTest`
  pass unchanged; a pin or golden step that moves is re-captured in its own commit with the reason.

## Notes
- Both pre-existing: the same at HEAD (c9883c820b) and untouched by US-059.
- Evidence: Claude scratchpad of session aaa1f9d7, `us059/review-r1.json` (the C lens: finding C2 and its
  out-of-scope line) and the probe `rev-clens/p.c`, mode `twin`.
- Related: US-059 (its Residual names part 1), US-046 (the Java port reproduces the paint, and these with it).
- US-046 deleted the C. The Java sites are `PiscesBlit.java:133-136` (`emitLineSource8888Pre`), `:168-171`
  (`emitLinePTSource8888Pre`) and `:278-281` (`emitLinePTSourceOver8888Pre`, the `SRC_OVER` texture path of the
  probed twins) for part 1, and `PiscesPaint.java:399-402` (`checkBoundsRepeat`) for part 2.
  `PiscesCorpusGoldenTest` steps 415-434, 1579-1650 and 1651-1710 pin the current behaviour; 1651-1710 show that
  REPEAT past 32767 texels is reachable (SW's maximum texture size is `Integer.MAX_VALUE`).

## Found while porting (US-046)
The Java compositor of US-046 reproduces the C of 42a5072596, so where the C completed a call these outcomes are
unchanged. Where the C crashed or went outside an array, the Java compositor throws instead. None of them is fixed;
a fix changes a pinned outcome and is re-captured or re-pinned in its own commit.
- **A. An alpha mask under `COMPOSITE_CLEAR`.** The C clears through the no-mask blit, which reads the row coverage
  that a mask call never sets (a null pointer): an access violation. Java: `NullPointerException`.
- **B. An LCD mask under any rule but `SRC_OVER`, or with a gradient or texture paint.** The C has no routine there
  (a null function pointer). Java: `NullPointerException`.
- **C. A paint that reads the texture `drawImage` detached.** A fill, mask or `emitAndClearAlphaRow` after
  `drawImage` without a new paint (the texture paint, or the multiply paint over a colour or a gradient unless its
  row-size guard below refuses the row) read through a null pointer in the C. Java: `NullPointerException`.
- **D (Issue D). An `emitAndClearAlphaRow` whose delta range ends exactly at the end of the delta array.** The
  range check of `PiscesRenderer.emitAndClearAlphaRow` (`PiscesRenderer.java:354`) is off by one: it lets
  `xOff + (xTo - xFrom) == alphaDeltas.length` through, although the row has `xTo - xFrom + 1` pixels. The C read
  and zeroed one int past the caller's array. Java gives every pixel that has a delta the value a long enough array
  gives it, zeroing the deltas as it consumes them, and throws `ArrayIndexOutOfBoundsException` at the pixel whose
  delta would lie past the end; that pixel and the later ones keep the background. Pinned by
  `PiscesDeltaRangeAtArrayEndTest` (7 cases); corpus steps 3801-3807 are the in-range twins, captured from the C.
- **E. A NaN LCD gamma.** `setLCDGammaCorrection(Float.NaN)` passes the `gamma <= 0` check, the tables then hold
  `0x80000000`, and an LCD mask over a channel that is not white computes a negative table index. The C read the
  tables out of bounds and crashed. Java throws `ArrayIndexOutOfBoundsException` (index -32768 under zero coverage,
  -32640 under coverage 128) and leaves that pixel and the later ones as they were. Pinned by
  `PiscesNanLcdGammaTest` (3).
- **F. Two `int` wraps.** REPEAT on a sub-rectangle whose width or height is a multiple of 65536 texels makes the
  modulus of part 2 zero: the C faulted on the division, Java throws `ArithmeticException`. A `clearRect` on a
  surface of 2^29 pixels or more wraps the `int` byte count of `clearRect8888_any`; a negative count crashed the
  C's `memset`, and Java throws.
- **G. A coverage sum outside the alpha map.** `emitAndClearAlphaRow` indexes the alpha map with the running sum of
  the deltas, unchecked (`PiscesBlit.java:352`, `:438`, `:521`, `:694`; `PiscesBlit.c:386`, `:490`, `:591`, `:752`
  at 42a5072596). The C composited the remaining pixels with a garbage coverage byte. Java throws
  `ArrayIndexOutOfBoundsException` after the earlier pixels and leaves the rest untouched.
- **H. `PiscesRenderer.inputImageCheck` overflows `int`** (`PiscesRenderer.java:435`), so `fillAlphaMask`,
  `fillLCDAlphaMask` and `drawImage` accept an offset near 2^31. The C read up to about 2 GiB past the array
  (undefined behaviour, normally an access violation). Java throws `ArrayIndexOutOfBoundsException`, or draws
  nothing when the blit's index `am = a + w` overflows (`PiscesBlit.java:587`).
- **`getRGB`/`setRGB` checks only the C had.** `AbstractSurface.rgbCheck` lets two kinds of call through that the C
  rejected with `IllegalArgumentException` "Out of range access of buffer" (`getRGB`) or "out of range access of
  buffer" (`setRGB`), leaving both arrays untouched: a last row whose whole scan line does not fit in the array (the
  C requires `offset + height * scanLength <= length`), and arguments whose sums overflow `int` in `rgbCheck`. The
  Java compositor keeps the C's checks and texts. Corpus steps 79, 80 and 41 random steps pin the first, steps
  3577-3584 the second.
- **"Invalid dimensions".** The multiply paint over a gradient (`genTexturePaintMultiply`) refuses a row of
  `w >= INT_MAX / height / 4` pixels: it prints `Invalid dimensions: width: <w>, height: <height>` and composites
  the paint buffer as it is. The C printed the line to its standard error; Java prints the same line to
  `System.err`. Pinned by `PiscesMultiplyRowGuardTest` (on every heap) and by
  `PiscesCorpusGoldenTest.aStaleMultiplyPaintOnARowTooWideToMultiplyCompositesItsPaintBufferAsItIs` (about 4.6 GiB
  of heap).
- **Renderer state after an exception.** After an exception of class A, B, C, E or H the renderer keeps the mask
  (`fillAlphaMask`/`fillLCDAlphaMask`), the `drawImage` texture or the delta row, and later calls on that renderer
  may composite through it until the next mask call or paint change. After Issue D and class G nothing a later valid
  call reads is left. The C crashed in classes A, B, C, E and H, so it has no state to compare.
