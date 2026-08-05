package studio.koeda.norrklang.media

import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import studio.koeda.norrklang.data.model.Album
import studio.koeda.norrklang.data.model.Artist
import studio.koeda.norrklang.data.model.Playlist
import studio.koeda.norrklang.data.model.Track

/** Builders translating domain models into [MediaItem]s for the car browse UI. */
@OptIn(UnstableApi::class)
internal object MediaItemFactory {

    /**
     * Extras key for the track's server artist id — standard metadata only
     * holds the *name*; ScrobbleListener matches exclusions by id.
     */
    const val EXTRA_ARTIST_ID = "studio.koeda.norrklang.extra.ARTIST_ID"

    /** Extras that make a browsable node render its children as an artwork grid. */
    fun gridChildrenExtras(): Bundle = Bundle().apply {
        putInt(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
        )
        putInt(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
        )
    }

    /** Extras that make a browsable node render its children as a list. */
    fun listChildrenExtras(): Bundle = Bundle().apply {
        putInt(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
        )
        putInt(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
        )
    }

    fun browsable(
        mediaId: MediaId,
        title: String,
        subtitle: String? = null,
        artworkUrl: String? = null,
        mediaType: Int = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
        childrenStyle: Bundle? = null,
        groupTitle: String? = null,
        supportedCommands: List<String> = emptyList(),
    ): MediaItem = MediaItem.Builder()
        .setMediaId(mediaId.encode())
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setArtworkUri(artworkUrl?.let(Uri::parse))
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(mediaType)
                .setSupportedCommands(supportedCommands)
                .apply {
                    // Both hints share MediaMetadata.extras; the style bundles
                    // are fresh per call, so mutating in place is safe.
                    if (childrenStyle != null || groupTitle != null) {
                        val extras = childrenStyle ?: Bundle()
                        groupTitle?.let {
                            extras.putString(
                                MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE,
                                it,
                            )
                        }
                        setExtras(extras)
                    }
                }
                .build(),
        )
        .build()

    fun forArtist(artist: Artist): MediaItem = browsable(
        mediaId = MediaId.Artist(artist.id),
        title = artist.name,
        subtitle = null,
        artworkUrl = artist.artworkUrl,
        mediaType = MediaMetadata.MEDIA_TYPE_ARTIST,
        // An artist opens their releases — show those as an artwork grid.
        childrenStyle = gridChildrenExtras(),
        // The artists tab renders A–Z headers from the server's index buckets;
        // null (detail/search artists) simply omits the hint.
        groupTitle = artist.sortGroup,
    )

    fun forAlbum(album: Album): MediaItem = browsable(
        mediaId = MediaId.Album(album.id),
        title = album.title,
        subtitle = listOfNotNull(album.artistName, album.year?.toString())
            .joinToString(" · ")
            .ifEmpty { null },
        artworkUrl = album.artworkUrl,
        mediaType = MediaMetadata.MEDIA_TYPE_ALBUM,
        childrenStyle = listChildrenExtras(),
        // Exactly one of the two album-favorite browse actions applies: the
        // one that flips the current state (see LibrarySessionCallback).
        supportedCommands = listOf(
            if (album.isFavorite) {
                ACTION_FAVORITE_ALBUM_REMOVE
            } else {
                ACTION_FAVORITE_ALBUM_ADD
            },
        ),
    )

    fun forPlaylist(playlist: Playlist): MediaItem = browsable(
        mediaId = MediaId.Playlist(playlist.id),
        title = playlist.name,
        subtitle = "${playlist.trackCount} tracks",
        artworkUrl = playlist.artworkUrl,
        mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST,
        childrenStyle = listChildrenExtras(),
    )

    /**
     * A track as shown in a browse list — no stream URI; the session callback
     * resolves that when playback is requested.
     */
    fun forTrack(track: Track, container: MediaId.Container? = null): MediaItem =
        MediaItem.Builder()
            .setMediaId(MediaId.Track(track.id, container).encode())
            .setMediaMetadata(trackMetadata(track))
            .build()

    /** A fully-resolved, playable track handed to ExoPlayer. */
    fun playableTrack(track: Track, container: MediaId.Container? = null): MediaItem =
        MediaItem.Builder()
            .setMediaId(MediaId.Track(track.id, container).encode())
            .setUri(track.streamUrl)
            .setMediaMetadata(trackMetadata(track))
            .build()

    private fun trackMetadata(track: Track): MediaMetadata =
        MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artistName)
            .setAlbumTitle(track.albumTitle)
            .setTrackNumber(track.trackNumber)
            .setDiscNumber(track.discNumber)
            .setDurationMs(track.durationSec?.let { it * 1000L })
            .setArtworkUri(track.artworkUrl?.let(Uri::parse))
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .apply {
                track.artistId?.let { artistId ->
                    setExtras(Bundle().apply { putString(EXTRA_ARTIST_ID, artistId) })
                }
            }
            .build()
}
