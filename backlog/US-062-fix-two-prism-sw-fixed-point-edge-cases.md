# US-062 — Fix two fixed-point edge cases in `prism_sw`: a box sliver painted as a column, and REPEAT past 32767 texels

**Status:** ✅ Done (2026-10-18, PR pending) · **Found:** 2026-10-16, by the C review of US-059

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

## Resolution (2026-10-18)
- **Part 1, the box sliver** (`PiscesBlit`). In a row the box covers in part, `emitLineSource8888Pre` (colour paint
  under `SRC`, and `COMPOSITE_CLEAR`), `emitLinePTSource8888Pre` (texture and gradient paints under `SRC`) and
  `emitLinePTSourceOver8888Pre` (the same under `SRC_OVER`) now decide from the unscaled `elLfrac`/`elRfrac` whether
  the first and the last column are edge columns, as `emitLineSourceOver8888Pre` already did, and blend them at the
  fraction scaled by the row's coverage, which is 0 for a 1/65536 px sliver. Before, the texture routines painted the
  sliver as an interior column at the row's coverage. The colour `SRC` routine counted its interior columns from the
  unscaled fractions already, so with a left sliver it began its interior run in the sliver, blended the right edge
  one column early and left the last column as it was, and it left a right sliver as it was. In a row where no edge
  fraction scales to 0 the pixels are unchanged.
- **Part 2, REPEAT** (`PiscesPaint`). `checkBoundsRepeat` wraps modulo `((long) max + 1) << 16`. The `int` shift
  overflowed from 32768 texels on and was 0 for a multiple of 65536 texels, an `ArithmeticException` (a division
  fault in the C). Nothing else on the REPEAT path overflows: the texture transform entries are `long` fields, so the
  row starts of `SCALE_TRANSLATE` and `GENERIC` are `long` sums. The class comments of both classes say that US-062
  departs from the C of 42a5072596 at these two points.
- **Tests.** New `test.com.sun.pisces.PiscesFixedPointEdgeTest`, 79 tests through the public
  `PiscesRenderer`/`JavaSurface` API. Before the fix 67 fail (47 failures and 20 errors); after it all pass.
  - 8 sliver tests: colour `SRC`, texture `SRC`, texture `SRC_OVER` and, as the control, colour `SRC_OVER`, each on a
    left and a right sliver of a box with a fractional top and bottom row. Every pixel is checked against the box's
    area in doubles (1 per 8-bit channel; the largest difference is 0.86), a pixel outside the box has to be the
    background, and the sliver is pinned exactly: under `SRC_OVER` the background, under `SRC` the S + D that the
    rows the box covers in full give it (this compositor's `SRC` is S + (1 - c) D; see US-063). Before the fix the
    six tests that are not controls failed; with a left sliver the colour `SRC` route showed the one-column shift.
  - The review's twins: all 5184 nearest-neighbour cases of `p.c` mode `twin`, a mirror-diagonal `GENERIC` draw
    against its axis-aligned `SCALE_TRANSLATE` twin with the transposed texture. Before the fix 16 differed, all at
    scale 0.7 and X = 60.3, the review's 16; now none does.
  - 70 REPEAT tests: sub-rectangles of 32767 (the control), 32768, 40000, 65535, 65536 and 65537 texels wide and one
    of 65536 texels high, through `setTexture` + `fillRect` and through `drawImage`, nearest and bilinear (on texel
    centres, and halfway between texels with a pixel at the seam between the last texel and texel 0). The oracle is
    the texel under the sample, from the transform in doubles, wrapped with `Math.floorMod`. Before the fix the 10
    at 32767 passed, the 40 at 32768, 40000, 65535 and 65537 read wrong texels and the 20 at 65536 threw
    `ArithmeticException`.
- **Mutants**, on copies outside the repository: reverting part 1 in the colour `SRC`, texture `SRC` and texture
  `SRC_OVER` routine fails 2, 2 and 3 tests; reverting part 2 fails 60; painting a sliver at the row's coverage, 2;
  wrapping modulo `max`, 70; leaving a `SRC` sliver unpainted, 2. The unmutated copy passes. The review built six
  more (one edge or the column count of a single routine reverted, a sliver at the row's coverage, an unsigned
  32-bit modulus, the control route decided from the scaled fraction); each fails a test.
- **Corpus.** `PiscesCorpusGoldenTest` is re-captured from the Java compositor of US-062, in its own commit, as
  `pisces-corpus-US-062-pending.bin` (775,062 bytes, SHA-256
  `cf29d98b6bc61b00d299a6aa591ab5ccf7d2952ac4cb11e6ff6a7a8cecd6c46d`, producer `native+java-US-062` in its header),
  and `pisces-corpus-42a5072596.bin`, the capture from the C, is deleted. 53 steps moved and 3,764 are byte-identical
  to the C's. Captures with one fix at a time attribute each moved step to one fix (the two sets are disjoint and
  make up the 53):
  - 25 by the sliver: the `SRC` and `CLEAR` fills of the `fillRect` boxes `lfrac1-tfrac256` and `rfrac1-bfrac1`
    (steps 295, 296, 329, 330, 363 and 397), `us062-sliver` 13 and 18 (428 and 433), `drawImage-edges` 36 (1532)
    and 16 `us062-twins` steps of sub-rectangle 1 (1592 to 1638). Every moved pixel lies in the sliver column of a
    row the box covers in part, or in the last two columns of such a row of a colour `SRC` or `CLEAR` fill.
  - 28 by REPEAT: `wide-repeat` 20 to 27 (1671 to 1678, 32768 texels, wrapped below texel 0; past the last texel the
    overflowed modulus happened to give the right texel) and 40 to 51 and `tall-repeat` 0 to 7 (1691 to 1710, 40000
    texels). Every pixel of their nearest-neighbour steps is the texel of an independent `floorMod` oracle.
  - The Notes above named steps 415 to 434, 1579 to 1650 and 1651 to 1710. Seven moved steps lie outside them (295,
    296, 329, 330, 363, 397 and 1532); all seven are sliver boxes.
  - The C cannot give the new outcomes, so there is no C cross-check: the deleted file is the capture from the C,
    and the step-by-step comparison proves every other step identical to it. The class comment lists the moved
    steps and the reason. The file is renamed to the hash of the commit that lands US-062, as the goldens were.
- **Found while porting.** No Java-only pin of F existed, so no pin had to move. F's REPEAT half is fixed by part 2,
  and the 65536-texel cases of `PiscesFixedPointEdgeTest` pin the wrap. Every other item (A to E, F's `clearRect`
  half, G, H, the `getRGB`/`setRGB` checks, "Invalid dimensions" and the renderer state after an exception) is
  unchanged: the Java exception that replaced the C's crash or out-of-bounds access is accepted, and the pins the
  section names (`PiscesDeltaRangeAtArrayEndTest`, `PiscesNanLcdGammaTest`, `PiscesMultiplyRowGuardTest`, the
  corpus steps and the corpus method it lists) still hold.
- **Filed:** the review of this story found that the compositor's `SRC` blends a pixel a box edge covers by c as
  S + (1 - c) D, which overflows over an opaque destination (the same in the C, not changed here): US-063.
- **US-061 stamp.** `pisces-golden-US-061-pending.bin` is renamed `pisces-golden-ee923ace41.bin`, bytes unchanged,
  and the US-061 story and its README row carry ee923ace41, PR #33.
- **Criteria.** 1 is met by the sliver tests and the twin grid, red before the fix. 2 is met by the REPEAT tests
  past 32767 texels, red before the fix. 3: `PiscesTexturePaintEdgeTest` (175), `PiscesTextureSamplingTest` (86) and
  `PiscesGoldenRenderTest` (7) pass unchanged; `PiscesCorpusGoldenTest` moved and is re-captured in its own commit,
  with the reason in its class comment.
- **Tests** (Windows x64, Maven 3.10.0, JDK 27): `PiscesFixedPointEdgeTest` 79, 0 failures; `test.com.sun.pisces`
  and `test.com.sun.prism.sw` 368, 0 failures, 0 skipped (`-Xmx6g`); the module run with `clean` and
  `-Djfx.parity.require=true`, javafx.graphics 26,107, 0 failures, 0 errors, 356 skipped (US-061: 26,028; the only
  per-class change is the new class), javafx.base 5,510, 0 failures, 22 skipped, jslc 159, 0 failures. WSL Linux x64
  (a fresh clone of ee923ace41 with the change): `test.com.sun.pisces` 366, 0 failures, 0 skipped, the corpus exact.
- **Evidence:** Claude scratchpad of session 0e6e2804, `us062/` (`notes/A.md`, `notes/R.md`, `corpus/`, `mutants/`,
  `logs/`, `gate/`).
