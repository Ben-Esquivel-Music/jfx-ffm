# US-054 — Test the libjpeg merged upsampler, then replace it with the separate path if the two are identical

**Status:** 📋 Ready (filed 2026-10-01 from the PR review of US-044, which found the path live and untested, and
from an independent review of that correction; read: `jdmaster.c:20-532`, `jdinput.c:40-354`, `jdmerge.c`,
`jdsample.c:60-341`, `jdcolor.c:186-230,716-760,795-808`, `jdmarker.c:325-392`,
`iio_api.c:226-250,574-650,692-750,770-790` and the SOF and SOS headers of the 9 corpus members; two scratch probes
outside the tree, GCC 15.2 in WSL only, are cited below as scratch evidence; the Maven/CMake build, MSVC and the
tests were not run; not checked: every point marked so below) · **Epic:** Less native code (goal 1) ·
**Blocked by:** part 1: none; part 2: part 1, US-044 and the mutation corpus of US-045 slice 1 · **Blocks:** US-045
slice 1, which builds on part 1's members. The outcome of part 2 decides whether US-045 slice 6 ports `jdmerge` ·
**Related:** US-044, which keeps `jdmerge.c`. Part 1 and US-044 can land in either order; part 2 comes after US-044

## Story
As a JavaFX app developer who loads JPEGs from untrusted sources,
I want the merged upsampling path of the bundled libjpeg pinned by goldens, and replaced by the separate path only
if those goldens show that the two are identical,
so that a decode path any image URL can reach is no longer untested, and the Java port of US-045 has one path fewer
to reproduce.

## Current state
Paths are relative to `modules/javafx.graphics/src/main/native-iio/libjpeg` unless they name `iio_api.c`.
- **When the path runs** is derived in US-044, "Current state". In short: the decoder derives a block size of 9 to
  16 (from the `Se` of an SOF1 scan, from a pseudo-SOS in an SOF2 file, or from a cut SOF1 header), the decode is at
  1/1, and the file is YCbCr or BG_YCC, sampled 2h1v or 2h2v, and converted to RGB.
- **No test reaches it.** All 9 corpus members have block size 8 (US-044 and US-045, "Current state").
- **The members need no encoder.** For a block size N of 8 or more the entropy data keeps the 64-coefficient layout
  (`lim_Se = DCTSIZE2-1`, `jdinput.c:285-329`), so only the headers differ from an 8x8 file:
  - SOF0 becomes SOF1, the `Se` of the SOS becomes N*N-1, and the SOF size is set so that the MCU count stays what
    the entropy data holds. An MCU is 2N pixels wide; it is 2N high for 2h2v and N high for 2h1v. In a
    single-scan file any width and height inside the last MCU are free;
  - BG_YCC: the ids of the second and third components become 0x22 and 0x23 (`jdapimin.c:136-139`);
  - progressive: a pseudo-SOS `FF DA 00 06 00 00 Se 00` is inserted before the first scan (`jdmarker.c:339-344`).
    Its AC scans are not interleaved, and such a scan counts the blocks of its one component
    (`jdinput.c:394-401`, `:350-355`). So the luma block count, `ceil(W/N)` by `ceil(H/N)`, must stay as well.

  Scratch, from the reviewer's probe through the `iio_api.c` entry points (GCC in WSL):
  - 96 sequential files for N = 9 to 16 from `baseline-rgb-64x48.jpg`, `odd-17x13.jpg` and a 2h1v file. All 96
    decoded merged at 1/1: 68 without a warning, and 28, whose size had changed the MCU count, with "extraneous
    bytes before marker 0xd9".
  - `progressive-48x32.jpg` at N = 9 with a pseudo-SOS, as the reviewer reports it: 54x36 and 46x28 keep the 6 by
    4 luma blocks and decode clean and merged; 45x36, 37x36 and 54x27 keep the MCU count but not the luma block
    count, and warn "extraneous bytes before marker 0xc4".
- **An exact replacement is plausible.** With `#undef UPSAMPLE_MERGING_SUPPORTED`, `use_merged_upsample` returns
  FALSE (`jdmaster.c:87-88`) and the same images take `jdsample.c` and `jdcolor.c`. Nothing reaches
  `JERR_NOT_COMPILED` (`jdmaster.c:347`).
  - The tables are the same: `jdmerge.c:107-119`, `:141-153` and `jdcolor.c:129-137`, `:163-171`.
  - The per-pixel expression and the range limit are the same: `jdmerge.c:289-301`, `:352-375` and
    `jdcolor.c:220-224`.
  - Both replicate chroma (`jdmerge.c:14-16`; `jdsample.c:222-270`).
  - Odd width: a separate last column (`jdmerge.c:305-314`, `:378-392`) against a colour buffer rounded up to an
    even width (`jdsample.c:335-339`).
  - Odd height: a spare row (`jdmerge.c:201-214`) against the `rows_to_go` clamp (`jdsample.c:125-126`).
  - `iio_api.c:780` reads one row per call, and both paths return one.
  - Neither module emits a warning or a trace message. The progress upcalls come from the loop of
    `iio_api.c:775-788`, which does not depend on the upsampler.
  - The separate path runs error checks the merged one never did (`jdcolor.c:728-758`, `jdsample.c:294-295`,
    `:333-334`). None can fire for an input `use_merged_upsample` accepted: three components, YCC, no colour
    transform, no CCIR601 sampling, 2h1v or 2h2v (`jdmaster.c:59-76`).
- **Where it can differ.**
  - Memory. Separate: two chroma sample arrays of `max_v_samp_factor` rows each (`jdsample.c:335-339`), so 4 rows
    for 2h2v and 2 for 2h1v, plus the `my_color_deconverter` struct (`jdcolor.c:722-723`). Merged: one spare row of
    3 bytes per pixel for 2h2v and none for 2h1v (`jdmerge.c:417-429`). The image size at which libjpeg reports
    `JERR_OUT_OF_MEMORY` therefore moves.
  - Speed: merging is the fast path for these files. Not measured.
  - `rec_outbuf_height` becomes 1 (`jdmaster.c:188-191`); `iio_api.c` does not read it.
- **Scratch evidence, not the gate** (GCC 15.2 in WSL; not MSVC):
  - US-044's probe, calling libjpeg directly, with merging compiled in and compiled out: 1,152 decodes of 288
    YCbCr files and 80 decodes of 40 BG_YCC files (US-044, "Current state"). All agreed in output size, rows per
    call, warning count and pixel hash, the 150 merged ones included.
  - The reviewer's probe, through the `iio_api.c` entry points: 15,480 decodes of 1,290 inputs, truncated and
    mutated variants included, 1,366 of them merged. With merging compiled out no line differed (pixels, events,
    warnings, errors).

## Approach
### Part 1 — members and goldens for the merged path (pick up now)
This story owns the merged-path members. US-045 slice 1 builds on them and does not specify them again.
- `JpegCorpusGenerator` builds every member by header surgery on a JPEG the JDK writer produced. No encoder and
  no committed fixture is needed, so the provenance stays in the tree.
  - Not verified: that the JDK writer can produce the 4:2:2 source the 2h1v members need. If it cannot, that one
    source is a committed fixture with written provenance.
- Members, each decoded at 1/1 (merged) and at 1/2, 1/4 and 1/8 (not merged), except the 1-pixel ones:
  - 2h2v and 2h1v, YCbCr, SOF1, at every block size from 9 to 16, with odd and even widths and heights;
  - BG_YCC, 2h2v and 2h1v, at one block size or more;
  - SOF2 with a pseudo-SOS, 2h2v, at one block size or more;
  - a 1-pixel-wide and a 1-pixel-high member for 2h2v and for 2h1v, from a one-MCU source. They reach the
    odd-column code alone (`jdmerge.c:305`, `:378`) and the spare-row code with one row to go (`:201-213`).
    They decode at 1/1 only: the loader clamps a requested dimension to at least 1 pixel
    (`ImageTools.java:213-219`), and the scale follows the larger of the two ratios (`iio_api.c:734-736`), which
    is then 1. Scratch, as the reviewer reports it (GCC in WSL; the one-MCU 16x8 source came from the IJG
    encoder, not the JDK writer): at N = 12 the 2h2v and 2h1v members of 1x1, 1xH and Wx1 decode clean and
    merged, and with merging compiled out 0 of 156 output lines differ;
  - the cut SOF1 member: an 8x8 SOF1 file cut right after the `Ss` byte of its SOS (block size 16 from the
    supplied EOI, `iio_api.c:241-242`). It gives one libjpeg warning, "Invalid SOS parameters for sequential
    JPEG", and two missing-EOI warnings (`iio_api.c:143-150`);
  - a truncated variant, cut inside the entropy data, of one 2h2v and one 2h1v member.
- A scale of 1/d needs both requested ratios at or below 1/d (`iio_api.c:734-746`), so a member decoded at 1/d has
  at least d pixels in each dimension, and the test gives the requested size with `preserveAspectRatio` off, so
  that the rounding of `ImageTools.java:194-200` cannot move a ratio.
- Which members take the merged path cannot be seen through the exported ABI. It is **predicted**: a test reads
  each member's SOF and SOS headers, applies the rule of US-044 to them and to the requested size, and asserts that
  the shapes above are all present. The prediction is confirmed once by an instrumented scratch run of the C
  (`cinfo->cconvert == NULL` after `jpeg_start_decompress`), and the capture provenance records that run.
- The goldens are extended under the rules of US-045 slice 1: existing keys keep their values, new members are
  appended, capture is on Windows **and** WSL from today's C, and a difference between the two is a finding.
- Gate: the Jpeg* module tests pass on Windows and WSL with the new members; the 111 member keys of today's
  `jpeg-goldens.txt` are unchanged.

### Part 2 — compile merging out (optional, gated)
`jdmerge.c` is live, so this replaces one decode path by another. It is not a dead-code deletion.
- Gate:
  1. Part 1 has landed, with its 1-pixel members.
  2. With merging compiled out, every golden of the corpus is byte-identical and the event traces are identical,
     on Windows and WSL. That includes the truncation goldens of part 1 and, for the merged-path members, the
     outcomes of the seeded mutation corpus of US-045 slice 1: warning and event parity is tested there.
  3. The maintainer accepts the memory difference and a measured decode time for the merged-path members.
- If the gate passes: `#undef UPSAMPLE_MERGING_SUPPORTED` (`jmorecfg.h:413`), delete `jdmerge.c` (437 lines; after
  US-044 that leaves 18 `.c` and 18,902 lines), record the `#undef` in `UPDATING.txt`, and US-045 slice 6 does not
  port `jdmerge`.
- If any golden differs: `jdmerge.c` stays, US-045 ports it, and this story closes with part 1.
- If US-045 is ruled out, its mutation corpus does not exist. Part 2 then adds a seeded mutation set for the
  merged-path members first.

## Acceptance criteria
- Part 1:
  - the corpus holds every member listed above, and the header test asserts the predicted path of each;
  - every member except the cut and the truncated ones decodes with no warning event, and a test asserts it, so a
    member whose size does not match its entropy data fails instead of being captured into the golden;
  - the capture provenance names the instrumented run that confirmed the prediction, with its commit and platform;
  - goldens are identical on Windows and WSL, or the difference is recorded as a finding;
  - no native source changes.
- Part 2, if it is taken:
  - the diff of the kept sources is line 413 of `jmorecfg.h` and the deletion of `jdmerge.c`;
  - every golden and event trace is identical before and after, on Windows and WSL;
  - the export list is identical (the 9 `iio_*` symbols), and `IIO_ABI_VERSION` stays 2;
  - the PR records the memory difference and the decode times the maintainer accepted.

## Definition of Done
Merged PR per part; verified on Windows and WSL Linux; macOS not built (no host). `backlog/README.md`, US-044 and
US-045 are updated with the outcome of part 2, or with the decision not to take it.

## Risks
| # | Risk | Mitigation |
| --- | --- | --- |
| 1 | The header prediction is wrong, so a member believed merged is not | The one instrumented run recorded in the provenance; the rule is two source ranges (`jdinput.c:243-333`, `jdmaster.c:59-84`) |
| 2 | A surgery member does not keep its MCU count, or for the SOF2 member its luma block count, and decodes with a warning | The generator derives the size from the source's block counts; the acceptance test fails any member, other than the cut and truncated ones, that emits a warning event |
| 3 | Windows and Linux differ for these members (`INT32` is `long`, `jmorecfg.h:252`) | Capture on both; a difference is a finding for the maintainer, as in US-045 risk 3 |
| 4 | Part 2 passes the gate but changes the out-of-memory threshold for huge images | Gate item 3: the maintainer accepts it knowingly; no golden can pin it |
