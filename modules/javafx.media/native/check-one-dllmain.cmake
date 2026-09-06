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

# Post-link assertion for gstreamer-lite.dll: the DLL must contain exactly one
# DllMain, and it must be the deliberate no-op in
# gst-plugins-good/sys/directsound/gstdirectsoundnotify.cpp.
#
# That no-op exists so that a second DllMain fails the link with LNK2005 naming
# the file, forcing whoever adds one to read why: neither the wait in
# GSTDirectSoundNotify::Dispose() nor the one in Init() may run under the loader
# lock, and a DllMain that reached them would deadlock the process. This check
# covers the remaining case the linker does not - a future toolchain that
# resolved a second definition without erroring, or a build that dropped the
# no-op and let the CRT default back in, either of which removes the guard
# silently.
#
# What no check inside this library can cover: a DllMain in some OTHER module of
# the process that reaches gst_directsound_sink_finalize. The loader lock is
# process-wide, so that deadlocks just as surely and is out of reach from here.
#
# Run by win.cmake as:
#   cmake -DMAP_FILE=<...>/gstreamer-lite.map -P check-one-dllmain.cmake

if(NOT DEFINED MAP_FILE)
    message(FATAL_ERROR "check-one-dllmain.cmake: -DMAP_FILE=<gstreamer-lite.map> is required")
endif()

if(NOT EXISTS "${MAP_FILE}")
    message(FATAL_ERROR
        "check-one-dllmain.cmake: no linker map at ${MAP_FILE}. "
        "add_media_library passes /map: to every DLL; restore it, or this guard cannot run.")
endif()

# A public-symbol line in an MSVC map reads
#   0001:0001380c       DllMain      000000018001480c f   libgstplugins:gstdirectsoundnotify.obj
# Whitespace on both sides of the name keeps out _DllMainCRTStartup, _pRawDllMain
# and $unwind$_DllMainCRTStartup; the "f" flag keeps out data symbols.
file(STRINGS "${MAP_FILE}" JFXM_DLLMAIN_LINES
    REGEX "[ \t]DllMain[ \t]+[0-9a-fA-F]+[ \t]+f[ \t]")
list(LENGTH JFXM_DLLMAIN_LINES JFXM_DLLMAIN_COUNT)

if(JFXM_DLLMAIN_COUNT EQUAL 0)
    message(FATAL_ERROR
        "gstreamer-lite defines no DllMain of its own. The no-op in "
        "gst-plugins-good/sys/directsound/gstdirectsoundnotify.cpp is a link-time assertion: "
        "without it the CRT supplies the default DllMain, and a second, real one can then be "
        "added with nothing to collide with. Restore it, or delete this check deliberately.")
elseif(NOT JFXM_DLLMAIN_COUNT EQUAL 1)
    string(REPLACE ";" "\n    " JFXM_DLLMAIN_TEXT "${JFXM_DLLMAIN_LINES}")
    message(FATAL_ERROR
        "gstreamer-lite links ${JFXM_DLLMAIN_COUNT} DllMain functions:\n    ${JFXM_DLLMAIN_TEXT}\n"
        "A DLL runs exactly one, under the loader lock, and nothing reachable from it may wait "
        "on a thread or take a lock a loading thread holds. Read the comment at the top of "
        "gstdirectsoundnotify.cpp before adding one.")
endif()

if(NOT JFXM_DLLMAIN_LINES MATCHES "libgstplugins:gstdirectsoundnotify[.]obj")
    message(FATAL_ERROR
        "gstreamer-lite's single DllMain does not come from "
        "libgstplugins:gstdirectsoundnotify.obj:\n    ${JFXM_DLLMAIN_LINES}\n"
        "The guard is only meaningful where its rationale is written down.")
endif()

message(STATUS "gstreamer-lite: exactly one DllMain, from gstdirectsoundnotify.obj")
