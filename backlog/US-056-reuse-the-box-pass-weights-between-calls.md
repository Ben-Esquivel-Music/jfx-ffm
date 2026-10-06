# US-056 — Reuse the box-blur pass weights instead of rebuilding them on every call

**Status:** ✅ Done (2026-10-06, PR pending; filed 2026-10-02 from the implementation of US-011; the rebuild cost
was not measured) · **Found:** 2026-10-02, while
writing the weights test of US-011

## Story
As a JavaFX app developer animating a `BoxBlur` or a box-type shadow,
I want the box kernel built once per pass size, not on every read,
so that the box effects do no needless work and make no needless garbage per frame.

## Problem
`BoxRenderState` keeps its kernel in `weights` and keys it with `weightsValidSize` and `weightsValidSpread`
(`:103-104`). `validateWeights()` returns early when the buffer exists and both keys match the pass (`:471-476`).
Nothing ever assigns the two keys. They stay `0.0f`, and the pass size is at least `1.0f` there, so the early return
never happens. Every call to `getPassWeightsArrayLength()` or `getPassWeights()` builds the kernel again: it allocates
a `double[]` of up to 127 taps, convolves it `passes - 1` times and refills the buffer.

`GaussianRenderState.validateWeights()` sets its keys (`weightsValidRadius = r; weightsValidSpread = s;`, `:556-557`)
and does not have the problem. The `openjdk/jfx` master copy of `BoxRenderState` declares and compares the two fields
and never assigns them either (checked 2026-10-02), so this is not a fork regression.

### Who is affected
- CPU time and garbage only. Each rebuild produces the same weights, so no pixel changes.
- Every box pass that reads the weights: the GPU `LinearConvolve` and `LinearConvolveShadow` peers for every
  `BoxBlur` and box shadow, and the SW `LinearConvolve` fallback (a shadow spread, or an input that is not
  `swCompatible`). The SW box peers never read the weights.

## Proposed fix
After the kernel is built, set `weightsValidSize = pSize; weightsValidSpread = passSpread;`, as
`GaussianRenderState` does. The two keys, with the final `blurPasses`, determine the kernel.

## Acceptance criteria
- A unit test, through public API, shows that a second read for the same pass and size does not rebuild the kernel,
  for example a sentinel written into the returned buffer survives the next `getPassWeights()`. The test also shows
  that a new pass size or spread does rebuild it. It fails before the fix and passes after.
- The weights test of US-011 (`BoxRenderStateWeightsTest`) and `DecoraJavaGoldenTest` (`-Djfx.parity.require=true`)
  are unchanged. The golden test's per-row output is byte-identical before and after.

## Notes
- Related: US-011 (the kernel's tap count, the same method) and US-055 (the trimming of non-odd sizes).

## Resolution (2026-10-06)
- **Fix:** `BoxRenderState.validateWeights()` now sets `weightsValidSize = pSize; weightsValidSpread = passSpread;`
  after it builds the kernel, as `GaussianRenderState` does. This is the only production change (+2 lines).
- **Tests:** `BoxRenderStateWeightsTest` gains four tests. They use only the public API and compare the whole
  buffer bit for bit:
  - `samePassAndSizeReusesTheKernel`: a sentinel survives a second read and a second `validatePassInput` of the same
    pass, and every read returns the same buffer.
  - `newPassSizeRebuildsTheKernel`: sizes 3, then 5, then 3 give the exact repeated box each time.
  - `newSpreadRebuildsTheKernel`: sizes 3/3 with spread 0.5 give `[1 2 3 2 1] / 9`, then
    `[0.2 0.4 0.6 0.4 0.2]`, then `/ 9` again.
  - `noBlurPassesReusesTheOneTapKernelForBothPasses`: with zero blur passes, `[1 0 0 0]` is kept for sizes 5 and 9.
- **Before and after the fix:** on the unfixed code 34 tests ran and 2 failed (the two reuse tests). After the fix
  all 34 pass. The 30 tests from US-011 are unchanged.
- **Mutants:** each half-fix fails at least one new test.

  | Mutant | Failures |
  | --- | --- |
  | Size key only | `newSpreadRebuildsTheKernel` |
  | Spread key only | both reuse tests |
  | Unfixed code | both reuse tests |
  | Size comparison removed | 20 failures and 6 errors, `newPassSizeRebuildsTheKernel` among them |
- **Golden:** `DecoraJavaGoldenTest` with `-Djfx.parity.require=true` ran 370 tests, 0 failed and 0 skipped, before
  and after. Its per-row output is byte-identical (md5 `a2701ce7eb18aff43faf52de71b8b771`, 396 lines).
- **Review:** two independent read-only reviews, one of correctness (stale-cache paths, peer use of the buffer) and
  one of the tests and conventions, found no issues.
- **Not run:** the robot suite. No pixel changes and no robot test uses box effects.
- **Upstream:** `openjdk/jfx` master has the same missing assignments. This could go upstream together with US-011
  and US-055.
