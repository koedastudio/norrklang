package studio.koeda.norrklang.media

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession.ControllerInfo
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import studio.koeda.norrklang.data.diagnostics.Diagnostics
import studio.koeda.norrklang.data.repo.MusicRepository
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
    @Inject internal lateinit var randomMix: RandomMixSession
    @Inject internal lateinit var similarMixes: SimilarMixesSession
    @Inject internal lateinit var bestOfMixes: BestOfMixesSession
    @Inject internal lateinit var catalogMixes: CatalogMixesSession

    private var mediaSession: MediaLibrarySession? = null
    private var resumptionPersister: ResumptionPersister? = null
    private var playbackRecovery: PlaybackRecoveryListener? = null
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

        // Stream URLs are resolved per load from canonical refs (StreamRef),
        // picking the quality tier for the network the device is on at that
        // moment — a Wi-Fi→LTE handoff mid-drive changes the next load, not
        // nothing.
        val monitor = NetworkMonitor(this).also { networkMonitor = it }
        val resolver = StreamUrlResolver { sessionManager.connectedOrNull()?.session }
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
        player.addListener(PlaybackReporter(serviceScope, repository, settings, player))
        player.addListener(PlaybackErrorRecorder())
        player.addListener(RandomMixPlaySourceListener(randomMix))
        playbackRecovery = PlaybackRecoveryListener(this, serviceScope, player)
            .also(player::addListener)
        resumptionPersister = ResumptionPersister(serviceScope, settings, player)
            .also(player::addListener)
        player.addListener(
            QueueRadioListener(
                scope = serviceScope,
                autoplayEnabled = { settings.autoplaySimilar.first() },
                radio = QueueRadio(repository),
                player = player,
            ),
        )

        val browseTree =
            BrowseTree(this, repository, randomMix, similarMixes, bestOfMixes, catalogMixes)
        val resumptionLoader = ResumptionQueueLoader(
            settings,
            repository,
            randomMix,
            similarMixes,
            bestOfMixes,
            catalogMixes,
        )
        val callback = LibrarySessionCallback(
            context = this,
            scope = serviceScope,
            sessionManager = sessionManager,
            repository = repository,
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
                    PlaybackButtonsListener(this, serviceScope, repository, session),
                )
            }

        observeSessionState(player, resumptionLoader)
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
                DefaultMediaSourceFactory(
                    ResolvingDataSource.Factory(
                        DefaultDataSource.Factory(this, httpDataSourceFactory),
                        resolver,
                    ),
                )
                    .setLoadErrorHandlingPolicy(
                        DefaultLoadErrorHandlingPolicy(LOAD_RETRY_COUNT),
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
        return AuthGatePlayer(ShuffleFromCurrentPlayer(exoPlayer))
    }

    /**
     * Signed-out: persistent auth-error state (with the sign-in resolution
     * intent) — what makes the car render the message + "Sign in" button.
     * Signed-in: clear it and refresh the tree.
     */
    private fun observeSessionState(
        player: AuthGatePlayer,
        resumptionLoader: ResumptionQueueLoader,
    ) {
        var queueRestored = false
        // Debounce the post-connect burst of mix-section notifies — one
        // notify per section made some hosts visibly re-render repeatedly.
        var homeNotify: Job? = null
        fun scheduleHomeNotify(session: MediaLibrarySession) {
            homeNotify?.cancel()
            homeNotify = serviceScope.launch {
                delay(HOME_NOTIFY_COALESCE_MS)
                session.notifyChildrenChanged(MediaId.TabHome.encode(), Int.MAX_VALUE, null)
            }
        }
        serviceScope.launch {
            sessionManager.state.collect { state ->
                Log.d(TAG, "session state: ${state::class.simpleName}")
                val session = mediaSession ?: return@collect
                when (state) {
                    is SessionManager.SessionState.Connected -> {
                        player.setAuthError(null)
                        session.notifyChildrenChanged(MediaId.Root.encode(), 3, null)
                        // Generate the mix sections off the browse path (up
                        // to dozens of requests each), each popping into the
                        // home tab when ready. Separate launches so a slow
                        // generation delays neither the other sections nor
                        // the queue restore below — except Best of ahead of
                        // Similar to in one launch: Similar's generation
                        // excludes Best of's seed artists (see MediaModule),
                        // so that snapshot must settle first. The shared
                        // top-songs cache makes the wait cheap.
                        suspend fun refreshIntoHome(mixes: HomeMixesSession<*, *>) {
                            if (mixes.refresh(state.session.cacheFingerprint)) {
                                scheduleHomeNotify(session)
                            }
                        }
                        serviceScope.launch {
                            refreshIntoHome(bestOfMixes)
                            refreshIntoHome(similarMixes)
                        }
                        serviceScope.launch { refreshIntoHome(catalogMixes) }
                        // The car's control bar is always on screen; pre-load
                        // the last queue (paused) so it shows the track play
                        // would resume instead of an empty box.
                        if (!queueRestored && player.mediaItemCount == 0) {
                            queueRestored = true
                            resumptionLoader.load()?.let { resumed ->
                                player.setMediaItems(
                                    resumed.mediaItems,
                                    resumed.startIndex,
                                    resumed.startPositionMs,
                                )
                                player.prepare()
                            }
                        }
                    }
                    is SessionManager.SessionState.SignedOut -> {
                        Log.d(TAG, "applying auth player error")
                        player.setAuthError(
                            SessionErrors.authenticationExpiredException(
                                this@NorrklangMediaLibraryService,
                                signInPendingIntent(),
                            ),
                        )
                        session.notifyChildrenChanged(MediaId.Root.encode(), 0, null)
                    }
                    else -> Unit
                }
            }
        }
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
        playbackRecovery?.release()
        playbackRecovery = null
        networkMonitor?.close()
        networkMonitor = null
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        serviceScope.cancel()
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
        private const val TAG = "NorrklangMedia"

        // Buffer up to 3 minutes ahead (audio is cheap) so short dead zones
        // never reach the user; keep at least 1 minute before pausing loads.
        private const val HOME_NOTIFY_COALESCE_MS = 500L

        private const val MIN_BUFFER_MS = 60_000
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
