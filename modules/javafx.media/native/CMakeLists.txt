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

# CMake build for the javafx.media native libraries, ported from the retired
# GNU make projects under src/main/native/jfxmedia/projects/<os> and
# src/main/native/gstreamer/projects/<os> (which the Gradle COMPILE_MEDIA=true
# build used to drive). Invoked from modules/javafx.media/pom.xml via the
# exec-maven-plugin, exactly like modules/javafx.graphics/native.
# Platform-specific targets live in win.cmake, linux.cmake and mac.cmake.
#
# Libraries (OUTPUT_NAME -> what NativeMediaManager / OSXPlatform load):
#   glib-lite        (Windows, macOS; Linux uses the system GLib)
#   gstreamer-lite   (all platforms)
#   fxplugins        (all platforms)
#   avplugin         (Linux only; libav/ffmpeg wrappers)
#   jfxmedia         (all platforms)
#   jfxmedia_avf     (macOS only; AVFoundation platform)

cmake_minimum_required(VERSION 3.20)

cmake_policy(SET CMP0091 NEW) # MSVC runtime library via CMAKE_MSVC_RUNTIME_LIBRARY
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>DLL")

# Minimum macOS version (Gradle: MACOSX_MIN_VERSION); ignored on other
# platforms. Must be set before project().
set(CMAKE_OSX_DEPLOYMENT_TARGET "11.0" CACHE STRING "Minimum macOS deployment version")

project(javafxMediaNatives LANGUAGES C CXX)

# ---------------------------------------------------------------------------
# Inputs from Maven (shared across platforms)
# ---------------------------------------------------------------------------
set(GENSRC_DIR "" CACHE PATH "modules/javafx.media/target/gensrc")
# HEADERS_DIR holds the generated jfxmedia_errors.h (produced from
# MediaError.java by the headergen tool). jfxmedia is JNI-free, so no JDK
# include directory is needed by any target; modules/javafx.media/pom.xml still
# passes -DJDK_HOME=, which CMake now ignores as an unused variable.
set(HEADERS_DIR "" CACHE PATH "Directory containing the generated jfxmedia_errors.h")
set(BIN_DIR "" CACHE PATH "Output directory for the built native libraries")

if(NOT GENSRC_DIR OR NOT HEADERS_DIR OR NOT BIN_DIR)
    message(FATAL_ERROR "GENSRC_DIR, HEADERS_DIR and BIN_DIR must be provided")
endif()

# ---------------------------------------------------------------------------
# Source roots shared by the platform files
# ---------------------------------------------------------------------------
set(MEDIA_SRC "${CMAKE_CURRENT_SOURCE_DIR}/../src/main/native")
set(JFXMEDIA_SRC "${MEDIA_SRC}/jfxmedia")
set(GST_SRC "${MEDIA_SRC}/gstreamer/gstreamer-lite")
set(GLIB_SRC "${MEDIA_SRC}/gstreamer/3rd_party/glib")
set(LIBFFI_SRC "${MEDIA_SRC}/gstreamer/3rd_party/libffi")
set(BASECLASSES_SRC "${MEDIA_SRC}/gstreamer/3rd_party/baseclasses")
set(PLUGINS_SRC "${MEDIA_SRC}/gstreamer/plugins")
set(MEDIA_PROJECTS_SRC "${MEDIA_SRC}/gstreamer/projects")
# The Windows version resource shared with the graphics natives
set(JFX_VERSION_RC "${CMAKE_CURRENT_SOURCE_DIR}/../../javafx.graphics/src/main/resources/version.rc")

if(WIN32)
    include("${CMAKE_CURRENT_SOURCE_DIR}/win.cmake")
elseif(APPLE)
    include("${CMAKE_CURRENT_SOURCE_DIR}/mac.cmake")
elseif(CMAKE_SYSTEM_NAME STREQUAL "Linux")
    include("${CMAKE_CURRENT_SOURCE_DIR}/linux.cmake")
else()
    message(FATAL_ERROR "This CMake build currently supports Windows, macOS and Linux only.")
endif()
