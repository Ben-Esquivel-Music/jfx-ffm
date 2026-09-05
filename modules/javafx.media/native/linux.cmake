# Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
# DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
#
# This code is free software; you can redistribute it and/or modify it
# under the terms of the GNU General Public License version 2 only, as
# published by the Free Software Foundation.  Oracle designates this
# particular file as subject to the "Classpath" exception as provided
# by Oracle in the LICENSE file that accompanied this code.
#
# This code is distributed in the hope that it will be useful, but WITHOUT
# ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
# FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
# version 2 for more details (a copy is included in the LICENSE file that
# accompanied this code).
#
# You should have received a copy of the GNU General Public License version
# 2 along with this work; if not, write to the Free Software Foundation,
# Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
#
# Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
# or visit www.oracle.com if you need additional information or have any
# questions.


# Linux-specific portion of the javafx.media native build. Transliterated from
# the retired GNU make projects src/main/native/jfxmedia/projects/linux/Makefile
# and src/main/native/gstreamer/projects/linux/{gstreamer-lite,fxplugins,
# avplugin}/Makefile (driven by the Gradle COMPILE_MEDIA=true build through
# buildSrc/linux.gradle): same source lists, defines, include directories,
# warning/optimisation flags and link libraries. Included from CMakeLists.txt;
# the shared inputs (GENSRC_DIR, HEADERS_DIR, BIN_DIR and the *_SRC source roots)
# are defined there.
#
# Notes on makefile parity:
#   - Linux links the SYSTEM GLib (pkg-config glib-2.0 gobject-2.0 gmodule-2.0
#     gthread-2.0), so there is no glib-lite target here, unlike Windows/macOS.
#   - Gradle compiled with gcc and linked with g++ (buildSrc/linux.gradle:201-202);
#     forcing LINKER_LANGUAGE CXX below matches that, and is what the
#     -static-libstdc++ in every makefile's LDFLAGS assumes.
#   - avplugin is optional: the makefile builds it against either a bundled
#     LIBAV_DIR or the system ffmpeg found by pkg-config. Only the system case
#     is ported, and the target is skipped with a message when libavcodec and
#     libavformat are absent (CI installs no ffmpeg development packages today).
#   - Three include directories listed by the gstreamer-lite makefile do not
#     exist in the tree (gstreamer/gst/parse, gst-plugins-good/gst-libs,
#     gst-plugins-bad/gst-libs) and are omitted, as the Windows port omits the
#     equally stale gst-plugins-base/win32/common.
#   - The i386 (-m32), IS_STATIC_BUILD and parfait variants of the Gradle build
#     are not ported, matching the javafx.graphics port.

find_package(PkgConfig REQUIRED)
pkg_check_modules(JFXM_GLIB REQUIRED IMPORTED_TARGET
    glib-2.0 gobject-2.0 gmodule-2.0 gthread-2.0)
pkg_check_modules(JFXM_ALSA REQUIRED IMPORTED_TARGET alsa)
pkg_check_modules(JFXM_LIBAV IMPORTED_TARGET libavcodec libavformat)

# ---------------------------------------------------------------------------
# Global flags: the makefiles set every flag explicitly, so CMake's defaults
# are cleared exactly as the javafx.graphics port does.
# ---------------------------------------------------------------------------
foreach(lang C CXX)
    set(CMAKE_${lang}_FLAGS "")
    set(CMAKE_${lang}_FLAGS_RELEASE "")
    set(CMAKE_${lang}_FLAGS_DEBUG "")
endforeach()
set(CMAKE_SHARED_LINKER_FLAGS "")
set(CMAKE_SHARED_LINKER_FLAGS_RELEASE "")
set(CMAKE_SHARED_LINKER_FLAGS_DEBUG "")

# Warning and code-generation flags shared by every media makefile
set(JFXM_COMMON_COMPILE_OPTIONS
    -fPIC -Wformat -Wextra -Wformat-security -fstack-protector
    -Werror=trampolines -Werror=deprecated-declarations
    -ffunction-sections -fdata-sections
    "$<$<CONFIG:Release>:-Os>"
    "$<$<NOT:$<CONFIG:Release>>:-g;-Wall>")

# Carried only by the C-only libraries; the jfxmedia makefile does not set it
set(JFXM_C_STRICT_OPTIONS
    "$<$<COMPILE_LANGUAGE:C>:-Werror=implicit-function-declaration>")

set(JFXM_COMMON_LINK_OPTIONS
    -static-libgcc -static-libstdc++
    "LINKER:-z,relro"
    "LINKER:--gc-sections")

# The GLib version window every media makefile pins
set(JFXM_GLIB_DEFINITIONS
    GLIB_VERSION_MIN_REQUIRED=GLIB_VERSION_2_48
    GLIB_VERSION_MAX_ALLOWED=GLIB_VERSION_2_48)

# -msse2 on x86 (makefiles: ifneq (,$(findstring $(ARCH), x64 x32)))
set(JFXM_SSE2_OPTIONS)
if(CMAKE_SYSTEM_PROCESSOR MATCHES "^(x86_64|amd64|AMD64|i[3-6]86)$")
    set(JFXM_SSE2_OPTIONS -msse2)
endif()

# ---------------------------------------------------------------------------
# add_media_library(<name>
#     OUTPUT_NAME <so base name>     # produces lib<OUTPUT_NAME>.so
#     SOURCES <files...>             # explicit, exactly as the makefile listed
#     INCLUDE_DIRS <dirs...>
#     COMPILE_DEFINITIONS <defs...>
#     COMPILE_OPTIONS <opts...>
#     LINK_LIBS <libs...>
#     LINK_OPTIONS <opts...>
# )
# ---------------------------------------------------------------------------
function(add_media_library name)
    cmake_parse_arguments(MEDIA "" "OUTPUT_NAME"
        "SOURCES;INCLUDE_DIRS;COMPILE_DEFINITIONS;COMPILE_OPTIONS;LINK_LIBS;LINK_OPTIONS" ${ARGN})

    add_library(${name} SHARED ${MEDIA_SOURCES})
    set_target_properties(${name} PROPERTIES
        OUTPUT_NAME "${MEDIA_OUTPUT_NAME}"
        LINKER_LANGUAGE CXX
        LIBRARY_OUTPUT_DIRECTORY "${BIN_DIR}"
        LIBRARY_OUTPUT_DIRECTORY_RELEASE "${BIN_DIR}"
        LIBRARY_OUTPUT_DIRECTORY_DEBUG "${BIN_DIR}")

    target_compile_definitions(${name} PRIVATE ${MEDIA_COMPILE_DEFINITIONS})
    target_compile_options(${name} PRIVATE
        ${JFXM_COMMON_COMPILE_OPTIONS} ${MEDIA_COMPILE_OPTIONS})
    target_include_directories(${name} PRIVATE ${MEDIA_INCLUDE_DIRS})
    target_link_libraries(${name} PRIVATE ${MEDIA_LINK_LIBS})
    target_link_options(${name} PRIVATE ${JFXM_COMMON_LINK_OPTIONS} ${MEDIA_LINK_OPTIONS})
endfunction()

# ===========================================================================
# libgstreamer-lite.so  (gstreamer/projects/linux/gstreamer-lite/Makefile)
# ===========================================================================
add_media_library(gstreamerLite
    OUTPUT_NAME gstreamer-lite
    SOURCES
        "${GST_SRC}/gstreamer/gst/gst.c"
        "${GST_SRC}/gstreamer/gst/gstallocator.c"
        "${GST_SRC}/gstreamer/gst/gstatomicqueue.c"
        "${GST_SRC}/gstreamer/gst/gstbin.c"
        "${GST_SRC}/gstreamer/gst/gstbuffer.c"
        "${GST_SRC}/gstreamer/gst/gstbufferlist.c"
        "${GST_SRC}/gstreamer/gst/gstbufferpool.c"
        "${GST_SRC}/gstreamer/gst/gstbus.c"
        "${GST_SRC}/gstreamer/gst/gstcaps.c"
        "${GST_SRC}/gstreamer/gst/gstcapsfeatures.c"
        "${GST_SRC}/gstreamer/gst/gstchildproxy.c"
        "${GST_SRC}/gstreamer/gst/gstclock.c"
        "${GST_SRC}/gstreamer/gst/gstcontext.c"
        "${GST_SRC}/gstreamer/gst/gstcontrolbinding.c"
        "${GST_SRC}/gstreamer/gst/gstcontrolsource.c"
        "${GST_SRC}/gstreamer/gst/gstdatetime.c"
        "${GST_SRC}/gstreamer/gst/gstdebugutils.c"
        "${GST_SRC}/gstreamer/gst/gstdynamictypefactory.c"
        "${GST_SRC}/gstreamer/gst/gstelement.c"
        "${GST_SRC}/gstreamer/gst/gstelementfactory.c"
        "${GST_SRC}/gstreamer/gst/gstenumtypes.c"
        "${GST_SRC}/gstreamer/gst/gsterror.c"
        "${GST_SRC}/gstreamer/gst/gstevent.c"
        "${GST_SRC}/gstreamer/gst/gstformat.c"
        "${GST_SRC}/gstreamer/gst/gstghostpad.c"
        "${GST_SRC}/gstreamer/gst/gstidstr.c"
        "${GST_SRC}/gstreamer/gst/gstinfo.c"
        "${GST_SRC}/gstreamer/gst/gstiterator.c"
        "${GST_SRC}/gstreamer/gst/gstmemory.c"
        "${GST_SRC}/gstreamer/gst/gstmessage.c"
        "${GST_SRC}/gstreamer/gst/gstmeta.c"
        "${GST_SRC}/gstreamer/gst/gstminiobject.c"
        "${GST_SRC}/gstreamer/gst/gstobject.c"
        "${GST_SRC}/gstreamer/gst/gstpad.c"
        "${GST_SRC}/gstreamer/gst/gstpadtemplate.c"
        "${GST_SRC}/gstreamer/gst/gstparamspecs.c"
        "${GST_SRC}/gstreamer/gst/gstparse.c"
        "${GST_SRC}/gstreamer/gst/gstpipeline.c"
        "${GST_SRC}/gstreamer/gst/gstplugin.c"
        "${GST_SRC}/gstreamer/gst/gstpluginfeature.c"
        "${GST_SRC}/gstreamer/gst/gstpoll.c"
        "${GST_SRC}/gstreamer/gst/gstprotection.c"
        "${GST_SRC}/gstreamer/gst/gstquark.c"
        "${GST_SRC}/gstreamer/gst/gstquery.c"
        "${GST_SRC}/gstreamer/gst/gstregistry.c"
        "${GST_SRC}/gstreamer/gst/gstregistrybinary.c"
        "${GST_SRC}/gstreamer/gst/gstregistrychunks.c"
        "${GST_SRC}/gstreamer/gst/gstsample.c"
        "${GST_SRC}/gstreamer/gst/gstsegment.c"
        "${GST_SRC}/gstreamer/gst/gststructure.c"
        "${GST_SRC}/gstreamer/gst/gstsystemclock.c"
        "${GST_SRC}/gstreamer/gst/gststreamcollection.c"
        "${GST_SRC}/gstreamer/gst/gststreams.c"
        "${GST_SRC}/gstreamer/gst/gsttaglist.c"
        "${GST_SRC}/gstreamer/gst/gsttagsetter.c"
        "${GST_SRC}/gstreamer/gst/gsttask.c"
        "${GST_SRC}/gstreamer/gst/gsttaskpool.c"
        "${GST_SRC}/gstreamer/gst/gsttoc.c"
        "${GST_SRC}/gstreamer/gst/gsttocsetter.c"
        "${GST_SRC}/gstreamer/gst/gsttracer.c"
        "${GST_SRC}/gstreamer/gst/gsttracerfactory.c"
        "${GST_SRC}/gstreamer/gst/gsttracerrecord.c"
        "${GST_SRC}/gstreamer/gst/gsttracerutils.c"
        "${GST_SRC}/gstreamer/gst/gsttypefind.c"
        "${GST_SRC}/gstreamer/gst/gsttypefindfactory.c"
        "${GST_SRC}/gstreamer/gst/gsturi.c"
        "${GST_SRC}/gstreamer/gst/gstutils.c"
        "${GST_SRC}/gstreamer/gst/gstvalue.c"
        "${GST_SRC}/gstreamer/gst/gstvecdeque.c"
        "${GST_SRC}/gstreamer/libs/gst/base/gstadapter.c"
        "${GST_SRC}/gstreamer/libs/gst/base/gstbaseparse.c"
        "${GST_SRC}/gstreamer/libs/gst/base/gstbasesink.c"
        "${GST_SRC}/gstreamer/libs/gst/base/gstbasesrc.c"
        "${GST_SRC}/gstreamer/libs/gst/base/gstbasetransform.c"
        "${GST_SRC}/gstreamer/libs/gst/base/gstbitreader.c"
        "${GST_SRC}/gstreamer/libs/gst/base/gstbytereader.c"
        "${GST_SRC}/gstreamer/libs/gst/base/gstbytewriter.c"
        "${GST_SRC}/gstreamer/libs/gst/base/gstcollectpads.c"
        "${GST_SRC}/gstreamer/libs/gst/base/gstdataqueue.c"
        "${GST_SRC}/gstreamer/libs/gst/base/gstflowcombiner.c"
        "${GST_SRC}/gstreamer/libs/gst/base/gstpushsrc.c"
        "${GST_SRC}/gstreamer/libs/gst/base/gstqueuearray.c"
        "${GST_SRC}/gstreamer/libs/gst/base/gsttypefindhelper.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/app/app-enumtypes.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/app/gstapp-marshal.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/app/gstappsink.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/app/gstapputils.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/audio/audio.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/audio/audio-channels.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/audio/audio-channel-mixer.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/audio/audio-buffer.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/audio/audio-converter.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/audio/audio-resampler.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/audio/audio-enumtypes.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/audio/audio-quantize.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/audio/audio-format.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/audio/audio-info.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/audio/gstaudiobasesink.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/audio/gstaudiobasesrc.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/audio/gstaudioclock.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/audio/gstaudiodecoder.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/audio/gstaudioencoder.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/audio/gstaudiofilter.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/audio/gstaudioiec61937.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/audio/gstaudiometa.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/audio/gstaudiopack-dist.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/audio/gstaudioringbuffer.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/audio/gstaudiosink.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/audio/gstaudiosrc.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/audio/gstaudioutilsprivate.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/audio/streamvolume.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/fft/gstfft.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/fft/gstfftf32.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/fft/kiss_fft_f32.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/fft/kiss_fftr_f32.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/pbutils/codec-utils.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/pbutils/descriptions.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/pbutils/gstpluginsbaseversion.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/pbutils/missing-plugins.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/pbutils/pbutils.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/pbutils/pbutils-enumtypes.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/riff/riff-media.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/riff/riff-read.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/riff/riff.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/tag/gstid3tag.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/tag/gsttagdemux.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/tag/id3v2.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/tag/id3v2frames.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/tag/lang.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/tag/tags.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/tag/tag-enumtypes.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/video/gstvideometa.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/video/gstvideopool.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/video/gstvideotimecode.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/video/video.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/video/video-chroma.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/video/video-color.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/video/video-converter.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/video/video-format.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/video/video-frame.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/video/video-hdr.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/video/video-enumtypes.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/video/video-info.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/video/video-multiview.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/video/video-orc-dist.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/video/video-tile.c"
        "${GST_SRC}/gst-plugins-good/gst/audiofx/audiofx.c"
        "${GST_SRC}/gst-plugins-good/gst/audiofx/audiopanorama.c"
        "${GST_SRC}/gst-plugins-good/gst/audiofx/audiopanoramaorc.c"
        "${GST_SRC}/gst-plugins-base/gst/audioconvert/plugin.c"
        "${GST_SRC}/gst-plugins-base/gst/audioconvert/gstaudioconvert.c"
        "${GST_SRC}/gst-plugins-bad/gst/aiff/aiff.c"
        "${GST_SRC}/gst-plugins-bad/gst/aiff/aiffparse.c"
        "${GST_SRC}/gst-plugins-bad/gst/aiff/gstaiffelement.c"
        "${GST_SRC}/gst-plugins-base/gst/app/gstapp.c"
        "${GST_SRC}/gst-plugins-base/gst/app/gstappsink.c"
        "${GST_SRC}/gst-plugins-base/gst/typefind/gsttypefindfunctions.c"
        "${GST_SRC}/gst-plugins-base/gst/typefind/gsttypefindfunctionsplugin.c"
        "${GST_SRC}/gst-plugins-good/gst/audioparsers/gstmpegaudioparse.c"
        "${GST_SRC}/gst-plugins-good/gst/audioparsers/gstaacparse.c"
        "${GST_SRC}/gst-plugins-good/gst/audioparsers/parsersplugin.c"
        "${GST_SRC}/gst-plugins-good/gst/equalizer/gstiirequalizer.c"
        "${GST_SRC}/gst-plugins-good/gst/equalizer/gstiirequalizernbands.c"
        "${GST_SRC}/gst-plugins-good/gst/equalizer/gstiirequalizerplugin.c"
        "${GST_SRC}/gst-plugins-good/gst/isomp4/isomp4-plugin.c"
        "${GST_SRC}/gst-plugins-good/gst/isomp4/gstisomp4element.c"
        "${GST_SRC}/gst-plugins-good/gst/isomp4/qtdemux-webvtt.c"
        "${GST_SRC}/gst-plugins-good/gst/isomp4/qtdemux.c"
        "${GST_SRC}/gst-plugins-good/gst/isomp4/gstisoff.c"
        "${GST_SRC}/gst-plugins-good/gst/isomp4/qtdemux_dump.c"
        "${GST_SRC}/gst-plugins-good/gst/isomp4/qtdemux_lang.c"
        "${GST_SRC}/gst-plugins-good/gst/isomp4/qtdemux_tags.c"
        "${GST_SRC}/gst-plugins-good/gst/isomp4/qtdemux_tree.c"
        "${GST_SRC}/gst-plugins-good/gst/isomp4/qtdemux_types.c"
        "${GST_SRC}/gst-plugins-good/gst/spectrum/gstspectrum.c"
        "${GST_SRC}/gst-plugins-good/gst/wavparse/gstwavparse.c"
        "${GST_SRC}/gstreamer/plugins/elements/gstcoreelementsplugin.c"
        "${GST_SRC}/gstreamer/plugins/elements/gstqueue.c"
        "${GST_SRC}/gstreamer/plugins/elements/gsttypefindelement.c"
        "${GST_SRC}/gst-plugins-base/gst/volume/gstvolume.c"
        "${GST_SRC}/gst-plugins-base/gst/volume/gstvolumeorc-dist.c"
        "${GST_SRC}/gst-plugins-base/ext/alsa/gstalsaplugin.c"
        "${GST_SRC}/gst-plugins-base/ext/alsa/gstalsa.c"
        "${GST_SRC}/gst-plugins-base/ext/alsa/gstalsadeviceprobe.c"
        "${GST_SRC}/gst-plugins-base/ext/alsa/gstalsasink.c"
        "${GST_SRC}/gst-plugins-base/ext/alsa/gstalsaelement.c"
        "${GST_SRC}/projects/plugins/gstplugins-lite.c"
    INCLUDE_DIRS
        "${PLUGINS_SRC}"
        "${GST_SRC}/projects/build/linux/common"
        "${GST_SRC}/gstreamer"
        "${GST_SRC}/gstreamer/libs"
        "${GST_SRC}/gst-plugins-base"
        "${GST_SRC}/gst-plugins-base/gst-libs"
        "${GST_SRC}/projects/plugins"
        "${GST_SRC}/gst-plugins-good/gst/isomp4"
    COMPILE_DEFINITIONS
        _GNU_SOURCE GST_REMOVE_DEPRECATED GSTREAMER_LITE HAVE_CONFIG_H
        OUTSIDE_SPEEX LINUX GST_DISABLE_GST_DEBUG GST_DISABLE_LOADSAVE
        ${JFXM_GLIB_DEFINITIONS}
    COMPILE_OPTIONS ${JFXM_C_STRICT_OPTIONS}
    LINK_LIBS m PkgConfig::JFXM_ALSA PkgConfig::JFXM_GLIB)

# ===========================================================================
# libfxplugins.so  (gstreamer/projects/linux/fxplugins/Makefile)
# ===========================================================================
add_media_library(fxplugins
    OUTPUT_NAME fxplugins
    SOURCES
        "${PLUGINS_SRC}/fxplugins.c"
        "${PLUGINS_SRC}/progressbuffer/progressbuffer.c"
        "${PLUGINS_SRC}/progressbuffer/hlsprogressbuffer.c"
        "${PLUGINS_SRC}/progressbuffer/posix/filecache.c"
        "${PLUGINS_SRC}/javasource/javasource.c"
        "${PLUGINS_SRC}/javasource/marshal.c"
    INCLUDE_DIRS
        "${PLUGINS_SRC}"
        "${PLUGINS_SRC}/progressbuffer"
        "${PLUGINS_SRC}/progressbuffer/posix"
        "${PLUGINS_SRC}/javasource"
        "${GST_SRC}/gstreamer"
        "${GST_SRC}/gstreamer/libs"
    COMPILE_DEFINITIONS
        HAVE_STDINT_H LINUX ENABLE_PULL_MODE ENABLE_SOURCE_SEEKING
        __MEDIALIB_OLD_NAMES GST_DISABLE_LOADSAVE GST_DISABLE_GST_DEBUG
        GSTREAMER_LITE ${JFXM_GLIB_DEFINITIONS}
    COMPILE_OPTIONS ${JFXM_C_STRICT_OPTIONS} -fbuiltin ${JFXM_SSE2_OPTIONS}
    LINK_LIBS gstreamerLite PkgConfig::JFXM_GLIB)

# ===========================================================================
# libavplugin.so  (gstreamer/projects/linux/avplugin/Makefile) - optional
# ===========================================================================
if(JFXM_LIBAV_FOUND)
    add_media_library(avplugin
        OUTPUT_NAME avplugin
        SOURCES
        "${PLUGINS_SRC}/av/fxavcodecplugin.c"
        "${PLUGINS_SRC}/av/avelement.c"
        "${PLUGINS_SRC}/av/decoder.c"
        "${PLUGINS_SRC}/av/audiodecoder.c"
        "${PLUGINS_SRC}/av/videodecoder.c"
        "${PLUGINS_SRC}/av/mpegtsdemuxer.c"
        INCLUDE_DIRS
            "${PLUGINS_SRC}"
            "${PLUGINS_SRC}/av"
            "${GST_SRC}/gstreamer"
            "${GST_SRC}/gstreamer/libs"
        COMPILE_DEFINITIONS
            HAVE_STDINT_H LINUX GST_DISABLE_LOADSAVE GSTREAMER_LITE
            ${JFXM_GLIB_DEFINITIONS}
        COMPILE_OPTIONS ${JFXM_C_STRICT_OPTIONS} -fbuiltin ${JFXM_SSE2_OPTIONS}
        LINK_LIBS gstreamerLite PkgConfig::JFXM_GLIB PkgConfig::JFXM_LIBAV)
else()
    message(STATUS "javafx.media: libavcodec/libavformat not found, skipping avplugin")
endif()

# ===========================================================================
# libjfxmedia.so  (jfxmedia/projects/linux/Makefile). Exports the jfxm_* C ABI
# of jfxmedia/jfxmedia_api.h; it needs no JDK headers.
# ===========================================================================
add_media_library(jfxmedia
    OUTPUT_NAME jfxmedia
    SOURCES
        "${JFXMEDIA_SRC}/jni/Logger.cpp"
        "${JFXMEDIA_SRC}/PipelineManagement/AudioTrack.cpp"
        "${JFXMEDIA_SRC}/PipelineManagement/Pipeline.cpp"
        "${JFXMEDIA_SRC}/PipelineManagement/PipelineFactory.cpp"
        "${JFXMEDIA_SRC}/PipelineManagement/Track.cpp"
        "${JFXMEDIA_SRC}/PipelineManagement/VideoFrame.cpp"
        "${JFXMEDIA_SRC}/PipelineManagement/VideoTrack.cpp"
        "${JFXMEDIA_SRC}/PipelineManagement/SubtitleTrack.cpp"
        "${JFXMEDIA_SRC}/MediaManagement/Media.cpp"
        "${JFXMEDIA_SRC}/MediaManagement/MediaManager.cpp"
        "${JFXMEDIA_SRC}/Locator/Locator.cpp"
        "${JFXMEDIA_SRC}/Locator/LocatorStream.cpp"
        "${JFXMEDIA_SRC}/Utils/posix/posix_critical_section.cpp"
        "${JFXMEDIA_SRC}/platform/gstreamer/GstAudioEqualizer.cpp"
        "${JFXMEDIA_SRC}/platform/gstreamer/GstAudioPlaybackPipeline.cpp"
        "${JFXMEDIA_SRC}/platform/gstreamer/GstAudioSpectrum.cpp"
        "${JFXMEDIA_SRC}/platform/gstreamer/GstAVPlaybackPipeline.cpp"
        "${JFXMEDIA_SRC}/platform/gstreamer/GstElementContainer.cpp"
        "${JFXMEDIA_SRC}/platform/gstreamer/GstMediaManager.cpp"
        "${JFXMEDIA_SRC}/platform/gstreamer/GstPipelineFactory.cpp"
        "${JFXMEDIA_SRC}/platform/gstreamer/GstVideoFrame.cpp"
        "${JFXMEDIA_SRC}/Utils/ColorConverter.c"
        "${JFXMEDIA_SRC}/ffi/jfxmedia_api.cpp"
        "${JFXMEDIA_SRC}/ffi/FfiPlayerEventDispatcher.cpp"
        "${JFXMEDIA_SRC}/ffi/FfiStreamCallbacks.cpp"
        "${JFXMEDIA_SRC}/ffi/FfiBandsHolder.cpp"
    INCLUDE_DIRS
        "${JFXMEDIA_SRC}"
        "${JFXMEDIA_SRC}/ffi"
        "${HEADERS_DIR}"
        "${GST_SRC}/gstreamer"
        "${GST_SRC}/gst-plugins-base/gst-libs"
        "${GST_SRC}/gstreamer/libs"
        "${PLUGINS_SRC}"
    COMPILE_DEFINITIONS
        TARGET_OS_LINUX=1 _GNU_SOURCE GST_REMOVE_DEPRECATED GST_DISABLE_GST_DEBUG
        GST_DISABLE_LOADSAVE GST_DISABLE_XML HAVE_CONFIG_H
        LINUX GSTREAMER_LITE ${JFXM_GLIB_DEFINITIONS}
    COMPILE_OPTIONS
        ${JFXM_SSE2_OPTIONS}
        "$<$<COMPILE_LANGUAGE:CXX>:-fno-rtti>"
    LINK_LIBS gstreamerLite PkgConfig::JFXM_GLIB
    LINK_OPTIONS "LINKER:-rpath,$ORIGIN")
