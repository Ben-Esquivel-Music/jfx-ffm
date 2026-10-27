# US-001 — Migrate `glass/gtk`, `glass/mac` and `prism_mtl` from JNI to FFM

| Field | Value |
| --- | --- |
| **Status** | 🔀 **Split by OS** (2026-10-27) into [US-001a](US-001a-migrate-glass-gtk-jni-to-ffm-on-linux.md) (Linux, `glass/gtk`: JNI removed in PR #12, build clean-up left) and [US-001b](US-001b-migrate-glass-mac-and-prism-mtl-jni-to-ffm-on-macos.md) (macOS, `glass/mac` + `prism_mtl`: not started). This story is done when both are |
| **Parent epic** | Fully remove JNI from `javafx.graphics` (replaced by a functioning FFM API) |
| **Branch of record** | `ffm/graphics` |
| **Repository** | `Ben-Esquivel-Music/jfx-ffm` |
| **Priority** | Medium (blocking for "100% JNI-free `javafx.graphics`", not blocking for the Windows deliverable) |
| **Estimate** | See the substories |
| **Blocked by** | Nothing. Both premises of the de-scoping are gone: WSL builds and tests the Linux natives (2026-09-15), and a macOS host builds and tests the module (2026-10-24) |

---

## Split by OS

The three libraries share no code, no build target and no host, so the work and its acceptance are tracked per
operating system. The criteria and sub-tasks below were moved into the substories unchanged in substance; this
story keeps the original rationale and measurements for the record.

| Substory | OS | Libraries | State on 2026-10-27 |
| --- | --- | --- | --- |
| [US-001a](US-001a-migrate-glass-gtk-jni-to-ffm-on-linux.md) | Linux | `glass/gtk` | 🔶 JNI removed in PR #12 (`26ce75d02f`): 0 Java `native` declarations, 0 `JNIEXPORT`. Left: the `glassgtk3` target still compiles against `HEADERS_DIR` and the JDK headers (AC 9), plus the AC 6 and AC 10 records |
| [US-001b](US-001b-migrate-glass-mac-and-prism-mtl-jni-to-ffm-on-macos.md) | macOS | `glass/mac`, `prism_mtl` | 🔶 Not started: 155 + 60 Java `native` declarations, 128 upcall sites. Next: the JNI baseline on the macOS host, then `prism_mtl` |

Windows has no part in this story: its libraries were delivered by the parent epic.

### Where each acceptance criterion went

| # | Criterion | Linux (US-001a) | macOS (US-001b) |
| --- | --- | --- | --- |
| 1 | No JNI in the native sources | ✅ Met | Open |
| 2 | No Java `native` declarations | ✅ Met | Open |
| 3 | A plain C ABI | ✅ Met | Open |
| 4 | Upcalls through callback tables | ✅ Met | Open |
| 5 | Thread confinement preserved | Covered by PR #12's tests | Open |
| 6 | Behaviour equal to the JNI baseline | Partly recorded | Open |
| 7 | Metal pixel parity | Not applicable | Open |
| 8 | Accessibility against a real screen reader | Not applicable (GTK Glass has no accessibility bridge) | Open (VoiceOver) |
| 9 | No JDK headers, `HEADERS_DIR` or `javac -h` for the targets | **Open** | Open |
| 10 | `ffm-reviewer` sign-off | Not recorded | Open |

The sub-tasks moved too: A (`prism_mtl`) and C (`glass/mac`) are US-001b's workstreams A and B; B (`glass/gtk`)
was done in PR #12, and its Wayland/X11 item is answered in US-001a's notes; D (close-out) is AC 9 of each
substory plus US-001b's AC 11.

---

## Story

> **As** a JavaFX platform maintainer,
> **I want** the Linux (`glass/gtk`), macOS (`glass/mac`) and Metal (`prism_mtl`) native
> layers migrated from JNI to the Java 22+ Foreign Function & Memory API,
> **so that** `javafx.graphics` contains no JNI on *any* supported platform and the module
> can eventually drop `jni.h`, generated JNI headers, and the `javac -h` build step entirely.

---

## Why this was de-scoped (2026-09-07, historical)

The parent epic was being delivered on a **Windows-only development machine**. These three
libraries are gated behind `mac.cmake` and `linux.cmake` in
`modules/javafx.graphics/native/CMakeLists.txt` and **could not be compiled, linked, or executed
there at all**:

| Library | Requires | Available on that machine then |
| --- | --- | --- |
| `glass/gtk` | Linux + GTK 3 dev headers, X11/Wayland session | ❌ (WSL since 2026-09-15) |
| `glass/mac` | macOS + Xcode + Cocoa/AppKit (Objective-C) | ❌ (macOS host since 2026-10-24) |
| `prism_mtl` | macOS + Xcode + a Metal-capable GPU | ❌ (macOS host since 2026-10-24) |

Migrating them regardless would have meant rewriting the **most user-visible and least
unit-testable** parts of the toolkit — window management, input, clipboard, drag-and-drop,
screen/DPI handling, platform accessibility, and the entire macOS render pipeline — with
**no ability to compile the C/Objective-C, no ability to run a single test, and no way to
observe a regression**. That directly violates the epic's governing constraint that
migrations must be **behaviour-neutral and provably so**. The work was therefore parked behind
an explicit readiness gate rather than attempted blind; each substory now carries its own.

---

## Scope at de-scoping (historical)

Measured on `ffm/graphics` on 2026-09-07. The substories carry the current numbers.

| Area | Native files | Files containing JNI | Native LOC | JNI upcalls (`Call*Method`) | Java files | Java `native` methods |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `src/main/native-glass/gtk` | 130 | 25 | 22,247 | 102 | 22 | 96 |
| `src/main/native-glass/mac` | 100 | 41 | 17,239 | 127 | 20 | 67 |
| `src/main/native-prism-mtl` | 24 | 6 | 4,353 | 1 | 17 | 59 |
| **Total** | **254** | **72** | **43,839** | **230** | **59** | **222** |

The macOS Glass count of 67 missed the `@Override native protected …` declarations; there are 155 (US-001b).

---

## References

- `26ce75d02f` — PR #12, which removed the GTK JNI
- `modules/javafx.graphics/native/CMakeLists.txt` — platform dispatch
- `modules/javafx.graphics/native/linux.cmake` — the `glassgtk3` target (US-001a)
- `modules/javafx.graphics/native/mac.cmake` — the `glass` and `prismMTL` targets (US-001b)
