package studio.koeda.norrklang.media

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import studio.koeda.norrklang.data.model.Track
import studio.koeda.norrklang.data.repo.MusicRepository
import studio.koeda.norrklang.data.settings.ServerSettingsRepository
import studio.koeda.norrklang.subsonic.SubsonicException

/**
 * Rebuilds the persisted "last playing" queue (see [ResumptionPersister]) —
 * for the OS-driven `onPlaybackResumption` and for eagerly populating the
 * player at signed-in service start (last track paused beats an empty box).
 */
@OptIn(UnstableApi::class)
internal class ResumptionQueueLoader(
    private val settings: ServerSettingsRepository,
    private val repository: MusicRepository,
    private val randomMix: RandomMixSession,
    private val similarMixes: SimilarMixesSession,
    private val bestOfMixes: BestOfMixesSession,
    private val catalogMixes: CatalogMixesSession,
) {

    /** The restored queue, or null when there is nothing (or no way) to restore. */
    suspend fun load(): MediaItemsWithStartPosition? {
        val state = settings.resumptionState() ?: return null
        val id = MediaId.parse(state.mediaId) as? MediaId.Track ?: return null
        return try {
            val container = id.container
            if (container != null) {
                // Mix containers resume as [saved] + fresh mix (resumeQueue
                // adopts it, keeping later browsing consistent). Exhaustive
                // on purpose: a new Container type must decide its resume
                // semantics here.
                val queue = when (container) {
                    MediaId.HomeRandomMix -> randomMix.resumeQueue(id.id)
                    is MediaId.HomeSimilar ->
                        similarMixes.resumeQueue(container.artistId, id.id)
                    is MediaId.HomeBestOf ->
                        bestOfMixes.resumeQueue(container.artistId, id.id)
                    is MediaId.CatalogMix -> catalogMixes.resumeQueue(container, id.id)
                    is MediaId.Album, is MediaId.Playlist, MediaId.HomeFavoriteSongs ->
                        containerTracks(container)
                }
                if (queue.isEmpty()) return null
                val index = queue.indexOfFirst { it.id == id.id }.coerceAtLeast(0)
                MediaItemsWithStartPosition(
                    queue.map { MediaItemFactory.playableTrack(it, container) },
                    index,
                    state.positionMs,
                )
            } else {
                MediaItemsWithStartPosition(
                    listOf(MediaItemFactory.playableTrack(repository.track(id.id))),
                    /* startIndex = */ 0,
                    state.positionMs,
                )
            }
        } catch (e: SubsonicException) {
            // Offline or signed out — resume silently becomes "nothing to resume".
            null
        }
    }

    suspend fun containerTracks(container: MediaId.Container): List<Track> =
        when (container) {
            is MediaId.Album -> repository.album(container.id).tracks
            is MediaId.Playlist -> repository.playlist(container.id).tracks
            MediaId.HomeFavoriteSongs -> repository.favoriteTracks()
            MediaId.HomeRandomMix -> randomMix.queueTracks()
            is MediaId.HomeSimilar -> similarMixes.queueTracks(container.artistId)
            is MediaId.HomeBestOf -> bestOfMixes.queueTracks(container.artistId)
            is MediaId.CatalogMix -> catalogMixes.queueTracks(container)
        }
}
