/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 * Compile-time check that the jfxmedia crate implements jfxmedia_api.h. Compiled as C++, never linked: in C++ a
 * second extern "C" declaration of a function with other parameters or another return type is an error (MSVC
 * C2733/C2556, GCC "conflicting declaration"); MSVC's C mode would only warn (C4028).
 *
 * Include path: the directory of jfxmedia_api.h, and the directory of the two files cbindgen generates from this
 * crate with the configuration files next to this one:
 *   jfxmedia_rs_functions.h  from cbindgen-functions.toml: the crate's extern "C" functions, with the header's
 *                            type names;
 *   jfxmedia_rs_types.h      from cbindgen-types.toml: the crate's constants and repr(C) types, prefixed rs_.
 */

#include <jfxmedia_api.h>

#include <jfxmedia_rs_functions.h>
#include <jfxmedia_rs_types.h>

#include <type_traits>

static_assert(rs_JFXM_ABI_VERSION == JFXM_ABI_VERSION,
              "jfxmedia crate: JFXM_ABI_VERSION differs from jfxmedia_api.h");
static_assert(std::is_same<decltype(rs_JFXM_ABI_VERSION), const uint32_t>::value,
              "jfxmedia crate: JFXM_ABI_VERSION is not a u32 (uint32_t, the return type of jfxm_abi_version)");
