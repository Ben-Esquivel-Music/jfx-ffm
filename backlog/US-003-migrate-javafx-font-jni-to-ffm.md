# US-003 — Migrate `javafx_font` from JNI to FFM

| Field | Value |
| --- | --- |
| **Status** | 🟢 **Complete** (all three platforms; macOS 2026-10-24). Windows half done; Linux half done 2026-09-15 (landed in PR #12); macOS half done 2026-10-24 (landed in PR #38, `4745af9f89`) |
| **Parent epic** | Fully remove JNI from `javafx.graphics` (replaced by a functioning FFM API) |
| **Branch of record** | `ffm/graphics` |
| **Repository** | `Ben-Esquivel-Music/jfx-ffm` |
| **Priority** | High — largest single JNI surface remaining in the module (200 of ~335 exports) |
| **Estimate** | XL — 8 native files, 6 499 LOC, 200 `JNIEXPORT`, 4 Java binding classes |
| **Blocked by** | Nothing. Both halves that US-001's lack of build capability blocked are done: Linux in PR #12, macOS on a macOS host on 2026-10-24. |
| **Audit of record** | `jni-auditor`, saved at `session-state/.../files/audit-javafx-font.md` (34 KB) |

---

## Story

> **As** a JavaFX platform maintainer,
> **I want** the font layer's 200 JNI entry points replaced by direct FFM bindings to the
> platform font APIs,
> **so that** ~6 500 lines of hand-written marshalling C are deleted outright rather than
> re-plumbed, and `javafx_font` stops being the largest JNI surface in `javafx.graphics`.

---

## Why this is being carved out now

Two independent reasons.

**1. It is the biggest remaining item and deserves its own review.** At 200 `JNIEXPORT`
functions it is larger than `glass/win` (124) and larger than everything else left combined.
Folding it into the tail of the current epic would produce an unreviewable diff.

**2. It is only half-deliverable on this machine.** Every source file in `native-font` is
wrapped in a whole-file platform guard, so a Windows build compiles only two of the eight:

| File | Guard | Exports | Upcalls | Lines | Buildable here |
| --- | --- | ---: | ---: | ---: | --- |
| `directwrite.cpp` | `#ifdef WIN32` | 70 | 0 | 2 240 | ✅ |
| `fontpath.c` | `#ifdef WIN32` | 9 | 10 | 918 | ✅ |
| `coretext.c` | `#ifdef __APPLE__` | 58 | 0 | 1 101 | ❌ |
| `pango.c` | `#if defined __linux__` | 34 | 0 | 413 | ❌ |
| `freetype.c` | `#if defined __linux__ \|\| ANDROID_NDK` | 15 | 0 | 638 | ❌ |
| `MacFontFinder.c` | `#ifdef __APPLE__` | 6 | 0 | 241 | ❌ |
| `dfontdecoder.c` | `#ifdef __APPLE__` | 5 | 0 | 155 | ❌ |
| `fontpath_linux.c` | `#if defined (__linux__)` | 3 | 7 | 793 | ❌ |
| **Total** | | **200** | **17** | **6 499** | **79 exports / 3 158 lines** |

`win.cmake:260-266` globs the whole `native-font` directory into one target; the other six
files compile to empty translation units on Windows. So the Windows-verifiable slice is
**79 of 200 exports (40%)** and **3 158 of 6 499 lines (49%)**. The remaining 121 exports
inherit US-001's constraint exactly and must not be migrated blind.

---

## The central finding: delete, do not wrap

The audit's verdict is **not** "build an FFM facade." It is **"delete the C and bind the OS
directly from Java."** Three measured facts support that:

**a. There is almost nothing for the C to do.** 186 of ~196 natives are 1:1 WRAPPER functions
over DirectWrite COM / CoreText / FreeType / Pango / Win32 font enumeration. 6 are PURE.
**Zero** are OS-CALL functions that must remain in our C.

**b. Every font-API binding file has zero upcalls.** All 17 `Call*Method` sites in the whole
tree live in `fontpath.c` (10) and `fontpath_linux.c` (7) — the font *enumeration* files.
`directwrite.cpp`, `coretext.c`, `pango.c`, `freetype.c`, `MacFontFinder.c` and
`dfontdecoder.c` never call back into Java at all. **The main binding surface needs no
callback table whatsoever**, which is the single biggest simplification available in this
epic.

**c. Half the C exists only to move struct fields across the JNI boundary.** The tree
contains **19 `cacheXxxFields` functions, 16 `setXxxFields` functions and 257
`GetFieldID`/`Get*Field`/`Set*Field` calls** (e.g. `directwrite.cpp:79-153` for
`DWRITE_GLYPH_METRICS` alone). Under FFM this entire category **disappears** — Java reads and
writes the struct directly in a `MemorySegment` through a `StructLayout`. None of it needs to
be ported; it needs to be deleted.

### COM dispatch from Java is the enabling technique

Java can perform COM vtable dispatch without any C:

- read the vtable pointer at object word 0;
- index the desired slot;
- `Linker.downcallHandle` with `this` as the leading argument.

`IUnknown` is fixed: `QueryInterface` = 0, `AddRef` = 1, `Release` = 2. Interface slot indices
come from `dwrite.h` via `jextract`. This removes `directwrite.cpp`'s 70 exports without a
replacement shim.

### The one genuine residue

`directwrite.cpp:936-1768` defines three **C++ objects that implement COM interfaces so that
DirectWrite can call back into them**: `JFXTextAnalysisSink`, `JFXTextRenderer`,
`JFXGeometrySink`. Note these are *inbound from DirectWrite*, not inbound to Java — they hold
results in C++ fields which Java then reads through ordinary getters. Two options:

- **(preferred)** synthesise the COM objects in Java — an upcall-stub-backed vtable, with
  multi-IID `QueryInterface` and struct-by-value upcall support; or
- **(fallback)** keep a ~200-line plain-C `jfxfont_` shim for these three only.

Decide with a spike (see sub-task F). Do not assume the Java route works until a
`QueryInterface` round-trip is demonstrated.

---

## Scope

### In scope

- `directwrite.cpp` and `fontpath.c` — full migration and deletion (Windows, verifiable here).
- The Java binding classes that own them: `com.sun.javafx.font.directwrite.OS` (70 natives)
  and the Windows paths of `com.sun.javafx.font.PrismFontFactory` (10 natives).
- Deleting the struct-marshalling boilerplate that those two files contain.
- Replacing Windows font enumeration (`fontpath.c`) with Java, including its 10 upcalls.
- Fixing the orphan identified below.

### Out of scope

- `coretext.c`, `MacFontFinder.c`, `dfontdecoder.c` (macOS) and `pango.c`, `freetype.c`,
  `fontpath_linux.c` (Linux) — **de-scoped for the same reason as US-001**: no build or test
  capability. Track them as US-003b when a macOS/Linux runner exists.
- Any change to font *selection*, *fallback*, *shaping* or *rasterisation* behaviour. This is
  a transport-layer change only.
- Text layout and the `javafx.scene.text` API surface.

### Known orphan to resolve first

`OSFreetype.java:102` declares

```java
static final native int FT_Get_Char_Index(long face, long charcode);
```

and **no C implementation exists anywhere in `src/main`** (a repo-wide search returns only
this declaration). Either it is dead and should be deleted, or a caller is relying on a
`UnsatisfiedLinkError` path. Resolve before touching FreeType.

---

## Definition of Ready (entry gate)

- [ ] Golden-capture parity tests exist for Windows font enumeration and DirectWrite metrics,
      captured **against the current JNI build**, following the `javafx_iio` precedent.
- [ ] A baseline test count and `javafx_font.dll` export dump are recorded.
- [ ] `jextract` output for `dwrite.h` is generated and slot indices verified against at least
      two interfaces by hand.
- [ ] Sub-task F (COM-synthesis spike) has produced a yes/no answer.

---

## Acceptance criteria

1. `com.sun.javafx.font.directwrite.OS` declares **zero** `native` methods.
2. Windows font enumeration is implemented in Java; `fontpath.c` is deleted.
3. `directwrite.cpp` is deleted, or reduced to a documented `jfxfont_` shim containing **only**
   the three COM callback objects.
4. No `cacheXxxFields` / `setXxxFields` / `Get*Field` marshalling code survives on the Windows
   path — struct access is via `StructLayout`.
5. `dumpbin /exports javafx_font.dll` shows **zero** `Java_com_sun_javafx_font_*` symbols.
6. `mvn -pl modules/javafx.graphics **clean** package` succeeds — `clean` is mandatory, since
   incremental builds hide stale-JNI-header breakage.
7. Test count ≥ the recorded baseline with **0 failures**.
8. Font rendering is verified by eye on a scene using system fonts, bold/italic synthesis, and
   at least one font with a `.dfont`/TTC-style collection.
9. `ffm-reviewer` sign-off on the diff.
10. The macOS/Linux files are untouched and still compile in their guards.

---

## Sub-tasks

Ordered. Each is a commit-sized, behaviour-neutral change.

### A. Resolve the `FT_Get_Char_Index` orphan
Delete it or implement it. Ten minutes; do it first so it stops confusing later inventories.

### B. Golden-capture parity tests (blocking)
Capture enumerated font families, glyph metrics for a fixed string, and shaping results from
the **current JNI build**. Without this there is no parity oracle.

### C. `fontpath.c` → Java
9 exports, 10 upcalls, 918 lines. Windows font enumeration is registry + `GetFontResourceInfo`
work that Java can do directly. This is the easiest real win and it removes 10 of the tree's
17 upcalls.

### D. Outbound DirectWrite COM from Java
70 exports, 0 upcalls. Bind `IDWriteFactory` and friends via vtable dispatch. Land it in
interface-sized slices, not one commit.

### E. Delete the struct-marshalling layer
Should fall out of D automatically. Verify nothing references the mirror classes' fields from
C any more.

### F. Spike: COM object synthesis in Java
Prove (or disprove) that an upcall-stub vtable satisfies DirectWrite's `QueryInterface` for
`JFXTextAnalysisSink`. **Time-boxed.** If it fails, keep the ~200-line shim and say so
explicitly in the story's close-out notes.

### G. Close-out
Remove the `font` target's JNI include dirs if and only if nothing else in the target needs
them. Confirm `grep -rn 'jni\.h\|JNIEnv' native-font/` returns only guarded non-Windows files.

---

## Suggested agents and skills

| Role | Agent |
| --- | --- |
| Re-inventory before starting (the audit will be stale) | `jni-auditor` |
| Java-side implementation (**only** agent permitted to touch Java) | `java-26` |
| C ABI / shim work, C deletion | `ffm-migrator` |
| Diff review for ABI, lifetime and thread-confinement bugs | `ffm-reviewer` |
| Golden-capture and parity tests | `ffm-test-porter` |
| CMake / toolchain | `native-build-engineer` |

Skills: `jni-to-ffm-migration`, `jfx-graphics-native`, `jfx-native-build`, `jfx-ffm-testing`.

Precedent to follow: `com.sun.prism.es2.ES2Native` on `ffm/graphics` — same
"delete the dead JNIEXPORTs, keep the live helpers" discipline.

---

## Risks

| # | Risk | Impact | Mitigation |
| --- | --- | --- | --- |
| 1 | COM vtable slot indices wrong or version-dependent | Silent call to the wrong method — arbitrary behaviour | Generate from `dwrite.h` via `jextract`; assert `QueryInterface`/`AddRef`/`Release` round-trip before anything else |
| 2 | Missing `Release` calls leak COM objects | Slow leak, only visible under long runs | Arena-scoped wrappers; explicit ref-count test |
| 3 | COM object synthesis (sub-task F) proves infeasible | Loses part of the deletion win | Accept the ~200-line shim — a shim is still a 90% deletion |
| 4 | Font enumeration rewritten in Java diverges subtly (ordering, dedup, hidden fonts) | Wrong font picked; hard to spot | Golden-capture list comparison is an AC, not optional |
| 5 | Someone migrates the mac/Linux files blind | Unverifiable breakage on platforms we cannot test | Explicitly out of scope; guarded files must be untouched |
| 6 | Struct layout mismatch vs `sizeof` | Silent metric corruption | Validate every `StructLayout` against on-device `sizeof` |
| 7 | Scope creep into shaping/fallback logic | Unreviewable diff | Transport layer only; behaviour neutrality outranks elegance |

---

## Definition of Done

- All 10 acceptance criteria met.
- `ffm-reviewer` sign-off recorded.
- ~~US-003b raised for the macOS/Linux remainder~~ No longer needed: both halves were done under
  this story (Linux in PR #12, macOS in "macOS half (2026-10-24)").
- Changes left in the working tree / a review branch — **no commits, merges or PRs without
  explicit approval from the repository owner.**

---

## macOS half (2026-10-24)

28b02ef511 is the last commit with the JNI; its `libjavafx_font.dylib` is the build the five
`mac-*-golden.txt` files were captured from. US-003 lands as one commit, so no commit can
re-capture the macOS goldens; a re-capture needs the C of 28b02ef511 with the natives
`MacFontBindings`' capture guard expects (`coretext.OS` 56, `MacFontFinder` 4,
`DFontDecoder` 5: the 68 less three dead ones).

**What was migrated**
- `coretext.OS` (58 natives), `MacFontFinder` (5) and `DFontDecoder` (5) keep every entry point;
  the bodies are Java over three FFM facades in `com.sun.javafx.font.coretext`, one per
  framework: `CFNative` (CoreFoundation, plus the shared linkage toolkit), `CGNative`
  (CoreGraphics, plus the one `CGPathApply` upcall stub) and `CTNative` (CoreText, plus
  `link()`). Together they bind 75 functions and 11 data symbols; each file is under 900 lines.
- The pure-computation C is reimplemented in Java, each behind an A/B test against the JNI
  with 0 mismatches before the switch: `GlyfTableBounds` (glyph bounds from `loca`/`glyf`),
  `CGPathSink` (glyph outlines) and `CGBitmapReader` (glyph masks). Where the C read past a
  bitmap (bpp 24 or 32 on an 8-bit context, which `CTGlyph` never requests), `CGBitmapReader`
  throws `IndexOutOfBoundsException`.
- `native-font/` (`coretext.c`, `MacFontFinder.c`, `dfontdecoder.c`: 1,685 lines) and the
  `font` target of `modules/javafx.graphics/native/mac.cmake` are deleted. `PrismFontFactory`
  no longer loads `javafx_font`; it calls `CTNative.link()`. No platform builds `javafx_font`.
- The C's crashes on NULL and its leaks are kept on purpose, so the migration stays
  behaviour-neutral.

**Acceptance results**
- Natives: 68 to 0 (every golden run prints `native methods left: OS 0, MacFontFinder 0,
  DFontDecoder 0`).
- `mvn -pl modules/javafx.graphics -am clean package`: BUILD SUCCESS, no `libjavafx_font*`
  under `target`; javafx.graphics 26,163 tests, 0 failures, 0 errors, 761 skipped.
- macOS font gate (`Mac*Test`, `PrismFontFactoryTest`, `CFNativeTest`, `CGNativeTest`,
  `CTNativeTest`, `CGPathSinkTest`, `GlyfTableBoundsTest`, `CGBitmapReaderTest`) with
  `-Djfx.parity.require=true`: 123 tests, 0 failures, 0 errors, 0 skipped; no `hs_err_pid*`.
- Render, JNI (28b02ef511) against FFM, on a scene with system fonts, bold/italic synthesis,
  two `.dfont` files and a TTC, grey and LCD text, at 1x and 2x: 0 differing pixels in all 12
  comparisons.
- Startup: the first frame comes 62 ms later (median 524 against 462 ms); 39 ms of that is
  the eager `CTNative.link()` in `PrismFontFactory`'s static initialiser.

**AC map** (the acceptance criteria above are worded for Windows)
- AC 1-5: natives 68 to 0; `native-font/` and the `mac.cmake` `font` target are deleted; none
  of the four remaining dylibs (`libglass`, `libjavafx_iio`, `libprism_es2`, `libprism_mtl`)
  exports a `Java_com_sun_javafx_font_*` symbol.
- AC 6-7: clean package, 26,163 tests, 0 failures.
- AC 8: owner check (0 differing pixels in the 12 JNI against FFM comparisons).
- AC 9: `ffm-reviewer` sign-off, approved with nits on 2026-10-24.
- AC 10: no Windows or Linux production or build file changed; the one behavioural
  `PrismFontFactory` change (`NativeLibLoader.loadLibrary("javafx_font")` becomes
  `CTNative.link()`) is inside its `isMacOSX` branch; the rest is an import swap and a comment.
  Test code is the exception: the Linux golden tests moved onto the shared golden harness, and
  `JpegNatives` and `ES2Natives` no longer list `javafx_font`. The owner's CI run covers both: PR #38's run
  40149053730 passed in all five jobs.


**Follow-up:** US-067 fixes the kept crashes and leaks.

---

## References

26ce75d02f (PR #12) removed what the first five cite; the pointers show it at the parent commit,
a544256444.

- `git show a544256444:modules/javafx.graphics/native/win.cmake`, lines 258-266 — the `font`
  target (globs the directory)
- `git show a544256444:modules/javafx.graphics/src/main/native-font/directwrite.cpp`,
  lines 79-153 — representative struct marshalling
- `git show a544256444:modules/javafx.graphics/src/main/native-font/directwrite.cpp`,
  lines 936-1768 — the three COM callback objects
- `git show a544256444:modules/javafx.graphics/src/main/java/com/sun/javafx/font/directwrite/OS.java`
  — 70 natives
- `git show a544256444:modules/javafx.graphics/src/main/java/com/sun/javafx/font/freetype/OSFreetype.java`,
  line 102 — the orphan
- `session-state/.../files/audit-javafx-font.md` — full audit
