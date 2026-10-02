# US-052 part 1 — "this thread is ending" notification probe: measured results

Evidence for `US-052-fix-two-glass-windows-toolkit-teardown-gaps.md`. A standalone Windows probe, not shipped and
not built by Maven. Measured 2026-10-01 on one machine. Everything below is what the logs show, not what was
expected; anything not run is marked NOT MEASURED or NOT CHECKED.

Files (all next to this one):

| File | What it is |
|---|---|
| `US-052-thread-end-probe-dll.cpp` | the probe DLL: `probe.dll` (no `DllMain`, like glass.dll) and, with `/DPROBE_DLLMAIN`, `probe_dm.dll` |
| `US-052-thread-end-probe-delayload.cpp` | `probe_dl.dll`, which reaches user32 through delay-load thunks (S8c, S8d) |
| `US-052-thread-end-probe-exe.cpp` | `probe_exe.exe`: the scenarios and a launcher with a 20 s watchdog |
| `US-052-thread-end-probe-log.h` | CRT-free logging shared by the probe DLL and the EXE sources (the delay-load source does not include it) |
| `US-052-thread-end-probe-build.bat` | builds the four binaries with glass.dll's compiler and linker flags |
| `US-052-thread-end-probe-run-all.pl` | runs every scenario in a fresh process, eight passes |
| `US-052-thread-end-probe-summarize.pl` | normalises the logs and prints the figures quoted here |

To rebuild and rerun (Visual Studio 2022 x64 and Git Bash perl; about five minutes). Copy the files to a scratch
directory first: the binaries, `logs/` and `summary.txt` are written next to the sources. None of them is kept in
the tree; this file quotes the figures.

```
cmd /c US-052-thread-end-probe-build.bat
perl US-052-thread-end-probe-run-all.pl
```

All figures below are from runs of exactly these binaries: 176 processes (27 scenarios x 4 passes against
`probe.dll`, 17 scenarios x 4 passes against `probe_dm.dll`). The S2 and S6 runs against `probe_dm.dll` were
added in a second invocation of the driver, without rebuilding.

## Environment

| Item | Value |
|---|---|
| Windows | `Microsoft Windows [Version 10.0.19045.6466]` (`cmd /c ver`), x64 |
| Compiler | `Microsoft (R) C/C++ Optimizing Compiler Version 19.44.35207.1 for x64` (VS 2022 Community, toolset 14.44.35207, `vcvars64.bat`) |
| Compile flags | `/nologo /W3 /EHsc /MD /O2 /DNDEBUG /D_DISABLE_CONSTEXPR_MUTEX_CONSTRUCTOR /DINLINE=__inline /DWIN32 /DIAL /D_LITTLE_ENDIAN /DWIN32_LEAN_AND_MEAN /DUNICODE /D_UNICODE` — `JFX_COMMON_COMPILE_OPTIONS` and the UNICODE pair from `modules/javafx.graphics/native/win.cmake`, Release, and `/MD` from `CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>DLL"` (`native/CMakeLists.txt:32`) |
| Link flags | `/nologo /manifest /opt:REF /incremental:no /dynamicbase /nxcompat` (`CMAKE_SHARED_LINKER_FLAGS`, `win.cmake:75`), `user32.lib`; `probe_dl.dll` adds `/DELAYLOAD:user32.dll delayimp.lib` as `win.cmake` does for glass |
| `probe.dll` | no `DllMain` of its own (CRT default); imports `VCRUNTIME140.dll`, `VCRUNTIME140_1.dll`, `api-ms-win-crt-*` (= `/MD`); `dumpbin /TLS` shows three TLS callbacks: the probe's `.CRT$XLB` one and the CRT's two for `thread_local` |
| `probe_dm.dll` | the same source with its own `DllMain`; `dumpbin /TLS` shows the same TLS directory and the same three callbacks |
| Loading | `probe_exe.exe` loads the DLL with `LoadLibraryW`; every scenario runs in a fresh process under a 20 s watchdog |
| Windows SDK version | not recorded |

No scenario was run against a real glass.dll or inside a JVM. The probe matches glass.dll's flags and CRT model.
A glass.dll binary was inspected with `dumpbin` (section "glass.dll TLS directory").

## Method

Mechanisms, each logging one line per invocation with `CreateFile(FILE_APPEND_DATA)`/`WriteFile` only:

- **F** — FLS slot (`FlsAlloc` with callback). Armed with `FlsSetValue(slot, <arming thread id>)`.
- **T** — raw image TLS callback (`.CRT$XLB` pointer, `/INCLUDE:_tls_used`), logs its Reason.
- **C** — C++ `thread_local` object with constructor and destructor; "armed" = touched by the thread under test.
- **M** — `DllMain`, in `probe_dm.dll` only (log tag `DM`), logs its Reason and whether `lpReserved` is non-NULL.
- **D** — extra: destructor of a namespace-scope static object in the DLL (run by the CRT at
  `DLL_PROCESS_DETACH`; also used to call `FlsFree` in S5b).
- **W** — S6 only: `RegisterWaitForSingleObject` on a duplicated handle of the thread under test.

Fields per line: QPC time in µs, `GetCurrentThreadId()`, `IsWindow(hwnd)`, `GetWindowThreadProcessId(hwnd)`,
`IsThreadAFiber()`, `RtlDllShutdownInProgress()`, `TryAcquireSRWLockExclusive` on a probe lock, whether the
handle of the thread under test is already signaled, and for F the callback argument next to `FlsGetValue(slot)`
read inside the callback.

Loader lock, two methods; the tables say which one each answer comes from:

- **PEB** — `PEB->LoaderLock` (x64 `gs:[0x60] + 0x110`) → `RTL_CRITICAL_SECTION.OwningThread` compared with the
  current thread id. **Undocumented layout.** Sanity: it reads "owned by me" in every `DLL_PROCESS_ATTACH` /
  `DLL_THREAD_ATTACH` notification and "free" in every line logged from ordinary code.
- **BEH** — behavioural (passes b1, b2, mb1, mb2): the callback starts a helper thread and waits 300 ms for its
  start routine to run. A new thread must get through its `DLL_THREAD_ATTACH` notifications, which need the
  loader lock, before its start routine runs. "helper blocked" = start routine did not run within 300 ms.

Passes: **r1, r2** = `probe.dll`, PEB only; **b1, b2** = `probe.dll`, PEB + BEH; **m1, m2** and **mb1, mb2** =
the same two kinds against `probe_dm.dll`. Roles: MAIN = the EXE's main thread, TUT = thread under test (creates
a hidden top-level window of its own class and arms F and C).

Stability (from the `summary.txt` of the run): the normalised logs of r1 and r2, and of m1 and m2, are compared
whole; for the behavioural passes only the callback lines of non-helper threads are compared, because the helper threads
interleave their own lines. S9b is a stress loop and is compared by its counters. 83 of 86 comparisons are
identical. The three differences are snapshots of another thread's state: in S8a the process-exit lines on MAIN
saw the other thread's window still valid in r1 and already invalid in r2; in S9a (mb1/mb2) one detach line of the
poster thread saw TUT's handle signaled in one pass and not yet in the other; and in S6 (mb1/mb2) the PEB owner
field of the wait-callback line read a helper thread's id in one pass and 0 in the other (a helper thread of the
behavioural test doing its own attach).

The window procedure logged `WM_NCCREATE` and `WM_CREATE` and **no** `WM_DESTROY`, `WM_NCDESTROY`, `WM_CLOSE` or
any other message after its thread started to end, in any run that logs window messages (all but S9b).

## S1 — thread returns from its thread procedure, window alive

Same in S1a (thread created before `LoadLibrary`) and S1b (created after), r1 r2 b1 b2. Order as logged.

| # | What fired | On thread | IsWindow / owner inside | Loader lock inside |
|---|---|---|---|---|
| 1 | F callback (arg = TUT's value; `FlsGetValue` inside = same value) | TUT | 1 / TUT | **not owned** — PEB owner 0; BEH helper ran within 300 ms |
| 2 | T `DLL_THREAD_DETACH` | TUT | 1 / TUT | **owned** — PEB; BEH helper blocked |
| 3 | C destructor (armed=1) | TUT | 1 / TUT | **owned** — PEB; BEH helper blocked |

- Window procedure: no `WM_DESTROY`, no `WM_NCDESTROY`.
- MAIN, right after the thread handle was signaled: `IsWindow` = 0, `PostMessage` = 0 with `GetLastError` 1400
  (`ERROR_INVALID_WINDOW_HANDLE`); the same 200 ms later.
- S1a only: no T `DLL_THREAD_ATTACH` for TUT (it predates the DLL); T `DLL_THREAD_DETACH` still fires for it. C's
  constructor ran lazily at first touch (no loader lock) instead of at thread attach; its destructor still ran.
- S1b: T `DLL_THREAD_ATTACH` and C's constructor ran on TUT under the loader lock before the thread procedure,
  i.e. the CRT constructs the `thread_local` for **every** thread created after the DLL is loaded, touched or not
  (360 destructor lines with `armed=0` on helper and other threads across the logs).
- Later process exit (MAIN returns): T `DLL_PROCESS_DETACH`, C destructor of MAIN's object, D — all on MAIN, loader
  lock owned (PEB). No F (MAIN had not armed it; TUT's slot was already cleaned up).

## S2 — ConvertThreadToFiber … ConvertFiberToThread

| Variant | At `ConvertFiberToThread` (thread + window alive) | `FlsGetValue` after it | At the later thread exit |
|---|---|---|---|
| S2a (F armed after `ConvertThreadToFiber`) | **nothing fired**: no F, no T, no C (0 callback lines between the "before" and "after" log lines, 4/4 runs) | still TUT's value | F, then T `DLL_THREAD_DETACH`, then C — as S1 |
| S2b (F armed before `ConvertThreadToFiber`) | **nothing fired** (4/4 runs) | still TUT's value | as S1 |
| S2c (armed after conversion, thread returns while still a fiber) | n/a (never converted back) | n/a | F (with `IsThreadAFiber` = 1), then T, then C — as S1 |

`ConvertFiberToThread` returned TRUE in every run. On this build F does **not** run at `ConvertFiberToThread`.

The same three variants against `probe_dm.dll` (passes m1, m2, mb1, mb2), with M logging next to T:
**M did not fire at `ConvertFiberToThread` either** — 0 callback lines of any mechanism (F, T, C, M) between the
"before" and "after" log lines in 8/8 runs of S2a and S2b, and `FlsGetValue` afterwards still TUT's value. At the
later thread exit the order was F, T `DLL_THREAD_DETACH`, C, M `DLL_THREAD_DETACH`, in all 12 runs, including S2c
where the thread returns while still a fiber (`IsThreadAFiber` = 1 inside F and M).

## S3 — DeleteFiber of the fiber that armed F and created the window

TUT converts to fiber F1, creates F2, switches to F2; F2 creates the window and arms F; back on F1
(`FlsGetValue` on F1 = 0).

| Variant | At `DeleteFiber(F2)` | F callback thread | IsWindow / owner inside F | Loader lock inside F | `FlsGetValue` inside F vs argument | At the later thread exit (F1) |
|---|---|---|---|---|---|---|
| S3a — TUT deletes F2 | **F fires**; no T, no C | **TUT** | 1 / TUT (thread and window alive) | not owned — PEB 0; BEH helper ran | 0 vs TUT's value (differs) | T `DLL_THREAD_DETACH` and C only; **no F** (F1 never armed) |
| S3b — MAIN deletes F2 while TUT waits on F1 | **F fires**; no T, no C | **MAIN** (the deleting thread) | 1 / TUT | not owned — PEB 0; BEH helper ran | 0 vs TUT's value (differs) | T `DLL_THREAD_DETACH` and C only; **no F** |

So F runs at `DeleteFiber` with the OS thread and the window alive: on the owning thread in S3a (a thread-id
check would pass), on a different thread in S3b. In both variants the thread's real exit later produces no F.

## S4 — process exit

MAIN also armed F and C here, so each F line shows whose slot it is.

| Variant | F | T | C | D | Thread | Loader lock | Window inside the callbacks |
|---|---|---|---|---|---|---|---|
| S4a — MAIN calls `ExitProcess`, TUT blocked | fires once, for **MAIN's** value only | `DLL_PROCESS_DETACH` (no `DLL_THREAD_DETACH` for anyone) | MAIN's object only | fires | all on MAIN | owned (PEB). BEH could not answer: `CreateThread` failed, error 5 | F line: `IsWindow` 1, owner TUT, TUT handle not signaled. T/C/D lines: `IsWindow` 0, TUT handle signaled |
| S4b — TUT calls `ExitProcess` | fires once, for **TUT's** value only | `DLL_PROCESS_DETACH` | TUT's object only | fires | all on TUT | owned (PEB); BEH: `CreateThread` error 5 | `IsWindow` 1, owner TUT in all four |
| S4c — MAIN returns from `wmain` (CRT exit) | as S4a | as S4a | as S4a | as S4a | all on MAIN | as S4a | as S4a |

- Order in every run: F, then T `DLL_PROCESS_DETACH`, then C, then D. `RtlDllShutdownInProgress()` = 1 in all of
  them (0 in every thread-exit, fiber and `FreeLibrary` callback).
- A thread that is killed by the process exit gets **no** F, T or C of its own; its FLS value never reaches the
  callback (S4a/S4c: no line with TUT's value; S4b: no line with MAIN's value).
- S4a/S4c, state of the other thread: in all 16 runs F ran before TUT was gone (window still valid) and T ran
  after. That state is another thread's and is not synchronised with the callbacks: S8a shows the same fields
  differing between two passes (see Stability), so treat the window state of another thread as racy here.
- The TLS callback's `Reserved` argument was NULL for `DLL_PROCESS_DETACH` in every run, at process exit and at
  `FreeLibrary` alike (unlike `DllMain`'s `lpReserved`, see S11).

Variants for a callback that blocks at process exit:

| Variant | Setup | Measured |
|---|---|---|
| S4d | S4b + a holder thread that took the probe SRWLOCK **shared** and blocks for ever | In F, T, C, D during exit: `TryAcquireSRWLockExclusive` = 0 (the holder was killed with the lock held; nothing will release it). Exit code 0, no hang |
| S4e | S4d, and F calls `AcquireSRWLockExclusive` | **No hang.** The acquire never returned ("acquired" line absent), the process was gone 4–8 ms after the "blocking" line with exit code 0, and **no further callback ran** (no T `DLL_PROCESS_DETACH`, no C, no D). 4/4 runs |
| S4f | control: S4b, and F calls `WaitForSingleObject(never-signaled event, INFINITE)` | **Hang.** Watchdog killed the child after 20 s (exit code 0xdead), 4/4 runs. Shows the watchdog detects a real hang, and that a block other than the SRW acquire does hang |

Reading S4e and S4f together: on this build a contended SRW acquire inside a callback during process shutdown
ended the process on the spot instead of deadlocking. Why (ntdll's shutdown handling of lock waits) was not
investigated; it is an observation on 10.0.19045.6466, not documented behaviour.

## S5 — FreeLibrary(probe.dll) while the thread under test is alive

| Variant | At `FreeLibrary` (all on MAIN, the caller; loader lock owned — PEB; BEH helper blocked) | Later, TUT returns from its thread procedure |
|---|---|---|
| S5a — DLL does not call `FlsFree` | T `DLL_PROCESS_DETACH`; C destructor of MAIN's object only; D. **No F.** Nothing for TUT. `GetModuleHandle` of the DLL = NULL afterwards | **Crash**: unhandled `0xC0000005` on TUT, exception address inside the former DLL range, DLL not loaded — the FLS slot still points at the unloaded callback. Process exit code `0xc0000005`. 8/8 runs (4 per DLL) |
| S5b — D calls `FlsFree(slot)` | T `DLL_PROCESS_DETACH`; C (MAIN's only); D; then inside `FlsFree`: **F fires on MAIN for TUT's value**, `IsWindow` 1 / owner TUT, TUT alive, loader lock owned (PEB; BEH helper blocked), `FlsGetValue` inside = 0 | No callback of any kind (no F, no T, no C — TUT's `thread_local` object is never destroyed). No crash; `IsWindow` = 0 after the handle is signaled; exit code 0 |

The window was alive (`IsWindow` 1, owner TUT) in every callback at `FreeLibrary`.

## S6 — thread-handle wait (`RegisterWaitForSingleObject` on a duplicated handle), thread exits as in S1

| Measurement | r1 | r2 | b1 / b2 |
|---|---|---|---|
| F, T `DLL_THREAD_DETACH`, C on TUT | as S1 | as S1 | as S1 |
| Wait callback fired | yes, on a thread-pool thread, `timedOut` = 0 | yes | yes |
| `IsWindow(hwnd)` in the wait callback | 0 | 0 | 0 |
| `PostMessage(hwnd, WM_APP+1)` in the wait callback | 0, `GetLastError` 1400 | 0, 1400 | 0, 1400 |
| Loader lock in the wait callback (PEB) | not owned | not owned | not owned by the callback thread |
| "U returning" line → F line | 78 µs | 67 µs | 122 / 33 µs |
| "U returning" line → T `DLL_THREAD_DETACH` line | 119 µs | 107 µs | not comparable |
| T `DLL_THREAD_DETACH` line → wait-callback line | **1050 µs** | **1153 µs** | not comparable (each callback line is written after its own 300 ms helper wait) |

In every run the window was already gone when the wait callback ran.

Against `probe_dm.dll` (m1, m2, mb1, mb2) the result is the same: F, T, C and then M `DLL_THREAD_DETACH` on TUT,
then the wait callback on a thread-pool thread with `IsWindow` 0 and `PostMessage` failing with 1400. T
`DLL_THREAD_DETACH` line → wait-callback line: 831 µs (m1) and 1397 µs (m2); M `DLL_THREAD_DETACH` line →
wait-callback line: 818 µs and 1373 µs.

## S7 — TerminateThread on the thread under test

| Measurement | Result (4/4 runs) |
|---|---|
| F / T / C for TUT at `TerminateThread` | none fired |
| Window procedure | no `WM_DESTROY` / `WM_NCDESTROY` |
| `IsWindow` on MAIN right after `TerminateThread` returned (handle not yet waited) | 1, owner TUT |
| `IsWindow` on MAIN after the thread handle was signaled, and 200 ms later | 0; `PostMessage` = 0, error 1400 |
| At the later process exit (MAIN returns) | T `DLL_PROCESS_DETACH`, C (MAIN's object), D on MAIN; **no F** for the terminated thread's value |

## S8 — loader lock vs `PostMessageW` / `SendNotifyMessageW`

Helper thread H returns from its thread procedure and parks inside its T `DLL_THREAD_DETACH` callback (waits on
an event), so H owns the loader lock for the whole measurement: PEB owner = H in every line logged during the
park, and in the b passes the BEH helper started from H's callback was blocked. A third thread WT owns the live
hidden window W and pumps. The caller thread P was created before the park, and calls
`PostMessageW(W, WM_APP+1)` then `SendNotifyMessageW(W, WM_APP+2)` while H is parked; MAIN waits 2 s for each.
H is released 300 ms after the calls. P logs without calling any user32 function.

| Variant | Caller | `PostMessageW` (r1 / r2 / b1 / b2) | `SendNotifyMessageW` (r1 / r2 / b1 / b2) | Returned within 2 s | W's window procedure got the message while H was still parked | Needed H's release to complete |
|---|---|---|---|---|---|---|
| S8a | P1: **no user32 call ever** before (only waited on an event) | ret 1, error 0; 7 / 7 / 9 / 43 µs | ret 1, error 0; 13 / 20 / 27 / 24 µs | yes, both, 4/4 | yes, both messages, 4/4 | no |
| S8b | P2: had already called `IsGUIThread(TRUE)`, `PeekMessageW`, `GetDesktopWindow` | ret 1, error 0; 8 / 9 / 22 / 24 µs | ret 1, error 0; 7 / 9 / 12 / 14 µs | yes, 4/4 | yes, 4/4 | no |
| S8c | P1 as S8a, but the calls go through `probe_dl.dll`, which **delay-loads user32** as glass.dll does; the thunks are resolved for the **first time in the process** by these calls | ret 1, error 0; 14 / 16 / 28 / 189 µs | ret 1, error 0; 7 / 37 / 15 / 89 µs | yes, 4/4 | yes, 4/4 | no |
| S8d | as S8c, thunks already resolved by a call from MAIN before the park | ret 1, error 0; 12 / 15 / 13 / 13 µs | ret 1, error 0; 5 / 5 / 5 / 4 µs | yes, 4/4 | yes, 4/4 | no |

No call blocked: in all 16 runs both calls returned in under 0.2 ms with the loader lock owned by H before and
after the call, and WT dispatched both messages while H was still parked. No deadlock evidence.

Why S8c/S8d exist: `dumpbin /imports` of a built glass.dll (see "glass.dll TLS directory") lists `USER32.dll`
under "delay load imports" (`PostMessageW`, `SendMessageW`, …), matching `win.cmake`'s `/DELAYLOAD:user32.dll`.
A first call through a delay-load thunk runs `LoadLibrary` + `GetProcAddress`. With user32.dll already loaded in
the process (the probe EXE imports it statically; NOT MEASURED with user32 absent) that resolution did not wait
for the loader lock.

## S9 — `DLL_THREAD_DETACH` takes the SRWLOCK exclusively while posters hold it shared

**S9a / S9c — one parked poster.** TUT owns W and returns from its thread procedure; its T `DLL_THREAD_DETACH`
callback logs "acquiring" and calls `AcquireSRWLockExclusive`. A poster thread already holds the lock **shared**
and is parked just before its `PostMessageW(W)`; MAIN releases it 300 ms after the "acquiring" line.
S9a: the poster had made user32 calls before. S9c: the `PostMessageW` is the poster's first user32 call.

| Measurement | S9a (r1 / r2 / b1 / b2) | S9c (r1 / r2 / b1 / b2) |
|---|---|---|
| Callback on TUT, loader lock owned (PEB), `IsWindow(W)` at "acquiring" | yes, 1 | yes, 1 |
| TUT handle signaled while the poster was still parked (300 ms after "acquiring") | no, 4/4 — the callback is blocked | no, 4/4 |
| Poster's `PostMessageW(W)` with TUT blocked in the callback (loader lock owner = TUT before and after the call) | ret 1, error 0; 12 / 14 / 16 / 25 µs | ret 1, error 0; 62 / 119 / 179 / 78 µs |
| Time the callback waited in `AcquireSRWLockExclusive` | 301 / 309 / 312 / 312 ms | 302 / 305 / 309 / 312 ms |
| `IsWindow(W)` inside the exclusive section | 1, owner TUT, 4/4 | 1, owner TUT, 4/4 |
| After release: C destructor on TUT, thread handle signaled | yes, 4/4 | yes, 4/4 |
| `IsWindow(W)` on MAIN after the handle was signaled; `PostMessage` | 0; fails, error 1400 | 0; fails, error 1400 |
| Hang / exit code | none / 0 | none / 0 |

The message the poster posted was never dispatched (TUT does not pump again); only the call's result was measured.

**S9b — 200 thread exits against four posters, one process.** Each iteration: a new TUT creates W, publishes it,
pumps 3 ms and returns; its T `DLL_THREAD_DETACH` takes the lock exclusively, clears the published HWND and
releases. Two persistent posters (already GUI threads) loop `shared → read HWND → PostMessageW → release`, about
20 µs apart. Two fresh poster threads are created per iteration and make their **first-ever user32 call** inside
the shared section: poster A holds the shared lock until the loader lock is owned by the exiting TUT (PEB,
bounded 5 ms) and only then posts; poster B posts as soon as TUT signals that it is about to return.

| Measurement | r1 | r2 | b1 | b2 |
|---|---|---|---|---|
| Iterations completed (TUT exit and both fresh posters finished) | 200 / 200 | 200 / 200 | 200 / 200 | 200 / 200 |
| Exclusive acquire time in the callback: min / median / p95 / max (µs) | 38 / 84 / 127 / 202 | 0 / 83 / 150 / 273 | 32 / 85 / 116 / 200 | 0 / 84 / 135 / 251 |
| `IsWindow(W)` inside the exclusive section | 1 in 200 | 1 in 200 | 1 in 200 | 1 in 200 |
| Persistent posters: posts ok / failed | 39 253 / 0 | 38 564 / 0 | 38 580 / 0 | 39 089 / 0 |
| Fresh poster A: first user32 call made / of those with TUT owning the loader lock / ok | 187 / 187 / 187 | 188 / 188 / 188 | 189 / 189 / 189 | 195 / 195 / 195 |
| Fresh poster B: first user32 call made / of those with TUT owning the loader lock / ok | 195 / 49 / 195 | 191 / 50 / 191 | 198 / 46 / 198 | 196 / 73 / 196 |
| Wall time for 200 iterations | 1.04 s | 1.05 s | 1.06 s | 1.05 s |
| Hang / exit code | none / 0 | none / 0 | none / 0 | none / 0 |

Where a fresh poster made no call, the published HWND had already been cleared when it got the shared lock. No
post failed in any pass, i.e. no poster reached a dead window while holding the shared lock. In total 759
first-ever user32 calls by poster A were made while the exiting thread owned the loader lock and was waiting for
the exclusive lock; all returned.

## S10 — two more thread-exit shapes

TUT owns the window and armed F and C, as in S1.

| Variant | What fired, in order, all on TUT | `IsWindow` / owner inside | Loader lock inside | Afterwards on MAIN |
|---|---|---|---|---|
| S10a — TUT calls `ExitThread(0)` | F (argument = `FlsGetValue` = TUT's value); T `DLL_THREAD_DETACH`; C destructor (armed=1) | 1 / TUT in all three | F: not owned (PEB 0; BEH helper ran). T, C: owned (PEB; BEH helper blocked) | handle signaled; `IsWindow` 0; `PostMessage` error 1400 |
| S10b — TUT calls `ConvertThreadToFiber`, then `DeleteFiber` on its own running fiber | same three, same order; `IsThreadAFiber` = 1 in each; `DeleteFiber` did not return | 1 / TUT | as S10a | as S10a |

Identical to S1 in every respect, 4/4 passes each. No `WM_DESTROY` / `WM_NCDESTROY` reached the window procedure.

## S11 — the same with a `DllMain` (mechanism M, `probe_dm.dll`)

`probe_dm.dll` is `probe.dll` plus a `DllMain` that logs every Reason with the same fields; T stays in it, so
the log shows the order of T, C and M. Run under it: S1a, S1b, S2a, S2b, S2c, S3a, S3b, S4a, S4b, S4c, S5a, S6,
S7, S9a, S10a, S10b and S12, passes m1, m2 (PEB) and mb1, mb2 (PEB + BEH). In S9a the exclusive acquire is done
in M's `DLL_THREAD_DETACH` instead of T's. F, T and C behaved exactly as with `probe.dll` in every scenario.

| Question for M | Measured (4/4 passes unless noted) |
|---|---|
| `DLL_THREAD_DETACH` at thread exit: return (S1b), `ExitThread` (S10a), `DeleteFiber` on the running fiber (S10b) | **yes**, on the exiting thread |
| … for a thread that predates `LoadLibrary` (S1a) | **yes** (no `DLL_THREAD_ATTACH` was delivered for it, the detach still is) |
| Order at thread exit | F, T `DLL_THREAD_DETACH`, C destructor, **M `DLL_THREAD_DETACH` last** |
| Order at thread start (thread created after the load) | T `DLL_THREAD_ATTACH`, C constructor, then M `DLL_THREAD_ATTACH` |
| `IsWindow` / owner inside M `DLL_THREAD_DETACH` | 1 / the exiting thread |
| Loader lock inside M `DLL_THREAD_DETACH` | **owned** — PEB; BEH helper blocked (22/22 lines in mb1, mb2) |
| At `ConvertFiberToThread` (S2a, S2b) | **no** M, and no F, T or C either: nothing fired, 8/8 runs; M `DLL_THREAD_DETACH` comes at the real thread exit, also when the thread exits while still a fiber (S2c) |
| At `DeleteFiber` of a sibling fiber, same thread (S3a) or other thread (S3b) | **no** M (F only, as with `probe.dll`); M `DLL_THREAD_DETACH` comes at the real thread exit |
| At process exit (S4a, S4b, S4c) | no `DLL_THREAD_DETACH` for any thread; M `DLL_PROCESS_DETACH` on the thread that exits the process, after F, T and C and before D; **`lpReserved` non-NULL** |
| At `FreeLibrary` (S5a) | no `DLL_THREAD_DETACH`; M `DLL_PROCESS_DETACH` on the thread calling `FreeLibrary`, after T and C and before D; **`lpReserved` NULL**; nothing for the surviving thread afterwards (its later exit crashes in F's dangling callback, as with `probe.dll`) |
| At `TerminateThread` (S7) | **nothing** for the terminated thread |
| `lpReserved` overall | non-NULL in 64 of 64 `DLL_PROCESS_DETACH` at process exit, NULL in 4 of 4 at `FreeLibrary`, NULL for every other Reason. T's `Reserved` was NULL in all of these |
| S9a with the exclusive acquire in M | as with T: TUT's handle not signaled while the poster was parked; poster's `PostMessageW` ret 1, error 0 (169 / 15 / 22 / 37 µs); the acquire waited 310 / 308 / 314 / 315 ms; `IsWindow` 1 inside; exit completed; no hang |

What defining `DllMain` changed, and what it did not:

- `dumpbin /TLS` is unchanged: the same TLS directory and the same three callbacks in both DLLs.
- Thread notifications reach `DllMain`: M logged `DLL_THREAD_ATTACH` and `DLL_THREAD_DETACH` for every thread
  created after the load.
- The CRT's default `DllMain` stub (`vcruntime/dll_dllmain_stub.cpp` of toolset 14.44, read, not measured) calls
  `DisableThreadLibraryCalls` at `DLL_PROCESS_ATTACH` in a `/MD` DLL that defines neither `DllMain` nor
  `_pRawDllMain`. A DLL with its own `DllMain` does not link that stub.
- **S12** measures what that call does to these DLLs: the EXE calls `DisableThreadLibraryCalls(hModule)` after
  loading, then starts a thread that returns. The call **returned TRUE with error 0** for both DLLs (8/8 runs),
  and the thread created afterwards still got T `DLL_THREAD_ATTACH` and `DLL_THREAD_DETACH` (both DLLs) and M
  `DLL_THREAD_ATTACH` and `DLL_THREAD_DETACH` (`probe_dm.dll`). So for a DLL with static TLS the call reported
  success and disabled nothing on this build, which is what the comment at `GlassApplication.cpp:245-249`
  relies on. Whether it would take effect in a DLL without a TLS directory was NOT MEASURED.

## glass.dll TLS directory (read-only check on an existing binary)

No glass.dll built from the current HEAD was available (no `modules/javafx.graphics/target` on the machine, and
Maven was not run for this probe). The newest binary on the machine from an earlier local build of this
fork was inspected with `dumpbin /TLS`, `/headers`, `/dependents`, `/exports` and `/imports`:

| Item | Value |
|---|---|
| File | `glass.dll`, 247 296 bytes, file time 2026-09-26 11:30 |
| PE link time stamp, linker | Fri Sep 25 01:11:14 2026, linker 14.44, x64 |
| Is it this fork's glass | yes: 106 exports including `gwin_app_create`; imports `VCRUNTIME140.dll`, `MSVCP140.dll`, `api-ms-win-crt-*` (`/MD`) |
| TLS directory | **present** — RVA `0x29500`, size `0x28`; raw data 8 bytes, 4-byte align |
| TLS callbacks | **0** — the callback array holds only the terminating NULL |
| Delay-load imports | `urlmon.dll`, `SHELL32.dll`, `UIAutomationCore.DLL`, `dwmapi.dll`, `VERSION.dll`, **`USER32.dll`** (incl. `PostMessageW`, `SendMessageW`) |

So a binary confirms the claim in `GlassApplication.cpp:245-249` that glass.dll has static TLS. It has no TLS
callback today (`probe.dll` has three: its own and the CRT's two for a `thread_local` with a destructor). A build
of the current HEAD was NOT CHECKED.

## Summary

"Window alive" = `IsWindow(hwnd)` inside the callback. LL = loader lock owned by the calling thread inside the
callback; (PEB) / (BEH) name the method, "both" = the two agree.

| Mechanism | Thread exit (window alive) | `ConvertFiberToThread` | `DeleteFiber` of another fiber, same thread / other thread | Process exit | `FreeLibrary` | Window alive inside | LL inside |
|---|---|---|---|---|---|---|---|
| **F** FLS callback | **yes**, on the exiting thread, before T — but only if the fiber that ends the thread is the one that armed the slot (S3: no F at thread exit) | **no** (S2a, S2b) | **yes on the owning thread / yes on the deleting thread**, thread and window alive | **yes**, only for the value of the thread that calls `ExitProcess` or returns from main, on that thread; nothing for threads killed by the exit | **no**; the slot keeps pointing into the unloaded DLL and the next thread exit crashes (S5a). With `FlsFree` at unload: **yes, on the unloading thread**, for the other thread's value (S5b) | thread exit 1; `DeleteFiber` 1; `FlsFree` 1; process exit 1 on the owning thread, racy for another thread's window | thread exit **no** (both); `DeleteFiber` **no** (both); process exit **yes** (PEB; BEH not answerable); `FlsFree` in a static destructor at unload **yes** (both) |
| **T** `DLL_THREAD_ATTACH` | n/a — fires at thread start for threads created after the load, on the new thread; not for threads that predate the load | no | no / no | no | no | n/a | yes (both) |
| **T** `DLL_THREAD_DETACH` | **yes**, on the exiting thread, also for a thread that predates `LoadLibrary` (S1a) | **no** | **no / no** | **no** — not for the exiting thread, not for the killed ones | no | 1 | **yes** (both) |
| **T** `DLL_PROCESS_ATTACH` / `DLL_PROCESS_DETACH` | no | no | no / no | `DLL_PROCESS_DETACH` **yes**, on the thread that exits the process; `Reserved` NULL | `DLL_PROCESS_DETACH` **yes**, on the thread calling `FreeLibrary`; `Reserved` NULL; nothing afterwards for surviving threads | exit: 1 if the exiting thread owns the window (S4b), 0 otherwise (S4a/c); `FreeLibrary`: 1 | **yes** (PEB; BEH agrees at `FreeLibrary`, not answerable at process exit) |
| **M** `DllMain` `DLL_THREAD_DETACH` (S11) | **yes**, on the exiting thread, also for a thread that predates `LoadLibrary`; **after** T and after C's destructor | **no** (S2a, S2b under `probe_dm.dll`) | **no / no** | **no** | no | 1 | **yes** (both) |
| **M** `DllMain` `DLL_PROCESS_DETACH` (S11) | no | no | no / no | **yes**, on the thread that exits the process, after T and C; **`lpReserved` non-NULL** | **yes**, on the thread calling `FreeLibrary`; **`lpReserved` NULL** | as T | **yes** (PEB; BEH agrees at `FreeLibrary`) |
| **C** `thread_local` destructor | **yes**, on the exiting thread, right after T `DLL_THREAD_DETACH` (and before M); also for every other thread created after the load, touched or not | **no** | **no / no** | **yes**, only the exiting thread's object, on that thread | only the unloading thread's object; the surviving thread's object is never destroyed (no crash) | same as T | **yes** (both; PEB only at process exit) |
| **W** thread-handle wait | **yes**, on a thread-pool thread, 0.8–1.4 ms after the T line | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | **0** — window already gone; `PostMessage` fails with 1400 | no (PEB) |
| (`TerminateThread`) | F, T, M, C: none | | | | | | |

"Thread exit" was measured for three shapes with the same result: returning from the thread procedure (S1, S2,
S6), `ExitThread` (S10a) and `DeleteFiber` on the running fiber (S10b).

For a `DLL_THREAD_DETACH` notification (T or M) that takes an SRWLOCK exclusively under the loader lock while
other threads hold it shared across one `PostMessageW` or one `SendNotifyMessageW`:

| Question | Measured |
|---|---|
| Does `PostMessageW` / `SendNotifyMessageW` to another thread's window wait for the loader lock? | **No** in 16/16 runs (S8a–d): both returned in under 0.2 ms while another thread held the loader lock, and the target window received both messages during that time |
| Also when it is the calling thread's first user32 call? | **No** (S8a, S8c, S9c; 759 such calls in S9b with the exiting thread owning the loader lock) |
| Also through a delay-load thunk that is resolved by that very call (glass.dll delay-loads user32)? | **No** (S8c), with user32.dll already loaded in the process |
| Does the notification wait for a shared holder and then proceed? | **Yes** (S9a and S9c in T, S9a in M): blocked for the 300 ms the poster was parked, then acquired; `IsWindow` = 1 inside; thread exit then completed |
| Under load? | S9b (T): 800/800 thread exits completed; exclusive acquire median 83–85 µs, max 273 µs; no failed post, no hang |
| Deadlock or hang in any S8, S9, S10, S11 or S12 run? | **None** |

### Three points the story's FLS revision left unverified, as measured

1. *Callback runs on the exiting thread before the system frees the window* — measured yes for F, T, C and M at
   a normal thread exit (`IsWindow` 1, owner = the exiting thread, in every pass of S1a, S1b, S2a–c, S6, S10a,
   S10b, and after a 300 ms blocked exclusive acquire in S9a/S9c). The window procedure never received
   `WM_DESTROY`/`WM_NCDESTROY`; the window was invalid by the time the thread handle was signaled.
2. *Loader lock* — F at thread exit and at `DeleteFiber` ran **without** the loader lock (PEB and BEH agree).
   F ran **with** it at process exit (PEB) and inside `FlsFree` called during `FreeLibrary` (both). T, C and M
   always ran with it. The assumption that a shared holder inside one `PostMessage` or `SendNotifyMessage` does
   not need that lock held in every S8 and S9 run.
3. *Process exit* — F does run at process exit, on the thread that exits the process and for that thread's value
   only, with `RtlDllShutdownInProgress()` = 1. With the lock held shared by a killed thread, a blocking
   `AcquireSRWLockExclusive` in F did not deadlock here: the process ended immediately with its normal exit code
   and the remaining detach callbacks were skipped (S4e). A different kind of block did hang (S4f). T and M get
   no `DLL_THREAD_DETACH` at process exit at all.

### The review finding on the FLS design, as measured

- "`ConvertFiberToThread` runs the callback" — **not reproduced** (S2a, S2b: nothing fired, value preserved).
- "the callback runs on fiber deletion with the thread and window alive" — **reproduced** with `DeleteFiber` of a
  fiber that armed the slot (S3a on the owning thread, S3b on another thread), and additionally with `FlsFree`
  (S5b, on the thread that frees the index).
- Fields that differed between "thread is really ending" and "fiber deleted / index freed" in the F callback, in
  every run: `FlsGetValue(slot)` inside the callback equalled the callback argument at thread exit and at process
  exit (S1, S2, S2c, S4, S6, S10a, S10b) and was 0 at `DeleteFiber` of another fiber and `FlsFree` (S3a, S3b,
  S5b). `IsThreadAFiber()` did **not** separate them (1 at thread exit in S2c and S10b, 1 in S3a, 0 in S3b). This
  is an observation of this build's behaviour with one armed fiber per thread, not a documented contract, and it
  was not tested with the current fiber holding a value of its own.
- T and M `DLL_THREAD_DETACH` fired at thread exit only (return, `ExitThread`, `DeleteFiber` on the running
  fiber) — never at `ConvertFiberToThread`, `DeleteFiber` of another fiber, process exit, `FreeLibrary` or
  `TerminateThread` — but always under the loader lock. Neither therefore gives a notification when the toolkit
  thread is killed by process exit or `TerminateThread`, or after the DLL was unloaded.

## Not measured

- **A real glass.dll in a JVM**: `System.load`, the JVM's own exit path (`System.exit`, `DestroyJavaVM`), whether
  the JVM ever unloads glass.dll. S4b only models "the toolkit thread calls `ExitProcess`". The glass.dll that
  was inspected with `dumpbin` was linked 2026-09-25; a build of the current HEAD was NOT CHECKED.
- **M in S4d–f, S5b, S8, S9b, S9c**: those scenarios were run against `probe.dll` only.
- **Delay-load resolution with user32.dll not yet loaded** (S8c had it loaded, as a JVM process does), and
  delay-load thunks of the other delay-loaded DLLs.
- **`SendMessage`-family calls other than `SendNotifyMessageW`** (`SendMessageW`, `SendMessageTimeoutW`) under
  the shared lock, and a `SendNotifyMessageW` to a window of the *calling* thread (which runs the window
  procedure inline): not run. S8/S9 cover exactly one `PostMessageW` or one `SendNotifyMessageW` to a window of
  another thread.
- **A T or M notification that takes the exclusive lock at process exit**: neither delivers `DLL_THREAD_DETACH`
  there (S4, S11), so the S4e/S4f blocking measurements exist for F only. A `DLL_PROCESS_DETACH` handler that
  blocks was not run.
- **`DisableThreadLibraryCalls` on a DLL without a TLS directory** (S12 ran it only on the two probe DLLs, which
  have one).
- **W (handle wait) outside S6**: the wait was registered only in S6, so its behaviour at fiber operations,
  process exit, `FreeLibrary` and `TerminateThread` is NOT MEASURED. (S7 shows the handle becomes signaled after
  `TerminateThread` through MAIN's own `WaitForSingleObject`, nothing more.)
- **BEH at process exit**: `CreateThread` fails with error 5 once shutdown has begun, so loader-lock ownership
  there rests on the undocumented PEB read alone.
- **Why S4e ends the process**: not investigated (no debugger, no ntdll disassembly).
- **Other Windows versions**: only 10.0.19045.6466 x64. FLS and shutdown internals are known to differ between
  Windows releases; Windows 11 / Server and x86 / ARM64 were not run.
- **Thread-id reuse**: not part of this probe.
