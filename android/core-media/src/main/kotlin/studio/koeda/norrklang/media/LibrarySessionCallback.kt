package studio.koeda.norrklang.media

import android.app.PendingIntent
import android.content.Context
import android.os.Bundle
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ControllerInfo
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import studio.koeda.norrklang.data.diagnostics.Diagnostics
import studio.koeda.norrklang.data.model.Track
import studio.koeda.norrklang.data.radio.SavedRadioSongs
import studio.koeda.norrklang.data.repo.MusicRepository
import studio.koeda.norrklang.data.session.SessionManager
import studio.koeda.norrklang.data.repo.MusicException
import studio.koeda.norrklang.data.runSuspendCatching

/**
 * All browse/playback resolution for the car UI.
 *
 * Auth errors carry the "error resolution" extras that make AAOS render a
 * tappable "Sign in" affordance launching [signInIntent] (parked only).
 */
@OptIn(UnstableApi::class)
internal class LibrarySessionCallback(
    private val context: Context,
    private val scope: CoroutineScope,
    private val sessionManager: SessionManager,
    private val repository: MusicRepository,
    private val savedSongs: SavedRadioSongs,
    private val browseTree: BrowseTree,
    private val resumption: ResumptionQueueLoader,
    private val containers: ContainerTracks,
    private val stationQueue: StationQueue,
    private val voiceSearch: VoiceSearchResolver,
    private val signInIntent: PendingIntent,
    private val offlineGuard: OfflineGuard = OfflineGuard(isOnline = { true }),
    private val buildTrack: (Track, MediaId.Container?) -> MediaItem = MediaItemFactory::playableTrack,
) : MediaLibrarySession.Callback {

    // Deliberately accepts every controller: the car media hosts ARE
    // third-party controllers, and the custom commands exist precisely so
    // they can render our buttons. Gating on package name would break the car.
    override fun onConnect(
        session: MediaSession,
        controller: ControllerInfo,
    ): MediaSession.ConnectionResult =
        MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
            .setAvailableSessionCommands(
                MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                    .buildUpon()
                    .add(SessionCommand(ACTION_TOGGLE_SHUFFLE, Bundle.EMPTY))
                    .add(SessionCommand(ACTION_TOGGLE_FAVORITE, Bundle.EMPTY))
                    .add(SessionCommand(ACTION_FAVORITE_ALBUM_ADD, Bundle.EMPTY))
                    .add(SessionCommand(ACTION_FAVORITE_ALBUM_REMOVE, Bundle.EMPTY))
                    .build(),
            )
            .build()

    override fun onCustomCommand(
        session: MediaSession,
        controller: ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> = when (customCommand.customAction) {
        ACTION_TOGGLE_SHUFFLE -> toggleShuffle(session)
        ACTION_TOGGLE_FAVORITE -> toggleTrackFavorite(session)
        ACTION_FAVORITE_ALBUM_ADD, ACTION_FAVORITE_ALBUM_REMOVE ->
            setAlbumFavorite(
                session,
                args,
                favorite = customCommand.customAction == ACTION_FAVORITE_ALBUM_ADD,
            )
        else -> super.onCustomCommand(session, controller, customCommand, args)
    }

    /**
     * Playback-row shuffle toggle. Enabling reshuffles from the current track
     * ([ShuffleFromCurrentPlayer]); the button refresh rides on
     * onShuffleModeEnabledChanged ([PlaybackButtonsListener]).
     */
    private fun toggleShuffle(session: MediaSession): ListenableFuture<SessionResult> {
        session.player.shuffleModeEnabled = !session.player.shuffleModeEnabled
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    /**
     * Playback-row heart: toggles the *currently playing* track's favorite —
     * or, on a radio station, saves the song playing ([toggleRadioSong]).
     */
    private fun toggleTrackFavorite(session: MediaSession): ListenableFuture<SessionResult> {
        val current = session.player.currentMediaItem
        val currentMediaId = current?.mediaId
        if (currentMediaId?.let(MediaId::parse) is MediaId.RadioStation) {
            return toggleRadioSong(session, current)
        }
        return commandFuture("toggle-favorite") {
            val trackId = (currentMediaId?.let(MediaId::parse) as? MediaId.Track)?.id
                ?: return@commandFuture SessionResult(SessionError.ERROR_INVALID_STATE)
            // Toggle off the same cached state the button rendering used,
            // so display and action can't disagree.
            val favorite = !repository.isFavoriteTrack(trackId)
            repository.setTrackFavorite(trackId, favorite)
            // Only flip the button if the toggled track is still current —
            // the user may have skipped meanwhile.
            if (session.player.currentMediaItem?.mediaId == currentMediaId) {
                session.setMediaButtonPreferences(
                    playbackButtons(
                        context,
                        shuffleOn = session.player.shuffleModeEnabled,
                        heart = Heart.Track(favorite),
                    ),
                )
            }
            // Have the car re-query the home tab's "Favourite songs" list.
            (session as? MediaLibrarySession)?.notifyChanged(MediaId.HomeFavoriteSongs)
            SessionResult(SessionResult.RESULT_SUCCESS)
        }
    }

    /**
     * The heart on a station notes the song the stream reports (see
     * [SavedRadioSongs]); nothing to save while the stream hasn't named one.
     */
    private fun toggleRadioSong(session: MediaSession, item: MediaItem): ListenableFuture<SessionResult> {
        val song = radioSong(item) ?: return Futures.immediateFuture(
            SessionResult(SessionError.ERROR_INVALID_STATE),
        )
        return commandFuture("save-radio-song") {
            val saved = savedSongs.toggle(song.station, song.title)
            // Only flip the button if that song is still what's playing.
            if (session.player.currentMediaItem?.let(::radioSong) == song) {
                session.setMediaButtonPreferences(
                    playbackButtons(
                        context,
                        shuffleOn = session.player.shuffleModeEnabled,
                        heart = Heart.RadioSong(saved),
                    ),
                )
            }
            SessionResult(SessionResult.RESULT_SUCCESS)
        }
    }

    /**
     * Browse-view heart on album items (custom browse action). The tapped
     * button encodes the direction ([MediaItemFactory.forAlbum] offers
     * exactly one of the two), and the target album arrives in [args],
     * independent of what is playing.
     */
    private fun setAlbumFavorite(
        session: MediaSession,
        args: Bundle,
        favorite: Boolean,
    ): ListenableFuture<SessionResult> = commandFuture("favorite-album") {
        val encodedMediaId = args.getString(MediaConstants.EXTRA_KEY_MEDIA_ID)
        val albumId = (encodedMediaId?.let(MediaId::parse) as? MediaId.Album)?.id
            ?: return@commandFuture SessionResult(SessionError.ERROR_BAD_VALUE)
        // Resolve the album's artist before the toggle wipes the repository
        // cache — the artist's album list renders the heart too.
        val artistId = runSuspendCatching { repository.album(albumId).album.artistId }.getOrNull()
        repository.setAlbumFavorite(albumId, favorite)
        // Re-query every list that shows album hearts (and, for the
        // favorites list, membership).
        val library = session as? MediaLibrarySession
        library?.notifyChanged(MediaId.HomeFavoriteAlbums)
        library?.notifyChanged(MediaId.HomeRecentlyAdded)
        library?.notifyChanged(MediaId.TabAlbums)
        artistId?.let { library?.notifyChanged(MediaId.Artist(it)) }
        // Have the host re-fetch the tapped item right away, flipping the
        // heart in place — without this it only updates on re-entry.
        val refreshTappedItem = Bundle().apply {
            putString(EXTRAS_KEY_BROWSE_ACTION_RESULT_REFRESH_ITEM, encodedMediaId)
        }
        SessionResult(SessionResult.RESULT_SUCCESS, refreshTappedItem)
    }

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> =
        // Never error here: for legacy browsers (the car hosts) a root error
        // becomes a *rejected connection* — blank screen. Signed-out is
        // signaled via a player-level auth error (AuthGatePlayer) plus an
        // empty tree.
        Futures.immediateFuture(LibraryResult.ofItem(browseTree.rootItem, rootParams()))

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = resolved { sessionState ->
        if (MediaId.parse(parentId) == MediaId.Root &&
            sessionState is SessionManager.SessionState.SignedOut
        ) {
            // An auth error, not an empty success: an empty root renders as a
            // dead "Media isn't available" pane once the sign-in dialog is
            // dismissed. The error keeps the host in its error view, which
            // offers the sign-in resolution from the player-level auth error
            // (AuthGatePlayer).
            return@resolved authenticationExpiredResult()
        }
        library {
            offMain { browseTree.children(parentId, page, pageSize) }
                ?.let { LibraryResult.ofItemList(ImmutableList.copyOf(it), params) }
                ?: unknownId(parentId)
        }
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> = resolved {
        library {
            offMain { browseTree.item(mediaId) }?.let { LibraryResult.ofItem(it, null) } ?: unknownId(mediaId)
        }
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: ControllerInfo,
        mediaItems: List<MediaItem>,
    ): ListenableFuture<List<MediaItem>> = resolved {
        val failsEmpty = emptyMayBeFailure(mediaItems)
        connected(mediaSession, failedSilently = { failsEmpty && it.isEmpty() }) {
            mediaItems.flatMap { resolve(it) }
        }
    }

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: ControllerInfo,
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaItemsWithStartPosition> = resolved {
        val failsEmpty = emptyMayBeFailure(mediaItems)
        connected(mediaSession, failedSilently = { failsEmpty && it.mediaItems.isEmpty() }) {
            queueFor(mediaItems, startIndex, startPositionMs)
        }.also {
            // A failed future leaves the current queue playing; an empty success would replace it with silence.
            if (it.mediaItems.isEmpty()) throw IllegalArgumentException("Nothing to play")
        }
    }

    private suspend fun queueFor(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): MediaItemsWithStartPosition {
        // Assistant voice requests (legacy playFromSearch) arrive as one item
        // with no media id, carrying only requestMetadata.
        mediaItems.singleOrNull()?.takeIf(::isVoiceRequest)?.let { requested ->
            return when (val resolved = resolveVoiceRequest(requested)) {
                is VoiceSearchResolver.Station -> stationQueue.startingAt(resolved.id)
                is VoiceSearchResolver.Queue -> MediaItemsWithStartPosition(
                    resolved.tracks.map { buildTrack(it, resolved.container) },
                    /* startIndex = */ 0,
                    startPositionMs,
                )
            }
        }
        // Tapping one track in an album/playlist sends a single item carrying a
        // container context — rebuild the sibling queue around it. A tapped
        // station likewise plays amid the other stations.
        val single = mediaItems.singleOrNull()?.let { MediaId.parse(it.mediaId) }
        return if (single is MediaId.Track && single.container != null) {
            val queue = containers.tracks(single.container)
            val index = queue.indexOfFirst { it.id == single.id }.coerceAtLeast(0)
            MediaItemsWithStartPosition(
                queue.map { buildTrack(it, single.container) },
                index,
                startPositionMs,
            )
        } else if (single is MediaId.RadioStation) {
            stationQueue.startingAt(single.id)
        } else {
            val resolved = mediaItems.flatMap { resolve(it) }
            MediaItemsWithStartPosition(
                resolved,
                startIndex.coerceIn(0, (resolved.size - 1).coerceAtLeast(0)),
                startPositionMs,
            )
        }
    }

    override fun onSearch(
        session: MediaLibrarySession,
        browser: ControllerInfo,
        query: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> = resolved {
        library {
            val results = offMain { searchItems(query) }
            session.notifySearchResultChanged(browser, query, results.size, params)
            LibraryResult.ofVoid(params)
        }
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = resolved {
        library {
            val pageItems = offMain { Paging.slice(searchItems(query), page, pageSize) }
            LibraryResult.ofItemList(ImmutableList.copyOf(pageItems), params)
        }
    }

    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: ControllerInfo,
        isForPlayback: Boolean,
    ): ListenableFuture<MediaItemsWithStartPosition> = resolved {
        resumption.load() ?: throw IllegalStateException("Nothing to resume")
    }

    // --- helpers ---

    /** Whether the request is for a mix whose empty answer can hide a failed load. */
    private fun emptyMayBeFailure(requested: List<MediaItem>): Boolean = requested.any { item ->
        val id = MediaId.parse(item.mediaId)
        val container = (id as? MediaId.Track)?.container ?: id as? MediaId.Container
        container != null && containers.emptyMayBeFailure(container)
    }

    /**
     * Runs a play request through [offlineGuard]. Without a connection the
     * driver gets a message and the request fails, which leaves the queue
     * that is playing alone.
     */
    private suspend fun <T : Any> connected(
        session: MediaSession,
        failedSilently: (T) -> Boolean,
        block: suspend () -> T,
    ): T = try {
        offlineGuard.run(failedSilently, block)
    } catch (e: OfflineGuard.NoConnection) {
        session.sendError(
            SessionError(SessionError.ERROR_IO, context.getString(R.string.error_no_connection)),
        )
        throw e
    }

    /** Resolves any requested item into playable track items (containers expand). */
    private suspend fun resolve(item: MediaItem): List<MediaItem> =
        when (val id = MediaId.parse(item.mediaId)) {
            is MediaId.Track ->
                listOf(buildTrack(repository.track(id.id), id.container))
            is MediaId.Container -> containers.tracks(id).map { buildTrack(it, id) }
            is MediaId.RadioStation ->
                listOf(browseTree.playableStation(repository.radioStation(id.id)))
            else -> emptyList()
        }

    /**
     * A voice/Assistant media request: no media id and no URI, only
     * requestMetadata (whose query is empty for "play some music").
     * A mediaUri request (playFromUri) is not ours to interpret.
     */
    private fun isVoiceRequest(item: MediaItem): Boolean =
        item.mediaId.isEmpty() &&
            item.localConfiguration == null &&
            item.requestMetadata.mediaUri == null

    /**
     * Throws when nothing matched (or the lookup failed): a failed future
     * leaves the current queue playing, where an empty success would replace
     * it with silence on a misheard query.
     */
    private suspend fun resolveVoiceRequest(item: MediaItem): VoiceSearchResolver.Resolved {
        val resolved = runSuspendCatching { voiceSearch.resolve(voiceRequest(item)) }
            .onFailure { Diagnostics.record("voice-search", it) }
            .getOrThrow()
        return resolved ?: throw IllegalArgumentException("No match for voice query")
    }

    private fun voiceRequest(item: MediaItem): VoiceSearchResolver.Request {
        val extras = item.requestMetadata.extras
        val focus = when (extras?.getString(MediaStore.EXTRA_MEDIA_FOCUS)) {
            MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE -> VoiceSearchResolver.Focus.Artist
            MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE -> VoiceSearchResolver.Focus.Album
            MediaStore.Audio.Media.ENTRY_CONTENT_TYPE -> VoiceSearchResolver.Focus.Title
            FOCUS_PLAYLIST -> VoiceSearchResolver.Focus.Playlist
            FOCUS_GENRE -> VoiceSearchResolver.Focus.Genre
            MediaStore.Audio.Radio.ENTRY_CONTENT_TYPE -> VoiceSearchResolver.Focus.Radio
            else -> VoiceSearchResolver.Focus.Unstructured
        }
        return VoiceSearchResolver.Request(
            query = item.requestMetadata.searchQuery.orEmpty(),
            focus = focus,
            artist = extras?.getString(MediaStore.EXTRA_MEDIA_ARTIST),
            album = extras?.getString(MediaStore.EXTRA_MEDIA_ALBUM),
            title = extras?.getString(MediaStore.EXTRA_MEDIA_TITLE),
            playlist = extras?.getString(MediaStore.EXTRA_MEDIA_PLAYLIST),
            genre = extras?.getString(MediaStore.EXTRA_MEDIA_GENRE),
            radioChannel = extras?.getString(MediaStore.EXTRA_MEDIA_RADIO_CHANNEL),
        )
    }

    private suspend fun searchItems(query: String): List<MediaItem> {
        val results = repository.search(query)
        // Broad-to-specific sections; consecutive items sharing a group title
        // render under one header. Capped per section so "Tracks" is reachable
        // without scrolling past a wall of artists. Voice hosts that auto-play
        // from search pick the first *playable* item, so the browsable
        // artist/album sections ahead of the tracks don't confuse them.
        return results.artists.take(SEARCH_SECTION_LIMIT).map {
            MediaItemFactory.forArtist(it, context.getString(R.string.search_section_artists))
        } + results.albums.take(SEARCH_SECTION_LIMIT).map {
            MediaItemFactory.forAlbum(it, context.getString(R.string.search_section_albums))
        } + results.tracks.take(SEARCH_SECTION_TRACK_LIMIT).map {
            MediaItemFactory.forTrack(it, groupTitle = context.getString(R.string.search_section_tracks))
        }
    }

    private fun rootParams(): LibraryParams {
        val extras = Bundle().apply {
            // Legacy key some head units still check before showing search UI.
            putBoolean("android.media.browse.SEARCH_SUPPORTED", true)
            putInt(
                MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
                MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_LIST_ITEM,
            )
        }
        return LibraryParams.Builder().setExtras(extras).build()
    }

    /** A future that first waits out session restore ([awaitResolvedSession]); [block] gets the resolved state. */
    private fun <T> resolved(block: suspend (SessionManager.SessionState) -> T): ListenableFuture<T> =
        scope.future { block(awaitResolvedSession()) }

    /**
     * Maps any failure to a graceful error result. Broader than
     * MusicException on purpose: the host retries a graceful error result,
     * but a failed future on a car host can read as a dead service. Anything
     * unexpected is still recorded.
     */
    private suspend fun <T : Any> library(block: suspend () -> LibraryResult<T>): LibraryResult<T> =
        runSuspendCatching { block() }.getOrElse(::errorResult)

    /** A custom-command future; any failure is recorded under [where] and answered with ERROR_IO. */
    private fun commandFuture(where: String, block: suspend () -> SessionResult): ListenableFuture<SessionResult> =
        scope.future {
            runSuspendCatching { block() }.getOrElse { e ->
                Diagnostics.record(where, e)
                SessionResult(SessionError.ERROR_IO)
            }
        }

    /**
     * List building (bucket partitions of thousands of artists, hundreds of
     * MediaItems with Bundles) has no main-thread affinity; keeping it off
     * [scope]'s application thread leaves that thread to the player and the
     * session's own IPC.
     */
    private suspend fun <T> offMain(block: suspend () -> T): T = withContext(Dispatchers.Default) { block() }

    private fun <T : Any> unknownId(id: String): LibraryResult<T> =
        LibraryResult.ofError(SessionError(SessionError.ERROR_BAD_VALUE, "Unknown id $id"))

    private fun <T : Any> errorResult(e: Throwable): LibraryResult<T> =
        when (e) {
            is MusicException.AuthFailed -> authenticationExpiredResult()
            else -> {
                // Class + message only — a stream/cover URL would carry the
                // auth token.
                Diagnostics.record("browse", e)
                LibraryResult.ofError<T>(
                    SessionError(
                        SessionError.ERROR_IO,
                        context.getString(R.string.error_loading_library),
                    ),
                )
            }
        }

    /**
     * Waits out the Initializing window at process start (credentials
     * restoring from DataStore) — a car that binds immediately would
     * otherwise flash a spurious "sign in required" error.
     *
     * Bounded: if the restore wedges (Keystore IPC can stall on some
     * vendors' keymint), degrade to SignedOut so the car shows a sign-in
     * error instead of spinning on browse futures that never complete. A
     * restore that completes later notifies the root and the host recovers.
     */
    private suspend fun awaitResolvedSession(): SessionManager.SessionState =
        withTimeoutOrNull(SESSION_RESOLVE_TIMEOUT_MS) {
            sessionManager.state.first { it !is SessionManager.SessionState.Initializing }
        } ?: SessionManager.SessionState.SignedOut.also {
            Diagnostics.record("browse", "session state unresolved after ${SESSION_RESOLVE_TIMEOUT_MS}ms")
        }

    private fun <T : Any> authenticationExpiredResult(): LibraryResult<T> =
        LibraryResult.ofError<T>(
            SessionError(
                SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED,
                context.getString(R.string.error_sign_in_required),
            ),
        )

    companion object {
        /**
         * Generous next to a normal restore (a DataStore read + one Keystore
         * decrypt, well under a second) — only a genuinely stuck restore hits
         * it, and cold-boot Keystore contention deserves some slack first.
         */
        private const val SESSION_RESOLVE_TIMEOUT_MS = 15_000L

        // Focus values whose MediaStore.Audio owners (Playlists, Genres) are
        // deprecated; assistants still send them.
        private const val FOCUS_PLAYLIST = "vnd.android.cursor.item/playlist"
        private const val FOCUS_GENRE = "vnd.android.cursor.item/genre"

        // Search section caps: tracks get more room since a spoken "play X"
        // resolves against them; the repository caches more per query, so
        // raising these needs no server round-trip change.
        private const val SEARCH_SECTION_LIMIT = 6
        private const val SEARCH_SECTION_TRACK_LIMIT = 8

        /**
         * Legacy result key the car hosts read: the media id under it is
         * re-fetched (onGetItem) so its heart updates in place. Media3 passes
         * SessionResult.extras through but doesn't re-export the constant —
         * value matches androidx.media.utils.MediaConstants
         * .EXTRAS_KEY_CUSTOM_BROWSER_ACTION_RESULT_REFRESH_ITEM (media:1.7.0).
         */
        private const val EXTRAS_KEY_BROWSE_ACTION_RESULT_REFRESH_ITEM =
            "androidx.media.utils.extras.KEY_CUSTOM_BROWSER_ACTION_RESULT_REFRESH_ITEM"
    }
}

/** Has the host re-query [id]'s children; [itemCount] is only a hint, by default unknown. */
internal fun MediaLibrarySession.notifyChanged(id: MediaId, itemCount: Int = Int.MAX_VALUE) =
    notifyChildrenChanged(id.encode(), itemCount, null)
