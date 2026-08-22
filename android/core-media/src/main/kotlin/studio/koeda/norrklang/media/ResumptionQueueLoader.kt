package studio.koeda.norrklang.media

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import kotlin.coroutines.cancellation.CancellationException
import studio.koeda.norrklang.data.model.Track
import studio.koeda.norrklang.data.repo.MusicRepository
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
    private val randomMix: RandomMixSession,
    private val similarMixes: SimilarMixesSession,
    private val bestOfMixes: BestOfMixesSession,
    private val catalogMixes: CatalogMixesSession,
) {

    /** The restored queue, or null when there is nothing (or no way) to restore. */
    suspend fun load(): MediaItemsWithStartPosition? {
        return try {
            val state = settings.resumptionState() ?: return null
            val id = MediaId.parse(state.mediaId) as? MediaId.Track ?: return null
            val container = id.container
            if (container != null) {
                val queue = resumeTracks(container, id.id)
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Offline, signed out, or unreadable persisted state — resume
            // silently becomes "nothing to resume". Deliberately broader than
            // MusicException: this runs inside the service scope at every
            // signed-in start, where the same bad persisted state would
            // otherwise replay a failure on every bind.
            null
        }
    }

    /**
     * Mix containers resume as [saved] + fresh mix (resumeQueue adopts it,
     * keeping later browsing consistent). Exhaustive on purpose: a new
     * Container type must decide its resume semantics here.
     */
    suspend fun resumeTracks(container: MediaId.Container, savedTrackId: String): List<Track> =
        when (container) {
            MediaId.HomeRandomMix -> randomMix.resumeQueue(savedTrackId)
            is MediaId.HomeSimilar ->
                similarMixes.resumeQueue(container.artistId, savedTrackId)
            is MediaId.HomeBestOf ->
                bestOfMixes.resumeQueue(container.artistId, savedTrackId)
            is MediaId.CatalogMix -> catalogMixes.resumeQueue(container, savedTrackId)
            is MediaId.SongRadio ->
                savedTrackFirst(
                    repository,
                    savedTrackId,
                    repository.similarTracks(container.seedArtistId),
                )
            is MediaId.Album, is MediaId.Playlist, MediaId.HomeFavoriteSongs ->
                containerTracks(container)
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
            is MediaId.SongRadio -> repository.similarTracks(container.seedArtistId)
        }
}
