// US-052-thread-end-probe-delayload.cpp
// Evidence for backlog story US-052 (Glass Windows toolkit teardown). NOT shipped and NOT built by Maven.
// probe_dl.dll: PostMessageW / SendNotifyMessageW reached through DELAY-LOAD thunks, as in glass.dll, which
// win.cmake links with delayimp.lib and /DELAYLOAD:user32.dll. Used by scenarios S8c and S8d.
#include <windows.h>

extern "C" __declspec(dllexport) int dl_post(HWND h, unsigned msg) {
    return PostMessageW(h, msg, 0, 0) ? 1 : 0;
}
extern "C" __declspec(dllexport) int dl_sendnotify(HWND h, unsigned msg) {
    return SendNotifyMessageW(h, msg, 0, 0) ? 1 : 0;
}
