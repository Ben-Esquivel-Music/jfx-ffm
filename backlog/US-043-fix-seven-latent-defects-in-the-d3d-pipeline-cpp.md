# US-043 — Fix seven latent defects in the D3D pipeline's C++

**Status:** 📋 Ready (filed 2026-09-30 from the Rust-port survey of `native-prism-d3d`; each site was confirmed by
reading the source. Six of them are the latent bugs that the JNI→FFM migration carried verbatim (its prism_d3d
audit, §12 R-9); the seventh is new. Nothing was built or run) · **Found:** 2026-09-30, Rust-port survey (the
Prism area)

## Story
As a JavaFX app developer on Windows,
I want the D3D pipeline to release only the shaders it created, to blend with defined factors, and to copy and check
read-back sizes correctly,
so that a failed shader creation, an unexpected composite mode or a zero-width read cannot crash the JVM or render
garbage.

## Problem
All paths are relative to `modules/javafx.graphics/src/main/native-prism-d3d`. The FFM migration kept all seven
defects on purpose, as a behaviour-neutral port, and left them to "a follow-up with tests". No story tracked that
follow-up until now.

1. **The phong shader destructor releases NULL slots (new).**
   - `createVertexShader`/`createPixelShader` return 0 when `Create*Shader` fails (`D3DPhongShader.cc:34-41`).
   - The constructor stores the results unchecked (`:94-106`), for 1 vertex shader and 2 + 2×2×4×3 pixel shaders.
   - `~D3DPhongShader` calls `->Release()` on every slot (`:46-55`).
   - One failed creation, e.g. out of video memory, therefore crashes when the context is disposed
     (`D3DContext.cc:164-166`; the shader is created at `:202-203`).
2. **Blend factors are uninitialised outside modes 0-4.** `srcBlend`/`dstBlend` (`prism_d3d_api.cpp:952`) are
   assigned only by the known composite modes (`:957-973`). They are then passed to `SetRenderState` whenever
   blending is enabled (`:976-979`).
3. **The `X8R8G8B8` read-back over-copies by 4×.**
   - `copyX8R8G8B8(dst, src, n)` copies `n` pixels (`prism_d3d_api.cpp:230-235`), but `readPixelsImpl` passes
     `cntW*4`, a byte count (`:296-299`).
   - The call writes four rows' worth per row, and past the buffer on the last rows.
   - The audit recorded this branch as dead (render targets are `A8R8G8B8`); reachability was not re-checked here.
4. **`d3d_texture_read_pixels` divides by `w` before any check.** `UINT(dst_bytes)/4/w` (`prism_d3d_api.cpp`, the
   sanity check after `:709`) runs before any validation of `w`. The comment claims "cntW and cntH are positive";
   the Java-side guarantee was not checked.
5. **`OutputDebugStringA("Texture.Usage.DYNAMIC")`** runs on every dynamic texture creation in release builds
   (`prism_d3d_api.cpp:565`).
6. **`D3DMeshView::render` discards a status.** `SUCCEEDED(device->SetVertexShaderConstantF(VSR_WORLDMATRIX, …))`
   is evaluated and dropped (`D3DMeshView.cc:217`), and the `if (!status)` after it tests the previous call's result
   (`:203`, `:218-219`).
7. **`adapterOrdinal < 0` on an unsigned value is always false** (`D3DPipelineManager.cc:581`). It is harmless
   today, because `>= adapterCount` catches the same values, but it is misleading.

## Proposed fix
1. Guard each `Release()` (the file's own `SAFE_RELEASE`, `D3DPipeline.h:44-50`). Keep the failed slots NULL, so
   `setPixelShader` keeps today's behaviour for a missing shader.
2. Initialise both factors to the `SRC_OVER` pair (`D3DBLEND_ONE`, `D3DBLEND_INVSRCALPHA`), and assert in debug builds.
3. Pass `cntW`, the pixel count, to `copyX8R8G8B8`.
4. Return `E_INVALIDARG` for `w <= 0 || h <= 0` before the division, and check that no Java caller can pass it.
5. Delete the `OutputDebugStringA` call, or route it through `Trace`.
6. Assign the `SetVertexShaderConstantF` result to `status`.
7. Drop the `< 0` test, or make the ordinal signed where it is declared.

## Acceptance criteria
- A test forces shader creation to fail and then disposes the context without a crash (a fault-injection hook in
  `D3DPhongShader`, compiled into test builds only, is acceptable).
- An unknown composite mode leaves defined blend state.
- Reading back an `X8R8G8B8` surface (if one is reachable) returns exactly `cntW*cntH` pixels with alpha 0xFF, and
  nothing is written past `dst_bytes`.
- `d3d_texture_read_pixels` with `w == 0` returns an error.
- `D3DNativeTest` and the D3D snapshot checks used by US-010/US-012 stay green.
- The export list and `PRISM_D3D_ABI_VERSION` are unchanged.

## Definition of Done
The fixes are merged, verified on Windows on D3D, and `backlog/README.md` is updated. A later Java port of
`prism_d3d` reproduces the fixed behaviour, not these bugs. The upstream issue is drafted: all seven are present in
openjdk/jfx.
