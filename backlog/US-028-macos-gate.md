# US-028 — macOS gate

Evidence for [US-028](US-028-build-and-link-the-rust-slices-on-macos.md): the Rust-linked `libjfxmedia.dylib` against
its C build on the arm64 macOS host, for arm64 and x86_64 in Release and Debug; the names `libjfxmedia_avf.dylib`
imports from it; the Mach-O differences; the negative controls; and the Maven gates.

## Host and tools
- macOS 27.2.1 (26B131), arm64, 12 cores, no Rosetta (`arch -x86_64 /usr/bin/true`: `Bad CPU type in executable`):
  x86_64 code was built and inspected here, never run.
- Xcode 27.1 (27A9275), Command Line Tools 27.2.0, ld `PROJECT:ld-27037.1`, Apple clang 21.0.0 (clang-2100.3.34.2).
  The links used the Command Line Tools SDK (`sdk 27.2` in `LC_BUILD_VERSION`). CMake 4.4.4, Unix Makefiles.
- rustc 1.100.0 (0ef1bc386 2026-11-09, LLVM 23.1.5), cargo 1.100.0 (3acc79c0c 2026-11-05), cbindgen 0.29.4, rustup
  1.29.3, with the std of `aarch64-apple-darwin` and `x86_64-apple-darwin`.
- OpenJDK 27.0.1.0.2, Apache Maven 3.10.0, perl 5.34.1, bash 3.2.57.
- The tree: `master` at `6832730e09` with the change uncommitted. The final `buildtools/rust/jfx_rust.cmake` has md5
  `b17e019a2f0af91230eabf87f87e2a73`. Every run is of 2026-11-21.

## Trees
A matrix pair is two scratch build trees with a `BIN_DIR` of their own, configured with the arguments that the
`native-mac` profile of `modules/javafx.media/pom.xml` passes, and built:

```
cmake -S modules/javafx.media/native -B <tree> -DCMAKE_BUILD_TYPE=<Release|Debug> \
  -DGENSRC_DIR=<repo>/modules/javafx.media/target/gensrc \
  -DHEADERS_DIR=<repo>/modules/javafx.media/target/gensrc/headers -DBIN_DIR=<tree>-bin \
  -DJFX_VER=28 -DJFX_FVER=28,0,0,0 -DJFX_BUILD_ID=<id> -DJFX_RUST=<true|false> [-DCMAKE_OSX_ARCHITECTURES=x86_64]
cmake --build <tree> --parallel
```

- **Rust** is `-DJFX_RUST=true`, **C** is `-DJFX_RUST=false`; the x86_64 trees add `-DCMAKE_OSX_ARCHITECTURES=x86_64`.
- **Baseline:** the C build of `6832730e09`, configured the same way, for arm64 Release and Debug and x86_64 Release.
  The x86_64 Debug baseline was built from the `6832730e09` versions of the helper, `mac.cmake` and
  `CMakeLists.txt` in a scratch copy. Every C tree of the matrix equals its baseline in the exports and `otool -L` of
  `libjfxmedia`, in the avf imports and in the `link.txt` of `jfxmedia` after path normalisation; the first three
  also in the exports and `otool -L` of all five dylibs, `flags.make`, `build.make`, the avf `link.txt` and
  `Makefile2`.
- **Helper versions.** The independent verification measured all four pairs with the helper before the fix round
  (md5 `c17c811d1ba6d0f28568ecf10a8e6ed6`). The fix round rebuilt the arm64 Release, arm64 Debug and x86_64 Release
  Rust trees with the final helper: the exports, `otool -L`, avf imports, N_OSO counts and origin members were the
  same. Configured natively on this Mac, a minimal project with the slice gets the same files from both helpers,
  with Rust on and off, in Release and in Debug. The x86_64 Debug pair was not rebuilt with the final helper.

## Matrix
`libjfxmedia.dylib`, Rust tree against C tree. "diff 0" is an empty `diff` of the sorted lists.

| Pair | `nm -gU`, Rust / C | `otool -L` | avf imports | `LC_BUILD_VERSION` |
| --- | --- | --- | --- | --- |
| arm64 Release | 501 / 501, diff 0 | 10 entries, equal in order | 13 / 13, diff 0 | minos 11.0, sdk 27.2, both |
| arm64 Debug | 557 / 557, diff 0 | equal in order | 13 / 13, diff 0 | the same |
| x86_64 Release | 519 / 519, diff 0 | equal in order | 13 / 13, diff 0 | the same |
| x86_64 Debug | 575 / 575, diff 0 | equal in order | 13 / 13, diff 0 | the same |

| Pair | Size, Rust / C (bytes) | N_OSO, Rust / C | All stabs, Rust / C | Unexported list |
| --- | --- | --- | --- | --- |
| arm64 Release | 529,392 / 206,032 | 0 / 0 | 0 / 0 | 712 names |
| arm64 Debug | 1,841,824 / 438,528 | 90 / 29 | 13,370 / 4,119 | 2,613 names |
| x86_64 Release | 528,088 / 187,536 | 0 / 0 | 0 / 0 | 622 names |
| x86_64 Debug | 1,834,368 / 451,456 | 92 / 29 | 13,345 / 4,127 | 2,539 names |

- No Rust tree exports a name that starts with `__R` or `_rust_`. The only name of the archive that the library
  exports is `_jfxm_abi_version`: no list shares a name with the exports.
- Each list equals `nm --no-llvm-bc -gUj <archive>` less `_jfxm_abi_version`, recomputed by the verification.
- The Debug sizes depend on the length of the build-tree path: the fix round's arm64 Debug tree is 1,841,072 bytes
  and the Maven Debug build 1,835,744.
- The origin log of each Rust tree names a member of the tree's own archive, which lies in
  `<build tree>/cargo/macos-11.0/`:

| Pair | Member that defines `_jfxm_abi_version` | Map lines from the archive |
| --- | --- | --- |
| arm64 Release | `aarch64-apple-darwin/release/libjfxmedia.a(jfxmedia-be3e4707ae7bb7a4…-cgu.0.rcgu.o)` | 1,454 |
| arm64 Debug | `aarch64-apple-darwin/debug/libjfxmedia.a(jfxmedia-5366bd8c07e327d7…-cgu.09.rcgu.o)` | 8,285 |
| x86_64 Release | `x86_64-apple-darwin/release/libjfxmedia.a(jfxmedia-6740e7e4549a353f…-cgu.0.rcgu.o)` | 1,231 |
| x86_64 Debug | `x86_64-apple-darwin/debug/libjfxmedia.a(jfxmedia-e96a86b6be2ee924…-cgu.09.rcgu.o)` | 7,171 |

- **The other dylibs.** `libfxplugins`, `libglib-lite`, `libgstreamer-lite` and `libjfxmedia_avf` have equal exports
  and `otool -L` in all four pairs, and the avf `link.txt` holds no Rust item. `BIN_DIR` holds the same five dylibs.
- **Smoke run** (arm64 only): a C program built with `cc -arch arm64 -mmacosx-version-min=11.0` `dlopen`s the library
  and prints `jfxm_abi_version() = 4` for the Release and Debug Rust builds and for both C builds.
  `libjfxmedia_avf.dylib` loads with `RTLD_NOW` against both Rust builds. The value cannot tell the origin; the map
  can: the function is 0x84 bytes at index `[ 35]` in the Rust Release build and 0x8 bytes in the C build.

## `otool -L`
The same ten entries, in this order, in all eight libraries of the matrix and in the baseline (arm64 Release shown;
the `(compatibility version …)` part is on the next line here, one line in `otool`'s output):

```
@rpath/libjfxmedia.dylib (compatibility version 0.0.0, current version 0.0.0)
@rpath/libgstreamer-lite.dylib (compatibility version 0.0.0, current version 0.0.0)
@rpath/libglib-lite.dylib (compatibility version 0.0.0, current version 0.0.0)
/usr/lib/libobjc.A.dylib (compatibility version 1.0.0, current version 228.0.0)
/System/Library/Frameworks/Cocoa.framework/Versions/A/Cocoa
    (compatibility version 1.0.0, current version 24.0.0)
/System/Library/Frameworks/CoreVideo.framework/Versions/A/CoreVideo
    (compatibility version 1.2.0, current version 776.2.0)
/usr/lib/libc++.1.dylib (compatibility version 1.0.0, current version 2200.32.0)
/usr/lib/libSystem.B.dylib (compatibility version 1.0.0, current version 1359.40.3)
/System/Library/Frameworks/CoreFoundation.framework/Versions/A/CoreFoundation
    (compatibility version 150.0.0, current version 5027.1.8)
/System/Library/Frameworks/Foundation.framework/Versions/C/Foundation
    (compatibility version 300.0.0, current version 5027.1.8)
```

A link that also passes rustc's `-lSystem -lc -lm` lists `libSystem.B` before `libc++.1`, with the same set of
libraries.

## The names `libjfxmedia_avf` imports
`nm -m -u libjfxmedia_avf.dylib`, the names bound `(from libjfxmedia)`: the same 13 in Release and Debug, on arm64 and
x86_64, against the C and the Rust builds. All 13 are in the C build's export list.

```
__ZN11CAudioTrackC1ExNSt3__112basic_stringIcNS0_11char_traitsIcEENS0_9allocatorIcEEEEN6CTrack8EncodingEbS6_iif
__ZN11CVideoFrame8GetWidthEv
__ZN11CVideoFrame9GetHeightEv
__ZN11CVideoTrackC1ExNSt3__112basic_stringIcNS0_11char_traitsIcEENS0_9allocatorIcEEEEN6CTrack8EncodingEbiifb
__ZN12CBandsHolder10ReleaseRefEPS_
__ZN12CBandsHolder6AddRefEPS_
__ZN12CVVideoFrame17IsFormatSupportedEj
__ZN12CVVideoFrameC1EP10__CVBufferdy
__ZN14CSubtitleTrackC1ExNSt3__112basic_stringIcNS0_11char_traitsIcEENS0_9allocatorIcEEEEN6CTrack8EncodingEbS6_
__ZN7CLogger11s_SingletonE
__ZN7CLogger14CreateInstanceEPPS_
__ZN7CLogger6logMsgEiPKc
__ZN8CLocator11GetSizeHintEv
```

Demangled (`std::string` for `std::__1::basic_string<char, std::__1::char_traits<char>, std::__1::allocator<char>>`),
with the file of `modules/javafx.media/src/main/native/jfxmedia/` that defines each:
- `CAudioTrack::CAudioTrack(long long, std::string, CTrack::Encoding, bool, std::string, int, int, float)`:
  `PipelineManagement/AudioTrack.cpp`
- `CVideoFrame::GetWidth()` and `CVideoFrame::GetHeight()`: `PipelineManagement/VideoFrame.cpp`
- `CVideoTrack::CVideoTrack(long long, std::string, CTrack::Encoding, bool, int, int, float, bool)`:
  `PipelineManagement/VideoTrack.cpp`
- `CBandsHolder::ReleaseRef(CBandsHolder*)` and `CBandsHolder::AddRef(CBandsHolder*)`:
  `platform/gstreamer/GstAudioSpectrum.cpp`
- `CVVideoFrame::IsFormatSupported(unsigned int)` and `CVVideoFrame::CVVideoFrame(__CVBuffer*, double, unsigned long
  long)`: `platform/osx/CVVideoFrame.mm`
- `CSubtitleTrack::CSubtitleTrack(long long, std::string, CTrack::Encoding, bool, std::string)`:
  `PipelineManagement/SubtitleTrack.cpp`
- `CLogger::s_Singleton`, `CLogger::CreateInstance(CLogger**)` and `CLogger::logMsg(int, char const*)`:
  `Utils/Logger.cpp`
- `CLocator::GetSizeHint()`: `Locator/Locator.cpp`

An `-exported_symbols_list` of the 58 header names makes the avf link fail with these 13 undefined symbols, the first
`"CAudioTrack::CAudioTrack(...)", referenced from: -[AVFMediaPlayer extractTrackInfo] in AVFMediaPlayer.mm.o`. With
them added it links, and the library exports 71 names instead of 501 (Release) or 557 (Debug). Hidden then: 346 `__Z`
C++ names, 66 vtables and typeinfo, 5 Objective-C names of `OSXMediaPlayer` and 13 C names (`_ColorConvert_*`,
`_color_t*`, `_gLastFormat`), and in Debug 56 more: 32 inline member functions of jfxmedia's classes and 24 libc++
template instantiations. A stale `libjfxmedia_avf.dylib` against a `libjfxmedia.dylib` without them fails at `dlopen`
(`Symbol not found: __ZN7CLogger11s_SingletonE`), which Java swallows in `OSXPlatform`.

## Mach-O differences
Rust-linked library against its C twin, for the owner to accept. Equal in all four pairs: the exports, `otool -L`,
the avf imports, `LC_BUILD_VERSION` and the kinds of load commands; `LC_RPATH` is each tree's own `BIN_DIR`, as in
the C build.

| Item | arm64 Release | arm64 Debug | x86_64 Release | x86_64 Debug |
| --- | --- | --- | --- | --- |
| File size, Rust less C (bytes) | +323,360 | +1,403,296 | +340,552 | +1,382,912 |
| Header flag added | `MH_HAS_TLV_DESCRIPTORS` | the same | the same | the same |
| Sections, C → Rust | 33 → 36 | 32 → 36 | 31 → 35 | 31 → 35 |
| `__TEXT,__eh_frame` | 0x88 → 0x5160 bytes | new | new | new |
| `sizeofcmds` | 4,088 → 4,336 | 4,008 → 4,336 | 3,920 → 4,240 | 3,912 → 4,240 |
| `__text` size | 0xf5dc → 0x3f34c | 0x20664 → 0xb9628 | 0x114da → 0x49cb0 | 0x2defa → 0xd69f0 |
| Undefined imports, C → Rust | 206 → 248 | 211 → 393 | 206 → 249 | 212 → 392 |
| `nsyms`, C → Rust | 972 → 1,606 | 5,554 → 18,047 | 940 → 1,544 | 5,535 → 17,996 |
| `nlocalsym`, C → Rust | 265 → 857 | 4,786 → 17,097 | 215 → 776 | 4,748 → 17,029 |
| N_OSO (debug map), C → Rust | 0 → 0 | 29 → 90 | 0 → 0 | 29 → 92 |
| `_jfxm_abi_version`: size, map index | 0x84, `[ 35]` | 0x30, `[ 44]` | 0x70, `[ 35]` | 0x22, `[ 44]` |

- **New sections:** `__DATA,__thread_vars`, `__DATA,__thread_data` and `__DATA,__thread_bss` in all four, and
  `__TEXT,__eh_frame` where the C build has none.
- **New imports:** all bound to libSystem; none of the C build's is removed. Release: 42 on arm64 (`_Unwind_*`,
  `_dyld_*`, `_tlv_*`, the malloc family, `open`, `read`, `write`, dispatch semaphores), 43 on x86_64 (with `$INODE64`
  and `$NOCANCEL` variants and `___bzero`). Debug, which links std without LTO: 182 and 180, among them `_fork`,
  `_execvp`, `_posix_spawnp`, `_dlopen`, `_setuid`, `_chroot` and sockets, which jfxmedia never calls.
- **Debug map:** Release has none in either build (`-S`). In Debug the 61 (x86_64 63) new N_OSO entries name the
  members of `<build tree>/cargo/macos-11.0/<triple>/debug/libjfxmedia.a` by their absolute path.
- **Strings:** the Release builds hold 48 panic-location paths (`/rustc/0ef1bc…/library/…`,
  `/cargo/registry/…/{rustc-demangle,gimli,addr2line}…`, `buildtools/rust/jfx-rust-support/src/lib.rs`) and no
  absolute path of the build machine. Not checked in Debug.
- **Without `-S`** the arm64 Release link has a debug map: 1 N_OSO with the absolute build-tree path of the archive
  member and 1,950 stabs in all, 561,184 bytes. `-S` removes only the debug map; on the C-only Release link it is a
  no-op (byte-identical output).

## Negative controls
The checks ran by hand with the arguments of the tree's `build.make`, the map, library, list and log redirected, on
hand links of the arm64 Release Rust tree (its `link.txt` with `-o`, `-map` and the list redirected) or on doctored
copies of real maps. The independent verification ran them with the helper before the fix round. The fix round ran
the origin-check cases again with the final helper, with the same verdicts, and added the cases of check (d); it did
not change the listing step. The texts are the final helper's: `<…>` replaces paths, and CMake's line wrapping is
undone.

### The origin check after the link
- **Positive control,** the unchanged relink, byte-identical to the tree's library: rc 0, the two lines of the origin
  log (see C4 under "Maven gates").
- **A C definition wins:** the C tree's `jfxmedia_api.cpp.o` linked in. ld64 gives rc 0 and no diagnostic, the library
  is 206,032 bytes, and the archive is not loaded. The check: rc 1, message (a) with the remedy.
- **The same in a real build:** `-DJFX_RUST_JFXMEDIA_ABI_VERSION=1` taken out of a scratch tree's `flags.make`. Build
  rc 2 with message (a), then `make: *** Deleting file '<…>/libjfxmedia.dylib'`; the next build relinks and fails
  again.
- **Another archive:** a copy of the crate's archive in another directory. rc 1, message (a) in its foreign form,
  without the remedy, and message (d) too (below).
- **A Rust name exported:** the list without `_rust_eh_personality`, 502 exports. rc 1, message (c) naming it. **No
  list at all,** 503 exports: rc 1, message (c) naming `__RNvNtCsi4LIHK2nfKK_3std9panicking11EMPTY_PANIC` and
  `_rust_eh_personality`.
- **The export hidden:** the list plus `_jfxm_abi_version`, 500 exports. rc 1, message (b).
- **No map:** rc 1, `jfx_rust: no linker map <…> after the link of jfxmedia, so the origin of its Rust exports cannot
  be checked`.
- **A C name hidden by the list:** in a real Release map, `_rust_eh_personality` moved from `[ 35]` to `[  1]`
  (`Media.cpp.o`): rc 1, message (d) naming it and `Media.cpp.o`. In the Debug map, the `CURRENT` thread-local moved
  from `[  0]` to `[ 12]`: rc 1, naming `Logger.cpp.o`. A list name added at `[  0] linker synthesized`: rc 0.
- **A hostile map:** raw byte 0xE9, unbalanced `[` and `]`, `;`, a trailing `\`, index `[1234]`, an Objective-C
  `-[Foo bar:baz]` line, a dead-stripped C `_jfxm_abi_version`, and the archive reached through a symbolic link with a
  non-ASCII name and through `/tmp`: rc 0, 1,456 lines from the archive. The same map with the C++ export
  `__ZN7CLogger11s_SingletonE` given to the archive's index: rc 1, message (c) naming it.
- **`-u` alone,** the link without the archive: link rc 1, `Undefined symbols for architecture arm64:
  "_jfxm_abi_version", referenced from: <initial-undefines>` and `ld: symbol(s) not found for architecture arm64`.

The messages (a) to (d) start `jfx_rust: jfxmedia (target jfxmedia)` and end `This text is also in
<…>/jfxmedia-origin.log.`:
- (a): `does not take the Rust exports of its slice JFXMEDIA_ABI_VERSION from Rust: jfxm_abi_version is defined by
  <…>/ffi/jfxmedia_api.cpp.o, not by the jfxmedia crate's archive <…>/libjfxmedia.a`, then `A C definition of a slice
  symbol wins over the archive at link time. Compile the C definition out with #ifndef JFX_RUST_JFXMEDIA_ABI_VERSION.`
  The foreign form: `jfxm_abi_version is defined by <copy>/libjfxmedia.a(<member>): another archive defines it, not
  the jfxmedia crate's archive <…>`.
- (b): `does not export jfxm_abi_version of its slice JFXMEDIA_ABI_VERSION: nm -g -U of <…>/libjfxmedia.dylib does not
  list it. <…>/jfxmedia.unexported must not hold it, and its definition must not be hidden.`
- (c): `exports 1 name(s) that the linker map takes from the jfxmedia crate's archive and no slice of the target lists
  in EXPORTS (at most 20 shown): _rust_eh_personality`, then `The list <…>/jfxmedia.unexported, written before the
  link, must hold every name the archive defines but those EXPORTS.`
- (d): `hides 1 definition(s) that the linker map takes from outside the jfxmedia crate's archive (at most 20 shown):
  _rust_eh_personality is defined by <…>/Media.cpp.o`, then `A C symbol has the name of a symbol of the Rust archive,
  and so is hidden: the list <…>/jfxmedia.unexported holds every name the archive defines. Rename the C symbol.` In
  the foreign-archive case it names the two list names that the copy defines, `_rust_eh_personality` and
  `__RNvNtCsi4LIHK2nfKK_3std9panicking11EMPTY_PANIC`, and its "A C symbol" is wrong for that artificial case.

Before (d) was added, its rule was run on seven real maps (arm64 and x86_64, Release and Debug). The Release maps
define none of their 712 and 622 list names outside the archive. Each Debug map gives 6 to `[  0] linker
synthesized`, all std thread-local descriptors (`…3std6thread7current7CURRENT`, `…current2id2ID`, `…SPAWN_HOOKS…`,
`…RandomState3new4KEYS…`, `…mpmc7context…CONTEXT…`, `…waker17current_thread_id5DUMMY…`), whose `$tlv$init` lines are
the std member's. Without the "linker synthesized" exception the Debug map fails, naming those 6. The whole check
takes 0.04 to 0.06 s on a Release map and 0.10 to 0.11 s on a Debug map (11,091 symbol lines).

### The list before the link
No failing case writes a list.
- **No such archive:** rc 1, `jfx_rust: "<nm> --no-llvm-bc -g -U -P <…>" failed (1), so the names of the jfxmedia
  crate cannot be kept out of the exports of jfxmedia:`, then nm's stderr.
- **A truncated archive:** rc 1, the same with `failed (Segmentation fault)`: Apple's nm crashes on it.
- **A text file:** rc 1, the same, with nm's `The file was not recognized as a valid object file`.
- **`/usr/bin/false` as nm:** rc 1, `failed (1)`. **`/usr/bin/true` as nm:** rc 1, `jfx_rust: of the 0 names that nm
  lists in <…>, none is _jfxm_abi_version, an EXPORTS symbol of a slice of jfxmedia: either the jfxmedia crate does
  not define it as a #[unsafe(no_mangle)] pub extern "C" fn, or nm did not read every member of the archive`.
- **The real nm:** rc 0, the tree's list, byte for byte.
- **A real build with `JFX_RUST_NM=/usr/bin/false` in `build.make`:** build rc 2 at the listing step; no library is
  linked, and the old list stays.

Plain `nm -gU`, without `--no-llvm-bc`, exits 1 on the archives with 23 (Release) or 32 (Debug) errors such as
`Unknown attribute kind (105) (Producer: 'LLVM23.1.5-rust-1.100.0-stable' Reader: 'LLVM APPLE_1_2100.3.34.2_0')` and
prints 562 of the 713 names of the Release archive.

### Configure
- **`-DCMAKE_OSX_ARCHITECTURES="arm64;x86_64"`, Rust on:** `jfxmedia: its Rust slices are built for one macOS
  architecture, arm64 or x86_64, and this tree builds for arm64, x86_64 (CMAKE_OSX_ARCHITECTURES). Configure with
  -DCMAKE_OSX_ARCHITECTURES=arm64 or -DCMAKE_OSX_ARCHITECTURES=x86_64, or with -DJFX_RUST=false to build the C
  implementation of jfxmedia instead.` With Rust off the tree configures. `arm64e`: the same error, `this tree builds
  for arm64e`.
- **No std for the triple,** in a toy project with a helper copy that asks for `aarch64-apple-ios`: `<project>: its
  Rust slices need the Rust toolchain 1.100.0 pinned in <…>/rust-toolchain.toml, and it has no standard library for
  aarch64-apple-ios (rustc --print target-libdir --target aarch64-apple-ios: <…>/lib/rustlib/aarch64-apple-ios/lib),
  one of the targets that rust-toolchain.toml lists for "rustup toolchain install".`, then `Install rustup from
  https://rustup.rs and run "rustup toolchain install" in <…>, or configure with -DJFX_RUST=false to build the C
  implementation of <project> instead.`
- **A slice marked `C_DELETED`, Rust off** (toy): `cdel: the C implementation of its slice JFXMEDIA_ABI_VERSION is
  deleted, so it cannot be built with -DJFX_RUST=false`.
- **`CMAKE_NM` empty** (toy): `jfx_add_rust_slice(<target>): CMake found no nm (CMAKE_NM), … Configure with
  -DCMAKE_NM=<nm>, or with -DJFX_RUST=false to build the C implementation of <target> instead.`
- **rustc's list given `-framework CoreFoundation`** (helper copy): configure prints `links -framework CoreFoundation`,
  `link.txt` ends `libjfxmedia.a -framework CoreFoundation`, the build gives rc 0, and `otool -L` lists
  CoreFoundation. Given `-Wl,-foo`, a lone `-framework`, or `-framework -lz`: `jfx_rust: rustc --print
  native-static-libs lists "<item>" for the Rust standard library, a link item that jfx_rust.cmake does not know: on
  macOS it knows -l<name> and -framework <Name> only`.
- **A Rust tree configured again with `-DJFX_RUST=false`, then `true`:** off, the origin log, map and list are
  removed, only `jfxmedia_api.cpp.o` is compiled again, and the library is 206,032 bytes with 501 names and no Rust
  item in `link.txt`; on again, the three files are written again, the library is 529,392 bytes, and `link.txt` is
  byte-identical to that of the first Rust configure.
- **`-DCMAKE_OSX_DEPLOYMENT_TARGET=12.0`:** `cargo directory <…>/cargo/macos-12.0`, a fresh cargo build, the crate
  member and every dylib minos 12.0, no ld warning. Back to 11.0: `macos-11.0` reused, minos 11.0.
- **`MACOSX_DEPLOYMENT_TARGET=13.0` inherited at build time,** cargo forced to rebuild: the crate member is minos 11.0.

### The CI step
"Verify the exports of jfxmedia, which links Rust", its body run under `/bin/bash -e` 3.2 with BSD tools, with
`RUNNER_TEMP` and the `GITHUB_*` files in a scratch directory. The first case ran against the Maven tree's Release
build; the others in scratch trees with copied or linked inputs, each doctored library checked beforehand to differ
from the Maven one only in the intended fault.
- **The Maven tree,** the bodies of both macOS jobs: rc 0, `58 names in the header, 501 exported`, both diffs empty,
  no N_OSO; the Maven tree unchanged (1,519 entries, by time, size and mode). The C-only build took 5 s.
- **A header name the library lacks:** rc 1, `59 names in the header, 501 exported`, then `_jfxm_not_in_the_library`.
- **The library lacks a header name** (a relink with `-load_hidden`): rc 1, printing `_jfxm_abi_version`.
- **Extra exports** (a relink without a list, 503 names): rc 1, the names diff `>
  __RNvNtCsi4LIHK2nfKK_3std9panicking11EMPTY_PANIC` and `> _rust_eh_personality`.
- **Another load order** (a relink with `-lSystem -lc -lm`): rc 1, the `otool -L` diff with `libc++.1` after
  `libSystem.B`.
- **A Release build with a debug map** (a relink without `-S`): rc 1, the N_OSO line, then `is a Release build with a
  debug map (the N_OSO stabs above): link it with -S`.
- **No library:** rc 1, `was not built`. **No origin log:** rc 1, `no <…>/jfxmedia-origin.log: the link of <…> did
  not check the origin of its Rust exports`.
- **The slice marked `C_DELETED`** in a copy of `mac.cmake`: rc 1. The header check passes, then the C-only configure
  stops: `jfxmedia: the C implementation of its slice JFXMEDIA_ABI_VERSION is deleted, so it cannot be built with
  -DJFX_RUST=false`.

## Windows and Linux
Not built. On this Mac, a minimal project with media's `CMAKE_OSX_DEPLOYMENT_TARGET 11.0` and the slice was configured
with `APPLE` forced false (Linux) and, for Windows, `APPLE` false, `WIN32` true and `USERPROFILE=$HOME`:
- the helper of `6832730e09` against this story's before the fix round: identical configure output and generated
  files in the implementer's 5 configurations, the build review's 8 pairs and the verification's 6;
- the helper before the fix round (`c17c811d…`) against the final one: identical in 12 pairs (Linux and Windows,
  Rust on and off, no build type, Release and Debug; 41 to 70 files per tree, `CMakeCache.txt`, every `build.make`
  and `link.txt`, the version script and the probe archive among them), and in 4 native macOS pairs (Rust on and
  off, Release and Debug). Left out of the comparison: the try-compile `CMakeFiles/4.4.4/*.bin` and
  `CMakeConfigureLog.yaml`.

The simulation used a Darwin rustc and Unix Makefiles, not MSVC or GNU ld.

## Maven gates
From the repository root, after the last change to the helper, in this order. Run / failures / errors / skipped:

| Gate | Command | rc | Time | jslc | base | graphics | media |
| --- | --- | --- | --- | --- | --- | --- | --- |
| C1 | `mvn -pl modules/javafx.media -am verify` | 0 | 79 s | 437/0/0/0 | 5510/0/0/22 | 26696/0/0/858 | 41/0/0/6 |
| C2 | the same with `-DJFX_RUST=false` | 0 | 78 s | the same | the same | the same | 41/0/0/6 |
| C3 | the same with `-DCONF=Debug` | 0 | 87 s | the same | the same | the same | 41/0/0/6 |
| C4 | `mvn -pl modules/javafx.media -am verify` | 0 | 87 s | the same | the same | the same | 41/0/0/6 |

- **Skipped in media,** the same 6 in every gate, each a `TestAbortedException` with "there is no hooks build of
  jfxmedia on macOS…": `SourceElementReleaseTest` 4 times, `JfxmTestHooksExportTest#theHooksBuildExportsEveryTestHook`
  and `SpectrumBandChangeTest#theHooksBuildDropsStaleMessages…`. Base: 22 `@Disabled` with JDK ids. Graphics: 744
  "Disabled on operating system: Mac OS X", 19 assumption aborts, the rest `@Disabled` with JDK ids.
- **cargo test** (C1, C3, C4): 14 passed, 0 failed (9 + 2 + 1 + 1 + 1), and two doc-test runs with 0 tests. C2: "no
  Rust slice is enabled in this tree".
- **jfx_rust_lint** (C1, C3, C4): `fmt.log` empty; clippy with `-D warnings`; `cbindgen 0.29.4 wrote
  …/jfxmedia_rs_functions.h` and `…_rs_types.h`; `the jfxmedia crate declares exactly the 1 EXPORTS symbol(s)`; the
  ABI check built; `check-crate-licences --self-test: all 68 cases passed`; `check-crate-licences: OK, 2 linked
  crate(s), 0 build-time crate(s)`. C2: both trees print "no Rust slice is enabled".
- **C1:** `jfxmedia` was not relinked: the tree was up to date, and the final helper changes no file it generates.
- **C2:** the configure removed the origin log, map and list. `libjfxmedia.dylib` is 206,032 bytes; its 501 names and
  `otool -L` equal the Release baseline's; avf imports 13; no Rust item in `link.txt`.
- **C3:** the list, the link and the check ran, (d) included. 1,835,744 bytes; 557 names and `otool -L` equal to the
  Debug baseline's; avf imports 13; N_OSO 90. The origin log names a `…/debug/libjfxmedia.a(…cgu.09.rcgu.o)` member,
  with 8,285 map lines from the archive.
- **C4,** `modules/javafx.media/target/native/bin/libjfxmedia.dylib`: arm64, 529,392 bytes; `nm -gU` 501 names, equal
  to the Release baseline, none starting `__R` or `_rust_`, `_jfxm_abi_version` at 0x12e00; `otool -L` equal to the
  baseline in order; minos 11.0, sdk 27.2; N_OSO 0; `libjfxmedia_avf.dylib` imports the 13 names; `BIN_DIR` holds the
  five dylibs. The origin log, `<build tree>/jfx-rust/logs/jfxmedia-origin.log`, has two lines:
  - `jfx_rust: jfxmedia: jfxm_abi_version is defined by
    cargo/macos-11.0/aarch64-apple-darwin/release/libjfxmedia.a(<member>)`, the member
    `jfxmedia-be3e4707ae7bb7a4.jfxmedia.eed1b76411edb7b6-cgu.0.rcgu.o`;
  - `jfx_rust: jfxmedia: libjfxmedia.dylib exports jfxm_abi_version, and none of the other names on the 1454 lines of
    the map that the archive defines (nm -g -U lists 501 names)`.

In an x86_64 tree on this Mac `jfx_rust_test` fails: `could not execute process … (never executed)`, `Bad CPU type in
executable (os error 86)`. Maven never configures such a tree; the `macos-15-intel` CI job runs the x86_64 tests.
