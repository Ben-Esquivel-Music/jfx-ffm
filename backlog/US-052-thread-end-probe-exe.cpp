// US-052-thread-end-probe-exe.cpp
// Evidence for backlog story US-052 (Glass Windows toolkit teardown). NOT shipped and NOT built by Maven.
// Driver for the probe DLLs built from US-052-thread-end-probe-dll.cpp:
//   probe_exe run <scenario> [beh] [dm]   launcher: runs "probe_exe <scenario> ..." under a 20 s watchdog
//   probe_exe <scenario> [beh] [dm]       runs one scenario in this process
//     beh  also run the behavioural loader-lock test inside every callback
//     dm   load probe_dm.dll (the variant with its own DllMain) instead of probe.dll
// The log file comes from the PROBE_LOG environment variable (shared with the DLLs). The scenarios S1..S12 are
// described in US-052-thread-end-probe-results.md.
#include "US-052-thread-end-probe-log.h"
#include <string.h>
#include <wchar.h>

enum {   // must match US-052-thread-end-probe-dll.cpp
    FLAG_BEHAVIOURAL = 1, FLAG_FLSFREE_ON_UNLOAD = 2, FLAG_BLOCK_SRW_IN_FLS = 4, FLAG_BLOCK_EVENT_IN_FLS = 8,
    FLAG_QUIET = 16, FLAG_HOOKS_IN_M = 32
};

struct Api {
    uint32_t (*init)(void);
    void (*set_hwnd)(HWND);
    void (*set_thread)(HANDLE);
    void (*set_flags)(uint32_t);
    void (*set_helper)(LPTHREAD_START_ROUTINE);
    int32_t (*arm_fls)(void);
    uint64_t (*get_fls)(void);
    void (*arm_cpp)(void);
    void (*hold_shared)(void);
};
static Api api;
static HMODULE g_dll;
static const wchar_t* g_dllName = L"probe.dll";   // or probe_dm.dll ("dm" argument)
static uintptr_t g_dllBase, g_dllEnd;
static HWND volatile g_hwnd;
static LONG volatile g_logAllMsgs;
static LONG volatile g_logApp, g_hParked, g_quietWnd;   // S8, S9
static char g_scen[16];
static uint32_t g_flags;

static HANDLE evStarted, evGo, evReady, evExit, evFiberReady, evFiberDeleted, evWaitDone, evHeld;
static LPVOID g_f1, g_f2;

static bool is(const char* s) { return strcmp(g_scen, s) == 0; }
static bool starts(const char* p) { return strncmp(g_scen, p, strlen(p)) == 0; }

static void mlog(const char* who, const char* text) {
    PLine l;
    pl_begin(&l, who); pl_s(&l, " "); pl_s(&l, text);
    pl_state(&l, g_hwnd);
    pl_end(&l);
}
static void mlog1(const char* who, const char* text, const char* k, uint64_t v) {
    PLine l;
    pl_begin(&l, who); pl_s(&l, " "); pl_s(&l, text);
    pl_kv(&l, k, v);
    pl_state(&l, g_hwnd);
    pl_end(&l);
}
static void mlog_post(const char* who, const char* text) {
    PLine l;
    pl_begin(&l, who); pl_s(&l, " "); pl_s(&l, text);
    HWND h = g_hwnd;
    BOOL iw = IsWindow(h);
    SetLastError(0);
    BOOL p = PostMessageW(h, WM_APP + 1, 0, 0);
    DWORD gle = GetLastError();
    pl_kv(&l, "isWindowFirst", iw ? 1 : 0);
    pl_kv(&l, "postMessage", p ? 1 : 0);
    pl_kv(&l, "postGle", gle);
    pl_state(&l, h);
    pl_end(&l);
}

static LRESULT CALLBACK WndProc(HWND h, UINT m, WPARAM w, LPARAM lp) {
    if (g_quietWnd) return DefWindowProcW(h, m, w, lp);
    if (g_logApp && (m == WM_APP + 1 || m == WM_APP + 2)) {
        PLine l;
        pl_begin(&l, "WNDPROC");
        pl_s(&l, m == WM_APP + 1 ? " received WM_APP+1 (posted)" : " received WM_APP+2 (SendNotifyMessage)");
        pl_kv(&l, "hParked", (uint64_t)g_hParked);
        pl_kv(&l, "llOwner", pl_loader_lock_owner());
        pl_end(&l);
    }
    if (m == WM_DESTROY || m == WM_NCDESTROY || m == WM_CREATE || m == WM_NCCREATE || m == WM_CLOSE || g_logAllMsgs) {
        PLine l;
        pl_begin(&l, "WNDPROC");
        pl_s(&l, m == WM_DESTROY ? " WM_DESTROY" : m == WM_NCDESTROY ? " WM_NCDESTROY" : m == WM_CREATE ? " WM_CREATE"
               : m == WM_NCCREATE ? " WM_NCCREATE" : m == WM_CLOSE ? " WM_CLOSE" : " other");
        pl_kx(&l, "msg", m);
        pl_state(&l, h);
        pl_end(&l);
    }
    return DefWindowProcW(h, m, w, lp);
}

static void make_window() {
    static ATOM atom;
    HINSTANCE inst = GetModuleHandleW(NULL);
    if (!atom) {
        WNDCLASSEXW wc = { sizeof(wc) };
        wc.lpfnWndProc = WndProc;
        wc.hInstance = inst;
        wc.lpszClassName = L"Us052ProbeWnd";
        atom = RegisterClassExW(&wc);
    }
    HWND h = CreateWindowExW(0, L"Us052ProbeWnd", L"us052 probe", WS_OVERLAPPEDWINDOW, 0, 0, 200, 100, NULL, NULL,
                             inst, NULL);
    g_hwnd = h;
    api.set_hwnd(h);
    mlog("U", "window created (hidden top-level)");
}

static void pump(DWORD ms) {
    DWORD start = GetTickCount();
    for (;;) {
        MSG msg;
        while (PeekMessageW(&msg, NULL, 0, 0, PM_REMOVE)) { TranslateMessage(&msg); DispatchMessageW(&msg); }
        if (GetTickCount() - start >= ms) break;
        Sleep(10);
    }
}
static void pump_until(HANDLE ev) {
    for (;;) {
        DWORD r = MsgWaitForMultipleObjects(1, &ev, FALSE, INFINITE, QS_ALLINPUT);
        if (r == WAIT_OBJECT_0) return;
        MSG msg;
        while (PeekMessageW(&msg, NULL, 0, 0, PM_REMOVE)) { TranslateMessage(&msg); DispatchMessageW(&msg); }
    }
}

static DWORD WINAPI HelperProc(LPVOID p) { SetEvent((HANDLE)p); CloseHandle((HANDLE)p); return 0; }
static DWORD WINAPI HolderProc(LPVOID) {
    api.hold_shared();
    mlog("H", "holder thread: SRW lock held SHARED, now blocking for ever");
    SetEvent(evHeld);
    Sleep(INFINITE);
    return 0;
}

static VOID CALLBACK FiberProc(LPVOID) {
    mlog("U", "on fiber F2");
    make_window();
    int32_t ok = api.arm_fls();
    mlog1("U", "F2 armed F (FlsSetValue)", "ok", (uint64_t)ok);
    SwitchToFiber(g_f1);
    mlog("U", "UNEXPECTED: F2 resumed");
    for (;;) Sleep(1000);
}

// thread under test
static DWORD WINAPI TutProc(LPVOID) {
    mlog("U", "role=thread-under-test started");
    SetEvent(evStarted);
    if (is("S1a")) {
        WaitForSingleObject(evGo, INFINITE);
        mlog("U", "S1a: probe.dll was loaded after this thread was created");
    }
    if (starts("S3")) {
        api.arm_cpp();
        g_f1 = ConvertThreadToFiber(NULL);
        g_f2 = CreateFiber(0, FiberProc, NULL);
        mlog1("U", "converted to fiber F1, created F2, switching to F2", "f1NonNull", g_f1 ? 1 : 0);
        SwitchToFiber(g_f2);
        mlog1("U", "back on F1", "flsValueOnF1", api.get_fls());
        if (is("S3a")) {
            mlog("U", "before DeleteFiber(F2) on owning thread");
            DeleteFiber(g_f2);
            mlog("U", "after DeleteFiber(F2) on owning thread");
        } else {
            SetEvent(evFiberReady);
            WaitForSingleObject(evFiberDeleted, INFINITE);
            mlog("U", "resumed after the other thread deleted F2");
        }
        pump(300);
        InterlockedExchange(&g_logAllMsgs, 1);
        mlog("U", "returning from thread procedure (still fiber F1, F1 never armed F), window alive");
        return 0;
    }
    make_window();
    api.arm_cpp();
    if (starts("S2")) {
        if (is("S2b")) mlog1("U", "S2b: armed F BEFORE ConvertThreadToFiber", "ok", (uint64_t)api.arm_fls());
        LPVOID f = ConvertThreadToFiber(NULL);
        mlog1("U", "ConvertThreadToFiber done", "nonNull", f ? 1 : 0);
        if (is("S2a")) mlog1("U", "S2a: armed F AFTER ConvertThreadToFiber", "ok", (uint64_t)api.arm_fls());
        if (is("S2c")) {
            mlog1("U", "S2c: armed F AFTER ConvertThreadToFiber", "ok", (uint64_t)api.arm_fls());
            pump(100);
            InterlockedExchange(&g_logAllMsgs, 1);
            mlog("U", "S2c: returning from thread procedure while STILL A FIBER (no ConvertFiberToThread), "
                      "window alive");
            return 0;
        }
        mlog1("U", "before ConvertFiberToThread", "flsValue", api.get_fls());
        BOOL ok = ConvertFiberToThread();
        mlog1("U", "after ConvertFiberToThread", "ok", ok ? 1 : 0);
        mlog1("U", "after ConvertFiberToThread", "flsValue", api.get_fls());
        pump(300);
        InterlockedExchange(&g_logAllMsgs, 1);
        mlog("U", "returning from thread procedure, window alive");
        return 0;
    }
    mlog1("U", "armed F and C", "flsOk", (uint64_t)api.arm_fls());
    SetEvent(evReady);
    if (is("S10a")) {
        pump(100);
        InterlockedExchange(&g_logAllMsgs, 1);
        mlog("U", "calling ExitThread(0) explicitly, window alive");
        ExitThread(0);
    }
    if (is("S10b")) {
        LPVOID f = ConvertThreadToFiber(NULL);
        mlog1("U", "ConvertThreadToFiber done", "flsValue", api.get_fls());
        pump(100);
        InterlockedExchange(&g_logAllMsgs, 1);
        mlog("U", "calling DeleteFiber on the currently running fiber, window alive");
        DeleteFiber(f);
        mlog("U", "UNEXPECTED: DeleteFiber(current fiber) returned");
        return 0;
    }
    if (starts("S1") || is("S6")) {
        pump(200);
        InterlockedExchange(&g_logAllMsgs, 1);
        mlog("U", "returning from thread procedure, window alive");
        return 0;
    }
    if (is("S4b") || is("S4d") || is("S4e") || is("S4f")) {
        pump(150);
        mlog("U", "thread under test calling ExitProcess(0)");
        ExitProcess(0);
    }
    if (starts("S5")) {
        pump_until(evExit);
        InterlockedExchange(&g_logAllMsgs, 1);
        mlog("U", "returning from thread procedure (after FreeLibrary), window alive");
        return 0;
    }
    // S4a, S4c, S7: block for ever in a message wait
    pump_until(evExit);
    return 0;
}

static VOID CALLBACK WaitCb(PVOID, BOOLEAN timedOut) {
    PLine l;
    pl_begin(&l, "W wait-callback fired");          // timestamp taken first
    HWND h = g_hwnd;
    BOOL iw = IsWindow(h);
    SetLastError(0);
    BOOL p = PostMessageW(h, WM_APP + 1, 0, 0);
    DWORD gle = GetLastError();
    pl_kv(&l, "timedOut", timedOut ? 1 : 0);
    pl_kv(&l, "isWindowFirst", iw ? 1 : 0);
    pl_kv(&l, "postMessage", p ? 1 : 0);
    pl_kv(&l, "postGle", gle);
    pl_state(&l, h);
    pl_end(&l);
    SetEvent(evWaitDone);
}

static LONG WINAPI Unhandled(EXCEPTION_POINTERS* ep) {
    PLine l;
    uintptr_t a = (uintptr_t)ep->ExceptionRecord->ExceptionAddress;
    pl_begin(&l, "X UNHANDLED EXCEPTION");
    pl_kx(&l, "code", ep->ExceptionRecord->ExceptionCode);
    pl_kx(&l, "address", a);
    pl_kx(&l, "probeDllBase", g_dllBase);
    pl_kv(&l, "addressInsideFormerProbeDll", (a >= g_dllBase && a < g_dllEnd) ? 1 : 0);
    pl_kv(&l, "probeDllStillLoaded", GetModuleHandleW(g_dllName) ? 1 : 0);
    pl_state(&l, g_hwnd);
    pl_end(&l);
    TerminateProcess(GetCurrentProcess(), ep->ExceptionRecord->ExceptionCode);
    return EXCEPTION_EXECUTE_HANDLER;
}

static void load_dll() {
    mlog("M", g_dllName[5] == L'_' ? "calling LoadLibrary(probe_dm.dll)" : "calling LoadLibrary(probe.dll)");
    g_dll = LoadLibraryW(g_dllName);
    if (!g_dll) { mlog1("M", "LoadLibrary FAILED", "gle", GetLastError()); ExitProcess(3); }
    g_dllBase = (uintptr_t)g_dll;
    IMAGE_DOS_HEADER* dos = (IMAGE_DOS_HEADER*)g_dll;
    IMAGE_NT_HEADERS* nt = (IMAGE_NT_HEADERS*)((BYTE*)g_dll + dos->e_lfanew);
    g_dllEnd = g_dllBase + nt->OptionalHeader.SizeOfImage;
    api.init = (uint32_t (*)(void))GetProcAddress(g_dll, "probe_init");
    api.set_hwnd = (void (*)(HWND))GetProcAddress(g_dll, "probe_set_hwnd");
    api.set_thread = (void (*)(HANDLE))GetProcAddress(g_dll, "probe_set_thread");
    api.set_flags = (void (*)(uint32_t))GetProcAddress(g_dll, "probe_set_flags");
    api.set_helper = (void (*)(LPTHREAD_START_ROUTINE))GetProcAddress(g_dll, "probe_set_helper");
    api.arm_fls = (int32_t (*)(void))GetProcAddress(g_dll, "probe_arm_fls");
    api.get_fls = (uint64_t (*)(void))GetProcAddress(g_dll, "probe_get_fls");
    api.arm_cpp = (void (*)(void))GetProcAddress(g_dll, "probe_arm_cpp");
    api.hold_shared = (void (*)(void))GetProcAddress(g_dll, "probe_hold_shared");
    api.set_flags(g_flags);
    api.set_helper(HelperProc);
    uint32_t idx = api.init();
    mlog1("M", "LoadLibrary returned, FlsAlloc done", "flsIndex", idx);
}

static int launcher(int argc, wchar_t** argv) {
    wchar_t exe[MAX_PATH];
    GetModuleFileNameW(NULL, exe, MAX_PATH);
    wchar_t cmd[2048];
    cmd[0] = 0;
    wcscat_s(cmd, L"\""); wcscat_s(cmd, exe); wcscat_s(cmd, L"\"");
    for (int i = 2; i < argc; i++) { wcscat_s(cmd, L" "); wcscat_s(cmd, argv[i]); }
    STARTUPINFOW si = { sizeof(si) };
    PROCESS_INFORMATION pi;
    PLine l;
    if (!CreateProcessW(exe, cmd, NULL, NULL, FALSE, 0, NULL, NULL, &si, &pi)) {
        pl_begin(&l, "L CreateProcess FAILED"); pl_kv(&l, "gle", GetLastError()); pl_end(&l);
        return 2;
    }
    DWORD w = WaitForSingleObject(pi.hProcess, 20000);
    if (w != WAIT_OBJECT_0) {
        pl_begin(&l, "L HANG: child still running after 20000 ms, watchdog terminates it"); pl_end(&l);
        TerminateProcess(pi.hProcess, 0xDEAD);
        WaitForSingleObject(pi.hProcess, 5000);
    }
    DWORD code = 0;
    GetExitCodeProcess(pi.hProcess, &code);
    pl_begin(&l, "L child ended");
    pl_kx(&l, "exitCode", code);
    pl_kv(&l, "hang", w != WAIT_OBJECT_0 ? 1 : 0);
    pl_end(&l);
    return 0;
}

// ====================================================================== S8, S9
struct Api2 {
    void (*release_shared)(void);
    HWND (*get_hwnd)(void);
    void (*set_park)(uint32_t, HANDLE, HANDLE);
    void (*set_detach_acquire)(uint32_t, HANDLE);
};
static Api2 api2;
// S8c, S8d: the calls go through the delay-load thunks of probe_dl.dll
static int (*g_dlPost)(HWND, unsigned), (*g_dlSend)(HWND, unsigned);
static HANDLE evWReady, evPReady, evPGo, evPostDone, evSendDone, evParked, evRelease, evPosterStart, evPosterHolding,
              evAcquiring, evIterGo;

// loggers that make NO user32 call (pl_state calls IsWindow/GetWindowThreadProcessId, these do not)
static void plain(const char* who, const char* text) {
    PLine l;
    pl_begin(&l, who); pl_s(&l, " "); pl_s(&l, text);
    pl_kv(&l, "llOwner", pl_loader_lock_owner());
    pl_end(&l);
}
static void plain1(const char* who, const char* text, const char* k, uint64_t v) {
    PLine l;
    pl_begin(&l, who); pl_s(&l, " "); pl_s(&l, text);
    pl_kv(&l, k, v);
    pl_kv(&l, "llOwner", pl_loader_lock_owner());
    pl_end(&l);
}

static void load_dll_ext() {
    load_dll();
    api2.release_shared = (void (*)(void))GetProcAddress(g_dll, "probe_release_shared");
    api2.get_hwnd = (HWND (*)(void))GetProcAddress(g_dll, "probe_get_hwnd");
    api2.set_park = (void (*)(uint32_t, HANDLE, HANDLE))GetProcAddress(g_dll, "probe_set_park");
    api2.set_detach_acquire = (void (*)(uint32_t, HANDLE))GetProcAddress(g_dll, "probe_set_detach_acquire");
}

// kind 0 = PostMessageW(WM_APP+1), 1 = SendNotifyMessageW(WM_APP+2); no user32 call other than the one measured
static void timed_call(const char* who, int kind, HWND h) {
    PLine l;
    pl_begin(&l, who);
    pl_s(&l, kind ? " before SendNotifyMessageW(WM_APP+2)" : " before PostMessageW(WM_APP+1)");
    pl_kv(&l, "hwndNonNull", h ? 1 : 0);
    pl_kv(&l, "llOwner", pl_loader_lock_owner());
    pl_end(&l);
    uint64_t t0 = pl_now_us();
    SetLastError(0);
    BOOL r = g_dlPost ? (kind ? g_dlSend(h, WM_APP + 2) : g_dlPost(h, WM_APP + 1))
                      : (kind ? SendNotifyMessageW(h, WM_APP + 2, 0, 0) : PostMessageW(h, WM_APP + 1, 0, 0));
    DWORD gle = GetLastError();
    uint64_t t1 = pl_now_us();
    pl_begin(&l, who);
    pl_s(&l, kind ? " SendNotifyMessageW returned" : " PostMessageW returned");
    pl_kv(&l, "ret", r ? 1 : 0);
    pl_kv(&l, "gle", gle);
    pl_kv(&l, "elapsedUs", t1 - t0);
    pl_kv(&l, "llOwner", pl_loader_lock_owner());
    pl_end(&l);
}

static void become_gui_thread(const char* who) {
    MSG m;
    IsGUIThread(TRUE);
    PeekMessageW(&m, NULL, 0, 0, PM_NOREMOVE);
    GetDesktopWindow();
    plain(who, "made user32 calls beforehand: IsGUIThread(TRUE), PeekMessageW, GetDesktopWindow");
}

// ------------------------------------------------------------------ S8
static DWORD WINAPI S8WndThread(LPVOID) {
    plain("WT", "role=WT window thread");
    make_window();
    SetEvent(evWReady);
    pump_until(evExit);
    return 0;
}
static DWORD WINAPI S8PProc(LPVOID param) {
    plain("P", "role=P caller thread");
    if (param) become_gui_thread("P");
    else plain("P", "no user32 call made on this thread so far (only waits on events)");
    SetEvent(evPReady);
    WaitForSingleObject(evPGo, INFINITE);
    HWND h = g_hwnd;
    timed_call("P", 0, h);
    SetEvent(evPostDone);
    timed_call("P", 1, h);
    SetEvent(evSendDone);
    plain("P", "returning from thread procedure");
    return 0;
}
static DWORD WINAPI S8HProc(LPVOID) {
    plain("H", "role=H returning from thread procedure; its T DLL_THREAD_DETACH will park");
    return 0;
}
static int s8_main() {
    load_dll_ext();
    if (is("S8c") || is("S8d")) {
        HMODULE dl = LoadLibraryW(L"probe_dl.dll");
        g_dlPost = (int (*)(HWND, unsigned))GetProcAddress(dl, "dl_post");
        g_dlSend = (int (*)(HWND, unsigned))GetProcAddress(dl, "dl_sendnotify");
        plain1("M", "probe_dl.dll loaded (user32 delay-loaded there); calls go through its delay-load thunks", "ok",
               g_dlSend ? 1 : 0);
        if (is("S8d")) {
            g_dlPost((HWND)1, WM_NULL);
            g_dlSend((HWND)1, WM_NULL);
            plain("M", "S8d: both delay-load thunks resolved by a call from main BEFORE H parks");
        } else {
            plain("M", "S8c: delay-load thunks NOT yet resolved; the first call through them happens while H is "
                       "parked");
        }
    }
    InterlockedExchange(&g_logApp, 1);
    CreateThread(NULL, 0, S8WndThread, NULL, 0, NULL);
    WaitForSingleObject(evWReady, INFINITE);
    HANDLE p = CreateThread(NULL, 0, S8PProc, is("S8b") ? (LPVOID)1 : NULL, 0, NULL);
    WaitForSingleObject(evPReady, INFINITE);
    DWORD htid = 0;
    HANDLE h = CreateThread(NULL, 0, S8HProc, NULL, CREATE_SUSPENDED, &htid);
    api2.set_park(htid, evParked, evRelease);
    ResumeThread(h);
    DWORD w = WaitForSingleObject(evParked, 10000);
    InterlockedExchange(&g_hParked, 1);
    plain1("M", "H parked inside its T DLL_THREAD_DETACH", "parked", w == WAIT_OBJECT_0 ? 1 : 0);
    SetEvent(evPGo);
    DWORD w1 = WaitForSingleObject(evPostDone, 2000);
    plain1("M", "PostMessageW returned within 2 s while H parked", "yes", w1 == WAIT_OBJECT_0 ? 1 : 0);
    DWORD w2 = WAIT_TIMEOUT;
    if (w1 == WAIT_OBJECT_0) {
        w2 = WaitForSingleObject(evSendDone, 2000);
        plain1("M", "SendNotifyMessageW returned within 2 s while H parked", "yes", w2 == WAIT_OBJECT_0 ? 1 : 0);
    }
    Sleep(300);
    plain("M", "releasing H now (300 ms after the calls)");
    InterlockedExchange(&g_hParked, 0);
    SetEvent(evRelease);
    if (w1 != WAIT_OBJECT_0 || w2 != WAIT_OBJECT_0) {
        DWORD w3 = WaitForSingleObject(evSendDone, 5000);
        plain1("M", "after releasing H: both calls completed within 5 s", "yes", w3 == WAIT_OBJECT_0 ? 1 : 0);
    }
    plain1("M", "H handle signaled within 5 s", "yes", WaitForSingleObject(h, 5000) == WAIT_OBJECT_0 ? 1 : 0);
    plain1("M", "P handle signaled within 5 s", "yes", WaitForSingleObject(p, 5000) == WAIT_OBJECT_0 ? 1 : 0);
    Sleep(100);
    plain("M", "main returning from wmain (process exit follows)");
    return 0;
}

// ------------------------------------------------------------------ S9a / S9c
static DWORD WINAPI S9Poster(LPVOID param) {
    plain("P", "role=P poster thread");
    if (param) become_gui_thread("P"); else plain("P", "no user32 call made on this thread so far");
    SetEvent(evPReady);
    WaitForSingleObject(evPosterStart, INFINITE);
    api.hold_shared();
    plain("P", "holds the SRW lock SHARED, parked just before PostMessageW");
    SetEvent(evPosterHolding);
    WaitForSingleObject(evPGo, INFINITE);
    timed_call("P", 0, api2.get_hwnd());
    api2.release_shared();
    plain("P", "released the SRW lock (shared)");
    return 0;
}
static DWORD WINAPI S9Tut(LPVOID) {
    mlog("U", "role=thread-under-test started");
    make_window();
    SetEvent(evReady);
    pump_until(evGo);
    api2.set_detach_acquire(GetCurrentThreadId(), evAcquiring);
    mlog("U", "returning from thread procedure, window alive; T DLL_THREAD_DETACH will take the SRW lock exclusively");
    return 0;
}
static int s9a_main() {
    load_dll_ext();
    HANDLE p = CreateThread(NULL, 0, S9Poster, is("S9a") ? (LPVOID)1 : NULL, 0, NULL);
    WaitForSingleObject(evPReady, INFINITE);
    HANDLE th = CreateThread(NULL, 0, S9Tut, NULL, CREATE_SUSPENDED, NULL);
    api.set_thread(th);
    ResumeThread(th);
    WaitForSingleObject(evReady, INFINITE);
    SetEvent(evPosterStart);
    WaitForSingleObject(evPosterHolding, INFINITE);
    SetEvent(evGo);
    DWORD w = WaitForSingleObject(evAcquiring, 5000);
    plain1("M", "callback reached 'acquiring'", "yes", w == WAIT_OBJECT_0 ? 1 : 0);
    Sleep(300);
    mlog1("M", "300 ms later, poster still parked: TUT handle signaled", "yes",
          WaitForSingleObject(th, 0) == WAIT_OBJECT_0 ? 1 : 0);
    SetEvent(evPGo);
    w = WaitForSingleObject(th, 10000);
    mlog_post("M", w == WAIT_OBJECT_0 ? "poster released; thread handle signaled"
                                      : "poster released; thread handle NOT signaled after 10 s");
    plain1("M", "poster handle signaled within 5 s", "yes", WaitForSingleObject(p, 5000) == WAIT_OBJECT_0 ? 1 : 0);
    Sleep(100);
    mlog("M", "main returning from wmain (process exit follows)");
    return 0;
}

// ------------------------------------------------------------------ S9b
static LONG volatile g_stop, g_freshReady;
static DWORD volatile g_iterTutTid;
static LONG volatile cPersistOk, cPersistFail1400, cPersistFail1816, cPersistFailOther, cPersistNoHwnd;
static LONG volatile cFreshCall[2], cFreshCallWhileTutOwnsLL[2], cFreshOk[2], cFreshFail[2], cFreshNoHwnd[2];

static DWORD WINAPI S9bPersistent(LPVOID) {
    MSG m;
    IsGUIThread(TRUE);
    PeekMessageW(&m, NULL, 0, 0, PM_NOREMOVE);
    while (!g_stop) {
        api.hold_shared();
        HWND h = api2.get_hwnd();
        if (h) {
            SetLastError(0);
            if (PostMessageW(h, WM_APP + 1, 0, 0)) InterlockedIncrement(&cPersistOk);
            else {
                DWORD gle = GetLastError();
                InterlockedIncrement(gle == 1400 ? &cPersistFail1400
                                     : gle == 1816 ? &cPersistFail1816 : &cPersistFailOther);
            }
        } else InterlockedIncrement(&cPersistNoHwnd);
        api2.release_shared();
        if (!h) SwitchToThread();
        else { uint64_t t0 = pl_now_us(); while (pl_now_us() - t0 < 20) YieldProcessor(); }   // ~20 us between posts
    }
    return 0;
}
// A thread whose FIRST user32 call is the PostMessageW inside the shared section. spin=1: it holds the shared lock
// until the loader lock is owned by the exiting TUT (bounded 5 ms), so the call overlaps TUT's blocked detach.
static DWORD WINAPI S9bFresh(LPVOID param) {
    int spin = param ? 1 : 0;
    InterlockedIncrement(&g_freshReady);
    WaitForSingleObject(evIterGo, INFINITE);
    api.hold_shared();
    DWORD tut = g_iterTutTid;
    if (spin) {
        uint64_t t0 = pl_now_us();
        while (pl_loader_lock_owner() != tut && pl_now_us() - t0 < 5000) YieldProcessor();
    }
    DWORD owner = pl_loader_lock_owner();
    HWND h = api2.get_hwnd();
    if (h) {
        InterlockedIncrement(&cFreshCall[spin]);
        if (owner == tut) InterlockedIncrement(&cFreshCallWhileTutOwnsLL[spin]);
        SetLastError(0);
        if (PostMessageW(h, WM_APP + 1, 0, 0)) InterlockedIncrement(&cFreshOk[spin]);
        else InterlockedIncrement(&cFreshFail[spin]);
    } else InterlockedIncrement(&cFreshNoHwnd[spin]);
    api2.release_shared();
    return 0;
}
static DWORD WINAPI S9bTut(LPVOID) {
    g_iterTutTid = GetCurrentThreadId();
    HWND h = CreateWindowExW(0, L"Us052ProbeWnd2", L"us052 probe", WS_OVERLAPPEDWINDOW, 0, 0, 200, 100, NULL, NULL,
                             GetModuleHandleW(NULL), NULL);
    api.set_hwnd(h);
    uint64_t t0 = pl_now_us();
    while (pl_now_us() - t0 < 3000) {      // time-bounded: the posters never let the queue run empty
        MSG msg;
        if (PeekMessageW(&msg, NULL, 0, 0, PM_REMOVE)) DispatchMessageW(&msg);
    }
    api2.set_detach_acquire(GetCurrentThreadId(), NULL);
    SetEvent(evIterGo);
    return 0;
}
static int s9b_main() {
    InterlockedExchange(&g_quietWnd, 1);
    load_dll_ext();
    WNDCLASSEXW wc = { sizeof(wc) };
    wc.lpfnWndProc = WndProc;
    wc.hInstance = GetModuleHandleW(NULL);
    wc.lpszClassName = L"Us052ProbeWnd2";
    RegisterClassExW(&wc);
    HANDLE pa = CreateThread(NULL, 0, S9bPersistent, NULL, 0, NULL);
    HANDLE pb = CreateThread(NULL, 0, S9bPersistent, NULL, 0, NULL);
    int done = 0;
    uint64_t tStart = pl_now_us();
    for (int i = 0; i < 200; i++) {
        ResetEvent(evIterGo);
        LONG want = g_freshReady + 2;
        HANDLE fa = CreateThread(NULL, 0, S9bFresh, (LPVOID)1, 0, NULL);
        HANDLE fb = CreateThread(NULL, 0, S9bFresh, NULL, 0, NULL);
        while (g_freshReady != want) Sleep(0);
        HANDLE th = CreateThread(NULL, 0, S9bTut, NULL, 0, NULL);
        if (WaitForSingleObject(th, 10000) != WAIT_OBJECT_0) {
            plain1("M", "S9b: TUT exit did NOT complete within 10 s", "iteration", (uint64_t)i);
            break;
        }
        if (WaitForSingleObject(fa, 10000) != WAIT_OBJECT_0 || WaitForSingleObject(fb, 10000) != WAIT_OBJECT_0) {
            plain1("M", "S9b: a fresh poster did NOT exit within 10 s", "iteration", (uint64_t)i);
            break;
        }
        CloseHandle(fa); CloseHandle(fb); CloseHandle(th);
        done++;
    }
    uint64_t tEnd = pl_now_us();
    InterlockedExchange(&g_stop, 1);
    plain1("M", "S9b: persistent posters stopped within 5 s", "yes",
           (WaitForSingleObject(pa, 5000) == WAIT_OBJECT_0 && WaitForSingleObject(pb, 5000) == WAIT_OBJECT_0) ? 1 : 0);
    PLine l;
    pl_begin(&l, "M S9b summary:");
    pl_kv(&l, "iterationsCompleted", (uint64_t)done);
    pl_kv(&l, "totalUs", tEnd - tStart);
    pl_kv(&l, "persistPostOk", (uint64_t)cPersistOk);
    pl_kv(&l, "persistPostFail1400", (uint64_t)cPersistFail1400);
    pl_kv(&l, "persistPostFail1816", (uint64_t)cPersistFail1816);
    pl_kv(&l, "persistPostFailOther", (uint64_t)cPersistFailOther);
    pl_kv(&l, "persistSawNoHwnd", (uint64_t)cPersistNoHwnd);
    pl_end(&l);
    for (int s = 1; s >= 0; s--) {
        pl_begin(&l, s ? "M S9b fresh poster A (first user32 call; waits for TUT to own the loader lock):"
                       : "M S9b fresh poster B (first user32 call; natural race):");
        pl_kv(&l, "firstCallsMade", (uint64_t)cFreshCall[s]);
        pl_kv(&l, "madeWhileTutOwnedLoaderLock", (uint64_t)cFreshCallWhileTutOwnsLL[s]);
        pl_kv(&l, "postOk", (uint64_t)cFreshOk[s]);
        pl_kv(&l, "postFail", (uint64_t)cFreshFail[s]);
        pl_kv(&l, "sawNoHwnd", (uint64_t)cFreshNoHwnd[s]);
        pl_end(&l);
    }
    return 0;
}

// ------------------------------------------------------------------ S12
// Is DisableThreadLibraryCalls (which the CRT's default DllMain stub calls) honoured for these DLLs?
static DWORD WINAPI S12Thread(LPVOID) {
    plain("U", "S12 thread running; returning");
    return 0;
}
static int s12_main() {
    load_dll_ext();
    SetLastError(0);
    BOOL ok = DisableThreadLibraryCalls(g_dll);
    DWORD gle = GetLastError();
    PLine l;
    pl_begin(&l, "M S12: DisableThreadLibraryCalls(probe DLL) returned");
    pl_kv(&l, "ret", ok ? 1 : 0);
    pl_kv(&l, "gle", gle);
    pl_end(&l);
    HANDLE th = CreateThread(NULL, 0, S12Thread, NULL, 0, NULL);
    plain1("M", "S12: thread created after the call has exited", "signaled",
           WaitForSingleObject(th, 5000) == WAIT_OBJECT_0 ? 1 : 0);
    plain("M", "main returning from wmain (process exit follows)");
    return 0;
}

static int s8_s9_main() {
    evWReady = CreateEventW(NULL, TRUE, FALSE, NULL);
    evPReady = CreateEventW(NULL, TRUE, FALSE, NULL);
    evPGo = CreateEventW(NULL, TRUE, FALSE, NULL);
    evPostDone = CreateEventW(NULL, TRUE, FALSE, NULL);
    evSendDone = CreateEventW(NULL, TRUE, FALSE, NULL);
    evParked = CreateEventW(NULL, TRUE, FALSE, NULL);
    evRelease = CreateEventW(NULL, TRUE, FALSE, NULL);
    evPosterStart = CreateEventW(NULL, TRUE, FALSE, NULL);
    evPosterHolding = CreateEventW(NULL, TRUE, FALSE, NULL);
    evAcquiring = CreateEventW(NULL, TRUE, FALSE, NULL);
    evIterGo = CreateEventW(NULL, TRUE, FALSE, NULL);
    if (starts("S8")) return s8_main();
    if (is("S9b")) return s9b_main();
    return s9a_main();   // S9a (poster already a GUI thread), S9c (poster's first user32 call)
}

int wmain(int argc, wchar_t** argv) {
    if (argc < 2) return 1;
    if (wcscmp(argv[1], L"run") == 0) return launcher(argc, argv);
    WideCharToMultiByte(CP_ACP, 0, argv[1], -1, g_scen, sizeof(g_scen), NULL, NULL);
    for (int i = 2; i < argc; i++) {
        if (wcscmp(argv[i], L"beh") == 0) g_flags |= FLAG_BEHAVIOURAL;
        if (wcscmp(argv[i], L"dm") == 0) g_dllName = L"probe_dm.dll";
    }
    if (is("S9a") && g_dllName[5] == L'_') g_flags |= FLAG_HOOKS_IN_M;   // exclusive acquire in DllMain
    if (is("S5b")) g_flags |= FLAG_FLSFREE_ON_UNLOAD;
    if (is("S4e")) g_flags |= FLAG_BLOCK_SRW_IN_FLS;
    if (is("S4f")) g_flags |= FLAG_BLOCK_EVENT_IN_FLS;
    if (is("S9b")) g_flags |= FLAG_QUIET;
    SetErrorMode(SEM_FAILCRITICALERRORS | SEM_NOGPFAULTERRORBOX | SEM_NOOPENFILEERRORBOX);
    SetUnhandledExceptionFilter(Unhandled);

    evStarted = CreateEventW(NULL, TRUE, FALSE, NULL);
    evGo = CreateEventW(NULL, TRUE, FALSE, NULL);
    evReady = CreateEventW(NULL, TRUE, FALSE, NULL);
    evExit = CreateEventW(NULL, TRUE, FALSE, NULL);
    evFiberReady = CreateEventW(NULL, TRUE, FALSE, NULL);
    evFiberDeleted = CreateEventW(NULL, TRUE, FALSE, NULL);
    evWaitDone = CreateEventW(NULL, TRUE, FALSE, NULL);
    evHeld = CreateEventW(NULL, TRUE, FALSE, NULL);

    {
        PLine l;
        pl_begin(&l, "M role=main scenario="); pl_s(&l, g_scen); pl_kv(&l, "flags", g_flags); pl_end(&l);
    }

    if (starts("S8") || starts("S9")) return s8_s9_main();
    if (is("S12")) return s12_main();

    HANDLE th;
    if (is("S1a")) {
        th = CreateThread(NULL, 0, TutProc, NULL, 0, NULL);
        WaitForSingleObject(evStarted, INFINITE);
        load_dll();
        api.set_thread(th);
        SetEvent(evGo);
    } else {
        load_dll();
        if (starts("S4")) {
            api.arm_fls();
            api.arm_cpp();
            mlog("M", "main thread armed F and C as well");
        }
        if (is("S4d") || is("S4e")) {
            CreateThread(NULL, 0, HolderProc, NULL, 0, NULL);
            WaitForSingleObject(evHeld, INFINITE);
        }
        th = CreateThread(NULL, 0, TutProc, NULL, CREATE_SUSPENDED, NULL);
        api.set_thread(th);
        ResumeThread(th);
    }

    if (starts("S1") || starts("S2") || is("S3a")) {
        DWORD w = WaitForSingleObject(th, 10000);
        mlog_post("M", w == WAIT_OBJECT_0 ? "thread handle signaled" : "thread handle NOT signaled after 10 s");
        Sleep(200);
        mlog("M", "200 ms later");
    } else if (is("S3b")) {
        WaitForSingleObject(evFiberReady, INFINITE);
        Sleep(50);
        mlog("M", "before DeleteFiber(F2) from the MAIN thread (owning thread waits on F1)");
        DeleteFiber(g_f2);
        mlog("M", "after DeleteFiber(F2) from the MAIN thread");
        SetEvent(evFiberDeleted);
        DWORD w = WaitForSingleObject(th, 10000);
        mlog_post("M", w == WAIT_OBJECT_0 ? "thread handle signaled" : "thread handle NOT signaled after 10 s");
    } else if (is("S4a")) {
        WaitForSingleObject(evReady, INFINITE);
        Sleep(100);
        mlog("M", "main calling ExitProcess(0), thread under test alive and blocked");
        ExitProcess(0);
    } else if (is("S4b") || is("S4d") || is("S4e") || is("S4f")) {
        Sleep(INFINITE);
    } else if (is("S4c")) {
        WaitForSingleObject(evReady, INFINITE);
        Sleep(100);
        mlog("M", "main RETURNING from wmain (CRT exit), thread under test alive and blocked");
        return 0;
    } else if (starts("S5")) {
        WaitForSingleObject(evReady, INFINITE);
        Sleep(100);
        mlog("M", "main calling FreeLibrary(probe.dll), thread under test alive");
        BOOL ok = FreeLibrary(g_dll);
        mlog1("M", "FreeLibrary returned", "ok", ok ? 1 : 0);
        mlog1("M", "after FreeLibrary", "probeDllStillLoaded", GetModuleHandleW(g_dllName) ? 1 : 0);
        Sleep(100);
        mlog("M", "telling the thread under test to return from its thread procedure");
        SetEvent(evExit);
        DWORD w = WaitForSingleObject(th, 10000);
        mlog_post("M", w == WAIT_OBJECT_0 ? "thread handle signaled" : "thread handle NOT signaled after 10 s");
        Sleep(200);
        mlog("M", "200 ms later");
    } else if (is("S6")) {
        WaitForSingleObject(evReady, INFINITE);
        HANDLE dup = NULL, wh = NULL;
        DuplicateHandle(GetCurrentProcess(), th, GetCurrentProcess(), &dup, 0, FALSE, DUPLICATE_SAME_ACCESS);
        BOOL ok = RegisterWaitForSingleObject(&wh, dup, WaitCb, NULL, INFINITE, WT_EXECUTEONLYONCE);
        mlog1("M", "RegisterWaitForSingleObject on duplicated thread handle", "ok", ok ? 1 : 0);
        DWORD w = WaitForSingleObject(evWaitDone, 10000);
        mlog_post("M", w == WAIT_OBJECT_0 ? "wait callback completed" : "wait callback did NOT fire within 10 s");
        UnregisterWaitEx(wh, INVALID_HANDLE_VALUE);
        Sleep(200);
        mlog("M", "200 ms later");
    } else if (is("S7")) {
        WaitForSingleObject(evReady, INFINITE);
        Sleep(100);
        mlog("M", "main calling TerminateThread on the thread under test");
        BOOL ok = TerminateThread(th, 7);
        mlog1("M", "TerminateThread returned", "ok", ok ? 1 : 0);
        DWORD w = WaitForSingleObject(th, 10000);
        mlog_post("M", w == WAIT_OBJECT_0 ? "thread handle signaled" : "thread handle NOT signaled after 10 s");
        Sleep(200);
        mlog("M", "200 ms later");
    } else {
        mlog("M", "unknown scenario");
        return 1;
    }
    mlog("M", "main returning from wmain (process exit follows)");
    return 0;
}
