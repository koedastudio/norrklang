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
 * With a [persistence], the previous drive's snapshot stands in until this
 * drive's generation lands, so the section is there before the server
 * answers; that one replacement is the only swap a drive sees.
 *
 * Kept free of Android imports so it stays JVM-unit-testable.
 */
internal abstract class HomeMixesSession<K : Any, S : Any>(
    protected val repository: MusicRepository,
    private val persistence: MixPersistence<S>? = null,
) {

    /** Guards the snapshot state; critical sections stay free of network I/O. */
    private val stateMutex = Mutex()

    private val queues = MixQueues<K>(repository) { buildTracksFor(it) }

    private var snapshotFingerprint: String? = null

    /** null = never generated for this fingerprint; empty = generated, no data. */
    private var snapshot: S? = null

    /** True while [snapshot] is the previous drive's, restored from disk and due a refresh. */
    private var provisional = false

    /** The fingerprint whose stored snapshot was already looked for. */
    private var restoreTriedFor: String? = null

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
            if (snapshotFingerprint == fingerprint && snapshot != null && !provisional) return false
            if (snapshotFingerprint != fingerprint) {
                epoch++
                snapshotFingerprint = fingerprint
                snapshot = null
                provisional = false
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
            // discard rather than resurrect another account's tracks.
            if (epoch != startEpoch) return false
            if (snapshotFingerprint == fingerprint && snapshot != null && !provisional) return false
            val previous = snapshot
            val changed = if (provisional) {
                generated != previous
            } else {
                !isEmpty(generated) || previous?.let { !isEmpty(it) } == true
            }
            snapshotFingerprint = fingerprint
            snapshot = generated
            provisional = false
            queues.replaceAll(sectionTracks(generated))
            if (changed) persistence?.save(fingerprint, generated)
            changed
        }
    }

    /** The snapshot as the home grid should show it; never generates. */
    protected suspend fun currentSnapshot(): S? {
        restore()
        return stateMutex.withLock { snapshot }
    }

    /** Adopts the stored snapshot for the current account, once, unless this drive already has one. */
    private suspend fun restore() {
        val persistence = persistence ?: return
        val fingerprint = persistence.currentFingerprint() ?: return
        val startEpoch = stateMutex.withLock {
            if (restoreTriedFor == fingerprint) return
            if (snapshotFingerprint == fingerprint && snapshot != null) return
            epoch
        }
        val stored = persistence.load(fingerprint)
        stateMutex.withLock {
            // Signed out or re-scoped during the read: this is the old account's snapshot.
            if (epoch != startEpoch) return
            if (restoreTriedFor == fingerprint) return
            restoreTriedFor = fingerprint
            if (stored == null || isEmpty(stored)) return
            if (snapshotFingerprint == fingerprint && snapshot != null) return
            // A generation running for another account must not land on top.
            if (snapshotFingerprint != fingerprint) epoch++
            snapshotFingerprint = fingerprint
            snapshot = stored
            provisional = true
            queues.replaceAll(sectionTracks(stored))
        }
    }

    /**
     * The tracks of one mix — queue and browse list, always the same list
     * once adopted. A mix this session doesn't know (e.g. a resumption id
     * from a previous drive) is built on demand.
     */
    suspend fun queueTracks(key: K): List<Track> {
        restore()
        return queues.queueTracks(key)
    }

    /** The resumption queue for a persisted `track/{id}|<mix>`. */
    suspend fun resumeQueue(key: K, savedTrackId: String): List<Track> {
        restore()
        return queues.resumeQueue(key, savedTrackId)
    }

    /** Drops everything held in memory. */
    suspend fun clear() {
        stateMutex.withLock {
            epoch++
            snapshotFingerprint = null
            snapshot = null
            provisional = false
            restoreTriedFor = null
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
