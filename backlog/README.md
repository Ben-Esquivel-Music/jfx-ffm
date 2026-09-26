# Backlog — open user stories of the JNI-to-FFM epic

Parent epic: *fully remove JNI from `javafx.graphics`, replacing it with the Java 22+ FFM API, and
delete as much C/C++ as can be removed safely without changing behaviour.* Branch of record:
`ffm/graphics`.

This directory holds the **open** user stories so they are tracked in source control. A story
lives here until it is done; done stories are removed with the commit that finishes them and their
outcome is recorded in the commit message. Defect reports and upstream (OpenJDK) requests are not
stories and are kept outside the repository until filed. Numbers are never reused.

Conventions: one file per story, `US-NNN-<slug>.md`, with Story / Problem or Central finding /
Acceptance criteria / Definition of Done. Supporting evidence shares the story's prefix
(`US-009-*`). Files are LF-terminated, like the rest of the tree.

## Open stories

| ID | Title | Status (2026-09-22) | Next action |
| --- | --- | --- | --- |
| [US-001](US-001-descope-glass-gtk-glass-mac-prism-mtl-ffm-migration.md) | Migrate `glass/gtk`, `glass/mac`, `prism_mtl` to FFM | 🔶 Linux half unblocked (WSL builds and tests the module); macOS half needs a macOS host | Schedule `glass/gtk` (99 natives, 102 upcall sites) |
| [US-003](US-003-migrate-javafx-font-jni-to-ffm.md) | Migrate `javafx_font` to FFM | 🔶 Windows and Linux halves done; macOS half (68 natives: `coretext.OS`, `MacFontFinder`, `DFontDecoder`) remains | Needs a macOS host |
| [US-005](US-005-fix-coloradjust-divide-by-zero-in-jsl.md) | Fix the ColorAdjust divide-by-zero in `ColorAdjust.jsl` | ✅ Done in the working tree (2026-09-25, uncommitted); `if (cmax > cmin && cmax != 0.0)`, `ColorAdjustZeroMaxChannelTest`, D3D/ES2 visual check | Commit, then remove this row and the story file |
| [US-008](US-008-remove-dead-jslc-me-backend-and-simd.md) | Remove the dead jslc ME backend and `AccelType.SIMD` | 📋 Ready (`backend/sw/me` and `AccelType.SIMD` still present) | Pick up when scheduled |
| [US-009](US-009-migrate-monocle-jni-to-ffm.md) | Keep Monocle (embedded Linux) and migrate it from JNI to FFM | ✅ S1–S8 + D3 in the working tree (2026-09-23, uncommitted); 195 natives → 0, ~3,500 lines of C deleted, `prism_es2_monocle` target, `monocle_egl_ext.h` | Commit the series (hand-off notes in the session scratchpad), then remove this row and the `US-009-*` files |
| [US-010](US-010-fix-gaussian-input-clip-sign-under-rotation-mirror.md) | Pad the Gaussian input clip by absolute distances | 📋 Ready (filed 2026-09-25); `GaussianRenderState.getInputClip` lacks `Math.abs`, so rotated/mirrored Gaussian effects under a cutting clip fade at the clip edges (up to 196 steps on SW/D3D/ES2); `Math.abs` fix validated in scratch | Pick up when scheduled; also upstream |
| [US-011](US-011-fix-box-kernel-weights-off-by-one-for-multipass.md) | Build symmetric box-blur kernels for two or more passes | 📋 Ready (filed 2026-09-26); `BoxRenderState.validateWeights` sums one tap too many at `i == klen`, so every GPU `BoxBlur` with 2+ iterations (the default 3) and every `TWO_PASS_BOX`/`THREE_PASS_BOX` shadow is skewed (12–18 steps vs SW); fix `while (i >= klen)` | Pick up when scheduled; also upstream |
| [US-013](US-013-fix-sw-texture-paint-first-texel-edge-and-out-of-bounds-read.md) | Fix the first texel row and column of the software texture paint | 📋 Ready (filed 2026-09-26); SW only. `PiscesPaint.c` `genTexturePaintTarget` interpolates the first device column and row between texels 0 and 1 with the weights of texels −1 and 0, so scaled or sub-pixel images are smeared one texel up-left (alpha 191 where D3D/ES2 give 0). The same path reads past the end of the texture array for images one texel tall or wide | Pick up when scheduled; also upstream |

Not here: US-002 `prism_common` (deleted 2026-09-07), US-004 `glass/win`
(no `native` method left in `com.sun.glass.ui.win`, verified 2026-09-22), US-006 Gaussian pass-0
clip growth (`GaussianRenderState` grows the pass-0 clip by `inputRadiusY`, fixed 2026-09-25 in PR #15), US-007
software BoxBlur peer dropping its input transform (`JSWBoxBlurPeer` passes `inputs[0].getTransform()` on, fixed
2026-09-26; the two translated `BoxBlur` golden rows are a reviewed `TransformDeviation`), US-012 box pass size
squared by the input scale (`BoxRenderState.validatePassInput` scales the pass size by `srcScale` once, fixed
2026-09-26, uncommitted; `BoxRenderStateScaledInputTest`, golden unchanged, D3D/ES2/SW snapshot check met).

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
