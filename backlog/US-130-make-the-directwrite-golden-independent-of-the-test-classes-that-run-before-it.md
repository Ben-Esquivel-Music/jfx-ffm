# US-130 — Make the DirectWrite golden independent of the test classes that run before it

**Status:** 📋 Ready (filed 2026-11-24 from US-111) · **Found:** finding R6 of the independent review of US-111's
Windows and Linux round, 2026-11-24; quoted from its report and logs; nothing built or run for this story

## Story
As a developer who runs the javafx.graphics tests on the DirectWrite golden's capture machine,
I want `DirectWriteMetricsGoldenTest` to record and compare the same rows whatever test classes ran before it in its
JVM,
so that a change to another test class cannot fail the golden's comparison or change what a capture writes.

## Problem
`test.com.sun.javafx.font.DirectWriteMetricsGoldenTest` (Windows only) captures the rows of
`directwrite-metrics-golden.txt` and compares them with it only where every `machine.*` key of the golden matches the
machine. It makes each font through the process's font factory, `factory.createFont(...)`
(`DirectWriteMetricsGoldenTest.java:155` and `:212`), which hands out the composites and physical fonts that factory has
cached; US-111 records that the test's 24 pt capture of each font reuses its 12 pt composite through `compResourceMap`
(F1 under "The Copilot review" there). Its `slots.*` rows record the "fallback fonts the composite resource holds after
layout" (the golden's key list), each as a name and a file. The review's finding R6: the test "captures through the
factory's cached composites and physical fonts (createFont), so its slots.* file-name rows depend on which earlier class
in the JVM cached a linked font first"; "a LogicalFont test that resolved DWFactory.getFallbacks' lower-case
"seguisym.ttf" slot moved 16 slots.* rows (SEGUISYM.TTF -> seguisym.ttf, logs/w-LF2.log)". `DWFactory.getFallbacks` adds
Segoe UI Symbol as `getPathNameWindows("seguisym.ttf")` (`DWFactory.java:199`), and the golden holds `SEGUISYM.TTF` in
16 rows. CI's Windows runner is not the capture machine and skips the comparison, so only the capture machine shows
this.

### Measured (logs on file; nothing new run)
- **The review's mutants LF2 and LF9**, on Windows 10 Pro 10.0.19045.6466, the golden's capture machine, on
  2026-11-24, on US-111's tree before its rework, with
  `mvn -B -ntp -pl modules/javafx.graphics -am test -DskipNative=true -Dsurefire.failIfNoSpecifiedTests=false
  -Dtest=CompositeFontLocksTest,PrismFontFactoryTest,DirectWriteNoSlotLayoutTest,DirectWriteMetricsGoldenTest
  -Djfx.parity.require=true`. LF2 makes `LogicalFont.addNativeFallback` answer `ns - 1`, LF9 makes
  `LogicalFont.getNumSlots` answer `num + 0` for `num + 1`. Surefire ran `CompositeFontLocksTest` first and
  `DirectWriteMetricsGoldenTest` second. Under each, `CompositeFontLocksTest` gave 20 / 3 / 0 / 1 (run, failures,
  errors, skipped), the mutant's own kill, and `DirectWriteMetricsGoldenTest` 2 / 1 / 0 / 0, with "16 difference(s)
  against the golden captured from the JNI build:", the rows `slots.9` and `slots.11` of arial, consolas, segoeui and
  timesnewroman at 12.0 and at 24.0, each of them, as the log prints it (broken here after the golden's value):
  ```
  font.arial.12.0.slots.9: golden=Segoe UI Symbol|C:\WINDOWS\Fonts\SEGUISYM.TTF
                           now=Segoe UI Symbol|C:\WINDOWS\Fonts\seguisym.ttf
  ```
  Logs: `review/logs/w-LF2.log` and `review/logs/w-LF9.log` in the session scratch directory that US-111's "Windows and
  Linux" names.
- **The unmutated tree passes in both orders.** The font package, `-Dtest=test/com/sun/javafx/font/**/*Test` with the
  flag, in file-system order (`CompositeFontLocksTest` 1st, `DirectWriteMetricsGoldenTest` 14th) and with
  `-Dsurefire.runOrder=reversealphabetical` (`DirectWriteMetricsGoldenTest` 51st, `CompositeFontLocksTest` 53rd): 552 /
  0 / 0 / 190 both ways, per class identical (`review/logs/w-order-asis.log`, `w-order-reverse.log`); with US-111's
  cap test 553 / 0 / 0 / 190 both ways (`confirm/logs/w-order-asis.log`, `w-order-reverse.log`).
- The golden in the tree was captured with `-Dtest=DirectWriteMetricsGoldenTest` alone, three times byte for byte
  (sha256 `b16182fd...`, US-111's "Windows and Linux").

## Requirement
1. On the golden's capture machine, `DirectWriteMetricsGoldenTest` records and compares the rows it gets when it runs
   alone in its JVM, whatever test classes ran before it there and whatever they left in the process's font factory.
2. A capture writes the same file whether the test runs alone or after other test classes in the same JVM.

## Constraints
- Test code only: no production code changes.
- Windows only, like the test (`@EnabledOnOs(OS.WINDOWS)`); the comparison still runs only where every `machine.*` key
  matches.
- The committed golden stays as it is: a capture of the test alone on its machine still writes it byte for byte.
- No weaker comparison: every row of the golden is still compared, as exactly as now.

## Acceptance criteria
On the golden's capture machine (every `machine.*` key equal), with `-Djfx.parity.require=true`, with the commands and
counts recorded here:
1. **The coupling is gone.** With LF2, then LF9, applied one at a time and restored after, the four-class run above
   gives `DirectWriteMetricsGoldenTest` the same result as `-Dtest=DirectWriteMetricsGoldenTest` alone with the same
   mutant. Before the change the four-class run fails it with the 16 `slots.*` differences above, measured again on the
   tree the story starts from.
2. **Order.** The font package in file-system and in reverse alphabetical order passes, per class identical, before and
   after the change.
3. **The golden.** A capture (`-Djfx.font.golden.capture=true -Djfx.font.golden.regenerate=true`) of the test alone,
   and one after the other font test classes in the same JVM, each write the committed file byte for byte.
4. **Nothing else changes.** The Windows module gate, `mvn -B -ntp -pl modules/javafx.graphics -am test
   -Djfx.parity.require=true`, gives the per-class counts of the after column of `US-111-windows-per-class.tsv`, but for
   tests this story adds, and `submit.yml` is green.

## Definition of Done
- All acceptance criteria met, logs kept, results stated here.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without
  explicit approval from the repository owner.**
