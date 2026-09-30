# US-048 — Call OpenGL from Java and delete the prism_es2 wrappers (Windows and Linux)

**Status:** 🔶 Needs a maintainer ruling first (drafted 2026-09-30 from the Rust-port survey of `native-prism-es2`;
exports and OS calls counted with `git grep`/`wc -l`; no frame-time measurement exists; nothing built). The survey
disagrees with the ES2 audit of record, which rules the GL entry points OS-CALL. · **Epic:** Less native code
(goal 1); routed here by the Rust-port survey · **Blocked by:** nothing

## Story
As a platform maintainer,
I want Prism's ES2 pipeline to call OpenGL through the function pointers it already resolves, from Java, and not
through 58 one-call C wrappers,
so that the owned `prism_es2` C on Windows and Linux (4,911 of the 5,804 owned lines) is deleted, and each GL call
costs one downcall instead of two.

## The ruling this story needs
The ES2 audit (the Copilot JNI→FFM audit of `prism_es2`, §1, §11) rules the GL entry points OS-CALL. Its reason is
that they are resolved per context through `wglGetProcAddress`/`dlsym` and are "not directly bindable from Java".
Under FFM they are bindable:
- an address-taking downcall handle binds a function pointer at call time;
- the ABI already hands Java the stored pointer (`prism_es2_api.h:320-324`).

If the maintainer keeps the audit's reading instead, the verdict is **KEEP, not RUST**. There is no untrusted input,
no COM and no UB to gain from Rust.

## Current state
- **Size.** 30 files, 23,305 lines. 17,501 of them are vendored Khronos headers under `GL/` and `KHR/`. Owned code
  is 5,804 lines:
  - shared: 2,837 (`prism_es2_api.c` 1,471, `.h` 497, `PrismES2Defs.h` 366, `GLContext.c` 336, `GLFactory.c` 99,
    `GLPixelFormat.c` 68);
  - Windows 934, X11 977, Monocle 136, macOS 920.
- **ABI.** 77 exports, `ES2_ABI_VERSION` 3u (`prism_es2_api.h:126`). By section:
  - guards 4, factory 3;
  - pixel format / drawable / context lifecycle 12;
  - state setters 35, resources 9, texture upload/read-back 3, 2D draw 1, shaders 5, 3D mesh 5.
- **The 58 GL-call exports** are one call, or a few, through the per-context pointer table. For example,
  `es2_active_texture` is `ctxInfo->glActiveTexture` (`prism_es2_api.c:295-301`).
- **The 15 lifecycle and factory exports** are OS-CALL sequences over plain WGL/GLX exports. Windows makes 53
  `wglGetProcAddress` calls; X11 has 51 `dlsym` calls, plus `glXCreateNewContext`, `glXChooseFBConfig`,
  `XCreateWindow` and others. Monocle already builds its EGL context in Java and adopts it through
  `es2_context_adopt` (US-009).

## Approach and slices
1. **Parity corpus.** Read back scripted 2D and 3D scenes through `es2_*` read-back, on Windows (GPU and driver
   recorded) and on WSL (Mesa, with the version recorded).
2. **State setters (35).** Java downcalls through the stored per-context pointers, behind the same `ES2Native`
   entry points.
3. **Resources, texture, shaders and mesh (23).** Same pattern, plus a frame-time A/B.
4. **Lifecycle and factory: WGL (Windows) and GLX (Linux).** Follow the `es2_context_adopt` pattern, so Java creates
   the context and hands it to what remains.
5. **Delete** the Windows and Linux C, the shared C, and the vendored `GL/`/`KHR/` headers once no C includes them.
   `macosx/` stays (no macOS host, as US-001), and so does its share of the shared C.

## Acceptance criteria
- The slice-1 corpus is exact on both recorded machines.
- The frame-time A/B is within the bound agreed in slice 1.
- The Windows ES2 pipeline (`-Dprism.order=es2`) and the WSL GTK robot tests are unchanged.
- The owned C, apart from macOS, is gone. The `prism_es2` library remains only on macOS.

## Definition of Done
Merged PRs, verified on Windows (ES2) and WSL Linux; macOS unverified. `backlog/README.md` is updated.

## Risks
| # | Risk | Mitigation |
| --- | --- | --- |
| 1 | Per-context pointers differ between contexts (WGL) | Keep the per-context table; bind handles per context, as the C does |
| 2 | Extra Java overhead on state-heavy frames | The downcall count halves; slice 3's A/B decides |
| 3 | Mesa output drifts with the Mesa version | Record the version with the golden; skip elsewhere, as the Linux font goldens do |
