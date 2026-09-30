# US-030 — Port the Glass Windows COM servers (clipboard, DnD, UI Automation) to Rust behind the glass_win_api ABI

**Status:** 📋 Ready (drafted 2026-09-30 from a read-only survey; read `glass_win_api.h:97-113,1697-1733,2293-2400,
2893-2930`, class declarations and refcount sites by `grep`; the COM method bodies of `GlassClipboard.cpp`,
`GlassDnD.cpp`, `GlassAccessible.cpp` and `GlassTextRangeProvider.cpp` were NOT read; nothing built) · **Epic:** Rust
port of the remaining native code (goal 3) · **Blocked by:** US-027,
US-039 part 3 (a C++ fix); should follow
US-029, which proves the mixed-library seam on a small scale

## Story
As a JavaFX app developer whose users run screen readers, copy and paste, and drag and drop,
I want the six COM objects Windows calls into (`ClipboardData`, `ClipboardEnumFORMATETC`, `GlassDropTarget`,
`GlassDropSource`, `GlassAccessible`, `GlassTextRangeProvider`) to be Rust `#[implement]` objects behind the same 32
exports,
so that reference counting, `QueryInterface` and object lifetime across processes and RPC threads are generated
and checked instead of written by hand.

## Why Rust fits here
- **Must stay native (R1):** "a Java-synthesised COM object would need 19 hand-built vtables, 87 entries, the IIDs,
  and refcounts other processes hold references into" (`glass_win_api.h:2297-2301`). For clipboard and DnD, "the
  COM objects stay native" (`glass_win_api.h:110-111`). The WRAPPERs already left: `isOwner` (`:112`),
  `UiaRaiseAutomationEvent` and `UiaClientsAreListening` (`:2306-2310`).
- **Owned code (R2):** OpenJFX code.
- **Buildable and testable here (R3):** Windows 10 + VS2022; 46 module test annotations, plus
  `WinTextRangeProviderTest` in `tests/system`.
- **Benefit (R4):**
  - Hand-written refcounting. `delete this` appears at `GlassAccessible.cpp:424`, `GlassTextRangeProvider.cpp:200`
    and `OleUtils.h:199` (the `IUnknownImpl<T>` behind four of the six classes), and there are about 25 explicit
    `AddRef()`/`Release()` lines in these files (`grep -c`). `#[implement]` generates `QueryInterface`,
    `AddRef`, `Release` and the vtables; interface smart pointers release on `Drop`.
  - The UIA lifetime rule: the Java id must outlive `dispose()` until `accessible_disposed`, and the destructors run
    on RPC threads (`glass_win_api.h:2320-2346`). In Rust that is `Drop` of the COM object, wherever the last
    `Release` lands, which is exactly the C++ rule without the hand-written count.
  - Documented defects: `gwin_a11y_text_range_destroy(NULL)` crashes (`glass_win_api.h:2898-2901`), and a
    `VT_BSTR` property change leaks its BSTR (`:2913-2916`). The C++ fix this story is blocked by fixes both, and
    the port then carries the fixed behaviour with owned `BSTR`/`VARIANT` types.
- **Binding (R5):** `windows` 0.62.2 (MIT taken). Features `Win32_UI_Accessibility`, `Win32_System_Ole`,
  `Win32_System_Com`, `Win32_System_Variant`, `Win32_System_DataExchange`, `Win32_System_Memory` and
  `Win32_Graphics_Gdi` are present; `#[implement]` is documented in `windows-core`. The item placement and the
  interface-pointer → object recovery (`AsImpl`) are known by name only.
- **Why not Java:** the two header citations above. These are inbound COM servers held by other processes.

## Current state
- `GlassClipboard.cpp/.h` 1,900, `GlassDnD.cpp/.h` 423, `GlassAccessible.cpp/.h` 1,578, `GlassTextRangeProvider.cpp/.h`
  606 = 4,507 lines (`wc -l`), plus the shared `OleUtils.h` (209).
- 32 exports: the 19 of the clipboard/DnD section (including `gwin_alloc`/`gwin_free` and three test hooks), and the
  13 of the UIA section.
- Tables: `GwinClipboardCallbacks` 7, `GwinDndCallbacks` 9, `GwinAccessibleCallbacks` 70, `GwinTextRangeCallbacks`
  19 (105 slots).
  - Clipboard and DnD run on the toolkit thread (`glass_win_api.h:1733`).
  - UIA runs in the toolkit STA, except `AdviseEventRemoved` and the two `*_disposed` slots
    (`glass_win_api.h:2336-2346`).
- Handles: an accessible handle *is* its `IRawElementProviderSimple*`, a range handle its `ITextRangeProvider*`,
  and slots pass other providers' handles back and forth (`glass_win_api.h:2330-2334`).
- The C++ core hands the objects out: `WM_GETOBJECT` (`GlassWindow.cpp:658`, `FullScreenWindow.cpp:374`,
  `ViewContainer.cpp:230`), and `RegisterDragDrop(m_hwnd, this)` (`GlassDnD.cpp:57`). `GlassApplication`'s clipboard
  viewer arms call the clipboard code (`GlassApplication.cpp:84-96`).
- Tests: `WinClipboardNativeTest` 22, `WinDndNativeTest` 8, `WinAccessibilityNativeTest` 16, and
  `WinTextRangeProviderTest` 1 in `tests/system`. The slot tests fire slots through the shim-only hooks. No test
  drives a UIA client, a cross-process clipboard read or an OS drag.

## Approach
- ABI unchanged; same crate, modules `clipboard`, `dnd`, `uia`.
- The internal seam is COM itself: each Rust object is created through a non-exported `extern "C"` constructor that
  returns the interface pointer the C++ already stores (`IDataObject*`, `IDropTarget*`, `IRawElementProviderSimple*`).
  C++ keeps holding it in its existing smart pointers until the core story.
- A per-class `QueryInterface` sweep (the IIDs answered, and a set that is refused) is captured from the C and must
  match.
- If a shim-only test hook is needed to drive an object, it is added to the header in C++ first. Shim-only hooks
  never bump the ABI (`glass_win_api.h:199-201`).

### Slices
1. **Goldens from the C**:
   - **Clipboard**: push/pop round trips for every mime in the process-wide maps; the `EnumFormatEtc` order and the
     `GetData`/`QueryGetData` answers per `FORMATETC`; one out-of-process read of text and files; a recording
     `GwinClipboardCallbacks`.
   - **DnD**: a Robot-driven `DoDragDrop` between two Glass windows of one process (`tests/system`), recording
     `GwinDndCallbacks` and the `DROPEFFECT` sequence.
   - **UIA**: a test-only in-process UIA client (the `WinTextRangeProviderTest` pattern) walks a scripted scene,
     reads properties, invokes patterns and moves text ranges. It records all 89 slots and every HRESULT and
     out-value, including the thread of each `*_disposed`.

   Record the commit and the Windows build.
2. **Clipboard**: `ClipboardData`, `ClipboardEnumFORMATETC`, the mime maps, the `gwin_clipboard_*` exports,
   `gwin_alloc`/`gwin_free`. Gate: `WinClipboardNativeTest`, clipboard golden, QI sweep.
3. **Drag and drop**: `GlassDropTarget`, `GlassDropSource`, `gwin_dnd_*`. Gate: `WinDndNativeTest`, DnD trace.
4. **UIA text range**, the smaller object first: `GlassTextRangeProvider` and `gwin_a11y_text_range_*`. Gate: the
   range tests in `WinAccessibilityNativeTest`, `WinTextRangeProviderTest`, the range part of the UIA trace.
5. **UIA accessible**: `GlassAccessible` (19 interfaces), `gwin_a11y_create`/`destroy`/`raise_property_changed`,
   `GwinVariant`. The layout is checked by `gwin_test_variant_offsets` and by `const` asserts. Gate:
   `WinAccessibilityNativeTest`, the UIA trace, and a manual Narrator smoke test recorded in the PR.
6. **Delete** each slice's C++ in its own commit. Reduce `OleUtils.h` to what `CommonDialogs_COM.cpp` still uses.

## Acceptance criteria
- `dumpbin /exports glass.dll` is identical (106 exports), and `gwin_abi_version()` is still 6.
- The 46 module annotations and `WinTextRangeProviderTest` pass unchanged; the slice-1 goldens and traces are exact.
- `QueryInterface` answers the same IIDs per class. `accessible_disposed` and `range_disposed` fire at the same
  points, on the same kind of thread, as in the C trace.
- `GlassClipboard.cpp/.h`, `GlassDnD.cpp/.h`, `GlassAccessible.cpp/.h` and `GlassTextRangeProvider.cpp/.h` are
  deleted.
- Every export and every COM method is guarded, and answers the HRESULT the C++ answered on its failure path.
- `unsafe` appears only in the boundary modules; clippy and rustfmt are clean.

## Definition of Done
Merged PR. Verified on Windows: the module suite, `tests/system` Win tests and the DnD robot trace, run locally
because CI does not run `tests/system`. The C++ is deleted. `backlog/README.md` is updated.

## Risks
| # | Risk | Mitigation |
| --- | --- | --- |
| 1 | Handle identity: the handle must stay the `IRawElementProviderSimple*`/`ITextRangeProvider*` interface pointer, and the Rust object must be recovered from it (`AsImpl`, not verified). | Slice 4 proves it on the range object first; a test round-trips handles through slots and compares values. |
| 2 | A panic in a COM method aborts, because no export guard is on the stack. | A guard in every method, answering `E_FAIL`, the value the C++ gave for a caught exception or `GWIN_ERR_UPCALL`. |
| 3 | `#[implement]`/`ComObject` may require `Send`/`Sync` (not checked) while provider state is STA-affine. | Thread-checked cells; the RPC-thread paths touch only the id registries, as `glass_win_api.h:2350-2352` states today. |
| 4 | `STGMEDIUM`/`HGLOBAL` ownership across processes (`ReleaseStgMedium` rules). | The golden includes an out-of-process reader; ownership is copied call by call from the C. |
| 5 | Blocks from `gwin_alloc` freed by the other side during the transition (port rule P7). | `gwin_alloc`/`gwin_free` stay the only pair for crossing blocks, and C++ that frees one calls `gwin_free`. |
| 6 | OLE-marshalled calls on the UIA path could reorder once objects move. | The traces record order and thread, and no marshalling is added. |

