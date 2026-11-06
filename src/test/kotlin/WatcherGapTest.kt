import com.apollographql.apollo.ApolloCall
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.Query
import com.apollographql.cache.normalized.FetchPolicy
import com.apollographql.cache.normalized.api.CacheHeaders
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.DefaultCacheResolver
import com.apollographql.cache.normalized.api.IdCacheKeyGenerator
import com.apollographql.cache.normalized.api.NormalizedCache
import com.apollographql.cache.normalized.api.NormalizedCacheFactory
import com.apollographql.cache.normalized.api.Record
import com.apollographql.cache.normalized.fetchPolicy
import com.apollographql.cache.normalized.memory.MemoryCacheFactory
import com.apollographql.cache.normalized.normalizedCache
import com.apollographql.cache.normalized.watch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import repro.GetViewerQuery
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A `CacheOnly` watcher is started while a `NetworkOnly` refresh of the same query is in flight.
 *
 * `watch()` reads the cache, then subscribes to `changedKeys`. When the refresh's write is published
 * after that read but before the subscription exists, nobody receives it and the watcher stays on
 * the stale data until some unrelated write touches the same keys.
 *
 * [PausingCache] widens that window deterministically: it pauses after the last record read of the
 * watcher's initial fetch. It sits in front of the [com.apollographql.cache.normalized.memory.MemoryCache],
 * so the pause holds no cache lock and the refresh's write goes through while the watcher waits.
 */
class WatcherGapTest {

    @Test
    fun `control - a write published after the watcher subscribed is delivered`() =
        scenario(writeBetweenReadAndSubscription = false, catchUpRead = false)

    @Test
    fun `a write published between the watcher's initial read and its subscription is delivered`() =
        scenario(writeBetweenReadAndSubscription = true, catchUpRead = false)

    @Test
    fun `workaround - one extra cache read after the first emission delivers that write`() =
        scenario(writeBetweenReadAndSubscription = true, catchUpRead = true)
}

private fun scenario(
    writeBetweenReadAndSubscription: Boolean,
    catchUpRead: Boolean,
) = runBlocking(Dispatchers.Default) {
    val server = MockWebServer().apply { start() }
    val cache = PausingCache(MemoryCacheFactory().create())
    val apolloClient = ApolloClient.Builder()
        .serverUrl(server.url("/").toString())
        .normalizedCache(
            normalizedCacheFactory = object : NormalizedCacheFactory() {
                override fun create() = cache
            },
            cacheKeyGenerator = IdCacheKeyGenerator(),
            cacheResolver = DefaultCacheResolver,
            writeToCacheAsynchronously = false,
        )
        .dispatcher(Dispatchers.IO)
        .build()

    suspend fun refresh(name: String) {
        server.enqueue(MockResponse(body = """{"data":{"viewer":{"__typename":"Viewer","id":"1","name":"$name"}}}"""))
        apolloClient.query(GetViewerQuery()).fetchPolicy(FetchPolicy.NetworkOnly).execute().dataOrThrow()
    }

    refresh(name = "stale")

    cache.reads.set(0)
    apolloClient.query(GetViewerQuery()).fetchPolicy(FetchPolicy.CacheOnly).watch().first()
    val readsPerInitialFetch = cache.reads.get()

    cache.reads.set(0)
    cache.pauseAfterRead = if (writeBetweenReadAndSubscription) readsPerInitialFetch else -1

    val emittedNames = CopyOnWriteArrayList<String?>()
    val call = apolloClient.query(GetViewerQuery()).fetchPolicy(FetchPolicy.CacheOnly)
    val watcher = launch {
        val responses = if (catchUpRead) call.watchWithCatchUpRead() else call.watch()
        responses.collect { emittedNames += it.data?.viewer?.name }
    }

    if (writeBetweenReadAndSubscription) cache.paused.await() else delay(500)
    refresh(name = "fresh")

    delay(3_000)
    watcher.cancel()
    server.close()

    println("readsPerInitialFetch=$readsPerInitialFetch emitted=$emittedNames")
    assertEquals("fresh", emittedNames.last(), "The watcher emitted $emittedNames")
}

private fun <D : Query.Data> ApolloCall<D>.watchWithCatchUpRead(): Flow<ApolloResponse<D>> = flow {
    var caughtUp = false
    watch().collect { response ->
        emit(response)
        if (!caughtUp) {
            caughtUp = true
            emit(copy().fetchPolicy(FetchPolicy.CacheOnly).execute())
        }
    }
}

private class PausingCache(private val delegate: NormalizedCache) : NormalizedCache by delegate {

    val reads = AtomicInteger()

    @Volatile
    var pauseAfterRead = -1
    val paused = CompletableDeferred<Unit>()

    override suspend fun loadRecord(key: CacheKey, cacheHeaders: CacheHeaders): Record? =
        delegate.loadRecord(key, cacheHeaders).also { onRead() }

    override suspend fun loadRecords(keys: Collection<CacheKey>, cacheHeaders: CacheHeaders): Collection<Record> =
        delegate.loadRecords(keys, cacheHeaders).also { onRead() }

    private suspend fun onRead() {
        if (reads.incrementAndGet() == pauseAfterRead) {
            paused.complete(Unit)
            delay(1_500)
        }
    }
}
