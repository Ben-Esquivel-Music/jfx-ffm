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
    pNotify->Release();
  }
}

bool GSTDirectSoundNotify::Init(GSTDSNotfierCallback pCallback, void *pData) {
  m_pCallback = pCallback;
  m_pData = pData;
  bool bResult = false;

  if (SUCCEEDED(CoInitialize(NULL))) {
    m_bCoUninitialize = true;
    m_dwApartmentThreadId = GetCurrentThreadId();
  }

  HRESULT hr = CoCreateInstance(__uuidof(MMDeviceEnumerator),
                                NULL,
                                CLSCTX_INPROC_SERVER,
                                IID_PPV_ARGS(&m_pEnumerator));
  if (SUCCEEDED(hr)) {
    hr = m_pEnumerator->RegisterEndpointNotificationCallback(this);
    if (SUCCEEDED(hr)) {
      bResult = true;
    } else {
      m_pEnumerator->Release();
      m_pEnumerator = NULL;
    }
  }

  // On success m_pEnumerator outlives this call, so the apartment reference has to as
  // well and is given back by Dispose(). Nothing is retained on failure, release it now.
  if (!bResult) {
    ReleaseApartment();
  }

  return bResult;
}

void GSTDirectSoundNotify::Dispose() {
  if (m_pEnumerator) {
    m_pEnumerator->UnregisterEndpointNotificationCallback(this);
    m_pEnumerator->Release();
    m_pEnumerator = NULL;
  }

  ReleaseApartment();
}

void GSTDirectSoundNotify::ReleaseApartment() {
  // CoUninitialize() is per thread, so it may only be called on the thread that called
  // CoInitialize(). If we are disposed on another thread we leak the apartment reference
  // on purpose: leaking it is better than unloading MMDevAPI.dll under a live pointer.
  if (m_bCoUninitialize && GetCurrentThreadId() == m_dwApartmentThreadId) {
    CoUninitialize();
  }

  m_bCoUninitialize = false;
  m_dwApartmentThreadId = 0;
}

GSTDirectSoundNotify::GSTDirectSoundNotify() {
  m_cRef = 1;
  m_pEnumerator = NULL;
  m_pCallback = NULL;
  m_pData = NULL;
  m_bCoUninitialize = false;
  m_dwApartmentThreadId = 0;
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