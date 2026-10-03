# US-055 — Trim every pass of a multi-pass box kernel whose size is not an odd whole number

**Status:** 📋 Ready (filed 2026-10-02 from the decision US-011 recorded; read: `BoxRenderState.java:41-78` and
`validateWeights()`; the variances below are arithmetic from the weights, not measured on hardware) · **Found:**
2026-10-02, US-011's acceptance criterion "decide and record the fractional-size kernel"

## Story
As a JavaFX app developer using a multi-pass `BoxBlur` or a box-type shadow whose box is not an odd whole number of
pixels (an even width, or any size under a node scale, a snapshot scale or a HiDPI render scale such as 125 %),
I want every pass to blur by the stated size,
so that the GPU blur is as wide as asked, grows smoothly with the size, and is the repeated box the effect promises.

## Problem
For a pass size `s` that is not an odd integer, `validateWeights()` builds the first box with `klen = ceil(s) | 1`
taps and trims its two end taps to `1 - (klen - s) / 2` each, so that the box sums to `s` (the class javadoc,
`:41-64`). It then convolves that box `passes - 1` times with `klen` UNtrimmed taps of 1. After US-011 the kernel is
symmetric and normalised, but it is `trimmed(s) ⊛ ones(klen)^(passes-1)`, not the `passes`-fold box of size `s`.
The later passes act as boxes of `klen`, up to 2 px wider than `s`. Nothing in the code says this is intended: the
trimming is written and documented for one box only. US-011 kept the kernel, because changing the trimming is a
behaviour change of its own, and pinned it in `BoxRenderStateWeightsTest` (`nonOddSizeTrimsTheFirstBoxOnly`).

Kernel variance in px² (arithmetic; a box of `klen` ones has variance `(klen² - 1) / 12`):

| size x passes | now (first box trimmed) | every box trimmed | SW, boxes of `ceil(s) \| 1` | continuous box, `n s² / 12` |
| --- | --- | --- | --- | --- |
| 4 x 2 | 3.50 | 3.00 | 4.00 | 2.67 |
| 4 x 3 | 5.50 | 4.50 | 6.00 | 4.00 |
| 4.5 x 3 | 5.78 | 5.33 | 6.00 | 5.06 |
| 7.25 x 2 | 11.08 | 8.83 | 13.33 | 8.76 |
| 7.25 x 3 | 17.75 | 13.24 | 20.00 | 13.14 |

So a 7.25 px box over 3 passes blurs about 16 % wider (in sigma) than the 3-fold box of 7.25, and jumps when `s`
crosses an odd integer, because `klen` does.

### Who is affected
- **GPU pipelines (D3D, ES2):** every `BoxBlur` with 2 or 3 iterations and every `TWO_PASS_BOX`/`THREE_PASS_BOX`
  shadow whose device box size is not an odd integer:
  - even widths (the FX `BoxBlur` takes whole widths; a box shadow's box is `Math.round(width / 3)`, so `DropShadow`
    radius 5 gives box 4);
  - every size under a non-integer node, snapshot or HiDPI scale.
- **Software pipeline:** only the `LinearConvolve` fallback (a shadow spread, or an input that is not `swCompatible`).
  The SW box peers use boxes of `ceil(s) | 1` by design (the class javadoc's "SOFTWARE LIMITATION CAVEAT").
- **Not affected:** odd integer sizes (no trimming), one pass, and the `GAUSSIAN` blur type.

## Options
- **(A) Trim every pass (recommended).** The kernel becomes the `passes`-fold trimmed box: the javadoc's box
  repeated, continuous in `s`, and the closest of the three to the continuous box. For non-odd sizes the GPU moves
  further from SW, which rounds up by design.
- **(B) Keep the current kernel** and document it in the class javadoc as intended.
- **(C) Round up like SW** (`ceil(s) | 1`, untrimmed, for every pass). GPU then equals SW for every size, but the
  blur grows in steps of 2 px as the size changes (animated widths, scaled nodes).

## Acceptance criteria
- The maintainer chooses (A), (B) or (C), and the choice is recorded in the class javadoc.
- (A) or (C): `validateWeights()` builds the chosen kernel. `BoxRenderStateWeightsTest`'s pin of non-odd sizes is
  changed to the chosen kernel, computed independently, as a deliberate test change. Odd sizes are unchanged.
- (A) or (C): the `DecoraJavaGoldenTest` rows with a non-odd box size that read the weights (the
  `LinearConvolveShadow/box` rows of size 4 x 6 x 3 with spread) move. They are handled by extending the reviewed
  deviation US-011 added. **Never regenerate the golden.**
- (A) or (C): a hardware check on D3D (`Node.snapshot`) with an even box and with a scaled node: the measured kernel
  variance is the chosen kernel's, and the profile stays symmetric.

## Notes
- Upstream too: `openjdk/jfx` master has the same trimming (checked 2026-10-02).
- Related: US-011 (the tap count, fixed), US-056 (the weights cache in the same method), US-012 (the scaled pass size).
