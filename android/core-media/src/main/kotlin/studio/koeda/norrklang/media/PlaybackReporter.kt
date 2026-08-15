package studio.koeda.norrklang.media

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import studio.koeda.norrklang.data.repo.MusicRepository
import studio.koeda.norrklang.data.repo.PlayState
import studio.koeda.norrklang.data.settings.ServerSettingsRepository
import studio.koeda.norrklang.data.settings.ServerSettingsRepository.ScrobbleSettings

/**
 * Reports playback to the music server. Two channels:
 *
 * Scrobbles (both providers; Navidrome's `stream` endpoint does NOT scrobble):
 *  - on every track transition → "now playing" (`submission=false`)
 *  - when a track has played ≥50% or ≥4 minutes (Last.fm convention) →
 *    submission (`submission=true`)
 *
 * Submissions fire from a position discontinuity when the queue moves off a
 * track, and from [Player.STATE_ENDED] for the final track (no discontinuity).
 *
 * Play-state reports ([MusicRepository.reportPlayState] — Plex timeline;
 * gated on a non-null [MusicRepository.playbackReportIntervalMs]):
 *  - play/pause edges and end-of-queue report the state change
 *  - a ticker repeats PLAYING every interval while playback runs, which is
 *    what keeps the server's now-playing/on-deck live
 *
 * Both channels are filtered by [ServerSettingsRepository.ScrobbleSettings]:
 * a suppressed play sends nothing at all, so it never reaches the server (or
 * the services it forwards to) in any form.
 *
 * All reports are delivered through one serialized queue, so they reach the
 * server in the order the player produced them — the Plex timeline is a state
 * machine, and a late STOPPED overtaking the next track's PLAYING would
 * corrupt on-deck.
 *
 * The construction scope must be main-thread ([Player] reads in the ticker).
 */
@OptIn(UnstableApi::class)
internal class PlaybackReporter(
    private val scope: CoroutineScope,
    private val repository: MusicRepository,
    private val settings: ServerSettingsRepository,
    private val player: Player,
) : Player.Listener {

    /** Guards against double-submitting the last track if STATE_ENDED re-fires. */
    private var endedSubmitted = false

    private var tickerJob: Job? = null

    private sealed interface Report {
        data class Scrobble(val submission: Boolean) : Report
        data class State(
            val state: PlayState,
            val positionMs: Long,
            val durationMs: Long?,
        ) : Report
    }

    private data class QueuedReport(
        val mediaId: MediaId.Track,
        val artistId: String?,
        val report: Report,
    )

    private val reports = Channel<QueuedReport>(Channel.UNLIMITED)

    init {
        // The single consumer: applies the settings gate and talks to the
        // server one report at a time, preserving enqueue order on the wire.
        scope.launch {
            for ((mediaId, artistId, report) in reports) {
                // The settings read stays inside runCatching: a failed read
                // drops one report instead of killing the consumer for good.
                runCatching {
                    val allowed = settings.scrobbleSettings.first()
                        .allowsScrobble(mediaId, artistId)
                    if (!allowed) return@runCatching
                    when (report) {
                        is Report.Scrobble ->
                            repository.scrobble(mediaId.id, report.submission)
                        is Report.State -> repository.reportPlayState(
                            mediaId.id,
                            report.state,
                            report.positionMs,
                            report.durationMs,
                        )
                    }
                }
            }
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        endedSubmitted = false
        mediaItem ?: return
        scrobble(mediaItem, submission = false)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        tickerJob?.cancel()
        tickerJob = null
        val item = player.currentMediaItem ?: return
        if (isPlaying) {
            reportState(item, PlayState.PLAYING, player.currentPosition)
            startTicker()
        } else if (player.playbackState == Player.STATE_READY) {
            // Only a genuine pause is PAUSED: ENDED/IDLE already reported
            // STOPPED from onPlaybackStateChanged (state changes are
            // delivered first), and a BUFFERING dip — e.g. right after a
            // manual skip — resolves into PLAYING on its own.
            reportState(item, PlayState.PAUSED, player.currentPosition)
        }
    }

    private fun startTicker() {
        val interval = repository.playbackReportIntervalMs ?: return
        tickerJob = scope.launch {
            while (true) {
                delay(interval)
                // Re-read each tick: auto transitions don't restart the ticker.
                val item = player.currentMediaItem ?: continue
                reportState(item, PlayState.PLAYING, player.currentPosition)
            }
        }
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

        // Finalize the finished track's play-state before the submission —
        // Plex closes its timeline entry on "stopped".
        reportState(finished, PlayState.STOPPED, oldPosition.positionMs)
        submitIfPlayedEnough(
            item = finished,
            playedMs = oldPosition.positionMs,
        )
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        when (playbackState) {
            // The last track of a queue ends without a discontinuity.
            Player.STATE_ENDED -> {
                if (endedSubmitted) return
                val item = player.currentMediaItem ?: return
                reportState(item, PlayState.STOPPED, player.currentPosition)
                endedSubmitted = submitIfPlayedEnough(
                    item = item,
                    playedMs = player.currentPosition,
                )
            }
            // Explicit stop() or a playback error: close the timeline entry
            // so the track doesn't linger as paused/on-deck. Deliberately no
            // scrobble submission — matching the pre-Plex behavior, an
            // aborted session is not a finished play.
            Player.STATE_IDLE -> {
                val item = player.currentMediaItem ?: return
                reportState(item, PlayState.STOPPED, player.currentPosition)
            }
        }
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

    private fun scrobble(item: MediaItem, submission: Boolean) =
        enqueue(item, Report.Scrobble(submission))

    /**
     * Queues a play-state report, subject to the same [ScrobbleSettings] gate
     * as scrobbles — a suppressed play must not surface in the server's
     * timeline/on-deck either. No-op for providers with null
     * [MusicRepository.playbackReportIntervalMs].
     */
    private fun reportState(item: MediaItem, state: PlayState, positionMs: Long) {
        if (repository.playbackReportIntervalMs == null) return
        enqueue(item, Report.State(state, positionMs, item.mediaMetadata.durationMs))
    }

    private fun enqueue(item: MediaItem, report: Report) {
        val mediaId = MediaId.parse(item.mediaId) as? MediaId.Track ?: return
        val artistId = item.mediaMetadata.extras?.getString(MediaItemFactory.EXTRA_ARTIST_ID)
        reports.trySend(QueuedReport(mediaId, artistId, report))
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
