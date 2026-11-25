# US-131 — Evidence: the macOS deadlock of `UserDataDirectoryTest`

Recorded 2026-11-25 on the macOS host: macOS 27.2.1 (arm64), OpenJDK 27.0.1, Maven 3.10.0, tree `3f19f2c154`,
javafx.graphics and javafx.media natives built from source, `jfxwebkit` from `../caches/sdk/lib`.

## The two libraries

| Name | Source | `libjfxwebkit.dylib` sha256 |
| --- | --- | --- |
| new | `jfxwebkit-28-macos-aarch64`, downloaded 2026-11-25 | `2137d5ec85b1272dce0e85e198890a767f26792ffd0478f95f0c31bc8b6c463f` |
| old | the copy in `../caches/sdk/lib` before it, dated 2026-10-24 | `88f6174a9455677c4f931eb5e32644fa85dc8e152e06c85dc8225a6367c775d3` |

Both export the same 1,965 `wkj_*` symbols, and `WebKitLibraryAbiTest` passes against the new one.

## Runs

| Run | Command | Result |
| --- | --- | --- |
| 1, new library | `mvn -B -ntp -pl modules/javafx.web -am test -Djfx.web.skipTests=false -Djfx.web.skipFfmTests=false` | `default-test` 290 / 18 / 32 / 110, build failure; `ffm-binding-test` never ran |
| 2, new library | the same with `-Dtest='UserDataDirectoryTest' -Dsurefire.failIfNoSpecifiedTests=false`, `jstack -l` twice, 10 s apart | 19 / 17 / 1 / 0 |
| 3, new library | run 2 with `-o`, `sample <pid> 3` during the hang | 19 / 17 / 1 / 0 |
| 4, old library | run 3 with the old library in `../caches/sdk/lib` | 19 / 17 / 1 / 0 |
| 5, new library | run 1 with `-o -fae '-Dtest=!UserDataDirectoryTest' -Dsurefire.failIfNoSpecifiedTests=false` | `default-test` 473 / 0 / 0 / 113, `ffm-binding-test` 194 / 0 / 0 / 0 ([per class](US-131-macos-per-class.tsv)) |

In run 1 the class's first test, `testAlreadyInUseErrorWithPassiveHandler`, passes. The second,
`testSimpleModification`, loads a page, writes to `localStorage` (`assertHasLocalStorage`, `:576-590`) and is then
disposed. Its `@AfterEach` times out after 20 s in `dispose` (`UserDataDirectoryTest.java:113`, `:496`), and the
other 17 tests fail in the constructor with `Waiting timed out` (`TestBase.java:110`). The 31 classes after it fail
in `TestBase#setupOnce()`, `timed out after 20 seconds`, and `WebKitRegistryLeakTest` fails with
`the FX runtime did not start`: all of them wait on the same blocked FX thread. Runs 2 to 4 give the class the same
19 / 17 / 1 / 0. The 113 skips of run 5
are tests disabled in the source: `JDK-8290292` (107), `JDK-8280421` (3), `JDK-8290237`, `JDK-8290238`, and one
without a reason.

## The FX thread (`jstack`, runs 2)

Both dumps, at 14.8 s and 24.9 s after the thread started, show the same frames and the same CPU time,
285.35 ms: the thread is blocked, not spinning. No other Java thread is in WebKit or FFM code.

```
"JavaFX Application Thread" #37 ... runnable
    at jdk.internal.foreign.abi.DowncallStub/0x00001ffe00497000.invoke
    at com.sun.webkit.WebPageNative.destroyPage(WebPageNative.java:565)
    at com.sun.webkit.WebPage.twkDestroyPage(WebPage.java:2609)
    at com.sun.webkit.WebPage.dispose(WebPage.java:1053)
    at javafx.scene.web.WebEngine$SelfDisposer.dispose(WebEngine.java:1125)
    at javafx.scene.web.WebEngine.dispose(WebEngine.java:1110)
    at javafx.scene.web.WebEngineShim.dispose(WebEngineShim.java:37)
    at test.javafx.scene.web.UserDataDirectoryTest.lambda$dispose$0(UserDataDirectoryTest.java:496)
    ...
    at com.sun.javafx.application.PlatformImpl.lambda$runLater$0(PlatformImpl.java:424)
```

## The native stacks (`sample`, runs 3 and 4)

Run 3, 1,847 samples in 3 s. Every sample has these frames on both threads. Template arguments are shortened and
addresses removed; run 4 (old library, 1,868 samples) has the same frames.

```
Thread: Java: JavaFX Application Thread
  ??? (4 frames, the downcall stub)
  WebCore::WebPage::~WebPage()
  WebCore::Page::~Page()
  WebStorageNamespaceProviderJava::~WebStorageNamespaceProviderJava()
  WebCore::StorageNamespaceProvider::~StorageNamespaceProvider()
  WebKit::StorageNamespaceImpl::~StorageNamespaceImpl()
  WebKit::StorageNamespaceImpl::close()
  WebCore::StorageSyncManager::close()
  WebCore::StorageThread::terminate()
  WTF::Thread::waitForCompletion()
  _pthread_join
  __ulock_wait

Thread: LocalStorage
  thread_start / _pthread_start / WTF::Thread::entryPoint
  CallableWrapper<StorageThread::start()::$_0>::call()
  WebKit::StorageAreaSync::performSync()
  WebKit::StorageAreaSync::sync(bool, WTF::HashMap<...> const&)
  WebKit::StorageAreaSync::openDatabase(OpenDatabaseParamType)
  WebCore::SQLiteDatabase::open(...)
  std::__call_once(...)
  std::__call_once_proxy<initializeSQLiteIfNecessary()::$_0>(void*)
  WTF::callOnMainThreadAndWait(WTF::Function<void ()>&&)
  WTF::BinarySemaphore::waitUntil(...)
  WTF::Condition::waitUntilUnchecked<WTF::Lock>(...)
  WTF::ParkingLot::parkConditionallyImpl(...)
  WTF::ThreadCondition::timedWait(WTF::Mutex&, WTF::WallTime)
  _pthread_cond_wait
  __psynch_cvwait
```

The FX thread waits for the `LocalStorage` thread to end, and the `LocalStorage` thread waits for the FX thread to
run `sqlite3_initialize()`.
