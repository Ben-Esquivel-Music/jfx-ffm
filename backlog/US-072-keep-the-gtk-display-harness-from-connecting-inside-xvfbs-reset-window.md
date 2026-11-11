# US-072 — Keep the GTK display harness from connecting inside Xvfb's reset window

**Status:** ✅ Done (2026-11-11, uncommitted, PR pending; filed 2026-11-01 from the diagnosis of a CI failure on the
US-065 branch; the script keeps one X client connected from Xvfb's readiness check until openbox is up, see "Done"
below) · **Priority:** Low (reproduced in WSL: 18 of 1000 rounds under CPU load, 0 of 500 idle; a hit fails the step
with a clear message, it is never silent) · **Estimate:** S · **Found:** 2026-11-01, sweep after the
`XOpenDisplay(NULL) failed` in `GtkWindowPlacementTest` (CI run 41307952109, PR #46 at commit bfd94a69b0)

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

The sweep found the same close-then-connect pattern in four more places. **None was reproduced.** (Criterion 1 later
reproduced item 2 under CPU load; see "Done".) In each, the gap is milliseconds, not microseconds, so a hit needs
Xvfb to be kept off the CPU for that long.

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

## Done (2026-11-11, uncommitted)

### What changed
`.github/scripts/run-gtk-display-tests.sh` only.
- **The holder.** `start_xvfb`'s existing 0.2 s readiness poll starts `xprop -display $DISPLAY_NAME -root -spy
  RESOURCE_MANAGER` (`HOLDER_PID`), one attempt alive at a time, with its output in the run's `xvfb-holder.txt`.
  Once connected, it writes `RESOURCE_MANAGER:  not found.` and waits for changes to that property until it is
  stopped. An attempt made before Xvfb listens exits without having connected, and the next poll starts another.
  `RESOURCE_MANAGER` is a predefined atom that Xvfb leaves unset, so the holder interns no atom (checked with
  `xlsatoms`). Xvfb is ready when the file is not empty and the holder is alive. Only then does the readiness
  `xdpyinfo` run, so its close is never the last one.
- **Stop.** `run_tests` stops the holder right after `start_xvfb && { [ "$run" != openbox ] || start_openbox; }`
  succeeds, before `.started` and Maven. The teardown and `cleanup` stop it between openbox and Xvfb.
- **Each pass now.** Openbox: the holder, the readiness `xdpyinfo`, openbox and every `start_openbox` poll share the
  server's first generation. The holder closes while openbox is connected, so Maven starts on generation 1 (2
  before). No window manager: the holder's close resets the server before Maven, as the readiness `xdpyinfo`'s close
  did, and the server still resets whenever the last child closes, which the 5 no-WM trace goldens need.
- No `-noreset`, no sleep, no retry: the holder attempts are the readiness poll's existing bounded attempts.
  `submit.yml` is unchanged: the script already used `xprop`, which comes with `x11-utils`, which the step installs.

### Measured (2026-11-11, WSL x64 Ubuntu 26.04, 8 CPUs, Xvfb 21.1.22, Openbox 3.6.1)
**Criterion 1.** 1000 rounds of the script's own `start_xvfb` and `start_openbox` from `2a1271703b`, then the
teardown, under 16 `perl -e '1 while 1'` loops (1-minute load average 17.0 to 17.9 once ramped). 18 rounds failed
with "openbox did not start", each a NULL display in openbox's log. Idle: 0 of 500. 96 of the 982 passing rounds
were near misses, a reset before openbox connected that openbox survived (idle: 1). A diagnostic run with
`-audit 4` (500 rounds, 7 hits, not counted) charged every hit to item 2, the close of `start_openbox`'s first
`xprop` poll, and none to item 1. This Xvfb is a rootless copy of the packaged 21.1.22, not CI's 21.1.12 (both have
the same `CloseDownClient`, `EstablishNewConnections` and `KillAllClients` paths), and openbox starts through an
`env` wrapper, so the 1.8 % rate does not carry over to CI. The third server generation that item 2 cites from CI
run 41307952109 is most likely the reset at teardown: every idle round without a near miss also ends with 3.

**Criterion 3.** Same load and setup. Each round ran the script's own `run_tests`, with a stub `mvn` that records
the server generations and connects once as a test child. The mutant changes one line: `run_tests` stops the holder
right after `start_xvfb` returns, so no client of the script is connected from that close until openbox connects.

| script | rounds | failed | near misses | generations when openbox was up |
| --- | --- | --- | --- | --- |
| before the fix (criterion 1) | 1000 | 18 | 96 | 2: 886, 3: 96 |
| fixed | 1000 | 0 | 0 | 1: 1000 |
| mutant: the holder stopped before `start_openbox` | 1000 | 20 | 16 | 2: 964, 3: 16 |

Each mutant failure had the script's own "openbox did not start" error. With the fixed script, Maven started on
generation 1 in all 1000 rounds. Without a window manager, the fixed script passed 200 of 200 rounds under the same
load, each with a reset on the holder's close and another on the stub child's close.

**Criterion 4.** Temurin 27+35, Maven 3.10.0, GTK 3.24.52. Before: a clone of master `2a1271703b`. After: the same
clone, natives (built from source) and command (`bash .github/scripts/run-gtk-display-tests.sh -am`), with the
fixed script.

| pass | before | after |
| --- | --- | --- |
| no window manager | 39 classes, 269 / 0 / 0 / 4 | 39 classes, 269 / 0 / 0 / 4 |
| openbox | 39 classes, 242 / 0 / 0 / 27 | 39 classes, 242 / 0 / 0 / 27 |

Run / failures / errors / skipped. Equal per class and per method (name and status). Under openbox the same 5
classes, the script's expected list, run no test. No `hs_err_pid`; natives unchanged. Per class:
[US-072-linux-display-per-class.tsv](US-072-linux-display-per-class.tsv). Maven started on server generation 1 in the
after openbox pass and on 2 in the other three.

The baseline that criterion 4 names (no-WM 38 classes / 255 tests, openbox 38 / 228, the US-065 branch) predates
three class changes, which make up the whole difference: `GtkDnDRefusedConversionTest` +12 (new in US-069, PR #54),
`GtkTraceRulesTest` +1 (US-069) and `GtkWindowPlacementTest` +1 (US-065's later review round; skipped under
openbox). So 255 + 14 = 269 and 228 + 14 = 242.

On the stated baseline tree itself, `692cd3a432` (the US-065 branch with the fix for CI run 41307952109), both
passes also ran with that tree's own script and with the fix applied to it, with the same versions and command
and natives built from source on that tree.

| pass | before (`692cd3a432`) | after (`692cd3a432` with the fix) |
| --- | --- | --- |
| no window manager | 38 classes, 255 / 0 / 0 / 4 | 38 classes, 255 / 0 / 0 / 4 |
| openbox | 38 classes, 228 / 0 / 0 / 26 | 38 classes, 228 / 0 / 0 / 26 |

Equal per class and per method, and per class (all four counts) equal to that tree's 2026-11-01 gate, whose counts
US-065's Review section records; its glass library is byte-identical to that gate's. No `hs_err_pid`. Maven started
on server generation 1 in the after openbox pass and on 2 in the other three. Per class:
[US-072-baseline-692cd3a432-per-class.tsv](US-072-baseline-692cd3a432-per-class.tsv).

### Acceptance criteria
1. ✅ Reproduced: 18 of 1000 under load, 0 of 500 idle; every attributed hit was item 2. Not closed as a note.
2. ✅ A client of the script is connected from the readiness check until openbox is up. Xvfb still starts without
   `-noreset`, and the no-WM pass still resets.
3. ✅ Before 18 of 1000, fixed 0 of 1000, mutant 20 of 1000.
4. ✅ Both passes match the stated baseline on `692cd3a432` (38 / 255, 38 / 228), before and after the fix, and
   equal the before gate on `2a1271703b`, per class and per method.

### Notes
- Accepted and documented, not stories:
  - The holder is the script's first background process that relies on SIGTERM's default action (`xprop` sets no
    handler). If the script is started with SIGTERM ignored, the holder inherits the ignored SIGTERM, and
    `stop_process`'s `wait` blocks. The GitHub runner and an interactive shell start the script with the default.
  - A holder that printed and then vanished, which only an outside kill or a dying server can cause, ends
    `start_xvfb` with the generic "Xvfb did not start" message.
- The readiness check needs `xprop -spy` to flush its line before it blocks. On the CI images `ubuntu-24.04` and
  `ubuntu-24.04-arm`, the step installs x11-utils 7.7+6build2, that is xprop 1.2.6, whose `-spy` loop calls
  `fflush(stdout)` before `XNextEvent`. WSL's xprop 1.2.7 has the same code. Read from the source and the logs of submit
  run 42941178735; the fix has not run on CI yet.
- The mutant's shape differs from the before run (16 near misses against 96; 3-generation hits at 96 to 408 ms in
  `start_openbox` against 94 to 124 ms): its `start_openbox` follows the holder's stop at once, not the "is up"
  line's `grep | tr | paste`, so its first poll lands at another point of the reset. With no `-audit` run of the
  mutant, its hits are not attributed to the holder's close or a poll's; either is the close of the script's last
  client before openbox connected, which the fix removes.
