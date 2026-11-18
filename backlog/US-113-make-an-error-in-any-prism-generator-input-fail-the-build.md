# US-113 — Make an error in any Prism generator input fail the build

**Status:** 📋 Ready (filed 2026-11-14 from US-020) ·
**Found:** 2026-11-14, the end-to-end run of US-020 on macOS (its case P6), measured again for this story the same day

## Story
As a developer who edits a Prism `.jsl` shader,
I want an error in any `.jsl` file that the Prism shader generator reads to fail the build,
so that a mistake in `MaskSolid.jsl`, `MaskAlphaOne.jsl` or `PaintColor.jsl`, a glue block included, does not pass
in silence while the same mistake in any other Prism input stops the build.

## Problem
Paths are relative to `modules/javafx.graphics`. `CompileJSL.java` is the Prism generator,
`src/main/jsl-prism/CompileJSL.java`. Its line numbers are the same at `9186c05be8` and in the US-020 working tree up
to line 474.

### Three inputs are read and never compiled
- The generator composes 212 programs from mask and paint files (`CompileJSL.java:468-512`). `getMaskInfo` and
  `getPaintInfo` read each file's text and time (`:221-241`).
- `getFullSource` builds the text of each program (`:354-410`). For the masks `Solid` and `AlphaOne` it uses an
  empty mask text and no `mask()` call (`:362-371`, "can omit trivial mask() call"). For the paint `Color` it uses an
  empty paint text and no `paint()` call (`:375-378`, "can omit trivial paint() call"). So the text of
  `MaskSolid.jsl`, `MaskAlphaOne.jsl` and `PaintColor.jsl` goes into no program, and nothing parses it.
- Their time still counts. A program's source time is the newer of its mask and paint times (`:407-408`), so a newer
  time of one of the three makes its programs out of date (US-023).
- Since US-023 (amended 2026-11-17) the pom runs the generator once per build, in one JVM (`pom.xml:178-197`), and
  the generator reads all 28 `.jsl` files of `src/main/jsl-prism`. Before US-021 there were 30, and
  `PaintTextureYUV422.jsl` and `PaintTextureYUV444.jsl` were never read; US-021 deleted them.
- The two omissions are in the first open-source version of the generator, `839a0d8349` (2013-02-25). `git log -S`
  on either comment finds only that commit and the 2016 directory rename `c420248b9b`.

### What passes (measured 2026-11-14)
One generator run per edit, on a copy of the inputs with that one edit (setup below). "Pass" means exit 0, nothing
printed, and the 850 files of the unmodified inputs (848 `jsl-prism`, 2 `mtl-headers`), identical by name and md5.
"Fail, n" means exit 1 after writing n files, each identical by name and md5 to the file of that name from the
unmodified inputs, and no Metal header.

| Edit | `MaskSolid.jsl` | `MaskAlphaOne.jsl` | `PaintColor.jsl` | `MaskFillPgram.jsl` |
| --- | --- | --- | --- | --- |
| 1. A line holding only `}` after line 25 | pass | pass | pass | fail, 64, message 1 |
| 2. `    float v = undefinedFunc(1.0);` after the function's `{` | pass | pass | pass | fail, 64, message 2 |
| 3. The whole file replaced by `This is not JSL at all.` | pass | pass | pass | fail, 64, message 3 |
| 4. US-020's five-line glue block after line 25 | pass | pass | pass | fail, 64, message 4 |
| 5. The file emptied (0 bytes) | pass | pass | pass | fail, 64, message 5 |
| 6. The file deleted | fail, 0, message 6 | fail, 352, message 6 | fail, 0, message 6 | fail, 64, message 6 |
| 7. A valid edit: the value returned halved | pass | pass | pass | not run |

- The function's `{` of edit 2 is line 27 in the three files and line 100 in `MaskFillPgram.jsl`. The glue block of
  edit 4 is the one of US-020's Prism case: `<<`, a Java method `getThreshold()` of three lines, `>>`. Edit 7 is
  `return 0.5;` in the two masks and `return float4(0.5, 0.5, 0.5, 0.5);` in the paint.
- The 64 files are those of the 16 `Solid_*` and `Texture_*` programs, compiled before `FillPgram_Color`. The 352 are
  those of the 88 programs of the 11 masks, compiled before the alpha masks.
- The messages, first line of the output. Messages 1 to 5 come from `FillPgram_Color`: their stack ends with
  `CompileJSL.compileColorPaint(CompileJSL.java:249)` and `CompileJSL.main(CompileJSL.java:484)`.
  1. `org.antlr.v4.runtime.misc.ParseCancellationException: line 26:0 extraneous input '}' expecting {'const', ...}`
  2. `java.lang.RuntimeException: Unknown function undefinedFunc(float)`
  3. `ParseCancellationException: line 1:0 mismatched input 'This' expecting {'const', ...}`
  4. `java.lang.RuntimeException: A Prism program cannot have a glue block: FillPgram_Color` (US-020's check)
  5. `java.lang.RuntimeException: Unknown function mask(float2, float2)`
  6. `Input file not found: <file>.jsl`, then the generator's usage text, with no stack.
- Since US-022 (amended 2026-11-17), messages 1 to 5 start with the file, or the generator's own text, its line and
  column and the program, `<piece>:<line>:<column>: in program FillPgram_Color: `, and messages 2, 4 and 5 are a
  `com.sun.scenario.effect.compiler.JSLSourceException`. The text after the location is that of the list, except that
  message 4 no longer ends in the program's name. Not rerun for this story: US-022's generator case S08 measured the
  form for an error in `MaskFillPgram.jsl` (`MaskFillPgram.jsl:101:4: in program FillPgram_Color: …`), and its review
  measured message 5's text, with `mask` renamed in that file, at
  `<Prism CompileJSL main>:5:12: in program FillPgram_Color: Unknown function mask(float2, float2)`.
- Edit 4 in each of the 30 files (measured before US-021, with 30 files): the run fails with message 4 for 25 of the
  28 files the generator reads, naming the first program compiled with the file (for example `Solid_ImagePattern` for
  `PaintImagePattern.jsl`, `AlphaOne_ImagePattern` for `PaintAlphaTextureImagePattern.jsl`). It passes for the three
  files and for the two files that were never read, which US-021 deleted.
- A second run on the output tree that a run left behind: for the unmodified inputs, exit 0 and no file rewritten
  (file times compared to the nanosecond); for edit 1 in `MaskFillPgram.jsl`, exit 1 with the same output and no file
  rewritten, so the failure repeats until the file is fixed; for edit 1 in `MaskSolid.jsl`, exit 0 and nothing
  rewritten.

### Which programs each file belongs to (measured 2026-11-14)
Generate on a copy of the unmodified inputs, set every input to 10:00 and every output to 12:00 (`touch -t`), set
the one file to 13:00, run again and list the outputs rewritten. Each run exits 0, rewrites the 4 files of each
program listed, and no Metal header; the 850 files stay identical by md5 to those of the unmodified run.
- `MaskSolid.jsl`: 28 programs (112 files): `Solid_Color`, `Solid_ImagePattern`, the 6
  `Solid_{Linear,Radial}Gradient_{PAD,REFLECT,REPEAT}`, `Solid_Texture{RGB,YV12,FirstPassLCD,SecondPassLCD}` and
  `Mask_Texture{RGB,Super}`, each without and with `_AlphaTest`.
- `MaskAlphaOne.jsl`: 8 programs (32 files): `AlphaOne_{Color,ImagePattern,LinearGradient,RadialGradient}`, each
  without and with `_AlphaTest`.
- `PaintColor.jsl`: 28 programs (112 files): `<mask>_Color` for the 11 masks and the 3 alpha masks, each without and
  with `_AlphaTest`.
- `MaskFillPgram.jsl`, the control: the 16 `FillPgram_*` programs (64 files).

These are the counts the end-to-end run of US-020 read from the code (28, 8 and 28).

### How it was measured
- macOS 27.2.1 (26B131), JDK 27.0.1.0.2. The generator and the JSL compiler of the US-020 working tree, as the
  end-to-end run of US-020 built them and checked them against that tree: the `javafx.graphics` and `javafx.base`
  `target/classes`, the `javafx-jslc` jar, `src/jslc/resources` and `target/jsl-compilers/prism`. ANTLR is antlr4
  4.7.2, antlr4-runtime 4.7.2, antlr-runtime 3.5.2, ST4 4.1, org.abego.treelayout.core 1.0.3, javax.json 1.0.4 and
  icu4j 61.1 from the local Maven repository.
- The inputs: a copy of `src/main/jsl-prism` at `9186c05be8` (its `.jsl` files and `PrismLoaderGlue.stg` are the same
  in the US-020 tree), one copy per case. Each run has its own output directory and an empty working directory,
  which stayed empty:
  ```
  java -cp <graphics>:<base>:<jslc jar>:<ANTLR jars>:<jslc resources>:<jsl-compilers/prism>:<in> CompileJSL \
      -i <in> -o <out>/jsl-prism -t -mtlheaders <out>/mtl-headers -pkg com/sun/prism -d3d -es2 -mtl \
      -name <in>/MaskAlphaOne.jsl
  ```
- One run per case is enough. The generator ignores `-name` and writes all 212 programs in every JVM (US-023): the
  one run of the unmodified inputs wrote the same 850 files, by name and md5, as the 30 JVMs of the end-to-end run.
  Since US-023 (amended 2026-11-17) the pom runs the generator in one JVM, as these runs do but without `-name`, and
  `failonerror` fails the build when it exits 1; with the pom's former loop of 28 JVMs (30 before US-021), the first
  did all the work and the `<apply>` stopped at the first JVM that exited 1.
- Files compared by name and md5: `find . -type f | sort | xargs md5 -r` in each output directory.

### Upstream too
openjdk/jfx `master` at `d0e72f9d86` has the same `getFullSource`, with the same two omissions
(`CompileJSL.java:361-370` and `:374-377` there). That file differs from the fork's at `9186c05be8` only in the
copyright year and in three lines for the Metal headers. Its `MaskSolid.jsl`, `MaskAlphaOne.jsl` and `PaintColor.jsl`
have the same git blob ids as the fork's (read through the GitHub API). Upstream has no glue check. The upstream
generator was not run.

## Requirement
1. **An error in any input fails the run.** Each of the 28 `.jsl` files that the Prism generator reads is held to the
   rules that a file whose text goes into a program is held to. Its text compiles as JSL as the mask or paint of its
   programs, and it defines the function the generator would call for it: `mask()` in `MaskSolid.jsl` and
   `MaskAlphaOne.jsl`, `paint()` in `PaintColor.jsl`, as they do today. An edit that breaks this makes the generator
   run exit non-zero with a message that states the error, as the same edit of `MaskFillPgram.jsl` does today.
2. **The glue check covers every input.** A glue block in any of the 28 files fails the run with a message that says
   a Prism shader cannot have a glue block (US-020).
3. **A failure repeats.** After a run that fails, a run without `clean` on the same inputs fails again until the file
   is fixed, as it does for `MaskFillPgram.jsl` today.

## Constraints
- **Generated files unchanged.** With the unmodified inputs, the regeneration with `-d3d -es2 -mtl` writes the same
  files, identical by name and md5: the 850 Prism files above, and the 1,059 files of US-020's "Generated files
  unchanged" criterion (208 `jsl-decora`, 848 `jsl-prism`, 3 `mtl-headers`).
- **The same programs.** The generator writes the same 212 programs, and a newer time of each input makes the same
  programs out of date as today: 28, 8 and 28 for the three files.
- **Nothing written when up to date.** A run on a complete, up-to-date output tree rewrites no file.
- **A deleted input still fails,** with `Input file not found: <file>.jsl` and exit 1.
- **Code rules.** Java 27 final features only, no `var`; a production file that is at most 900 lines (`wc -l`) today
  stays so; lines at most 120 columns, one statement per line; comments only for a non-obvious "why".

## Acceptance criteria
1. **The three files fail like the control.** Run as in "How it was measured", each of edits 1 to 5 in each of
   `MaskSolid.jsl`, `MaskAlphaOne.jsl` and `PaintColor.jsl` (15 runs) exits non-zero with a message that states the
   error. Today all 15 pass. Every file such a run writes is identical by name and md5 to the file of that name from
   the unmodified inputs.
2. **The glue check covers all 28.** Edit 4 in each of the 28 files the generator reads fails the run, with a message
   that says a Prism shader cannot have a glue block. Today 25 do.
3. **A failure repeats.** For edit 1 in each of the three files, a second run on the output tree the failing run left
   exits non-zero with the same message and rewrites no file.
4. **The control is unchanged.** Edits 1 to 6 in `MaskFillPgram.jsl` fail as in the table: exit 1, the 64 files, and a
   message that states the same error.
5. **The constraints hold.** Before and after the change: the 850 Prism files and the 1,059 files are identical by
   name and md5; the time measurement above gives 28, 8, 28 and 16 programs; a second run on the unmodified inputs
   rewrites nothing; edit 6 in each of the three files gives message 6 and exit 1.
6. **Mutant proof.** With the change undone in a scratch copy, criteria 1 and 2 fail for the three files. The
   repository version passes.
7. **Build gate.** `mvn -B -ntp -pl buildtools/jslc,modules/javafx.graphics -am clean test` passes, with
   `target/gensrc/{jsl-decora,jsl-prism,headers,mtl-headers}` identical by name and md5 before and after, and the same
   test counts per class as before plus any new tests. The platforms it ran on are recorded here.

## Out of scope
- What a valid edit of the three files does. Today edit 7 passes: the generated files do not change. This story asks
  only that an error fails the run.
- The two files the generator never read, `PaintTextureYUV422.jsl` and `PaintTextureYUV444.jsl`; edit 4 passed in
  them too. US-021 deleted them.
- Which file and line a message names: US-022.
- Running the generator once per build: US-023.
- Regenerating when the compiler or a generator changes, or when a file comes back with an older time: US-024.

## Definition of Done
- All acceptance criteria met, with the logs and md5 lists of every run kept with the change and the results stated
  in this story.
- An independent review repeated criteria 1 to 3 with its own inputs.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without
  explicit approval from the repository owner.**

## Notes
- Found by the end-to-end run of US-020 (2026-11-14, macOS, Maven 3.10.0, JDK 27.0.1.0.2). Its case P6 put the glue
  block of edit 4 after line 25 of each of the three files. In each, all 30 JVMs (before US-021) exited 0 and wrote
  850 files identical to those of the unmodified inputs, before and after US-020 (quoted from that run's report). The
  rest of the table was measured for this story, with the US-020 tree only.
- US-020 makes a glue block in a Prism program an error. The limit is in the generator, not in that check: a syntax
  error, an undefined function and text that is not JSL pass in the three files as well (edits 1 to 3).
- US-019 met the same three files when it counted what the build parses ("What the build does not parse"). Parsed
  alone, each stops at EOF.
- US-022 lists how the line of an error maps to the line of a Prism input; it already notes the three omissions.
- US-022 (amended 2026-11-17): since US-022 the generators read their `.jsl` files with `JSLSource.file`, so an error
  names the file, its line and column and the program. An error in the three files is to be named the same way; the
  requirement and the criteria above hold with US-022's message forms.
- US-023 (amended 2026-11-17): the pom runs the generator in one JVM, without `-name`, and that JVM reports any Prism
  error, whatever file holds it; with the former loop of 28 JVMs (30 before US-021) it was the first, started with
  `-name …/MaskAlphaOne.jsl`. Its "A source time in the future" case also rewrote the 112 files of the 28
  `<mask>_Color` programs for `PaintColor.jsl`.
