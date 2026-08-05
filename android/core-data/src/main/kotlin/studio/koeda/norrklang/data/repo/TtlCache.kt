package studio.koeda.norrklang.data.repo

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Minimal in-memory TTL cache. Keeps the browse tree snappy without pulling
 * in Room; hidden behind the repository interface so a persistent cache can
 * replace it later.
 */
class TtlCache(
    private val ttlMillis: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private class Entry(val value: Any, val storedAt: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    // Per-key locks coalesce concurrent misses (the car UI fans the browse
    // tree out in parallel): the first caller loads, the rest reuse its result.
    private val locks = ConcurrentHashMap<String, Mutex>()

    @Suppress("UNCHECKED_CAST")
    suspend fun <T : Any> getOrLoad(key: String, loader: suspend () -> T): T {
        fresh(key)?.let { return it as T }
        return locks.computeIfAbsent(key) { Mutex() }.withLock {
            fresh(key)?.let { return@withLock it as T }
            prune()
            val loaded = loader()
            entries[key] = Entry(loaded, clock())
            loaded
        } as T
    }

    private fun fresh(key: String): Any? {
        val existing = entries[key] ?: return null
        return existing.value.takeIf { clock() - existing.storedAt < ttlMillis }
    }

    /** Drops expired entries so superseded keys don't pile up for the process lifetime. */
    private fun prune() {
        val now = clock()
        entries.entries.removeIf { now - it.value.storedAt >= ttlMillis }
    }

    fun clear() {
        entries.clear()
        locks.clear()
    }
}
