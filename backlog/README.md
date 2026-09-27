# Backlog — user stories of the JNI-to-FFM epic

Parent epic: *fully remove JNI from `javafx.graphics`, replacing it with the Java 22+ FFM API, and
delete as much C/C++ as can be removed safely without changing behaviour.* Branch of record:
`ffm/graphics`.

This directory holds the epic's user stories, **open and done**, so they are tracked in source
control. A done story stays here for the record: its status is set to "✅ Done" with the date and
the PR that finished it, and its row moves from the open table to the done table below. Numbers are
never reused. Defect reports and upstream (OpenJDK) requests are not stories and are kept outside
the repository until filed.

Conventions: one file per story, `US-NNN-<slug>.md`, with Story / Problem or Central finding /
Acceptance criteria / Definition of Done. Supporting evidence shares the story's prefix
(`US-009-*`). Files are LF-terminated, like the rest of the tree.

## Open stories

| ID | Title | Status (2026-09-26) | Next action |
| --- | --- | --- | --- |
| [US-001](US-001-descope-glass-gtk-glass-mac-prism-mtl-ffm-migration.md) | Migrate `glass/gtk`, `glass/mac`, `prism_mtl` to FFM | 🔶 Linux half unblocked (WSL builds and tests the module); macOS half needs a macOS host | Schedule `glass/gtk` (99 natives, 102 upcall sites) |
| [US-003](US-003-migrate-javafx-font-jni-to-ffm.md) | Migrate `javafx_font` to FFM | 🔶 Windows and Linux halves done; macOS half (68 natives: `coretext.OS`, `MacFontFinder`, `DFontDecoder`) remains | Needs a macOS host |
| [US-008](US-008-remove-dead-jslc-me-backend-and-simd.md) | Remove the dead jslc ME backend and `AccelType.SIMD` | 📋 Ready (`backend/sw/me` and `AccelType.SIMD` still present) | Pick up when scheduled |
| [US-011](US-011-fix-box-kernel-weights-off-by-one-for-multipass.md) | Build symmetric box-blur kernels for two or more passes | 📋 Ready (filed 2026-09-26); `BoxRenderState.validateWeights` sums one tap too many at `i == klen`, so every GPU `BoxBlur` with 2+ iterations (the default 3) and every `TWO_PASS_BOX`/`THREE_PASS_BOX` shadow is skewed (12–18 steps vs SW); fix `while (i >= klen)` | Pick up when scheduled; also upstream |
| [US-013](US-013-fix-sw-texture-paint-first-texel-edge-and-out-of-bounds-read.md) | Fix the first texel row and column of the software texture paint | 📋 Ready (filed 2026-09-26); SW only. `PiscesPaint.c` `genTexturePaintTarget` interpolates the first device column and row between texels 0 and 1 with the weights of texels −1 and 0, so scaled or sub-pixel images are smeared one texel up-left (alpha 191 where D3D/ES2 give 0). The same path reads past the end of the texture array for images one texel tall or wide | Pick up when scheduled; also upstream |
| [US-014](US-014-fix-sw-linear-convolve-steps-for-rotated-inputs.md) | Step the software linear convolution along the right destination axis for rotated inputs | 📋 Ready (filed 2026-09-26); SW only. `JSWLinearConvolvePeer.filter` divides `dycol` by the destination height and `dxrow` by its width, swapped, for an input with a rotation or shear, so SW `InnerShadow(GAUSSIAN)` on a node rotated by 90 or 270 differs from D3D/ES2 by up to 156 steps even unclipped. The swap is validated in scratch; a rotate-45 residual of up to 29 steps remains to explain | Pick up when scheduled; also upstream |

## Done stories

| ID | Title | Done | Outcome |
| --- | --- | --- | --- |
| [US-005](US-005-fix-coloradjust-divide-by-zero-in-jsl.md) | Fix the ColorAdjust divide-by-zero in `ColorAdjust.jsl` | 2026-09-25, PR #14 | `if (cmax > cmin && cmax != 0.0)`, `ColorAdjustZeroMaxChannelTest`, D3D/ES2 visual check |
| [US-006](US-006-fix-gaussian-pass0-clip-growth-radiusy.md) | Grow the Gaussian pass-0 clip by the vertical radius | 2026-09-25, PR #15 | `GaussianRenderState` grows the pass-0 clip by `inputRadiusY` |
| [US-007](US-007-fix-boxblur-software-peer-drops-input-transform.md) | Keep the input transform in the software BoxBlur peer | 2026-09-26, PR #16 | `JSWBoxBlurPeer` passes `inputs[0].getTransform()` on; the two translated `BoxBlur` golden rows are a reviewed `TransformDeviation` |
| [US-009](US-009-migrate-monocle-jni-to-ffm.md) | Keep Monocle (embedded Linux) and migrate it from JNI to FFM | 2026-09-23, PR #12 | S1–S8 + D3; 195 natives → 0, ~3,500 lines of C deleted, `prism_es2_monocle` target, `monocle_egl_ext.h` |
| [US-010](US-010-fix-gaussian-input-clip-sign-under-rotation-mirror.md) | Pad the Gaussian input clip by absolute distances | 2026-09-26, PR #18 | `GaussianRenderState.getInputClip` pads by `ceil(\|dx0\| + \|dx1\|)` and `ceil(\|dy0\| + \|dy1\|)`; `GaussianInputClipTest`, golden unchanged, D3D/ES2/SW snapshot check met |
| [US-012](US-012-fix-box-pass-size-squared-srcscale-for-scaled-inputs.md) | Scale the box pass size by the input scale once, not twice | 2026-09-26, PR #17 | `BoxRenderState.validatePassInput` scales the pass size by `srcScale` once; `BoxRenderStateScaledInputTest`, golden unchanged, D3D/ES2/SW snapshot check met |

Never filed in this directory: US-002 `prism_common` (deleted 2026-09-07) and US-004 `glass/win` (no
`native` method left in `com.sun.glass.ui.win`, verified 2026-09-22).

## US-009 evidence

| File | Purpose |
| --- | --- |
| `US-009-monocle-ffm-research-dossier.md` | Four read-only research passes over the tree with a triage verdict, FFM replacement and file:line citation for every Monocle native |
| `US-009-s0-monocle-baseline.tsv` | The S0 baseline: every test of the Monocle-Headless suite with its status in three consecutive runs and a verdict (stable-pass 1 222, persistent 42, flaky 58, skipped 50) |
| `US-009-s0-classify.pl` | Produces that TSV from surefire report directories; the S1–S8 gate tool |

The S1–S8 gate, from the repository root on a Linux host (no display needed, never with
`-DHEADLESS_TEST=true`, which forces `glass.platform=Headless`):

```
mvn -B -ntp -pl tests/system -am test -DskipNative=true -DFULL_TEST=true -DUSE_ROBOT=true \
    -DUNSTABLE_TEST=true -Dtest='test/**/monocle/**/*Test' -Dsurefire.failIfNoSpecifiedTests=false
perl backlog/US-009-s0-classify.pl <archived S0 report dirs> tests/system/target/surefire-reports
```

A slice passes when every `stable-pass` row of the baseline still passes. The `persistent` rows
are the known baseline and the `flaky` rows are tracked but not gating.
