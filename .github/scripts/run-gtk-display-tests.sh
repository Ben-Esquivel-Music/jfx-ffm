#!/bin/bash
#
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
#

#
# Runs the GTK glass display tests of javafx.graphics - the classes of test/com/sun/glass/ui/gtk - on a private Xvfb,
# twice: first with no window manager, then under openbox. The Linux jobs of .github/workflows/submit.yml run it
# after their install step; it runs unchanged on any Linux machine with Xvfb, openbox, xdpyinfo, xprop, perl and
# dbus-run-session, once a build has left the javafx.graphics natives in modules/javafx.graphics/target/native/bin.
#
# Each run starts its own Xvfb with one 1920x1080 screen at depth 24, the display that
# GtkGlassChildJvm.CAPTURE_ENVIRONMENT describes, exports DISPLAY for it with WAYLAND_DISPLAY unset, and runs
#
#   mvn -B -ntp -pl modules/javafx.graphics test -DskipNative=true -Dtest='test/com/sun/glass/ui/gtk/*Test' \
#       -Dsurefire.failIfNoSpecifiedTests=false -DPRIVATE_DISPLAY=true -DUSE_ROBOT=true [arguments of this script]
#
# -DPRIVATE_DISPLAY=true is the flag of GtkGlassChildJvm.requireDisplay that says DISPLAY names a private X server,
# and -DUSE_ROBOT=true lets the input-injecting classes (GtkGlassChildJvm.requireRobot) run too.
# -Djfx.parity.require=true is not passed: the expectations bound to the captured GTK and X server versions skip
# where those differ, and that flag would turn each skip into a failure.
#
# Maven runs on a session bus of its own, from a dbus-run-session started without DISPLAY. Without a session bus,
# the first GTK child autolaunches one through X11 (dbus-launch), and the at-spi-bus-launcher activated on it
# connects to the private display too; both stay connected. The X server then never resets between the children of
# the run without a window manager, so the pointer stays where the robot classes left it, and the
# no-window-manager trace goldens (GtkTraceGolden), which were captured with a fresh pointer for every child, fail.
# A bus whose daemon has no DISPLAY starts nothing on the display, and the run fails if AT_SPI_BUS is still set on
# the root window afterwards; the private bus also keeps the tests off the session bus of a developer's desktop.
#
# A run fails when Maven fails, when its setup is not the one it claims, when a class reports no tests that it
# should have, when it leaves a fatal error log, or when openbox does not survive it. Both runs always execute;
# the script exits 1 at the end if either failed (2 if it could not start at all).
#
# The setup proof: every child that calls GtkGlassChild.recordEnvironment writes env.* into
# target/gtk-glass-child/<scenario>/values.txt. At least one child of each run must have, every env.screen must be
# 1920x1080 and every env.depth 24, and every env.windowManager must be "unknown" without a window manager and
# "Openbox" under openbox. So a window manager that died during the run, or an X server with another screen
# (xvfb-run's default is 1280x1024x24), fails the run instead of passing as something it is not.
#
# Usage: bash .github/scripts/run-gtk-display-tests.sh [extra Maven arguments, such as -Dmaven.repo.local=<dir>]
# Environment, all optional:
#   GTK_TEST_DISPLAY     the display to start Xvfb on (default :99); one that already answers is refused
#   GTK_TEST_XVFB        the Xvfb command (default Xvfb)
#   GTK_TEST_OPENBOX     the openbox command (default openbox)
#   GTK_TEST_RUNS        the runs to make, in order (default "nowm openbox")
#   GITHUB_STEP_SUMMARY  a file to append the report to in Markdown, as GitHub Actions sets it
# Output, per run: modules/javafx.graphics/target/gtk-display-tests/<run>/ with report.txt, maven.log,
# surefire-reports, gtk-glass-child, xvfb.log, openbox.log and any hs_err_pid*.log. A surefire-reports or
# gtk-glass-child directory that an earlier build left in target is first moved to <name>.before-gtk-display-tests.
#

set -u -o pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd) || exit 2
MODULE=$ROOT/modules/javafx.graphics
TARGET=$MODULE/target
OUT=$TARGET/gtk-display-tests
SOURCES=$MODULE/src/test/java/test/com/sun/glass/ui/gtk
PACKAGE=test.com.sun.glass.ui.gtk
TEST_PATTERN='test/com/sun/glass/ui/gtk/*Test'
SCREEN=1920x1080
DEPTH=24

DISPLAY_NAME=${GTK_TEST_DISPLAY:-:99}
read -r -a XVFB_COMMAND <<< "${GTK_TEST_XVFB:-Xvfb}"
read -r -a OPENBOX_COMMAND <<< "${GTK_TEST_OPENBOX:-openbox}"
read -r -a RUNS <<< "${GTK_TEST_RUNS:-nowm openbox}"
EXTRA_MAVEN_ARGS=("$@")

XVFB_PID=
OPENBOX_PID=
FAILED_RUNS=()

# The classes of the package that report "Tests run: 0" in a run, each with its reason ("<class><TAB><reason>").
# Surefire records no reason for a class-level skip, so this list is where it is written down. Every other class of
# the package must run tests, and a class listed here must not.
expected_zero_classes() {
    local class reason
    case "$1" in
        nowm)
            # Every class runs without a window manager, the six robot classes included (-DUSE_ROBOT=true).
            ;;
        openbox)
            reason="GtkTraceGolden.requireNoWindowManager: it scripts window positions, stacking and focus for an"
            reason+=" X server without a window manager"
            for class in GtkDnDTargetTest GtkDnDTraceTest GtkNullSlotsTest GtkUpcallExceptionTest \
                    GtkWindowStateTraceTest; do
                printf '%s\t%s\n' "$class" "$reason"
            done
            ;;
    esac
}

describe() {
    case "$1" in
        nowm) echo "no window manager" ;;
        openbox) echo "openbox" ;;
    esac
}

expected_window_manager() {
    case "$1" in
        nowm) echo "unknown" ;;
        openbox) echo "Openbox" ;;
    esac
}

# Prints a GitHub Actions error annotation and records it for the run's report.
error() {
    echo "::error::$*"
    if [ -n "${RUN_DIR:-}" ]; then
        echo "$*" >> "$RUN_DIR/errors.txt"
    fi
}

minutes() {
    printf '%d min %02d s' $(($1 / 60)) $(($1 % 60))
}

cleanup() {
    stop_process "$OPENBOX_PID"
    stop_process "$XVFB_PID"
}

stop_process() {
    if [ -n "$1" ] && kill -0 "$1" 2> /dev/null; then
        kill "$1" 2> /dev/null
        wait "$1" 2> /dev/null
    fi
}

root_property() {
    xprop -display "$DISPLAY_NAME" -root "$1" 2> /dev/null
}

# Starts Xvfb and waits until it answers. No -dpi: the 96 dpi of CAPTURE_ENVIRONMENT is GDK's resolution
# (env.resolution), which is 96 whatever physical size the X server reports, and the expectations were captured on
# an Xvfb with its default size (100 dpi).
start_xvfb() {
    if xdpyinfo -display "$DISPLAY_NAME" > /dev/null 2>&1; then
        error "display $DISPLAY_NAME already answers; the GTK display tests need an X server of their own"
        return 1
    fi
    "${XVFB_COMMAND[@]}" "$DISPLAY_NAME" -screen 0 "${SCREEN}x${DEPTH}" -nolisten tcp \
        > "$RUN_DIR/xvfb.log" 2>&1 < /dev/null &
    XVFB_PID=$!
    local i
    for ((i = 0; i < 150; i++)); do
        if xdpyinfo -display "$DISPLAY_NAME" > "$RUN_DIR/xdpyinfo.txt" 2>&1; then
            echo "Xvfb $DISPLAY_NAME is up (pid $XVFB_PID):" \
                "$(grep -E '^ *(dimensions|resolution|depth of root window):' "$RUN_DIR/xdpyinfo.txt" \
                    | tr -s ' ' | paste -sd ';' -)"
            return 0
        fi
        if ! kill -0 "$XVFB_PID" 2> /dev/null; then
            break
        fi
        sleep 0.2
    done
    error "Xvfb did not start on $DISPLAY_NAME; its log: $(tr '\n' ' ' < "$RUN_DIR/xvfb.log")"
    return 1
}

# Starts openbox in a session of its own and waits until it has set both _NET_SUPPORTING_WM_CHECK and
# _NET_WORKAREA on the root window: a test that starts once only the first is there can still race openbox.
start_openbox() {
    setsid "${OPENBOX_COMMAND[@]}" > "$RUN_DIR/openbox.log" 2>&1 < /dev/null &
    OPENBOX_PID=$!
    local i
    for ((i = 0; i < 150; i++)); do
        if root_property _NET_SUPPORTING_WM_CHECK | grep -q 'window id' \
                && root_property _NET_WORKAREA | grep -q '(CARDINAL) = '; then
            if openbox_alive; then
                echo "openbox is up (pid $OPENBOX_PID): $(root_property _NET_WORKAREA)"
                return 0
            fi
            break
        fi
        if ! kill -0 "$OPENBOX_PID" 2> /dev/null; then
            break
        fi
        sleep 0.2
    done
    error "openbox did not start on $DISPLAY_NAME; its log: $(tr '\n' ' ' < "$RUN_DIR/openbox.log")"
    return 1
}

# Openbox's process is alive and the root window still names a supporting window that says it is Openbox.
openbox_alive() {
    kill -0 "$OPENBOX_PID" 2> /dev/null || return 1
    local id
    id=$(root_property _NET_SUPPORTING_WM_CHECK | sed -n 's/.*window id # *//p')
    [ -n "$id" ] && xprop -display "$DISPLAY_NAME" -id "$id" _NET_WM_NAME 2> /dev/null | grep -q '"Openbox"'
}

# Checksums, sizes and times of the natives the tests load, so that the end can show -DskipNative=true kept them.
natives_state() {
    (cd "$TARGET/native/bin" && find . -maxdepth 1 -type f -name '*.so' -printf '%f %s %T@\n' | sort \
        && sha256sum ./*.so)
}

# The per-class counts of the run's Maven log, "<class><TAB><tests><TAB><failures><TAB><errors><TAB><skipped>".
# The log, not the surefire XML: a report file is per top-level class, and nested classes share it.
class_counts() {
    perl -ne 'my $counts = qr/Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)/;
        print "$5\t$1\t$2\t$3\t$4\n" if /$counts.* in test\.com\.sun\.glass\.ui\.gtk\.([\w\$]+)\s*$/' "$1"
}

# The skipped tests of a surefire reports directory, "<class>.<method><TAB><reason>". JUnit records an assumption's
# message as the first line of the <skipped> element's text; @Disabled records it as its message attribute.
skipped_tests() {
    local reports=("$1"/TEST-"$PACKAGE".*.xml)
    [ -e "${reports[0]}" ] || return 0
    perl -0777 -ne '
        while (/<testcase\b([^>]*?)(?:\/>|>(.*?)<\/testcase>)/gs) {
            my ($attributes, $body) = ($1, defined $2 ? $2 : "");
            next unless $body =~ /<skipped\b([^>]*?)(?:\/>|>(.*?)<\/skipped>)/s;
            my ($skippedAttributes, $text) = ($1, defined $2 ? $2 : "");
            my ($name) = $attributes =~ /\bname="([^"]*)"/;
            my ($class) = $attributes =~ /\bclassname="([^"]*)"/;
            my ($message) = $skippedAttributes =~ /\bmessage="([^"]*)"/;
            $text =~ s/^\s*<!\[CDATA\[//;
            my $reason = defined $message && $message ne "" ? $message : (split /\n/, $text)[0];
            $reason = "(no reason recorded)" unless defined $reason && $reason ne "";
            $reason =~ s/\]\]>.*//;
            $reason =~ s/^org\.opentest4j\.TestAbortedException: //;
            $reason =~ s/^Assumption failed: //;
            $reason =~ s/&lt;/</g;
            $reason =~ s/&gt;/>/g;
            $reason =~ s/&quot;/"/g;
            $reason =~ s/&apos;/\x27/g;
            $reason =~ s/&amp;/&/g;
            $reason =~ s/\s+/ /g;
            $class =~ s/^test\.com\.sun\.glass\.ui\.gtk\.//;
            print "$class.$name\t$reason\n";
        }' "${reports[@]}"
}

# Fails the run unless its children recorded the screen, depth and window manager that the run claims.
prove_setup() {
    local run=$1 wm values=() file key bad
    wm=$(expected_window_manager "$run")
    for file in "$RUN_DIR"/gtk-glass-child/*/values.txt; do
        if [ -f "$file" ] && grep -q '^env\.screen=' "$file"; then
            values+=("$file")
        fi
    done
    if [ ${#values[@]} -eq 0 ]; then
        error "$run: setup unproven: no child recorded env.* in target/gtk-glass-child/*/values.txt"
        return
    fi
    {
        echo "Setup proof: ${#values[@]} children recorded env.*:"
        for key in env.screen env.depth env.windowManager env.resolution env.scale env.compositing env.gtk \
                env.xserver; do
            echo "  $key: $(grep -h "^${key//./\\.}=" "${values[@]}" | sed 's/^[^=]*=//' | sort | uniq -c \
                | sed -E 's/^ *([0-9]+) (.*)$/\2 (\1)/' | paste -sd ',' - | sed 's/,/, /g')"
        done
    } | tee -a "$RUN_DIR/report.txt"
    for key in "env.screen=$SCREEN" "env.depth=$DEPTH" "env.windowManager=$wm"; do
        bad=$(grep -L -x -F "$key" "${values[@]}" | sed -E 's#.*/gtk-glass-child/([^/]+)/values.txt#\1#' \
            | head -5 | paste -sd ' ' -)
        if [ -n "$bad" ]; then
            error "$run: setup proof failed: not every child recorded $key, for example: $bad"
        fi
    done
}

# Fails the run unless every class of the package reported a result, and each one reported tests unless the
# run's list says why it reports none.
check_counts() {
    local run=$1 class tests failures errors skipped reason expected=() file
    local -A count=() zero=()
    while IFS=$'\t' read -r class tests failures errors skipped; do
        count[$class]=$tests
    done < "$RUN_DIR/counts.tsv"
    while IFS=$'\t' read -r class reason; do
        zero[$class]=$reason
    done < <(expected_zero_classes "$run")
    for file in "$SOURCES"/*Test.java; do
        [ -f "$file" ] && expected+=("$(basename "$file" .java)")
    done
    if [ ${#expected[@]} -eq 0 ]; then
        error "$run: no *Test.java in $SOURCES"
        return
    fi
    for class in "${expected[@]}"; do
        if [ -z "${count[$class]+set}" ]; then
            error "$run: $class reported no result: the test pattern $TEST_PATTERN no longer selects it, or its" \
                "test JVM died"
        elif [ "${count[$class]}" = 0 ] && [ -z "${zero[$class]+set}" ]; then
            error "$run: $class reported Tests run: 0, and it is not on this run's list of classes that may"
        elif [ "${count[$class]}" != 0 ] && [ -n "${zero[$class]+set}" ]; then
            error "$run: $class ran ${count[$class]} tests, but this run's list says it runs none" \
                "(${zero[$class]}); update expected_zero_classes in this script"
        fi
    done
    for class in "${!zero[@]}"; do
        if [ ! -f "$SOURCES/$class.java" ]; then
            error "$run: expected_zero_classes lists $class, which is not a class of $SOURCES"
        fi
    done
}

report_run() {
    local run=$1 seconds=$2 maven_exit=$3 result=passed class tests failures errors skipped reason
    local classes=0 total=0 total_failures=0 total_errors=0 total_skipped=0 zeros=0
    if [ -s "$RUN_DIR/errors.txt" ]; then
        result=FAILED
    fi
    while IFS=$'\t' read -r class tests failures errors skipped; do
        classes=$((classes + 1))
        total=$((total + tests))
        total_failures=$((total_failures + failures))
        total_errors=$((total_errors + errors))
        total_skipped=$((total_skipped + skipped))
        if [ "$tests" = 0 ]; then
            zeros=$((zeros + 1))
        fi
    done < "$RUN_DIR/counts.tsv"
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$run" "$result" "$seconds" "$classes" "$total" \
        "$total_failures" "$total_errors" "$total_skipped" "$zeros" > "$RUN_DIR/totals.tsv"
    {
        echo "== GTK display tests, $(describe "$run") (Xvfb $DISPLAY_NAME ${SCREEN}x${DEPTH}): $result in" \
            "$(minutes "$seconds"), Maven exit code $maven_exit"
        echo "classes $classes, tests $total, failures $total_failures, errors $total_errors," \
            "skipped $total_skipped; classes with Tests run: 0: $zeros"
        printf '  %-36s %6s %9s %7s %8s\n' class tests failures errors skipped
        while IFS=$'\t' read -r class tests failures errors skipped; do
            printf '  %-36s %6s %9s %7s %8s\n' "$class" "$tests" "$failures" "$errors" "$skipped"
        done < <(sort "$RUN_DIR/counts.tsv")
        echo "Classes with Tests run: 0, and why:"
        while IFS=$'\t' read -r class tests failures errors skipped; do
            if [ "$tests" = 0 ]; then
                reason=$(expected_zero_classes "$run" | awk -F '\t' -v c="$class" '$1 == c { print $2 }')
                echo "  $class: ${reason:-not expected}"
            fi
        done < <(sort "$RUN_DIR/counts.tsv")
        echo "Skipped tests, and why:"
        awk -F '\t' '{ print "  " $1 ": " $2 }' "$RUN_DIR/skipped.tsv"
        if [ -s "$RUN_DIR/errors.txt" ]; then
            echo "Errors:"
            sed 's/^/  /' "$RUN_DIR/errors.txt"
        fi
    } | tee -a "$RUN_DIR/report.txt"
}

run_tests() {
    local run=$1 start seconds maven_exit=none file bus hs_err=()
    RUN_DIR=$OUT/$run
    mkdir -p "$RUN_DIR"
    : > "$RUN_DIR/errors.txt"
    : > "$RUN_DIR/report.txt"
    : > "$RUN_DIR/counts.tsv"
    : > "$RUN_DIR/skipped.tsv"
    echo "::group::GTK display tests, $(describe "$run")"
    export DISPLAY=$DISPLAY_NAME
    unset WAYLAND_DISPLAY
    start=$SECONDS
    if start_xvfb && { [ "$run" != openbox ] || start_openbox; }; then
        touch "$RUN_DIR/.started"
        (cd "$ROOT" && env -u DISPLAY dbus-run-session -- env "DISPLAY=$DISPLAY_NAME" \
            mvn -B -ntp -pl modules/javafx.graphics test -DskipNative=true "-Dtest=$TEST_PATTERN" \
            -Dsurefire.failIfNoSpecifiedTests=false -DPRIVATE_DISPLAY=true -DUSE_ROBOT=true \
            "${EXTRA_MAVEN_ARGS[@]}") 2>&1 | tee "$RUN_DIR/maven.log"
        maven_exit=${PIPESTATUS[0]}
        if [ "$maven_exit" != 0 ]; then
            error "$run: Maven exited with $maven_exit"
        fi
        if ! kill -0 "$XVFB_PID" 2> /dev/null; then
            error "$run: Xvfb did not survive the run; its log: $(tr '\n' ' ' < "$RUN_DIR/xvfb.log")"
        else
            if [ "$run" = openbox ] && ! openbox_alive; then
                error "$run: openbox did not survive the run (pid $OPENBOX_PID); its log:" \
                    "$(tr '\n' ' ' < "$RUN_DIR/openbox.log")"
            fi
            bus=$(xprop -display "$DISPLAY_NAME" -root AT_SPI_BUS 2> /dev/null | grep ' = ' | tr '\n' ' ')
            if [ -n "$bus" ]; then
                error "$run: at-spi-bus-launcher connected to the display and stayed (AT_SPI_BUS is set on the" \
                    "root window), so the X server did not reset between the children: $bus"
            fi
        fi
    fi
    seconds=$((SECONDS - start))
    stop_process "$OPENBOX_PID"
    OPENBOX_PID=
    stop_process "$XVFB_PID"
    XVFB_PID=
    for file in surefire-reports gtk-glass-child; do
        if [ -d "$TARGET/$file" ]; then
            mv "$TARGET/$file" "$RUN_DIR/$file"
        fi
    done
    if [ -f "$RUN_DIR/.started" ]; then
        mkdir -p "$RUN_DIR/hs_err"
        while IFS= read -r file; do
            mv "$file" "$RUN_DIR/hs_err/"
            hs_err+=("$RUN_DIR/hs_err/$(basename "$file")")
        done < <(find "$MODULE" -maxdepth 2 -type f -name 'hs_err_pid*' -newer "$RUN_DIR/.started")
        if [ -d "$RUN_DIR/gtk-glass-child" ]; then
            while IFS= read -r file; do
                hs_err+=("$file")
            done < <(find "$RUN_DIR/gtk-glass-child" -type f -name 'hs_err_pid*')
        fi
        if [ ${#hs_err[@]} -gt 0 ]; then
            error "$run: a JVM died with a fatal error: ${hs_err[*]#"$OUT/"}"
        fi
        class_counts "$RUN_DIR/maven.log" > "$RUN_DIR/counts.tsv"
        skipped_tests "$RUN_DIR/surefire-reports" > "$RUN_DIR/skipped.tsv"
        prove_setup "$run"
        check_counts "$run"
    fi
    echo "::endgroup::"
    report_run "$run" "$seconds" "$maven_exit"
    if [ -s "$RUN_DIR/errors.txt" ]; then
        FAILED_RUNS+=("$run")
    fi
    RUN_DIR=
}

cell() {
    local line tests failures errors skipped notes=()
    line=$(awk -F '\t' -v c="$2" '$1 == c' "$OUT/$1/counts.tsv")
    if [ -z "$line" ]; then
        echo "no result"
        return
    fi
    IFS=$'\t' read -r _ tests failures errors skipped <<< "$line"
    [ "$failures" = 0 ] || notes+=("$failures failed")
    [ "$errors" = 0 ] || notes+=("$errors errors")
    [ "$skipped" = 0 ] || notes+=("$skipped skipped")
    if [ ${#notes[@]} -gt 0 ]; then
        local IFS=,
        echo "$tests (${notes[*]})" | sed 's/,/, /g'
    else
        echo "$tests"
    fi
}

write_summary() {
    local run class header separator row result seconds classes tests failures errors skipped zeros file reason
    {
        echo "### GTK display tests on a private Xvfb (${SCREEN}x${DEPTH})"
        echo
        echo "| run | result | time | classes | tests | failures | errors | skipped | classes with \`Tests run: 0\` |"
        echo "| --- | --- | --- | --- | --- | --- | --- | --- | --- |"
        for run in "${RUNS[@]}"; do
            if [ -s "$OUT/$run/totals.tsv" ]; then
                IFS=$'\t' read -r _ result seconds classes tests failures errors skipped zeros < "$OUT/$run/totals.tsv"
                echo "| $(describe "$run") | $result | $(minutes "$seconds") | $classes | $tests | $failures |" \
                    "$errors | $skipped | $zeros |"
            fi
        done
        echo
        echo "Installed: $(paste -sd ';' "$OUT/versions.txt" | sed 's/;/, /g')"
        for run in "${RUNS[@]}"; do
            echo
            echo "Recorded by the children, $(describe "$run"):" \
                "$(grep -E '^  env\.(gtk|xserver|resolution|scale|compositing):' "$OUT/$run/report.txt" \
                    | sed 's/^  //' | paste -sd ';' - | sed 's/;/; /g')"
        done
        for run in "${RUNS[@]}"; do
            if [ -s "$OUT/$run/errors.txt" ]; then
                echo
                echo "**Errors, $(describe "$run"):**"
                sed 's/^/- /' "$OUT/$run/errors.txt"
            fi
        done
        echo
        echo "<details><summary>Tests per class</summary>"
        echo
        header="| class |"
        separator="| --- |"
        for run in "${RUNS[@]}"; do
            header+=" $(describe "$run") |"
            separator+=" --- |"
        done
        echo "$header"
        echo "$separator"
        for file in "$SOURCES"/*Test.java; do
            class=$(basename "$file" .java)
            row="| \`$class\` |"
            for run in "${RUNS[@]}"; do
                row+=" $(cell "$run" "$class") |"
            done
            echo "$row"
        done
        echo
        echo "</details>"
        echo
        echo "<details><summary>Skipped tests and classes with <code>Tests run: 0</code>, and why</summary>"
        for run in "${RUNS[@]}"; do
            echo
            echo "**$(describe "$run")**"
            echo
            while IFS=$'\t' read -r class tests _; do
                if [ "$tests" = 0 ]; then
                    reason=$(expected_zero_classes "$run" | awk -F '\t' -v c="$class" '$1 == c { print $2 }')
                    echo "- \`$class\`: Tests run: 0 - ${reason:-not expected}"
                fi
            done < <(sort "$OUT/$run/counts.tsv")
            awk -F '\t' '{ print "- `" $1 "`: skipped - " $2 }' "$OUT/$run/skipped.tsv"
        done
        echo
        echo "</details>"
    } >> "$GITHUB_STEP_SUMMARY"
}

main() {
    local run dir tool natives_before natives_after
    trap cleanup EXIT
    trap 'exit 130' INT
    trap 'exit 143' TERM
    for run in "${RUNS[@]}"; do
        if [ -z "$(describe "$run")" ]; then
            echo "::error::unknown run '$run' in GTK_TEST_RUNS; the runs are nowm and openbox"
            exit 2
        fi
    done
    for tool in xdpyinfo xprop setsid dbus-run-session perl mvn "${XVFB_COMMAND[0]}" "${OPENBOX_COMMAND[0]}"; do
        if ! command -v "$tool" > /dev/null; then
            echo "::error::$tool is not installed; the GTK display tests need it"
            exit 2
        fi
    done
    if ! ls "$TARGET"/native/bin/libglassgtk3.so > /dev/null 2>&1; then
        echo "::error::no $TARGET/native/bin/libglassgtk3.so: build javafx.graphics with its natives first"
        exit 2
    fi
    rm -rf "$OUT"
    mkdir -p "$OUT"
    for dir in surefire-reports gtk-glass-child; do
        rm -rf "$TARGET/$dir.before-gtk-display-tests"
        if [ -d "$TARGET/$dir" ]; then
            mv "$TARGET/$dir" "$TARGET/$dir.before-gtk-display-tests"
        fi
    done
    # The installed packages (where they come from packages), and openbox's own version; the X server's release is
    # what the children record as env.xserver.
    {
        dpkg-query -W -f '${db:Status-Abbrev}|${Package} ${Version}\n' xvfb openbox x11-utils dbus-daemon \
            'libgtk-3-0*' 2> /dev/null | awk -F '|' '$1 ~ /^ii/ { print $2 }'
        "${OPENBOX_COMMAND[@]}" --version 2>&1 | head -n 1
    } > "$OUT/versions.txt"
    echo "Installed: $(paste -sd ';' "$OUT/versions.txt" | sed 's/;/, /g')"
    echo "Display $DISPLAY_NAME; XDG_RUNTIME_DIR=${XDG_RUNTIME_DIR:-unset}" \
        "(bus socket: $([ -S "${XDG_RUNTIME_DIR:-/nonexistent}/bus" ] && echo yes || echo no));" \
        "DBUS_SESSION_BUS_ADDRESS $([ -n "${DBUS_SESSION_BUS_ADDRESS:-}" ] && echo set || echo unset)"
    natives_before=$(natives_state)
    echo "$natives_before" > "$OUT/natives-before.txt"
    for run in "${RUNS[@]}"; do
        run_tests "$run"
    done
    natives_after=$(natives_state)
    echo "$natives_after" > "$OUT/natives-after.txt"
    if [ "$natives_before" != "$natives_after" ]; then
        echo "::error::the natives in $TARGET/native/bin changed during the runs; -DskipNative=true must keep them"
        diff "$OUT/natives-before.txt" "$OUT/natives-after.txt"
        FAILED_RUNS+=(natives)
    else
        echo "Natives unchanged by the runs: $(grep -c '\.so$' "$OUT/natives-before.txt") libraries, same size," \
            "time and SHA-256"
    fi
    if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
        write_summary
    fi
    if [ ${#FAILED_RUNS[@]} -gt 0 ]; then
        echo "::error::GTK display tests failed: ${FAILED_RUNS[*]}; the reports are in $OUT"
        exit 1
    fi
    echo "GTK display tests passed: ${RUNS[*]}"
}

main
