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


# macOS-specific portion of the javafx.media native build. Transliterated from
# the retired GNU make projects src/main/native/jfxmedia/projects/mac/Makefile
# and src/main/native/gstreamer/projects/mac/{libffi,glib-lite,gstreamer-lite,
# fxplugins}/Makefile (driven by the Gradle COMPILE_MEDIA=true build through
# buildSrc/mac.gradle): same source lists, defines, include directories,
# warning/optimisation flags and link libraries. Included from CMakeLists.txt;
# the shared inputs (GENSRC_DIR, HEADERS_DIR, BIN_DIR and the *_SRC source roots)
# are defined there.
#
# Notes on makefile parity:
#   - jfxmedia/projects/mac/Makefile builds TWO products, libjfxmedia.dylib and
#     libjfxmedia_avf.dylib; the second links -ljfxmedia and is therefore built
#     after the first (targets jfxmedia and jfxmediaAvf below).
#   - libffi is a static archive that only glib-lite links (-lffi), exactly as
#     on Windows, so it is the STATIC target glibLiteFfi. It is also the only
#     target with .S sources, hence enable_language(ASM). The makefile archived
#     with "libtool -static"; CMake uses ar/ranlib, which produces the same
#     archive content.
#   - Gradle compiled with clang and linked with clang++ (buildSrc/mac.gradle
#     MAC.media.compiler / MAC.media.linker); forcing LINKER_LANGUAGE CXX below
#     matches that. The media flags carried no -std=c99 (that was graphics
#     only), so the compiler default applies, as before.
#   - The -mmacosx-version-min / -isysroot / -iframework / -arch flags Gradle
#     folded into MAC.media.compiler and MAC.media.linker are covered by
#     CMAKE_OSX_DEPLOYMENT_TARGET (11.0 in CMakeLists.txt, the same value as
#     Gradle's MACOSX_MIN_VERSION; the media makefiles set no minimum of their
#     own) and CMake's Apple SDK handling. Universal (lipo) builds, the parfait
#     toolchain and IS_STATIC_BUILD are not ported, as in javafx.graphics.
#   - Gradle folded the JDK include directories into MAC.media.compiler, so
#     every media library saw the JNI headers. No media source includes
#     <jni.h> any more, so no target here needs them.
#   - -msse2: the makefiles guard it with ifneq ($(ARCH), arm64), but Gradle
#     passed ARCH=${ARCH_NAME} ("aarch64") to the jfxmedia, gstreamer-lite and
#     fxplugins makefiles and ARCH=${TARGET_ARCH} ("arm64") to libffi and
#     glib-lite, so on Apple silicon the first three were compiled with -msse2
#     for an arm64 target. Here the flag follows the real target architecture.
#   - -Werror=implicit-function-declaration (every makefile) and -fobjc-arc
#     (the avf half of the jfxmedia makefile) are passed to every language by
#     the makefiles; they are restricted here to the languages they apply to
#     (C/ObjC and ObjC/ObjC++), which is what clang does with them anyway.
#   - The gstreamer-lite makefile hardcodes -I<gstreamer-lite>/projects/build/
#     osx/common/x86_64 (its config.h) in the compile rule for every
#     architecture; there is no aarch64 variant in the tree, so that include
#     directory is kept verbatim for both.
#   - gst-plugins-base/gst-libs/gst/interfaces, listed in the gstreamer-lite
#     DIRLIST, does not exist in the tree and is omitted, exactly as linux.cmake
#     omits its three stale include directories.
#   - -Wl,-install_name,@rpath/lib<name>.dylib is reproduced with
#     INSTALL_NAME_DIR plus BUILD_WITH_INSTALL_NAME_DIR, so the dylibs carry the
#     @rpath install name in the build tree and not only after an install step
#     (there is none: sdk/pom.xml copies the files as they are built).
#     CMake's build-tree RPATH is left enabled on top of that; the makefiles
#     recorded no LC_RPATH at all and relied on NativeMediaManager loading
#     glib-lite and gstreamer-lite before jfxmedia.
#   - The makefile's default rule asserted that neither dylib links QTKit or
#     QuickTime; that assertion is ported as a POST_BUILD nm check.
#   - Gradle stripped the dylibs only while copying them into the SDK image, so
#     the libraries are left unstripped here, like the javafx.graphics port.

enable_language(OBJC)
enable_language(OBJCXX)
enable_language(ASM) # libffi's src/<arch>/*.S

# ---------------------------------------------------------------------------
# Global flags: the makefiles set every flag explicitly, so CMake's defaults
# are cleared exactly as the javafx.graphics port does.
# ---------------------------------------------------------------------------
foreach(lang C CXX OBJC OBJCXX ASM)
    set(CMAKE_${lang}_FLAGS "")
    set(CMAKE_${lang}_FLAGS_RELEASE "")
    set(CMAKE_${lang}_FLAGS_DEBUG "")
endforeach()
set(CMAKE_SHARED_LINKER_FLAGS "")
set(CMAKE_SHARED_LINKER_FLAGS_RELEASE "")
set(CMAKE_SHARED_LINKER_FLAGS_DEBUG "")

# The architecture the makefiles selected sources and flags with (make ARCH=).
set(JFXM_TARGET_ARCH "${CMAKE_SYSTEM_PROCESSOR}")
if(CMAKE_OSX_ARCHITECTURES)
    list(GET CMAKE_OSX_ARCHITECTURES 0 JFXM_TARGET_ARCH)
endif()

# CFLAGS common to all five makefiles: -fPIC, the implicit-declaration error
# and the Release/Debug optimisation split (-Os vs -O0 -g -Wall)
set(JFXM_COMMON_COMPILE_OPTIONS
    -fPIC
    "$<$<OR:$<COMPILE_LANGUAGE:C>,$<COMPILE_LANGUAGE:OBJC>>:-Werror=implicit-function-declaration>"
    "$<$<CONFIG:Release>:-Os>"
    "$<$<NOT:$<CONFIG:Release>>:-O0;-g;-Wall>")

# -msse2 on Intel only (see the note on ARCH above); glib-lite and libffi never
# set it, the other three do.
set(JFXM_SSE2_OPTIONS)
if(JFXM_TARGET_ARCH MATCHES "^(x86_64|amd64|AMD64|i[3-6]86)$")
    set(JFXM_SSE2_OPTIONS -msse2)
endif()

# jfxmedia/projects/mac/Makefile LDFLAGS, shared by libjfxmedia and
# libjfxmedia_avf (-lobjc -framework Cocoa -framework CoreVideo)
set(JFXM_BASE_LINK_LIBS objc "-framework Cocoa" "-framework CoreVideo")

# The glib and gstreamer include directories every media library repeats
set(JFXM_GLIB_INCLUDE_DIRS
    "${GLIB_SRC}"
    "${GLIB_SRC}/glib"
    "${GLIB_SRC}/gmodule"
    "${GLIB_SRC}/build/osx")

# ---------------------------------------------------------------------------
# add_media_archive(<name>          # one libtool -static archive of the makefiles
#     OUTPUT_NAME <lib base name>   # produces lib<OUTPUT_NAME>.a
#     SOURCES <files...>            # explicit, exactly as the makefile listed
#     INCLUDE_DIRS <dirs...>
#     COMPILE_DEFINITIONS <defs...>
#     COMPILE_OPTIONS <opts...>
# )
# ---------------------------------------------------------------------------
function(add_media_archive name)
    cmake_parse_arguments(MEDIA "" "OUTPUT_NAME"
        "SOURCES;INCLUDE_DIRS;COMPILE_DEFINITIONS;COMPILE_OPTIONS" ${ARGN})

    add_library(${name} STATIC ${MEDIA_SOURCES})
    set_target_properties(${name} PROPERTIES OUTPUT_NAME "${MEDIA_OUTPUT_NAME}")
    target_include_directories(${name} PRIVATE ${MEDIA_INCLUDE_DIRS})
    target_compile_definitions(${name} PRIVATE ${MEDIA_COMPILE_DEFINITIONS})
    target_compile_options(${name} PRIVATE
        ${JFXM_COMMON_COMPILE_OPTIONS} ${MEDIA_COMPILE_OPTIONS})
endfunction()

# ---------------------------------------------------------------------------
# add_media_library(<name>
#     OUTPUT_NAME <dylib base name>  # produces lib<OUTPUT_NAME>.dylib
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
        MACOSX_RPATH TRUE
        INSTALL_NAME_DIR "@rpath"
        BUILD_WITH_INSTALL_NAME_DIR TRUE
        LIBRARY_OUTPUT_DIRECTORY "${BIN_DIR}"
        LIBRARY_OUTPUT_DIRECTORY_RELEASE "${BIN_DIR}"
        LIBRARY_OUTPUT_DIRECTORY_DEBUG "${BIN_DIR}")

    target_compile_definitions(${name} PRIVATE ${MEDIA_COMPILE_DEFINITIONS})
    target_compile_options(${name} PRIVATE
        ${JFXM_COMMON_COMPILE_OPTIONS} ${MEDIA_COMPILE_OPTIONS})
    target_include_directories(${name} PRIVATE ${MEDIA_INCLUDE_DIRS})
    target_link_libraries(${name} PRIVATE ${MEDIA_LINK_LIBS})
    target_link_options(${name} PRIVATE ${MEDIA_LINK_OPTIONS})
endfunction()

# ---------------------------------------------------------------------------
# The jfxmedia makefile's default rule ran, for both dylibs:
#     ! nm -m <lib> | grep -E "(QTKit|QuickTime)"
# ---------------------------------------------------------------------------
function(add_no_quicktime_check name)
    add_custom_command(TARGET ${name} POST_BUILD
        COMMAND sh -c "! nm -m \"$0\" | grep -E '(QTKit|QuickTime)'" "$<TARGET_FILE:${name}>"
        VERBATIM
        COMMENT "Ensuring $<TARGET_FILE_NAME:${name}> does not link against QuickTime")
endfunction()

# ===========================================================================
# libffi.a  (gstreamer/projects/mac/libffi/Makefile, built with BASE_NAME=ffi)
# Only glib-lite links it (-L$(BUILD_DIR) -lffi), so it stays a static archive.
# ===========================================================================
if(JFXM_TARGET_ARCH MATCHES "^(arm64|aarch64)$")
    set(FFI_ARCH_DEFINITION AARCH64)
    set(FFI_ARCH_INCLUDE_DIRS
        "${LIBFFI_SRC}/src/aarch64"
        "${LIBFFI_SRC}/include/mac/aarch64")
    set(FFI_ARCH_SOURCES
        "${LIBFFI_SRC}/src/aarch64/ffi.c"
        "${LIBFFI_SRC}/src/aarch64/sysv.S")
else()
    set(FFI_ARCH_DEFINITION X86_64)
    set(FFI_ARCH_INCLUDE_DIRS
        "${LIBFFI_SRC}/src/x86"
        "${LIBFFI_SRC}/include/mac/x64")
    set(FFI_ARCH_SOURCES
        "${LIBFFI_SRC}/src/x86/ffi64.c"
        "${LIBFFI_SRC}/src/x86/ffiw64.c"
        "${LIBFFI_SRC}/src/x86/unix64.S"
        "${LIBFFI_SRC}/src/x86/win64.S")
endif()

add_media_archive(glibLiteFfi
    OUTPUT_NAME ffi
    SOURCES
        "${LIBFFI_SRC}/src/closures.c"
        "${LIBFFI_SRC}/src/java_raw_api.c"
        "${LIBFFI_SRC}/src/prep_cif.c"
        "${LIBFFI_SRC}/src/raw_api.c"
        "${LIBFFI_SRC}/src/types.c"
        ${FFI_ARCH_SOURCES}
    INCLUDE_DIRS
        "${LIBFFI_SRC}/include"
        ${FFI_ARCH_INCLUDE_DIRS}
    COMPILE_DEFINITIONS GSTREAMER_LITE ${FFI_ARCH_DEFINITION})

# ===========================================================================
# libglib-lite.dylib  (gstreamer/projects/mac/glib-lite/Makefile)
# ===========================================================================
add_media_library(glibLite
    OUTPUT_NAME glib-lite
    SOURCES
        "${GLIB_SRC}/glib/garcbox.c"
        "${GLIB_SRC}/glib/garray.c"
        "${GLIB_SRC}/glib/gasyncqueue.c"
        "${GLIB_SRC}/glib/gatomic.c"
        "${GLIB_SRC}/glib/gbacktrace.c"
        "${GLIB_SRC}/glib/gbase64.c"
        "${GLIB_SRC}/glib/gbytes.c"
        "${GLIB_SRC}/glib/gbitlock.c"
        "${GLIB_SRC}/glib/gcharset.c"
        "${GLIB_SRC}/glib/gchecksum.c"
        "${GLIB_SRC}/glib/gconvert.c"
        "${GLIB_SRC}/glib/gdataset.c"
        "${GLIB_SRC}/glib/gdate.c"
        "${GLIB_SRC}/glib/gdatetime.c"
        "${GLIB_SRC}/glib/gdatetime-private.c"
        "${GLIB_SRC}/glib/gdir.c"
        "${GLIB_SRC}/glib/gerror.c"
        "${GLIB_SRC}/glib/genviron.c"
        "${GLIB_SRC}/glib/ghmac.c"
        "${GLIB_SRC}/glib/gfileutils.c"
        "${GLIB_SRC}/glib/ghash.c"
        "${GLIB_SRC}/glib/ghook.c"
        "${GLIB_SRC}/glib/giochannel.c"
        "${GLIB_SRC}/glib/giounix.c"
        "${GLIB_SRC}/glib/glib-init.c"
        "${GLIB_SRC}/glib/glib-private.c"
        "${GLIB_SRC}/glib/glist.c"
        "${GLIB_SRC}/glib/gmain.c"
        "${GLIB_SRC}/glib/gmappedfile.c"
        "${GLIB_SRC}/glib/gmarkup.c"
        "${GLIB_SRC}/glib/gmem.c"
        "${GLIB_SRC}/glib/gmessages.c"
        "${GLIB_SRC}/glib/ggettext.c"
        "${GLIB_SRC}/glib/gnode.c"
        "${GLIB_SRC}/glib/goption.c"
        "${GLIB_SRC}/glib/gpattern.c"
        "${GLIB_SRC}/glib/gpoll.c"
        "${GLIB_SRC}/glib/gprimes.c"
        "${GLIB_SRC}/glib/gprintf.c"
        "${GLIB_SRC}/glib/gqsort.c"
        "${GLIB_SRC}/glib/gquark.c"
        "${GLIB_SRC}/glib/gqueue.c"
        "${GLIB_SRC}/glib/grand.c"
        "${GLIB_SRC}/glib/grcbox.c"
        "${GLIB_SRC}/glib/grefcount.c"
        "${GLIB_SRC}/glib/gscanner.c"
        "${GLIB_SRC}/glib/gsequence.c"
        "${GLIB_SRC}/glib/gspawn.c"
        "${GLIB_SRC}/glib/gspawn-posix.c"
        "${GLIB_SRC}/glib/gshell.c"
        "${GLIB_SRC}/glib/gslice.c"
        "${GLIB_SRC}/glib/gslist.c"
        "${GLIB_SRC}/glib/gstdio.c"
        "${GLIB_SRC}/glib/gstrfuncs.c"
        "${GLIB_SRC}/glib/gstring.c"
        "${GLIB_SRC}/glib/gstringchunk.c"
        "${GLIB_SRC}/glib/gtestutils.c"
        "${GLIB_SRC}/glib/gthread.c"
        "${GLIB_SRC}/glib/gthreadpool.c"
        "${GLIB_SRC}/glib/gtimer.c"
        "${GLIB_SRC}/glib/gtimezone.c"
        "${GLIB_SRC}/glib/gtrashstack.c"
        "${GLIB_SRC}/glib/gtranslit.c"
        "${GLIB_SRC}/glib/gtree.c"
        "${GLIB_SRC}/glib/gunibreak.c"
        "${GLIB_SRC}/glib/gunidecomp.c"
        "${GLIB_SRC}/glib/guri.c"
        "${GLIB_SRC}/glib/gutf8.c"
        "${GLIB_SRC}/glib/gutils.c"
        "${GLIB_SRC}/glib/ghostutils.c"
        "${GLIB_SRC}/glib/gvarianttype.c"
        "${GLIB_SRC}/glib/gvariant.c"
        "${GLIB_SRC}/glib/gvariant-core.c"
        "${GLIB_SRC}/glib/gvariant-serialiser.c"
        "${GLIB_SRC}/glib/gvarianttypeinfo.c"
        "${GLIB_SRC}/glib/gwakeup.c"
        "${GLIB_SRC}/glib/glib-unix.c"
        "${GLIB_SRC}/glib/libcharset/localcharset.c"
        "${GLIB_SRC}/glib/gnulib/asnprintf.c"
        "${GLIB_SRC}/glib/gnulib/printf-args.c"
        "${GLIB_SRC}/glib/gnulib/printf-parse.c"
        "${GLIB_SRC}/glib/gnulib/printf.c"
        "${GLIB_SRC}/glib/gnulib/vasnprintf.c"
        "${GLIB_SRC}/gobject/gatomicarray.c"
        "${GLIB_SRC}/gobject/gboxed.c"
        "${GLIB_SRC}/gobject/gclosure.c"
        "${GLIB_SRC}/gobject/genums.c"
        "${GLIB_SRC}/gobject/gobject.c"
        "${GLIB_SRC}/gobject/gparam.c"
        "${GLIB_SRC}/gobject/gparamspecs.c"
        "${GLIB_SRC}/gobject/gsignal.c"
        "${GLIB_SRC}/gobject/gsourceclosure.c"
        "${GLIB_SRC}/gobject/gtype.c"
        "${GLIB_SRC}/gobject/gtypemodule.c"
        "${GLIB_SRC}/gobject/gtypeplugin.c"
        "${GLIB_SRC}/gobject/gvalue.c"
        "${GLIB_SRC}/gobject/gvaluearray.c"
        "${GLIB_SRC}/gobject/gvaluetransform.c"
        "${GLIB_SRC}/gobject/gvaluetypes.c"
        "${GLIB_SRC}/gobject/gmarshal.c"
        "${GLIB_SRC}/gthread/gthread-impl.c"
        "${GLIB_SRC}/gmodule/gmodule-deprecated.c"
        "${GLIB_SRC}/gmodule/gmodule.c"
    INCLUDE_DIRS
        "${GLIB_SRC}"
        "${GLIB_SRC}/glib"
        "${GLIB_SRC}/build/osx"
        "${LIBFFI_SRC}/src"
        "${LIBFFI_SRC}/include"
        ${FFI_ARCH_INCLUDE_DIRS}
    COMPILE_DEFINITIONS
        G_DISABLE_CAST_CHECKS GLIB_COMPILATION GOBJECT_COMPILATION
        "LIBDIR=\"/irrelevant/lib\"" "G_LOG_DOMAIN=\"GLib\""
        GSTREAMER_LITE G_DISABLE_DEPRECATED G_DISABLE_ASSERT
        LINK_SIZE=2 MAX_NAME_SIZE=32 MAX_NAME_COUNT=10000 NEWLINE=-1
        POSIX_MALLOC_THRESHOLD=10 MATCH_LIMIT=10000000
        MATCH_LIMIT_RECURSION=10000000
    LINK_LIBS glibLiteFfi iconv "-framework CoreServices")

# ===========================================================================
# libgstreamer-lite.dylib  (gstreamer/projects/mac/gstreamer-lite/Makefile)
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
        "${GST_SRC}/gst-plugins-good/sys/osxaudio/gstosxaudio.c"
        "${GST_SRC}/gst-plugins-good/sys/osxaudio/gstosxaudioelement.c"
        "${GST_SRC}/gst-plugins-good/sys/osxaudio/gstosxaudiosink.c"
        "${GST_SRC}/gst-plugins-good/sys/osxaudio/gstosxaudiosrc.c"
        "${GST_SRC}/gst-plugins-good/sys/osxaudio/gstosxaudioringbuffer.c"
        "${GST_SRC}/gst-plugins-good/sys/osxaudio/gstosxcoreaudio.c"
        "${GST_SRC}/gst-plugins-good/sys/osxaudio/gstosxcoreaudiocommon.c"
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
        "${GST_SRC}/projects/plugins/gstplugins-lite.c"
    INCLUDE_DIRS
        # The makefile's compile rule puts this (its config.h) ahead of every
        # other -I, on both architectures; there is no aarch64 variant.
        "${GST_SRC}/projects/build/osx/common/x86_64"
        "${GST_SRC}/gstreamer/gst"
        "${GST_SRC}/gstreamer/libs/gst/base"
        "${GST_SRC}/gstreamer/libs/gst/controller"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/audio"
        # omitted (not in the tree): ${GST_SRC}/gst-plugins-base/gst-libs/gst/interfaces
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/video"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/pbutils"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/tag"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/riff"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/fft"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/app"
        "${GST_SRC}/projects/plugins"
        "${GST_SRC}/gstreamer/plugins/elements"
        "${GST_SRC}/gst-plugins-base/gst/typefind"
        "${GST_SRC}/gst-plugins-base/gst/audioconvert"
        "${GST_SRC}/gst-plugins-good/gst/audioparsers"
        "${GST_SRC}/gst-plugins-good/gst/isomp4"
        "${GST_SRC}/gst-plugins-good/gst/audiofx"
        "${GST_SRC}/gst-plugins-good/gst/equalizer"
        "${GST_SRC}/gst-plugins-good/gst/spectrum"
        "${GST_SRC}/gst-plugins-good/gst/wavparse"
        "${GST_SRC}/gst-plugins-bad/gst/aiff"
        "${GST_SRC}/gst-plugins-base/gst/app"
        "${GST_SRC}/gst-plugins-good/sys/osxaudio"
        "${PLUGINS_SRC}"
        "${GST_SRC}/projects/plugins"
        "${GST_SRC}/gstreamer"
        "${GST_SRC}/gstreamer/libs"
        "${GST_SRC}/gst-plugins-base"
        "${GST_SRC}/gst-plugins-base/gst-libs"
        ${JFXM_GLIB_INCLUDE_DIRS}
    COMPILE_DEFINITIONS
        _GNU_SOURCE GST_REMOVE_DEPRECATED GST_DISABLE_GST_DEBUG
        GST_DISABLE_LOADSAVE G_DISABLE_DEPRECATED G_DISABLE_ASSERT
        HAVE_CONFIG_H GSTREAMER_LITE GST_REMOVE_DISABLED OSX
    COMPILE_OPTIONS ${JFXM_SSE2_OPTIONS}
    LINK_LIBS glibLite
        "-framework CoreAudio"
        "-framework AudioUnit"
        "-framework CoreServices"
        "-framework AudioToolbox")

# ===========================================================================
# libfxplugins.dylib  (gstreamer/projects/mac/fxplugins/Makefile)
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
        "${PLUGINS_SRC}/progressbuffer"
        "${PLUGINS_SRC}/progressbuffer/posix"
        "${PLUGINS_SRC}/javasource"
        "${PLUGINS_SRC}"
        "${GST_SRC}/gstreamer"
        "${GST_SRC}/gstreamer/libs"
        ${JFXM_GLIB_INCLUDE_DIRS}
    COMPILE_DEFINITIONS
        ENABLE_SOURCE_SEEKING=1 ENABLE_PULL_MODE=1 GST_DISABLE_GST_DEBUG
        GST_DISABLE_LOADSAVE HAVE_STDINT_H GSTREAMER_LITE G_DISABLE_DEPRECATED
        OSX
    COMPILE_OPTIONS ${JFXM_SSE2_OPTIONS}
    LINK_LIBS gstreamerLite glibLite)

# ===========================================================================
# libjfxmedia.dylib  (jfxmedia/projects/mac/Makefile, first product). Exports
# the jfxm_* C ABI of jfxmedia/jfxmedia_api.h; the four ffi/*.cpp sources are
# its entry points, listed here exactly as in win.cmake and linux.cmake.
# ===========================================================================
add_media_library(jfxmedia
    OUTPUT_NAME jfxmedia
    SOURCES
        "${JFXMEDIA_SRC}/MediaManagement/Media.cpp"
        "${JFXMEDIA_SRC}/MediaManagement/MediaManager.cpp"
        "${JFXMEDIA_SRC}/Locator/Locator.cpp"
        "${JFXMEDIA_SRC}/Locator/LocatorStream.cpp"
        "${JFXMEDIA_SRC}/PipelineManagement/Pipeline.cpp"
        "${JFXMEDIA_SRC}/PipelineManagement/PipelineFactory.cpp"
        "${JFXMEDIA_SRC}/PipelineManagement/VideoFrame.cpp"
        "${JFXMEDIA_SRC}/PipelineManagement/Track.cpp"
        "${JFXMEDIA_SRC}/PipelineManagement/AudioTrack.cpp"
        "${JFXMEDIA_SRC}/PipelineManagement/VideoTrack.cpp"
        "${JFXMEDIA_SRC}/PipelineManagement/SubtitleTrack.cpp"
        "${JFXMEDIA_SRC}/jni/Logger.cpp"
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
        "${JFXMEDIA_SRC}/platform/osx/OSXPlatform.mm"
        "${JFXMEDIA_SRC}/platform/osx/OSXMediaPlayer.mm"
        "${JFXMEDIA_SRC}/platform/osx/CVVideoFrame.mm"
        "${JFXMEDIA_SRC}/ffi/jfxmedia_api.cpp"
        "${JFXMEDIA_SRC}/ffi/FfiPlayerEventDispatcher.cpp"
        "${JFXMEDIA_SRC}/ffi/FfiStreamCallbacks.cpp"
        "${JFXMEDIA_SRC}/ffi/FfiBandsHolder.cpp"
    INCLUDE_DIRS
        "${JFXMEDIA_SRC}"
        "${JFXMEDIA_SRC}/jni"
        "${JFXMEDIA_SRC}/ffi"
        "${HEADERS_DIR}"
        ${JFXM_GLIB_INCLUDE_DIRS}
        "${GST_SRC}/gstreamer"
        "${GST_SRC}/gst-plugins-base/gst-libs"
        "${GST_SRC}/gstreamer/libs"
        "${PLUGINS_SRC}"
    COMPILE_DEFINITIONS
        TARGET_OS_MAC=1 _GNU_SOURCE GST_REMOVE_DEPRECATED GST_DISABLE_GST_DEBUG
        GST_DISABLE_LOADSAVE GST_DISABLE_XML G_DISABLE_DEPRECATED GSTREAMER_LITE
        HAVE_CONFIG_H
    COMPILE_OPTIONS -pipe ${JFXM_SSE2_OPTIONS}
    LINK_LIBS gstreamerLite glibLite ${JFXM_BASE_LINK_LIBS})

add_no_quicktime_check(jfxmedia)

# ===========================================================================
# libjfxmedia_avf.dylib  (jfxmedia/projects/mac/Makefile, second product).
# Links -ljfxmedia, so it is built after it.
# ===========================================================================
add_media_library(jfxmediaAvf
    OUTPUT_NAME jfxmedia_avf
    SOURCES
        "${JFXMEDIA_SRC}/platform/osx/avf/AVFMediaPlayer.mm"
        "${JFXMEDIA_SRC}/platform/osx/avf/AVFAudioProcessor.mm"
        "${JFXMEDIA_SRC}/platform/osx/avf/AVFAudioEqualizer.cpp"
        "${JFXMEDIA_SRC}/platform/osx/avf/AVFAudioSpectrumUnit.cpp"
        "${JFXMEDIA_SRC}/platform/osx/avf/AVFSoundLevelUnit.cpp"
    INCLUDE_DIRS
        "${JFXMEDIA_SRC}"
        "${JFXMEDIA_SRC}/jni"
        "${HEADERS_DIR}"
        "${JFXMEDIA_SRC}/platform/osx"
        ${JFXM_GLIB_INCLUDE_DIRS}
        "${GST_SRC}/gstreamer"
        "${GST_SRC}/gst-plugins-base/gst-libs"
        "${GST_SRC}/gstreamer/libs"
        # gstspectrum.h, whose GstSpectrum extension is guarded by
        # GSTREAMER_LITE && OSX: both this target and gstreamerLite define OSX,
        # so the two dylibs agree on the struct layout.
        "${GST_SRC}/gst-plugins-good/gst/spectrum"
    COMPILE_DEFINITIONS
        TARGET_OS_MAC=1 _GNU_SOURCE
        CA_AU_USE_FAST_DISPATCH=1 CA_BASIC_AU_FEATURES=1
        CA_NO_AU_HOST_CALLBACKS=1 CA_NO_AU_UI_FEATURES=1
        CA_USE_AUDIO_PLUGIN_ONLY=1
        GST_REMOVE_DEPRECATED GST_DISABLE_GST_DEBUG GST_DISABLE_LOADSAVE
        GST_DISABLE_XML G_DISABLE_DEPRECATED GSTREAMER_LITE OSX HAVE_CONFIG_H
    COMPILE_OPTIONS -pipe ${JFXM_SSE2_OPTIONS}
        "$<$<OR:$<COMPILE_LANGUAGE:OBJC>,$<COMPILE_LANGUAGE:OBJCXX>>:-fobjc-arc>"
    LINK_LIBS jfxmedia gstreamerLite glibLite ${JFXM_BASE_LINK_LIBS}
        "-framework AVFoundation"
        "-framework CoreMedia"
        "-framework Accelerate"
        "-framework AudioUnit"
        "-framework MediaToolbox")

add_no_quicktime_check(jfxmediaAvf)
