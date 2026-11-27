# US-026 — Make composited WebView content render on the Java port

**Status:** 🔷 Implemented (2026-11-26, committed as `e5ec21f25b`; the fixes for the code review of that commit are in
the working tree, uncommitted: 13 findings, 9 fixed, 4 refuted, 628 / 0 / 0 / 113 against the rebuilt library, and
the page tests also pass offscreen on Metal and es2, see
[Code review of the commit](#code-review-of-the-commit-2026-11-26); filed 2026-09-30): direction (A), by defining
`TextureMapper` and
`BitmapTexture` for Java on top of `GraphicsContext` and `ImageBuffer`, so composited content draws and no GL code or
`data()` user links; on macOS arm64 the `modules/javafx.web` tests pass against a local `jfxwebkit` with the change
(550 / 0 / 0 / 113: the baseline's 473 plus 77 new tests; of the 68 that existed when the baseline library was last
run, 42 fail on it); every run is offscreen under Headless glass at scale 1, the commit's on software Prism only;
left for the owner: the commit and the PR, the `tests/system` web tests and the graphics robot tests in a window
(never run by an agent, `CSS3DCompositingTest` included), the Linux and Windows builds, the d3d change of the review
fixes (unrun, to be confirmed on Windows), and filing the upstream issue; see
[Implementation](#implementation-2026-11-26-macos) · **Found:** 2026-09-30, fixing the `TextureMapper` crash behind the
`TextureMapperTest` failure (the `m_data(nullptr)` fix, uncommitted at filing)

## Story
As a JavaFX app developer whose WebView pages use view transitions, or who turns on CSS 3D
(`-Dcom.sun.webkit.useCSS3D=true`),
I want composited page content to reach the screen,
so that 3D-transformed, animated or filtered layers draw, and a view transition shows its animation and new content
instead of the frame drawn before it began.

## Problem
Paths are relative to `modules/javafx.web/src/main/native/Source`. Upstream OpenJFX has the same code; it dates from
the WebKit 623.1 update (8368572), which made `TextureMapper` one concrete GL class with non-virtual members.

- **Nothing draws.** The Java port keeps its drawing code in `TextureMapperJava` and wraps it in
  `TextureMapperJavaAdapter`, but every caller goes through `TextureMapper&`: `WebPage::renderCompositedLayers`
  (`WebKitLegacy/java/WebCoreSupport/WebPage.cpp:465-483`), `TextureMapperLayer`, `TextureMapperTile`
  (`WebCore/platform/graphics/texmap/TextureMapperTile.cpp:91-95`) and `TextureMapperSolidColorLayer`. So the base
  functions run, and on Java they are no-ops: `beginPainting` (`texmap/TextureMapper.cpp:257-272`, compiled out, so
  `m_clipStack` is never reset and keeps an empty scissor box), `drawTexture` (`:516-532`), `drawSolidColor`,
  `drawBorder`, and `BitmapTexture::updateContents(GraphicsLayer*)` (`texmap/BitmapTexture.cpp:381-388`). The empty
  clip also culls every child of a `masksToBounds` layer (`texmap/TextureMapperLayer.cpp:682-686`), and all page
  content sits under the compositor's clip layers. `TextureMapperJava`'s real draw methods
  (`texmap/TextureMapperJava.cpp:52-117`) are never called, and `TextureMapperJavaAdapter::javaMapper()` has no caller.
- **No new page content.** In compositing mode `WebPage::paint` returns at once (`WebPage.cpp:293-297`) and
  `postPaint` draws only through `renderCompositedLayers` (`:337-342`), so no page content is drawn. The probe below
  shows what that looks like: a composited element never appears, and during a view transition the frame drawn before
  compositing mode began stays on screen (the render queues `com.sun.webkit.WebPage.updateContent` retains keep being
  replayed). Not probed: that a DOM change made in a transition's update callback stays invisible until the
  transition ends, which follows from the same early return.
- **When.** With default settings during every view transition (`Document::setActiveViewTransition` calls
  `enableCompositingMode()`, `WebCore/dom/Document.cpp:12042`), and all the time with CSS 3D on
  (`com.sun.webkit.useCSS3D`, `modules/javafx.web/src/main/java/com/sun/webkit/WebPage.java:152-157` →
  `WebPage.cpp:1023`).
- **Latent crashes, to guard first.** With `m_data` null on Java (the crash fix), the base functions that still use
  `data()` on Java dereference null: `bindSurface` with a non-null surface and `updateProjectionMatrix`
  (`TextureMapper.cpp:1396-1406`, `:1642-1657`), `depthRange` (`:1637-1640`), `applySinglePassFilter` (`:1300-1320`;
  `TextureMapperGLData::getShaderProgram` has no `return` on Java) and `acquireBufferFromPool` (`:1668-1676`). Only the
  culling above keeps them unreachable today. Repairing the clip alone turns filters, masks, reflections, overlapping
  `opacity < 1` and 3D flattening into deterministic crashes (before the crash fix they were writes through a garbage
  pointer).
- **Smaller.** `TextureMapperJavaAdapter` (`texmap/TextureMapperJavaAdapter.h`) declares no
  `WTF_MAKE_TZONE_ALLOCATED`, so on macOS (TZone on) every allocation takes TZone's size-mismatch slow path; the file
  also has no license header (upstream).

## Probe
2026-09-30, Windows 10, D3D, a local `jfxwebkit` of the current source with the `TextureMapper` crash fix (a temporary
`tests/system` class, not committed). A 400 x 300 WebView shows a white page with a 200 x 200 red box styled
`transform: translateZ(0)`; `WebView.snapshot` is sampled inside the box (100,100) and outside it (300,100) after two
animation frames, once the view transition's `ready` has resolved, and after its `finished`:

| Configuration | Box pixel | Page pixel |
| --- | --- | --- |
| Default (`useCSS3D` off): before, during and after the view transition | red | white |
| `-Dcom.sun.webkit.useCSS3D=true`: all three samples | **white** (box not drawn) | white |

With CSS 3D on, the box gets a compositing layer and never reaches the screen. In the default configuration the view
transition does not blank the page, which corrects an earlier reading of the code: the frame from before the transition
stays visible.

## Proposed fix
The maintainer chooses the direction first:
- **(A) Draw through `TextureMapperJava`.** Java branches in the base `TextureMapper` functions: `beginPainting` resets
  `m_clipStack` to the paint clip; `drawTexture`, `drawSolidColor` and `drawBorder` forward to the `GraphicsContext`
  code `TextureMapperJava` already has; `BitmapTexture::updateContents(GraphicsLayer*)` paints the layer into the
  texture's image buffer. Land Java guards (or real Java implementations with offscreen `BitmapTextureJava` surfaces)
  for the `data()` users above in the same change or before it.
- **(B) Keep the default configuration out of compositing mode.** Do not enter compositing mode for view transitions
  when accelerated compositing is off (or turn the View Transitions API off on Java), and document CSS 3D as
  unsupported until (A). Turning the API off makes `document.startViewTransition` undefined, so the 8386859
  `TextureMapperTest` would have to change with it.

## Acceptance criteria
- With `-Dcom.sun.webkit.useCSS3D=true`, a `translateZ(0)` element renders in a snapshot (only under (A); the probe
  above is the test).
- With default settings, a view transition whose update callback changes the DOM shows the new content in a snapshot
  taken before `finished` resolves (under (A)), or never enters compositing mode (under (B)).
- No `TextureMapper::data()` call is reachable on Java: a system test with CSS filters, a mask, overlapping
  `opacity < 1` and a `preserve-3d` flattening case runs with `useCSS3D=true` without crashing.
- `TextureMapperTest` and the `tests/system` web tests pass.

## Definition of Done
Merged with the `jfxwebkit` it was verified with named in the PR (a local build or `build-webkit.yml`); an upstream
issue drafted (upstream has the same code).

## Implementation (2026-11-26, macOS)
The change is described against `5a709c189d`, the baseline ("before"). Every run was made on one host, macOS 27.2.1
arm64, in the main tree: Oracle JDK 27.0.1.0.2, Maven 3.10.0, AppleClang 21.0.0, CMake 4.4.4. WebKit was built in the
local build tree `modules/javafx.web/build/mac` (the command is under [Libraries](#libraries)). The module tests ran on
Headless glass with software Prism at scale 1. **No GPU pipeline, no HiDPI scale, no Linux or Windows build and no
`tests/system` run is behind anything stated here.** Paths that start with `modules/`, `tests/`, `backlog/` or
`../caches/` are relative to the repository root, other source paths to `modules/javafx.web/src/main/native/Source`,
as in Problem. Times are America/New_York. `<scratch>` is the session's temporary directory, `scratchpad` under
`/private/tmp/claude-501/-Users-benesquivelmusic-Documents-SourceCode-jfx-ffm/b54e2ec6-0b42-419e-99b2-7fd9affc64af`.

The work, all on 2026-11-26: the baseline `jfxwebkit` from the unmodified tree (10:26 to 10:40) and its gate (10:43 to
10:46); a read of the paint path and a design in two versions, each reviewed before any code (the first version was
rejected by four reviews); tests written first and run against the baseline; the native and Java change (first
library 11:40, phase-2 library 12:27, gate 524 / 0 / 0 / 113 at 12:32); an independent review in five lenses (20
findings confirmed) and its rework; a confirming review (7 confirmed) and its rework; the native mutants (13:51 to
15:21); a third review of what had changed since (6 confirmed, none above minor) and its rework; a review of the last
native hunk (nits only); the final gate (15:46 to 15:50).

Kept with the change: [`US-026-macos-per-class.tsv`](US-026-macos-per-class.tsv), the per-class lists of the three
gates. Not kept: libraries, logs, reports, review records and the design. They are in `<scratch>` until it is cleaned
(`us026-design/design-v2.md`, `us026-review/`, `us026-review2/`, `us026-review3/`, `us026-cpp/`, `us026-tests/`).

### Choices
- **Direction (A), but not through Java branches in the GL files.** `texmap/TextureMapper.cpp` and
  `texmap/BitmapTexture.cpp` leave the Java build (`WebCore/PlatformJava.cmake`), and the same two classes are defined
  for Java in `texmap/TextureMapperJava.cpp` and `texmap/BitmapTextureJava.cpp`. Every caller keeps calling
  `TextureMapper&`; no function is virtual; no GL code and no `data()` user is compiled, which settles the latent
  crashes by construction instead of by guards. `TextureMapperJava.h`, `BitmapTextureJava.h` and
  `TextureMapperJavaAdapter.h` are deleted (with them the TZone and licence-header points under "Smaller").
- **A texture is an `ImageBuffer`**, that is a Java `RTImage` with its own render queue, drawn into the target with
  `DECODERQ` + `DRAWIMAGE`. Decoding is deferred to the render thread, so a buffer has no snapshot semantics: `reset`
  makes a fresh buffer every time. The maximum texture size stays 256 x 256, so layers are tiled.
- **Transforms:** a model-view that maps the layer plane affinely is concatenated to the context; a true projection is
  sent as `SET_PERSPECTIVE_TRANSFORM` with the z output zeroed. Only es2, metal and d3d apply a perspective; where the
  graphics cannot (software, Java2D, printing), such a draw is skipped, not drawn unprojected.
- **Masks** are accumulated in a buffer and applied with one full-surface `DestinationIn` blend. **Filters** are not
  composited on Java (`filtersCanBeComposited` returns false), so WebKit paints them in software as before.
- **Printing** gets its own export, `wkj_page_print_composited`, called before `wkj_page_update_content`: a print draws
  the composited layers and never the inspector highlight. `WKJ_ABI_VERSION` stays 1 (an added function is not a shape
  change under the header's rule), so a `jfxwebkit` built before this change fails with
  `UnsatisfiedLinkError: missing native symbol: wkj_page_print_composited`, not with the version message.
- **Fixed in the same change, because a correct composited frame needs them** (each has a test that fails without it):
  the layer sync ran layout without the compositing update; an image whose commands were recorded in a dropped frame
  was drawn stale; form controls were missing from every `ImageBuffer` context; Prism composed the state transform and
  the perspective in the wrong order and kept the perspective after a decode; an image queue destroyed while its only
  decode sat in a dropped frame leaked what it referenced; print decoded image queues on the print thread; the texture
  pool's release timer never fires where nothing runs the WTF main run loop.
- **Fixed although older than the story:** with a node highlighted by the Web Inspector, `InspectorOverlay::paint`
  cleared the whole frame (it expects a layer of its own). The story's print test exposed it. `postPaint` now gives the
  overlay a transparency layer and, outside compositing mode, repaints the page under it. This changes the default
  configuration, but only while a node is highlighted. If the owner wants it out of this PR, it is one block in
  `WebPage::postPaint` and the screen samples of two `CompositedPrintTest` cases.
- **Removed again as unproven:** a repaint request when the root layer detaches (two tests written to need it pass
  without it, and three readers traced it as redundant), and a walk that invalidated every layer on each resize (it
  served the test harness; the harness was changed instead).

### Change
`git diff --stat` outside `backlog/`: 27 tracked files, 933 insertions, 330 deletions (18 native, 640 / 290; 5 Java,
177 / 16; 2 docs; 2 tests), and 16 new files (15 tests and helpers, 1 shim).
- **Native, new behaviour:** `texmap/TextureMapperJava.cpp` (the mapper: targets and per-target clip stacks, scissor,
  rounded, projected and polygon clips, `drawTexture`, `drawSolidColor`, mask mode, a cull of draws outside the clip
  bounds, the pool release at `endPainting`); `texmap/BitmapTextureJava.cpp` (`reset`, `updateContents` for a layer and
  for an image); `texmap/TextureMapper.h`, `BitmapTexture.h` (the Java members, fenced); `BitmapTexturePool.{h,cpp}`
  (`releaseUnreferencedTextures`, fenced); `ClipPath.h` (`vertices()`); `GraphicsLayerTextureMapper.cpp`
  (`filtersCanBeComposited`); `platform/graphics/java/GraphicsContextJava.cpp` (`flushImageRQ` always emits
  `DECODERQ` for an image drawn into another context); `platform/java/RenderThemeJava.cpp` (the page's theme when the
  context has none and the page has a Java `WebPage`; the upcall failure cleared right after `create_widget`);
  `WebKitLegacy/java/WebCoreSupport/WebPage.{h,cpp}` (`syncAndRenderCompositedLayers`, `printCompositedLayers`,
  `LayoutOptions::UpdateCompositingLayers` in `syncLayers`, the page rect and clip in `renderCompositedLayers`, the
  highlight block); `WebKitLegacy/java/api/webkit_java_api_page.h` (the export). `texmap/TextureMapper.cpp` loses the
  fork's `m_data(nullptr)` hunk and equals upstream again; it is no longer compiled.
- **Java:** `WCGraphicsPrismContext` (folds the state transform into a non-identity perspective for graphics that apply
  one, skips projected draws elsewhere, resets the perspective at flush and dispose); `WCRenderQueue` and
  `WCRenderQueueImpl` (decode of an image queue hops to the render thread, returns at once for an empty queue, flushes
  in `finally`; buffers of a disposed, unreferenced queue are released); `WebPage.print` and `WebPageNative` (the new
  downcall, the disposed check). `WCGraphicsPrismContext.java` (1,948 lines), `WebPage.java` (2,977) and
  `WebPageNative.java` (2,327) were over 900 lines before the story.
- **Docs:** `modules/javafx.web/FFM-STATUS.md` and `FFM-ABI-CONTRACT.md` (1,964 bound names; the cached library and
  earlier `build-webkit.yml` zips are stale).

### Libraries
Built with `perl Tools/Scripts/build-webkit --java --icu-unicode --64-bit --no-experimental-features` (Release, Unix
Makefiles, `-j 10`, `-DENABLE_TOOLS=0 -DWEBVIEW_BROWSER_VERSION=18.4 -DJAVAFX_RELEASE_VERSION=28
-DCMAKE_OSX_DEPLOYMENT_TARGET=11.0`, `WEBKIT_OUTPUTDIR=modules/javafx.web/build/mac`), then incrementally with
`cmake --build modules/javafx.web/build/mac --config Release -- -j10`. All are Mach-O arm64 `libjfxwebkit.dylib`.

| Library | sha256 | Where |
| --- | --- | --- |
| Baseline, unmodified `5a709c189d` | `40b5c6db8f965215462b3f12452e78cacc6f89eb5a645a9f5680dce5de0f581b` | `modules/javafx.web/build/us026-baseline/lib` |
| Before the review fixes | `00fdb8460a552a802f3c455b0411338aa3c1d7b2ddd087692240606eb776e7c5` | `modules/javafx.web/build/us026-phase2/lib` |
| Before the last native fix | `bcc31b05d8b52a707d638eb986054bf1676f55142de6fce0d4ecbb32e8042669` | `modules/javafx.web/build/us026-final-bcc31b05/lib` |
| **Final** (built 15:39) | `37f184fa42328b1ff6cd5f62c68eaf76bb4151a9eb323efa2dca5e626fc5c618` | `modules/javafx.web/build/us026-final-37f184fa/lib`, and `build/mac/lib` until the next build there |

`modules/javafx.web/build/us026-final/lib` holds an intermediate library (`4eae7567…`), not the final one. All five
directories are ignored by git and can be deleted once the PR names a library.

Final library: `nm -a` finds 0 `TextureMapperGLData` symbols (baseline 28); `compile_commands.json` has no entry for
`texmap/TextureMapper.cpp` or `texmap/BitmapTexture.cpp`; 1,966 exported `wkj_*` names, the baseline's 1,965 plus
`wkj_page_print_composited`. The baseline's export set equals the cached release library's. Whether the build is
reproducible bit for bit was not checked.

### Corrections to the story as filed
- **The default-settings symptom does not exist.** With default settings a view transition enters and leaves
  compositing mode inside the first rendering update, and the page is drawn. `ViewTransitionContentTest`'s three
  default-settings cases pass on the baseline library; acceptance criterion 2 already held. What failed on the
  baseline is every page that stays in compositing mode (accelerated compositing on).
- **"Not probed"** (a DOM change in the update callback staying invisible) does not happen with default settings, for
  the same reason.
- **The latent crashes** are removed by not compiling the GL files, not by guards.
- The fork's `m_data(nullptr)` crash fix is no longer needed and is gone with the GL file.

### Acceptance criteria and evidence
| Criterion | Evidence | Open |
| --- | --- | --- |
| 1. With CSS 3D a `translateZ(0)` element renders | `CompositedContentTest.translateZBoxDrawsLikeTheReference` and 23 more comparisons of a composited page with the same page drawn without compositing: the 23 that existed when the baseline was last run all fail on it, all 24 pass after. Compositing is forced per page with `WebKitAcceleratedCompositingEnabled`, on software Prism | The story's probe on a real pipeline with `-Dcom.sun.webkit.useCSS3D=true`: `CSS3DCompositingTest.aTranslateZBoxIsDrawnBeforeDuringAndAfterAViewTransition`, compiled, **never run** |
| 2. A default view transition shows the new content before `finished` | `ViewTransitionContentTest` (4 cases; the 3 default-settings ones pass before and after, the composited one fails before) | `TextureMapperTest.testViewTransitionShowsTheNewContentBeforeItFinishes`, compiled, **never run** |
| 3. No `data()` reachable; a system test with filters, a mask, overlapping opacity and `preserve-3d` runs with CSS 3D | The symbol and compile evidence under [Libraries](#libraries); the same content as module tests on software Prism (`CompositedContentTest`, `CompositedContentChangesTest`, `CompositedPixelsTest`) | `CSS3DCompositingTest` (11 cases), compiled, **never run** |
| 4. `TextureMapperTest` and the `tests/system` web tests pass | `tests/system` compiles (`test-compile`) | The run is the owner's |

### Gates
`mvn -B -ntp -o -pl modules/javafx.web -am test -DskipNative=true -Djfx.web.skipTests=false
'-Dtest=!UserDataDirectoryTest' -Dsurefire.failIfNoSpecifiedTests=false '-Djfx.native.librarypath=<library
directory>:<media bin>:<graphics bin>'`. `UserDataDirectoryTest` is excluded on macOS (US-131).

| Gate | javafx-web default-test | Classes |
| --- | --- | --- |
| Head: `5a709c189d`, baseline library (10:43 to 10:46) | 473 / 0 / 0 / 113 | 50 |
| Before: baseline library, the story's Java change and tests as of 13:35 (9 tests fewer than final) | 541 / 42 / 0 / 113 | 60 |
| After: final tree, final library (15:46 to 15:50) | **550 / 0 / 0 / 113** | 60 |

No class present at head changes its counts; the 42 failures before are all in 7 classes the story adds
([per class](US-026-macos-per-class.tsv)). The final Java code cannot load the baseline library (the new export), so
"before" is not the final test set. The other modules in the final run: jslc 437 / 0 / 0 / 0, base 5,510 / 0 / 0 / 22,
graphics 26,889 / 0 / 0 / 859, controls 9,445 / 0 / 0 / 183, media 41 / 0 / 0 / 6. The 77 new tests passed in two more
runs on the final library. The FFM binding tests (194 / 0 / 0) were last run at 13:47, before the later Javadoc-only
change to `WebKitNativeSymbolTest`. `java buildtools/CheckNoJni.java .`: no JNI found. `tests/system`:
`test-compile` passes. No `hs_err_pid*` from any gate.

### The tests
Module tests, 77 in 10 classes under `modules/javafx.web/src/test/java/test`, with helpers `PumpedPage` (drives update,
scene sync and paint as a pulse does, and tells whether the page is in compositing mode), `PixelAssertions`,
`SettledRegistry`, `CaseAnnouncer` and the shim `WCRenderQueueShim`:
- `CompositedContentTest` (24): each page drawn with compositing on must equal the same page drawn without.
- `CompositedContentChangesTest` (11): recolouring, resize, scroll, animation, partial tile updates, leaving
  compositing mode.
- `CompositedPixelsTest` (5): pixels computed from CSS where the reference cannot serve (3D order, intersecting planes).
- `CompositedPrintTest` (8), `DroppedFramesTest` (4), `ThemedControlsInImageBuffersTest` (3),
  `ViewTransitionContentTest` (4), `SurfacePoolTest` (1).
- `WCGraphicsPrismContextPerspectiveTest` (13) and `WCRenderQueueReleaseTest` (4): unit tests of the Java changes.

System tests (owner only): `CSS3DCompositingTest` (new, 11) and one case added to `TextureMapperTest`.

### Mutants
- **Native:** 36 one-hunk mutants of the code as it stood after the first rework (28 caught, 8 survived), then 7 runs
  after tests were added: 4 of the survivors and 2 new mutants caught (one by a JVM crash in the named test), one
  survivor again not caught and its hunk, the detach repaint, removed. Of 38 mutants 34 are caught. **Three
  survive:** the page clip in `renderCompositedLayers` (older than the story; affects only culling), a truncating
  instead of enclosing cull rectangle (less than a pixel at a seam, inside the tests' edge tolerance), and the
  clip-stack reset in `reset` (equal in practice, clips are balanced). No mutant can be shown on software Prism for:
  the z zeroing and the projection itself, polygon flattening of rounded clips under a projection, `ImageBuffer`
  creation failures. The last native hunk (the page repaint under the highlight) has no mutant run of its own: its
  test fails on the library built without it (`ff7f8000` becomes `ff1fdf00`) and passes on the final one.
- **Java:** every production change was removed alone and failed a named test, except a dead operand that was simply
  deleted, and the disposed-page check in `print`, whose mutant would crash the JVM and **was not run**.
- Not re-run after the last test tightening: the animation-repaint mutant against
  `aRunningCompositedAnimationKeepsDrawingFrames` (it failed the earlier version).

### Independent review, rework and confirming reviews
| Round | Confirmed | Worst | Outcome |
| --- | --- | --- | --- |
| 1: five lenses, each finding checked by a second reader | 20 of 23 (two are one defect) | Blocker: the texture pool never frees where the WTF run loop is not run | All fixed |
| 2: four lenses on the rework | 7 of 8 (two are one defect) | Major: the polygon clip of intersecting 3D planes was pinned by no test | Fixed, but one nit declined, and the reviewer of round 3 agreed (the second upcall-failure check in `paintWidget` is live) |
| 3: two lenses on what changed after round 2 | 6 of 6 | Minor: the highlight built up over the retained back buffer | All fixed |
| Last native hunk | 3 nits | Nit | None taken: no debug LED on the highlight repaint (debug borders cannot be turned on in this port) |

Review findings that were refuted are in `<scratch>/us026-review*/findings-all.md` with the reasons.

### Code review of the commit (2026-11-26)
A review of `e5ec21f25b` reported 13 findings, none of them run. Each was first checked by a reader who traced the
paths and tried to refute it; then the native and Java fixes were made, reviewed again by readers who had not written
them, and reworked until a review found nothing in the code. Everything below is in the working tree on top of
`e5ec21f25b`, uncommitted, on the same host as the story (macOS arm64, Apple M6). The gate runs as in the story
(Headless glass, software Prism, scale 1). The same page tests were then run with Metal and with es2 (Apple's OpenGL
2.1 on Metal) as the Prism pipeline, still under Headless glass, so without a window; and a suite of its own drives
the Prism glue on both pipelines in a child JVM. Every verdict with its evidence is in
`review-fix-decisions.md`, in `scratchpad` under
`/private/tmp/claude-501/-Users-benesquivelmusic-Documents-SourceCode-jfx-ffm/705399a4-bc70-4899-92ab-644997a6f70e`
(a temporary directory).

| # | Finding | Verdict | Outcome |
| --- | --- | --- | --- |
| 1 | `WCBufferedContext` culls a draw without the perspective | Refuted: every decode calls `isValid()` first, which sets `baseGraphics`, so the cull never runs while a queue is decoded | No change |
| 2 | `RTImage.draw` reads a print back into a heap buffer of the logical source size | Confirmed, worse than reported: Metal copies into `NULL` (a JVM crash at any scale), es2 and d3d print blank tiles at scale 2 or more, software Prism garbles them, and a source rectangle that is not the whole image is wrong at every scale | Fixed in `RTImage.draw`: the path is chosen on the render thread, the whole texture is read into a direct buffer of its content size and drawn with the screen path's source mapping. The run on real Metal then showed the print still blank (Metal's `readPixels` does not flush the draws Prism holds back), so the read-back flushes first |
| 3 | `m14`/`m24` tested for exact zero | Partly: `rotateY(180deg)` is snapped by WebKit; a whole turn (360°, 720°, `1turn`) under a perspective leaves about 1e-19 and the layer was dropped on software Prism and in a print | Fixed in `TextureMapperJava.cpp`: a matrix whose w varies by at most 1e-9 over the drawn rectangle is plane-affine |
| 4 | A print blocks on the render thread without a timeout | Refuted: after shutdown the submit is rejected and caught, so nothing hangs; `RTImage.draw` and `NGCanvas` already hop the same way; a timeout would only give wrong output | No change |
| 5 | The pool's release timer destroys textures under `m_lock` | Partly: no deadlock (only the FX thread takes the lock), but the rule was broken, also on the reuse path (`reset` under the lock), which the finding missed | Fixed: the timer is not armed on Java, and the reused texture's buffer is taken out and dropped after the unlock |
| 6 | `BufferImage` keeps the old `DECODERQ` rule | Not reachable (nothing has constructed a `BufferImage` since the 615.1 update), but a dead second copy of the rule | `BufferImageJava.{h,cpp}` and `Image::drawImage` deleted: `flushImageRQ` is the only writer of `DECODERQ` |
| 7 | The pool reuses nothing | Confirmed; the proposed native reuse is unsound (a buffer is decoded later, on another thread, and the pool is process-wide) | Fixed in Java: `RTTextureCache` reuses the render texture of a disposed `RTImage` (exact size, cleared after the previous owner's pending draws, at most 32 textures and 32 MiB, none over 1024 px a side, released about 2 s after the last use) |
| 8 | The whole page is repainted under the inspector highlight | Partly: the repaint is needed (the overlay would build up; a transparent fill clears the view), and the overlay exposes no bounds | Fixed where it was a duplicate: `postPaint` skips it when the update's own paints covered the view and the background is opaque |
| 9 | A theme upcall and a new reference per form control in an `ImageBuffer` context | Confirmed (about 12 upcalls and a registry id per control per repaint) | Fixed: one cache in `PageSupplementJava`, used by `WebPage::jRenderTheme()` too |
| 10 | The perspective order should be fixed in Prism | Refuted as a remedy: about 20 vertex paths pre-transform on the CPU, 2D-affine ones among them, in code every JavaFX application draws with; folding the transform makes all of them no-ops, and on Metal and es2 a projected fill and image land where the folded matrix puts them | No Prism change. Two real defects on the topic are fixed in `WCGraphicsPrismContext`: a nested `DECODERQ` reset the outer context's folded perspective (found by this check), and left its own decode drawing through the outer's (found by the confirming review) |
| 11 | `WebPage` should decode what a dropped frame carried | Refuted: Java cannot find the targets without a second parser or a new export, drops happen on the FX thread, and an invisible view drops on every pulse; an extra `DECODERQ` is 8 bytes and a map lookup | No change |
| 12 | `WebPageNative.printComposited` grows a facade over the limit | Confirmed (2,301 to 2,327 lines) | Fixed: one helper for the three encode calls; the file is 2,300 lines |
| 13 | A dead exception check in `paintWidget` | Partly: live in principle, redundant in effect (`RQRef` clears the flag before anything reads it) | Deleted |

Also fixed, found by the confirming reviews: the clear of a transparent page is now decided when a frame is recorded,
not when it is decoded (`RenderFrame`; it must agree with the native skip of finding 8, and it closes an older race
of the same kind); and the texture cache of finding 7 releases idle textures on a timer, not only when it is next
used.

Also fixed, found only by running on a GPU pipeline:
- **A print of an `RTImage` on Metal was blank** (finding 2's fix read the texture before Prism had flushed its last
  draws). `RTImage.readContent` now flushes through the texture's graphics first.
- **A reused texture showed the previous image** (3,071 of 3,072 pixels, on Metal and es2): the clear ran at once,
  ahead of draws still held for the same, still current render target. `RTTextureCache` now clears through
  `RTImage.clearWholeContent`, which sets the whole-content clip first; Prism flushes before a clear only when the
  clip changed. A `sync()` before the clear is also correct but costs a command-buffer commit on Metal and a
  `glFinish` on es2: measured on this Mac, a frame of 8 reused tiles took 383 to 388 µs on Metal against 208 to 323
  for fresh allocation, and about 1,300 µs against about 660 on es2, while the clip form costs what an unflushed
  clear costs (198 to 205 µs and 486 to 529 µs).
- **`RTImage.drawPixelBuffer` had the same overtaking** (older than the story, reproduced on both pipelines) and uses
  the same helper.
- **On es2 a rotated layer showed a fringe from its opposite edge** (106 pixels in
  `CompositedContentTest.rotatedMaskOverCompositedContentDrawsLikeTheReference`; Metal and software pass). Prism's
  es2 pipeline sets no wrap mode for a texture asked for as `CLAMP_NOT_NEEDED`, so OpenGL's default, repeat, stays,
  and a rotated, filtered draw reads half a texel past the edge. javafx.web cannot ask for anything better
  (`ES2RTTexture.create` refuses `CLAMP_TO_EDGE`; `CLAMP_TO_ZERO` softens every layer edge and opens seams between
  tiles on every GPU pipeline: measured). So the review fixes change two files in `javafx.graphics`.
  `ES2RTTexture.create` sets clamp-to-edge once on a `CLAMP_NOT_NEEDED` render texture, which is what Metal's
  default sampler does. It reaches every such es2 render texture of any application, only where a filtered sample
  reads past the texture's edge. d3d had the same shape by reading: for a `CLAMP_NOT_NEEDED` texture
  `D3DContext.updateTexture` passed no address mode, so the sampler kept the one the previous texture on it left
  (wrap after a 3D draw, border after a `CLAMP_TO_ZERO` texture). `D3DContext.updateTexture` now gives a
  `CLAMP_NOT_NEEDED` render texture the clamp address mode instead. It mirrors the es2 change and reaches every such
  d3d render texture of any application, the swap chain's back buffer among them; a draw at 1:1 samples texel
  centres and is unchanged. It compiles here and has **not been run on d3d by anyone**.
The missing flush in Prism's `MTLRTTexture.readPixels(Buffer)` itself is not changed; it is recorded with L21 in
[`US-001b-prism-mtl-audit.md`](US-001b-prism-mtl-audit.md).

**The page tests on a GPU pipeline.** A Maven user property overrides the module's `prism.order=sw`, and glass stays
Headless:
```bash
mvn -B -ntp -o -pl modules/javafx.web -am test -DskipNative=true -Djfx.web.skipTests=false \
  '-Dtest=test/javafx/scene/web/**,test/com/sun/javafx/webkit/**,test/com/sun/webkit/**,!UserDataDirectoryTest,!FormControlsTest' \
  -Dsurefire.failIfNoSpecifiedTests=false -Dprism.order=mtl,sw -Dprism.verbose=true "-Djfx.native.librarypath=$L"
```
and the same with `-Dprism.order=es2,sw` (`$L` as under [Left for the owner](#left-for-the-owner); the package
patterns keep the other modules' tests on their own pipeline; the first test class's output file names the pipeline
that was initialised). Result on the final tree: **610 / 0 / 0 / 114 on Metal and on es2**, the composited-page,
dropped-frame, highlight, theme, print and reuse tests among them. `FormControlsTest` (older than the story) is left
out: it prints into a GPU texture on the FX thread, which software Prism tolerates, Metal refuses and es2 crashes
on; production prints only into printer graphics. Before this round the story's own print tests did the same (8
failures on Metal, a crash on es2) and one unit test assumed software layers; both are corrected in the tests, and
what they found in the product is the es2 fringe above.

**Library.** `modules/javafx.web/build/us026-review-fix/lib/libjfxwebkit.dylib`, sha256
`e45f418d1ad22a118091a0c562aa46a08a7bc7feff6e4e02918e9ed448c08d4e`, built twice from the final source with the same
hash (and in `build/mac/lib` until the next build there). `nm`: 0 `BufferImage` symbols; the 1,966 `wkj_*` names are
those of `37f184fa…`, so no export changed. `java buildtools/CheckNoJni.java .`: no JNI found. Checked a second time
by an agent that had not built it: the incremental build of the final source compiles nothing and leaves the hash
unchanged, no list, unified bundle or copied header still names `BufferImageJava`, and the complete exported name
lists of the two libraries are equal (2,094 names).

**Gate,** the story's command with that library, on the final tree: `javafx.web` **628 / 0 / 0 / 113** (the 550 of
the commit plus 78 new tests); jslc 437 / 0 / 0 / 0, base 5,510 / 0 / 0 / 22, graphics 26,889 / 0 / 0 / 859, controls
9,445 / 0 / 0 / 183, media 41 / 0 / 0 / 6. The FFM binding tests on the final tree: 194 / 0 / 0
(`WebKitNativeSymbolTest` still pins 1,964 bound names). macOS compiles the d3d classes but never loads them, so none
of these runs says anything about d3d.
`tests/system` compiles (`test-compile`). No new `hs_err_pid*`. The totals are those of runs made from the main
session after the last change, not only of the runs of the agents that wrote the code.

**Tests, 78 new.** Each was seen to fail without the code it pins, on the commit's library (`37f184fa…`), on a
one-hunk native mutant library, or with the Java change removed. The exceptions are guards that pass before and
after: the 180° control, hiding the highlight, the registry after a themed page closes, the two canvases that draw
each other, a purge after the factory's release, and the two prints through a clip on a printer graphics (the
predicted `ClassCastException` does not happen there: a print context's clip layers are printer graphics).
- GPU pipelines: `WebPrismGpuOffscreenTest` (new, 17 scenarios on Metal and the same 17 on es2, with
  `WebPrismGpuOffscreenChild` and `GpuOffscreenGate`). The test starts a child JVM whose default Prism pipeline is the
  GPU one, under Headless glass, and drives the production classes there: the read-back and the print of an
  `RTImage` at scale 1 and 2, a projected fill and image through `WCGraphicsPrismContext` (also inside a clip layer
  and a transparency layer, and under a 2× scale), a nested decode in both directions, the identity left in the
  device, the clear of a reused texture, reuse through `RTImage` after a pool sweep, cache disposal, a pixel buffer
  drawn over a pending fill, and a rotated image's edges. It skips where the pipeline is missing (other platforms,
  no device) and fails instead with `-Djfx.parity.require=true`; CI does not run this module's tests.
  With the fixed code removed, on both pipelines unless noted: the fold (the projected fill and image, the draw
  inside the layers, and both nested decodes), the re-apply in `getGraphics` (both nested ones), the reset in
  `flush()` (the nested decode at the start of a buffer), `resetPerspective()` in `flush()` and in `dispose()` (the
  identity left after a decode, and after a dispose), the flush before the read-back (the four read-back scenarios,
  on Metal only: es2's own read flushes), the clip before the reuse clear, the clear itself, `makePermanent()` in
  `RTImage.getTexture`, the dispose in the cache's dispose-all, the clip in `drawPixelBuffer`, and the es2 clamp
  (es2 only) each fail their scenario. Not separated by any test: the fold under a scale (Prism then hands the whole
  transform to the shader, which composes it with the perspective as the fold does; only a translation it adds to
  the vertices differs), and `contentsNotUseful()` in `RTImage.dispose` (it only keeps a count that Prism reads for
  textures that are not permanent).
- `CompositedPrintTest` now prints into printer graphics, as a print job does, not into a render texture; the
  mutant that decodes a tile queue on the print thread still fails it.
- Native fixes: whole turns under a perspective (`CompositedPixelsTest` 4, one of them the 180° control;
  `CompositedPrintTest` 1); two surfaces of one size in one paint (`SurfacePoolTest` 1, fails the mutant that keeps
  the buffer); dropped frames with default settings (`DroppedFramesTest` 3: a canvas, a canvas drawn into a canvas,
  both fail the mutant with the old `DECODERQ` rule, and two canvases that draw each other in one frame, a guard);
  the highlight (`InspectorHighlightRepaintTest`, new, 4: the page is painted once, a transparent page keeps its
  content, hiding restores the page, a frame recorded over an opaque fill is not cleared); the theme
  (`ThemedControlsInImageBuffersTest` 2: registry ids per control fall from 2 to 1, nothing is left after the page
  closes).
- Java fixes: `RTImagePrintTest` (new, 5), `RTTextureCacheTest` (new, 18), `CompositedTextureReuseTest` (new, 2: 30
  animation frames must create at most 4 textures and reuse at least 27; measured 0 and 30),
  `WCGraphicsPrismContextPerspectiveTest` (+3),
  `CompositedPrintTest` (+1, a rounded clip printed on a printer graphics).
- Mutant libraries, in `modules/javafx.web/build/us026-review-mutant-{flush,keepbuffer,opaque,covered}/lib`
  (ignored by git).
- Dropped: a gradient-tile case for finding 6, which cannot fail (the pattern path decodes the image's own queue in
  Java).

**Sizes.** The three files that were over 900 lines before the story are all smaller than before it:
`WebPageNative.java` 2,300 (2,301 before the story, 2,327 in the commit), `WCGraphicsPrismContext.java` 1,772 (1,882;
1,948) and `WebPage.java` 2,918 (2,967; 2,977). For the last two, one nested class each moved to its own
package-private file, unchanged but for the access of the members the outer class calls: `ContextState` to
`PrismContextState.java` (217 lines) and `RenderFrame` to `RenderFrame.java` (88). The other production files the
review fixes touch or add: `RTImage.java` 344, `RTTextureCache.java` 226, `WCImage.java` 94, and in
`javafx.graphics` `ES2RTTexture.java` 408 and `D3DContext.java` 680.

**Accepted, not changed.** Scrollbars styled translucent by the application are drawn once instead of twice on a
highlighted update that repaints the whole view (now as on any full repaint). On software Prism and es2 a texture
cached within 2 s of the toolkit's exit is not released before the process ends. The print path still waits on the
FX thread without a timeout after `Platform.exit` (older than the story).

**Not verified by any run:** a window, swap chain and present (every run here is offscreen); d3d (the `D3DContext`
change is compiled only), and es2 on Linux (the es2 here is OpenGL on Metal; the `ES2RTTexture` change has met no
GLX, EGL or GLES without non-power-of-two textures); a screen scale other than 1 through a page
(`HeadlessApplication` fixes it at 1 and reads no property; only hand-built images of scale 2 are run); a real print
job (this Mac has no print destination, so `PrinterJob.createPrinterJob()` answers null; the tests print into
`PrismPrintGraphics` as a job does); device loss; GPU memory over a live page; any GPU but this Apple M6; Linux and
Windows builds (the `SourcesJava.txt` change moves the unified-source bundles from `BitmapImageJava.cpp` on;
`std::abs`/`std::max` in `TextureMapperJava.cpp` and the include fence in `BitmapTexturePool.cpp` have only met
clang; read for both compilers, no error was found; on Windows `PageSupplementJava::jRenderTheme` becomes one more
C++ export of the DLL, as its `WEBCORE_EXPORT` siblings already are). No agent ran Linux or Windows: this Mac has no
container or virtual-machine tooling (none of docker, podman, colima, lima, multipass, qemu, UTM, Parallels, VMware
or VirtualBox is installed) and agents may not install any; and the review fixes are uncommitted, so no agent can
start a CI run of them (only the owner commits and pushes).

### Known limits
- A true projection (`rotateX`/`rotateY` under `perspective`) is drawn only by es2, metal and d3d. On software Prism,
  Java2D and in a print it is **not drawn at all**.
- No depth buffer: 3D order comes from WebKit's plane splitting. No edge antialiasing of rotated layers; seams between
  256 px tiles are possible under non-integer transforms.
- Per intermediate surface, flattened tile and mask, per frame: a fresh `ImageBuffer`, render queue, 2 registry ids
  and about 15 upcalls. Only the render texture is reused, when a size repeats (`RTTextureCache`). A page whose
  translucent or masked content spans many tiles records many draws (measured on the test pages: 12 to 10 draws with
  the cull; the larger saving is a calculation only).
- Text smoothing in transparent layer tiles can differ from the page; filters do not reach composited descendants; a
  lost graphics device leaves layer tiles blank until they repaint.
- On d3d the edges of a rotated or scaled layer rest on a change nobody has run: `D3DContext.updateTexture` now
  clamps a render texture asked for as `CLAMP_NOT_NEEDED`, which before kept the sampler address mode of whatever was
  drawn before it (`prism_d3d_api.cpp`), as es2 kept repeat. Read and compiled, not run; kept by the owner's decision
  of 2026-11-27, to be verified by the owner on Windows. If the change is wrong, the fringe from the opposite edge
  that es2 showed, or another edge artefact, is possible on d3d.
- While a node is highlighted outside compositing mode, an update that dirties only part of the view still repaints
  the whole view (an update that repaints all of it paints it once).

### Deviations
- **ICU data:** the archive CI downloads is not on this Mac, so the data was read out of the cached release library
  (`../caches/sdk/lib/libjfxwebkit.dylib`, symbol `_icudt77_dat`, 31,895,376 bytes, sha256 `310d9b2c…`) and zipped
  into the build tree. Not checked against the official archive. Also: Makefiles instead of Ninja, no ccache.
- The FFM binding tests were run once without `-DskipNative=true` (the recording stub had to be rebuilt), which also
  ran the graphics and media CMake builds incrementally.
- Reviewers read snapshots of the changed files while mutants were being built in the tree.
- `modules/javafx.web/hs_err_pid75448.log` is left from the deliberate mutant crash (ignored by git).
- **For the owner to confirm, from the review fixes:** two classes older than the story were moved out of files that
  were over 900 lines, `ContextState` to `PrismContextState.java` and `RenderFrame` to `RenderFrame.java`. The rule
  for such files is not to refactor them unless asked; the moves are what brings both files back under their size
  before the story, and each is a pure move (diffed: only the access of the members the outer class calls and
  qualified names change). The two new files keep 2011 as their first copyright year, the year of the code, not
  2026. And the review fixes change two files outside `javafx.web`, `ES2RTTexture.java` and `D3DContext.java` in
  `javafx.graphics`; the owner decided on 2026-11-27 to keep the second in this PR and verify it on Windows.

### Seen on the way, not changed
- `Page::didUpdateRendering` has no caller in this tree, so a same-document view transition never sets "rendering
  suppressed during the update callback" (`WebCore/dom/Document.cpp:12064-12081`). Older than the story; no test
  depends on it.
- The Java object registry keeps 2 ids per drawn `<canvas>` after its page is destroyed (probe logs
  `<scratch>/us026-tests/p2-probe-leak*.log`). Older than the story, unrelated to compositing, **not filed**.
- `HeadlessApplication` hard-codes scale 1, so no module test can cover HiDPI.

### Upstream check
Read on 2026-11-26 from the raw files of openjdk/jfx master (the commit of master was not recorded; the newest
integration seen is JDK-8391823, `e043975ed7`, 2026-09-07). Master has the defect: `TextureMapper`'s members are not
virtual; on Java `beginPainting`, `drawTexture`, `drawSolidColor` and `drawBorder` are compiled out and
`BitmapTexture::updateContents(GraphicsLayer*)` is an explicit no-op; `WebPage::paint` returns at once in compositing
mode and `renderCompositedLayers` calls the mapper through `TextureMapper*`; `TextureMapperJavaAdapter::javaMapper()`
is still there, unused by that path. Also on master as this story found them: `syncLayers` without
`LayoutOptions::UpdateCompositingLayers`, `flushImageRQ` emitting `DECODERQ` only for a non-empty queue,
`paintWidget` returning when the context has no theme, `drawHighlight` straight into the page's context with
`InspectorOverlay::paint` clearing the viewport, and the pool's `RunLoop::Timer`. Different from the story's Problem
text: master now guards `bindSurface`, `depthRange` and `acquireBufferFromPool` on Java and sets `m_data` to null in
the header (JDK-8386859, JDK-8391823); `applySinglePassFilter` still uses `data()` unguarded. The Java-side items
(Prism perspective, print, the image-queue leak) were not read on master. Pull requests of openjdk/jfx: only those two
crash fixes mention `TextureMapper`, none mentions `useCSS3D`; JBS itself refused automated access and was **not**
searched. The draft is kept outside the repository at
`<scratch>/us026-upstream/UPSTREAM-US026-jbs-webkit-java-composited-content-not-drawn.md`, a temporary directory.

### Left for the owner
1. **The commit** of the review fixes on top of `e5ec21f25b` (`git status`: 34 changed files, two of them the deleted
   `BufferImageJava.{h,cpp}` and three under `backlog/`, and 11 new files); and the PR, which names the `jfxwebkit` it
   was verified with (the `us026-review-fix` library above, or a `build-webkit.yml` run) and says what was run: the
   page tests on software Prism, on Metal and on es2, offscreen, all on one Mac; and that the review fixes change
   two files of `javafx.graphics` (`ES2RTTexture.java`, `D3DContext.java`), which the robot runs of item 4 have to
   cover, the second never run by anyone.
2. **The `tests/system` web tests on a real pipeline** (owner only: they take over the screen; no agent ran them, so
   there is no expected result). The session could post input events and capture the screen and offered to run
   them; the owner decided on 2026-11-27 to run them personally. Check the library first, then run the non-robot and
   the robot web tests:
   ```bash
   cd /Users/benesquivelmusic/Documents/SourceCode/jfx-ffm
   shasum -a 256 modules/javafx.web/build/us026-review-fix/lib/libjfxwebkit.dylib
   L="$PWD/modules/javafx.web/build/us026-review-fix/lib:$PWD/modules/javafx.media/target/native/bin:$PWD/modules/javafx.graphics/target/native/bin"
   mvn -pl tests/system -am test -DFULL_TEST=true -Djfx.web.skipTests=false -Djfx.parity.require=true -Dsurefire.failIfNoSpecifiedTests=false '-Dtest=test/javafx/scene/web/**,!UserDataDirectoryTest' "-Djfx.native.librarypath=$L"
   mvn -pl tests/system -am test -DFULL_TEST=true -DUSE_ROBOT=true -Djfx.web.skipTests=false -Dsurefire.failIfNoSpecifiedTests=false '-Dtest=test/robot/javafx/web/**' "-Djfx.native.librarypath=$L"
   ```
   The hash must be `e45f418d1ad22a118091a0c562aa46a08a7bc7feff6e4e02918e9ed448c08d4e`. The first `mvn` command also
   runs the module's `test.javafx.scene.web` classes. `-Djfx.parity.require=true` makes `CSS3DCompositingTest` fail
   instead of skip where the pipeline has no 3D. For Metal at scale 1, run the first command again with
   `JDK_JAVA_OPTIONS=-Dprism.allowhidpi=false` in front. What to look at beyond pass or fail, all on a GPU pipeline:
   a rotated layer under `perspective`; two such layers where the second one's content animates (its tile must not
   show a projected or partial background); a layer resting at `rotateY(360deg)` under a perspective; a print of a
   composited page on Metal and at scale 2 (the read-back that crashed before the review fix); a composited animation
   with overlapping translucent layers (texture reuse), and GPU memory about 2 s after it stops.
3. **`build-webkit.yml`** after the branch is pushed (`gh workflow run build-webkit.yml --ref <branch> -f
   platforms=all -f configuration=Release`): Linux and Windows have never compiled this change. Watch
   `BitmapTexturePool.cpp`, `TextureMapperJava.cpp` and the seven files that include `WebPage.h`, which no longer
   brings in `TextureMapper.h`; and, since the review fixes removed `BufferImageJava.cpp` from `SourcesJava.txt`,
   every unified bundle from `platform/graphics/java/BitmapImageJava.cpp` to the end of that list. The cached
   `../caches/sdk` library is stale from this commit on.
4. **The graphics robot tests for the `ES2RTTexture` and `D3DContext` changes** (owner only). The review fixes make
   every es2 render texture asked for as `CLAMP_NOT_NEEDED` clamp at its edge, for every application, and es2 is the
   default pipeline on Linux. No agent ran a window: the change is shown offscreen only, by the es2 page run and the
   rotated-image scenario, on Apple's OpenGL. The session could have run this set on Metal and with
   `-Dprism.order=es2`; the owner decided on 2026-11-27 to run it personally. Run the rendering regression set on
   es2, here with `-Dprism.order=es2` and on Linux with the default pipeline, and a green `submit.yml` for the branch:
   ```bash
   mvn -pl tests/system -am test -DFULL_TEST=true -DUSE_ROBOT=true -Dsurefire.includes='test/robot/**/*.java'
   ```
   What to look at: the window's edge pixels when the render size differs from the output size (the back buffer is
   such a texture), and region-cached controls. The d3d change does the same for every d3d render texture asked for as
   `CLAMP_NOT_NEEDED`, for every application, and d3d is the default pipeline on Windows; nobody has run it. Run the
   same set on Windows with the default pipeline, plus the web tests of item 2 with a Windows `jfxwebkit` from item
   3 in `L`. What to look at there: the window's edge pixels at 1:1, at a render scale other than the output scale and
   during a live resize (the swap chain's back buffer is such a texture); a rotated or scaled composited WebView layer,
   a rotated HTML `<canvas>` and a rotated or scaled `WebView`; and a Phong material whose specular map has UVs
   outside [0, 1], drawn after WebView content (its texture unit, 1, keeps the address mode the last 2D draw on it
   left, which can now be a render texture's clamp, as it already was after a gradient fill).
5. **d3d:** the change in `D3DContext.updateTexture` (a `CLAMP_NOT_NEEDED` render texture gets the clamp address
   mode) is kept in this PR by the owner's decision of 2026-11-27, to be verified by the owner's Windows run under
   item 4. If that run shows a problem, the one-condition hunk is what to drop, and the fringe under
   [Known limits](#known-limits) is then a limit of d3d again.
6. **Filing the upstream issue** from the draft under [Upstream check](#upstream-check); move it out of the temporary
   directory first, and search JBS for an existing issue, which no agent could do.
7. **Done:** set the status to ✅ Done with the date and the PR, and move the row to the done table of
   `backlog/README.md`.

Not run or not verified, in one place: a composited page in a window on any GPU pipeline (since the review fixes
the page tests pass offscreen on Metal and es2, and the perspective path, the read-back, the nested decodes and
texture reuse have scenarios of their own there); d3d; any scale but 1 on a real screen;
Linux and Windows; every `tests/system` class; a Debug build; DumpRenderTree; pool growth while a page is alive, which
never showed on macOS (only the leftover after a page closes did: 2 registry ids per page before, none after), so the
Linux leak is a reading of the code; mask results beyond the tests' tolerance; the Java-side items on upstream
master, and JBS.
