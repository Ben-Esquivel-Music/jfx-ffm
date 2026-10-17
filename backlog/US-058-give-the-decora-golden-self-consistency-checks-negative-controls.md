# US-058 — Give the Decora golden test's self-consistency checks negative controls

**Status:** ✅ Done (2026-10-06, PR #28; filed 2026-10-02 from the independent review of US-011;
read: `DecoraJavaGoldenTest`
`checkSelfConsistency`, `enum Kind`, the class javadoc, `DecoraCorpus.GoldenRow`; whether any row still sets
`edgeRows >= 0` was not checked) · **Found:** 2026-10-02, review of US-011 (finding L4)

## Story
As a maintainer of the Decora software peers,
I want every check of `DecoraJavaGoldenTest` to have a control that shows it can fail,
so that a green golden run means the clipped renders were really compared, and the class javadoc's promise holds.

## Problem
The class javadoc of `DecoraJavaGoldenTest` says "The negative controls prove that every branch of the comparison can
fail." Two kinds of finding have no control at all: no test asserts `Kind.SELF_CONSISTENCY` or `Kind.EDGE_ROWS`
(checked with `git grep`). Both come from `checkSelfConsistency`. It holds a clipped row's Java render against the
same kernel rendered without the clip, and against the golden's `sseSelf` record:
- `SELF_CONSISTENCY`: the golden has no `sseSelf` for the row, the clipped result's bounds are wrong, or the clipped
  render differs from the unclipped one by more than allowed;
- `EDGE_ROWS`: a difference of more than one step outside the `edgeRows` rows next to the clip edges.

US-011 added one more caller: on the clipped rows of its `BOX_KERNEL_TAP_COUNT` deviation, the production render is
also held to `checkSelfConsistency`. The reviewer found that deleting that block leaves the suite green.

An earlier session noted that `DecoraCorpus.GoldenRow.edgeRows` may be vestigial since the F2 fix (the pass-0 clip of
the Java peers). If no row sets it any more, the `EDGE_ROWS` branch is dead code.

## Acceptance criteria
- Each of `SELF_CONSISTENCY` (each of its three causes) and `EDGE_ROWS` has a negative control that asserts the kind
  and its message, in the style of the existing controls (a mutated peer or a doctored result through the backend,
  never a doctored golden). Each control is shown to fail when its branch is removed.
- The US-011 caller of `checkSelfConsistency` in `judgeKernelDeviation` is covered by one of those controls.
- If no row sets `edgeRows >= 0`, the branch and the field are removed instead, and the javadoc says so.
- `DecoraJavaGoldenTest` per-row output byte-identical before and after (`-Djfx.parity.require=true`). **Never
  regenerate the golden.**

## Notes
- Related: US-011 (the deviation that added the caller), US-006 and US-010 (the clip fixes the clipped rows guard).

## Resolution (2026-10-06)
- **`edgeRows` stays:** the four `LinearConvolve/gaussian clipped` rows set `edgeRows = ceil(radius)`, so the
  `EDGE_ROWS` branch is live and gets a control instead of being removed.
- **Tests:** `DecoraJavaGoldenTest` gains five negative controls (test-only change, +206/-3). Each asserts the kind
  and pins its whole message, the finding count, and that the mutated peer ran:
  - `clippedRowWithoutGoldenSseSelfIsReported`: the unchanged golden entry of an unclipped Gaussian row (no
    `sseSelf`), judged with an in-memory copy of its corpus row that renders through a clip.
  - `clippedResultOutsideItsUnclippedResultIsReported`: a peer that moves every unclipped pass-1 result one pixel
    right, so the clipped bounds no longer lie inside the unclipped ones.
  - `clippedRenderAwayFromItsUnclippedRenderIsReported`: a peer that moves one unclipped pixel two steps, inside the
    edge rows (`SELF_CONSISTENCY` only).
  - `clippedRenderAwayFromItsUnclippedRenderOutsideTheEdgeRowsIsReported`: the same peer moves a pixel outside the
    edge rows (`EDGE_ROWS` plus `SELF_CONSISTENCY`, since `max(sseSelf, bound)` is one step on these rows).
  - `clippedProductionRenderOnKernelDeviationRowAwayFromItsUnclippedRenderIsReported`: covers the US-011 caller in
    `judgeKernelDeviation`. The peer moves a pixel only in the production `BoxRenderState` render, so only that
    render's self-consistency check can find it.
- **Class javadoc:** it now says each self-consistency branch, the kernel-deviation caller included, has a control.
- **Mutants:** removing each branch makes its control fail.

  | Mutant (removed) | Failing control |
  | --- | --- |
  | `sseSelf < 0` branch | `clippedRowWithoutGoldenSseSelfIsReported` |
  | Bounds branch | `clippedResultOutsideItsUnclippedResultIsReported` |
  | Distance branch | the two distance controls and the kernel-deviation control |
  | `edgeRows >= 0` branch | `clippedRenderAwayFromItsUnclippedRenderOutsideTheEdgeRowsIsReported` |
  | `row.clipped()` block in `judgeKernelDeviation` | `clippedProductionRenderOnKernelDeviationRowAwayFromItsUnclippedRenderIsReported` |
- **Golden:** with `-Djfx.parity.require=true`, 370 tests before and 375 after, 0 failed and 0 skipped. The per-row
  output is byte-identical (md5 `a2701ce7eb18aff43faf52de71b8b771`, 396 lines). The golden was not regenerated.
