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

#include <assert.h>
#include <process.h>

void* InitNotificator(GSTDSNotfierCallback pCallback, void *pData) {
  GSTDirectSoundNotify *pNotify = new GSTDirectSoundNotify();
  if (pNotify != NULL) {
    if (pNotify->Init(pCallback, pData)) {
      return (void*)pNotify;
    } else {
      pNotify->Release();
    }
  }

  return NULL;
}

void ReleaseNotificator(void *pObject) {
  GSTDirectSoundNotify *pNotify = (GSTDirectSoundNotify*)pObject;
  if (pNotify) {
    pNotify->Dispose();
    // Deliberate leak, do not "fix" it: if the unregister in Dispose() failed, MMDevAPI
    // still holds a pointer to this object and would call a freed vtable on the next
    // device change. Dispose() has made the object inert, so leaking it is the same
    // trade 8267819 made for the apartment reference - a few dozen bytes rather than a
    // use after free.
    if (!pNotify->IsRegistered()) {
      pNotify->Release();
    }
  }
}

bool GSTDirectSoundNotify::Init(GSTDSNotfierCallback pCallback, void *pData) {
  m_pCallback = pCallback;
  m_pData = pData;

  // Manual reset events: m_hReady is signalled once and read by Init() only, and
  // Dispose() may signal m_hQuit before the apartment thread reaches its wait.
  m_hReady = CreateEvent(NULL, TRUE, FALSE, NULL);
  m_hQuit = CreateEvent(NULL, TRUE, FALSE, NULL);
  if (m_hReady != NULL && m_hQuit != NULL) {
    // _beginthreadex() rather than CreateThread(): this is a DLL, and the thread runs
    // CRT code (assert() in RunApartment()), so it needs the per thread CRT state that
    // _beginthreadex() creates and gives back when the thread returns. glib does the
    // same in this tree (3rd_party/glib/glib/gthread-win32.c).
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
    // Joining is what withdraws the registration: when the apartment thread has exited
    // it has attempted the unregister, and IsRegistered() says whether that took, so
    // ReleaseNotificator() knows whether this object may be deleted. It does not join
    // MMDevAPI's own notification thread - UnregisterEndpointNotificationCallback has
    // no documented in-flight drain - so a callback already dispatched may still be
    // running; the apartment thread has made this object inert for exactly that case.
    // The wait is bounded only by MMDevAPI returning promptly from the unregister,
    // which is the same exposure as before this thread existed, when those two calls
    // ran inline on the disposing thread.
    // Neither this wait nor the one in Init() may ever be made under the loader lock:
    // this one waits for the apartment thread's DLL_THREAD_DETACH, Init()'s waits for
    // its DLL_THREAD_ATTACH. No such path exists today - gstreamer-lite builds no
    // DllMain under GSTREAMER_LITE, and ReleaseNotificator() is reached from a GObject
    // finalize - and it has to stay that way.
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
    // callbacks. The HRESULT decides whether this object may be deleted at all, so it
    // is kept rather than discarded; the assert is a developer aid only, since it is
    // compiled out under NDEBUG and this library has no logging to report to either
    // (GST_DISABLE_GST_DEBUG is defined for it).
    HRESULT hr = m_pEnumerator->UnregisterEndpointNotificationCallback(this);
    if (SUCCEEDED(hr)) {
      m_bRegistered = false;
    }
    assert(SUCCEEDED(hr));

    // The sink that m_pData points at is being finalized, so the callback must not
    // reach it again. If the unregister above failed, MMDevAPI still holds this
    // object and will call it: what is left behind has to be inert, not freed.
    m_pCallback = NULL;
    m_pData = NULL;

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
    if (m_pCallback && m_pData) {
      m_pCallback(m_pData);
    }
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