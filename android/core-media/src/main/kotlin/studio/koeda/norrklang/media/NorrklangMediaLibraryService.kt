package studio.koeda.norrklang.media

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession.ControllerInfo
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import studio.koeda.norrklang.data.diagnostics.Diagnostics
import studio.koeda.norrklang.data.radio.SavedRadioSongs
import studio.koeda.norrklang.data.repo.MusicRepository
import studio.koeda.norrklang.data.session.ProviderSession
import studio.koeda.norrklang.data.model.LibraryScope
import studio.koeda.norrklang.data.session.SessionManager
import studio.koeda.norrklang.data.settings.ServerSettingsRepository

/**
 * The single media entry point for Android Auto AND Android Automotive OS.
 * The car system binds here and renders everything: browse tree, playback
 * controls, artwork, errors.
 */
@AndroidEntryPoint
@OptIn(UnstableApi::class)
class NorrklangMediaLibraryService : MediaLibraryService() {

    @Inject internal lateinit var sessionManager: SessionManager
    @Inject internal lateinit var repository: MusicRepository
    @Inject internal lateinit var settings: ServerSettingsRepository
    @Inject internal lateinit var savedSongs: SavedRadioSongs
    @Inject internal lateinit var randomMix: RandomMixSession
    @Inject internal lateinit var similarMixes: SimilarMixesSession
    @Inject internal lateinit var bestOfMixes: BestOfMixesSession
    @Inject internal lateinit var catalogMixes: CatalogMixesSession

    private var mediaSession: MediaLibrarySession? = null
    @Volatile private var playbackAccount: ProviderSession? = null
    private var resumptionPersister: ResumptionPersister? = null
    private var networkMonitor: NetworkMonitor? = null
    // The handler is load-bearing: an uncaught throw here kills the process,
    // the car host rebinds into the same state, and the app "flash-loops"
    // until the host gives up ("Norrklang isn't working at the moment").
    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, e -> Diagnostics.record("media-service", e) },
    )

    override fun onCreate() {
        super.onCreate()

        // Each track's data source pins its quality across retries and seeks.
        // New tracks pick the latest network tier.
        val monitor = NetworkMonitor(this).also { networkMonitor = it }
        val resolver = StreamUrlResolver {
            playbackAccount?.takeIf { sessionManager.connectedOrNull()?.session === it }
        }
        serviceScope.launch {
            settings.streamQualityWifi.collect { resolver.wifiQuality = it }
        }
        serviceScope.launch {
            settings.streamQualityCellular.collect { resolver.cellularQuality = it }
        }
        serviceScope.launch {
            monitor.onCellular.collect { resolver.onCellular = it }
        }

        val player = buildPlayer(resolver)
        player.addListener(PlaybackErrorRecorder())
        player.addListener(RandomMixPlaySourceListener(randomMix))
        player.addListener(RadioNowPlayingListener(player))

        val browseTree = BrowseTree(
            this,
            repository,
            randomMix,
            similarMixes,
            bestOfMixes,
            catalogMixes,
            tileVersion = { LibraryScope.exclusionKey(settings.excludedLibraryIds.first()) },
            radioPlayStats = { settings.radioPlayStats.first() },
        )
        val resumptionLoader = ResumptionQueueLoader(
            settings,
            repository,
            randomMix,
            similarMixes,
            bestOfMixes,
            catalogMixes,
            buildStation = browseTree::playableStation,
        )
        val callback = LibrarySessionCallback(
            context = this,
            scope = serviceScope,
            sessionManager = sessionManager,
            repository = repository,
            savedSongs = savedSongs,
            browseTree = browseTree,
            resumption = resumptionLoader,
            voiceSearch = VoiceSearchResolver(repository, resumptionLoader::containerTracks),
            signInIntent = signInPendingIntent(),
        )

        // Keep the skip slots reserved even when prev/next are unavailable —
        // without this the car host moves a custom button into the vacant
        // skip position instead of showing a disabled skip button.
        val slotReservations = Bundle().apply {
            putBoolean(MediaConstants.EXTRAS_KEY_SLOT_RESERVATION_SEEK_TO_NEXT, true)
            putBoolean(MediaConstants.EXTRAS_KEY_SLOT_RESERVATION_SEEK_TO_PREV, true)
        }

        mediaSession = MediaLibrarySession.Builder(this, player, callback)
            .setSessionActivity(signInPendingIntent())
            .setMediaButtonPreferences(
                playbackButtons(this, shuffleOn = false, favorite = false),
            )
            // Per-item heart on album rows; which of the two shows is decided
            // per album via supported commands.
            .setCommandButtonsForMediaItems(albumFavoriteButtons(this))
            .setSessionExtras(slotReservations)
            // Fetches https artwork in-process and hands bitmaps to the
            // platform session — without this, covers stay blank on Android
            // Auto head units.
            .setBitmapLoader(CacheBitmapLoader(DataSourceBitmapLoader.Builder(this).build()))
            .build()
            .also { session ->
                // The Builder's extras never reach the platform session legacy
                // hosts observe (media3 only publishes on a *change*) —
                // re-setting at runtime forces the publish.
                session.setSessionExtras(slotReservations)
                // Needs the session, so it can't join the listeners added above.
                player.addListener(
                    PlaybackButtonsListener(this, serviceScope, repository, savedSongs, session),
                )
            }

        observeSessionState(player, resumptionLoader, monitor)
    }

    /**
     * Car connectivity is flaky, so buffer well ahead and be patient:
     * generous timeouts plus extra in-loader retries keep a hiccup in
     * BUFFERING instead of escalating to a fatal player error.
     */
    private fun buildPlayer(resolver: StreamUrlResolver): AuthGatePlayer {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(HTTP_CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(HTTP_READ_TIMEOUT_MS)
            .setAllowCrossProtocolRedirects(true)
        // Capped tiers are live server transcodes: chunked, no length, no
        // seek table — without this flag the extractor marks them unseekable
        // and the car host freezes the scrubber. All three providers are
        // pinned to CBR MP3 when capped, so byte-estimate seeking is sound;
        // the seek-bar duration comes from MediaMetadata.durationMs.
        val extractorsFactory = DefaultExtractorsFactory()
            .setConstantBitrateSeekingAlwaysEnabled(true)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                MIN_BUFFER_MS,
                MAX_BUFFER_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            )
            .build()

        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                // Declares the metadata duration so a chunked transcode is
                // on-demand, not LIVE: the seek bar stays, and a dropped
                // connection resumes instead of restarting the track.
                MetadataDurationMediaSourceFactory(
                    dataSourceFactory = DataSource.Factory {
                        ResolvingDataSource(
                            DefaultDataSource.Factory(this, httpDataSourceFactory).createDataSource(),
                            resolver.createResolver(),
                        )
                    },
                    extractorsFactory = extractorsFactory,
                    loadErrorHandlingPolicy = DefaultLoadErrorHandlingPolicy(LOAD_RETRY_COUNT),
                ),
            )
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        // Preload the next queue item's start so transitions stay gapless on
        // flaky car LTE and manual skip-next starts instantly.
        exoPlayer.preloadConfiguration =
            ExoPlayer.PreloadConfiguration(TARGET_PRELOAD_DURATION_US)
        return AuthGatePlayer(ShuffleFromCurrentPlayer(exoPlayer)) { error ->
            RadioErrors.present(this, exoPlayer.currentMediaItem, error)
        }
    }

    /**
     * Signed-out: persistent auth-error state (with the sign-in resolution
     * intent) — what makes the car render the message + "Sign in" button.
     * Signed-in: clear it and refresh the tree.
     */
    private fun observeSessionState(
        player: AuthGatePlayer,
        resumptionLoader: ResumptionQueueLoader,
        monitor: NetworkMonitor,
    ) {
        serviceScope.launch {
            val session = mediaSession ?: return@launch
            followPlaybackAccounts(
                player = player,
                states = sessionManager.state,
                signedOut = {
                    playbackAccount = null
                    player.setAuthError(
                        SessionErrors.authenticationExpiredException(
                            this@NorrklangMediaLibraryService, signInPendingIntent(),
                        ),
                    )
                    session.notifyChildrenChanged(MediaId.Root.encode(), 0, null)
                },
                connected = { account ->
                    playbackAccount = account
                    player.setAuthError(null)
                    session.notifyChildrenChanged(MediaId.Root.encode(), 3, null)
                    val reporter = PlaybackReporter(this, repository, settings, player, account)
                    val persister = try {
                        settings.accountRevision()?.let { revision ->
                            ResumptionPersister(this, settings, player, revision)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Diagnostics.record("resumption-setup", e)
                        null
                    }
                    val radio = QueueRadioListener(
                        scope = this,
                        autoplayEnabled = { settings.autoplaySimilar.first() },
                        radio = QueueRadio(repository),
                        player = player,
                    )
                    val stationCounter = RadioPlayCounter(
                        scope = this,
                        player = player,
                        record = { settings.recordRadioPlay(it) },
                        onRecorded = {
                            session.notifyChildrenChanged(MediaId.TabHome.encode(), Int.MAX_VALUE, null)
                        },
                    )
                    var homeNotify: Job? = null
                    // The raw setting, not the resolved scope: no network, and it
                    // changes exactly when the user changes the selection.
                    suspend fun mixFingerprint(): String =
                        account.cacheFingerprint + "/" +
                            LibraryScope.exclusionKey(settings.excludedLibraryIds.first())
                    suspend fun refreshIntoHome(mixes: HomeMixesSession<*, *>) {
                        if (mixes.refresh(mixFingerprint())) {
                            homeNotify?.cancel()
                            homeNotify = launch {
                                delay(HOME_NOTIFY_COALESCE_MS)
                                session.notifyChildrenChanged(MediaId.TabHome.encode(), Int.MAX_VALUE, null)
                            }
                        }
                    }
                    val initialization = PlaybackInitialization(
                        scope = this,
                        player = player,
                        loadQueue = resumptionLoader::load,
                        refreshHome = {
                            coroutineScope {
                                launch {
                                    refreshIntoHome(bestOfMixes)
                                    refreshIntoHome(similarMixes)
                                }
                                launch { refreshIntoHome(catalogMixes) }
                            }
                        },
                    )
                    val recovery = PlaybackRecoveryListener(
                        this@NorrklangMediaLibraryService, this, player,
                    )
                    val listeners = listOfNotNull(
                        reporter, recovery, persister, radio, stationCounter, initialization,
                    )
                    listeners.forEach(player::addListener)
                    resumptionPersister = persister
                    // A library selection change re-scopes browse + home under
                    // the new fingerprint; the player's queue is untouched.
                    // distinctUntilChanged: DataStore re-emits on every write.
                    launch {
                        settings.excludedLibraryIds.distinctUntilChanged().drop(1).collect {
                            randomMix.clear()
                            notifyLibraryScopeChanged(session)
                            initialization.retry()
                        }
                    }
                    try {
                        initialization.retry()
                        monitor.isConnected.collect { connected ->
                            if (connected) initialization.retry()
                        }
                    } finally {
                        listeners.forEach(player::removeListener)
                        recovery.release()
                        resumptionPersister = null
                    }
                },
            )
        }
    }

    /** Every scoped browse node, like the favourite-toggle refresh in LibrarySessionCallback. */
    private fun notifyLibraryScopeChanged(session: MediaLibrarySession) {
        session.notifyChildrenChanged(MediaId.Root.encode(), 3, null)
        listOf(
            MediaId.TabHome,
            MediaId.TabLibrary,
            MediaId.TabArtists,
            MediaId.TabAlbums,
            MediaId.HomeRecentlyAdded,
            MediaId.HomeFavoriteAlbums,
            MediaId.HomeFavoriteArtists,
            MediaId.HomeRecentlyPlayed,
            MediaId.HomeMostPlayed,
            MediaId.HomeFavoriteSongs,
            MediaId.HomeRecentlyAddedSongs,
            MediaId.HomeRandomMix,
        ).forEach { session.notifyChildrenChanged(it.encode(), Int.MAX_VALUE, null) }
    }

    override fun onGetSession(controllerInfo: ControllerInfo): MediaLibrarySession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        resumptionPersister?.saveNow()
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        // Capture the final position before teardown — the save itself is
        // NonCancellable, so cancelling serviceScope below is safe.
        resumptionPersister?.saveNow()
        resumptionPersister = null
        networkMonitor?.close()
        networkMonitor = null
        serviceScope.cancel()
        playbackAccount = null
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private fun signInPendingIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            /* requestCode = */ 0,
            Intent(ACTION_SIGN_IN).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    companion object {
        private const val HOME_NOTIFY_COALESCE_MS = 500L

        // 3 minutes ahead (audio is cheap) so short dead zones never reach
        // the user. Min == max keeps the socket trickling: an idle refill
        // gap gets closed by proxies (nginx send_timeout 60s) and NAT (#9).
        private const val MIN_BUFFER_MS = 180_000
        private const val MAX_BUFFER_MS = 180_000

        // 30s of the upcoming track buffered before it's needed.
        private const val TARGET_PRELOAD_DURATION_US = 30_000_000L

        // Defaults (8s/8s/3 retries) give up after a few seconds of bad LTE.
        private const val HTTP_CONNECT_TIMEOUT_MS = 15_000
        private const val HTTP_READ_TIMEOUT_MS = 20_000
        private const val LOAD_RETRY_COUNT = 8

        /** Both app modules register their sign-in activity for this action. */
        const val ACTION_SIGN_IN = "studio.koeda.norrklang.action.SIGN_IN"
    }
}
