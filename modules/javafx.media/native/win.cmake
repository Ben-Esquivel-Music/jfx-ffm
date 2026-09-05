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

# Windows-specific portion of the javafx.media native build. Transliterates the
# retired GNU make projects src/main/native/jfxmedia/projects/win/Makefile and
# src/main/native/gstreamer/projects/win/{glib-lite,gstreamer-lite,fxplugins}/
# Makefile* (driven by the Gradle COMPILE_MEDIA=true build through
# buildSrc/win.gradle): same source lists, defines, include directories,
# warning/optimisation flags, link libraries and export mechanism. Included from
# CMakeLists.txt; the shared inputs (GENSRC_DIR, HEADERS_DIR, BIN_DIR, the *_SRC
# source roots and JFX_VERSION_RC) are defined there.
#
# Each makefile sub-project that produced a lib.exe archive (libffi.lib,
# libglib.lib, libmodule.lib, libgobject.lib, libgthread.lib, libgstreamer.lib,
# libgstplugins.lib, baseclasses.lib) is a STATIC target with its own flags
# (add_media_archive) and the DLL targets (add_media_library) link those
# archives exactly as link.exe did, so only the objects the .def exports and
# the DLL's own code reference are pulled in.
#
# Deliberately not carried over: the SOURCE_DATE_EPOCH-conditional
# /experimental:deterministic flag (the graphics port omits it too), the
# -manifestfile: path (MSBuild embeds the manifest instead of writing it next
# to the DLL) and -libpath:strmiids.lib, which the Debug lib.exe steps passed
# but which has no effect on an archive.

enable_language(RC)

# ---------------------------------------------------------------------------
# Windows-only inputs from Maven (version resource defines, as in graphics)
# ---------------------------------------------------------------------------
set(JFX_VER "28" CACHE STRING "JavaFX release version")
set(JFX_FVER "28,0,0,0" CACHE STRING "JavaFX file version quad")
set(JFX_BUILD_ID "28-internal" CACHE STRING "JavaFX build identifier")
set(JFX_COMPANY "N/A" CACHE STRING "Company name embedded in version resources")
set(JFX_PRODUCT "OpenJFX" CACHE STRING "Product name embedded in version resources")

string(TIMESTAMP JFX_COPYRIGHT_YEAR "%Y")

# ml64.exe lives next to cl.exe (needed for libffi's win64_intel.S)
get_filename_component(MSVC_BIN_DIR "${CMAKE_C_COMPILER}" DIRECTORY)
find_program(ML64_EXECUTABLE ml64 HINTS "${MSVC_BIN_DIR}" NO_DEFAULT_PATH)
if(NOT ML64_EXECUTABLE)
    message(FATAL_ERROR "Cannot find ml64.exe next to ${CMAKE_C_COMPILER}")
endif()

# ---------------------------------------------------------------------------
# Global flags: every flag comes from the makefiles, so drop CMake's defaults.
# link.exe flags shared by all four Makefiles; /MACHINE:x64 is added by CMake
# for the x64 generator platform.
# ---------------------------------------------------------------------------
foreach(lang C CXX)
    set(CMAKE_${lang}_FLAGS "")
    set(CMAKE_${lang}_FLAGS_RELEASE "")
    set(CMAKE_${lang}_FLAGS_DEBUG "")
endforeach()
set(CMAKE_SHARED_LINKER_FLAGS
    "/nologo /incremental:no /manifest /manifestuac:\"level='asInvoker' uiAccess='false'\"")
string(APPEND CMAKE_SHARED_LINKER_FLAGS " /subsystem:windows /dynamicbase /nxcompat /errorreport:queue")
set(CMAKE_SHARED_LINKER_FLAGS_RELEASE "/opt:ref /opt:icf")
set(CMAKE_SHARED_LINKER_FLAGS_DEBUG "/debug")

# cl.exe flags common to every makefile (the /MD | /MDd runtime flag comes from
# CMAKE_MSVC_RUNTIME_LIBRARY in CMakeLists.txt; the -Fd<pdb> of /Zi from CMake)
set(MEDIA_COMMON_COMPILE_OPTIONS /nologo /W3 /WX- /EHsc /GS /fp:precise /Gm- /errorReport:queue)

set(JFX_RC_COMMON_DEFINITIONS
    "JFX_COMPANY=${JFX_COMPANY}"
    "JFX_COMPONENT=${JFX_PRODUCT} Platform binary"
    "JFX_NAME=${JFX_PRODUCT} Platform ${JFX_VER}"
    "JFX_VER=${JFX_VER}"
    "JFX_BUILD_ID=${JFX_BUILD_ID}"
    "JFX_COPYRIGHT=Copyright © ${JFX_COPYRIGHT_YEAR}"
    "JFX_FVER=${JFX_FVER}"
    "JFX_FTYPE=0x2L")

set(GLIB_VS100 "${GLIB_SRC}/build/win32/vs100")

# ---------------------------------------------------------------------------
# add_media_archive(<name>          # one lib.exe archive of the makefiles
#     OUTPUT_NAME <lib base name>
#     SOURCES <files...>
#     INCLUDE_DIRS <dirs...>
#     COMPILE_DEFINITIONS <defs...>
#     COMPILE_OPTIONS <opts...>
# )
# ---------------------------------------------------------------------------
function(add_media_archive name)
    cmake_parse_arguments(MEDIA "" "OUTPUT_NAME"
        "SOURCES;INCLUDE_DIRS;COMPILE_DEFINITIONS;COMPILE_OPTIONS" ${ARGN})

    add_library(${name} STATIC ${MEDIA_SOURCES})
    set_target_properties(${name} PROPERTIES
        OUTPUT_NAME "${MEDIA_OUTPUT_NAME}"
        PREFIX "")
    target_include_directories(${name} PRIVATE ${MEDIA_INCLUDE_DIRS})
    target_compile_definitions(${name} PRIVATE ${MEDIA_COMPILE_DEFINITIONS})
    target_compile_options(${name} PRIVATE ${MEDIA_COMMON_COMPILE_OPTIONS} ${MEDIA_COMPILE_OPTIONS})
endfunction()

# ---------------------------------------------------------------------------
# add_media_library(<name>          # one link.exe DLL of the makefiles
#     OUTPUT_NAME <dll base name>
#     RC_INTERNAL_NAME <name>        # JFX_INTERNAL_NAME in version.rc (default <name>)
#     DEF_FILE <file>                # link.exe -def: export list
#     SOURCES <files...>
#     INCLUDE_DIRS <dirs...>
#     COMPILE_DEFINITIONS <defs...>
#     COMPILE_OPTIONS <opts...>
#     LINK_LIBS <targets/libs...>
#     LINK_OPTIONS <opts...>
# )
# ---------------------------------------------------------------------------
function(add_media_library name)
    cmake_parse_arguments(MEDIA "" "OUTPUT_NAME;RC_INTERNAL_NAME;DEF_FILE"
        "SOURCES;INCLUDE_DIRS;COMPILE_DEFINITIONS;COMPILE_OPTIONS;LINK_LIBS;LINK_OPTIONS" ${ARGN})

    if(NOT MEDIA_RC_INTERNAL_NAME)
        set(MEDIA_RC_INTERNAL_NAME "${name}")
    endif()

    # Each DLL compiles its own copy of the shared version resource with its own
    # defines (win.gradle WIN.media.*RcFlags)
    get_filename_component(rc_name "${JFX_VERSION_RC}" NAME)
    set(rc_copy "${CMAKE_CURRENT_BINARY_DIR}/rc/${name}/${rc_name}")
    configure_file("${JFX_VERSION_RC}" "${rc_copy}" COPYONLY)
    get_filename_component(rc_src_dir "${JFX_VERSION_RC}" DIRECTORY)
    set(rc_definitions
        "JFX_FNAME=${MEDIA_OUTPUT_NAME}.dll"
        "JFX_INTERNAL_NAME=${MEDIA_RC_INTERNAL_NAME}"
        ${JFX_RC_COMMON_DEFINITIONS})
    set_source_files_properties("${rc_copy}" PROPERTIES
        COMPILE_DEFINITIONS "${rc_definitions}"
        INCLUDE_DIRECTORIES "${rc_src_dir}")

    set(sources ${MEDIA_SOURCES} "${rc_copy}")
    if(MEDIA_DEF_FILE)
        list(APPEND sources "${MEDIA_DEF_FILE}")
    endif()

    add_library(${name} SHARED ${sources})
    set_target_properties(${name} PROPERTIES
        OUTPUT_NAME "${MEDIA_OUTPUT_NAME}"
        PREFIX ""
        RUNTIME_OUTPUT_DIRECTORY "${BIN_DIR}"
        RUNTIME_OUTPUT_DIRECTORY_RELEASE "${BIN_DIR}"
        RUNTIME_OUTPUT_DIRECTORY_DEBUG "${BIN_DIR}"
        PDB_OUTPUT_DIRECTORY "${BIN_DIR}"
        PDB_OUTPUT_DIRECTORY_RELEASE "${BIN_DIR}"
        PDB_OUTPUT_DIRECTORY_DEBUG "${BIN_DIR}")
    if(NOT MEDIA_SOURCES)
        # Only archives, a .def and the .rc: nothing to infer the linker language from
        set_target_properties(${name} PROPERTIES LINKER_LANGUAGE C)
    endif()

    target_include_directories(${name} PRIVATE ${MEDIA_INCLUDE_DIRS})
    target_compile_definitions(${name} PRIVATE ${MEDIA_COMPILE_DEFINITIONS})
    target_compile_options(${name} PRIVATE ${MEDIA_COMMON_COMPILE_OPTIONS} ${MEDIA_COMPILE_OPTIONS})

    target_link_libraries(${name} PRIVATE ${MEDIA_LINK_LIBS})
    target_link_options(${name} PRIVATE
        "/map:$<TARGET_FILE_DIR:${name}>/${MEDIA_OUTPUT_NAME}.map"
        ${MEDIA_LINK_OPTIONS})
endfunction()

# ===========================================================================
# glib-lite.dll  (projects/win/glib-lite/Makefile + Makefile.ffi/.glib/
#                 .gmodule/.gobject/.gthread; exports from glib-lite.def)
# ===========================================================================

# --- libffi.lib (Makefile.ffi, x64 branch) ---------------------------------
set(FFI_COMPILE_DEFINITIONS FFI_STATIC_BUILD _DISABLE_CONSTEXPR_MUTEX_CONSTRUCTOR GSTREAMER_LITE X86_WIN64)
set(FFI_INCLUDE_DIRS "${LIBFFI_SRC}/include" "${LIBFFI_SRC}/src/x86" "${LIBFFI_SRC}/include/win/x64")

# win64_intel.S goes through the C preprocessor (cl -EP) and then ml64, as in
# Makefile.ffi. /TC only tells cl that the .S file is C input.
set(FFI_ASM_SRC "${LIBFFI_SRC}/src/x86/win64_intel.S")
set(FFI_ASM_DIR "${CMAKE_CURRENT_BINARY_DIR}/libffi")
set(FFI_ASM_PREPROCESSED "${FFI_ASM_DIR}/win64_intel.asm")
set(FFI_ASM_OBJECT "${FFI_ASM_DIR}/win64_intel.obj")
set(FFI_ASM_INCLUDE_FLAGS)
foreach(dir ${FFI_INCLUDE_DIRS})
    list(APPEND FFI_ASM_INCLUDE_FLAGS "/I${dir}")
endforeach()
set(FFI_ASM_DEFINE_FLAGS)
foreach(def ${FFI_COMPILE_DEFINITIONS})
    list(APPEND FFI_ASM_DEFINE_FLAGS "/D${def}")
endforeach()
add_custom_command(OUTPUT "${FFI_ASM_OBJECT}"
    COMMAND "${CMAKE_COMMAND}" -E make_directory "${FFI_ASM_DIR}"
    COMMAND "${CMAKE_C_COMPILER}" /nologo /EP /P /TC "/Fi${FFI_ASM_PREPROCESSED}"
        ${FFI_ASM_INCLUDE_FLAGS} ${FFI_ASM_DEFINE_FLAGS} "${FFI_ASM_SRC}"
    COMMAND "${ML64_EXECUTABLE}" /nologo /c "/Fo${FFI_ASM_OBJECT}" "${FFI_ASM_PREPROCESSED}"
    DEPENDS "${FFI_ASM_SRC}"
        "${LIBFFI_SRC}/include/win/x64/fficonfig.h" "${LIBFFI_SRC}/include/win/x64/ffi.h"
        "${LIBFFI_SRC}/include/ffi_cfi.h" "${LIBFFI_SRC}/src/x86/asmnames.h"
        "${LIBFFI_SRC}/src/x86/ffitarget.h"
    COMMENT "Preprocessing and assembling win64_intel.S"
    VERBATIM)
set_source_files_properties("${FFI_ASM_OBJECT}" PROPERTIES EXTERNAL_OBJECT TRUE GENERATED TRUE)

add_media_archive(glibLiteFfi
    OUTPUT_NAME libffi
    SOURCES
        "${LIBFFI_SRC}/src/closures.c"
        "${LIBFFI_SRC}/src/java_raw_api.c"
        "${LIBFFI_SRC}/src/prep_cif.c"
        "${LIBFFI_SRC}/src/raw_api.c"
        "${LIBFFI_SRC}/src/types.c"
        "${LIBFFI_SRC}/src/x86/ffiw64.c"
        "${FFI_ASM_OBJECT}"
    INCLUDE_DIRS ${FFI_INCLUDE_DIRS}
    COMPILE_DEFINITIONS ${FFI_COMPILE_DEFINITIONS}
    COMPILE_OPTIONS /Zc:wchar_t /Zc:forScope /Gd /wd4430 /analyze-
        "$<$<CONFIG:Release>:/O1;/Oy;/Gy;/GF>"
        "$<$<CONFIG:Debug>:/Od;/Oy-;/RTCu;/Zi>")

# --- flags shared by Makefile.glib/.gobject/.gmodule/.gthread --------------
set(GLIB_COMPILE_DEFINITIONS
    WIN32 _WINDOWS _DISABLE_CONSTEXPR_MUTEX_CONSTRUCTOR _USRDLL GSTREAMER_LITE
    HAVE_CONFIG_H _MBCS G_OS_WIN32 FFI_STATIC_BUILD G_DISABLE_DEPRECATED
    G_DISABLE_ASSERT _WIN64
    "$<$<CONFIG:Release>:NDEBUG>"
    "$<$<CONFIG:Debug>:_DEBUG>")
set(GLIB_COMPILE_OPTIONS
    /Zc:wchar_t /Zc:forScope /Gd /wd4430 /analyze-
    /wd4005 /wd4018 /wd4028 /wd4090 /wd4113 /wd4267 /wd4715
    /wd4146 /wd4311 /wd4312 /wd4133 /wd4146 /wd4334
    /FImsvc_recommended_pragmas.h
    "$<$<CONFIG:Release>:/O1;/Oy;/Gy;/GF>"
    "$<$<CONFIG:Debug>:/Od;/Oy-;/RTC1;/Zi>")

# --- libglib.lib (Makefile.glib) -------------------------------------------
add_media_archive(glibLiteGlib
    OUTPUT_NAME libglib
    SOURCES
        "${GLIB_SRC}/glib/garcbox.c"
        "${GLIB_SRC}/glib/garray.c"
        "${GLIB_SRC}/glib/gasyncqueue.c"
        "${GLIB_SRC}/glib/gatomic.c"
        "${GLIB_SRC}/glib/gbacktrace.c"
        "${GLIB_SRC}/glib/gbase64.c"
        "${GLIB_SRC}/glib/gbitlock.c"
        "${GLIB_SRC}/glib/gbytes.c"
        "${GLIB_SRC}/glib/gcharset.c"
        "${GLIB_SRC}/glib/gchecksum.c"
        "${GLIB_SRC}/glib/gconvert.c"
        "${GLIB_SRC}/glib/gdataset.c"
        "${GLIB_SRC}/glib/gdate.c"
        "${GLIB_SRC}/glib/gdatetime.c"
        "${GLIB_SRC}/glib/gdatetime-private.c"
        "${GLIB_SRC}/glib/gdir.c"
        "${GLIB_SRC}/glib/genviron.c"
        "${GLIB_SRC}/glib/gerror.c"
        "${GLIB_SRC}/glib/gfileutils.c"
        "${GLIB_SRC}/glib/ggettext.c"
        "${GLIB_SRC}/glib/ghash.c"
        "${GLIB_SRC}/glib/ghmac.c"
        "${GLIB_SRC}/glib/ghook.c"
        "${GLIB_SRC}/glib/ghostutils.c"
        "${GLIB_SRC}/glib/giochannel.c"
        "${GLIB_SRC}/glib/giowin32.c"
        "${GLIB_SRC}/glib/glib-init.c"
        "${GLIB_SRC}/glib/glib-private.c"
        "${GLIB_SRC}/glib/glist.c"
        "${GLIB_SRC}/glib/gmain.c"
        "${GLIB_SRC}/glib/gmappedfile.c"
        "${GLIB_SRC}/glib/gmarkup.c"
        "${GLIB_SRC}/glib/gmem.c"
        "${GLIB_SRC}/glib/gmessages.c"
        "${GLIB_SRC}/glib/gnode.c"
        "${GLIB_SRC}/glib/gnulib/asnprintf.c"
        "${GLIB_SRC}/glib/gnulib/printf-args.c"
        "${GLIB_SRC}/glib/gnulib/printf-parse.c"
        "${GLIB_SRC}/glib/gnulib/printf.c"
        "${GLIB_SRC}/glib/gnulib/vasnprintf.c"
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
        "${GLIB_SRC}/glib/gshell.c"
        "${GLIB_SRC}/glib/gslice.c"
        "${GLIB_SRC}/glib/gslist.c"
        "${GLIB_SRC}/glib/gspawn.c"
        "${GLIB_SRC}/glib/gspawn-win32-helper.c"
        "${GLIB_SRC}/glib/gspawn-win32.c"
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
        "${GLIB_SRC}/glib/gunicollate.c"
        "${GLIB_SRC}/glib/gunidecomp.c"
        "${GLIB_SRC}/glib/guri.c"
        "${GLIB_SRC}/glib/gutf8.c"
        "${GLIB_SRC}/glib/gutils.c"
        "${GLIB_SRC}/glib/gvariant-core.c"
        "${GLIB_SRC}/glib/gvariant-parser.c"
        "${GLIB_SRC}/glib/gvariant-serialiser.c"
        "${GLIB_SRC}/glib/gvariant.c"
        "${GLIB_SRC}/glib/gvarianttype.c"
        "${GLIB_SRC}/glib/gvarianttypeinfo.c"
        "${GLIB_SRC}/glib/gversion.c"
        "${GLIB_SRC}/glib/gwakeup.c"
        "${GLIB_SRC}/glib/gwin32.c"
        "${GLIB_SRC}/glib/libcharset/localcharset.c"
    INCLUDE_DIRS "${GLIB_SRC}/glib/gnulib" "${GLIB_SRC}/glib/libcharset" "${GLIB_SRC}/glib/dirent"
        "${GLIB_SRC}/glib" "${GLIB_SRC}" "${GLIB_VS100}"
    COMPILE_DEFINITIONS ${GLIB_COMPILE_DEFINITIONS}
        GLIB_COMPILATION "G_LOG_DOMAIN=\"Glib\""
        LINK_SIZE=2 MAX_NAME_SIZE=32 MAX_NAME_COUNT=10000 NEWLINE=-1
        POSIX_MALLOC_THRESHOLD=10 MATCH_LIMIT=10000000 MATCH_LIMIT_RECURSION=10000000
        HAVE_LONG_LONG LIBDIR NVALGRIND
    COMPILE_OPTIONS ${GLIB_COMPILE_OPTIONS} /wd4116)

# --- libmodule.lib (Makefile.gmodule) --------------------------------------
add_media_archive(glibLiteGModule
    OUTPUT_NAME libmodule
    SOURCES
        "${GLIB_SRC}/gmodule/gmodule.c"
    INCLUDE_DIRS "${GLIB_SRC}" "${GLIB_SRC}/glib" "${GLIB_SRC}/gmodule" "${GLIB_VS100}"
    COMPILE_DEFINITIONS ${GLIB_COMPILE_DEFINITIONS} "G_LOG_DOMAIN=\"GModule\""
    COMPILE_OPTIONS ${GLIB_COMPILE_OPTIONS})

# --- libgobject.lib (Makefile.gobject, x64 branch) -------------------------
add_media_archive(glibLiteGObject
    OUTPUT_NAME libgobject
    SOURCES
        "${GLIB_SRC}/gobject/gatomicarray.c"
        "${GLIB_SRC}/gobject/gboxed.c"
        "${GLIB_SRC}/gobject/gclosure.c"
        "${GLIB_SRC}/gobject/genums.c"
        "${GLIB_SRC}/gobject/gmarshal.c"
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
    INCLUDE_DIRS "${GLIB_SRC}" "${GLIB_SRC}/glib" "${GLIB_SRC}/gobject" "${GLIB_VS100}"
        "${LIBFFI_SRC}/include" "${LIBFFI_SRC}/src/x86" "${LIBFFI_SRC}/include/win/x64"
    COMPILE_DEFINITIONS ${GLIB_COMPILE_DEFINITIONS}
        GOBJECT_EXPORTS GOBJECT_COMPILATION "G_LOG_DOMAIN=\"Glib-GObject\""
    COMPILE_OPTIONS ${GLIB_COMPILE_OPTIONS})

# --- libgthread.lib (Makefile.gthread) -------------------------------------
add_media_archive(glibLiteGThread
    OUTPUT_NAME libgthread
    SOURCES
        "${GLIB_SRC}/gthread/gthread-impl.c"
    INCLUDE_DIRS "${GLIB_SRC}" "${GLIB_SRC}/glib" "${GLIB_SRC}/gthread" "${GLIB_VS100}"
    COMPILE_DEFINITIONS ${GLIB_COMPILE_DEFINITIONS} "G_LOG_DOMAIN=\"GThread\""
    COMPILE_OPTIONS ${GLIB_COMPILE_OPTIONS})

# --- glib-lite.dll (glib-lite/Makefile) ------------------------------------
add_media_library(glibLite
    OUTPUT_NAME glib-lite
    RC_INTERNAL_NAME glib
    DEF_FILE "${GLIB_VS100}/glib-lite.def"
    LINK_LIBS glibLiteFfi glibLiteGlib glibLiteGModule glibLiteGObject glibLiteGThread
        Ws2_32.lib kernel32.lib user32.lib shell32.lib advapi32.lib ole32.lib Winmm.lib
    LINK_OPTIONS /tlbid:1)

# ===========================================================================
# gstreamer-lite.dll  (projects/win/gstreamer-lite/Makefile + Makefile.gstreamer/
#                      .gstplugins; exports from projects/win/gstreamer-lite.def)
# ===========================================================================

# --- include directories, defines and flags shared by Makefile.gstreamer and
#     Makefile.gstplugins (each puts its own DIRLIST directories in front) ----
# Both makefiles also list gst-plugins-base/win32/common/ (and Makefile.gstplugins
# gstreamer/plugins/indexers/), which no longer exist in the tree.
set(GST_COMMON_INCLUDE_DIRS
    "${PLUGINS_SRC}"
    "${GST_SRC}/projects/build/win32/common"
    "${GST_SRC}/projects/plugins"
    "${GST_SRC}/gstreamer"
    "${GST_SRC}/gstreamer/libs"
    "${GST_SRC}/gst-plugins-base"
    "${GST_SRC}/gst-plugins-base/gst-libs"
    "${GLIB_SRC}" "${GLIB_SRC}/glib" "${GLIB_SRC}/gmodule" "${GLIB_VS100}")
set(GST_COMMON_COMPILE_DEFINITIONS
    WIN32 _WINDOWS _DISABLE_CONSTEXPR_MUTEX_CONSTRUCTOR HAVE_CONFIG_H GSTREAMER_LITE
    GST_REMOVE_DEPRECATED GST_DISABLE_GST_DEBUG GST_DISABLE_LOADSAVE _USE_MATH_DEFINES
    _USRDLL _WINDLL _MBCS G_DISABLE_DEPRECATED G_DISABLE_ASSERT _WIN64
    "$<$<CONFIG:Release>:NDEBUG>"
    "$<$<CONFIG:Debug>:_DEBUG>")
# -wd4018 and -wd4244 are repeated in the makefiles; listed once here
set(GST_COMMON_COMPILE_OPTIONS
    /Zc:wchar_t /Zc:forScope /Gd /analyze-
    /wd4018 /wd4244 /wd4005 /wd4101 /wd4146 /wd4996
    "$<$<CONFIG:Release>:/O1;/Oy;/Gy;/GF>"
    "$<$<CONFIG:Debug>:/Od;/Oy-;/RTC1;/Zi>")

# --- libgstreamer.lib (Makefile.gstreamer) ---------------------------------
add_media_archive(gstreamerLiteGst
    OUTPUT_NAME libgstreamer
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
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/audio/streamvolume.c"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/audio/gstaudioutilsprivate.c"
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
    INCLUDE_DIRS
        "${GST_SRC}/gstreamer/gst"
        "${GST_SRC}/gstreamer/libs/gst/base"
        "${GST_SRC}/gstreamer/libs/gst/controller"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/app"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/audio"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/fft"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/pbutils"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/riff"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/tag"
        "${GST_SRC}/gst-plugins-base/gst-libs/gst/video"
        ${GST_COMMON_INCLUDE_DIRS}
    COMPILE_DEFINITIONS ${GST_COMMON_COMPILE_DEFINITIONS}
        LIBGSTREAMER_EXPORTS HAVE_WIN32 LIBDSHOW_EXPORTS GST_REMOVE_DISABLED
    COMPILE_OPTIONS ${GST_COMMON_COMPILE_OPTIONS} /wd4273)

# --- libgstplugins.lib (Makefile.gstplugins) -------------------------------
add_media_archive(gstreamerLiteGstPlugins
    OUTPUT_NAME libgstplugins
    SOURCES
        "${GST_SRC}/gst-plugins-bad/gst/aiff/aiff.c"
        "${GST_SRC}/gst-plugins-bad/gst/aiff/aiffparse.c"
        "${GST_SRC}/gst-plugins-bad/gst/aiff/gstaiffelement.c"
        "${GST_SRC}/gst-plugins-base/gst/app/gstapp.c"
        "${GST_SRC}/gst-plugins-base/gst/app/gstappsink.c"
        "${GST_SRC}/gst-plugins-base/gst/audioconvert/gstaudioconvert.c"
        "${GST_SRC}/gst-plugins-base/gst/audioconvert/plugin.c"
        "${GST_SRC}/gst-plugins-base/gst/typefind/gsttypefindfunctions.c"
        "${GST_SRC}/gst-plugins-base/gst/typefind/gsttypefindfunctionsplugin.c"
        "${GST_SRC}/gst-plugins-good/gst/audioparsers/gstmpegaudioparse.c"
        "${GST_SRC}/gst-plugins-good/gst/audioparsers/parsersplugin.c"
        "${GST_SRC}/gst-plugins-good/sys/directsound/gstdirectsoundsink.c"
        "${GST_SRC}/gst-plugins-good/sys/directsound/gstdirectsoundplugin.c"
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
        "${GST_SRC}/gst-plugins-good/sys/directsound/gstdirectsoundnotify.cpp"
    INCLUDE_DIRS
        "${GST_SRC}/gst-plugins-bad/gst/aiff"
        "${GST_SRC}/gst-plugins-base/gst/app"
        "${GST_SRC}/gst-plugins-base/gst/audioconvert"
        "${GST_SRC}/gst-plugins-base/gst/typefind"
        "${GST_SRC}/gst-plugins-good/gst/audioparsers"
        "${GST_SRC}/gst-plugins-good/sys/directsound"
        "${GST_SRC}/gst-plugins-good/gst/equalizer"
        "${GST_SRC}/gst-plugins-good/gst/isomp4"
        "${GST_SRC}/gst-plugins-good/gst/spectrum"
        "${GST_SRC}/gst-plugins-good/gst/wavparse"
        "${GST_SRC}/gstreamer/plugins/elements"
        "${GST_SRC}/projects/plugins"
        ${GST_COMMON_INCLUDE_DIRS}
    COMPILE_DEFINITIONS ${GST_COMMON_COMPILE_DEFINITIONS}
        LIBGSTELEMENTS_EXPORTS _WIN32_DCOM COBJMACROS
    COMPILE_OPTIONS ${GST_COMMON_COMPILE_OPTIONS})

# --- gstreamer-lite.dll (gstreamer-lite/Makefile) --------------------------
add_media_library(gstreamerLite
    OUTPUT_NAME gstreamer-lite
    RC_INTERNAL_NAME gstreamer
    DEF_FILE "${MEDIA_PROJECTS_SRC}/win/gstreamer-lite.def"
    LINK_LIBS gstreamerLiteGst gstreamerLiteGstPlugins glibLite
        Ws2_32.lib kernel32.lib user32.lib shell32.lib advapi32.lib ole32.lib DSound.lib
    LINK_OPTIONS /tlbid:1)

# ===========================================================================
# fxplugins.dll  (projects/win/fxplugins/Makefile + Makefile.BaseClasses; the
#                 only export, gst_plugin_desc, is __declspec(dllexport) in
#                 plugins/fxplugins.c)
# ===========================================================================

# --- baseclasses.lib (Makefile.BaseClasses) --------------------------------
add_media_archive(fxpluginsBaseClasses
    OUTPUT_NAME baseclasses
    SOURCES
        "${BASECLASSES_SRC}/amextra.cpp"
        "${BASECLASSES_SRC}/amfilter.cpp"
        "${BASECLASSES_SRC}/amvideo.cpp"
        "${BASECLASSES_SRC}/arithutil.cpp"
        "${BASECLASSES_SRC}/combase.cpp"
        "${BASECLASSES_SRC}/cprop.cpp"
        "${BASECLASSES_SRC}/ctlutil.cpp"
        "${BASECLASSES_SRC}/ddmm.cpp"
        "${BASECLASSES_SRC}/dllentry.cpp"
        "${BASECLASSES_SRC}/dllsetup.cpp"
        "${BASECLASSES_SRC}/mtype.cpp"
        "${BASECLASSES_SRC}/outputq.cpp"
        "${BASECLASSES_SRC}/perflog.cpp"
        "${BASECLASSES_SRC}/pstream.cpp"
        "${BASECLASSES_SRC}/pullpin.cpp"
        "${BASECLASSES_SRC}/refclock.cpp"
        "${BASECLASSES_SRC}/renbase.cpp"
        "${BASECLASSES_SRC}/schedule.cpp"
        "${BASECLASSES_SRC}/seekpt.cpp"
        "${BASECLASSES_SRC}/source.cpp"
        "${BASECLASSES_SRC}/strmctl.cpp"
        "${BASECLASSES_SRC}/sysclock.cpp"
        "${BASECLASSES_SRC}/transfrm.cpp"
        "${BASECLASSES_SRC}/transip.cpp"
        "${BASECLASSES_SRC}/videoctl.cpp"
        "${BASECLASSES_SRC}/vtrans.cpp"
        "${BASECLASSES_SRC}/winctrl.cpp"
        "${BASECLASSES_SRC}/winutil.cpp"
        "${BASECLASSES_SRC}/wxdebug.cpp"
        "${BASECLASSES_SRC}/wxlist.cpp"
        "${BASECLASSES_SRC}/wxutil.cpp"
    INCLUDE_DIRS "${BASECLASSES_SRC}"
    COMPILE_DEFINITIONS WIN32 _DISABLE_CONSTEXPR_MUTEX_CONSTRUCTOR _LIB _WIN32_DCOM _MBCS NODEFAULTLIB _WIN64
        "$<$<CONFIG:Release>:NDEBUG>"
        "$<$<CONFIG:Debug>:_DEBUG>"
    COMPILE_OPTIONS /Zc:wchar_t /Zc:forScope- /Gd /wd4430 /analyze-
        "$<$<CONFIG:Release>:/O1;/Oy;/Gy;/GF>"
        "$<$<CONFIG:Debug>:/Od;/Oy-;/RTC1;/Zi>")

# --- fxplugins.dll (fxplugins/Makefile; strmiids.lib is listed twice there) -
add_media_library(fxplugins
    OUTPUT_NAME fxplugins
    SOURCES
        "${PLUGINS_SRC}/javasource/javasource.c"
        "${PLUGINS_SRC}/javasource/marshal.c"
        "${PLUGINS_SRC}/progressbuffer/progressbuffer.c"
        "${PLUGINS_SRC}/progressbuffer/win32/filecache.c"
        "${PLUGINS_SRC}/progressbuffer/hlsprogressbuffer.c"
        "${PLUGINS_SRC}/fxplugins.c"
        "${PLUGINS_SRC}/dshowwrapper/Allocator.cpp"
        "${PLUGINS_SRC}/dshowwrapper/dshowwrapper.cpp"
        "${PLUGINS_SRC}/dshowwrapper/Sink.cpp"
        "${PLUGINS_SRC}/dshowwrapper/Src.cpp"
        "${PLUGINS_SRC}/mfwrapper/mfwrapper.cpp"
        "${PLUGINS_SRC}/mfwrapper/mfgstbuffer.cpp"
    INCLUDE_DIRS
        "${PLUGINS_SRC}/dshowwrapper"
        "${PLUGINS_SRC}/javasource"
        "${PLUGINS_SRC}/progressbuffer"
        "${PLUGINS_SRC}/progressbuffer/win32"
        "${PLUGINS_SRC}/mfwrapper"
        "${PLUGINS_SRC}"
        "${GLIB_SRC}" "${GLIB_SRC}/glib" "${GLIB_SRC}/gmodule" "${GLIB_VS100}"
        "${GST_SRC}/gstreamer" "${GST_SRC}/gstreamer/libs" "${GST_SRC}/gst-plugins-base/gst-libs"
        "${BASECLASSES_SRC}"
    COMPILE_DEFINITIONS
        WIN32 _WINDOWS _DISABLE_CONSTEXPR_MUTEX_CONSTRUCTOR _USRDLL ENABLE_PULL_MODE=1
        ENABLE_SOURCE_SEEKING=1 GSTREAMER_LITE GST_REMOVE_DEPRECATED GST_REMOVE_DISABLED
        GST_DISABLE_GST_DEBUG GST_DISABLE_LOADSAVE G_DISABLE_DEPRECATED G_DISABLE_ASSERT
        _WINDLL _MBCS INITGUID _WIN64
        "$<$<CONFIG:Release>:NDEBUG>"
        "$<$<CONFIG:Debug>:_DEBUG;ENABLE_VISUAL_STUDIO_MEMORY_LEAKS_DETECTION>"
    COMPILE_OPTIONS /Zc:wchar_t /Zc:forScope- /analyze-
        "$<$<CONFIG:Release>:/Oy;/Gy;/GF;/O1>"
        "$<$<CONFIG:Debug>:/Oy-;/RTC1;/wd4018;/wd4244;/wd4274;/Zi;/Od>"
    LINK_LIBS glibLite gstreamerLite winmm.lib strmiids.lib kernel32.lib user32.lib shell32.lib
        advapi32.lib ole32.lib oleaut32.lib Mfplat.lib mfuuid.lib fxpluginsBaseClasses
    LINK_OPTIONS /nodefaultlib:libcmt /tlbid:1)

# ===========================================================================
# jfxmedia.dll  (jfxmedia/projects/win/Makefile; the exports are the jfxm_*
#                C ABI of jfxmedia/jfxmedia_api.h). The makefile also lists
#                gst-plugins-base/win32/common/, which no longer exists.
# ===========================================================================
add_media_library(jfxmedia
    OUTPUT_NAME jfxmedia
    SOURCES
        "${JFXMEDIA_SRC}/jni/Logger.cpp"
        # FFM C ABI (jfxmedia_api.h)
        "${JFXMEDIA_SRC}/ffi/jfxmedia_api.cpp"
        "${JFXMEDIA_SRC}/ffi/FfiPlayerEventDispatcher.cpp"
        "${JFXMEDIA_SRC}/ffi/FfiStreamCallbacks.cpp"
        "${JFXMEDIA_SRC}/ffi/FfiBandsHolder.cpp"
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
        "${JFXMEDIA_SRC}/platform/gstreamer/GstAudioEqualizer.cpp"
        "${JFXMEDIA_SRC}/platform/gstreamer/GstAudioPlaybackPipeline.cpp"
        "${JFXMEDIA_SRC}/platform/gstreamer/GstAudioSpectrum.cpp"
        "${JFXMEDIA_SRC}/platform/gstreamer/GstAVPlaybackPipeline.cpp"
        "${JFXMEDIA_SRC}/platform/gstreamer/GstElementContainer.cpp"
        "${JFXMEDIA_SRC}/platform/gstreamer/GstMediaManager.cpp"
        "${JFXMEDIA_SRC}/platform/gstreamer/GstPipelineFactory.cpp"
        "${JFXMEDIA_SRC}/platform/gstreamer/GstVideoFrame.cpp"
        "${JFXMEDIA_SRC}/Utils/win32/WinCriticalSection.cpp"
        "${JFXMEDIA_SRC}/Utils/win32/WinExceptionHandler.cpp"
        "${JFXMEDIA_SRC}/Utils/ColorConverter.c"
    INCLUDE_DIRS
        "${JFXMEDIA_SRC}"
        "${JFXMEDIA_SRC}/jni"
        "${JFXMEDIA_SRC}/ffi"
        "${HEADERS_DIR}"
        "${GLIB_SRC}" "${GLIB_SRC}/glib" "${GLIB_SRC}/gmodule" "${GLIB_VS100}"
        "${GST_SRC}/gstreamer"
        "${GST_SRC}/gst-plugins-base/gst-libs"
        "${GST_SRC}/gstreamer/libs"
        "${PLUGINS_SRC}"
    COMPILE_DEFINITIONS
        WIN32 _WINDOWS _DISABLE_CONSTEXPR_MUTEX_CONSTRUCTOR _USRDLL
        TARGET_OS_WIN32=1 _WIN32_WINNT=0x0500 GST_DISABLE_LOADSAVE GST_REMOVE_DEPRECATED
        G_DISABLE_DEPRECATED GSTREAMER_LITE _WINDLL _MBCS _WIN64
        "$<$<CONFIG:Release>:NDEBUG>"
        "$<$<CONFIG:Debug>:_DEBUG>"
    COMPILE_OPTIONS /Zc:wchar_t- /Zc:forScope /Gd
        "$<$<CONFIG:Release>:/O2>"
        "$<$<CONFIG:Debug>:/Od;/RTC1;/Zi>"
    LINK_LIBS gstreamerLite glibLite Winmm.lib kernel32.lib user32.lib comdlg32.lib advapi32.lib)
