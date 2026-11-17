package studio.koeda.norrklang.data.repo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer

/**
 * Stale-while-revalidate over [TtlCache] and [SnapshotStore] for the lists in
 * [BrowseList]: a list whose memory entry has expired is answered at once
 * from its last-known value (memory, else disk) while one background load
 * fetches the current one; [changes] fires only when that load differs.
 *
 * A list with no last-known value loads like any other cached call.
 */
internal class SnapshotCache(
    private val cache: TtlCache,
    private val store: SnapshotStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val monitor = Any()

    /** Last-known values by full cache key, kept past the TTL. */
    private val known = HashMap<String, Known>()

    private class Known(val list: BrowseList, val value: Any)
    private val revalidating = HashSet<String>()
    private val failedAt = HashMap<String, Long>()

    /** Revalidations that failed, re-run by [retryFailed]. */
    private val pending = HashMap<String, () -> Unit>()

    /** Lists whose disk files must not be trusted until loaded live again. */
    private val dropped = HashSet<BrowseList>()

    /** Bumped by [drop] and [clear] so an older load cannot be adopted after them. */
    private var generation = 0L

    private val _changes = MutableSharedFlow<BrowseList>(extraBufferCapacity = 64)

    /** Lists whose content changed under a value already handed out. */
    val changes: Flow<BrowseList> = _changes

    @Suppress("UNCHECKED_CAST")
    suspend fun <T : Any> get(
        fingerprint: String,
        list: BrowseList,
        key: String,
        serializer: KSerializer<T>,
        loader: suspend () -> T,
    ): T {
        val full = "$fingerprint/$key"
        cache.peek(full)?.let { return it as T }
        val (inMemory, trustDisk, startedIn) = synchronized(monitor) {
            Triple(known[full]?.value, list !in dropped, generation)
        }
        val stale = inMemory as T?
            ?: if (trustDisk) store.read(fingerprint, list.fileName, key, serializer) else null
        if (stale == null) {
            val loaded = cache.getOrLoad(full, loader)
            val adopted = synchronized(monitor) {
                (generation == startedIn).also {
                    if (it) {
                        known[full] = Known(list, loaded)
                        dropped.remove(list)
                    }
                }
            }
            if (adopted) store.write(fingerprint, list.fileName, key, serializer, loaded)
            return loaded
        }
        if (inMemory == null) {
            synchronized(monitor) { if (generation == startedIn) known.getOrPut(full) { Known(list, stale) } }
        }
        revalidate(fingerprint, list, key, serializer, loader)
        return stale
    }

    private fun <T : Any> revalidate(
        fingerprint: String,
        list: BrowseList,
        key: String,
        serializer: KSerializer<T>,
        loader: suspend () -> T,
    ) {
        val full = "$fingerprint/$key"
        val startedIn = synchronized(monitor) {
            val failed = failedAt[full]
            if (failed != null && clock() - failed < RETRY_AFTER_MS) return
            if (!revalidating.add(full)) return
            generation
        }
        scope.launch {
            try {
                val fresh = cache.getOrLoad(full, loader)
                val changed = synchronized(monitor) {
                    failedAt.remove(full)
                    pending.remove(full)
                    if (generation != startedIn) null else known.put(full, Known(list, fresh))?.value != fresh
                }
                if (changed == true) {
                    store.write(fingerprint, list.fileName, key, serializer, fresh)
                    _changes.emit(list)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                synchronized(monitor) {
                    if (generation == startedIn) {
                        failedAt[full] = clock()
                        pending[full] = { revalidate(fingerprint, list, key, serializer, loader) }
                    }
                }
            } finally {
                synchronized(monitor) { revalidating.remove(full) }
            }
        }
    }

    /** Re-runs the refreshes that failed, for when the network returns. */
    fun retryFailed() {
        val retries = synchronized(monitor) {
            failedAt.clear()
            pending.values.toList().also { pending.clear() }
        }
        retries.forEach { it() }
    }

    /** Forgets [lists] in memory and on disk, so their next read is a live load. */
    fun drop(fingerprint: String?, lists: Set<BrowseList>) {
        synchronized(monitor) {
            generation++
            dropped += lists
            known.values.removeAll { it.list in lists }
            failedAt.clear()
            pending.clear()
        }
        if (fingerprint != null) {
            scope.launch { store.drop(fingerprint, lists.mapTo(HashSet()) { it.fileName }) }
        }
    }

    /** Forgets everything in memory and on disk, for sign-out. */
    fun clear() {
        synchronized(monitor) {
            generation++
            dropped.clear()
            known.clear()
            failedAt.clear()
            pending.clear()
        }
        scope.launch { store.purge() }
    }

    private companion object {
        /** A failed refresh is not retried on every read of a list shown from disk. */
        const val RETRY_AFTER_MS = 30_000L
    }
}
