package studio.koeda.norrklang.media

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import studio.koeda.norrklang.data.repo.MusicRepository
import studio.koeda.norrklang.data.settings.ServerSettingsRepository
import studio.koeda.norrklang.data.settings.ServerSettingsRepository.ScrobbleSettings

/**
 * Reports playback to Navidrome. The `stream` endpoint does NOT scrobble, so:
 *  - on every track transition → "now playing" (`submission=false`)
 *  - when a track has played ≥50% or ≥4 minutes (Last.fm convention) →
 *    submission (`submission=true`)
 *
 * Submissions fire from a position discontinuity when the queue moves off a
 * track, and from [Player.STATE_ENDED] for the final track (no discontinuity).
 *
 * Filtered by [ServerSettingsRepository.ScrobbleSettings]: a suppressed play
 * sends neither submission nor now-playing ping, so it never reaches the
 * server (or the services it forwards to) in any form.
 */
@OptIn(UnstableApi::class)
internal class ScrobbleListener(
    private val scope: CoroutineScope,
    private val repository: MusicRepository,
    private val settings: ServerSettingsRepository,
    private val player: Player,
) : Player.Listener {

    /** Guards against double-submitting the last track if STATE_ENDED re-fires. */
    private var endedSubmitted = false

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        endedSubmitted = false
        mediaItem ?: return
        scrobble(mediaItem, submission = false)
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        // SEEK covers the user skipping tracks (seekToNext/Previous); seeks
        // within one track are filtered by the mediaId comparison below.
        if (reason != Player.DISCONTINUITY_REASON_AUTO_TRANSITION &&
            reason != Player.DISCONTINUITY_REASON_SEEK &&
            reason != Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT &&
            reason != Player.DISCONTINUITY_REASON_REMOVE
        ) {
            return
        }
        val finished = oldPosition.mediaItem ?: return
        if (finished.mediaId == newPosition.mediaItem?.mediaId) return

        submitIfPlayedEnough(
            item = finished,
            playedMs = oldPosition.positionMs,
        )
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        // The last track of a queue ends without a discontinuity.
        if (playbackState != Player.STATE_ENDED || endedSubmitted) return
        val item = player.currentMediaItem ?: return
        endedSubmitted = submitIfPlayedEnough(
            item = item,
            playedMs = player.currentPosition,
        )
    }

    private fun submitIfPlayedEnough(item: MediaItem, playedMs: Long): Boolean {
        val durationMs = item.mediaMetadata.durationMs
        val threshold = when {
            durationMs != null && durationMs > 0 -> minOf(durationMs / 2, FOUR_MINUTES_MS)
            else -> FOUR_MINUTES_MS
        }
        if (playedMs < threshold) return false
        scrobble(item, submission = true)
        return true
    }

    private fun scrobble(item: MediaItem, submission: Boolean) {
        val mediaId = MediaId.parse(item.mediaId) as? MediaId.Track ?: return
        val artistId = item.mediaMetadata.extras?.getString(MediaItemFactory.EXTRA_ARTIST_ID)
        scope.launch {
            // The settings read can fail with an IOException (it is not a
            // repository call) — treat that as "don't scrobble" rather than
            // letting it escape this bare launch.
            val allowed = try {
                settings.scrobbleSettings.first().allowsScrobble(mediaId, artistId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (!allowed) return@launch
            runCatching { repository.scrobble(mediaId.id, submission) }
        }
    }

    private companion object {
        const val FOUR_MINUTES_MS = 4 * 60 * 1000L
    }
}

/**
 * Whether a play of [mediaId] may be reported. Artist exclusion applies to
 * any play; playlist exclusion only to plays whose queue context is that
 * playlist — the same track from its album still scrobbles.
 */
internal fun ScrobbleSettings.allowsScrobble(
    mediaId: MediaId.Track,
    artistId: String?,
): Boolean {
    if (!enabled) return false
    if (artistId != null && artistId in excludedArtistIds) return false
    val playlist = mediaId.container as? MediaId.Playlist ?: return true
    return playlist.id !in excludedPlaylistIds
}
