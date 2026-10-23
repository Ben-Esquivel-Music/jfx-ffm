/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 * glass_win_test.cpp - the shim-only test hooks of glass_win_api.h that belong to no single owner: the
 * counters and logs with their switch, the reads of GlassApplication's published toolkit window and
 * thread that STALE and gwin_test_publication use, the park gate, the private-message send and queue
 * peek, the decoy windows and the IME composition driver. The preference-sink hooks that need the
 * delegates live in PlatformSupport.cpp. The probes production code carries (glass_win_test.h) read only
 * the flags written here, and nothing here runs unless a test calls a gwin_test_* export.
 */

#include "common.h"

#include "glass_win_api.h"
#include "glass_win_test.h"

#include "GlassApplication.h"
#include "ViewContainer.h"
#include "GlassView.h"

EXTERN_C IMAGE_DOS_HEADER __ImageBase;

volatile LONG g_gwinTestObserving = 0;
volatile LONG g_gwinTestParkThread = 0;
__declspec(thread) void* g_gwinTestImeState = NULL;

namespace {

/* The private message range of a window class: WM_USER .. 0x7FFF. */
const UINT kPrivateFirst = WM_USER;
const UINT kPrivateLast = 0x7FFF;

volatile LONG64 s_counters[GWIN_TEST_COUNTER_COUNT];

/*
 * Each fixed log has its own lock over its count and entries: a writer claims and fills a slot holding it
 * exclusive, a reset holds it exclusive, a snapshot holds it shared. Nothing is called while one is held.
 */
const LONG kEventCapacity = 64;
SRWLOCK s_eventLock = SRWLOCK_INIT;                 // s_eventCount and s_events
LONG s_eventCount = 0;
int32_t s_events[kEventCapacity];

const LONG kDelegateCallCapacity = 256;
SRWLOCK s_delegateCallLock = SRWLOCK_INIT;          // s_delegateCallCount and s_delegateCalls
LONG s_delegateCallCount = 0;
int32_t s_delegateCalls[kDelegateCallCapacity][5];

/* The publication GlassApplication keeps: what STALE compares with and gwin_test_publication reports. */
HWND PublishedHwnd()
{
    return GlassApplication::GetToolkitHWND();
}

DWORD PublishedThreadId()
{
    return GlassApplication::GetMainThreadId();
}

/*
 * Appends one entry to a fixed log, with its lock held exclusive until the entry is written: the count always
 * advances, only the first `capacity` are kept.
 */
LONG ClaimLogSlot(LONG* count, LONG capacity)
{
    const LONG slot = (*count)++;
    return slot < capacity ? slot : -1;
}

/*
 * Copies min(total, capacity) entries of `width` int32 / int64 out of a log and returns its total, both read
 * with the log's lock held shared.
 */
template <typename T, typename U>
LONG CopyLog(SRWLOCK* lock, const LONG* count, const U* log, LONG kept, int32_t width, T* out, int32_t capacity)
{
    ::AcquireSRWLockShared(lock);
    const LONG total = *count;
    const LONG n = total < kept ? total : kept;
    for (LONG i = 0; i < n && i < capacity; ++i) {
        for (int32_t k = 0; k < width; ++k) {
            out[i * width + k] = (T) log[i * width + k];
        }
    }
    ::ReleaseSRWLockShared(lock);
    return total;
}

/* Empties a fixed log, with its lock held exclusive. */
void ResetLog(SRWLOCK* lock, LONG* count)
{
    ::AcquireSRWLockExclusive(lock);
    *count = 0;
    ::ReleaseSRWLockExclusive(lock);
}

bool BadBuffer(const void* out, int32_t capacity)
{
    return capacity < 0 || (out == NULL && capacity > 0);
}

} // namespace

void GwinTestCountSlow(int32_t which)
{
    if (which >= 0 && which < GWIN_TEST_COUNTER_COUNT) {
        ::InterlockedIncrement64(&s_counters[which]);
    }
}

void GwinTestCountCallSlow(int32_t which, HWND target)
{
    GwinTestCountSlow(which);
    if (target != PublishedHwnd()) {
        GwinTestCountSlow(GWIN_TEST_COUNTER_STALE);
    }
}

void GwinTestLogEventSlow(int32_t code)
{
    ::AcquireSRWLockExclusive(&s_eventLock);
    const LONG slot = ClaimLogSlot(&s_eventCount, kEventCapacity);
    if (slot >= 0) {
        s_events[slot] = code;
    }
    ::ReleaseSRWLockExclusive(&s_eventLock);
}

void GwinTestRecordDelegateCallSlow(int32_t which)
{
    const DWORD tid = ::GetCurrentThreadId();
    const int32_t onToolkit = tid == PublishedThreadId() ? 1 : 0;
    APTTYPE type;
    APTTYPEQUALIFIER qualifier;
    int32_t aptType = -1;
    int32_t aptQualifier = -1;
    if (SUCCEEDED(::CoGetApartmentType(&type, &qualifier))) {
        aptType = (int32_t) type;
        aptQualifier = (int32_t) qualifier;
    }
    ::AcquireSRWLockExclusive(&s_delegateCallLock);
    const LONG slot = ClaimLogSlot(&s_delegateCallCount, kDelegateCallCapacity);
    if (slot >= 0) {
        s_delegateCalls[slot][0] = which;
        s_delegateCalls[slot][1] = (int32_t) tid;
        s_delegateCalls[slot][2] = onToolkit;
        s_delegateCalls[slot][3] = aptType;
        s_delegateCalls[slot][4] = aptQualifier;
    }
    ::ReleaseSRWLockExclusive(&s_delegateCallLock);
}

/*
 * ---- the park gate ----
 */
namespace {

enum ParkState { PARK_IDLE = 0, PARK_ARMED = 1, PARK_PARKED = 2 };

volatile LONG s_parkState = PARK_IDLE;
HANDLE s_parkedEvent = NULL;    // manual reset: a thread is parked
HANDLE s_releaseEvent = NULL;   // manual reset: let it go
INIT_ONCE s_parkInit = INIT_ONCE_STATIC_INIT;

/* Created by the arming thread, never at the gate. */
BOOL CALLBACK InitParkEvents(PINIT_ONCE, PVOID, PVOID*)
{
    s_parkedEvent = ::CreateEventW(NULL, TRUE, FALSE, NULL);
    s_releaseEvent = ::CreateEventW(NULL, TRUE, FALSE, NULL);
    return s_parkedEvent != NULL && s_releaseEvent != NULL;
}

bool ParkEventsReady()
{
    return ::InitOnceExecuteOnce(&s_parkInit, InitParkEvents, NULL, NULL) != FALSE;
}

/* The safety cap of a parked thread that nobody releases. */
const DWORD kParkCapMs = 120000;

} // namespace

/* Waits on a kernel event and calls nothing that could need the loader lock. */
void GwinTestParkSlow()
{
    const LONG me = (LONG) ::GetCurrentThreadId();
    if (::InterlockedCompareExchange(&g_gwinTestParkThread, 0, me) != me) {
        return;   // another post of this thread consumed the gate first
    }
    ::InterlockedExchange(&s_parkState, PARK_PARKED);
    ::SetEvent(s_parkedEvent);
    ::WaitForSingleObject(s_releaseEvent, kParkCapMs);
    ::ResetEvent(s_parkedEvent);
    ::InterlockedExchange(&s_parkState, PARK_IDLE);
}

/*
 * ---- the decoy windows ----
 */
namespace {

const UINT kDecoyHunt = WM_APP + 1;   // thread messages to the decoy thread, outside the private range
const UINT kDecoyStop = WM_APP + 2;

struct DecoyHunt {
    HWND target;
    int64_t maxCreations;
    int64_t created;
    int64_t sameSlot;
    int32_t result;
    HANDLE done;
};

SRWLOCK s_decoyLock = SRWLOCK_INIT;   // serializes start / hunt / stop
HANDLE s_decoyThread = NULL;
DWORD s_decoyThreadId = 0;
ATOM s_decoyClass = 0;

/* The decoy-message log has its own lock: the decoy thread cannot take s_decoyLock, which stop holds as it joins. */
const LONG kDecoyMessageCapacity = 256;
SRWLOCK s_decoyMessageLock = SRWLOCK_INIT;          // s_decoyMessageCount and s_decoyMessages
LONG s_decoyMessageCount = 0;
int64_t s_decoyMessages[kDecoyMessageCapacity][3];

LRESULT CALLBACK DecoyWndProc(HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam)
{
    if (msg >= kPrivateFirst && msg <= kPrivateLast) {
        ::AcquireSRWLockExclusive(&s_decoyMessageLock);
        const LONG slot = ClaimLogSlot(&s_decoyMessageCount, kDecoyMessageCapacity);
        if (slot >= 0) {
            s_decoyMessages[slot][0] = (int64_t) (intptr_t) hwnd;
            s_decoyMessages[slot][1] = (int64_t) msg;
            s_decoyMessages[slot][2] = (int64_t) wParam;
        }
        ::ReleaseSRWLockExclusive(&s_decoyMessageLock);
        return 0;   // wParam is never dereferenced
    }
    return ::DefWindowProcW(hwnd, msg, wParam, lParam);
}

HWND CreateDecoy()
{
    return ::CreateWindowExW(0, MAKEINTATOM(s_decoyClass), L"", 0, 0, 0, 0, 0, HWND_MESSAGE, NULL,
                             reinterpret_cast<HINSTANCE>(&__ImageBase), NULL);
}

/* Decoys held to walk the free list down to the target's slot; well under the 10,000 USER objects a
 * process may own by default. */
const size_t kHoldCap = 5000;

/*
 * A HWND is a handle-table slot (low 16 bits) plus that slot's reuse count (high 16 bits), and a freed
 * slot is handed out again first. So: hold every decoy that lands in another slot, until one lands in
 * the target's; then destroy and re-create only that one, which advances its reuse count by one per
 * round, until the value is the target's or max_creations is spent. The held decoys are destroyed again
 * at the end; a decoy equal to the target is kept.
 */
void RunHunt(DecoyHunt* hunt, std::vector<HWND>& kept)
{
    const WORD slot = LOWORD((UINT_PTR) hunt->target);
    std::vector<HWND> held;
    hunt->result = 0;
    while (hunt->created < hunt->maxCreations) {
        HWND hwnd = CreateDecoy();
        if (hwnd == NULL) {
            break;
        }
        hunt->created += 1;
        if (hwnd == hunt->target) {
            kept.push_back(hwnd);
            hunt->result = 1;
            break;
        }
        if (LOWORD((UINT_PTR) hwnd) == slot) {
            hunt->sameSlot += 1;
            ::DestroyWindow(hwnd);
        } else if (held.size() < kHoldCap) {
            held.push_back(hwnd);
        } else {
            ::DestroyWindow(hwnd);
        }
    }
    for (size_t i = 0; i < held.size(); ++i) {
        ::DestroyWindow(held[i]);
    }
}

DWORD WINAPI DecoyThreadProc(LPVOID ready)
{
    std::vector<HWND> kept;
    MSG msg;
    ::PeekMessageW(&msg, NULL, 0, 0, PM_NOREMOVE);   // create this thread's queue before start returns
    ::SetEvent((HANDLE) ready);

    while (::GetMessageW(&msg, NULL, 0, 0) > 0) {
        if (msg.hwnd == NULL && msg.message == kDecoyHunt) {
            DecoyHunt* hunt = reinterpret_cast<DecoyHunt*>(msg.lParam);
            RunHunt(hunt, kept);
            ::SetEvent(hunt->done);
            continue;
        }
        if (msg.hwnd == NULL && msg.message == kDecoyStop) {
            break;
        }
        ::TranslateMessage(&msg);
        ::DispatchMessageW(&msg);
    }
    for (size_t i = 0; i < kept.size(); ++i) {
        ::DestroyWindow(kept[i]);
    }
    return 0;
}

bool DecoyRunning()
{
    return s_decoyThread != NULL;
}

} // namespace

/*
 * ---- gwin_test_ime_composition ----
 */
namespace {

/* The calling thread's state while gwin_test_ime_composition runs; g_gwinTestImeState points at it. */
struct ImeState {
    int32_t faultAt;          // the allocation attempt that throws; < 1 never
    int32_t attempts;
    int32_t faults;
    int32_t helperFailures;
    int32_t backstops;
};

/* The canned composition (glass_win_api.h, gwin_test_ime_composition). */
const wchar_t kResultStr[] = { L'X', L'Y' };
const DWORD kResultClause[] = { 0, 2 };
const wchar_t kCompStr[] = { L'a', L'b', L'c' };
const DWORD kCompClause[] = { 0, 1, 3 };
const BYTE kCompAttr[] = { ATTR_TARGET_CONVERTED, ATTR_INPUT, ATTR_INPUT };
const LONG kCursorPos = 1;

/* ImmGetCompositionStringW's contract for one block: the size in bytes for a size query, else a copy. */
LONG CopyCanned(const void* data, LONG size, LPVOID buf, DWORD len)
{
    if (buf == NULL || len == 0) {
        return size;
    }
    if (len < (DWORD) size) {
        return IMM_ERROR_GENERAL;
    }
    ::memcpy(buf, data, (size_t) size);
    return size;
}

/* ViewContainer::HandleViewInputMethodEvent is protected; this container lets the hook's procedure reach it. */
class TestImeContainer : public ViewContainer {
public:
    BOOL Route(HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam)
    {
        return HandleViewInputMethodEvent(hwnd, msg, wParam, lParam);
    }
};

/* What the hook's own window procedure needs, reached through GWLP_USERDATA. */
struct ImeCall {
    TestImeContainer container;
    int32_t escaped;
};

/*
 * GlassWindow::WindowProc's IME arm, minus its IsEnabled() test. Production has nothing that catches
 * between WmImeComposition and user32; this procedure catches only at its own boundary, so an exception
 * that would have unwound into user32 is reported instead of being let in.
 */
LRESULT CALLBACK ImeTestWndProc(HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam)
{
    if (msg == WM_IME_COMPOSITION) {
        ImeCall* call = reinterpret_cast<ImeCall*>(::GetWindowLongPtrW(hwnd, GWLP_USERDATA));
        if (call != NULL) {
            try {
                if (call->container.Route(hwnd, msg, wParam, lParam)) {
                    return 0;
                }
            } catch (...) {
                call->escaped = 1;
                return 0;
            }
        }
    }
    return ::DefWindowProcW(hwnd, msg, wParam, lParam);
}

ATOM ImeTestWindowClass()
{
    static const ATOM atom = []() -> ATOM {
        WNDCLASSEXW wc;
        ::ZeroMemory(&wc, sizeof(wc));
        wc.cbSize = sizeof(wc);
        wc.lpfnWndProc = ImeTestWndProc;
        wc.hInstance = reinterpret_cast<HINSTANCE>(&__ImageBase);
        wc.lpszClassName = L"GlassTestImeCompositionWindow";
        return ::RegisterClassExW(&wc);
    }();
    return atom;
}

/* SendMessageW, reporting a C++ exception that came back out of it - after it crossed user32. */
int32_t SendCompositionCatching(HWND hwnd, LPARAM lParam)
{
    try {
        ::SendMessageW(hwnd, WM_IME_COMPOSITION, 0, lParam);
        return 0;
    } catch (...) {
        return 1;
    }
}

} // namespace

LONG GwinTestImeCannedComposition(DWORD index, LPVOID buf, DWORD len)
{
    switch (index) {
        case GCS_RESULTSTR:    return CopyCanned(kResultStr, (LONG) sizeof(kResultStr), buf, len);
        case GCS_RESULTCLAUSE: return CopyCanned(kResultClause, (LONG) sizeof(kResultClause), buf, len);
        case GCS_COMPSTR:      return CopyCanned(kCompStr, (LONG) sizeof(kCompStr), buf, len);
        case GCS_COMPCLAUSE:   return CopyCanned(kCompClause, (LONG) sizeof(kCompClause), buf, len);
        case GCS_COMPATTR:     return CopyCanned(kCompAttr, (LONG) sizeof(kCompAttr), buf, len);
        case GCS_CURSORPOS:    return kCursorPos;
        default:               return 0;
    }
}

void GwinTestImeAllocPointSlow()
{
    ImeState* state = static_cast<ImeState*>(g_gwinTestImeState);
    state->attempts += 1;
    if (state->attempts == state->faultAt) {
        state->faults += 1;
        throw std::bad_alloc();
    }
}

void GwinTestImeHelperFailureSlow()
{
    static_cast<ImeState*>(g_gwinTestImeState)->helperFailures += 1;
}

void GwinTestImeBackstopSlow()
{
    static_cast<ImeState*>(g_gwinTestImeState)->backstops += 1;
}

extern "C" {

int32_t gwin_test_observe(int32_t on)
{
    ::InterlockedExchange(&g_gwinTestObserving, 0);
    if (on != 0) {
        for (int i = 0; i < GWIN_TEST_COUNTER_COUNT; ++i) {
            ::InterlockedExchange64(&s_counters[i], 0);
        }
        ResetLog(&s_eventLock, &s_eventCount);
        ResetLog(&s_delegateCallLock, &s_delegateCallCount);
        ::InterlockedExchange(&g_gwinTestObserving, 1);
    }
    return GWIN_OK;
}

int64_t gwin_test_counter(int32_t which)
{
    if (which < 0 || which >= GWIN_TEST_COUNTER_COUNT) {
        return -1;
    }
    return ::InterlockedCompareExchange64(&s_counters[which], 0, 0);
}

int32_t gwin_test_publication(int64_t* out2)
{
    if (out2 == NULL) {
        return GWIN_ERR_INVALID_ARG;
    }
    out2[0] = (int64_t) (intptr_t) PublishedHwnd();
    out2[1] = (int64_t) PublishedThreadId();
    return GWIN_OK;
}

int32_t gwin_test_event_log(int32_t* out, int32_t capacity)
{
    if (BadBuffer(out, capacity)) {
        return GWIN_ERR_INVALID_ARG;
    }
    const LONG total = CopyLog(&s_eventLock, &s_eventCount, s_events, kEventCapacity, 1, out, capacity);
    return (int32_t) total;
}

int32_t gwin_test_prefs_delegate_calls(int32_t* out5, int32_t capacity)
{
    if (BadBuffer(out5, capacity)) {
        return GWIN_ERR_INVALID_ARG;
    }
    const LONG total = CopyLog(&s_delegateCallLock, &s_delegateCallCount, &s_delegateCalls[0][0],
                               kDelegateCallCapacity, 5, out5, capacity);
    return (int32_t) total;
}

int32_t gwin_test_park_arm(int32_t on)
{
    if (!ParkEventsReady()) {
        return GWIN_ERR_INVALID_ARG;
    }
    if (on != 0) {
        if (::InterlockedCompareExchange(&s_parkState, 0, 0) == PARK_PARKED) {
            return GWIN_ERR_INVALID_ARG;
        }
        ::ResetEvent(s_parkedEvent);
        ::ResetEvent(s_releaseEvent);
        ::InterlockedExchange(&s_parkState, PARK_ARMED);
        ::InterlockedExchange(&g_gwinTestParkThread, (LONG) ::GetCurrentThreadId());
    } else {
        ::InterlockedExchange(&g_gwinTestParkThread, 0);
        ::InterlockedCompareExchange(&s_parkState, PARK_IDLE, PARK_ARMED);
        if (::InterlockedCompareExchange(&s_parkState, 0, 0) == PARK_PARKED) {
            ::SetEvent(s_releaseEvent);
        }
    }
    return GWIN_OK;
}

int32_t gwin_test_park_wait(int32_t timeout_ms)
{
    if (!ParkEventsReady()) {
        return 0;
    }
    const DWORD wait = timeout_ms < 0 ? 0 : (DWORD) timeout_ms;
    return ::WaitForSingleObject(s_parkedEvent, wait) == WAIT_OBJECT_0 ? 1 : 0;
}

int32_t gwin_test_park_release(void)
{
    if (!ParkEventsReady() || ::InterlockedCompareExchange(&s_parkState, 0, 0) != PARK_PARKED) {
        return GWIN_ERR_INVALID_ARG;
    }
    ::SetEvent(s_releaseEvent);
    return GWIN_OK;
}

int32_t gwin_test_send_action_notify(int64_t wparam)
{
    HWND hwnd = PublishedHwnd();
    if (hwnd == NULL) {
        return GWIN_ERR_NO_TOOLKIT;
    }
    ::SendMessageW(hwnd, WM_DO_ACTION_NOTIFY, (WPARAM) wparam, 0);
    return GWIN_OK;
}

int32_t gwin_test_peek_private_messages(int64_t* out3, int32_t capacity)
{
    if (BadBuffer(out3, capacity)) {
        return GWIN_ERR_INVALID_ARG;
    }
    int32_t found = 0;
    MSG msg;
    while (::PeekMessageW(&msg, NULL, kPrivateFirst, kPrivateLast, PM_REMOVE | PM_QS_POSTMESSAGE)) {
        if (found < capacity) {
            out3[found * 3 + 0] = (int64_t) (intptr_t) msg.hwnd;
            out3[found * 3 + 1] = (int64_t) msg.message;
            out3[found * 3 + 2] = (int64_t) msg.wParam;
        }
        found += 1;
    }
    return found;
}

int32_t gwin_test_decoy_start(void)
{
    ::AcquireSRWLockExclusive(&s_decoyLock);
    int32_t status = GWIN_OK;
    if (!DecoyRunning()) {
        if (s_decoyClass == 0) {
            WNDCLASSEXW wc;
            ::ZeroMemory(&wc, sizeof(wc));
            wc.cbSize = sizeof(wc);
            wc.lpfnWndProc = DecoyWndProc;
            wc.hInstance = reinterpret_cast<HINSTANCE>(&__ImageBase);
            wc.lpszClassName = L"GlassTestDecoyWindow";
            s_decoyClass = ::RegisterClassExW(&wc);
        }
        HANDLE ready = s_decoyClass != 0 ? ::CreateEventW(NULL, TRUE, FALSE, NULL) : NULL;
        if (ready == NULL) {
            status = (int32_t) ::GetLastError();
        } else {
            ResetLog(&s_decoyMessageLock, &s_decoyMessageCount);
            s_decoyThread = ::CreateThread(NULL, 0, DecoyThreadProc, ready, 0, &s_decoyThreadId);
            if (s_decoyThread == NULL) {
                status = (int32_t) ::GetLastError();
            } else {
                ::WaitForSingleObject(ready, INFINITE);
            }
            ::CloseHandle(ready);
        }
        if (status == GWIN_OK && !DecoyRunning()) {
            status = GWIN_ERR_INVALID_ARG;
        }
    }
    ::ReleaseSRWLockExclusive(&s_decoyLock);
    return status;
}

int32_t gwin_test_decoy_hunt(void* target, int64_t max_creations, int64_t* out2)
{
    if (out2 == NULL) {
        return GWIN_ERR_INVALID_ARG;
    }
    ::AcquireSRWLockExclusive(&s_decoyLock);
    int32_t result = GWIN_ERR_INVALID_ARG;
    if (DecoyRunning()) {
        DecoyHunt hunt = { (HWND) target, max_creations, 0, 0, 0, ::CreateEventW(NULL, TRUE, FALSE, NULL) };
        if (hunt.done != NULL && ::PostThreadMessageW(s_decoyThreadId, kDecoyHunt, 0, (LPARAM) &hunt)) {
            ::WaitForSingleObject(hunt.done, INFINITE);
            out2[0] = hunt.created;
            out2[1] = hunt.sameSlot;
            result = hunt.result;
        }
        if (hunt.done != NULL) {
            ::CloseHandle(hunt.done);
        }
    }
    ::ReleaseSRWLockExclusive(&s_decoyLock);
    return result;
}

int32_t gwin_test_decoy_messages(int64_t* out3, int32_t capacity)
{
    if (BadBuffer(out3, capacity) || !DecoyRunning()) {
        return GWIN_ERR_INVALID_ARG;
    }
    const LONG total = CopyLog(&s_decoyMessageLock, &s_decoyMessageCount, &s_decoyMessages[0][0],
                               kDecoyMessageCapacity, 3, out3, capacity);
    return (int32_t) total;
}

int32_t gwin_test_decoy_stop(void)
{
    ::AcquireSRWLockExclusive(&s_decoyLock);
    if (DecoyRunning()) {
        ::PostThreadMessageW(s_decoyThreadId, kDecoyStop, 0, 0);
        ::WaitForSingleObject(s_decoyThread, INFINITE);
        ::CloseHandle(s_decoyThread);
        s_decoyThread = NULL;
        s_decoyThreadId = 0;
    }
    ::ReleaseSRWLockExclusive(&s_decoyLock);
    return GWIN_OK;
}

int32_t gwin_test_ime_composition(void* hwnd, int64_t view_id, int64_t lparam, int32_t fault_at, int32_t* out4)
{
    if (out4 == NULL) {
        return GWIN_ERR_INVALID_ARG;
    }

    ImeState state = { fault_at, 0, 0, 0, 0 };
    int32_t escaped = 0;

    if (hwnd != NULL) {
        g_gwinTestImeState = &state;
        escaped = SendCompositionCatching((HWND) hwnd, (LPARAM) lparam);
        g_gwinTestImeState = NULL;
    } else {
        const ATOM atom = ImeTestWindowClass();
        GlassView* view = new (std::nothrow) GlassView(view_id);
        if (atom == 0 || view == NULL) {
            delete view;
            return GWIN_ERR_INVALID_ARG;
        }
        ImeCall call;
        call.escaped = 0;
        call.container.SetGlassView(view);
        view->EnableInputMethodEvents(TRUE);

        HWND own = ::CreateWindowExW(0, MAKEINTATOM(atom), L"", WS_POPUP, 0, 0, 0, 0, NULL, NULL,
                                     reinterpret_cast<HINSTANCE>(&__ImageBase), NULL);
        if (own == NULL) {
            call.container.SetGlassView(NULL);
            delete view;
            return GWIN_ERR_INVALID_ARG;
        }
        ::SetWindowLongPtrW(own, GWLP_USERDATA, reinterpret_cast<LONG_PTR>(&call));

        g_gwinTestImeState = &state;
        escaped = SendCompositionCatching(own, (LPARAM) lparam);
        g_gwinTestImeState = NULL;
        if (call.escaped != 0) {
            escaped = 1;
        }

        ::SetWindowLongPtrW(own, GWLP_USERDATA, 0);
        ::DestroyWindow(own);
        call.container.SetGlassView(NULL);
        delete view;
    }

    out4[0] = state.attempts;
    out4[1] = state.faults;
    out4[2] = state.helperFailures;
    out4[3] = state.backstops;
    return escaped;
}

} // extern "C"
