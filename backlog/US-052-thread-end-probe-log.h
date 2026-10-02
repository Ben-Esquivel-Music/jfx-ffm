// US-052-thread-end-probe-log.h
// Evidence for backlog story US-052 (Glass Windows toolkit teardown). NOT shipped and NOT built by Maven.
// CRT-free append-only logging shared by the probe DLLs and the probe EXE: each module gets its own handle
// (static), and every line is one WriteFile on a FILE_APPEND_DATA handle, so lines of different threads and
// modules never interleave. The log file name comes from the PROBE_LOG environment variable.
#pragma once
#include <windows.h>
#include <intrin.h>
#include <stdint.h>

struct PLine { char b[640]; int n; };

static inline void pl_s(PLine* l, const char* s) { while (*s && l->n < 620) l->b[l->n++] = *s++; }
static inline void pl_u(PLine* l, uint64_t v) {
    char t[24]; int i = 0;
    do { t[i++] = (char)('0' + (v % 10)); v /= 10; } while (v);
    while (i && l->n < 620) l->b[l->n++] = t[--i];
}
static inline void pl_x(PLine* l, uint64_t v) {
    char t[20]; int i = 0;
    pl_s(l, "0x");
    do { int d = (int)(v & 15); t[i++] = (char)(d < 10 ? '0' + d : 'a' + d - 10); v >>= 4; } while (v);
    while (i && l->n < 620) l->b[l->n++] = t[--i];
}
static inline void pl_kv(PLine* l, const char* k, uint64_t v) { pl_s(l, " "); pl_s(l, k); pl_s(l, "="); pl_u(l, v); }
static inline void pl_kx(PLine* l, const char* k, uint64_t v) { pl_s(l, " "); pl_s(l, k); pl_s(l, "="); pl_x(l, v); }

static HANDLE volatile g_plog = NULL;

static HANDLE plog_handle() {
    HANDLE h = g_plog;
    if (h) return h;
    wchar_t path[1024];
    DWORD n = GetEnvironmentVariableW(L"PROBE_LOG", path, 1024);
    if (!n || n >= 1024) return INVALID_HANDLE_VALUE;
    h = CreateFileW(path, FILE_APPEND_DATA, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, NULL,
                    OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
    if (h == INVALID_HANDLE_VALUE) return h;
    HANDLE prev = (HANDLE)InterlockedCompareExchangePointer((PVOID volatile*)&g_plog, h, NULL);
    if (prev) { CloseHandle(h); return prev; }
    return h;
}

static inline uint64_t pl_now_us() {
    LARGE_INTEGER c, f;
    QueryPerformanceCounter(&c);
    QueryPerformanceFrequency(&f);
    uint64_t q = (uint64_t)c.QuadPart, fr = (uint64_t)f.QuadPart;
    return (q / fr) * 1000000ull + ((q % fr) * 1000000ull) / fr;
}

// line prefix: t=<QPC microseconds> tid=<GetCurrentThreadId()> <mechanism>
static inline void pl_begin(PLine* l, const char* mech) {
    l->n = 0;
    pl_s(l, "t="); pl_u(l, pl_now_us());
    pl_kv(l, "tid", GetCurrentThreadId());
    pl_s(l, " "); pl_s(l, mech);
}
static inline void pl_end(PLine* l) {
    l->b[l->n++] = '\n';
    HANDLE h = plog_handle();
    DWORD w;
    if (h != INVALID_HANDLE_VALUE) WriteFile(h, l->b, (DWORD)l->n, &w, NULL);
}

// UNDOCUMENTED: x64 PEB (gs:[0x60]) + 0x110 = PEB->LoaderLock (RTL_CRITICAL_SECTION*);
// its OwningThread field holds the owner's thread id (0 when free).
static inline DWORD pl_loader_lock_owner() {
    BYTE* peb = (BYTE*)__readgsqword(0x60);
    RTL_CRITICAL_SECTION* cs = *(RTL_CRITICAL_SECTION**)(peb + 0x110);
    return (DWORD)(uintptr_t)cs->OwningThread;
}

// isWindow / owning thread of the window / loader lock (PEB method)
static inline void pl_state(PLine* l, HWND hwnd) {
    DWORD pid = 0;
    BOOL iw = hwnd ? IsWindow(hwnd) : FALSE;
    DWORD wt = hwnd ? GetWindowThreadProcessId(hwnd, &pid) : 0;
    DWORD owner = pl_loader_lock_owner();
    pl_kx(l, "hwnd", (uint64_t)(uintptr_t)hwnd);
    pl_kv(l, "isWindow", iw ? 1 : 0);
    pl_kv(l, "wndTid", wt);
    pl_kv(l, "llOwner", owner);
    pl_kv(l, "llMine", owner == GetCurrentThreadId() ? 1 : 0);
}
