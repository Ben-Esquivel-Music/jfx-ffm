# US-001b — Migrate `glass/mac` and `prism_mtl` from JNI to FFM (macOS)

| Field | Value |
| --- | --- |
| **Status** | 🔶 **Open** — unblocked: a macOS host has built and tested the module since 2026-10-24 (US-003's macOS half). Not started; the first step is the JNI baseline on that host |
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
      left its robot text tests as an owner check, so this is not yet shown.
- [x] CI runners exist: `submit.yml` has macOS x64 and aarch64 jobs.
- [ ] A **green baseline** from the unmodified JNI code on the macOS host: the full `javafx.graphics` module tests
      plus `tests/system`, and a Metal golden capture for AC 7.
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

---

## Sub-tasks

Two independent workstreams. Never migrate both in one step.

### A. `prism_mtl` — first (smallest: 5 JNI files, 1 upcall)

- [ ] `jni-auditor` inventory of `native-prism-mtl` + `com.sun.prism.mtl`, with an OS-CALL / WRAPPER / PURE /
      PURE-HOT verdict per function.
- [ ] Capture golden render output from the unmodified JNI build.
- [ ] Define the C ABI header and export table.
- [ ] `MTLNative` facade (`MTLContext`'s 34 methods first, then `MTLShader`, `MTLRTTexture`, `MTLTexture`, the
      rest), with `StructLayout`s validated against `sizeof` and `alignof` on the device.
- [ ] Flip callers, delete the JNI, update `mac.cmake`.
- [ ] Pixel-parity test against the golden capture.

### B. `glass/mac` — second

Hardest part: Objective-C, `objc_msgSend`, AppKit main-thread confinement, and the `NSAccessibility` protocol.

- [ ] `jni-auditor` inventory of `native-glass/mac` + `com.sun.glass.ui.mac` (127 upcall sites).
- [ ] Plain-C shims over the Objective-C surface. **Do not** call `objc_msgSend` from Java; its variadic ABI is
      not portably expressible via `Linker`.
- [ ] Callback table and arena strategy that respect AppKit's main-thread rules.
- [ ] `MacNative` facade in dependency order: `MacApplication` → `MacWindow` → `MacView` → `MacPasteboard` /
      `MacSystemClipboard` → `MacMenuDelegate` → `MacAccessible` (last).
- [ ] VoiceOver verification of `MacAccessible`.

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
- `ffm-reviewer` sign-off recorded for each library.
- Changes left in the working tree or a review branch; no commits, merges or PRs without the owner's approval.

---

## References

- `modules/javafx.graphics/native/mac.cmake:130-154` (`add_jfx_library`), `:159` (`glass`), `:186` (`prismMTL`)
- `modules/javafx.graphics/src/main/java/com/sun/glass/ui/gtk/GtkGlassNative.java` — the Linux Glass facade
- [US-003](US-003-migrate-javafx-font-jni-to-ffm.md) — the macOS font migration on the same host
