# US-058 — Give the Decora golden test's self-consistency checks negative controls

**Status:** 📋 Ready (filed 2026-10-02 from the independent review of US-011; read: `DecoraJavaGoldenTest`
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
