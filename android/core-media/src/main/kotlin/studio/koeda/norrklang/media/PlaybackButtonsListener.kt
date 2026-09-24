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
 * button mirrors the player's shuffle mode, and the heart is filled when the
 * current track is a favorite on the server — or, on a radio station, when
 * the song playing is in [SavedRadioSongs]. Favorite lookups go through the
 * repository's TTL cache, so track skips don't turn into a request storm.
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

    // A station's title changes with every song (RadioNowPlayingListener).
    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = refresh()

    // Shuffle can also be flipped by other controllers (voice, phone UI), not
    // just our own custom button — mirror whatever the player says.
    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = refresh()

    private fun refresh() {
        refreshJob?.cancel()
        val item = session.player.currentMediaItem
        val radioSong = item?.let(::radioSong)
        val trackId = item?.let { (MediaId.parse(it.mediaId) as? MediaId.Track)?.id }
        refreshJob = scope.launch {
            // Signed out or offline resolves to false: the outline heart is
            // the right default, and tapping it will surface the real error.
            // CancellationException must propagate (runCatching would swallow
            // it): during service teardown the session below is already
            // released, and touching it then throws.
            val favorite = try {
                when {
                    radioSong != null -> savedSongs.isSaved(radioSong.station, radioSong.title)
                    trackId != null -> repository.isFavoriteTrack(trackId)
                    else -> false
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            session.setMediaButtonPreferences(
                playbackButtons(
                    context,
                    shuffleOn = session.player.shuffleModeEnabled,
                    favorite = favorite,
                    radio = item?.let { MediaId.parse(it.mediaId) } is MediaId.RadioStation,
                ),
            )
        }
    }
}

/** What the heart saves on a station: the stream's current song, by station. */
internal data class RadioSong(val station: String, val title: String)

/**
 * The song a station item is playing, or null when the stream's metadata
 * hasn't named one (the title is still the station's own name).
 */
internal fun radioSong(item: MediaItem): RadioSong? {
    if (MediaId.parse(item.mediaId) !is MediaId.RadioStation) return null
    val station = item.mediaMetadata.station?.toString()?.trim().orEmpty()
    val title = item.mediaMetadata.title?.toString()?.trim().orEmpty()
    if (title.isEmpty() || title.equals(station, ignoreCase = true)) return null
    return RadioSong(station, title)
}
