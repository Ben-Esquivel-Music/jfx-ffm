# US-055 — Trim every pass of a multi-pass box kernel whose size is not an odd whole number

**Status:** ✅ Done (2026-10-04, PR pending; option A, trim every pass) · **Found:** 2026-10-02, US-011's
acceptance criterion "decide and record the fractional-size kernel"

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

## Resolution (2026-10-04)
- **Decision: (A) trim every pass.** It is the story's recommendation and the only option where the kernel is the
  box the class javadoc describes, repeated, for every size: continuous in `s`, and the closest of the three to the
  continuous box (table above). (B) keeps a kernel up to 2 px per pass wider than asked that nothing in the code
  intended; (C) makes every GPU blur grow in 2 px steps, which shows on animated widths and scaled nodes. The cost
  of (A), as the story says: for a non-odd size the GPU moves further from the SW box peers, which round up to
  `ceil(s) | 1` by design. The decision is recorded in the class javadoc of `BoxRenderState` (`:66-78`), with the
  two rejected options, and the SW caveat gains a sentence saying that the SW box loops differ from the trimmed
  repeated box the `LinearConvolve` peers apply (every GPU pipeline, and the SW fallback for a spread or an input
  that is not `swCompatible`).
- **Fix:** `validateWeights()` copies the trimmed box once, `box[] = Arrays.copyOf(ik, klen)` (`:528`), and every
  further pass convolves with `box[k]` instead of ones, still in place from the last tap backwards. The second loop
  now runs `while (i >= 0)` and weighs every tap, `ik[i-k] * box[k]` for `k = 0..i`, because with a trimmed
  `box[0]` the tap `ik[0]` no longer keeps its value. Nothing else changed: the tap count `ceil(s) | 1`,
  `getPassKernelSize()`, the spread, the normalisation, the zero padding and every caller. Size 4 over 2 passes
  gives `[0.25 1 2 3 3.5 3 2 1 0.25] / 16` (was `[0.5 1.5 2.5 3.5 4 3.5 2.5 1.5 0.5] / 20`). An odd integer size
  trims nothing (`box` is all ones) and its kernel is bit-identical to before; one pass and `GAUSSIAN` are
  unaffected.
- **Correction to this story:** the Status line read "the variances below are arithmetic from the weights, not
  measured on hardware". They are now measured too (hardware check below); the "every box trimmed" column matches.
  The javadoc lines it cited (`:41-78`) are now `:42-98`.
- **Tests:**
  - `BoxRenderStateWeightsTest` (27 → 30 tests): the pin of the old kernel, `nonOddSizeTrimsTheFirstBoxOnly`, is
    deliberately changed to `nonOddSizeTrimsEveryPass` against `BoxKernels.repeatedTrimmedBox`, a plain
    nested-loop convolution of the trimmed box with itself; the class javadoc says which test it replaces and why.
    New case 7.3/2.5. Hand-worked kernels pin the oracle itself: 4 x 2 (sum 16), 4 x 3 (sum 64), 2.5 x 3
    (sum 15.625) and 7.25 x 2 (sum 52.5625). Odd sizes keep their tests unchanged.
  - **Exactness rule, fixed before the change was run:** a pass is compared bitwise with the oracle and with its
    mirror image when a bit count (`provablyExact`: `passes * f + b <= 53`, `f` the fraction bits of the trimmed
    tap, `b` the bit length of `klen^passes`) proves every value before the final division exact in `double`;
    otherwise within one `float` ulp, with the bound argued in the javadoc. The new test
    `onlyOnePassIsNotProvablyExact` pins that exactly one pass is ulp-compared: pass 1 of 42.5/7.3 over 3 passes
    (`f = 21`, 73 bits). An independent reviewer simulated 20,000 sizes against the rule: no violation.
  - **Test-first:** against the HEAD class (patched in from outside the tree), `nonOddSizeTrimsEveryPass` fails
    all 7 multi-pass non-odd cases; the other 23 pass. After the fix, 30/30.
  - **Mutants**, each patched into the module from outside the repo; all 7 killed:

    | mutant | weights test failures | golden test failures |
    | --- | --- | --- |
    | first box trimmed only (the old kernel) | 7 | 10 |
    | no trimming at all | 8 | 10 |
    | last pass trimmed only | 7 | 10 |
    | trimmed tap `1 - excess` (not `/ 2`) | 16 | 10 |
    | second loop `while (i > 0)` (the old loop) | 14 | 10 |
    | `k < i` in the second loop | 22 | 34 |
    | `box[0]` not applied | 8 | 10 |

  - `BoxBlurInputTransformTest` (37/0) and `BoxRenderStateScaledInputTest` (19/0) stay green.
- **Golden (`DecoraJavaGoldenTest`, `-Djfx.parity.require=true`); the golden was not regenerated:**
  - Exactly the 8 `LinearConvolveShadow/box` rows `h=4 v=6 passes=3` (spread 0.3 and 1.0, black and tinted, 64x48
    full frame and 257x129 hash) move. The other rows of US-011's deviation have odd sizes (the clip rows are 9x9x3)
    and do not; neither does any other row. Run against the HEAD class, the 8 fail with only `KERNEL_ORACLE`.
  - They are handled by extending US-011's reviewed deviation, `DecoraCorpus.KernelDeviation`
    `BOX_KERNEL_TAP_COUNT`, whose id is kept stable (it is printed on every row's report line) and whose javadoc
    now says it covers both changes: US-011's tap count on 28 rows and US-055's trimming on the 8 `4x6x3` ones. Its
    oracle (`BoxKernels.ORACLE`) is now the repeated trimmed box for non-odd sizes; `FIRST_BOX_TRIMMED` keeps the
    US-011 kernel for the controls. The three checks per row are unchanged: the frozen pre-US-011 recipe still
    reproduces the golden, production differs from it, and production equals the render with the independent kernel
    exactly.
  - On the 8 rows production now differs from the golden by up to 16 steps at spread 0.3 (4,426 differing values on
    a 64x48 row, 34,441 on a 257x129 row) and up to 255 steps at spread 1.0 (472 and 1,406); with the HEAD class it
    was 2 to 5 steps. At spread 1.0 the weights stay un-normalised (`sum += (1 - sum) * spread` makes the divisor 1), so the
    shadow saturates inside and the narrower kernel shows on its rim at full scale. That is the kernel change itself:
    the third check holds production to the independent kernel exactly on every row.
  - New negative control `revertedTrimmingOfEveryPassOnKernelDeviationRowIsReported` (2 runs: the 64x48 row and the
    257x129 hash row, spread 0.3, black and tinted): the first-box-only kernel on a `4x6x3` row is reported with
    only `KERNEL_ORACLE`. `productionOffTheOracleOnKernelDeviationRowIsReported` now uses "round up like SW" (C) as
    its wrong kernel. 368 → 370 tests. Against the HEAD class the class fails 10: the 8 rows,
    `fixedKernelDoesNotReproduceTheGolden` and `revertedFixOnKernelDeviationRowIsReported` on the tinted 257x129 row.
  - Report: against the HEAD class only the 8 `h=4 v=6 passes=3` lines and the `LinearConvolveShadow/box` summary
    line differ; every other line is byte-identical.
- **Windows run** (`mvn -B -ntp -pl buildtools/jslc,modules/javafx.graphics clean test -Djfx.parity.require=true`,
  counts from the Maven log): graphics 25,910 run, 0 failures, 0 errors, 356 skipped; jslc 159/0. Only
  `BoxRenderStateWeightsTest` (+3) and `DecoraJavaGoldenTest` (+2) changed counts. No `hs_err`. Linux not run: the
  change is pure Java arithmetic in `double` with a proof of exactness, and US-011's Linux run showed the same
  counts per class as Windows.
- **Hardware check (met):** `Node.snapshot` with a fixed viewport of a 1 px line blurred along each axis, AMD
  Radeon R7 240, D3D9Ex and the Java SW pipeline. PRE is the runtime plus the HEAD class, POST the runtime plus the
  class built from the tree. Each measured alpha profile is compared with the expected profile of each kernel
  rounded to 8 bits:

  | scene | s x passes | variance PRE | POST | trimmed every pass (exact / 8-bit) | POST vs 8-bit, max steps |
  | --- | --- | --- | --- | --- | --- |
  | `BoxBlur(4,4,2)` (even) | 4 x 2 | 3.451 | 3.000 | 3.000 / 3.000 | 0 |
  | `BoxBlur(4,4,3)` (even) | 4 x 3 | 5.368 | 4.378 | 4.500 / 4.378 | 0 |
  | `BoxBlur(6,6,3)` (even) | 6 x 3 | 11.082 | 9.681 | 9.500 / 9.681 | 0 |
  | `BoxBlur(8,8,2)` (even) | 8 x 2 | 12.150 | 11.000 | 11.000 / 11.000 | 0 |
  | `BoxBlur(5,5,2)`, parent scale 1.25 | 6.25 x 2 | 7.394 | 6.898 | 6.800 / 6.898 | 0 |
  | `BoxBlur(5,5,2)`, snapshot scale 1.25 | 6.25 x 2 | 7.394 | 6.898 | 6.800 / 6.898 | 0 |
  | `BoxBlur(5,5,3)`, parent scale 1.25 | 6.25 x 3 | 11.750 | 9.833 | 10.200 / 9.833 | 0 |
  | `BoxBlur(7,7,2)`, parent scale 1.25 | 8.75 x 2 | 13.189 | 13.000 | 12.800 / 13.000 | 0 |
  | `BoxBlur(5,5,2)`, parent scale 1.5 | 7.5 x 2 | 11.465 | 9.444 | 9.600 / 9.444 | 0 |
  | `DropShadow` radius 5 (box 4) | 4 x 3 | 5.368 | 4.378 | 4.500 / 4.378 | 0 |
  | `DropShadow` radius 5, spread 0.5 (H / V) | 4 x 3 | 5.401 / 5.546 | 4.383 / 4.530 | 4.500 / 4.530 | 1 / 0 |

  - POST D3D is the trimmed-every-pass kernel rounded to 8 bits, exactly, on 27 of 28 axis-rows (1 step on the
    spread shadow's H axis); PRE D3D is the first-box-only kernel within 1 step on all 28. The gap between the exact
    and measured variance is the 8-bit rounding of the profile itself. Every profile is mirror-symmetric (0 steps)
    with its alpha centroid at +0.000 px.
  - `BoxBlur(7,7,2)` at 1.25 was flagged in advance as weak: its three candidate kernels are within 0.5 to 0.8
    steps of each other. The 8-bit comparison still separates them (POST 0 steps from trimmed-every-pass).
  - Controls, identical PRE and POST on D3D: `BoxBlur(5,5,3)` (odd), `BoxBlur(4,4,1)` (one pass) and
    `BoxBlur(4,4,2)` at 1.25 (s = 5, odd). On SW every scene is identical PRE and POST except the spread shadow,
    which runs the `LinearConvolve` fallback and moves from nearest-first-box-only to nearest-trimmed-every-pass;
    the SW box peers stay boxes of `ceil(s) | 1`.
  - Not verified here: ES2 (the Windows build does not make it) and Metal, whose `LinearConvolve` peers read the
    same weights.
- **Robot suite not run (waiver),** as for US-011: the change moves GPU pixels of multi-pass box blurs and box
  shadows with a non-odd device box size, which includes the default `DropShadow` (`THREE_PASS_BOX`) at an even box
  or a non-integer render scale. No robot test uses a `BoxBlur`, `DropShadow`, `InnerShadow`, `Shadow` or
  `BlurType`; `ShapeCacheTest` compares two renders of the same shadow with each other. A `FULL_TEST`/`USE_ROBOT`
  run takes over the desktop for about 45 minutes, so it is left to the pre-push check (`openjfx-conventions`):
  `mvn -pl tests/system test -DFULL_TEST=true -DUSE_ROBOT=true`.
- **Review:** an independent code review of the diff found no issues (correctness, exactness argument, worked
  examples, golden handling, style).
- **Evidence:** Copilot session 06bd1f2a, `files/us055/`: `before/` and `after/` (surefire reports),
  `mutants/` (one source per mutant), `full-gate.txt`, `hw/US-055-hardware-report.md` with the probe
  `Us055Probe.java`, the dumps in `hw/out/` and `hw/variance-analysis.txt`.
- **Upstream:** `openjdk/jfx` master still trims the first box only. A candidate for upstream together with
  US-011.
