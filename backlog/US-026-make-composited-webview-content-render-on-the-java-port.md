# US-026 — Make composited WebView content render on the Java port

**Status:** 🔷 Implemented (2026-11-26, uncommitted; filed 2026-09-30): direction (A), by defining `TextureMapper` and
`BitmapTexture` for Java on top of `GraphicsContext` and `ImageBuffer`, so composited content draws and no GL code or
`data()` user links; on macOS arm64 the `modules/javafx.web` tests pass against a local `jfxwebkit` with the change
(550 / 0 / 0 / 113: the baseline's 473 plus 77 new tests; of the 68 that existed when the baseline library was last
run, 42 fail on it); run only on software Prism at scale 1 under Headless glass; left for the owner: the commit and
the PR, the `tests/system` web tests on a real pipeline (never run by an agent, `CSS3DCompositingTest` included),
the Linux and Windows builds, and filing the upstream issue; see
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

### Known limits
- A true projection (`rotateX`/`rotateY` under `perspective`) is drawn only by es2, metal and d3d. On software Prism,
  Java2D and in a print it is **not drawn at all**.
- No depth buffer: 3D order comes from WebKit's plane splitting. No edge antialiasing of rotated layers; seams between
  256 px tiles are possible under non-integer transforms.
- A fresh render texture per intermediate surface per frame; a page whose translucent or masked content spans many
  tiles records many draws (measured on the test pages: 12 to 10 draws with the cull; the larger saving is a
  calculation only).
- Text smoothing in transparent layer tiles can differ from the page; filters do not reach composited descendants; a
  lost graphics device leaves layer tiles blank until they repaint.
- While a node is highlighted outside compositing mode, the whole view is repainted on every frame.

### Deviations
- **ICU data:** the archive CI downloads is not on this Mac, so the data was read out of the cached release library
  (`../caches/sdk/lib/libjfxwebkit.dylib`, symbol `_icudt77_dat`, 31,895,376 bytes, sha256 `310d9b2c…`) and zipped
  into the build tree. Not checked against the official archive. Also: Makefiles instead of Ninja, no ccache.
- The FFM binding tests were run once without `-DskipNative=true` (the recording stub had to be rebuilt), which also
  ran the graphics and media CMake builds incrementally.
- Reviewers read snapshots of the changed files while mutants were being built in the tree.
- `modules/javafx.web/hs_err_pid75448.log` is left from the deliberate mutant crash (ignored by git).

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
1. **The commit,** with the 29 changed files of `git status` (the three deleted headers, this file and
   `backlog/README.md` among them) and its 17 new files (`US-026-macos-per-class.tsv` among them); and the PR, which
   names the `jfxwebkit` it was verified with (the final library above, or a `build-webkit.yml` run) and says that only
   software Prism on macOS was run.
2. **The `tests/system` web tests on a real pipeline** (owner only: they take over the screen; no agent ran them, so
   there is no expected result). Check the library first, then run the non-robot and the robot web tests:
   ```bash
   cd /Users/benesquivelmusic/Documents/SourceCode/jfx-ffm
   shasum -a 256 modules/javafx.web/build/us026-final-37f184fa/lib/libjfxwebkit.dylib
   L="$PWD/modules/javafx.web/build/us026-final-37f184fa/lib:$PWD/modules/javafx.media/target/native/bin:$PWD/modules/javafx.graphics/target/native/bin"
   mvn -pl tests/system -am test -DFULL_TEST=true -Djfx.web.skipTests=false -Djfx.parity.require=true -Dsurefire.failIfNoSpecifiedTests=false '-Dtest=test/javafx/scene/web/**,!UserDataDirectoryTest' "-Djfx.native.librarypath=$L"
   mvn -pl tests/system -am test -DFULL_TEST=true -DUSE_ROBOT=true -Djfx.web.skipTests=false -Dsurefire.failIfNoSpecifiedTests=false '-Dtest=test/robot/javafx/web/**' "-Djfx.native.librarypath=$L"
   ```
   The hash must be `37f184fa42328b1ff6cd5f62c68eaf76bb4151a9eb323efa2dca5e626fc5c618`. The first `mvn` command also
   runs the module's `test.javafx.scene.web` classes. `-Djfx.parity.require=true` makes `CSS3DCompositingTest` fail
   instead of skip where the pipeline has no 3D. For Metal at scale 1, run the first command again with
   `JDK_JAVA_OPTIONS=-Dprism.allowhidpi=false` in front. What to look at beyond pass or fail: a rotated layer under
   `perspective` (only a GPU pipeline draws it), and a print of a composited page.
3. **`build-webkit.yml`** after the branch is pushed (`gh workflow run build-webkit.yml --ref <branch> -f
   platforms=all -f configuration=Release`): Linux and Windows have never compiled this change. Watch
   `BitmapTexturePool.cpp`, `TextureMapperJava.cpp` and the seven files that include `WebPage.h`, which no longer
   brings in `TextureMapper.h`. The cached `../caches/sdk` library is stale from this commit on.
4. **Filing the upstream issue** from the draft under [Upstream check](#upstream-check); move it out of the temporary
   directory first, and search JBS for an existing issue, which no agent could do.
5. **Done:** set the status to ✅ Done with the date and the PR, and move the row to the done table of
   `backlog/README.md`.

Not run or not verified, in one place: any GPU pipeline, so the perspective path, the print-thread hop and the
transparency layer are proven only on software Prism or by unit tests with a recording graphics; any scale but 1;
Linux and Windows; every `tests/system` class; a Debug build; DumpRenderTree; pool growth while a page is alive, which
never showed on macOS (only the leftover after a page closes did: 2 registry ids per page before, none after), so the
Linux leak is a reading of the code; mask results beyond the tests' tolerance; the Java-side items on upstream
master, and JBS.
