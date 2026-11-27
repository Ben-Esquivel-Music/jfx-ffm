# Backlog — user stories

This folder holds the fork's user stories, open and done, one file per story. Four lines of work feed it:

- **Less native code** (the first epic): remove JNI from the fork, replace it with the FFM API, and delete C/C++ where
  Java can do the job without changing behaviour. JNI is gone since US-074.
- **Rust port**: move the native code that has to stay native to Rust. Surveyed 2026-09-30. See "Rust port" below.
- **Game toolkit**: a game loop, a built-in physics engine ported from Godot, and a contract for other engines.
  Planned 2026-11-07. See "Game toolkit" below.
- **Media formats**: more formats in `javafx.media`. Planned 2026-11-07. See "Media formats" below.

## How a story is written

- One file per story, `US-NNN-<slug>.md`. Numbers are never reused. A story split into substories keeps its number,
  and each substory adds a letter (`US-001a`, `US-001b`). Files are LF-terminated.
- **Every story starts with "In short"**: at most ten lines (a few more for a story in parts), in plain words, that
  a reader who has never seen the code can follow. No file paths, line numbers or commit hashes unless the reader
  needs one to act.
  - **Problem:** what is wrong or missing, for whom, and what they see.
  - **Seen failing?** yes (and how), or no (read from the code only), or not a defect.
  - **Goal:** what is true when the story is done.
  - **Done when:** the one or two checks that prove it.
  - **Order:** what it waits for, what waits for it, and how urgent it is.
  - Once a story is implemented, **Order** gives way to **State** and **Left to do**.
- Below "In short" come the details for whoever implements it: Story, Problem, Acceptance criteria, Definition of
  Done. A story for later work states the requirement, its constraints and how to verify it, and no design.
- A done story stays here. Its status is set to "✅ Done" with the date and the PR, and its row moves to the done
  table.
- **This folder holds stories only.** Test tables, gate records, audits and probe write-ups are not kept here. The
  ones that were here were removed on 2026-11-27; the last commit that has them is `cee405c84e`
  (`git show cee405c84e:backlog/<file>`). A story states its result in a few lines and says where its logs are.
- Defect reports and upstream (OpenJDK) requests are not stories and are kept outside the repository until filed.

### When a new story is filed

Solving one story must not leave three new ones behind. A finding made while working on a story is handled in this
order:

1. **Fix it in the story**, when it is in code the story touches or it is small.
2. **Drop it with one line in the story**, when nobody has seen it fail and no known path reaches it.
3. **File a new story** only when the fix needs another machine or operating system, needs the owner's ruling, is a
   new feature, or is larger than the story it was found in. The owner agrees before any story is filed, and related
   findings go into one story.

## Open stories

State as of 2026-11-27, checked against `master` (`cee405c84e`).

### Merged; only closing steps are left

The code of these is on `master`. What is left is for the owner. Each story's "In short" has the list.

| ID | Story | Merged | Left to do |
| --- | --- | --- | --- |
| [US-001](US-001-descope-glass-gtk-glass-mac-prism-mtl-ffm-migration.md) | Move Glass GTK, Glass mac and the Metal pipeline to FFM (parent of US-001a and US-001b) | both halves | Close with US-001b |
| [US-001b](US-001b-migrate-glass-mac-and-prism-mtl-jni-to-ffm-on-macos.md) | Move Glass mac and the Metal pipeline to FFM | PR #48 | Robot comparison and VoiceOver check on the Mac; two rulings |
| [US-016](US-016-run-gtk-display-tests-in-ci-on-a-virtual-display.md) | Run the GTK display tests in CI | PR #35 | One deliberately failing CI run; fill in the story's tables |
| [US-018](US-018-fix-jslc-metal-header-path-and-static-state.md) | Keep the Metal shader headers complete in a build without `clean` | PR #50 | One Windows build check; upstream report |
| [US-019](US-019-reject-trailing-input-in-the-jsl-compiler.md) | Make the shader compiler reject text after the last declaration | PR #62 | Upstream issue |
| [US-020](US-020-stop-jsl-glue-blocks-swallowing-code.md) | Stop a Java block in a shader file swallowing the code after it | PR #63 | Upstream issue |
| [US-021](US-021-remove-unused-decora-generator-inputs.md) | Delete the unused shader generator inputs | PR #65 | Two upstream issues |
| [US-022](US-022-report-jsl-errors-at-the-source-file-line.md) | Report shader errors at the source file and line | PR #67 | One test proof on Windows and Linux; upstream issue |
| [US-023](US-023-run-the-prism-shader-generator-once.md) | Run the Prism shader generator once per build | PR #68 | Upstream issue |
| [US-024](US-024-regenerate-shaders-when-the-jsl-tools-change.md) | Regenerate the shaders when the shader tools change | PR #69 | Upstream comment |
| [US-025](US-025-fix-uninitialized-and-dangling-members-in-java-webkit-branches.md) | Fix three uninitialised or dangling values in the Java port of WebKit | PR #72 | `tests/system` web tests; manual cursor check; WebKit CI build; upstream issue |
| [US-026](US-026-make-composited-webview-content-render-on-the-java-port.md) | Make layered WebView content (CSS 3D, view transitions) draw | PR #75 | **Never run in a real window, on Direct3D, Linux or Windows.** `tests/system` web tests; robot tests on es2 and d3d; WebKit CI build; upstream issue |
| [US-027](US-027-add-a-rust-toolchain-to-the-native-build-and-ci.md) | Add Rust to the native build and CI | PR #66 | Confirm two side effects; record a green CI run |
| [US-028](US-028-build-and-link-the-rust-slices-on-macos.md) | Build and link the Rust code on macOS | PR #70 | Confirm the side effects |
| [US-029](US-029-port-the-glass-windows-winrt-preferences-to-rust.md) | Move the Windows system-settings code of Glass to Rust | PR #76 | `tests/system` preference tests; manual check; accept the side effects |
| [US-062](US-062-fix-two-prism-sw-fixed-point-edge-cases.md) | Fix two rounding slips in the software renderer | not merged | Bring the branch up to date and open its PR |

US-063 (*blend a partly covered box edge under `SRC` by its coverage in the software compositor*) was filed on the
US-062 branch and reaches `master` with it.

### Defects that were seen failing

| ID | Story | What was seen | Start |
| --- | --- | --- | --- |
| [US-131](US-131-stop-a-webview-page-teardown-deadlocking-on-macos-when-sqlite-is-not-yet-initialized.md) | Closing a WebView page that uses `localStorage` can freeze the app on macOS | The web tests hang in 4 of 4 runs on the Mac | **High.** Needs a WebKit build |
| [US-134](US-134-make-a-javascript-assignment-to-a-wrapper-typed-java-field-store-the-value-every-time.md) | A script's assignment to a Java field sometimes leaves it `null` | A test fails in about half of the runs on Windows; cause unknown | **High.** Find the cause first |
| [US-116](US-116-keep-the-build-directory-out-of-the-runpath-of-the-linux-media-libraries.md) | The shipped Linux media libraries search the build machine's folder for libraries | Measured with `readelf` | Now. Small |
| [US-119](US-119-point-the-gstreamer-glib-and-webkit-notices-at-this-forks-sources.md) | Three legal notices name the wrong place for the modified sources | Read in the notice files | Now. Small, text only |
| [US-113](US-113-make-an-error-in-any-prism-generator-input-fail-the-build.md) | An error in three shader input files never fails the build | 15 of 15 broken builds pass | Now. Small |
| [US-128](US-128-make-a-build-without-changes-compile-nothing-again.md) | A build without `clean` compiles everything again, and keeps the classes of deleted sources (was US-128, US-121) | 19 timed builds; one measured case | Now. Saves minutes on every build |
| [US-127](US-127-make-a-build-to-the-compile-phase-pass-from-a-fresh-clone.md) | `mvn clean compile` fails in a fresh clone | Measured on Windows | Now. Workaround: build to `test-compile` |
| [US-125](US-125-keep-the-output-of-the-native-build-tools-readable-in-the-maven-log.md) | Native build errors arrive scrambled in the Maven log | Quoted from build logs | Now |
| [US-126](US-126-keep-the-native-memory-bound-of-linuxfontstresstest-from-failing-under-load.md) | A Linux font stress test can fail on a busy machine | Once, in one WSL gate | Now. Small, test only |
| [US-122](US-122-stop-the-jsl-java-software-peer-crashing-or-writing-wrong-code.md) | The shader compiler crashes, writes wrong Java, drops `? :` or writes Metal shaders that Apple's compiler rejects (was US-122, US-123, US-124) | On test inputs only; no shipped shader is affected | Now. Part 3 needs the Mac |

### Defects read from the code, never seen failing

| ID | Story | Start |
| --- | --- | --- |
| [US-115](US-115-fix-the-band-count-allocation-and-phases-of-the-macos-avfoundation-spectrum.md) | Audio spectrum and equalizer: wrong spectrum data on macOS, silence above 1024 bands, a possible hang when a band moves on macOS, a removed band still used (was US-115, US-117, US-118, US-120) | Now. Try the hang on the Mac first: urgent if it is real |
| [US-114](US-114-release-everything-a-failed-gstreamer-media-creation-has-built.md) | A failed media open leaks the pipeline parts already built | Now |
| [US-043](US-043-fix-seven-latent-defects-in-the-d3d-pipeline-cpp.md) | Seven defects in the Direct3D C++; one can crash the JVM after a failed shader creation | Now. US-047 waits for it |
| [US-041](US-041-fix-gstbuffer-map-misuse-a-leaked-buffer-and-a-float-to-int-ub-in-fxplugins.md) | Four defects in the media plugins (a write through a read-only buffer, a leak, an unchecked conversion) | Now. US-032 and US-033 wait for it |
| [US-051](US-051-keep-the-mta-alive-for-mfwrapper-com-calls.md) | The Windows H.265 decoder relies on another component to keep COM alive | Now. US-034 waits for it |
| [US-052](US-052-fix-two-glass-windows-toolkit-teardown-gaps.md) | Windows: if the toolkit thread ends without a shutdown, a later `invokeAndWait` waits for ever | Now. Before slice 3 of US-031 |
| [US-053](US-053-fix-two-glass-windows-window-procedure-hazards.md) | Windows: a late clean-up can hit another window; a private message is trusted as a pointer | Now. Before slice 3 of US-031 |
| [US-042](US-042-fix-the-lost-wake-up-and-unchecked-frame-geometry-in-the-gtk-screencast.md) | Wayland screen capture can hang or read past a frame buffer | After US-038 |

### Test equipment and records

| ID | Story | Start |
| --- | --- | --- |
| [US-038](US-038-run-the-gtk-screencast-paths-in-wsl-against-a-mock-portal-and-a-stub-pipewire.md) | A fake portal and a fake PipeWire, so the Wayland screen-capture code can run in WSL | Now. US-042 and US-037 wait for it |
| [US-054](US-054-test-the-libjpeg-merged-upsampler-then-replace-it-if-identical.md) | Test images for a JPEG decode path that no test reaches | Part 1 now. US-045 waits for it |
| [US-132](US-132-make-the-glass-windows-preference-tests-catch-a-dropped-deferred-delivery-discard-or-sink-removal.md) | Seven breakages of the Windows settings code that no test notices | Before slice 3 of US-031 |
| [US-130](US-130-make-the-directwrite-golden-independent-of-the-test-classes-that-run-before-it.md) | The DirectWrite font reference test depends on which tests ran before it | Now. Small |

### Less native code

| ID | Story | Start |
| --- | --- | --- |
| [US-044](US-044-delete-the-encoder-and-unreachable-decoder-modules-from-the-bundled-libjpeg.md) | Delete 22 files of the bundled JPEG library that can never run | Now. US-045 waits for it |
| [US-045](US-045-replace-javafx-iio-with-a-faithful-java-port-of-the-ijg-libjpeg-decoder.md) | Replace the native JPEG decoder with an identical Java one | After US-044 and part 1 of US-054. Needs the owner's sign-off |
| [US-047](US-047-drive-direct3d-9ex-from-java-and-delete-the-prism-d3d-cpp.md) | Call Direct3D from Java and delete the Direct3D C++ | After US-043 |
| [US-048](US-048-call-opengl-from-java-and-delete-the-prism-es2-wrappers.md) | Call OpenGL from Java and delete the C wrappers on Windows and Linux | **Needs the owner's ruling first** |
| [US-049](US-049-move-glass-windows-robot-capture-to-java-and-delete-the-pre-vista-file-dialogs.md) | Move the Windows `Robot` screen capture to Java; delete dead file-dialog code | Part 2 now. **Part 1 needs the owner's ruling**, before US-031 |
| [US-050](US-050-retire-dshowwrapper-by-decoding-through-media-foundation.md) | Decode Windows media through Media Foundation and delete the DirectShow plugin | **Needs the owner's ruling first**: AAC and MP3 would sound close, not identical |

### Rust port

No defect is known in any of these. Each moves hand-written native code to Rust with no change in behaviour.

| ID | Story | Start |
| --- | --- | --- |
| [US-030](US-030-port-the-glass-windows-com-servers-to-rust.md) | Windows clipboard, drag and drop and screen-reader objects | Now (after US-029) |
| [US-031](US-031-port-the-glass-windows-toolkit-core-to-rust.md) | Windows message loop, window procedures and input | After US-030 and the ruling on US-049 part 1 |
| [US-032](US-032-port-the-javasource-gstreamer-element-to-rust.md) | The media element that feeds bytes from Java | After US-041 |
| [US-033](US-033-port-progressbuffer-and-hlsprogressbuffer-to-rust.md) | The two download buffer elements | After US-032 |
| [US-034](US-034-port-the-mfwrapper-h265-decoder-element-to-rust.md) | The Windows H.265 decoder element | After US-032 and US-051 |
| [US-035](US-035-port-the-jfxmedia-frame-conversion-spectrum-equalizer-and-logger-code-to-rust.md) | `libjfxmedia`: frames, colour conversion, spectrum, equalizer | Now |
| [US-036](US-036-port-the-jfxmedia-gstreamer-pipeline-core-to-rust.md) | `libjfxmedia`: the playback core | After US-035 |
| [US-037](US-037-port-the-gtk-glass-screencast-code-to-rust.md) | Wayland screen capture of GTK Glass | Blocked: after US-038 and US-042 |

### Game toolkit

New features. See "Game toolkit" below for the order and the owner's rulings.

| ID | Story | Start |
| --- | --- | --- |
| [US-076](US-076-capture-per-step-physics-goldens-from-a-pinned-godot-build.md) | Record what Godot's physics does at every step | First. The owner installs SCons and Python on the capture machine |
| [US-077](US-077-measure-the-physics-ports-speed-against-godot-before-the-port-is-written.md) | Measure whether a Java port of the physics can be fast enough | After US-076 |
| [US-078](US-078-add-a-fixed-step-game-loop-with-interpolation-and-per-tick-input.md) | A fixed-step game loop with per-step input | Now |
| [US-079](US-079-port-godots-2d-physics-engine-to-pure-java-bit-exact.md) | Port Godot's 2D physics to Java, with identical results | After US-076 and US-077 |
| [US-080](US-080-let-applications-bring-their-own-2d-physics-engine.md) | A public 2D physics API that any engine plugs into | After US-079 |
| [US-081](US-081-port-godots-2d-character-controller-and-collider-tools-above-the-engine-contract.md) | Godot's 2D character controller and collider tools | After US-079 and US-080 |
| [US-082](US-082-ship-a-playable-2d-game-preview.md) | A playable 2D sample game | After US-078, US-080 and US-081 |
| [US-083](US-083-prove-a-native-2d-physics-engine-plugs-in-through-ffm.md) | Prove that a native 2D engine fits the API | After US-080 |
| [US-084](US-084-ship-a-game-as-a-jlink-image-and-a-jpackage-installer.md) | Ship a game as a self-contained image and an installer | After US-082 |
| [US-085](US-085-let-a-native-3d-physics-engine-plug-into-a-3d-engine-contract.md) | A public 3D physics API, with a native 3D engine behind it | After US-080 |
| [US-086](US-086-port-godots-3d-rigid-body-physics-to-pure-java-bit-exact.md) | Port Godot's 3D physics to Java, with identical results | After US-077, US-079 and US-085 |
| [US-087](US-087-ship-a-playable-3d-game-preview.md) | A playable 3D sample game | After US-078, US-085 and US-086 |
| [US-088](US-088-read-gamepads-into-the-per-tick-input-snapshot.md) | Gamepads | After US-078 |
| [US-089](US-089-capture-the-pointer-and-report-layout-independent-physical-keys.md) | Mouse capture and physical keys | Now |
| [US-090](US-090-give-javafx-3d-the-rendering-a-3d-game-needs.md) | Shadows, more lights and animated meshes in JavaFX 3D | Now, with a measurement. Likely to be split |
| [US-091](US-091-load-gltf-models-into-javafx-nodes-and-collision-shapes.md) | Load glTF models | After US-085; animated meshes after US-090 |
| [US-093](US-093-step-physics-off-the-fx-thread-and-restore-snapshots-bit-exactly.md) | Physics on its own thread; save and restore a world | After US-078, US-079 and US-080 |
| [US-094](US-094-re-pin-the-built-in-physics-engine-to-a-new-godot-release.md) | Move the port to a newer Godot release | After US-079 and US-086 |
| [US-095](US-095-grow-the-built-in-3d-engine-past-its-godot-origin.md) | Improve the 3D engine beyond Godot's | Last |

### Media formats

New features, except US-096 and US-099. See "Media formats" below for the order.

| ID | Story | Start |
| --- | --- | --- |
| [US-096](US-096-tell-applications-why-media-will-not-play-and-keep-audioclip-working-after-a-failed-play.md) | Say why media will not play; one failed `AudioClip` must not silence the rest | Now. First of this group |
| [US-097](US-097-play-every-wav-and-aiff-the-bundled-parsers-already-decode.md) | Play the ordinary WAV and AIFF files that are refused today | Now |
| [US-098](US-098-play-a-law-mu-law-and-adpcm-audio-in-wav-and-aifc.md) | Play A-law, µ-law and ADPCM audio | After US-097 |
| [US-099](US-099-keep-mp3-aac-and-h264-playing-on-linux-whatever-ffmpeg-is-installed.md) | Linux: keep MP3, AAC and H.264 playing with any supported ffmpeg | Now, with the reproduction |
| [US-100](US-100-let-media-decoded-in-java-play-through-javafx-media.md) | Let media decoded in Java play like native media | After US-096 |
| [US-101](US-101-play-mp3-where-no-platform-decoder-is-available.md) | Play MP3 where the platform has no decoder | After US-096 and US-100 |
| [US-102](US-102-play-cd-g-karaoke-with-its-paired-audio.md) | Play CD+G karaoke | After US-096 and US-100 |
| [US-103](US-103-play-flac-audio.md) | Play FLAC | After US-100 |
| [US-104](US-104-play-ogg-opus-and-ogg-vorbis-audio.md) | Play Ogg Opus and Ogg Vorbis | After US-100 |
| [US-105](US-105-play-midi-files-and-kar-karaoke.md) | Play MIDI and KAR karaoke | After US-100 |
| [US-106](US-106-show-srt-and-webvtt-subtitles-with-media.md) | Show SRT and WebVTT subtitles | Now |
| [US-107](US-107-play-matroska-and-webm-video.md) | Play WebM and Matroska video | Now |
| [US-092](US-092-play-low-latency-mixed-sound-effects-through-javafx-media.md) | Short sounds that start at once and mix | With the media refactor |

### Closed without change, or merged into another story

The files stay, so that the numbers are not reused and older references resolve.

| ID | Story | What happened (2026-11-27, owner's decision) |
| --- | --- | --- |
| [US-112](US-112-make-the-backlogs-skill-citations-point-at-text-that-says-what-they-claim.md) | Stories point at skill rules by line numbers that no longer match | Closed: paperwork. The rule for ported files that US-079 relies on is still unwritten |
| [US-117](US-117-keep-the-audio-spectrum-band-count-within-what-the-spectrum-can-compute.md) | More than 1024 spectrum bands silences the listener | Merged into US-115 (part 2) |
| [US-118](US-118-balance-the-equalizer-lock-and-the-converted-pixel-buffers-of-the-macos-avfoundation-player.md) | macOS equalizer lock and video frame buffer | Merged into US-115 (part 3) |
| [US-120](US-120-give-each-equalizer-band-its-own-native-band-and-none-once-it-is-removed.md) | A removed equalizer band is still used | Merged into US-115 (part 4) |
| [US-121](US-121-keep-the-classes-of-a-deleted-source-out-of-a-build-without-clean.md) | A build without `clean` keeps the classes of a deleted source | Merged into US-128 (part 2) |
| [US-123](US-123-stop-the-jsl-compiler-dropping-the-conditional-operator-and-accepting-duplicate-functions.md) | The shader compiler drops `? :` and accepts duplicate functions | Merged into US-122 (part 2) |
| [US-124](US-124-stop-the-jsl-compiler-writing-metal-shaders-that-the-metal-compiler-rejects.md) | The shader compiler writes Metal shaders that Apple's compiler rejects | Merged into US-122 (part 3) |
| [US-129](US-129-give-the-predefined-cursors-of-the-java-webkit-port-their-type.md) | WebKit's standard cursors have an unset internal type | Closed: no user-visible effect |
| [US-133](US-133-balance-each-toolkit-threads-roinitialize-in-the-handle-that-made-it.md) | The Windows Runtime could be stopped on the wrong thread | Closed: never seen failing, no known way to hit it |

## Done stories

| ID | Title | Done | Outcome |
| --- | --- | --- | --- |
| [US-001a](US-001a-migrate-glass-gtk-jni-to-ffm-on-linux.md) | Migrate `glass/gtk` to FFM (Linux) | 2026-10-27, PR #40 | The JNI went in PR #12 (`26ce75d02f`). AC 9: `glassgtk3` builds without `HEADERS_DIR` and the JDK include directories; 11 hand-kept, JNI-free constants headers with `javac -h`'s file and macro names in `native-glass/gtk/constants/` serve the 9 sources that include them, `GtkConstantsHeaderTest` checks them against the Java classes (12 tests, 15 mutants), and the library is byte-identical for that change. A hang that PR #12 brought to the drop target (a drop inside a read's wait) is fixed in `glass_dnd.cpp` and pinned by `GtkDnDReentrantDropTest`. AC 6 met on WSL x64: module tests and `tests/system` equal to `033187ad90`, the GTK display tests to `26ed4ba2f2` plus the two new classes; three review passes signed off. `linux_x64_build` and `linux_aarch64_build` green, GTK display step included (run 40584664686) |
| [US-003](US-003-migrate-javafx-font-jni-to-ffm.md) | Migrate `javafx_font` to FFM | 2026-10-24, 4745af9f89, PR #38 | The font natives of Windows, Linux and macOS are Java over FFM, `native-font/` is deleted and no platform builds `javafx_font`. The CoreText NULL crashes and leaks kept from the C are US-067 |
| [US-005](US-005-fix-coloradjust-divide-by-zero-in-jsl.md) | Fix the ColorAdjust divide-by-zero in `ColorAdjust.jsl` | 2026-09-25, PR #14 | `if (cmax > cmin && cmax != 0.0)`, `ColorAdjustZeroMaxChannelTest`, D3D/ES2 visual check |
| [US-006](US-006-fix-gaussian-pass0-clip-growth-radiusy.md) | Grow the Gaussian pass-0 clip by the vertical radius | 2026-09-25, PR #15 | `GaussianRenderState` grows the pass-0 clip by `inputRadiusY` |
| [US-007](US-007-fix-boxblur-software-peer-drops-input-transform.md) | Keep the input transform in the software BoxBlur peer | 2026-09-26, PR #16 | `JSWBoxBlurPeer` passes `inputs[0].getTransform()` on; the two translated `BoxBlur` golden rows are a reviewed `TransformDeviation` |
| [US-008](US-008-remove-dead-jslc-me-backend-and-simd.md) | Remove the dead jslc ME backend and `AccelType.SIMD` | 2026-09-27, PR #19 | ME backend (4 `.java`, 2 `.stg`; it could still emit JNI C) and `AccelType.SIMD` deleted, −1,867 lines; `OUT_*` bits kept; `jsl-decora`/`jsl-prism` md5-identical and test counts unchanged on Windows and Linux. Extended: `AccelType.FIXED` deleted; the jslc tests ported to ANTLR 4/JUnit 5 and run in the build (159 tests; 6 that could never fail now can, and every negative is pinned to its reason, with an end-of-input check in all 13 parser helpers); `GtkWindowNativeTest`/`GtkUploadBenchmarkTest` corrected for window managers and WSLg (6 failures, all test assumptions). Later findings filed as US-015 to US-024 |
| [US-009](US-009-migrate-monocle-jni-to-ffm.md) | Keep Monocle (embedded Linux) and migrate it from JNI to FFM | 2026-09-23, PR #12 | S1–S8 + D3; 195 natives → 0, ~3,500 lines of C deleted, `prism_es2_monocle` target, `monocle_egl_ext.h` |
| [US-010](US-010-fix-gaussian-input-clip-sign-under-rotation-mirror.md) | Pad the Gaussian input clip by absolute distances | 2026-09-26, PR #18 | `GaussianRenderState.getInputClip` pads by `ceil(\|dx0\| + \|dx1\|)` and `ceil(\|dy0\| + \|dy1\|)`; `GaussianInputClipTest`, golden unchanged, D3D/ES2/SW snapshot check met |
| [US-011](US-011-fix-box-kernel-weights-off-by-one-for-multipass.md) | Build symmetric box-blur kernels for two or more passes | 2026-10-02, PR #22 | `BoxRenderState.validateWeights` sums a full window at `i == klen` (`while (i >= klen)`); `BoxRenderStateWeightsTest` checks the kernels bitwise against independent convolutions; the 28 golden rows recorded with the old kernel are a reviewed `KernelDeviation`; D3D/ES2/SW snapshot check met. Follow-ups US-055 to US-058 |
| [US-012](US-012-fix-box-pass-size-squared-srcscale-for-scaled-inputs.md) | Scale the box pass size by the input scale once, not twice | 2026-09-26, PR #17 | `BoxRenderState.validatePassInput` scales the pass size by `srcScale` once; `BoxRenderStateScaledInputTest`, golden unchanged, D3D/ES2/SW snapshot check met |
| [US-013](US-013-fix-sw-texture-paint-first-texel-edge-and-out-of-bounds-read.md) | Fix the first texel row and column of the software texture paint | 2026-10-03, PR #23 | SW honours the texture's `WrapMode` at its content edge (images clamp, render targets read zero, as on D3D/ES2) and never reads outside the texture; `prism_sw` ABI 2 (`wrap_mode`); `PiscesTexturePaintEdgeTest`, `SWTextureEdgeSnapshotTest`, mutant proof; `PiscesGoldenRenderTest` golden moved (nearest REPEAT, steps 14-25), recaptured as `pisces-golden-820a4c35d7.bin`; D3D/ES2/SW snapshot check met |
| [US-014](US-014-fix-sw-linear-convolve-steps-for-rotated-inputs.md) | Step the software linear convolution along the right destination axis for rotated inputs | 2026-10-03, PR #24 | `JSWLinearConvolvePeer.filter` divides `dycol` by the destination width and `dxrow` by its height (the two denominators were swapped); the shadow peer inherits it; `LinearConvolveRotatedInputTest` (step and pixel oracles), mutant proof; `DecoraJavaGoldenTest` byte-identical; D3D/ES2/SW snapshot check met (rotate 90/270 InnerShadow 156 to 2). The rotate-45 residual is the shadow peer's nearest sampling at exact ties, filed as US-060 |
| [US-015](US-015-keep-gtk-display-tests-off-the-developer-desktop.md) | Keep the GTK display tests from changing the developer's desktop | 2026-10-19, 8c43d5ff36, PR #34 | The GTK display classes are opt-in behind `-DPRIVATE_DISPLAY=true` (root pom default false; the robot classes also need `-DUSE_ROBOT=true`); `GtkScreenNativeTest`, `GtkEventTraceTest` and `GtkUpcallExceptionTest` save and restore the window manager's root properties; the file chooser test keeps its writes out of the account. Its review filed US-064 |
| [US-017](US-017-keep-wm-frame-extents-out-of-undecorated-gtk-stages.md) | Keep a window manager's frame extents out of undecorated GTK stage bounds | 2026-10-20, PR #36 | Fix option 1, as openjdk/jfx#2139 does: in `glass_window.cpp`, `update_frame_extents` returns unless the window is `TITLED` (no extents, no cache write, no `set_bounds`), and `process_configure` takes an undecorated window's position from the configure event with its view at 0,0; `TITLED` unchanged. On WSLg an undecorated 1920x1080 stage at 0,0 now reports 0,0 1920x1080 with its scene at 0,0 (was -38,-59 1996x1177, scene 38,59), and its size and size limits reach GTK as asked. Survey of no WM, openbox, i3 and Weston recorded; the openbox rows explained (a startup race in `process_configure`, which the fix narrows for undecorated windows but does not remove by construction; the rest is in US-065). `GtkFrameExtentsNativeTest` (14) and `GtkUndecoratedStageBoundsTest` (2), with Weston's extents written as a stand-in where the WM publishes none, fail before the fix; mutant proof; GTK gates unchanged apart from the new classes (194 to 210 tests without a WM, 167 to 183 under openbox), `:0` and i3 scenarios green. Weston's shadow margin stays in decorated stages (D3); the `TITLED` faults filed as US-065 |
| [US-039](US-039-fix-four-glass-windows-cpp-defects-before-its-rust-port.md) | Fix four Glass Windows C++ defects before its Rust port | 2026-10-23; landed 2026-10-27, `7dcae7baeb`, PR #37 | Part 1: the toolkit HWND and thread id are published as atomics and cleared in `WM_NCDESTROY` under an exclusive `SRWLOCK`; every post goes through `PostToToolkit` (shared side across one load and one `PostMessage`, else `GWIN_ERR_NO_TOOLKIT`); a cross-thread `gwin_invoke_and_wait` queues on a pending list through `SendNotifyMessage` and `WM_NCDESTROY` runs the list down, order kept. Part 2: the WinRT sinks only post, so `preferences_changed` runs on the toolkit thread (now asynchronously); both registrations removed and the WinRT objects and factory released before `RoUninitialize`; the stub's arena closed after the loop. Part 3: a NULL text-range destroy returns, both VARIANTs cleared. Part 4: no `bad_alloc` leaves `WmImeComposition`. Six new test classes (39 tests, 2 opt-in), every listed mutant killed; `GLASS_WIN_ABI_VERSION` stays 6 (17 shim-only hooks); javafx.graphics 26198 run, 0 failed, 442 skipped, natives from source. Upstream shares parts 1 and 2 in part, 3 and 4 fully |
| [US-040](US-040-fix-lock-allocation-and-leak-defects-in-the-jfxmedia-gstreamer-pipeline.md) | Fix lock, allocation and leak defects in the jfxmedia GStreamer pipeline | 2026-11-15, uncommitted, PR pending | Part 1: every access of `m_bLastProgressValueEOS` and `m_bHLSPBFull` outside the constructor holds `m_StallLock`; `UpdatePlayerState` reads the two before it takes `m_StateLock` and hands them to `SetPlayerState`, because taking `m_StallLock` inside `m_StateLock` would invert `HLSBufferResume`'s lock order. A discipline fix: every access outside the constructor runs on the GLib main loop, and no race was reproduced. Part 2: the bus handler takes the band count from the message and drops a message computed for another count than the installed arrays' (no write, no event), with `gst_value_list_get_size` exported by the Windows gstreamer-lite; the old read past the lists printed a GLib critical on Linux only and was a silent 0.0 band on Windows. A band count above 1024 now gets no spectrum event, where the listener used to get the seeded values. Part 3: 9 of the 14 plain `new` outside `platform/osx` are `new (nothrow)`, the 5 left run in no callback, and the `bad_alloc` note of `jfxmedia_api.h` lists every escape. Part 4: each failure return of `CreateSourceElement` sinks and unrefs what it made, and each stream-callbacks adapter is freed once. Also: the vendored `gstspectrum.c` reads its band count under its lock and, on Windows and Linux, resets under it in start and stop; a hooks build `jfxmedia_test` in `target/native/test-bin` (3 `jfxm_test_*` exports, 12 counters, never shipped), for which `glib-lite` exports `g_object_weak_ref`. 8 new tests in 3 classes: the module gate went from 33 to 41 tests, 0 failures, 2 skipped, on Windows and in a fresh WSL clone; the 58 shipped `jfxm_*` exports and `JFXM_ABI_VERSION` 4u are unchanged; the mutants are caught, except four the story states: two races no test provokes, a release made once too often and a leaked buffer element. macOS, CI and `tests/system` not run; follow-ups US-114 to US-120 |
| [US-046](US-046-port-the-pisces-software-compositor-to-java-and-delete-prism-sw.md) | Port the Pisces software compositor to Java and delete prism_sw | 2026-10-18, 410b8036c1, PR #32 | The compositor is Java: package-private `PiscesSurfaceState`, `PiscesRendererState`, `PiscesBlit`, `PiscesPaint`, `PiscesTransform` behind the unchanged `PiscesRenderer`/`JavaSurface` API, a faithful port of the C of 42a5072596 (its quirks included, no tolerance anywhere); `native-prism-sw/` (16 files, 5,342 lines), the `prismSW` targets, `PiscesNative`, its shim and its tests deleted; `PiscesCorpusGoldenTest` (`pisces-corpus-42a5072596.bin`, 3,817 steps captured from the C; gcov 96.36 % of its lines) and `PiscesGoldenRenderTest` (renamed `pisces-golden-42a5072596.bin`, bytes unchanged) exact and strict on Windows x64 and WSL Linux x64; frame-time A/B java/native 0.83 to 1.04 per scene after one round of hot-loop changes; where the C crashed or went outside an array, Java throws (listed in US-062); the renderer buffers live on the Java heap |
| [US-055](US-055-trim-every-pass-of-a-non-odd-multipass-box-kernel.md) | Trim every pass of a multi-pass box kernel whose size is not an odd whole number | 2026-10-04, PR #26 | Option A, recorded in the `BoxRenderState` javadoc: `validateWeights` convolves every further pass with the trimmed box (`box[k]`, second loop `while (i >= 0)`), so a non-odd size over n passes is the n-fold trimmed box (4 x 2: `[0.25 1 2 3 3.5 3 2 1 0.25] / 16`); odd sizes bit-identical, SW box peers unchanged. `BoxRenderStateWeightsTest` pin changed deliberately (`nonOddSizeTrimsEveryPass`, exactness rule pinned by `onlyOnePassIsNotProvablyExact`), 7 mutants killed; the 8 `4x6x3` spread golden rows join the reviewed `BOX_KERNEL_TAP_COUNT` deviation with a new negative control, golden not regenerated; D3D/SW snapshot check met (POST D3D = trimmed-every-pass kernel rounded to 8 bits on 27 of 28 axis-rows, symmetric)
| [US-056](US-056-reuse-the-box-pass-weights-between-calls.md) | Reuse the box-blur pass weights instead of rebuilding them on every call | 2026-10-06, PR #27 | `BoxRenderState.validateWeights` assigns its cache keys `weightsValidSize`/`weightsValidSpread` after it builds the kernel, as `GaussianRenderState` does (+2 lines); `BoxRenderStateWeightsTest` +4 reuse and rebuild tests, bit for bit (2 fail on the unfixed code), every half-fix mutant killed; `DecoraJavaGoldenTest` byte-identical, no pixel changes |
| [US-057](US-057-give-javafx-base-the-module-version-of-the-build.md) | Give `javafx.base` the same module version as the rest of the build | 2026-10-04, PR #25 | Nine of the ten modules, not only `javafx.base`, were `28-ea`: `maven-compiler-plugin` 3.14.0 passes its own `--module-version` (`moduleVersion`, default `${project.version}`) after `compilerArgs`; only the graphics Ant recompile escaped. The root pom sets `moduleVersion` to `${jfx.release.version.short}` and the module `compilerArgs` pairs are gone; `buildtools/CheckModuleVersions.java`, run by the root profile `module-version-check` at `process-classes` and `verify`, fails the build on any module or JavaFX `requires` version mismatch (negative controls shown); `-Djfx.release.suffix=-ea` gives `28-ea` throughout; `mvn -fae clean install` green |
| [US-058](US-058-give-the-decora-golden-self-consistency-checks-negative-controls.md) | Give the Decora golden test's self-consistency checks negative controls | 2026-10-06, PR #28 | `edgeRows` is live (the four clipped `LinearConvolve`/gaussian rows set it) and stays; `DecoraJavaGoldenTest` gains five negative controls, one per self-consistency branch including the US-011 kernel-deviation caller, each pinning the kind, the whole message and the finding count; removing a branch fails its control; test only, golden not regenerated (370 to 375 tests, per-row output byte-identical) |
| [US-059](US-059-fix-the-remaining-sw-texture-sampling-differences.md) | Fix the remaining software texture-sampling differences from D3D/ES2 | 2026-10-16, 42a5072596, PR #29 | Nearest-neighbour sampling takes the texel under the pixel centre (`renderer_setTexture`) and clamps to the drawn sub-rectangle; `GENERIC` without REPEAT clamps as the other branches do within half a texel of the sub-rectangle and stays transparent beyond it, which keeps the bounding-box corners of a rotated quad clear as before (the story's premise that they have no coverage was wrong; linear output at those rotations stays byte-identical to HEAD); at quarter turns and mirror diagonals the band widens to half the pixel's texture-space extent, so there the rule equals the clamp of the axis-aligned branches; `ImageView.setSmooth(false)` stays a no-op; `drawImage` rejects an empty texture and a sub-rectangle outside it in Java and C, and `SWGraphics.drawTexture` skips an empty texture. `PiscesTextureSamplingTest` (86 cases, 54 fail on HEAD), `PiscesNativeTest` 18 to 20; golden steps 12 and 14 moved (`pisces-golden-42a5072596.bin`, renamed after the landing commit by US-046); SW = D3D on the probe except at exact ties |
| [US-060](US-060-interpolate-the-sw-shadow-convolution-samples.md) | Interpolate the samples of the software shadow convolution | 2026-10-17, 7d5ffd66f0, PR #31 | `JSWLinearConvolveShadowPeer.filterVector` interpolates the alpha bilinearly at the sample positions and edge tests of `laccumsample` (JDK-8090445), exact on texel centres; `LinearConvolveShadowSamplingTest` (13 tests, 9 fail at 4f71e99535, the 45-degree top cut by 54 steps), mutant proof; one golden row moves (7 px by 1 step), recorded as the reviewed `SamplerDeviation` `SHADOW_SAMPLER_BILINEAR` with 8 negative controls; SW snapshot check met (45-degree top cut 29 to 2, SW vs D3D `InnerShadow` 22 to 3) |
| [US-061](US-061-make-the-golden-drawimage-steps-sample-the-texture.md) | Make the golden `drawImage` steps sample the texture | 2026-10-18, `ee923ace41`, PR #33 | Each `drawImage` step gets its own texture-to-device transform (scale = box / source size, translation = box origin, as `SWGraphics.drawTexture` builds it, plus a shear of 0.05 and -0.03 pixel per texel that keeps `GENERIC`; step 18 on a centred half-size tile, so REPEAT wraps), defined once for the script and the guard `theDrawImageStepsPaintTheirBoxFromInsideTheTexture` (opaque-replay oracle, 6 mutants fail); the golden is re-captured with no compositor change as `pisces-golden-US-061-pending.bin` (frames 0 to 15 byte-identical; steps 16 to 19 now change 488, 525, 457 and 500 pixels, previously 0, 0, 454 and 0), byte-identical to a capture of the same script from the C of 42a5072596; test only |
| [US-064](US-064-guard-the-empty-net-current-desktop-read-in-gtkglassnative.md) | Guard the empty `_NET_CURRENT_DESKTOP` read in `GtkGlassNative.currentDesktop` | 2026-10-29, uncommitted | `GtkScreenCalls.currentDesktop` (`GtkGlassNative.currentDesktop` before US-071) reads the first item of a `CARDINAL`/32 `_NET_CURRENT_DESKTOP` only when `nitems > 0`: an empty property is desktop 0, as a missing one is, and no byte of Xlib's 1-byte block is read. `GtkScreenNativeTest.anEmptyCurrentDesktopIsDesktopZero` writes one through the US-015 shim, pins the read-back (present, `CARDINAL`, format 32, no item) and expects the first work-area rectangle; on a private Xvfb, with no window manager and under openbox, 11/11 with the ten others as on `2fd2bc900f` and `xprop -root` before == after. Without the guard it failed in 8 of 8 runs and in 80 of 80 diagnostic children, every read a glibc safe-linked free-list pointer, never desktop 0 |
| [US-065](US-065-keep-decorated-gtk-stage-bounds-right-across-state-changes-and-placement.md) | Keep decorated GTK stage bounds right across full screen, maximize and the window manager's placement | 2026-10-30, uncommitted | Fix option 2: a fork-local fix that re-implements openjdk/jfx#2139's ideas rather than merging or backporting it (#2139 still open); D2 kept, with the extents in force. In `glass_window.cpp` a `TITLED` window is reported as its client plus the `_NET_FRAME_EXTENTS` in force; while GDK's state or a live `_NET_WM_STATE` read says maximized or full screen it asks the WM for nothing and stores and caches no extents; it never sends a position read back from X (an extents change asks for the size only, a one-axis `setBounds` sends `CWX` or `CWY` alone); it keeps the requested position until its first map and reads it there; its `centerOnScreen` gravity counts only up to its first floating frame, and an all-zero report before that frame is ignored. `GlassApplication.cpp` hands a disabled window its first map and its property notifications. Measured before and after in WSL: full screen 0,0 1920x1080 under openbox, i3 and Weston (was 1922x1105, 1924x1100, 1996x1177); maximized on Weston 0,0 1920x1040 (was 400x300); restored at 200,200 400x300 under openbox and 168,168 400x300 on Weston (was 0,0 1920x1080 and 136,136 336x236); the startup race 0 of 12 under load under openbox (was 9 of 12) and 0 of 7 on Weston (was 7 of 7). Item 5 unchanged (D3), and Weston's 32 px per one-axis `setBounds` accepted, the same to the pixel as before. `GtkTitledStateBoundsTest` (11) and `GtkWindowPlacementTest` (13), with a no-WM stand-in and a scripted reparenting WM in the shims: 17 of 24 fail on master, every mutant m1-m16 caught; GTK gates unchanged apart from the new classes (228 to 252 tests without a WM, 201 to 225 under openbox), trace goldens unchanged, i3 scenarios green; three independent review rounds, the last with no findings |
| [US-066](US-066-split-dwnative-into-aggregate-classes-of-at-most-900-lines.md) | Split `DWNative` into aggregate classes of at most 900 lines | 2026-10-27, PR #41 | `DWNative` (5,307 lines) became the entry point every peer still calls plus eleven package-private aggregates (`DWFontCalls`, `DWFontFaceCalls`, `DWGeometrySinkObject`, `DWShapingCalls`, `DWAnalysisSinkObject`, `DWTextRendererObject`, `DWInboundTestHooks`, `DWWicCalls`, `DWD2DCalls`, `DWLayouts`, `DWComObjectSupport`): 12 files, the largest 820 lines. Every restricted call, `HANDLES` and `BOUND_SYMBOLS` stay in `DWNative`, class initialization runs only towards `DWNative` and `DWLayouts` (no cycle), the five lazy holders still initialize on first use; descriptors, slots, layouts and IIDs unchanged. On Windows the 84 DirectWrite tests and the full module run (911 test classes) match `e3b8b21e6d`, no crash file (gate); `jni-to-ffm-migration` rule 2 and the `native-font` row carry the 900-line limit; `ffm-reviewer` signed off (AC 8). `windows_x64_build` green (run 40617510930) |
| [US-067](US-067-fix-the-coretext-null-crashes-and-leaks-kept-by-the-ffm-migration.md) | Fix the CoreText NULL crashes and leaks kept by the FFM migration | 2026-11-13, uncommitted, PR pending | Inputs 1-6 have a defined outcome instead of a dead JVM: a run font CoreText did not copy takes no fallback slot, `MacFontFinder.getFont` answers null for a UI font type without a font or a font without a family name, `getCascadeList` an empty list for a zero font, an embedded font CoreGraphics rejects keeps the hard-wired fallbacks, a cascade entry without a display name has a null name, `DFontDecoder` reads format 0 and a null table, and a null `CGRect` or `CGAffineTransform` mirror stays a `NullPointerException` before any downcall, now pinned. Sites 7-10 release what they create: the cascade list's name and URL copies (75.42 heap blocks per call to at most 0.85), the fallback font's extra retain (retain count 2 to 1), the CTFont of `getCascadingInfo` and every create or copy on an exception path (retain deltas 20 or 40 to 0), each in a `finally` or a rethrowing `catch`; the extra retain is deleted. The strike's CTFont window is closed too: `CTFontStrike.createDisposer` and `PrismFontFile.getStrike` release the CTFont when the disposer cannot be made or registered (`OutOfMemoryError`-only, so no behaviour test provokes it; the release in `createDisposer` is pinned as a call site, and deleting it fails the pin check; the rest is review). New `MacFontNullInputTest` (9 tests) and `MacFontReleaseTest` (7), every scenario in a child JVM; 17 of 17 mutants and the pin mutant killed, repeated on the final tree; 9 `MacFontBindings` pins moved with reasons. Goldens and the `CFNative`, `CGNative` and `CTNative` facades unchanged. On the macOS host, now 27.2.1 where the goldens are from 27.0.1: gate 466 to 482 tests (font classes 123 to 139, Glass 343), 0 failures and 8 skipped without `-Djfx.parity.require=true`, the same 8 oracle classes failing with it, before and after; no golden re-captured, and a dump of the five corpora is identical before and on the final tree; a comparison on the golden machine remains for the owner. Module run graphics 26,680 to 26,696, 0 failures; `ffm-reviewer` reviewed the whole diff and repeated the gate on the final tree (approve). Logs, review report and per-fix patches are in `backlog/US-067-evidence/`. Deviations: no commit exists yet (the owner commits, per fix or as one); the NULL sites outside inputs 1-6 went to US-111 |
| [US-068](US-068-keep-the-glass-windows-test-hooks-out-of-the-shipped-glass-dll.md) | Keep the Glass Windows test hooks out of the shipped glass.dll | 2026-11-08, uncommitted, PR pending | Option 1: the 26 `gwin_test_*` hooks and the probes that serve them are compiled only into a test build of glass.dll, the `glass_test` target (`OUTPUT_NAME glass`, CMake option `GLASS_WIN_TEST_HOOKS`, default OFF; compile definition `GLASS_WIN_TEST_HOOKS`), written to `GLASS_TEST_BIN_DIR` = `target/native/test-bin`, which only the javafx.graphics tests load (surefire `java.library.path`: `test-bin`, then `bin`). The release glass.dll (target `glass`, `target/native/bin`) exports 97 names and no `gwin_test_*`, `8daf41d78a`'s 123 minus the 26 (the test build keeps all 123), and its probes are empty inlines. The three facade-bound fire hooks are bound by `WinGlassNativeShim` (`FireHooks`), so `GLASS_WIN_ABI_VERSION` and `WinGlassNative.ABI_VERSION` go 6 → 7; `WinGlassNativeTest` changes only in `BOUND_SYMBOLS` and its ABI literal. `WinReleaseGlassLibraryTest` guards the release DLL in a child JVM (the facade initializes, no hook resolves; mutant proof). On Windows the 24 `test.com.sun.glass.ui.win` classes and the full module run (graphics 26,624 to 26,628 tests, 0 failures, 0 errors) match `8daf41d78a` plus the new class, `WinToolkitShutdownHammerTest` is clean in 10 of 10 runs and the CI replay (`clean verify`) is green; on the release DLL the `tests/system` Win* classes pass (27 of 27), and the robot suite's one error, `JFXPanelScaledTest`, fails the same way on master (filed as US-108). The Maven native build takes +7.7 s |
| [US-069](US-069-stop-gtk-drop-targets-reading-a-refused-conversions-stale-data.md) | Stop GTK drop targets reading a refused conversion's stale data | 2026-11-09, `7ae305cb2a`, PR #54 (its review fixes uncommitted) | `wait_for_selection_data_hook` (`glass_dnd.cpp`) takes the first `GDK_SELECTION_NOTIFY` whose window, selection and target are those of its conversion, and reads `GDK_SELECTION` only when the event names it: a refusal, which GDK hands on with property = target, now yields no data instead of what an earlier conversion left on the window (commit 033187ad90 tested property 0, which GDK never delivers, and matched the window only). `glass_gtk_api.h` contracts updated; exports unchanged. New `GtkDnDRefusedConversionTest` (two child JVMs, one window, three drags, with and without a window manager, 12 tests) fails on master's C in 3 of 3 runs and passes with the fix; mutants: no refusal check and window-only match each killed, never-read killed through the positive reads, the received guard an accepted survivor. `GtkDnDTraceTest`'s `text/html` reads now answer `null`; its JNI goldens stay untouched, with a golden-only rule for exactly their 8 refused-read lines (`GtkTraceRulesTest` pins its reach). Module gate (graphics 26,389 to 26,390, 0 failures) and both GTK display runs (no window manager 256 to 267, openbox 229 to 240, 0 failures) match master plus the new tests (per class). The PR #54 review (2026-11-10) added 2 of the 12 tests, for a read of the mime `GDK_SELECTION`, whose refusal names the property a reply names: `dnd_target_receive_data` now deletes `GDK_SELECTION` before it converts that one target, so the refused read answers `null` (on `7ae305cb2a`'s C: the bytes an earlier conversion left) and an answered one its bytes ([review](US-069-stop-gtk-drop-targets-reading-a-refused-conversions-stale-data.md#pr-54-review-2026-11-10)) |
| [US-070](US-070-split-winglassnative-into-aggregate-classes-of-at-most-900-lines.md) | Split `WinGlassNative` into aggregate classes of at most 900 lines | 2026-10-28, PR #43 | `WinGlassNative` (9,223 lines) became the entry point every peer still calls plus eighteen package-private aggregates (`WinRobotCalls`, `WinCursorCalls`, `WinPreferenceCalls`, `WinApplicationCalls`, `WinTimerCalls`, `WinMenuCalls`, `WinViewCalls`, `WinViewCallbacks`, `WinWindowCalls`, `WinWindowCallbacks`, `WinScreenCalls`, `WinClipboardCalls`, `WinClipboardCallbacks`, `WinAccessibilityCalls`, `WinAccessibleCallbacks`, `WinTextRangeCallbacks`, `WinAccessibilityMarshalling`, `WinCallSupport`): 19 files, the largest 852 lines. Every restricted call and `BOUND_SYMBOLS` stay in `WinGlassNative`, the ABI guard still runs first, its last static initializer still installs the clipboard and drag tables, class initialization has no cycle, and the five lazy holders still initialize on first use; descriptors, exports, layouts and arenas unchanged. `boundSymbols()` now follows `ensureLoaded()`'s order and the 33 constants the peers name moved to their aggregates (both recorded in the story). On Windows the 24 `test.com.sun.glass.ui.win` classes and the full module run (911 test classes) match `e9d25742bc`, no crash file (gate); `jni-to-ffm-migration` rule 2 updated; `ffm-reviewer` signed off (AC 7). `windows_x64_build` green (run 40718289172) |
| [US-071](US-071-split-gtkglassnative-into-aggregate-classes-of-at-most-900-lines.md) | Split `GtkGlassNative` into aggregate classes of at most 900 lines | 2026-10-28, PR #44 (open) | `GtkGlassNative` (6,019 lines) became the entry point every peer still calls, plus fifteen package-private binding classes (`GtkMainLoopCalls`, `GtkPixelsCalls`, `GtkRobotCalls`, `GtkKeyCalls`, `GtkApplicationCalls`, `GtkScreenCalls`, `GtkPreferenceCalls`, `GtkClipboardCalls`, `GtkClipboardReadCalls`, `GtkDialogCalls`, `GtkCallbackTables`, `GtkWindowCalls`, `GtkViewCalls`, `GtkDndCalls`, `GtkCallbackTargets`) and the library query's holder `GtkLibraryLoader`: 17 files, the largest 744 lines. `link()` initializes the fifteen in a fixed order, so every system symbol still resolves there, and a missing one still fails the toolkit start with an `UnsatisfiedLinkError` that names it. `Glass` (now nested in `GtkCallbackTables`) and the nested `Loader` stay independent holders, every restricted call stays in `GtkGlassNative`, class initialization has no cycle, and the census is unchanged (142 entries, in `SYMBOLS` order). Descriptors, exports, layouts, arenas and the `ggtk` ABI are unchanged. The callback install methods now lock `GtkCallbackTables` (recorded in the story). In WSL the module run with `DISPLAY` unset (911 test classes) and the 36 GTK display classes, with no window manager and under openbox, match `14d7e2a18d` with no crash file (gate); `jni-to-ffm-migration` rule 2 updated; `ffm-reviewer` signed off (AC 7). `linux_x64_build` and `linux_aarch64_build` green, GTK display step included (run 40875501864) |
| [US-072](US-072-keep-the-gtk-display-harness-from-connecting-inside-xvfbs-reset-window.md) | Keep the GTK display harness from connecting inside Xvfb's reset window | 2026-11-11, uncommitted, PR pending | Reproduced in WSL under CPU load: 18 of 1000 rounds of the script's `start_xvfb` and `start_openbox` failed with "openbox did not start" (a NULL display; 0 of 500 idle), every attributed hit at the close of `start_openbox`'s first `xprop` poll. `run-gtk-display-tests.sh` now keeps one X client, an `xprop -root -spy RESOURCE_MANAGER` holder, connected from `start_xvfb`'s readiness check until openbox is up, and stops it before Maven: the openbox pass no longer resets before its tests, and the run without a window manager still resets whenever its last child closes. No `-noreset`, no sleeps, no retries; `submit.yml` unchanged. Under the same load the fixed script failed 0 of 1000 rounds, and a mutant that stops the holder before `start_openbox` 20 of 1000. Both GTK display passes equal the before gate on `2a1271703b` per class and per method (no window manager 39 classes / 269 tests, openbox 39 / 242, 0 failures; per class), and with the fix the counts match the stated baseline on `692cd3a432` (38 / 255 and 38 / 228; per class) |
| [US-073](US-073-make-the-system-menu-bar-click-test-pass-with-a-non-auto-hide-context-menu.md) | Make the system menu bar click test pass with a non-auto-hide context menu | 2026-11-26, uncommitted, PR pending | Ported openjdk/jfx `81cc4f0b50` (JDK-8393642): Glass mac's `menuWillOpen:` no longer calls `cancelTrackingWithoutAnimation` and returns when a window holds the grab (it keeps `_resetGrab`), and `SystemMenuBarClickEventsTest` is upstream's file but for one fork divergence (review finding T6): a class-level `@EnabledOnOs(OS.MAC)` gate replaces upstream's per-method macOS assumption, which ran only after `@BeforeAll` had launched the app and waited for stage focus, so a non-macOS host whose stage never took focus would fail the class at launch ("Failed to launch FX application ... within 15 sec.") instead of skipping it; the macOS re-run with the gate is owner-run and deferred. Cause: in the class run the context menu was never shown, not late: after the first method's menu-bar click the application gets no more mouse events, because of that call, which JDK-8372398 added (JDK-8393642: from macOS 27 it leaves AppKit in its menu tracking loop); the one method-alone failure of 2026-11-05 did not reproduce (3 of 3 runs, 5 of 5 diagnostic variants), its cause not established. On macOS 27.2.1, 2026-11-26: the method alone 20 of 20 and the class 20 of 20 (0.514 to 0.540 s per method); a mutant that never shows the menu fails both methods at the right-click check; the full `-DFULL_TEST=true -DUSE_ROBOT=true` run 0 failures in every module, `tests/system` 1,099 tests, 0 failures against 1 (this class) in the owner's run of 2026-11-25, every other class unchanged (per class). Criterion 5 amended by the owner to upstream's macOS 27 behaviour: a menu-bar click closes an auto-hide context menu and also opens the system menu; the change has no version check, so the consumed click is given up on every macOS version, including those where the hang does not occur (upstream's reviewer: it does not reproduce on macOS 26.7); restoring the second click is upstream's JDK-8394185 (evidence) |
| [US-074](US-074-remove-the-last-of-jni-from-the-fork.md) | Remove the last of JNI from the fork | 2026-11-11, uncommitted, PR pending | No native target reaches `jni.h` and no build step runs `javac -h`: Windows glass reads 10 JNI-free constants headers in `native-glass/win/constants` with every macro javac wrote at `0fd9cb0ef5` (new `WinConstantsHeaderTest`), the graphics CMake has no `JDK_HOME`/`HEADERS_DIR`, and 623 `@Native` left 38 files. `NativeLibLoader` opens every library with `SymbolLookup.libraryLookup(Path, Arena.global())` instead of `System.load*`, so no `JNI_OnLoad` runs; search order, locations, texts and lifetime kept, with five accepted deviations (a refused library's OS reason among them); 32 `loaderLookup()` calls use its `loadedLibrariesLookup()`, `DumpRenderTree` its `loadLibraryFromSystemPaths`. Deleted: the iOS/Android skins (8 `native` methods), `CodeGeneratorJava.pm`, `extract-jni.pl`, `dom-cpp-to-ffm.pl`, both `verify-no-jni.pl` and the `JniStringOracle*` test, replaced by `JniStringCodecGoldenTest` against a golden captured from it; `bridge/jni`, `jfxmedia/jni`, `JNI_EXPAND` and the other JNI names renamed. `buildtools/CheckNoJni.java` (self-test 133 of 133, CI job `no_jni_check`) fails the build on any of it. glass.dll exports unchanged; in an A/B build every graphics DLL and `.so` is byte-identical, media exports identical. Windows full build 1,233 → 1,235 classes, 42,333 → 42,355 tests, 0 failures, differing only by the four test classes (per class); Linux, macOS and `build-webkit.yml` pending CI |
| [US-075](US-075-ship-every-modules-legal-notices-in-its-jar-and-the-sdk.md) | Ship every module's legal notices in its jar and the SDK | 2026-11-13, `c696e6d3f6`, PR #60 (open) | The 13 notices of 4 modules (`javafx.graphics`, `javafx.media`, `javafx.web`, `jfx.incubator.richtext`) ship byte for byte at `META-INF/legal/` in their module jars, copied at prepare-package by the root profile `legal-notices`, which a `src/main/legal` directory alone activates; the SDK has `legal/` with the 3 root files (`LICENSE`, `ADDITIONAL_LICENSE_INFO`, `ASSEMBLY_EXCEPTION`) and `legal/<module>/` per module, in the Java-only and the native SDK alike. `buildtools/CheckLegalNotices.java` checks every such jar and the SDK at verify: plants P1-P5 (a notice missing or changed in a jar, missing from the SDK or its jar, added after the build) fail it and pass once removed, and P6 shows a module that adds `src/main/legal` shipping and checking its notice with no build change. AC 5: the jar diff of every jar shows only the added entries (jar diff); per-class tests identical before and after (1,237 classes, 42,371 tests, 0 failures, per class). WSL Linux CI-equivalent build with natives and tests passed on the final tree (1,237 classes, 42,115 tests, 0 failures), and so did the Verification loop. `game-toolkit-reviewer` signed off after three general-purpose stand-in reviews, first as a general-purpose agent following its definition, then as the registered agent type on the final tree; all seven findings fixed (evidence). AC 7: `submit.yml` run 43544611258 is green in all five build jobs, macOS x64 and aarch64 included; every leg ran the four jar checks and the SDK check (13 notices of 4 modules, 11 jars), and its test totals per module equal those of run 43431958537 on the base tree. The Copilot review of PR #60 recommended approval with no open issues |
| [US-108](US-108-make-the-jfxpanel-scaled-test-pass-on-windows.md) | Make the JFXPanel scaled test pass on Windows | 2026-11-14, uncommitted, PR pending | A race in the test, which upstream OpenJFX 27 has too; `JFXPanel` is unchanged. The test set its scene with `Platform.runLater` and resized on a fixed 500 ms timer. The back buffer exists only once the embedded scene is attached, so a scene that arrived after the resize to 101 left `pixelsIm` null at the latch. A harness showed this in 18 of 18 runs; the fork attaches the scene in about 900 ms against upstream's 500, so it lost the race under CPU load (upstream lost it under heavier load). The fix is test-only, as JDK-8324239 (`876d5356bd`) did for `JFXPanelHiDPITest`: `Util.runAndWait` for the scene and `invokeAndWait` for the frame before the resizes, and the buffer is read and copied on the EDT. The unchanged test failed at :92 under load; the fixed one passed under the same load and in 20 of 20 consecutive runs, and the robot suite gave 91 classes, 491 tests, 0 failures, 0 errors and 42 skipped (US-068's run, the same apart from this class's 1 error). A `Math.round` mutant fails the size check (127 vs 126) |
| [US-109](US-109-stop-gtk-drop-targets-reading-past-a-16-or-32-bit-reply.md) | Stop GTK drop targets reading past a 16- or 32-bit-format reply | 2026-11-11, uncommitted, PR pending | No fix or bug upstream: openjdk/jfx `master` `c6413874cc` still multiplies, no JBS issue and no open PR is about it. `dnd_target_get_raw` and `dnd_target_get_image` (`glass_dnd.cpp`) take the answer of `gdk_selection_property_get` as the byte count it already is (`_gdk_x11_display_get_selection_property` counts a 16-bit item as a `short` and a 32-bit one as a `long`) instead of multiplying it by format / 8 again, as commit 033187ad90 did; `glass_gtk_api.h` and the exports unchanged, no new compiler diagnostic. New `GtkDnDWideFormatReplyTest` (two child JVMs, one drag, 5 tests, with and without a window manager): the source offers a 3-byte format-8 control and 300,000 bytes of a custom mime, which GTK answers with an INCR header (type INCR, format 32, one item), and the target's read of it answers exactly that item's 8 bytes. On master's C that read answered 32 bytes in 4 of 4 runs, the header, the block's NUL and 23 bytes past the block, while the control passed; with the fix every run passed. The image half is checked by reading only: a Glass source sends images with format 8, and an INCR header decodes to no image either way. Module gate (jslc 196, base 5,510, graphics 26,390, 0 failures) equal per method; both GTK display runs (no window manager 269 to 274, openbox 242 to 247, 0 failures) differ only by the new class (per class) |
| [US-110](US-110-make-the-dialog-and-table-robot-tests-pass-on-linux.md) | Make the dialog and table robot tests pass on Linux | 2026-11-12, uncommitted, PR pending | All four fail only under a window manager (openbox), none without. `DialogWithOwnerSizingTest` (79 vs 175) was a fork regression from `f8c745cf2c` (US-065), which gives a disabled window's property events to glass: `process_configure` now takes the size to come back to only after the first map (F1, JDK-8232811's rule, dropped upstream by JDK-8260528). `DialogRepeatedShowHideTest` (304 vs 300) was upstream's, exposed by openbox's frame estimate (1,1,20,5, then 1,1,20,1): `update_frame_extents` keeps a content size a content size (F2). Each fix reverted alone fails its test 3 of 3; the GTK display gate is unchanged per method. The TableView and TreeTableView `ResizeColumnToFitContentTest` lost the second click of a zero-gap double click to the window manager's click-to-focus grab (upstream JDK-8322019): they double-click with `Util.doubleClick`, mutant-proved. Full robot run under openbox: exactly the four changed, failing to passing; only the 25 WebKit tests fail |
| [US-111](US-111-fix-the-coretext-null-inputs-and-font-layer-defects-us-067-left.md) | Fix the CoreText NULL inputs and font-layer defects US-067 left | 2026-11-22, `40e53db102` and its CI fix `f352c6008c`, PR #71 (open; the further fixes `e1de3dd32a`, the fixes of its Copilot review `2c9003b909`, its Windows and Linux round uncommitted, 2026-11-24) | Sites 1-7 hand none of their listed inputs to CoreFoundation, CoreGraphics or CoreText as NULL or 0: `MacFontFinder.getSystemFontSize` answers 13 without a UI font, `getFontData` still lists the two UI fonts without a collection, `DFontDecoder.createCTFont` answers 0 for a NULL collection, array or name and `releaseCTFont(0)` does nothing, `OS.copyOfUIFont` answers 0 for a NULL UI font and `CTFontCreateWithName` 0 for name 0, reading a name's length before its first character, `getAdvanceFromPlatform` answers 0 for a strike without a CTFont, `CTGlyph.createContext` returns 0 before any setter, and NULL language codes give an empty cascade list without asking CoreText. `CTGlyphLayout.layout` fences the strike until its line holds the font; `CTGlyph.getCachedContext` runs under one lock, so two racing threads create one context (2 before); `getCascadeList` runs in an autorelease pool, through a fourth facade `ObjCNative` (`objc_autoreleasePoolPush` and `objc_autoreleasePoolPop` of `/usr/lib/libobjc.A.dylib`, the token a `JAVA_LONG`), a virtual caller's on one daemon platform thread, "JavaFX font cascade list", whose task holds its own reference to the lent font, so on this Mac a call left at most 0.283 heap blocks in the runs before the further fixes and at most 0.49 in their final evidence, where a call without the pool left about 230 (bound 10); `GlyfTableBounds.boundingBox` leaves a short `bb` as it was, and the `NullPointerException` of `OS.rect` for a null half is pinned; a fallback face its file lacks is looked up once while the run font reports that file in the same version (5 times in 5 layouts before), each file of a name apart, by one caller per name and file at a time, and a file that cannot be read is looked at again at the next layout. 24 new tests in five classes, each crashing input in a child JVM; 27 of 29 mutants killed on the finished tree and 3 of 3 after the test-only fixes. CI run 45022158121 failed the two heap checks and the fallback-face check of `MacFontReleaseTest` on the macOS 15 runners, where a region without a pool leaves about 6 heap blocks per call and the fallback-face child printed no lookup of a face missing from its file; the test-only CI fix, committed as `f352c6008c`, has them decide through `MacFontReleaseVerdict`, skipped with their figures where the machine cannot show the defect or failing under `-Djfx.parity.require=true`, and adds 36 tests (`MacFontReleaseVerdictTest`, four class-file checks of site 10, a pool-drain and a face-memory test); 12 of 12 mutants killed on its tree; CI run 45041254324 on it is green on all six jobs, the three checks skipping on macOS 15, where a pooled platform call leaves about 4 blocks per call. The fourteen defects and test gaps found beside the twelve sites while PR 71 was reviewed are fixed in the PR, in six clusters: two threads drawing into `CTGlyph`'s cached bitmap, its bounds published unfilled, its cached context keeping the first strike's sub-pixel options and an always-true condition; the font maps and the native-fallback slots written without the lock their readers take, and every racing caller looking a composite's fallbacks up; a thread per virtual cascade-list caller and the error edges of its wait; NULL handed to CoreFoundation and CoreText at entry points only shims reach and from unchecked one-time objects; a run whose font takes no slot drawn with another font (8a missing-glyph codes, 8b "no slot" from the composite on every platform, 8c upstream's `ns + 1` corrected); an unreadable font file remembered as a missing face; guards no test pinned (R3 and R5 now killed); checks that depended on the machine, which now decide with pool tokens and a missing face the child makes, and compare only figures taken the same way or through a verdict; and, beside them, the glyph, bounds and slot caches, mappers, metrics and name lists of the font layer, now concurrent maps, volatile fields read once, filled copies or the factory's monitor. 116 more tests in 7 new classes and 5 changed ones, 81 of them on any OS and 35 on macOS only, with 23 new `FontCallOrder` class-file helpers; 253 of 256 mutants killed (L2 does not compile, C-M14 is equivalent, M8g7 cannot be pinned) and 14 of US-111's rows again, each on this Mac on its cluster's tree or a later one; ten rows of `mac-layout-golden.txt` edited by hand for item 8, derived from this Mac's dump; `directwrite-metrics-golden.txt` (609 rows) re-captured on its Windows machine on 2026-11-24; `CFNative.CFRetain` public, `CGNative` and `CTNative` unchanged, and every `MacFontBindings` pin unchanged until the Copilot review moved one call site (`OS.CTFontCopyURLAttribute` from `CTFactory` to `CTGlyphLayout`). On the macOS host, 27.2.1 where the goldens are from 27.0.1: gate 482 to 506 tests (font classes 139 to 163, Glass 343), 0 failures and 8 skipped without `-Djfx.parity.require=true`, the same 8 oracle classes failing with it, before and after; module run graphics 26,696 to 26,720, 26,756 with the CI fix and 26,872 with the further fixes, 0 failures; the font package with the further fixes 548 / 0 / 0 / 156, ten verdicts PROVED, and with the flag only the 5 golden comparisons fail; the five corpora are identical before and after except the ten rows of item 8. The Copilot review of PR 71 found six defects, F1 to F6, and an independent review of their fixes four more, R1 to R4: the cascade-list thread survives what a task throws, a virtual caller that cannot start it gets the error, and a task's reference to the font is released if the queue refuses it (F2 to F4); a missing fallback face is looked up again once its file changes or another file of its name is reported, and callers that need it in one file at once share one lookup (F5, F6, R2); a registration that throws ends its own lookup and the ending makes no key, and the order that publishes a lookup's outcome is pinned (R1, R3); the DirectWrite rows of 8c cannot be derived by hand (F1); 15 more tests (`MacFontReleaseTest` 3, `MacFontBindingStructureTest` 4 with one replaced, the new `MacFontFaceLookupTest` 8), 6 new `FontCallOrder` helpers (4 for F2 to F4, `assertWrittenBefore` for R3 and `assertFieldStoredOnlyIn` beside it); all 61 mutant rows of the review killed (25 for F2 to F4, 27 for F5 and F6, 6 for R1 to R3, 3 for the publication pins), and 8 of them again on later code, each on this Mac; on the final tree the font package 563 / 0 / 0 / 156 (563 / 5 / 0 / 151 with the flag, the 5 golden classes), the gate 673 / 0 / 0 / 8 (673 / 8 / 0 / 0, the same 8 classes) and the module run graphics 26,887 / 0 / 0 / 858, every test class run; no golden file changed. On CI run 45278364431 of `e1de3dd32a` all ten verdict lines were `PROVED` on both macOS 15 runners; the review's fixes, committed as `2c9003b909`, passed CI run 45456403357 on all six jobs, its Linux x64 job on the second attempt, with all twelve verdict lines `PROVED` on both macOS 15 runners. Slice reviews by `ffm-reviewer`, three final reviews with a sceptic on each must and should, a confirmation that repeated the gate both ways (pass); each cluster's design critiqued and its implementation reviewed until accepted, and a whole-tree review, a fix round and a confirming review (mergeable), then a hardening round of the tests with its own fix round (accept). Logs are in the session scratch directories the story names. CI run 45278364431 of `e1de3dd32a` passed Windows x64, Linux aarch64, both macOS jobs and No JNI, and its Linux x64 job stopped at an HTTP 429 of Maven Central before it compiled anything. On 2026-11-24, on Windows 10 19045.6466, the golden's capture machine, and in WSL, in five test files that are uncommitted, with no production change: `directwrite-metrics-golden.txt` captured again with the lines that say why written by `DirectWriteMetricsGoldenTest.header()`, exactly its 609 rows moving (9 layout rows, 300 glyph rows out and 300 in) and every new row holding the data of the font its code names, each further capture of the same header byte-identical; 13 macOS slot tests enabled on Windows and Linux (`aNewFallbackResourceLooksUpItsFallbacksOnceForThreeCallers` on Linux only, its control measured failing on Windows; `aFontACompositeAddsIsAtTheSlotItIsHandedOutFor` on Ahem off macOS); the new `DirectWriteNoSlotLayoutTest` (Windows: a run whose fallback font gets no slot is laid out with glyph 0) and `PrismFontFactoryTest.aCompositeWhoseFallbackListIsFullHasNoSlotForAnotherFont` (macOS, Windows and Linux: a full composite answers -1). Gates: Windows graphics 26,875 / 1 / 0 / 917 (the 609 differences) at `2c9003b909` to 26,877 / 0 / 0 / 905 with `-Djfx.parity.require=true`; WSL 26,428 / 0 / 0 / 967 at master, 26,619 / 0 / 0 / 1,034 at `2c9003b909` and 26,621 / 0 / 0 / 1,022 with the round; a dump of the four Linux font corpora unchanged between master and `2c9003b909` (4,704 rows), though it reaches neither 8b's -1 nor 8c's `ns`. Mutants: the implementer's 9 killed on both systems; of the review's 77 runs DW1 (`renderShape`'s guard) survives on Windows, FB3 to FB5 survived on Linux until the cap test killed them, and LF3 to LF5 (`LogicalFont`'s cap) survive on both; the rework's and both confirmations' rows all killed. Review must_fix (R1, the header's cause, reworded and captured again), confirmation pass, a second confirmation pass; R6 filed as US-130. Both defects are still on upstream `master` `94168cc51f`, with no issue or PR found; a draft report is kept outside the repository. Nothing of the round ran on macOS. Remains for the owner: the commit of that round and a CI run of it, read for the slot tests' counts in every job and both macOS jobs' verdict lines; the reply to Copilot's F1 thread and the PR description's scope (drafts kept outside the repository); the upstream report; the robot and `tests/system` runs and the comparison on the macOS golden machine |

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
- it can be built and tested here: on Windows and in WSL, and on the arm64 macOS host, `javafx.graphics` since
  2026-10-24 (US-003) and the Rust slices of `javafx.media` since 2026-11-21 (US-028; x86_64 code runs only in the
  `macos-15-intel` CI job);
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
  function group behind `JFX_RUST`. The slice's C is deleted in its own commit, on all three platforms, once the
  slice is accepted on Windows, WSL Linux and macOS (US-027, US-028); macOS no longer waits for US-028. A file that
  only one platform compiles is deleted once its slice is accepted on that platform. On macOS a slice is accepted on
  the arm64 macOS host and, for x86_64, by the `macos-15-intel` CI job, as x86_64 code cannot run on that host. In
  `jfxmedia`, C++ that defines a name that `libjfxmedia_avf` imports (13 today, listed in
  US-028's gate), or that jfxmedia's own macOS sources
  reference (`platform/osx/OSXPlatform.mm`, `OSXMediaPlayer.mm`, `CVVideoFrame.mm`), cannot leave the macOS build
  while they reference it. No story ports the AVF layer (`platform/osx/avf`) or those three files.
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
- **P9 Exports.** The export list stays identical in every slice (`dumpbin /exports`, `nm -D --defined-only`,
  `nm -gU`). On Linux, one exception is allowed: symbols that were exported only through default visibility, and that
  no consumer resolves, may disappear if the PR lists them. They are the names hidden by the version script that a
  library's first slice adds (generated from its header, applied with Rust on and off; US-027 lists its 1,244 in
  `US-027-linux-hidden-symbols.txt`), and symbols of C that a slice took out of the build or deleted. On macOS nothing
  is hidden (US-028): with Rust on, the library exports exactly the names of its C build (`-DJFX_RUST=false`), less
  the listed names of C that a slice took out of the build, none of them a name `libjfxmedia_avf` resolves (P2).

Test media for the media stories is small and generated by a command recorded next to the file, with a provenance
note. No downloaded or third-party media is committed.

### Verdict per native library (survey of 2026-09-30)

| Native code | Lines (`wc -l`) | Verdict | Why | Stories |
| --- | --- | --- | --- | --- |
| Glass Windows: WinRT preferences | 755 (981 at `5a709c189d`) | RUST, implemented (US-029, 2026-11-26, committed as `7c38bdc73a`, review fixes as `fcfc41cf48`): the `glass-win` crate, the C++ deleted | Java-side COM already rejected in the header; WinRT sinks capture a raw `this` and are never unregistered (US-039 part 2 fixed both in the C++ first) | US-039 → US-029 |
| Glass Windows: COM servers (clipboard, DnD, UIA) | 4,507 | RUST | Inbound COM objects that other processes hold; hand-written refcounts and `delete this` | US-039 → US-030 |
| Glass Windows: toolkit core (loop, WndProcs, IME, keys, screen, menu) | 9,306 | RUST, last | OS-CALL and native state; toolkit-HWND race, per-HWND lifetimes; "why not Java" rests on unmeasured upcall volume | US-039, US-049 → US-031 (US-052 and US-053 should precede) |
| Glass Windows: robot capture; pre-Vista file dialogs | in the core row; 384 | JAVA / delete | A stateless GDI sequence; dead on Windows 10 and later | US-049 |
| Glass Windows: COM file dialogs, `OleUtils.h` | 453 + 209 | DEFER | A COM client on `_com_ptr_t`, no defect found. Trigger: the last C++ left in glass.dll, or a test that drives the dialogs | — |
| Glass GTK: screencast (portal + PipeWire) | 3,051 | BLOCKED, then RUST | Callbacks on PipeWire's thread and compositor-described buffers; WSL has no PipeWire, portal or D-Bus yet | US-038, US-042 → US-037 |
| Glass GTK: window, events, IME, DnD, keys, cursor, screen | 7,779 | DEFER | gtk-rs's GTK 3 crates are archived, so there is no safe binding. Trigger: a GTK 4 Glass story | — |
| Glass macOS, including accessibility | 19,728 | BLOCKED | The survey was made before this project had a macOS host, and no Rust-port survey has covered the library since. A macOS host has built and tested `javafx.graphics` since 2026-10-24 (US-003), and US-001b moved the library from JNI to FFM (implemented 2026-11-05, uncommitted; 195 `gmac_*` exports, no `Java_`). Its Java-replacement candidates stay native behind `gmac_*` exports until the owner's A/B passes (PB2 follow-ups, gate) | US-001b |
| Monocle EGL headers; Monocle test stub | 179 + 273 | KEEP | Headers only; a test double of a vendor contract | — |
| prism_d3d | 7,545 C++ + 709 HLSL | JAVA (conditional; fallback KEEP) | Every OS entry is a COM slot or a plain export; Java also removes the two-language reset protocol; needs a readback corpus first | US-043 → US-047 |
| prism_es2 | 5,804 owned + 17,501 Khronos headers | JAVA (needs a ruling) | The GL calls are WRAPPERs that FFM binds by address, contrary to the ES2 audit; macOS part BLOCKED (surveyed before this project had a macOS host; no Rust-port survey has covered it since). A macOS host has built and tested `javafx.graphics` since 2026-10-24 (US-003). There `libprism_es2.dylib` is FFM (`es2_*` exports, no `Java_`, no `JNI_OnLoad`, no `native` method in `com.sun.prism.es2`), US-001b changed none of its sources or exports, and `ES2NativeTest`'s symbol, ABI and layout checks pass against it (per-class results, gate). US-048 covers Windows and Linux and leaves the library on macOS. The headers are KEEP | US-048 |
| prism_sw (Pisces) | 5,240 | JAVA, done | The plan of record in `prism_sw_api.h:54-62`; US-046 ported it and deleted the C (5,342 lines by then) | US-046 |
| prism_mtl | 5,737 | BLOCKED | The survey was made before this project had a macOS host, and no Rust-port survey has covered the library since. A macOS host has built and tested `javafx.graphics` since 2026-10-24 (US-003), and US-001b moved the library from JNI to FFM (implemented 2026-11-05, uncommitted; 53 `mtl_*` exports, no `Java_`). US-001b's `jni-auditor` triage gave each function an OS-CALL / WRAPPER / PURE / PURE-HOT verdict | US-001b |
| iio: libjpeg encoder and unreachable decoder modules | 15,433 | delete | Never executed by `iio_api.c`: the encoder, the IFAST and float IDCTs, the colour quantizers and the transcoder. `jdmerge.c` is live (block sizes 9 to 16) and is in the next row | US-044 |
| iio: used libjpeg decoder + `iio_api.c` | 19,339 + 1,140 | JAVA (conditional; Rust fallback) | Integer-only, `jdmerge.c` included, so a faithful port is as provable in Java as in Rust; memory safety on web images; the maintainer rules on forking IJG. The merged upsampler has no test yet (US-054) | US-044, US-054 → US-045 |
| Fonts (macOS: CoreText, DFont) | 1,685 | JAVA, done | US-003 bound CoreFoundation, CoreGraphics and CoreText from Java and deleted the C (2026-10-24) | US-003 |
| jfxmedia: frames, colour conversion, spectrum, equalizer, logger | 5,224 | RUST | Java cannot bind gstreamer-lite on Windows (ordinal-only exports); refcount-dense; `ColorConverter` is PURE-HOT | US-035 |
| jfxmedia: GStreamer pipeline core | 9,544 | RUST | GStreamer callbacks on foreign threads; a hand-rolled teardown handshake; racy fields | US-040 → US-036 |
| jfxmedia: `platform/osx` (AVFoundation) | 4,407 | BLOCKED | The survey was made before this project had a macOS host, and no Rust-port survey has covered it since. A macOS host has built and tested `javafx.graphics` since 2026-10-24 (US-003). The `native-mac` profile of `javafx.media` builds it into `libjfxmedia` and `libjfxmedia_avf`, and it is FFM: Java reaches it through `JfxMediaNative` (`jfxm_osx_platform_init`, and `jfxm_media_create` with `JFXM_BACKEND_AVF`), no `platform/osx` source defines a `JNIEXPORT` function and no `javafx.media` class declares a `native` method. Both macOS CI jobs build it and fail when `libjfxmedia_avf.dylib` is missing | — |
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
   - the C/C++ fixes: US-040 (✅ done 2026-11-15, uncommitted), US-041, US-043, US-051, US-052 and US-053 (US-039
     landed in PR #37);
   - the deletions and Java routes: US-044, US-049 part 2, US-047 (after US-043);
   - the tests for libjpeg's merged upsampler: US-054 part 1;
   - the screencast test bed US-038, then US-042, whose regression tests run on it.
2. **US-027**, the toolchain (🔶 merged 2026-11-17 as `f5e137e15e`, PR #66; rustup is installed on Windows, in WSL
   and on the macOS host).
3. **Rust ports:**
   - Glass Windows: US-029 (🔷 implemented 2026-11-26, committed as `7c38bdc73a`, its review fixes as `fcfc41cf48`;
     US-133 filed from its code review) → US-030 → US-031 (US-052, US-053 and US-132 before its slice 3).
     Windows-only, so only the Windows acceptance applies to their C (P2).
   - Media plugins: US-032 → US-033, plus US-034 (after US-051) alongside US-033.
   - jfxmedia: US-035 → US-036.
   - Screencast: US-037.
   - US-028 (🔷 implemented 2026-11-21, uncommitted) links the media slices on macOS, so their C is deleted there too
     (P2).
4. **After the maintainer's rulings:** US-045 (forking IJG), US-048 (vs the ES2 audit), US-049 part 1 (vs the ABI
   header's "stays native"), US-050 (AAC/MP3 parity).
   US-054 part 2 follows slice 1 of US-045.

## Game toolkit

The README's game-toolkit goal, planned on 2026-11-07. Every story keeps these owner rulings:
- 3D physics is a firm goal: "I do want to have a 3D engine, even if we build it ourselves over time". A failed gate
  changes how 3D is reached, never whether.
- "Bring your own engine" means physics engines only. The renderer stays JavaFX.
- Game audio is a set of requirements on the planned javafx.media refactor (US-092), not a toolkit module.

The stories state requirements only. How to meet them is in three skills:
- `.claude/skills/jfx-game-physics-port/SKILL.md`: the port, the parity oracle, the speed gate and upstream tracking.
- `.claude/skills/jfx-game-toolkit/SKILL.md`: the modules, the engine contract, the loop and input.
- `.claude/skills/jfx-game-testing/SKILL.md`: goldens, determinism and allocation gates, the conformance kit, loop
  tests and what only the owner runs.

The agents are:
- `physics-oracle-engineer`, `godot-physics-porter` and `godot-physics-port-reviewer` for the port;
- `game-toolkit-engineer` and `game-toolkit-reviewer` for the toolkit;
- `game-tester` for its tests;
- `game-developer`, who builds the samples and reports on the API from a game's point of view.

| ID | Story | Blocked by |
| --- | --- | --- |
| US-075 | Legal notices in every jar and the SDK | — |
| US-076 | Per-step goldens from a pinned Godot build (the parity oracle) | — |
| US-077 | The port's speed against Godot, measured before the port is written | US-076 |
| US-078 | Fixed-step loop, interpolation, per-tick input | — |
| US-079 | Built-in 2D engine, ported from Godot, bit-exact | US-075, US-076, US-077 |
| US-080 | 2D engine contract and conformance kit | US-079 |
| US-081 | 2D character controller and collider tools | US-075, US-076, US-079, US-080 |
| US-082 | Playable 2D preview | US-078, US-080, US-081 |
| US-083 | A native 2D engine through FFM | US-080 |
| US-084 | jlink image and jpackage installer | US-075, US-082 |
| US-085 | 3D engine contract and a native 3D engine | US-080 |
| US-086 | Built-in 3D rigid-body engine, ported from Godot, bit-exact | US-075, US-076, US-077, US-079, US-085 |
| US-087 | Playable 3D preview | US-076, US-078, US-085, US-086 |
| US-088 | Gamepads | US-078 |
| US-089 | Pointer capture and physical keys (Glass) | — |
| US-090 | 3D rendering a game needs (Prism) | — |
| US-091 | glTF 2.0 models | US-085; US-090 for skinned meshes |
| US-092 | Low-latency mixed sound effects (media refactor) | — |
| US-093 | Physics thread and bit-exact snapshots | US-078, US-079, US-080 |
| US-094 | Re-pin to a new Godot release | US-079; US-086 for 3D |
| US-095 | Grow the 3D engine past its Godot origin | US-085, US-086 |

### Order

1. **Free to start:**
   - US-075 (✅ done 2026-11-13, PR #60), which ships the 13 notices that reached no jar before it;
   - US-076 and US-078;
   - US-089, US-090 and US-092, which belong to Glass, Prism and media.
2. **The 2D route:** US-076 → US-077 (the speed gate) → US-079 → US-080 → US-081 → US-082, then US-083 and US-084.
3. **3D:**
   - US-085 starts once the 2D contract exists.
   - US-086 starts after US-077's gate has passed and US-079 matches exactly.
   - US-087 and US-095 follow.
4. **After their blockers:** US-088, US-091, US-093 and US-094.

## Media formats

The README's goal "Extend the media API to support more formats", planned on 2026-11-07. Every story keeps these owner
rulings:
- **MP3 in Java fills gaps only.** A pure-Java MP3 decoder plays MP3 only where no OS decoder works. Wherever an OS
  decoder plays MP3 today, the output stays bit-for-bit as it is.
- **MP3+G opens from an explicit pair.** The application creates the `Media` from a `videoUri` (the `.cdg`) and an
  `audioUri`, carried by one public record; the module never scans a directory or an archive for a companion. An
  MP3+G `.zip` is reached through two `jar:` URIs. Opening the `.mp3` alone behaves as today. (Revised 2026-11-07;
  the earlier ruling opened MP3+G from the `.cdg` or the `.zip` alone.)
- **Permissive licences only in the jars.** Third-party Java code inside the jars is MIT, BSD, zlib, CC0, BSL-1.0 or
  similar; anything else is written in-house from the specification. Native code keeps port rule P8.
- **One audio output.** US-092, the game toolkit's sound effects, belongs to this epic. Java-decoded audio shares one
  output path with it.

How to meet them is in `.claude/skills/jfx-media-formats/SKILL.md`, with the agents `media-format-engineer` and
`media-format-reviewer`.

| ID | Story | Blocked by |
| --- | --- | --- |
| US-092 | Low-latency mixed sound effects | — |
| US-096 | Clear errors for unplayable media; AudioClip survives a failed play | — |
| US-097 | Every WAV and AIFF the bundled parsers decode | — |
| US-098 | A-law, µ-law and ADPCM | US-097 |
| US-099 | MP3, AAC and H.264 on Linux with any supported ffmpeg | — |
| US-100 | Media decoded in Java | US-096 |
| US-101 | MP3 where no OS decoder works | US-096, US-100 |
| US-102 | CD+G karaoke (MP3+G) | US-096, US-100 |
| US-103 | FLAC | US-100 |
| US-104 | Ogg Opus and Ogg Vorbis | US-100 |
| US-105 | MIDI and KAR | US-100 |
| US-106 | SRT and WebVTT subtitles | — |
| US-107 | Matroska and WebM | US-040 |

### Order

1. **US-096 first,** because every new format makes its failures more common. Then US-097 and US-099. US-106 is free
   to start.
2. **The Java path:** US-100 lands with its first format. US-101, US-102, US-103, US-104 and US-105 follow.
3. **Native additions** (US-098 if it is done natively, and US-107) land after US-040. They keep US-036's "unsupported
   content" scenario on content that stays unsupported.
4. **US-092 and US-100 settle the one audio output together.**
