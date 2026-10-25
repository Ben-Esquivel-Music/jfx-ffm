/*
 * Copyright (c) 2011, 2026, Oracle and/or its affiliates. All rights reserved.
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

#include "common.h"

#include "GlassApplication.h"
#include "GlassClipboard.h"
#include "GlassScreen.h"
#include "GlassWindow.h"
#include "RoActivationSupport.h"
#include "glass_win_test.h"


/**********************************
 * GlassApplication
 **********************************/

static LPCTSTR szGlassToolkitWindow = TEXT("GlassToolkitWindowClass");

GlassApplication *GlassApplication::pInstance = NULL;
bool GlassApplication::sm_shouldLeaveNestedLoop = false;

std::atomic<HWND> GlassApplication::sm_toolkitHwnd(NULL);
std::atomic<DWORD> GlassApplication::sm_toolkitThreadId(0);
SRWLOCK GlassApplication::sm_publicationLock = SRWLOCK_INIT;

/*
 * The pending list of ExecAction's cross-thread path. A caller on another thread builds a record on its
 * own stack and links it here, inside the shared side of the publication lock, before it sends
 * WM_DO_ACTION_NOTIFY naming it. The toolkit window procedure unlinks the record it is handed and runs it;
 * the WM_NCDESTROY arm unlinks whatever is left after the publication is cleared. Neither touches a record
 * after SetEvent: the caller's frame may be gone by then. s_pendingLock guards only the list and is never
 * held across a call out of this library.
 */
namespace {

enum PendingState { PENDING_QUEUED = 0, PENDING_DONE = 1, PENDING_NOT_RUN = 2 };

struct PendingAction {
    Action *action;
    HANDLE done;           // manual reset, set exactly once
    volatile LONG state;   // PendingState, written before done is set
    PendingAction *next;
};

SRWLOCK s_pendingLock = SRWLOCK_INIT;
PendingAction *s_pendingHead = NULL;

void LinkPending(PendingAction *record)
{
    ::AcquireSRWLockExclusive(&s_pendingLock);
    record->next = s_pendingHead;
    s_pendingHead = record;
    ::ReleaseSRWLockExclusive(&s_pendingLock);
}

/* Unlinks `candidate` when it is in the list. Compares pointers only: an unknown value is never read. */
bool UnlinkPending(const void *candidate)
{
    bool found = false;
    ::AcquireSRWLockExclusive(&s_pendingLock);
    for (PendingAction **link = &s_pendingHead; *link != NULL; link = &(*link)->next) {
        if (*link == candidate) {
            *link = (*link)->next;
            found = true;
            break;
        }
    }
    ::ReleaseSRWLockExclusive(&s_pendingLock);
    return found;
}

PendingAction *DetachAllPending()
{
    ::AcquireSRWLockExclusive(&s_pendingLock);
    PendingAction *head = s_pendingHead;
    s_pendingHead = NULL;
    ::ReleaseSRWLockExclusive(&s_pendingLock);
    return head;
}

/* Marks an unlinked record and releases its caller; the record is not touched afterwards. */
void CompletePending(PendingAction *record, LONG state)
{
    HANDLE done = record->done;
    ::InterlockedExchange(&record->state, state);
    ::SetEvent(done);
}

/*
 * The caller's wait: until `done` is set, still handling the messages other threads send to this one, as
 * SendMessage does while it waits.
 */
void WaitHandlingSentMessages(HANDLE done)
{
    for (;;) {
        const DWORD r = ::MsgWaitForMultipleObjectsEx(1, &done, INFINITE, QS_SENDMESSAGE, MWMO_INPUTAVAILABLE);
        if (r == WAIT_OBJECT_0) {
            return;
        }
        if (r == WAIT_OBJECT_0 + 1) {
            MSG msg;
            ::PeekMessageW(&msg, NULL, 0, 0, PM_NOREMOVE | PM_QS_SENDMESSAGE);   // dispatches sent messages
            continue;
        }
        // The record stays linked or running until `done` is set, so this caller must not leave early.
        ::WaitForSingleObject(done, INFINITE);
        return;
    }
}

} // namespace

/*
 * gwin_app_create's constructor (glass_win_api.h, ABI 5): the four statements of the former JNI
 * constructor GlassApplication(jobject), with the no-JNI PlatformSupport - the same WinRT activation.
 */
GlassApplication::GlassApplication() : BaseWnd(), m_platformSupport()
{
    m_clipboardId = 0;
    m_hNextClipboardView = NULL;
    m_mainThreadId = ::GetCurrentThreadId();

    Create(NULL, 0, 0, 400, 300, TEXT(""), 0, 0, NULL);
}

GlassApplication::~GlassApplication()
{
}

LPCTSTR GlassApplication::GetWindowClassNameSuffix()
{
    return szGlassToolkitWindow;
}

LRESULT GlassApplication::WindowProc(UINT msg, WPARAM wParam, LPARAM lParam)
{
    switch (msg) {
        case WM_DO_ACTION:
        case WM_DO_ACTION_LATER:
            {
                Action * action = (Action *)wParam;
                action->Do();
                if (msg == WM_DO_ACTION_LATER) {
                    delete action;
                    GwinTestCount(GWIN_TEST_COUNTER_ACTION_FREE);
                    // A posted message, so whichever pump dispatched it - also one that is not ours - is
                    // where the WM_PREFERENCES_CHANGED arm would run: deliver what a sink kept.
                    PlatformSupport::RunDeferredPreferences();
                }
            }
            return 0;
        case WM_DO_ACTION_NOTIFY:
            // ExecAction from another thread. Only a record found in the pending list is run; any other
            // WPARAM is ignored and never dereferenced. Do() runs with no lock held.
            if (UnlinkPending((const void *)wParam)) {
                PendingAction *record = (PendingAction *)wParam;
                record->action->Do();
                CompletePending(record, PENDING_DONE);
            }
            return 0;
        case WM_CREATE:
            // Published with no lock held. Only this window procedure writes the publication, and only the
            // clearing in WM_NCDESTROY needs the exclusive side. The value replaced here is NULL or, when
            // gwin_app_create is called again, the toolkit window of the earlier instance, whose WM_NCDESTROY
            // has not run, so a guarded post or send that loaded it still reaches a window that exists.
            // Preference types a sink kept under an earlier toolkit window are dropped before this one is
            // published (PlatformSupport::PostPreferencesChanged).
            PlatformSupport::DiscardDeferredPreferences();
            pInstance = this;
            sm_toolkitThreadId.store(m_mainThreadId);
            sm_toolkitHwnd.store(GetHWND());
            STRACE(_T("GlassApplication: created."));
            break;
        case WM_DESTROY:
            //Alarm clipboard dispose if any.
            //Please, use RegisterClipboardViewerId(0) instead of UnregisterClipboardViewer.
            RegisterClipboardViewerId(0);
            return 0;
        case WM_NCDESTROY:
            // The handle still names this window while its procedure handles this message, so it cannot
            // go to another window before the exclusive section below has ended. Every guarded post or
            // send that loaded it has finished by then, and every later one loads NULL. Then drop the
            // preference types a sink kept for this window, which is never dispatched to again, and run down
            // the cross-thread calls still queued: their actions will not run.
            ::AcquireSRWLockExclusive(&sm_publicationLock);
            sm_toolkitHwnd.store(NULL);
            sm_toolkitThreadId.store(0);
            ::ReleaseSRWLockExclusive(&sm_publicationLock);
            PlatformSupport::DiscardDeferredPreferences();
            // pInstance is deleted in BaseWnd::StaticWindowProc
            pInstance = NULL;
            RunDownPendingActions();
            STRACE(_T("GlassApplication: destroyed."));
            return 0;
        case WM_CHANGECBCHAIN:
            if ((HWND)wParam == m_hNextClipboardView) {
                m_hNextClipboardView = (HWND)lParam;
            } else if (NULL != m_hNextClipboardView) {
                ::SendMessage(m_hNextClipboardView, WM_CHANGECBCHAIN, wParam, lParam);
            }
            break;
        case WM_DRAWCLIPBOARD:
            {
                // The slot fires only for a peer that registered through gwin_clipboard_register_viewer.
                const GwinClipboardCallbacks* cb = GlassClipboardCallbacks();
                if (cb != NULL && 0 != m_clipboardId) {
                    cb->content_changed(m_clipboardId);
                }
            }
            if (NULL != m_hNextClipboardView) {
                ::SendMessage(m_hNextClipboardView, WM_DRAWCLIPBOARD, wParam, lParam);
            }
            break;
        case WM_SETTINGCHANGE:
            if (m_platformSupport.onSettingChanged(wParam, lParam)) {
                return 0;
            }

            if ((UINT)wParam != SPI_SETWORKAREA) {
                break;
            }
            // Fall through
        case WM_DISPLAYCHANGE:
            GlassScreen::HandleDisplayChange();
            break;
        case WM_PREFERENCES_CHANGED:
            // A WinRT sink posted from its own thread (PlatformSupport::PostPreferencesChanged); handled
            // here like the arms below, so preferences_changed only ever runs on this thread. wParam 0 is
            // the wake that follows a failed post: it delivers the types the sink kept instead, and answers
            // what DefWindowProc answers for it without touching this instance afterwards.
            {
                const int types = (int)wParam & PlatformSupport::PT_ALL;
                if (types == 0) {
                    PlatformSupport::RunDeferredPreferences();
                    return 0;
                }
                if (m_platformSupport.updatePreferences(PlatformSupport::PreferenceType(types))) {
                    return 0;
                }
            }
            break;
        case WM_THEMECHANGED:
        case WM_SYSCOLORCHANGE:
        case WM_DWMCOLORIZATIONCOLORCHANGED:
            if (m_platformSupport.updatePreferences(
                    PlatformSupport::PreferenceType(PlatformSupport::PT_SYSTEM_COLORS |
                                                    PlatformSupport::PT_UI_SETTINGS))) {
                return 0;
            }
            break;
    }
    return ::DefWindowProc(GetHWND(), msg, wParam, lParam);
}

/*
 * The "alarm dispose" of whatever clipboard peer is registered: GwinClipboardCallbacks.dispose_peer
 * (glass_win_api.h) runs the peer's dispose body, which calls UnregisterClipboardViewer; if it did not (no
 * table, no-op slot, unknown id) the viewer is unregistered here so a re-registration never enters
 * the chain twice.
 */
void GlassApplication::DisposeRegisteredClipboard()
{
    if (0 != m_clipboardId) {
        const GwinClipboardCallbacks* cb = GlassClipboardCallbacks();
        if (cb != NULL) {
            cb->dispose_peer(m_clipboardId);
        }
        if (0 != m_clipboardId) {
            UnregisterClipboardViewer();
        }
    }
}

void GlassApplication::RegisterClipboardViewerId(int64_t clipboardId)
{
    DisposeRegisteredClipboard();
    if (0 != clipboardId) {
        m_clipboardId = clipboardId;
        m_hNextClipboardView = ::SetClipboardViewer(GetHWND()) ;
        STRACE(_T("RegisterClipboardViewerId"));
    }
}

/* static */
void GlassApplication::UnregisterClipboardViewer()
{
    if (NULL != m_hNextClipboardView) {
        ::ChangeClipboardChain(GetHWND(), m_hNextClipboardView);
        m_hNextClipboardView = NULL;
        STRACE(_T("UnregisterClipboardViewer"));
    }
    m_clipboardId = 0;
}

/* static */
bool GlassApplication::ExecAction(Action *action)
{
    const DWORD toolkitThread = sm_toolkitThreadId.load();
    if (toolkitThread == 0 || toolkitThread != ::GetCurrentThreadId()) {
        return ExecActionFromOtherThread(action);
    }

    // The toolkit thread, the only writer of the publication: nothing races here. No guard either - the
    // action may end the toolkit, and WM_NCDESTROY would wait for a lock this thread holds. SendMessage
    // calls the window procedure inline, as it always did.
    if (pInstance == NULL) {
        return false;
    }
    HWND hWnd = pInstance->GetHWND();
    GwinTestCountCall(GWIN_TEST_COUNTER_SEND, hWnd);
    ::SendMessage(hWnd, WM_DO_ACTION, (WPARAM)action, (LPARAM)0);
    return true;
}

/*
 * static
 *
 * ExecAction from any thread but the toolkit thread. A guard held across a cross-thread SendMessage would
 * deadlock - a toolkit thread waiting on the exclusive side handles no sent message, and the action itself
 * may end the toolkit - so the call is queued under the guard and waited for outside it:
 * SendNotifyMessage from another thread hands the message to the window procedure and returns at once,
 * and the toolkit thread handles it where it handled the SendMessage of before, ahead of posted messages.
 * Returns true when the action ran.
 */
bool GlassApplication::ExecActionFromOtherThread(Action *action)
{
    PendingAction record;
    record.action = action;
    record.state = PENDING_QUEUED;
    record.next = NULL;
    record.done = ::CreateEventW(NULL, TRUE, FALSE, NULL);
    if (record.done == NULL) {
        return false;   // nothing was queued
    }

    bool queued = false;
    ::AcquireSRWLockShared(&sm_publicationLock);
    HWND hWnd = sm_toolkitHwnd.load();
    if (hWnd != NULL) {
        LinkPending(&record);
        GwinTestCountCall(GWIN_TEST_COUNTER_NOTIFY, hWnd);
        if (::SendNotifyMessage(hWnd, WM_DO_ACTION_NOTIFY, (WPARAM)&record, (LPARAM)0)) {
            queued = true;
        } else {
            UnlinkPending(&record);   // still inside the section, so the rundown never sees it
        }
    }
    ::ReleaseSRWLockShared(&sm_publicationLock);

    bool ran = false;
    if (queued) {
        WaitHandlingSentMessages(record.done);
        ran = record.state == PENDING_DONE;
    }
    ::CloseHandle(record.done);
    return ran;
}

/*
 * static
 *
 * The WM_NCDESTROY arm's rundown, after the publication is cleared: no new record can be linked any more,
 * and every linked one has its message queued but not yet handled. A running record is not in the list;
 * its caller waits for Do() to return.
 */
void GlassApplication::RunDownPendingActions()
{
    PendingAction *record = DetachAllPending();
    while (record != NULL) {
        PendingAction *next = record->next;
        CompletePending(record, PENDING_NOT_RUN);
        record = next;
    }
}

/* static */
bool GlassApplication::PostToToolkit(UINT msg, WPARAM wParam, LPARAM lParam, bool *published)
{
    // The shared section holds the load and one PostMessage, which runs no window procedure and no user
    // code on this thread, so its holder can never reach WM_NCDESTROY inside it.
    bool posted = false;
    ::AcquireSRWLockShared(&sm_publicationLock);
    HWND hWnd = sm_toolkitHwnd.load();
    if (published != NULL) {
        *published = hWnd != NULL;
    }
    if (hWnd != NULL) {
        GwinTestParkPoint();
        GwinTestCountCall(GWIN_TEST_COUNTER_POST, hWnd);
        if (msg == WM_PREFERENCES_CHANGED) {
            GwinTestCount(GWIN_TEST_COUNTER_PREFS_POST);
        }
        posted = ::PostMessage(hWnd, msg, wParam, lParam) != FALSE;
    } else if (msg == WM_PREFERENCES_CHANGED) {
        GwinTestCount(GWIN_TEST_COUNTER_PREFS_REFUSED);
    }
    ::ReleaseSRWLockShared(&sm_publicationLock);
    return posted;
}

/* static */
void GlassApplication::ExecActionLater(Action *action)
{
    GwinTestCount(GWIN_TEST_COUNTER_ACTION_ALLOC);
    if (!PostToToolkit(WM_DO_ACTION_LATER, (WPARAM)action, (LPARAM)0)) {
        delete action;
        GwinTestCount(GWIN_TEST_COUNTER_ACTION_FREE);
    }
}

/*
 * static
 *
 * The pump itself, with no JVM in it: glass_win_api.h's gwin_enter_nested_event_loop forwards here.
 * The loop's return value is not this library's business: it never left Java, and WinApplication
 * holds it itself and stores it before calling gwin_leave_nested_event_loop. After each dispatch it
 * delivers the preference types a sink kept, as gwin_run_loop's pump does.
 */
void GlassApplication::EnterNestedEventLoop()
{
    sm_shouldLeaveNestedLoop = false;

    MSG msg;
    while (GlassApplication::GetInstance()
            && !sm_shouldLeaveNestedLoop
            && ::GetMessage(&msg, NULL, 0, 0) > 0)
    {
        ::TranslateMessage(&msg);
        ::DispatchMessage(&msg);
        PlatformSupport::RunDeferredPreferences();
    }

    sm_shouldLeaveNestedLoop = false;
}

/* static */
void GlassApplication::LeaveNestedEventLoop()
{
    sm_shouldLeaveNestedLoop = true;
}

ULONG GlassApplication::s_accessibilityCount = 0;

/* static */
ULONG GlassApplication::IncrementAccessibility()
{
    return InterlockedIncrement(&GlassApplication::s_accessibilityCount);
}

/* static */
ULONG GlassApplication::DecrementAccessibility()
{
    return InterlockedDecrement(&GlassApplication::s_accessibilityCount);
}

/* static */
ULONG GlassApplication::GetAccessibilityCount()
{
    return GlassApplication::s_accessibilityCount;
}

/*
 * No DllMain here, deliberately: the one that stored the module handle for the cursor code was dead. The CRT's
 * default DllMain calls DisableThreadLibraryCalls, which is ignored for a DLL with static TLS, as this one is;
 * nothing here consumes thread attach/detach notifications either way - do not re-add a DllMain.
 */
