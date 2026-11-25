# US-131 — Stop a WebView page teardown deadlocking on macOS when SQLite is not yet initialized

**Status:** 📋 Ready (filed 2026-11-25) · **Found:** 2026-11-25, running the `javafx.web` tests on the macOS host
against a newly downloaded `jfxwebkit`; the hang reproduced in all 4 runs, with the new library and with the one
before it, and thread dumps and native samples locate it ([evidence](US-131-macos-evidence.md)) · **Related:** US-025
(its Windows gate passes the same test)

## Story
As a JavaFX app developer on macOS whose WebView pages use `localStorage`,
I want destroying a page never to block the FX thread for good,
so that my application does not hang when a page goes away, and the `javafx.web` tests run to the end on macOS.

## Problem
Paths are relative to `modules/javafx.web/src/main/native/Source`; line numbers are those of `3f19f2c154`.

- **The two waits.** Destroying a page (`wkj_page_destroy`, `WebKitLegacy/java/WebCoreSupport/WebPage.cpp:1062-1076`)
  destroys its `WebStorageNamespaceProviderJava` (`:849`), whose `StorageNamespaceImpl::close`
  (`WebKitLegacy/Storage/StorageNamespaceImpl.cpp:115`) runs each storage area's final sync and then
  `StorageSyncManager::close` (`WebKitLegacy/Storage/StorageSyncManager.cpp:76`). That calls
  `StorageThread::terminate` (`WebKitLegacy/Storage/StorageThread.cpp:90-105`), which queues the end of the
  `LocalStorage` thread and waits for it to end (`:102`), on the main thread, which on the Java port is the FX thread.
  If that thread's pending sync is the first to open the storage database (`StorageAreaSync::sync`, `:399`), it
  reaches `SQLiteDatabase::open` (`WebCore/platform/sql/SQLiteDatabase.cpp:133`) and `initializeSQLiteIfNecessary`
  (`:64-99`). On Darwin that runs `sqlite3_initialize()` through `callOnMainThreadAndWait` (`:73-82`), which waits for
  the FX thread. The FX thread waits for the `LocalStorage` thread, which waits for the FX thread.
- **Observed.** In `UserDataDirectoryTest.testSimpleModification` a page writes to `localStorage` and is disposed
  within the 1 s sync interval (`WebKitLegacy/Storage/StorageAreaSync.cpp:44`), in a JVM where nothing has opened an
  SQLite database before. The native stacks of both threads are those above, frame for frame, with both libraries
  ([evidence](US-131-macos-evidence.md#the-native-stacks-sample-runs-3-and-4)).
- **Effect on the test run.** On macOS the `javafx.web` `default-test` cannot finish: the blocked FX thread fails
  the class's remaining 17 tests and every later class that needs it, 31 in `TestBase#setupOnce()` and
  `WebKitRegistryLeakTest`. That is 290 / 18 / 32 / 110, where the run without `UserDataDirectoryTest` is
  473 / 0 / 0 / 113 ([per class](US-131-macos-per-class.tsv)). Because the `default-test` fails, the
  `ffm-binding-test` execution after it does not run. CI never runs `default-test`, and US-025's gate was on
  Windows, so neither saw it.
- **Only Darwin.** Every other OS calls `sqlite3_initialize()` directly (`SQLiteDatabase.cpp:83-88`). US-025's
  Windows gate passes `UserDataDirectoryTest` 19 / 0 / 0 / 0 before and after
  ([per class](US-025-windows-per-class.tsv)).
- **Origin.** The Darwin branch came with the WebKit 625.1 update (`1e7e7d03e8`, 8380556). Its comment gives the
  reason for the main thread: in WebKit's Network process `sqlite3_initialize()` can race with other code that
  queries the Darwin user temp directory through `confstr()`. Whether upstream OpenJFX has the same hang was not
  checked.
- **In applications** (read from the source, not reproduced outside the test): the same hang follows whenever the
  FX thread destroys a page while that page's `LocalStorage` thread is making the process's first SQLite open. That
  is, for example, when a `WebEngine` is disposed within a second of its page writing to `localStorage` and nothing
  in the process has initialized SQLite yet.

## Constraints
- **SQLite is still initialized once, before the first open,** on every OS, and an initialization failure still
  logs and crashes as it does today (`SQLiteDatabase.cpp:91-98`).
- **No `localStorage` write is lost:** what a page wrote before it is destroyed is on disk afterwards, as the final
  sync does today on the OSes that do not hang.
- **The reason for the main-thread call is answered:** the change states why the `confstr()` race in the comment at
  `SQLiteDatabase.cpp:76-80` cannot happen on the Java port, or keeps `sqlite3_initialize()` free of it.
- **Other OSes and other ports unchanged:** the code compiled for Windows and Linux, and for every WebKit port
  but Java, is the same as today. Java-port changes stay in the `*/java/` directories where they can; an upstream
  file changes only if it must.
- **No JNI:** `perl buildtools/ffm-web/verify-no-jni.pl` stays at zero and `java buildtools/CheckNoJni.java .`
  passes.

## Acceptance criteria
1. **No deadlock on macOS.** Against a `jfxwebkit` built from the changed tree for macOS, `UserDataDirectoryTest`
   passes 19 / 0 / 0 / 0. Against the same tree's `jfxwebkit` built without the change, it hangs as in the
   [evidence](US-131-macos-evidence.md).
2. **A test that cannot be hidden by test order.** A test destroys a page that has just written to `localStorage`,
   in a JVM in which nothing has initialized SQLite before it, whatever classes run before it. It passes against the
   changed library and fails against the unchanged one on macOS.
3. **The data is kept.** A test writes a value to `localStorage` less than 1 s before its page is destroyed, then
   reads it back through a new engine on the same user data directory. It passes against the changed library on macOS
   and against the libraries before and after the change on Windows.
4. **The whole module runs on macOS.** `mvn -B -ntp -pl modules/javafx.web -am test -Djfx.web.skipTests=false
   -Djfx.web.skipFfmTests=false` passes with 0 failures and 0 errors, and its per-class list is
   [`US-131-macos-per-class.tsv`](US-131-macos-per-class.tsv) plus `UserDataDirectoryTest` and the new tests.
5. **Windows and Linux unchanged.** The same command's per-class list on Windows equals the final list of
   [`US-025-windows-per-class.tsv`](US-025-windows-per-class.tsv) plus the new tests, and on Linux (WSL) it equals
   that run against the library before the change, plus the new tests.

## Definition of Done
- All acceptance criteria met, with the results and the `jfxwebkit` hashes stated here.
- The PR names the `jfxwebkit` builds it was verified with (local builds or `build-webkit.yml`, both macOS targets
  among them), with the `tests/system` web tests passing against them (owner-run).
- Whether upstream OpenJFX has the hang is checked, and if it does, the upstream report is drafted and kept outside
  the repository until filed.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree or on a review branch — **no commits, merges or PRs without explicit approval from
  the repository owner.**
