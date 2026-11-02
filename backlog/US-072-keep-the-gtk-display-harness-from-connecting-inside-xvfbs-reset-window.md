# US-072 — Keep the GTK display harness from connecting inside Xvfb's reset window

**Status:** 📋 Ready (filed 2026-11-01 from the diagnosis of a CI failure on the US-065 branch) · **Priority:** Low
(possible, **not reproduced**; a hit fails the step with a clear message, it is never silent) · **Estimate:** S ·
**Found:** 2026-11-01, sweep after the `XOpenDisplay(NULL) failed` in `GtkWindowPlacementTest` (CI run 41307952109,
PR #46 at commit bfd94a69b0)

## Story
As the maintainer reading a red "GTK display tests" step,
I want the harness never to open an X connection at the moment Xvfb's last client hangs up,
so that a failure means a test failed, not that Xvfb reset under a connection that had only just opened.

## Central finding
This mechanism is proven for the CI defect that PR #46 fixes. Xvfb is started without `-noreset`, so it resets
when its last client closes. A new connection can `connect()` before Xvfb's `epoll_wait` returns the last client's
hang-up. Then one batch of xorg-server 21.1.12 (the CI's Xvfb) does three things: `CloseDownClient` closes the old
client (`nClients` goes to 0, `DE_RESET`), `EstablishNewConnections` accepts the new client in state Initial, and
`KillAllClients` closes the new client before its setup is read, so `XOpenDisplay` returns NULL (ECONNRESET). The
defect had a check followed by `start()`, 1-5 µs apart: it failed 956 of 2000 times in the micro-repro and 40 of 87
times in the real child under load. The no-WM pass has to keep resetting, because 5 no-WM trace goldens depend on
it, so `-noreset` is not an option there.

The sweep found the same close-then-connect pattern in four more places. **None was reproduced.** In each, the gap
is milliseconds, not microseconds, so a hit needs Xvfb to be kept off the CPU for that long.

| # | Where | Last client to close | Connection that can be killed | Effect of a hit | Exposed with today's suites |
| --- | --- | --- | --- | --- | --- |
| 1 | `.github/scripts/run-gtk-display-tests.sh:172` (`start_xvfb` readiness `xdpyinfo`), then `:190` (`start_openbox`'s `setsid` openbox) | `xdpyinfo` | openbox's `XOpenDisplay` | openbox exits, then "openbox did not start" (`:207`), and the openbox pass fails | Yes, openbox pass, once per run |
| 2 | `start_openbox`'s 0.2 s poll (`:194`, `root_property` `:155`, `xprop`) | an `xprop` that closes before openbox has connected | openbox's `XOpenDisplay` | as 1 | Yes, openbox pass. The pass's `xvfb.log` in the artifacts of CI run 41307952109 shows 3 server generations (one "XKEYBOARD keymap compiler" line each): the start, the `xdpyinfo` reset and one more. The extra one fits a poll that closed before openbox connected, but it was not traced |
| 3 | `GtkLibraryLoader.displayOpens` (`GtkLibraryLoader.java:162-173`): `XOpenDisplay` + `XCloseDisplay` before `gtk_init` (`GlassApplication.cpp:104`) | the probe's own connection | the connection `gtk_init` opens | toolkit startup fails in that child | No. In every `Platform.startup` child, Prism ES2's factory connection (`prism_es2_api_x11.c:102`, kept for the life of the process) is already open, so the probe is never the last client. The race opens only where ES2 opens no display (`prism.order=sw`), and no suite sets that |
| 4 | Child JVMs started as a pair: `GtkClipboardNativeTest.java:137`, `GtkDnDReentrantDropTest.java:156-160`, `GtkDnDTraceTest.java:233-235` | one child's last connection | the other child's first connection | that child fails | No. The same cover as 3; only with `prism.order=sw` |

## Acceptance criteria
1. **Reproduce first, or close as a note.** Before changing anything, run items 1 and 2 under CPU load in WSL, the
   way the CI fix was reproduced: a loop of `start_xvfb` + `start_openbox` + teardown, at least 500 rounds, counting
   "openbox did not start" and NULL displays. Record N and the count here. If none of the N rounds fails, close the
   story with that result as a note in US-016 ("possible, not reproduced in N rounds under load") and change
   nothing.
2. **If it reproduces, openbox never connects inside the window.** The implementer chooses how. For example, keep
   one harness X client open from the readiness check until openbox is up (closing it then cannot reset, because
   openbox is a running client), or poll openbox's root properties over that one connection. The no-WM pass keeps
   resetting on last close, and Xvfb still starts without `-noreset`. No sleeps and no retries.
3. **Proof.** The loop from criterion 1 fails before the fix and passes after it (0 of N). A scratch mutant that
   takes the fix back out fails again.
4. **Gates green.** Both GTK display passes run in WSL, and their class and test counts match the baseline before
   the change (the US-065 branch with the fix for CI run 41307952109: no-WM 38 classes / 255 tests, openbox 38 /
   228, the counts US-065's Review section records after that fix).

## Out of scope
- Items 3 and 4 are recorded as a note, not as criteria. No suite configuration reaches them. A desktop session also
  never resets, because the window manager and other clients stay connected. Look at them again only if a suite
  starts a child with `prism.order=sw` on an Xvfb without a window manager.
- The CI defect itself (`runningWindowManager()` then `start()`) and the child work-directory collision in
  `GtkGlassChildJvm.start`, which PR #46 fixes.
- `-noreset` for the no-WM pass.

## Definition of Done
- Criterion 1's result is recorded. Either the story is closed as a note, or criteria 2 to 4 are met with the
  before/after logs kept with the change.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without
  explicit approval from the repository owner.**

## Notes
- Related: US-016 (owns the script and the CI step) and US-065 / PR #46 (where the mechanism was proven).
- Evidence: CI run 41307952109 (PR #46 at bfd94a69b0; its failure artifacts hold both passes' `xvfb.log`); the
  "CI run 41307952109" bullet of US-065's Review section (cause, fix, figures under load); xorg-server 21.1.12's
  `CloseDownClient` and `KillAllClients` (`dix/dispatch.c`) and `EstablishNewConnections` (`os/connection.c`).
- Filed against bfd94a69b0 with the fix for CI run 41307952109 in the working tree; the line numbers above are of
  that tree.
