# US-001b — Migrate `glass/mac` and `prism_mtl` from JNI to FFM (macOS)

| Field | Value |
| --- | --- |
| **Status** | 🔷 **Implemented** (2026-11-05), uncommitted in the working tree on `1a6788cc4d`. Both libraries are on FFM: `libglass.dylib` and `libprism_mtl.dylib` export no `Java_` function and no `JNI_OnLoad`, and no class of `com.sun.glass.ui.mac` or `com.sun.prism.mtl` declares a `native` method. Owner-run and pending: AC 6's `tests/system` run, AC 8's VoiceOver check and the Definition of Ready's robot baseline on `1a6788cc4d` (see the [gate file's owner-run checklist](US-001b-macos-gate.md#owner-run-deferred)). Pending the owner's ruling: whether the review rounds of the PR 48 review's key-chars fix are AC 10's sign-off for Glass mac |
| **Records** | [US-001b-glass-mac-audit.md](US-001b-glass-mac-audit.md) (workstream B: inventory, slices, divergences, owner-run items), [US-001b-prism-mtl-audit.md](US-001b-prism-mtl-audit.md) (workstream A), [US-001b-macos-gate.md](US-001b-macos-gate.md) (AC 6 module runs and the owner-run checklist), [US-001b-macos-before-per-class.tsv](US-001b-macos-before-per-class.tsv) |
| **Parent** | [US-001](US-001-descope-glass-gtk-glass-mac-prism-mtl-ffm-migration.md), split by OS on 2026-10-27; the Linux half is [US-001a](US-001a-migrate-glass-gtk-jni-to-ffm-on-linux.md) |
| **Branch of record** | `ffm/graphics` |
| **Repository** | `Ben-Esquivel-Music/jfx-ffm` |
| **Priority** | Medium — the last JNI in `javafx.graphics` on any platform |
| **Estimate** | XL — 2 independent workstreams, 25,465 lines of native code, 215 Java `native` declarations, 128 upcall sites |
| **Blocked by** | Nothing, once the Definition of Ready below is met |

---

## Story

> **As** a JavaFX platform maintainer,
> **I want** the macOS Glass layer (`glass/mac`) and the Metal pipeline (`prism_mtl`) to use the Java 22+ Foreign
> Function & Memory API instead of JNI,
> **so that** `javafx.graphics` contains no JNI on any supported platform.

---

## Scope

Measured on `7dcae7baeb` (2026-10-27). Outside these two libraries `javafx.graphics` has no Java `native`
declaration and no `JNIEXPORT` left on any platform.

| Library | Native files | Lines (`wc -l`) | Files with JNI | `JNIEXPORT` | `Call*Method` sites | Java `native` declarations |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `native-glass/mac` (with `a11y/`) | 100 | 19,728 | 32 | 155 | 127 | 155 in 15 classes |
| `native-prism-mtl` | 29 | 5,737 | 5 | 59 | 1 | 60 in 7 classes |
| **Total** | **129** | **25,465** | **37** | **214** | **128** | **215** |

Java `native` declarations per class:

- **Glass** — `MacWindow` 30, `MacApplication` 21, `MacView` 20, `MacAccessible` 14, `MacPasteboard` 13,
  `MacRobot` 12, `MacMenuDelegate` 11, `MacTimer` 8, `MacFileNSURL` 6, `MacCursor` 6, `MacCommonDialogs` 4,
  `MacSystemClipboard` 3, `MacPixels` 3, `MacMenuBarDelegate` 3, `MacGestureSupport` 1. US-001 counted 67 at
  de-scoping; that count missed the `@Override native protected …` declarations.
- **Metal** — `MTLContext` 34, `MTLShader` 12, `MTLRTTexture` 6, `MTLTexture` 4, `MTLResourceFactory` 2,
  `MTLPipeline` 1, `MTLGraphics` 1. `JNIEXPORT` per file: `MetalContext.m` 38, `MetalShader.m` 12,
  `MetalRTTexture.m` 5, `MetalTexture.m` 4.

Build targets: `add_jfx_library(glass …)` and `add_jfx_library(prismMTL OUTPUT_NAME prism_mtl …)` in `mac.cmake`.

### Out of scope

- The macOS `prismES2` and `iio` targets (US-048 and US-044/US-045), and the CoreText follow-ups (US-067).
- The jslc Metal backend's header path and static state (US-018); it changes the build-time compiler only.
- Changing rendering behaviour, redesigning the Glass/Prism SPI, or improving platform behaviour while migrating.
  This is a transport-layer change only.

---

## Definition of Ready

- [x] A macOS host with Xcode builds the module and runs its tests: the Mac mini, used for US-003's macOS half
      (clean package with 26,163 module tests, 0 failures, 2026-10-24).
- [ ] The same host runs the interactive suites (`-DFULL_TEST=true -DUSE_ROBOT=true`, `tests/system`). US-003
      left its robot text tests as an owner check, so this is not yet shown. Owner-run, pending (gate file).
- [x] CI runners exist: `submit.yml` has macOS x64 and aarch64 jobs.
- [ ] A **green baseline** from the unmodified JNI code on the macOS host: the full `javafx.graphics` module tests
      plus `tests/system`, and a Metal golden capture for AC 7. The module tests ("Before" in the gate file, 1
      failure that comes from the clone's path) and the Metal goldens (`mtl-golden-1a6788cc4d.txt`/`.bin`,
      `mtl-shaders-golden-1a6788cc4d.txt`) exist; the `tests/system` part is owner-run and pending.
- [x] The `*Native` facade pattern is settled: the Windows and Linux work merged (PR #12), and US-003 bound
      CoreFoundation, CoreGraphics and CoreText from Java on macOS (`CFNative`, `CGNative`, `CTNative`).

---

## Acceptance criteria

Numbered as in US-001.

1. **No JNI remains in the two libraries.** No `JNIEXPORT` symbol, no `#include <jni.h>`, and no `JNIEnv*`
   parameter survives in `native-glass/mac` (including `a11y/`) or `native-prism-mtl`.
2. **No Java `native` declarations remain** in `com.sun.glass.ui.mac` or `com.sun.prism.mtl`. All 215 are served
   by FFM downcalls, or deleted where the `jni-auditor` triage rules them `WRAPPER` or parity-clearing `PURE`.
3. **A plain C ABI is exposed** by each library: `extern "C"` plain-C shims over the Objective-C, with explicit,
   primitive-typed parameters. No JNI type appears in any exported signature.
4. **All 128 upcall sites go through callback tables**, a struct of function pointers registered from Java via
   `Linker.upcallStub`, replacing every `Call*Method`. No Java exception can cross the native boundary.
5. **Thread confinement is preserved.** AppKit calls still occur on the macOS main thread, and `MTLContext` work
   on the correct render thread. Upcall stubs are bound to arenas whose lifetime provably outlives every native
   use.
6. **Behaviour is unchanged**, against the baseline of the Definition of Ready: module tests plus `tests/system`
   pass on macOS with results equal to it. No test is deleted or `@Disabled` to make the suite pass.
7. **`prism_mtl` is visually verified**: Metal rendering output is pixel-compared against the pre-migration
   golden capture, since unit tests barely cover Metal correctness.
8. **Accessibility still works.** `MacAccessible` is exercised against real VoiceOver. This is manual and
   non-negotiable; accessibility regressions are invisible to the automated suite.
9. **Build is clean.** The `glass` and `prismMTL` targets no longer get the JDK include directories or
   `HEADERS_DIR`. Today `add_jfx_library` in `mac.cmake:130-154` gives them to every target, so it needs an opt-in
   like `win.cmake`'s `JNI` flag. The `javac -h` step is no longer required for these targets.
10. **`ffm-reviewer` sign-off** on the final diff of each library.
11. **Close-out.** When both libraries are done, the README and build docs say that `javafx.graphics` has no JNI
    (Windows `glass` may still need `javac -h` for its constant headers; see US-001a, AC 9).

### State on 2026-11-05

Measured on the final working tree, macOS 27.0.1 arm64 only; x86_64 is for CI.

| # | State |
| --- | --- |
| 1 | ✅ Met: `grep -rnE 'JNIEXPORT\|jni\.h\|JNIEnv'` over `native-glass/mac` and `native-prism-mtl` prints nothing |
| 2 | ✅ Met: the §R1 row 4 `native` grep of the glass audit gives 0 for `com.sun.glass.ui.mac` and `com.sun.prism.mtl`. The deletions and the kept-native exports are listed in each audit (§9 and §10 of each glass group) |
| 3 | ✅ Met: `glass_mac_api.h` (195 `gmac_*`) and `prism_mtl_api.h` (53 `mtl_*`) declare no JNI type; `nm -gU` shows the same 195 and 53 names and no `Java_` |
| 4 | ✅ Met: no `Call*Method` is left in either library. Glass mac calls Java through the 8 tables of `glass_mac_api.h` (75 slots); `prism_mtl` makes no upcall. Each upcall target catches `Throwable` and reports it where the JNI did (PB7) |
| 5 | ✅ Met by construction and review (glass audit §6 of each group, PB8): AppKit work stays on the main thread, `MacView._begin`/`_end`/`_getNativeFrameBuffer` and `MTLContext` work on the render thread, and the upcall stubs live in `Arena.global()`. The live check is the owner's AC 6 run |
| 6 | 🔶 Module tests met: the final run, on the tree with the PR 48 review's key-chars fix, equals the "Before" run plus 21 new classes, the "Before" run's one failure (from its clone's path) passes, and no test was deleted or disabled ([gate file](US-001b-macos-gate.md#after-the-key-chars-fix-final)). `tests/system` and robot, before and after: owner-run, pending |
| 7 | ✅ Met for everything a golden covers: `MTLGoldenRenderTest` (4 profiles) and `MTLShaderTableGoldenTest` match the `1a6788cc4d` goldens exactly under `-Djfx.parity.require=true`. The swap chain has no golden; the robot `painttest`, `scenegraph` and `test3d` cover it (owner-run) |
| 8 | 🔶 Owner-run, pending: VoiceOver before and after |
| 9 | ✅ Met on macOS: `mac.cmake`'s `add_jfx_library` has no `JNI` keyword (`mac.cmake:133`), so no target, `glass` and `prismMTL` included, gets the JDK include directories or `HEADERS_DIR` (`:151`). Workstream A added the `JNI` opt-in, which only `glass` passed; slice 11 removed it with Glass mac's last JNI. `glass`'s `javac -h`-named constant imports resolve in `native-glass/mac/constants` (`:169`), which `MacConstantsHeaderTest` checks. The `javac -h` step stays for Windows `glass` only |
| 10 | 🔶 Met for `prism_mtl`, pending for Glass mac. Workstream A (`prism_mtl`): signed off in review round 2; the key-chars fix changed none of its files. Workstream B (Glass mac): slices 0-10 signed off one review at a time, slice 11 in review round 2, and the final whole-library review of Glass mac in round 1 on 2026-11-05, verdict mergeable, with one docs nit (FR1-N1: the workstream B after run and the owner checklist were not checked in yet), which the close-out docs step resolved ([gate file](US-001b-macos-gate.md)). That review was of workstream B's tree; the PR 48 review's key-chars fix (glass audit g2 §6 (n)) changed Glass mac after it. Three review rounds of the fix ran on 2026-11-05: every lens judged Copilot comment 4485919806 fully addressed, and the findings they confirmed, all in comments and docs, are fixed in the tree. Pending: the owner's ruling on whether those rounds are the sign-off on Glass mac's final diff |
| 11 | ✅ Met: `README.md` ("Building") says that `javafx.graphics` has no JNI on any platform, and that the `javac -h` step remains for the constant headers of Windows `glass`. The `jfx-native-build`, `jfx-graphics-native` and `jni-to-ffm-migration` skills say the same; they are local files (`.claude/**` is gitignored) |

---

## Sub-tasks

Two independent workstreams. Never migrate both in one step.

### A. `prism_mtl` — first (smallest: 5 JNI files, 1 upcall)

- [x] `jni-auditor` inventory of `native-prism-mtl` + `com.sun.prism.mtl`, with an OS-CALL / WRAPPER / PURE /
      PURE-HOT verdict per function: [US-001b-prism-mtl-audit.md](US-001b-prism-mtl-audit.md).
- [x] Capture golden render output from the unmodified JNI build: `mtl-golden-1a6788cc4d.txt`/`.bin` and
      `mtl-shaders-golden-1a6788cc4d.txt` under `src/test/resources/test/com/sun/prism/mtl/`.
- [x] Define the C ABI header and export table: `prism_mtl_api.h` (`PRISM_MTL_ABI_VERSION` 1, 53 `mtl_*`, all
      exported by `libprism_mtl.dylib`).
- [x] `MTLNative` facade (`MTLContext`'s 34 methods first, then `MTLShader`, `MTLRTTexture`, `MTLTexture`, the
      rest), with `StructLayout`s validated against `sizeof` and `alignof` on the device: `MTLNative` with
      `MTLContextCalls`, `MTLTextureCalls`, `MTLShaderCalls` and `MTLMeshCalls`; no struct crosses the ABI, and
      `MTLNativeTest` pins the ABI version, the constants and the pixel-format ordinals.
- [x] Flip callers, delete the JNI, update `mac.cmake`: 0 `native` declarations, 0 `Java_` exports, and `prismMTL`
      builds without the JDK include directories or `HEADERS_DIR`.
- [x] Pixel-parity test against the golden capture: `MTLGoldenRenderTest` and `MTLShaderTableGoldenTest` match
      exactly under `-Djfx.parity.require=true` (gate file, 55 / 0 / 0 / 0 on the final tree).

### B. `glass/mac` — second

Hardest part: Objective-C, `objc_msgSend`, AppKit main-thread confinement, and the `NSAccessibility` protocol.

- [x] `jni-auditor` inventory of `native-glass/mac` + `com.sun.glass.ui.mac` (127 upcall sites):
      [US-001b-glass-mac-audit.md](US-001b-glass-mac-audit.md), groups g0-g4 and Reconciliation R1-R4.
- [x] Plain-C shims over the Objective-C surface. **Do not** call `objc_msgSend` from Java; its variadic ABI is
      not portably expressible via `Linker`: `glass_mac_api.h/.m` (`GLASS_MAC_ABI_VERSION` 1, 195 `gmac_*`); the
      only libobjc function Java binds is `objc_release`.
- [x] Callback table and arena strategy that respect AppKit's main-thread rules: 8 tables in `glass_mac_api.h` (App
      21 slots, Timer 1, Window 10, View 15, Gesture 5, Dialog 2, Menu 5, Accessible 16), installed once, with their
      upcall stubs in `Arena.global()` and Java-assigned registry ids.
- [x] `MacNative` facade in dependency order: `MacApplication` → `MacWindow` → `MacView` → `MacPasteboard` /
      `MacSystemClipboard` → `MacMenuDelegate` → `MacAccessible` (last): `MacGlassNative` (the precedents' name,
      audit R2) with the `Mac*Calls` and `Mac*Callbacks` aggregates, in the audit's slices 0-11.
- [ ] VoiceOver verification of `MacAccessible`. Owner-run, pending (AC 8).

---

## Suggested agents and skills

| Role | Agent |
| --- | --- |
| Inventory and migrate-or-delete verdict | `jni-auditor` |
| FFM facade and C ABI | `ffm-migrator` |
| Diff review (ABI, lifetimes, thread confinement) | `ffm-reviewer` |
| Tests and golden-capture parity | `ffm-test-porter` |
| CMake and toolchain | `native-build-engineer` |
| Metal pipeline specifics | `graphics-rendering-engineer` |

Skills: `jni-to-ffm-migration`, `jfx-graphics-native`, `jfx-native-build`, `jfx-ffm-testing`,
`openjfx-conventions`. Precedent: US-003's macOS facades (`CFNative`, `CGNative`, `CTNative`) and
`com.sun.glass.ui.gtk.GtkGlassNative`.

---

## Risks

| # | Risk | Impact | Mitigation |
| --- | --- | --- | --- |
| 1 | AppKit main-thread violations introduced by upcall stubs | Deadlock or crash | Explicit thread-confinement assertions; `ffm-reviewer` gate |
| 2 | An arena frees an upcall stub AppKit still references | Use-after-free, hard to reproduce | Global or shared arenas for long-lived callbacks; leak-check tests |
| 3 | `objc_msgSend`'s variadic ABI is not expressible via `Linker` | Blocks `glass/mac` | Mandatory plain-C shim layer |
| 4 | Accessibility regressions escape automated tests | Ships broken for assistive-tech users | Manual VoiceOver check is AC 8 |
| 5 | Metal struct layout or alignment mismatch | Silent corruption or GPU crash | Validate every `StructLayout` against on-device `sizeof`/`alignof` |
| 6 | Scope creep into rendering or SPI redesign | Unreviewable diff | Transport-layer change only; behaviour-neutrality outranks elegance |

---

## Definition of Done

- All 11 acceptance criteria met.
- macOS CI green, matching the pre-migration baseline.
- `ffm-reviewer` sign-off recorded for each library. Recorded for `prism_mtl`; pending for Glass mac, whose recorded
  sign-off predates the key-chars fix (AC 10 in "State on 2026-11-05").
- Changes left in the working tree or a review branch; no commits, merges or PRs without the owner's approval.

---

## References

- `modules/javafx.graphics/native/mac.cmake:130-154` (`add_jfx_library`), `:159` (`glass`), `:186` (`prismMTL`) at
  `1a6788cc4d`; `:132-153`, `:166` and `:194` in the final tree
- [US-001b-glass-mac-audit.md](US-001b-glass-mac-audit.md), [US-001b-prism-mtl-audit.md](US-001b-prism-mtl-audit.md)
  and [US-001b-macos-gate.md](US-001b-macos-gate.md)
- `modules/javafx.graphics/src/main/java/com/sun/glass/ui/gtk/GtkGlassNative.java` — the Linux Glass facade
- [US-003](US-003-migrate-javafx-font-jni-to-ffm.md) — the macOS font migration on the same host
