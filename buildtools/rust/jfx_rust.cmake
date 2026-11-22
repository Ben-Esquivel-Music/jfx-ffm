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

# Links Rust crates into the existing native libraries, one slice of a library's C ABI at a time.
# Included by modules/javafx.graphics/native/CMakeLists.txt and modules/javafx.media/native/CMakeLists.txt
# before their platform file. A platform file registers a slice after creating the library target:
#
#   jfx_add_rust_slice(<target>
#       CRATE <crate dir>                 # modules/<module>/src/main/native-rust/<crate>
#       SLICE <NAME>                      # JFX_RUST_<NAME>=1 is defined for <target> when JFX_RUST is on
#       HEADERS <h>...                    # their <LIB>_EXPORT lines are the target's whole export list
#       EXPORTS <sym>...                  # the symbols this slice implements in Rust
#       [ABI_CHECK_INCLUDE_DIRS <dir>...] # what the header(s) need to compile in the ABI check
#       [C_DELETED])                      # the slice's C is gone: JFX_RUST=OFF is then a configure error
#
# With JFX_RUST on, the crate's staticlib (cargo's default name, built by "cargo rustc --locked --offline
# --crate-type staticlib" in the repository root with the toolchain rust-toolchain.toml pins, the profiles of
# cargo-config.toml plus a triple-dependent split-debuginfo, and --target <triple>, into
# <cargo directory>/<triple>/<profile directory>) is linked into <target>. <triple> is the rustc host on Windows and
# Linux, and on macOS that of the one architecture CMake builds for. The cargo directory is <build tree>/cargo, on
# macOS <build tree>/cargo/macos-<CMAKE_OSX_DEPLOYMENT_TARGET> if set: cargo and rustc run with that
# MACOSX_DEPLOYMENT_TARGET, and cargo does not rebuild when it changes. Only the staticlib is built there: with the
# rlib in the same rustc call, cargo would not apply the profile's lto. Two targets that register the same crate share
# one cargo step and one IMPORTED library. Windows exports the Rust symbols with /EXPORT:, which also pulls them out of
# the archive, since no C code references them. Linux pulls them in with --require-defined= and links every target that
# has a slice, with JFX_RUST on or off, with a version script that exports exactly the headers' names, which also keeps
# the Rust standard library's symbols local. macOS pulls them in with -u, which also fails the link when nothing
# defines them, and keeps the rest of the archive local with an -unexported_symbols_list, written from nm of the
# archive before each link: an exported list of the headers' names would also hide the C++ names that
# libjfxmedia_avf imports from libjfxmedia, so the C exports stay as they are, and with JFX_RUST off the link is
# unchanged. All three linkers take a C definition left in an object file over the archive member without a word, so
# after each link the linker map must show every EXPORTS symbol defined by the crate's archive; on macOS nm -gU of the
# library must also list each of them and no other name that the map takes from the archive.
#
# The targets jfx_rust_test (cargo test) and jfx_rust_lint (cargo fmt --check, clippy -D warnings, the ABI
# check of each library crate, then buildtools/rust/check-crate-licences.pl --self-test and the licence check
# itself) exist in every tree; with no enabled slice they only print that there is nothing to do, and need no
# Rust tool.
#
# Every cargo call writes stdout and stderr into one log file, printed when cargo ends: exec-maven-plugin
# copies a child's two streams into the Maven log concurrently, which garbles both. The log of a running
# call is <build tree>/jfx-rust/logs/<step>.log. The lines of the origin check after a link are garbled the
# same way, so it also writes its verdict on the last link of <target> to <build tree>/jfx-rust/logs/
# <target>-origin.log.
#
# Everything generated goes to <build tree>/jfx-rust and <build tree>/cargo, never to the source tree or
# to BIN_DIR. Run as "cmake -P jfx_rust.cmake" by the build, this file performs one action.

# ---------------------------------------------------------------------------
# Script mode: the actions the build runs
# ---------------------------------------------------------------------------

if(CMAKE_SCRIPT_MODE_FILE)
    # A script run starts with no policy set; the functions below record these, IN_LIST among them
    cmake_policy(VERSION 3.20)
endif()

function(_jfx_rust_lines text out_var)
    string(REPLACE "\r" "" text "${text}")
    string(REPLACE ";" "\\;" text "${text}")
    string(REPLACE "\n" ";" lines "${text}")
    list(REMOVE_ITEM lines "")
    set(${out_var} "${lines}" PARENT_SCOPE)
endfunction()

# The names of the functions an extern "C" header of cbindgen declares: per statement, the identifier
# before its first "("
function(_jfx_rust_declared_functions header out_var)
    file(READ "${header}" text)
    string(REGEX REPLACE "/[*]([^*]|[*]+[^*/])*[*]+/" "" text "${text}")
    string(REGEX REPLACE "//[^\n]*" "" text "${text}")
    string(REGEX REPLACE "(^|\n)[ \t]*#[^\n]*" "\\1" text "${text}")
    string(REPLACE "extern \"C\" {" "" text "${text}")
    string(REPLACE "}" "" text "${text}")
    string(REPLACE ";" "\n;\n" text "${text}")
    string(REGEX REPLACE "[\r\n\t ]+" " " text "${text}")
    string(REPLACE " ; " ";" text "${text}")
    string(REPLACE " ;" ";" text "${text}")
    set(names)
    foreach(statement ${text})
        string(STRIP "${statement}" statement)
        if(statement STREQUAL "")
            continue()
        endif()
        if(NOT statement MATCHES "^[^(]*[^A-Za-z0-9_]([A-Za-z_][A-Za-z0-9_]*) ?[(]")
            message(FATAL_ERROR "jfx_rust_lint: cannot read a function name in ${header}: ${statement}")
        endif()
        list(APPEND names "${CMAKE_MATCH_1}")
    endforeach()
    set(${out_var} "${names}" PARENT_SCOPE)
endfunction()

function(_jfx_rust_origin_fail text)
    file(WRITE "${JFX_RUST_LOG}" "${text}\n")
    message(FATAL_ERROR "${text}\nThis text is also in ${JFX_RUST_LOG}.")
endfunction()

# ld64's map and nm's output with "\", "[", "]" and ";" replaced by the control characters 5, 2, 3 and 4, so that
# each of their lines can be a list element: a literal string of the map may hold any of them, brackets unbalanced
function(_jfx_rust_ld64_safe text out_var)
    string(ASCII 2 lb)
    string(ASCII 3 rb)
    string(ASCII 4 sc)
    string(ASCII 5 bs)
    string(REPLACE "\\" "${bs}" text "${text}")
    string(REPLACE "[" "${lb}" text "${text}")
    string(REPLACE "]" "${rb}" text "${text}")
    string(REPLACE ";" "${sc}" text "${text}")
    set(${out_var} "${text}" PARENT_SCOPE)
endfunction()

function(_jfx_rust_ld64_plain text out_var)
    string(ASCII 2 lb)
    string(ASCII 3 rb)
    string(ASCII 4 sc)
    string(ASCII 5 bs)
    string(REPLACE "${lb}" "[" text "${text}")
    string(REPLACE "${rb}" "]" text "${text}")
    string(REPLACE "${sc}" ";" text "${text}")
    string(REPLACE "${bs}" "\\" text "${text}")
    set(${out_var} "${text}" PARENT_SCOPE)
endfunction()

# The names of nm -P output ("<name> <type> <value> <size>" per symbol, "<member>:" before those of each archive
# member), in the form of _jfx_rust_ld64_safe
function(_jfx_rust_nm_names text out_var)
    _jfx_rust_ld64_safe("${text}" text)
    string(REGEX MATCHALL "[^\n]+" lines "${text}")
    set(names)
    foreach(line IN LISTS lines)
        if(line MATCHES "^(.*[^ ]) [A-Za-z?] [0-9A-Fa-f]+ [0-9A-Fa-f]+$")
            list(APPEND names "${CMAKE_MATCH_1}")
        endif()
    endforeach()
    set(${out_var} "${names}" PARENT_SCOPE)
endfunction()

if(CMAKE_SCRIPT_MODE_FILE)
    if(JFX_RUST_ACTION STREQUAL "fail")
        message(FATAL_ERROR "${JFX_RUST_MESSAGE}")
    elseif(JFX_RUST_ACTION STREQUAL "cargo")
        # Every process of the build that runs cargo starts here. Removed from its environment: each variable whose
        # upper-cased name (Windows compares names without case) is CARGO, RUSTC, RUSTDOC, RUSTFMT, RUSTFLAGS or
        # RUSTDOCFLAGS, or starts with CARGO_, __CARGO_ or RUSTC_, except CARGO_HOME, CARGO_BUILD_JOBS and
        # CARGO_TERM_*. That takes CARGO_INCREMENTAL, which outranks even --config, every CARGO_PROFILE_* and
        # CARGO_TARGET_<triple>_*, and RUSTC_BOOTSTRAP, which would turn on cargo's unstable features, profile
        # rustflags among them. The names are read from the raw listing, as a value may hold anything.
        # Then set: the pinned toolchain (a missing one fails, it is never downloaded); the rustc that configure
        # checked against the pin (RUSTC outranks a config file's build.rustc); RUSTFLAGS of one blank, an empty list
        # that outranks the rustflags of every config file (set(ENV) of "" would unset it); the build tree's target
        # and build directories. The release and dev profiles and the [build] keys of cargo-config.toml are --config
        # arguments of every cargo call that compiles, and these outrank config files. Whatever a cargo config file
        # sets that neither these variables nor the commands' arguments override still applies, and cargo has no
        # switch that ignores config files. Examples, not the whole set: a config file can reach the staticlib by
        # replacing sources or tuning one package (paths, [patch], [source], [env], [profile.<name>.package.<spec>],
        # [profile.<name>.build-override], [target.<triple>.<links>], resolver.lockfile-path); it can change what
        # jfx_rust_test and jfx_rust_lint run, and so their result ([profile.test], a target's linker and runner,
        # rustdoc and its flags, an [alias] named clippy or fmt), as can a clippy.toml above the repository.
        if(NOT JFX_RUST_TOOLCHAIN OR NOT JFX_RUST_RUSTC OR NOT JFX_RUST_CARGO_TARGET_DIR)
            message(FATAL_ERROR "jfx_rust.cmake: the cargo action needs -DJFX_RUST_TOOLCHAIN=<pinned channel>, "
                "-DJFX_RUST_RUSTC=<rustc> and -DJFX_RUST_CARGO_TARGET_DIR=<directory>")
        endif()
        execute_process(COMMAND "${CMAKE_COMMAND}" -E environment
            RESULT_VARIABLE jfx_rust_rc OUTPUT_VARIABLE jfx_rust_env)
        if(NOT jfx_rust_rc EQUAL 0)
            message(FATAL_ERROR "jfx_rust: \"${CMAKE_COMMAND} -E environment\" failed (${jfx_rust_rc})")
        endif()
        string(REGEX MATCHALL "\n[A-Za-z0-9_]+=" jfx_rust_names "\n${jfx_rust_env}")
        foreach(jfx_rust_name IN LISTS jfx_rust_names)
            string(REGEX MATCH "[A-Za-z0-9_]+" jfx_rust_name "${jfx_rust_name}")
            string(TOUPPER "${jfx_rust_name}" jfx_rust_upper)
            if(jfx_rust_upper MATCHES "^(CARGO|RUSTC|RUSTDOC|RUSTFMT|RUSTFLAGS|RUSTDOCFLAGS|(__)?CARGO_.*|RUSTC_.*)$"
                    AND NOT jfx_rust_upper MATCHES "^(CARGO_HOME|CARGO_BUILD_JOBS|CARGO_TERM_.*)$")
                unset(ENV{${jfx_rust_name}})
            endif()
        endforeach()
        set(ENV{RUSTFLAGS} " ")
        set(ENV{RUSTUP_TOOLCHAIN} "${JFX_RUST_TOOLCHAIN}")
        set(ENV{RUSTUP_AUTO_INSTALL} 0)
        set(ENV{RUSTC} "${JFX_RUST_RUSTC}")
        set(ENV{CARGO_TARGET_DIR} "${JFX_RUST_CARGO_TARGET_DIR}")
        set(ENV{CARGO_BUILD_BUILD_DIR} "${JFX_RUST_CARGO_TARGET_DIR}")
        # Passed on macOS only: rustc reads it as clang does, and the C build's value outranks an inherited one
        if(NOT "${JFX_RUST_DEPLOYMENT_TARGET}" STREQUAL "")
            set(ENV{MACOSX_DEPLOYMENT_TARGET} "${JFX_RUST_DEPLOYMENT_TARGET}")
        endif()
        get_filename_component(jfx_rust_log_dir "${JFX_RUST_LOG}" DIRECTORY)
        file(MAKE_DIRECTORY "${jfx_rust_log_dir}")
        execute_process(COMMAND ${JFX_RUST_COMMAND}
            RESULT_VARIABLE jfx_rust_rc OUTPUT_FILE "${JFX_RUST_LOG}" ERROR_FILE "${JFX_RUST_LOG}")
        # The log goes to the stream the build tool writes its own next lines to (stdout after a success,
        # stderr after a failure), so that the two streams are never busy at the same time
        if(jfx_rust_rc EQUAL 0)
            execute_process(COMMAND "${CMAKE_COMMAND}" -E cat "${JFX_RUST_LOG}")
        else()
            file(READ "${JFX_RUST_LOG}" jfx_rust_text)
            message(NOTICE "${jfx_rust_text}")
            list(JOIN JFX_RUST_COMMAND " " jfx_rust_text)
            message(FATAL_ERROR "jfx_rust: \"${jfx_rust_text}\" failed (${jfx_rust_rc}); its output is above "
                "and in ${JFX_RUST_LOG}")
        endif()
    elseif(JFX_RUST_ACTION STREQUAL "cbindgen")
        # cbindgen is a build-time tool of the lint only, so it is looked up here, in the environment
        # the build runs in, and not at configure time, in the directories that cargo and rustc are looked up in
        if(NOT "$ENV{CARGO_HOME}" STREQUAL "")
            set(jfx_rust_hint "$ENV{CARGO_HOME}/bin")
        elseif(CMAKE_HOST_WIN32)
            set(jfx_rust_hint "$ENV{USERPROFILE}/.cargo/bin")
        else()
            set(jfx_rust_hint "$ENV{HOME}/.cargo/bin")
        endif()
        find_program(jfx_rust_cbindgen NAMES cbindgen PATHS ENV PATH "${jfx_rust_hint}" NO_DEFAULT_PATH)
        set(jfx_rust_install "Install it with \"cargo install --locked cbindgen@${JFX_CBINDGEN_VERSION}\".")
        if(NOT jfx_rust_cbindgen)
            message(FATAL_ERROR "jfx_rust_lint: the ABI check of the ${JFX_RUST_CRATE} crate needs cbindgen "
                "${JFX_CBINDGEN_VERSION}, and no cbindgen is on PATH or in ${jfx_rust_hint}. ${jfx_rust_install}")
        endif()
        execute_process(COMMAND "${jfx_rust_cbindgen}" --version
            RESULT_VARIABLE jfx_rust_rc OUTPUT_VARIABLE jfx_rust_out ERROR_VARIABLE jfx_rust_err
            OUTPUT_STRIP_TRAILING_WHITESPACE)
        if(NOT jfx_rust_rc EQUAL 0 OR NOT jfx_rust_out STREQUAL "cbindgen ${JFX_CBINDGEN_VERSION}")
            message(FATAL_ERROR "jfx_rust_lint: the ABI check of the ${JFX_RUST_CRATE} crate needs cbindgen "
                "${JFX_CBINDGEN_VERSION}, and ${jfx_rust_cbindgen} --version printed "
                "\"${jfx_rust_out}${jfx_rust_err}\". ${jfx_rust_install}")
        endif()
        # The crate's root source file, not its directory: in directory mode cbindgen runs cargo metadata
        # itself, without --locked and --offline. cbindgen skips an item it cannot translate with an
        # "ERROR:" line and still exits 0.
        file(MAKE_DIRECTORY "${JFX_RUST_GEN_DIR}")
        foreach(jfx_rust_kind functions types)
            set(jfx_rust_header "${JFX_RUST_GEN_DIR}/${JFX_RUST_LIB_NAME}_rs_${jfx_rust_kind}.h")
            execute_process(COMMAND "${jfx_rust_cbindgen}"
                    --config "${JFX_RUST_CRATE_DIR}/cbindgen-${jfx_rust_kind}.toml"
                    --output "${jfx_rust_header}" "${JFX_RUST_CRATE_DIR}/src/lib.rs"
                RESULT_VARIABLE jfx_rust_rc OUTPUT_VARIABLE jfx_rust_out ERROR_VARIABLE jfx_rust_out)
            # A failure prints everything to stderr only, like the cargo action
            if(NOT jfx_rust_rc EQUAL 0)
                message(NOTICE "${jfx_rust_out}")
                message(FATAL_ERROR "jfx_rust_lint: cbindgen failed (${jfx_rust_rc}) on the ${JFX_RUST_CRATE} "
                    "crate with cbindgen-${jfx_rust_kind}.toml; its output is above")
            endif()
            if(jfx_rust_out MATCHES "(^|\n)ERROR")
                message(NOTICE "${jfx_rust_out}")
                message(FATAL_ERROR "jfx_rust_lint: cbindgen reported an error on the ${JFX_RUST_CRATE} crate with "
                    "cbindgen-${jfx_rust_kind}.toml (above): the item it names is missing from "
                    "${jfx_rust_header}, so the ABI check would not see it")
            endif()
            _jfx_rust_lines("${jfx_rust_out}" jfx_rust_out_lines)
            foreach(jfx_rust_line IN LISTS jfx_rust_out_lines)
                message(STATUS "jfx_rust_lint: cbindgen (${JFX_RUST_CRATE}, ${jfx_rust_kind}): ${jfx_rust_line}")
            endforeach()
            message(STATUS "jfx_rust_lint: cbindgen ${JFX_CBINDGEN_VERSION} wrote ${jfx_rust_header}")
        endforeach()

        # The ABI check compiles what cbindgen emitted, so each Rust export must be there, and nothing else
        _jfx_rust_declared_functions("${JFX_RUST_GEN_DIR}/${JFX_RUST_LIB_NAME}_rs_functions.h" jfx_rust_emitted)
        set(jfx_rust_problems)
        foreach(jfx_rust_sym IN LISTS JFX_RUST_EXPORTS)
            if(NOT jfx_rust_sym IN_LIST jfx_rust_emitted)
                string(CONCAT jfx_rust_problem "${jfx_rust_sym} is an EXPORTS symbol of a slice of the "
                    "${JFX_RUST_CRATE} crate, and cbindgen emitted no declaration of it: the ABI check cannot see "
                    "its signature. Define it as a plain #[unsafe(no_mangle)] pub extern \"C\" fn in the crate's "
                    "module tree, not through a macro or a re-export.")
                list(APPEND jfx_rust_problems "${jfx_rust_problem}")
            endif()
        endforeach()
        foreach(jfx_rust_sym IN LISTS jfx_rust_emitted)
            if(NOT jfx_rust_sym IN_LIST JFX_RUST_EXPORTS)
                string(CONCAT jfx_rust_problem "${jfx_rust_sym} is an extern \"C\" function of the "
                    "${JFX_RUST_CRATE} crate, and no slice lists it in EXPORTS: no library exports it. Add it to "
                    "EXPORTS of the slice that implements it, or remove it.")
                list(APPEND jfx_rust_problems "${jfx_rust_problem}")
            endif()
        endforeach()
        if(jfx_rust_problems)
            list(JOIN jfx_rust_problems "\n" jfx_rust_text)
            message(FATAL_ERROR "jfx_rust_lint: the functions cbindgen emitted for the ${JFX_RUST_CRATE} crate "
                "differ from the EXPORTS of its slices:\n${jfx_rust_text}")
        endif()
        list(LENGTH jfx_rust_emitted jfx_rust_count)
        message(STATUS "jfx_rust_lint: the ${JFX_RUST_CRATE} crate declares exactly the ${jfx_rust_count} "
            "EXPORTS symbol(s) of its slices")
    elseif(JFX_RUST_ACTION STREQUAL "unexported-list")
        # Every name the crate's archive defines but the EXPORTS of the target's slices, for ld64's
        # -unexported_symbols_list, which skips a name that the link does not define. Apple's nm cannot read the LLVM
        # bitcode that rustc embeds in some members: without --no-llvm-bc it leaves out their names and exits 1.
        execute_process(COMMAND "${JFX_RUST_NM}" --no-llvm-bc -g -U -P "${JFX_RUST_ARCHIVE}"
            RESULT_VARIABLE jfx_rust_rc OUTPUT_VARIABLE jfx_rust_out ERROR_VARIABLE jfx_rust_err)
        if(NOT jfx_rust_rc EQUAL 0)
            message(FATAL_ERROR "jfx_rust: \"${JFX_RUST_NM} --no-llvm-bc -g -U -P ${JFX_RUST_ARCHIVE}\" failed "
                "(${jfx_rust_rc}), so the names of the ${JFX_RUST_CRATE} crate cannot be kept out of the exports of "
                "${JFX_RUST_TARGET}:\n${jfx_rust_err}")
        endif()
        _jfx_rust_nm_names("${jfx_rust_out}" jfx_rust_names)
        list(LENGTH jfx_rust_names jfx_rust_count)
        set(jfx_rust_missing)
        foreach(jfx_rust_sym IN LISTS JFX_RUST_EXPORTS)
            if(NOT "_${jfx_rust_sym}" IN_LIST jfx_rust_names)
                list(APPEND jfx_rust_missing "_${jfx_rust_sym}")
            endif()
            list(REMOVE_ITEM jfx_rust_names "_${jfx_rust_sym}")
        endforeach()
        if(jfx_rust_missing)
            list(JOIN jfx_rust_missing ", " jfx_rust_text)
            message(FATAL_ERROR "jfx_rust: of the ${jfx_rust_count} names that nm lists in ${JFX_RUST_ARCHIVE}, "
                "none is ${jfx_rust_text}, an EXPORTS symbol of a slice of ${JFX_RUST_TARGET}: either the "
                "${JFX_RUST_CRATE} crate does not define it as a #[unsafe(no_mangle)] pub extern \"C\" fn, or nm did "
                "not read every member of the archive")
        endif()
        # ld64 reads *, ? and [ in a line as a pattern, and a line that starts with # as a comment
        string(ASCII 2 jfx_rust_lb)
        string(ASCII 3 jfx_rust_rb)
        set(jfx_rust_patterns)
        foreach(jfx_rust_name IN LISTS jfx_rust_names)
            if(jfx_rust_name MATCHES "[*?${jfx_rust_lb}${jfx_rust_rb}]|^#")
                list(APPEND jfx_rust_patterns "${jfx_rust_name}")
            endif()
        endforeach()
        if(jfx_rust_patterns)
            list(JOIN jfx_rust_patterns "\n  " jfx_rust_text)
            _jfx_rust_ld64_plain("${jfx_rust_text}" jfx_rust_text)
            message(FATAL_ERROR "jfx_rust: ${JFX_RUST_ARCHIVE} defines names that an -unexported_symbols_list "
                "cannot hold, as ld64 would read them as patterns or comments:\n  ${jfx_rust_text}")
        endif()
        list(JOIN jfx_rust_names "\n" jfx_rust_text)
        _jfx_rust_ld64_plain("${jfx_rust_text}" jfx_rust_text)
        file(WRITE "${JFX_RUST_LIST}" "# Generated by jfx_rust.cmake before the link of ${JFX_RUST_TARGET}: the names "
            "that ${JFX_RUST_ARCHIVE} defines, but the EXPORTS of the target's slices\n${jfx_rust_text}\n")
    elseif(JFX_RUST_ACTION STREQUAL "check-origin" AND JFX_RUST_MAP_FORMAT STREQUAL "ld64")
        # ld64's -map: under "# Object files:", lines "[<index>] <file>", an archive member written as
        # <archive as the link command names it>(<member>); under "# Symbols:", lines
        # "<address>\t<size>\t[<index>] <name>", the name with the Mach-O "_", and pseudo names (literal strings,
        # FDEs, Objective-C methods) among them; with -dead_strip, then "# Dead Stripped Symbols:", which define
        # nothing. The log is gone before anything can fail, so that the verdict of an earlier link never outlives
        # this one.
        get_filename_component(jfx_rust_log_dir "${JFX_RUST_LOG}" DIRECTORY)
        file(MAKE_DIRECTORY "${jfx_rust_log_dir}")
        file(REMOVE "${JFX_RUST_LOG}")
        if(NOT EXISTS "${JFX_RUST_MAP}")
            string(CONCAT jfx_rust_text "jfx_rust: no linker map ${JFX_RUST_MAP} after the link of "
                "${JFX_RUST_TARGET}, so the origin of its Rust exports cannot be checked")
            _jfx_rust_origin_fail("${jfx_rust_text}")
        endif()
        string(ASCII 2 jfx_rust_lb)
        string(ASCII 3 jfx_rust_rb)
        file(READ "${JFX_RUST_MAP}" jfx_rust_map_text)
        _jfx_rust_ld64_safe("${jfx_rust_map_text}" jfx_rust_map_text)
        string(FIND "${jfx_rust_map_text}" "\n# Object files:\n" jfx_rust_objects_at)
        string(FIND "${jfx_rust_map_text}" "\n# Sections:\n" jfx_rust_sections_at)
        string(FIND "${jfx_rust_map_text}" "\n# Symbols:\n" jfx_rust_symbols_at)
        if(jfx_rust_objects_at EQUAL -1 OR jfx_rust_sections_at LESS jfx_rust_objects_at
                OR jfx_rust_symbols_at LESS jfx_rust_sections_at)
            string(CONCAT jfx_rust_text "jfx_rust: the linker map ${JFX_RUST_MAP} of ${JFX_RUST_TARGET} lacks "
                "\"# Object files:\", \"# Sections:\" or \"# Symbols:\", or has them in another order")
            _jfx_rust_origin_fail("${jfx_rust_text}")
        endif()
        math(EXPR jfx_rust_length "${jfx_rust_sections_at} - ${jfx_rust_objects_at}")
        string(SUBSTRING "${jfx_rust_map_text}" ${jfx_rust_objects_at} ${jfx_rust_length} jfx_rust_objects)
        string(SUBSTRING "${jfx_rust_map_text}" ${jfx_rust_symbols_at} -1 jfx_rust_symbols)
        unset(jfx_rust_map_text)
        string(FIND "${jfx_rust_symbols}" "\n# Dead Stripped Symbols:" jfx_rust_dead_at)
        if(NOT jfx_rust_dead_at EQUAL -1)
            string(SUBSTRING "${jfx_rust_symbols}" 0 ${jfx_rust_dead_at} jfx_rust_symbols)
        endif()
        string(APPEND jfx_rust_symbols "\n")

        # The indexes of the archive's members, by real path: a relative one is relative to the directory the link
        # runs in, and the build tree may be reached through a symbolic link
        get_filename_component(jfx_rust_archive_path "${JFX_RUST_ARCHIVE}" REALPATH)
        set(jfx_rust_members)
        set(jfx_rust_named_last)
        string(REGEX MATCHALL "\n${jfx_rust_lb} *[0-9]+${jfx_rust_rb} [^\n]*" jfx_rust_entries "${jfx_rust_objects}")
        foreach(jfx_rust_entry IN LISTS jfx_rust_entries)
            string(REGEX MATCH "[0-9]+" jfx_rust_index "${jfx_rust_entry}")
            string(FIND "${jfx_rust_entry}" "${jfx_rust_rb} " jfx_rust_at)
            math(EXPR jfx_rust_at "${jfx_rust_at} + 2")
            string(SUBSTRING "${jfx_rust_entry}" ${jfx_rust_at} -1 jfx_rust_file)
            _jfx_rust_ld64_plain("${jfx_rust_file}" jfx_rust_file)
            set("jfx_rust_file_${jfx_rust_index}" "${jfx_rust_file}")
            if(NOT jfx_rust_file MATCHES "^(.+)[(][^()]+[)]$")
                continue()
            endif()
            set(jfx_rust_named "${CMAKE_MATCH_1}")
            if(NOT "${jfx_rust_named}" STREQUAL "${jfx_rust_named_last}")
                set(jfx_rust_named_last "${jfx_rust_named}")
                set(jfx_rust_named_ours FALSE)
                foreach(jfx_rust_dir IN LISTS JFX_RUST_LINK_DIRS)
                    get_filename_component(jfx_rust_path "${jfx_rust_named}" REALPATH BASE_DIR "${jfx_rust_dir}")
                    if("${jfx_rust_path}" STREQUAL "${jfx_rust_archive_path}")
                        set(jfx_rust_named_ours TRUE)
                    endif()
                endforeach()
            endif()
            if(jfx_rust_named_ours)
                list(APPEND jfx_rust_members ${jfx_rust_index})
            endif()
        endforeach()

        # (a) Each EXPORTS symbol of the slice is defined by a member of the archive, and by nothing else
        set(jfx_rust_found "")
        set(jfx_rust_problems "")
        set(jfx_rust_remedy FALSE)
        foreach(jfx_rust_sym IN LISTS JFX_RUST_EXPORTS)
            string(REGEX MATCHALL "\t${jfx_rust_lb} *[0-9]+${jfx_rust_rb} _${jfx_rust_sym}\n" jfx_rust_lines
                "${jfx_rust_symbols}")
            if(NOT jfx_rust_lines)
                string(APPEND jfx_rust_problems "\n  ${jfx_rust_sym}: the linker map names no file that defines it")
                set(jfx_rust_remedy TRUE)
            endif()
            foreach(jfx_rust_line IN LISTS jfx_rust_lines)
                string(REGEX MATCH "[0-9]+" jfx_rust_index "${jfx_rust_line}")
                set(jfx_rust_object "${jfx_rust_file_${jfx_rust_index}}")
                if(jfx_rust_object STREQUAL "")
                    set(jfx_rust_object "the object file [${jfx_rust_index}], which the map does not list")
                endif()
                if(jfx_rust_index IN_LIST jfx_rust_members)
                    string(APPEND jfx_rust_found "jfx_rust: ${JFX_RUST_TARGET}: ${jfx_rust_sym} is defined by "
                        "${jfx_rust_object}\n")
                elseif(jfx_rust_object MATCHES "^(.+)[(][^()]+[)]$")
                    string(APPEND jfx_rust_problems "\n  ${jfx_rust_sym} is defined by ${jfx_rust_object}: another "
                        "archive defines it, not the ${JFX_RUST_CRATE} crate's archive ${JFX_RUST_ARCHIVE}")
                else()
                    string(APPEND jfx_rust_problems "\n  ${jfx_rust_sym} is defined by ${jfx_rust_object}, not by "
                        "the ${JFX_RUST_CRATE} crate's archive ${JFX_RUST_ARCHIVE}")
                    set(jfx_rust_remedy TRUE)
                endif()
            endforeach()
        endforeach()
        set(jfx_rust_failure "")
        if(NOT jfx_rust_problems STREQUAL "")
            string(CONCAT jfx_rust_failure "jfx_rust: ${JFX_RUST_LIBRARY} (target ${JFX_RUST_TARGET}) does not take "
                "the Rust exports of its slice ${JFX_RUST_SLICE} from Rust:${jfx_rust_problems}\n")
            if(jfx_rust_remedy)
                string(APPEND jfx_rust_failure "A C definition of a slice symbol wins over the archive at link time. "
                    "Compile the C definition out with #ifndef JFX_RUST_${JFX_RUST_SLICE}.\n")
            endif()
        endif()

        # (b) The library exports each of them, and (c) no other name that the map takes from the archive
        execute_process(COMMAND "${JFX_RUST_NM}" -g -U -P "${JFX_RUST_LIBRARY_FILE}"
            RESULT_VARIABLE jfx_rust_rc OUTPUT_VARIABLE jfx_rust_out ERROR_VARIABLE jfx_rust_err)
        if(NOT jfx_rust_rc EQUAL 0)
            string(CONCAT jfx_rust_text "${jfx_rust_failure}jfx_rust: \"${JFX_RUST_NM} -g -U -P "
                "${JFX_RUST_LIBRARY_FILE}\" failed (${jfx_rust_rc}), so the exports of ${JFX_RUST_TARGET} cannot be "
                "checked:\n${jfx_rust_err}")
            _jfx_rust_origin_fail("${jfx_rust_text}")
        endif()
        _jfx_rust_nm_names("${jfx_rust_out}" jfx_rust_exported)
        list(LENGTH jfx_rust_exported jfx_rust_export_count)
        set(jfx_rust_hidden)
        foreach(jfx_rust_sym IN LISTS JFX_RUST_EXPORTS)
            if(NOT "_${jfx_rust_sym}" IN_LIST jfx_rust_exported)
                list(APPEND jfx_rust_hidden "${jfx_rust_sym}")
            endif()
        endforeach()
        if(jfx_rust_hidden)
            list(JOIN jfx_rust_hidden ", " jfx_rust_text)
            string(APPEND jfx_rust_failure "jfx_rust: ${JFX_RUST_LIBRARY} (target ${JFX_RUST_TARGET}) does not "
                "export ${jfx_rust_text} of its slice ${JFX_RUST_SLICE}: nm -g -U of ${JFX_RUST_LIBRARY_FILE} does "
                "not list it. ${JFX_RUST_LIST} must not hold it, and its definition must not be hidden.\n")
        endif()
        set(jfx_rust_allowed)
        foreach(jfx_rust_sym IN LISTS JFX_RUST_TARGET_EXPORTS)
            list(APPEND jfx_rust_allowed "_${jfx_rust_sym}")
        endforeach()
        set(jfx_rust_taken 0)
        if(jfx_rust_members)
            list(JOIN jfx_rust_members "|" jfx_rust_alternatives)
            string(REGEX MATCHALL "\t${jfx_rust_lb} *(${jfx_rust_alternatives})${jfx_rust_rb} [^\n]*" jfx_rust_lines
                "${jfx_rust_symbols}")
            foreach(jfx_rust_line IN LISTS jfx_rust_lines)
                string(FIND "${jfx_rust_line}" "${jfx_rust_rb} " jfx_rust_at)
                math(EXPR jfx_rust_at "${jfx_rust_at} + 2")
                string(SUBSTRING "${jfx_rust_line}" ${jfx_rust_at} -1 jfx_rust_name)
                set("jfx_rust_taken:${jfx_rust_name}" TRUE)
            endforeach()
            list(LENGTH jfx_rust_lines jfx_rust_taken)
        endif()
        set(jfx_rust_leaked)
        foreach(jfx_rust_name IN LISTS jfx_rust_exported)
            if(DEFINED "jfx_rust_taken:${jfx_rust_name}" AND NOT "${jfx_rust_name}" IN_LIST jfx_rust_allowed)
                list(APPEND jfx_rust_leaked "${jfx_rust_name}")
            endif()
        endforeach()
        if(jfx_rust_leaked)
            list(LENGTH jfx_rust_leaked jfx_rust_count)
            list(SUBLIST jfx_rust_leaked 0 20 jfx_rust_leaked)
            list(JOIN jfx_rust_leaked "\n  " jfx_rust_text)
            _jfx_rust_ld64_plain("${jfx_rust_text}" jfx_rust_text)
            string(APPEND jfx_rust_failure "jfx_rust: ${JFX_RUST_LIBRARY} (target ${JFX_RUST_TARGET}) exports "
                "${jfx_rust_count} name(s) that the linker map takes from the ${JFX_RUST_CRATE} crate's archive and "
                "no slice of the target lists in EXPORTS (at most 20 shown):\n  ${jfx_rust_text}\nThe list "
                "${JFX_RUST_LIST}, written before the link, must hold every name the archive defines but those "
                "EXPORTS.\n")
        endif()

        # (d) Nothing but the archive defines a name of the list, as ld64 would hide that definition too. The map
        # gives the descriptor of a thread-local variable that the archive defines to "linker synthesized".
        set(jfx_rust_shadowed)
        if(EXISTS "${JFX_RUST_LIST}")
            file(READ "${JFX_RUST_LIST}" jfx_rust_text)
            _jfx_rust_ld64_safe("${jfx_rust_text}" jfx_rust_text)
            string(REGEX MATCHALL "[^\n]+" jfx_rust_lines "${jfx_rust_text}")
            foreach(jfx_rust_name IN LISTS jfx_rust_lines)
                set("jfx_rust_listed:${jfx_rust_name}" TRUE)
            endforeach()
            string(REGEX MATCHALL "\t${jfx_rust_lb} *[0-9]+${jfx_rust_rb} [^\n]*" jfx_rust_lines "${jfx_rust_symbols}")
            foreach(jfx_rust_line IN LISTS jfx_rust_lines)
                string(REGEX MATCH "^\t${jfx_rust_lb} *([0-9]+)${jfx_rust_rb} (.*)" jfx_rust_line "${jfx_rust_line}")
                if(DEFINED "jfx_rust_listed:${CMAKE_MATCH_2}" AND NOT CMAKE_MATCH_1 IN_LIST jfx_rust_members
                        AND NOT "${jfx_rust_file_${CMAKE_MATCH_1}}" STREQUAL "linker synthesized")
                    _jfx_rust_ld64_safe("${CMAKE_MATCH_2} is defined by ${jfx_rust_file_${CMAKE_MATCH_1}}"
                        jfx_rust_text)
                    list(APPEND jfx_rust_shadowed "${jfx_rust_text}")
                endif()
            endforeach()
        endif()
        if(jfx_rust_shadowed)
            list(LENGTH jfx_rust_shadowed jfx_rust_count)
            list(SUBLIST jfx_rust_shadowed 0 20 jfx_rust_shadowed)
            list(JOIN jfx_rust_shadowed "\n  " jfx_rust_text)
            _jfx_rust_ld64_plain("${jfx_rust_text}" jfx_rust_text)
            string(APPEND jfx_rust_failure "jfx_rust: ${JFX_RUST_LIBRARY} (target ${JFX_RUST_TARGET}) hides "
                "${jfx_rust_count} definition(s) that the linker map takes from outside the ${JFX_RUST_CRATE} crate's "
                "archive (at most 20 shown):\n  ${jfx_rust_text}\nA C symbol has the name of a symbol of the Rust "
                "archive, and so is hidden: the list ${JFX_RUST_LIST} holds every name the archive defines. Rename "
                "the C symbol.\n")
        endif()
        if(NOT jfx_rust_failure STREQUAL "")
            string(STRIP "${jfx_rust_failure}" jfx_rust_failure)
            _jfx_rust_origin_fail("${jfx_rust_failure}")
        endif()
        list(JOIN JFX_RUST_EXPORTS ", " jfx_rust_text)
        get_filename_component(jfx_rust_file "${JFX_RUST_LIBRARY_FILE}" NAME)
        string(APPEND jfx_rust_found "jfx_rust: ${JFX_RUST_TARGET}: ${jfx_rust_file} exports ${jfx_rust_text}, and "
            "none of the other names on the ${jfx_rust_taken} lines of the map that the archive defines (nm -g -U "
            "lists ${jfx_rust_export_count} names)\n")
        _jfx_rust_lines("${jfx_rust_found}" jfx_rust_found_lines)
        foreach(jfx_rust_line IN LISTS jfx_rust_found_lines)
            message(STATUS "${jfx_rust_line}")
        endforeach()
        file(WRITE "${JFX_RUST_LOG}" "${jfx_rust_found}")
    elseif(JFX_RUST_ACTION STREQUAL "check-origin")
        # Gone before anything can fail, so that the verdict of an earlier link never outlives this one
        get_filename_component(jfx_rust_log_dir "${JFX_RUST_LOG}" DIRECTORY)
        file(MAKE_DIRECTORY "${jfx_rust_log_dir}")
        file(REMOVE "${JFX_RUST_LOG}")
        if(NOT EXISTS "${JFX_RUST_MAP}")
            string(CONCAT jfx_rust_text "jfx_rust: no linker map ${JFX_RUST_MAP} after the link of "
                "${JFX_RUST_TARGET}, so the origin of its Rust exports cannot be checked")
            _jfx_rust_origin_fail("${jfx_rust_text}")
        endif()
        if(JFX_RUST_MAP_FORMAT STREQUAL "msvc")
            # An archive member is <archive name>:<member>, and the map names the archive without its directory,
            # so a C object in another archive of the same name would match the name alone. The member must
            # therefore end in .rcgu.o, as rustc's codegen units do, and be one that the librarian lists in the
            # crate's archive itself.
            get_filename_component(jfx_rust_member_prefix "${JFX_RUST_ARCHIVE}" NAME_WLE)
            string(APPEND jfx_rust_member_prefix ":")
            string(LENGTH "${jfx_rust_member_prefix}" jfx_rust_prefix_length)
            # lib.exe lists nothing and exits 0 for an archive that does not exist
            execute_process(COMMAND "${JFX_RUST_AR}" /NOLOGO /LIST "${JFX_RUST_ARCHIVE}"
                RESULT_VARIABLE jfx_rust_rc OUTPUT_VARIABLE jfx_rust_members ERROR_VARIABLE jfx_rust_err)
            string(STRIP "${jfx_rust_members}" jfx_rust_listed)
            if(NOT jfx_rust_rc EQUAL 0 OR jfx_rust_listed STREQUAL "")
                string(CONCAT jfx_rust_text "jfx_rust: the librarian \"${JFX_RUST_AR}\" could not list the members "
                    "of ${JFX_RUST_ARCHIVE} (${jfx_rust_rc}), so the origin of the Rust exports of ${JFX_RUST_TARGET} "
                    "cannot be checked")
                string(STRIP "${jfx_rust_members}${jfx_rust_err}" jfx_rust_output)
                if(NOT jfx_rust_output STREQUAL "")
                    string(APPEND jfx_rust_text ":\n${jfx_rust_output}")
                endif()
                _jfx_rust_origin_fail("${jfx_rust_text}")
            endif()
            string(REPLACE "\r" "" jfx_rust_members "\n${jfx_rust_members}\n")
        else()
            # GNU ld's cross reference table lists the defining file first, an archive member as
            # <archive>(<member>) with the archive as the link command names it, maybe relative to the
            # directory the link runs in
            get_filename_component(jfx_rust_archive_path "${JFX_RUST_ARCHIVE}" REALPATH)
            file(READ "${JFX_RUST_MAP}" jfx_rust_map_text)
            string(FIND "${jfx_rust_map_text}" "\nCross Reference Table" jfx_rust_cref_at)
            if(jfx_rust_cref_at EQUAL -1)
                string(CONCAT jfx_rust_text "jfx_rust: the linker map ${JFX_RUST_MAP} of ${JFX_RUST_TARGET} has "
                    "no cross reference table (--cref)")
                _jfx_rust_origin_fail("${jfx_rust_text}")
            endif()
            string(SUBSTRING "${jfx_rust_map_text}" ${jfx_rust_cref_at} -1 jfx_rust_map_text)
        endif()
        set(jfx_rust_found)
        set(jfx_rust_problems)
        foreach(jfx_rust_sym IN LISTS JFX_RUST_EXPORTS)
            set(jfx_rust_objects)
            if(JFX_RUST_MAP_FORMAT STREQUAL "msvc")
                # 0001:00000000       jfxm_abi_version   0000000180001000 f   jfxmedia:jfxmedia-<hash>.rcgu.o
                file(STRINGS "${JFX_RUST_MAP}" jfx_rust_lines
                    REGEX "^ [0-9a-fA-F]+:[0-9a-fA-F]+[ \t]+${jfx_rust_sym}[ \t]+[0-9a-fA-F]+[ \t]")
                foreach(jfx_rust_line IN LISTS jfx_rust_lines)
                    if(jfx_rust_line MATCHES "[ \t]([^ \t]+)[ \t]*$")
                        list(APPEND jfx_rust_objects "${CMAKE_MATCH_1}")
                    endif()
                endforeach()
            elseif(jfx_rust_map_text MATCHES "\n${jfx_rust_sym}[ \t]*\n?[ \t]+([^\r\n]+)")
                # A name of 50 columns or more puts the file on the next line
                string(STRIP "${CMAKE_MATCH_1}" jfx_rust_object)
                list(APPEND jfx_rust_objects "${jfx_rust_object}")
            endif()
            if(NOT jfx_rust_objects)
                list(APPEND jfx_rust_problems "${jfx_rust_sym}: the linker map names no file that defines it")
            endif()
            foreach(jfx_rust_object IN LISTS jfx_rust_objects)
                set(jfx_rust_from_archive FALSE)
                if(JFX_RUST_MAP_FORMAT STREQUAL "msvc")
                    string(SUBSTRING "${jfx_rust_object}" 0 ${jfx_rust_prefix_length} jfx_rust_head)
                    if(jfx_rust_head STREQUAL jfx_rust_member_prefix AND jfx_rust_object MATCHES "[.]rcgu[.]o$")
                        string(SUBSTRING "${jfx_rust_object}" ${jfx_rust_prefix_length} -1 jfx_rust_member)
                        string(FIND "${jfx_rust_members}" "\n${jfx_rust_member}\n" jfx_rust_at)
                        if(NOT jfx_rust_at EQUAL -1)
                            set(jfx_rust_from_archive TRUE)
                        endif()
                    endif()
                elseif(jfx_rust_object MATCHES "^(.+)[(][^()]+[)]$")
                    set(jfx_rust_named "${CMAKE_MATCH_1}")
                    foreach(jfx_rust_dir IN LISTS JFX_RUST_LINK_DIRS)
                        get_filename_component(jfx_rust_path "${jfx_rust_named}" REALPATH BASE_DIR "${jfx_rust_dir}")
                        if(jfx_rust_path STREQUAL jfx_rust_archive_path)
                            set(jfx_rust_from_archive TRUE)
                        endif()
                    endforeach()
                endif()
                if(jfx_rust_from_archive)
                    list(APPEND jfx_rust_found "${jfx_rust_sym} is defined by ${jfx_rust_object}")
                else()
                    string(CONCAT jfx_rust_problem "${jfx_rust_sym} is defined by ${jfx_rust_object}, not by the "
                        "${JFX_RUST_CRATE} crate's archive ${JFX_RUST_ARCHIVE}")
                    list(APPEND jfx_rust_problems "${jfx_rust_problem}")
                endif()
            endforeach()
        endforeach()
        if(jfx_rust_problems)
            list(JOIN jfx_rust_problems "\n  " jfx_rust_text)
            string(CONCAT jfx_rust_text "jfx_rust: ${JFX_RUST_LIBRARY} (target ${JFX_RUST_TARGET}) does not take "
                "the Rust exports of its slice ${JFX_RUST_SLICE} from Rust:\n  ${jfx_rust_text}\nA C definition of "
                "a slice symbol wins over the archive at link time. Compile the C definition out with "
                "#ifndef JFX_RUST_${JFX_RUST_SLICE}.")
            _jfx_rust_origin_fail("${jfx_rust_text}")
        endif()
        set(jfx_rust_text)
        foreach(jfx_rust_line IN LISTS jfx_rust_found)
            message(STATUS "jfx_rust: ${JFX_RUST_TARGET}: ${jfx_rust_line}")
            string(APPEND jfx_rust_text "jfx_rust: ${JFX_RUST_TARGET}: ${jfx_rust_line}\n")
        endforeach()
        file(WRITE "${JFX_RUST_LOG}" "${jfx_rust_text}")
    else()
        message(FATAL_ERROR "jfx_rust.cmake: unknown JFX_RUST_ACTION \"${JFX_RUST_ACTION}\"")
    endif()
    return()
endif()

include_guard(GLOBAL)

option(JFX_RUST "Link the Rust slices of the native libraries; OFF builds their C implementation" ON)

set(JFX_CBINDGEN_VERSION 0.29.4)
get_filename_component(JFX_RUST_REPO_ROOT "${CMAKE_CURRENT_LIST_DIR}/../.." ABSOLUTE)
set(JFX_RUST_HELPER "${CMAKE_CURRENT_LIST_FILE}")
set(JFX_RUST_SUPPORT_CRATE "${CMAKE_CURRENT_LIST_DIR}/jfx-rust-support")
set(JFX_RUST_LICENCE_SCRIPT "${CMAKE_CURRENT_LIST_DIR}/check-crate-licences.pl")
set(JFX_RUST_CARGO_CONFIG "${CMAKE_CURRENT_LIST_DIR}/cargo-config.toml")
set(JFX_RUST_BINARY_DIR "${CMAKE_BINARY_DIR}/jfx-rust")
set(JFX_RUST_CARGO_TARGET_DIR "${CMAKE_BINARY_DIR}/cargo")
if(APPLE AND CMAKE_OSX_DEPLOYMENT_TARGET)
    # cargo does not fingerprint MACOSX_DEPLOYMENT_TARGET, so an archive built for another value would stay
    string(APPEND JFX_RUST_CARGO_TARGET_DIR "/macos-${CMAKE_OSX_DEPLOYMENT_TARGET}")
endif()
get_property(JFX_RUST_MULTI_CONFIG GLOBAL PROPERTY GENERATOR_IS_MULTI_CONFIG)

function(_jfx_rust_library_name target out_var)
    get_target_property(name ${target} OUTPUT_NAME)
    if(NOT name)
        set(name "${target}")
    endif()
    set(${out_var} "${name}" PARENT_SCOPE)
endfunction()

# The names the headers export. An export is a line that starts with the export macro (<UPPER>_EXPORT) in
# column 0 and holds the function's name and its "("; its parameters may go on. The name is the identifier
# before the line's first "(", so these are configure errors, as a type, an attribute or a macro could be read
# as the name: a "*" or "(" as the first non-blank character after that "(", outside comments (a function
# pointer type); a "(" in the code after the parameter list when the list closes on the line (an attribute, or a
# macro with arguments before the name whose "(" was taken for the list); a name with no lower-case letter or
# one that starts with "__". So is any other line that names an export macro and is no preprocessor line, a
# comment too: the Linux version script made from this list, and the CI export checks, which grep the headers
# for the same form, would miss its function. Lines, not the preprocessor: a declaration under #if counts, once
# if it is repeated under #else, which is why each target passes exactly the headers that make up its export
# list.
function(_jfx_rust_header_exports out_var)
    set(macro_word "(^|[^A-Za-z0-9_])[A-Z][A-Z0-9_]*_EXPORT([^A-Za-z0-9_]|$)")
    # Stands for a closed pair of parentheses; no header line holds it
    string(ASCII 1 group)
    set(names)
    set(problems "")
    foreach(header ${ARGN})
        if(NOT EXISTS "${header}")
            message(FATAL_ERROR "jfx_add_rust_slice: header ${header} does not exist")
        endif()
        file(READ "${header}" text)
        string(REPLACE "\r" "" text "${text}")
        # Cut off line by line, never made a list: a line may hold ";", "[" or "]"
        set(line_number 0)
        while(NOT text STREQUAL "")
            string(FIND "${text}" "\n" end)
            if(end EQUAL -1)
                set(line "${text}")
                set(text "")
            else()
                string(SUBSTRING "${text}" 0 ${end} line)
                math(EXPR end "${end} + 1")
                string(SUBSTRING "${text}" ${end} -1 text)
            endif()
            math(EXPR line_number "${line_number} + 1")
            if(line MATCHES "^[ \t]*#" OR NOT line MATCHES "${macro_word}")
                continue()
            endif()
            string(REGEX MATCH "^[A-Z][A-Z0-9_]*_EXPORT[ \t][^(]*[(]" declaration "${line}")
            string(REGEX REPLACE "^[A-Z][A-Z0-9_]*_EXPORT" "" rest "${line}")
            set(name "")
            if(NOT rest MATCHES "${macro_word}" AND declaration MATCHES "([A-Za-z_][A-Za-z0-9_]*)[ \t]*[(]$")
                set(name "${CMAKE_MATCH_1}")
                string(LENGTH "${declaration}" length)
                string(SUBSTRING "${line}" ${length} -1 parameters)
                # A comment is no code: /* sizeof(T) */ after the declaration opens no parameter list
                string(REGEX REPLACE "/[*]([^*]|[*]+[^*/])*[*]+/" "" parameters "${parameters}")
                string(REGEX REPLACE "(/[*]|//).*$" "" parameters "${parameters}")
                string(REGEX MATCH "[^ \t]" first "${parameters}")
                if(first STREQUAL "")
                    # From the line's own rest on, as a block comment opened there may close lines later. Comments are
                    # cut with string(FIND), not matched: CMake's regular expressions recurse for each repetition of a
                    # group, and the following text is the rest of the header.
                    string(SUBSTRING "${line}" ${length} -1 next)
                    string(APPEND next "\n${text}")
                    while(TRUE)
                        string(REGEX MATCH "[^ \t\n]" first "${next}")
                        if(first STREQUAL "")
                            break()
                        endif()
                        string(FIND "${next}" "${first}" at)
                        string(SUBSTRING "${next}" ${at} 2 opener)
                        if(opener STREQUAL "/*")
                            set(closer "*/")
                        elseif(opener STREQUAL "//")
                            set(closer "\n")
                        else()
                            break()
                        endif()
                        math(EXPR at "${at} + 2")
                        string(SUBSTRING "${next}" ${at} -1 next)
                        string(FIND "${next}" "${closer}" at)
                        if(at EQUAL -1)
                            set(first "")
                            break()
                        endif()
                        string(LENGTH "${closer}" closer_length)
                        math(EXPR at "${at} + ${closer_length}")
                        string(SUBSTRING "${next}" ${at} -1 next)
                    endwhile()
                endif()
                set(closed "(${parameters}")
                while(closed MATCHES "[(][^()]*[)]")
                    string(REGEX REPLACE "[(][^()]*[)]" "${group}" closed "${closed}")
                endwhile()
                string(SUBSTRING "${closed}" 0 1 head)
                string(SUBSTRING "${closed}" 1 -1 after_list)
                string(FIND "${after_list}" "(" open_at)
                string(FIND "${after_list}" "${group}" group_at)
                if(first MATCHES "^[*(]$" OR NOT name MATCHES "[a-z]" OR name MATCHES "^__"
                        OR ("${head}" STREQUAL "${group}" AND (NOT open_at EQUAL -1 OR NOT group_at EQUAL -1)))
                    set(name "")
                endif()
            endif()
            if(name STREQUAL "")
                string(APPEND problems "\n  ${header}:${line_number}: ${line}")
            else()
                list(APPEND names "${name}")
            endif()
        endwhile()
    endforeach()
    if(NOT problems STREQUAL "")
        message(FATAL_ERROR "jfx_add_rust_slice: these header lines name an export macro, but are no export "
            "declaration this build can read:${problems}\nWrite each export as one line that starts with the "
            "macro in column 0 and holds the function's name and its \"(\": <LIB>_EXPORT <type> <name>(...); with "
            "no \"(\" before the name's (a typedef names a function pointer type) and none after its parameter "
            "list, and a name that has a lower-case letter and does not start with \"__\". Where a listed line names "
            "the macro in a comment, reword the comment.")
    endif()
    list(REMOVE_DUPLICATES names)
    list(SORT names)
    set(${out_var} "${names}" PARENT_SCOPE)
endfunction()

function(_jfx_rust_fail_toolchain library problem)
    message(FATAL_ERROR "${library}: its Rust slices need the Rust toolchain ${JFX_RUST_PINNED_VERSION} "
        "pinned in ${JFX_RUST_REPO_ROOT}/rust-toolchain.toml, and ${problem}.\n"
        "Install rustup from https://rustup.rs and run \"rustup toolchain install\" in ${JFX_RUST_REPO_ROOT}, "
        "or configure with -DJFX_RUST=false to build the C implementation of ${library} instead.")
endfunction()

# The command that runs <command> <args> through the cargo action above, logging to logs/<log name>.log.
# $<SEMICOLON> keeps the command one argument of the returned command list.
function(_jfx_rust_action_command out_var log_name)
    get_property(toolchain GLOBAL PROPERTY JFX_RUST_PINNED_VERSION)
    set(command ${ARGN})
    string(REPLACE ";" "$<SEMICOLON>" command "${command}")
    set(deployment_target)
    if(APPLE AND CMAKE_OSX_DEPLOYMENT_TARGET)
        set(deployment_target "-DJFX_RUST_DEPLOYMENT_TARGET=${CMAKE_OSX_DEPLOYMENT_TARGET}")
    endif()
    set(${out_var} "${CMAKE_COMMAND}" -DJFX_RUST_ACTION=cargo
        "-DJFX_RUST_CARGO_TARGET_DIR=${JFX_RUST_CARGO_TARGET_DIR}" "-DJFX_RUST_TOOLCHAIN=${toolchain}"
        "-DJFX_RUST_RUSTC=${JFX_RUST_RUSTC}" ${deployment_target}
        "-DJFX_RUST_LOG=${JFX_RUST_BINARY_DIR}/logs/${log_name}.log"
        "-DJFX_RUST_COMMAND=${command}" -P "${JFX_RUST_HELPER}" PARENT_SCOPE)
endfunction()

function(_jfx_rust_cargo_command out_var log_name)
    _jfx_rust_action_command(command "${log_name}" "${JFX_RUST_CARGO}" ${ARGN})
    set(${out_var} ${command} PARENT_SCOPE)
endfunction()

# The arguments of every cargo call that compiles: the profiles, then split-debuginfo, which has no value that
# every target accepts, then the target, which puts the archive in <cargo directory>/<triple>/
function(_jfx_rust_pins out_var)
    get_property(triple GLOBAL PROPERTY JFX_RUST_TARGET_TRIPLE)
    if(triple MATCHES "-windows-msvc$")
        set(split packed)
    else()
        set(split off)
    endif()
    set(${out_var} --config "${JFX_RUST_CARGO_CONFIG}" --config "profile.release.split-debuginfo='${split}'"
        --config "profile.dev.split-debuginfo='${split}'" --target ${triple} PARENT_SCOPE)
endfunction()

# macOS: the triple of the one architecture the tree builds for, which may differ from rustc's host (an arm64 Mac
# builds x86_64 with CMAKE_OSX_ARCHITECTURES=x86_64)
function(_jfx_rust_darwin_triple library out_var)
    if(CMAKE_OSX_ARCHITECTURES)
        set(archs ${CMAKE_OSX_ARCHITECTURES})
        set(source CMAKE_OSX_ARCHITECTURES)
    else()
        set(archs ${CMAKE_SYSTEM_PROCESSOR})
        set(source CMAKE_SYSTEM_PROCESSOR)
    endif()
    if(archs STREQUAL "arm64")
        set(triple aarch64-apple-darwin)
    elseif(archs STREQUAL "x86_64")
        set(triple x86_64-apple-darwin)
    else()
        list(JOIN archs ", " archs_text)
        message(FATAL_ERROR "${library}: its Rust slices are built for one macOS architecture, arm64 or x86_64, and "
            "this tree builds for ${archs_text} (${source}). Configure with -DCMAKE_OSX_ARCHITECTURES=arm64 or "
            "-DCMAKE_OSX_ARCHITECTURES=x86_64, or with -DJFX_RUST=false to build the C implementation of ${library} "
            "instead.")
    endif()
    set(${out_var} ${triple} PARENT_SCOPE)
endfunction()

# Finds cargo and rustc, checks rustc against the pin, reads its host target, chooses the target triple and probes the
# platform libraries std needs. Runs once per tree, and only for a tree with an enabled slice.
function(_jfx_rust_require_toolchain library)
    get_property(done GLOBAL PROPERTY JFX_RUST_TOOLCHAIN_FOUND)
    if(done)
        return()
    endif()
    if(APPLE)
        _jfx_rust_darwin_triple("${library}" darwin_triple)
    endif()

    set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${JFX_RUST_REPO_ROOT}/rust-toolchain.toml")
    file(STRINGS "${JFX_RUST_REPO_ROOT}/rust-toolchain.toml" channel_lines
        REGEX "^[ \t]*channel[ \t]*=[ \t]*\"[^\"]+\"")
    if(NOT channel_lines MATCHES "\"([^\"]+)\"")
        message(FATAL_ERROR "jfx_rust.cmake: no channel = \"...\" line in ${JFX_RUST_REPO_ROOT}/rust-toolchain.toml")
    endif()
    set(JFX_RUST_PINNED_VERSION "${CMAKE_MATCH_1}")

    # PATH first, then where rustup installs: $CARGO_HOME/bin, else ~/.cargo/bin. Never both: a rustup proxy
    # starts its tool with $CARGO_HOME/bin first on PATH, so one found outside it fails only at build time.
    if(NOT "$ENV{CARGO_HOME}" STREQUAL "")
        set(hint "$ENV{CARGO_HOME}/bin")
    elseif(WIN32)
        set(hint "$ENV{USERPROFILE}/.cargo/bin")
    else()
        set(hint "$ENV{HOME}/.cargo/bin")
    endif()
    find_program(JFX_RUST_CARGO NAMES cargo PATHS ENV PATH "${hint}" NO_DEFAULT_PATH)
    find_program(JFX_RUST_RUSTC NAMES rustc PATHS ENV PATH "${hint}" NO_DEFAULT_PATH)
    if(NOT JFX_RUST_CARGO OR NOT JFX_RUST_RUSTC)
        _jfx_rust_fail_toolchain("${library}" "no cargo or no rustc is on PATH or in ${hint}")
    endif()

    # With the pin in RUSTUP_TOOLCHAIN, as the cargo action runs, so that neither the shell's RUSTUP_TOOLCHAIN
    # nor a rustup override replaces it. A distribution's rustc ignores both, hence the version check.
    set(pinned_env RUSTUP_AUTO_INSTALL=0 "RUSTUP_TOOLCHAIN=${JFX_RUST_PINNED_VERSION}")
    if(APPLE AND CMAKE_OSX_DEPLOYMENT_TARGET)
        # The value the cargo action sets
        list(APPEND pinned_env "MACOSX_DEPLOYMENT_TARGET=${CMAKE_OSX_DEPLOYMENT_TARGET}")
    endif()
    execute_process(COMMAND "${CMAKE_COMMAND}" -E env ${pinned_env} "${JFX_RUST_RUSTC}" -vV
        WORKING_DIRECTORY "${JFX_RUST_REPO_ROOT}"
        RESULT_VARIABLE rc OUTPUT_VARIABLE out ERROR_VARIABLE err OUTPUT_STRIP_TRAILING_WHITESPACE)
    if(NOT rc EQUAL 0 OR NOT out MATCHES "^(rustc ([0-9]+[.][0-9]+[.][0-9]+)[ -][^\r\n]*)")
        _jfx_rust_fail_toolchain("${library}" "\"${JFX_RUST_RUSTC} -vV\" failed: ${out}${err}")
    endif()
    set(version "${CMAKE_MATCH_1}")
    if(NOT CMAKE_MATCH_2 STREQUAL JFX_RUST_PINNED_VERSION)
        _jfx_rust_fail_toolchain("${library}" "${JFX_RUST_RUSTC} is ${version}")
    endif()
    if(NOT out MATCHES "\nhost: ([^\r\n]+)")
        _jfx_rust_fail_toolchain("${library}" "\"${JFX_RUST_RUSTC} -vV\" names no host: ${out}")
    endif()
    set(host "${CMAKE_MATCH_1}")
    message(STATUS "jfx_rust: ${version} (${JFX_RUST_RUSTC}), host ${host}, cargo ${JFX_RUST_CARGO}")

    set(triple "${host}")
    set(probe_target)
    if(APPLE)
        set(triple "${darwin_triple}")
        set(probe_target --target ${triple})
        execute_process(COMMAND "${CMAKE_COMMAND}" -E env ${pinned_env} "${JFX_RUST_RUSTC}" --print target-libdir
                --target ${triple}
            WORKING_DIRECTORY "${JFX_RUST_REPO_ROOT}"
            RESULT_VARIABLE rc OUTPUT_VARIABLE libdir ERROR_VARIABLE err OUTPUT_STRIP_TRAILING_WHITESPACE)
        set(std_rlibs)
        if(rc EQUAL 0 AND NOT libdir STREQUAL "")
            file(GLOB std_rlibs "${libdir}/libstd-*.rlib")
        endif()
        if(NOT std_rlibs)
            string(CONCAT problem "it has no standard library for ${triple} (rustc --print target-libdir --target "
                "${triple}: ${libdir}${err}), one of the targets that rust-toolchain.toml lists for "
                "\"rustup toolchain install\"")
            _jfx_rust_fail_toolchain("${library}" "${problem}")
        endif()
        message(STATUS "jfx_rust: target ${triple}, MACOSX_DEPLOYMENT_TARGET \"${CMAKE_OSX_DEPLOYMENT_TARGET}\", "
            "cargo directory ${JFX_RUST_CARGO_TARGET_DIR}")
    endif()

    # The platform libraries std needs, from rustc itself, for an empty staticlib
    set(probe_dir "${JFX_RUST_BINARY_DIR}/probe")
    file(MAKE_DIRECTORY "${probe_dir}")
    file(WRITE "${probe_dir}/empty.rs" "")
    if(WIN32)
        set(probe_archive "${probe_dir}/jfx_rust_probe.lib")
    else()
        set(probe_archive "${probe_dir}/libjfx_rust_probe.a")
    endif()
    execute_process(COMMAND "${CMAKE_COMMAND}" -E env ${pinned_env}
            "${JFX_RUST_RUSTC}" --crate-type staticlib --crate-name jfx_rust_probe ${probe_target}
            --print native-static-libs -o "${probe_archive}" "${probe_dir}/empty.rs"
        WORKING_DIRECTORY "${JFX_RUST_REPO_ROOT}"
        RESULT_VARIABLE rc OUTPUT_VARIABLE out ERROR_VARIABLE err)
    if(NOT rc EQUAL 0 OR NOT "${out}${err}" MATCHES "native-static-libs: ([^\r\n]*)")
        message(FATAL_ERROR "jfx_rust: rustc --print native-static-libs failed:\n${out}${err}")
    endif()
    string(STRIP "${CMAKE_MATCH_1}" printed)
    if(APPLE)
        # -framework <Name> is one link item, as in the LINK_LIBS of mac.cmake
        string(REGEX MATCHALL "-framework +[^ ]+|[^ ]+" printed "${printed}")
        foreach(lib ${printed})
            if(NOT lib MATCHES "^(-l[^ ]+|-framework +[^ -][^ ]*)$")
                message(FATAL_ERROR "jfx_rust: rustc --print native-static-libs lists \"${lib}\" for the Rust standard "
                    "library, a link item that jfx_rust.cmake does not know: on macOS it knows -l<name> and "
                    "-framework <Name> only")
            endif()
        endforeach()
    else()
        string(REPLACE " " ";" printed "${printed}")
    endif()
    set(libs)
    set(dropped)
    foreach(lib ${printed})
        if(WIN32 AND lib MATCHES "^[/-][Dd][Ee][Ff][Aa][Uu][Ll][Tt][Ll][Ii][Bb]:")
            # The C runtime is CMAKE_MSVC_RUNTIME_LIBRARY's choice, as for the C code of the library. The
            # archive's objects carry no /DEFAULTLIB directive, so with this entry gone a Debug (/MDd) link
            # names no msvcrt.lib and gets no LNK4098, and needs no /NODEFAULTLIB.
            list(APPEND dropped "${lib}")
        elseif(APPLE AND lib MATCHES "^-l(System|c|m)$")
            # libc and libm are libSystem, which clang links anyway: passed again, -lSystem draws ld's "ignoring
            # duplicate libraries" and moves libSystem before libc++ in the load commands
            list(APPEND dropped "${lib}")
        elseif(NOT WIN32 AND NOT APPLE AND lib STREQUAL "-lgcc_s")
            # -static-libgcc of the C link supplies the unwinder
            list(APPEND dropped "${lib}")
        else()
            list(APPEND libs "${lib}")
        endif()
    endforeach()
    list(JOIN libs " " libs_text)
    list(JOIN dropped " " dropped_text)
    if(APPLE AND libs_text STREQUAL "")
        set(libs_text "nothing that clang does not link")
    endif()
    message(STATUS "jfx_rust: the Rust standard library links ${libs_text}; dropped from rustc's list: "
        "${dropped_text}")
    set_property(GLOBAL PROPERTY JFX_RUST_NATIVE_LIBS "${libs}")
    set_property(GLOBAL PROPERTY JFX_RUST_PINNED_VERSION "${JFX_RUST_PINNED_VERSION}")
    # What --target, the archive directory and split-debuginfo follow
    set_property(GLOBAL PROPERTY JFX_RUST_TARGET_TRIPLE "${triple}")
    set_property(GLOBAL PROPERTY JFX_RUST_TOOLCHAIN_FOUND TRUE)
endfunction()

# The cargo step and the IMPORTED staticlib of one crate, created by the first slice that links it
function(_jfx_rust_crate_library crate_dir crate_name out_var)
    string(REPLACE "-" "_" lib_name "${crate_name}")
    set(imported "jfx_rust_${lib_name}")
    set(${out_var} "${imported}" PARENT_SCOPE)
    if(TARGET ${imported})
        return()
    endif()

    if(WIN32)
        set(archive "${lib_name}.lib")
    else()
        set(archive "lib${lib_name}.a")
    endif()
    get_property(triple GLOBAL PROPERTY JFX_RUST_TARGET_TRIPLE)
    set(debug_archive "${JFX_RUST_CARGO_TARGET_DIR}/${triple}/debug/${archive}")
    set(release_archive "${JFX_RUST_CARGO_TARGET_DIR}/${triple}/release/${archive}")
    if(JFX_RUST_MULTI_CONFIG)
        set(profile "$<IF:$<CONFIG:Debug>,dev,release>")
        set(byproducts "${debug_archive}" "${release_archive}")
    else()
        string(TOUPPER "${CMAKE_BUILD_TYPE}" build_type)
        if(build_type STREQUAL "DEBUG")
            set(profile dev)
            set(byproducts "${debug_archive}")
        else()
            set(profile release)
            set(byproducts "${release_archive}")
        endif()
    endif()

    # Always run: cargo decides what is out of date
    _jfx_rust_pins(pins)
    _jfx_rust_cargo_command(cargo_build "${lib_name}-build-${profile}"
        rustc --locked --offline ${pins} -p ${crate_name} --lib --profile ${profile} --crate-type staticlib)
    add_custom_target(${imported}_cargo
        COMMAND ${cargo_build}
        WORKING_DIRECTORY "${JFX_RUST_REPO_ROOT}"
        BYPRODUCTS ${byproducts}
        COMMENT "cargo build of the ${crate_name} crate's staticlib"
        VERBATIM)

    get_property(native_libs GLOBAL PROPERTY JFX_RUST_NATIVE_LIBS)
    add_library(${imported} STATIC IMPORTED)
    set_target_properties(${imported} PROPERTIES INTERFACE_LINK_LIBRARIES "${native_libs}")
    if(JFX_RUST_MULTI_CONFIG)
        set_target_properties(${imported} PROPERTIES
            IMPORTED_LOCATION "${release_archive}"
            IMPORTED_LOCATION_RELEASE "${release_archive}"
            IMPORTED_LOCATION_DEBUG "${debug_archive}"
            MAP_IMPORTED_CONFIG_RELWITHDEBINFO Release
            MAP_IMPORTED_CONFIG_MINSIZEREL Release)
    else()
        set_target_properties(${imported} PROPERTIES IMPORTED_LOCATION "${byproducts}")
    endif()
endfunction()

# The linker map the origin check reads: on Windows the /map: the target already writes, else one in the
# build tree; on macOS ld64's -map, in the build tree, one per configuration in a multi-config tree; on Linux a map
# with the cross reference table, in the build tree
function(_jfx_rust_link_map target out_var)
    get_target_property(map ${target} JFX_RUST_LINK_MAP)
    if(map)
        set(${out_var} "${map}" PARENT_SCOPE)
        return()
    endif()
    if(WIN32)
        set(map)
        get_target_property(options ${target} LINK_OPTIONS)
        foreach(option IN LISTS options)
            if(option MATCHES "^[/-][Mm][Aa][Pp]:(.+)$")
                set(map "${CMAKE_MATCH_1}")
            endif()
        endforeach()
        if(NOT map)
            set(map "${JFX_RUST_BINARY_DIR}/${target}-$<CONFIG>.map")
            target_link_options(${target} PRIVATE "/MAP:${map}")
        endif()
    elseif(APPLE)
        if(JFX_RUST_MULTI_CONFIG)
            set(map "${JFX_RUST_BINARY_DIR}/${target}-$<CONFIG>.map")
        else()
            set(map "${JFX_RUST_BINARY_DIR}/${target}.map")
        endif()
        target_link_options(${target} PRIVATE "LINKER:-map,${map}")
    else()
        set(map "${JFX_RUST_BINARY_DIR}/${target}.map")
        target_link_options(${target} PRIVATE "LINKER:-Map=${map}" "LINKER:--cref")
    endif()
    set_target_properties(${target} PROPERTIES JFX_RUST_LINK_MAP "${map}")
    set(${out_var} "${map}" PARENT_SCOPE)
endfunction()

# macOS, once per target: the -unexported_symbols_list, which a pre-link step writes from nm of the archive that this
# very link uses, so that no archive rebuild, configuration or JFX_RUST toggle leaves it stale. It holds the names of
# one archive, and leaves out the EXPORTS of all slices of the target, which the step reads when it runs.
function(_jfx_rust_darwin_link target crate_dir crate_name imported)
    set_property(TARGET ${target} APPEND PROPERTY JFX_RUST_DARWIN_EXPORTS ${ARGN})
    get_target_property(linked ${target} JFX_RUST_DARWIN_CRATE)
    if(linked)
        if(NOT linked STREQUAL crate_dir)
            message(FATAL_ERROR "jfx_add_rust_slice(${target}): its slices come from ${linked}, and on macOS the "
                "names it keeps local are those of one crate's archive; register no slice of ${crate_dir} for it")
        endif()
        return()
    endif()
    if(NOT CMAKE_NM)
        message(FATAL_ERROR "jfx_add_rust_slice(${target}): CMake found no nm (CMAKE_NM), which lists the names of "
            "the ${crate_name} crate's archive that ${target} keeps local. Configure with -DCMAKE_NM=<nm>, or with "
            "-DJFX_RUST=false to build the C implementation of ${target} instead.")
    endif()
    set(config)
    if(JFX_RUST_MULTI_CONFIG)
        set(config "-$<CONFIG>")
    endif()
    set(list "${JFX_RUST_BINARY_DIR}/${target}${config}.unexported")
    add_custom_command(TARGET ${target} PRE_LINK
        COMMAND "${CMAKE_COMMAND}" -DJFX_RUST_ACTION=unexported-list "-DJFX_RUST_NM=${CMAKE_NM}"
            "-DJFX_RUST_ARCHIVE=$<TARGET_FILE:${imported}>"
            "-DJFX_RUST_EXPORTS=$<TARGET_PROPERTY:${target},JFX_RUST_DARWIN_EXPORTS>" "-DJFX_RUST_LIST=${list}"
            "-DJFX_RUST_TARGET=${target}" "-DJFX_RUST_CRATE=${crate_name}" -P "${JFX_RUST_HELPER}"
        COMMENT "Listing the names of the ${crate_name} crate's archive that ${target} keeps local"
        VERBATIM)
    target_link_options(${target} PRIVATE "LINKER:-unexported_symbols_list,${list}")
    # std's objects carry DWARF even in release, so the link would add a debug map, whose N_OSO names the archive
    # member by its build-tree path. -S drops the whole debug map, the C code's too, hence Release only.
    target_link_options(${target} PRIVATE "$<$<CONFIG:Release>:LINKER:-S>")
    set_target_properties(${target} PROPERTIES JFX_RUST_DARWIN_CRATE "${crate_dir}" JFX_RUST_DARWIN_LIST "${list}")
endfunction()

function(jfx_add_rust_slice target)
    cmake_parse_arguments(SLICE "C_DELETED" "CRATE;SLICE" "HEADERS;EXPORTS;ABI_CHECK_INCLUDE_DIRS" ${ARGN})
    if(NOT TARGET ${target})
        message(FATAL_ERROR "jfx_add_rust_slice: ${target} is not a target")
    endif()
    if(NOT SLICE_CRATE OR NOT SLICE_SLICE OR NOT SLICE_HEADERS OR NOT SLICE_EXPORTS)
        message(FATAL_ERROR "jfx_add_rust_slice(${target}): CRATE, SLICE, HEADERS and EXPORTS are required")
    endif()
    _jfx_rust_library_name(${target} library)
    set(headers)
    foreach(header ${SLICE_HEADERS})
        get_filename_component(header "${header}" ABSOLUTE)
        list(APPEND headers "${header}")
    endforeach()
    set(SLICE_HEADERS ${headers})
    list(JOIN SLICE_HEADERS ", " header_text)
    list(JOIN SLICE_EXPORTS ", " rust_text)

    get_filename_component(crate_dir "${SLICE_CRATE}" ABSOLUTE)
    if(NOT EXISTS "${crate_dir}/Cargo.toml")
        message(FATAL_ERROR "jfx_add_rust_slice(${target}): no Cargo.toml in ${crate_dir}")
    endif()
    get_filename_component(crate_name "${crate_dir}" NAME)

    # The export list is read from the headers at configure time, so a header change must configure again
    set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ${SLICE_HEADERS})
    _jfx_rust_header_exports(exports ${SLICE_HEADERS})
    list(LENGTH exports export_count)
    foreach(sym ${SLICE_EXPORTS})
        if(NOT sym IN_LIST exports)
            message(FATAL_ERROR "jfx_add_rust_slice(${target}): ${sym}, an EXPORTS symbol of the slice "
                "${SLICE_SLICE}, is declared by none of ${header_text}")
        endif()
    endforeach()

    # The same export list with Rust on and off. Anonymous version node: no symbol version is attached.
    if(NOT WIN32 AND NOT APPLE)
        set(script "${JFX_RUST_BINARY_DIR}/${target}.version")
        string(REPLACE ";" ";\n        " globals "${exports}")
        # file(GENERATE) rewrites the script only when its text changes, so a configure run relinks nothing
        file(GENERATE OUTPUT "${script}" CONTENT "/* Generated by jfx_rust.cmake from ${header_text}: the \
${export_count} exports of ${library} (target ${target}) */\n{\n    global:\n        ${globals};\n    local:\n\
        *;\n};\n")
        target_link_options(${target} PRIVATE "LINKER:--version-script=${script}")
        set_property(TARGET ${target} APPEND PROPERTY LINK_DEPENDS "${script}")
    endif()

    if(NOT JFX_RUST)
        if(SLICE_C_DELETED)
            message(FATAL_ERROR "${library}: the C implementation of its slice ${SLICE_SLICE} is deleted, "
                "so it cannot be built with -DJFX_RUST=false")
        endif()
        # Left by an earlier link with Rust, they would say that the library links the crate
        set(stale "${JFX_RUST_BINARY_DIR}/logs/${target}-origin.log")
        if(APPLE)
            foreach(config IN ITEMS "" ${CMAKE_CONFIGURATION_TYPES})
                string(REGEX REPLACE "^(.)" "-\\1" config "${config}")
                list(APPEND stale "${JFX_RUST_BINARY_DIR}/${target}${config}.map"
                    "${JFX_RUST_BINARY_DIR}/${target}${config}.unexported")
            endforeach()
        endif()
        file(REMOVE ${stale})
        message(STATUS "${library} (${target}): slice ${SLICE_SLICE} built from C (JFX_RUST=OFF), "
            "${export_count} exports")
        return()
    endif()

    _jfx_rust_require_toolchain("${library}")
    _jfx_rust_crate_library("${crate_dir}" "${crate_name}" imported)
    target_link_libraries(${target} PRIVATE ${imported})
    add_dependencies(${target} ${imported}_cargo)
    target_compile_definitions(${target} PRIVATE "JFX_RUST_${SLICE_SLICE}=1")
    foreach(sym ${SLICE_EXPORTS})
        if(WIN32)
            target_link_options(${target} PRIVATE "/EXPORT:${sym}")
        elseif(APPLE)
            # Pulls the member out of the archive, and fails the link when nothing defines it
            target_link_options(${target} PRIVATE "LINKER:-u,_${sym}")
        else()
            # Pulls the member out of the archive like --undefined=, and fails the link when nothing defines it
            target_link_options(${target} PRIVATE "LINKER:--require-defined=${sym}")
        endif()
    endforeach()
    if(APPLE)
        _jfx_rust_darwin_link(${target} "${crate_dir}" "${crate_name}" ${imported} ${SLICE_EXPORTS})
    elseif(NOT WIN32)
        # std's prebuilt objects carry debug sections, and cargo's strip setting does not reach a staticlib.
        # Release only: the other configurations are built to be debugged, std's sections included.
        target_link_options(${target} PRIVATE "$<$<CONFIG:Release>:LINKER:--strip-debug>")
    endif()

    _jfx_rust_link_map(${target} map)
    set(ld64_args)
    if(WIN32)
        set(map_format msvc)
        set(librarian "-DJFX_RUST_AR=${CMAKE_AR}")
    elseif(APPLE)
        set(map_format ld64)
        set(librarian)
        get_target_property(list ${target} JFX_RUST_DARWIN_LIST)
        set(ld64_args "-DJFX_RUST_NM=${CMAKE_NM}" "-DJFX_RUST_LIBRARY_FILE=$<TARGET_FILE:${target}>"
            "-DJFX_RUST_TARGET_EXPORTS=$<TARGET_PROPERTY:${target},JFX_RUST_DARWIN_EXPORTS>" "-DJFX_RUST_LIST=${list}")
    else()
        set(map_format gnu)
        set(librarian)
    endif()
    # A generator may name the archive relative to the top of the build tree or to the target's directory
    get_target_property(target_binary_dir ${target} BINARY_DIR)
    add_custom_command(TARGET ${target} POST_BUILD
        COMMAND "${CMAKE_COMMAND}" -DJFX_RUST_ACTION=check-origin "-DJFX_RUST_MAP=${map}"
            "-DJFX_RUST_MAP_FORMAT=${map_format}" "-DJFX_RUST_ARCHIVE=$<TARGET_FILE:${imported}>" ${librarian}
            ${ld64_args} "-DJFX_RUST_LINK_DIRS=${CMAKE_BINARY_DIR};${target_binary_dir}"
            "-DJFX_RUST_TARGET=${target}" "-DJFX_RUST_LOG=${JFX_RUST_BINARY_DIR}/logs/${target}-origin.log"
            "-DJFX_RUST_LIBRARY=${library}" "-DJFX_RUST_CRATE=${crate_name}"
            "-DJFX_RUST_SLICE=${SLICE_SLICE}" "-DJFX_RUST_EXPORTS=${SLICE_EXPORTS}" -P "${JFX_RUST_HELPER}"
        COMMENT "Checking that ${library} (${target}) takes ${rust_text} from the ${crate_name} crate"
        VERBATIM)

    set_property(GLOBAL APPEND PROPERTY JFX_RUST_LINKED_CRATES "${crate_dir}")
    set_property(GLOBAL APPEND PROPERTY JFX_RUST_EXPORTS_${crate_name} ${SLICE_EXPORTS})
    foreach(header ${SLICE_HEADERS})
        get_filename_component(header_dir "${header}" DIRECTORY)
        set_property(GLOBAL APPEND PROPERTY JFX_RUST_ABI_INCLUDE_DIRS_${crate_name} "${header_dir}")
    endforeach()
    set_property(GLOBAL APPEND PROPERTY JFX_RUST_ABI_INCLUDE_DIRS_${crate_name} ${SLICE_ABI_CHECK_INCLUDE_DIRS})

    message(STATUS "${library} (${target}): slice ${SLICE_SLICE} from the ${crate_name} crate, "
        "${rust_text} of ${export_count} exports in Rust")
endfunction()

# jfx_rust_test and jfx_rust_lint, once every slice of the tree is registered
function(_jfx_rust_define_check_targets)
    get_property(crates GLOBAL PROPERTY JFX_RUST_LINKED_CRATES)
    if(NOT crates)
        add_custom_target(jfx_rust_test
            COMMAND "${CMAKE_COMMAND}" -E echo "jfx_rust_test: no Rust slice is enabled in this tree"
            VERBATIM)
        add_custom_target(jfx_rust_lint
            COMMAND "${CMAKE_COMMAND}" -E echo "jfx_rust_lint: no Rust slice is enabled in this tree"
            VERBATIM)
        return()
    endif()
    list(REMOVE_DUPLICATES crates)

    set(packages)
    foreach(crate_dir ${crates} "${JFX_RUST_SUPPORT_CRATE}")
        get_filename_component(crate_name "${crate_dir}" NAME)
        list(APPEND packages -p ${crate_name})
    endforeach()
    list(JOIN packages " " packages_text)
    _jfx_rust_pins(pins)

    _jfx_rust_cargo_command(cargo_test test test --locked --offline ${pins} ${packages})
    add_custom_target(jfx_rust_test
        COMMAND ${cargo_test}
        WORKING_DIRECTORY "${JFX_RUST_REPO_ROOT}"
        COMMENT "jfx_rust_test: cargo test ${packages_text}"
        VERBATIM)

    _jfx_rust_cargo_command(cargo_fmt fmt fmt --check ${packages})
    _jfx_rust_cargo_command(cargo_clippy clippy
        clippy --locked --offline ${pins} --all-targets ${packages} -- -D warnings)
    add_custom_target(jfx_rust_lint_cargo
        COMMAND ${cargo_fmt}
        COMMAND ${cargo_clippy}
        WORKING_DIRECTORY "${JFX_RUST_REPO_ROOT}"
        COMMENT "jfx_rust_lint: cargo fmt --check and cargo clippy -D warnings ${packages_text}"
        VERBATIM)

    set(abi_checks)
    foreach(crate_dir ${crates})
        get_filename_component(crate_name "${crate_dir}" NAME)
        string(REPLACE "-" "_" lib_name "${crate_name}")
        if(NOT EXISTS "${crate_dir}/abi_check.cpp")
            message(FATAL_ERROR "jfx_rust: the library crate ${crate_name} has no abi_check.cpp in ${crate_dir}")
        endif()
        get_property(crate_exports GLOBAL PROPERTY JFX_RUST_EXPORTS_${crate_name})
        list(REMOVE_DUPLICATES crate_exports)
        set(gen_dir "${JFX_RUST_BINARY_DIR}/${lib_name}/abi-check")
        add_custom_target(jfx_rust_abi_${lib_name}_headers
            COMMAND "${CMAKE_COMMAND}" -DJFX_RUST_ACTION=cbindgen
                "-DJFX_CBINDGEN_VERSION=${JFX_CBINDGEN_VERSION}" "-DJFX_RUST_CRATE=${crate_name}"
                "-DJFX_RUST_LIB_NAME=${lib_name}" "-DJFX_RUST_CRATE_DIR=${crate_dir}"
                "-DJFX_RUST_GEN_DIR=${gen_dir}" "-DJFX_RUST_EXPORTS=${crate_exports}" -P "${JFX_RUST_HELPER}"
            WORKING_DIRECTORY "${JFX_RUST_REPO_ROOT}"
            COMMENT "jfx_rust_lint: cbindgen of the ${crate_name} crate for its ABI check"
            VERBATIM)
        add_dependencies(jfx_rust_abi_${lib_name}_headers jfx_rust_lint_cargo)

        # Compiled, never linked: as C++, where a second extern "C" declaration with other types is an error
        get_property(include_dirs GLOBAL PROPERTY JFX_RUST_ABI_INCLUDE_DIRS_${crate_name})
        list(REMOVE_DUPLICATES include_dirs)
        add_library(jfx_rust_abi_${lib_name} OBJECT EXCLUDE_FROM_ALL "${crate_dir}/abi_check.cpp")
        set_target_properties(jfx_rust_abi_${lib_name} PROPERTIES CXX_STANDARD 14 CXX_STANDARD_REQUIRED ON)
        target_include_directories(jfx_rust_abi_${lib_name} PRIVATE ${include_dirs} "${gen_dir}")
        if(MSVC)
            target_compile_options(jfx_rust_abi_${lib_name} PRIVATE /nologo /W4 /WX /EHsc)
        else()
            target_compile_options(jfx_rust_abi_${lib_name} PRIVATE -Wall -Wextra -Werror)
        endif()
        add_dependencies(jfx_rust_abi_${lib_name} jfx_rust_abi_${lib_name}_headers)
        list(APPEND abi_checks jfx_rust_abi_${lib_name})
    endforeach()

    # A NOTFOUND result is searched again by the next configure, so installing perl later needs no cache edit
    find_program(JFX_RUST_PERL NAMES perl)
    set(perl_places "on PATH")
    set(perl_remedy "Put a perl on PATH")
    if(WIN32)
        set(perl_places "on PATH or in the usr/bin of the Git for Windows that the git on PATH belongs to")
        set(perl_remedy "Put a perl on PATH, or install Git for Windows, which ships one,")
    endif()
    if(NOT JFX_RUST_PERL AND WIN32)
        # git.exe is in <Git>/cmd, <Git>/bin or <Git>/mingw64/bin; the perl of Git for Windows is <Git>/usr/bin
        find_program(JFX_RUST_GIT NAMES git)
        set(git "${JFX_RUST_GIT}")
        unset(JFX_RUST_GIT CACHE)
        if(git)
            get_filename_component(git_root "${git}" REALPATH)
            get_filename_component(git_root "${git_root}" DIRECTORY)
            get_filename_component(git_dir_name "${git_root}" NAME)
            get_filename_component(git_root "${git_root}" DIRECTORY)
            get_filename_component(git_parent_name "${git_root}" NAME)
            if(git_dir_name STREQUAL "bin" AND git_parent_name STREQUAL "mingw64")
                get_filename_component(git_root "${git_root}" DIRECTORY)
            endif()
            find_program(JFX_RUST_PERL NAMES perl PATHS "${git_root}/usr/bin" NO_DEFAULT_PATH)
        endif()
    endif()
    if(JFX_RUST_PERL)
        message(STATUS "jfx_rust: perl for the licence check: ${JFX_RUST_PERL}")
        # The script calls a crate shipped only when its Cargo.toml lists a staticlib; --linked makes it
        # check that each crate the slices link does
        set(linked)
        foreach(crate_dir ${crates})
            get_filename_component(crate_name "${crate_dir}" NAME)
            list(APPEND linked --linked ${crate_name})
        endforeach()
        # Its cargo metadata runs in the environment of every other cargo call
        _jfx_rust_action_command(licence_check licences "${JFX_RUST_PERL}" "${JFX_RUST_LICENCE_SCRIPT}"
            --cargo "${JFX_RUST_CARGO}" --config "${JFX_RUST_CARGO_CONFIG}" ${linked})
        set(licence_commands
            COMMAND "${JFX_RUST_PERL}" "${JFX_RUST_LICENCE_SCRIPT}" --self-test
            COMMAND ${licence_check})
        set(licence_comment "self-test of the licence check, then the licence check of the linked crates")
    else()
        set(no_perl "jfx_rust_lint: the licence check ${JFX_RUST_LICENCE_SCRIPT} needs perl, and at configure "
            "time none was ${perl_places}. ${perl_remedy} and configure again.")
        string(CONCAT no_perl ${no_perl})
        set(licence_commands COMMAND "${CMAKE_COMMAND}" -DJFX_RUST_ACTION=fail
            "-DJFX_RUST_MESSAGE=${no_perl}" -P "${JFX_RUST_HELPER}")
        set(licence_comment "the licence check of the linked crates fails: configure found no perl")
    endif()
    add_custom_target(jfx_rust_lint
        ${licence_commands}
        WORKING_DIRECTORY "${JFX_RUST_REPO_ROOT}"
        COMMENT "jfx_rust_lint: ${licence_comment}"
        VERBATIM)
    add_dependencies(jfx_rust_lint jfx_rust_lint_cargo ${abi_checks})
endfunction()

cmake_language(DEFER CALL _jfx_rust_define_check_targets)
