package studio.koeda.norrklang.media

import studio.koeda.norrklang.data.model.Track
import studio.koeda.norrklang.data.repo.MusicRepository

/**
 * The one answer to "which tracks does this container hold": the queue a
 * tapped track rebuilds around itself, a container's browse list, a voice
 * request's queue and the resumed queue all come from here, so they agree.
 */
internal class ContainerTracks(
    private val repository: MusicRepository,
    private val randomMix: RandomMixSession,
    private val similarMixes: SimilarMixesSession,
    private val bestOfMixes: BestOfMixesSession,
    private val catalogMixes: CatalogMixes,
) {

    suspend fun tracks(container: MediaId.Container): List<Track> =
        when (container) {
            is MediaId.Album -> repository.album(container.id).tracks
            is MediaId.Playlist -> repository.playlist(container.id).tracks
            MediaId.HomeFavoriteSongs -> repository.favoriteTracks()
            MediaId.HomeRecentlyAddedSongs -> repository.recentlyAddedTracks()
            MediaId.HomeRandomMix -> randomMix.queueTracks()
            MediaId.HomeRandomAlbums -> randomAlbum()
            is MediaId.HomeSimilar -> similarMixes.queueTracks(container.artistId)
            is MediaId.HomeBestOf -> bestOfMixes.queueTracks(container.artistId)
            is MediaId.CatalogMix -> catalogMixes.queueTracks(container)
            is MediaId.SongRadio -> repository.similarTracks(container.seedArtistId)
        }

    /**
     * The queue to resume at [savedTrackId]: mix containers resume as the
     * saved track + a fresh mix (resumeQueue adopts it, keeping later
     * browsing consistent). Exhaustive on purpose: a new Container type must
     * decide its resume semantics here.
     */
    suspend fun resume(container: MediaId.Container, savedTrackId: String): List<Track> =
        when (container) {
            MediaId.HomeRandomMix -> randomMix.resumeQueue(savedTrackId)
            // The album the saved track belongs to, so the drive resumes mid-album.
            MediaId.HomeRandomAlbums -> albumTracks(repository.track(savedTrackId).albumId)
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
            is MediaId.Album, is MediaId.Playlist, MediaId.HomeFavoriteSongs,
            MediaId.HomeRecentlyAddedSongs,
            -> tracks(container)
        }

    private suspend fun randomAlbum(): List<Track> =
        albumTracks(repository.randomAlbums(1).firstOrNull()?.id)

    private suspend fun albumTracks(albumId: String?): List<Track> =
        albumId?.let { repository.album(it).tracks }.orEmpty()
}
