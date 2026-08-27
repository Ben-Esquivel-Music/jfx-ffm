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

# Linux-specific portion of the javafx.graphics native build. Replicates the
# compiler/linker flags previously defined by the retired Gradle native build
# (buildSrc/linux.gradle). Included from CMakeLists.txt; shared input variables
# (JDK_HOME, GENSRC_DIR, HEADERS_DIR, BIN_DIR, INCLUDE_ES2, GRAPHICS_SRC) are
# defined there.
#
# Notes on Gradle parity:
#   - Gradle compiled with gcc and linked everything with g++; forcing
#     LINKER_LANGUAGE CXX below matches that.
#   - Gradle stripped the .so files (strip -x) only when copying them into the
#     SDK image; the libraries are left unstripped here, same as the build/
#     output of the Gradle build.
#   - The static (IS_STATIC_BUILD) and i386/parfait toolchain variants of the
#     Gradle build were not ported.

# ---------------------------------------------------------------------------
# Required system packages (development headers)
# ---------------------------------------------------------------------------
find_package(PkgConfig REQUIRED)
# Glass Gtk is built with GTK+ 3. Requires GTK+ 3.20.0 or newer.
set(GTK3_MIN_MINOR_VERSION 20)
set(GTK3_MIN_MICRO_VERSION 0)
pkg_check_modules(GTK3 REQUIRED IMPORTED_TARGET
    "gtk+-3.0>=3.${GTK3_MIN_MINOR_VERSION}.${GTK3_MIN_MICRO_VERSION}"
    gthread-2.0 xtst gio-unix-2.0)
pkg_check_modules(FREETYPE2 REQUIRED IMPORTED_TARGET freetype2)
pkg_check_modules(PANGOFT2 REQUIRED IMPORTED_TARGET pangoft2)

# ---------------------------------------------------------------------------
# Global flags: exact parity with the retired Gradle Linux toolchain config,
# replacing CMake defaults
# ---------------------------------------------------------------------------
foreach(lang C CXX)
    set(CMAKE_${lang}_FLAGS "")
    set(CMAKE_${lang}_FLAGS_RELEASE "")
    set(CMAKE_${lang}_FLAGS_DEBUG "")
endforeach()
set(CMAKE_SHARED_LINKER_FLAGS "")
set(CMAKE_SHARED_LINKER_FLAGS_RELEASE "")
set(CMAKE_SHARED_LINKER_FLAGS_DEBUG "")

# Common parameters used for both compiling and linking (Gradle commonFlags)
set(JFX_COMMON_FLAGS
    -fno-strict-aliasing -fPIC -fno-omit-frame-pointer # optimization flags
    -fstack-protector
    -Wextra -Wall -Wformat-security -Wno-unused -Wno-parentheses
    -Werror=trampolines) # warning flags

# Gradle cppFlags (shared C/C++ compile flags)
set(JFX_COMMON_COMPILE_OPTIONS
    ${JFX_COMMON_FLAGS}
    -ffunction-sections -fdata-sections
    "$<$<CONFIG:Debug>:-ggdb;-DVERBOSE>"
    "$<$<NOT:$<CONFIG:Debug>>:-O2;-DNDEBUG>")

# Gradle cFlags = cppFlags + this (only some targets used cFlags; C sources only)
set(JFX_C_STRICT_OPTIONS
    "$<$<COMPILE_LANGUAGE:C>:-Werror=implicit-function-declaration>")

# Gradle dynamicLinkFlags (CMake adds -shared itself)
set(JFX_COMMON_LINK_OPTIONS
    -static-libgcc -static-libstdc++
    ${JFX_COMMON_FLAGS}
    "LINKER:-z,relro"
    "LINKER:--gc-sections"
    "$<$<CONFIG:Debug>:-g>")

# ---------------------------------------------------------------------------
# add_jfx_library(<name>
#     OUTPUT_NAME <so base name>     # produces lib<OUTPUT_NAME>.so
#     SOURCE_DIRS <dirs...>          # globbed non-recursively, like Gradle listFiles()
#     EXTRA_SOURCES <files...>
#     EXCLUDE_REGEX <regex>          # dropped from the globbed sources
#     INCLUDE_DIRS <dirs...>
#     COMPILE_OPTIONS <opts...>
#     LINK_LIBS <libs...>
# )
# ---------------------------------------------------------------------------
function(add_jfx_library name)
    cmake_parse_arguments(JFX "" "OUTPUT_NAME;EXCLUDE_REGEX"
        "SOURCE_DIRS;EXTRA_SOURCES;INCLUDE_DIRS;COMPILE_OPTIONS;LINK_LIBS" ${ARGN})

    set(sources)
    foreach(dir ${JFX_SOURCE_DIRS})
        file(GLOB dir_sources "${dir}/*.c" "${dir}/*.cc" "${dir}/*.cpp")
        list(APPEND sources ${dir_sources})
    endforeach()
    if(JFX_EXCLUDE_REGEX)
        list(FILTER sources EXCLUDE REGEX "${JFX_EXCLUDE_REGEX}")
    endif()
    list(APPEND sources ${JFX_EXTRA_SOURCES})

    add_library(${name} SHARED ${sources})
    set_target_properties(${name} PROPERTIES
        OUTPUT_NAME "${JFX_OUTPUT_NAME}"
        LINKER_LANGUAGE CXX
        LIBRARY_OUTPUT_DIRECTORY "${BIN_DIR}"
        LIBRARY_OUTPUT_DIRECTORY_RELEASE "${BIN_DIR}"
        LIBRARY_OUTPUT_DIRECTORY_DEBUG "${BIN_DIR}")

    target_compile_options(${name} PRIVATE
        ${JFX_COMMON_COMPILE_OPTIONS} ${JFX_COMPILE_OPTIONS})
    target_include_directories(${name} PRIVATE
        "${JDK_HOME}/include" "${JDK_HOME}/include/linux"
        "${HEADERS_DIR}"
        ${JFX_SOURCE_DIRS} ${JFX_INCLUDE_DIRS})
    target_link_libraries(${name} PRIVATE ${JFX_LINK_LIBS})
    target_link_options(${name} PRIVATE ${JFX_COMMON_LINK_OPTIONS})
endfunction()

# ---------------------------------------------------------------------------
# libglass.so (the GTK launcher/loader only)
# ---------------------------------------------------------------------------
add_jfx_library(glass
    OUTPUT_NAME glass
    EXTRA_SOURCES "${GRAPHICS_SRC}/native-glass/gtk/launcher.c"
    INCLUDE_DIRS "${GRAPHICS_SRC}/native-glass/gtk"
    COMPILE_OPTIONS -Werror
    LINK_LIBS X11 dl)

# ---------------------------------------------------------------------------
# libglassgtk3.so (all GTK glass sources except the launcher)
# ---------------------------------------------------------------------------
add_jfx_library(glassgtk3
    OUTPUT_NAME glassgtk3
    SOURCE_DIRS "${GRAPHICS_SRC}/native-glass/gtk"
    EXCLUDE_REGEX "launcher\\.c$"
    INCLUDE_DIRS "${GRAPHICS_SRC}/native-glass/gtk/libpipewire/include"
    COMPILE_OPTIONS -Werror -Wno-deprecated-declarations
        -DGTK_3_MIN_MINOR_VERSION=${GTK3_MIN_MINOR_VERSION}
        -DGTK_3_MIN_MICRO_VERSION=${GTK3_MIN_MICRO_VERSION}
    LINK_LIBS PkgConfig::GTK3)

# ---------------------------------------------------------------------------
# libprism_common.so
# ---------------------------------------------------------------------------
add_jfx_library(prism
    OUTPUT_NAME prism_common
    SOURCE_DIRS "${GRAPHICS_SRC}/native-prism"
    COMPILE_OPTIONS ${JFX_C_STRICT_OPTIONS} -DINLINE=inline)

# ---------------------------------------------------------------------------
# libprism_sw.so
# ---------------------------------------------------------------------------
add_jfx_library(prismSW
    OUTPUT_NAME prism_sw
    SOURCE_DIRS "${GRAPHICS_SRC}/native-prism-sw"
    COMPILE_OPTIONS ${JFX_C_STRICT_OPTIONS} -DINLINE=inline)

# ---------------------------------------------------------------------------
# libprism_es2.so (optional, mirrors IS_INCLUDE_ES2; default true on Linux)
# ---------------------------------------------------------------------------
if(INCLUDE_ES2)
    add_jfx_library(prismES2
        OUTPUT_NAME prism_es2
        SOURCE_DIRS "${GRAPHICS_SRC}/native-prism-es2"
            "${GRAPHICS_SRC}/native-prism-es2/GL"
            "${GRAPHICS_SRC}/native-prism-es2/x11"
        COMPILE_OPTIONS -DLINUX ${JFX_C_STRICT_OPTIONS}
        LINK_LIBS X11 Xxf86vm GL)
endif()

# ---------------------------------------------------------------------------
# libjavafx_font.so (platform-independent font sources; the platform-specific
# files self-exclude via #ifdef guards)
# ---------------------------------------------------------------------------
add_jfx_library(font
    OUTPUT_NAME javafx_font
    SOURCE_DIRS "${GRAPHICS_SRC}/native-font"
    COMPILE_OPTIONS -DJFXFONT_PLUS)

# ---------------------------------------------------------------------------
# libjavafx_font_freetype.so
# ---------------------------------------------------------------------------
add_jfx_library(fontFreetype
    OUTPUT_NAME javafx_font_freetype
    EXTRA_SOURCES "${GRAPHICS_SRC}/native-font/freetype.c"
    INCLUDE_DIRS "${GRAPHICS_SRC}/native-font"
    COMPILE_OPTIONS -DJFXFONT_PLUS ${JFX_C_STRICT_OPTIONS} -D_ENABLE_PANGO
    LINK_LIBS PkgConfig::FREETYPE2)

# ---------------------------------------------------------------------------
# libjavafx_font_pango.so
# ---------------------------------------------------------------------------
add_jfx_library(fontPango
    OUTPUT_NAME javafx_font_pango
    EXTRA_SOURCES "${GRAPHICS_SRC}/native-font/pango.c"
    INCLUDE_DIRS "${GRAPHICS_SRC}/native-font"
    COMPILE_OPTIONS -DJFXFONT_PLUS ${JFX_C_STRICT_OPTIONS} -D_ENABLE_PANGO
    LINK_LIBS PkgConfig::PANGOFT2)

# ---------------------------------------------------------------------------
# libjavafx_iio.so
# ---------------------------------------------------------------------------
add_jfx_library(iio
    OUTPUT_NAME javafx_iio
    SOURCE_DIRS "${GRAPHICS_SRC}/native-iio" "${GRAPHICS_SRC}/native-iio/libjpeg"
    COMPILE_OPTIONS ${JFX_C_STRICT_OPTIONS} -fvisibility=hidden)

# ---------------------------------------------------------------------------
# libdecora_sse.so (generated JSL .cc files + native-decora; despite the name
# the sources are scalar C++ without SSE intrinsics, so this also builds on
# non-x86 architectures, matching the Gradle build)
# ---------------------------------------------------------------------------
add_jfx_library(decora
    OUTPUT_NAME decora_sse
    SOURCE_DIRS "${GENSRC_DIR}/jsl-decora" "${GRAPHICS_SRC}/native-decora"
    COMPILE_OPTIONS -ffast-math)
