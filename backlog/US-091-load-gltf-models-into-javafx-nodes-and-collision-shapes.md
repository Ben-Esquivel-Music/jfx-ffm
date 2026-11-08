# US-091 — Load glTF 2.0 models into JavaFX nodes and collision shapes

**Status:** 📋 Ready (filed 2026-11-07 from the game-toolkit design review) · **Epic:** Game toolkit ·
**Blocked by:** US-085 (collision shapes need its 3D engine contract); US-090 (skinned meshes only: JavaFX cannot draw
them before it) · **Blocks:** none

## Story
As a developer of a 3D game on JavaFX,
I want to load glTF 2.0 models into JavaFX nodes and turn their meshes into collision shapes,
so that a game can use models made in common 3D tools without writing its own loader.

## Problem
Paths that start with `java/` are under `modules/javafx.graphics/src/main/`, and `importers/` is
`apps/samples/3DViewer/src/main/java/com/javafx/experiments/importers/`; other paths are relative to the repository
root.
- **No glTF reader.** A case-insensitive search for `gltf` in `modules/javafx.base`, `modules/javafx.graphics`,
  `modules/javafx.controls` and `apps/` finds nothing.
- **The only importers are a sample's.** The 3DViewer sample reads `.ma`, `.ase`, `.obj`, `.fxml` and `.dae`
  (`importers/Importer3D.java:58-60`) under Oracle's BSD sample licence (`importers/obj/ObjImporter.java:1-30`). It
  is built by its own Ant project (`apps/samples/3DViewer/build.xml:10`), outside the Maven reactor
  (`pom.xml:37-52`).
- **What JavaFX can draw.** One mesh kind (`java/javafx/scene/shape/Mesh.java:102-103`), whose vertices carry no
  colour, joint indices or weights (`java/javafx/scene/shape/VertexFormat.java:38`, `:43`), and one material,
  `PhongMaterial` (`java/javafx/scene/paint/Material.java:48`). US-090 adds skinned meshes and more materials.
- **glTF 2.0** (https://registry.khronos.org/glTF/specs/2.0/glTF-2.0.html, knowledge): a `.gltf` file is JSON whose
  buffers and images are separate files or embedded data URIs, and a `.glb` file is a binary container. Core
  materials are metallic-roughness PBR, and core images are PNG and JPEG. The axes are right-handed with +Y up, in
  metres. A loader must refuse an asset whose `extensionsRequired` lists an extension it does not support.
- **Axes differ.** JavaFX 3D has the Y axis pointing down (`java/javafx/scene/PerspectiveCamera.java:54-56`).

## Constraints
- **Pure Java.** No native code. The loader uses public JavaFX API (with what US-090 adds), `java.base` and the game
  toolkit's own modules. Any third-party code it includes is under a GPLv2-compatible licence, and its notices ship
  through US-075's mechanism.
- **Where it lives.** The loader is documented API of the game toolkit's modules. It adds no public API to existing
  `javafx.*` modules, and loading a model into nodes needs no physics engine.
- **Threads.** A model can be loaded off the FX thread.
- **The 3DViewer importers** stay sample code: they are not shipped API, and the loader does not depend on them.
- **Java.** Java 27 final features only (no preview, no incubator, no `var`). Production Java files are at most 900
  lines (`wc -l`); tests and shims are exempt. Comments only for a non-obvious "why". GPLv2+CPE headers.
- **Tests.** Visual checks of loaded models are robot tests that the owner runs. Agents never run `USE_ROBOT` or
  `tests/system` runs.

## Out of scope
- Writing or converting glTF, and any asset build pipeline (README "Non Goals").
- Other model formats.
- Drawing what JavaFX 3D cannot draw: that is US-090 and later stories. This story reports it.

## Acceptance criteria
1. **Static models.** A glTF 2.0 file, `.gltf` or `.glb`, loads from a file, a URL or a module resource into JavaFX
   nodes that keep its node hierarchy, transforms, meshes, materials and textures as far as JavaFX can draw them.
2. **Skinned models** (once US-090's skinned meshes land). Skinned meshes load and draw bound to their joints, and
   when the application poses a joint, the mesh follows.
3. **Nothing dropped silently.** Everything in a file that the loader does not turn into something JavaFX draws (for
   example a material property `PhongMaterial` lacks, a morph target, an animation, a camera or an optional
   extension) is listed, item by item, in a report that comes with the loaded model. A file whose
   `extensionsRequired` names an extension the loader does not support fails with an exception naming it.
4. **Feature table.** This story records, for each core glTF 2.0 feature, whether it is loaded, reported, or loaded
   only once US-090 lands, and the tests show each row.
5. **Collision shapes.** A loaded mesh becomes a convex or a concave (triangle) collision shape for any engine through
   the engine contract, where the engine declares that shape kind, without the application copying arrays. The shape
   lines up with the drawn mesh, and the loader documents the axis and unit mapping it applies.
6. **Malformed files.** A malformed file fails with an exception that names the file and the fault. No other
   exception, no hang and no `OutOfMemoryError`: a size or count that the data cannot back fails before it is
   allocated.
7. **Untrusted input.** A file cannot make the loader read anything outside the location it was loaded from (an
   absolute path, a `..` escape or another URI scheme) unless the application allows it. A seeded fuzz run of at
   least 1,000,000 mutated files ends each in a load or in the loader's exception; its seed, corpus and counts are
   recorded here, and a fixed-seed subset runs in the normal test suite.
8. **CI.** All five CI legs are green.

## Definition of Done
- All acceptance criteria met, with the evidence (logs, counts, goldens, tables) kept with the change.
- An independent review by `game-toolkit-reviewer` signed off.
- `game-tester` ran the fuzzing with recorded seeds and counts; a `game-developer` probe loaded models through the
  public API.
- The owner runs the robot tests.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without explicit approval from
  the repository owner.**

## Notes
- Related: US-080 (the 2D contract that US-085 extends to 3D); US-087 (the 3D preview's samples).
