# US-001b — `prism_mtl` JNI audit: `native-prism-mtl` + `com.sun.prism.mtl` (workstream A)

Inventory by `jni-auditor` for [US-001b](US-001b-migrate-glass-mac-and-prism-mtl-jni-to-ffm-on-macos.md), checked
against the tree at `master` `1a6788cc4d` on 2026-11-03 (Mac mini, macOS 27.0.1 arm64).

## Summary

- **Counts (re-verified):** 60 Java natives (7 classes, all `static`), 59 `JNIEXPORT` (4 files), 1 `Call*Method`.
- **Verdict of the 59 exports:** 53 OS-CALL (32 Metal/Foundation/libdispatch, 21 ObjC-ivar state), 5 WRAPPER, 1 dead.
- **No PURE / PURE-HOT.** The 5 WRAPPERs are `if (p) objc_release(p)` (PARITY: exact); `nGetPixelDataPtr` has no C.
- **Decision:** migrate `libprism_mtl`, do not delete it; 5 natives become Java-bound `objc_release`, 2 are deleted.
- **ABI:** flat `prism_mtl_api.h`: 51 `mtl_*` + `mtl_abi_version`/`mtl_abi_constant`; +1 ObjC handles; no structs.
- **Upcalls:** 1 site (`HashMap.put`, plus 2 `NewObject`), replaced by out-parameters: no callback table, no stubs.
- **Threads:** every export runs on the Prism render thread; the Metal completion block stays in C, never calls Java.
- **Top risks:** R1 critical + blocking draw, R2 buffer position (zero-length metallib), R3 heap-buffer `NULL` crash
  (reached by printing a `Canvas` on Metal).
- **Also:** R5 JSLC headers define symbols, R6 Glass raw-`Long` boundary, R7 `objc_release` not in SDK, R8 split.
- **Tests:** none touch `com.sun.prism.mtl` today; capture pixel and uniform-table goldens on the JNI build first.

## Count verification

The headline counts were re-run over the tree, from `modules/javafx.graphics/src/main`:

| Count | Grep | Result | Auditor |
|---|---|---:|---|
| `JNIEXPORT` | `grep -h JNIEXPORT native-prism-mtl/* \| wc -l` (per file: `grep -c`) | 59 (MetalContext.m 38, MetalShader.m 12, MetalRTTexture.m 5, MetalTexture.m 4) | agrees |
| `Java_` exports in the baseline dylib | `nm -gU libprism_mtl.dylib \| grep -c ' T _Java_'` | 59 (324 `T` symbols in all, 64 undefined) | agrees |
| `Call*Method` sites | `grep -nE 'Call(Static\|Nonvirtual)?[A-Za-z]*Method' native-prism-mtl/*` | 1 (MetalShader.m:124) | agrees |
| `NewObject` sites | `grep -nE '\bNewObject[A-Za-z]*\(' native-prism-mtl/*` | 2 (MetalShader.m:118, :123) | agrees |
| Java `native` declarations | `grep -h 'static native ' java/com/sun/prism/mtl/*.java \| wc -l` | 60 (MTLContext 34, MTLShader 12, MTLRTTexture 6, MTLTexture 4, MTLResourceFactory 2, MTLPipeline 1, MTLGraphics 1) | agrees |
| Raw `native` word | `grep -nE '\bnative\b' java/com/sun/prism/mtl/*.java \| grep -v 'static native '` | 11 more lines, all comments or string literals; 0 non-`static` declarations | agrees |
| Files naming JNI | `grep -lE 'JNIEnv\|jni\.h' native-prism-mtl/*` | 6 | agrees (the story's table says 5) |
| Size | `find native-prism-mtl -type f \| xargs cat \| wc -l` | 29 files / 5,737 lines | agrees |

One correction: the auditor wrote that the 9 `Get…ArrayCritical` sites sit in 5 functions. They sit in **6**
(`nDrawIndexedQuads`, `nBuildNativeGeometryShort`, `nBuildNativeGeometryInt`, `nUpdate`, `nUpdateFloat`, `nUpdateInt`);
§2 below is corrected. The story's "Files with JNI = 5" matches neither the 4 files with `JNIEXPORT` nor the 6 that
name `jni.h`/`JNIEnv`.

## Sources and path legend

I read the tree at HEAD `1a6788cc4d`. The binary evidence is the baseline JNI build of that same commit, the
"Before" run recorded in [US-001b-macos-gate.md](US-001b-macos-gate.md): its
`modules/javafx.graphics/target/native/bin/libprism_mtl.dylib` and `target/gensrc` (arm64 only). That build lived in
a session clone that is gone; a build of `1a6788cc4d` reproduces it, and the gate file records its 59 `Java_`
exports. Nothing was modified, and Maven and CMake were not run.

| Prefix | Path |
|---|---|
| `C/` | `modules/javafx.graphics/src/main/native-prism-mtl/` |
| `J/` | `modules/javafx.graphics/src/main/java/com/sun/prism/mtl/` |
| `G/` | `modules/javafx.graphics/src/main/native-glass/mac/` |
| `GEN/` | `target/gensrc` (baseline) |

## 1. Verdict

**Migrate this library. Do not delete it.** Of the 59 `JNIEXPORT`s:

- **53 are `OS-CALL`.**
  - 32 reach Metal, Foundation or libdispatch directly.
  - 21 only read or write state that lives in the project's own Objective-C objects (`MetalContext`, `MetalShader`,
    `MetalMeshView`…). They can only be reached through `objc_msgSend`, which the story forbids from Java.
- **5 are `WRAPPER`.** `nDisposeShader`, `nReleaseMTLMesh`, `nReleaseMTLPhongMaterial`, `nReleaseMTLMeshView` and
  `nReleaseTexture` compile to exactly `if (p) objc_release(p)`; I confirmed this by disassembling the arm64 dylib.
  `objc_release` is a plain, non-variadic C function in libobjc, so these get **PARITY: exact** and their C goes away.
- **1 is dead.** `nSetWorldTransformToIdentity` exists on both sides but has no Java caller.
- **0 are `PURE` and 0 are `PURE-HOT`.**

On the Java side, one `native` has no C body at all: `MTLRTTexture.nGetPixelDataPtr` (no implementation, no caller).

The pure fragments (readback stride copy, row memcpys, spot-light `cos`, scissor maths) sit inside Metal-calling
bodies. Splitting them out would *add* exports, so they stay.

Result: 60 Java natives become **51 functional `mtl_*` exports + 2 ABI/test exports + 1 Java-bound `objc_release` +
2 deletions**. There is **1 JNI upcall site** (`HashMap.put`, plus 2 `NewObject`). It becomes an out-parameter
enumeration, so **the ABI needs no callback table**.

## 2. Summary counts

| Item | Count | Where / how counted |
|---|---:|---|
| Java `native` declarations | **60** in 7 classes: MTLContext 34, MTLShader 12, MTLRTTexture 6, MTLTexture 4, MTLResourceFactory 2, MTLPipeline 1, MTLGraphics 1. All `static`; **0** instance and **0** `@Override native protected` | `grep -h 'static native ' J/*.java \| wc -l` = 60. A raw `grep -cE '\bnative\b'` gives 71; the other 11 lines are comments or strings: MTLContext.java:100,101; MTLPipeline.java:49,53,83,90; MTLMeshView.java:41; MTLFBOTextureData.java:39; MTLTextureResource.java:43; MTLRTTexture.java:71; MTLTexture.java:164 |
| `JNIEXPORT` | **59** in 4 files: MetalContext.m 38, MetalShader.m 12, MetalRTTexture.m 5, MetalTexture.m 4 | `grep -c JNIEXPORT C/*.m`. `nm -gU libprism_mtl.dylib \| grep -c Java_` = 59 |
| Orphans | Java→C: `nGetPixelDataPtr` (J/MTLRTTexture.java:173; prototype in GEN/headers/…/com_sun_prism_mtl_MTLRTTexture.h:47, no body). C with no Java caller: `nSetWorldTransformToIdentity` (C/MetalContext.m:1100). C/Java signature mismatch: `nUpdate`, `nUpdateFloat`, `nUpdateInt`, `nUpdateYUV422` return `jlong` (MetalTexture.m:302,340,377,413), Java and the generated header say `void` | per-native caller count; header diff |
| Files that touch JNI | 4 with `JNIEXPORT`; 6 name `jni.h`/`JNIEnv`/j-types (MetalCommon.h, MetalShader.h, MetalContext.m, MetalShader.m, MetalRTTexture.m, MetalTexture.m); 7 need the JDK/`HEADERS_DIR` include path (the 6 plus MetalPipelineManager.m through its generated header). The story's "5" matches neither count | `grep -lE 'JNIEnv\|jni\.h' C/*` |
| `Call*Method` | **1**: MetalShader.m:124 `CallObjectMethod` → `HashMap.put` | `grep -nE 'Call(Static\|Nonvirtual)?[A-Za-z]*Method' C/*` |
| `NewObject` (constructor upcalls) | 2: MetalShader.m:118 `HashMap.<init>()V`, :123 `Integer.<init>(I)V` | same grep, `NewObject` |
| `FindClass` / `GetMethodID` | 2 (MetalShader.m:93,94 `java/util/HashMap`, `java/lang/Integer`) / 3 (:100, :101, :109), **not cached**: looked up on every call | — |
| `GetFieldID`, `GetStatic*ID`, cached `jclass`/`jmethodID` globals | 0 | — |
| `NewGlobalRef` / `DeleteGlobalRef` / weak | **1 / 0 / 0**, unmatched: MetalShader.m:126 (returned to Java, never deleted) | — |
| `Get…ArrayCritical` | 9 sites in 6 functions (corrected from 5): MetalContext.m:970,971 (quads), 1160,1165 (geom short), 1205,1210 (geom int); MetalTexture.m:315,353,390 (`nUpdate`, `nUpdateFloat`, `nUpdateInt`). Releases (mode 0) at MetalContext.m:975,976,1167,1175,1176,1212,1220,1221 and MetalTexture.m:328,366,403 | `grep -nE 'Get[A-Za-z]*ArrayCritical' C/*.m` |
| `GetStringCritical` | 1 (MetalShader.m:36, released :42; `GetStringLength` :34) | — |
| `Get…ArrayElements` (copying) | 4, all released with mode **0** (copy back): Int MetalRTTexture.m:234/238, 263/267; Byte MetalTexture.m:418/429; Float MetalShader.m:475/477 | — |
| `GetDirectBufferAddress` / `GetDirectBufferCapacity` / `NewDirectByteBuffer` | 6 (MetalContext.m:888; MetalRTTexture.m:250; MetalTexture.m:316,354,391; MetalShader.m:461) / 4 (MetalContext.m:894; MetalTexture.m:311,349,386) / 0 | — |
| `NewStringUTF` | 1 (MetalShader.m:121) | — |
| `GetArrayLength` | 7 (MetalContext.m:1153,1154,1198,1199; MetalTexture.m:310,348,385) | — |
| `ThrowNew` / `Exception*` / `GLASS_CHECK_EXCEPTION` | 0 / 0 / 0 | `grep -cE 'ThrowNew\|ExceptionCheck\|…'` = 0 |
| `GetJavaVM` / `Attach` / `DetachCurrentThread` / `JNI_OnLoad` / `_initIDs` | 0 / 0 / 0 / 0 / 0 | — |
| Generated-header includes | 5 sites over 4 headers: MetalContext.m:41 (`…MTLContext.h`), :42 (`…MTLPipeline.h`); MetalPipelineManager.m:28 (`…MTLContext.h`); MetalShader.m:29 (`…MTLShader.h`); MetalRTTexture.m:27 (`…MTLRTTexture.h`) | `grep -n 'com_sun_prism_mtl' C/*` |
| Generated constants used | 8 distinct, 9 sites: `MTL_COMPMODE_SRCOVER` MetalContext.m:69; `CULL_BACK/FRONT/NONE` (110/111/112) :1339,1342,1345; `MTL_COMPMODE_CLEAR/SRC/SRCOVER/DSTOUT/ADD` (0–4) MetalPipelineManager.m:277–297. The other three headers contribute no constants | — |
| ObjC exception sites | `@try/@finally` MetalShader.m:38–43; `NSAssert` (still active under `-DNDEBUG`) MetalPipelineManager.m:151,188,200,256 | — |
| Size | 29 files / 5,737 lines (21 C/ObjC = 4,812; `msl/` = 925) | `find C -type f \| xargs cat \| wc -l` |

## 3. Java classes and C files

| Java class | Natives (file:line) → C body | Load / handles |
|---|---|---|
| `MTLPipeline` (public) | `nSupportsMTL()Z` :43 → MetalContext.m:1560 | `<clinit>` :45–56 `NativeLibLoader.loadLibrary("prism_mtl")` |
| `MTLContext` | `nInitialize(Ljava/nio/ByteBuffer;)J` :496→882 · `nCommitCurrentCommandBuffer(J)V` :497→952 · `nGetCommandQueue(J)J` :498→1492 · `nDrawIndexedQuads(J[F[BI)V` :499→964 · `nUpdateRenderTarget(JJZ)I` :500→984 · `nSetProjViewMatrix(JZ+16D)V` :502→1031 · `nSetTransform(J+16D)V` :507→1052 · `nSetCompositeMode(JI)V` :513→1479 · `nResetClipRect(J)V` :514→1019 · `nSetClipRect(JIIII)V` :515→1006 · `nSetWorldTransformToIdentity(J)V` :517→1100 (**no caller**) · `nSetWorldTransform(J+16D)V` :518→1080 · `nSetCameraPosition(JDDD)V` :523→1466 · `nCreateMTLMesh(J)J` :524→1112 · `nReleaseMTLMesh(JJ)V` :525→1126 · `nBuildNativeGeometryShort(JJ[FI[SI)Z` :526→1141 · `nBuildNativeGeometryInt(JJ[FI[II)Z` :528→1186 · `nCreateMTLPhongMaterial(J)J` :530→1231 · `nReleaseMTLPhongMaterial(JJ)V` :531→1244 · `nSetDiffuseColor(JJFFFF)V` :532→1259 · `nSetSpecularColor(JJZFFFF)V` :534→1272 · `nSetMap(JJIJ)V` :536→1286 · `nCreateMTLMeshView(JJ)J` :538→1301 · `nReleaseMTLMeshView(JJ)V` :539→1318 · `nSetCullingMode(JJI)V` :540→1333 · `nSetMaterial(JJJ)V` :542→1357 · `nSetWireframe(JJZ)V` :544→1371 · `nSetAmbientLight(JJFFF)V` :546→1384 · `nSetLight(JJI+18F)V` :548→1397 · `nRenderMeshView(JJ)V` :555→1419 · `nBlit(JJJIIIIIIII)V` :557→1445 · `nRelease(J)V` :561→936 · `nIsCurrentRTT(JJ)Z` :563→1432 · `nDisposeShader(J)V` :564→921 | `<clinit>` :95–111 reads the resource `msl/jfxshaders.metallib` into a static direct `ByteBuffer` (position left **at capacity**). `long pContext` :72 |
| `MTLGraphics` | `nClear(JFFFFZ)V` :64 → MetalContext.m:1506 | — |
| `MTLResourceFactory` | `nCreateTexture(JIIZIIIZ)J` :418 → MetalContext.m:1526 (hint, isRTT and samples unused) · `nReleaseTexture(J)V` :421 → :1544 | package-private `static native` |
| `MTLShader` | `nCreateMetalShader(JLjava/lang/String;)J` :190→MetalShader.m:328 · `nGetUniformNameIdMap(J)Ljava/util/Map;` :191→343 · `nEnable` :192→355 · `nDisable` :193→367 · `nSetTexture(JIIJZI)V` :195→379 · `nSetInt(JII)V` :198→394 · `nSetFloat1..4` :200,201,203,205→406,418,430,443 · `nSetConstants(JI[FI)V` :208→470 · `nSetConstantsBuf(JILjava/lang/Object;II)V` :210→456 | `long nMetalShaderRef` :39 |
| `MTLRTTexture` | `nCreateRT(JIIII…WrapMode;Z)J` :168→MetalRTTexture.m:193 (wrapMode unused) · `nCreateRT2(JJII)J` :170→212 · `nReadPixels(J[I)V` :171→259 · `nReadPixelsFromRTT(JLjava/nio/IntBuffer;)V` :172→246 · `nGetPixelDataPtr(J)J` :173→**none** · `nInitRTT(J[I)V` :174→230 | `long nTexPtr` :46 |
| `MTLTexture` | `nUpdate(J ByteBuffer [B IIIIIII)V` :254→MetalTexture.m:302 · `nUpdateFloat` :258→340 · `nUpdateInt` :262→377 · `nUpdateYUV422(J[BIIIIIII)V` :266→413 | `long texPtr` :41 |
| No natives | `MTLTextureData`/`MTLRTTextureData`/`MTLFBOTextureData` (`long pTexture`), `MTLMesh`/`MTLMeshView`/`MTLPhongMaterial` (`long nativeHandle` plus disposer records), `MTLSwapChain`, `MTLVramPool`, `MTLRenderTarget`, `MTLTextureResource` | `com.sun.prism.mtl` is **not exported** (module-info.java), so handles may become `MemorySegment` directly (rule 2.4). Exceptions: the two `long`s crossing to or from Glass (§4a) |

| C file | Lines | JNI content | Role |
|---|---:|---|---|
| MetalContext.m | 1569 | 38 exports (MTLContext 34 + Graphics 1 + ResourceFactory 2 + Pipeline 1); 6 critical, 2 direct-buffer | context, ring buffers, command buffer, clear, quads, clip, blit |
| MetalShader.m | 478 | 12 exports; `jStringToNSString` :31 (exported, non-static); the only upcalls :93–133; includes the JSLC headers | shader, argument encoder, uniforms |
| MetalRTTexture.m | 268 | 5 exports; `jlong_to_ptr` in ObjC :102 | RTT, MSAA, depth, readback |
| MetalTexture.m | 433 | 4 exports; 3 critical | texture create/upload, YUV kernel |
| MetalPipelineManager.m | 366 | includes generated header :28 (composite constants) | metallib load, pipeline-state caches, blend table |
| MetalCommon.h | 37 | `#import <jni.h>` :29; `jlong_to_ptr`/`ptr_to_jlong` :34–35 | root of jni.h for every TU |
| MetalShader.h | 93 | `- (jobject) getUniformNameIdMap:(JNIEnv*)env;` | — |
| MetalMesh/MeshView/PhongMaterial/Light/RingBuffer .m/.h; MetalContext.h, MetalTexture.h, MetalRTTexture.h, MetalPipelineManager.h | rest | no JNI | 3D, ring buffer |
| msl/*.metal + 3 .h | 925 | none | `passThrough`, `clearVF`/`clearFF`, `PhongVS0-3`/`PhongPS0-3`, `uyvy422_to_rgba`; the uniform structs mirror MetalMeshView.h:36–57 (C↔MSL only, never Java) |

## 4. External dependencies

- **Build.** `add_jfx_library(prismMTL OUTPUT_NAME prism_mtl …)` at `modules/javafx.graphics/native/mac.cmake:186–191`.
  - `SOURCE_DIRS native-prism-mtl`, `INCLUDE_DIRS ${GENSRC_DIR}/mtl-headers`,
    `COMPILE_OPTIONS ${JFX_CC_OPTIONS} -DMACOSX -Werror=objc-method-access`.
  - `LINK_LIBS ${JFX_FRAMEWORK_LINK_LIBS}` (:110–119: AppKit, ApplicationServices, Carbon, OpenGL, QuartzCore,
    Security, Network, Metal, objc).
  - Every target gets `${JDK_HOME}/include`, `${JDK_HOME}/include/darwin` and `${HEADERS_DIR}` from the shared
    function at :149–151.
- **`otool -L`.** All 8 frameworks plus libobjc, libc++, libSystem, CoreFoundation and Foundation. `nm -u` references
  **no** symbol from AppKit, ApplicationServices, Carbon, OpenGL, QuartzCore, Security or Network; they are linked but
  unused (a load-time side effect only, so do not drop them in a migration).
- **`nm -u`: 64 undefined symbols.**
  - C-callable: `_MTLCreateSystemDefaultDevice` (Metal); `_dispatch_data_create`,
    `_dispatch_semaphore_create/signal/wait` (libdispatch); `_cos` (libm, MetalMeshView.m:181–182); `_memcpy`; `_NSLog` (variadic, NSString);
    `_matrix_identity_float4x4` (simd data).
  - libobjc: `_objc_alloc`, `_objc_alloc_init`, `_objc_opt_new`, `_objc_retain`, `_objc_release`, `_objc_autorelease`,
    `_objc_autoreleasePoolPush/Pop`, `_objc_msgSend`, `_objc_msgSendSuper2`, `_objc_enumerationMutation`, plus
    exception personality and the Block runtime.
  - Classes: `MTL{DepthStencil,RenderPass,RenderPipeline,Sampler,Texture}Descriptor`, `NSArray`, `NSDictionary`,
    `NSMutable{Array,Dictionary,Set}`, `NSNumber`, `NSString`, `NSLock`, `NSAssertionHandler`, `NSObject`.
  - `_dlsym`, `_fopen/_fread/_fseek/_ftell/_rewind/_fclose`, `_malloc/_free`, `_sscanf`, `_dispatch_once_f` and
    `___assert_rtn` are **not** from project sources (attributed per object file with `nm -u *.m.o`). They come from
    compiler-rt's `@available` support (`___isPlatformVersionAtLeast` at dylib 0x1eeb0), pulled in by
    `@available(macOS 13, *)` at MetalRingBuffer.m:43.
- **`nm -gU`: 324 exported `T` symbols** = 59 `Java_*` + `_jStringToNSString` + 264 non-static
  `get*_Uniform_VarID_Dict`/`getPRISMDict`/`getDECORADict` functions that the JSLC headers *define*.

### 4a. Coupling with Glass, the JSLC `mtl-headers`, and the metallib

| Coupling | Flow | Threads | Ownership / hazards |
|---|---|---|---|
| `"mtlCommandQueue"` (Prism → Glass) | `MTLPipeline.getResourceFactory` J/MTLPipeline.java:95–98 puts `nGetCommandQueue()` (a raw `id<MTLCommandQueue>` as `Long`) into the pipeline's device-details `HashMap`. QuantumRenderer.java:93–103 makes **that same object** `Application.deviceDetails`. Glass reads it with JNI `Map.get` + `Long.longValue` at G/GlassView3D.m:87–106 (non-zero selects the Metal layer, :137) and G/GlassViewMTL.m:63–80. It is stored **unretained** in GlassLayerMTL.m:55 `_blitCommandQueue` and GlassMTLOffscreen.m:58 | written on the render thread; read on the AppKit main thread (`GET_MAIN_JENV`, GlassViewMTL.m:42) | owned by `MetalContext` (released in `-dealloc`, MetalContext.m:771–773). A Glass layer that outlives `MTLPipeline.dispose` keeps a dangling queue (latent). **The value must stay the raw address as a `Long`**: Glass/mac is still JNI until workstream B |
| `"isVsyncEnabled"` | MTLPipeline.java:69–71 → GlassViewMTL.m:81–89 | same | — |
| Frame buffer (Glass → Prism) | `MacView._getNativeFrameBuffer` (G/GlassView.m:326–345) returns `[[layer getPainterOffscreen] fbo]`, the `GlassMTLFrameBufferObject`'s `id<MTLTexture>` (GlassMTLOffscreen.m:80–84). Then `PresentableState.lock` (PresentableState.java:283–288) → `MTLSwapChain.createGraphics` (:123–145) → `MTLRTTexture.create(ctx, pTex,…)` → `nCreateRT2`, which wraps the texture **without retain** (MetalRTTexture.m:102–103) | render thread; `MacView._begin` pushes an autorelease pool popped by `_end` on that thread (GlassView.m:470, :496) | `MTLFBOTextureData.dispose` never releases the wrapper (comment "handled by Glass"), so the wrapper leaks and Glass's texture is never over-released (latent, keep) |
| Present | `MTLSwapChain.present` :98–103 → `nCommitCurrentCommandBuffer`. Glass's `GlassLayerMTL blitToScreen` (GlassLayerMTL.m:85+) copies the FBO texture to a `CAMetalDrawable` on **Prism's** queue | main thread (CA display, `performSelectorOnMainThread` in GlassMTLOffscreen `flush`) | — |
| JSLC `mtl-headers` | `GEN/mtl-headers/PrismShaderCommon.h` (9,981 lines; `getPRISMDict` :9128) and `DecoraShaderCommon.h` (2,091; `getDECORADict` :1886) are imported **only** by MetalShader.m:26–27. They **define** 264 non-static functions returning autoreleased `NSDictionary` objects of uniform name → argument-buffer index. These indices differ from the Java loaders' `params` maps (e.g. `Solid_ImagePattern`: MSL `xParams=1, perspVec=3`; `Solid_ImagePattern_Loader` `xParams=0, perspVec=2`), so the **only** source of truth is the generated ObjC | — | A second TU that includes them causes **duplicate symbols**, so the new uniform enumeration must live in MetalShader.m |
| `jfxshaders.metallib` | Built by mac.cmake:201–247: 212 prism + 50 decora generated `.metal` (`-Wdeprecated -I mtl-headers`) plus 5 hand-written `msl/*.metal` → `.air` → `metallib` → `target/native/shader-obj/com/sun/prism/mtl/msl/jfxshaders.metallib` (1,621,807 B in the baseline). Copied into `target/classes` by `copy-shaders` (pom.xml:243). At run time: MTLContext.java:95–111 → direct buffer → `nInitialize` (MetalContext.m:888–905; base address and **capacity**, position ignored) → `dispatch_data_create` with a **no-op destructor and no copy** → `-[MTLDevice newLibraryWithData:error:]` (MetalPipelineManager.m:64). Functions looked up by name: `passThrough` (:67), `clearVF`/`clearFF` (:137–138), `PhongVS%d`/`PhongPS%d` (:229–230), `uyvy422_to_rgba` (MetalTexture.m:244), and every JSL shader name (`MetalShader` :71) | render thread | the buffer must live for the process (it is `static final`) |

## 5. Triage table

Every export runs **on the Prism render thread** (QuantumRenderer; `MTLPipeline.<clinit>` and `nSupportsMTL` run in
`GraphicsPipeline.createPipeline` on that thread, QuantumRenderer.java:88). The JNI code does **no** thread
marshalling: no `performSelectorOnMainThread`, `dispatch_async` or `GET_MAIN_JENV`. The only foreign thread is Metal's
completion-handler block (MetalContext.m:275–297); it touches only C state (file-static `isBufferInUse[]`, semaphore,
`NSLock`) and never Java.

"Blocks" means the function can reach `dispatch_semaphore_wait(FOREVER)` (MetalContext.m:304–309) or
`waitUntilCompleted` (:302).

Legend: **OS** = OS-CALL into Metal/Foundation/libdispatch; **OS-st** = OS-CALL whose body is only objc dispatch to
project objects (ivar state). Parity for OS rows: n/a, stays native.

| # | Export (C/…:line) | Verdict | Evidence | Blocks | Replacement |
|---|---|---|---|---|---|
| 1 | nInitialize MetalContext.m:882 | OS | `dispatch_data_create`, `MTLCreateSystemDefaultDevice`, `newCommandQueue`, `newLibraryWithData:error:`, blit + commit | no | `mtl_context_create` |
| 2 | nDisposeShader :921 | **WRAPPER** | disasm 0x64c8: `cbz x2; b _objc_release` | no | Java `objc_release` (guarded by `isValid()`) |
| 3 | nRelease :936 | OS | `objc_msgSend$dealloc` (direct `-dealloc`, disasm 0x64d8) → commit **and wait** | **yes** | `mtl_context_release` |
| 4 | nCommitCurrentCommandBuffer :952 | OS | `-[MTLCommandBuffer addCompletedHandler:/commit]`, `dispatch_semaphore_wait` | **yes** | `mtl_context_commit` |
| 5 | nDrawIndexedQuads :964 | OS | `-[MTLRenderCommandEncoder …drawIndexedPrimitives…]`, lazy `newRenderPipelineStateWithDescriptor:` (MetalPipelineManager.m:185), commit when `commitOnDraw` (:454–456) | **yes** | `mtl_context_draw_indexed_quads` (see R1) |
| 6 | nUpdateRenderTarget :984 | OS | `MTLRenderPassDescriptor` setters, `-endEncoding`, depth `newTextureWithDescriptor:` | no | `mtl_context_update_render_target` |
| 7 | nSetClipRect :1006 | OS | `-[MTLTexture width/height]` | no | `mtl_context_set_clip_rect` |
| 8 | nResetClipRect :1019 | OS-st | `-[MetalContext resetClipRect]` ivars only | no | `mtl_context_reset_clip_rect` |
| 9 | nSetProjViewMatrix :1031 | OS | depth-attachment descriptor setters | no | `mtl_context_set_proj_view_matrix` |
| 10 | nSetTransform :1052 | OS-st | ivar `mvpMatrix` | no | `mtl_context_set_transform` |
| 11 | nSetWorldTransform :1080 | OS-st | ivar | no | `mtl_context_set_world_transform` |
| 12 | nSetWorldTransformToIdentity :1100 | **dead** | no Java caller; uses `_matrix_identity_float4x4` | — | delete both sides plus `-setWorldTransformIdentityMatrix` (:509–512) |
| 13 | nCreateMTLMesh :1112 | OS-st | `objc_alloc` + init | no | `mtl_mesh_create` |
| 14 | nReleaseMTLMesh :1126 | **WRAPPER** | disasm 0x67a4 `objc_release`; `MetalMesh` overrides `-release` (MetalMesh.m:115), and `objc_release` dispatches to it (custom RR) | no | Java `objc_release` |
| 15–16 | nBuildNativeGeometryShort/Int :1141/:1186 | OS | `-[MTLDevice newBufferWithLength:options:]` + memcpy | no | `mtl_mesh_build_geometry_short/_int` |
| 17 | nCreateMTLPhongMaterial :1231 | OS-st | `objc_alloc` | no | `mtl_phong_material_create` |
| 18 | nReleaseMTLPhongMaterial :1244 | **WRAPPER** | disasm 0x6a70 `objc_release` | no | Java `objc_release` |
| 19–21 | nSetDiffuseColor / nSetSpecularColor / nSetMap :1259/:1272/:1286 | OS-st | ivars (`setMap` stores `id<MTLTexture>` unretained, MetalPhongMaterial.m:119) | no | `mtl_phong_material_set_*` |
| 22 | nCreateMTLMeshView :1301 | OS-st | `objc_alloc` | no | `mtl_mesh_view_create` |
| 23 | nReleaseMTLMeshView :1318 | **WRAPPER** | disasm 0x6b00 `objc_release`; `MetalMeshView` overrides `-release` (MetalMeshView.m:245) | no | Java `objc_release` |
| 24–28 | nSetCullingMode / nSetMaterial / nSetWireframe / nSetAmbientLight / nSetLight :1333/:1357/:1371/:1384/:1397 | OS-st | ivars; `nSetLight` may `objc_alloc` a `MetalLight`; cull 110/111/112 → MTLCullMode | no | `mtl_mesh_view_set_*` |
| 29 | nRenderMeshView :1419 | OS | Phong pipeline state, encoder draw, `cos` | no (note 1) | `mtl_mesh_view_render` |
| 30 | nIsCurrentRTT :1432 | OS-st | pointer compare of ivar `rtt` | no | `mtl_context_is_current_rtt` |
| 31 | nBlit :1445 | OS | `-[MTLBlitCommandEncoder synchronizeTexture:/copyFromTexture:…]` | no | `mtl_context_blit` |
| 32–33 | nSetCameraPosition / nSetCompositeMode :1466/:1479 | OS-st | ivars | no | `mtl_context_set_*` |
| 34 | nGetCommandQueue :1492 | OS-st | `objc_msgSend$getCommandQueue` (0x6c3c) | no | `mtl_context_get_command_queue` |
| 35 | MTLGraphics.nClear :1506 | OS | clear pipeline state + draw | no | `mtl_context_clear` |
| 36 | MTLResourceFactory.nCreateTexture :1526 | OS | `newTextureWithDescriptor:` | no | `mtl_texture_create` |
| 37 | MTLResourceFactory.nReleaseTexture :1544 | **WRAPPER** | 0x6cb4 unconditional `b _objc_release` (nil-safe) | no | Java `objc_release` (callers already guard `0L`) |
| 38 | MTLPipeline.nSupportsMTL :1560 | OS | `MTLCreateSystemDefaultDevice` (C) **+** `objc_msgSend$supportsFamily:` (0x6cc4–0x6ccc), so not a WRAPPER | no | `mtl_supports_metal` |
| 39 | nCreateMetalShader MetalShader.m:328 | OS | `+[NSString stringWithCharacters:length:]`, `newFunctionWithName:`, `newArgumentEncoderWithBufferIndex:`, `newBufferWithLength:` | no | `mtl_shader_create` |
| 40 | nGetUniformNameIdMap :343 | OS | Foundation enumeration of the JSLC `NSDictionary`, `-UTF8String`, `-intValue`; **plus the 3 JNI upcalls** | no | `mtl_shader_uniform_count` + `_table`; Java builds the `HashMap` |
| 41–42 | nEnable / nDisable :355/:367 | OS-st | `-[MetalContext setCurrentShader:]` | no | `mtl_shader_enable/_disable` |
| 43 | nSetTexture :379 | OS | `-[MTLArgumentEncoder setTexture:atIndex:]`, lazy `newSamplerStateWithDescriptor:` | no | `mtl_shader_set_texture` |
| 44–48 | nSetInt / nSetFloat1..4 :394–443 | OS | `-[MTLArgumentEncoder constantDataAtIndex:]` | no | `mtl_shader_set_int/_float1..4` |
| 49–50 | nSetConstantsBuf / nSetConstants :456/:470 | OS | `constantDataAtIndex:` + memcpy | no | merged into `mtl_shader_set_constants` |
| 51 | nCreateRT MetalRTTexture.m:193 | OS | `newTextureWithDescriptor:` (Managed BGRA8 + Private 4× MSAA) | no | `mtl_rtt_create` |
| 52 | nCreateRT2 :212 | OS-st | `objc_alloc`; wraps the Glass texture | no | `mtl_rtt_wrap` |
| 53 | nInitRTT :230 | OS | `-[MTLTexture replaceRegion:…]` | no | `mtl_rtt_init` |
| 54–55 | nReadPixelsFromRTT / nReadPixels :246/:259 | OS | blit to the pixel buffer + `commitCurrentCommandBufferAndWait` (MetalTexture.m:164) | **yes** | `mtl_rtt_read_pixels_contiguous` / `mtl_rtt_read_pixels` |
| 56–58 | nUpdate / nUpdateFloat / nUpdateInt MetalTexture.m:302/340/377 | OS | ring-buffer memcpy, `copyFromBuffer:…toTexture:`, `generateMipmapsForTexture:` | no | merged into `mtl_texture_update` |
| 59 | nUpdateYUV422 :413 | OS | `newBufferWithLength:` (Managed), compute dispatch, commit | **yes** | `mtl_texture_update_yuv422` |
| — | MTLRTTexture.nGetPixelDataPtr (Java only) | **dead** | no C body, no caller | — | delete the declaration |

Note 1: `nRenderMeshView` does not commit, but it may compile a Phong pipeline state on first use.

## 6. Upcall table

| Java target | C site | Calling thread | Replacement |
|---|---|---|---|
| `HashMap.<init>()V` | MetalShader.m:118 `NewObject` | render thread, synchronously inside `nGetUniformNameIdMap` (from the `MTLShader` constructor, J/MTLShader.java:59) | none: Java builds the map |
| `Integer.<init>(I)V` | MetalShader.m:123 `NewObject` (once per key) | same | `Integer.valueOf`. Identity is irrelevant: callers only unbox `uniformNameIdMap.get(name)` |
| `HashMap.put(Object,Object)` | MetalShader.m:124 `CallObjectMethod` | same | none |

Exceptions: a pending exception after any of these is ignored (no `ExceptionCheck`) and the loop keeps calling JNI.
With FFM there are no upcalls, so there is no exception path.

## 7. Proposed C ABI header

The file would be `C/prism_mtl_api.h` with its implementation in `C/prism_mtl_api.m`, except that the uniform
enumeration goes in MetalShader.m (R5). No struct crosses the boundary.

```c
#ifndef PRISM_MTL_API_H
#define PRISM_MTL_API_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define JFX_EXPORT __attribute__((visibility("default")))
#define PRISM_MTL_ABI_VERSION 1u

/* Values the C took from com_sun_prism_mtl_MTLContext.h; pinned against MTLContext by MTLNativeTest. */
enum { MTL_COMPMODE_CLEAR = 0, MTL_COMPMODE_SRC = 1, MTL_COMPMODE_SRCOVER = 2,
       MTL_COMPMODE_DSTOUT = 3, MTL_COMPMODE_ADD = 4 };
enum { MTL_CULL_BACK = 110, MTL_CULL_FRONT = 111, MTL_CULL_NONE = 112 };

/*
 * Handles are Objective-C objects returned +1: ctx = MetalContext*, tex / rtt = MetalTexture* / MetalRTTexture*,
 * shader = MetalShader*, mesh = MetalMesh*, material = MetalPhongMaterial*, view = MetalMeshView*.
 * Freed by mtl_context_release (ctx) or by objc_release, which Java binds from libobjc (all others).
 * Every function: Prism render thread only. BLOCKS = may wait on GPU completion; never bind critical.
 */

JFX_EXPORT uint32_t mtl_abi_version(void);
/* Test hook: 0-4 COMPMODE, 5-7 CULL, 8 sizeof(PrismSourceVertex) = 28, 9 NUM_OF_FLOATS_PER_VERTEX = 9,
 * 10 MAX_NUM_QUADS = 4096, 11 MAX_NUM_LIGHTS = 3, 12.. PFORMAT_* in enum order; -1 out of range. */
JFX_EXPORT int32_t  mtl_abi_constant(int32_t index);
/* Default device supportsFamily:Mac2; leaks the device (verbatim). */
JFX_EXPORT int32_t  mtl_supports_metal(void);

/* metallib is borrowed, NOT copied: it must outlive ctx. NULL on failure. */
JFX_EXPORT void*   mtl_context_create(const void* metallib, int64_t size);
JFX_EXPORT void    mtl_context_release(void* ctx);   /* sends -dealloc (verbatim); BLOCKS */
JFX_EXPORT void    mtl_context_commit(void* ctx);    /* BLOCKS */
/* coords: 7 floats per vertex; colors: 4 bytes per vertex. BLOCKS; may compile pipeline state. */
JFX_EXPORT void    mtl_context_draw_indexed_quads(void* ctx, const float* coords, const uint8_t* colors,
        int32_t num_vertices);
JFX_EXPORT int32_t mtl_context_update_render_target(void* ctx, void* rtt, int32_t depth_test); /* always 1 */
JFX_EXPORT void    mtl_context_set_clip_rect(void* ctx, int32_t x, int32_t y, int32_t w, int32_t h);
JFX_EXPORT void    mtl_context_reset_clip_rect(void* ctx);
JFX_EXPORT void    mtl_context_set_proj_view_matrix(void* ctx, int32_t depth_test,
        double m00, double m01, double m02, double m03, double m10, double m11, double m12, double m13,
        double m20, double m21, double m22, double m23, double m30, double m31, double m32, double m33);
JFX_EXPORT void    mtl_context_set_transform(void* ctx,
        double m00, double m01, double m02, double m03, double m10, double m11, double m12, double m13,
        double m20, double m21, double m22, double m23, double m30, double m31, double m32, double m33);
JFX_EXPORT void    mtl_context_set_world_transform(void* ctx,
        double m00, double m01, double m02, double m03, double m10, double m11, double m12, double m13,
        double m20, double m21, double m22, double m23, double m30, double m31, double m32, double m33);
/* Narrowed to float in C, as now. */
JFX_EXPORT void    mtl_context_set_camera_position(void* ctx, double x, double y, double z);
JFX_EXPORT void    mtl_context_set_composite_mode(void* ctx, int32_t mode);   /* MTL_COMPMODE_* */
JFX_EXPORT int32_t mtl_context_is_current_rtt(void* ctx, void* rtt);
/* Coordinates ignored (verbatim). */
JFX_EXPORT void    mtl_context_blit(void* ctx, void* src_rtt, void* dst_rtt,
        int32_t sx0, int32_t sy0, int32_t sx1, int32_t sy1, int32_t dx0, int32_t dy0, int32_t dx1, int32_t dy1);
/* Borrowed id<MTLCommandQueue>, owned by ctx. */
JFX_EXPORT void*   mtl_context_get_command_queue(void* ctx);
JFX_EXPORT void    mtl_context_clear(void* ctx, float r, float g, float b, float a, int32_t clear_depth);

/* pixel_format: PixelFormat.ordinal(). */
JFX_EXPORT void*   mtl_texture_create(void* ctx, int32_t pixel_format, int32_t w, int32_t h, int32_t use_mipmap);
/* pixels: array/buffer BASE, position ignored. */
JFX_EXPORT void    mtl_texture_update(void* tex, const void* pixels,
        int32_t dstx, int32_t dsty, int32_t srcx, int32_t srcy, int32_t w, int32_t h, int32_t scan_bytes);
/* BLOCKS */
JFX_EXPORT void    mtl_texture_update_yuv422(void* tex, const uint8_t* pixels,
        int32_t dstx, int32_t dsty, int32_t srcx, int32_t srcy, int32_t w, int32_t h, int32_t scan_bytes);

JFX_EXPORT void*   mtl_rtt_create(void* ctx, int32_t pw, int32_t ph, int32_t cw, int32_t ch, int32_t msaa);
/* mtl_texture is Glass-owned and NOT retained. */
JFX_EXPORT void*   mtl_rtt_wrap(void* ctx, void* mtl_texture, int32_t pw, int32_t ph);
JFX_EXPORT void    mtl_rtt_init(void* rtt, const int32_t* pixels);  /* reads physical w*h ints (over-read latent, L6) */
JFX_EXPORT void    mtl_rtt_read_pixels(void* rtt, int32_t* dst);    /* content w*h; BLOCKS */
JFX_EXPORT void    mtl_rtt_read_pixels_contiguous(void* rtt, int32_t* dst); /* BLOCKS; dst NULL crashes, as now */

JFX_EXPORT void*   mtl_shader_create(void* ctx, const uint16_t* name_utf16, int32_t name_len);
JFX_EXPORT int32_t mtl_shader_uniform_count(void* shader);
/* Fast-enumeration order; names = NUL-separated UTF-8; returns bytes needed (names may be NULL). */
JFX_EXPORT int32_t mtl_shader_uniform_table(void* shader, int32_t* ids, int32_t ids_cap, char* names,
        int32_t names_cap);
JFX_EXPORT void    mtl_shader_enable(void* shader);
JFX_EXPORT void    mtl_shader_disable(void* shader);
/* wrap_mode: -1 or MTLSamplerAddressMode. */
JFX_EXPORT void    mtl_shader_set_texture(void* shader, int32_t tex_unit, int32_t uniform_id, void* tex,
        int32_t linear, int32_t wrap_mode);
JFX_EXPORT void    mtl_shader_set_int(void* shader, int32_t uniform_id, int32_t i0);
JFX_EXPORT void    mtl_shader_set_float1(void* shader, int32_t uniform_id, float f0);
JFX_EXPORT void    mtl_shader_set_float2(void* shader, int32_t uniform_id, float f0, float f1);
JFX_EXPORT void    mtl_shader_set_float3(void* shader, int32_t uniform_id, float f0, float f1, float f2);
JFX_EXPORT void    mtl_shader_set_float4(void* shader, int32_t uniform_id, float f0, float f1, float f2, float f3);
JFX_EXPORT void    mtl_shader_set_constants(void* shader, int32_t uniform_id, const float* values, int32_t float_count);

JFX_EXPORT void*   mtl_mesh_create(void* ctx);
/* Capacity checks kept in C. */
JFX_EXPORT int32_t mtl_mesh_build_geometry_short(void* mesh, const float* vb, int32_t vb_len, int32_t vb_capacity,
        const uint16_t* ib, int32_t ib_len, int32_t ib_capacity);
JFX_EXPORT int32_t mtl_mesh_build_geometry_int(void* mesh, const float* vb, int32_t vb_len, int32_t vb_capacity,
        const uint32_t* ib, int32_t ib_len, int32_t ib_capacity);
JFX_EXPORT void*   mtl_phong_material_create(void* ctx);
JFX_EXPORT void    mtl_phong_material_set_diffuse_color(void* m, float r, float g, float b, float a);
JFX_EXPORT void    mtl_phong_material_set_specular_color(void* m, int32_t set, float r, float g, float b, float a);
/* tex may be NULL; stored unretained. */
JFX_EXPORT void    mtl_phong_material_set_map(void* m, int32_t map_type, void* tex);
JFX_EXPORT void*   mtl_mesh_view_create(void* ctx, void* mesh);
JFX_EXPORT void    mtl_mesh_view_set_culling_mode(void* view, int32_t cull);   /* MTL_CULL_* */
JFX_EXPORT void    mtl_mesh_view_set_material(void* view, void* material);
JFX_EXPORT void    mtl_mesh_view_set_wireframe(void* view, int32_t wireframe);
JFX_EXPORT void    mtl_mesh_view_set_ambient_light(void* view, float r, float g, float b);
JFX_EXPORT void    mtl_mesh_view_set_light(void* view, int32_t index, float x, float y, float z,
        float r, float g, float b, float w, float ca, float la, float qa, float is_attenuated, float max_range,
        float dir_x, float dir_y, float dir_z, float inner_angle, float outer_angle, float falloff);
JFX_EXPORT void    mtl_mesh_view_render(void* ctx, void* view);

#ifdef __cplusplus
}
#endif

#endif
```

Java also binds `void objc_release(id)` from `/usr/lib/libobjc.A.dylib` via `libraryLookup`, with
`FunctionDescriptor.ofVoid(ADDRESS)`.

### Marshalling and lifetime

| Value | Encoding / passing | Owner and lifetime |
|---|---|---|
| metallib | `MemorySegment.ofBuffer(shaderLibBuffer.duplicate().clear())`: position is at capacity after `put` (R2) | the static direct buffer, process lifetime |
| quad `coords`/`colors` | per-context scratch segment (`Arena.ofConfined()` owned by `MTLContext`, created on the render thread, closed after `mtl_context_release`), filled with `MemorySegment.copy`; or `critical(true)` (R1) | per call |
| `mtl_texture_update` pixels | heap: `MemorySegment.ofArray(arr)` under `critical(true)` (the JNI used Critical). Direct: `ofBuffer(buf.duplicate().clear())` (base, as `GetDirectBufferAddress`). Neither: `NULL` (verbatim) | per call |
| geometry arrays, `set_constants` heap | `critical(true)` + `ofArray` (non-blocking) | per call |
| `set_constants` direct | base segment `.asSlice(valuesByteOffset)` | per call |
| `rtt_init`, `yuv422` | confined-arena copy (the JNI copied) | per call |
| `rtt_read_pixels` | confined arena, then copied back into the `int[]` (JNI mode-0 copy-back) | per call |
| `rtt_read_pixels_contiguous` | direct base or `NULL` for heap buffers (R3) | per call |
| shader name | UTF-16 code units + length (`GetStringCritical` + `stringWithCharacters:length:` today) | per-call arena |
| uniform names out | UTF-8 from `-UTF8String`; ASCII JSL identifiers, so identical to `NewStringUTF`'s modified UTF-8 | Java-allocated buffers |
| booleans | `int32_t`, tested `!= 0` in C (as `if (jboolean)` today; `nClear` compiled `cmp/cset`) | — |
| command queue / Glass texture | `MemorySegment` inside; `.address()` / `ofAddress(long)` at the Glass `Long`/`long` boundary | borrowed |

Element layouts that cross as raw memory:

- `PrismSourceVertex`: 7 floats = 28 B (MetalContext.h:44–48), equal to MSL `VS_INPUT`.
- Colors: `uchar4`.
- Mesh vertex: 9 floats (MetalMesh.h).
- Index type: u16 or u32.
- Pixel formats: BGRA8, A8, RGBA32F.

No Java exception can reach native code: there are no upcalls. An `NSException` (from `NSAssert`) unwinds through
the stub and crashes, exactly as it does today (R9).

## 8. Callback tables

**None.** The only upcall class (uniform map building) becomes out-parameters. There are no foreign-thread callbacks
to Java: the Metal completion block stays internal to the C. No upcall stubs and no stub arenas are needed.

## 9. Deletion candidates (all clear the parity gate)

| Candidate | Verdict / parity | C removed | Copies | Java counterpart | Parity test asserts |
|---|---|---|---|---|---|
| nDisposeShader, nReleaseMTLMesh, nReleaseMTLPhongMaterial, nReleaseMTLMeshView, nReleaseTexture | WRAPPER, **PARITY: exact**: the arm64 machine code of each is `[cbz] b _objc_release`, so Java calls the same entry point | MetalContext.m:916–929, 1121–1134, 1239–1252, 1313–1326, 1539–1550 (68 lines) | 1 (macOS only) | one `objc_release` handle in `MTLNative`; all 5 Java call sites already guard `0L` | symbol resolves; create → `objc_release` round-trip for each handle type in a child JVM; the overridden `-release` still runs (mesh/view buffers freed). Owner decision: `objc_release` is **not declared in the SDK headers** (grep of `$SDK/usr/include/objc` finds nothing; it is the clang ARC runtime ABI). The alternative is `CFRelease` (public, already in `CFNative`): same observable effect via `-release` dispatch, but it aborts on `NULL`. x86_64 codegen not disassembled |
| nSetWorldTransformToIdentity | dead (no caller) | :1095–1105 + :509–512 + MetalContext.h:168 (16 lines) | 1 | — | none needed (unreachable) |
| MTLRTTexture.nGetPixelDataPtr | dead (no body, no caller) | 0 C lines | — | — | none |

## 10. Kept native despite being pure

| Function or fragment | Why it stays |
|---|---|
| 21 `OS-st` exports (state setters/getters on `MetalContext`, `MetalShader`, `MetalMeshView`, `MetalPhongMaterial`) | Pure state writes, but the state lives in ObjC ivars that the Metal-calling C reads. Moving it to Java would redesign the ABI (pass state per draw), not migrate it. **PARITY: unknown**: settling it would need that redesign plus golden replay. Out of scope ("transport-layer only") |
| `nIsCurrentRTT` | **PARITY: unknown**. Java's `MTLContext.renderTarget` is also written by `setRenderTargetTexture()` without a native call (J/MTLContext.java:113–115), so a Java mirror can diverge from the C `rtt`. Experiment: assert equality at every call over the golden corpus |
| Readback stride loop / memcpy (MetalRTTexture.m:149–153, :159); upload row copies (MetalTexture.m:61–79, 225–230) | **PARITY: exact** possible, but each needs a new export for the `MTLBuffer` contents pointer to save fewer than 10 lines. Not worth it |
| Spot-light factors `cos(deg*M_PI/180)` (MetalMeshView.m:181–182) | **PARITY: tolerance ≤1 float ulp** (Apple libm `cos` vs `StrictMath.cos`), inside an OS-CALL body. Stays |
| Uniform name→id data (JSLC `NSDictionary`) | The Java loaders' `params` maps differ (§4a), so no Java source of truth exists. **PARITY: exact** would be available only if JSLC emitted Java tables (US-018, out of scope) |

## 11. Build/test touchpoints, risks, recommendation

### Build

- mac.cmake:130–154 hands the JDK includes and `HEADERS_DIR` to every target (:149–151). AC9 needs a `JNI` opt-in like
  win.cmake.
- prismMTL (:186–191) must keep `INCLUDE_DIRS mtl-headers`.
- CMakeLists.txt:59 makes an empty `HEADERS_DIR` fatal, so prove AC9 by pointing it at an empty directory.
- `javac -h`: pom.xml:218–219; mac `-DHEADERS_DIR`: pom.xml:841.
- Remove `#import <jni.h>` from MetalCommon.h:29 and replace `jlong_to_ptr`/`ptr_to_jlong` (:34–35; used at
  MetalRTTexture.m:102) with plain casts.
- Change MetalShader.h's `getUniformNameIdMap:(JNIEnv*)` prototype.
- Delete the 5 generated-header includes and define the 8 constants locally (header above).
- A new `.m` is picked up by `file(GLOB)`; it needs a re-configure.

### Tests

- **No existing test touches `com.sun.prism.mtl`.** `grep -rlE 'com\.sun\.prism\.mtl|MTLContext|…|metal'` over
  `modules/*/src/test`, `src/shims` and `tests/system` finds nothing.
- Indirect owner-run coverage: robot `painttest`, `test3d` (8 classes) and `scenegraph` under the default mac order
  `mtl,es2,sw` (PrismSettings.java:208).
- New tests to add:
  - `MTLNativeTest`: symbols, `mtl_abi_version`, `mtl_abi_constant` vs the `MTLContext` constants, `PixelFormat`
    ordinals, `VertexBuffer` stride.
  - A uniform-table golden for all 262 shaders, captured from JNI `nGetUniformNameIdMap`.
  - In-JVM JNI-vs-`mtl_*` A/B while both exist.
  - `MTLGoldenRenderTest` (§12), machine-gated (`machine.*`: `hw.model`, macOS build, metallib and dylib sha256;
    `-Djfx.parity.require=true`) per jfx-ffm-testing §7.
- Robot and `tests/system` runs are owner-run.

### Risks

| # | Risk |
|---|---|
| R1 | **Critical plus blocking.** `nDrawIndexedQuads` holds `GetPrimitiveArrayCritical` across a possible `dispatch_semaphore_wait(FOREVER)` (MetalContext.m:304–309 via :454–456) and across lazy pipeline-state compilation. JDK behaviour (not measured here): HotSpot does not transition a `critical(true)` downcall to native state, so a blocking critical call delays every safepoint, where JNI only pinned the regions. Recommend a non-critical draw with per-context scratch copies (≤ 458,752 B coords + 65,536 B colours per flush with superShader). Alternative: `critical(true)` like D3D/ES2, but only with the commit split into a second, non-critical call. Mark `commit`, `release`, `read_pixels*` and `yuv422` non-critical |
| R2 | Position semantics. The JNI uses base addresses: `GetDirectBufferAddress`, array base, `arrayOffset` and position ignored (MetalTexture.m:314–316, 352–354, 389–391; MetalContext.m:888–894). `MemorySegment.ofBuffer` honours position; use `duplicate().clear()`. A naive port passes a **zero-length metallib** |
| R3 | Heap or read-only buffers make the JNI pass `NULL` and crash (`nReadPixelsFromRTT` with a heap `IntBuffer`, MetalRTTexture.m:250; `nUpdate*` with a heap buffer that has no array). Verbatim means passing `NULL`; making it work would be a behaviour change. **Production reaches the read-back crash when it prints.** Printing draws through `PrismPrintGraphics`, whose J2D factory cannot use a Metal texture (`J2DResourceFactory.isCompatibleTexture`, J2DResourceFactory.java:164–166). So `NGCanvas.printedCanvas` (NGCanvas.java:574–604) has `PixelData.save` (:288–299) read the canvas texture into an `IntBuffer.allocate` buffer, `MTLRTTexture.readPixels` (J/MTLRTTexture.java:125–134) passes it to `nReadPixelsFromRTT`, which hands `readPixelsFromRTT` a `NULL` destination, and that `memcpy`s the content into `NULL` (MetalRTTexture.m:156–160). Printing a `Canvas` that has been rendered on Metal therefore crashes the JVM, in the JNI build and in the FFM build alike. javafx.web's `RTImage.draw` takes the same path when it prints (RTImage.java:154–166). Traced in the source, not run (printing is owner-run). For `nUpdate*` no JavaFX caller was found: the images it uploads wrap arrays or direct buffers, and outside the other pipelines the only `asIntBuffer`/`asFloatBuffer`/`asReadOnlyBuffer` views in the production code of graphics, web, swing and media are in Monocle and Windows Glass, which never run with Metal, and in `BufferUtil`, whose buffers are direct. An application reaches it through a `javafx.scene.image.PixelBuffer` over a heap buffer without an accessible array, such as a read-only buffer or `ByteBuffer.allocate(n).asIntBuffer()`, which `PixelBuffer` does not reject. Owner to accept, with these paths named |
| R4 | Generated constants disappear once the classes have no natives; they need local definitions plus a pin test |
| R5 | The JSLC headers define symbols, so only MetalShader.m may include them |
| R6 | The Glass `Long`/`long` boundaries (§4a) must stay raw addresses. Ordering: workstream B reads the map via JNI until it is migrated |
| R7 | `objc_release` is not in the SDK headers and was verified on arm64 only |
| R8 | `MTLNative` with about 53 bindings would exceed the 900-line production-file budget; split it like `Gtk*Calls` (context / shader / mesh parts). D3DNative is 1,293 lines and ES2Native 1,458 |
| R9 | ObjC exceptions (`NSAssert`) through FFM frames crash, as now. Do not add `@try` |
| R10 | Unverified: objects autoreleased outside any pool on the render thread (e.g. `[NSMutableDictionary dictionary]` per upload, MetalTexture.m:47) build up in the thread's implicit pool except between `MacView._begin` and `_end`. This is unchanged as long as threads and call order are unchanged; do not add `@autoreleasepool` |
| R11 | 64-bit only (`jlong_to_ptr` is `(intptr_t)`); macOS is 64-bit only |

### Latent bugs to carry verbatim

- **L1** `nRelease` sends `-dealloc` directly (MetalContext.m:942); `-[MetalContext dealloc]` does the same to its ring
  buffers (:792, :797).
- **L2** `MetalMesh` and `MetalMeshView` override `-release` without `[super release]` (MetalMesh.m:115,
  MetalMeshView.m:245), so the objects leak.
- **L3** `-[MetalShader dealloc]` over-releases dictionary values it does not own: textures, samplers and the generated
  `NSNumber`s (MetalShader.m:299–307).
- **L4** The global ref from MetalShader.m:126 is never deleted. It disappears with JNI, which changes memory only.
- **L5** The `nCreateRT2` wrapper is not retained and never released (§4a).
- **L6** `nInitRTT` over-reads under `-Dprism.forcepow2`: the Java array is content-sized (J/MTLRTTexture.java:65),
  the upload is physical-sized (MetalRTTexture.m:139–143).
- **L7** `readPixelsFromRTT` copies contiguously from a buffer with a physical row stride (:156–160).
- **L8** `length` in `nUpdate*` is computed but unused.
- **L9** The `jlong` vs `void` returns noted in §2.
- **L10** `PFORMAT_BYTE_BGRA_PRE = 8` while the ordinal is 1 (MetalTexture.h), so it lands in `default`.
- **L11** `nBlit` ignores its coordinates (MetalContext.m:845–870).
- **L12** `nSupportsMTL` leaks the device (:1563).
- **L13** `nSetCullingMode` passes unknown values through (:1338–1349).
- **L14** `computeNumLights` dereferences unset light slots (MetalMeshView.m:76+).
- **L15** File-static `isBufferInUse[]` is shared across contexts and races with completion threads
  (MetalRingBuffer.m:31–32).
- **L16** The heap path of `MTLShader.setConstants` uses `off`, the direct path uses `position()`
  (J/MTLShader.java:163–174).
- **L17** `setMap` stores the texture unretained.
- **L18** `dispatch_data_create` receives `DISPATCH_QUEUE_SERIAL` (NULL) as its queue (MetalContext.m:903–905).
- **L19** The 264 generated functions plus `jStringToNSString` are exported. After the migration the 264 are still
  exported, beside the 53 `mtl_*`; `jStringToNSString` went with the JNI.
- **L20** `BYTE_RGB` and `BYTE_GRAY` uploads of a sub-image with a non-zero origin read the wrong bytes and run past
  the array (J/MTLTexture.java:135–190). The Java conversion reads from offset 0 with a packed stride, ignoring
  `srcx`, `srcy` and `srcscan`, and builds an array of exactly `srcw * srch * 4` bytes. It then passes the caller's
  `srcx` and `srcy`, so the C starts reading at `srcy * srcw * 4 + srcx * 4` (C/MetalTexture.m:73) and reads that
  many bytes past the end. Production reaches it through `BaseTexture.update(Image)` with `getMinX`/`getMinY`:
  `CachingCompoundImage` tiles (images over 16384 px on a side) and `NGRegion.paintTiles` sub-images. The JNI read
  the Java heap after the pinned array there, which gave garbage texels but no crash. The FFM facade copies only
  the bytes the C reads and makes the part past the array zeros (`MTLTextureCalls.SourceRows`). **Owner to
  accept** transparent-black texels instead of heap garbage. The golden cannot show this: its RGB and grey steps
  keep `srcx = srcy = 0`.
- **L21** Printing a `Canvas` rendered on Metal, or a WebView page that draws an `RTImage`, crashes the JVM: the
  read-back of the texture into a heap `IntBuffer` copies into `NULL` (R3 has the path). The FFM build passes the
  same `NULL`.

### Recommendation

**Migrate; no FFM-free deletion of the library is possible.** Order:

1. Before any change, on the JNI build: capture the golden (§12) and the uniform-table golden. This is the Definition
   of Ready baseline.
2. Add `prism_mtl_api.h/.m` beside the JNI, with local constants and the test hooks. Add `MTLNative` and
   `MTLNativeTest`.
3. Flip callers in the story's order: `MTLPipeline` and `MTLContext` (34), then `MTLGraphics`, `MTLResourceFactory`,
   `MTLTexture`, `MTLRTTexture`, `MTLShader`. In the same change, bind `objc_release` for the 5 WRAPPERs and drop the
   2 dead natives.
4. Run `mvn -pl modules/javafx.graphics test -Dtest='*MTL*'` after each class, then the full module suite.
5. Delete the `Java_*` bodies, `jStringToNSString`, the `jni.h` and generated-header includes, and the macros. Add the
   `mac.cmake` JNI opt-in.
6. Pixel-parity test against the golden: an **exact** match is expected, since the C, the metallib and the GPU are
   unchanged.

Owner-run: the robot `painttest`/`test3d`/`scenegraph` suites, and anything that needs a window.

## 12. Golden-capture surface

Capture route without a window (unverified): a child JVM with
`-Dglass.platform=Headless -Dprism.order=mtl -Dprism.verbose=true`, rendering through `Scene`/`Node.snapshot`. That
path goes RTT → `MTLRTTexture.getPixels` → `nReadPixels`. HeadlessView returns frame buffer 0
(HeadlessView.java:87–89), and nothing in PrismSettings or Quantum forces `sw` for headless. It must be confirmed (the
verbose output should show MTL). Headless Glass never touches `NSApp`. I did not run it.

| Feature | Natives | Variants to cover | Needs a Glass window? |
|---|---|---|---|
| Device / metallib / teardown | nSupportsMTL, nInitialize, nRelease, nDisposeShader | — | no |
| Clear | nClear | colour (premultiplied), clearDepth on/off, scissor on/off (MetalContext.m:557–565), MSAA on/off (4 states, MetalPipelineManager.m:122–159) | no |
| Quad batches | nDrawIndexedQuads, nSetTransform | more than 4,096 quads (IB loop :437–452), ring-buffer overflow → transient buffer + commit, superShader on/off | no |
| Prism paint shaders (212) | nCreateMetalShader, nEnable/nDisable, setters | {Solid, Texture, AlphaOne, AlphaTexture, AlphaTextureDifference, Mask, Fill/Draw Pgram, Circle, Ellipse, RoundRect, DrawSemiRoundRect} × {Color, ImagePattern, Linear/Radial × PAD/REFLECT/REPEAT} × {plain, _AlphaTest}; TextureRGB, TextureSuper, TextureYV12, TextureFirst/SecondPassLCD | no |
| Decora effects (50) | same | Blend × 19, Brightpass, ColorAdjust, DisplacementMap, InvertMask, PerspectiveTransform, SepiaTone, PhongLighting DISTANT/POINT/SPOT, LinearConvolve(Shadow)_{4,8,12,16,20,24,28,32,64,96,128} | no |
| Uniforms | nSetInt, nSetFloat1–4, nSetConstants (heap), nSetConstantsBuf (direct) | convolve weight arrays | no |
| Sampling | nSetTexture | linear/nearest × wrap {−1, CLAMP_TO_EDGE, REPEAT, CLAMP_TO_ZERO}; up to 4 textures (YV12) | no |
| Texture upload | nCreateTexture, nUpdate / Int / Float, nUpdateYUV422 | INT_ARGB_PRE, BYTE_BGRA_PRE, BYTE_RGB and BYTE_GRAY (Java → BGRA), BYTE_ALPHA → A8, FLOAT_XYZW → RGBA32F, APPLE_422 via `uyvy422_to_rgba`, MULTI_YCbCr_420 (4 × A8); heap vs direct; sub-rects and strides; mipmaps incl. the 1×1 exception (MetalTexture.m:120–123); uploads over 20 MB | no |
| Composite | nSetCompositeMode | CLEAR, SRC, SRC_OVER, DST_OUT, ADD (MetalPipelineManager.m:276–306) × MSAA × depth | no |
| Clip | nSetClipRect, nResetClipRect | negative origin, overflow clamp, full cover → reset, inverted → empty; applied to quads, clear and mesh | no |
| RTT / MSAA / blit | nCreateRT, nInitRTT, nUpdateRenderTarget, nIsCurrentRTT, nBlit | content ≠ physical (forcePow2), 4× MSAA resolve (MetalContext.m:143–151), lazy depth texture, RT switching, whole-texture blit | no |
| Readback | nReadPixels, nReadPixelsFromRTT | int[] with stride; direct `IntBuffer` | no (this is the capture mechanism) |
| 3D | mesh, material, mesh-view natives, nSetCameraPosition, nSetWorldTransform, nSetProjViewMatrix, nRenderMeshView | u16/u32 indices; spec NONE/TEX/CLR/MIX; maps diffuse/specular/bump/self-illum; cull NONE/BACK/FRONT; wireframe; ambient; 0–3 point/directional/spot lights with attenuation, range, falloff; PhongVS0–3/PS0–3; depth LessEqual; MESH_INDEX_LIMIT split | no |
| Swapchain | nCreateRT2 on `MacView._getNativeFrameBuffer`, present → nCommitCurrentCommandBuffer, Glass `blitToScreen` on Prism's queue (nGetCommandQueue), vsync | — | **yes**: a Glass view plus `CAMetalLayer`; capture by screen readback is owner-run. A post-migration-only hook `mtl_test_create_bare_texture` could exercise `mtl_rtt_wrap` headless |

## Files most relevant to the migrator

- `modules/javafx.graphics/src/main/native-prism-mtl/MetalContext.m`
- `modules/javafx.graphics/src/main/native-prism-mtl/MetalShader.m`
- `modules/javafx.graphics/src/main/native-prism-mtl/MetalTexture.m`
- `modules/javafx.graphics/src/main/native-prism-mtl/MetalRTTexture.m`
- `modules/javafx.graphics/src/main/native-prism-mtl/MetalCommon.h`
- `modules/javafx.graphics/src/main/java/com/sun/prism/mtl/MTLContext.java`
- `modules/javafx.graphics/src/main/java/com/sun/prism/mtl/MTLPipeline.java`
- `modules/javafx.graphics/native/mac.cmake`
- Baseline dylib: `modules/javafx.graphics/target/native/bin/libprism_mtl.dylib` of a build of `1a6788cc4d`, the
  "Before" run of [US-001b-macos-gate.md](US-001b-macos-gate.md)
