package studio.koeda.norrklang.media

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import studio.koeda.norrklang.data.model.Track
import studio.koeda.norrklang.data.repo.MusicException
import studio.koeda.norrklang.data.repo.MusicRepository

/**
 * Shared shell for the generated home-tab mix sections: a tile snapshot of
 * type [S] plus the per-tile track lists ([MixQueues]) keyed by [K], serving
 * browse, tap-to-play and resumption from one state.
 *
 * Stability contract (what keeps the sections from confusing the driver):
 * the snapshot is generated once per signed-in session (keyed by the
 * credentials fingerprint) and never reshuffled until the process restarts,
 * the account changes, or [clear] drops it — a drive keeps its mixes. Track
 * lists the snapshot doesn't already carry are built on first use and then
 * kept under the same contract.
 *
 * Kept free of Android imports so it stays JVM-unit-testable.
 */
internal abstract class HomeMixesSession<K : Any, S : Any>(
    protected val repository: MusicRepository,
) {

    /** Guards the snapshot state; critical sections stay free of network I/O. */
    private val stateMutex = Mutex()

    private val queues = MixQueues<K>(repository) { buildTracksFor(it) }

    private var snapshotFingerprint: String? = null

    /** null = never generated for this fingerprint; empty = generated, no data. */
    private var snapshot: S? = null

    /** Bumped by [clear] so an in-flight generation can't adopt a stale result. */
    private var epoch = 0

    /**
     * Generates the section snapshot for [fingerprint] unless it already
     * exists; true means the home tab's content changed (caller notifies).
     * A [MusicException] mid-generation aborts without adopting — a
     * transient network blip must not read as "no data" and hide the
     * section all session.
     */
    suspend fun refresh(fingerprint: String): Boolean {
        val startEpoch = stateMutex.withLock {
            if (snapshotFingerprint == fingerprint && snapshot != null) return false
            if (snapshotFingerprint != fingerprint) {
                epoch++
                snapshotFingerprint = fingerprint
                snapshot = null
                queues.clear()
            }
            epoch
        }
        val generated = try {
            // The caller launches from the service's main-thread scope; list
            // building and response parsing have no main-thread affinity.
            withContext(Dispatchers.Default) { generate() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Broader than MusicException: refresh runs in a bare launch on
            // the service scope, where anything escaping kills the process.
            return false
        }
        return stateMutex.withLock {
            // Cleared (sign-out) or already regenerated while we were working —
            // discard rather than resurrect tracks with stale auth URLs.
            if (epoch != startEpoch) return false
            if (snapshotFingerprint == fingerprint && snapshot != null) return false
            val changed = !isEmpty(generated) || snapshot?.let { !isEmpty(it) } == true
            snapshotFingerprint = fingerprint
            snapshot = generated
            queues.replaceAll(sectionTracks(generated))
            changed
        }
    }

    /** The snapshot as the home grid should show it; never generates. */
    protected suspend fun currentSnapshot(): S? = stateMutex.withLock { snapshot }

    /**
     * The tracks of one mix — queue and browse list, always the same list
     * once adopted. A mix this session doesn't know (e.g. a resumption id
     * from a previous drive) is built on demand.
     */
    suspend fun queueTracks(key: K): List<Track> = queues.queueTracks(key)

    /** The resumption queue for a persisted `track/{id}|<mix>`. */
    suspend fun resumeQueue(key: K, savedTrackId: String): List<Track> =
        queues.resumeQueue(key, savedTrackId)

    /** Drops everything (tracks embed authenticated stream URLs). */
    suspend fun clear() {
        stateMutex.withLock {
            epoch++
            snapshotFingerprint = null
            snapshot = null
        }
        queues.clear()
    }

    /**
     * Builds the tile snapshot from scratch. A thrown [MusicException]
     * aborts the refresh without adopting anything; an "empty" snapshot (per
     * [isEmpty]) means "this library has no data" and hides the section.
     */
    protected abstract suspend fun generate(): S

    /** Whether [snapshot] contributes no tiles to the home tab. */
    protected abstract fun isEmpty(snapshot: S): Boolean

    /**
     * Track lists [snapshot] already carries, keyed by tile — adopted into
     * the queues on refresh. Sections that build lazily return empty.
     */
    protected abstract fun sectionTracks(snapshot: S): Map<K, List<Track>>

    /**
     * Rebuilds one tile's mix for [queueTracks]/[resumeQueue]; failures may
     * either throw [MusicException] or return empty — both serve empty.
     */
    protected abstract suspend fun buildTracksFor(key: K): List<Track>
}
