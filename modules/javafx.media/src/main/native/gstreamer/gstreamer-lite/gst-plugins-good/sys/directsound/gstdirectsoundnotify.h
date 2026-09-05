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

#ifndef GSTDIRECTSOUNDNOTIFY_H
#define GSTDIRECTSOUNDNOTIFY_H

#include <mmdeviceapi.h>

typedef void (*GSTDSNotfierCallback)(void*);

#ifdef __cplusplus
extern "C" {
#endif
  void* InitNotificator(GSTDSNotfierCallback pCallback, void *pData);
  void ReleaseNotificator(void *pObject);
#ifdef __cplusplus
}
#endif

#ifdef __cplusplus
class GSTDirectSoundNotify : IMMNotificationClient
{
public:
  GSTDirectSoundNotify();

  bool Init(GSTDSNotfierCallback pCallback, void *pData);
  void Dispose();

  // IUnknown
  IFACEMETHODIMP_(ULONG) AddRef();
  IFACEMETHODIMP_(ULONG) Release();

private:
  // IMMNotificationClient
  IFACEMETHODIMP OnDeviceStateChanged(LPCWSTR pwstrDeviceId, DWORD dwNewState) { return S_OK; }
  IFACEMETHODIMP OnDeviceAdded(LPCWSTR pwstrDeviceId) { return S_OK; }
  IFACEMETHODIMP OnDeviceRemoved(LPCWSTR pwstrDeviceId) { return S_OK; }
  IFACEMETHODIMP OnDefaultDeviceChanged(EDataFlow flow, ERole role, LPCWSTR pwstrDefaultDeviceId);
  IFACEMETHODIMP OnPropertyValueChanged(LPCWSTR pwstrDeviceId, const PROPERTYKEY key) { return S_OK; }

  // The apartment thread, created by Init() and joined by Dispose(). It owns the whole
  // COM lifetime of m_pEnumerator: CoInitializeEx(), CoCreateInstance(),
  // RegisterEndpointNotificationCallback(), UnregisterEndpointNotificationCallback(),
  // Release() and CoUninitialize() all run on it, in that order. The enumerator pointer
  // therefore never leaves the apartment it was created in and needs no marshaling,
  // whichever threads happen to call Init() and Dispose().
  static unsigned __stdcall ApartmentThreadProc(void *pParam);
  void RunApartment();

  long m_cRef;
  IMMDeviceEnumerator* m_pEnumerator;
  // The callback pair and the lock that guards it. The pair is written by Init() before
  // this object is registered, cleared by the apartment thread once it has unregistered,
  // and read and invoked by OnDefaultDeviceChanged() on a thread MMDevAPI owns; every one
  // of those accesses is made under m_srwCallback.
  //
  // Two things follow from that, and only the first of them is a drain.
  // OnDefaultDeviceChanged() holds the lock across the invocation and not merely across
  // the read of the pair, so the clearing thread cannot take the lock until a callback
  // that has already acquired it has returned. A callback that MMDevAPI has dispatched
  // but that has not yet reached its acquire is not drained by that and cannot be: the
  // clearing thread takes the lock uncontended, having no way to know it is coming. That
  // one is covered by the second thing instead - it acquires the lock afterwards, reads
  // the pair, finds NULL and returns - which works only because ReleaseNotificator()
  // never frees this object, so the lock is still there for it to acquire.
  //
  // Between them, once ReleaseNotificator() returns no callback is running with the stale
  // m_pData and none can start with it, which is what the sink needs: m_pData is the
  // GstDirectSoundSink that gst_directsound_sink_finalize() goes on to free. The sink is
  // freed; this object, once registered, never is. Release() above does free it when the
  // last reference goes, but the only call to it in this tree is InitNotificator()'s after
  // a failed Init(), and that path is reached only when the registration never succeeded -
  // so MMDevAPI was never handed this pointer and has nothing it could dispatch into the
  // object being freed.
  //
  // An SRWLOCK rather than a CRITICAL_SECTION because it has no destroy call to get wrong.
  // ReleaseNotificator() abandons this object rather than deleting it, and MMDevAPI may
  // still call into it afterwards, so the lock those calls take has to outlive every
  // teardown path there is; with nothing to destroy, nothing can be destroyed too early,
  // and Dispose() - which promises to be safe when called twice - never touches it.
  // InitializeSRWLock() runs in the constructor, before Init() can start the apartment
  // thread and before MMDevAPI can learn this object exists.
  SRWLOCK m_srwCallback;
  GSTDSNotfierCallback m_pCallback;
  void *m_pData;
  // The apartment thread and its handshake. m_hReady is signalled once the thread has
  // finished trying to register; m_hQuit asks it to tear down and exit.
  HANDLE m_hThread;
  HANDLE m_hReady;
  HANDLE m_hQuit;
  // Set by the apartment thread before it signals m_hReady and cleared by it again once
  // it has unregistered. Beyond that thread's own reads, the only reader is Init(), after
  // its m_hReady wait; the clear that follows the unregister has no reader left and is
  // kept as state rather than as a decision, since nothing chooses a path on it any more.
  // That one wait is the barrier - for this flag, and for this flag only. It orders the
  // apartment thread against Init(); it says nothing about the MMDevAPI thread, which can
  // enter OnDefaultDeviceChanged() at any point between the register and the unregister,
  // and after the unregister too, and is party to no wait at all. What that thread
  // touches is the callback pair above, and m_srwCallback is what protects that.
  bool m_bRegistered;

  // IUnknown
  IFACEMETHODIMP QueryInterface(const IID& iid, void** ppUnk);
};
#endif // __cplusplus

#endif // GSTDIRECTSOUNDNOTIFY_H
#endif // GSTREAMER_LITE