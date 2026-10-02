// US-052-thread-end-probe-dll.cpp
// Evidence for backlog story US-052 (Glass Windows toolkit teardown). NOT shipped and NOT built by Maven.
// Builds two measurement DLLs (see US-052-thread-end-probe-build.bat), compiled like glass.dll (/MD, Release):
//   probe.dll     no DllMain of its own (CRT default), like glass.dll
//   probe_dm.dll  the same source compiled with /DPROBE_DLLMAIN, which adds a DllMain
// Each "thread is ending" notification mechanism logs one line per invocation:
//   F   FLS slot with a callback (FlsAlloc / FlsSetValue)
//   T   raw image TLS callback (.CRT$XLB pointer, /INCLUDE:_tls_used)
//   C   C++ thread_local object, constructor and destructor
//   D   destructor of a namespace-scope static object (run by the CRT at DLL_PROCESS_DETACH)
//   DM  DllMain (probe_dm.dll only); the results file calls this mechanism M
#include "US-052-thread-end-probe-log.h"

#define PROBE_API extern "C" __declspec(dllexport)

enum {
    FLAG_BEHAVIOURAL = 1,         // run the behavioural loader-lock test inside each callback
    FLAG_FLSFREE_ON_UNLOAD = 2,   // the static destructor calls FlsFree
    FLAG_BLOCK_SRW_IN_FLS = 4,    // the FLS callback blocks in AcquireSRWLockExclusive
    FLAG_BLOCK_EVENT_IN_FLS = 8,  // the FLS callback blocks on an event that is never set
    FLAG_QUIET = 16,              // log nothing but the exclusive-acquire line (stress scenario S9b)
    FLAG_HOOKS_IN_M = 32          // park / exclusive acquire run in DllMain instead of the TLS callback
};

static DWORD g_fls = FLS_OUT_OF_INDEXES;
static HWND volatile g_hwnd = NULL;       // the "published" window
static HANDLE volatile g_tut = NULL;      // handle of the thread under test (set by the EXE)
static DWORD volatile g_flags = 0;
static SRWLOCK g_srw = SRWLOCK_INIT;
static LPTHREAD_START_ROUTINE volatile g_helperProc = NULL;
static DWORD volatile g_helpers[256];
static LONG volatile g_nhelpers = 0;
// S8: a thread that parks inside its DLL_THREAD_DETACH notification; S9: one that takes g_srw exclusively there
static DWORD volatile g_parkTid = 0;
static HANDLE volatile g_evParked = NULL, g_evRelease = NULL;
static DWORD volatile g_acqTid = 0;
static HANDLE volatile g_evAcquiring = NULL;

typedef BOOLEAN (NTAPI* RtlDllShutdownInProgress_t)(void);

static bool is_helper(DWORD tid) {
    LONG n = g_nhelpers;
    if (n > 256) n = 256;
    for (LONG i = 0; i < n; i++) if (g_helpers[i] == tid) return true;
    return false;
}

// Behavioural loader-lock test: a thread started here must pass its DLL_THREAD_ATTACH notifications (which need
// the loader lock) before its start routine runs. 1 = helper ran within 300 ms, 0 = helper did not run within
// 300 ms (blocked), 2 = the test could not run: CreateEventW, DuplicateHandle, CreateThread, ResumeThread or the
// wait failed (error in *err; logged as behGle), 9 = not attempted. Only 0 is evidence of a held loader lock.
static int behavioural(DWORD* err) {
    *err = 0;
    LPTHREAD_START_ROUTINE proc = g_helperProc;
    if (!(g_flags & FLAG_BEHAVIOURAL) || !proc || is_helper(GetCurrentThreadId())) return 9;
    HANDLE ev = CreateEventW(NULL, TRUE, FALSE, NULL);
    if (!ev) { *err = GetLastError(); return 2; }
    HANDLE dup = NULL;   // the helper's own handle to ev; the helper closes it
    if (!DuplicateHandle(GetCurrentProcess(), ev, GetCurrentProcess(), &dup, 0, FALSE, DUPLICATE_SAME_ACCESS)) {
        *err = GetLastError();
        CloseHandle(ev);
        return 2;
    }
    DWORD tid = 0;
    HANDLE th = CreateThread(NULL, 0, proc, dup, CREATE_SUSPENDED, &tid);
    if (!th) { *err = GetLastError(); CloseHandle(ev); CloseHandle(dup); return 2; }
    LONG i = InterlockedIncrement(&g_nhelpers) - 1;
    if (i < 256) g_helpers[i] = tid;
    if (ResumeThread(th) == (DWORD)-1) {
        *err = GetLastError();
        TerminateThread(th, 0);   // never ran, so it never touched dup
        CloseHandle(th);
        CloseHandle(ev);
        CloseHandle(dup);
        return 2;
    }
    DWORD w = WaitForSingleObject(ev, 300);
    if (w == WAIT_FAILED) *err = GetLastError();
    CloseHandle(ev);
    CloseHandle(th);
    return w == WAIT_OBJECT_0 ? 1 : w == WAIT_TIMEOUT ? 0 : 2;
}

static void cb_state(PLine* l, bool withBehavioural) {
    DWORD tid = GetCurrentThreadId();
    pl_kv(l, "helperThread", is_helper(tid) ? 1 : 0);
    pl_kv(l, "isFiber", IsThreadAFiber() ? 1 : 0);
    pl_state(l, g_hwnd);
    static RtlDllShutdownInProgress_t volatile s_sd = NULL;   // resolved once (first use is DLL_PROCESS_ATTACH)
    RtlDllShutdownInProgress_t sd = s_sd;
    if (!sd) {
        HMODULE nt = GetModuleHandleW(L"ntdll.dll");
        sd = nt ? (RtlDllShutdownInProgress_t)GetProcAddress(nt, "RtlDllShutdownInProgress") : NULL;
        s_sd = sd;
    }
    pl_kv(l, "shutdownInProgress", sd ? (sd() ? 1 : 0) : 9);
    BOOLEAN got = TryAcquireSRWLockExclusive(&g_srw);
    if (got) ReleaseSRWLockExclusive(&g_srw);
    pl_kv(l, "srwTryExcl", got ? 1 : 0);
    HANDLE tut = g_tut;
    pl_kv(l, "tutExited", tut ? (WaitForSingleObject(tut, 0) == WAIT_OBJECT_0 ? 1 : 0) : 9);
    if (withBehavioural) {
        DWORD err;
        int b = behavioural(&err);
        pl_kv(l, "behHelperRan", (uint64_t)b);
        if (b == 2) pl_kv(l, "behGle", err);
    }
}

static const char* reason_name(DWORD reason) {
    return reason == DLL_PROCESS_ATTACH ? " reason=DLL_PROCESS_ATTACH"
         : reason == DLL_THREAD_ATTACH  ? " reason=DLL_THREAD_ATTACH"
         : reason == DLL_THREAD_DETACH  ? " reason=DLL_THREAD_DETACH"
         : reason == DLL_PROCESS_DETACH ? " reason=DLL_PROCESS_DETACH" : " reason=?";
}

// What a DLL_THREAD_DETACH notification does for the thread registered with probe_set_park (S8) or
// probe_set_detach_acquire (S9). who = "T" (TLS callback) or "DM" (DllMain).
static void detach_hooks(const char* who) {
    PLine l;
    DWORD tid = GetCurrentThreadId();
    if (tid == g_parkTid) {
        pl_begin(&l, who);
        pl_s(&l, " DLL_THREAD_DETACH: PARKING inside the callback (loader lock held)");
        pl_state(&l, g_hwnd);
        pl_end(&l);
        SetEvent(g_evParked);
        WaitForSingleObject(g_evRelease, INFINITE);
        pl_begin(&l, who);
        pl_s(&l, " DLL_THREAD_DETACH: park released");
        pl_end(&l);
    }
    if (tid == g_acqTid) {
        HWND h = g_hwnd;
        if (!(g_flags & FLAG_QUIET)) {
            pl_begin(&l, who);
            pl_s(&l, " DLL_THREAD_DETACH: acquiring SRW exclusive");
            pl_state(&l, h);
            pl_end(&l);
        }
        HANDLE e = g_evAcquiring;
        if (e) SetEvent(e);
        uint64_t t0 = pl_now_us();
        AcquireSRWLockExclusive(&g_srw);
        uint64_t t1 = pl_now_us();
        pl_begin(&l, who);
        pl_s(&l, " DLL_THREAD_DETACH: acquired SRW exclusive");
        pl_kv(&l, "acquireUs", t1 - t0);
        pl_state(&l, h);
        pl_end(&l);
        g_hwnd = NULL;                      // what the real callback would do: clear the publication
        ReleaseSRWLockExclusive(&g_srw);
        if (!(g_flags & FLAG_QUIET)) {
            pl_begin(&l, who);
            pl_s(&l, " DLL_THREAD_DETACH: released SRW exclusive, published hwnd cleared");
            pl_end(&l);
        }
    }
}

// ---------------------------------------------------------------- F
static VOID WINAPI fls_callback(PVOID value) {
    if (g_flags & FLAG_QUIET) return;
    PLine l;
    pl_begin(&l, "F fls-callback");
    pl_kv(&l, "armedByTid", (uint64_t)(uintptr_t)value);
    pl_kv(&l, "flsGetValueNow", (uint64_t)(uintptr_t)FlsGetValue(g_fls));   // value of the CURRENT fiber
    cb_state(&l, true);
    pl_end(&l);
    if (g_flags & FLAG_BLOCK_SRW_IN_FLS) {
        pl_begin(&l, "F blocking: AcquireSRWLockExclusive...");
        pl_end(&l);
        AcquireSRWLockExclusive(&g_srw);
        pl_begin(&l, "F blocking: acquired");
        pl_end(&l);
        ReleaseSRWLockExclusive(&g_srw);
    }
    if (g_flags & FLAG_BLOCK_EVENT_IN_FLS) {
        pl_begin(&l, "F blocking: WaitForSingleObject(never-signaled event, INFINITE)...");
        pl_end(&l);
        WaitForSingleObject(CreateEventW(NULL, TRUE, FALSE, NULL), INFINITE);
        pl_begin(&l, "F blocking: wait returned");
        pl_end(&l);
    }
}

// ---------------------------------------------------------------- T
extern "C" void NTAPI probe_tls_callback(PVOID module, DWORD reason, PVOID reserved) {
    if (!(g_flags & FLAG_QUIET)) {
        PLine l;
        pl_begin(&l, "T tls-callback");
        pl_s(&l, reason_name(reason));
        pl_kv(&l, "reservedNonNull", reserved ? 1 : 0);
        cb_state(&l, true);
        pl_end(&l);
    }
    if (reason == DLL_THREAD_DETACH && !(g_flags & FLAG_HOOKS_IN_M)) detach_hooks("T");
}
#pragma comment(linker, "/INCLUDE:_tls_used")
#pragma comment(linker, "/INCLUDE:probe_tls_cb_ptr")
#pragma section(".CRT$XLB", read)
extern "C" __declspec(allocate(".CRT$XLB")) const PIMAGE_TLS_CALLBACK probe_tls_cb_ptr = probe_tls_callback;

// ---------------------------------------------------------------- DM (mechanism M)
#ifdef PROBE_DLLMAIN
BOOL WINAPI DllMain(HINSTANCE instance, DWORD reason, LPVOID reserved) {
    if (!(g_flags & FLAG_QUIET)) {
        PLine l;
        pl_begin(&l, "DM dllmain");
        pl_s(&l, reason_name(reason));
        pl_kv(&l, "reservedNonNull", reserved ? 1 : 0);
        cb_state(&l, true);
        pl_end(&l);
    }
    if (reason == DLL_THREAD_DETACH && (g_flags & FLAG_HOOKS_IN_M)) detach_hooks("DM");
    return TRUE;
}
#endif

// ---------------------------------------------------------------- C
struct TlProbe {
    DWORD ctorTid;
    int armed;
    TlProbe() : ctorTid(GetCurrentThreadId()), armed(0) {
        if (g_flags & FLAG_QUIET) return;
        PLine l;
        pl_begin(&l, "C thread_local-ctor");
        cb_state(&l, false);
        pl_end(&l);
    }
    ~TlProbe() {
        if (g_flags & FLAG_QUIET) return;
        PLine l;
        pl_begin(&l, "C thread_local-dtor");
        pl_kv(&l, "armed", (uint64_t)armed);
        pl_kv(&l, "ctorTid", ctorTid);
        cb_state(&l, true);
        pl_end(&l);
    }
};
static thread_local TlProbe t_probe;

// ---------------------------------------------------------------- D
struct StaticProbe {
    int dummy;
    StaticProbe() : dummy(1) {}
    ~StaticProbe() {
        PLine l;
        pl_begin(&l, "D static-dtor");
        cb_state(&l, true);
        pl_end(&l);
        if ((g_flags & FLAG_FLSFREE_ON_UNLOAD) && g_fls != FLS_OUT_OF_INDEXES) {
            pl_begin(&l, "D static-dtor calling FlsFree");
            pl_end(&l);
            BOOL ok = FlsFree(g_fls);
            pl_begin(&l, "D static-dtor FlsFree returned");
            pl_kv(&l, "ok", ok ? 1 : 0);
            pl_end(&l);
        }
    }
};
static StaticProbe g_static;

// ---------------------------------------------------------------- exports
PROBE_API uint32_t probe_init(void) {
    if (g_fls == FLS_OUT_OF_INDEXES) g_fls = FlsAlloc(fls_callback);
    return g_fls;
}
PROBE_API void probe_set_hwnd(HWND h) { g_hwnd = h; }
PROBE_API HWND probe_get_hwnd(void) { return g_hwnd; }
PROBE_API void probe_set_thread(HANDLE h) { g_tut = h; }
PROBE_API void probe_set_flags(uint32_t f) { g_flags = f; }
PROBE_API void probe_set_helper(LPTHREAD_START_ROUTINE p) { g_helperProc = p; }
PROBE_API int32_t probe_arm_fls(void) {
    return FlsSetValue(g_fls, (PVOID)(uintptr_t)GetCurrentThreadId()) ? 1 : 0;
}
PROBE_API uint64_t probe_get_fls(void) { return (uint64_t)(uintptr_t)FlsGetValue(g_fls); }
PROBE_API void probe_arm_cpp(void) { t_probe.armed = 1; }
PROBE_API void probe_hold_shared(void) { AcquireSRWLockShared(&g_srw); }
PROBE_API void probe_release_shared(void) { ReleaseSRWLockShared(&g_srw); }
PROBE_API void probe_set_park(uint32_t tid, HANDLE parked, HANDLE release) {
    g_evParked = parked;
    g_evRelease = release;
    g_parkTid = tid;
}
PROBE_API void probe_set_detach_acquire(uint32_t tid, HANDLE acquiring) {
    g_evAcquiring = acquiring;
    g_acqTid = tid;
}
