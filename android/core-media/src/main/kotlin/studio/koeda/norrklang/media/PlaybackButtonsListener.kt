package studio.koeda.norrklang.media

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import studio.koeda.norrklang.data.repo.MusicRepository

/**
 * Keeps the playback-row custom buttons in sync with the player: the shuffle
 * button mirrors the player's shuffle mode, and the heart is filled when the
 * current track is a favorite on the server. Favorite lookups go through the
 * repository's TTL cache, so track skips don't turn into a request storm.
 */
@OptIn(UnstableApi::class)
internal class PlaybackButtonsListener(
    private val context: Context,
    private val scope: CoroutineScope,
    private val repository: MusicRepository,
    private val session: MediaSession,
) : Player.Listener {

    private var refreshJob: Job? = null

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = refresh()

    // Shuffle can also be flipped by other controllers (voice, phone UI), not
    // just our own custom button — mirror whatever the player says.
    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = refresh()

    private fun refresh() {
        refreshJob?.cancel()
        val trackId = session.player.currentMediaItem
            ?.let { (MediaId.parse(it.mediaId) as? MediaId.Track)?.id }
        refreshJob = scope.launch {
            // Signed out or offline resolves to false: the outline heart is
            // the right default, and tapping it will surface the real error.
            val favorite = trackId != null && runCatching {
                repository.isFavoriteTrack(trackId)
            }.getOrDefault(false)
            session.setMediaButtonPreferences(
                playbackButtons(
                    context,
                    shuffleOn = session.player.shuffleModeEnabled,
                    favorite = favorite,
                ),
            )
        }
    }
}
