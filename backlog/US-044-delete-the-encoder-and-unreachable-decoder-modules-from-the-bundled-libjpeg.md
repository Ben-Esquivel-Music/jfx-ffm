# US-044 — Delete the encoder and the unreachable decoder modules from the bundled libjpeg

**Status:** 📋 Ready (drafted 2026-09-30 from a read-only survey; entry points, settings and references counted with
grep/`wc -l`; the build was not run, so link success is not yet proven; nothing built). Corrected on 2026-10-01 after
PR review: `jdmerge.c` is reachable and stays, so 22 files go, not 23. Re-read `jdmaster.c:20-532`,
`jdinput.c:40-354`, `jdapimin.c:128-140,195-220`, `jddctmgr.c:56-70,84-100,218-286`, `jdapistd.c:98-132`, `jdmerge.c`,
`jdsample.c:60-341`, `jdcolor.c:186-230,716-728,795-808` and `iio_api.c:574-650,692-750,770-790`. Every count was
taken again with `wc -l`, and the external symbols of the 22 files were checked against the kept files with grep
and `nm`. A scratch probe outside the tree (copies of the unmodified sources, GCC 15.2 in WSL) reported the decode
path of the 9 corpus members and of 2 other JPEG test resources, and linked the kept file set. The Maven/CMake build,
MSVC and the tests were not run. An independent review the same day led to a second round: the block-size rule, the
fourth setting, the side effects of the `#undef`s and two acceptance criteria were corrected, and the
merged-upsampler follow-up became US-054. · **Epic:** Less native code (goal 1) · **Blocked by:** none ·
**Related:** US-054 (tests for the merged upsampler, which this story keeps)

## Story
As a platform maintainer,
I want the 22 libjpeg source files that `javafx_iio` can never execute removed from the tree,
so that the native code we build, ship, review and may later port shrinks by 15,433 lines with no behaviour
change.

## Current state
- `native-iio/libjpeg/` holds IJG libjpeg 10 (`jversion.h:12`): 41 `.c` + 9 `.h`, 34,772 lines (`wc -l`). It is
  compiled by directory glob (`native/win.cmake:262`, `native/linux.cmake:241`, `native/mac.cmake:217`).
- `iio_api.c` is decode-only. It calls 8 libjpeg functions (`:534`, `:549`, `:553`, `:569`, `:748`, `:780`, `:790`,
  `:865`) and takes the address of a ninth, `jpeg_resync_to_restart` (`:564`). All nine are defined in kept files
  (`jerror.c`, `jdapimin.c`, `jdapistd.c`, `jdmarker.c`).
- No kept file references any of the 68 `GLOBAL` functions of the 17 compressor files (`grep -c '^GLOBAL'`; `nm`
  finds no other external symbol): 12,530 lines.
- `iio_api.c` writes four decompression settings:
  - `jpeg_color_space` (`:604`, `:616`, `:640`) and `out_color_space` (`:605`, `:617`, `:620`, `:645`) in
    `iio_create`, after the header is read;
  - `out_color_space` again (`:717`), `scale_num` and `scale_denom` (`:732-745`) in `iio_start_decompression`.

  Every other setting keeps its default (`jdapimin.c:204-213`), and no kept file writes `dct_method` or
  `quantize_colors` again.
- Five decoder files are unreachable with those settings. They total 2,903 lines:
  - `jidctfst.c`/`jidctflt.c`: `dct_method` stays `JDCT_ISLOW` (`jdapimin.c:210`; `jpeglib.h:246-247`, with no
    override in `jconfig.h`). Each defines one function, referenced only at `jddctmgr.c:237`, `:243`, inside the
    8x8 case of the `dct_method` switch. Every scaled size takes a `jidctint.c` kernel (`jddctmgr.c:99-221`).
  - `jquant1.c`/`jquant2.c`: `quantize_colors` stays FALSE (`jdapimin.c:213`). Each defines one function,
    referenced only at `jdmaster.c:320`, `:330`, under `if (cinfo->quantize_colors)` (`:301`).
  - `jdtrans.c`: `jpeg_read_coefficients` (`jdtrans.c:46`), its only external symbol, is never called.
- **`jdmerge.c` (437 lines) is reachable. It stays, and `UPSAMPLE_MERGING_SUPPORTED` (`jmorecfg.h:413`) stays
  defined.**
  - `do_fancy_upsampling` stays TRUE (`jdapimin.c:211`), but that does not switch merging off: the rejection in
    `use_merged_upsample` is compiled out (`jdmaster.c:55-58`, `#if 0`).
  - What keeps ordinary files off the merged path is the IDCT scaling of `jdmaster.c:123-142`. While
    `min_DCT_h_scaled_size` is at most 8, a chroma component sampled 1 of 2 gets twice the IDCT size, and the test
    at `:78-84` returns FALSE. With the scales `iio_api.c` sets, `min_DCT_h_scaled_size` is the block size divided
    by 1, 2, 4 or 8, rounded up (`jdinput.c:57-190`).
  - So merging runs when the decoder derives a DCT block size of 9 to 16 and decodes at 1/1 a JPEG that is YCbCr
    or BG_YCC, sampled 2h1v or 2h2v, and converted to RGB (`jdmaster.c:59-76`; RGB is the default output,
    `jdapimin.c:163`). `jdmaster.c:290` stores the verdict and `:343-345` calls `jinit_merged_upsampler`.
    `jpeg_color_space` is one of the settings `iio_create` rewrites: a three-component file with an Adobe transform
    other than 1 becomes `JCS_UNKNOWN` (`iio_api.c:597-605`) and never merges (`jdmaster.c:62-63`).
  - The block size is 8 for SOF0, and for SOF2 when the first SOS names a component (`jdinput.c:243-247`).
    Otherwise it comes from `Se` = N*N-1 (`jdinput.c:249-333`, IJG's SmartScale extension), in three ways:
    - SOF1: the `Se` of the first scan (`jdmarker.c:390-391`);
    - SOF2: the `Se` of a pseudo-SOS, an SOS with no component, which only progressive mode accepts
      (`jdmarker.c:339-344`);
    - an ordinary 8x8 SOF1 file cut right after the `Ss` byte of its SOS: `Se` is then read as 0xFF from the EOI
      that `iio_api.c:241-242` supplies at the end of the stream, and 255 is 16*16-1.

    Any of the three can arrive from an image URL.
  - Scratch evidence (GCC 15.2 in WSL, calling libjpeg directly, files written with the tree's encoder), in two
    runs:
    - 288 files: block sizes 8, 9, 10, 12, 15 and 16; 1h1v, 2h1v, 1h2v and 2h2v; six sizes from 1x1 to 64x48;
      sequential and progressive; YCbCr. Decoded at 1/1, 1/2, 1/4 and 1/8: 1,152 decodes, 120 merged.
    - 40 files: block sizes 8, 9, 13 and 16; 2h1v and 2h2v; five sizes; sequential; BG_YCC. Decoded at 1/1 and
      1/2: 80 decodes, 30 merged.

    In both, the merged decodes were exactly those of a 2h1v or 2h2v file with a block size of 9 or more at 1/1.
    The reviewer's probe, through the `iio_api.c` entry points, confirmed the cut SOF1 case: `baseline-rgb-64x48`
    with SOF0 changed to SOF1 and cut after `Ss` decodes merged at 1/1, with one libjpeg warning and two
    missing-EOI warnings.
  - No test reaches this path. All 9 corpus members have block size 8, and the 7 three-component ones are 2h2v.
    At 1/1 the chroma of those that decode goes through 16x16 IDCTs (`jdmaster.c:123-142`), then
    `fullsize_upsample` (`jdsample.c:161`) and `jdcolor.c`. US-054 part 1 adds the missing members.
- Precedent: OpenJFX already prunes IJG (`UPDATING.txt:9-12`). It removed arithmetic coding by `#undef` in
  `jmorecfg.h` plus file edits (4.1, `jmorecfg.h:406`, `jdmaster.c:358-359`).
- Tests that pin today's behaviour:
  - `JpegDecodeParityTest` against `jpeg-goldens.txt` (113 keys);
  - `JpegWarningOrderTest`, `JpegStreamCallbackTest`, `JpegLifecycleTest`, `JPEGNativeTest`;
  - `tests/system/.../iio/LoadCorruptJPEGTest`.

## Approach
Delete in three commits, then fix the import procedure so the next IJG update does not bring the files back.
`jmorecfg.h` is IJG's own configuration header and is already locally modified. `#undef` of a `*_SUPPORTED` macro
turns the matching dispatch into `ERREXIT(JERR_NOT_COMPILED)` (`jdmaster.c:323`, `:333`, and the
`jddctmgr.c:247-248` default), which the fixed settings never reach.

The same `#undef`s change kept files in three ways:
- **Code compiled out** (`jdapistd.c:106`, `jdmainct.c:151`, `:335`, `:453`, `jdpostct.c:52`, `:98`, `:151`,
  `:272`, `jdmaster.c:419`, `jddctmgr.c:283`, `:317`). It is reached only with `quantize_colors` set or with a
  `dct_method` other than `JDCT_ISLOW`.
- **A default that flips, on every header.** `jdapimin.c:216-220` sets `two_pass_quantize` to FALSE instead of
  TRUE. Its only readers are `jdmaster.c:312` and `:431`, both under `quantize_colors`.
- **Two union members dropped** from `multiplier_table` (`jddctmgr.c:62`, `:65`). Its size stays the same: all
  three members are 64 elements of a 4-byte type (`MULTIPLIER` is `int`, `jmorecfg.h:473`; `FAST_FLOAT` is `float`,
  `:487`; `jdct.h:79-88`). So the `dct_table` allocation is unchanged.

Today's gates would pass a wrong `#undef` on any path the 9 corpus members do not take. Neutrality is therefore
shown by construction: the diff of the kept files is those four lines of `jmorecfg.h` and nothing else
(acceptance).

### Slices
1. **Delete the encoder.** Delete `jcapimin.c`, `jcapistd.c`, `jccoefct.c`, `jccolor.c`, `jcdctmgr.c`,
   `jchuff.c`, `jcinit.c`, `jcmainct.c`, `jcmarker.c`, `jcmaster.c`, `jcparam.c`, `jcprepct.c`, `jcsample.c`,
   `jctrans.c`, `jfdctflt.c`, `jfdctfst.c` and `jfdctint.c`.
   - Gate: builds clean on Windows and WSL (a fresh configure, so the glob drops them). The export list is
     identical. The Jpeg* module tests pass with `jpeg-goldens.txt` untouched.
2. **Delete `jdtrans.c`.** Same gate.
3. **Compile out and delete the unreachable decoder modules.** In `jmorecfg.h`, `#undef DCT_IFAST_SUPPORTED`,
   `DCT_FLOAT_SUPPORTED` (`:383-384`), `QUANT_1PASS_SUPPORTED` and `QUANT_2PASS_SUPPORTED` (`:414-415`). Leave
   `UPSAMPLE_MERGING_SUPPORTED` (`:413`) defined. Delete `jidctfst.c`, `jidctflt.c`, `jquant1.c` and `jquant2.c`.
   - Same gate, plus `tests/system` `LoadCorruptJPEGTest`.
4. **Update the import procedure.** Rewrite `UPDATING.txt` steps 3-4 in that file's own convention, which counts
   `jconfig.h` apart (`UPDATING.txt:11-12`, "41 .c and 8 .h"): copy only the same 19 `.c` and 8 `.h` files. List
   the deleted files, record the `#undef`s as modification 4.6, and drop the deleted files from the modification
   list. Update the backlog table.

## Acceptance criteria
- `libjpeg/` holds exactly 19 `.c` + 9 `.h` + `README` + `UPDATING.txt`, and `wc -l` over the sources is 19,339.
- `jdmerge.c` is one of the 19, and `UPSAMPLE_MERGING_SUPPORTED` is still defined.
- The diff of the kept sources is exactly lines 383, 384, 414 and 415 of `jmorecfg.h`. No other kept `.c` or `.h`
  file changes.
- The export list is identical: `dumpbin /exports javafx_iio.dll` and `nm -D --defined-only libjavafx_iio.so`
  show the 9 `iio_*` symbols. `IIO_ABI_VERSION` stays 2.
- `jpeg-goldens.txt` is byte-identical, and every Jpeg* module test plus `LoadCorruptJPEGTest` passes on Windows
  and WSL.
- No build file, test or non-comment source line references a deleted file (`git grep`). Expected and left alone:
  the IJG comments that name one (`jdapimin.c:16` names `jdtrans.c`; `jdct.h:11`, `:32` name `jcdctmgr.c`), the
  list of deleted files in `UPDATING.txt`, and the backlog.

## Definition of Done
Merged PR; verified on Windows and WSL Linux; macOS not built (no host), but the change only removes code that
`mac.cmake:217` globs; `UPDATING.txt` and `backlog/README.md` updated.

## Follow-up: US-054
`jdmerge.c` is live, so removing it would replace one decode path by another. That is not a dead-code deletion, and
no count above includes it. US-054 tests the merged path first and then decides whether to replace it.

## Risks
| # | Risk | Mitigation |
| --- | --- | --- |
| 1 | A macro-mediated reference survives, for example a table in a kept file | The link fails loudly; slice order isolates it. In the scratch probe the 19 files plus `iio_api.c` linked with `--no-undefined` and exported the 9 `iio_*` symbols; without the `#undef`s the link failed on `jpeg_idct_ifast`, `jpeg_idct_float`, `jinit_1pass_quantizer` and `jinit_2pass_quantizer`, and on nothing else |
| 2 | A later change sets `dct_method` or `quantize_colors` | It gets `JERR_NOT_COMPILED` → IOException in the Jpeg* tests, never silent |
| 3 | The next upstream OpenJFX libjpeg import re-adds the files (modify/delete conflicts) | `UPDATING.txt` step 3 names them; resolve by deleting again |
| 4 | The glob keeps stale objects in an incremental build | A fresh configure in the gate |
| 5 | The merged path has no test, so a change to it would pass the gate | This story leaves `jdmerge.c` and its macro untouched (acceptance); US-054 part 1 adds the members |

