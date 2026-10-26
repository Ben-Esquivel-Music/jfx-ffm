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

#ifndef _GLASS_APPLICATION_
#define _GLASS_APPLICATION_

#include <atomic>

#include "BaseWnd.h"
#include "PlatformSupport.h"

class Action {
public:
    virtual void Do() = 0;
    virtual ~Action() {}
};

#define ENTER_MAIN_THREAD_AND_RETURN(RetType) \
    class _MyAction : public Action {    \
        public: \
                RetType _retValue;  \
                virtual void Do() {   \
                    _retValue = _UserDo(); \
                }   \
                RetType _UserDo()

#define ENTER_MAIN_THREAD() \
    class _MyAction : public Action {    \
        public: \
                virtual void Do()

#define LEAVE_MAIN_THREAD   \
    } _action;

#define LEAVE_MAIN_THREAD_LATER   \
    };                                      \
    _MyAction * _pAction = new _MyAction(); \
    _MyAction & _action = *_pAction;

#define ARG(var) _action.var

#define PERFORM() GlassApplication::ExecAction(&_action)

#define PERFORM_AND_RETURN() (PERFORM(), _action._retValue)

#define PERFORM_LATER() GlassApplication::ExecActionLater(_pAction)

#define WM_DO_ACTION        (WM_USER+1)
#define WM_DO_ACTION_LATER  (WM_USER+2)
// A synchronous call from another thread, sent with SendNotifyMessage; WPARAM names a pending record that
// the toolkit window procedure looks up and ignores when it does not find it (GlassApplication.cpp).
#define WM_DO_ACTION_NOTIFY (WM_USER+3)
// Posted by PlatformSupport's two WinRT sinks; WPARAM carries their PlatformSupport::PreferenceType bits, or 0
// for the wake a sink posts after keeping the types of a failed post (PlatformSupport::PostPreferencesChanged).
#define WM_PREFERENCES_CHANGED (WM_USER+4)
// Sent, never posted, by ArmPreferencesTimer from a sink on another thread when that wake was refused too; WPARAM
// and LPARAM are 0. The toolkit window procedure only arms its preferences timer: it never calls Java.
#define WM_PREFERENCES_ARM_TIMER (WM_USER+5)

class GlassApplication : protected BaseWnd {
public:
    // gwin_app_create's constructor (glass_win_api.h): the toolkit window, with a PlatformSupport that holds no
    // JNI state.
    GlassApplication();
    virtual ~GlassApplication();

    /*
     * The PUBLISHED toolkit window and thread: stored in the WM_CREATE arm, cleared under the exclusive side
     * of the publication lock in the WM_NCDESTROY arm. Any thread may load them; a loaded HWND can be stale
     * by the time it is used, so only PostToToolkit, ExecAction and ArmPreferencesTimer, which use it inside
     * the shared side, may message it from another thread.
     */
    static HWND GetToolkitHWND() { return sm_toolkitHwnd.load(); }
    static DWORD GetMainThreadId() { return sm_toolkitThreadId.load(); }

    /* The toolkit thread's own copy of the instance. Toolkit thread only. */
    static GlassApplication *GetInstance() { return pInstance; }

    /*
     * Runs action->Do() on the toolkit thread and returns once it has returned: true when it ran, false when
     * no toolkit window was published, it was destroyed before the action started, or, from another thread,
     * CreateEventW failed for the event the caller waits on, so nothing was queued, or Do() left by an
     * exception, which keeps propagating on the toolkit thread. On the toolkit thread
     * itself this is SendMessage(WM_DO_ACTION), inline and without a lock; from any other thread the action
     * is queued with SendNotifyMessage(WM_DO_ACTION_NOTIFY) inside the shared side of the publication lock
     * and waited for outside it, and the WM_NCDESTROY arm runs down what is still queued.
     */
    static bool ExecAction(Action *action);

    /*
     * The guarded post: inside the shared side of the publication lock, load the published HWND and, when
     * it is not NULL, PostMessage to it. Returns whether the message was posted. When `published` is not
     * NULL it receives whether a toolkit window was published, so a false return tells "no toolkit" (the
     * window was never created or is torn down) from a PostMessage that failed with the window published -
     * the toolkit thread's posted-message queue is at its quota. The only way this library posts to the
     * toolkit window; nothing relies on PostMessage failing for a dead handle. Any thread.
     */
    static bool PostToToolkit(UINT msg, WPARAM wParam, LPARAM lParam, bool *published = NULL);

    /* Posts WM_DO_ACTION_LATER through PostToToolkit; deletes the action when that refuses. */
    static void ExecActionLater(Action *action);

    /*
     * What PlatformSupport::PostPreferencesChanged does when the wake it posts is refused too, with the toolkit
     * window published: arms the one-shot preferences timer on the toolkit window, whose WM_TIMER arm delivers
     * the kept types (PlatformSupport::RunDeferredPreferences). SetTimer works only on the window's own thread.
     * On the toolkit thread it is called here, with no guard, as ExecAction's inline path; from any other
     * thread WM_PREFERENCES_ARM_TIMER is sent with SendNotifyMessage inside the shared side of the publication
     * lock, and the toolkit thread arms the timer at its next message retrieval, in any pump, ahead of the
     * posted backlog. Never calls Java, never waits. Any thread.
     */
    static void ArmPreferencesTimer();
    /*
     * The registration behind gwin_clipboard_register_viewer (glass_win_api.h): remembers
     * clipboardId as the target of GwinClipboardCallbacks.content_changed. Disposes whatever was
     * registered before; 0 only disposes (the WM_DESTROY path).
     */
    void RegisterClipboardViewerId(int64_t clipboardId);
    void UnregisterClipboardViewer();

    /*
     * The live PlatformSupport, or NULL before Application.run() creates the toolkit window and after
     * it is destroyed. glass_win_api.h's gwin_prefs_query_ui_settings / gwin_prefs_query_network reach
     * the WinRT objects through this; NULL means "no WinRT keys" (both report available == 0), not "no
     * preference keys": WinPreferences.collect still returns the user32 keys it reads itself
     * (Windows.SysColor.*, Windows.SPI.*).
     */
    static PlatformSupport* GetPlatformSupport() {
        return pInstance ? &pInstance->m_platformSupport : NULL;
    }

    /*
     * The nested event loop, JVM-free: glass_win_api.h's gwin_enter_nested_event_loop /
     * gwin_leave_nested_event_loop are one-line forwarders to these. The loop's return value never
     * left Java in the first place; WinApplication holds it itself. Toolkit thread only.
     */
    static void EnterNestedEventLoop();
    static void LeaveNestedEventLoop();

    static ULONG IncrementAccessibility();
    static ULONG DecrementAccessibility();
    static ULONG GetAccessibilityCount();

protected:
    virtual LRESULT WindowProc(UINT msg, WPARAM wParam, LPARAM lParam);
    virtual LPCTSTR GetWindowClassNameSuffix();

private:
    int64_t m_clipboardId;   // the registered clipboard peer's id; 0 when none is registered
    void DisposeRegisteredClipboard();
    static GlassApplication *pInstance;
    HWND    m_hNextClipboardView;
    DWORD m_mainThreadId;
    PlatformSupport m_platformSupport;

    // These are static because the GlassApplication instance may be
    // destroyed while the nested loop is spinning
    static bool sm_shouldLeaveNestedLoop;

    static ULONG s_accessibilityCount;

    // The publication (see GetToolkitHWND). sm_publicationLock is held shared across one load and one
    // PostMessage or SendNotifyMessage, never nested and never across a wait; exclusive only to clear.
    static std::atomic<HWND> sm_toolkitHwnd;
    static std::atomic<DWORD> sm_toolkitThreadId;
    static SRWLOCK sm_publicationLock;

    static bool ExecActionFromOtherThread(Action *action);
    static void RunDownPendingActions();
};


#endif //_GLASS_APPLICATION_
