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

//! The panic guard of the native libraries' Rust code.
//!
//! Every export of a library crate, and every entry point foreign code calls (window procedures, COM methods,
//! GLib and GStreamer callbacks), runs its body through [`guard`] or [`guard_handle`]. A panic is a bug: it is
//! caught there, reported as one line on stderr, and turned into the value the C/C++ returns on its failure path.
//! It never unwinds into C, C++ or Java, where an unwinding panic would abort the process without an hs_err file.

use std::any::Any;
use std::io::Write;
use std::panic::{self, AssertUnwindSafe, Location};
use std::sync::Once;
use std::sync::atomic::{AtomicBool, Ordering};

/// The poison latch of one handle. A library embeds one in each of its handle types; once a panic has run on the
/// handle, every later guarded call on it returns its failure value without running.
#[derive(Debug, Default)]
pub struct Poison(AtomicBool);

impl Poison {
    pub const fn new() -> Self {
        Poison(AtomicBool::new(false))
    }

    fn is_poisoned(&self) -> bool {
        // Acquire pairs with the Release in poison(): a call that sees the latch also sees that the handle's state
        // may be half updated, and does not touch it.
        self.0.load(Ordering::Acquire)
    }

    fn poison(&self) {
        self.0.store(true, Ordering::Release);
    }
}

/// Runs `body` for an export that has no handle. Returns the body's value, or `failure` when the body panics.
/// `R` is `Copy` because the value goes back across the C ABI, and a `Copy` type has no `Drop`, so dropping the
/// unused failure value cannot panic.
pub fn guard<R: Copy>(failure: R, body: impl FnOnce() -> R) -> R {
    install_hook();
    // AssertUnwindSafe: after a panic nothing the body touched is used again; the caller only gets `failure`.
    match panic::catch_unwind(AssertUnwindSafe(body)) {
        Ok(value) => value,
        Err(payload) => {
            drop_inside_guard(payload);
            failure
        }
    }
}

/// Runs `body` on the handle that owns `poison`. Returns `failure` without running `body` when the handle is
/// poisoned; poisons it and returns `failure` when the body panics; otherwise returns the body's value.
pub fn guard_handle<R: Copy>(poison: &Poison, failure: R, body: impl FnOnce() -> R) -> R {
    if poison.is_poisoned() {
        drop_inside_guard(body);
        return failure;
    }
    install_hook();
    // AssertUnwindSafe: the latch set below keeps every later call away from the state the panic left behind.
    match panic::catch_unwind(AssertUnwindSafe(body)) {
        Ok(value) => value,
        Err(payload) => {
            poison.poison();
            drop_inside_guard(payload);
            failure
        }
    }
}

/// Drops `value` (a caught panic's payload, or a body that is not run) without letting a panicking `Drop` escape
/// the guard. The payload of that second panic is forgotten, since its own `Drop` could panic as well.
fn drop_inside_guard<T>(value: T) {
    if let Err(again) = panic::catch_unwind(AssertUnwindSafe(|| drop(value))) {
        std::mem::forget(again);
    }
}

static HOOK: Once = Once::new();

/// Replaces the default panic hook, once, by one that writes exactly one line. The hook belongs to the copy of std
/// linked into this library, so neither the JVM nor another native library sees it.
fn install_hook() {
    // set_hook panics on a panicking thread (a guarded call from a Drop during unwinding); a later call installs it.
    if std::thread::panicking() {
        return;
    }
    HOOK.call_once(|| {
        panic::set_hook(Box::new(|info| {
            let line = panic_line(info.location(), info.payload());
            // A failed write is ignored: a panic inside the hook would abort the process.
            let _ = std::io::stderr().lock().write_all(line.as_bytes());
        }));
    });
}

/// The line the hook writes for a panic: `jfx-rust: panic at <file>:<line>:<column>: <message>` and a newline.
/// Line breaks inside the message are written as `\n` and `\r`, so that a panic is always one line.
fn panic_line(location: Option<&Location<'_>>, payload: &(dyn Any + Send)) -> String {
    let message = if let Some(text) = payload.downcast_ref::<&str>() {
        text
    } else if let Some(text) = payload.downcast_ref::<String>() {
        text.as_str()
    } else {
        // What std's own hook prints for a payload that is not a string.
        "Box<dyn Any>"
    };
    let message = message.replace('\n', "\\n").replace('\r', "\\r");
    match location {
        Some(at) => format!(
            "jfx-rust: panic at {}:{}:{}: {}\n",
            at.file(),
            at.line(),
            at.column(),
            message
        ),
        None => format!("jfx-rust: panic at <unknown>: {message}\n"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::Cell;

    #[test]
    fn a_panicking_body_returns_the_failure_value() {
        assert_eq!(guard(-1, || -> i32 { panic!("guard body") }), -1);
        assert_eq!(
            guard_handle(&Poison::new(), -2, || -> i32 { panic!("guard_handle body") }),
            -2
        );
    }

    #[test]
    fn a_panic_poisons_the_handle_and_later_calls_do_not_run() {
        let poison = Poison::new();
        assert_eq!(guard_handle(&poison, 0, || -> i32 { panic!("first call") }), 0);
        assert!(poison.is_poisoned());
        let mut ran = false;
        assert_eq!(
            guard_handle(&poison, 0, || {
                ran = true;
                7
            }),
            0
        );
        assert!(!ran, "the body of a call on a poisoned handle ran");
    }

    #[test]
    fn a_body_that_returns_gives_its_value_and_does_not_poison() {
        assert_eq!(guard(-1, || 42), 42);
        let poison = Poison::new();
        assert_eq!(guard_handle(&poison, -1, || 43), 43);
        assert!(!poison.is_poisoned());
        assert_eq!(guard_handle(&poison, -1, || 44), 44);
    }

    #[test]
    fn a_payload_whose_drop_panics_does_not_leave_the_guard() {
        struct PanicsOnDrop;
        impl Drop for PanicsOnDrop {
            fn drop(&mut self) {
                panic!("payload drop");
            }
        }
        let poison = Poison::new();
        let outcome = panic::catch_unwind(AssertUnwindSafe(|| {
            (
                guard(-1, || -> i32 { panic::panic_any(PanicsOnDrop) }),
                guard_handle(&poison, -2, || -> i32 { panic::panic_any(PanicsOnDrop) }),
            )
        }));
        assert!(
            matches!(outcome, Ok((-1, -2))),
            "the panic of the payload's drop left the guard"
        );
        assert!(poison.is_poisoned());
    }

    #[test]
    fn a_body_not_run_on_a_poisoned_handle_is_dropped_inside_the_guard() {
        struct PanicsOnDrop<'a>(&'a Cell<bool>);
        impl Drop for PanicsOnDrop<'_> {
            fn drop(&mut self) {
                self.0.set(true);
                panic!("capture drop");
            }
        }
        let poison = Poison::new();
        assert_eq!(guard_handle(&poison, 0, || -> i32 { panic!("first call") }), 0);
        let ran = Cell::new(false);
        let dropped = Cell::new(false);
        let outcome = panic::catch_unwind(AssertUnwindSafe(|| {
            let capture = PanicsOnDrop(&dropped);
            let ran = &ran;
            guard_handle(&poison, -3, move || {
                let _keep = &capture;
                ran.set(true);
                7
            })
        }));
        assert!(
            matches!(outcome, Ok(-3)),
            "the panic of the unrun body's drop left the guard"
        );
        assert!(!ran.get(), "the body of a call on a poisoned handle ran");
        assert!(dropped.get(), "the body of a call on a poisoned handle was not dropped");
    }

    #[test]
    fn the_line_names_the_location_and_a_str_message() {
        let at = Location::caller();
        let payload: Box<dyn Any + Send> = Box::new("static text");
        assert_eq!(
            panic_line(Some(at), payload.as_ref()),
            format!(
                "jfx-rust: panic at {}:{}:{}: static text\n",
                at.file(),
                at.line(),
                at.column()
            )
        );
    }

    #[test]
    fn the_line_carries_a_string_message() {
        let payload: Box<dyn Any + Send> = Box::new(String::from("formatted 12"));
        assert_eq!(
            panic_line(None, payload.as_ref()),
            "jfx-rust: panic at <unknown>: formatted 12\n"
        );
    }

    #[test]
    fn the_line_names_a_payload_of_another_type_like_std() {
        let payload: Box<dyn Any + Send> = Box::new(12_u32);
        assert_eq!(
            panic_line(None, payload.as_ref()),
            "jfx-rust: panic at <unknown>: Box<dyn Any>\n"
        );
    }

    #[test]
    fn a_message_with_line_breaks_stays_one_line() {
        let payload: Box<dyn Any + Send> = Box::new("one\ntwo\r\nthree");
        assert_eq!(
            panic_line(None, payload.as_ref()),
            "jfx-rust: panic at <unknown>: one\\ntwo\\r\\nthree\n"
        );
    }
}
