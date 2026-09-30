# US-026 — Make composited WebView content render on the Java port

**Status:** 📋 Ready (filed 2026-09-30; from a read-only analysis of the Java compositing path, every claim checked by a second reader; one runtime probe, see "Probe") · **Found:** 2026-09-30, fixing the `TextureMapper` crash behind the `TextureMapperTest` failure (the `m_data(nullptr)` fix, uncommitted at filing)

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
