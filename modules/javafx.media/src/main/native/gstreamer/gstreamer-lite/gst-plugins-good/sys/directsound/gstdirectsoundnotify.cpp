/*
 * Copyright (c) 2021, 2026, Oracle and/or its affiliates. All rights reserved.
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

#ifdef GSTREAMER_LITE

#include "gstdirectsoundnotify.h"

#include <process.h>

// Link-time enforcement of the loader-lock constraint that Dispose() below records: neither the
// wait in Dispose() nor the one in Init() may ever be made under the loader lock, and both would
// be if this DLL ever grew a DllMain that reached them. Until now that constraint lived in a
// comment and nothing checked it. This definition makes the linker check it: a DLL has exactly one
// DllMain, so a second one compiled into gstreamer-lite fails the link with LNK2005 naming this
// file, and whoever is adding it has to come here and read why. The obvious candidate is
// gstreamer/gst/gst.c, whose DllMain is guarded out by #ifndef GSTREAMER_LITE today and would come
// back with an upstream merge that dropped the guard.
//
// It has to be a definition and not a declaration: the CRT supplies a default DllMain to a DLL
// that defines none, so there is nothing for a second one to collide with unless we define the
// first. Ours is that same no-op, so the DLL behaves as it did - _DllMainCRTStartup calls DllMain
// for every notification and acts on nothing but a FALSE at process attach - and nothing may ever
// be added to the body, which runs under the loader lock by definition and so is the one place in
// this library that must not do any of what the rest of this file does.
//
// What it does not cover, and what no check inside this library could: a DllMain in some other
// module of the process that reaches gst_directsound_sink_finalize. The loader lock is
// process-wide, so that deadlocks just as surely. Nothing in the tree does it - ReleaseNotificator()
// is reached from a GObject finalize, on a pipeline teardown driven from Java - and keeping it that
// way is out of this file's reach.
extern "C" BOOL WINAPI DllMain(HINSTANCE hinstDLL, DWORD fdwReason, LPVOID lpvReserved);

extern "C" BOOL WINAPI DllMain(HINSTANCE hinstDLL, DWORD fdwReason, LPVOID lpvReserved) {
  (void)hinstDLL;
  (void)fdwReason;
  (void)lpvReserved;
  return TRUE;
}

void* InitNotificator(GSTDSNotfierCallback pCallback, void *pData) {
  GSTDirectSoundNotify *pNotify = new GSTDirectSoundNotify();
  if (pNotify != NULL) {
    if (pNotify->Init(pCallback, pData)) {
      return (void*)pNotify;
    } else {
      // The one path on which this object is ever freed, and the only one on which
      // freeing it is provably safe: Init() returns true only after
      // RegisterEndpointNotificationCallback() has succeeded, and m_bRegistered is set
      // nowhere else, so a false return means MMDevAPI was never told this object exists
      // and has nothing it could dispatch into it. ReleaseNotificator() cannot make that
      // argument and therefore does not free it at all - see the comment there.
      pNotify->Release();
    }
  }

  return NULL;
}

void ReleaseNotificator(void *pObject) {
  GSTDirectSoundNotify *pNotify = (GSTDirectSoundNotify*)pObject;
  if (pNotify) {
    pNotify->Dispose();
    // This object is deliberately never freed here, on any path. Do not "fix" that by
    // deleting it, and do not make the delete conditional on the unregister in Dispose()
    // having succeeded: that condition was tried and it is not sufficient, for the reason
    // below.
    //
    // MMDevAPI keeps the raw pointer it was handed at registration and takes no reference
    // of its own, and it reaches this object through a virtual call: the load of the
    // vtable pointer out of "this" happens inside MMDevAPI, before a single instruction of
    // ours runs. No lock, flag or refcount this class takes on entry can come early enough
    // to protect the object's own lifetime, so the only thing that makes an already
    // dispatched call safe is the object still being there when it lands. That is true of
    // all five IMMNotificationClient methods and not just OnDefaultDeviceChanged(): the
    // other four are inline "return S_OK" no-ops with no lock to add one to, and a single
    // unplug raises OnDeviceStateChanged() and OnPropertyValueChanged() as well.
    //
    // Dispose() does not close that window. It joins the apartment thread, and that thread
    // waits out a callback which has already taken m_srwCallback - but a callback that
    // MMDevAPI has dispatched and that has not yet reached its acquire is waited for by
    // nobody: the apartment thread takes the lock uncontended, clears the callback pair
    // and exits, this function returns, and only then does that callback acquire the lock
    // and read the pair. Deleting the object here would leave that acquire performing an
    // interlocked read-modify-write on a freed SRWLOCK; a dispatch that begins after the
    // delete instead loads the vtable pointer out of freed memory, one step earlier again.
    // A successful unregister does not rule either of them out -
    // UnregisterEndpointNotificationCallback() promises no in-flight drain - which is why
    // the success path is leaked exactly as the failure path is.
    //
    // What is abandoned is inert and holds nothing scarce. Dispose() has joined the
    // apartment thread and closed all three handles - the thread handle from
    // _beginthreadex() and the two events - and that thread releases m_pEnumerator before
    // it returns on the only path that reaches this function, so no thread, handle or COM
    // reference goes with it: about 80 bytes of x64 heap holding a valid vtable pointer, a
    // valid SRWLOCK and a NULL callback pair, which is precisely what a late callback
    // needs to find. That is the trade being made deliberately: a fixed, inert allocation
    // rather than a use after free.
    //
    // The cost is those 80 bytes per directsoundsink element, and an element is created
    // per audio pipeline - so per MediaPlayer with audio and per AudioClip.play(), which
    // makes it proportional to playbacks rather than to live players. Amortizing it would
    // mean one process-wide notificator holding a list of sinks, which changes who
    // registers with MMDevAPI rather than how long this object lives; it is a separate
    // piece of work and is not done here.
  }
}

bool GSTDirectSoundNotify::Init(GSTDSNotfierCallback pCallback, void *pData) {
  // Under the lock for the invariant rather than out of necessity: nothing is registered
  // yet and no apartment thread exists, so nobody else can be looking at the pair. Having
  // every access after construction take m_srwCallback is cheaper to check than to argue;
  // the constructor's NULL stores at the bottom of this file are the only unlocked ones,
  // and they run before InitNotificator() has a pointer it could hand to anybody.
  AcquireSRWLockExclusive(&m_srwCallback);
  m_pCallback = pCallback;
  m_pData = pData;
  ReleaseSRWLockExclusive(&m_srwCallback);

  // Manual reset events: m_hReady is signalled once and read by Init() only, and
  // Dispose() may signal m_hQuit before the apartment thread reaches its wait.
  m_hReady = CreateEvent(NULL, TRUE, FALSE, NULL);
  m_hQuit = CreateEvent(NULL, TRUE, FALSE, NULL);
  if (m_hReady != NULL && m_hQuit != NULL) {
    // _beginthreadex() rather than CreateThread(): this is a DLL, and RunApartment() calls
    // into COM and MMDevAPI, either of which may leave per thread CRT state (errno, locale)
    // behind on the way through. _beginthreadex() is what creates that state and gives it
    // back when the thread returns; CreateThread() would leak it. glib does the same in this
    // tree (3rd_party/glib/glib/gthread-win32.c).
    m_hThread = (HANDLE)_beginthreadex(NULL, 0, ApartmentThreadProc, this, 0, NULL);
    if (m_hThread != NULL) {
      // m_pEnumerator may only be touched by the apartment thread, so wait here until
      // it has finished trying to register and Init() can keep its bool contract.
      WaitForSingleObject(m_hReady, INFINITE);
    }
  }

  // InitNotificator() calls Release() without Dispose() when this returns false, so
  // nothing may be left behind here: stop the thread, if it started, and give the
  // handles back now. On success Dispose() does it.
  if (!m_bRegistered) {
    Dispose();
  }

  return m_bRegistered;
}

void GSTDirectSoundNotify::Dispose() {
  // Safe to call after a failed Init() and safe to call twice: every handle is closed
  // and NULLed here, and the apartment thread is joined exactly once.
  if (m_hThread != NULL) {
    // Joining is what withdraws the registration: when the apartment thread has exited it
    // has attempted the unregister and cleared the callback pair. It does not join
    // MMDevAPI's own notification thread - UnregisterEndpointNotificationCallback has
    // no documented in-flight drain - so a callback already dispatched may still be
    // running when the unregister returns. The apartment thread takes m_srwCallback
    // before it clears the pair, so by the time this join returns a callback that had
    // already acquired that lock has finished, and one that MMDevAPI had dispatched but
    // that has not yet reached the lock will acquire it and read the pair as NULL. Only
    // the first of those is waited for; the second is still to touch this object after
    // this join returns, and no join can wait for it, which is why ReleaseNotificator()
    // never frees the object.
    //
    // This join is therefore bounded by two things: MMDevAPI returning promptly from the
    // unregister, which is the same exposure as before this thread existed, when those
    // two calls ran inline on the disposing thread; and that in-flight callback
    // returning, which is new, and short - gst_directsound_device_callback() sets two
    // flags on the sink and takes no lock of its own, so nothing can hold it up.
    //
    // The INFINITE is deliberate and a timeout is not an available alternative, even though
    // the thread this runs on is routinely the FX Application Thread - gst_directsound_sink_
    // finalize() reaches here from the pipeline teardown that MediaPlayer.dispose() drives.
    // Abandoning the apartment thread on a timeout would give up everything the join is for:
    // the unregister may not have been attempted, so MMDevAPI would keep dispatching with
    // m_pData still pointing at the sink that gst_directsound_sink_finalize() is about to
    // free, which is the use after free this whole design exists to prevent; the callback
    // pair would not have been cleared, so the drain described above would not have
    // happened; and the three CloseHandle() calls below would run on handles the abandoned
    // thread is still waiting on and still owns. A timeout would convert a bounded stall
    // into a use after free, which is the wrong trade whatever the bound.
    //
    // Neither this wait nor the one in Init() may ever be made under the loader lock:
    // this one waits for the apartment thread's DLL_THREAD_DETACH, Init()'s waits for
    // its DLL_THREAD_ATTACH. No such path exists today - the only DllMain gstreamer-lite
    // has is the empty one at the top of this file, and ReleaseNotificator() is reached
    // from a GObject finalize - and it has to stay that way. That is no longer left to this
    // comment to arrange: that empty DllMain is there as a link-time assertion that this DLL
    // grows no other one, so the first attempt to add a real DllMain to gstreamer-lite fails
    // the link instead of shipping a deadlock. Read the comment there for what it does not
    // cover.
    SetEvent(m_hQuit);
    WaitForSingleObject(m_hThread, INFINITE);
    CloseHandle(m_hThread);
    m_hThread = NULL;
  }

  if (m_hReady != NULL) {
    CloseHandle(m_hReady);
    m_hReady = NULL;
  }

  if (m_hQuit != NULL) {
    CloseHandle(m_hQuit);
    m_hQuit = NULL;
  }
}

unsigned __stdcall GSTDirectSoundNotify::ApartmentThreadProc(void *pParam) {
  ((GSTDirectSoundNotify*)pParam)->RunApartment();
  return 0;
}

void GSTDirectSoundNotify::RunApartment() {
  // We created this thread, so it is not in an apartment yet and CoInitializeEx()
  // cannot fail with RPC_E_CHANGED_MODE. Join the MTA and not an STA: MMDeviceEnumerator
  // is registered ThreadingModel="Both", so it is at home here, and an MTA has no
  // message pump obligation. This thread has no window queue and so could not service
  // an STA, which would silently stop notification delivery if MMDevAPI ever marshalled
  // calls into the registering apartment.
  bool bCoUninitialize = false;

  if (SUCCEEDED(CoInitializeEx(NULL, COINIT_MULTITHREADED))) {
    bCoUninitialize = true;

    HRESULT hr = CoCreateInstance(__uuidof(MMDeviceEnumerator),
                                  NULL,
                                  CLSCTX_INPROC_SERVER,
                                  IID_PPV_ARGS(&m_pEnumerator));
    if (SUCCEEDED(hr)) {
      hr = m_pEnumerator->RegisterEndpointNotificationCallback(this);
      if (SUCCEEDED(hr)) {
        m_bRegistered = true;
      } else {
        m_pEnumerator->Release();
        m_pEnumerator = NULL;
      }
    }
  }

  // Init() is blocked until here and reads m_bRegistered once this returns. The Win32
  // wait it does is a full memory barrier, so the flag needs no atomics.
  SetEvent(m_hReady);

  if (m_bRegistered) {
    WaitForSingleObject(m_hQuit, INFINITE);

    // Unregister before releasing: MMDevAPI holds this object without a reference of
    // its own, and it is the unregister, not the Release() below, that stops the
    // callbacks. Clearing m_bRegistered records that it took; no control flow depends on
    // that any more, since ReleaseNotificator() abandons this object whatever the answer
    // is, so the flag survives past this point as state for a debugger rather than as a
    // decision.
    //
    // A failed unregister is handled, not asserted. This used to carry an
    // assert(SUCCEEDED(hr)) directly under the branch that handles the failure, which in any
    // build without NDEBUG - a -DCONF=Debug SDK - turned a device unplug racing an AudioClip
    // teardown into an abort() of the whole JVM, from a background apartment thread, with no
    // diagnostic: this library is built with GST_DISABLE_GST_DEBUG, so there was nothing to
    // log to either. The failure it fired on is not a programming error and not fatal. It
    // means MMDevAPI still holds this pointer, which is the same position a successful
    // unregister leaves us in as far as an already dispatched call is concerned, and it is
    // handled the same way and by the same code: the callback pair is cleared below and
    // ReleaseNotificator() never frees the object, so a call that arrives afterwards finds a
    // live vtable, a live lock and a NULL pair. Nothing here is worth ending a process over.
    HRESULT hr = m_pEnumerator->UnregisterEndpointNotificationCallback(this);
    if (SUCCEEDED(hr)) {
      m_bRegistered = false;
    }

    // The sink that m_pData points at is being finalized, so the callback must not reach
    // it again. MMDevAPI may still call this object - it was given the pointer without a
    // reference of its own, the unregister above may have failed, and even when it
    // succeeded it makes no promise about a call already dispatched - so what is left
    // behind has to be inert, not freed.
    //
    // m_srwCallback makes it inert, but in two steps rather than one, and only the first
    // is a drain. A callback that has already acquired the lock holds it across its whole
    // invocation, so the acquire below blocks until that call has returned. A callback
    // that MMDevAPI has dispatched but that has not yet reached its own acquire is not
    // waited for at all: this thread takes the lock uncontended and walks straight past
    // it. That one is covered by what it finds when it does acquire - a NULL pair, on an
    // object ReleaseNotificator() deliberately never frees, so the lock it takes and the
    // vtable it was dispatched through are both still there. Between the two, once the
    // release below returns no thread is inside m_pCallback(m_pData) and none can enter
    // it afterwards. Dispose() is still inside its join while all of this happens, so
    // ReleaseNotificator() cannot return - and gst_directsound_sink_finalize() cannot
    // free the sink - until that is true.
    //
    // The lock is taken after the unregister rather than around it, and dropped again
    // before the Release() below: MMDevAPI dispatches OnDefaultDeviceChanged() while
    // holding locks of its own, so a thread holding m_srwCallback must never call back
    // into MMDevAPI or the two could wait on each other. The order of the calls
    // themselves is unchanged.
    AcquireSRWLockExclusive(&m_srwCallback);
    m_pCallback = NULL;
    m_pData = NULL;
    ReleaseSRWLockExclusive(&m_srwCallback);

    m_pEnumerator->Release();
    m_pEnumerator = NULL;
  }

  // CoUninitialize() comes last, after the Release() above: it may unload MMDevAPI.dll
  // and leave the enumerator's vtable unmapped. Do not reorder these.
  if (bCoUninitialize) {
    CoUninitialize();
  }
}

GSTDirectSoundNotify::GSTDirectSoundNotify() {
  m_cRef = 1;
  m_pEnumerator = NULL;
  InitializeSRWLock(&m_srwCallback);
  m_pCallback = NULL;
  m_pData = NULL;
  m_hThread = NULL;
  m_hReady = NULL;
  m_hQuit = NULL;
  m_bRegistered = false;
}

HRESULT GSTDirectSoundNotify::OnDefaultDeviceChanged(EDataFlow flow,
                                                     ERole role,
                                                     LPCWSTR pwstrDefaultDeviceId) {
  if (flow == eRender && pwstrDefaultDeviceId != NULL) {
    // MMDevAPI dispatches this on a thread of its own, concurrently with everything else
    // in this file. The lock is held across the invocation and not merely across the read
    // of the pair: reading it and then calling outside the lock would leave exactly the
    // window this closes, in which the apartment thread clears the pair, Dispose()
    // returns and gst_directsound_sink_finalize() frees the sink while the call below
    // still has the old m_pData in hand. gst_directsound_device_callback() takes no lock
    // and makes no COM call - it sets two flags on the sink - so m_srwCallback stays a
    // leaf and holding it across the call cannot deadlock.
    //
    // Reaching here after the pair has been cleared is expected, not exceptional: MMDevAPI
    // may dispatch this call at any time, including after teardown has finished, and the
    // read below is how such a call learns that it has. It is safe only because
    // ReleaseNotificator() never frees this object - were it freed, the vtable dispatch
    // that got us here and the acquire below would both be touching freed memory, and
    // neither is something this method could guard against.
    AcquireSRWLockExclusive(&m_srwCallback);
    if (m_pCallback && m_pData) {
      m_pCallback(m_pData);
    }
    ReleaseSRWLockExclusive(&m_srwCallback);
  }

  // return value of this callback is ignored
  return S_OK;
}

//  IUnknown methods
HRESULT GSTDirectSoundNotify::QueryInterface(REFIID iid, void** ppUnk) {
  if ((iid == __uuidof(IUnknown)) ||
      (iid == __uuidof(IMMNotificationClient))) {
    *ppUnk = static_cast<IMMNotificationClient*>(this);
  } else {
    *ppUnk = NULL;
    return E_NOINTERFACE;
  }

  AddRef();

  return S_OK;
}

ULONG GSTDirectSoundNotify::AddRef() {
  return InterlockedIncrement(&m_cRef);
}

ULONG GSTDirectSoundNotify::Release() {
  long lRef = InterlockedDecrement(&m_cRef);
  if (lRef == 0) {
    delete this;
  }
  return lRef;
}

#endif // GSTREAMER_LITE