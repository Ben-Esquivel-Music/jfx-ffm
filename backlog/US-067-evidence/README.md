# US-067 evidence

Kept with the change for [US-067](../US-067-fix-the-coretext-null-crashes-and-leaks-kept-by-the-ffm-migration.md).
Host of every run: Apple M6, arm64, macOS 27.2.1 (26B131), JDK 27.0.1.0.2, Maven 3.10.0; 2026-11-12 and 2026-11-13.
"Before" is a clean tree at HEAD `559ca936af`. "Final" is the same commit with the working tree to commit, which
`final.cumulative.patch` holds. Results are tests / failures / errors / skipped.

## Before (clean tree)

| file | what | result |
| --- | --- | --- |
| `gate-before.log` | the macOS gate with `-Djfx.parity.require=true` | exit 1, 466 / 8 / 0 / 0 |
| `before-gate-noflag.log` | the macOS gate without the flag | exit 0, 466 / 0 / 0 / 8 |
| `before-module.log` | `mvn -pl modules/javafx.graphics -am test` | exit 0, graphics 26,680 / 0 / 0 / 858 |
| `before-checks.log` | the two `hs_err_pid*` finds and the golden `git diff --stat` | 0 bytes |

## Final tree, run by the implementer

| file | what | result |
| --- | --- | --- |
| `final3-gate-noflag.log` | the gate without the flag | exit 0, 482 / 0 / 0 / 8 |
| `final3-gate-require.log` | the gate with the flag | exit 1, 482 / 8 / 0 / 0 |
| `final3-module.log` | the module run | exit 0, graphics 26,696 / 0 / 0 / 858 |
| `final3-checks.log` | the two finds and the golden `git diff --stat` | 0 bytes |

## Final tree, run by the independent reviewer (`ffm-reviewer`)

| file | what | result |
| --- | --- | --- |
| `final-review.md` | the report: sections 1-4 on the tree with the strike fix, 5 on the final tree | approve |
| `finaltree2-gate-noflag.log` | the gate without the flag | exit 0, 482 / 0 / 0 / 8 |
| `finaltree2-gate-require.log` | the gate with the flag | exit 1, 482 / 8 / 0 / 0 |
| `finaltree2-module.log` | the module run | exit 0, graphics 26,696 / 0 / 0 / 858 |

In every module log jslc is 196 / 0 / 0 / 0 and `javafx.base` 5,510 / 0 / 0 / 22. The 8 failures under the flag are
the 8 golden comparisons that skip without it: the goldens are from macOS 27.0.1 (26A434), not from this machine.

## Final tree, the second mutant pass and corpus dump (implementer, after the review)

The tree was byte-identical to `final.cumulative.patch` before the pass, after it and after the last run (`cmp`).

| file | what | result |
| --- | --- | --- |
| `mutant2-<row>.log` | the row's test under its mutant, 17 rows | exit 1, 1 / 1 / 0 / 0 each |
| `mutant2-<row>-restored.log` | the same test with the lines put back | exit 0, 1 / 0 / 0 / 0 each |
| `mutant2-pin.log` | the `CTFontStrike` pin as `OS.CFRelease*2`, two classes | exit 1, 10 / 1 / 0 / 0 |
| `mutant2-pin-restored.log` | the pin put back | exit 0, 10 / 0 / 0 / 1 |
| `mutant2-strike.log` | the strike release deleted, three classes | exit 1, 17 / 1 / 0 / 0 |
| `mutant2-strike-restored.log` | the release put back | exit 0, 17 / 0 / 0 / 1 |
| `ab-final.log` | the dump of the five golden corpora on the final tree | exit 0, 1 / 0 / 0 / 0 |
| `ab-final-diff.log` | `diff -r` of that dump against the first "before" dump | 0 bytes |
| `final4-gate-noflag.log` | the gate without the flag, after the pass and the dump | exit 0, 482 / 0 / 0 / 8 |
| `final4-checks.log` | the two finds and the golden `git diff --stat` | 0 bytes |

The 17 rows are 1, 2a, 2b, 3a, 3b, 3c, 4, 5, 6, 7a, 7b, 8a, 8b, 9, 10a, 10b and 10c. The pin mutant ran
`MacFontBindingStructureTest,MacFontFinderGoldenTest`. The strike mutant deleted `OS.CFRelease(fontRef);` from the
`catch` of `CTFontStrike.createDisposer` and ran those two classes and `MacFontReleaseTest`: only the pin check of
`MacFontFinderGoldenTest` fails, no behaviour test does. The one skip of the restored pin and strike runs is the
golden comparison. The logs of rows 3a, 4 and 5 name `hs_err` files that the restored runs cleared.

The dump has 2,583, 260, 599, 558 and 1,580 lines, as before. The flagged gate and the module run were not
repeated after the pass; `final3-*` are their last runs.

## Measurements

- `US-067-measured.md`: the implementer's record (results per fix step, the mutant table, the pin table, the corpus
  dump, the strike fix in section 10, the `CTFontStrike` pin in section 11, and the second mutant pass and dump in
  its last section). Its later sections correct its earlier ones; its `SCRATCH` and `LOGS` paths are the scratch
  directory named below and its `logs/`.
- `US-067-macos-gate-per-class.tsv`: the gate per class (34 classes, two subtotals and a total), before and after, both
  ways. Its "after" columns were taken from the runs after the first mutant pass (`after-gate-*.log`, not copied here);
  the per-class lines of `final3-gate-noflag.log` and `final3-gate-require.log` are the same.

## Patches

Each is `git diff HEAD -- modules` plus the untracked files under `modules`, so each applies to a clean tree at
`559ca936af` and gives the tree after that step. The difference of two neighbours is one fix.

| file | tree | files |
| --- | --- | --- |
| `fix-01` to `fix-09` (`.cumulative.patch`) | after fixes 1 to 9, one step each | 5, 10, 12, 14, 16, 16, 19, 20, 20 |
| `fix-10a`, `fix-10b`, `fix-10c` | after fixes 10a, 10b and 10c | 21, 22, 23 |
| `fix-10d-strike` | after the strike fix (the tree sections 1-4 of the review read) | 24 |
| `final` | the final tree: the `CTFontStrike` pin added (129,683 bytes) | 24 |

Two things in `fix-01` to `fix-10c` are not in the final tree: the evidence-only `Us067CorpusDumpTest.java`, and,
from `fix-02` on, the class-level `@SuppressWarnings("restricted")` of `MacFontProbeShim` that the first review
round had deleted. Leave both out when committing fix by fix.

## Not copied

The per-fix logs (`fix-NN-{red,green,gate-noflag,gate-require,module,checks}.log`, `fix-NN-pin-*.log`: the story's
"Verification" block after every fix step), the mutant logs of the first pass (`mutant-<row>.log`,
`mutant-<row>-restored.log`), `pin-strike-*.log`, the corpus dumps themselves (`ab/`) and their earlier logs, the
snapshots of the second pass (`patches/pre-mutants2`, `after-mutants2` and `end`, each identical to `final`) and
the intermediate runs (`after-*.log`, `final-*.log`, `final2-*.log`, the reviewer's earlier logs) stayed in the
session scratch directory, which is temporary:

`/private/tmp/claude-501/-Users-benesquivelmusic-Documents-SourceCode-jfx-ffm/b184fe71-a1a0-4bc9-a84f-7a9056c40664/scratchpad/us067/`

The story's "Done" section summarises them.
