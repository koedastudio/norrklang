package studio.koeda.norrklang.media

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import studio.koeda.norrklang.data.radio.SavedRadioSongs
import studio.koeda.norrklang.data.repo.MusicRepository

/**
 * Keeps the playback-row custom buttons in sync with the player: the shuffle
 * button mirrors the player's shuffle mode, and the heart follows [heartFor].
 * Favorite lookups go through the repository's TTL cache, so track skips
 * don't turn into a request storm.
 */
@OptIn(UnstableApi::class)
internal class PlaybackButtonsListener(
    private val context: Context,
    private val scope: CoroutineScope,
    private val repository: MusicRepository,
    private val savedSongs: SavedRadioSongs,
    private val session: MediaSession,
) : Player.Listener {

    private var refreshJob: Job? = null

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = refresh()

    // A station's title changes with every song (RadioNowPlayingListener),
    // and the heart comes and goes with it.
    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = refresh()

    // Shuffle can also be flipped by other controllers (voice, phone UI), not
    // just our own custom button — mirror whatever the player says.
    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = refresh()

    private fun refresh() {
        refreshJob?.cancel()
        val item = session.player.currentMediaItem
        refreshJob = scope.launch {
            val heart = heartFor(
                item,
                savedSong = { song -> lookup { savedSongs.isSaved(song.station, song.title) } },
                favoriteTrack = { trackId -> lookup { repository.isFavoriteTrack(trackId) } },
            )
            session.setMediaButtonPreferences(
                playbackButtons(context, shuffleOn = session.player.shuffleModeEnabled, heart = heart),
            )
        }
    }

    /**
     * Signed out or offline resolves to false: the outline heart is the
     * right default, and tapping it will surface the real error.
     * CancellationException must propagate (runCatching would swallow it):
     * during service teardown the session is already released, and touching
     * it then throws.
     */
    private suspend fun lookup(query: suspend () -> Boolean): Boolean = try {
        query()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }
}

/**
 * The heart for the item playing: a track's server favorite, a station's
 * saved song — or none while the station hasn't named a song (a stream
 * without ICY/ID3 metadata never does). Nothing playing keeps the track
 * heart so the row doesn't reshuffle between queues.
 */
internal suspend fun heartFor(
    item: MediaItem?,
    savedSong: suspend (RadioSong) -> Boolean,
    favoriteTrack: suspend (String) -> Boolean,
): Heart {
    item ?: return Heart.Track(favorite = false)
    return when (val id = MediaId.parse(item.mediaId)) {
        is MediaId.RadioStation ->
            radioSong(item)?.let { Heart.RadioSong(savedSong(it)) } ?: Heart.None
        is MediaId.Track -> Heart.Track(favoriteTrack(id.id))
        else -> Heart.Track(favorite = false)
    }
}

/** What the heart saves on a station: the stream's current song, by station. */
internal data class RadioSong(val station: String, val title: String)

/**
 * The song a station item is playing — whichever of its two lines isn't the
 * station's own name (RadioNowPlayingListener decides which line that is) —
 * or null while the stream hasn't named one.
 */
internal fun radioSong(item: MediaItem): RadioSong? {
    if (MediaId.parse(item.mediaId) !is MediaId.RadioStation) return null
    val station = item.mediaMetadata.station?.toString()?.trim().orEmpty()
    val song = listOfNotNull(item.mediaMetadata.title, item.mediaMetadata.artist)
        .map { it.toString().trim() }
        .firstOrNull { it.isNotEmpty() && !it.equals(station, ignoreCase = true) }
        ?: return null
    return RadioSong(station, song)
}
