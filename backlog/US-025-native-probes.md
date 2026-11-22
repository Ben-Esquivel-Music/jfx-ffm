# US-025 — Native probes of the three fixes

Evidence for [US-025](US-025-fix-uninitialized-and-dangling-members-in-java-webkit-branches.md): how the checks of
acceptance criteria 1 to 3 were made, the probe sources, every mutant and the results. Two probe sets exist: the
author's (`LocaleNone` and the custom `Cursor`), run before the review, and the independent reviewer's (`LocaleNone`,
the custom `Cursor` with a fake host, and `ComplexTextRun`). Both ran on 2026-11-21 on Windows 10 Pro (10.0.19045)
against the main tree at `6832730e09` plus the uncommitted change, in the state that was reviewed: the five native files
as they were before the comment rework of `CursorJava.cpp` (see "The tree the probes saw" below).

## Why native probes
- Nothing on the Java port reaches the month labels of `LocaleNone`: its formats are numeric (`MM`), the date and month
  input types are off for WebKitLegacy, and DumpRenderTree or `Internals` can switch the input types on but cannot make
  a format ask for a label (`Internals.idl` has no locale hook). So no Java test and no DumpRenderTree page can check
  acceptance criterion 1.
- On the Java port only `testing/Internals.cpp:4498` (`getCurrentCursorInfo`) reads `Cursor::type()`, `image()` and
  `hotSpot()`, and DumpRenderTree is not run in this fork. The three are inline (`platform/Cursor.h:168-169` and
  `:249-254`) and not exported: of the 15,249 names `jfxwebkit.dll` exports, 1,963 are `wkj_*`, and the other 13,286
  are C++ names of WebCore, JSC, WTF and others, among them the custom `Cursor` constructor and `Cursor::fromType`, but
  none of the three. So no committed check can see what acceptance criterion 3 asks for.
- `ComplexTextRun` is reached from Java only through text layout, and the generic runs whose member was uninitialized
  are never read past their glyph count on today's paths.

## Technique
- **A scratch copy of one unified-source bundle.** WebCore is compiled as unified bundles
  (`build/jfxwebkit-windows-release/WebCore/DerivedSources/unified-sources/UnifiedSource-3c72abbe-<n>.cpp`, each a
  list of `#include "<path>.cpp"`). The classes under test are visible only inside their bundle's translation unit:
  bundle 36 includes `platform/text/LocaleNone.cpp`, 28 `platform/java/CursorJava.cpp`, 12
  `platform/graphics/ComplexTextController.cpp` and 25 `platform/graphics/java/ComplexTextControllerJava.cpp`. The copy
  replaces exactly one include with `#include "redirected/<file>.cpp"`, a copy of the source file under test (the
  fixed file, the baseline file or a mutant), and appends the probe code with an exported C function. The redirected
  path exists in no `-I` directory, so a missing copy fails the compile instead of falling back to the tree's file.
- **The bundle's own compile command.** The response file is the bundle's command from `compile_commands.json`
  (token for token the `build.ninja` expansion), with `/showIncludes` as the ninja rule adds it and only `/Fo`, `/Fd`
  and the input changed, run with the build's environment (`vcvars64` plus LLVM 19.1.7 first on `PATH`). The author
  checked that each scratch compile includes exactly the original object's recorded dependencies apart from the
  redirected file.
- **`link.exe` run directly, the scratch objects ahead of `WebCore.lib`.** The link response file is the
  `bin\jfxwebkit.dll` stanza of `build.ninja`: the 135 WebKitLegacy objects, then the scratch objects, then
  `LINK_LIBRARIES`. The linker takes the replaced bundle from the command-line object, so the archive member of the
  same bundle is never pulled in (two definitions would fail with LNK2005; no `/FORCE`). `/MAP:` shows where each
  symbol came from: in every probe map no symbol comes from the archive member of a replaced bundle.
- **All outputs outside the build tree.** `/out`, `/implib` and the map go to the scratch directory; no `/DEBUG`, so
  no PDB. The author hashed `bin/jfxwebkit.dll`, `bin/jfxwebkit.pdb` and `lib/jfxwebkit.lib` before the first link and
  after the last one (identical), and the reviewer `bin/jfxwebkit.dll`, `lib/jfxwebkit.lib` and `lib/WebCore.lib`
  (identical).
- **Identity and variant tag.** Each fixed copy is `cmp`-identical to the tree's file, each baseline copy to
  `git show 6832730e09:<path>`, and each mutant differs from the fixed copy by its one hunk (the diffs below). Each
  bundle copy defines its variant name (`JFX_PROBE_VARIANT`, `RV_VARIANT`) and exports it, and the loader prints the
  tags of the DLL it loaded, so a run cannot silently use another variant.
- **Check order of the `LocaleNone` probes.** A mutant that returns a reference to a local leaves it dangling, and
  `~Vector` does not clear its size, so reading it first could pass by accident or crash. Both probes therefore check
  first that every returned reference lies inside the live locale object (`[object, object + sizeof(LocaleNone))`,
  through a pointer laundered by a `volatile` slot so that each call is a virtual call into the compiled variant),
  and read a list only after that; then size, then content, then formatting through the real
  `Locale::formatDateTime`. Each call order gets a fresh locale from `Locale::create`. The author's format checks use
  March and September, not May, whose short and full names are equal.
- **One process per probe group,** so a crash of one group cannot hide or disturb another's result.

## The tree the probes saw
The probes ran on the change as reviewed. The rework that followed the review changed only comments in
`platform/java/CursorJava.cpp`: it added the line now at `:63` and reworded the comment now at `:201` (it read
`// A custom cursor gets its platform cursor in the constructor; if that failed it shows as the pointer, as before.`).
So in the `CursorJava.cpp` diffs below, line numbers after 62 are one lower than in the tree now, and the context line
above `case PlatformCursorType::Custom:` is the old comment. The final library's `.text` section is byte-identical to
that of the library built from the reviewed files (the story's "Libraries" section).

Placeholders in the commands below: `<S>` is the scratch directory of the story's runs in Git Bash form
(`/c/Users/<user>/AppData/Local/Temp/claude/<session>/scratchpad/us025`), `<SW>` the same directory in Windows form,
with backslashes or slashes as the command has them. The build tree is
`C:\SourceCode\jfx-ffm\build\jfxwebkit-windows-release`.
The `C:\PROGRA~1\...` paths are the 8.3 names of the Visual Studio 2022 Community directories (`vcvars64.bat`, the
VS-bundled `ninja.exe`, MSVC 14.44.35207 `link.exe`).

In the diffs, a context line of an empty source line is printed empty; the diff files have one blank there.

## The author's probes
Two probe groups in one probe DLL per variant pair: `locale` (bundle 36) and `cursor` (bundle 28). The cursor probe
installs no host table (`wkj_init` is never called), so a custom cursor never gets a platform cursor in its
constructor: that is the path it checks. Before anything asks for a platform cursor it plants the sentinel `0x5EED`
as the pointer cursor's platform cursor, so the pointer fallback of the `Custom` arm can be told apart from a second
construction, which would also fail and give 0. Its image is a probe-local `Image` subclass with a constant 16x16
size, since a `BitmapImage` without a host has no decoder and a 0x0 size, for which `determineHotSpot` returns 0,0
whatever the input.

### Commands, in order (Git Bash)

Shell variables used throughout:

```bash
S=<S>
P=$S/probe
T=/c/SourceCode/jfx-ffm/modules/javafx.web/src/main/native/Source/WebCore
U=/c/SourceCode/jfx-ffm/build/jfxwebkit-windows-release/WebCore/DerivedSources/unified-sources
WP='<SW>\probe'
B='\'
```

Before any build step this must print nothing (the link loops below stop when it prints something):

```bash
tasklist //FO CSV | grep -i -E '"(ninja|clang-cl|clang|link|lld-link|java|javaw|cl|cmake|mvn|msbuild)\.exe"'
```

#### 1. Hashes of the build outputs before any link (all variants)

At the start (16:18), and again right before the first link (16:41, after step 9):

```bash
mkdir -p $P && cd /c/SourceCode/jfx-ffm/build/jfxwebkit-windows-release && sha256sum bin/jfxwebkit.dll bin/jfxwebkit.pdb lib/jfxwebkit.lib $S/dll-after/jfxwebkit.dll | tee $P/sha256-build-outputs-before.txt
cd /c/SourceCode/jfx-ffm/build/jfxwebkit-windows-release && (sha256sum bin/jfxwebkit.dll bin/jfxwebkit.pdb lib/jfxwebkit.lib; ls -la --time-style=full-iso bin/jfxwebkit.dll bin/jfxwebkit.pdb lib/jfxwebkit.lib) > $P/sha256-build-outputs-before-first-link.txt; touch $P/link/marker-before-links
```

#### 2. Original stanzas (read-only)

```bash
cd /c/SourceCode/jfx-ffm/build/jfxwebkit-windows-release && mkdir -p $P/orig && sed -n '5090,5095p' compile_commands.json > $P/orig/cc-28.json && sed -n '5138,5143p' compile_commands.json > $P/orig/cc-36.json && sed -n '22598,22606p' build.ninja > $P/orig/ninja-28.txt && sed -n '22670,22678p' build.ninja > $P/orig/ninja-36.txt && sed -n '59056,59075p' build.ninja > $P/orig/ninja-link.txt
# recorded deps of the two original objects; -n keeps ninja from opening .ninja_log/.ninja_deps for writing
cd $P && for n in 28 36; do MSYS2_ARG_CONV_EXCL='*' cmd /c "C:\PROGRA~1\MICROS~4\2022\COMMUN~1\Common7\IDE\COMMON~1\MICROS~1\CMake\Ninja\ninja.exe -C C:\SourceCode\jfx-ffm\build\jfxwebkit-windows-release -n -t deps Source\WebCore\CMakeFiles\WebCore.dir\C_\SourceCode\jfx-ffm\build\jfxwebkit-short\wb\WebCore\DerivedSources\unified-sources\UnifiedSource-3c72abbe-$n.cpp.obj" > orig/deps-$n.txt 2>&1; done
```

#### 3. Variant copies (all variants)

```bash
cd /c/SourceCode/jfx-ffm && for v in L-fixed L-M0 L-M1 L-M2 L-M3 C-fixed C-M0 C-M1 C-M2 C-M3 C-noupcall C-hotspot; do mkdir -p $P/bundles/$v/redirected; done; mkdir -p $P/src $P/obj $P/link $P/tmp $P/diffs $P/runs
for v in L-fixed L-M1 L-M2 L-M3; do cp $T/platform/text/LocaleNone.cpp $P/bundles/$v/redirected/LocaleNone.cpp; done
for v in C-fixed C-M1 C-M2 C-M3 C-noupcall C-hotspot; do cp $T/platform/java/CursorJava.cpp $P/bundles/$v/redirected/CursorJava.cpp; done
git show 6832730e09:modules/javafx.web/src/main/native/Source/WebCore/platform/text/LocaleNone.cpp > $P/bundles/L-M0/redirected/LocaleNone.cpp
git show 6832730e09:modules/javafx.web/src/main/native/Source/WebCore/platform/java/CursorJava.cpp > $P/bundles/C-M0/redirected/CursorJava.cpp
cd $P/bundles && \
perl -0pi -e 's/(\#else\n)(    m_monthLabels\.reserveCapacity\(WTF::monthFullName\.size\(\)\);)/$1    Vector<String> m_monthLabels;\n$2/ or die "L-M1"' L-M1/redirected/LocaleNone.cpp && \
perl -0pi -e 's/(\#else\n)(    m_shortMonthLabels\.reserveCapacity\(WTF::monthName\.size\(\)\);)/$1    Vector<String> m_shortMonthLabels;\n$2/ or die "L-M2"' L-M2/redirected/LocaleNone.cpp && \
perl -0pi -e 's/    m_shortMonthLabels\.reserveCapacity\(WTF::monthName\.size\(\)\);\n(    for \(const auto& literal : WTF::monthName\)\n)        m_shortMonthLabels\.append\(String\(literal\)\);/    m_monthLabels.reserveCapacity(WTF::monthName.size());\n$1        m_monthLabels.append(String(literal));/ or die "L-M3"' L-M3/redirected/LocaleNone.cpp && \
perl -0pi -e 's/    : m_type\(Type::Custom\)\n    , m_image\(image\)\n    , m_hotSpot\(image \? determineHotSpot\(image, hotspot\) : IntPoint\(\)\)\n    , m_platformCursor\(0\)\n/    : m_platformCursor(0)\n/ or die "C-M1"' C-M1/redirected/CursorJava.cpp && \
perl -0pi -e 's/    \/\/ A custom cursor gets its platform cursor in the constructor; if that failed it shows as the pointer, as before\.\n    case PlatformCursorType::Custom:\n    default:/    case PlatformCursorType::Custom:\n      setPlatformCursor(Cursor(m_image.get(), m_hotSpot));\n        break;\n    default:/ or die "C-M2"' C-M2/redirected/CursorJava.cpp && \
perl -0pi -e 's/    , m_hotSpot\(image \? determineHotSpot\(image, hotspot\) : IntPoint\(\)\)\n/    , m_hotSpot(hotspot)\n/ or die "C-M3"' C-M3/redirected/CursorJava.cpp && \
perl -0pi -e 's/(        return;\n    \}\n\n)(    m_platformCursor = cb->cursor_get_custom_id\()/$1    return;\n$2/ or die "noupcall"' C-noupcall/redirected/CursorJava.cpp && \
perl -0pi -e 's/        hotspot\.x\(\), hotspot\.y\(\)\);/        m_hotSpot.x(), m_hotSpot.y());/ or die "hotspot"' C-hotspot/redirected/CursorJava.cpp
# identity and hunks
cmp $T/platform/text/LocaleNone.cpp L-fixed/redirected/LocaleNone.cpp && cmp $T/platform/java/CursorJava.cpp C-fixed/redirected/CursorJava.cpp
for v in L-M0 L-M1 L-M2 L-M3; do diff -u --label L-fixed/LocaleNone.cpp --label $v/LocaleNone.cpp L-fixed/redirected/LocaleNone.cpp $v/redirected/LocaleNone.cpp > $P/diffs/$v.diff; done
for v in C-M0 C-M1 C-M2 C-M3 C-noupcall C-hotspot; do diff -u --label C-fixed/CursorJava.cpp --label $v/CursorJava.cpp C-fixed/redirected/CursorJava.cpp $v/redirected/CursorJava.cpp > $P/diffs/$v.diff; done
```

#### 4. Bundle copies (all variants)

```bash
cd $P && for v in L-fixed L-M0 L-M1 L-M2 L-M3; do perl -e 'my ($in,$v,$rep,$probe)=@ARGV; local $/; open my $f,"<",$in or die; my $s=<$f>; my $n = ($s =~ s{^#include "platform/text/LocaleNone\.cpp"$}{#include "redirected/LocaleNone.cpp"}mg); die "replaced $n" unless $n==1; open my $r,"<",$rep or die; my $rs=<$r>; open my $p,"<",$probe or die; my $ps=<$p>; print $s, "\n// Probe code appended to this copy of the bundle.\n#define JFX_PROBE_VARIANT \"$v\"\n\n", $rs, "\n", $ps;' $U/UnifiedSource-3c72abbe-36.cpp $v src/probe_report.inc src/locale_probe.cpp > bundles/$v/UnifiedSource-3c72abbe-36.cpp || echo FAIL $v; done
cd $P && for v in C-fixed C-M0 C-M1 C-M2 C-M3; do perl -e 'my ($in,$v,$rep,$probe)=@ARGV; local $/; open my $f,"<",$in or die; my $s=<$f>; my $n = ($s =~ s{^#include "platform/java/CursorJava\.cpp"$}{#include "redirected/CursorJava.cpp"}mg); die "replaced $n" unless $n==1; open my $r,"<",$rep or die; my $rs=<$r>; open my $p,"<",$probe or die; my $ps=<$p>; print $s, "\n// Probe code appended to this copy of the bundle.\n#define JFX_PROBE_VARIANT \"$v\"\n\n", $rs, "\n", $ps;' $U/UnifiedSource-3c72abbe-28.cpp $v src/probe_report.inc src/cursor_probe.cpp > bundles/$v/UnifiedSource-3c72abbe-28.cpp || echo FAIL $v; done
cd $P && for v in C-noupcall C-hotspot; do perl -e 'my ($in)=@ARGV; local $/; open my $f,"<",$in or die; my $s=<$f>; my $n = ($s =~ s{^#include "platform/java/CursorJava\.cpp"$}{#include "redirected/CursorJava.cpp"}mg); die "replaced $n" unless $n==1; print $s;' $U/UnifiedSource-3c72abbe-28.cpp > bundles/$v/UnifiedSource-3c72abbe-28.cpp || echo FAIL $v; done
```

The redirected include `redirected/<file>.cpp` exists in no `-I` directory, so a missing copy fails the compile
instead of falling back to the tree's file.

#### 5. Compile commands (all variants)

The response file is the bundle's `compile_commands.json` command (identical, token for token, to the
`build.ninja` expansion up to `/Fo`) with `/showIncludes` inserted as the ninja rule does, and only
`/Fo`, `/Fd` and the input changed (`obj/<V>/orig-tokens.txt` vs `rsp-tokens.txt` show the token diff).

```bash
WP='<SW>/probe'; cd $P && for v in L-fixed L-M0 L-M1 L-M2 L-M3 C-fixed C-M0 C-M1 C-M2 C-M3 C-noupcall C-hotspot; do case $v in L-*) n=36;; C-*) n=28;; esac; mkdir -p obj/$v; perl -e 'my ($cc,$wp,$v,$n,$out)=@ARGV; local $/; open my $f,"<",$cc or die; my $j=<$f>; my ($c)= $j =~ /"command": "(.*)",\s*\n/ or die; $c =~ s/\x5c(["\x5c])/$1/g; $wp =~ s{/}{\x5c}g; my $exe = "C:\x5cSourceCode\x5cjfx-ffm\x5cbuild\x5ctoolchain\x5cllvm-19.1.7\x5cbin\x5cclang-cl.exe  "; substr($c,0,length $exe) eq $exe or die "exe"; $c = substr($c, length $exe); my $obj = "$wp\x5cobj\x5c$v\x5cUnifiedSource-3c72abbe-$n.cpp.obj"; my $pdb = "$wp\x5cobj\x5c$v\x5cWebCore.pdb"; my $src = "$wp\x5cbundles\x5c$v\x5cUnifiedSource-3c72abbe-$n.cpp"; my $k = ($c =~ s{ /Fo\S+ /Fd\S+ -c -- \S+$}{ /showIncludes /Fo$obj /Fd$pdb -c -- $src}); die "tail $k" unless $k == 1; open my $o, ">", $out or die; binmode $o; print $o $c;' orig/cc-$n.json "$WP" $v $n obj/$v/compile.rsp || echo FAIL $v; done
```

#### 6. Compile (all variants; 4 at a time, like ninja -j4)

The environment is the build's: vcvars64 plus the LLVM 19.1.7 bin dir first on PATH; TMP/TEMP point into
this directory; the working directory is the build tree (as ninja runs it).

As run: first `L-fixed` alone (16:33, later superseded); then the 11 others with the batches
`"L-M0 L-M1 L-M2 L-M3" "C-fixed C-M0 C-M1 C-M2" "C-M3 C-noupcall C-hotspot"` (16:35-16:37); then, after
`[[maybe_unused]]` was added to `opaqueAddress` in `probe_report.inc` (it warned unused in the cursor bundle) and
step 4 was re-run for the ten probe bundles, those ten again with the batches
`"L-fixed L-M0 L-M1 L-M2" "L-M3 C-fixed C-M0 C-M1" "C-M2 C-M3"` (16:38-16:40). The command, with the batch list
of the last round:

```bash
B='\'; cd $P && for batch in "L-fixed L-M0 L-M1 L-M2" "L-M3 C-fixed C-M0 C-M1" "C-M2 C-M3"; do for v in $batch; do ( date +%T > obj/$v/compile.times; MSYS2_ARG_CONV_EXCL='*' cmd /v:on /c "set VSCMD_SKIP_SENDTELEMETRY=1&& call C:\PROGRA~1\MICROS~4\2022\COMMUN~1\VC\AUXILI~1\Build\vcvars64.bat 1>NUL: && set PATH=C:\SourceCode\jfx-ffm\build\toolchain\llvm-19.1.7\bin;!PATH!&& set TMP=<SW>\probe\tmp&& set TEMP=<SW>\probe\tmp&& cd /d C:\SourceCode\jfx-ffm\build\jfxwebkit-windows-release && C:\SourceCode\jfx-ffm\build\toolchain\llvm-19.1.7\bin\clang-cl.exe @<SW>\probe\obj${B}${v}${B}compile.rsp" > obj/$v/compile.log 2>&1; echo "exit=$?" >> obj/$v/compile.times; date +%T >> obj/$v/compile.times ) & done; wait; done
```

(The objects kept are from 16:38-16:40 for the ten probe variants and from 16:36-16:37 for C-noupcall and
C-hotspot, whose bundles hold no probe code. `sha256-artifacts.txt` has the hash of every copy, bundle, object
and DLL.)

#### 7. Include-set check (all variants)

Every scratch compile must include exactly the original object's recorded deps, apart from the redirected file
(ninja drops "program files" paths from its deps, so the same filter is applied):

```bash
cd $P && for v in L-fixed L-M0 L-M1 L-M2 L-M3 C-fixed C-M0 C-M1 C-M2 C-M3 C-noupcall C-hotspot; do case $v in L-*) n=36;; C-*) n=28;; esac; perl -e 'sub norm { my ($p)=@_; $p =~ tr{\x5c}{/}; $p = "C:/SourceCode/jfx-ffm/build/jfxwebkit-windows-release/$p" unless $p =~ m{^[A-Za-z]:/}; my @o; for (split m{/}, $p) { next if $_ eq "." || $_ eq ""; if ($_ eq "..") { pop @o } else { push @o, $_ } } return lc join("/", @o); } my %a; open my $d,"<",$ARGV[0] or die; <$d>; while (<$d>) { s/^\s+//; s/\s+$//; $a{norm($_)}=1 if length } my %b; open my $s,"<",$ARGV[1] or die; while (<$s>) { if (/^Note: including file:\s*(.*?)\s*$/) { my $p = norm($1); next if $p =~ /program files|microsoft visual studio/; $b{$p}=1 } } my @oa = grep { !$b{$_} } sort keys %a; my @ob = grep { !$a{$_} } sort keys %b; print "$ARGV[2]: original deps ", scalar(keys %a), ", scratch includes ", scalar(keys %b), "; only original: @oa; only scratch: @ob\n";' orig/deps-$n.txt obj/$v/compile.log $v; done > diffs/include-sets.txt
```

#### 8. Loader (once)

```bash
mkdir -p $P/loader && cd $P/loader && MSYS2_ARG_CONV_EXCL='*' cmd /v:on /c "set VSCMD_SKIP_SENDTELEMETRY=1&& call C:\PROGRA~1\MICROS~4\2022\COMMUN~1\VC\AUXILI~1\Build\vcvars64.bat 1>NUL: && set PATH=C:\SourceCode\jfx-ffm\build\toolchain\llvm-19.1.7\bin;!PATH!&& set TMP=<SW>\probe\tmp&& set TEMP=<SW>\probe\tmp&& cd /d <SW>\probe\loader && C:\SourceCode\jfx-ffm\build\toolchain\llvm-19.1.7\bin\clang-cl.exe /nologo /W4 /O2 /MD /Foprobe_loader.obj /Feprobe_loader.exe ..\src\probe_loader.c" > build.log 2>&1
```

#### 9. Link response files (P-fixed, P-M0..P-M3, FULL-noupcall, FULL-hotspot)

`$in_newline $LINK_PATH $LINK_LIBRARIES` of the `bin\jfxwebkit.dll` stanza: the 135 WebKitLegacy objects,
then the scratch objects, then the libraries, so the scratch objects come before `lib\WebCore.lib`.

```bash
WP='<SW>\probe'; cd $P && for d in P-fixed:L-fixed:C-fixed P-M0:L-M0:C-M0 P-M1:L-M1:C-M1 P-M2:L-M2:C-M2 P-M3:L-M3:C-M3 FULL-noupcall::C-noupcall FULL-hotspot::C-hotspot; do IFS=: read name lv cv <<< "$d"; mkdir -p link/$name; perl -e 'my ($nj,$wp,$lv,$cv,$out)=@ARGV; local $/; open my $f,"<",$nj or die; my $t=<$f>; my ($line) = $t =~ /^(build bin\x5cjfxwebkit\.dll lib\x5cjfxwebkit\.lib: .*)$/m or die "no build"; my ($rest) = $line =~ /: CXX_SHARED_LIBRARY_LINKER__WebKitLegacy_Release (.*)$/; my ($exp) = split / \| /, $rest, 2; my @objs = split / /, $exp; die "count" unless @objs == 135; my %v; while ($t =~ /^  (\w+) = (.*)$/mg) { $v{$1} = $2 } die "LINK_PATH set" if exists $v{LINK_PATH}; my @mine; push @mine, "$wp\x5cobj\x5c$lv\x5cUnifiedSource-3c72abbe-36.cpp.obj" if length $lv; push @mine, "$wp\x5cobj\x5c$cv\x5cUnifiedSource-3c72abbe-28.cpp.obj"; open my $o, ">", $out or die; binmode $o; print $o join("\n", @objs, @mine), "  ", $v{LINK_LIBRARIES};' orig/ninja-link.txt "$WP" "$lv" "$cv" link/$name/link.rsp || echo FAIL $name; done
```

#### 10. Links, one at a time, link.exe run directly

Flags: the rule's `/nologo @rsp /out: /implib: /dll /version:0.0` plus `LINK_FLAGS` without `/DEBUG` (so no
`/pdb:`); `/MANIFEST:EMBED` replaces cmake's `vs_link_dll` manifest step (the embedded manifest and version
resource come out byte-identical to `bin\jfxwebkit.dll`'s); `/MAP:` added for the symbol-origin check. No
`/FORCE`.

Probe DLLs (`P-fixed`, `P-M0`, `P-M1`, `P-M2`, `P-M3`):

```bash
B='\'; cd $P && for name in P-fixed P-M0 P-M1 P-M2 P-M3; do if tasklist //FO CSV | grep -i -E '"(ninja|clang-cl|clang|link|lld-link|java|javaw|cl|cmake|mvn|msbuild)\.exe"'; then echo "BUSY before $name at $(date +%T): stopping"; break; fi; date +%T > link/$name/link.times; MSYS2_ARG_CONV_EXCL='*' cmd /v:on /c "set VSCMD_SKIP_SENDTELEMETRY=1&& call C:\PROGRA~1\MICROS~4\2022\COMMUN~1\VC\AUXILI~1\Build\vcvars64.bat 1>NUL: && set PATH=C:\SourceCode\jfx-ffm\build\toolchain\llvm-19.1.7\bin;!PATH!&& set TMP=<SW>\probe\tmp&& set TEMP=<SW>\probe\tmp&& cd /d C:\SourceCode\jfx-ffm\build\jfxwebkit-windows-release && C:\PROGRA~1\MICROS~4\2022\COMMUN~1\VC\Tools\MSVC\1444~1.352\bin\Hostx64\x64\link.exe /nologo @<SW>\probe\link${B}${name}${B}link.rsp /out:<SW>\probe\link${B}${name}${B}jfxprobe.dll /implib:<SW>\probe\link${B}${name}${B}jfxprobe.lib /dll /version:0.0 -shared /INCREMENTAL:NO C:/SourceCode/jfx-ffm/build/jfxwebkit-windows-release/WebCore/obj/version.res /OPT:NOICF /OPT:REF /INCREMENTAL:NO /MANIFEST:EMBED /MAP:<SW>\probe\link${B}${name}${B}jfxprobe.map" > link/$name/link.log 2>&1; echo "exit=$?" >> link/$name/link.times; date +%T >> link/$name/link.times; done
```

(As run: `P-fixed` once at 16:42:56 with the same command minus `/MAP:` (superseded); then this loop with
`P-fixed P-M0` (16:44), which relinked `P-fixed` with its map; then, after a foreign Maven run had finished,
`P-M1 P-M2 P-M3` (16:46). The recorded runs are against the DLLs on disk.)

Full mutant DLLs (`FULL-noupcall` -> `../dll-mut-noupcall/jfxwebkit.dll`, `FULL-hotspot` ->
`../dll-mut-hotspot/jfxwebkit.dll`):

```bash
B='\'; mkdir -p $S/dll-mut-noupcall $S/dll-mut-hotspot; cd $P && for m in noupcall hotspot; do name=FULL-$m; if tasklist //FO CSV | grep -i -E '"(ninja|clang-cl|clang|link|lld-link|java|javaw|cl|cmake|mvn|msbuild)\.exe"'; then echo "BUSY before $name at $(date +%T): stopping"; break; fi; date +%T > link/$name/link.times; MSYS2_ARG_CONV_EXCL='*' cmd /v:on /c "set VSCMD_SKIP_SENDTELEMETRY=1&& call C:\PROGRA~1\MICROS~4\2022\COMMUN~1\VC\AUXILI~1\Build\vcvars64.bat 1>NUL: && set PATH=C:\SourceCode\jfx-ffm\build\toolchain\llvm-19.1.7\bin;!PATH!&& set TMP=<SW>\probe\tmp&& set TEMP=<SW>\probe\tmp&& cd /d C:\SourceCode\jfx-ffm\build\jfxwebkit-windows-release && C:\PROGRA~1\MICROS~4\2022\COMMUN~1\VC\Tools\MSVC\1444~1.352\bin\Hostx64\x64\link.exe /nologo @<SW>\probe\link${B}${name}${B}link.rsp /out:<SW>\dll-mut-${m}${B}jfxwebkit.dll /implib:<SW>\dll-mut-${m}${B}jfxwebkit.lib /dll /version:0.0 -shared /INCREMENTAL:NO C:/SourceCode/jfx-ffm/build/jfxwebkit-windows-release/WebCore/obj/version.res /OPT:NOICF /OPT:REF /INCREMENTAL:NO /MANIFEST:EMBED /MAP:<SW>\probe\link${B}${name}${B}jfxwebkit.map" > link/$name/link.log 2>&1; echo "exit=$?" >> link/$name/link.times; date +%T >> link/$name/link.times; done
```

#### 11. Build outputs after the last link

```bash
cd /c/SourceCode/jfx-ffm/build/jfxwebkit-windows-release && (sha256sum bin/jfxwebkit.dll bin/jfxwebkit.pdb lib/jfxwebkit.lib $S/dll-after/jfxwebkit.dll; ls -la --time-style=full-iso bin/jfxwebkit.dll bin/jfxwebkit.pdb lib/jfxwebkit.lib) > $P/sha256-build-outputs-after-last-link.txt; cmp bin/jfxwebkit.dll $S/dll-after/jfxwebkit.dll
find . -maxdepth 1 -newer $P/link/marker-before-links; find bin lib CMakeFiles Source/WebKitLegacy/CMakeFiles/WebKitLegacy.dir Source/WebCore/CMakeFiles/WebCore.dir -maxdepth 1 -newer $P/link/marker-before-links
```

#### 12. Runs (each probe DLL x {locale, cursor})

```bash
B='\'; cd $P && for name in P-fixed P-M0 P-M1 P-M2 P-M3; do for g in locale cursor; do ./loader/probe_loader.exe "<SW>\probe\link${B}${name}${B}jfxprobe.dll" $g > runs/$name-$g.out 2> runs/$name-$g.err; echo "$name $g exit=$?" | tee runs/$name-$g.exit; done; done
# the two full DLLs load (the loader then reports the probe exports missing, exit 2)
for m in noupcall hotspot; do ./loader/probe_loader.exe "<SW>\dll-mut-$m\jfxwebkit.dll" cursor > runs/FULL-$m-loadcheck.out 2>&1; done
```

#### 13. Exports, maps, resources

```bash
cd $P && MSYS2_ARG_CONV_EXCL='*' cmd /v:on /c "set VSCMD_SKIP_SENDTELEMETRY=1&& call C:\PROGRA~1\MICROS~4\2022\COMMUN~1\VC\AUXILI~1\Build\vcvars64.bat 1>NUL: && dumpbin /EXPORTS <SW>\probe\link\P-fixed\jfxprobe.dll" > link/P-fixed/dumpbin-exports.txt 2>link/P-fixed/dumpbin-exports.stderr
cd $P && for m in noupcall hotspot; do MSYS2_ARG_CONV_EXCL='*' cmd /v:on /c "set VSCMD_SKIP_SENDTELEMETRY=1&& call C:\PROGRA~1\MICROS~4\2022\COMMUN~1\VC\AUXILI~1\Build\vcvars64.bat 1>NUL: && dumpbin /EXPORTS <SW>\dll-mut-$m\jfxwebkit.dll" > link/FULL-$m/dumpbin-exports.txt 2> link/FULL-$m/dumpbin-exports.stderr; perl -ne 'print if /^\s+\d+\s+[0-9A-F]+\s+[0-9A-F]+\s+wkj_/' link/FULL-$m/dumpbin-exports.txt | wc -l; perl -ne 'print "$1\n" if /^\s+\d+\s+[0-9A-F]+\s+[0-9A-F]+\s+(\S+)/' link/FULL-$m/dumpbin-exports.txt > link/FULL-$m/names.txt; diff $S/impl/names-after.txt link/FULL-$m/names.txt > link/FULL-$m/names-vs-real.diff; done
cd $P && for n in P-fixed P-M0 P-M1 P-M2 P-M3; do grep -c 'WebCore:UnifiedSource-3c72abbe-28.cpp.obj' link/$n/jfxprobe.map; grep -c 'WebCore:UnifiedSource-3c72abbe-36.cpp.obj' link/$n/jfxprobe.map; done
R=/c/SourceCode/jfx-ffm/build/toolchain/llvm-19.1.7/bin/llvm-readobj; $R --coff-resources /c/SourceCode/jfx-ffm/build/jfxwebkit-windows-release/bin/jfxwebkit.dll | grep -v -E 'File:|DataRVA|Base Table Address' > $P/link/resources-real.txt; for m in noupcall hotspot; do $R --coff-resources $S/dll-mut-$m/jfxwebkit.dll | grep -v -E 'File:|DataRVA|Base Table Address' > $P/link/FULL-$m/resources.txt; diff $P/link/resources-real.txt $P/link/FULL-$m/resources.txt; done
```

#### 14. Optional: `-Werror=return-stack-address` (Locale variants, syntax only, writes no object)

```bash
B='\'; cd $P && mkdir -p werror && for v in L-fixed L-M0 L-M1 L-M2 L-M3; do MSYS2_ARG_CONV_EXCL='*' cmd /v:on /c "set VSCMD_SKIP_SENDTELEMETRY=1&& call C:\PROGRA~1\MICROS~4\2022\COMMUN~1\VC\AUXILI~1\Build\vcvars64.bat 1>NUL: && set PATH=C:\SourceCode\jfx-ffm\build\toolchain\llvm-19.1.7\bin;!PATH!&& set TMP=<SW>\probe\tmp&& set TEMP=<SW>\probe\tmp&& cd /d C:\SourceCode\jfx-ffm\build\jfxwebkit-windows-release && C:\SourceCode\jfx-ffm\build\toolchain\llvm-19.1.7\bin\clang-cl.exe -Werror=return-stack-address /Zs @<SW>\probe\obj${B}${v}${B}compile.rsp" > werror/$v.log 2>&1; echo "$v: exit=$?"; done
```

#### 15. results.tsv

```bash
cd $P && perl -e 'print join("\t", qw(variant probe_dll check expected actual result)), "\n"; my @verdicts; for my $dll (qw(P-fixed P-M0 P-M1 P-M2 P-M3)) { for my $g (qw(locale cursor)) { my $f = "runs/$dll-$g.out"; open my $h, "<:crlf", $f or die $f; my ($variant, $failed, @orders); while (<$h>) { chomp; my @c = split /\t/; if ($c[0] eq "INFO" && $c[1] eq "variant") { $variant = $c[2] } if ($c[0] eq "INFO" && $c[1] eq "order") { push @orders, $c[2] } if ($c[0] eq "CHECK") { print join("\t", $variant, $dll, @c[1..4]), "\n" } if ($c[0] eq "RESULT") { ($failed) = $c[2] =~ /^(-?\d+) failed/ } } my $fixed = $variant =~ /fixed/; my $exit = do { open my $e, "<", "runs/$dll-$g.exit"; my $l = <$e>; $l =~ /exit=(\d+)/; $1 }; my $actual = "$failed failed, loader exit $exit" . (@orders ? "; " . join("; ", @orders) : ""); my $ok = $fixed ? ($failed == 0 && $exit == 0) : ($failed > 0 && $exit == 1); push @verdicts, join("\t", $variant, $dll, "verdict", $fixed ? "every check passes, exit 0" : "killed: at least one check fails, exit 1", $actual, $ok ? "PASS" : "FAIL"); } } print "$_\n" for @verdicts;' > results.tsv
```

### Notes on the probe design (from the author's record)

- Locale: the locale comes from the real `Locale::create`; its pointer goes through a volatile slot, so every
  `monthLabels()` / `shortMonthLabels()` call is a vtable call into the compiled variant code, and each returned
  address goes through a volatile slot too, so the compiler has no dereferenceable promise to hoist a load
  above the in-object test. A vector is read only once its address lies in `[object, object + sizeof(LocaleNone))`.
  Each call order uses its own fresh locale; an order stops at its first failed step; the other order still runs
  on its own fresh object, gated the same way. Step 4 uses a `LocaleNone` subclass whose `monthFormat()` is
  `MMMM|LLLL` and `shortMonthFormat()` `MMM|LLL`, through the real `Locale::formatDateTime` with
  `DateComponents::fromParsingMonth("2026-03"/"2026-09")` (March, September; not May).
- Cursor: `wkj_init` is never called, so `wkjTheme()` is null. The image is a probe-local `Image` subclass with a
  constant 16x16 `size()` and the other three pure virtuals trivial: it needs no decoder, no data and no host,
  so `isNull()` is false and `determineHotSpot` sees a real 16x16 rect. `Image::nullImage()` (a `BitmapImage`
  with no data) is 0x0. No WTF, JSC or main-thread initialization was needed: nothing here touches an
  `AtomString` table, a thread or a run loop, and the fixed variant passes every check without it.
  `pointerCursor()` gets the sentinel `0x5EED` before any `platformCursor()` call; `platformCursor()` itself is
  `Cursor.cpp` code from `WebCore.lib` (bundle 1), which calls the variant's `ensurePlatformCursor`.

### Sources

`src/probe_report.inc`, appended to both probe bundles:

```cpp
// Text report shared by the two probes. Each probe writes one line per check into a buffer
// that the loader passes in (and the same line to stderr as it goes):
//   CHECK <tab> check <tab> expected <tab> actual <tab> PASS|FAIL
//   INFO  <tab> what  <tab> value
// The probe export returns the number of failed checks, or -1 if the buffer was too small.
// Both probes append this to their bundle, so it has internal linkage.

#include <cstdint>
#include <cstdio>
#include <cstring>

namespace JFXProbe {
namespace {

class Report {
public:
    Report(char* out, int32_t capacity)
        : m_out(out)
        , m_capacity(capacity > 0 ? static_cast<size_t>(capacity) : 0)
    {
        if (m_capacity)
            m_out[0] = '\0';
    }

    // Each line also goes to stderr at once, so a crash shows how far the probe got.
    void text(const char* s)
    {
        fputs(s, stderr);
        fflush(stderr);
        size_t n = strlen(s);
        if (m_length + n + 1 > m_capacity) {
            m_overflow = true;
            return;
        }
        memcpy(m_out + m_length, s, n + 1);
        m_length += n;
    }

    bool check(const char* name, const char* expected, const char* actual, bool pass)
    {
        char line[1024];
        snprintf(line, sizeof(line), "CHECK\t%s\t%s\t%s\t%s\n", name, expected, actual, pass ? "PASS" : "FAIL");
        text(line);
        if (!pass)
            ++m_failures;
        return pass;
    }

    void info(const char* what, const char* value)
    {
        char line[1024];
        snprintf(line, sizeof(line), "INFO\t%s\t%s\n", what, value);
        text(line);
    }

    int32_t result() const { return m_overflow ? -1 : m_failures; }

private:
    char* m_out;
    size_t m_capacity;
    size_t m_length { 0 };
    int32_t m_failures { 0 };
    bool m_overflow { false };
};

// The compiler must not see through these: what goes in is only known at run time, so a value
// read back carries no knowledge of the object's dynamic type and no "dereferenceable" promise.
template<typename T> T* opaque(T* pointer)
{
    static T* volatile slot;
    slot = pointer;
    return slot;
}

[[maybe_unused]] uintptr_t opaqueAddress(uintptr_t address)
{
    static volatile uintptr_t slot;
    slot = address;
    return slot;
}

} // namespace
} // namespace JFXProbe
```

`src/locale_probe.cpp`, appended after it to the bundle copy that includes `LocaleNone.cpp`:

```cpp
// Native probe for LocaleNone::monthLabels() and LocaleNone::shortMonthLabels().
//
// Appended, after probe_report.inc, to a copy of the unified bundle that includes
// platform/text/LocaleNone.cpp: the LocaleNone class is only visible inside that translation
// unit. The bundle copy defines JFX_PROBE_VARIANT before this code.
//
// For each call order (full names first, short names first) a fresh locale from Locale::create
// is asked twice for each list, through a Locale& whose dynamic type the compiler cannot know,
// so each call goes through the vtable to the compiled LocaleNone code. The steps run in this
// order and an order stops at its first failed step, so a reference is read only after its
// address was found inside the live locale object:
//   1 address: every returned reference lies inside the locale object, the same on both calls;
//   2 size:    12 entries right after each call and after all four calls;
//   3 content: equal, entry by entry, to WTF::monthFullName / WTF::monthName;
//   4 format:  Locale::formatDateTime of two months with MMMM|LLLL and MMM|LLL patterns.

#ifndef JFX_PROBE_VARIANT
#error "JFX_PROBE_VARIANT must name the variant of LocaleNone.cpp this bundle copy includes"
#endif

namespace JFXProbe {
namespace {

using WebCore::DateComponents;
using WebCore::Locale;
using WebCore::LocaleNone;
using Labels = WTF::Vector<WTF::String>;

constexpr size_t monthCount = 12;

enum class Which { Full, Short };

const char* nameOf(Which which)
{
    return which == Which::Full ? "monthLabels" : "shortMonthLabels";
}

const std::array<WTF::ASCIILiteral, 12>& expectedOf(Which which)
{
    return which == Which::Full ? WTF::monthFullName : WTF::monthName;
}

// LocaleNone with month patterns that use the labels; everything else is LocaleNone's.
class FormatLocale final : public LocaleNone {
private:
    WTF::String monthFormat() final { return "MMMM|LLLL"_s; }
    WTF::String shortMonthFormat() final { return "MMM|LLL"_s; }
};

uintptr_t labelsAddress(Locale& locale, Which which)
{
    const Labels& labels = which == Which::Full ? locale.monthLabels() : locale.shortMonthLabels();
    return opaqueAddress(reinterpret_cast<uintptr_t>(&labels));
}

const Labels& labelsAt(uintptr_t address)
{
    return *reinterpret_cast<const Labels*>(address);
}

bool insideObject(uintptr_t address, uintptr_t base)
{
    return address >= base && address - base + sizeof(Labels) <= sizeof(LocaleNone);
}

struct Call {
    Which which;
    int number;
    uintptr_t address;
    bool inside;
    size_t sizeAfterCall;
};

// Returns the first failed step of this order, or 0 when all four steps passed.
int runOrder(Report& report, const char* order, Which first)
{
    Which second = first == Which::Full ? Which::Short : Which::Full;
    std::unique_ptr<Locale> owner = Locale::create(WTF::AtomString());
    Locale& locale = *opaque(owner.get());
    uintptr_t base = reinterpret_cast<uintptr_t>(static_cast<LocaleNone*>(owner.get()));

    char name[256];
    char expected[512];
    char actual[512];

    const Which sequence[4] = { first, second, first, second };
    Call calls[4];
    for (int i = 0; i < 4; ++i) {
        uintptr_t address = labelsAddress(locale, sequence[i]);
        bool inside = insideObject(address, base);
        calls[i] = { sequence[i], i / 2 + 1, address, inside, inside ? labelsAt(address).size() : 0 };
    }

    // Step 1: address.
    bool passed = true;
    snprintf(expected, sizeof(expected), "inside the object [+0, +%zu)", sizeof(LocaleNone));
    for (const Call& call : calls) {
        snprintf(name, sizeof(name), "%s/1-address/%s#%d", order, nameOf(call.which), call.number);
        if (call.inside)
            snprintf(actual, sizeof(actual), "+%llu", static_cast<unsigned long long>(call.address - base));
        else {
            snprintf(actual, sizeof(actual), "outside: address-object=%lld",
                static_cast<long long>(call.address) - static_cast<long long>(base));
        }
        passed &= report.check(name, expected, actual, call.inside);
    }
    for (Which which : { Which::Full, Which::Short }) {
        const Call& one = calls[which == first ? 0 : 1];
        const Call& two = calls[which == first ? 2 : 3];
        snprintf(name, sizeof(name), "%s/1-address/%s#2==#1", order, nameOf(which));
        snprintf(actual, sizeof(actual), "%s (#2-#1=%lld)", one.address == two.address ? "same" : "differs",
            static_cast<long long>(two.address) - static_cast<long long>(one.address));
        passed &= report.check(name, "same address", actual, one.address == two.address);
    }
    if (!passed)
        return 1;

    // Step 2: size, right after each call and once more after all four calls.
    for (const Call& call : calls) {
        snprintf(name, sizeof(name), "%s/2-size/%s#%d", order, nameOf(call.which), call.number);
        snprintf(actual, sizeof(actual), "%zu", call.sizeAfterCall);
        passed &= report.check(name, "12", actual, call.sizeAfterCall == monthCount);
    }
    for (Which which : { Which::Full, Which::Short }) {
        size_t size = labelsAt(calls[which == first ? 2 : 3].address).size();
        snprintf(name, sizeof(name), "%s/2-size/%s-after-all-calls", order, nameOf(which));
        snprintf(actual, sizeof(actual), "%zu", size);
        passed &= report.check(name, "12", actual, size == monthCount);
    }
    if (!passed)
        return 2;

    // Step 3: content.
    for (Which which : { Which::Full, Which::Short }) {
        const Labels& labels = labelsAt(calls[which == first ? 2 : 3].address);
        const auto& names = expectedOf(which);
        bool equal = true;
        expected[0] = '\0';
        actual[0] = '\0';
        for (size_t i = 0; i < monthCount; ++i) {
            equal &= labels[i] == names[i];
            const char* separator = i ? "," : "";
            strncat(expected, separator, sizeof(expected) - strlen(expected) - 1);
            strncat(expected, names[i].characters(), sizeof(expected) - strlen(expected) - 1);
            strncat(actual, separator, sizeof(actual) - strlen(actual) - 1);
            strncat(actual, labels[i].utf8().data(), sizeof(actual) - strlen(actual) - 1);
        }
        snprintf(name, sizeof(name), "%s/3-content/%s", order, nameOf(which));
        passed &= report.check(name, expected, actual, equal);
    }
    if (!passed)
        return 3;

    // Step 4: format through Locale::formatDateTime on a fresh locale, in this order's sequence,
    // then the first format once more.
    std::unique_ptr<FormatLocale> formatOwner = makeUnique<FormatLocale>();
    Locale& formatter = *opaque<Locale>(formatOwner.get());
    struct Format {
        const char* month;
        Which which;
        const char* expected;
    };
    const char* fullMarch = "March|March";
    const char* shortMarch = "Mar|Mar";
    const char* fullSeptember = "September|September";
    const char* shortSeptember = "Sep|Sep";
    const Format formats[5] = {
        { "2026-03", first, first == Which::Full ? fullMarch : shortMarch },
        { "2026-03", second, second == Which::Full ? fullMarch : shortMarch },
        { "2026-09", first, first == Which::Full ? fullSeptember : shortSeptember },
        { "2026-09", second, second == Which::Full ? fullSeptember : shortSeptember },
        { "2026-03", first, first == Which::Full ? fullMarch : shortMarch },
    };
    int number = 0;
    for (const Format& format : formats) {
        ++number;
        snprintf(name, sizeof(name), "%s/4-format/#%d %s %s", order, number, format.month,
            format.which == Which::Full ? "monthFormat MMMM|LLLL" : "shortMonthFormat MMM|LLL");
        auto date = DateComponents::fromParsingMonth(WTF::StringView::fromLatin1(format.month));
        if (!date) {
            passed &= report.check(name, format.expected, "month did not parse", false);
            continue;
        }
        WTF::String text = formatter.formatDateTime(*date,
            format.which == Which::Full ? Locale::FormatTypeUnspecified : Locale::FormatTypeShort);
        WTF::CString utf8 = text.utf8();
        passed &= report.check(name, format.expected, utf8.data(), !strcmp(utf8.data(), format.expected));
    }
    if (!passed)
        return 4;
    return 0;
}

} // namespace
} // namespace JFXProbe

extern "C" __declspec(dllexport) const char* jfx_probe_locale_variant()
{
    return JFX_PROBE_VARIANT;
}

extern "C" __declspec(dllexport) int32_t jfx_probe_locale(char* out, int32_t capacity)
{
    using namespace JFXProbe;
    Report report(out, capacity);
    char value[128];
    report.info("variant", JFX_PROBE_VARIANT);
    snprintf(value, sizeof(value), "%zu", sizeof(WebCore::LocaleNone));
    report.info("sizeof(LocaleNone)", value);
    struct Order {
        const char* name;
        Which first;
    };
    for (const Order& order : { Order { "full-then-short", Which::Full }, Order { "short-then-full", Which::Short } }) {
        int failedStep = runOrder(report, order.name, order.first);
        snprintf(value, sizeof(value), "%s: %s%d", order.name, failedStep ? "stopped at failed step " : "passed steps 1-",
            failedStep ? failedStep : 4);
        report.info("order", value);
    }
    return report.result();
}
```

`src/cursor_probe.cpp`, appended after it to the bundle copy that includes `CursorJava.cpp`:

```cpp
// Native probe for the Java Cursor(Image*, const IntPoint&) constructor and the Custom arm of
// Cursor::ensurePlatformCursor().
//
// Appended, after probe_report.inc, to a copy of the unified bundle that includes
// platform/java/CursorJava.cpp. The bundle copy defines JFX_PROBE_VARIANT before this code.
//
// wkj_init is never called, so no host table is installed: cursor_get_custom_id is unavailable,
// every predefined platform cursor is 0, and a custom cursor never gets a platform cursor in its
// constructor. That is the path checked here. Before anything asks for a platform cursor the
// pointer cursor is given a sentinel, so the pointer fallback of a failed custom cursor can be
// told apart from a second custom-cursor attempt, which also fails and gives 0.

namespace JFXProbe {
namespace {

using WebCore::Cursor;
using WebCore::Image;
using WebCore::IntPoint;

constexpr WebCore::PlatformCursor sentinel = 0x5EED;

// A 16x16 image that needs no decoder and no host: its size is a constant, so isNull() is
// false and rect() is 0,0 16x16. Only the four pure virtuals of Image are implemented.
class SizedImage final : public Image {
public:
    static Ref<SizedImage> create() { return adoptRef(*new SizedImage); }

private:
    bool currentFrameKnownToBeOpaque() const final { return false; }
    bool currentFrameIsComplete() const final { return true; }
    WebCore::FloatSize size(WebCore::ImageOrientation = WebCore::ImageOrientation::Orientation::FromImage) const final
    {
        return { 16, 16 };
    }
    WebCore::ImageDrawResult draw(WebCore::GraphicsContext&, const WebCore::FloatRect&, const WebCore::FloatRect&,
        WebCore::ImagePaintingOptions = { }) final
    {
        return WebCore::ImageDrawResult::DidNothing;
    }
};

const char* typeName(Cursor::Type type)
{
    if (type == Cursor::Type::Custom)
        return "Custom";
    if (type == Cursor::Type::Invalid)
        return "Invalid";
    return "other";
}

void checkCursor(Report& report, const char* label, const Cursor& cursor, Image* image, const char* imageName,
    IntPoint hotSpot)
{
    char name[256];
    char expected[128];
    char actual[128];

    Cursor::Type type = cursor.type();
    snprintf(name, sizeof(name), "%s/type", label);
    snprintf(expected, sizeof(expected), "Custom (%d)", static_cast<int>(Cursor::Type::Custom));
    snprintf(actual, sizeof(actual), "%s (%d)", typeName(type), static_cast<int>(type));
    report.check(name, expected, actual, type == Cursor::Type::Custom);

    RefPtr<Image> held = cursor.image();
    snprintf(name, sizeof(name), "%s/image", label);
    const char* heldName = held.get() == image ? imageName : (held ? "another image" : "null");
    report.check(name, imageName, heldName, held.get() == image);

    IntPoint point = cursor.hotSpot();
    snprintf(name, sizeof(name), "%s/hotSpot", label);
    snprintf(expected, sizeof(expected), "%d,%d", hotSpot.x(), hotSpot.y());
    snprintf(actual, sizeof(actual), "%d,%d", point.x(), point.y());
    report.check(name, expected, actual, point == hotSpot);
}

void checkPlatformCursor(Report& report, const char* label, const Cursor& cursor)
{
    char name[256];
    char expected[64];
    char actual[64];
    WebCore::PlatformCursor platformCursor = cursor.platformCursor();
    snprintf(name, sizeof(name), "%s/platformCursor", label);
    snprintf(expected, sizeof(expected), "0x%llX (pointer)", static_cast<unsigned long long>(sentinel));
    snprintf(actual, sizeof(actual), "0x%llX", static_cast<unsigned long long>(platformCursor));
    report.check(name, expected, actual, platformCursor == sentinel);
}

} // namespace
} // namespace JFXProbe

extern "C" __declspec(dllexport) const char* jfx_probe_cursor_variant()
{
    return JFX_PROBE_VARIANT;
}

extern "C" __declspec(dllexport) int32_t jfx_probe_cursor(char* out, int32_t capacity)
{
    using namespace JFXProbe;
    Report report(out, capacity);
    char value[64];
    report.info("variant", JFX_PROBE_VARIANT);
    report.info("host theme table", WebCore::wkjTheme() ? "installed" : "none (wkj_init not called)");

    WebCore::pointerCursor().setPlatformCursor(Cursor(WebCore::PlatformCursor { sentinel }));
    snprintf(value, sizeof(value), "0x%llX",
        static_cast<unsigned long long>(WebCore::pointerCursor().platformCursor()));
    report.check("sentinel/pointerCursor().platformCursor", "0x5EED", value,
        WebCore::pointerCursor().platformCursor() == sentinel);

    Ref<SizedImage> sized = SizedImage::create();
    Image* image = opaque<Image>(sized.ptr());
    Image* nullImage = opaque(&Image::nullImage());
    snprintf(value, sizeof(value), "%gx%g isNull=%d", image->size().width(), image->size().height(),
        image->isNull() ? 1 : 0);
    report.info("16x16 image", value);
    snprintf(value, sizeof(value), "%gx%g isNull=%d", nullImage->size().width(), nullImage->size().height(),
        nullImage->isNull() ? 1 : 0);
    report.info("Image::nullImage()", value);

    Cursor inRect(image, IntPoint(3, 5));
    Cursor outOfRect(image, IntPoint(40, 40));
    Cursor noImage(nullptr, IntPoint(3, 5));
    Cursor emptyImage(nullImage, IntPoint(3, 5));

    checkCursor(report, "image16x16-hotspot3,5", inRect, image, "the 16x16 image", IntPoint(3, 5));
    checkCursor(report, "image16x16-hotspot40,40", outOfRect, image, "the 16x16 image", IntPoint());
    checkCursor(report, "nullptr-hotspot3,5", noImage, nullptr, "null", IntPoint());
    checkCursor(report, "Image::nullImage()-hotspot3,5", emptyImage, nullImage, "Image::nullImage()", IntPoint());

    checkPlatformCursor(report, "image16x16-hotspot3,5", inRect);
    checkPlatformCursor(report, "image16x16-hotspot40,40", outOfRect);
    checkPlatformCursor(report, "nullptr-hotspot3,5", noImage);
    checkPlatformCursor(report, "Image::nullImage()-hotspot3,5", emptyImage);
    return report.result();
}
```

`src/probe_loader.c`, the loader (`probe_loader <probe dll> locale|cursor`):

```c
/*
 * Loads a probe build of jfxwebkit and runs one of its probes.
 *
 *   probe_loader <probe dll> locale|cursor
 *
 * Prints the variant tags compiled into the DLL, then the probe's report, then a RESULT line.
 * Exit code: 0 only if the probe ran and every check passed; 1 if a check failed; 2 if the DLL
 * or a probe export could not be found; 3 if the report did not fit the buffer. A crash ends
 * the process with the exception code, which is never 0.
 */

#include <windows.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

typedef const char* (*variant_fn)(void);
typedef int32_t (*probe_fn)(char* out, int32_t capacity);

static char report[1 << 16];

static const char* variant_of(HMODULE dll, const char* export_name)
{
    variant_fn fn = (variant_fn)(void*)GetProcAddress(dll, export_name);
    return fn ? fn() : "(export missing)";
}

int main(int argc, char** argv)
{
    HMODULE dll;
    probe_fn probe;
    int32_t failed;

    if (argc != 3 || (strcmp(argv[2], "locale") && strcmp(argv[2], "cursor"))) {
        fprintf(stderr, "usage: probe_loader <probe dll> locale|cursor\n");
        return 2;
    }
    setvbuf(stdout, NULL, _IONBF, 0);

    dll = LoadLibraryExA(argv[1], NULL, LOAD_WITH_ALTERED_SEARCH_PATH);
    if (!dll) {
        printf("LOAD\tfailed\t%lu\n", (unsigned long)GetLastError());
        return 2;
    }
    printf("TAG\tlocale bundle\t%s\n", variant_of(dll, "jfx_probe_locale_variant"));
    printf("TAG\tcursor bundle\t%s\n", variant_of(dll, "jfx_probe_cursor_variant"));

    probe = (probe_fn)(void*)GetProcAddress(dll, strcmp(argv[2], "locale") ? "jfx_probe_cursor" : "jfx_probe_locale");
    if (!probe) {
        printf("RESULT\t%s\tprobe export missing\n", argv[2]);
        return 2;
    }
    failed = probe(report, (int32_t)sizeof(report));
    fputs(report, stdout);
    if (failed < 0) {
        printf("RESULT\t%s\treport did not fit\n", argv[2]);
        return 3;
    }
    printf("RESULT\t%s\t%d failed\n", argv[2], (int)failed);
    return failed ? 1 : 0;
}
```

### Mutants
Each diff is against the fixed copy (`L-fixed`, `C-fixed`). `L-M0` and `C-M0` are the `6832730e09` files. Probe DLLs
pair them: `P-fixed` = `L-fixed` + `C-fixed`, `P-M<n>` = `L-M<n>` + `C-M<n>`. `C-noupcall` and `C-hotspot` carry no
probe code: they are linked as full libraries, `dll-mut-noupcall/jfxwebkit.dll` and `dll-mut-hotspot/jfxwebkit.dll`,
for the runs of `CustomCursorTest` (the story's "The test").

`L-M1`: the local in `monthLabels()` restored.

```diff
--- L-fixed/LocaleNone.cpp
+++ L-M1/LocaleNone.cpp
@@ -72,6 +72,7 @@
     m_monthLabels = { WTF::monthFullName, std::size(WTF::monthFullName) };
         return m_monthLabels;
 #else
+    Vector<String> m_monthLabels;
     m_monthLabels.reserveCapacity(WTF::monthFullName.size());
     for (const auto& literal : WTF::monthFullName)
         m_monthLabels.append(String(literal));
```

`L-M2`: the local in `shortMonthLabels()` restored.

```diff
--- L-fixed/LocaleNone.cpp
+++ L-M2/LocaleNone.cpp
@@ -122,6 +122,7 @@
     m_shortMonthLabels = { WTF::monthName, std::size(WTF::monthName) };
     return m_shortMonthLabels;
 #else
+    Vector<String> m_shortMonthLabels;
     m_shortMonthLabels.reserveCapacity(WTF::monthName.size());
     for (const auto& literal : WTF::monthName)
         m_shortMonthLabels.append(String(literal));
```

`L-M3`: the two `m_shortMonthLabels` uses in `shortMonthLabels()` back to `m_monthLabels`.

```diff
--- L-fixed/LocaleNone.cpp
+++ L-M3/LocaleNone.cpp
@@ -122,9 +122,9 @@
     m_shortMonthLabels = { WTF::monthName, std::size(WTF::monthName) };
     return m_shortMonthLabels;
 #else
-    m_shortMonthLabels.reserveCapacity(WTF::monthName.size());
+    m_monthLabels.reserveCapacity(WTF::monthName.size());
     for (const auto& literal : WTF::monthName)
-        m_shortMonthLabels.append(String(literal));
+        m_monthLabels.append(String(literal));
     return m_shortMonthLabels;
 #endif
 }
```

`L-M0`: the `6832730e09` file: L-M1, L-M2 and L-M3 together.

```diff
--- L-fixed/LocaleNone.cpp
+++ L-M0/LocaleNone.cpp
@@ -72,6 +72,7 @@
     m_monthLabels = { WTF::monthFullName, std::size(WTF::monthFullName) };
         return m_monthLabels;
 #else
+    Vector<String> m_monthLabels;
     m_monthLabels.reserveCapacity(WTF::monthFullName.size());
     for (const auto& literal : WTF::monthFullName)
         m_monthLabels.append(String(literal));
@@ -122,9 +123,10 @@
     m_shortMonthLabels = { WTF::monthName, std::size(WTF::monthName) };
     return m_shortMonthLabels;
 #else
-    m_shortMonthLabels.reserveCapacity(WTF::monthName.size());
+    Vector<String> m_shortMonthLabels;
+    m_monthLabels.reserveCapacity(WTF::monthName.size());
     for (const auto& literal : WTF::monthName)
-        m_shortMonthLabels.append(String(literal));
+        m_monthLabels.append(String(literal));
     return m_shortMonthLabels;
 #endif
 }
```

`C-M1`: the baseline initializer list.

```diff
--- C-fixed/CursorJava.cpp
+++ C-M1/CursorJava.cpp
@@ -41,10 +41,7 @@
  * slot or a 0 return - both of which land on the same null cursor as before.
  */
 Cursor::Cursor(Image* image, const IntPoint& hotspot)
-    : m_type(Type::Custom)
-    , m_image(image)
-    , m_hotSpot(image ? determineHotSpot(image, hotspot) : IntPoint())
-    , m_platformCursor(0)
+    : m_platformCursor(0)
 {
     if (!image) {
         return;
```

`C-M2`: the baseline `Custom` arm, which constructs a second cursor.

```diff
--- C-fixed/CursorJava.cpp
+++ C-M2/CursorJava.cpp
@@ -197,8 +197,9 @@
     case PlatformCursorType::ZoomOut:
       setPlatformCursor(zoomOutCursor());
         break;
-    // A custom cursor gets its platform cursor in the constructor; if that failed it shows as the pointer, as before.
     case PlatformCursorType::Custom:
+      setPlatformCursor(Cursor(m_image.get(), m_hotSpot));
+        break;
     default:
       setPlatformCursor(pointerCursor());
         break;
```

`C-M3`: `m_hotSpot(hotspot)`: the page's hotspot kept unchecked.

```diff
--- C-fixed/CursorJava.cpp
+++ C-M3/CursorJava.cpp
@@ -43,7 +43,7 @@
 Cursor::Cursor(Image* image, const IntPoint& hotspot)
     : m_type(Type::Custom)
     , m_image(image)
-    , m_hotSpot(image ? determineHotSpot(image, hotspot) : IntPoint())
+    , m_hotSpot(hotspot)
     , m_platformCursor(0)
 {
     if (!image) {
```

`C-M0`: the `6832730e09` file: C-M1 and C-M2 together.

```diff
--- C-fixed/CursorJava.cpp
+++ C-M0/CursorJava.cpp
@@ -41,10 +41,7 @@
  * slot or a 0 return - both of which land on the same null cursor as before.
  */
 Cursor::Cursor(Image* image, const IntPoint& hotspot)
-    : m_type(Type::Custom)
-    , m_image(image)
-    , m_hotSpot(image ? determineHotSpot(image, hotspot) : IntPoint())
-    , m_platformCursor(0)
+    : m_platformCursor(0)
 {
     if (!image) {
         return;
@@ -197,8 +194,9 @@
     case PlatformCursorType::ZoomOut:
       setPlatformCursor(zoomOutCursor());
         break;
-    // A custom cursor gets its platform cursor in the constructor; if that failed it shows as the pointer, as before.
     case PlatformCursorType::Custom:
+      setPlatformCursor(Cursor(m_image.get(), m_hotSpot));
+        break;
     default:
       setPlatformCursor(pointerCursor());
         break;
```

`C-noupcall`: `return;` before the upcall, so no custom cursor gets a platform cursor (full library, no probe code).

```diff
--- C-fixed/CursorJava.cpp
+++ C-noupcall/CursorJava.cpp
@@ -60,6 +60,7 @@
         return;
     }

+    return;
     m_platformCursor = cb->cursor_get_custom_id(
         wkj_ref(*cursorImageFrame->platformImage()->getImage()),
         hotspot.x(), hotspot.y());
```

`C-hotspot`: `m_hotSpot` passed to Java instead of the page's hotspot (full library, no probe code).

```diff
--- C-fixed/CursorJava.cpp
+++ C-hotspot/CursorJava.cpp
@@ -62,7 +62,7 @@

     m_platformCursor = cb->cursor_get_custom_id(
         wkj_ref(*cursorImageFrame->platformImage()->getImage()),
-        hotspot.x(), hotspot.y());
+        m_hotSpot.x(), m_hotSpot.y());
     wkjCheckAndClearException();
 }

```

### Results
`results.tsv` has 193 rows: 183 checks and one verdict row per variant (10), 146 PASS and 47 FAIL. Every FAIL is a
check of a mutant; every verdict row is PASS (the fixed variants pass every check with loader exit 0, every mutant is
killed with exit 1). `sizeof(LocaleNone)` is 192. The cursor runs report `host theme table: none (wkj_init not
called)`, the 16x16 image as `16x16 isNull=0` and `Image::nullImage()` as `0x0 isNull=1`.

Verdicts:

| Variant | Probe DLL | Expected | Actual | Verdict |
| --- | --- | --- | --- | --- |
| L-fixed | P-fixed | every check passes, exit 0 | 0 failed, loader exit 0; full-then-short: passed steps 1-4; short-then-full: passed steps 1-4 | PASS |
| C-fixed | P-fixed | every check passes, exit 0 | 0 failed, loader exit 0 | PASS |
| L-M0 | P-M0 | killed: at least one check fails, exit 1 | 6 failed, loader exit 1; full-then-short: stopped at failed step 1; short-then-full: stopped at failed step 1 | PASS |
| C-M0 | P-M0 | killed: at least one check fails, exit 1 | 8 failed, loader exit 1 | PASS |
| L-M1 | P-M1 | killed: at least one check fails, exit 1 | 4 failed, loader exit 1; full-then-short: stopped at failed step 1; short-then-full: stopped at failed step 1 | PASS |
| C-M1 | P-M1 | killed: at least one check fails, exit 1 | 8 failed, loader exit 1 | PASS |
| L-M2 | P-M2 | killed: at least one check fails, exit 1 | 4 failed, loader exit 1; full-then-short: stopped at failed step 1; short-then-full: stopped at failed step 1 | PASS |
| C-M2 | P-M2 | killed: at least one check fails, exit 1 | 4 failed, loader exit 1 | PASS |
| L-M3 | P-M3 | killed: at least one check fails, exit 1 | 10 failed, loader exit 1; full-then-short: stopped at failed step 2; short-then-full: stopped at failed step 2 | PASS |
| C-M3 | P-M3 | killed: at least one check fails, exit 1 | 3 failed, loader exit 1 | PASS |

Every check of the fixed variants (`P-fixed`):

| Variant | Check | Expected | Actual | Result |
| --- | --- | --- | --- | --- |
| L-fixed | full-then-short/1-address/monthLabels#1 | inside the object [+0, +192) | +176 | PASS |
| L-fixed | full-then-short/1-address/shortMonthLabels#1 | inside the object [+0, +192) | +160 | PASS |
| L-fixed | full-then-short/1-address/monthLabels#2 | inside the object [+0, +192) | +176 | PASS |
| L-fixed | full-then-short/1-address/shortMonthLabels#2 | inside the object [+0, +192) | +160 | PASS |
| L-fixed | full-then-short/1-address/monthLabels#2==#1 | same address | same (#2-#1=0) | PASS |
| L-fixed | full-then-short/1-address/shortMonthLabels#2==#1 | same address | same (#2-#1=0) | PASS |
| L-fixed | full-then-short/2-size/monthLabels#1 | 12 | 12 | PASS |
| L-fixed | full-then-short/2-size/shortMonthLabels#1 | 12 | 12 | PASS |
| L-fixed | full-then-short/2-size/monthLabels#2 | 12 | 12 | PASS |
| L-fixed | full-then-short/2-size/shortMonthLabels#2 | 12 | 12 | PASS |
| L-fixed | full-then-short/2-size/monthLabels-after-all-calls | 12 | 12 | PASS |
| L-fixed | full-then-short/2-size/shortMonthLabels-after-all-calls | 12 | 12 | PASS |
| L-fixed | full-then-short/3-content/monthLabels | January,February,March,April,May,June,July,August,September,October,November,December | January,February,March,April,May,June,July,August,September,October,November,December | PASS |
| L-fixed | full-then-short/3-content/shortMonthLabels | Jan,Feb,Mar,Apr,May,Jun,Jul,Aug,Sep,Oct,Nov,Dec | Jan,Feb,Mar,Apr,May,Jun,Jul,Aug,Sep,Oct,Nov,Dec | PASS |
| L-fixed | full-then-short/4-format/#1 2026-03 monthFormat MMMM|LLLL | March|March | March|March | PASS |
| L-fixed | full-then-short/4-format/#2 2026-03 shortMonthFormat MMM|LLL | Mar|Mar | Mar|Mar | PASS |
| L-fixed | full-then-short/4-format/#3 2026-09 monthFormat MMMM|LLLL | September|September | September|September | PASS |
| L-fixed | full-then-short/4-format/#4 2026-09 shortMonthFormat MMM|LLL | Sep|Sep | Sep|Sep | PASS |
| L-fixed | full-then-short/4-format/#5 2026-03 monthFormat MMMM|LLLL | March|March | March|March | PASS |
| L-fixed | short-then-full/1-address/shortMonthLabels#1 | inside the object [+0, +192) | +160 | PASS |
| L-fixed | short-then-full/1-address/monthLabels#1 | inside the object [+0, +192) | +176 | PASS |
| L-fixed | short-then-full/1-address/shortMonthLabels#2 | inside the object [+0, +192) | +160 | PASS |
| L-fixed | short-then-full/1-address/monthLabels#2 | inside the object [+0, +192) | +176 | PASS |
| L-fixed | short-then-full/1-address/monthLabels#2==#1 | same address | same (#2-#1=0) | PASS |
| L-fixed | short-then-full/1-address/shortMonthLabels#2==#1 | same address | same (#2-#1=0) | PASS |
| L-fixed | short-then-full/2-size/shortMonthLabels#1 | 12 | 12 | PASS |
| L-fixed | short-then-full/2-size/monthLabels#1 | 12 | 12 | PASS |
| L-fixed | short-then-full/2-size/shortMonthLabels#2 | 12 | 12 | PASS |
| L-fixed | short-then-full/2-size/monthLabels#2 | 12 | 12 | PASS |
| L-fixed | short-then-full/2-size/monthLabels-after-all-calls | 12 | 12 | PASS |
| L-fixed | short-then-full/2-size/shortMonthLabels-after-all-calls | 12 | 12 | PASS |
| L-fixed | short-then-full/3-content/monthLabels | January,February,March,April,May,June,July,August,September,October,November,December | January,February,March,April,May,June,July,August,September,October,November,December | PASS |
| L-fixed | short-then-full/3-content/shortMonthLabels | Jan,Feb,Mar,Apr,May,Jun,Jul,Aug,Sep,Oct,Nov,Dec | Jan,Feb,Mar,Apr,May,Jun,Jul,Aug,Sep,Oct,Nov,Dec | PASS |
| L-fixed | short-then-full/4-format/#1 2026-03 shortMonthFormat MMM|LLL | Mar|Mar | Mar|Mar | PASS |
| L-fixed | short-then-full/4-format/#2 2026-03 monthFormat MMMM|LLLL | March|March | March|March | PASS |
| L-fixed | short-then-full/4-format/#3 2026-09 shortMonthFormat MMM|LLL | Sep|Sep | Sep|Sep | PASS |
| L-fixed | short-then-full/4-format/#4 2026-09 monthFormat MMMM|LLLL | September|September | September|September | PASS |
| L-fixed | short-then-full/4-format/#5 2026-03 shortMonthFormat MMM|LLL | Mar|Mar | Mar|Mar | PASS |
| C-fixed | sentinel/pointerCursor().platformCursor | 0x5EED | 0x5EED | PASS |
| C-fixed | image16x16-hotspot3,5/type | Custom (44) | Custom (44) | PASS |
| C-fixed | image16x16-hotspot3,5/image | the 16x16 image | the 16x16 image | PASS |
| C-fixed | image16x16-hotspot3,5/hotSpot | 3,5 | 3,5 | PASS |
| C-fixed | image16x16-hotspot40,40/type | Custom (44) | Custom (44) | PASS |
| C-fixed | image16x16-hotspot40,40/image | the 16x16 image | the 16x16 image | PASS |
| C-fixed | image16x16-hotspot40,40/hotSpot | 0,0 | 0,0 | PASS |
| C-fixed | nullptr-hotspot3,5/type | Custom (44) | Custom (44) | PASS |
| C-fixed | nullptr-hotspot3,5/image | null | null | PASS |
| C-fixed | nullptr-hotspot3,5/hotSpot | 0,0 | 0,0 | PASS |
| C-fixed | Image::nullImage()-hotspot3,5/type | Custom (44) | Custom (44) | PASS |
| C-fixed | Image::nullImage()-hotspot3,5/image | Image::nullImage() | Image::nullImage() | PASS |
| C-fixed | Image::nullImage()-hotspot3,5/hotSpot | 0,0 | 0,0 | PASS |
| C-fixed | image16x16-hotspot3,5/platformCursor | 0x5EED (pointer) | 0x5EED | PASS |
| C-fixed | image16x16-hotspot40,40/platformCursor | 0x5EED (pointer) | 0x5EED | PASS |
| C-fixed | nullptr-hotspot3,5/platformCursor | 0x5EED (pointer) | 0x5EED | PASS |
| C-fixed | Image::nullImage()-hotspot3,5/platformCursor | 0x5EED (pointer) | 0x5EED | PASS |

Every failed check (the mutants):

| Variant | Check | Expected | Actual |
| --- | --- | --- | --- |
| L-M0 | full-then-short/1-address/monthLabels#1 | inside the object [+0, +192) | outside: address-object=-1490263159160 |
| L-M0 | full-then-short/1-address/shortMonthLabels#1 | inside the object [+0, +192) | outside: address-object=-1490263159168 |
| L-M0 | full-then-short/1-address/shortMonthLabels#2 | inside the object [+0, +192) | outside: address-object=-1490263159168 |
| L-M0 | full-then-short/1-address/monthLabels#2==#1 | same address | differs (#2-#1=1490263159336) |
| L-M0 | short-then-full/1-address/shortMonthLabels#1 | inside the object [+0, +192) | outside: address-object=-1490263159168 |
| L-M0 | short-then-full/1-address/shortMonthLabels#2 | inside the object [+0, +192) | outside: address-object=-1490263159168 |
| C-M0 | image16x16-hotspot3,5/type | Custom (44) | Invalid (0) |
| C-M0 | image16x16-hotspot3,5/image | the 16x16 image | null |
| C-M0 | image16x16-hotspot3,5/hotSpot | 3,5 | 0,0 |
| C-M0 | image16x16-hotspot40,40/type | Custom (44) | Invalid (0) |
| C-M0 | image16x16-hotspot40,40/image | the 16x16 image | null |
| C-M0 | nullptr-hotspot3,5/type | Custom (44) | Invalid (0) |
| C-M0 | Image::nullImage()-hotspot3,5/type | Custom (44) | Invalid (0) |
| C-M0 | Image::nullImage()-hotspot3,5/image | Image::nullImage() | null |
| L-M1 | full-then-short/1-address/monthLabels#1 | inside the object [+0, +192) | outside: address-object=-1632868732616 |
| L-M1 | full-then-short/1-address/monthLabels#2 | inside the object [+0, +192) | outside: address-object=-1632868732616 |
| L-M1 | short-then-full/1-address/monthLabels#1 | inside the object [+0, +192) | outside: address-object=-1632868732616 |
| L-M1 | short-then-full/1-address/monthLabels#2 | inside the object [+0, +192) | outside: address-object=-1632868732616 |
| C-M1 | image16x16-hotspot3,5/type | Custom (44) | Invalid (0) |
| C-M1 | image16x16-hotspot3,5/image | the 16x16 image | null |
| C-M1 | image16x16-hotspot3,5/hotSpot | 3,5 | 0,0 |
| C-M1 | image16x16-hotspot40,40/type | Custom (44) | Invalid (0) |
| C-M1 | image16x16-hotspot40,40/image | the 16x16 image | null |
| C-M1 | nullptr-hotspot3,5/type | Custom (44) | Invalid (0) |
| C-M1 | Image::nullImage()-hotspot3,5/type | Custom (44) | Invalid (0) |
| C-M1 | Image::nullImage()-hotspot3,5/image | Image::nullImage() | null |
| L-M2 | full-then-short/1-address/shortMonthLabels#1 | inside the object [+0, +192) | outside: address-object=-1179030556504 |
| L-M2 | full-then-short/1-address/shortMonthLabels#2 | inside the object [+0, +192) | outside: address-object=-1179030556504 |
| L-M2 | short-then-full/1-address/shortMonthLabels#1 | inside the object [+0, +192) | outside: address-object=-1179030556504 |
| L-M2 | short-then-full/1-address/shortMonthLabels#2 | inside the object [+0, +192) | outside: address-object=-1179030556504 |
| C-M2 | image16x16-hotspot3,5/platformCursor | 0x5EED (pointer) | 0x0 |
| C-M2 | image16x16-hotspot40,40/platformCursor | 0x5EED (pointer) | 0x0 |
| C-M2 | nullptr-hotspot3,5/platformCursor | 0x5EED (pointer) | 0x0 |
| C-M2 | Image::nullImage()-hotspot3,5/platformCursor | 0x5EED (pointer) | 0x0 |
| L-M3 | full-then-short/2-size/shortMonthLabels#1 | 12 | 0 |
| L-M3 | full-then-short/2-size/monthLabels#2 | 12 | 24 |
| L-M3 | full-then-short/2-size/shortMonthLabels#2 | 12 | 0 |
| L-M3 | full-then-short/2-size/monthLabels-after-all-calls | 12 | 36 |
| L-M3 | full-then-short/2-size/shortMonthLabels-after-all-calls | 12 | 0 |
| L-M3 | short-then-full/2-size/shortMonthLabels#1 | 12 | 0 |
| L-M3 | short-then-full/2-size/shortMonthLabels#2 | 12 | 0 |
| L-M3 | short-then-full/2-size/monthLabels#2 | 12 | 24 |
| L-M3 | short-then-full/2-size/monthLabels-after-all-calls | 12 | 24 |
| L-M3 | short-then-full/2-size/shortMonthLabels-after-all-calls | 12 | 0 |
| C-M3 | image16x16-hotspot40,40/hotSpot | 0,0 | 40,40 |
| C-M3 | nullptr-hotspot3,5/hotSpot | 0,0 | 3,5 |
| C-M3 | Image::nullImage()-hotspot3,5/hotSpot | 0,0 | 3,5 |

Optional compile check, `-Werror=return-stack-address /Zs` (step 14): `L-fixed` and `L-M3` exit 0; `L-M1` is rejected
at `LocaleNone.cpp(79,12)`, `L-M2` at `(129,12)` and `L-M0` at `(79,12)` and `(130,12)`, each with
`error: reference to stack memory associated with local variable … returned [-Werror,-Wreturn-stack-address]`. In
the normal compiles the same sites are warnings; `L-fixed` compiles with none.

The two full mutant libraries export the 15,249 names of the real library (1,963 `wkj_`, no `jfx_probe`), load with
`LoadLibrary`, and their VERSIONINFO and manifest resources equal those of `bin/jfxwebkit.dll` apart from RVAs.
`dll-mut-noupcall/jfxwebkit.dll` has sha256 `fc93e6d5ca1e64f04bc4a1c06f7a9467fdfd9d311d36e1031f60f84de60d337d`,
`dll-mut-hotspot/jfxwebkit.dll` `7b0924d4cc5a1e3244c856f49e9fb7f82dbe798a19bab128e703231dd2505253`.

## The reviewer's probes
Four probe groups, each exported from the bundle it needs: `rv_probe_locale` (bundle 36), `rv_probe_cursor`
(bundle 28), `rv_probe_ctrun_values` and `rv_probe_ctrun_clamp` (bundle 12). Unlike the author's cursor probe, the
cursor and `ComplexTextRun` probes install a fake host with `wkj_init`: the cursor host answers
`cursor_get_predefined_id` with `0x1000 + type` and records every `cursor_get_custom_id` call, so the probe sees the
upcalls and the ids that reach Java (`OBS` lines, compared between the baseline and the fixed build); the
`ComplexTextRun` host answers the `text_run_*` slots from a table of runs. The locale probe also calls the
stand-alone variants and formats all 12 months with `MMM yyyy` and `MMMM yyyy`. Its runs are under
`<S>/review` (not kept).

### Commands (from the reviewer's report)
`R` is `<SW>\review` (or `<S>/review`). Before each compile, link, Maven run and `CheckNoJni`:
`tasklist //FO CSV | grep -i -E '"(ninja|clang-cl|clang|link|lld-link|java|javaw|cl|cmake|mvn)\.exe"'`, which stops
the step when it prints.

- **Bundle copies.** The reviewer's report gives no command for them. Read from the copies under
  `<S>/review/bundles/<V>/`: the bundle's include of the file under test is replaced by
  `#include "redirected/<file>.cpp"`, and after the includes come `// Reviewer probe code appended to this copy of the
  bundle.`, `#define RV_VARIANT "<V>"`, `rv_report.inc` and the probe source of that bundle (`rv_ctrun.cpp` for 12,
  `rv_cursor.cpp` for 28, `rv_locale.cpp` for 36). Bundle 25 (`25-TX4`) gets only the tag function:
  ```cpp
  // Reviewer probe code appended to this copy of the bundle.
  #define RV_VARIANT "25-TX4"

  extern "C" __declspec(dllexport) const char* rv_variant_25()
  {
      return RV_VARIANT;
  }
  ```
- **Compile,** per variant `V` (16 variants: `12F`, `12-TX1` to `12-TX3`, `25-TX4`, `28F`, `28B`, `28-CX1` to
  `28-CX4`, `36F`, `36B`, `36-LX1` to `36-LX3`), from `<R>\obj\<V>` with the build's response file, only `/Fo`, `/Fd`
  and the input changed and `/showIncludes` added:
  ```bash
  MSYS2_ARG_CONV_EXCL='*' cmd /v:on /c "set VSCMD_SKIP_SENDTELEMETRY=1&& call C:\PROGRA~1\MICROS~4\2022\COMMUN~1\VC\AUXILI~1\Build\vcvars64.bat 1>NUL: && set PATH=C:\SourceCode\jfx-ffm\build\toolchain\llvm-19.1.7\bin;!PATH!&& set TMP=<R>\tmp&& set TEMP=<R>\tmp&& cd /d <R>\obj\V && C:\SourceCode\jfx-ffm\build\toolchain\llvm-19.1.7\bin\clang-cl.exe @<R>\obj\V\compile.rsp"
  ```
  Every compile log shows the redirected copy and never the tree's.
- **Link,** per probe DLL `P` (13), from `<R>\link\P`, with absolute paths in `link.rsp` (the 135 WebKitLegacy
  objects, the scratch objects, `LINK_LIBRARIES`):
  ```bash
  C:\PROGRA~1\MICROS~4\2022\COMMUN~1\VC\Tools\MSVC\1444~1.352\bin\Hostx64\x64\link.exe /nologo @link.rsp /out:jfxwebkit.dll /implib:jfxwebkit.lib /dll /version:0.0 -shared /INCREMENTAL:NO C:/SourceCode/jfx-ffm/build/jfxwebkit-windows-release/WebCore/obj/version.res /OPT:NOICF /OPT:REF /INCREMENTAL:NO /MANIFEST:EMBED /MAP:jfxwebkit.map
  ```
  The scratch objects of each probe DLL, read from its `link.rsp`: `RV-fixed` 12F, 28F, 36F; `RV-base` 28B, 36B;
  `RV-LX<n>` 36-LX<n>; `RV-CX<n>` 28-CX<n>; `RV-TX1` to `RV-TX3` 12-TX1 to 12-TX3; `RV-TX4` 12F and 25-TX4. The maps
  confirm that each replaced bundle comes only from the scratch object. `RV-fixed` and `RV-CX2` export the 15,249
  names of the real library plus their `rv_*` names.
- **Loader:** `rv_loader.c` below, built into `<R>/loader/rv_loader.exe`; the report does not give its compile command.
- **Runs,** one process per group:
  ```bash
  <R>/loader/rv_loader.exe '<R>\link\P\jfxwebkit.dll' <rv_probe_locale|rv_probe_cursor|rv_probe_ctrun_values|rv_probe_ctrun_clamp>
  ```
  For the two runs that crashed, `cmd /v:on /c "rv_loader.exe ... & echo errorlevel=!ERRORLEVEL!"` printed
  `errorlevel=-1073740791`, that is `0xC0000409` (Git Bash recorded exit 127).
- **Java runs** of `CustomCursorTest` against `RV-CX2` and `RV-CX3`: the story's "The test".

### Sources

`src/rv_report.inc`, appended to every probe bundle:

```cpp
// Reviewer's probe report helper. Appended to a scratch copy of a unified bundle, after the
// bundle's own includes and before one probe body. Every line goes to stdout at once:
//   CHECK <tab> name <tab> expected <tab> actual <tab> PASS|FAIL
//   OBS   <tab> name <tab> value          (observations compared across variants)
//   INFO  <tab> name <tab> value
#include <cstdint>
#include <cstdio>
#include <cstring>

#ifndef RV_VARIANT
#error "RV_VARIANT must be defined"
#endif

namespace RVProbe {
namespace {

int g_failures = 0;
int g_checks = 0;

bool check(const char* name, const char* expected, const char* actual, bool pass)
{
    ++g_checks;
    if (!pass)
        ++g_failures;
    fprintf(stdout, "CHECK\t%s\t%s\t%s\t%s\n", name, expected, actual, pass ? "PASS" : "FAIL");
    fflush(stdout);
    return pass;
}

bool checkNum(const char* name, long long expected, long long actual)
{
    char e[64];
    char a[64];
    snprintf(e, sizeof(e), "%lld", expected);
    snprintf(a, sizeof(a), "%lld", actual);
    return check(name, e, a, expected == actual);
}

bool checkHex(const char* name, unsigned long long expected, unsigned long long actual)
{
    char e[64];
    char a[64];
    snprintf(e, sizeof(e), "0x%llX", expected);
    snprintf(a, sizeof(a), "0x%llX", actual);
    return check(name, e, a, expected == actual);
}

void obs(const char* name, const char* value)
{
    fprintf(stdout, "OBS\t%s\t%s\n", name, value);
    fflush(stdout);
}

void info(const char* name, const char* value)
{
    fprintf(stdout, "INFO\t%s\t%s\n", name, value);
    fflush(stdout);
}

// Hides a pointer's provenance from the optimizer, so calls through it stay virtual calls into
// the compiled variant and no load is hoisted above a check.
template<typename T> T* opaque(T* pointer)
{
    static T* volatile slot;
    slot = pointer;
    return slot;
}

[[maybe_unused]] uintptr_t opaqueAddress(uintptr_t address)
{
    static volatile uintptr_t slot;
    slot = address;
    return slot;
}

int finish(const char* group)
{
    fprintf(stdout, "SUMMARY\t%s\t%s\t%d checks\t%d failed\n", RV_VARIANT, group, g_checks, g_failures);
    fflush(stdout);
    return g_failures;
}

} // namespace
} // namespace RVProbe
```

`src/rv_locale.cpp` (bundle 36):

```cpp
// Reviewer's probe for LocaleNone::monthLabels() / shortMonthLabels() (Java branches).
// Appended after rv_report.inc to a copy of the bundle that includes platform/text/LocaleNone.cpp.
//
// A returned reference is dereferenced only after its address was found inside the live locale
// object, so a variant that returns a reference to a local is reported, never read.

namespace RVProbe {
namespace {

using WebCore::Locale;
using WebCore::LocaleNone;
using Labels = WTF::Vector<WTF::String>;

constexpr size_t months = 12;

// LocaleNone whose month patterns use the labels: "MMMM yyyy" / "MMM yyyy".
class LabelFormatLocale final : public LocaleNone {
private:
    WTF::String monthFormat() final { return "MMMM yyyy"_s; }
    WTF::String shortMonthFormat() final { return "MMM yyyy"_s; }
};

enum class Kind { Full, Short, StandAlone, ShortStandAlone };

const char* kindName(Kind kind)
{
    switch (kind) {
    case Kind::Full: return "monthLabels";
    case Kind::Short: return "shortMonthLabels";
    case Kind::StandAlone: return "standAloneMonthLabels";
    case Kind::ShortStandAlone: return "shortStandAloneMonthLabels";
    }
    return "?";
}

uintptr_t call(Locale& locale, Kind kind)
{
    const Labels* labels = nullptr;
    switch (kind) {
    case Kind::Full: labels = &locale.monthLabels(); break;
    case Kind::Short: labels = &locale.shortMonthLabels(); break;
    case Kind::StandAlone: labels = &locale.standAloneMonthLabels(); break;
    case Kind::ShortStandAlone: labels = &locale.shortStandAloneMonthLabels(); break;
    }
    return opaqueAddress(reinterpret_cast<uintptr_t>(labels));
}

bool inside(uintptr_t address, uintptr_t base, size_t objectSize)
{
    return address >= base && address - base + sizeof(Labels) <= objectSize;
}

const Labels& at(uintptr_t address)
{
    return *reinterpret_cast<const Labels*>(address);
}

// Calls the four accessors in the given sequence on a fresh locale and checks, in this order:
// address inside the object (gate), identity across calls, size 12, content.
void runSequence(const char* label, const Kind* sequence, size_t count)
{
    std::unique_ptr<Locale> owner = Locale::create(WTF::AtomString());
    Locale& locale = *opaque(owner.get());
    uintptr_t base = reinterpret_cast<uintptr_t>(static_cast<LocaleNone*>(owner.get()));
    uintptr_t addresses[16] = { };
    char name[256];
    char actual[512];
    bool allInside = true;
    for (size_t i = 0; i < count; ++i) {
        addresses[i] = call(locale, sequence[i]);
        bool in = inside(addresses[i], base, sizeof(LocaleNone));
        snprintf(name, sizeof(name), "%s/call%zu-%s/address-inside-object", label, i + 1, kindName(sequence[i]));
        if (in)
            snprintf(actual, sizeof(actual), "+%llu", static_cast<unsigned long long>(addresses[i] - base));
        else
            snprintf(actual, sizeof(actual), "outside (delta %lld)", static_cast<long long>(addresses[i] - base));
        allInside &= check(name, "inside", actual, in);
    }
    if (!allInside) {
        check(label, "every reference inside the object", "stopped before reading any list", false);
        return;
    }
    uintptr_t full = 0;
    uintptr_t shortList = 0;
    for (size_t i = 0; i < count; ++i) {
        bool isFull = sequence[i] == Kind::Full || sequence[i] == Kind::StandAlone;
        uintptr_t& first = isFull ? full : shortList;
        if (!first)
            first = addresses[i];
        snprintf(name, sizeof(name), "%s/call%zu-%s/same-object-as-first-%s", label, i + 1, kindName(sequence[i]),
            isFull ? "full" : "short");
        check(name, "same", addresses[i] == first ? "same" : "different", addresses[i] == first);
    }
    snprintf(name, sizeof(name), "%s/full-and-short-are-different-objects", label);
    check(name, "different", full != shortList ? "different" : "same", full != shortList);
    for (int which = 0; which < 2; ++which) {
        const Labels& labels = at(which ? shortList : full);
        const auto& expected = which ? WTF::monthName : WTF::monthFullName;
        snprintf(name, sizeof(name), "%s/%s/size-after-all-calls", label, which ? "short" : "full");
        if (!checkNum(name, months, labels.size()))
            continue;
        for (size_t m = 0; m < months; ++m) {
            snprintf(name, sizeof(name), "%s/%s/[%zu]", label, which ? "short" : "full", m);
            WTF::CString got = labels[m].utf8();
            check(name, expected[m].characters(), got.data(), labels[m] == expected[m]);
        }
    }
}

void runFormat()
{
    auto owner = makeUnique<LabelFormatLocale>();
    Locale& locale = *opaque<Locale>(owner.get());
    uintptr_t base = reinterpret_cast<uintptr_t>(static_cast<LocaleNone*>(owner.get()));
    // Gate: formatDateTime reads the same references internally.
    bool shortIn = inside(call(locale, Kind::Short), base, sizeof(LabelFormatLocale));
    bool fullIn = inside(call(locale, Kind::Full), base, sizeof(LabelFormatLocale));
    if (!check("format/gate-references-inside-object", "inside", shortIn && fullIn ? "inside" : "outside",
        shortIn && fullIn))
        return;
    char name[128];
    char month[16];
    char expected[64];
    for (int m = 1; m <= 12; ++m) {
        snprintf(month, sizeof(month), "2031-%02d", m);
        auto date = WebCore::DateComponents::fromParsingMonth(WTF::StringView::fromLatin1(month));
        if (!date) {
            snprintf(name, sizeof(name), "format/%s/parse", month);
            check(name, "parsed", "not parsed", false);
            continue;
        }
        // Short first, then full, for every month.
        snprintf(name, sizeof(name), "format/%s/MMM yyyy", month);
        snprintf(expected, sizeof(expected), "%s 2031", WTF::monthName[m - 1].characters());
        WTF::CString shortText = locale.formatDateTime(*date, Locale::FormatTypeShort).utf8();
        check(name, expected, shortText.data(), !strcmp(expected, shortText.data()));
        snprintf(name, sizeof(name), "format/%s/MMMM yyyy", month);
        snprintf(expected, sizeof(expected), "%s 2031", WTF::monthFullName[m - 1].characters());
        WTF::CString fullText = locale.formatDateTime(*date, Locale::FormatTypeUnspecified).utf8();
        check(name, expected, fullText.data(), !strcmp(expected, fullText.data()));
    }
}

} // namespace
} // namespace RVProbe

extern "C" __declspec(dllexport) const char* rv_variant_36()
{
    return RV_VARIANT;
}

extern "C" __declspec(dllexport) int32_t rv_probe_locale()
{
    using namespace RVProbe;
    info("variant", RV_VARIANT);
    const Kind shortFirst[] = { Kind::Short, Kind::Full, Kind::ShortStandAlone, Kind::StandAlone, Kind::Short, Kind::Full, Kind::Full };
    const Kind fullFirst[] = { Kind::Full, Kind::StandAlone, Kind::Short, Kind::Full, Kind::ShortStandAlone, Kind::Short, Kind::Short };
    runSequence("short-first", shortFirst, std::size(shortFirst));
    runSequence("full-first", fullFirst, std::size(fullFirst));
    runFormat();
    return finish("locale");
}
```

`src/rv_cursor.cpp` (bundle 28):

```cpp
// Reviewer probe for the Java Cursor(Image*, const IntPoint&) constructor and
// Cursor::ensurePlatformCursor(). Appended after rv_report.inc to a copy of the bundle that
// includes platform/java/CursorJava.cpp.
//
// A fake host is installed with wkj_init: the theme table answers cursor_get_predefined_id with
// 0x1000 + type and records every cursor_get_custom_id call; core.retain mints 0x100000 + ref.
// OBS lines are what reaches Java (upcall arguments, upcall count, the PlatformCursor handed to
// set_cursor) and are compared between the baseline and the fixed build. CHECK lines pin the
// values of the fixed build.

namespace RVProbe {
namespace {

using WebCore::Cursor;
using WebCore::Image;
using WebCore::IntPoint;

struct CustomCall {
    wkj_ref image;
    int32_t x;
    int32_t y;
};

WKJHost g_host { };
CustomCall g_calls[64];
int g_callCount = 0;
int64_t g_customAnswer = 0;
int g_predefinedCalls = 0;
int g_retains = 0;
int g_releases = 0;

int64_t predefinedId(int32_t type)
{
    ++g_predefinedCalls;
    return 0x1000 + type;
}

int64_t customId(wkj_ref image, int32_t x, int32_t y)
{
    if (g_callCount < 64)
        g_calls[g_callCount] = { image, x, y };
    ++g_callCount;
    return g_customAnswer;
}

wkj_ref retainRef(wkj_ref ref)
{
    ++g_retains;
    return ref + 0x100000;
}

void releaseRef(wkj_ref)
{
    ++g_releases;
}

int32_t noException()
{
    return 0;
}

bool installHost()
{
    g_host.size = static_cast<int32_t>(sizeof(WKJHost));
    g_host.core.retain = retainRef;
    g_host.core.release = releaseRef;
    g_host.core.check_and_clear_exception = noException;
    g_host.theme.cursor_get_predefined_id = predefinedId;
    g_host.theme.cursor_get_custom_id = customId;
    int32_t status = wkj_init(&g_host, static_cast<int32_t>(sizeof(WKJHost)), WKJ_ABI_VERSION);
    return checkNum("host/wkj_init", WKJ_INIT_OK, status);
}

// A 16x16 image with no decoder. javaImage() hands out a NativeImage over a fresh RQRef of
// frameRef, or nothing when hasFrame is false.
class ProbeImage final : public Image {
public:
    static Ref<ProbeImage> create(bool hasFrame, wkj_ref frameRef) { return adoptRef(*new ProbeImage(hasFrame, frameRef)); }
    int javaImageCalls() const { return m_javaImageCalls; }

    RefPtr<WebCore::NativeImage> javaImage() final
    {
        ++m_javaImageCalls;
        if (!m_hasFrame)
            return nullptr;
        return WebCore::NativeImage::create(WebCore::ImageJava::create(WebCore::RQRef::create(m_frameRef), nullptr, 16, 16));
    }

private:
    ProbeImage(bool hasFrame, wkj_ref frameRef)
        : m_hasFrame(hasFrame)
        , m_frameRef(frameRef)
    {
    }

    bool currentFrameKnownToBeOpaque() const final { return false; }
    bool currentFrameIsComplete() const final { return true; }
    WebCore::FloatSize size(WebCore::ImageOrientation = WebCore::ImageOrientation::Orientation::FromImage) const final { return { 16, 16 }; }
    WebCore::ImageDrawResult draw(WebCore::GraphicsContext&, const WebCore::FloatRect&, const WebCore::FloatRect&,
        WebCore::ImagePaintingOptions = { }) final
    {
        return WebCore::ImageDrawResult::DidNothing;
    }

    bool m_hasFrame;
    wkj_ref m_frameRef;
    int m_javaImageCalls { 0 };
};

const char* typeName(Cursor::Type type)
{
    switch (type) {
    case Cursor::Type::Invalid: return "Invalid";
    case Cursor::Type::Custom: return "Custom";
    case Cursor::Type::Pointer: return "Pointer";
    default: return "other";
    }
}

// Builds one custom cursor, asks for its platform cursor twice and once on a copy, and reports
// what reached the host. The expect* arguments are what the fixed build must produce.
void runCase(const char* label, Image* image, IntPoint hotspot, int64_t answer, int expectCalls, int32_t expectX,
    int32_t expectY, int64_t expectPlatformCursor, IntPoint expectHotSpot)
{
    char name[256];
    char value[512];
    g_callCount = 0;
    g_customAnswer = answer;
    Cursor cursor(image, hotspot);
    int callsInConstructor = g_callCount;
    int64_t first = cursor.platformCursor();
    int64_t second = cursor.platformCursor();
    Cursor copy = cursor;
    int64_t ofCopy = copy.platformCursor();
    int totalCalls = g_callCount;

    snprintf(value, sizeof(value), "ctorCalls=%d totalCalls=%d", callsInConstructor, totalCalls);
    for (int i = 0; i < totalCalls && i < 64; ++i) {
        size_t used = strlen(value);
        snprintf(value + used, sizeof(value) - used, " call%d=(0x%llX,%d,%d)", i + 1,
            static_cast<unsigned long long>(g_calls[i].image), g_calls[i].x, g_calls[i].y);
    }
    size_t used = strlen(value);
    snprintf(value + used, sizeof(value) - used, " platformCursor=0x%llX,0x%llX copy=0x%llX",
        static_cast<unsigned long long>(first), static_cast<unsigned long long>(second),
        static_cast<unsigned long long>(ofCopy));
    snprintf(name, sizeof(name), "cursor/%s/to-java", label);
    obs(name, value);

    snprintf(name, sizeof(name), "%s/custom-upcalls-in-constructor", label);
    checkNum(name, expectCalls, callsInConstructor);
    snprintf(name, sizeof(name), "%s/custom-upcalls-after-platformCursor-and-copy", label);
    checkNum(name, expectCalls, totalCalls);
    if (expectCalls && totalCalls) {
        snprintf(name, sizeof(name), "%s/upcall-hotspot-x", label);
        checkNum(name, expectX, g_calls[0].x);
        snprintf(name, sizeof(name), "%s/upcall-hotspot-y", label);
        checkNum(name, expectY, g_calls[0].y);
    }
    snprintf(name, sizeof(name), "%s/platformCursor", label);
    checkHex(name, expectPlatformCursor, first);
    snprintf(name, sizeof(name), "%s/platformCursor-second-call", label);
    checkHex(name, expectPlatformCursor, second);
    snprintf(name, sizeof(name), "%s/platformCursor-of-copy", label);
    checkHex(name, expectPlatformCursor, ofCopy);

    snprintf(name, sizeof(name), "%s/type", label);
    check(name, "Custom", typeName(cursor.type()), cursor.type() == Cursor::Type::Custom);
    RefPtr<Image> held = cursor.image();
    snprintf(name, sizeof(name), "%s/image", label);
    snprintf(value, sizeof(value), "%s", held.get() == image ? "the image passed in" : (held ? "another image" : "null"));
    check(name, "the image passed in", value, held.get() == image);
    snprintf(name, sizeof(name), "%s/hotSpot", label);
    char expected[64];
    snprintf(expected, sizeof(expected), "%d,%d", expectHotSpot.x(), expectHotSpot.y());
    snprintf(value, sizeof(value), "%d,%d", cursor.hotSpot().x(), cursor.hotSpot().y());
    check(name, expected, value, cursor.hotSpot() == expectHotSpot);
    snprintf(name, sizeof(name), "%s/copy-type-image-hotSpot", label);
    bool same = copy.type() == cursor.type() && copy.image() == cursor.image() && copy.hotSpot() == cursor.hotSpot();
    check(name, "equal to the original", same ? "equal" : "different", same);
}

} // namespace
} // namespace RVProbe

extern "C" __declspec(dllexport) const char* rv_variant_28()
{
    return RV_VARIANT;
}

extern "C" __declspec(dllexport) int32_t rv_probe_cursor()
{
    using namespace RVProbe;
    info("variant", RV_VARIANT);
    if (!installHost())
        return finish("cursor");

    char value[160];
    int64_t pointer = WebCore::pointerCursor().platformCursor();
    snprintf(value, sizeof(value), "pointer=0x%llX predefinedCalls=%d", static_cast<unsigned long long>(pointer), g_predefinedCalls);
    obs("cursor/predefined-pointer/to-java", value);
    checkHex("predefined/pointerCursor", 0x1000 + com_sun_webkit_CursorManager_POINTER, pointer);
    int64_t hand = WebCore::handCursor().platformCursor();
    int64_t text = WebCore::iBeamCursor().platformCursor();
    int64_t handAgain = WebCore::handCursor().platformCursor();
    snprintf(value, sizeof(value), "hand=0x%llX text=0x%llX handAgain=0x%llX predefinedCalls=%d",
        static_cast<unsigned long long>(hand), static_cast<unsigned long long>(text),
        static_cast<unsigned long long>(handAgain), g_predefinedCalls);
    obs("cursor/predefined-hand-text/to-java", value);
    checkHex("predefined/handCursor", 0x1000 + com_sun_webkit_CursorManager_HAND, hand);
    checkHex("predefined/iBeamCursor", 0x1000 + com_sun_webkit_CursorManager_TEXT, text);
    checkNum("predefined/one-upcall-per-predefined-cursor", 3, g_predefinedCalls);
    Cursor plain;
    snprintf(value, sizeof(value), "0x%llX", static_cast<unsigned long long>(plain.platformCursor()));
    obs("cursor/default-constructed/to-java", value);
    checkHex("default-constructed/platformCursor", pointer, plain.platformCursor());

    Ref<ProbeImage> loaded = ProbeImage::create(true, 0x4242);
    Ref<ProbeImage> frameless = ProbeImage::create(false, 0);
    Image* loadedImage = opaque<Image>(loaded.ptr());
    Image* framelessImage = opaque<Image>(frameless.ptr());
    Image* emptyImage = opaque(&Image::nullImage());

    runCase("loaded-hotspot-3,5", loadedImage, IntPoint(3, 5), 0x7001, 1, 3, 5, 0x7001, IntPoint(3, 5));
    runCase("loaded-hotspot-20,20", loadedImage, IntPoint(20, 20), 0x7002, 1, 20, 20, 0x7002, IntPoint());
    runCase("loaded-hotspot--2,4", loadedImage, IntPoint(-2, 4), 0x7003, 1, -2, 4, 0x7003, IntPoint());
    runCase("loaded-hotspot-15,15", loadedImage, IntPoint(15, 15), 0x7004, 1, 15, 15, 0x7004, IntPoint(15, 15));
    runCase("java-answers-0", loadedImage, IntPoint(3, 5), 0, 1, 3, 5, pointer, IntPoint(3, 5));
    runCase("no-java-frame", framelessImage, IntPoint(3, 5), 0x7005, 0, 0, 0, pointer, IntPoint(3, 5));
    runCase("still-loading-nullImage", emptyImage, IntPoint(3, 5), 0x7006, 0, 0, 0, pointer, IntPoint());
    runCase("null-image", nullptr, IntPoint(3, 5), 0x7007, 0, 0, 0, pointer, IntPoint());

    snprintf(value, sizeof(value), "loaded=%d frameless=%d retains=%d releases=%d", loaded->javaImageCalls(),
        frameless->javaImageCalls(), g_retains, g_releases);
    obs("cursor/javaImage-and-ref-traffic", value);
    checkNum("refs/every-retained-frame-ref-released", g_retains, g_releases);
    return finish("cursor");
}
```

`src/rv_ctrun.cpp` (bundle 12):

```cpp
// Reviewer probe for ComplexTextController::ComplexTextRun: the Java constructor, the two generic
// constructors, indexAt() and setIsNonMonotonic(). Appended after rv_report.inc to a copy of the
// bundle that includes platform/graphics/ComplexTextController.cpp, where indexAt() is defined.
//
// A fake host answers the text_run_* slots from the RunSpec table below, keyed by the wkj_ref
// of the run. The font is the system-fallback placeholder Font: no platform data, zero-width
// space glyph 0, so no glyph used here is skipped as zero width. It is leaked on purpose.
//
// Expected values for runs built by the Java constructor are what the 6832730e09 code computes:
// there m_stringLength was initialised from the same stringLength argument as m_characters.

namespace RVProbe {
namespace {

using Run = WebCore::ComplexTextController::ComplexTextRun;

struct RunSpec {
    int32_t glyphCount;
    int32_t start;
    int32_t end;
    int32_t ltr;
    int32_t offsets[8];
    int32_t glyphs[8];
};

// Index = wkj_ref of the run.
const RunSpec g_specs[] = {
    { 0, 0, 0, 0, { }, { } },
    // 1: three glyphs, the second mapped one past the last code unit of a 6-unit line.
    { 3, 0, 99, 0, { 2, 6, 0 }, { 10, 11, 12 } },
    // 2: the host reports two glyphs for an empty string.
    { 2, 0, 0, 1, { 0, 1 }, { 20, 21 } },
    // 3: no glyphs: the Java constructor synthesises one fallback glyph.
    { 0, 0, 50, 1, { }, { } },
    // 4: one glyph, one code unit.
    { 1, 0, 1, 1, { 0 }, { 30 } },
    // 5: two glyphs starting at code unit 3 of an 8-unit line.
    { 2, 3, 5, 1, { 0, 1 }, { 40, 41 } },
    // 6: three glyphs mapped to the last unit, past the end, and the first unit of a 4-unit line.
    { 3, 0, 4, 0, { 3, 9, 0 }, { 50, 51, 52 } },
};

WKJHost g_host { };

const RunSpec* spec(wkj_ref run)
{
    return run < std::size(g_specs) ? &g_specs[run] : nullptr;
}

int32_t runLtr(wkj_ref run) { return spec(run) ? spec(run)->ltr : 0; }
int32_t runGlyphCount(wkj_ref run) { return spec(run) ? spec(run)->glyphCount : 0; }
int32_t runStart(wkj_ref run) { return spec(run) ? spec(run)->start : 0; }
int32_t runEnd(wkj_ref run) { return spec(run) ? spec(run)->end : 0; }
int32_t runCharOffset(wkj_ref run, int32_t i) { return spec(run) && i >= 0 && i < 8 ? spec(run)->offsets[i] : -1; }
int32_t runGlyph(wkj_ref run, int32_t i) { return spec(run) && i >= 0 && i < 8 ? spec(run)->glyphs[i] : 0; }
int32_t runPosAndAdvance(wkj_ref, int32_t i, float out[4])
{
    out[0] = 1.5f + i;
    out[1] = 0.25f;
    out[2] = 7.0f + i;
    out[3] = 0.0f;
    return 1;
}
int32_t noException() { return 0; }

bool installHost()
{
    g_host.size = static_cast<int32_t>(sizeof(WKJHost));
    g_host.core.check_and_clear_exception = noException;
    g_host.graphics.text_run_is_left_to_right = runLtr;
    g_host.graphics.text_run_get_glyph_count = runGlyphCount;
    g_host.graphics.text_run_get_start = runStart;
    g_host.graphics.text_run_get_end = runEnd;
    g_host.graphics.text_run_get_char_offset = runCharOffset;
    g_host.graphics.text_run_get_glyph = runGlyph;
    g_host.graphics.text_run_get_glyph_pos_and_advance = runPosAndAdvance;
    int32_t status = wkj_init(&g_host, static_cast<int32_t>(sizeof(WKJHost)), WKJ_ABI_VERSION);
    return checkNum("host/wkj_init", WKJ_INIT_OK, status);
}

const WebCore::Font& font()
{
    static WebCore::Font* placeholder = &WebCore::Font::createSystemFallbackFontPlaceholder().leakRef();
    return *placeholder;
}

const char16_t g_text[] = u"abcdefghijklmnop";

void describe(const char* label, const Run& run)
{
    char value[512];
    int used = snprintf(value, sizeof(value), "glyphCount=%u stringLength=%zu stringLocation=%u begin=%u end=%u ltr=%d indexAt=",
        run.glyphCount(), run.stringLength(), run.stringLocation(), run.indexBegin(), run.indexEnd(), run.isLTR() ? 1 : 0);
    for (unsigned i = 0; i < run.glyphCount() + 2 && used < 400; ++i)
        used += snprintf(value + used, sizeof(value) - used, "%s%u", i ? "," : "", run.indexAt(i));
    used += snprintf(value + used, sizeof(value) - used, " indexAt(1000)=%u glyphs=", run.indexAt(1000));
    for (unsigned i = 0; i < run.glyphCount() && used < 450; ++i)
        used += snprintf(value + used, sizeof(value) - used, "%s%d", i ? "," : "", static_cast<int>(run.glyphs()[i]));
    char name[128];
    snprintf(name, sizeof(name), "ctrun/%s", label);
    obs(name, value);
}

void checkIndexAt(const char* label, const Run& run, unsigned i, unsigned expected)
{
    char name[128];
    snprintf(name, sizeof(name), "%s/indexAt(%u)", label, i);
    checkNum(name, expected, run.indexAt(i));
}

void checkEndOffsets(const char* label, Run& run, const unsigned* expected, unsigned count)
{
    char name[128];
    run.setIsNonMonotonic();
    snprintf(name, sizeof(name), "%s/isMonotonic-after-setIsNonMonotonic", label);
    checkNum(name, 0, run.isMonotonic() ? 1 : 0);
    for (unsigned i = 0; i < count; ++i) {
        snprintf(name, sizeof(name), "%s/endOffsetAt(%u)", label, i);
        checkNum(name, expected[i], run.endOffsetAt(i));
    }
}

Ref<Run> javaRun(wkj_ref id, unsigned location, unsigned length)
{
    return Run::create(id, font(), g_text, location, length);
}

} // namespace
} // namespace RVProbe

namespace RVProbe {
namespace {

Ref<Run> genericRun(unsigned length, unsigned location, unsigned begin, unsigned end, bool ltr)
{
    return Run::create(font(), std::span<const char16_t>(g_text, length), location, begin, end, ltr);
}

Ref<Run> vectorRun()
{
    Vector<WebCore::FloatSize> advances { WebCore::FloatSize(3, 0), WebCore::FloatSize(4, 0) };
    Vector<WebCore::FloatPoint> origins;
    Vector<WebCore::Glyph> glyphs { 5, 6 };
    Vector<unsigned> indices { 1, 0 };
    return Run::create(advances, origins, glyphs, indices, WebCore::FloatSize(), font(), std::span<const char16_t>(g_text, 3), 0, 0, 2, true);
}

void checkShape(const char* label, const Run& run, unsigned glyphCount, size_t stringLength)
{
    char name[128];
    snprintf(name, sizeof(name), "%s/glyphCount", label);
    checkNum(name, glyphCount, run.glyphCount());
    snprintf(name, sizeof(name), "%s/stringLength", label);
    checkNum(name, static_cast<long long>(stringLength), static_cast<long long>(run.stringLength()));
}

} // namespace
} // namespace RVProbe

extern "C" __declspec(dllexport) const char* rv_variant_12()
{
    return RV_VARIANT;
}

// Construction and indexAt() only; never calls setIsNonMonotonic().
extern "C" __declspec(dllexport) int32_t rv_probe_ctrun_values()
{
    using namespace RVProbe;
    info("variant", RV_VARIANT);
    if (!installHost())
        return finish("ctrun-values");

    Ref<Run> j1 = javaRun(1, 0, 6);
    describe("java-1", j1);
    checkShape("java-1", j1, 3, 6);
    const unsigned j1Index[] = { 2, 6, 0, 5, 5 };
    for (unsigned i = 0; i < 5; ++i)
        checkIndexAt("java-1", j1, i, j1Index[i]);
    checkIndexAt("java-1", j1, 1000, 5);
    checkNum("java-1/isLTR", 0, j1->isLTR() ? 1 : 0);
    checkNum("java-1/indexEnd", 99, j1->indexEnd());

    Ref<Run> j2 = javaRun(2, 0, 0);
    describe("java-2-empty", j2);
    checkShape("java-2-empty", j2, 0, 0);
    checkIndexAt("java-2-empty", j2, 0, 0);
    checkIndexAt("java-2-empty", j2, 1000, 0);

    Ref<Run> j3 = javaRun(3, 7, 4);
    describe("java-3-fallback", j3);
    checkShape("java-3-fallback", j3, 1, 4);
    checkIndexAt("java-3-fallback", j3, 0, 7);
    checkIndexAt("java-3-fallback", j3, 1, 3);
    checkIndexAt("java-3-fallback", j3, 1000, 3);

    Ref<Run> j4 = javaRun(4, 0, 1);
    describe("java-4-one", j4);
    checkShape("java-4-one", j4, 1, 1);
    checkIndexAt("java-4-one", j4, 0, 0);
    checkIndexAt("java-4-one", j4, 1, 0);

    Ref<Run> j5 = javaRun(5, 2, 8);
    describe("java-5-offset", j5);
    checkShape("java-5-offset", j5, 2, 8);
    checkIndexAt("java-5-offset", j5, 0, 3);
    checkIndexAt("java-5-offset", j5, 1, 4);
    checkIndexAt("java-5-offset", j5, 2, 7);

    Ref<Run> j6 = javaRun(6, 0, 4);
    describe("java-6", j6);
    checkShape("java-6", j6, 3, 4);
    checkIndexAt("java-6", j6, 3, 3);

    Ref<Run> g1 = genericRun(4, 3, 0, 4, true);
    describe("missing-glyphs-ltr", g1);
    checkShape("missing-glyphs-ltr", g1, 4, 4);
    checkIndexAt("missing-glyphs-ltr", g1, 3, 3);
    checkIndexAt("missing-glyphs-ltr", g1, 4, 3);
    checkIndexAt("missing-glyphs-ltr", g1, 1000, 3);

    Ref<Run> g2 = genericRun(3, 0, 0, 3, false);
    describe("missing-glyphs-rtl", g2);
    checkShape("missing-glyphs-rtl", g2, 3, 3);
    checkIndexAt("missing-glyphs-rtl", g2, 0, 2);
    checkIndexAt("missing-glyphs-rtl", g2, 3, 2);

    Ref<Run> g3 = genericRun(5, 1, 1, 3, true);
    describe("missing-glyphs-sub", g3);
    checkShape("missing-glyphs-sub", g3, 2, 5);
    checkIndexAt("missing-glyphs-sub", g3, 2, 4);
    checkIndexAt("missing-glyphs-sub", g3, 1000, 4);

    Ref<Run> g4 = genericRun(0, 0, 0, 0, true);
    describe("missing-glyphs-empty", g4);
    checkShape("missing-glyphs-empty", g4, 0, 0);
    checkIndexAt("missing-glyphs-empty", g4, 0, 0);

    Ref<Run> v1 = vectorRun();
    describe("vector", v1);
    checkShape("vector", v1, 2, 3);
    checkIndexAt("vector", v1, 1, 0);
    checkIndexAt("vector", v1, 2, 2);
    checkIndexAt("vector", v1, 1000, 2);
    return finish("ctrun-values");
}

// setIsNonMonotonic() and the glyph end offsets it computes. java-1 (an index equal to the
// line length) and java-2 (the host reporting glyphs for an empty string) run last.
extern "C" __declspec(dllexport) int32_t rv_probe_ctrun_clamp()
{
    using namespace RVProbe;
    info("variant", RV_VARIANT);
    if (!installHost())
        return finish("ctrun-clamp");

    Ref<Run> j3 = javaRun(3, 7, 4);
    const unsigned j3End[] = { 50 };
    checkEndOffsets("java-3-fallback", j3, j3End, 1);
    Ref<Run> j4 = javaRun(4, 0, 1);
    const unsigned j4End[] = { 1 };
    checkEndOffsets("java-4-one", j4, j4End, 1);
    Ref<Run> j5 = javaRun(5, 2, 8);
    const unsigned j5End[] = { 4, 5 };
    checkEndOffsets("java-5-offset", j5, j5End, 2);
    Ref<Run> j6 = javaRun(6, 0, 4);
    const unsigned j6End[] = { 4, 4, 3 };
    checkEndOffsets("java-6", j6, j6End, 3);

    Ref<Run> g1 = genericRun(4, 3, 0, 4, true);
    const unsigned g1End[] = { 1, 2, 3, 4 };
    checkEndOffsets("missing-glyphs-ltr", g1, g1End, 4);
    Ref<Run> g2 = genericRun(3, 0, 0, 3, false);
    const unsigned g2End[] = { 3, 2, 1 };
    checkEndOffsets("missing-glyphs-rtl", g2, g2End, 3);
    Ref<Run> g3 = genericRun(5, 1, 1, 3, true);
    const unsigned g3End[] = { 2, 3 };
    checkEndOffsets("missing-glyphs-sub", g3, g3End, 2);
    Ref<Run> v1 = vectorRun();
    const unsigned v1End[] = { 2, 1 };
    checkEndOffsets("vector", v1, v1End, 2);

    Ref<Run> j1 = javaRun(1, 0, 6);
    const unsigned j1End[] = { 5, 99, 2 };
    checkEndOffsets("java-1", j1, j1End, 3);
    Ref<Run> j2 = javaRun(2, 0, 0);
    checkEndOffsets("java-2-empty", j2, nullptr, 0);
    checkNum("java-2-empty/glyphCount-after", 0, j2->glyphCount());
    return finish("ctrun-clamp");
}
```

`src/rv_loader.c`, the loader (`rv_loader <dll> <export>`):

```c
/*
 * rv_loader <dll> <export>
 * Loads a reviewer probe build of jfxwebkit, prints the variant tag of every probe bundle it
 * holds, runs int32_t <export>(void) and prints RESULT. Exit code: 0 = every check passed,
 * 1 = a check failed, 2 = the DLL or the export is missing. A crash ends the process with the
 * exception code.
 */
#include <windows.h>
#include <stdint.h>
#include <stdio.h>

typedef const char* (*tag_fn)(void);
typedef int32_t (*probe_fn)(void);

static void tag(HMODULE dll, const char* name)
{
    tag_fn fn = (tag_fn)(void*)GetProcAddress(dll, name);
    printf("TAG\t%s\t%s\n", name, fn ? fn() : "(not in this DLL)");
}

int main(int argc, char** argv)
{
    HMODULE dll;
    probe_fn probe;
    int32_t failed;
    if (argc != 3) {
        fprintf(stderr, "usage: rv_loader <dll> <export>\n");
        return 2;
    }
    setvbuf(stdout, NULL, _IONBF, 0);
    dll = LoadLibraryExA(argv[1], NULL, LOAD_WITH_ALTERED_SEARCH_PATH);
    if (!dll) {
        printf("LOAD\tfailed\t%lu\n", (unsigned long)GetLastError());
        return 2;
    }
    tag(dll, "rv_variant_12");
    tag(dll, "rv_variant_25");
    tag(dll, "rv_variant_28");
    tag(dll, "rv_variant_36");
    probe = (probe_fn)(void*)GetProcAddress(dll, argv[2]);
    if (!probe) {
        printf("RESULT\t%s\texport missing\n", argv[2]);
        return 2;
    }
    failed = probe();
    printf("RESULT\t%s\t%d failed\n", argv[2], (int)failed);
    return failed ? 1 : 0;
}
```

### Mutants
Each diff is against the fixed copy of its bundle's file (`12F`, `28F`, `36F`; for bundle 25 the tree's file).
`28B` and `36B` are the `6832730e09` files, linked together as `RV-base`.

`36B`: the `6832730e09` `LocaleNone.cpp`.

```diff
--- 36F/LocaleNone.cpp
+++ 36B/LocaleNone.cpp
@@ -72,6 +72,7 @@
     m_monthLabels = { WTF::monthFullName, std::size(WTF::monthFullName) };
         return m_monthLabels;
 #else
+    Vector<String> m_monthLabels;
     m_monthLabels.reserveCapacity(WTF::monthFullName.size());
     for (const auto& literal : WTF::monthFullName)
         m_monthLabels.append(String(literal));
@@ -122,9 +123,10 @@
     m_shortMonthLabels = { WTF::monthName, std::size(WTF::monthName) };
     return m_shortMonthLabels;
 #else
-    m_shortMonthLabels.reserveCapacity(WTF::monthName.size());
+    Vector<String> m_shortMonthLabels;
+    m_monthLabels.reserveCapacity(WTF::monthName.size());
     for (const auto& literal : WTF::monthName)
-        m_shortMonthLabels.append(String(literal));
+        m_monthLabels.append(String(literal));
     return m_shortMonthLabels;
 #endif
 }
```

`36-LX1`: `monthLabels()` without its `isEmpty` early return.

```diff
--- 36F/LocaleNone.cpp
+++ 36-LX1/LocaleNone.cpp
@@ -66,8 +66,6 @@

 const Vector<String>& LocaleNone::monthLabels()
 {
-    if (!m_monthLabels.isEmpty())
-        return m_monthLabels;
 #if !PLATFORM(JAVA) // overload operattor issue with Vector.h ,error: no viable overloaded '='
     m_monthLabels = { WTF::monthFullName, std::size(WTF::monthFullName) };
         return m_monthLabels;
```

`36-LX2`: `shortMonthLabels()` loops over `monthFullName`.

```diff
--- 36F/LocaleNone.cpp
+++ 36-LX2/LocaleNone.cpp
@@ -123,7 +123,7 @@
     return m_shortMonthLabels;
 #else
     m_shortMonthLabels.reserveCapacity(WTF::monthName.size());
-    for (const auto& literal : WTF::monthName)
+    for (const auto& literal : WTF::monthFullName)
         m_shortMonthLabels.append(String(literal));
     return m_shortMonthLabels;
 #endif
```

`36-LX3`: `shortMonthLabels()` also fills an empty `m_monthLabels` with the short names.

```diff
--- 36F/LocaleNone.cpp
+++ 36-LX3/LocaleNone.cpp
@@ -125,6 +125,10 @@
     m_shortMonthLabels.reserveCapacity(WTF::monthName.size());
     for (const auto& literal : WTF::monthName)
         m_shortMonthLabels.append(String(literal));
+    if (m_monthLabels.isEmpty()) {
+        for (const auto& literal : WTF::monthName)
+            m_monthLabels.append(String(literal));
+    }
     return m_shortMonthLabels;
 #endif
 }
```

`28B`: the `6832730e09` `CursorJava.cpp`.

```diff
--- 28F/CursorJava.cpp
+++ 28B/CursorJava.cpp
@@ -41,10 +41,7 @@
  * slot or a 0 return - both of which land on the same null cursor as before.
  */
 Cursor::Cursor(Image* image, const IntPoint& hotspot)
-    : m_type(Type::Custom)
-    , m_image(image)
-    , m_hotSpot(image ? determineHotSpot(image, hotspot) : IntPoint())
-    , m_platformCursor(0)
+    : m_platformCursor(0)
 {
     if (!image) {
         return;
@@ -197,8 +194,9 @@
     case PlatformCursorType::ZoomOut:
       setPlatformCursor(zoomOutCursor());
         break;
-    // A custom cursor gets its platform cursor in the constructor; if that failed it shows as the pointer, as before.
     case PlatformCursorType::Custom:
+      setPlatformCursor(Cursor(m_image.get(), m_hotSpot));
+        break;
     default:
       setPlatformCursor(pointerCursor());
         break;
```

`28-CX1`: `m_image(image)` dropped.

```diff
--- 28F/CursorJava.cpp
+++ 28-CX1/CursorJava.cpp
@@ -42,7 +42,6 @@
  */
 Cursor::Cursor(Image* image, const IntPoint& hotspot)
     : m_type(Type::Custom)
-    , m_image(image)
     , m_hotSpot(image ? determineHotSpot(image, hotspot) : IntPoint())
     , m_platformCursor(0)
 {
```

`28-CX2`: the upcall gets `hotspot.y(), hotspot.x()`.

```diff
--- 28F/CursorJava.cpp
+++ 28-CX2/CursorJava.cpp
@@ -62,7 +62,7 @@

     m_platformCursor = cb->cursor_get_custom_id(
         wkj_ref(*cursorImageFrame->platformImage()->getImage()),
-        hotspot.x(), hotspot.y());
+        hotspot.y(), hotspot.x());
     wkjCheckAndClearException();
 }

```

`28-CX3`: the `Custom` arm sets `Cursor(0)` instead of falling through to the pointer.

```diff
--- 28F/CursorJava.cpp
+++ 28-CX3/CursorJava.cpp
@@ -199,6 +199,8 @@
         break;
     // A custom cursor gets its platform cursor in the constructor; if that failed it shows as the pointer, as before.
     case PlatformCursorType::Custom:
+      setPlatformCursor(Cursor(static_cast<PlatformCursor>(0)));
+        break;
     default:
       setPlatformCursor(pointerCursor());
         break;
```

`28-CX4`: `m_type(Type::Pointer)`.

```diff
--- 28F/CursorJava.cpp
+++ 28-CX4/CursorJava.cpp
@@ -41,7 +41,7 @@
  * slot or a 0 return - both of which land on the same null cursor as before.
  */
 Cursor::Cursor(Image* image, const IntPoint& hotspot)
-    : m_type(Type::Custom)
+    : m_type(Type::Pointer)
     , m_image(image)
     , m_hotSpot(image ? determineHotSpot(image, hotspot) : IntPoint())
     , m_platformCursor(0)
```

`12-TX1`: `indexAt`'s clamp uses `m_indexEnd`.

```diff
--- 12F/ComplexTextController.cpp
+++ 12-TX1/ComplexTextController.cpp
@@ -499,7 +499,7 @@
  #if PLATFORM(JAVA)
      // Prevent out-of-bounds access
      if (i >= m_glyphCount) {
-         return stringLength() ? stringLength() - 1 : 0; // clamp safely
+         return m_indexEnd ? m_indexEnd - 1 : 0; // clamp safely
      }
  #endif
     ASSERT(i < m_glyphCount);
```

`12-TX2`: `>=` becomes `>` in `setIsNonMonotonic`.

```diff
--- 12F/ComplexTextController.cpp
+++ 12-TX2/ComplexTextController.cpp
@@ -523,7 +523,7 @@
             continue;
         // Handle out of bound case
         unsigned charIndex = indexAt(i);
-        if (charIndex >= stringLength())
+        if (charIndex > stringLength())
             charIndex = stringLength() - 1; // clamp
         mappedIndices[charIndex] = true;
     }
```

`12-TX3`: the clamp value in `setIsNonMonotonic` becomes 0.

```diff
--- 12F/ComplexTextController.cpp
+++ 12-TX3/ComplexTextController.cpp
@@ -524,7 +524,7 @@
         // Handle out of bound case
         unsigned charIndex = indexAt(i);
         if (charIndex >= stringLength())
-            charIndex = stringLength() - 1; // clamp
+            charIndex = 0; // clamp
         mappedIndices[charIndex] = true;
     }
 #endif
```

`25-TX4`: the Java constructor tests `m_glyphCount == 0` instead of `stringLength == 0`.

```diff
--- tree/ComplexTextControllerJava.cpp
+++ 25-TX4/ComplexTextControllerJava.cpp
@@ -134,7 +134,7 @@
     , m_isLTR(runIsLeftToRight(jRun) != 0)
 {
     // Handle empty string runs (line breaks, etc.)
-    if (stringLength == 0) {
+    if (m_glyphCount == 0) {
         m_glyphCount = 0;
         return;
     }
```

### Results
One row per probe run: the checks the run printed, the failed ones, the loader's exit code as recorded, and the first
failed check (check, expected, actual).

| Probe DLL | Group | Checks printed | Failed | Exit | First failed check |
| --- | --- | --- | --- | --- | --- |
| RV-fixed | rv_probe_locale | 107 | 0 | 0 | - |
| RV-fixed | rv_probe_cursor | 89 | 0 | 0 | - |
| RV-fixed | rv_probe_ctrun_values | 53 | 0 | 0 | - |
| RV-fixed | rv_probe_ctrun_clamp | 33 | 0 | 0 | - |
| RV-base | rv_probe_locale | 17 | 12 | 1 | short-first/call1-shortMonthLabels/address-inside-object: expected inside, got outside (delta -2079511640032) |
| RV-base | rv_probe_cursor | 89 | 19 | 1 | loaded-hotspot-3,5/type: expected Custom, got Invalid |
| RV-LX1 | rv_probe_locale | 83 | 2 | 1 | short-first/full/size-after-all-calls: expected 12, got 48 |
| RV-LX2 | rv_probe_locale | 107 | 33 | 1 | short-first/short/[0]: expected Jan, got January |
| RV-LX3 | rv_probe_locale | 107 | 22 | 1 | short-first/full/[0]: expected January, got Jan |
| RV-CX1 | rv_probe_cursor | 89 | 7 | 1 | loaded-hotspot-3,5/image: expected the image passed in, got null |
| RV-CX2 | rv_probe_cursor | 89 | 6 | 1 | loaded-hotspot-3,5/upcall-hotspot-x: expected 3, got 5 |
| RV-CX3 | rv_probe_cursor | 89 | 12 | 1 | java-answers-0/platformCursor: expected 0x1000, got 0x0 |
| RV-CX4 | rv_probe_cursor | 89 | 8 | 1 | loaded-hotspot-3,5/type: expected Custom, got Pointer |
| RV-TX1 | rv_probe_ctrun_values | 53 | 10 | 1 | java-1/indexAt(3): expected 5, got 98 |
| RV-TX1 | rv_probe_ctrun_clamp | 33 | 0 | 0 | - |
| RV-TX2 | rv_probe_ctrun_values | 53 | 0 | 0 | - |
| RV-TX2 | rv_probe_ctrun_clamp | 27 | 0 | 127 (crashed, 0xC0000409) | - |
| RV-TX3 | rv_probe_ctrun_values | 53 | 0 | 0 | - |
| RV-TX3 | rv_probe_ctrun_clamp | 33 | 1 | 1 | java-1/endOffsetAt(0): expected 5, got 99 |
| RV-TX4 | rv_probe_ctrun_values | 53 | 3 | 1 | java-2-empty/glyphCount: expected 0, got 2 |
| RV-TX4 | rv_probe_ctrun_clamp | 2 | 0 | 127 (crashed, 0xC0000409) | - |

- `RV-fixed`: 282 checks in four groups, 0 failed.
- `RV-base`: 9 of the locale group's 14 address checks fail (every `shortMonthLabels` reference, and the
  `monthLabels` references made before a `shortMonthLabels` call, lie outside the object, delta about -2.08e12; once
  `shortMonthLabels` has filled the member `m_monthLabels`, `monthLabels` returns that member at +176), so both
  sequences and the format gate stop before reading any list (12 failed checks). The 19 failed cursor checks are all
  type, image or hotspot checks.
- `RV-TX2` and `RV-TX4`, clamp group: the process ended with `0xC0000409`, the fast-fail of WTF's `Vector` bounds check,
  after 27 passed checks (`RV-TX2`, in `java-1`'s `setIsNonMonotonic`, index 6 of a 6-unit line) and after 2
  (`RV-TX4`). Deterministic, and no freed memory is read. `RV-TX1` is killed by its values group only, `RV-TX2` and
  `RV-TX3` by their clamp group only.

What reaches Java, identical in `RV-base` and `RV-fixed` (`diff` of the two `OBS` lists empty):

| Observation | Value |
| --- | --- |
| `cursor/predefined-pointer/to-java` | pointer=0x1000 predefinedCalls=1 |
| `cursor/predefined-hand-text/to-java` | hand=0x1002 text=0x1004 handAgain=0x1002 predefinedCalls=3 |
| `cursor/default-constructed/to-java` | 0x1000 |
| `cursor/loaded-hotspot-3,5/to-java` | ctorCalls=1 totalCalls=1 call1=(0x104242,3,5) platformCursor=0x7001,0x7001 copy=0x7001 |
| `cursor/loaded-hotspot-20,20/to-java` | ctorCalls=1 totalCalls=1 call1=(0x104242,20,20) platformCursor=0x7002,0x7002 copy=0x7002 |
| `cursor/loaded-hotspot--2,4/to-java` | ctorCalls=1 totalCalls=1 call1=(0x104242,-2,4) platformCursor=0x7003,0x7003 copy=0x7003 |
| `cursor/loaded-hotspot-15,15/to-java` | ctorCalls=1 totalCalls=1 call1=(0x104242,15,15) platformCursor=0x7004,0x7004 copy=0x7004 |
| `cursor/java-answers-0/to-java` | ctorCalls=1 totalCalls=1 call1=(0x104242,3,5) platformCursor=0x1000,0x1000 copy=0x1000 |
| `cursor/no-java-frame/to-java` | ctorCalls=0 totalCalls=0 platformCursor=0x1000,0x1000 copy=0x1000 |
| `cursor/still-loading-nullImage/to-java` | ctorCalls=0 totalCalls=0 platformCursor=0x1000,0x1000 copy=0x1000 |
| `cursor/null-image/to-java` | ctorCalls=0 totalCalls=0 platformCursor=0x1000,0x1000 copy=0x1000 |
| `cursor/javaImage-and-ref-traffic` | loaded=5 frameless=1 retains=5 releases=5 |
