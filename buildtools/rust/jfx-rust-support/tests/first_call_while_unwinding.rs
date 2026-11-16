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

// The first guarded call of a process can come from a Drop that runs while a panic unwinds, when the panic hook
// cannot be installed. The calls must still return their values and catch their panics, and the hook must be
// installed by the next guarded call on a thread that is not panicking. Each case needs a fresh process, so it
// runs in a child: this test executable run again, for that one test, with CHILD set.

use jfx_rust_support::{Poison, guard, guard_handle};
use std::cell::Cell;
use std::panic::{self, AssertUnwindSafe};
use std::process::Command;

const CHILD: &str = "JFX_RUST_SUPPORT_UNWINDING_CHILD";

/// Runs `calls` in a Drop during the unwinding of a caught panic; returns whether the thread was panicking there,
/// and what `calls` returned.
fn call_while_unwinding(calls: fn() -> [i32; 3]) -> (bool, [i32; 3]) {
    struct CallsOnDrop<'a> {
        calls: fn() -> [i32; 3],
        seen: &'a Cell<Option<(bool, [i32; 3])>>,
    }
    impl Drop for CallsOnDrop<'_> {
        fn drop(&mut self) {
            self.seen.set(Some((std::thread::panicking(), (self.calls)())));
        }
    }
    let seen = Cell::new(None);
    let outer = panic::catch_unwind(AssertUnwindSafe(|| {
        let _on_drop = CallsOnDrop { calls, seen: &seen };
        panic!("outer");
    }));
    assert!(outer.is_err(), "the outer panic was not caught");
    seen.get().expect("the Drop did not run")
}

fn run_child(test_name: &str, last_message: &str) {
    let output = Command::new(std::env::current_exe().expect("the test executable"))
        .args(["--exact", test_name, "--nocapture", "--test-threads=1"])
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
    let lines: Vec<&str> = stderr
        .lines()
        .filter(|line| line.starts_with("jfx-rust: panic at"))
        .collect();
    assert_eq!(lines.len(), 1, "not exactly one line of the hook on stderr: {stderr:?}");
    let prefix = format!("jfx-rust: panic at {}:", file!());
    assert!(
        lines[0].starts_with(&prefix),
        "{:?} does not start with {prefix:?}",
        lines[0]
    );
    let suffix = format!(": {last_message}");
    assert!(
        lines[0].ends_with(&suffix),
        "{:?} does not end with {suffix:?}",
        lines[0]
    );
}

#[test]
fn a_first_guard_while_unwinding_returns_and_a_later_one_installs_the_hook() {
    const TEST_NAME: &str = "a_first_guard_while_unwinding_returns_and_a_later_one_installs_the_hook";
    if std::env::var_os(CHILD).is_none() {
        run_child(TEST_NAME, "guard after unwinding");
        return;
    }
    let seen = call_while_unwinding(|| {
        [
            guard(-1, || -> i32 { panic!("guard while unwinding") }),
            guard(-1, || 5),
            guard(-1, || 6),
        ]
    });
    assert_eq!(seen, (true, [-1, 5, 6]));
    assert!(!std::thread::panicking());
    assert_eq!(guard(-1, || -> i32 { panic!("guard after unwinding") }), -1);
}

#[test]
fn a_first_guard_handle_while_unwinding_returns_and_a_later_one_installs_the_hook() {
    const TEST_NAME: &str = "a_first_guard_handle_while_unwinding_returns_and_a_later_one_installs_the_hook";
    if std::env::var_os(CHILD).is_none() {
        run_child(TEST_NAME, "guard_handle after unwinding");
        return;
    }
    static POISON: Poison = Poison::new();
    let seen = call_while_unwinding(|| {
        [
            guard_handle(&POISON, -2, || -> i32 { panic!("guard_handle while unwinding") }),
            guard_handle(&POISON, -2, || 5),
            guard_handle(&Poison::new(), -2, || 6),
        ]
    });
    assert_eq!(seen, (true, [-2, -2, 6]));
    assert!(!std::thread::panicking());
    assert_eq!(
        guard_handle(&Poison::new(), -2, || -> i32 { panic!("guard_handle after unwinding") }),
        -2
    );
}
