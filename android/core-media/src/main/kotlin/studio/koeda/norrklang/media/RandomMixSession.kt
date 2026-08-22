package studio.koeda.norrklang.media

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import studio.koeda.norrklang.data.model.Track
import studio.koeda.norrklang.data.repo.MusicRepository

/**
 * In-memory snapshot of the "Random mix" track list. One instance per process
 * (see MediaModule) shared by [BrowseTree], [ResumptionQueueLoader] and the
 * tile artwork renderer, so the browsed list, the rebuilt queue and the
 * collage all describe the same mix.
 *
 * Stability contract (what keeps the mix from confusing the driver):
 *  - stable across paging and quick re-entries — every serve refreshes a
 *    sliding [graceMillis] window, so only a genuinely idle gap regenerates
 *  - stable for as long as the mix is the current play source, no matter how
 *    much later it is browsed again
 *  - regenerated on process restart (a new drive gets a new mix) and on
 *    re-entry after the grace window while something else is playing
 *
 * Kept free of Android imports so it stays JVM-unit-testable.
 */
internal class RandomMixSession(
    private val repository: MusicRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val size: Int = MIX_SIZE,
    private val graceMillis: Long = GRACE_MILLIS,
) {

    /** Maintained by [RandomMixPlaySourceListener] on the app main thread. */
    @Volatile
    var isCurrentPlaySource: Boolean = false

    private val mutex = Mutex()
    private var snapshot: List<Track> = emptyList()
    private var lastServedAt = 0L

    /** The mix as the browse view should show it — regenerates when due. */
    suspend fun browseTracks(): List<Track> = mutex.withLock {
        val regenerate = snapshot.isEmpty() ||
            (!isCurrentPlaySource && clock() - lastServedAt > graceMillis)
        if (regenerate) snapshot = repository.randomTracks(size)
        serve()
    }

    /**
     * The queue for a track tapped in the mix: the snapshot verbatim, so the
     * siblings are exactly what was on screen. The empty case can't happen
     * after a browse; covered defensively.
     */
    suspend fun queueTracks(): List<Track> = mutex.withLock {
        if (snapshot.isEmpty()) snapshot = repository.randomTracks(size)
        serve()
    }

    /**
     * The resumption queue for a persisted `track/{id}|home/random-mix`:
     * [savedTrackFirst] over a new mix, adopted as the snapshot so browsing
     * the mix afterwards shows this same queue.
     */
    suspend fun resumeQueue(savedTrackId: String): List<Track> = mutex.withLock {
        snapshot = savedTrackFirst(repository, savedTrackId, repository.randomTracks(size))
        serve()
    }

    /**
     * The mix for the tile montage: generates one when none exists (real
     * covers over the flat accent fallback), otherwise the snapshot as-is —
     * without refreshing the grace window, so a background render can
     * neither reshuffle the list the user saw nor keep it alive past grace.
     */
    suspend fun montageTracks(): List<Track> = mutex.withLock {
        if (snapshot.isEmpty()) {
            snapshot = repository.randomTracks(size)
            serve()
        } else {
            snapshot
        }
    }

    /** Drops the snapshot (tracks embed authenticated stream URLs). */
    suspend fun clear() = mutex.withLock {
        snapshot = emptyList()
        lastServedAt = 0L
    }

    private fun serve(): List<Track> {
        lastServedAt = clock()
        return snapshot
    }

    companion object {
        const val MIX_SIZE = 50
        const val GRACE_MILLIS = 60_000L
    }
}
