# `watch()` misses a cache write published between its initial read and its subscription

A `CacheOnly` watcher started while a `NetworkOnly` fetch of the same query is in flight can miss that fetch's cache write. When that happens it keeps emitting the stale data until some unrelated write touches the same keys.

Versions: `com.apollographql.apollo:apollo-runtime:5.2.0`, `com.apollographql.cache:normalized-cache:1.0.9`, Kotlin 2.4.21.

## Run

```bash
./gradlew test
```

[`WatcherGapTest`](src/test/kotlin/WatcherGapTest.kt) has three cases:

| Case | Emitted | Result |
| --- | --- | --- |
| Control: the refresh writes after the watcher subscribed | `[stale, fresh]` | passes |
| The refresh writes after the watcher's initial read, before it subscribes | `[stale]`, still 3 s later | **fails** |
| Same, with one extra `CacheOnly` read after the first emission | `[stale, fresh]` | passes |

The failing case is deterministic. The [Tests workflow](.github/workflows/tests.yml) runs the same command on every push to `main`, so its red run is the repro; the HTML test report is attached to each run as the `test-report` artifact.

## What happens

`WatcherInterceptor` (`fetchInitialResponses = true`):

1. runs the initial fetch, which reads the records from the cache;
2. subscribes to `CacheManager.changedKeys`, a `MutableSharedFlow` with no replay;
3. on subscription, releases the withheld initial response. It doesn't read again.

A write published between 1 and 2 reaches no subscriber and is dropped, and the response released in 3 was read before that write.

The `watch()` KDoc only covers updates "made after collecting" the last initial response, so this is arguably within the contract. In practice it bites the common pattern of a screen that opens a `CacheOnly` watcher and starts a `NetworkOnly` refresh of the same query at the same time. We hit it as a flaky Android UI test: Home stayed on data cached at login and never showed the refreshed response.

## How the test makes it deterministic

`PausingCache` wraps a `MemoryCache` and pauses for 1.5 s after the last record read of the watcher's initial fetch (2 reads for this query). It sits in front of the `MemoryCache`, so the pause holds no cache lock and the refresh's write goes through during it. Without the pause, the window is the time between the last record read and the `changedKeys` subscription: converting the records to `Data` and finishing the initial flow. On a busy emulator, with a large query, that is enough to lose a real refresh now and then.

If the pausing cache sits *behind* a `MemoryCache` instead (`MemoryCacheFactory().chain(pausingFactory)`), the write waits for the `MemoryCache` mutex and the race can't be reproduced that way.

## Workaround

Read the cache once more after the first emission. That emission is released only once the watcher is subscribed, so a write published before it is already in the store, and any later write is delivered by the subscription:

```kotlin
fun <D : Query.Data> ApolloCall<D>.watchWithCatchUpRead(): Flow<ApolloResponse<D>> = flow {
    var caughtUp = false
    watch().collect { response ->
        emit(response)
        if (!caughtUp) {
            caughtUp = true
            emit(copy().fetchPolicy(FetchPolicy.CacheOnly).execute())
        }
    }
}
```

## Possible fix in the library

On subscription, refetch with the refetch policy if anything was published since the initial fetch started (for example by comparing a publish counter in `CacheManager` before and after), instead of only releasing the withheld response.
