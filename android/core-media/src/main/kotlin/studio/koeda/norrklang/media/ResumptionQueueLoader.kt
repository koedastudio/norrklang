package studio.koeda.norrklang.media

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import studio.koeda.norrklang.data.model.Track
import studio.koeda.norrklang.data.repo.MusicRepository
import studio.koeda.norrklang.data.runSuspendCatching
import studio.koeda.norrklang.data.settings.ServerSettingsRepository

/**
 * Rebuilds the persisted "last playing" queue (see [ResumptionPersister]) —
 * for the OS-driven `onPlaybackResumption` and for eagerly populating the
 * player at signed-in service start (last track paused beats an empty box).
 */
@OptIn(UnstableApi::class)
internal class ResumptionQueueLoader(
    private val settings: ServerSettingsRepository,
    private val repository: MusicRepository,
    private val containers: ContainerTracks,
    private val stationQueue: StationQueue,
    private val buildItem: (Track, MediaId.Container?) -> MediaItem = MediaItemFactory::playableTrack,
) {

    /** The restored queue, or null when there is nothing (or no way) to restore. */
    suspend fun load(): MediaItemsWithStartPosition? =
        runSuspendCatching { restore(settings.resumptionState()) }.getOrNull()

    /**
     * Offline, signed out, or unreadable persisted state — resume silently
     * becomes "nothing to resume". Deliberately broader than MusicException:
     * this runs inside the service scope at every signed-in start, where the
     * same bad persisted state would otherwise replay a failure on every bind.
     */
    internal suspend fun restore(
        state: ServerSettingsRepository.ResumptionState?,
    ): MediaItemsWithStartPosition? = runSuspendCatching {
        state ?: return null
        val parsed = MediaId.parse(state.mediaId)
        if (parsed is MediaId.RadioStation) return stationQueue.startingAt(parsed.id)
        val id = parsed as? MediaId.Track ?: return null
        val container = id.container
        if (container != null) {
            val queue = containers.resume(container, id.id)
            if (queue.isEmpty()) return null
            val index = queue.indexOfFirst { it.id == id.id }
            MediaItemsWithStartPosition(
                queue.map { buildItem(it, container) },
                index.coerceAtLeast(0),
                if (index >= 0) state.positionMs else 0L,
            )
        } else {
            MediaItemsWithStartPosition(
                listOf(buildItem(repository.track(id.id), null)),
                /* startIndex = */ 0,
                state.positionMs,
            )
        }
    }.getOrNull()
}
