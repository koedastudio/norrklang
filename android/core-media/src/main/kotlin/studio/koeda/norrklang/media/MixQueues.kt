package studio.koeda.norrklang.media

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import studio.koeda.norrklang.data.model.Track
import studio.koeda.norrklang.data.repo.MusicRepository

/**
 * The track list behind each mix of a family, keyed by mix: built on first
 * use and kept until [clear], so browse paging, tap-to-play and resumption
 * all see the same list. Kept free of Android imports so it stays
 * JVM-unit-testable.
 */
internal class MixQueues<K : Any>(
    private val repository: MusicRepository,
    private val build: suspend (K) -> List<Track>,
) {

    private val stateMutex = Mutex()

    /** Serializes builds so concurrent pagings of a fresh mix share one result. */
    private val buildMutex = Mutex()

    private val tracksByMix = mutableMapOf<K, List<Track>>()

    /** Bumped by every reset so an in-flight build can't adopt a stale result. */
    private var epoch = 0

    /** Failures (thrown or empty) serve empty and are not kept. */
    suspend fun queueTracks(key: K): List<Track> {
        current(key)?.let { return it }
        return buildMutex.withLock {
            current(key)?.let { return it }
            val startEpoch = stateMutex.withLock { epoch }
            val built = try {
                withContext(Dispatchers.Default) { build(key) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            if (built.isNotEmpty()) {
                stateMutex.withLock { if (epoch == startEpoch) tracksByMix[key] = built }
            }
            built
        }
    }

    /** The saved track first over the mix, adopted so later browsing shows this same queue. */
    suspend fun resumeQueue(key: K, savedTrackId: String): List<Track> {
        val queue = savedTrackFirst(repository, savedTrackId, queueTracks(key))
        if (queue.isNotEmpty()) {
            stateMutex.withLock { tracksByMix[key] = queue }
        }
        return queue
    }

    /** Replaces every list with [tracks] (a fresh snapshot's lists). */
    suspend fun replaceAll(tracks: Map<K, List<Track>>) = stateMutex.withLock {
        epoch++
        tracksByMix.clear()
        tracksByMix.putAll(tracks)
    }

    /** Drops everything, for sign-out and account switches. */
    suspend fun clear() = replaceAll(emptyMap())

    private suspend fun current(key: K): List<Track>? = stateMutex.withLock { tracksByMix[key] }
}
