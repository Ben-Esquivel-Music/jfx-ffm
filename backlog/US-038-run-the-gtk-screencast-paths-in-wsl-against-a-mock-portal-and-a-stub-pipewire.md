# US-038 — Run the GTK screencast code paths in WSL against a mock portal and a stub PipeWire library

**Status:** 📋 Ready (drafted 2026-09-30 from a read-only survey and one WSL probe. NOT checked: whether the WSL user
can install packages, and whether GitHub's Ubuntu runners ship `dbus-daemon`. Nothing built) · **Epic:** none; it is a
prerequisite of US-037 and US-042 · **Blocked by:** — (the CI half
follows US-016)

## Story
As a platform maintainer,
I want the screen-capture and remote-desktop paths of GTK Glass to run in WSL against a scripted portal and a scripted
PipeWire,
so that goldens and regression tests can pin their behaviour; today the tests reach only the "no PipeWire" branch.

## Current state
- The WSL image (Ubuntu 26.04) has no `libpipewire-0.3.so.0`, no `xdg-desktop-portal`, no `dbus-daemon` and no
  `python3` (probed 2026-09-30).
- CI runs no GTK display test (US-016).
- The debug golden records a machine with no PipeWire library (`gtk-screencast-debug-golden.txt:1-3`).

## Approach
- **A stub `libpipewire-0.3.so.0`.** It is test native code, like the Monocle vendor stub
  `modules/javafx.graphics/src/test/native/monocle/egl_vendor_stub.c`. It implements the 25 symbols the C resolves
  (`screencast_pipewire.c:727-766`), runs a real thread as its loop, and delivers scripted formats and frames.
- **A mock `org.freedesktop.portal.Desktop`.** It covers ScreenCast and RemoteDesktop.
  - It is written in C on GDBus, because the image has no python3.
  - It runs on a private `dbus-daemon --session` for the test JVM.
  - It answers with scripted Responses, restore tokens and one PipeWire fd.
- **Goldens captured from today's C**, with the commit and platform recorded:
  - the D-Bus message trace (`dbus-monitor` on the private bus);
  - the `store_token` upcall trace;
  - raw-ARGB pixels;
  - the stderr debug text.
- **How the tests run.** They are opt-in in the module and run under the rootless-Xvfb recipe.

## Acceptance criteria
- The `GtkRobot` screen-capture, mouse and key paths run end to end in WSL against the stub and the mock.
- Each scenario's test javadoc names what that scenario pins.
- When the stub is absent, the existing "no PipeWire" tests behave exactly as before.

## Risks
| # | Risk | Mitigation |
| --- | --- | --- |
| 1 | No `sudo` in WSL to install `dbus-daemon` | Build it rootless from source, as with the patched Xvfb, or run the suite in CI first |
| 2 | The stub's timing hides real PipeWire races | Script both orders around `pw_thread_loop_signal`/`wait` explicitly |

