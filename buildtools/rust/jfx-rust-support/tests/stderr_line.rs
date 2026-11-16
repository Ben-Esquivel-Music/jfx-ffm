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

// The hook writes to the process's stderr directly, past the test harness's output capture, so the line is
// counted on a child process: this test executable run again, for this one test, with CHILD set.

use std::process::Command;

const CHILD: &str = "JFX_RUST_SUPPORT_STDERR_CHILD";
const TEST_NAME: &str = "one_caught_panic_writes_one_line_to_stderr";

#[test]
fn one_caught_panic_writes_one_line_to_stderr() {
    if std::env::var_os(CHILD).is_some() {
        assert_eq!(jfx_rust_support::guard(-1, || -> i32 { panic!("child panic") }), -1);
        return;
    }
    let output = Command::new(std::env::current_exe().expect("the test executable"))
        .args(["--exact", TEST_NAME, "--nocapture", "--test-threads=1"])
        .env(CHILD, "1")
        .env_remove("RUST_BACKTRACE")
        .output()
        .expect("the child test process");
    let stderr = String::from_utf8(output.stderr).expect("UTF-8 on the child's stderr");
    assert!(
        output.status.success(),
        "the child failed: {:?}, stderr: {stderr:?}",
        output.status
    );
    assert_eq!(
        stderr.matches('\n').count(),
        1,
        "not exactly one line on stderr: {stderr:?}"
    );
    let prefix = format!("jfx-rust: panic at {}:", file!());
    assert!(stderr.starts_with(&prefix), "{stderr:?} does not start with {prefix:?}");
    assert!(
        stderr.ends_with(": child panic\n"),
        "{stderr:?} does not end with the message"
    );
}
