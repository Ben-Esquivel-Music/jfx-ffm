# US-044 — Delete the encoder and the unreachable decoder modules from the bundled libjpeg

**Status:** 📋 Ready (drafted 2026-09-30 from a read-only survey; entry points, settings and references counted with
grep/`wc -l`; the build was not run, so link success is not yet proven; nothing built) · **Epic:** Less native code
(goal 1) · **Blocked by:** none

## Story
As a platform maintainer,
I want the 23 libjpeg source files that `javafx_iio` can never execute removed from the tree,
so that the native code we build, ship, review and may later port shrinks by 15,870 lines with no behaviour
change.

## Current state
- `native-iio/libjpeg/` holds IJG libjpeg 10 (`jversion.h:12`): 41 `.c` + 9 `.h`, 34,772 lines (`wc -l`). It is
  compiled by directory glob (`native/win.cmake:262`, `native/linux.cmake:241`, `native/mac.cmake:217`).
- `iio_api.c` is decode-only (8 entry points, `:534`-`:865`). No decoder or common file references any of the 67
  `GLOBAL` functions of the 17 compressor files: 12,530 lines.
- Six decoder files are unreachable with the settings `iio_api.c` leaves at their defaults (`jdapimin.c:208-213`).
  They total 3,340 lines:
  - `jidctfst.c`/`jidctflt.c` (`dct_method` stays `JDCT_ISLOW`, `jpeglib.h:246-247`; referenced only at
    `jddctmgr.c:237`, `:243`);
  - `jquant1.c`/`jquant2.c` (`quantize_colors` stays FALSE; `jdmaster.c:320`, `:330`);
  - `jdmerge.c` (fancy upsampling stays TRUE, so `jdmaster.c:56` never merges);
  - `jdtrans.c` (`jpeg_read_coefficients`, `jdtrans.c:46`, is never called).
- Precedent: OpenJFX already prunes IJG (`UPDATING.txt:9-12`). It removed arithmetic coding by `#undef` in
  `jmorecfg.h` plus file edits (4.1, `jmorecfg.h:406`, `jdmaster.c:358-359`).
- Tests that pin today's behaviour:
  - `JpegDecodeParityTest` against `jpeg-goldens.txt` (113 keys);
  - `JpegWarningOrderTest`, `JpegStreamCallbackTest`, `JpegLifecycleTest`, `JPEGNativeTest`;
  - `tests/system/.../iio/LoadCorruptJPEGTest`.

## Approach
Delete in three commits, then fix the import procedure so the next IJG update does not bring the files back.
`jmorecfg.h` is IJG's own configuration header and is already locally modified. `#undef` of a `*_SUPPORTED` macro
turns the matching dispatch into `ERREXIT(JERR_NOT_COMPILED)` (`jdmaster.c:323`, `:333`, `:347`, and the
`jddctmgr.c` default), which the fixed settings never reach.

### Slices
1. **Delete the encoder.** Delete `jcapimin.c`, `jcapistd.c`, `jccoefct.c`, `jccolor.c`, `jcdctmgr.c`,
   `jchuff.c`, `jcinit.c`, `jcmainct.c`, `jcmarker.c`, `jcmaster.c`, `jcparam.c`, `jcprepct.c`, `jcsample.c`,
   `jctrans.c`, `jfdctflt.c`, `jfdctfst.c` and `jfdctint.c`.
   - Gate: builds clean on Windows and WSL (a fresh configure, so the glob drops them). The export list is
     identical. The Jpeg* module tests pass with `jpeg-goldens.txt` untouched.
2. **Delete `jdtrans.c`.** Same gate.
3. **Compile out and delete the unreachable decoder modules.** In `jmorecfg.h`, `#undef DCT_IFAST_SUPPORTED`,
   `DCT_FLOAT_SUPPORTED` (`:383-384`), `UPSAMPLE_MERGING_SUPPORTED` (`:413`), `QUANT_1PASS_SUPPORTED` and
   `QUANT_2PASS_SUPPORTED` (`:414-415`). Delete `jidctfst.c`, `jidctflt.c`, `jdmerge.c`, `jquant1.c` and
   `jquant2.c`.
   - Same gate, plus `tests/system` `LoadCorruptJPEGTest`.
4. **Update the import procedure.** Rewrite `UPDATING.txt` steps 3-4: the directory now holds 18 `.c` and 9 `.h`;
   list the deleted files, record the `#undef`s as modification 4.6, and drop the deleted files from the
   modification list. Update the backlog table.

## Acceptance criteria
- `libjpeg/` holds exactly 18 `.c` + 9 `.h` + `README` + `UPDATING.txt`, and `wc -l` over the sources is 18,902.
- The export list is identical: `dumpbin /exports javafx_iio.dll` and `nm -D --defined-only libjavafx_iio.so`
  show the 9 `iio_*` symbols. `IIO_ABI_VERSION` stays 2.
- `jpeg-goldens.txt` is byte-identical, and every Jpeg* module test plus `LoadCorruptJPEGTest` passes on Windows
  and WSL.
- `git grep` finds no reference to a deleted file name outside `UPDATING.txt` and history.

## Definition of Done
Merged PR; verified on Windows and WSL Linux; macOS not built (no host), but the change only removes code that
`mac.cmake:217` globs; `UPDATING.txt` and `backlog/README.md` updated.

## Risks
| # | Risk | Mitigation |
| --- | --- | --- |
| 1 | A macro-mediated reference survives, for example a table in a kept file | The link fails loudly; slice order isolates it |
| 2 | A later change sets `dct_method`/`quantize_colors`/fancy=FALSE | It gets `JERR_NOT_COMPILED` → IOException in the Jpeg* tests, never silent |
| 3 | The next upstream OpenJFX libjpeg import re-adds the files (modify/delete conflicts) | `UPDATING.txt` step 3 names them; resolve by deleting again |
| 4 | The glob keeps stale objects in an incremental build | A fresh configure in the gate |

