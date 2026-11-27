# US-119 — Point the GStreamer, GLib and WebKit notices at this fork's sources

## In short
- **Problem:** The legal notices for GStreamer, GLib and WebKit say that the modified source code is in the OpenJDK
  repository. This fork has changed those sources further, so the notices point at the wrong place. These are LGPL
  libraries, where saying where the modified source is matters.
- **Seen failing?** Yes: read in the notice files and compared with the fork's history.
- **Goal:** Each notice names this fork as the place of the sources the binaries were built from.
- **Done when:** The shipped notices in the jars and the SDK carry the corrected text, and the legal-notice check
  passes.
- **Order:** Can start now. Small: text only.

**Status:** 📋 Ready (filed 2026-11-15 from US-040) · **Found:** 2026-11-15, while US-040 changed vendored media
sources; the notices, the patch manifest and the git history read for this story, nothing built or run ·
**Related:** US-040, US-075

## Story
As anyone who receives this fork's jars, SDK or native libraries,
I want each notice of a bundled library this fork has modified to say where those modified sources are,
so that the notice leads to the sources the binaries were built from.

## Problem
Paths are relative to the repository root; `gst/` is `modules/javafx.media/src/main/native/gstreamer`. Files that
US-040 changes are cited as in its working tree (uncommitted, on `29f69dc205`), and "section 14.2" is that section of
`modules/javafx.media/FFM-ABI-CONTRACT.md` in that tree.

### What the notices say
- `modules/javafx.media/src/main/legal/gstreamer.md:17-26`: "A copy of the Oracle modified GStreamer library source
  code is located in the following OpenJDK git repository: https://github.com/openjdk/jfx", with the root directory
  `rt/modules/javafx.media/src/main/native/gstreamer/gstreamer-lite/`. `glib.md:17-26` says the same of GNU Glib,
  with the root `rt/modules/javafx.media/src/main/native/gstreamer/3rd_party/glib/`. Both name LGPL 2.1 as the
  overarching licence, with parts under BSD-style licences and, for GLib, MIT (`gstreamer.md:31-33`,
  `glib.md:33-35`).
- `modules/javafx.web/src/main/legal/webkit.md:17-26` says the same of WebKit, with the root
  `rt/modules/javafx.web/src/main/native/`.
- No other file under `modules/*/src/main/legal`, and none of the root `LICENSE`, `ADDITIONAL_LICENSE_INFO` and
  `ASSEMBLY_EXCEPTION`, says where modified sources are (`git grep`). This tree has no `rt/` directory.

### What this tree holds
- The fork point is `ca9b07aeae` (8390652), the parent of the fork's first own commit `9090342ef6` (`git log`).
  `gst/` is unchanged from it to `939aa61ea`. `a544256444` (Port media module to FFM, #7) changed 25 files under
  `gst/`, and `29f69dc205` differs from the fork point in the same 25 (`git diff --stat`): 18 deleted makefiles
  under `gst/projects/`, and the seven files that section 14.2 lists as measured at the merge base `939aa61ea`
  (`:1143-1164`).
  - Under the GStreamer notice's root: `gstdirectsoundnotify.cpp`, `gstdirectsoundnotify.h` and
    `gstdirectsoundsink.c` in `gst/gstreamer-lite/gst-plugins-good/sys/directsound/`, built into the Windows
    `gstreamer-lite` (`modules/javafx.media/native/win.cmake:638`, `:652`, `:673`, `:693-697`).
  - Under neither root: `javasource.c`, `marshal.c`, `marshal.h` and `marshal.in` in `gst/plugins/javasource/`.
    `javasource.c` carries the GPLv2 + Classpath exception header of the JavaFX sources (`:1-24`); the three
    `marshal` files carry no licence header at all.
- US-040 changes three more files (section 14.2, `:1224-1259`); before it, no file under the GLib root differs from
  the fork point. Each export list gains one export.
  - `gst/gstreamer-lite/gst-plugins-good/gst/spectrum/gstspectrum.c`, under the GStreamer root;
  - `gst/3rd_party/glib/build/win32/vs100/glib-lite.def`, the Windows `glib-lite` export list, under the GLib root;
  - `gst/projects/win/gstreamer-lite.def`, the Windows `gstreamer-lite` export list, under neither root.
- `modules/javafx.web/src/main/native` differs from the fork point in 542 files at `29f69dc205` (`git diff --stat`),
  and `.github/workflows/build-webkit.yml:209` and `:502` build the fork's WebKit library from it.

### Where the notices go
US-075 ships every file of a module's `src/main/legal` in its jar under `META-INF/legal/` and in the SDK under
`legal/<module>/` (`backlog/US-075-ship-every-modules-legal-notices-in-its-jar-and-the-sdk.md:85-88`).
`buildtools/CheckLegalNotices.java` compares each jar entry and each SDK copy byte for byte with its source file
(`:54-68`, `:300`, `:343`); it does not check what a notice says.

So a recipient of this fork's binaries is sent to `openjdk/jfx` for the modified GStreamer and WebKit sources, and
for the modified GLib sources once US-040 lands, while the binaries are built from this tree's copies, which differ in
the files above.

## Requirement
1. **Each notice names this fork's modified sources.** Each notice under `modules/*/src/main/legal` of a bundled
   library whose sources this fork has modified (`gstreamer.md`, `webkit.md`, and `glib.md` with US-040) names the
   place where exactly those modified sources are published, and says nothing that is true only of upstream. The
   notices shipped in the jars and in the SDK are the corrected ones.

## Constraints
- The licence texts in the notices are not altered.
- US-075's mechanism and `buildtools/CheckLegalNotices.java` stay as they are, and the check passes.
- No other notice changes: every other file under `modules/*/src/main/legal`, and the root `LICENSE`,
  `ADDITIONAL_LICENSE_INFO` and `ASSEMBLY_EXCEPTION`, keep their bytes.
- The wording names the repository the binaries are built from and names no person.
- Whether the fork's binaries are distributed at all is not this story's question.

## Acceptance criteria
1. **Check.** `mvn -B -ntp verify -DskipNative=true -DskipTests` passes, and its log shows `CheckLegalNotices`
   passing the jar checks of `javafx.media` and `javafx.web` and the SDK check.
2. **Shipped text.** A listing of `META-INF/legal/` in `sdk/target/sdk/lib/javafx.media.jar` and
   `javafx.web.jar`, and of `sdk/target/sdk/legal/javafx.media/` and `legal/javafx.web/`, with the changed notices
   read from both, shows the corrected text; recorded here.
3. **Sentence review.** A review compares each sentence of the changed `gstreamer.md` and `glib.md` with section
   14.2's manifest, and each sentence of the changed `webkit.md` with the WebKit tree, and finds none that is untrue
   of this fork's sources; recorded here.
4. **No notice left.** A `git grep` of `modules/*/src/main/legal/*` and the three root licence files finds no notice
   that sends the reader upstream for sources this fork changed; the command and its output recorded here.

## Out of scope
- Copies of the notices outside the jars and the SDK, as in US-075: jmods, `src.zip`, a runtime image's legal
  directory (US-084) and the artifact of the prebuilt WebKit library.
- Section 14.2 and the modified sources themselves.

## Definition of Done
- All acceptance criteria met, logs kept, results stated here; an independent review repeated criteria 3 and 4.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without
  explicit approval from the repository owner.**

## Notes
- The owner approved filing this story on 2026-11-15.
