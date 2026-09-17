package studio.koeda.norrklang.data.repo

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

    private val monitor = Any()
    private val entries = mutableMapOf<String, Entry>()
    private var generation = 0L

    // Locks exist only while a load or one of its waiters is active.
    private class LoadLock(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val locks = mutableMapOf<String, LoadLock>()

    @Suppress("UNCHECKED_CAST")
    suspend fun <T : Any> getOrLoad(key: String, loader: suspend () -> T): T {
        val (startedIn, lock) = synchronized(monitor) {
            fresh(key)?.let { return it as T }
            generation to locks.getOrPut(key) { LoadLock() }.also { it.users++ }
        }
        try {
            return lock.mutex.withLock {
                synchronized(monitor) {
                    fresh(key)?.let { return@withLock it as T }
                    val now = clock()
                    entries.entries.removeAll { now - it.value.storedAt >= ttlMillis }
                }
                val loaded = loader()
                synchronized(monitor) {
                    // A favourite change or sign-out must win over an older load.
                    if (generation == startedIn) entries[key] = Entry(loaded, clock())
                }
                loaded
            }
        } finally {
            synchronized(monitor) {
                if (--lock.users == 0) locks.remove(key)
            }
        }
    }

    /** Called only while holding [monitor]. */
    private fun fresh(key: String): Any? {
        val existing = entries[key] ?: return null
        return existing.value.takeIf { clock() - existing.storedAt < ttlMillis }
    }

    fun clear() = synchronized(monitor) {
        generation++
        entries.clear()
    }
}
