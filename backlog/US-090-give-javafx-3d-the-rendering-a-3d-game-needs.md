# US-090 — Give JavaFX 3D the rendering a 3D game needs

**Status:** 📋 Ready (filed 2026-11-07 from the game-toolkit design review) · **Epic:** Game toolkit ·
**Blocked by:** none · **Blocks:** US-091 (skinned meshes only)

## Story
As a developer of a 3D game on JavaFX,
I want JavaFX 3D to draw shadows, many lights, skinned and instanced meshes, lines, points, sorted translucent shapes
and simple game materials,
so that a game built on the toolkit's 3D physics looks like a 3D game on every desktop target.

## Problem
Paths that start with `java/` or `native-` are under `modules/javafx.graphics/src/main/`; other paths are relative to
the repository root.
- **One material.** `Material` permits only `PhongMaterial` (`java/javafx/scene/paint/Material.java:48`), which has
  a diffuse and a specular colour, a specular power and four maps
  (`java/javafx/scene/paint/PhongMaterial.java:495-751`). There is no unlit, vertex-coloured or alpha-cutout material.
- **Three lights, the first three.** A shape takes at most three non-ambient lights: "Currently we simply select the
  first 3" (`java/com/sun/javafx/sg/prism/NGShape3D.java:157-159`, `:187-191`). Every pipeline has three light slots
  (`native-prism-d3d/D3DMeshView.h:34`, `native-prism-mtl/MetalMeshView.h:34`,
  `java/com/sun/prism/es2/ES2MeshView.java:52-53`).
- **No shadows.** A case-insensitive search for `shadow` in `native-prism-*` and in the `d3d`, `es2` and `mtl` Java
  packages of Prism finds only OpenGL constants and an unrelated comment.
- **One mesh kind, two vertex formats.** Only `TriangleMesh` is supported ("We only support one type of mesh for
  FX 8", `java/javafx/scene/shape/Mesh.java:102-103`), and `Shape3D` is sealed
  (`java/javafx/scene/shape/Shape3D.java:65`). A vertex holds a point, texture coordinates and optionally a normal
  (`java/javafx/scene/shape/VertexFormat.java:38`, `:43`); it cannot carry a colour, joint indices or weights.
- **Skinning is CPU only, in a sample.** The 3DViewer sample's `SkinningMesh` allocates a new point array and
  rewrites every point whenever a joint moves
  (`apps/samples/3DViewer/src/main/java/com/javafx/experiments/shape3d/SkinningMesh.java:184-203`).
- **No instancing.** Each mesh view is its own draw call (`native-prism-d3d/D3DMeshView.cc:309`), and no API draws
  one mesh many times (inferred from its absence).
- **No 3D lines or points.** `DrawMode` has only `LINE`, the wireframe of the triangles, and `FILL`
  (`java/javafx/scene/shape/DrawMode.java:38-48`); D3D draws `LINE` as `D3DFILL_WIREFRAME`
  (`native-prism-d3d/D3DMeshView.cc:303-304`), with no width.
- **Translucency is left to the application.** A group draws its children in list or `viewOrder` order
  (`java/com/sun/javafx/sg/prism/NGGroup.java:216-218`), and the `viewOrder` javadoc leaves transparency sorting to
  the application, which sets each node's `viewOrder` to its distance from the viewer
  (`java/javafx/scene/Node.java:5578-5583`).
- **Pipelines.** The default order is d3d on Windows, mtl then es2 on macOS, and es2 on Linux, each falling back to
  sw (`java/com/sun/prism/impl/PrismSettings.java:205-212`). Windows Prism 3D is Direct3D 9
  (`native-prism-d3d/D3DPipeline.h:37`). The SW pipeline has no 3D (`java/com/sun/prism/sw/SWPipeline.java:88-90`),
  and the headless test profile sets `-Dprism.order=sw` (`pom.xml:502`), so no headless test draws 3D (inferred).
- **No measured ceiling.** No story or document records how many 3D nodes or lights a frame can hold on any
  pipeline (inferred from a search of `backlog/`).

## Constraints
- **Every target.** Each criterion holds with the default pipeline of each of the five desktop CI targets: D3D on
  Windows, Metal on macOS and ES2 on Linux. ES2 on macOS meets them too, or each difference is documented.
- **3D stays.** No criterion is met by removing 3D, or a pipeline's 3D, from a target. A requirement that a pipeline
  cannot meet as it stands is met on that target by another route, never dropped.
- **The named change.** Under the README's exception for named stories ("Non Goals";
  `.claude/skills/openjfx-conventions/SKILL.md:112-116`), this story allows, in `javafx.graphics` only, new public API
  for the abilities of criteria 2 to 8 and the Prism and pipeline logic they need. Existing public API does not
  change.
- **Existing scenes.** Existing 3D behaviour changes only in the two places this story names: which lights light a
  shape that more than three lights reach (criterion 3), and the order in which translucent shapes blend
  (criterion 7).
- **No slower.** A scene that uses none of the new abilities keeps its frame time on each pipeline, within the
  run-to-run spread recorded with the baseline.
- **Native code** keeps the fork's FFM rules: plain C ABIs bound through FFM, no JNI
  (`.claude/skills/jni-to-ffm-migration/SKILL.md:148`, §2).
- **Java.** Java 27 final features only (no preview, no incubator such as the Vector API, no `var`). Production Java
  files are at most 900 lines (`wc -l`); tests and shims are exempt. Comments only for a non-obvious "why".
  GPLv2+CPE headers.
- **Tests.** The SW pipeline has no 3D, so visual checks of 3D rendering are robot tests that the owner runs.
  Agents never run `USE_ROBOT` or `tests/system` runs.

## Out of scope
- A renderer other than JavaFX's, or another renderer's frames shown in a scene.
- 3D in the SW pipeline.
- Abilities the criteria do not list, such as application shaders, post-processing or physically based materials.
  Each needs a story of its own.
- Loading models (US-091) and the 3D game preview (US-087).

## Acceptance criteria
1. **Baseline first.** Before the work of any other criterion is judged, today's ceiling is measured on D3D, Metal,
   and ES2 on macOS and on Linux, and recorded here: frame time (median and 99th percentile) against node count and
   against light count for a set of reference scenes, with the host, GPU, OS and driver of each run. The scenes and
   the measuring program are kept with the change so the figures can be repeated. Every count or frame-time target
   of criteria 4 and 5 is set from this baseline and recorded before the work it judges.
2. **Shadows.** Shapes cast real-time shadows, onto other shapes and onto themselves, from the directional, point
   and spot lights the application chooses. A scene that asks for no shadows renders as before.
3. **More than three lights.** A shape is lit by more than three non-ambient lights on every pipeline, up to a
   documented limit. When more lights reach a shape than the limit, the shape uses the lights that contribute most to
   it, not the first ones in scene order.
4. **Skinned meshes.** A mesh whose vertices carry joint indices and weights is drawn following its joints'
   transforms. The application moves joints and never rewrites vertex positions. A skinned reference mesh meets the
   frame-time target set in criterion 1.
5. **Many copies.** Many copies of one mesh, each with its own transform, are drawn, and the number drawn within the
   frame-time target meets the target set from the baseline's count of separate `MeshView`s.
6. **Lines and points.** 3D line segments and points are drawn with a width in pixels the application chooses, and
   nearer shapes hide them.
7. **Translucency.** Translucent shapes blend with each other and with opaque shapes in their depth order as seen
   from the camera, whatever their order in the scene graph, without the application sorting anything. The cases
   that do not blend exactly, such as shapes that intersect, are documented.
8. **Materials.** A shape can use an unlit material (its colour or texture shown as is, whatever the lights), a
   vertex-coloured material (a colour per vertex, interpolated across faces) and an alpha-cutout material (pixels
   whose texture alpha is below a threshold the application sets are not drawn and hide nothing).
9. **Pipelines agree.** Owner-run robot tests show each of criteria 2 to 8 on D3D, Metal and ES2, with the same result
   or with each difference documented here.
10. **Nothing else changed.** The existing 3D tests pass with the counts of a run before the change, and owner-run
    robot comparisons of the baseline scenes match except where criteria 3 and 7 name a change. An API comparison
    against the parent commit shows only additions to `javafx.graphics`; each added public type and member is listed
    here with its `@since`, and its javadoc passes doclint. All five CI legs are green.

## Definition of Done
- All acceptance criteria met, with the evidence (logs, counts, goldens, tables) kept with the change.
- An independent review by `game-toolkit-reviewer` signed off, and by `ffm-reviewer` for the native parts.
- The owner runs the robot tests.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without explicit approval from
  the repository owner.**

## Notes
- This story is likely to split into substories (US-090a, US-090b, ...), one or more per ability. Each keeps these
  constraints, and the baseline of criterion 1 comes first.
- Owner ruling, 2026-11-07: bring-your-own engines are physics engines only; the renderer stays JavaFX.
- Related: US-087 (each 3D sample states its node count and the renderer limits it hit); US-091 (skinned glTF models
  need criterion 4).
