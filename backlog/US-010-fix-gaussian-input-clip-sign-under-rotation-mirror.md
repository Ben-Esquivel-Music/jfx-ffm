# US-010 — Pad the Gaussian input clip by absolute distances

**Status:** ✅ Done (2026-09-26, uncommitted) · **Found:** 2026-09-25, while fixing US-006 (code reading, then measured)

## Story
As a JavaFX app developer blurring or shadowing a node that is rotated or mirrored (including a right-to-left scene),
I want partial repaints to render the same pixels as a full repaint,
so that dirty-region updates, viewport snapshots and scrolled clips don't leave faded bands along the clip edges.

## Problem
`GaussianRenderState.getInputClip` (`:367-382`) grows the input clip by `padx = (int) Math.ceil(dx0 + dx1)` and
`pady = (int) Math.ceil(dy0 + dy1)` (`:374-375`) **without `Math.abs`**. `FilterEffect.filter` (`:177-187`) passes that
clip to the input effect, so the input (usually `NodeEffectInput`) is rendered over it.

- **Where the signs come from.** The input actually needed is the clip grown by `|dx0| + |dx1|` and `|dy0| + |dy1|`.
  In the RenderSpace branches the sample vectors carry the filter transform's signs:
  - the 2-D constructor sets them to `(mxx, myx, mxy, myy) / scale` (`:221-231`);
  - the MotionBlur constructor sets them to the transformed blur direction (`:316-324`).
- **How the clip goes wrong.** A negative sum makes `Rectangle.grow` **shrink** the clip; opposite signs cancel and
  under-pad. The `(padx | pady) != 0` guard (`:376`) lets negative values through.
- **Worked example** (radius 10, a 100x100 clip), input clip returned:
  - identity: 120x120 (correct);
  - rotate 90 or scaleX = -1: 80x120;
  - rotate 180: 80x80;
  - rotate 45: 100x130, where 130x130 is needed.
- **Where it is fine already.** The same class's `getPassResultBounds` uses `Math.abs` throughout (`:466-486`), as do
  `MotionBlurState.getHPad/getVPad` (`:54-60`). `getInputClip` is the only site in `com.sun.scenario.effect` with the
  missing-`abs` pattern.
- **Upstream too.** The `openjdk/jfx` master copy of `getInputClip` is identical (checked 2026-09-25 and 2026-09-26),
  so this is not a fork regression.

### Who is affected
- **Transforms and effects:**
  - Any mirror (`scaleX = -1`, `scaleY = -1`, and right-to-left node orientation, which gives `mxx < 0` to every node
    that mirrors with it), any rotation other than 0, and negative shear, for
    - `GaussianBlur`;
    - `MotionBlur`;
    - `DropShadow`, `InnerShadow` and `Shadow` **only with `BlurType.GAUSSIAN`**, including CSS `dropshadow(gaussian, ...)`.
  - `MotionBlur` is also affected **with no transform at all** when `cos(angle) < 0` or `sin(angle) < 0`. For example the
    -15 degree angle of its own javadoc example, radius 30, pads `pady = -7` where `+8` is needed.
- **Glow and Bloom:** these use an inner `GaussianBlur` and are mostly rescued by the `NodeEffectInput` cache hit on
  the node image their `Blend` renders first (code-derived).
- **Not affected:**
  - The default `THREE_PASS_BOX` shadows and `BoxBlur` (`BoxRenderState.getInputClip` grows by `klen / 2 >= 0`, and
    `BoxRenderState` forces a positive-scale input transform).
  - Kernels whose device radius exceeds `MAX_RADIUS`: 63 on desktop, 31 on embedded, by default
    (`decora.maxLinearConvolveKernelSize`). They take the scaled CustomSpace branch with sample vectors `(1, 0, 0, 1)`.
  - Identity and positive-scale transforms.
  - Unclipped renders.
  - A Gaussian nested under a CustomSpace parent effect, because the parent hands it a positive-scale transform.
  - On D3D/ES2, a `Rectangle` with a `DropShadow`, which takes the `EffectUtil.renderRectDropShadow` fast path.
  - An effect set directly on a node that does not mirror in a right-to-left scene or subtree. `ImageView` and `Canvas`
    set their own orientation to `LEFT_TO_RIGHT` (`ImageView.java:185`, `:213`; `Canvas.java:132`), and `Text` and
    `TextFlow` answer `usesMirroring()` with `false`. A node set to `LEFT_TO_RIGHT` is counter-mirrored the same way,
    together with the descendants that inherit its orientation. `Node.hasMirroring` counter-mirrors each such node, so
    its effect sees `mxx > 0` (measured for `ImageView`, code-derived for the others). A mirroring node inside a
    `TextFlow`, such as a `Group`, is mirrored again and is affected.
- **When it shows:** only when the output clip cuts into the effect's input content on the shrunk axis:
  - a dirty-region repaint (`ViewPainter` `g.setClipRect(dirtyRect)` -> `PrEffectHelper.render` `:75`) of a clean
    blurred node, next to a dirty sibling or overlay;
  - `Node.snapshot` with a viewport;
  - a render target smaller than the node.

### Measured (2026-09-25 and 2026-09-26, Windows 10, AMD Radeon R7 240)
- **Method.** A `Node.snapshot` with a viewport cutting 30 px inside a 160x160 checker `ImageView`, compared with the
  full snapshot cropped to the viewport. Radius 10, run on:
  - SW;
  - D3D9Ex;
  - ES2 over WGL (a privately built `prism_es2.dll`).
- **Two runtimes that differ in one class:**
  - the current working-tree build, which includes the US-006 fix (the pass-0 clip grown by `inputRadiusY`);
  - the same build with only `getInputClip` changed to `Math.abs`, compiled with the flags that reproduce the shipped
    class byte-for-byte.
- **Second run (2026-09-26, the acceptance check).** 65 scenes x 6 viewport cuts per pipeline. The runtime before the
  fix is commit eb6cc1182f; the one after it adds only the `Math.abs` class, byte-identical to the Maven build of the
  fix. The rows from `Shadow` down come from this run. They give D3D/ES2 values, with SW in brackets where it differs.

| Effect | Transform | Before the fix: max delta / band | With `Math.abs` |
| --- | --- | --- | --- |
| `GaussianBlur(10)` | rotate 90 | 158-159, left and right edges, 0-17 px deep | GPU 0, SW ≤ 1 |
| `GaussianBlur(10)` | rotate 180 | 162, left, top, bottom (right masked, see below) | GPU 0, SW ≤ 1 |
| `GaussianBlur(10)` | scaleX = -1 / scaleY = -1 | 158-159 on the mirrored axis | GPU 0, SW ≤ 1 |
| `GaussianBlur(10)` | rotate 45 | 41, left edge, 0-6 px deep | GPU 0, SW ≤ 1 |
| `DropShadow(GAUSSIAN, 10)` | rotate 90 / 180 / 270 / mirrors | 125-126 on the affected edges | GPU 0, SW ≤ 2 |
| `MotionBlur(0, 10)` | rotate 180 / 270 / scaleX = -1 | 195-196, left or top edge | GPU 0, SW ≤ 1 |
| `Shadow(GAUSSIAN, 10)` | rotate 90 / 180 / 270 / mirrors (rotate 45) | 164 (86-87), on the same edges as `GaussianBlur` | GPU 0, SW ≤ 1 |
| `InnerShadow(GAUSSIAN, 10)` | rotate 90 / 180 / 270 / mirrors | 111, a **dark** band 0-7 px deep inside the clip edge (see below) | GPU 0 (SW: see below) |
| `MotionBlur(-15, 30)` | identity | 163, top band 0-13 px | 1 |
| `MotionBlur(180, 10)` | identity | 196, left band 0-17 px | 0 |
| `GaussianBlur` / `DropShadow(GAUSSIAN)` / `MotionBlur` on a `Group` | right-to-left root | 164 / 126 / 196, left edge | 0-1 |
| the same effects on the `ImageView` itself | right-to-left root | 0 (not mirrored) | 0 |
| `GaussianBlur` / `DropShadow(GAUSSIAN)` | rotate 30; shear (-0.5, 0) | 19 (SW 16-20); 15; left band 0-4 px | 1 (SW `DropShadow` 2 on 1 px) |
| `GaussianBlur` / `DropShadow(GAUSSIAN)` | emulated HiDPI: snapshot scale 2 + rotate 90, device radius 20 | 164 / 124, left and right bands 0-35 px | 0 |
| `GaussianBlur` / `MotionBlur` / `Shadow(GAUSSIAN)` / `DropShadow(GAUSSIAN)` | the affected transforms above, a clip 15 px wide on the shrunk axis | the whole input lost, every pixel a fade: 164 / 196 / 164 / 126 | 0 |
| controls: identity (all three), `BoxBlur` under all transforms, `MotionBlur` under rotate 90 / 45 / scaleY = -1, `MotionBlur(15, 30)` | — | GPU 0-1, SW ≤ 2 | same |

- **Consistency.**
  - D3D and ES2 agree within one step, before and after the fix alike: pixel-identical on axis-aligned scenes, and
    off-axis scenes (rotate 30/45, shear, `MotionBlur` at ±15 degrees) differ by one step on up to about 6,000 pixels
    of a full render.
  - Full, unclipped renders are byte-identical between the two runtimes on SW, and on D3D and ES2 in 64 of 65 scenes.
    The 65th, `MotionBlur(-15, 30)`, differs by one step on 11 and 25 pixels because of render history (`ImagePool`
    texture reuse), and is byte-identical when rendered alone.
  - A diagnostic probe calling `getInputClip` on the render state the peer used returned exactly the predicted shrunk
    rectangles. For example rotate 180 gave `[40,40 80x80]` where `[20,20 120x120]` was needed.
  - Every pixel more than 3 steps off has a kernel footprint that crosses the returned clip.
- **Left/top bands are deterministic; right/bottom are often masked.** `NodeEffectInput` renders the node, unclipped,
  into an `ImagePool` texture whose size is rounded up to 32 (`ImagePool.java:110-111`) or reused from a larger image
  (`:134`). The peers sample the texture's physical size, so the slack at its right and bottom edges usually still holds
  real content, and the band on those edges is hidden fully or partly. For `InnerShadow(GAUSSIAN)` the rule applies to
  the `InvertMask` input's user-space edges, so its band can sit on the device right or bottom (below).
- **SW-only noise.** The SW `DropShadow` differences of at most 2 steps are the known `filterHV` vs `filterVector`
  rounding difference (`JSWLinearConvolvePeer:99-104`, JDK-8092042). They are identical in both runtimes and absent on
  the GPU. `DropShadow` rotated by 45 degrees on SW shows 2 steps on 1-3 px per cut, from the same mechanism.
- **InnerShadow(GAUSSIAN): the predicted dark band, measured.**
  - **Mechanism.** The under-padded request makes `InvertMask` (user space) invert real input only up to the clip
    edge, and `InvertMask.getResultBounds` re-grows it by `pad` with synthetic opaque mask. The Gaussian samples that
    ring and sees too much shadow source.
  - **Symptom.** A darkened band (premultiplied RGB lower at equal alpha), not a fade: 111 steps on D3D/ES2, 0-7 px
    deep.
  - **Edges.** The band sits on the device edges that map to the `InvertMask` input's user-space left or top on the
    axis the input clip shrank: right under rotate 90 and `scaleX = -1`, right and bottom under rotate 180, bottom
    under rotate 270 and `scaleY = -1`. The user-space right/bottom ring reads real content from the `NodeEffectInput`
    texture slack. So under rotate 180 the InnerShadow band is on the right and bottom, where `GaussianBlur`'s is on
    the left and top: a check that looks only at the output's left and top edges misses it.
  - **No band at rotate 45,** where the untransformed rotated clip plus the pad covers the deficit.
  - **Narrow clips.** A clip narrower than `2r` on the shrunk axis removes the inner shadow from the whole viewport.
  - **With `Math.abs`:** 0 on D3D and ES2, and 0 on SW for rotate 180 and both mirrors. SW under rotate 90/270/45
    stays wrong, in the full render too, from a separate software-peer defect (US-014) that the fix neither causes nor
    changes.
- **A clip narrower than `2r` on the shrunk axis.** `getInputClip` returned a negative-width rectangle
  (`[10,-10 -5x120]` for a 15-px clip under rotate 90), and the effect lost its whole input inside that clip (table).
- **Not measured (bounds- or code-derived only):**
  - Glow, Bloom.
  - A real HiDPI screen (emulated with a snapshot scale of 2 only).
  - A windowed dirty-region repaint: that path is confirmed by code only.
  - Metal, Linux hardware, and GPUs other than the one above.

## Proposed fix
In `GaussianRenderState.getInputClip`:
```java
int padx = (int) Math.ceil(Math.abs(dx0) + Math.abs(dx1));
int pady = (int) Math.ceil(Math.abs(dy0) + Math.abs(dy1));
```
- **Bounds.** This is the exact integer bound of the two sampling segments' Minkowski sum, and it is what was measured
  above.
- **Consistency.** With it, the `MotionBlur` input pads equal `MotionBlurState.getHPad/getVPad`, which the effect already
  uses for its bounds and dirty regions. It also covers the pass-0 region, which `getPassResultBounds` grows by
  `inputRadiusY` since US-006.
- **The per-pass form.** The alternative `ceil(|dx0|) + ceil(|dx1|)` is at most 1 px larger per side, and only
  off-axis. Neither form changes identity or axis-aligned transforms.
- **Rounding caveat, pre-existing.** For non-integer radii off-axis, the kernel taps can reach 1 px beyond either
  bound, at a weight of about 4e-4. `getPassResultBounds` has the same property. Off axis, a bilinear sample also
  touches the pixel beyond each pass's segment end, so the blur's footprint reaches `ceil(|dx0|) + ceil(|dx1|)` (16 at
  45 degrees and radius 10, one more than the pad), at a weight near 1e-8. Measured: removing that ring changes no
  pixel, and removing even four rings inside the pad moves none by more than a step.

## Acceptance criteria
- **A reproducing test first, one that fails before the fix and passes after.**
  - Render-state level: `getInputClip` for identity, rotate 90/180/270/45, both mirrors, a negative shear, and the
    `MotionBlur` constructor with a negative direction at identity. Expect the clip grown by `ceil(|dx0|+|dx1|)` and
    `ceil(|dy0|+|dy1|)`.
  - Pixel level: through the production pass protocol, with the source first cut to `getInputClip(0, clip)` as
    `FilterEffect` and `NodeEffectInput` do (see `GaussianPassClipGrowthTest.Case.sourceBounds`). Clipped vs cropped
    unclipped must be within one step.
    - `DecoraBackend.motion` already reaches the signed `MotionBlur` case at identity, e.g. direction `(-1, 0)`.
    - The mirrored/rotated 2-D cases need a `DecoraBackend` overload that takes a prebuilt `LinearConvolveRenderState`.
    - Assert on the left/top edges, or control the source slack, so pool rounding cannot hide the band.
- The fix as proposed. `DecoraJavaGoldenTest` stays green, and its per-row report is byte-identical before and after.
  `DecoraBackend` never applies `getInputClip`, so no golden row is expected to change.
- Hardware check on D3D and ES2 (and SW) for rotated and mirrored `GaussianBlur`, `DropShadow(GAUSSIAN)` and
  `MotionBlur`: viewport vs cropped full snapshot, before and after. Add `InnerShadow(GAUSSIAN)` to confirm its
  expected symptom.

## Notes
- Found during US-006: a manager observation, confirmed at bounds level by its implementer, at code level by its
  reviewer, and at pixel level by a follow-up measurement with three independent skeptic checks.
- Also present in `openjdk/jfx`; a candidate for an upstream JBS report once fixed here.
- The right-to-left case makes this visible to any RTL application that uses a Gaussian effect on a node that mirrors
  (a container, shape or control, not an `ImageView`, `Canvas`, `Text` or `TextFlow`), whenever a partial repaint cuts
  the effect's result.

## Resolution (2026-09-26, uncommitted)
- **Fix:** `GaussianRenderState.getInputClip` grows the clip by `ceil(|dx0| + |dx1|)` and `ceil(|dy0| + |dy1|)`, as
  proposed.
  - The pads are now never negative and never smaller than before (`|a| + |b| >= a + b`), so every input clip contains
    the old one. A `null` clip still returns `null`.
  - The review checked every `getInputClip`, `grow` and pad in `com.sun.scenario.effect` and `javafx.scene.effect`:
    this was the only signed site. No other production file changed.
- **Tests:**
  - New `test.com.sun.scenario.effect.GaussianInputClipTest`, 55 tests, public API only:
    - **17 render-state cases** with hand-derived pads on a 100x100 clip:
      - identity, a positive scale and `MotionBlur` at 20 degrees as controls;
      - rotate 90/180/270 with radii 10 and 4;
      - rotate 45 at radius 10, which pads 15 where the signed sum gives 0 columns, `|dx0 + dx1|` 0 and the per-pass
        ceilings 16; rotate 45 with radii 10 and 4; rotate atan(4/3);
      - both mirrors, and shear (-0.35, -0.25);
      - `MotionBlur` along (-1, 0) and (0, -1); at -15 degrees and radius 30, which pads 8 rows where the signed sum
        gave -7; and under rotate 180 and `scaleY = -1`.
    - **Two more state tests:** the scaled `CustomSpace` branch (a device radius above `MAX_RADIUS` = 63) and a `null`
      clip.
    - **18 pixel cases** through the production pass protocol, `DecoraBackend.convolve(ImageData,
      LinearConvolveRenderState, Rectangle)`. That method already existed and is now public, so it is the overload the
      acceptance criteria asked for.
      - The source is a 72x72 opaque checkerboard cut to `getInputClip(0, clip)`, as `FilterEffect` and
        `NodeEffectInput` cut it. It is compared with the whole source rendered unclipped, with a fresh backend per
        render.
      - The source slack is controlled (exact-size sources), so all four edges are asserted, not only left and top.
      - Kernels: blur radius 10, tinted Gaussian shadow radii 10 and 6, and `MotionBlur` radius 10.
      - Transforms: the quarter turns, rotate 45, both mirrors, and `MotionBlur` along (-1, 0), (0, -1) and -15
        degrees. Identity and `MotionBlur` along (1, 0) are the controls.
    - **18 loop checks:** both renders run the same `filterHV`/`filterVector` loop in each pass, which is what keeps
      the one-step bound (JDK-8092042).
    - **Before the fix,** 29 of 55 failed: 14 of 17 render-state cases and 15 of 18 pixel cases.
      - The quarter turns, the mirrors and the axis-aligned `MotionBlur` differed by 203-255 steps across most of the
        clip.
      - Rotate 45 differed by 113 (blur) and 21 (shadow) steps in bands; `MotionBlur` at -15 degrees by 243.
    - **After the fix,** 55 of 55 pass. The renders are bit-identical except 8 of 1024 pixels at one step (blur, rotate
      45), which the review traced to float rounding of the image geometry, not to missing input.
    - **Mutants:** 23 mutants of `getInputClip`, run by the implementer and the reviewer, are all killed except one.
      The survivor drops the `(padx | pady) != 0` guard and is equivalent. The per-pass-ceiling, floor, round, hypot
      and max forms are killed only by the render-state literals: the pixel cases show that the input clip is enough,
      but they do not pin it.
  - Javadoc only: `DecoraBackend.convolve`, and one sentence of `GaussianPassClipGrowthTest`, which now points to the
    new test for rotated and mirrored renders.
  - `DecoraJavaGoldenTest` (`-Djfx.parity.require=true`): output byte-identical before and after, on Windows and on
    Linux (WSL, JDK 25). **The golden was not regenerated.**
  - **Suites:**
    - Windows: `test.com.sun.scenario.effect.**` 719 run, 0 failures; full `javafx.graphics` module 25,678 run, 0
      failures, 0 errors, 356 skipped.
    - Linux (WSL): `test.com.sun.scenario.effect.**` 719 run, 0 failures.
- **Hardware check (met):** D3D9Ex, ES2 over WGL and SW on the AMD Radeon R7 240, at commit eb6cc1182f with and
  without the fixed class, 65 scenes x 6 viewport cuts per pipeline (see Measured).
  - **After the fix:** every viewport comparison is within 1 step of the cropped full render on D3D and ES2 (0 of 390
    over). On SW every comparison in the acceptance scope (168) is within 2 steps; the 2s are the JDK-8092042
    rounding, the same before the fix.
  - **SW exceptions outside that scope:**
    - `InnerShadow` under rotate 90/270/45, which is US-014;
    - one `BoxBlur` rotate-30 cut at 2 steps on 9 px, identical before and after, consistent with US-013's SW edge
      sampling and not investigated further.
  - **Before the fix,** every band sat where the bounds prediction puts it: 0 unexplained pixels on D3D and ES2.
- **Found and filed:** US-014. `JSWLinearConvolvePeer` divides the per-column and per-row source steps of a rotated
  input by the wrong destination dimension, so SW `InnerShadow(GAUSSIAN)` on a rotated node is wrong even when
  unclipped. The right-to-left caveat above comes from the same check.
- **Evidence:** Claude scratchpad of session 2eaeb96f, `us010/`:
  - `A-notes.md` and the before/after outputs and logs;
  - the mutants in `step5/` and `C/`;
  - `hw/US-010-hardware-report.md`, `hw/verdict.txt` and `hw/table-all.md`; the probe is `hw/src/InputClipHW.java`;
  - the independent review, `review-C.md`.
- **Upstream:** `openjdk/jfx` master still has the signed sums (checked 2026-09-26).
