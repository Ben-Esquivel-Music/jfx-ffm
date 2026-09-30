# US-047 — Drive Direct3D 9Ex from Java and delete the prism_d3d C++

**Status:** 📋 Ready (drafted 2026-09-30 from the Rust-port survey of `native-prism-d3d`; exports, classes and COM
entry points counted with `git grep`/`wc -l`; no frame-time measurement exists; nothing built) · **Epic:** Less native
code (goal 1); routed here by the Rust-port survey · **Blocked by:** US-043 (the port reproduces the
fixed behaviour)

## Story
As a platform maintainer,
I want the D3D pipeline's 59 `d3d_*` exports replaced by Java that calls Direct3D 9Ex COM directly,
so that `prism_d3d`'s 7,545 lines of C++ are deleted, and the device-reset handle protocol that today spans two
languages lives in one.

## Why Java, and not Rust
- **R1: nothing here needs C.**
  - Every OS entry point is a COM vtable slot of `IDirect3D9Ex`, `IDirect3DDevice9Ex` or a resource interface, or a
    plain export: `Direct3DCreate9Ex`, `GetSystemDirectory`/`LoadLibrary`, `GetDesktopWindow`, `IsWindow`,
    `GetVersionEx`.
  - Nothing calls back into Java, and everything runs on the render thread (`prism_d3d_api.h:29-38`).
  - The fork already dispatches COM vtables and synthesises COM objects from Java for DirectWrite (US-003, Windows
    half done: `DWNative`, `IDWriteFactory`, `JFXTextAnalysisSink`).
- **Both ports need the same parity corpus, and only Java removes the reset protocol.**
  - Today, on `D3DERR_DEVICENOTRESET`, Java nulls its default-pool records (`D3DResourceFactory.java:505-514`)
    while the C++ deletes the same objects (`D3DResourceManager.cc:209-222`).
  - A Rust port keeps that split, because Java would still hold raw `D3DResource*` handles.
  - Rust's `windows` smart pointers would automate the 24 `SAFE_RELEASE` sites; the Java port has to release by
    hand, with US-003's reference-count test as the precedent.
- **Fallback: KEEP, not RUST.** If the parity corpus in slice 1 cannot be built, the C++ stays: a Rust port would
  need the same corpus.

## Current state
- **Size.** 29 C++ files, 7,545 lines. The ABI pair is 1,705 (`prism_d3d_api.h` 300 + `.cpp` 1,405), and
  `D3DBadHardware.h` is a 439-line data table. HLSL is 709 lines (the 3D bytecode is compiled into the DLL by
  `generateD3DHeaders`).
- **ABI.** 59 exports, `PRISM_D3D_ABI_VERSION` 1. By group:
  - guards 4, pipeline 7, context and resources 11;
  - pixel shaders 4, which are WRAPPERs;
  - 2D state 17, mostly WRAPPERs with state caching;
  - 3D 16.
- **Hot paths.** Two calls may be `critical(true)`: `draw_indexed_quads` (≤ 256 quads per lock,
  `D3DGraphics.cc:104-143`) and `texture_update` (the `TextureUpdater` conversion loops, `TextureUploader.cc:29-60`).
- **Tests.** `D3DNativeTest` pins symbols, layouts and constants. There is no pixel golden.

## Approach and slices
Java binds `d3d9.dll` and dispatches the COM vtables through a `D3D9Native` facade. Each slice keeps the
`D3DNative` entry points working, so Prism does not notice.
1. **Parity corpus from the C++.** Scripted scenes read back with `d3d_texture_read_pixels`:
   - 2D paints, composite modes, texture formats and upload paths, clips, render targets, 3D meshes, lights and
     materials;
   - captured on one Windows machine, with the GPU and driver recorded.

   Exact on that machine, like the D3D snapshot checks of US-010/US-012. A proxy `d3d9.dll` cannot record call
   traces, because the C++ loads d3d9 from the system directory (`D3DPipeline.cc:38-43`).
2. **Pipeline and adapters:** `Direct3DCreate9Ex`, adapter and caps queries, and the bad-hardware table as Java data.
3. **Context and resources:** device create/reset, textures, swap chains and depth. The reset protocol moves wholly
   into Java.
4. **Texture upload and read-back loops.** PURE-HOT, with a frame-time A/B. A regression blocks the slice.
5. **2D state and quad batching:** the state cache, `DISCARD`/`NOOVERWRITE` locking, `Present`.
6. **Shaders and 3D:** pixel shaders, the phong shader table, and mesh, material and mesh view.
7. **Delete:** `native-prism-d3d/` C++, the `prismD3D` CMake target and `D3DNative`. The compiled HLSL bytecode
   becomes class-path resources.

## Acceptance criteria
- The slice-1 corpus is exact on the capture machine.
- Frame-time A/B on the `tests/system` rendering benchmarks shows no regression beyond the bound agreed in slice 1.
- Default-pool reset (lock the screen, or switch users, with a live stage) recovers as it does today.
- No `d3d_*` export, `prism_d3d.dll` or `native-prism-d3d` C++ file remains.

## Definition of Done
Merged PRs, one per slice, verified on Windows with D3D. The C++ is deleted, and `backlog/README.md` is updated.

## Risks
| # | Risk | Mitigation |
| --- | --- | --- |
| 1 | A vtable slot index is wrong | Slots from `d3d9.h` via jextract; a QueryInterface/AddRef/Release round-trip test first, as US-003 did |
| 2 | Refcount leaks in hand-written Java releases | Arena-scoped wrappers; a reference-count test per resource kind |
| 3 | Java hot loops are slower | Slice 4's A/B; `critical(true)` heap segments stay available |
| 4 | Corpus exactness depends on the GPU and driver | Capture and verify on one recorded machine; others skip, as the Linux font goldens do |
