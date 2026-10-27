# US-001a — Migrate `glass/gtk` from JNI to FFM (Linux)

| Field | Value |
| --- | --- |
| **Status** | 🔶 **Open** — the JNI is gone (PR #12, `26ce75d02f`, 2026-09-24): no Java `native` method in `com.sun.glass.ui.gtk`, no `JNIEXPORT` or `jni.h` in `native-glass/gtk`, `libglass.so` no longer built. Left: AC 9, the `glassgtk3` target still compiles against `HEADERS_DIR` and the JDK include directories; and the AC 6 and AC 10 records |
| **Parent** | [US-001](US-001-descope-glass-gtk-glass-mac-prism-mtl-ffm-migration.md), split by OS on 2026-10-27; the macOS half is [US-001b](US-001b-migrate-glass-mac-and-prism-mtl-jni-to-ffm-on-macos.md) |
| **Branch of record** | `ffm/graphics` |
| **Repository** | `Ben-Esquivel-Music/jfx-ffm` |
| **Priority** | Medium |
| **Estimate** | S for what is left (one CMake target, 11 constant headers in 9 source files) |
| **Blocked by** | Nothing. WSL builds and tests the Linux natives (unblocked 2026-09-15); the GTK display tests run in CI on Xvfb since `c666773a96` (PR #35) |

---

## Story

> **As** a JavaFX platform maintainer,
> **I want** the Linux Glass layer (`glass/gtk`) to use the Java 22+ Foreign Function & Memory API instead of JNI,
> **so that** `javafx.graphics` has no JNI on Linux and the `glassgtk3` build needs neither `jni.h` nor the
> `javac -h` headers.

---

## Scope

Measured on `7dcae7baeb` (2026-10-27):

| Area | Then (de-scoping, 2026-09-07) | Now |
| --- | ---: | ---: |
| Java `native` declarations in `com.sun.glass.ui.gtk` | 96 (99 in the PR #12 hand-off) | 0 |
| `JNIEXPORT` in `native-glass/gtk` | — | 0 |
| Files in `native-glass/gtk` that include `jni.h` | 25 contained JNI | 0 |
| `Call*Method` upcall sites | 102 | 0 (9 matches, all in comments that cite the JNI) |

The C ABI is `glass_gtk_api.h` (four callback tables) and `screencast_api.h` (one callback slot), both free of
JNI types; the Java side is `com.sun.glass.ui.gtk.GtkGlassNative`.

Out of scope: the screencast port to Rust and its defects (US-037, US-038, US-042), the GTK bounds faults
(US-065), and any change of behaviour.

---

## Acceptance criteria

The criteria of US-001 that apply to Linux, with their state on 2026-10-27:

| # | Criterion | State |
| --- | --- | --- |
| 1 | No `JNIEXPORT`, no `#include <jni.h>`, no `JNIEnv*` parameter in `native-glass/gtk` | ✅ Met (measured; `JNIEnv`, `jobject` and `jclass` appear only in comments, in 4 files) |
| 2 | No Java `native` declaration in `com.sun.glass.ui.gtk` | ✅ Met (0) |
| 3 | A plain C ABI with primitive-typed parameters | ✅ Met: `glass_gtk_api.h`, `screencast_api.h` |
| 4 | Every upcall goes through a callback table registered from Java; no Java exception crosses the native boundary | ✅ Met in PR #12 (`GtkCallbackTableTest`, `GtkNullSlotsTest`, `GtkUpcallExceptionTest`) |
| 5 | GTK calls stay on the GTK main-loop thread; upcall stubs outlive every native use | Covered by PR #12's tests (`GtkInvokeLaterNativeTest`, `GtkViewLifetimeTest`, `GtkPeerRegistryTest`); not re-run for this split |
| 6 | Module tests and `tests/system` on Linux equal the JNI baseline; nothing deleted or `@Disabled` | Partly recorded. PR #12's Linux gate (2026-09-22 hand-off: module 25,051 run / 494 skipped, 193 GTK display test methods) was not re-run for this split, and its scratchpad is gone. No `tests/system` comparison on Linux is recorded in the tree; the JNI baseline is `033187ad90` |
| 7 | Metal pixel parity | Not applicable (macOS only, US-001b) |
| 8 | Accessibility checked against a real screen reader | Not applicable: GTK Glass has no accessibility bridge. `native-glass/gtk` has no ATK code, and `com.sun.glass.ui.gtk` does not override `Application.createAccessible()`, which returns `null` |
| 9 | `linux.cmake` no longer gives the GTK target `HEADERS_DIR` or the JDK include directories, and the target needs no `javac -h` step | ❌ **Open** — see below |
| 10 | `ffm-reviewer` sign-off on the diff | Not recorded in the tree |

### AC 9 — what is left

`linux.cmake:140-152` still passes `"${HEADERS_DIR}" "${JDK_HOME}/include" "${JDK_HOME}/include/linux"` to
`glassgtk3`. The GTK sources declare no JNI function, but 9 of them include `javac -h` headers for the constants of
`com.sun.glass.events.*` and `com.sun.glass.ui.*`, and every such header starts with `#include <jni.h>`:

`com_sun_glass_events_ViewEvent.h` (4 files), `com_sun_glass_events_KeyEvent.h` (4),
`com_sun_glass_events_WindowEvent.h` (3), `com_sun_glass_events_MouseEvent.h` (2),
`com_sun_glass_events_DndEvent.h`, `com_sun_glass_ui_Window_Level.h`, `com_sun_glass_ui_Window.h`,
`com_sun_glass_ui_GlassRobot.h`, `com_sun_glass_ui_View.h`, `com_sun_glass_ui_Cursor.h`,
`com_sun_glass_ui_Clipboard.h` (1 each).

Done when `glassgtk3` builds with none of the three include directories, the constants the C uses still equal the
Java ones (checked by the build or by a test that fails on a mismatch, with a mutant proof), and nothing else
in the library changes. The implementer chooses how: a JNI-free constants header generated
from the Java classes, a hand-kept header checked against them, or another way. Windows `glass` (the one target
with `win.cmake`'s `JNI` flag) and macOS (US-001b) still use the `javac -h` step, so it stays in the build.

---

## Notes carried over from US-001

- **Wayland and X11.** US-001 asked for both to be verified separately. Glass sets `GDK_BACKEND=x11` before GTK
  starts (`GtkGlassNative.Loader.queryLibrary`), so its windows always use GDK's X11 backend, which is XWayland on
  a Wayland session. The one exception is a deployment that renames `glassgtk3` to `libglass.so`, which skips the
  `putenv` as upstream's `GlassApplication.cpp` did. The only Wayland-specific path is the screencast portal
  (`XdgDesktopPortal`), which US-038, US-042 and US-037 cover. The X11 path runs in CI on Xvfb (US-016) and was
  exercised on WSLg's XWayland by US-017.
- **Thread rule.** GTK calls stay on the GTK main-loop thread, as in US-001 risk 1 for AppKit.

---

## Definition of Done

- AC 9 met, and the AC 6 and AC 10 rows above filled in with what was run.
- Module tests and the GTK display tests green on WSL and in CI, with counts equal to the gate before the change.
- Changes left in the working tree or a review branch; no commits, merges or PRs without the owner's approval.

---

## References

- `26ce75d02f` — PR #12, the GTK JNI removal
- `modules/javafx.graphics/native/linux.cmake:133-152` — the `glassgtk3` target
- `modules/javafx.graphics/src/main/native-glass/gtk/glass_gtk_api.h`, `screencast_api.h` — the C ABI
- `modules/javafx.graphics/src/main/java/com/sun/glass/ui/gtk/GtkGlassNative.java` — the facade
