# Backlog — user stories of the JNI-to-FFM epic

Parent epic: *fully remove JNI from `javafx.graphics`, replacing it with the Java 22+ FFM API, and
delete as much C/C++ as can be removed safely without changing behaviour.* Branch of record:
`ffm/graphics`.

A second epic, the **Rust port** (the fork's long-term goal 3), was surveyed on 2026-09-30. It covers the native code
that has to stay native. Its stories, and the defects and Java routes the survey found, are US-027 to US-050.
A review of US-034 added US-051, the PR review of US-039 added US-052 and US-053, and that of US-044 added US-054.
See "Rust port" below.

This directory holds the epic's user stories, **open and done**, so they are tracked in source
control. A done story stays here for the record: its status is set to "✅ Done" with the date and
the PR that finished it, and its row moves from the open table to the done table below. Numbers are
never reused. Defect reports and upstream (OpenJDK) requests are not stories and are kept outside
the repository until filed.

Conventions: one file per story, `US-NNN-<slug>.md`, with Story / Problem or Central finding /
Acceptance criteria / Definition of Done. Supporting evidence shares the story's prefix
(`US-009-*`, `US-052-*`). Files are LF-terminated, like the rest of the tree.

## Open stories

| ID | Title | Status (2026-10-01) | Next action |
| --- | --- | --- | --- |
| [US-001](US-001-descope-glass-gtk-glass-mac-prism-mtl-ffm-migration.md) | Migrate `glass/gtk`, `glass/mac`, `prism_mtl` to FFM | 🔶 Linux half unblocked (WSL builds and tests the module); macOS half needs a macOS host | Schedule `glass/gtk` (99 natives, 102 upcall sites) |
| [US-003](US-003-migrate-javafx-font-jni-to-ffm.md) | Migrate `javafx_font` to FFM | 🔶 Windows and Linux halves done; macOS half (68 natives: `coretext.OS`, `MacFontFinder`, `DFontDecoder`) remains | Needs a macOS host |
| [US-013](US-013-fix-sw-texture-paint-first-texel-edge-and-out-of-bounds-read.md) | Fix the first texel row and column of the software texture paint | 📋 Ready (filed 2026-09-26); SW only. `PiscesPaint.c` `genTexturePaintTarget` interpolates the first device column and row between texels 0 and 1 with the weights of texels −1 and 0, so scaled or sub-pixel images are smeared one texel up-left (alpha 191 where D3D/ES2 give 0). The same path reads past the end of the texture array for images one texel tall or wide | Pick up when scheduled; also upstream |
| [US-014](US-014-fix-sw-linear-convolve-steps-for-rotated-inputs.md) | Step the software linear convolution along the right destination axis for rotated inputs | 📋 Ready (filed 2026-09-26); SW only. `JSWLinearConvolvePeer.filter` divides `dycol` by the destination height and `dxrow` by its width, swapped, for an input with a rotation or shear, so SW `InnerShadow(GAUSSIAN)` on a node rotated by 90 or 270 differs from D3D/ES2 by up to 156 steps even unclipped. The swap is validated in scratch; a rotate-45 residual of up to 29 steps remains to explain | Pick up when scheduled; also upstream |
| [US-015](US-015-keep-gtk-display-tests-off-the-developer-desktop.md) | Keep the GTK display tests from changing the developer's desktop | 📋 Ready (filed 2026-09-27); the 21 GTK display test classes run on whatever X display `DISPLAY` names, so a plain module run from a WSL shell (WSLg's `:0`, as in US-008's first Linux gate) or a desktop terminal puts windows and file choosers on the screen. `GtkScreenNativeTest` overwrites and then deletes the window manager's `_NET_WORKAREA` and `_NET_CURRENT_DESKTOP`, and `GtkClipboardNativeTest` takes the clipboard without restoring it | Maintainer decides: opt-in via a system property (recommended; passed by the WSL gate and by CI under US-016 on a private Xvfb) or opt-in only for the classes with side effects |
| [US-016](US-016-run-gtk-display-tests-in-ci-on-a-virtual-display.md) | Run the GTK display tests in CI on a virtual display | 📋 Ready (filed 2026-09-27); neither Linux job of `submit.yml` has a `DISPLAY`, so 27 of the 32 GTK glass test classes report `Tests run: 0` and CI runs no GTK display test. Results that depend on the window manager (US-008's F3 failures) need two runs on a private Xvfb, one without a window manager and one under openbox | Lands with or after US-015's flag: add the two private-Xvfb runs after the `install` step of `linux_x64_build` |
| [US-017](US-017-keep-wm-frame-extents-out-of-undecorated-gtk-stages.md) | Keep a window manager's frame extents out of undecorated GTK stage bounds | 📋 Ready (filed 2026-09-27); Linux GTK only. Glass applies a WM's `_NET_FRAME_EXTENTS` to every frame type, and WSLg's Weston reports a decorated frame's 38, 38, 59, 38 (its 32 px shadow margin included) for undecorated windows too, so on WSLg an `UNDECORATED`, `TRANSPARENT` or `EXTENDED` stage reports its position 38, 59 px up-left of its client, a size 76 x 97 px too large, and minimum and maximum size hints 76 x 97 px too small (upstream too, not a fork regression) | Pick up when scheduled: survey the WMs (Weston, openbox, i3) first, then decide the behaviour per frame type; also upstream |
| [US-018](US-018-fix-jslc-metal-header-path-and-static-state.md) | Stop the jslc Metal backend depending on `jsl-` in the output path and on static state | 📋 Ready (filed 2026-09-27); `MSLBackend` cuts its header directory out of the output path at the first `jsl-` (a path without one throws `StringIndexOutOfBoundsException`) and keeps that directory and the Objective-C header in static fields, so the first Metal compile in a JVM sets the directory and every later one adds to the same header. A macOS build without `clean` after a `.jsl` edit rewrites `DecoraShaderCommon.h`/`PrismShaderCommon.h` with only the recompiled shaders (1 of 50 after a `ColorAdjust.jsl` edit, 28 of 212 after a `PaintColor.jsl` edit; reproduced by running the generators as the pom does, outside Maven; no macOS build run) | Pick up when scheduled (a macOS host only for the final native check); also upstream |
| [US-019](US-019-reject-trailing-input-in-the-jsl-compiler.md) | Make the JSL compiler reject input after the last declaration | 📋 Ready (filed 2026-09-27); `translation_unit` has no EOF and `JSLC.getParserInfo` does not check where the parser stopped, so everything from the first token that cannot start a declaration is dropped without an error: a stray `}` before `void main()` in `Brightpass.jsl` gives HLSL, GLSL and Metal without `main` while the generator exits 0, and text after the last `}` is ignored. Later, `javac` fails on the generated SW peer on every platform and Windows `fxc` on an HLSL without `main`; shaders without a SW peer (Prism, `LinearConvolve*`) pass a Linux ES2 build and are packaged without `main`. No shipped shader has trailing input (reproduced by running the generators as the pom does, outside Maven; upstream too, not a fork regression) | Pick up when scheduled: choose EOF in `JSL.g4` or a check in `getParserInfo`, either with `LINE_COMMENT` ending at EOF; also upstream |
| [US-020](US-020-stop-jsl-glue-blocks-swallowing-code.md) | Stop a JSL glue block swallowing the code after it | 📋 Ready (filed 2026-09-27); `GLUE_BLOCK` in `JSL.g4` has been greedy since the ANTLR 4 upgrade (`52adea7c36`, 2019; the ANTLR 3 rule was not), so a glue block runs to the last `>>` of the program: a second block's closer, or a `>>` in a later JSL comment. The code in between becomes Java text in the peers and vanishes from every shader: a function between two blocks is dropped without an error, a `param` there gives `Unknown variable`, and a `// luminance >> 1` comment in the `main` of `Brightpass.jsl` gives an empty HLSL and a GLSL without `main` while the generator exits 0; the build then fails in `javac` on the generated peers, never at the `>>`. Both fix options, non-greedy and a closer at the start of a line, regenerate all 1,059 files md5-identical and pass the 159 jslc tests. A glue block in a Prism shader, which no Prism output can hold, vanishes without a message too (reproduced by calling the compiler and by running the generators as the pom does, outside Maven; upstream too, not a fork regression) | Pick up when scheduled, with or after US-019 (under either option a block that ends early is an error only with it): choose (a) or (b), and where the Prism glue check goes; also upstream |
| [US-021](US-021-remove-unused-decora-generator-inputs.md) | Remove the unused Decora and Prism generator inputs | 📋 Ready (filed 2026-09-27); no build step calls the Decora drivers `CompileBoxBlur`, `CompileGaussian`, `CompileZoomRadialBlur` and `CompileExternal` or reads the `.jsl` files `BoxBlur`, `GaussianBlur`, `ZoomRadialBlur`, `PaintTextureYUV422` and `PaintTextureYUV444` (9 files, 663 lines; upstream too, the effects out of the build since 2009 and `CompileExternal` since 2013), yet every build compiles the 4 drivers and starts 2 Prism generator JVMs for the YUV shaders. Without the 9 files the generators write the same 1,059 files, md5-identical, and every JVM exits 0 (a copy also without `SepiaTone.jsl` exits 1). The drivers cannot simply be switched back on: 2 reject the shader name `GenAllDecoraShaders` passes and 3 of the 6 peers they generate do not compile. The runtime `ZoomRadialBlur` has no peer, so every filter through it (and `getAccelType`) throws (reproduced by running the generators as the pom does, outside Maven; Maven, Linux and macOS not run) | Pick up when scheduled: choose the scope, (A) delete the 9 files with (B1) the runtime `ZoomRadialBlur` (recommended), (A) with (B2) keeping it, or (C) keep and document; decide whether to propose the deletion upstream |
| [US-022](US-022-report-jsl-errors-at-the-source-file-line.md) | Report JSL errors at the source file and line | 📋 Ready (filed 2026-09-27); a JSL syntax error names no file and gives the line of the program the generator assembled: 234 of the 264 programs the build parses are assembled from several files or from generator text (`CompileBlend`, `CompilePhong`, the Prism `CompileJSL`), and 7 of 10 inserted errors were reported 1 to 81 lines off (`Blend_ADD.jsl:76` as `line 147:4`, `PaintLinearGradient.jsl:44` as `line 125:4`). Semantic errors (`Unknown variable`, `Unknown function`, the `unroll` restrictions) carry no location at all, and 7 limits of the Java software peer, a nested function call among them, surface as an `InternalError` after the shaders are written. `JSLC.compile(JSLCInfo, File)` also leaves the `.jsl` file open when every output is up to date (measured on Windows and Linux) (reproduced by running the generators as the pom does, outside Maven; upstream too, not a fork regression) | Pick up when scheduled: choose a source map, line markers or a per-piece parse for Part 1; also upstream |
| [US-023](US-023-run-the-prism-shader-generator-once.md) | Run the Prism shader generator once per build | 📋 Ready (filed 2026-09-27); the pom's `<apply>` starts the Prism `CompileJSL` once per `.jsl` file, 30 JVMs in every build, but the generator ignores `-name` and composes all 212 programs in each: the first JVM writes everything and the other 29 write nothing, about 10 s of every Windows build of the module, clean or not, and 5 s of a clean Linux build. One forked `<java>` step writes, rewrites and fails exactly as the loop does in every case measured (clean with `-d3d -es2 -mtl` and with `-d3d`, after edits, with a source time in the future, with a syntax error). The Maven port (`01f190af72`) dropped the Gradle task's up-to-date check, so the fork also pays in builds with nothing changed (run with Ant 1.10.12, outside Maven; upstream has the loop) | Pick up when scheduled: replace the `<apply>` with one `<java fork="true">` (recommended); update US-018 and US-021, whose numbers change |
| [US-024](US-024-regenerate-shaders-when-the-jsl-tools-change.md) | Regenerate the shaders when the JSL compiler or a generator changes | 📋 Ready (filed 2026-09-28); the up-to-date check compares only the time of the `.jsl` text, so a build without `clean` after a change to the JSL compiler or a generator rewrites nothing (a changed Prism generator: none of the 1,059 files rewritten, where a clean run changes 318), and a `.jsl` file restored with its older time is not compiled again. The gates build with `clean` and are unaffected; US-019, US-020 and US-022 all change the compiler (upstream too: JDK-8090470, open since 2012) | Pick up when scheduled, before a compiler change is built without `clean`: choose the tools in the source time, a stamp, always `-force`, or documentation |
| [US-025](US-025-fix-uninitialized-and-dangling-members-in-java-webkit-branches.md) | Fix three uninitialized or dangling members in the Java branches of WebKit | 📋 Ready (filed 2026-09-30); found by a sweep of all 573 `PLATFORM(JAVA)` guard sites while fixing the `TextureMapper` crash. `LocaleNone::monthLabels()`/`shortMonthLabels()` return references to locals that shadow the members (and fill the wrong member); `ComplexTextRun::m_stringLength` is garbage for runs built by the generic constructors; the Java custom `Cursor` constructor leaves `m_type` `Invalid`. None is reachable in production today (upstream too, not a fork regression) | Pick up when scheduled; needs a `jfxwebkit` rebuild; also upstream |
| [US-026](US-026-make-composited-webview-content-render-on-the-java-port.md) | Make composited WebView content render on the Java port | 📋 Ready (filed 2026-09-30); since the WebKit 623.1 update every composited paint on Java runs the base `TextureMapper` functions through `TextureMapper&`, and on Java they are no-ops (`beginPainting` compiled out, so the clip stays empty; `drawTexture`/`drawSolidColor` empty), so in compositing mode, which a view transition enters with default settings and CSS 3D enters always, `WebPage` draws no new page content (probe: with CSS 3D a `translateZ(0)` box never appears; during a default view transition the previous frame stays on screen). The `data()` users that became null dereferences with the `TextureMapper` crash fix are unreachable only because of this, so they must be guarded first (upstream too, not a fork regression) | Maintainer chooses (A) draw through `TextureMapperJava` or (B) keep the default configuration out of compositing mode |
| [US-027](US-027-add-a-rust-toolchain-to-the-native-build-and-ci.md) | Add a Rust toolchain to the native build and CI | 📋 Ready (filed 2026-09-30); the tree and the development machine have no Rust. Cargo runs from CMake (a custom target plus an IMPORTED staticlib); exports go through `/EXPORT:` and a Linux version script; the C/Rust switch is `JFX_RUST`; the toolchain is pinned, crates are vendored, a cbindgen check guards the ABI and a perl script gates licences. The proof slice moves `jfxm_abi_version` | The enabler for every Rust port: install rustup on Windows and in WSL, then pick up |
| [US-028](US-028-build-and-link-the-rust-slices-on-macos.md) | Build and link the Rust slices on macOS | 🔶 Deferred (filed 2026-09-30); the trigger is the first library also built on macOS whose C is ready to delete (`jfxmedia`, `fxplugins`). CI-only verification, since there is no macOS host | Pick up when US-032, US-033, US-035 or US-036 reaches its deletion slice |
| [US-029](US-029-port-the-glass-windows-winrt-preferences-to-rust.md) | Port the Glass Windows WinRT preferences to Rust | 📋 Ready (filed 2026-09-30); 755 lines, 5 exports. The WinRT sinks capture a raw `this` and are never unregistered, and Java-side COM was already rejected for this code in the ABI header | The first real Rust slice. glass.dll is Windows-only, so its C can be deleted without US-028. After US-027 and US-039 part 2 |
| [US-030](US-030-port-the-glass-windows-com-servers-to-rust.md) | Port the Glass Windows COM servers (clipboard, DnD, UI Automation) to Rust | 📋 Ready (filed 2026-09-30); 4,507 lines, 32 exports, 105 callback slots. `#[implement]` replaces hand-written refcounts and `delete this` in objects that other processes hold | After US-029 and US-039 part 3 |
| [US-031](US-031-port-the-glass-windows-toolkit-core-to-rust.md) | Port the Glass Windows toolkit core (loop, WndProcs, IME, key tables) to Rust | 📋 Ready (filed 2026-09-30); 9,306 lines, 65 exports (66 if the ruling on US-049 part 1 is no); the toolkit-HWND race and per-HWND lifetimes. Its "why not Java" rests on an unmeasured WndProc upcall volume, so the maintainer may defer it | After US-030, US-039 parts 1 and 4, and US-049 part 1; slice 3 should follow US-052 and US-053 |
| [US-032](US-032-port-the-javasource-gstreamer-element-to-rust.md) | Port the javasource GStreamer element to Rust | 📋 Ready (filed 2026-09-30); 1,398 lines. It proves gstreamer-rs against gstreamer-lite, whose ordinal-only `.def` grows append-only, and adds the native trace driver the other media ports reuse | After US-027 and US-041; its C stays on macOS until US-028 |
| [US-033](US-033-port-progressbuffer-and-hlsprogressbuffer-to-rust.md) | Port progressbuffer and hlsprogressbuffer to Rust | 📋 Ready (filed 2026-09-30); 2,357 lines and three threads. No test reaches either element today, so the trace goldens are the oracle | After US-032; its C stays on macOS until US-028 |
| [US-034](US-034-port-the-mfwrapper-h265-decoder-element-to-rust.md) | Port the mfwrapper H.265 decoder element to Rust | 📋 Ready (filed 2026-09-30); 2,550 lines. A hand-rolled `IMFMediaBuffer` refcount and an untrusted `hvcC` parser. Parity needs an HEVC decoder MFT on the test machine | After US-032 and US-051 |
| [US-035](US-035-port-the-jfxmedia-frame-conversion-spectrum-equalizer-and-logger-code-to-rust.md) | Port jfxmedia's frame, conversion, spectrum, equalizer and logger code to Rust | 📋 Ready (filed 2026-09-30); 5,224 lines, 25 exports. Java cannot bind GStreamer on Windows, because gstreamer-lite exports by ordinal only | After US-027; its C stays on macOS until US-028 |
| [US-036](US-036-port-the-jfxmedia-gstreamer-pipeline-core-to-rust.md) | Port the jfxmedia GStreamer pipeline core to Rust | 📋 Ready (filed 2026-09-30); 9,544 lines, 33 exports. A hand-rolled teardown handshake, and races reproduced as relaxed atomics, not fixed | After US-035, ideally after US-040; its C stays on macOS until US-028 |
| [US-037](US-037-port-the-gtk-glass-screencast-code-to-rust.md) | Port the GTK Glass screencast code to Rust | 🔶 Blocked (filed 2026-09-30); 3,051 lines, 11 exports. WSL has no PipeWire, portal or D-Bus daemon, and CI runs no GTK display tests. The rest of GTK Glass is deferred until a GTK 4 port, because gtk-rs's GTK 3 crates are archived | After US-027, US-038 and US-042 |
| [US-038](US-038-run-the-gtk-screencast-paths-in-wsl-against-a-mock-portal-and-a-stub-pipewire.md) | Run the GTK screencast paths in WSL against a mock portal and a stub PipeWire | 📋 Ready (filed 2026-09-30); today's tests reach only the "no PipeWire" branch | Pick up now; it blocks US-042 and US-037 |
| [US-039](US-039-fix-four-glass-windows-cpp-defects-before-its-rust-port.md) | Fix four Glass Windows C++ defects before its Rust port | 📋 Ready (filed 2026-09-30); a racy toolkit HWND, WinRT sinks that outlive their object, a UIA text-range NULL crash and BSTR leak, and `bad_alloc` unwinding through `user32` | Pick up now; the parts merge separately, part 2 after part 1; US-052 follows part 1 directly |
| [US-040](US-040-fix-lock-allocation-and-leak-defects-in-the-jfxmedia-gstreamer-pipeline.md) | Fix lock, allocation and leak defects in the jfxmedia GStreamer pipeline | 📋 Ready (filed 2026-09-30); two flags under mixed locks, spectrum lists indexed by the Java band count, a throwing `new` in a GLib callback, and a source-element leak on three failure returns | Pick up now |
| [US-041](US-041-fix-gstbuffer-map-misuse-a-leaked-buffer-and-a-float-to-int-ub-in-fxplugins.md) | Fix GstBuffer map misuse, a leaked buffer and a float-to-int UB in fxplugins | 📋 Ready (filed 2026-09-30); writes through read maps in javasource and dshowwrapper, a DirectShow sink leak, and an unbounded double-to-`gint64` in progressbuffer | Pick up now; it blocks US-032 and US-033 |
| [US-042](US-042-fix-the-lost-wake-up-and-unchecked-frame-geometry-in-the-gtk-screencast.md) | Fix the lost wake-up and unchecked frame geometry in the GTK screencast | 📋 Ready (filed 2026-09-30); the predicate is tested outside the PipeWire loop lock, compositor strides and sizes are not bounds-checked, and an uncropped frame is read after its buffer goes back to PipeWire | After US-038, whose test bed its regression tests run on |
| [US-043](US-043-fix-seven-latent-defects-in-the-d3d-pipeline-cpp.md) | Fix seven latent defects in the D3D pipeline's C++ | 📋 Ready (filed 2026-09-30); the phong destructor releases NULL slots (new), blend factors are uninitialised, a read-back over-copies 4× and divides by `w`, plus three more that the FFM migration carried | Pick up now; it blocks US-047 |
| [US-044](US-044-delete-the-encoder-and-unreachable-decoder-modules-from-the-bundled-libjpeg.md) | Delete the encoder and the unreachable decoder modules from the bundled libjpeg | 📋 Ready (filed 2026-09-30, corrected 2026-10-01); 22 files and 15,433 lines (44.4 % of libjpeg) that `iio_api.c` can never execute: the 17 compressor files and 5 decoder files. `jdmerge.c` is not one of them: the merged upsampler runs when the decoder derives a block size of 9 to 16 (a SmartScale file, or an SOF1 header cut short), so it stays, and US-054 tests it | Pick up now (goal 1) |
| [US-045](US-045-replace-javafx-iio-with-a-faithful-java-port-of-the-ijg-libjpeg-decoder.md) | Replace javafx_iio with a faithful Java port of the IJG libjpeg 10 decoder | 📋 Ready (filed 2026-09-30, corrected 2026-10-01); routed to Java by the Rust survey: an integer-only decoder is as provable in Java as in Rust. Rust is the fallback if the slice-4 benchmark fails. Forking IJG needs the maintainer's sign-off. Its corpus must give every one of the 32 IDCT kernels and the merged upsampler a member | After US-044 and US-054 part 1 |
| [US-046](US-046-port-the-pisces-software-compositor-to-java-and-delete-prism-sw.md) | Port the Pisces software compositor to Java and delete prism_sw | 📋 Ready (filed 2026-09-30); the plan of record is in `prism_sw_api.h:54-62`, and a golden harness exists (`PiscesGoldenRenderTest`) | After US-013 |
| [US-047](US-047-drive-direct3d-9ex-from-java-and-delete-the-prism-d3d-cpp.md) | Drive Direct3D 9Ex from Java and delete the prism_d3d C++ | 📋 Ready (filed 2026-09-30); routed to Java by the Rust survey, with KEEP as the fallback: every OS entry is a COM slot or a plain export (the DirectWrite precedent). A readback corpus is needed first | After US-043 |
| [US-048](US-048-call-opengl-from-java-and-delete-the-prism-es2-wrappers.md) | Call OpenGL from Java and delete the prism_es2 wrappers | 🔶 Needs a ruling (filed 2026-09-30); the survey reads the 58 GL-call exports as WRAPPERs that FFM binds by address, which contradicts the ES2 audit's OS-CALL | The maintainer rules first; then the corpus |
| [US-049](US-049-move-glass-windows-robot-capture-to-java-and-delete-the-pre-vista-file-dialogs.md) | Move Glass Windows robot capture to Java and delete the pre-Vista file dialogs | 🔶 Needs a ruling on part 1 (filed 2026-09-30); it contradicts the header's "stays native". Part 2 deletes 384 dead lines | Part 2 now; the ruling on part 1 before US-031 |
| [US-050](US-050-retire-dshowwrapper-by-decoding-through-media-foundation.md) | Retire dshowwrapper by decoding through Media Foundation | 🔶 Needs a ruling (filed 2026-09-30); it would delete 43k lines (the plugin plus the DirectShow baseclasses), but AAC/MP3 parity is `tolerance` or `unprovable` | The maintainer rules first; then after US-034 |
| [US-051](US-051-keep-the-mta-alive-for-mfwrapper-com-calls.md) | Keep the MTA alive for mfwrapper's COM calls | 📋 Ready (filed 2026-10-01); mfwrapper leaves the MTA as soon as `MFStartup` returns, then creates and drives its decoder and colour converter mostly on GStreamer threads that hold no apartment (a flushing seek's reload can run on the seeking thread, which may be in an STA), so it depends on another thread holding the MTA. The DirectSound sink's device notifier holds it in every jfxmedia pipeline today, but nothing in the element declares the dependence; a pipeline without that sink, such as US-034's trace driver, has no holder | Pick up now; it blocks US-034 |
| [US-052](US-052-fix-two-glass-windows-toolkit-teardown-gaps.md) | Fix two Glass Windows toolkit teardown gaps | 📋 Ready (filed 2026-10-01; part 1 redesigned the same day after PR review); a toolkit thread can end without `gwin_terminate_loop` (SWT-embedded, or a `WM_QUIT` on the pump, which needs foreign code). The system then frees the toolkit window (documented) and its procedure gets no `WM_NCDESTROY` (measured on a probe), so the toolkit stays published: US-039 part 1's guard protects a dead or recycled HWND and a thread id that can be reused, and a queued `gwin_invoke_and_wait` waits for ever. Part 1 clears the publication from the `DLL_THREAD_DETACH` arm of a `DllMain`. Separately, the classes of the toolkit window and of every window open at exit are never unregistered | Directly after US-039 part 1, which ships that wait until this lands; before US-031 slice 3, which it does not block |
| [US-053](US-053-fix-two-glass-windows-window-procedure-hazards.md) | Fix two Glass Windows window-procedure hazards | 📋 Ready (filed 2026-10-01); a window destroyed inside one of its own messages has its property removed after the system freed the handle, so `RemoveProp` can hit a recycled HWND; and the toolkit window calls through the `WPARAM` of any `WM_DO_ACTION` or `WM_DO_ACTION_LATER` it receives, from any sender. Both are in upstream's code too | Part 1 now; part 2 after US-039 part 1; before US-031 slice 3, which it does not block |
| [US-054](US-054-test-the-libjpeg-merged-upsampler-then-replace-it-if-identical.md) | Test the libjpeg merged upsampler, then replace it with the separate path if the two are identical | 📋 Ready (filed 2026-10-01); `jdmerge.c` runs when the decoder derives a block size of 9 to 16 for a 2h1v or 2h2v YCbCr JPEG decoded at 1/1, which an image URL can cause, and no corpus member reaches it. Part 1 adds the members by header surgery, with no encoder. Part 2 is optional: compile merging out if every golden stays identical | Part 1 now; it blocks US-045 slice 1. Part 2 after the mutation corpus of US-045 slice 1 |
| [US-055](US-055-trim-every-pass-of-a-non-odd-multipass-box-kernel.md) | Trim every pass of a multi-pass box kernel whose size is not an odd whole number | 📋 Ready (filed 2026-10-02 from the decision US-011 recorded); for a box size that is not an odd integer (an even width, or any size under a non-integer node, snapshot or HiDPI scale), `validateWeights` trims only the first box and convolves it with untrimmed boxes of `ceil(s) \| 1`, so a multi-pass GPU kernel is wider than the repeated box of `s` (7.25 x 3: variance 17.75 px² against 13.24) and jumps when `s` crosses an odd integer. `BoxRenderStateWeightsTest` pins the current kernel (upstream too, not a fork regression) | Maintainer chooses (A) trim every pass (recommended), (B) keep and document, or (C) round up like SW |
| [US-056](US-056-reuse-the-box-pass-weights-between-calls.md) | Reuse the box-blur pass weights instead of rebuilding them on every call | 📋 Ready (filed 2026-10-02 from the implementation of US-011); `BoxRenderState` compares its weights-cache keys `weightsValidSize`/`weightsValidSpread` but never assigns them, so every `getPassWeights()` and `getPassWeightsArrayLength()` rebuilds the kernel. CPU and garbage only, no pixel changes; `GaussianRenderState` sets its keys (upstream too, not a fork regression) | Pick up when scheduled: two assignments, plus a unit test that a second read does not rebuild |
| [US-057](US-057-give-javafx-base-the-module-version-of-the-build.md) | Give `javafx.base` the same module version as the rest of the build | 📋 Ready (filed 2026-10-02 from US-011's runtime snapshot); a local build gives `javafx.base@28-ea` but `javafx.graphics@28-internal`, which records `requires javafx.base` at `28-ea`, though both poms pass `--module-version ${jfx.release.version.short}`; the likely cause, `maven-compiler-plugin`'s own `moduleVersion` default, is not confirmed | Pick up when scheduled, or with the publishing work |
| [US-058](US-058-give-the-decora-golden-self-consistency-checks-negative-controls.md) | Give the Decora golden test's self-consistency checks negative controls | 📋 Ready (filed 2026-10-02 from the independent review of US-011); no test asserts `SELF_CONSISTENCY` or `EDGE_ROWS`, although the class javadoc says the negative controls prove every branch can fail, and the production self-check US-011 added on its clipped deviation rows can be deleted without a test failing. `edgeRows` may be vestigial since the F2 clip fix | Pick up when scheduled: a control per cause, or remove the `EDGE_ROWS` branch if no row sets it |

## Done stories

| ID | Title | Done | Outcome |
| --- | --- | --- | --- |
| [US-005](US-005-fix-coloradjust-divide-by-zero-in-jsl.md) | Fix the ColorAdjust divide-by-zero in `ColorAdjust.jsl` | 2026-09-25, PR #14 | `if (cmax > cmin && cmax != 0.0)`, `ColorAdjustZeroMaxChannelTest`, D3D/ES2 visual check |
| [US-006](US-006-fix-gaussian-pass0-clip-growth-radiusy.md) | Grow the Gaussian pass-0 clip by the vertical radius | 2026-09-25, PR #15 | `GaussianRenderState` grows the pass-0 clip by `inputRadiusY` |
| [US-007](US-007-fix-boxblur-software-peer-drops-input-transform.md) | Keep the input transform in the software BoxBlur peer | 2026-09-26, PR #16 | `JSWBoxBlurPeer` passes `inputs[0].getTransform()` on; the two translated `BoxBlur` golden rows are a reviewed `TransformDeviation` |
| [US-008](US-008-remove-dead-jslc-me-backend-and-simd.md) | Remove the dead jslc ME backend and `AccelType.SIMD` | 2026-09-27, PR #19 | ME backend (4 `.java`, 2 `.stg`; it could still emit JNI C) and `AccelType.SIMD` deleted, −1,867 lines; `OUT_*` bits kept; `jsl-decora`/`jsl-prism` md5-identical and test counts unchanged on Windows and Linux. Extended: `AccelType.FIXED` deleted; the jslc tests ported to ANTLR 4/JUnit 5 and run in the build (159 tests; 6 that could never fail now can, and every negative is pinned to its reason, with an end-of-input check in all 13 parser helpers); `GtkWindowNativeTest`/`GtkUploadBenchmarkTest` corrected for window managers and WSLg (6 failures, all test assumptions). Later findings filed as US-015 to US-024 |
| [US-009](US-009-migrate-monocle-jni-to-ffm.md) | Keep Monocle (embedded Linux) and migrate it from JNI to FFM | 2026-09-23, PR #12 | S1–S8 + D3; 195 natives → 0, ~3,500 lines of C deleted, `prism_es2_monocle` target, `monocle_egl_ext.h` |
| [US-010](US-010-fix-gaussian-input-clip-sign-under-rotation-mirror.md) | Pad the Gaussian input clip by absolute distances | 2026-09-26, PR #18 | `GaussianRenderState.getInputClip` pads by `ceil(\|dx0\| + \|dx1\|)` and `ceil(\|dy0\| + \|dy1\|)`; `GaussianInputClipTest`, golden unchanged, D3D/ES2/SW snapshot check met |
| [US-011](US-011-fix-box-kernel-weights-off-by-one-for-multipass.md) | Build symmetric box-blur kernels for two or more passes | 2026-10-02 | `BoxRenderState.validateWeights` sums a full window at `i == klen` (`while (i >= klen)`); `BoxRenderStateWeightsTest` checks the kernels bitwise against independent convolutions; the 28 golden rows recorded with the old kernel are a reviewed `KernelDeviation`; D3D/ES2/SW snapshot check met. Follow-ups US-055 to US-058 |
| [US-012](US-012-fix-box-pass-size-squared-srcscale-for-scaled-inputs.md) | Scale the box pass size by the input scale once, not twice | 2026-09-26, PR #17 | `BoxRenderState.validatePassInput` scales the pass size by `srcScale` once; `BoxRenderStateScaledInputTest`, golden unchanged, D3D/ES2/SW snapshot check met |

Never filed in this directory: US-002 `prism_common` (deleted 2026-09-07) and US-004 `glass/win` (no
`native` method left in `com.sun.glass.ui.win`, verified 2026-09-22).

## Rust port (goal 3)

The fork's goals, outermost first:
1. less native code, in favour of pure Java, with behaviour-neutrality outranking it;
2. JNI → FFM;
3. port what has to stay native to Rust.

So Rust is only for the residue. A library goes to Rust when all of these hold:
- the triage leaves it native (OS-CALL, PURE-HOT, or a Java replacement ruled `PARITY: unprovable`);
- it is our own code, not vendored;
- it can be built and tested here (Windows and WSL);
- Rust buys something concrete (memory safety on untrusted input, COM or refcount lifetimes, cross-thread state);
- a maintained, GPLv2-compatible binding exists;
- it can be sliced behind its unchanged C ABI.

Code that Java can own with provable parity goes to Java instead. The survey of 2026-09-30 applied these tests to
every native library; the verdicts are in the table below.

### Port rules

Every Rust story follows these rules. Stories cite them as P1-P9.

- **P1 The header is the contract.** The Rust code exports exactly the symbols of the library's `*_api.h`: the same
  C types, ABI version, struct layouts, threading and ownership rules. The Java facade and its tests do not change.
- **P2 Mixed library.** Each library has one Rust `staticlib`, linked into its existing CMake target. A slice moves one
  function group behind `JFX_RUST`. The slice's C is deleted in its own commit once the slice is accepted on Windows
  and WSL (US-027). The C of a library that is also built on macOS stays for macOS until US-028.
- **P3 Parity.** Goldens or call/event traces are captured from the C/C++ before it is deleted. The Rust must match
  them exactly, unless a tolerance was agreed in advance. Moving a golden is a behaviour change with its own commit.
- **P4 Failures and panics.**
  - Every failure value of the C comes back through a `Result`.
  - Allocations sized by input are fallible.
  - Every export and every entry point foreign code calls (WNDPROCs, COM methods, GLib/GStreamer/PipeWire
    callbacks) runs in the crate's `catch_unwind` guard. The guard returns the C/C++ failure value and poisons the
    handle.
  - Nothing unwinds into C, C++ or Java.
- **P5 `unsafe`.** `unsafe` appears only in a boundary module, with a true `// SAFETY:` comment on each block.
- **P6 No new concurrency.** No new threads, locks, event loops or async runtimes. A race the C has is reproduced
  (with atomics where the C raced) and fixed in its own story.
- **P7 Memory.** The side that allocates frees. There is no global allocator.
- **P8 Crates.** GPLv2-compatible licences only: MIT, BSD, ISC, Zlib, Unicode-3.0, or the MIT option of dual-licensed
  crates. Crates are vendored, built offline, and recorded in `legal/` (US-027's licence gate).
- **P9 Exports.** The export list stays identical in every slice (`dumpbin /exports`, `nm -D --defined-only`). On
  Linux, one exception is allowed: symbols of C removed from the Linux build (deleted, or kept only for macOS until
  US-028) that were exported only through default visibility, and that no consumer resolves, may disappear if the
  PR lists them.

Test media for the media stories is small and generated by a command recorded next to the file, with a provenance
note. No downloaded or third-party media is committed.

### Verdict per native library (survey of 2026-09-30)

| Native code | Lines (`wc -l`) | Verdict | Why | Stories |
| --- | --- | --- | --- | --- |
| Glass Windows: WinRT preferences | 755 | RUST | Java-side COM already rejected in the header; WinRT sinks capture a raw `this` and are never unregistered | US-039 → US-029 |
| Glass Windows: COM servers (clipboard, DnD, UIA) | 4,507 | RUST | Inbound COM objects that other processes hold; hand-written refcounts and `delete this` | US-039 → US-030 |
| Glass Windows: toolkit core (loop, WndProcs, IME, keys, screen, menu) | 9,306 | RUST, last | OS-CALL and native state; toolkit-HWND race, per-HWND lifetimes; "why not Java" rests on unmeasured upcall volume | US-039, US-049 → US-031 (US-052 and US-053 should precede) |
| Glass Windows: robot capture; pre-Vista file dialogs | in the core row; 384 | JAVA / delete | A stateless GDI sequence; dead on Windows 10 and later | US-049 |
| Glass Windows: COM file dialogs, `OleUtils.h` | 453 + 209 | DEFER | A COM client on `_com_ptr_t`, no defect found. Trigger: the last C++ left in glass.dll, or a test that drives the dialogs | — |
| Glass GTK: screencast (portal + PipeWire) | 3,051 | BLOCKED, then RUST | Callbacks on PipeWire's thread and compositor-described buffers; WSL has no PipeWire, portal or D-Bus yet | US-038, US-042 → US-037 |
| Glass GTK: window, events, IME, DnD, keys, cursor, screen | 7,779 | DEFER | gtk-rs's GTK 3 crates are archived, so there is no safe binding. Trigger: a GTK 4 Glass story | — |
| Glass macOS, including accessibility | 19,728 | BLOCKED | No macOS host; still JNI | US-001 |
| Monocle EGL headers; Monocle test stub | 179 + 273 | KEEP | Headers only; a test double of a vendor contract | — |
| prism_d3d | 7,545 C++ + 709 HLSL | JAVA (conditional; fallback KEEP) | Every OS entry is a COM slot or a plain export; Java also removes the two-language reset protocol; needs a readback corpus first | US-043 → US-047 |
| prism_es2 | 5,804 owned + 17,501 Khronos headers | JAVA (needs a ruling) | The GL calls are WRAPPERs that FFM binds by address, contrary to the ES2 audit; macOS part BLOCKED; the headers are KEEP | US-048 |
| prism_sw (Pisces) | 5,240 | JAVA | The plan of record in `prism_sw_api.h:54-62` | US-046 |
| prism_mtl | 5,737 | BLOCKED | macOS only | — |
| iio: libjpeg encoder and unreachable decoder modules | 15,433 | delete | Never executed by `iio_api.c`: the encoder, the IFAST and float IDCTs, the colour quantizers and the transcoder. `jdmerge.c` is live (block sizes 9 to 16) and is in the next row | US-044 |
| iio: used libjpeg decoder + `iio_api.c` | 19,339 + 1,140 | JAVA (conditional; Rust fallback) | Integer-only, `jdmerge.c` included, so a faithful port is as provable in Java as in Rust; memory safety on web images; the maintainer rules on forking IJG. The merged upsampler has no test yet (US-054) | US-044, US-054 → US-045 |
| Fonts (macOS: CoreText, DFont) | 1,685 | BLOCKED, then JAVA | US-003's plan; needs a macOS host | US-003 |
| jfxmedia: frames, colour conversion, spectrum, equalizer, logger | 5,224 | RUST | Java cannot bind gstreamer-lite on Windows (ordinal-only exports); refcount-dense; `ColorConverter` is PURE-HOT | US-035 |
| jfxmedia: GStreamer pipeline core | 9,544 | RUST | GStreamer callbacks on foreign threads; a hand-rolled teardown handshake; racy fields | US-040 → US-036 |
| jfxmedia: `platform/osx` (AVFoundation) | 4,407 | BLOCKED | macOS only | — |
| fxplugins: javasource | 1,398 | RUST | A `GstElement` on GStreamer threads; the smallest element, so it proves the gstreamer-rs setup | US-041 → US-032 |
| fxplugins: progressbuffer + hlsprogressbuffer | 2,357 | RUST | Three threads; range arithmetic on container-driven offsets | US-041 → US-033 |
| fxplugins: mfwrapper (Windows) | 2,550 | RUST | A hand-rolled COM refcount on a GstBuffer map; an untrusted `hvcC` parser | US-051 → US-034 |
| fxplugins: dshowwrapper (Windows) | 5,134 + 37,810 baseclasses | KEEP; retire instead | The DirectShow baseclasses have no Rust equivalent (2.6× the plugin) | US-041, US-050 |
| fxplugins: avplugin (Linux) and the registration shim | 4,066 + 138 | KEEP | The untrusted parsing happens inside the system ffmpeg; a binding per libavcodec major | — |
| gstreamer-lite, GLib, libffi, DirectShow baseclasses | about 690,000, vendored | KEEP | Vendored third-party code | — |
| WebKit (jfxwebkit) and its Java-port glue | 4.37 million, 55,835 of it under `*/java/*` | KEEP | A vendored engine; the glue is written against WebCore's C++ classes and is built only by `build-webkit.yml` | — |
| javafx.web test stub (`wkjstub`) | 2,731 | KEEP | Test-only | — |

### Order

1. **Now, with no Rust needed:**
   - the C/C++ fixes: US-039, US-040, US-041, US-043, US-051, US-053 part 1 and, after US-039 part 1, US-052 and
     US-053 part 2;
   - the deletions and Java routes: US-044, US-049 part 2, US-046 (after US-013), US-047 (after US-043);
   - the tests for libjpeg's merged upsampler: US-054 part 1;
   - the screencast test bed US-038, then US-042, whose regression tests run on it.
2. **US-027**, the toolchain. It needs rustup installed on Windows and in WSL.
3. **Rust ports:**
   - Glass Windows: US-029 → US-030 → US-031 (US-052 and US-053 before its slice 3). Windows-only, so their C
     can be deleted without US-028.
   - Media plugins: US-032 → US-033, plus US-034 (after US-051) alongside US-033.
   - jfxmedia: US-035 → US-036.
   - Screencast: US-037.
   - US-028 before any media C is deleted on macOS.
4. **After the maintainer's rulings:** US-045 (forking IJG), US-048 (vs the ES2 audit), US-049 part 1 (vs the ABI
   header's "stays native"), US-050 (AAC/MP3 parity).
   US-054 part 2 follows slice 1 of US-045.

## US-009 evidence

| File | Purpose |
| --- | --- |
| `US-009-monocle-ffm-research-dossier.md` | Four read-only research passes over the tree with a triage verdict, FFM replacement and file:line citation for every Monocle native |
| `US-009-s0-monocle-baseline.tsv` | The S0 baseline: every test of the Monocle-Headless suite with its status in three consecutive runs and a verdict (stable-pass 1 222, persistent 42, flaky 58, skipped 50) |
| `US-009-s0-classify.pl` | Produces that TSV from surefire report directories; the S1–S8 gate tool |

The S1–S8 gate, from the repository root on a Linux host (no display needed, never with
`-DHEADLESS_TEST=true`, which forces `glass.platform=Headless`):

```
mvn -B -ntp -pl tests/system -am test -DskipNative=true -DFULL_TEST=true -DUSE_ROBOT=true \
    -DUNSTABLE_TEST=true -Dtest='test/**/monocle/**/*Test' -Dsurefire.failIfNoSpecifiedTests=false
perl backlog/US-009-s0-classify.pl <archived S0 report dirs> tests/system/target/surefire-reports
```

A slice passes when every `stable-pass` row of the baseline still passes. The `persistent` rows
are the known baseline and the `flaky` rows are tracked but not gating.

## US-052 evidence

A standalone Windows probe for the thread-end notification of US-052 part 1. It is not shipped and not built by
Maven.

| File | Purpose |
| --- | --- |
| `US-052-thread-end-probe-results.md` | What the probe measured on 2026-10-01 (Windows 10.0.19045.6466 x64, glass.dll's compiler and linker flags): which of five mechanisms (FLS callback, image TLS callback, `thread_local` destructor, `DllMain`, thread-handle wait) fires in scenarios S1 to S12, on which thread, with the window alive or not and the loader lock held or not; and what was not measured |
| `US-052-thread-end-probe-dll.cpp` | The probe DLL: `probe.dll` without a `DllMain`, as glass.dll is today, and `probe_dm.dll` with one (`/DPROBE_DLLMAIN`) |
| `US-052-thread-end-probe-delayload.cpp` | `probe_dl.dll`, which reaches `user32` through delay-load thunks as glass.dll does (S8c, S8d) |
| `US-052-thread-end-probe-exe.cpp` | `probe_exe.exe`: the scenarios, and a launcher with a 20 s watchdog |
| `US-052-thread-end-probe-log.h` | CRT-free logging shared by the probe DLL and the EXE sources; the delay-load source does not use it |
| `US-052-thread-end-probe-build.bat` | Builds the four binaries with the flags `native/win.cmake` gives glass.dll in a Release build |
| `US-052-thread-end-probe-run-all.pl` | Runs every scenario in a fresh process, eight passes, then calls the summary script |
| `US-052-thread-end-probe-summarize.pl` | Normalises the logs and prints the figures the results file quotes. A run writes them to `summary.txt` next to the sources; that file is not kept in the tree |

To rebuild and rerun (Visual Studio 2022 x64 and Git Bash perl, about five minutes), copy the files to a scratch
directory first: the binaries, `logs/` and `summary.txt` are written next to the sources and are not kept in the
tree.

```
cmd /c US-052-thread-end-probe-build.bat
perl US-052-thread-end-probe-run-all.pl
```
